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
package org.jackhuang.hmcl.web.zcode;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jackhuang.hmcl.web.config.ServerConfig;
import org.jackhuang.hmcl.web.http.Json;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// The REST surface of the **experimental** ZCode category, mapped at
/// `/api/zcode/*` (behind the same [org.jackhuang.hmcl.web.auth.AuthFilter]
/// session gate as every other `/api/*` route):
///
/// - `GET    /api/zcode/dist`                      — `{present, path, version}`
///   for the discovered distribution (`HDSL_ZCODE_PACKAGE`, else the newest
///   installed release below `<dataDir>/zcode/releases/`, else
///   `<dataDir>/zcode/current/`; valid when it holds `bin/zcode.mjs`)
/// - `GET    /api/zcode/releases`                  — the installed releases,
///   newest first, each flagged with whether it is the current one
/// - `POST   /api/zcode/build`                     — `{version}`, an upstream
///   tag or branch: starts the in-panel build (download → patch → pnpm install
///   → pnpm build:zcode → install) and returns 202; one build at a time
/// - `GET    /api/zcode/build`                     — the build's state, current
///   step and fraction
/// - `GET    /api/zcode/build/log?tail=N`          — the tail of the build log
/// - `GET    /api/zcode/instances`                 — every instance with its
///   runtime state and, while running, its `openUrl`
/// - `POST   /api/zcode/instances`                 — create `{name, baseUrl?, apiKey?}`
/// - `GET    /api/zcode/instances/{id}`            — one instance
/// - `PATCH  /api/zcode/instances/{id}`            — edit `{name?, baseUrl?, apiKey?}`;
///   credential changes are refused while the instance is running
/// - `DELETE /api/zcode/instances/{id}`            — remove; refused while running
/// - `POST   /api/zcode/instances/{id}/launch`     — start the process, wait for
///   its readiness line; idempotent while already running
/// - `POST   /api/zcode/instances/{id}/stop`       — SIGTERM the process
/// - `GET    /api/zcode/instances/{id}/open`       — `{url}` of the running
///   instance: `/i/<id>/`, the panel's own reverse-proxied mount — same port,
///   same certificate and the same session gate as dsh
/// - `GET    /api/zcode/instances/{id}/logs?tail=N`— the trailing lines of the
///   process log
///
/// The category is an implementation-level experiment around ZCode
/// (zai-org/ZCode, Apache-2.0): nothing here is covered by the dsh category's
/// compatibility promises, API keys are stored in clear text in the instance
/// manifest, and distributions are built from upstream source with [ZcodePatch]
/// applied. Instances bind loopback only and are reached through the panel's
/// `/i/<id>/` reverse proxy — the loopback-less, plain-HTTP era documented
/// earlier is over, and the patch in the built distribution is what makes the
/// proxied mounting work.
@NotNullByDefault
public final class ZcodeApiServlet extends HttpServlet {

    private static final int DEFAULT_LOG_TAIL = 200;
    private static final int MAX_LOG_TAIL = 2000;
    private static final int DEFAULT_BUILD_LOG_TAIL = 400;
    private static final int MAX_BUILD_LOG_TAIL = 4000;

    private final ServerConfig config;
    private final ZcodeInstanceManager manager;
    private final ZcodeBuilder.Settings buildSettings;

    public ZcodeApiServlet(ServerConfig config) {
        this.config = config;
        Path root = config.dataDir.resolve("zcode");
        this.manager = new ZcodeInstanceManager(root.resolve("instances"));
        this.buildSettings = new ZcodeBuilder.Settings(root, config.zcode.buildBin, config.zcode.pnpm,
                config.zcode.sourceUrl, config.zcode.keepSources);
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String[] segments = split(request.getPathInfo());
        try {
            if (segments.length == 1 && "dist".equals(segments[0]) && "GET".equals(request.getMethod())) {
                dist(response);
                return;
            }
            if (segments.length == 1 && "releases".equals(segments[0]) && "GET".equals(request.getMethod())) {
                releases(response);
                return;
            }
            if (segments.length >= 1 && "build".equals(segments[0])) {
                if (segments.length == 1) {
                    switch (request.getMethod()) {
                        case "GET" -> buildStatus(response);
                        case "POST" -> startBuild(request, response);
                        default -> Json.error(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
                                "method not allowed");
                    }
                    return;
                }
                if (segments.length == 2 && "log".equals(segments[1]) && "GET".equals(request.getMethod())) {
                    buildLog(request, response);
                    return;
                }
            }
            if (segments.length == 1 && "instances".equals(segments[0])) {
                switch (request.getMethod()) {
                    case "GET" -> listInstances(response);
                    case "POST" -> createInstance(request, response);
                    default -> Json.error(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "method not allowed");
                }
                return;
            }
            if (segments.length >= 2 && "instances".equals(segments[0])) {
                String id = segments[1];
                if (segments.length == 2) {
                    switch (request.getMethod()) {
                        case "GET" -> getInstance(response, id);
                        case "PATCH" -> patchInstance(request, response, id);
                        case "DELETE" -> deleteInstance(response, id);
                        default -> Json.error(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "method not allowed");
                    }
                    return;
                }
                if (segments.length == 3 && "launch".equals(segments[2]) && "POST".equals(request.getMethod())) {
                    launch(response, id);
                    return;
                }
                if (segments.length == 3 && "stop".equals(segments[2]) && "POST".equals(request.getMethod())) {
                    stop(response, id);
                    return;
                }
                if (segments.length == 3 && "open".equals(segments[2]) && "GET".equals(request.getMethod())) {
                    open(request, response, id);
                    return;
                }
                if (segments.length == 3 && "logs".equals(segments[2]) && "GET".equals(request.getMethod())) {
                    logs(request, response, id);
                    return;
                }
            }
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "not found");
        } catch (ZcodeException e) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        }
    }

    // -------------------------------------------------------------------- dist --

    private void dist(HttpServletResponse response) throws IOException {
        Path directory = packageDir();
        boolean present = Files.isRegularFile(directory.resolve("bin/zcode.mjs"));
        JsonObject body = new JsonObject();
        body.addProperty("present", present);
        body.addProperty("path", directory.toString());
        body.add("version", present ? versionOf(directory) : JsonNull.INSTANCE);
        Json.writePreservingNulls(response, body);
    }

    /// The installed releases, newest first.
    private void releases(HttpServletResponse response) throws IOException {
        JsonArray array = new JsonArray();
        for (ZcodeBuilder.Release release : ZcodeBuilder.releases(config.dataDir.resolve("zcode"))) {
            JsonObject item = new JsonObject();
            item.addProperty("version", release.version());
            item.addProperty("path", release.path());
            item.addProperty("builtAt", release.builtAt());
            item.addProperty("current", release.current());
            array.add(item);
        }
        JsonObject body = new JsonObject();
        body.add("releases", array);
        Json.write(response, body);
    }

    /// The distribution directory: the `HDSL_ZCODE_PACKAGE` override, else the
    /// newest installed release (the `current` link the builder maintains, or
    /// whatever `releases/` holds), else where the next build would land.
    private Path packageDir() {
        String configured = config.zcode.packageDir;
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        return ZcodeBuilder.currentPackage(config.dataDir.resolve("zcode"));
    }

    /// The distribution's version, from its `package.json`; `null` when the
    /// file is missing or unreadable.
    private static @Nullable com.google.gson.JsonElement versionOf(Path directory) {
        Path manifest = directory.resolve("package.json");
        if (!Files.isRegularFile(manifest)) {
            return JsonNull.INSTANCE;
        }
        try {
            com.google.gson.JsonObject parsed = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
            return parsed.has("version") && parsed.get("version").isJsonPrimitive()
                    ? new com.google.gson.JsonPrimitive(parsed.get("version").getAsString())
                    : JsonNull.INSTANCE;
        } catch (Exception e) {
            return JsonNull.INSTANCE;
        }
    }

    // --------------------------------------------------------------- instances --

    private void listInstances(HttpServletResponse response) throws IOException {
        JsonArray instances = new JsonArray();
        for (ZcodeInstance instance : manager.list()) {
            instances.add(instanceJson(instance));
        }
        JsonObject body = new JsonObject();
        body.add("instances", instances);
        Json.writePreservingNulls(response, body);
    }

    private void createInstance(HttpServletRequest request, HttpServletResponse response)
            throws IOException, ZcodeException {
        JsonObject body = body(request, response);
        if (body == null) {
            return;
        }
        String name = stringField(body, "name");
        String baseUrl = stringField(body, "baseUrl");
        String apiKey = stringField(body, "apiKey");
        ZcodeInstance created = manager.create(name, baseUrl, apiKey);
        Json.writePreservingNulls(response, HttpServletResponse.SC_CREATED,
                Map.of("instance", instanceJson(created)));
    }

    private void getInstance(HttpServletResponse response, String id) throws IOException {
        ZcodeInstance instance = manager.find(id);
        if (instance == null) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "no such ZCode instance");
            return;
        }
        Json.writePreservingNulls(response, Map.of("instance", instanceJson(instance)));
    }

    private void patchInstance(HttpServletRequest request, HttpServletResponse response, String id)
            throws IOException, ZcodeException {
        ZcodeInstance instance = manager.find(id);
        if (instance == null) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "no such ZCode instance");
            return;
        }
        JsonObject body = body(request, response);
        if (body == null) {
            return;
        }
        boolean touchesCredentials = body.has("baseUrl") || body.has("apiKey");
        if (touchesCredentials && ZcodeRuntime.isRunning(id)) {
            Json.error(response, HttpServletResponse.SC_CONFLICT,
                    "stop the ZCode instance before changing its credentials");
            return;
        }

        ZcodeInstance patched = instance;
        String name = stringField(body, "name");
        if (name != null && !name.trim().isEmpty() && !name.trim().equals(instance.name())) {
            patched = patched.withName(name.trim());
        }
        if (body.has("baseUrl") || body.has("apiKey")) {
            String baseUrl = stringField(body, "baseUrl");
            String apiKey = stringField(body, "apiKey");
            patched = patched.withCredentials(
                    baseUrl == null ? patched.baseUrl() : baseUrl,
                    apiKey == null ? patched.apiKey() : apiKey);
        }
        manager.update(patched);
        // Save-time credential injection, best effort by design: a future ZCode
        // that renames the file simply stops reading what we write here.
        manager.writeProviderConfig(patched);
        Json.writePreservingNulls(response, Map.of("instance", instanceJson(patched)));
    }

    private void deleteInstance(HttpServletResponse response, String id) throws IOException, ZcodeException {
        ZcodeInstance instance = manager.find(id);
        if (instance == null) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "no such ZCode instance");
            return;
        }
        if (ZcodeRuntime.isRunning(id)) {
            Json.error(response, HttpServletResponse.SC_CONFLICT,
                    "stop the ZCode instance before deleting it");
            return;
        }
        manager.delete(id);
        response.setStatus(204);
    }

    // ------------------------------------------------------------ launch / stop --

    private void launch(HttpServletResponse response, String id) throws IOException {
        ZcodeInstance instance = manager.find(id);
        if (instance == null) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "no such ZCode instance");
            return;
        }
        if (!Files.isRegularFile(packageDir().resolve("bin/zcode.mjs"))) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST,
                    "no ZCode distribution was found; place one at " + packageDir());
            return;
        }
        ZcodeRuntime.Status status = ZcodeRuntime.launch(
                manager, instance, new HashMap<>(System.getenv()), nodePath(), packageDir());
        writeStatus(response, id, status);
    }

    private void stop(HttpServletResponse response, String id) throws IOException {
        ZcodeInstance instance = manager.find(id);
        if (instance == null) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "no such ZCode instance");
            return;
        }
        ZcodeRuntime.stop(id);
        writeStatus(response, id, ZcodeRuntime.status(id));
    }

    /// The node executable: the env override, else `node` from `PATH`.
    private String nodePath() {
        String configured = config.zcode.nodePath;
        return configured == null || configured.isBlank() ? "node" : configured;
    }

    // ------------------------------------------------------------------ open --

    private void open(HttpServletRequest request, HttpServletResponse response, String id)
            throws IOException {
        ZcodeInstance instance = manager.find(id);
        if (instance == null) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "no such ZCode instance");
            return;
        }
        if (!ZcodeRuntime.isRunning(id)) {
            Json.error(response, HttpServletResponse.SC_CONFLICT,
                    "the ZCode instance is not running");
            return;
        }
        // The instance binds loopback and is reached through the panel's own
        // mount — same origin, same certificate, same session gate as dsh.
        JsonObject body = new JsonObject();
        // Browser-consumed URL: carries the mount base (see BrandApiServlet).
        body.addProperty("url", config.basePath + "/i/" + id + "/");
        Json.write(response, body);
    }

    // ------------------------------------------------------------------ logs --

    private void logs(HttpServletRequest request, HttpServletResponse response, String id)
            throws IOException {
        ZcodeInstance instance = manager.find(id);
        if (instance == null) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "no such ZCode instance");
            return;
        }
        int tail = DEFAULT_LOG_TAIL;
        String parameter = request.getParameter("tail");
        if (parameter != null) {
            try {
                tail = Math.min(MAX_LOG_TAIL, Math.max(1, Integer.parseInt(parameter.trim())));
            } catch (NumberFormatException ignored) {
                // The default stands.
            }
        }
        List<String> lines = manager.tailLog(instance, tail);
        JsonArray array = new JsonArray();
        lines.forEach(array::add);
        JsonObject body = new JsonObject();
        body.add("lines", array);
        Json.write(response, body);
    }

    // ----------------------------------------------------------------- build --

    private void buildStatus(HttpServletResponse response) throws IOException {
        Json.writePreservingNulls(response, buildJson(ZcodeBuilder.status()));
    }

    private void startBuild(HttpServletRequest request, HttpServletResponse response)
            throws IOException, ZcodeException {
        JsonObject body = body(request, response);
        if (body == null) {
            return;
        }
        String version = stringField(body, "version");
        ZcodeBuilder.Status started = ZcodeBuilder.start(settingsFor(version), version);
        Json.writePreservingNulls(response, HttpServletResponse.SC_ACCEPTED, buildJson(started));
    }

    private void buildLog(HttpServletRequest request, HttpServletResponse response) throws IOException {
        int tail = DEFAULT_BUILD_LOG_TAIL;
        String parameter = request.getParameter("tail");
        if (parameter != null) {
            try {
                tail = Math.min(MAX_BUILD_LOG_TAIL, Math.max(1, Integer.parseInt(parameter.trim())));
            } catch (NumberFormatException ignored) {
                // The default stands.
            }
        }
        JsonArray array = new JsonArray();
        ZcodeBuilder.tailLog(tail).forEach(array::add);
        JsonObject body = new JsonObject();
        body.add("lines", array);
        Json.write(response, body);
    }

    /// The build settings for a ref, with the source template pointed at
    /// `refs/heads` when the ref is a branch rather than a version tag.
    private ZcodeBuilder.Settings settingsFor(@Nullable String version) {
        String ref = version == null ? "" : version.trim();
        String template = config.zcode.sourceUrl;
        if (!ref.isEmpty() && !ref.matches("v?\\d[\\w.\\-]*") && template.contains("/refs/tags/")) {
            template = template.replace("/refs/tags/", "/refs/heads/");
        }
        return new ZcodeBuilder.Settings(buildSettings.root(), buildSettings.buildBin(),
                buildSettings.pnpm(), template, buildSettings.keepSources());
    }

    private static JsonObject buildJson(ZcodeBuilder.Status status) {
        JsonObject body = new JsonObject();
        body.addProperty("state", status.state().name().toLowerCase(java.util.Locale.ROOT));
        body.addProperty("version", status.version());
        body.addProperty("message", status.message());
        body.addProperty("fraction", status.fraction());
        if (status.error() != null) {
            body.addProperty("error", status.error());
        }
        return body;
    }

    // --------------------------------------------------------------- response --

    /// The wire shape of one instance: the manifest fields minus the secret,
    /// plus runtime state. `token` travels to the panel's own admin only —
    /// this API is session-gated like every other `/api/*` route.
    private JsonObject instanceJson(ZcodeInstance instance) {
        ZcodeRuntime.Status status = ZcodeRuntime.status(instance.id());
        JsonObject body = new JsonObject();
        body.addProperty("id", instance.id());
        body.addProperty("name", instance.name());
        body.addProperty("workspacePath", instance.workspacePath());
        body.add("baseUrl", instance.baseUrl() == null
                ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(instance.baseUrl()));
        body.addProperty("hasApiKey", instance.apiKey() != null && !instance.apiKey().isBlank());
        body.addProperty("token", instance.token());
        body.addProperty("lastPort", instance.lastPort());
        body.addProperty("createdAt", instance.createdAt());
        body.addProperty("state", status.state().name().toLowerCase(java.util.Locale.ROOT));
        if (status.error() != null) {
            body.addProperty("error", status.error());
        }
        return body;
    }

    private static void writeStatus(HttpServletResponse response, String id, ZcodeRuntime.Status status)
            throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("state", status.state().name().toLowerCase(java.util.Locale.ROOT));
        if (status.error() != null) {
            body.addProperty("error", status.error());
        }
        Json.writePreservingNulls(response, body);
    }

    private static String[] split(@Nullable String pathInfo) {
        if (pathInfo == null || pathInfo.isEmpty() || pathInfo.equals("/")) {
            return new String[0];
        }
        String trimmed = pathInfo.startsWith("/") ? pathInfo.substring(1) : pathInfo;
        return trimmed.split("/");
    }

    private static @Nullable JsonObject body(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        try {
            JsonObject object = JsonParser
                    .parseString(new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            if (object.isJsonNull()) {
                throw new JsonParseException("not an object");
            }
            return object;
        } catch (JsonParseException | IllegalStateException e) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "request body must be a JSON object");
            return null;
        }
    }

    private static @Nullable String stringField(JsonObject object, String name) {
        return object.has(name) && object.get(name).isJsonPrimitive() ? object.get(name).getAsString() : null;
    }
}
