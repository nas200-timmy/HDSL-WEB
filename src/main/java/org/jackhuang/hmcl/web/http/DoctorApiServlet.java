/*
 * HDSL-web
 * Copyright (C) 2026  HDSL-web contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.web.http;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jackhuang.hmcl.dsh.DshDoctor;
import org.jackhuang.hmcl.web.config.ServerConfig;
import org.jackhuang.hmcl.web.server.SshBridge;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/// The diagnostics endpoint, mapped at `/api/doctor`:
///
/// - `GET /api/doctor` — `{checks: [{id, name, status, detail?}], generatedAt}`
///
/// [DshDoctor] writes its report as plain text for a terminal; the panel
/// needs structure. Rather than reimplementing the checks (and drifting from
/// them), this runs the real report and parses it: every section heading the
/// report prints becomes one check, its body the check's `detail`, and the
/// report's own words decide the status — `FAILED` / `NOT FOUND` /
/// `UNSUPPORTED` are failures, `INCOMPLETE` / `SHORTAGE` / `skipped` /
/// `unavailable` are warnings. The report's exit code (its own verdict) becomes
/// the `overall` check. Two checks are not the domain's to print and are
/// appended after the parsed ones: the SSH channel's platform row and its
/// state row (see docs/ssh-terminal-channel.md).
@NotNullByDefault
public final class DoctorApiServlet extends HttpServlet {

    private final ServerConfig config;
    private final SshBridge ssh;

    public DoctorApiServlet(ServerConfig config, SshBridge ssh) {
        this.config = config;
        this.ssh = ssh;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String text;
        int exitCode;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
            exitCode = DshDoctor.report(out);
        }
        text = buffer.toString(StandardCharsets.UTF_8);

        JsonArray checks = new JsonArray();
        for (Section section : sections(text)) {
            JsonObject check = new JsonObject();
            check.addProperty("id", slug(section.title()));
            check.addProperty("name", section.title());
            check.addProperty("status", statusOf(section.body()));
            if (!section.body().isBlank()) {
                check.addProperty("detail", section.body());
            }
            checks.add(check);
        }

        JsonObject overall = new JsonObject();
        overall.addProperty("id", "overall");
        overall.addProperty("name", "Overall");
        overall.addProperty("status", exitCode == 0 ? "ok" : "fail");
        String verdict = verdict(text);
        if (verdict != null) {
            overall.addProperty("detail", verdict);
        }
        checks.add(overall);

        checks.add(sshPlatformCheck());
        checks.add(sshStateCheck());

        JsonObject body = new JsonObject();
        body.add("checks", checks);
        body.addProperty("generatedAt", Instant.now().toString());
        Json.write(response, body);
    }

    /// The platform row of the SSH channel: Linux passes, macOS passes with
    /// the "unverified" label the threat model requires, everything else
    /// fails — the same verdict the service itself gates on. A build without
    /// the channel (default build) gets its own honest row.
    private JsonObject sshPlatformCheck() {
        JsonObject check = new JsonObject();
        check.addProperty("id", "ssh-platform");
        check.addProperty("name", "SSH platform");
        String verdict = ssh.platformVerdict();
        if (verdict == null) {
            check.addProperty("status", "ok");
            check.addProperty("detail", "此构建不含 SSH 终端通道（默认构建，需要时用 -PwithSsh）");
        } else if (verdict.equals("supported")) {
            check.addProperty("status", "ok");
            check.addProperty("detail", "Linux：SSH 终端通道完整支持");
        } else if (verdict.equals("supported_unverified")) {
            check.addProperty("status", "warn");
            check.addProperty("detail", "macOS：SSH 终端通道放行，但尚未真机实测");
        } else {
            check.addProperty("status", "fail");
            check.addProperty("detail", "此平台不支持 SSH 终端通道");
        }
        return check;
    }

    /// The channel state row: off is a green check ("已关闭 ✓" is the
    /// healthy default), on-and-listening is green with the address, on-but-
    /// not-listening is a warning that names the two usual causes.
    private JsonObject sshStateCheck() {
        JsonObject check = new JsonObject();
        check.addProperty("id", "ssh");
        check.addProperty("name", "SSH channel");
        if (!ssh.present()) {
            check.addProperty("status", "ok");
            check.addProperty("detail", "此构建不含 SSH 终端通道（默认构建，需要时用 -PwithSsh）");
        } else if (!config.ssh.enabled) {
            check.addProperty("status", "ok");
            check.addProperty("detail", "SSH 终端通道已关闭（默认，无监听端口）");
        } else if (ssh.isRunning()) {
            check.addProperty("status", "ok");
            check.addProperty("detail", "SSH 终端通道监听中：" + config.ssh.bindHost + ":" + ssh.activePort()
                    + (config.ssh.passwordAuth ? "；口令登录已开启" : "；仅密钥登录"));
        } else {
            check.addProperty("status", "warn");
            check.addProperty("detail", "SSH 终端通道已启用但未运行（平台不支持或启动失败，见日志）");
        }
        return check;
    }

    /// One section of the plain-text report: its heading and the body under it.
    private record Section(String title, String body) {
    }

    /// Splits the report into sections. A heading is a line with no leading
    /// whitespace and no colon — the report's headings (`Language`,
    /// `npm registry`, …) are bare words, which is what tells them from the
    /// preamble's `Java: …` / `OS: …` rows and the final `Result: …` verdict.
    /// The preamble rows become a `runtime` check of their own; they are the
    /// toolchain the rest of the report is about.
    private static List<Section> sections(String text) {
        List<Section> sections = new ArrayList<>();
        String title = null;
        StringBuilder body = new StringBuilder();
        StringBuilder preamble = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            boolean heading = !line.isEmpty() && !Character.isWhitespace(line.charAt(0))
                    && !line.startsWith("HDSL ") && !line.startsWith("Result:")
                    && !line.contains(":");
            if (heading) {
                if (title != null) {
                    sections.add(new Section(title, body.toString().strip()));
                }
                title = line.strip();
                body = new StringBuilder();
            } else if (title != null) {
                body.append(line).append('\n');
            } else if (line.startsWith("Java:") || line.startsWith("OS:")) {
                preamble.append(line.strip()).append('\n');
            }
        }
        if (title != null) {
            sections.add(new Section(title, body.toString().strip()));
        }
        if (!preamble.isEmpty()) {
            sections.add(0, new Section("Runtime", preamble.toString().strip()));
        }
        return sections;
    }

    /// The status a section's own words imply: hard failures first, then the
    /// degradations the report marks, else fine.
    ///
    /// Package-private so the mapping can be tested without producing every
    /// ending it has to classify — a section whose body is a shortage is not
    /// something a test host can be made to report on demand.
    ///
    /// @param body the section's body
    /// @return `fail`, `warn` or `ok`
    static String statusOf(String body) {
        String lower = body.toLowerCase(Locale.ROOT);
        if (lower.contains("failed") || lower.contains("not found") || lower.contains("[unsupported]")) {
            return "fail";
        }
        if (lower.contains("[incomplete]") || lower.contains("[shortage]")
                || lower.contains("skipped") || lower.contains("unavailable")) {
            return "warn";
        }
        return "ok";
    }

    /// The report's final `Result: …` line, which carries its verdict.
    private static @Nullable String verdict(String text) {
        for (String line : text.split("\n")) {
            if (line.startsWith("Result:")) {
                return line.strip();
            }
        }
        return null;
    }

    /// The id a section title gets: lowercase, words joined by dashes.
    private static String slug(String title) {
        String slug = title.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return slug.isEmpty() ? "section" : slug;
    }
}
