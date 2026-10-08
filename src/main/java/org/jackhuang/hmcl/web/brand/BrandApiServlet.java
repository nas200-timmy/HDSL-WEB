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
package org.jackhuang.hmcl.web.brand;

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
import org.jackhuang.hmcl.web.server.HdslServer;
import org.jackhuang.hmcl.web.task.TaskService;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/// The REST surface of the third-party brand categories, mapped at
/// `/api/brands/<brand>/*` (behind the same session gate as every other
/// `/api/*` route):
///
/// - `GET  /api/brands/<brand>/versions` — `{versions, latest, releases,
///   default}`: the registry's clean versions (newest first), the `latest`
///   dist-tag, the installed releases and the version a new instance gets
/// - `GET  /api/brands/<brand>/releases` — the installed releases
/// - `POST /api/brands/<brand>/install` — `{version}`: install that version,
///   202 `{taskId}`; one install per brand at a time
/// - `GET  /api/brands/<brand>/install` — the install task's state
/// - `GET  /api/brands/<brand>/install/log?tail=N` — the install log's tail
/// - `GET/POST /api/brands/<brand>/instances` — list / create `{name, version?}`
/// - `GET/PATCH/DELETE /api/brands/<brand>/instances/{id}` — version changes
///   are refused while the instance runs
/// - `POST /api/brands/<brand>/instances/{id}/launch` | `stop`
/// - `GET  /api/brands/<brand>/instances/{id}/open` — `{url}` of the running
///   instance: `/i/<id>/`, same mount, certificate and session gate as dsh
/// - `GET  /api/brands/<brand>/instances/{id}/logs?tail=N` — the process log
///
/// These tools are third-party: the panel installs them from their npm
/// packages, launches their own web modes and reverse-proxies them. Their
/// functionality, security and billing belong to their vendors.
@NotNullByDefault
public final class BrandApiServlet extends HttpServlet {

    private static final int DEFAULT_LOG_TAIL = 200;
    private static final int MAX_LOG_TAIL = 2000;
    private static final int DEFAULT_INSTALL_LOG_TAIL = 400;
    private static final int MAX_INSTALL_LOG_TAIL = 4000;

    private final ServerConfig config;
    private final TaskService tasks;
    private final Supplier<@Nullable HdslServer> serverRef;
    private final Map<Brand, PathRef> brands = new EnumMap<>(Brand.class);

    private record PathRef(Path root, BrandInstanceManager manager) {
    }

    public BrandApiServlet(ServerConfig config, TaskService tasks,
                           Supplier<@Nullable HdslServer> serverRef) {
        this.config = config;
        this.tasks = tasks;
        this.serverRef = serverRef;
        Path base = config.dataDir.resolve("brands");
        for (Brand brand : Brand.values()) {
            Path root = base.resolve(brand.id());
            brands.put(brand, new PathRef(root, new BrandInstanceManager(brand, root.resolve("instances"))));
        }
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String[] segments = split(request.getPathInfo());
        if (segments.length == 0) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "not found");
            return;
        }
        Brand brand = Brand.fromId(segments[0]);
        if (brand == null) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "unknown brand");
            return;
        }
        PathRef ref = brands.get(brand);
        String[] rest = new String[segments.length - 1];
        System.arraycopy(segments, 1, rest, 0, rest.length);
        try {
            route(brand, ref, request, response, rest);
        } catch (BrandException e) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        }
    }

    private void route(Brand brand, PathRef ref, HttpServletRequest request, HttpServletResponse response,
                       String[] segments) throws IOException, BrandException {
        if (segments.length == 1 && "versions".equals(segments[0]) && "GET".equals(request.getMethod())) {
            versions(brand, ref, response);
            return;
        }
        if (segments.length == 1 && "releases".equals(segments[0]) && "GET".equals(request.getMethod())) {
            releases(brand, ref, response);
            return;
        }
        if (segments.length >= 1 && "install".equals(segments[0])) {
            if (segments.length == 1) {
                switch (request.getMethod()) {
                    case "GET" -> installStatus(brand, response);
                    case "POST" -> startInstall(brand, ref, request, response);
                    default -> Json.error(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "method not allowed");
                }
                return;
            }
            if (segments.length == 2 && "log".equals(segments[1]) && "GET".equals(request.getMethod())) {
                installLog(brand, ref, request, response);
                return;
            }
        }
        if (segments.length == 1 && "instances".equals(segments[0])) {
            switch (request.getMethod()) {
                case "GET" -> listInstances(brand, ref, request, response);
                case "POST" -> createInstance(brand, ref, request, response);
                default -> Json.error(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "method not allowed");
            }
            return;
        }
        if (segments.length >= 2 && "instances".equals(segments[0])) {
            String id = segments[1];
            if (segments.length == 2) {
                switch (request.getMethod()) {
                    case "GET" -> getInstance(brand, ref, request, response, id);
                    case "PATCH" -> patchInstance(brand, ref, request, response, id);
                    case "DELETE" -> deleteInstance(ref, response, id);
                    default -> Json.error(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "method not allowed");
                }
                return;
            }
            if (segments.length == 3 && "launch".equals(segments[2]) && "POST".equals(request.getMethod())) {
                launch(brand, ref, request, response, id);
                return;
            }
            if (segments.length == 3 && "stop".equals(segments[2]) && "POST".equals(request.getMethod())) {
                stop(ref, response, id);
                return;
            }
            if (segments.length == 3 && "open".equals(segments[2]) && "GET".equals(request.getMethod())) {
                open(brand, ref, request, response, id);
                return;
            }
            if (segments.length == 3 && "logs".equals(segments[2]) && "GET".equals(request.getMethod())) {
                logs(ref, request, response, id);
                return;
            }
        }
        Json.error(response, HttpServletResponse.SC_NOT_FOUND, "not found");
    }

    // --------------------------------------------------------------- versions --

    private void versions(Brand brand, PathRef ref, HttpServletResponse response) throws IOException {
        List<String> versions = BrandCatalog.versions(brand);
        JsonObject body = new JsonObject();
        JsonArray array = new JsonArray();
        versions.forEach(array::add);
        body.add("versions", array);
        body.addProperty("latest", BrandCatalog.latest(brand));
        JsonArray installed = new JsonArray();
        for (BrandInstaller.Release release : BrandInstaller.releases(ref.root())) {
            JsonObject item = new JsonObject();
            item.addProperty("version", release.version());
            item.addProperty("current", release.current());
            installed.add(item);
        }
        body.add("releases", installed);
        String def = defaultVersion(ref);
        body.add("default", def == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(def));
        Json.writePreservingNulls(response, body);
    }

    private void releases(Brand brand, PathRef ref, HttpServletResponse response) throws IOException {
        JsonArray array = new JsonArray();
        for (BrandInstaller.Release release : BrandInstaller.releases(ref.root())) {
            JsonObject item = new JsonObject();
            item.addProperty("version", release.version());
            item.addProperty("path", release.path());
            item.addProperty("current", release.current());
            array.add(item);
        }
        JsonObject body = new JsonObject();
        body.add("releases", array);
        Json.write(response, body);
    }

    /// The version a new instance gets: the newest installed release.
    private static @Nullable String defaultVersion(PathRef ref) {
        List<BrandInstaller.Release> releases = BrandInstaller.releases(ref.root());
        return releases.isEmpty() ? null : releases.get(0).version();
    }

    // ---------------------------------------------------------------- install --

    private void startInstall(Brand brand, PathRef ref, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        JsonObject body = body(request, response);
        if (body == null) {
            return;
        }
        String version = stringField(body, "version");
        if (version == null || version.isBlank()) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "version is required");
            return;
        }
        String target = version.trim();
        if (!BrandCatalog.versions(brand).contains(target)) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST,
                    target + " is not a published " + brand.npmPackage() + " version");
            return;
        }
        if (tasks.activeInstall(brand.id()).isPresent()) {
            Json.error(response, HttpServletResponse.SC_CONFLICT,
                    "an install for " + brand.id() + " is already running");
            return;
        }
        TaskService.Task[] holder = new TaskService.Task[1];
        TaskService.Task task = tasks.submit("install", brand.id(), () -> {
            try {
                BrandInstaller.install(brand, target, ref.root(), message -> {
                    TaskService.Task owner = holder[0];
                    if (owner != null) {
                        owner.update(message, -1);
                    }
                });
                return "Installed " + brand.npmPackage() + " " + target;
            } catch (BrandException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        });
        holder[0] = task;
        JsonObject result = new JsonObject();
        result.addProperty("taskId", task.id());
        Json.write(response, HttpServletResponse.SC_ACCEPTED, result);
    }

    private void installStatus(Brand brand, HttpServletResponse response) throws IOException {
        JsonObject body = new JsonObject();
        java.util.Optional<TaskService.TaskInfo> active = tasks.activeInstall(brand.id());
        java.util.Optional<TaskService.TaskInfo> last = tasks.lastInstall(brand.id());
        TaskService.TaskInfo info = active.or(() -> last).orElse(null);
        if (info == null) {
            body.addProperty("state", "none");
            body.addProperty("message", "");
            body.addProperty("fraction", -1);
        } else {
            body.addProperty("state", info.state().toLowerCase(java.util.Locale.ROOT));
            body.addProperty("message", info.message());
            body.addProperty("fraction", info.fraction());
            if (info.error() != null) {
                body.addProperty("error", info.error());
            }
        }
        Json.writePreservingNulls(response, body);
    }

    private void installLog(Brand brand, PathRef ref, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        JsonArray array = new JsonArray();
        BrandInstaller.tailLog(ref.root(), boundedTail(request, DEFAULT_INSTALL_LOG_TAIL, MAX_INSTALL_LOG_TAIL))
                .forEach(array::add);
        JsonObject body = new JsonObject();
        body.add("lines", array);
        Json.write(response, body);
    }

    // --------------------------------------------------------------- instances --

    private void listInstances(Brand brand, PathRef ref, HttpServletRequest request,
                               HttpServletResponse response) throws IOException {
        JsonArray instances = new JsonArray();
        for (BrandInstance instance : ref.manager().list()) {
            instances.add(instanceJson(brand, instance, request));
        }
        JsonObject body = new JsonObject();
        body.add("instances", instances);
        Json.writePreservingNulls(response, body);
    }

    private void createInstance(Brand brand, PathRef ref, HttpServletRequest request, HttpServletResponse response)
            throws IOException, BrandException {
        JsonObject body = body(request, response);
        if (body == null) {
            return;
        }
        String name = stringField(body, "name");
        String version = stringField(body, "version");
        if (version == null || version.isBlank()) {
            version = defaultVersion(ref);
        }
        if (version == null || version.isBlank()) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST,
                    "install a " + brand.npmPackage() + " version before creating an instance");
            return;
        }
        if (BrandInstaller.packageDir(brand, ref.root(), version) == null) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST,
                    brand.npmPackage() + " " + version + " is not installed");
            return;
        }
        BrandInstance created = ref.manager().create(name, version);
        Json.writePreservingNulls(response, HttpServletResponse.SC_CREATED,
                Map.of("instance", instanceJson(brand, created, request)));
    }

    private void getInstance(Brand brand, PathRef ref, HttpServletRequest request, HttpServletResponse response,
                             String id) throws IOException {
        BrandInstance instance = ref.manager().find(id);
        if (instance == null) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "no such instance");
            return;
        }
        Json.writePreservingNulls(response, Map.of("instance", instanceJson(brand, instance, request)));
    }

    private void patchInstance(Brand brand, PathRef ref, HttpServletRequest request, HttpServletResponse response,
                               String id)
            throws IOException, BrandException {
        BrandInstance instance = ref.manager().find(id);
        if (instance == null) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "no such instance");
            return;
        }
        JsonObject body = body(request, response);
        if (body == null) {
            return;
        }
        boolean touchesVersion = body.has("version");
        if (touchesVersion && BrandRuntime.isRunning(id)) {
            Json.error(response, HttpServletResponse.SC_CONFLICT,
                    "stop the instance before changing its version");
            return;
        }
        BrandInstance patched = instance;
        String name = stringField(body, "name");
        if (name != null && !name.trim().isEmpty() && !name.trim().equals(instance.name())) {
            patched = patched.withName(name.trim());
        }
        if (touchesVersion) {
            String version = stringField(body, "version");
            if (version != null && !version.isBlank() && !version.trim().equals(instance.version())
                    && BrandInstaller.packageDir(brand, ref.root(), version.trim()) == null) {
                Json.error(response, HttpServletResponse.SC_BAD_REQUEST,
                        brand.npmPackage() + " " + version.trim() + " is not installed");
                return;
            }
            if (version != null && !version.isBlank() && !version.trim().equals(instance.version())) {
                patched = patched.withVersion(version.trim());
            }
        }
        ref.manager().update(patched);
        Json.writePreservingNulls(response, Map.of("instance", instanceJson(brand, patched, request)));
    }

    private void deleteInstance(PathRef ref, HttpServletResponse response, String id)
            throws IOException, BrandException {
        BrandInstance instance = ref.manager().find(id);
        if (instance == null) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "no such instance");
            return;
        }
        if (BrandRuntime.isRunning(id)) {
            Json.error(response, HttpServletResponse.SC_CONFLICT, "stop the instance before deleting it");
            return;
        }
        ref.manager().delete(id);
        if (instance.publicPort() > 0) {
            BrandPortRegistry.release(instance.publicPort());
        }
        response.setStatus(204);
    }

    // ------------------------------------------------------------ launch / stop --

    private void launch(Brand brand, PathRef ref, HttpServletRequest request, HttpServletResponse response, String id)
            throws IOException {
        BrandInstance instance = ref.manager().find(id);
        if (instance == null) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "no such instance");
            return;
        }
        java.nio.file.Path packageDir = BrandInstaller.packageDir(brand, ref.root(), instance.version());
        if (packageDir == null) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST,
                    brand.npmPackage() + " " + instance.version() + " is not installed");
            return;
        }
        // Path-sensitive clients (OpenCode) cannot live under /i/<id>/ — the
        // brand gets an origin of its own: a published port from the registry,
        // persisted in the manifest so the origin survives restarts.
        if (brand.needsOwnOrigin() && instance.publicPort() <= 0) {
            int port;
            try {
                port = BrandPortRegistry.allocate();
            } catch (BrandException e) {
                Json.error(response, HttpServletResponse.SC_CONFLICT, e.getMessage());
                return;
            }
            BrandPortRegistry.register(port, id);
            try {
                ref.manager().update(instance.withPublicPort(port));
                instance = instance.withPublicPort(port);
            } catch (BrandException e) {
                BrandPortRegistry.release(port);
                Json.error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
                return;
            }
            HdslServer server = serverRef.get();
            if (server != null) {
                server.ensureBrandPort(port);
            }
        }
        BrandRuntime.Status status = BrandRuntime.launch(
                brand, ref.manager(), instance, hostOf(request), packageDir);
        writeStatus(response, status);
    }

    private void stop(PathRef ref, HttpServletResponse response, String id) throws IOException {
        BrandInstance instance = ref.manager().find(id);
        if (instance == null) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "no such instance");
            return;
        }
        BrandRuntime.stop(id);
        writeStatus(response, BrandRuntime.status(id));
    }

    /// The external host for the brand's host allowlist: the request's Host
    /// header without its port, which is exactly the name the browser will use.
    private static @Nullable String hostOf(HttpServletRequest request) {
        String host = request.getHeader("Host");
        if (host == null) {
            return null;
        }
        int colon = host.indexOf(':');
        return (colon > 0 ? host.substring(0, colon) : host).trim();
    }

    // ------------------------------------------------------------------ open --

    private void open(Brand brand, PathRef ref, HttpServletRequest request, HttpServletResponse response, String id)
            throws IOException {
        BrandInstance instance = ref.manager().find(id);
        if (instance == null) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "no such instance");
            return;
        }
        if (!BrandRuntime.isRunning(id)) {
            Json.error(response, HttpServletResponse.SC_CONFLICT, "the instance is not running");
            return;
        }
        JsonObject body = new JsonObject();
        // An own origin is absolute: the instance owns that origin's root and
        // the panel's session cookie (host-scoped, ports ignored) rides along;
        // the mount keeps everything on one origin, certificate and gate.
        body.addProperty("url", openUrl(brand, instance, request));
        Json.write(response, body);
    }

    // ------------------------------------------------------------------ logs --

    private void logs(PathRef ref, HttpServletRequest request, HttpServletResponse response, String id)
            throws IOException {
        BrandInstance instance = ref.manager().find(id);
        if (instance == null) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "no such instance");
            return;
        }
        JsonArray array = new JsonArray();
        ref.manager().tailLog(instance, boundedTail(request, DEFAULT_LOG_TAIL, MAX_LOG_TAIL)).forEach(array::add);
        JsonObject body = new JsonObject();
        body.add("lines", array);
        Json.write(response, body);
    }

    // --------------------------------------------------------------- response --

    private JsonObject instanceJson(Brand brand, BrandInstance instance, HttpServletRequest request) {
        BrandRuntime.Status status = BrandRuntime.status(instance.id());
        JsonObject body = new JsonObject();
        body.addProperty("id", instance.id());
        body.addProperty("brand", instance.brand());
        body.addProperty("name", instance.name());
        body.addProperty("version", instance.version());
        body.addProperty("lastPort", instance.lastPort());
        body.addProperty("publicPort", instance.publicPort());
        body.addProperty("createdAt", instance.createdAt());
        body.addProperty("state", status.state().name().toLowerCase(java.util.Locale.ROOT));
        if (status.error() != null) {
            body.addProperty("error", status.error());
        }
        if (status.state() == BrandRuntime.State.RUNNING) {
            body.addProperty("url", openUrl(brand, instance, request));
        }
        return body;
    }

    /// Where the browser reaches a running instance: the shared mount for
    /// mount-aware clients, the absolute origin URL for path-routed ones.
    private static String openUrl(Brand brand, BrandInstance instance, HttpServletRequest request) {
        if (brand.needsOwnOrigin()) {
            String scheme = request.isSecure() ? "https" : "http";
            String host = hostOf(request);
            return scheme + "://" + host + ":" + instance.publicPort() + "/";
        }
        return "/i/" + instance.id() + "/";
    }

    private static void writeStatus(HttpServletResponse response, BrandRuntime.Status status) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("state", status.state().name().toLowerCase(java.util.Locale.ROOT));
        if (status.error() != null) {
            body.addProperty("error", status.error());
        }
        Json.writePreservingNulls(response, body);
    }

    private static int boundedTail(HttpServletRequest request, int defaultTail, int maxTail) {
        String parameter = request.getParameter("tail");
        if (parameter == null) {
            return defaultTail;
        }
        try {
            return Math.min(maxTail, Math.max(1, Integer.parseInt(parameter.trim())));
        } catch (NumberFormatException ignored) {
            return defaultTail;
        }
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
