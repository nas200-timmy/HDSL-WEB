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
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jackhuang.hmcl.web.audit.AuditLog;
import org.jackhuang.hmcl.web.auth.AuthFilter;
import org.jackhuang.hmcl.web.config.ServerConfig;
import org.jackhuang.hmcl.web.http.Json;
import org.jackhuang.hmcl.web.task.TaskService;
import org.jackhuang.hmcl.web.terminal.BrandTui;
import org.jackhuang.hmcl.web.terminal.TerminalManager;
import org.jackhuang.hmcl.web.terminal.TerminalSession;
import org.jackhuang.hmcl.web.terminal.TerminalTarget;
import org.jackhuang.hmcl.web.terminal.InstanceTerminal;
import org.jackhuang.hmcl.web.terminal.TerminalTickets;
import org.jackhuang.hmcl.web.terminal.pty.PtySize;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

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
/// - `GET  /api/brands/<brand>/instances/{id}/plugins` — the plugin channel:
///   `{supported: false, plugins: []}` until a brand grows plugins
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
    private final TerminalManager terminals;
    private final TerminalTickets terminalTickets;
    private final org.jackhuang.hmcl.web.auth.AuthService auth;
    private final org.jackhuang.hmcl.web.developer.DeveloperAuth developerAuth;
    private final org.jackhuang.hmcl.web.developer.DeveloperElevation developerElevation;
    /// Live TLS state of the main connector; the terminal is HTTPS-only.
    private final java.util.function.BooleanSupplier tlsActive;
    private final InstanceTerminal instanceTerminal;
    private final BrandPolicy policy;
    private final Map<Brand, PathRef> brands = new EnumMap<>(Brand.class);

    private record PathRef(Path root, BrandInstanceManager manager) {
    }

    public BrandApiServlet(ServerConfig config, TaskService tasks,
                           TerminalManager terminals, TerminalTickets terminalTickets,
                           org.jackhuang.hmcl.web.auth.AuthService auth,
                           org.jackhuang.hmcl.web.developer.DeveloperAuth developerAuth,
                           org.jackhuang.hmcl.web.developer.DeveloperElevation developerElevation,
                           java.util.function.BooleanSupplier tlsActive,
                           org.jackhuang.hmcl.web.emergency.EmergencyStop emergency) {
        this.config = config;
        this.tasks = tasks;
        this.terminals = terminals;
        this.terminalTickets = terminalTickets;
        this.auth = auth;
        this.developerAuth = developerAuth;
        this.developerElevation = developerElevation;
        this.tlsActive = tlsActive;
        this.policy = new BrandPolicy(config.dataDir);
        this.instanceTerminal = new InstanceTerminal(config, terminals, terminalTickets, auth,
                developerAuth, developerElevation, tlsActive, emergency);
        Path base = config.dataDir.resolve("brands");
        for (Brand brand : Brand.values()) {
            Path root = base.resolve(brand.id());
            BrandInstanceManager manager = new BrandInstanceManager(brand, root.resolve("instances"));
            // Directories a delete could not remove (something held a file inside) were
            // renamed aside; at startup whatever held them is most likely gone.
            manager.sweepTombstones();
            brands.put(brand, new PathRef(root, manager));
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
                    case "DELETE" -> deleteInstance(brand, ref, response, id);
                    default -> Json.error(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "method not allowed");
                }
                return;
            }
            if (segments.length == 3 && "launch".equals(segments[2]) && "POST".equals(request.getMethod())) {
                launch(brand, ref, request, response, id);
                return;
            }
            if (segments.length == 3 && "stop".equals(segments[2]) && "POST".equals(request.getMethod())) {
                stop(brand, ref, response, id);
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
            if (segments.length == 3 && "plugins".equals(segments[2]) && "GET".equals(request.getMethod())) {
                plugins(brand, ref, response, id);
                return;
            }
            if (segments.length == 3 && "terminals".equals(segments[2]) && "GET".equals(request.getMethod())) {
                kernels(brand, ref, response, id);
                return;
            }
            if (segments.length == 3 && "terminal".equals(segments[2])
                    && ("POST".equals(request.getMethod()) || "DELETE".equals(request.getMethod()))) {
                terminal(brand, ref, request, response, id);
                return;
            }
            if (segments.length == 3 && "admin-password".equals(segments[2])
                    && "POST".equals(request.getMethod())) {
                adminPassword(brand, ref, request, response, id);
                return;
            }
            if (segments.length == 3 && "reveal".equals(segments[2]) && "POST".equals(request.getMethod())) {
                reveal(ref, request, response, id);
                return;
            }
        }
        Json.error(response, HttpServletResponse.SC_NOT_FOUND, "not found");
    }

    // --------------------------------------------------------------- versions --

    private void versions(Brand brand, PathRef ref, HttpServletResponse response) throws IOException {
        List<String> versions = BrandCatalog.versions(brand);
        if (versions.isEmpty()) {
            // An empty picker is indistinguishable from "this project has no releases",
            // and the certificate failure behind it is the one thing a person can act
            // on. Say it here, in the place they are looking.
            org.jackhuang.hmcl.util.platform.OutboundTls.Trouble trouble = BrandCatalog.trouble(brand);
            if (trouble != null) {
                Json.error(response, 409, "读不到 " + brand.id() + " 的版本列表。\n\n"
                        + org.jackhuang.hmcl.util.platform.OutboundTls.explain(trouble));
                return;
            }
        }
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
                    target + " is not a published " + brand.sourceLabel() + " version");
            return;
        }
        boolean sourceBuild = brand == Brand.OCTOP && policy.get(brand).sourceBuild();
        // Reinstalling deletes the target directory, so it must not happen under
        // a running instance — whatever the install path. (The source build has
        // the same rule for its own reason: it would rebuild the tree the
        // process is executing from.)
        for (BrandInstance instance : ref.manager().list()) {
            if (target.equals(instance.version()) && BrandRuntime.isRunning(instance.id())) {
                Json.error(response, HttpServletResponse.SC_CONFLICT,
                        sourceBuild
                                ? "stop the running instance before rebuilding this version from source"
                                : "stop the running instance before reinstalling this version");
                return;
            }
        }
        java.util.Optional<TaskService.TaskInfo> running = tasks.activeInstall(brand.id());
        if (running.isPresent()) {
            TaskService.TaskInfo info = running.get();
            // Asking for the version that is already being installed is not an error: the create
            // and switch flows both land here, and failing them left the page saying "install
            // failed" while an install was in fact under way. Hand back the live task instead.
            if (info.result() != null && info.result().has("version")
                    && target.equals(info.result().get("version").getAsString())) {
                JsonObject attached = new JsonObject();
                attached.addProperty("taskId", info.id());
                attached.addProperty("alreadyRunning", true);
                Json.write(response, attached);
                return;
            }
            Json.error(response, HttpServletResponse.SC_CONFLICT,
                    "an install for " + brand.id() + " is already running (" + info.message() + ")");
            return;
        }
        TaskService.Task task = tasks.submit("install", brand.id(), live -> {
            BrandInstaller.Progress progress = new BrandInstaller.Progress() {
                @Override
                public void update(String message, double fraction) {
                    live.update(message, fraction);
                }

                @Override
                public boolean isCancelled() {
                    return live.isCancelled();
                }

                @Override
                public void stage(String stage, double fraction) {
                    live.updateStage(stage, fraction);
                }

                @Override
                public void stage(String stage, String key, double fraction) {
                    live.updateStage(stage, key, fraction);
                }
            };
            // What this task is installing, so a second request for the same version can attach
            // to it instead of being told "already running".
            JsonObject installing = new JsonObject();
            installing.addProperty("version", target);
            installing.addProperty("sourceBuild", sourceBuild);
            live.attachResult(installing);
            try {
                if (sourceBuild) {
                    BrandInstaller.installOctopSource(brand, target, ref.root(),
                            ref.root().resolve(BrandInstaller.RELEASES_DIR).resolve(target), progress);
                } else {
                    BrandInstaller.install(brand, target, ref.root(), progress);
                }
                return "Installed " + brand.sourceLabel() + " " + target
                        + (sourceBuild ? " (source build)" : "");
            } catch (BrandException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        });
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
            body.addProperty("stageFraction", -1);
        } else {
            body.addProperty("taskId", info.id());
            body.addProperty("state", info.state().toLowerCase(java.util.Locale.ROOT));
            body.addProperty("message", info.message());
            body.addProperty("fraction", info.fraction());
            if (info.stage() != null) {
                body.addProperty("stage", info.stage());
            }
            if (info.stageKey() != null) {
                body.addProperty("stageKey", info.stageKey());
            }
            body.addProperty("stageFraction", info.stageFraction());
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
        if (version != null) {
            // A padded value would fail the marker lookup for a version that is installed.
            version = version.trim();
        }
        if (version == null || version.isBlank()) {
            version = defaultVersion(ref);
        }
        if (version == null || version.isBlank()) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST,
                    "install a " + brand.sourceLabel() + " version before creating an instance");
            return;
        }
        if (BrandInstaller.packageDir(brand, ref.root(), version) == null) {
            // A version that is being installed right now is not a mistake: saying so turns
            // "creation is broken" into "wait a moment".
            java.util.Optional<TaskService.TaskInfo> running = tasks.activeInstall(brand.id());
            if (running.isPresent() && running.get().result() != null
                    && running.get().result().has("version")
                    && version.equals(running.get().result().get("version").getAsString())) {
                Json.error(response, HttpServletResponse.SC_CONFLICT,
                        brand.sourceLabel() + " " + version + " is being installed ("
                                + running.get().message() + "); create the instance when it finishes");
                return;
            }
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST,
                    brand.sourceLabel() + " " + version + " is not installed");
            return;
        }
        BrandInstance created = ref.manager().create(name, version);
        if (body.has("baseUrl")) {
            created = created.withBaseUrl(baseUrlFrom(brand, body));
            ref.manager().update(created);
        }
        Json.writePreservingNulls(response, HttpServletResponse.SC_CREATED,
                Map.of("instance", instanceJson(brand, created, request)));
    }

    /// Claude Web's endpoint override, read out of a request body.
    ///
    /// `ANTHROPIC_BASE_URL`: Anthropic refuses the official kernel outright in
    /// unsupported regions, and pointing the CLI at a reachable gateway is the
    /// documented way around that. Only Claude Web stores one, and only over
    /// http/https — the value goes into the child's environment verbatim.
    ///
    /// @param brand the brand the instance belongs to
    /// @param body  the request body, which carries the field
    /// @return the value to store, or `null` to clear it
    /// @throws BrandException when the brand has no such setting or the URL is
    ///                        not one a client could talk to
    private static @Nullable String baseUrlFrom(Brand brand, JsonObject body) throws BrandException {
        if (brand != Brand.CLAUDE_WEB) {
            throw new BrandException(brand.id()
                    + " instances have no ANTHROPIC_BASE_URL; only claude-web does");
        }
        String raw = stringField(body, "baseUrl");
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            throw new BrandException("baseUrl must start with http:// or https://");
        }
        return trimmed;
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
        if (refuseDisabled(brand, id, response)) {
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
                        brand.sourceLabel() + " " + version.trim() + " is not installed");
                return;
            }
            if (version != null && !version.isBlank() && !version.trim().equals(instance.version())) {
                patched = patched.withVersion(version.trim());
            }
        }
        if (body.has("baseUrl")) {
            patched = patched.withBaseUrl(baseUrlFrom(brand, body));
        }
        ref.manager().update(patched);
        Json.writePreservingNulls(response, Map.of("instance", instanceJson(brand, patched, request)));
    }

    private void deleteInstance(Brand brand, PathRef ref, HttpServletResponse response, String id)
            throws IOException, BrandException {
        BrandInstance instance = ref.manager().find(id);
        if (instance == null) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "no such instance");
            return;
        }
        if (refuseDisabled(brand, id, response)) {
            return;
        }
        if (BrandRuntime.isRunning(id)) {
            Json.error(response, HttpServletResponse.SC_CONFLICT, "stop the instance before deleting it");
            return;
        }
        instanceTerminal.close(brand.id(), id);
        ref.manager().delete(id);
        response.setStatus(204);
    }

    /// The single-instance policy hides every non-survivor. Refuse the actions
    /// that would start, open, change or delete one; stopping remains allowed
    /// so an operator can always wind a disabled instance down.
    private boolean refuseDisabled(Brand brand, String id, HttpServletResponse response) throws IOException {
        if (!policy.disabled(brand, id)) {
            return false;
        }
        Json.error(response, HttpServletResponse.SC_CONFLICT,
                "disabled_by_brand_policy: this instance is disabled by the single-instance policy; "
                        + "choose it as the survivor in Settings → Brand Instances");
        return true;
    }

    // ------------------------------------------------------------ launch / stop --

    private void launch(Brand brand, PathRef ref, HttpServletRequest request, HttpServletResponse response, String id)
            throws IOException {
        BrandInstance instance = ref.manager().find(id);
        if (instance == null) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "no such instance");
            return;
        }
        if (refuseDisabled(brand, id, response)) {
            return;
        }
        java.nio.file.Path packageDir = BrandInstaller.packageDir(brand, ref.root(), instance.version());
        if (packageDir == null) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST,
                    brand.sourceLabel() + " " + instance.version() + " is not installed");
            return;
        }
        BrandRuntime.Status status = BrandRuntime.launch(
                brand, ref.manager(), instance,
                org.jackhuang.hmcl.web.http.HostHeaders.stripPort(request.getHeader("Host")), packageDir);
        writeStatus(response, status);
    }

    private void stop(Brand brand, PathRef ref, HttpServletResponse response, String id) throws IOException {
        BrandInstance instance = ref.manager().find(id);
        if (instance == null) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "no such instance");
            return;
        }
        // Stopping the instance stops its terminal: the agent process belongs
        // to the instance, and leaving it running behind a "stopped" state
        // would be a lie the next browser tab could still see.
        instanceTerminal.close(brand.id(), id);
        BrandRuntime.stop(id);
        writeStatus(response, BrandRuntime.status(id));
    }

    // --------------------------------------------------------------- terminal --

    /// `POST /api/brands/{brand}/instances/{id}/terminal` opens the instance's
    /// native TUI; `DELETE` kills it (the footer's stop button).
    ///
    /// On POSIX the panel spawns the agent on a PTY and answers a one-use
    /// ticket for `/ws/terminal/{ticket}`. On Windows the console belongs to
    /// the desktop, so the panel starts a console window in the instance
    /// workspace instead and answers `{"mode":"desktop"}`.
    /// Opens one of the instance's own folders (or its log) in the desktop.
    /// The path is computed here from the instance record — the request only
    /// names one of three known targets, never a path.
    private void reveal(PathRef ref, HttpServletRequest request, HttpServletResponse response, String id)
            throws IOException {
        BrandInstance instance = ref.manager().find(id);
        if (instance == null) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "no such instance");
            return;
        }
        if (!org.jackhuang.hmcl.web.desktop.DesktopIntegration.supported()) {
            Json.error(response, HttpServletResponse.SC_CONFLICT, "desktop_unsupported: this host has no desktop");
            return;
        }
        String target = readBodyField(request, "target");
        Path path = switch (target == null ? "" : target) {
            case "home" -> ref.manager().homeDirectory(instance);
            case "workspace" -> ref.manager().workspaceDirectory(instance);
            case "log" -> ref.manager().logFile(instance);
            default -> null;
        };
        if (path == null) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST,
                    "target must be one of home, workspace, log");
            return;
        }
        try {
            if ("log".equals(target)) {
                org.jackhuang.hmcl.web.desktop.DesktopIntegration.openText(path);
            } else {
                Files.createDirectories(path);
                org.jackhuang.hmcl.web.desktop.DesktopIntegration.reveal(path);
            }
        } catch (IOException e) {
            Json.error(response, HttpServletResponse.SC_CONFLICT, e.getMessage());
            return;
        }
        AuditLog.log(AuditLog.Channel.TERMINAL, "desktop.reveal", id, "success",
                actorOf(request), request.getRemoteAddr());
        JsonObject body = new JsonObject();
        body.addProperty("opened", path.toString());
        Json.write(response, body);
    }

    /// Reads one string field out of the request body, or `null`.
    private static @Nullable String readBodyField(HttpServletRequest request, String field) throws IOException {
        try (java.io.InputStreamReader reader =
                     new java.io.InputStreamReader(request.getInputStream(), StandardCharsets.UTF_8)) {
            JsonObject body = JsonParser.parseReader(reader).getAsJsonObject();
            return body.has(field) && !body.get(field).isJsonNull()
                    ? body.get(field).getAsString()
                    : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /// Who asked, for the audit trail. `null` when the panel runs with
    /// authentication disabled (loopback-only).
    private static @Nullable String actorOf(HttpServletRequest request) {
        Object session = request.getAttribute(AuthFilter.SESSION_ATTRIBUTE);
        return session instanceof org.jackhuang.hmcl.web.auth.AuthService.Session value
                ? value.username()
                : null;
    }

    private void terminal(Brand brand, PathRef ref, HttpServletRequest request,
                          HttpServletResponse response, String id) throws IOException {
        BrandInstance instance = ref.manager().find(id);
        if (instance == null) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "no such instance");
            return;
        }
        if ("DELETE".equals(request.getMethod())) {
            JsonObject body = new JsonObject();
            body.addProperty("ok", true);
            body.addProperty("killed", instanceTerminal.close(brand.id(), id));
            Json.write(response, body);
            return;
        }
        try {
            instanceTerminal.authorize(request);
            // Claude Web is a workbench in front of the Claude Code CLI and ships
            // two kernels: the community engine its provider configuration is
            // written for, and the official CLI. The caller picks one
            // (`?kernel=`); the adapter refuses rather than substituting.
            BrandTui tui = brand == Brand.CLAUDE_WEB
                    ? new org.jackhuang.hmcl.web.terminal.ClaudeWebTui(
                            request.getParameter("kernel"), instance.baseUrl())
                    : BrandTui.forBrand(brand.id());
            if (tui == null) {
                throw new InstanceTerminal.Refused(409,
                        "brand_has_no_tui: " + brand.id() + " has no interactive terminal");
            }
            Path packageDir = BrandInstaller.packageDir(brand, ref.root(), instance.version());
            if (packageDir == null) {
                throw new InstanceTerminal.Refused(409,
                        brand.sourceLabel() + " " + instance.version() + " is not installed");
            }
            // The official kernel is refused outright in unsupported regions, and
            // Claude Code exits with "App unavailable in region" after a 403 the
            // user cannot act on. Ask the endpoint first and answer with a
            // sentence pointing at the two ways out.
            if (brand == Brand.CLAUDE_WEB
                    && org.jackhuang.hmcl.web.terminal.ClaudeWebTui.OFFICIAL
                            .equals(request.getParameter("kernel"))) {
                org.jackhuang.hmcl.web.terminal.ClaudeWebTui.Probe probe =
                        org.jackhuang.hmcl.web.terminal.ClaudeWebTui.probeAnthropic(instance.baseUrl());
                if (probe != null && org.jackhuang.hmcl.web.terminal.ClaudeWebTui
                        .regionBlocked(probe.status(), probe.body())) {
                    throw new InstanceTerminal.Refused(409,
                            "terminal_failed: 官方内核在这里被 Anthropic 拒绝（HTTP " + probe.status()
                                    + "）。面板位于不受支持地区时，官方 Claude Code 会被直接拒绝——"
                                    + "请改用社区引擎，或在这个实例上配置 ANTHROPIC_BASE_URL 指向可达的网关。");
                }
            }
            // A web runtime and a TUI on one HOME race the agent's own state;
            // the terminal takes the instance over, and the footer's start
            // button brings the web mode back when the user wants it.
            JsonObject body = instanceTerminal.open(request, id,
                    () -> BrandRuntime.stop(id),
                    resume -> tui.target(packageDir, ref.manager().homeDirectory(instance),
                            ref.manager().workspaceDirectory(instance), id, resume));
            Json.write(response, body);
        } catch (InstanceTerminal.Refused e) {
            InstanceTerminal.write(request, response, e);
        }
    }

    // ------------------------------------------------------------------ kernels --

    /// `GET /api/brands/claude-web/instances/{id}/terminals` — the Claude Code
    /// kernels this installation can start, community engine first.
    ///
    /// Exists so the picker never offers a kernel that would fail: the answer
    /// carries availability and, when missing, the reason. Other brands answer
    /// 404 — they have exactly one TUI and therefore nothing to choose.
    private void kernels(Brand brand, PathRef ref, HttpServletResponse response, String id)
            throws IOException {
        BrandInstance instance = ref.manager().find(id);
        if (instance == null) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "no such instance");
            return;
        }
        if (brand != Brand.CLAUDE_WEB) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "not found");
            return;
        }
        Path packageDir = BrandInstaller.packageDir(brand, ref.root(), instance.version());
        if (packageDir == null) {
            Json.error(response, HttpServletResponse.SC_CONFLICT,
                    brand.sourceLabel() + " " + instance.version() + " is not installed");
            return;
        }
        JsonArray array = new JsonArray();
        for (org.jackhuang.hmcl.web.terminal.ClaudeWebTui.Kernel kernel
                : org.jackhuang.hmcl.web.terminal.ClaudeWebTui.kernels(packageDir)) {
            JsonObject item = new JsonObject();
            item.addProperty("id", kernel.id());
            item.addProperty("label", kernel.label());
            item.addProperty("available", kernel.available());
            item.addProperty("detail", kernel.detail());
            array.add(item);
        }
        JsonObject body = new JsonObject();
        body.add("kernels", array);
        body.addProperty("default",
                org.jackhuang.hmcl.web.terminal.ClaudeWebTui.defaultKernel(packageDir));
        Json.writePreservingNulls(response, body);
    }

    // ------------------------------------------------------------------ open --

    private void open(Brand brand, PathRef ref, HttpServletRequest request, HttpServletResponse response, String id)
            throws IOException {
        BrandInstance instance = ref.manager().find(id);
        if (instance == null) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "no such instance");
            return;
        }
        if (refuseDisabled(brand, id, response)) {
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

    // ----------------------------------------------------------------- plugins --

    /// The per-instance plugin channel: `{supported, plugins[]}`.
    ///
    /// Nothing to install, list or toggle today — no brand the panel drives has
    /// a plugin story — so this answers `supported: false` plus an empty list.
    /// The endpoint exists anyway: the panel's plugin dialog reads this shape,
    /// so the day [Brand#supportsPlugins()] flips and real plugins appear here,
    /// the UI lists them without a change.
    private void plugins(Brand brand, PathRef ref, HttpServletResponse response, String id)
            throws IOException {
        if (ref.manager().find(id) == null) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "no such instance");
            return;
        }
        JsonObject body = new JsonObject();
        body.addProperty("supported", brand.supportsPlugins());
        body.add("plugins", new JsonArray());
        Json.write(response, body);
    }

    // ------------------------------------------------------ admin credentials --

    /// Throttle for the panel-password fallback: a plain-HTTP panel cannot elevate at all, so the
    /// panel password is the only second factor there — it must not become an unthrottled oracle.
    private final org.jackhuang.hmcl.web.auth.LoginThrottle adminPasswordThrottle =
            new org.jackhuang.hmcl.web.auth.LoginThrottle();

    /// Octop's own denylist (`src/octop/infra/users/password.py`, compared case-insensitively).
    /// Its `user passwd` does **not** enforce the policy (the offline path only hashes), so the
    /// panel checks the same list itself — otherwise the page would happily set `password123`,
    /// which the web console then refuses to accept as a new password.
    private static final java.util.Set<String> OCTOP_COMMON_PASSWORDS = java.util.Set.of(
            "password", "password1", "password12", "password123", "12345678", "123456789",
            "qwerty123", "admin123", "welcome1", "letmein1", "changeme1", "octop123",
            "abc12345", "iloveyou1");

    /// The instance's own admin credentials.
    ///
    /// Octop generates them on first boot and writes `octop-admin.txt`; until now the only way to
    /// learn or change that password was `docker exec … octop user passwd`. Reading is allowed
    /// without a password for the first minutes after the instance was created — the password was
    /// generated moments ago and the user has to log in at least once. Everything else (a later
    /// read, and every reset) needs the developer password over HTTPS, or the panel password on a
    /// plain-HTTP panel, where elevation is impossible by design.
    private void adminPassword(Brand brand, PathRef ref, HttpServletRequest request,
                               HttpServletResponse response, String id) throws IOException {
        if (brand != Brand.OCTOP) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST,
                    "admin-password is not supported for " + brand.id());
            return;
        }
        BrandInstance instance = ref.manager().find(id);
        if (instance == null) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "unknown instance");
            return;
        }
        JsonObject body = optionalBody(request);
        boolean reset = body != null && body.has("password") && !body.get("password").isJsonNull();
        if (reset || !firstRunWindow(instance)) {
            if (!authorizeSecretAccess(request, response, body)) {
                return;
            }
        }

        Path home = ref.manager().homeDirectory(instance);
        String username = "admin";
        String password = null;
        boolean wizard = false;
        // Octop's own credential file first (`$OCTOP_HOME/credential.txt`), then the panel's
        // `octop-admin.txt`. The same file carries either a login block or Octop's "Setup Required"
        // hint with a wizard password; both are read, because "finish the wizard" and "log in" are
        // two different answers the page must be able to give.
        for (String line : readCredentialFile(home.resolve("credential.txt"))) {
            if (line.startsWith("Setup Required") || line.startsWith("Octop Setup Required")) {
                wizard = true;
                continue;
            }
            String value = credentialValue(line, "Username:");
            if (value != null) {
                username = value;
                continue;
            }
            value = credentialValue(line, "Password:");
            if (value != null && !value.isBlank()) {
                password = value;
                continue;
            }
            value = credentialValue(line, "Wizard password:");
            if (value != null && !value.isBlank()) {
                wizard = true;
                password = value;
            }
        }
        for (String line : readCredentialFile(home.resolve("octop-admin.txt"))) {
            if (line.startsWith("username:")) {
                username = line.substring("username:".length()).trim();
            } else if (line.startsWith("password:")) {
                password = line.substring("password:".length()).trim();
            }
        }
        if (wizard && password == null) {
            // Older entrypoints put the wizard password in its own file instead of the hint block.
            List<String> wizardFile = readCredentialFile(home.resolve("octop-login.txt"));
            if (!wizardFile.isEmpty()) {
                String first = wizardFile.get(0).trim();
                if (!first.isEmpty()) {
                    password = first;
                }
            }
        }
        if (password == null) {
            // Nothing recorded: ask Octop's own user table who the admin is, so the page does not
            // tell somebody their admin is called "admin" when they renamed it during the wizard.
            String discovered = discoverAdminUsername(instance, ref.root(), home);
            if (discovered != null) {
                username = discovered;
            }
        }

        if (reset) {
            String wanted = stringField(body, "password");
            String next = wanted != null && !wanted.isBlank() ? wanted.trim() : randomPassword();
            if (next.length() < 8 || !next.matches(".*[A-Za-z].*") || !next.matches(".*[0-9].*")) {
                Json.error(response, HttpServletResponse.SC_BAD_REQUEST,
                        "password must be at least 8 characters and contain letters and digits");
                return;
            }
            if (OCTOP_COMMON_PASSWORDS.contains(next.toLowerCase(java.util.Locale.ROOT))) {
                Json.error(response, HttpServletResponse.SC_BAD_REQUEST,
                        "password is too common (Octop refuses it in its own console)");
                return;
            }
            try {
                resetOctopPassword(instance, ref.root(), home, username, next);
                writeAdminCredentials(home, username, next);
            } catch (IOException e) {
                String detail = e.getMessage() == null ? "" : e.getMessage();
                if (detail.contains("locked") || detail.contains("busy")) {
                    // `octop user passwd` writes the SQLite database of a *running* server; a busy
                    // instance is the ordinary reason it fails, and stopping it is the fix.
                    Json.error(response, HttpServletResponse.SC_CONFLICT,
                            "database is busy (stop the instance, then retry): " + detail);
                    return;
                }
                Json.error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, detail);
                return;
            }
            AuditLog.log(AuditLog.Channel.WEB, "brand.admin-password", brand.id() + "/" + id,
                    "reset", actorOf(request), request.getRemoteAddr());
            LOG.info("Reset the Octop admin password for instance " + id);
            password = next;
        }

        if (password == null) {
            // Not an error: Octop keeps only a hash, and a password set in its own wizard or web
            // console is not knowable from the panel. Say so, and let the page offer a reset.
            JsonObject result = new JsonObject();
            result.addProperty("username", username);
            result.add("password", JsonNull.INSTANCE);
            result.addProperty("reset", false);
            result.addProperty("readable", false);
            result.addProperty("mode", wizard ? "wizard" : "admin");
            Json.write(response, result);
            return;
        }
        JsonObject result = new JsonObject();
        result.addProperty("username", username);
        result.addProperty("password", password);
        result.addProperty("readable", true);
        result.addProperty("mode", wizard ? "wizard" : "admin");
        result.addProperty("reset", reset);
        Json.write(response, result);
    }

    /// Whether the instance is still inside the "just created" window, where showing the generated
    /// password needs no second factor.
    private static boolean firstRunWindow(BrandInstance instance) {
        return System.currentTimeMillis() - instance.createdAt() < 15 * 60 * 1000L;
    }

    /// Refuses a credentials request that has not proven the developer password — or, on a panel
    /// without HTTPS, the panel password, because there is nothing to elevate over there.
    private boolean authorizeSecretAccess(HttpServletRequest request, HttpServletResponse response,
                                          @Nullable JsonObject body) throws IOException {
        if (org.jackhuang.hmcl.web.http.RequestSecurity.isSecure(request,
                tlsActive.getAsBoolean(), config)) {
            if (developerElevation.require(keyOf(request), sessionAlive(request))) {
                return true;
            }
            Json.error(response, HttpServletResponse.SC_FORBIDDEN,
                    "developer_elevation_required: elevate with the developer password first");
            return false;
        }
        String adminPassword = body == null ? null : stringField(body, "adminPassword");
        if (adminPassword == null || adminPassword.isBlank()) {
            Json.error(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "panel_password_required: this panel has no HTTPS; confirm with the panel password");
            return false;
        }
        return confirmPanelPassword(request, response, adminPassword);
    }

    /// Verifies the panel password, with the same per-address throttle the developer page uses.
    private boolean confirmPanelPassword(HttpServletRequest request, HttpServletResponse response,
                                         String adminPassword) throws IOException {
        String address = request.getRemoteAddr();
        String key = address == null || address.isBlank() ? "unknown" : address;
        java.time.Instant now = java.time.Instant.now();
        if (adminPasswordThrottle.isBlocked(key, now)) {
            response.setHeader("Retry-After",
                    Long.toString(Math.max(1, adminPasswordThrottle.block().toSeconds())));
            Json.error(response, 429, "too many failed panel-password attempts; try again later");
            return false;
        }
        org.jackhuang.hmcl.web.auth.UserStore users = auth.userStore();
        org.jackhuang.hmcl.web.auth.UserStore.User admin = users.admin();
        if (admin != null && users.verify(admin.username(), adminPassword)) {
            adminPasswordThrottle.clear(key);
            return true;
        }
        java.time.Duration refused = adminPasswordThrottle.recordFailure(key, java.time.Instant.now());
        if (refused.isZero()) {
            Json.error(response, HttpServletResponse.SC_FORBIDDEN, "the panel password does not match");
        } else {
            response.setHeader("Retry-After", Long.toString(Math.max(1, refused.toSeconds())));
            Json.error(response, 429, "too many failed panel-password attempts; try again later");
        }
        return false;
    }

    /// The lines of a credential file, or an empty list when it does not exist.
    private static List<String> readCredentialFile(Path file) {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return List.of();
        }
    }

    /// The value after `prefix` in a `Key: value` line, or `null` when the line is something else.
    private static @Nullable String credentialValue(String line, String prefix) {
        return line.startsWith(prefix) ? line.substring(prefix.length()).trim() : null;
    }

    /// Writes the new password where both the panel and Octop's own documentation look for it.
    ///
    /// `octop-admin.txt` is the panel's record; `credential.txt` is Octop's (`<OCTOP_HOME>/credential.txt`,
    /// the file its entrypoint writes and its docs tell people to `cat`). No URL is written: the panel
    /// does not know which hostname the instance will be opened under, and a wrong one misleads.
    private static void writeAdminCredentials(Path home, String username, String password)
            throws IOException {
        Path file = home.resolve("octop-admin.txt");
        Files.writeString(file, "username: " + username + "\npassword: " + password + "\n",
                StandardCharsets.UTF_8);
        restrictToOwner(file);
        Path credential = home.resolve("credential.txt");
        Files.writeString(credential, "Octop Login Credential\n"
                + "======================\n"
                + "Username: " + username + "\n"
                + "Password: " + password + "\n\n"
                + "Rewritten by the hdsl-web panel's password reset.\n"
                + "A password changed in Octop's web console wins over this file.\n",
                StandardCharsets.UTF_8);
        restrictToOwner(credential);
    }

    private static void restrictToOwner(Path file) {
        try {
            Files.setPosixFilePermissions(file,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows has no POSIX modes; a volume that refuses the chmod is still confined to the
            // instance directory.
        }
    }

    /// Runs `octop user passwd <username> --password <pw>` with the instance's own environment.
    private static void resetOctopPassword(BrandInstance instance, Path brandRoot, Path home,
                                           String username, String password) throws IOException {
        Path packageDir = BrandInstaller.packageDir(Brand.OCTOP, brandRoot, instance.version());
        if (packageDir == null) {
            throw new IOException("Octop " + instance.version() + " is not installed");
        }
        List<String> command = new java.util.ArrayList<>(octopCommand(packageDir));
        command.addAll(List.of("user", "passwd", username, "--password", password));

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(home.toFile());
        builder.redirectErrorStream(true);
        builder.environment().put("OCTOP_HOME", home.toString());
        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (java.io.BufferedReader lines = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) {
                    synchronized (output) {
                        if (output.length() < 4000) {
                            output.append(line).append('\n');
                        }
                    }
                }
            } catch (IOException ignored) {
                // The exit code is what matters.
            }
        }, "brand-admin-password");
        reader.setDaemon(true);
        reader.start();
        boolean finished;
        try {
            finished = process.waitFor(90, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("octop user passwd was interrupted", e);
        }
        if (!finished) {
            process.descendants().forEach(java.lang.ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw new IOException("octop user passwd timed out");
        }
        String text;
        synchronized (output) {
            text = output.toString().trim();
        }
        if (process.exitValue() != 0) {
            throw new IOException("octop user passwd failed (exit " + process.exitValue() + "): "
                    + (text.isEmpty() ? "no output" : text));
        }
    }

    /// How to invoke Octop's CLI inside an installed release: the source build has an entry point, the
    /// portable archive ships an interpreter plus `launch.py`.
    private static List<String> octopCommand(Path packageDir) throws IOException {
        List<String> command = new java.util.ArrayList<>();
        Path sourceOctop = packageDir.resolve(".venv/bin/octop");
        if (Files.isRegularFile(sourceOctop)) {
            command.add(sourceOctop.toString());
            return command;
        }
        Path launch = packageDir.resolve("launch.py");
        if (!Files.isRegularFile(launch)) {
            throw new IOException("Octop launcher not found: " + launch);
        }
        command.add(BrandRuntime.octopPython(packageDir).toString());
        command.add(launch.toString());
        return command;
    }

    /// The first admin in Octop's own user table, via `octop --json user list`.
    ///
    /// Only used when no credential file records a username — Octop's setup wizard lets people
    /// rename the admin, and guessing `admin` would then be wrong. Any failure keeps the default
    /// rather than turning a read into an error.
    private static @Nullable String discoverAdminUsername(BrandInstance instance, Path brandRoot,
                                                          Path home) {
        try {
            Path packageDir = BrandInstaller.packageDir(Brand.OCTOP, brandRoot, instance.version());
            if (packageDir == null) {
                return null;
            }
            List<String> command = new java.util.ArrayList<>(octopCommand(packageDir));
            command.addAll(List.of("--json", "user", "list"));
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(home.toFile());
            builder.redirectErrorStream(true);
            builder.environment().put("OCTOP_HOME", home.toString());
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) {
                return null;
            }
            int start = output.indexOf('[');
            int end = output.lastIndexOf(']');
            if (start < 0 || end <= start) {
                return null;
            }
            JsonArray users = JsonParser.parseString(output.substring(start, end + 1)).getAsJsonArray();
            for (com.google.gson.JsonElement element : users) {
                JsonObject user = element.getAsJsonObject();
                String role = user.has("role") ? user.get("role").getAsString() : "";
                if ("admin".equals(role) && user.has("username")) {
                    return user.get("username").getAsString();
                }
            }
        } catch (IOException | RuntimeException e) {
            LOG.info("Could not list Octop users for " + instance.id() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    /// A 16-character password with letters and digits, for the "reset" without a typed value.
    private static String randomPassword() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        java.security.SecureRandom random = new java.security.SecureRandom();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            text.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return text.toString();
    }

    /// Reads a body when there is one; `null` otherwise (read requests may carry none).
    private static @Nullable JsonObject optionalBody(HttpServletRequest request) throws IOException {
        String text = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (text.isBlank()) {
            return null;
        }
        try {
            com.google.gson.JsonElement parsed = JsonParser.parseString(text);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String keyOf(HttpServletRequest request) {
        Object token = request.getAttribute(AuthFilter.SESSION_TOKEN_ATTRIBUTE);
        return org.jackhuang.hmcl.web.developer.DeveloperElevation.key(
                token instanceof String value ? value : null, request.getRemoteAddr());
    }

    private boolean sessionAlive(HttpServletRequest request) {
        Object token = request.getAttribute(AuthFilter.SESSION_TOKEN_ATTRIBUTE);
        return !(token instanceof String value) || value.isBlank() || auth.isAlive(value);
    }

    // --------------------------------------------------------------- response --

    private JsonObject instanceJson(Brand brand, BrandInstance instance, HttpServletRequest request) {
        BrandRuntime.Status status = BrandRuntime.status(instance.id());
        JsonObject body = new JsonObject();
        body.addProperty("id", instance.id());
        body.addProperty("brand", instance.brand());
        body.addProperty("name", instance.name());
        body.addProperty("version", instance.version());
        if (brand == Brand.CLAUDE_WEB) {
            body.addProperty("baseUrl", instance.baseUrl());
        }
        body.addProperty("lastPort", instance.lastPort());
        body.addProperty("createdAt", instance.createdAt());
        body.addProperty("state", status.state().name().toLowerCase(java.util.Locale.ROOT));
        body.addProperty("disabled", policy.disabled(brand, instance.id()));
        if (status.error() != null) {
            body.addProperty("error", status.error());
        }
        if (status.state() == BrandRuntime.State.RUNNING) {
            body.addProperty("url", openUrl(brand, instance, request));
        }
        return body;
    }

    /// Where the browser reaches a running instance: the panel's own mount —
    /// same origin, same certificate, same session gate as dsh. Path-routed
    /// clients (OpenCode) get the router shim injected there instead of an
    /// origin of their own.
    private String openUrl(Brand brand, BrandInstance instance, HttpServletRequest request) {
        return mountPath(config.basePath, brand, instance.id(), BrandRuntime.lockedOctopSurvivor());
    }

    /// The mount a browser should open for one instance.
    ///
    /// Normally the token mount `/i/<instanceId>/`. When the developer policy
    /// has switched the fixed `/i/octop/` alias on **and** this instance is its
    /// survivor, that alias is the URL to hand out — otherwise the "open"
    /// button would keep sending people to the token path and the fixed route
    /// would only ever be reachable by typing it.
    ///
    /// Package-private and pure so the two cases can be asserted without a
    /// running instance.
    static String mountPath(String basePath, Brand brand, String instanceId, @Nullable String lockedSurvivor) {
        // Browser-consumed URL: carries the mount base so a subpath-mounted
        // panel hands out /panel/i/<id>/ instead of a root path that 404s.
        if (brand == Brand.OCTOP && lockedSurvivor != null && lockedSurvivor.equals(instanceId)) {
            return basePath + "/i/octop/";
        }
        return basePath + "/i/" + instanceId + "/";
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
