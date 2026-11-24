/*
 * HDSL
 * Copyright (C) 2026  HDSL contributors
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
import com.google.gson.JsonParser;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jackhuang.hmcl.dsh.BootstrapCatalog;
import org.jackhuang.hmcl.dsh.BootstrapRuntimeManager;
import org.jackhuang.hmcl.dsh.BuildToolchainManager;
import org.jackhuang.hmcl.dsh.PythonResolver;
import org.jackhuang.hmcl.dsh.PythonRuntimeManager;
import org.jackhuang.hmcl.dsh.UvRuntime;
import org.jackhuang.hmcl.util.platform.Architecture;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jackhuang.hmcl.web.audit.AuditLog;
import org.jackhuang.hmcl.web.auth.AuthFilter;
import org.jackhuang.hmcl.web.task.TaskService;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/// The runtime-environment page: what the panel can provide, what is already
/// here, and what it would fetch.
///
/// - `GET  /api/bootstrap`          — `{catalog, bundles: [...], generatedAt}`
/// - `POST /api/bootstrap/install`  — `{bundle}` → `{taskId}`
///
/// Every bundle names four things, because "missing libatomic" alone does not
/// tell anyone what to do: what is missing, why it will be needed, where the
/// bytes come from (URL and digest), and where they land. Installing is always
/// an explicit request; nothing here runs on a timer or on page load.
@NotNullByDefault
public final class BootstrapApiServlet extends HttpServlet {

    /// Bundles this page can provision.
    private static final List<String> BUNDLES = List.of("closure", "ca", "python", "toolchain", "uv");

    private final TaskService tasks;

    public BootstrapApiServlet(TaskService tasks) {
        this.tasks = tasks;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Json.write(response, report());
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String bundle = bundleOf(request);
        if (bundle == null || !BUNDLES.contains(bundle)) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "unknown_bundle: " + bundle);
            return;
        }
        TaskService.Task task = submit(bundle);
        AuditLog.log(AuditLog.Channel.WEB, "bootstrap.install", bundle, "submitted",
                actorOf(request), request.getRemoteAddr());
        Json.write(response, Map.of("taskId", task.id()));
    }

    private TaskService.Task submit(String bundle) {
        return switch (bundle) {
            case "closure" -> tasks.submit("bootstrap-closure", null, live -> {
                live.update("下载并校验运行时闭包", -1);
                BootstrapRuntimeManager.ensureRuntimeClosure();
                return "运行时闭包已就绪";
            });
            case "ca" -> tasks.submit("bootstrap-ca", null, live -> {
                live.update("下载并校验 CA 证书", -1);
                BootstrapRuntimeManager.ensureCaBundle();
                return "CA 证书已就绪";
            });
            case "python" -> tasks.submit("bootstrap-python", null, live -> {
                live.update("下载并校验 CPython", -1);
                PythonRuntimeManager.Installed installed = PythonRuntimeManager.ensureShared();
                return "CPython " + installed.version() + " 已就绪";
            });
            case "toolchain" -> tasks.submit("bootstrap-toolchain", null, live -> {
                live.update("准备构建工具链", -1);
                BuildToolchainManager.Toolchain toolchain = BuildToolchainManager.defaultManager()
                        .ensureForBuild((message, fraction) -> live.update(message, fraction),
                                () -> false);
                return toolchain.portable() ? "便携构建工具链已就绪" : "宿主构建工具链可用";
            });
            case "uv" -> tasks.submit("bootstrap-uv", null, live -> {
                live.update("下载并校验 uv", -1);
                org.jackhuang.hmcl.web.brand.BrandInstaller.ensureSharedUv(
                        message -> live.update(message, -1));
                return "uv " + UvRuntime.version() + " 已就绪";
            });
            default -> throw new IllegalArgumentException("unknown bundle: " + bundle);
        };
    }

    /// The whole page in one response.
    ///
    /// @return the JSON body
    public static JsonObject report() {
        JsonObject body = new JsonObject();
        body.addProperty("catalog", BootstrapCatalog.origin().describe());
        JsonArray bundles = new JsonArray();
        bundles.add(closure());
        bundles.add(ca());
        bundles.add(python());
        bundles.add(toolchain());
        bundles.add(uv());
        body.add("bundles", bundles);
        body.addProperty("generatedAt", Instant.now().toString());
        return body;
    }

    static JsonObject closure() {
        String why = "安装或运行 Node.js 时需要：Node 的二进制链接这些库。";
        if (OperatingSystem.CURRENT_OS != OperatingSystem.LINUX) {
            return bundle("closure", "运行时闭包", "not-applicable",
                    "此系统的 Node.js 不链接 glibc 闭包", why, null, null, null, false);
        }
        BootstrapRuntimeManager.Installed installed = BootstrapRuntimeManager.currentRuntimeClosure();
        List<String> missing = BootstrapRuntimeManager.missingNodeLibraries();
        if (missing.isEmpty()) {
            return bundle("closure", "运行时闭包", "ready",
                    installed == null
                            ? "宿主提供 libstdc++ / libgcc / libatomic，无需下载"
                            : "面板闭包 " + installed.version(),
                    why, null, null, installed == null ? null : installed.directory(), false);
        }
        BootstrapCatalog.Runtime pinned = BootstrapCatalog.current().runtime("linux", archTag());
        BootstrapCatalog.Artifact first = pinned == null || pinned.artifacts().isEmpty()
                ? null : pinned.artifacts().get(0);
        return bundle("closure", "运行时闭包", "missing",
                "缺 " + String.join(", ", missing), why,
                first == null ? null : first.url(),
                first == null ? null : first.sha256(),
                installed == null ? null : installed.directory(), first != null);
    }

    static JsonObject ca() {
        String why = "子进程走 HTTPS 时需要：npm、git、uv 和各个 agent 都读它。";
        BootstrapRuntimeManager.CaInstalled installed = BootstrapRuntimeManager.currentCaBundle();
        if (installed != null) {
            return bundle("ca", "CA 证书", "ready",
                    "cacert " + installed.version() + " · " + installed.file(),
                    why, null, null, installed.file(), false);
        }
        BootstrapCatalog.Ca pinned = BootstrapCatalog.current().ca();
        return bundle("ca", "CA 证书", "missing",
                "未安装（子进程目前依赖 JVM 自带的信任库）", why,
                pinned == null ? null : pinned.url(),
                pinned == null ? null : pinned.sha256(), null, pinned != null);
    }

    static JsonObject python() {
        String why = "品牌源码安装、node-gyp 编译原生模块时需要解释器。";
        PythonResolver.Interpreter python = PythonResolver.resolve();
        if (python.usable()) {
            return bundle("python", "Python", "ready",
                    python.version() + " · " + originLabel(python.origin()),
                    why, null, null, python.executable(), false);
        }
        PythonRuntimeManager.Artifact pinned = PythonRuntimeManager.defaultArtifact();
        return bundle("python", "Python", "missing",
                "没有可用的解释器（面板可提供 CPython "
                        + PythonRuntimeManager.defaultVersion() + "）", why,
                pinned == null ? null : pinned.url(),
                pinned == null ? null : pinned.sha256(),
                PythonRuntimeManager.defaultRoot().resolve(PythonRuntimeManager.defaultVersion()),
                pinned != null);
    }

    static JsonObject toolchain() {
        String why = "从源码构建（品牌源码安装、ffmpeg）时需要 cc / c++ / make。";
        BuildToolchainManager.Toolchain toolchain = BuildToolchainManager.defaultManager().detect();
        if (toolchain.usable()) {
            String detail = toolchain.portable() && toolchain.directory() != null
                    ? "便携工具链 " + toolchain.os() + "-" + toolchain.arch() + " · "
                    + toolchain.directory()
                    : "宿主提供（cc=" + String.valueOf(toolchain.pathOf("cc")) + "）";
            return bundle("toolchain", "构建工具链", "ready", detail, why, null, null,
                    toolchain.directory(), false);
        }
        return bundle("toolchain", "构建工具链", "missing",
                toolchain.missing().isEmpty() ? "没有可用的编译器"
                        : "缺 " + String.join(", ", toolchain.missing()),
                why, null, null, null, canInstallToolchain());
    }

    static JsonObject uv() {
        String why = "品牌源码安装用它装 Python 依赖；面板会先校验它的 SHA-256。";
        Path binary = UvRuntime.sharedBinary();
        if (Files.isRegularFile(binary) && UvRuntime.verified(binary)) {
            return bundle("uv", "uv", "ready",
                    "uv " + UvRuntime.version() + " · SHA-256 校验通过",
                    why, null, null, binary, false);
        }
        String sha = UvRuntime.binarySha256();
        if (sha == null) {
            return bundle("uv", "uv", "not-applicable",
                    "此平台没有固定摘要，面板拒绝安装未校验的 uv",
                    why, null, null, binary, false);
        }
        return bundle("uv", "uv", "missing",
                "未安装（uv " + UvRuntime.version() + "，" + UvRuntime.platformTag() + "）",
                why,
                "https://github.com/astral-sh/uv/releases/tag/" + UvRuntime.version(),
                sha, binary, true);
    }

    private static JsonObject bundle(String id, String label, String state, String detail,
                                     String why, @Nullable String source, @Nullable String sha256,
                                     @Nullable Path destination, boolean canInstall) {
        JsonObject object = new JsonObject();
        object.addProperty("id", id);
        object.addProperty("label", label);
        object.addProperty("state", state);
        object.addProperty("detail", detail);
        object.addProperty("why", why);
        if (source != null) {
            object.addProperty("source", source);
        }
        if (sha256 != null) {
            object.addProperty("sha256", sha256);
        }
        if (destination != null) {
            object.addProperty("destination", destination.toString());
        }
        object.addProperty("canInstall", canInstall);
        return object;
    }

    private static String originLabel(PythonResolver.Origin origin) {
        return switch (origin) {
            case OPERATOR -> "操作者指定";
            case SYSTEM -> "宿主提供";
            case MANAGED -> "面板托管";
            case NONE -> "无";
        };
    }

    static boolean canInstallToolchain() {
        return switch (OperatingSystem.CURRENT_OS) {
            case LINUX -> Architecture.CURRENT_ARCH == Architecture.X86_64
                    || Architecture.CURRENT_ARCH == Architecture.ARM64;
            case WINDOWS -> Architecture.CURRENT_ARCH == Architecture.X86_64;
            default -> false;
        };
    }

    /// `x64`, `arm64`, or the enum name — the keys the catalogue uses.
    static String archTag() {
        return switch (Architecture.CURRENT_ARCH) {
            case X86_64 -> "x64";
            case ARM64 -> "arm64";
            default -> Architecture.CURRENT_ARCH.name().toLowerCase(Locale.ROOT);
        };
    }

    private static @Nullable String bundleOf(HttpServletRequest request) throws IOException {
        String body = request.getReader().lines().collect(Collectors.joining("\n"));
        if (body.isBlank()) {
            return null;
        }
        try {
            JsonObject object = JsonParser.parseString(body).getAsJsonObject();
            return object.has("bundle") && object.get("bundle").isJsonPrimitive()
                    ? object.get("bundle").getAsString() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static @Nullable String actorOf(HttpServletRequest request) {
        Object session = request.getAttribute(AuthFilter.SESSION_ATTRIBUTE);
        return session instanceof org.jackhuang.hmcl.web.auth.AuthService.Session s
                ? s.username() : null;
    }
}