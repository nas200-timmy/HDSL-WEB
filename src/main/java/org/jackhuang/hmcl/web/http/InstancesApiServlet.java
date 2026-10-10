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
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jackhuang.hmcl.dsh.DshBuildScripts;
import org.jackhuang.hmcl.dsh.DshException;
import org.jackhuang.hmcl.dsh.DshHomeMode;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshInstanceManager;
import org.jackhuang.hmcl.dsh.DshInstanceSettings;
import org.jackhuang.hmcl.dsh.DshLocalPlugins;
import org.jackhuang.hmcl.dsh.DshPackForge;
import org.jackhuang.hmcl.dsh.DshPluginInstaller;
import org.jackhuang.hmcl.dsh.DshPortMode;
import org.jackhuang.hmcl.dsh.DshPorts;
import org.jackhuang.hmcl.dsh.DshProcessManager;
import org.jackhuang.hmcl.dsh.DshVersionManager;
import org.jackhuang.hmcl.setting.GameDirectory;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.web.config.ServerConfig;
import org.jackhuang.hmcl.web.instance.InstanceRuntime;
import org.jackhuang.hmcl.web.task.TaskService;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/// The instance half of the REST surface, mapped at `/api/instances/*`:
///
/// - `GET    /api/instances`            — list
/// - `POST   /api/instances`            — create `{name, version, homeMode?, portMode?, port?, gameDirectoryId?, autoInstall?}`
///   and, unless `autoInstall` is false, start installing the version right
///   away: the answer carries the instance and `installTaskId`. A launcher's
///   "new instance" is meant to be run, not to sit as an empty manifest.
/// - `GET    /api/instances/{id}`       — detail, including `installProgress` and `account`
/// - `PATCH  /api/instances/{id}`       — edit `{name?, homeMode?, portMode?, port?, autoPort?, account?}`
/// - `DELETE /api/instances/{id}`       — remove (refused while running)
/// - `POST   /api/instances/{id}/install` — install a version, 202 `{taskId}`
/// - `POST   /api/instances/{id}/launch`  — start, 202 `{state}`; an instance
///   that is not installed yet is installed first and launched when pnpm is
///   done (202 `{state: "installing", taskId}`), unless `autoInstall` is false
/// - `POST   /api/instances/{id}/stop`    — stop the process tree, 202 `{state}`
/// - `GET    /api/instances/{id}/logs`    — the retained output, `?tail=N`
/// - `GET    /api/instances/{id}/open`    — the running URL with its token
/// - `GET    /api/instances/{id}/plugins`         — the profile's dependencies and bundles
/// - `POST   /api/instances/{id}/plugins/install` — `{specs: [...]}`, 202 `{taskId}`
/// - `POST   /api/instances/{id}/plugins/remove`  — `{specs: [...]}`, 202 `{taskId}`
/// - `POST   /api/instances/{id}/plugins/local`   — a `.tgz` upload (multipart field
///   `file`, at most 50 MiB), 202 `{taskId}`
/// - `POST   /api/instances/{id}/export`          — `{name?, includeSessions?}`, 202
///   `{taskId}`: a DSH-PackForge `.dspack` into `<HDSL_DATA>/exports/` (the
///   domain's credential filter decides what travels), the done task carries
///   `result: {filename}`
/// - `GET    /api/instances/{id}/sessions`        — the home's sessions, newest first
/// - `POST   /api/instances/{id}/sessions/export` — `{sessionIds: [...]}`, 202
///   `{taskId}`: a `.sspack` into `exports/`, `result: {filename}`
/// - `GET    /api/instances/{id}/workspaces`      — the sessions grouped the
///   harness's own way, by workspace slug
/// - `GET    /api/instances/{id}/skills`          — the home's skill packs
/// - `POST   /api/instances/{id}/skills/install`  — `{source}`, 202 `{taskId}`:
///   a GitHub `owner/repo` (optionally with a skill name) or a catalogue
///   query resolved through [org.jackhuang.hmcl.dsh.DshSkillSource]
/// - `DELETE /api/instances/{id}/skills/{skillId}` — remove one skill pack
///
/// Plugin installs that meet a build script they may not run park their task
/// in `waiting_approval`; `POST /api/tasks/{id}/approve` carries the decision.
///
/// Everything slow or stateful runs through [InstanceRuntime] on the task
/// pool; the handlers themselves only parse, validate and answer.
@NotNullByDefault
public final class InstancesApiServlet extends HttpServlet {

    private final InstanceRuntime runtime;
    private final TaskService tasks;
    private final ServerConfig config;

    public InstancesApiServlet(InstanceRuntime runtime, TaskService tasks, ServerConfig config) {
        this.runtime = runtime;
        this.tasks = tasks;
        this.config = config;
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String pathInfo = request.getPathInfo();
        String[] segments = split(pathInfo);
        try {
            if (segments.length == 0) {
                switch (request.getMethod()) {
                    case "GET" -> listInstances(response);
                    case "POST" -> createInstance(request, response);
                    default -> Json.error(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "method not allowed");
                }
                return;
            }
            String id = segments[0];
            if (segments.length == 1) {
                switch (request.getMethod()) {
                    case "GET" -> getInstance(response, id);
                    case "PATCH" -> updateInstance(request, response, id);
                    case "DELETE" -> deleteInstance(response, id);
                    default -> Json.error(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "method not allowed");
                }
                return;
            }
            switch (segments[1]) {
                case "install" -> install(request, response, id);
                case "launch" -> launch(request, response, id);
                case "stop" -> stop(response, id);
                case "logs" -> logs(request, response, id);
                case "open" -> open(response, id);
                case "plugins" -> plugins(request, response, id, segments);
                case "export" -> exportPack(request, response, id);
                case "sessions" -> sessions(request, response, id, segments);
                case "workspaces" -> workspaces(request, response, id);
                case "skills" -> skills(request, response, id, segments);
                default -> Json.error(response, HttpServletResponse.SC_NOT_FOUND, "not found");
            }
        } catch (DshException e) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ list --

    private void listInstances(HttpServletResponse response) throws IOException {
        JsonObject body = new JsonObject();
        JsonArray array = new JsonArray();
        for (DshInstance instance : DshInstanceManager.list()) {
            array.add(runtime.toJson(instance));
        }
        body.add("instances", array);
        Json.writePreservingNulls(response, body);
    }

    // ----------------------------------------------------------------- create --

    private void createInstance(HttpServletRequest request, HttpServletResponse response)
            throws IOException, DshException {
        JsonObject body = body(request, response);
        if (body == null) {
            return;
        }
        String name = stringField(body, "name");
        String version = stringField(body, "version");
        if (name == null || name.isBlank() || version == null || version.isBlank()) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "name and version are required");
            return;
        }

        DshHomeMode homeMode = homeMode(body);
        if (homeMode == null) {
            homeMode = DshHomeMode.ISOLATED;
        }
        if (homeMode == DshHomeMode.CUSTOM) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST,
                    "custom homes are not supported through the web API");
            return;
        }

        Path workspace = resolveDirectory(body).directory();

        String id = uniqueId(name);
        DshInstance instance = DshInstanceManager.create(id, version, DshInstance.DEFAULT_PROFILE,
                workspace, homeMode, null, List.of(), java.util.Map.of());

        DshPortMode portMode = portMode(body, DshPortMode.AUTO);
        Integer port = intField(body, "port");
        if (portMode == DshPortMode.FIXED) {
            if (port == null || port <= 0) {
                port = instance.portOrDefault();
            }
            if (!applyFixedPort(instance, port, response)) {
                return;
            }
            instance = DshInstanceManager.find(id);
            if (instance == null) {
                return;
            }
        }

        runtime.announceCreated(instance);

        JsonObject created = new JsonObject();
        created.add("instance", instanceBody(instance));
        // The launcher behaviour: "new instance" means "I want to run this", so
        // the install starts here instead of waiting for a second click on the
        // detail page. The instance already exists when this runs, so a failure
        // leaves it in place with the reason on its detail page.
        if (!Boolean.FALSE.equals(boolField(body, "autoInstall"))) {
            try {
                TaskService.Task task = submitInstall(instance, version, null);
                created.addProperty("installTaskId", task.id());
            } catch (TaskService.InstanceBusyException e) {
                // The instance is still created; only the head start is lost.
                org.jackhuang.hmcl.util.logging.Logger.LOG.warning(
                        "Could not auto-install " + id + ": " + e.getMessage());
            }
        }
        Json.writePreservingNulls(response, HttpServletResponse.SC_CREATED, created);
    }

    /// Applies a fixed-port choice, answering 4xx through `response` when the
    /// port is unusable. Returns whether the choice was persisted.
    private boolean applyFixedPort(DshInstance instance, int port, HttpServletResponse response)
            throws IOException, DshException {
        String complaint = DshPorts.validate(port);
        if (complaint != null) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, complaint);
            return false;
        }
        if (!DshPorts.isFree(port)) {
            Json.error(response, HttpServletResponse.SC_CONFLICT, "port " + port + " is already in use");
            return false;
        }
        DshInstanceManager.update(DshPorts.withMode(instance, DshPortMode.FIXED)
                .withPortPolicy(DshPortMode.FIXED, port));
        return true;
    }

    // ------------------------------------------------------------------ detail --

    private void getInstance(HttpServletResponse response, String id) throws IOException {
        DshInstance instance = DshInstanceManager.find(id);
        if (instance == null) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "instance not found");
            return;
        }
        JsonObject body = instanceBody(instance);
        body.add("installProgress", runtime.installProgress(id));
        Json.writePreservingNulls(response, body);
    }

    private void updateInstance(HttpServletRequest request, HttpServletResponse response, String id)
            throws IOException, DshException {
        JsonObject body = body(request, response);
        if (body == null) {
            return;
        }
        DshInstance instance = DshInstanceManager.find(id);
        if (instance == null) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "instance not found");
            return;
        }
        String state = runtime.stateOf(id);
        if (state.equals("RUNNING") || state.equals("STARTING") || state.equals("STOPPING")) {
            Json.error(response, HttpServletResponse.SC_CONFLICT, "instance is running");
            return;
        }

        String name = stringField(body, "name");
        if (name != null && !name.isBlank() && !name.equals(instance.id())) {
            // Renaming moves the instance directory; an install is writing into
            // it right now, so the rename would pull the ground out from under
            // pnpm. Other edits are fine while pnpm runs.
            if (state.equals("INSTALLING")) {
                Json.error(response, HttpServletResponse.SC_CONFLICT,
                        "install in progress; rename once it is done");
                return;
            }
            instance = DshInstanceManager.rename(id, uniqueId(name));
            runtime.forget(id);
        }

        DshHomeMode homeMode = homeMode(body);
        if (homeMode == DshHomeMode.CUSTOM) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST,
                    "custom homes are not supported through the web API");
            return;
        }
        if (homeMode != null) {
            instance = instance.withHome(homeMode, null);
        }

        // The instance's account: the `name` of an entry of `/api/accounts`,
        // or null to launch with none. Written to the instance's own settings
        // file; the launch injection itself is DshLauncher's.
        if (body.has("account")) {
            com.google.gson.JsonElement element = body.get("account");
            if (element.isJsonNull() || (element.isJsonPrimitive() && element.getAsString().isBlank())) {
                DshInstanceSettings.setAccountKey(instance, null);
            } else if (element.isJsonPrimitive()) {
                String key = element.getAsString().trim();
                boolean known = false;
                for (org.jackhuang.hmcl.dsh.DshAccount account
                        : SettingsManager.settings().getAccounts()) {
                    if (account.matchesKey(key)) {
                        known = true;
                        break;
                    }
                }
                if (!known) {
                    Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "unknown account: " + key);
                    return;
                }
                DshInstanceSettings.setAccountKey(instance, key);
            } else {
                Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "account must be a string or null");
                return;
            }
        }

        Boolean autoPort = boolField(body, "autoPort");
        DshPortMode portMode = portMode(body, autoPort != null && autoPort ? DshPortMode.AUTO : null);
        if (autoPort != null && autoPort) {
            instance = DshPorts.withMode(instance, DshPortMode.AUTO);
        } else if (portMode == DshPortMode.FIXED) {
            Integer port = intField(body, "port");
            if (port != null) {
                String complaint = DshPorts.validate(port);
                if (complaint != null) {
                    Json.error(response, HttpServletResponse.SC_BAD_REQUEST, complaint);
                    return;
                }
                if (!DshPorts.isFree(port)) {
                    Json.error(response, HttpServletResponse.SC_CONFLICT, "port " + port + " is already in use");
                    return;
                }
                instance = DshPorts.withMode(instance, DshPortMode.FIXED).withPortPolicy(DshPortMode.FIXED, port);
            } else {
                instance = DshPorts.withMode(instance, DshPortMode.FIXED);
            }
        } else if (portMode == DshPortMode.AUTO) {
            instance = DshPorts.withMode(instance, DshPortMode.AUTO);
        }

        DshInstanceManager.update(instance);
        runtime.announceUpdated(instance);
        JsonObject updated = new JsonObject();
        updated.add("instance", instanceBody(instance));
        Json.writePreservingNulls(response, updated);
    }

    private void deleteInstance(HttpServletResponse response, String id) throws IOException, DshException {
        DshInstance instance = DshInstanceManager.find(id);
        if (instance == null) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "instance not found");
            return;
        }
        String state = runtime.stateOf(id);
        if (state.equals("RUNNING") || state.equals("STARTING") || state.equals("STOPPING")) {
            Json.error(response, HttpServletResponse.SC_CONFLICT, "instance is running");
            return;
        }
        if (tasks.activeInstallLike(id).isPresent()) {
            Json.error(response, HttpServletResponse.SC_CONFLICT, "install in progress");
            return;
        }
        DshInstanceManager.delete(id);
        runtime.forget(id);
        runtime.announceDeleted(instance);
        Json.write(response, java.util.Map.of("ok", true));
    }

    // ---------------------------------------------------------------- install --

    /// How many lines of pnpm output a failure message carries.
    private static final int INSTALL_TAIL_LINES = 20;

    private void install(HttpServletRequest request, HttpServletResponse response, String id)
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
        DshInstance instance = DshInstanceManager.find(id);
        if (instance == null) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "instance not found");
            return;
        }
        String state = runtime.stateOf(id);
        if (state.equals("RUNNING") || state.equals("STARTING") || state.equals("STOPPING")) {
            Json.error(response, HttpServletResponse.SC_CONFLICT,
                    "stop the instance before changing its version");
            return;
        }
        if (version.equals(instance.version()) && DshVersionManager.isInstalled(instance)) {
            Json.error(response, HttpServletResponse.SC_CONFLICT, "已安装该版本");
            return;
        }
        try {
            TaskService.Task task = submitInstall(instance, version, null);
            JsonObject result = new JsonObject();
            result.addProperty("taskId", task.id());
            Json.write(response, HttpServletResponse.SC_ACCEPTED, result);
        } catch (TaskService.InstanceBusyException e) {
            Json.error(response, HttpServletResponse.SC_CONFLICT, e.getMessage());
        }
    }

    /// Submits the install task every entry point shares: pnpm runs on the
    /// task pool, its output drives the task's progress, and the manifest is
    /// only moved to `version` after the disk agrees that is what was
    /// installed. The last output lines ride along with a failure, so the
    /// reason survives even when the registry is what misbehaved.
    ///
    /// @param instance    the instance
    /// @param version     the version to install
    /// @param onInstalled run on success, still inside the task — the launch
    ///                    path uses it to start what it has just installed;
    ///                    may be null
    /// @return the submitted task
    /// @throws TaskService.InstanceBusyException when an install is already in flight
    private TaskService.Task submitInstall(DshInstance instance, String version,
                                          @Nullable java.util.function.Consumer<DshInstance> onInstalled) {
        String id = instance.id();
        TaskService.Task[] holder = new TaskService.Task[1];
        java.util.ArrayDeque<String> tail = new java.util.ArrayDeque<>();
        TaskService.Task task = tasks.submit("install", id, () -> {
            org.jackhuang.hmcl.dsh.DshInstallProgress progress =
                    new org.jackhuang.hmcl.dsh.DshInstallProgress();
            // The holder lets the progress listener reach the task whose
            // submission created this lambda; the tail is what a failure will
            // be explained with.
            progress.addListener(p -> {
                if (p.getMessage() != null && !p.getMessage().isBlank()) {
                    if (tail.size() >= INSTALL_TAIL_LINES) {
                        tail.removeFirst();
                    }
                    tail.addLast(p.getMessage().trim());
                }
                // The task may still be in flight to the pool while the first
                // line arrives; a missing holder just means nobody to tell yet.
                TaskService.Task owner = holder[0];
                if (owner != null) {
                    owner.update(p.getMessage(), p.getFraction());
                }
            });
            try {
                DshVersionManager.install(instance, version, progress::accept);
            } catch (DshException | RuntimeException e) {
                throw failedInstall(id, version, tail, e.getMessage(), e);
            }
            // pnpm succeeded — but the manifest must describe the disk, not the
            // request. A successful run that landed something else (a registry
            // serving a different tarball, a store gone strange) is recorded as
            // what it is and reported as a failure: claiming the requested
            // version here is exactly how an instance comes to lie about what
            // it runs.
            String actual = installedVersion(instance);
            if (actual == null) {
                throw failedInstall(id, version, tail,
                        "pnpm reported success, but the installed version could not be read back", null);
            }
            DshInstance installed = instance;
            if (!version.equals(actual)) {
                installed = instance.withVersion(actual);
                DshInstanceManager.update(installed);
                runtime.announceUpdated(installed);
                throw failedInstall(id, version, tail,
                        "pnpm installed " + actual + " instead of the requested version; "
                                + "the instance now records " + actual, null);
            }
            if (!version.equals(instance.version())) {
                installed = instance.withVersion(version);
                DshInstanceManager.update(installed);
                runtime.announceUpdated(installed);
            }
            org.jackhuang.hmcl.util.logging.Logger.LOG.info("Installed DSH " + version + " for " + id);
            if (onInstalled != null) {
                onInstalled.accept(installed);
            }
            return "Installed " + version;
        });
        holder[0] = task;
        // The task is registered by now, so stateOf() answers INSTALLING;
        // telling the panel right away saves it a poll.
        runtime.announceState(id);
        return task;
    }

    /// Builds the failure an install task ends with: the reason, the last lines
    /// pnpm printed, and a line in the container log — the task table lives in
    /// memory and would take the explanation with it on a restart.
    private static DshException failedInstall(String id, String version,
                                              java.util.ArrayDeque<String> tail,
                                              @Nullable String reason, @Nullable Throwable cause) {
        String detail = "Installing DSH " + version + " for " + id + " failed: " + reason
                + registryHint(reason + "\n" + String.join("\n", tail))
                + (tail.isEmpty() ? "" : "\n" + String.join("\n", tail));
        org.jackhuang.hmcl.util.logging.Logger.LOG.warning(detail);
        return new DshException(detail, cause);
    }

    /// A package pnpm could not find, as pnpm itself names it.
    private static final java.util.regex.Pattern MISSING_PACKAGE =
            java.util.regex.Pattern.compile("([@a-zA-Z0-9._/-]+) is not in the npm registry");

    /// Turns the two endings that are nobody's fault into one line of advice,
    /// placed before the raw output: a dependency the upstream unpublished (the
    /// version can never be installed again — the panel's own version list
    /// cannot see this, because it only checks the companion package) and a
    /// requirement the registry cannot satisfy. Both read as "retry" otherwise,
    /// and retrying is exactly what does not help.
    ///
    /// @param output the failure reason plus pnpm's tail
    /// @return the hint line, or an empty string when the ending needs none
    static String registryHint(String output) {
        java.util.regex.Matcher missing = MISSING_PACKAGE.matcher(output);
        if (output.contains("ERR_PNPM_FETCH_404") && missing.find()) {
            return "\n[hint] registry 上已没有 " + missing.group(1)
                    + "：该版本依赖的包被上游删除，这个 dsh 版本再也装不上（不是本地问题，重试无用）——请换一个版本。";
        }
        if (output.contains("ERR_PNPM_NO_MATCHING_VERSION")) {
            return "\n[hint] 该版本要求的配套包在 registry 上没有对应版本（上游没发布这一版）——请换一个版本。";
        }
        return "";
    }

    /// The version of the harness sitting in an instance's own `dsh`
    /// directory, or null when it cannot be read.
    private static @Nullable String installedVersion(DshInstance instance) {
        try {
            Path manifest = instance.dshDirectory()
                    .resolve(org.jackhuang.hmcl.dsh.DshVersion.PACKAGE_PATH)
                    .resolve("package.json");
            if (!Files.isRegularFile(manifest)) {
                return null;
            }
            com.google.gson.JsonElement parsed =
                    JsonParser.parseString(Files.readString(manifest, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                return null;
            }
            return stringField(parsed.getAsJsonObject(), "version");
        } catch (DshException | IOException | JsonParseException | IllegalStateException e) {
            return null;
        }
    }

    // ----------------------------------------------------------------- launch --

    private void launch(HttpServletRequest request, HttpServletResponse response, String id)
            throws IOException {
        JsonObject body = body(request, response);
        if (body == null) {
            return;
        }
        DshInstance instance = DshInstanceManager.find(id);
        if (instance == null) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "instance not found");
            return;
        }
        String state = runtime.stateOf(id);
        if (state.equals("STOPPING")) {
            Json.error(response, HttpServletResponse.SC_CONFLICT, "instance is stopping");
            return;
        }
        if (state.equals("RUNNING") || state.equals("STARTING")) {
            Json.write(response, HttpServletResponse.SC_ACCEPTED, java.util.Map.of("state", state.toLowerCase(Locale.ROOT)));
            return;
        }
        if (!DshVersionManager.isInstalled(instance)) {
            if (Boolean.FALSE.equals(boolField(body, "autoInstall"))) {
                Json.error(response, HttpServletResponse.SC_CONFLICT, "not installed");
                return;
            }
            if (state.equals("INSTALLING")) {
                Json.error(response, HttpServletResponse.SC_CONFLICT, "install in progress");
                return;
            }
            // Launcher behaviour: "start this" on a version that is not here
            // yet installs it and starts it — in one move, without a second
            // click. The whole exchange is one task, so the panel's progress
            // view covers both halves.
            String host = resolvePublicHost(body, request);
            try {
                TaskService.Task task = submitInstall(instance, instance.version(),
                        installed -> runtime.launchAsync(installed, host));
                JsonObject result = new JsonObject();
                result.addProperty("state", "installing");
                result.addProperty("taskId", task.id());
                Json.write(response, HttpServletResponse.SC_ACCEPTED, result);
            } catch (TaskService.InstanceBusyException e) {
                Json.error(response, HttpServletResponse.SC_CONFLICT, e.getMessage());
            }
            return;
        }
        String publicHost = resolvePublicHost(body, request);
        runtime.launchAsync(instance, publicHost);
        Json.write(response, HttpServletResponse.SC_ACCEPTED, java.util.Map.of("state", "starting"));
    }

    /// The host handed to dsh as `--trusted-host`: the request body first,
    /// then `server.yaml`'s `public_base_url`, then the request's own Host
    /// header — all reduced to the bare hostname, a level whose value does not
    /// parse falling through to the next.
    private @Nullable String resolvePublicHost(JsonObject body, HttpServletRequest request) {
        String fromBody = stringField(body, "publicHost");
        if (fromBody != null && !fromBody.isBlank()) {
            String host = hostOf(fromBody);
            if (host != null) {
                return host;
            }
        }
        if (config.publicBaseUrl != null && !config.publicBaseUrl.isBlank()) {
            String host = hostOf(config.publicBaseUrl);
            if (host != null) {
                return host;
            }
        }
        return HostHeaders.stripPort(request.getHeader("Host"));
    }

    // ------------------------------------------------------------------- stop --

    private void stop(HttpServletResponse response, String id) throws IOException {
        DshInstance instance = DshInstanceManager.find(id);
        if (instance == null) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "instance not found");
            return;
        }
        if (runtime.runningProcess(id).isEmpty() && !DshProcessManager.isStopping(id)) {
            Json.error(response, HttpServletResponse.SC_CONFLICT, "instance not running");
            return;
        }
        runtime.stopAsync(id);
        Json.write(response, HttpServletResponse.SC_ACCEPTED, java.util.Map.of("state", "stopping"));
    }

    // ------------------------------------------------------------------- logs --

    private void logs(HttpServletRequest request, HttpServletResponse response, String id) throws IOException {
        DshInstance instance = DshInstanceManager.find(id);
        if (instance == null) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "instance not found");
            return;
        }
        int tail = 200;
        String tailParam = request.getParameter("tail");
        if (tailParam != null) {
            try {
                tail = Integer.parseInt(tailParam);
            } catch (NumberFormatException ignored) {
                // The default stands for an unparsable bound.
            }
        }
        tail = Math.max(1, Math.min(2000, tail));
        JsonObject body = new JsonObject();
        body.add("lines", runtime.logs(id, tail));
        Json.write(response, body);
    }

    // -------------------------------------------------------------------- open --

    private void open(HttpServletResponse response, String id) throws IOException {
        DshInstance instance = DshInstanceManager.find(id);
        if (instance == null) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "instance not found");
            return;
        }
        var url = runtime.openUrl(id);
        if (url.isEmpty()) {
            Json.error(response, HttpServletResponse.SC_CONFLICT, "instance not running");
            return;
        }
        // The URL is consumed by the browser, so it carries the mount base:
        // a subpath-mounted panel must hand out /panel/i/<id>/, not /i/<id>/.
        Json.write(response, java.util.Map.of("url", config.basePath + url.get()));
    }

    // ---------------------------------------------------------------- plugins --

    /// Routes under `/api/instances/{id}/plugins`.
    private void plugins(HttpServletRequest request, HttpServletResponse response, String id,
                         String[] segments) throws IOException, DshException {
        if (segments.length == 2) {
            if ("GET".equals(request.getMethod())) {
                listPlugins(response, id);
            } else {
                Json.error(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "method not allowed");
            }
            return;
        }
        if (segments.length != 3 || !"POST".equals(request.getMethod())) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "not found");
            return;
        }
        switch (segments[2]) {
            case "install" -> installPlugins(request, response, id);
            case "remove" -> removePlugins(request, response, id);
            case "local" -> uploadLocalPlugin(request, response, id);
            default -> Json.error(response, HttpServletResponse.SC_NOT_FOUND, "not found");
        }
    }

    /// The profile's declared plugins: every dependency, whether it made the
    /// bundle list, and whether its build script is still unanswered (`pending`).
    private void listPlugins(HttpServletResponse response, String id) throws IOException, DshException {
        DshInstance instance = DshInstanceManager.find(id);
        if (instance == null) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "instance not found");
            return;
        }
        java.nio.file.Path home = instance.homeDirectory();
        java.util.Map<String, String> dependencies =
                DshPluginInstaller.readDependencies(home, instance.profile());
        java.util.List<String> bundles = DshPluginInstaller.readBundles(home, instance.profile());
        java.util.List<String> pending = DshBuildScripts.unanswered(instance);

        JsonArray plugins = new JsonArray();
        for (java.util.Map.Entry<String, String> dependency : dependencies.entrySet()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", dependency.getKey());
            entry.addProperty("version", dependency.getValue());
            if (bundles.contains(dependency.getKey())) {
                entry.addProperty("bundled", true);
            }
            if (pending.contains(dependency.getKey())) {
                entry.addProperty("pending", true);
            }
            plugins.add(entry);
        }
        JsonArray bundleArray = new JsonArray();
        for (String bundle : bundles) {
            bundleArray.add(bundle);
        }
        JsonObject body = new JsonObject();
        body.add("plugins", plugins);
        body.add("bundles", bundleArray);
        Json.write(response, body);
    }

    /// Reports a progress line to the task whose submission created the listener.
    ///
    /// [TaskService.submit] hands the work to its executor before it returns the
    /// handle, so an installer's first line can arrive while `holder[0]` is
    /// still empty. That is a recipient not yet there, not a failure — the same
    /// window [submitInstall] guards on its own listener.
    ///
    /// Package-private so the empty-holder case can be driven on purpose; the
    /// race itself is not something a test can be made to lose on demand.
    ///
    /// @param holder the one-slot holder the submitter fills in
    /// @param line   the line to report
    static void report(TaskService.Task[] holder, String line) {
        TaskService.Task owner = holder[0];
        if (owner != null) {
            owner.update(line, -1);
        }
    }

    /// Installs package specs as a task: `dsh plugin add` per spec, with the
    /// build-script approval dance when a package wants to run code.
    private void installPlugins(HttpServletRequest request, HttpServletResponse response, String id)
            throws IOException, DshException {
        JsonObject body = body(request, response);
        if (body == null) {
            return;
        }
        java.util.List<String> specs = specsField(body);
        if (specs == null || specs.isEmpty()) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "specs must be a non-empty array");
            return;
        }
        DshInstance instance = DshInstanceManager.find(id);
        if (instance == null) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "instance not found");
            return;
        }
        if (!DshVersionManager.isInstalled(instance)) {
            Json.error(response, HttpServletResponse.SC_CONFLICT, "not installed");
            return;
        }
        try {
            TaskService.Task[] holder = new TaskService.Task[1];
            TaskService.Task task = tasks.submit("plugin-install", id, () -> {
                DshPluginInstaller.installSpecs(instance, specs, line -> report(holder, line));
                return "Installed " + specs.size() + " plugin(s)";
            }, (allow, keys) -> DshBuildScripts.answer(instance, keys, allow));
            holder[0] = task;
            accepted(response, task);
        } catch (TaskService.InstanceBusyException e) {
            Json.error(response, HttpServletResponse.SC_CONFLICT, e.getMessage());
        }
    }

    /// Removes package specs as a task: one `dsh plugin remove` for all of them.
    private void removePlugins(HttpServletRequest request, HttpServletResponse response, String id)
            throws IOException, DshException {
        JsonObject body = body(request, response);
        if (body == null) {
            return;
        }
        java.util.List<String> specs = specsField(body);
        if (specs == null || specs.isEmpty()) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "specs must be a non-empty array");
            return;
        }
        DshInstance instance = DshInstanceManager.find(id);
        if (instance == null) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "instance not found");
            return;
        }
        if (!DshVersionManager.isInstalled(instance)) {
            Json.error(response, HttpServletResponse.SC_CONFLICT, "not installed");
            return;
        }
        try {
            TaskService.Task[] holder = new TaskService.Task[1];
            TaskService.Task task = tasks.submit("plugin-remove", id, () -> {
                DshPluginInstaller.removeSpecs(instance, specs, line -> report(holder, line));
                return "Removed " + specs.size() + " plugin(s)";
            }, (allow, keys) -> DshBuildScripts.answer(instance, keys, allow));
            holder[0] = task;
            accepted(response, task);
        } catch (TaskService.InstanceBusyException e) {
            Json.error(response, HttpServletResponse.SC_CONFLICT, e.getMessage());
        }
    }

    /// Installs an uploaded `.tgz` as a task. The file is first stored as the
    /// instance's own copy — the name [DshLocalPlugins] would give it — so the
    /// profile's recorded dependency points at a file the instance keeps.
    private void uploadLocalPlugin(HttpServletRequest request, HttpServletResponse response, String id)
            throws IOException, DshException {
        DshInstance instance = DshInstanceManager.find(id);
        if (instance == null) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "instance not found");
            return;
        }
        if (!DshVersionManager.isInstalled(instance)) {
            Json.error(response, HttpServletResponse.SC_CONFLICT, "not installed");
            return;
        }
        jakarta.servlet.http.Part part;
        try {
            part = request.getPart("file");
        } catch (jakarta.servlet.ServletException | IllegalStateException e) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST,
                    "expected a multipart form with a `file` field: " + e.getMessage());
            return;
        }
        if (part == null || part.getSize() == 0) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "a `file` field is required");
            return;
        }
        String submitted = part.getSubmittedFileName() == null ? "plugin.tgz" : part.getSubmittedFileName();
        String lower = submitted.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".tgz") && !lower.endsWith(".tar.gz")) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST,
                    submitted + " is not a packed plugin (.tgz)");
            return;
        }

        // Inspect from a staging file, then move into the instance under the
        // name DshLocalPlugins would give the copy — the install then finds the
        // file already in place and records the instance's own path.
        Path staging = config.dataDir.resolve("tmp").resolve(
                "upload-" + java.util.UUID.randomUUID() + ".tgz");
        DshLocalPlugins.Package pkg;
        try {
            java.nio.file.Files.createDirectories(staging.getParent());
            try (java.io.InputStream in = part.getInputStream()) {
                java.nio.file.Files.copy(in, staging);
            }
            pkg = DshLocalPlugins.inspect(staging);
        } catch (DshException e) {
            java.nio.file.Files.deleteIfExists(staging);
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
            return;
        }
        Path directory = instance.instanceDirectory().resolve(DshLocalPlugins.DIRECTORY);
        Path target = directory.resolve(safeFileName(pkg.name()) + "-" + safeFileName(pkg.version()) + ".tgz");
        java.nio.file.Files.createDirectories(directory);
        java.nio.file.Files.move(staging, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        try {
            TaskService.Task[] holder = new TaskService.Task[1];
            TaskService.Task task = tasks.submit("plugin-local", id, () -> {
                DshLocalPlugins.install(instance, target, line -> report(holder, line));
                return "Installed " + pkg.name() + " " + pkg.version();
            }, (allow, keys) -> DshBuildScripts.answer(instance, keys, allow));
            holder[0] = task;
            accepted(response, task);
        } catch (TaskService.InstanceBusyException e) {
            Json.error(response, HttpServletResponse.SC_CONFLICT, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ export --

    /// Exports the instance as a DSH-PackForge `.dspack` into
    /// `<HDSL_DATA>/exports/`. The domain's scan decides what travels —
    /// credentials, keys, tokens and nested archives never do — and when
    /// `includeSessions` is set the conversations are written beside the
    /// container in the domain's own session-pack layout.
    private void exportPack(HttpServletRequest request, HttpServletResponse response, String id)
            throws IOException, DshException {
        if (!"POST".equals(request.getMethod())) {
            Json.error(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "method not allowed");
            return;
        }
        JsonObject body = body(request, response);
        if (body == null) {
            return;
        }
        DshInstance instance = DshInstanceManager.find(id);
        if (instance == null) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "instance not found");
            return;
        }
        // The harness writes the profile tree on its first boot, so an instance
        // that was installed but never launched has none yet. That is not an
        // error: the profile is materialised here so the export carries what
        // exists (the pinned version, an empty plugin list), matching what
        // launching and the plugin endpoints consider "installed".
        if (!DshVersionManager.isInstalled(instance)) {
            Json.error(response, HttpServletResponse.SC_CONFLICT, "not installed");
            return;
        }
        Path profileDirectory = instance.homeDirectory().resolve("profiles").resolve(instance.profile());
        try {
            Files.createDirectories(profileDirectory);
        } catch (IOException e) {
            Json.error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "could not prepare the profile directory: " + e.getMessage());
            return;
        }
        String name = stringField(body, "name");
        Boolean includeSessions = boolField(body, "includeSessions");
        String base = name != null && !name.isBlank() ? name : instance.id();
        boolean withSessions = includeSessions != null && includeSessions;
        try {
            TaskService.Task[] holder = new TaskService.Task[1];
            TaskService.Task task = tasks.submit("pack-export", id, () -> {
                java.util.function.Consumer<String> report = line -> {
                    TaskService.Task live = holder[0];
                    if (live != null) {
                        live.update(line, -1);
                    }
                };
                Path dir = PackExports.ensureDirectory(config.dataDir.resolve("exports"));
                Path target = PackExports.uniqueFile(dir, base, ".dspack");
                DshPackForge.Options options = new DshPackForge.Options(
                        DshPackForge.Options.kebab(base), "1.0.0", base, "", "");
                java.util.List<org.jackhuang.hmcl.dsh.DshSession> sessions = withSessions
                        ? org.jackhuang.hmcl.dsh.DshSessions.list(instance.homeDirectory())
                        : java.util.List.of();
                if (sessions.isEmpty()) {
                    DshPackForge.export(instance, target, options, report);
                } else {
                    // PackForge writes no sessions, and a pack carrying
                    // conversations lays them out the session-pack way — same
                    // entries, same rules — so the container is written first
                    // and the session entries are appended beside it.
                    Path building = target.resolveSibling(target.getFileName() + ".building");
                    try {
                        DshPackForge.export(instance, building, options, report);
                        appendSessions(building, target, instance, sessions, report);
                    } finally {
                        Files.deleteIfExists(building);
                    }
                }
                PackExports.privateFile(target);
                JsonObject result = new JsonObject();
                result.addProperty("filename", target.getFileName().toString());
                holder[0].attachResult(result);
                return "Exported " + target.getFileName();
            });
            holder[0] = task;
            accepted(response, task);
        } catch (TaskService.InstanceBusyException e) {
            Json.error(response, HttpServletResponse.SC_CONFLICT, e.getMessage());
        }
    }

    /// Copies a finished `.dspack` and writes the sessions' entries (logs,
    /// projection cache, the attachments they name) after it, which is the
    /// layout [org.jackhuang.hmcl.dsh.DshSessionPacks] reads them back from —
    /// a modpack carries conversations exactly this way.
    private static void appendSessions(Path source, Path target, DshInstance instance,
                                       java.util.List<org.jackhuang.hmcl.dsh.DshSession> sessions,
                                       java.util.function.Consumer<String> report)
            throws IOException, DshException {
        try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(Files.newInputStream(source));
             java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(Files.newOutputStream(target))) {
            java.util.zip.ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                out.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));
                in.transferTo(out);
                out.closeEntry();
            }
            Path home = instance.homeDirectory();
            org.jackhuang.hmcl.dsh.DshSessionPacks.writeInto(out, home, sessions, report);
            org.jackhuang.hmcl.dsh.DshSessionPacks.writeAttachmentsInto(out, home, sessions, report);
        }
    }

    // --------------------------------------------------------------- sessions --

    /// Routes under `/api/instances/{id}/sessions`.
    private void sessions(HttpServletRequest request, HttpServletResponse response, String id,
                          String[] segments) throws IOException, DshException {
        if (segments.length == 2) {
            if (!"GET".equals(request.getMethod())) {
                Json.error(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "method not allowed");
                return;
            }
            listSessions(response, id);
            return;
        }
        if (segments.length == 3 && "export".equals(segments[2])) {
            if (!"POST".equals(request.getMethod())) {
                Json.error(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "method not allowed");
                return;
            }
            exportSessions(request, response, id);
            return;
        }
        Json.error(response, HttpServletResponse.SC_NOT_FOUND, "not found");
    }

    /// The home's sessions, newest first. `messageCount` would mean decoding
    /// every (compressed) log, which the domain layer deliberately never
    /// does, so the count is absent rather than expensive.
    private void listSessions(HttpServletResponse response, String id) throws IOException, DshException {
        DshInstance instance = DshInstanceManager.find(id);
        if (instance == null) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "instance not found");
            return;
        }
        JsonArray sessions = new JsonArray();
        for (org.jackhuang.hmcl.dsh.DshSession session
                : org.jackhuang.hmcl.dsh.DshSessions.list(instance.homeDirectory())) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", session.id());
            if (session.title() != null) {
                entry.addProperty("title", session.title());
            }
            entry.addProperty("workspaceSlug", session.workspaceSlug());
            entry.addProperty("updatedAt", session.modifiedAt());
            entry.addProperty("sizeBytes", session.sizeBytes());
            if (session.locked()) {
                entry.addProperty("locked", true);
            }
            sessions.add(entry);
        }
        JsonObject body = new JsonObject();
        body.add("sessions", sessions);
        Json.write(response, body);
    }

    /// Packs the chosen sessions into a `.sspack` under `exports/`.
    private void exportSessions(HttpServletRequest request, HttpServletResponse response, String id)
            throws IOException, DshException {
        JsonObject body = body(request, response);
        if (body == null) {
            return;
        }
        java.util.List<String> wanted = stringArrayField(body, "sessionIds");
        if (wanted == null || wanted.isEmpty()) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST,
                    "sessionIds must be a non-empty array");
            return;
        }
        DshInstance instance = DshInstanceManager.find(id);
        if (instance == null) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "instance not found");
            return;
        }
        java.util.List<org.jackhuang.hmcl.dsh.DshSession> all =
                org.jackhuang.hmcl.dsh.DshSessions.list(instance.homeDirectory());
        java.util.List<org.jackhuang.hmcl.dsh.DshSession> chosen = new java.util.ArrayList<>();
        for (String wantedId : wanted) {
            org.jackhuang.hmcl.dsh.DshSession found = null;
            for (org.jackhuang.hmcl.dsh.DshSession session : all) {
                if (session.id().equals(wantedId)) {
                    found = session;
                    break;
                }
            }
            if (found == null) {
                Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "unknown session: " + wantedId);
                return;
            }
            chosen.add(found);
        }
        try {
            TaskService.Task[] holder = new TaskService.Task[1];
            TaskService.Task task = tasks.submit("session-export", id, () -> {
                java.util.function.Consumer<String> report = line -> {
                    TaskService.Task live = holder[0];
                    if (live != null) {
                        live.update(line, -1);
                    }
                };
                Path dir = PackExports.ensureDirectory(config.dataDir.resolve("exports"));
                Path target = PackExports.uniqueFile(dir, instance.id() + "-sessions", ".sspack");
                org.jackhuang.hmcl.dsh.DshSessionPacks.export(instance, chosen, target, report);
                PackExports.privateFile(target);
                JsonObject result = new JsonObject();
                result.addProperty("filename", target.getFileName().toString());
                holder[0].attachResult(result);
                return "Exported " + chosen.size() + " session(s) to " + target.getFileName();
            });
            holder[0] = task;
            accepted(response, task);
        } catch (TaskService.InstanceBusyException e) {
            Json.error(response, HttpServletResponse.SC_CONFLICT, e.getMessage());
        }
    }

    // ------------------------------------------------------------- workspaces --

    /// The sessions grouped the harness's own way: one workspace per slug
    /// directory, named by the working directory its sessions recorded.
    private void workspaces(HttpServletRequest request, HttpServletResponse response, String id)
            throws IOException, DshException {
        if (!"GET".equals(request.getMethod())) {
            Json.error(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "method not allowed");
            return;
        }
        DshInstance instance = DshInstanceManager.find(id);
        if (instance == null) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "instance not found");
            return;
        }
        JsonArray workspaces = new JsonArray();
        for (org.jackhuang.hmcl.dsh.DshWorkspace workspace
                : org.jackhuang.hmcl.dsh.DshSessions.workspaces(instance.homeDirectory())) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", workspace.slug());
            entry.addProperty("name", workspace.title());
            if (workspace.path() != null) {
                entry.addProperty("path", workspace.path());
            }
            entry.addProperty("updatedAt", workspace.modifiedAt());
            entry.addProperty("sessionCount", workspace.sessions().size());
            workspaces.add(entry);
        }
        JsonObject body = new JsonObject();
        body.add("workspaces", workspaces);
        Json.write(response, body);
    }

    // ----------------------------------------------------------------- skills --

    /// Routes under `/api/instances/{id}/skills`.
    private void skills(HttpServletRequest request, HttpServletResponse response, String id,
                        String[] segments) throws IOException, DshException {
        if (segments.length == 2) {
            if (!"GET".equals(request.getMethod())) {
                Json.error(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "method not allowed");
                return;
            }
            listSkills(response, id);
            return;
        }
        if (segments.length == 3 && "install".equals(segments[2])) {
            if (!"POST".equals(request.getMethod())) {
                Json.error(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "method not allowed");
                return;
            }
            installSkill(request, response, id);
            return;
        }
        if (segments.length == 3 && "DELETE".equals(request.getMethod())) {
            removeSkill(response, id, segments[2]);
            return;
        }
        Json.error(response, HttpServletResponse.SC_NOT_FOUND, "not found");
    }

    private void listSkills(HttpServletResponse response, String id) throws IOException, DshException {
        DshInstance instance = DshInstanceManager.find(id);
        if (instance == null) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "instance not found");
            return;
        }
        JsonArray skills = new JsonArray();
        for (org.jackhuang.hmcl.dsh.DshSkill skill
                : org.jackhuang.hmcl.dsh.DshSkills.list(instance.homeDirectory())) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", skill.fileName());
            entry.addProperty("name", skill.name());
            if (!skill.description().isBlank()) {
                entry.addProperty("description", skill.description());
            }
            entry.addProperty("enabled", skill.enabled());
            entry.addProperty("bundle", skill.bundle());
            entry.addProperty("sizeBytes", skill.sizeBytes());
            skills.add(entry);
        }
        JsonObject body = new JsonObject();
        body.add("skills", skills);
        Json.write(response, body);
    }

    /// Installs a skill as a task. The source is parsed here, in the request
    /// thread, but everything that touches the network — the catalogue
    /// search, the GitHub lookups, the fetch — runs inside the task.
    private void installSkill(HttpServletRequest request, HttpServletResponse response, String id)
            throws IOException, DshException {
        JsonObject body = body(request, response);
        if (body == null) {
            return;
        }
        String source = stringField(body, "source");
        if (source == null || source.isBlank()) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "source is required");
            return;
        }
        DshInstance instance = DshInstanceManager.find(id);
        if (instance == null) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "instance not found");
            return;
        }
        String trimmed = source.trim();
        RepoForm repo = RepoForm.parse(trimmed);
        try {
            TaskService.Task[] holder = new TaskService.Task[1];
            TaskService.Task task = tasks.submit("skill-install", id, () -> {
                java.util.function.Consumer<String> report = line -> {
                    TaskService.Task live = holder[0];
                    if (live != null) {
                        live.update(line, -1);
                    }
                };
                org.jackhuang.hmcl.dsh.DshSkillSource.Bundle bundle;
                if (repo != null) {
                    bundle = org.jackhuang.hmcl.dsh.DshSkillSource.resolve(
                            new org.jackhuang.hmcl.dsh.DshSkillSource.Offering(
                                    "github", repo.source(), repo.source(), repo.name(), "",
                                    repo.ref(), repo.path(), 0));
                } else {
                    report.accept("Searching the skill catalogues for " + trimmed);
                    org.jackhuang.hmcl.dsh.DshSkillSource.Found found =
                            org.jackhuang.hmcl.dsh.DshSkillSource.find(trimmed, 10);
                    org.jackhuang.hmcl.dsh.DshSkillSource.Offering offering = null;
                    for (org.jackhuang.hmcl.dsh.DshSkillSource.Offering candidate : found.skills()) {
                        if (candidate.name().equalsIgnoreCase(trimmed)) {
                            offering = candidate;
                            break;
                        }
                    }
                    if (offering == null && !found.skills().isEmpty()) {
                        offering = found.skills().get(0);
                    }
                    if (offering == null) {
                        throw new DshException("No skill was found for \"" + trimmed + "\""
                                + (found.failures().isEmpty() ? ""
                                : " (unreachable catalogues: "
                                + String.join(", ", found.failures()) + ")"));
                    }
                    bundle = org.jackhuang.hmcl.dsh.DshSkillSource.resolve(offering);
                }
                org.jackhuang.hmcl.dsh.DshSkill installed =
                        org.jackhuang.hmcl.dsh.DshSkillSource.install(
                                instance.homeDirectory(), bundle, report);
                return "Installed skill " + installed.name() + " from " + bundle.repo().fullName();
            });
            holder[0] = task;
            accepted(response, task);
        } catch (TaskService.InstanceBusyException e) {
            Json.error(response, HttpServletResponse.SC_CONFLICT, e.getMessage());
        }
    }

    /// A GitHub spelling of a skill source: `owner/repo`, optionally with a
    /// third segment naming a skill inside a collection, or a full
    /// `https://github.com/owner/repo/tree/<ref>/<path>` address.
    private record RepoForm(String source, String name, @Nullable String ref, @Nullable String path) {

        /// Parses the GitHub forms, or answers `null` when the source is a
        /// catalogue query instead.
        static @Nullable RepoForm parse(String source) {
            String s = source;
            for (String prefix : new String[]{"https://github.com/", "http://github.com/", "github.com/"}) {
                if (s.startsWith(prefix)) {
                    s = s.substring(prefix.length());
                    break;
                }
            }
            if (s.endsWith(".git")) {
                s = s.substring(0, s.length() - ".git".length());
            }
            while (s.endsWith("/")) {
                s = s.substring(0, s.length() - 1);
            }
            String[] parts = s.split("/");
            if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
                return null;
            }
            if (parts.length == 2) {
                return new RepoForm(parts[0] + "/" + parts[1], parts[1], null, null);
            }
            if (parts.length >= 4 && "tree".equals(parts[2])) {
                String ref = parts[3];
                String path = parts.length > 4
                        ? String.join("/", java.util.Arrays.copyOfRange(parts, 4, parts.length)) : null;
                String name = path == null ? parts[1] : path.substring(path.lastIndexOf('/') + 1);
                return new RepoForm(parts[0] + "/" + parts[1], name, ref, path);
            }
            if (parts.length == 3) {
                return new RepoForm(parts[0] + "/" + parts[1], parts[2], null, null);
            }
            return null;
        }
    }

    /// Removes one skill pack. `skillId` is the pack's on-disk name (its id in
    /// the list), with its frontmatter name accepted as an alias.
    private void removeSkill(HttpServletResponse response, String id, String skillId)
            throws IOException, DshException {
        DshInstance instance = DshInstanceManager.find(id);
        if (instance == null) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "instance not found");
            return;
        }
        org.jackhuang.hmcl.dsh.DshSkill found = null;
        for (org.jackhuang.hmcl.dsh.DshSkill skill
                : org.jackhuang.hmcl.dsh.DshSkills.list(instance.homeDirectory())) {
            if (skill.fileName().equals(skillId) || skill.name().equals(skillId)) {
                found = skill;
                break;
            }
        }
        if (found == null) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "skill not found");
            return;
        }
        org.jackhuang.hmcl.dsh.DshSkills.remove(found);
        Json.write(response, java.util.Map.of("ok", true));
    }

    /// The `specs`-style string array member as a list of non-blank strings,
    /// or `null`.
    private static @Nullable java.util.List<String> stringArrayField(JsonObject body, String name) {
        if (!body.has(name) || !body.get(name).isJsonArray()) {
            return null;
        }
        java.util.List<String> values = new java.util.ArrayList<>();
        for (com.google.gson.JsonElement element : body.getAsJsonArray(name)) {
            if (!element.isJsonPrimitive()) {
                return null;
            }
            String value = element.getAsString();
            if (value == null || value.isBlank()) {
                return null;
            }
            values.add(value.trim());
        }
        return values;
    }

    /// The 202 answer of every task-starting endpoint.
    private static void accepted(HttpServletResponse response, TaskService.Task task) throws IOException {
        JsonObject result = new JsonObject();
        result.addProperty("taskId", task.id());
        Json.write(response, HttpServletResponse.SC_ACCEPTED, result);
    }

    /// The `specs` member as a list of non-blank strings, or `null`.
    private static @Nullable java.util.List<String> specsField(JsonObject body) {
        if (!body.has("specs") || !body.get("specs").isJsonArray()) {
            return null;
        }
        java.util.List<String> specs = new java.util.ArrayList<>();
        for (com.google.gson.JsonElement element : body.getAsJsonArray("specs")) {
            if (!element.isJsonPrimitive()) {
                return null;
            }
            String spec = element.getAsString();
            if (spec == null || spec.isBlank()) {
                return null;
            }
            specs.add(spec.trim());
        }
        return specs;
    }

    /// The file-name-safe spelling of a package name or version — the same
    /// rule [DshLocalPlugins] names its copies by.
    private static String safeFileName(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    // ----------------------------------------------------------------- helpers --

    /// The JSON envelope of one instance, plus `installProgress` where the
    /// contract calls for it.
    private JsonObject instanceBody(DshInstance instance) {
        return runtime.toJson(instance);
    }

    /// Splits the path after `/api/instances` into decoded segments.
    private static String[] split(@Nullable String pathInfo) {
        if (pathInfo == null || pathInfo.isEmpty() || pathInfo.equals("/")) {
            return new String[0];
        }
        String trimmed = pathInfo.startsWith("/") ? pathInfo.substring(1) : pathInfo;
        return trimmed.split("/");
    }

    /// Reads the request body as a JSON object, answering 400 itself when it
    /// is not one.
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

    private static @Nullable Integer intField(JsonObject object, String name) {
        return object.has(name) && object.get(name).isJsonPrimitive()
                && object.get(name).getAsJsonPrimitive().isNumber() ? object.get(name).getAsInt() : null;
    }

    private static @Nullable Boolean boolField(JsonObject object, String name) {
        return object.has(name) && object.get(name).isJsonPrimitive()
                && object.get(name).getAsJsonPrimitive().isBoolean() ? object.get(name).getAsBoolean() : null;
    }

    /// Parses the `homeMode` member; unknown values fall back to the default
    /// rather than failing the request.
    private static @Nullable DshHomeMode homeMode(JsonObject body) {
        String value = stringField(body, "homeMode");
        if (value == null) {
            return null;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "global" -> DshHomeMode.GLOBAL;
            case "isolated" -> DshHomeMode.ISOLATED;
            case "version_shared", "shared" -> DshHomeMode.VERSION_SHARED;
            case "custom" -> DshHomeMode.CUSTOM;
            default -> null;
        };
    }

    /// Parses the `portMode` member; `null` when absent, so callers can tell
    /// "not mentioned" from "explicitly automatic".
    private static @Nullable DshPortMode portMode(JsonObject body, @Nullable DshPortMode fallback) {
        String value = stringField(body, "portMode");
        if (value == null) {
            return fallback;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "auto" -> DshPortMode.AUTO;
            case "fixed", "manual" -> DshPortMode.FIXED;
            case "global" -> DshPortMode.GLOBAL;
            default -> fallback;
        };
    }

    /// Resolves the game directory a new instance's workspace is scoped to.
    private static GameDirectory resolveDirectory(JsonObject body) {
        String id = stringField(body, "gameDirectoryId");
        if (id != null && !id.isBlank()) {
            for (GameDirectory directory : GameDirectoryManager.getGameDirectories()) {
                if (directory.id().equals(id)) {
                    return directory;
                }
            }
        }
        return GameDirectoryManager.selected();
    }

    /// Turns a display name into a directory-safe, unique instance id.
    ///
    /// Package-visible: the pack servlet names the instance a pack install
    /// creates by the same rule, so an id means the same thing wherever it
    /// came from.
    static String uniqueId(String name) {
        String base = name.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-|-$", "");
        if (base.isEmpty()) {
            base = "instance";
        }
        String candidate = base;
        int suffix = 2;
        while (DshInstanceManager.exists(candidate)) {
            candidate = base + "-" + suffix;
            suffix++;
        }
        return candidate;
    }

    /// The host part of a URL or bare authority string: `https://h/x` and
    /// `h:8080` and `h` all answer `h`. dsh's trustedHosts entries are bare
    /// authorities, and a port-less one matches the host on any port, so the
    /// port is dropped here.
    private static @Nullable String hostOf(String url) {
        String trimmed = url.trim();
        try {
            java.net.URI uri = new java.net.URI(trimmed);
            if (uri.getHost() != null) {
                return uri.getHost();
            }
        } catch (java.net.URISyntaxException ignored) {
            // Fall through to the bare-authority parse.
        }
        try {
            return new java.net.URI("https://" + trimmed).getHost();
        } catch (java.net.URISyntaxException e) {
            return null;
        }
    }

}
