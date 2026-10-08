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
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
            try {
                Files.createDirectories(logFile.getParent());
                Files.createDirectories(workspace);
                Files.createDirectories(home);
                log = Files.newOutputStream(logFile,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
                List<String> command = command(brand, packageDir, instance, publicHost);
                ProcessBuilder builder = new ProcessBuilder(command);
                builder.directory(workspace.toFile());
                builder.redirectErrorStream(true);
                // The tool's own state lives under its HOME, which is the
                // instance directory — credentials, sessions and config all
                // land inside the volume, per instance.
                builder.environment().put("HOME", home.toString());
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

            Integer port = awaitReady(brand, id, process, ready);
            READY.remove(id);
            if (port != null) {
                PORTS.put(id, port);
                try {
                    manager.update(instance.withLastPort(port));
                } catch (BrandException e) {
                    LOG.warning("Instance " + id + " came up but its port could not be saved", e);
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

    /// Asks the instance's process to terminate and waits briefly for it.
    ///
    /// @param id the instance id
    public static void stop(String id) {
        STOPPING.add(id);
        Process process = PROCESSES.get(id);
        if (process == null || !process.isAlive()) {
            STATES.put(id, State.STOPPED);
            PROCESSES.remove(id);
            STOPPING.remove(id);
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(STOP_WAIT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        PROCESSES.remove(id);
        STOPPING.remove(id);
        STATES.put(id, State.STOPPED);
    }

    private static Object lockFor(String id) {
        return LOCKS.computeIfAbsent(id, key -> new Object());
    }

    /// Builds the brand's launch command.
    ///
    /// Package-private so the flag wiring (allowlist host, port strategy,
    /// bypass switch) is directly testable.
    static List<String> command(Brand brand, Path packageDir, BrandInstance instance,
                                @Nullable String publicHost) throws IOException {
        Path bin = packageDir.resolve("node_modules/.bin/" + brand.binName());
        if (!Files.isRegularFile(bin)) {
            throw new IOException("executable not found: " + bin);
        }
        Path executable = bin.toRealPath();
        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        switch (brand) {
            case KIMI -> {
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
            }
            case OPENCODE -> {
                command.add("web");
                command.add("--port");
                command.add(String.valueOf(allocatePort()));
                command.add("--hostname");
                command.add("127.0.0.1");
                command.add("--print-logs");
            }
            default -> throw new IOException("unknown brand " + brand);
        }
        return command;
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

    /// Copies the process's merged output into its log file, watching every
    /// ANSI-stripped line for the readiness announcement; on EOF records the
    /// terminal state, with the log's tail as the reason for an unexpected exit.
    private static void pumpLines(Brand brand, String id, Process process, OutputStream log,
                                  Path logFile, CompletableFuture<Integer> ready) {
        Pattern readyPattern = brand == Brand.KIMI ? KIMI_LOCAL_PORT : OPENCODE_WEB_INTERFACE;
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
