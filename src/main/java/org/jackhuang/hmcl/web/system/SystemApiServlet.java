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
package org.jackhuang.hmcl.web.system;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.web.config.ServerConfig;
import org.jackhuang.hmcl.web.http.Json;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.util.List;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/// `/api/system/*` — what the machine looks like, and where the panel lives.
///
/// Two reads, both behind the session gate (and readable by an agent ticket
/// holding the `system` class):
///
/// - `GET /api/system/stats[?history=N]` — the newest sample, optionally with
///   up to [#SystemMetrics.HISTORY_SIZE] earlier ones so a freshly opened page
///   can draw the last minutes immediately. The sampling period is **not** a
///   request parameter: the server decides, at most once a second.
/// - `GET /api/system/info` — the panel's own coordinates (bind address, port,
///   mount, data directory, version). No certificate paths, no credentials.
///
/// This servlet never touches the developer elevation: the metrics are exactly
/// what a shell in the same container could read, so asking for a password
/// would add a prompt without adding a boundary. The developer guard agrees —
/// `/api/system/` is on its allowlist.
@NotNullByDefault
public final class SystemApiServlet extends HttpServlet {

    private final SystemMetrics metrics;
    private final ServerConfig config;
    private final LongSupplier startedAtMillis;
    private final IntSupplier boundPort;

    public SystemApiServlet(SystemMetrics metrics, ServerConfig config, LongSupplier startedAtMillis,
                            IntSupplier boundPort) {
        this.metrics = metrics;
        this.config = config;
        this.startedAtMillis = startedAtMillis;
        this.boundPort = boundPort;
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getPathInfo();
        if (!"GET".equals(request.getMethod())) {
            Json.error(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "use GET");
            return;
        }
        response.setHeader("Cache-Control", "no-store");
        if ("/stats".equals(path)) {
            stats(request, response);
            return;
        }
        if ("/info".equals(path)) {
            info(response);
            return;
        }
        Json.error(response, HttpServletResponse.SC_NOT_FOUND, "not found");
    }

    private void stats(HttpServletRequest request, HttpServletResponse response) throws IOException {
        metrics.start();
        int history = parseInt(request.getParameter("history"), 0);
        history = Math.max(0, Math.min(history, SystemMetrics.HISTORY_SIZE));
        List<SystemSnapshot> samples = metrics.history(history);

        JsonObject body = new JsonObject();
        SystemSnapshot latest = metrics.latest();
        body.add("snapshot", latest == null ? com.google.gson.JsonNull.INSTANCE : Json.GSON.toJsonTree(latest));
        JsonArray array = new JsonArray();
        for (SystemSnapshot sample : samples) {
            array.add(Json.GSON.toJsonTree(sample));
        }
        body.add("history", array);
        Json.write(response, body);
    }

    private void info(HttpServletResponse response) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("version", Metadata.VERSION);
        body.addProperty("java", String.valueOf(Runtime.version().feature()));
        body.addProperty("bindHost", config.bindHost == null || config.bindHost.isBlank()
                ? "0.0.0.0" : config.bindHost);
        // The connector's real port, not the configured one: `port: 0` means
        // "pick a free port", and the answer must be the door that opened.
        int port = boundPort.getAsInt();
        body.addProperty("port", port > 0 ? port : config.port);
        body.addProperty("basePath", config.basePath == null ? "" : config.basePath);
        body.addProperty("dataDir", config.dataDir.toAbsolutePath().normalize().toString());
        body.addProperty("publicBaseUrl", config.publicBaseUrl == null ? "" : config.publicBaseUrl);
        body.addProperty("https", config.https.enabled);
        body.addProperty("security", config.https.hasExplicitCertificate()
                ? (config.https.pkcs12File.isBlank() ? "pem" : "pkcs12")
                : "self-signed-or-http");
        body.addProperty("startedAt", startedAtMillis.getAsLong());
        Json.write(response, body);
    }

    private static int parseInt(String text, int fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
