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

import org.jackhuang.hmcl.dsh.DshPorts;
import org.jackhuang.hmcl.util.ProcessTree;
import org.jackhuang.hmcl.util.platform.Executables;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// The process side of the third-party brand categories: one OS process per
/// instance, launched as the brand's own web mode — `kimi web` or
/// `opencode web` — which serves the tool's **own** web UI on a loopback port
/// the browser then reaches through the panel's `/i/<id>/` mount.
///
/// The launch differs per brand in two ways, both verified against the real
/// binaries in a spike:
///
/// - **Port.** Kimi honours `--port 0` with the ephemeral port announced on
///   stdout (`Local: http://127.0.0.1:<port>/`). OpenCode does **not** — a
///   `--port 0` there silently falls back to its default 4096, which two
///   instances would collide on — so the panel pre-allocates a free port with
///   the same probe the dsh pool uses and passes it explicitly.
/// - **Readiness.** Both brands print a `… http://127.0.0.1:<port>/` line, but
///   OpenCode wraps its banner in ANSI colour even when piped, so lines are
///   stripped of escape sequences before matching.
///
/// Kimi additionally gets `--allowed-host <public host>` (its DNS-rebinding
/// guard, the counterpart of dsh's `--trusted-host`) and
/// `--dangerous-bypass-auth`, whose own help describes it as the switch for
/// running "behind your own authenticating proxy" — the panel's session gate
/// plus `/i/<id>/` mount is exactly that proxy, so no token is ever handled
/// here. OpenCode runs without its optional basic auth for the same reason.
///
/// Everything here is in-memory, matching the ZCode category: a panel restart
/// forgets every process until the instance is launched again.
@NotNullByDefault
public final class BrandRuntime {

    /// The lifecycle states an instance can be in.
    public enum State {
        CREATED, STARTING, RUNNING, STOPPED, ERROR
    }

    /// What [status] reports: the state plus, for `error`, the log tail reason.
    public record Status(State state, @Nullable String error) {
    }

    /// How long a launch waits for the readiness line before giving up.
    public static final int READY_TIMEOUT_SECONDS = 60;

    /// Kimi's readiness announcement.
    private static final Pattern KIMI_LOCAL_PORT =
            Pattern.compile("Local:\\s+http://127\\.0\\.0\\.1:(\\d+)/");

    /// OpenCode's readiness announcement (matched on the ANSI-stripped line).
    private static final Pattern OPENCODE_WEB_INTERFACE =
            Pattern.compile("Web interface:\\s+http://127\\.0\\.0\\.1:(\\d+)/");

    /// Claude Web's readiness announcement (`claude-web <version> listening on
    /// http://127.0.0.1:<port>`, printed by its server entry).
    private static final Pattern CLAUDE_WEB_LISTENING =
            Pattern.compile("listening on http://127\\.0\\.0\\.1:(\\d+)");

    /// Octop's uvicorn line. The launch path polls `/api/health` instead (the
    /// health endpoint is Octop's own contract); the pattern only keeps the
    /// shared log pump exhaustive.
    private static final Pattern OCTOP_UVICORN =
            Pattern.compile("Uvicorn running on http://127\\.0\\.0\\.1:(\\d+)");

    /// Where pre-allocated ports for brands that cannot pick their own are
    /// drawn from — outside the dsh pool (3081–4081) so the two never fight.
    private static final int ALLOC_RANGE_START = 20000;
    private static final int ALLOC_RANGE_END = 60999;
    private static final int ALLOC_ATTEMPTS = 64;

    private static final int TAIL_LINES = 20;
    private static final int STOP_WAIT_SECONDS = 10;

    private static final Map<String, Process> PROCESSES = new ConcurrentHashMap<>();
    private static final Map<String, State> STATES = new ConcurrentHashMap<>();
    private static final Map<String, String> ERRORS = new ConcurrentHashMap<>();
    private static final Map<String, CompletableFuture<Integer>> READY = new ConcurrentHashMap<>();
    private static final Map<String, Brand> BRANDS = new ConcurrentHashMap<>();
    private static final java.util.Set<String> STOPPING = ConcurrentHashMap.newKeySet();
    private static final Map<String, Object> LOCKS = new ConcurrentHashMap<>();
    private static final Map<String, Integer> PORTS = new ConcurrentHashMap<>();
    private static final Random RANDOM = new Random();

    /// The instance that answers the last-resort `/i/octop/` mount, when the
    /// developer policy turns `lockedRoute` on. `null` means the normal token
    /// mounts only.
    private static volatile @Nullable String LOCKED_OCTOP_SURVIVOR;

    private BrandRuntime() {
    }

    /// What the instance is in right now, process liveness first.
    ///
    /// @param id the instance id
    /// @return the status; `created` when nothing is known about the instance
    public static Status status(String id) {
        Process process = PROCESSES.get(id);
        if (process != null && process.isAlive()) {
            State state = STATES.getOrDefault(id, State.STARTING);
            if (state == State.STOPPED || state == State.ERROR) {
                state = State.RUNNING;
            }
            return new Status(state, state == State.ERROR ? ERRORS.get(id) : null);
        }
        State state = STATES.getOrDefault(id, State.CREATED);
        return new Status(state, state == State.ERROR ? ERRORS.get(id) : null);
    }

    /// Whether the instance currently holds a live process.
    ///
    /// @param id the instance id
    /// @return whether it is running
    public static boolean isRunning(String id) {
        Process process = PROCESSES.get(id);
        return process != null && process.isAlive();
    }

    /// Whether this runtime has ever seen the id — the `/i/<id>/` proxy uses it
    /// to tell "no such instance" (404) from "known but not running" (502).
    ///
    /// @param id the instance id
    /// @return whether a launch was attempted for it in this panel run
    public static boolean known(String id) {
        return STATES.containsKey(id);
    }

    /// The loopback port the instance announced, or `0` when it never came up.
    ///
    /// @param id the instance id
    /// @return the port, or `0`
    public static int portOf(String id) {
        return PORTS.getOrDefault(id, 0);
    }

    /// Launches the instance's process and waits for its readiness line.
    ///
    /// The launch is idempotent: an instance that already holds a live process
    /// is left alone and its current status is returned.
    ///
    /// @param brand     the brand
    /// @param manager   the manager, for paths and the manifest write
    /// @param instance  the instance to launch
    /// @param publicHost the external host the browser uses, for the brand's
    ///                   host allowlist; `null` leaves the allowlist at the
    ///                   loopback default
    /// @param packageDir the installed release holding the executable
    /// @return the status after the attempt
    public static Status launch(Brand brand, BrandInstanceManager manager, BrandInstance instance,
                                @Nullable String publicHost, Path packageDir) {
        String id = instance.id();
        synchronized (lockFor(id)) {
            Process existing = PROCESSES.get(id);
            if (existing != null && existing.isAlive()) {
                return status(id);
            }

            STOPPING.remove(id);
            ERRORS.remove(id);
            STATES.put(id, State.STARTING);
            BRANDS.put(id, brand);

            Path logFile = manager.logFile(instance);
            Path workspace = manager.workspaceDirectory(instance);
            Path home = manager.homeDirectory(instance);
            Process process;
            OutputStream log;
            LaunchPlan plan;
            try {
                Files.createDirectories(logFile.getParent());
                Files.createDirectories(workspace);
                Files.createDirectories(home);
                log = Files.newOutputStream(logFile,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
                if (brand == Brand.OCTOP) {
                    ensureOctopInitialized(packageDir, home, logFile);
                }
                plan = plan(brand, packageDir, instance, publicHost);
                ProcessBuilder builder = new ProcessBuilder(Executables.startable(plan.command()));
                builder.directory(workspace.toFile());
                builder.redirectErrorStream(true);
                // The tool's own state lives under its HOME, which is the
                // instance directory — credentials, sessions and config all
                // land inside the volume, per instance.
                builder.environment().put("HOME", home.toString());
                if (brand == Brand.OCTOP) {
                    // Octop's data root; explicit because it is not derived from
                    // HOME on every platform.
                    builder.environment().put("OCTOP_HOME", home.toString());
                }
                // Node.js resolves `~` through `USERPROFILE` on Windows and ignores `HOME`,
                // so without this the isolation above silently does not apply there and a
                // brand's credentials land in the real user profile — outside the instance,
                // and outside the volume the operator backs up.
                if (org.jackhuang.hmcl.util.platform.OperatingSystem.CURRENT_OS
                        == org.jackhuang.hmcl.util.platform.OperatingSystem.WINDOWS) {
                    builder.environment().put("USERPROFILE", home.toString());
                }
                // Brand-specific environment (Claude Web takes its port from
                // PORT; it has no port flag).
                builder.environment().putAll(plan.environment());
                process = builder.start();
            } catch (IOException e) {
                STATES.put(id, State.ERROR);
                String reason = "Failed to start the " + brand.id() + " process: " + e.getMessage();
                ERRORS.put(id, reason);
                LOG.warning("Could not launch " + brand.id() + " instance " + id, e);
                return new Status(State.ERROR, reason);
            }

            PROCESSES.put(id, process);
            CompletableFuture<Integer> ready = new CompletableFuture<>();
            READY.put(id, ready);
            Thread pump = new Thread(
                    () -> pumpLines(brand, id, process, log, logFile, ready), brand.id() + "-log-" + id);
            pump.setDaemon(true);
            pump.start();

            Integer port = brand == Brand.OCTOP
                    ? awaitOctopHealth(id, process, plan.port())
                    : awaitReady(brand, id, process, ready);
            READY.remove(id);
            if (port != null) {
                PORTS.put(id, port);
                try {
                    manager.update(instance.withLastPort(port));
                } catch (BrandException e) {
                    LOG.warning("Instance " + id + " came up but its port could not be saved", e);
                }
                if (brand == Brand.CLAUDE_WEB) {
                    registerClaudeWebWorkspace(port, workspace);
                }
                STATES.put(id, State.RUNNING);
                LOG.info(brand.id() + " instance " + id + " is running on port " + port);
                return new Status(State.RUNNING, null);
            }

            Process removed = PROCESSES.remove(id);
            if (removed != null && removed.isAlive()) {
                removed.destroyForcibly();
            }
            State state = STATES.getOrDefault(id, State.ERROR);
            return new Status(state, state == State.ERROR ? ERRORS.get(id) : null);
        }
    }

    /// The brand an instance belongs to, when this runtime knows it.
    ///
    /// @param id the instance id
    /// @return the brand, or `null` when the id is unknown here
    public static @Nullable Brand brandOf(String id) {
        return BRANDS.get(id);
    }

    /// Selects the survivor that answers `/i/octop/`, or clears the fixed
    /// route. Called at boot and whenever the developer policy changes.
    public static void setLockedOctopSurvivor(@Nullable String instanceId) {
        LOCKED_OCTOP_SURVIVOR = instanceId == null || instanceId.isBlank() ? null : instanceId;
    }

    /// The survivor id behind `/i/octop/`, or `null` when the fixed route is
    /// off.
    public static @Nullable String lockedOctopSurvivor() {
        return LOCKED_OCTOP_SURVIVOR;
    }

    /// The brand a mount id names. A normal mount id is an instance id; the
    /// reserved `/i/octop/` alias is the one that has no instance of its own.
    public static @Nullable Brand brandForMount(String mountId) {
        if ("octop".equals(mountId) && LOCKED_OCTOP_SURVIVOR != null) {
            return Brand.OCTOP;
        }
        return brandOf(mountId);
    }

    /// The target a mount id resolves to: normally the instance id, but the
    /// fixed alias forwards to the survivor.
    public static String targetForMount(String mountId) {
        if ("octop".equals(mountId) && LOCKED_OCTOP_SURVIVOR != null) {
            return LOCKED_OCTOP_SURVIVOR;
        }
        return mountId;
    }

    /// The `workspaces.add` request the panel sends to a freshly started Claude
    /// Web instance — package-private so its envelope is testable.
    ///
    /// The envelope is the tool's own wire format: `{"type":"request",
    /// "request":{"id":…,"req":{"kind":"workspaces.add","path":…}}}`.
    ///
    /// @param path the directory to register
    /// @return the JSON text
    static String workspaceAddRequest(String path) {
        com.google.gson.JsonObject req = new com.google.gson.JsonObject();
        req.addProperty("kind", "workspaces.add");
        req.addProperty("path", path);
        com.google.gson.JsonObject envelope = new com.google.gson.JsonObject();
        envelope.addProperty("type", "request");
        com.google.gson.JsonObject request = new com.google.gson.JsonObject();
        request.addProperty("id", WORKSPACE_REQUEST_ID);
        request.add("req", req);
        envelope.add("request", request);
        return envelope.toString();
    }

    /// The request id the panel uses; also how the reply is recognised.
    private static final String WORKSPACE_REQUEST_ID = "hdsl-workspace";

    /// Tells a freshly started Claude Web instance that this instance's
    /// workspace directory is one of its projects.
    ///
    /// Claude Web's project picker ("打开文件夹…") asks the **server** to open a
    /// native folder dialog — `zenity` on Linux, `osascript` on macOS,
    /// PowerShell on Windows (its `terminal/service.ts` `pickDir`). In a
    /// container there is no desktop and no zenity, and the failure is silent:
    /// `pickDir` returns null and the UI simply does nothing when clicked. The
    /// protocol's own `workspaces.add` takes a path instead, so the panel
    /// registers the directory the instance was created with; from then on it
    /// is right there in the project menu, and the native dialog is not needed.
    ///
    /// Best effort by design: a failure costs a click (the dialog button still
    /// opens nothing), never the instance.
    static void registerClaudeWebWorkspace(int port, Path workspace) {
        String path = workspace.toAbsolutePath().toString();
        String payload = workspaceAddRequest(path);
        java.util.concurrent.CompletableFuture<Boolean> settled = new java.util.concurrent.CompletableFuture<>();
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(5))
                .build();
        try {
            client.newWebSocketBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(5))
                    .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws"), new java.net.http.WebSocket.Listener() {
                        private final StringBuilder buffer = new StringBuilder();

                        @Override
                        public void onOpen(java.net.http.WebSocket socket) {
                            socket.sendText(payload, true);
                        }

                        @Override
                        public java.util.concurrent.CompletionStage<?> onText(
                                java.net.http.WebSocket socket, CharSequence data, boolean last) {
                            buffer.append(data);
                            if (last && buffer.indexOf("\"type\":\"reply\"") >= 0
                                    && buffer.indexOf(WORKSPACE_REQUEST_ID) >= 0) {
                                settled.complete(buffer.indexOf("\"ok\":true") >= 0);
                                socket.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "bye");
                            }
                            return null;
                        }

                        @Override
                        public void onError(java.net.http.WebSocket socket, Throwable error) {
                            settled.completeExceptionally(error);
                        }
                    });
            settled.get(5, TimeUnit.SECONDS);
            LOG.info("Registered workspace " + path + " as a Claude Web project");
        } catch (Exception e) {
            LOG.warning("Could not register workspace " + path + " with the Claude Web instance on port "
                    + port + " — pick the folder in the page instead", e);
        }
    }

    /// Asks the instance's process to terminate and waits briefly for it.
    ///
    /// @param id the instance id
    public static void stop(String id) {
        STOPPING.add(id);
        Process process = PROCESSES.get(id);
        if (process == null) {
            // No output pump is watching this instance, so nothing else will
            // consume the flag.
            STOPPING.remove(id);
            STATES.put(id, State.STOPPED);
            PROCESSES.remove(id);
            return;
        }
        if (!process.isAlive()) {
            // The pump may still be on its way to the end of the output; it
            // consumes the flag and records STOPPED.
            STATES.put(id, State.STOPPED);
            PROCESSES.remove(id);
            return;
        }
        // The launcher is a shim (`kimi`, `opencode`, `claude-web` via tsx), and the
        // process actually holding the port is its child: take the tree down, not
        // just the handle we kept.
        ProcessTree.terminate(process, STOP_WAIT_SECONDS);
        PROCESSES.remove(id);
        // Deliberately leave STOPPING in place: the pump reaches EOF only after
        // the last process holding the pipe is gone, which can be after this
        // method returns. Removing it here would race the pump for the same
        // answer and report a requested stop as an unexpected exit.
        STATES.put(id, State.STOPPED);
    }

    private static Object lockFor(String id) {
        return LOCKS.computeIfAbsent(id, key -> new Object());
    }

    /// One brand's launch: the command to run plus any environment variables it
    /// needs (only Claude Web today — it reads its port from `PORT` rather than
    /// a flag).
    ///
    /// @param command the executable and arguments
    /// @param environment extra environment variables, on top of the panel's
    ///                      own (HOME etc.)
    record LaunchPlan(List<String> command, Map<String, String> environment, int port) {
    }

    /// Builds the brand's launch command.
    ///
    /// Package-private so the flag wiring (allowlist host, port strategy,
    /// bypass switch) is directly testable. Prefer [plan] when the process is
    /// actually started — it carries the environment the command needs.
    static List<String> command(Brand brand, Path packageDir, BrandInstance instance,
                                @Nullable String publicHost) throws IOException {
        return plan(brand, packageDir, instance, publicHost).command();
    }

    /// Builds the brand's full launch: command plus environment.
    ///
    /// Package-private so the flag wiring (allowlist host, port strategy,
    /// bypass switch, port environment) is directly testable.
    static LaunchPlan plan(Brand brand, Path packageDir, BrandInstance instance,
                           @Nullable String publicHost) throws IOException {
        List<String> command = new ArrayList<>();
        switch (brand) {
            case KIMI -> {
                command.add(entryPoint(packageDir.resolve(brand.installMarker())).toString());
                command.add("web");
                command.add("--no-open");
                command.add("--port");
                command.add("0");
                command.add("--host");
                command.add("127.0.0.1");
                String host = publicHost == null ? "" : publicHost.trim();
                if (!host.isEmpty()) {
                    // The DNS-rebinding guard: the browser reaches the instance
                    // through the panel's own host, so that host must be allowed.
                    command.add("--allowed-host");
                    command.add(host);
                }
                // The panel's session gate plus the /i/<id>/ mount is the
                // authenticating proxy this switch is documented for; no token
                // is ever generated, printed, or handled here.
                command.add("--dangerous-bypass-auth");
                return new LaunchPlan(List.copyOf(command), Map.of(), 0);
            }
            case OPENCODE -> {
                int port = allocatePort();
                command.add(entryPoint(packageDir.resolve(brand.installMarker())).toString());
                command.add("web");
                command.add("--port");
                command.add(String.valueOf(port));
                command.add("--hostname");
                command.add("127.0.0.1");
                command.add("--print-logs");
                return new LaunchPlan(List.copyOf(command), Map.of(), port);
            }
            case CLAUDE_WEB -> {
                // The server runs under tsx straight from the checkout — there
                // is no built entry point, and none is needed: tsx executes the
                // TypeScript source, resolving the workspace's dependencies
                // (including the engine package) from the cloned node_modules.
                Path entry = packageDir.resolve("server/src/index.ts");
                if (!Files.isRegularFile(entry)) {
                    throw new IOException("server entry not found: " + entry);
                }
                int port = allocatePort();
                command.add(entryPoint(packageDir.resolve("node_modules/.bin/tsx")).toString());
                command.add(entry.toString());
                // Its port comes from the environment, not a flag (verified in
                // server/src/index.ts: PORT ?? 3090), so the pre-allocated
                // port travels in the environment while the command stays a
                // plain `tsx <entry>`. The instance's endpoint override rides
                // along: the browser mode drives the same CLI, so a gateway
                // configured for the terminal must serve the web workbench too.
                java.util.Map<String, String> environment = new java.util.LinkedHashMap<>();
                environment.put("PORT", String.valueOf(port));
                org.jackhuang.hmcl.web.terminal.ClaudeWebTui.applyBaseUrl(environment, instance.baseUrl());
                return new LaunchPlan(List.copyOf(command), java.util.Map.copyOf(environment), port);
            }
            case OCTOP -> {
                int port = allocatePort();
                Path launch = packageDir.resolve("launch.py");
                if (!Files.isRegularFile(launch)) {
                    throw new IOException("Octop launcher not found: " + launch);
                }
                // The portable archive carries its own CPython, so no system
                // Python (and no uv) is involved. `launch.py` puts packages/
                // on sys.path and then runs the `octop` module.
                command.add(octopPython(packageDir).toString());
                command.add(launch.toString());
                command.add("run");
                command.add("--host");
                command.add("127.0.0.1");
                command.add("--port");
                command.add(String.valueOf(port));
                java.util.Map<String, String> environment = new java.util.LinkedHashMap<>();
                environment.put("OCTOP_PORT", String.valueOf(port));
                environment.put("PYTHONDONTWRITEBYTECODE", "1");
                return new LaunchPlan(List.copyOf(command), java.util.Map.copyOf(environment), port);
            }
            default -> throw new IOException("unknown brand " + brand);
        }
    }

    /// Resolves the file inside an installed package that is actually started.
    ///
    /// A brand's entry point is `node_modules/.bin/<tool>` on every platform, but on
    /// Windows that file has no extension and is a POSIX shell script — the executable
    /// form is `<tool>.cmd` beside it, and the extensionless one fails with
    /// "not a valid Win32 application". One marker therefore has to become two
    /// different files, which is what [Executables] decides.
    ///
    /// @param marker the `node_modules/.bin/<tool>` marker (or any other entry point)
    /// @return the file to start
    /// @throws IOException when nothing startable is there
    private static Path entryPoint(Path marker) throws IOException {
        return Executables.resolve(marker.toString())
                .map(path -> path.toAbsolutePath().normalize())
                .orElseThrow(() -> new IOException("executable not found: " + marker));
    }

    /// Picks a free loopback port for brands that cannot ask the OS for one
    /// (`--port 0` is not honoured by OpenCode; it would fall back to its
    /// default and two instances would collide).
    private static int allocatePort() throws IOException {
        for (int attempt = 0; attempt < ALLOC_ATTEMPTS; attempt++) {
            int candidate = ALLOC_RANGE_START + RANDOM.nextInt(ALLOC_RANGE_END - ALLOC_RANGE_START + 1);
            if (DshPorts.isFree(candidate)) {
                return candidate;
            }
        }
        throw new IOException("no free port between " + ALLOC_RANGE_START + " and " + ALLOC_RANGE_END);
    }

    /// The portable archive's own interpreter: the panel must not depend on a
    /// system Python, and the archive ships CPython 3.12 next to `launch.py`.
    static Path octopPython(Path packageDir) throws IOException {
        Path runtime = packageDir.resolve("runtime");
        for (String candidate : List.of("bin/python3.12", "bin/python3", "python.exe")) {
            Path path = runtime.resolve(candidate);
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        throw new IOException("Octop runtime has no Python interpreter under " + runtime);
    }

    /// First-run bootstrap: `octop init --yes` with a generated admin password.
    ///
    /// Idempotent by the control-plane database, which is what `octop init`
    /// itself treats as "already bootstrapped". The password is written to
    /// `<home>/octop-admin.txt` (0600) once; later launches leave it alone.
    private static void ensureOctopInitialized(Path packageDir, Path home, Path logFile) throws IOException {
        if (Files.isRegularFile(home.resolve("octop.db"))) {
            return;
        }
        Path launch = packageDir.resolve("launch.py");
        if (!Files.isRegularFile(launch)) {
            throw new IOException("Octop launcher not found: " + launch);
        }
        String password = randomPassword();
        List<String> command = List.of(
                octopPython(packageDir).toString(), launch.toString(),
                "init", "--yes",
                "--admin-username", "admin",
                "--admin-password", password);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(home.toFile());
        builder.redirectErrorStream(true);
        builder.environment().put("HOME", home.toString());
        builder.environment().put("OCTOP_HOME", home.toString());
        builder.environment().put("PYTHONDONTWRITEBYTECODE", "1");
        if (org.jackhuang.hmcl.util.platform.OperatingSystem.CURRENT_OS
                == org.jackhuang.hmcl.util.platform.OperatingSystem.WINDOWS) {
            builder.environment().put("USERPROFILE", home.toString());
        }
        Process process = builder.start();
        byte[] output = process.getInputStream().readAllBytes();
        boolean finished;
        try {
            finished = process.waitFor(120, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("Octop init was interrupted", e);
        }
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Octop init did not finish within 120 seconds");
        }
        String text = new String(output, StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            throw new IOException("Octop init failed (exit " + process.exitValue() + "): " + tail(text));
        }
        Files.writeString(home.resolve("octop-admin.txt"),
                "username: admin\npassword: " + password + "\n", StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(home.resolve("octop-admin.txt"),
                    PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Windows: the file still lives inside the instance directory.
        }
        Files.writeString(logFile, text, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        LOG.info("Initialised Octop at " + home + " (admin credentials in octop-admin.txt)");
    }

    /// Polls Octop's `/api/health` until it answers or the process dies. Its
    /// log line is uvicorn's, not a stable readiness contract; the health
    /// endpoint is the one Octop itself documents.
    private static @Nullable Integer awaitOctopHealth(String id, Process process, int port) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/health"))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(READY_TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                return null;
            }
            try {
                HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() / 100 == 2) {
                    return port;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (IOException ignored) {
                // Not listening yet (or still migrating the database).
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        ERRORS.put(id, "octop did not answer /api/health within " + READY_TIMEOUT_SECONDS + " seconds");
        STATES.put(id, State.ERROR);
        return null;
    }

    /// A 16-character password that satisfies Octop's policy (a letter and a
    /// digit), avoiding the common-password list by construction.
    static String randomPassword() {
        String letters = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ";
        String digits = "23456789";
        String all = letters + digits;
        SecureRandom random = new SecureRandom();
        StringBuilder out = new StringBuilder(16);
        out.append(letters.charAt(random.nextInt(letters.length())));
        for (int i = 1; i < 15; i++) {
            out.append(all.charAt(random.nextInt(all.length())));
        }
        out.append(digits.charAt(random.nextInt(digits.length())));
        return out.toString();
    }

    /// The last line(s) of a failed subprocess, bounded for an error message.
    private static String tail(String text) {
        String trimmed = text.strip();
        int newline = trimmed.lastIndexOf('\n');
        String last = newline >= 0 ? trimmed.substring(newline + 1) : trimmed;
        return last.length() > 500 ? last.substring(last.length() - 500) : last;
    }

    private static @Nullable Integer awaitReady(Brand brand, String id, Process process,
                                                CompletableFuture<Integer> ready) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(READY_TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            Integer port = ready.getNow(null);
            if (port != null) {
                // Some servers print their address a moment before the socket
                // accepts connections (OpenCode answered /project with 502s
                // right after the readiness line, healthy a second later), so
                // "ready" means the announced port is actually refusing to be
                // free — bound, accepting — not just printed.
                long boundDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (DshPorts.isFree(port) && System.nanoTime() < boundDeadline) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
                if (DshPorts.isFree(port)) {
                    return null;
                }
                return port;
            }
            if (!process.isAlive()) {
                ready.getNow(null);
                return null;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        ERRORS.put(id, brand.id() + " did not become ready within " + READY_TIMEOUT_SECONDS + " seconds");
        STATES.put(id, State.ERROR);
        return null;
    }

    /// The readiness line each brand prints — matched on ANSI-stripped output.
    private static Pattern readyPattern(Brand brand) {
        return switch (brand) {
            case KIMI -> KIMI_LOCAL_PORT;
            case OPENCODE -> OPENCODE_WEB_INTERFACE;
            case CLAUDE_WEB -> CLAUDE_WEB_LISTENING;
            case OCTOP -> OCTOP_UVICORN;
        };
    }

    /// Copies the process's merged output into its log file, watching every
    /// ANSI-stripped line for the readiness announcement; on EOF records the
    /// terminal state, with the log's tail as the reason for an unexpected exit.
    private static void pumpLines(Brand brand, String id, Process process, OutputStream log,
                                  Path logFile, CompletableFuture<Integer> ready) {
        Pattern readyPattern = readyPattern(brand);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                byte[] bytes = (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
                synchronized (log) {
                    log.write(bytes);
                    log.flush();
                }
                java.util.regex.Matcher matcher = readyPattern.matcher(stripAnsi(line));
                if (matcher.find()) {
                    try {
                        ready.complete(Integer.parseInt(matcher.group(1)));
                    } catch (NumberFormatException ignored) {
                        // Not a port; keep waiting for a better line.
                    }
                }
            }
        } catch (IOException e) {
            if (!ready.isDone()) {
                ready.completeExceptionally(e);
            }
        } finally {
            try {
                log.close();
            } catch (IOException ignored) {
                // The log is best-effort diagnostics.
            }
            if (!ready.isDone()) {
                ready.complete(null);
            }
            PROCESSES.remove(id, process);
            if (STOPPING.remove(id)) {
                STATES.put(id, State.STOPPED);
            } else {
                String reason = "The " + brand.id() + " process exited unexpectedly";
                try {
                    List<String> tail = tail(logFile, TAIL_LINES);
                    if (!tail.isEmpty()) {
                        reason = reason + " — log tail: " + String.join(" | ", tail);
                    }
                } catch (IOException ignored) {
                    // The generic reason stands.
                }
                ERRORS.put(id, reason);
                STATES.put(id, State.ERROR);
            }
        }
    }

    /// Removes ANSI colour escapes — OpenCode colours its banner even when the
    /// output is a pipe, and escape sequences inside the readiness line would
    /// break the port match.
    static String stripAnsi(String line) {
        return line.replaceAll("\u001B\\[[;\\d]*m", "");
    }

    /// Reads the last lines of a log file.
    private static List<String> tail(Path file, int maxLines) throws IOException {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
            List<String> all = lines.toList();
            return all.subList(Math.max(0, all.size() - maxLines), all.size());
        }
    }
}
