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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// The process side of the experimental ZCode category: one OS process per
/// instance, launched as
/// `node <package>/bin/zcode.mjs --web --no-open --host 0.0.0.0 --port 0
/// --workspace <dir> --token <token>` with `ZCODE_DATA_BASE_DIR` pointing at
/// the instance's own `data/` directory.
///
/// ZCode's web mode announces itself on stdout: a `ZCode Web is running` line,
/// then `Local:   http://127.0.0.1:<port>/` carrying the real port (`--port 0`
/// asks the process to pick a free one). The pump thread below watches for
/// that line; until it arrives, or the deadline passes, or the process dies,
/// the instance is `starting`. Port liveness is the ground truth for
/// `running`: the moment the process exits, the instance becomes `error`
/// (with the log's tail as the reason) unless it was asked to stop, in which
/// case it becomes `stopped`.
///
/// Everything here is in-memory. A panel restart forgets every process; an
/// instance that was left running behind the panel's back simply reads as
/// `stopped` afterwards, and the orphaned process is reclaimed by whoever
/// owns the machine. That is accepted for an experimental category.
@NotNullByDefault
public final class ZcodeRuntime {

    /// The lifecycle states an instance can be in.
    public enum State {
        CREATED, STARTING, RUNNING, STOPPED, ERROR
    }

    /// What [status] reports: the state plus, for `error`, the log tail reason.
    public record Status(State state, @Nullable String error) {
    }

    /// How long a launch waits for the readiness line before giving up.
    public static final int READY_TIMEOUT_SECONDS = 60;

    /// The readiness line's port: `Local:   http://127.0.0.1:<port>/`.
    private static final Pattern LOCAL_PORT =
            Pattern.compile("Local:\\s+http://127\\.0\\.0\\.1:(\\d+)/");

    private static final int TAIL_LINES = 20;
    private static final int STOP_WAIT_SECONDS = 10;

    private static final Map<String, Process> PROCESSES = new ConcurrentHashMap<>();
    private static final Map<String, State> STATES = new ConcurrentHashMap<>();
    private static final Map<String, String> ERRORS = new ConcurrentHashMap<>();
    private static final Map<String, CompletableFuture<Integer>> READY = new ConcurrentHashMap<>();
    private static final Set<String> STOPPING = ConcurrentHashMap.newKeySet();
    private static final Map<String, Object> LOCKS = new ConcurrentHashMap<>();

    private ZcodeRuntime() {
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
                // Winding down; until the waiters notice the exit it is still up.
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

    /// Launches the instance's process and waits for its readiness line.
    ///
    /// The launch is idempotent: an instance that already holds a live process
    /// is left alone and its current status is returned. Otherwise the log is
    /// truncated, the process is started with `ZCODE_DATA_BASE_DIR` injected
    /// on top of `envMap`, and this method blocks until the process announces
    /// its port (which is persisted into the instance manifest), fails, exits
    /// early, or the 60-second deadline passes.
    ///
    /// @param manager   the instance manager, for paths and the manifest write
    /// @param instance  the instance to launch
    /// @param envMap    extra environment variables for the process
    /// @param nodePath  the node executable
    /// @param packageDir the ZCode distribution holding `bin/zcode.mjs`
    /// @return the status after the attempt
    public static Status launch(ZcodeInstanceManager manager, ZcodeInstance instance,
                                Map<String, String> envMap, String nodePath, Path packageDir) {
        String id = instance.id();
        synchronized (lockFor(id)) {
            Process existing = PROCESSES.get(id);
            if (existing != null && existing.isAlive()) {
                return status(id);
            }

            STOPPING.remove(id);
            ERRORS.remove(id);
            STATES.put(id, State.STARTING);

            Path logFile = manager.logFile(instance);
            Path workspace = manager.workspaceDirectory(instance);
            Process process;
            OutputStream log;
            try {
                Files.createDirectories(logFile.getParent());
                Files.createDirectories(workspace);
                log = Files.newOutputStream(logFile,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                ProcessBuilder builder = new ProcessBuilder(
                        nodePath,
                        packageDir.resolve("bin/zcode.mjs").toString(),
                        "--web", "--no-open",
                        "--host", "0.0.0.0",
                        "--port", "0",
                        "--workspace", workspace.toString(),
                        "--token", instance.token());
                builder.directory(workspace.toFile());
                builder.redirectErrorStream(true);
                builder.environment().putAll(envMap);
                builder.environment().put("ZCODE_DATA_BASE_DIR", manager.dataDirectory(instance).toString());
                process = builder.start();
            } catch (IOException e) {
                STATES.put(id, State.ERROR);
                String reason = "Failed to start the ZCode process: " + e.getMessage();
                ERRORS.put(id, reason);
                LOG.warning("Could not launch ZCode instance " + id, e);
                return new Status(State.ERROR, reason);
            }

            PROCESSES.put(id, process);
            CompletableFuture<Integer> ready = new CompletableFuture<>();
            READY.put(id, ready);
            Thread pump = new Thread(
                    () -> pumpLines(id, process, log, logFile, ready), "zcode-log-" + id);
            pump.setDaemon(true);
            pump.start();

            Integer port = awaitReady(id, process, ready);
            READY.remove(id);
            if (port != null) {
                ZcodeInstance updated = instance.withLastPort(port);
                try {
                    manager.update(updated);
                    STATES.put(id, State.RUNNING);
                    LOG.info("ZCode instance " + id + " is running on port " + port);
                    return new Status(State.RUNNING, null);
                } catch (ZcodeException e) {
                    LOG.warning("ZCode instance " + id + " came up but its port could not be saved", e);
                    // Running all the same; the next open will not know the port,
                    // which is what lastPort == 0 already means elsewhere.
                    STATES.put(id, State.RUNNING);
                    return new Status(State.RUNNING, null);
                }
            }

            Process removed = PROCESSES.remove(id);
            if (removed != null && removed.isAlive()) {
                removed.destroyForcibly();
            }
            State state = STATES.getOrDefault(id, State.ERROR);
            return new Status(state, state == State.ERROR ? ERRORS.get(id) : null);
        }
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

    /// Waits for the readiness future, the process's death or the deadline —
    /// whichever comes first. Returns the announced port, or `null`.
    private static @Nullable Integer awaitReady(String id, Process process, CompletableFuture<Integer> ready) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(READY_TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            Integer port = ready.getNow(null);
            if (port != null) {
                return port;
            }
            if (!process.isAlive()) {
                // Give the pump a moment to record the exit reason.
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
        ERRORS.put(id, "ZCode did not become ready within " + READY_TIMEOUT_SECONDS + " seconds");
        STATES.put(id, State.ERROR);
        return null;
    }

    /// Copies the process's merged output into its log file, watching every
    /// line for the readiness announcement; on EOF (crash or stop) records the
    /// terminal state, with the log's tail as the reason for an unexpected exit.
    private static void pumpLines(String id, Process process, OutputStream log,
                                  Path logFile, CompletableFuture<Integer> ready) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                byte[] bytes = (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
                synchronized (log) {
                    log.write(bytes);
                    log.flush();
                }
                java.util.regex.Matcher matcher = LOCAL_PORT.matcher(line);
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
                String reason = "The ZCode process exited unexpectedly";
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
