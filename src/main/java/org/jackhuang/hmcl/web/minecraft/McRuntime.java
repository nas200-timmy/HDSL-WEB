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
package org.jackhuang.hmcl.web.minecraft;

import org.jackhuang.hmcl.util.platform.VirtualThreads;
import org.jackhuang.hmcl.web.terminal.TerminalManager;
import org.jackhuang.hmcl.web.terminal.TerminalSession;
import org.jackhuang.hmcl.web.terminal.TerminalSink;
import org.jackhuang.hmcl.web.terminal.TerminalTarget;
import org.jackhuang.hmcl.web.terminal.pty.PtySize;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/// Starts, stops and watches one Minecraft server per instance.
///
/// The process is a **terminal session**, not a bare `Process`: the console is
/// the existing PTY, so the browser gets history, resize and multi-viewer
/// attach for free, and the panel's shutdown takes the server with it. The only
/// thing added here is the Minecraft-specific part — the argv, the readiness
/// line, and the state the UI polls.
///
/// The console is attach-only: [console] never starts a server. That split is
/// what keeps "open the console" from becoming "start a service" (see
/// `docs/minecraft.md` §6.2.1).
@NotNullByDefault
public final class McRuntime {

    /// The terminal brand every Minecraft session is registered under.
    public static final String BRAND = "minecraft";

    /// How long a launch may take to print its readiness line.
    public static final int READY_TIMEOUT_SECONDS = 120;
    /// How long `stop` waits for the server to exit before it is killed.
    public static final int STOP_GRACE_SECONDS = 30;

    /// What the panel shows beside an instance.
    public enum State {
        STOPPED,
        STARTING,
        RUNNING,
        STOPPING,
        FAILED
    }

    /// The state and the reason it is not running.
    ///
    /// @param state the state
    /// @param error the last failure, or `null`
    public record Status(State state, @Nullable String error) {
    }

    /// Builds the argv for one instance. Production adds the heap and `nogui`;
    /// tests substitute a fake server.
    @FunctionalInterface
    public interface CommandFactory {
        List<String> command(McInstance instance);
    }

    private static final org.jackhuang.hmcl.util.logging.Logger LOG =
            org.jackhuang.hmcl.util.logging.Logger.LOG;

    private final McInstanceManager manager;
    private final TerminalManager terminals;
    private final McFeatureState feature;
    private final CommandFactory commandFactory;
    private final @Nullable String javaBin;
    private final @Nullable Path versionsRoot;

    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final Map<String, String> errors = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Void>> readiness = new ConcurrentHashMap<>();
    /// Windows has no PTY; those servers run as a plain child process with
    /// piped stdio. The console is then the log + a command box, not a terminal
    /// (docs/minecraft.md §15.2).
    private final Map<String, PipeServer> pipes = new ConcurrentHashMap<>();
    private static final boolean PTY_SUPPORTED =
            org.jackhuang.hmcl.util.platform.OperatingSystem.CURRENT_OS
                    == org.jackhuang.hmcl.util.platform.OperatingSystem.LINUX
            || org.jackhuang.hmcl.util.platform.OperatingSystem.CURRENT_OS
                    == org.jackhuang.hmcl.util.platform.OperatingSystem.MACOS;
    private static final Map<String, Integer> JAVA_MAJOR = new ConcurrentHashMap<>();

    /// The production wiring: `java -Xmx<memory>M -jar server.jar nogui`.
    ///
    /// @param manager   the instance manager
    /// @param terminals the terminal registry
    /// @param feature   the feature switch (start is refused while disabled or locked)
    /// @param javaBin   the java executable
    public McRuntime(McInstanceManager manager, TerminalManager terminals,
                     McFeatureState feature, String javaBin) {
        this(manager, terminals, feature, javaBin, null);
    }

    /// The production wiring plus the version metadata directory, so a launch
    /// can refuse a Java too old for the version before it starts.
    ///
    /// @param manager      the instance manager
    /// @param terminals    the terminal registry
    /// @param feature      the feature switch
    /// @param javaBin      the java executable
    /// @param versionsRoot the shared versions directory, or `null` to skip the check
    public McRuntime(McInstanceManager manager, TerminalManager terminals,
                     McFeatureState feature, String javaBin, @Nullable Path versionsRoot) {
        this(manager, terminals, feature, _instance -> List.of(
                javaBin,
                "-Xms512M",
                "-Xmx" + _instance.memoryMb() + "M",
                "-jar",
                McInstanceManager.SERVER_JAR_NAME,
                "nogui"), javaBin, versionsRoot);
    }

    /// The same, with an injectable argv for tests.
    ///
    /// @param manager        the instance manager
    /// @param terminals      the terminal registry
    /// @param feature        the feature switch
    /// @param commandFactory the argv builder
    public McRuntime(McInstanceManager manager, TerminalManager terminals,
                     McFeatureState feature, CommandFactory commandFactory) {
        this(manager, terminals, feature, commandFactory, null, null);
    }

    private McRuntime(McInstanceManager manager, TerminalManager terminals, McFeatureState feature,
                      CommandFactory commandFactory, @Nullable String javaBin, @Nullable Path versionsRoot) {
        this.manager = manager;
        this.terminals = terminals;
        this.feature = feature;
        this.commandFactory = commandFactory;
        this.javaBin = javaBin;
        this.versionsRoot = versionsRoot;
    }

    /// The state of an instance.
    ///
    /// @param id the instance id
    /// @return the status
    public Status status(String id) {
        TerminalSession session = terminals.find(BRAND, id).orElse(null);
        boolean alive = session != null || pipes.containsKey(id);
        State state = states.getOrDefault(id, State.STOPPED);
        if (!alive && (state == State.STARTING || state == State.RUNNING || state == State.STOPPING)) {
            // The registry drops a session that exited on its own; the maps follow.
            state = State.STOPPED;
        }
        return new Status(state, errors.get(id));
    }

    /// Whether the instance's server process is alive.
    ///
    /// @param id the instance id
    /// @return whether a session or a pipe process exists
    public boolean isRunning(String id) {
        return terminals.find(BRAND, id).isPresent() || pipes.containsKey(id);
    }

    /// The console of a running server. Never starts one.
    ///
    /// @param id the instance id
    /// @return the session, or empty when the server is not running
    public Optional<TerminalSession> console(String id) {
        return terminals.find(BRAND, id);
    }

    /// Starts an instance's server.
    ///
    /// @param id the instance id
    /// @throws McException when the feature is off or locked, the instance is
    ///                      unknown, not installed, or already running
    public void launch(String id) throws McException {
        if (!feature.enabled()) {
            throw new McException("the Minecraft feature is disabled");
        }
        if (feature.locked()) {
            throw new McException("the Minecraft feature is locked for another "
                    + feature.lockRemainingSeconds() + " second(s)");
        }
        McInstance instance = manager.find(id);
        if (instance == null) {
            throw new McException("Minecraft instance " + id + " does not exist");
        }
        if (terminals.find(BRAND, id).isPresent()) {
            throw new McException("the server for " + id + " is already running");
        }
        if (pipes.containsKey(id)) {
            throw new McException("the server for " + id + " is already running");
        }
        if (!Files.isRegularFile(manager.serverJar(instance))) {
            throw new McException(McInstanceManager.SERVER_JAR_NAME + " is not installed for this instance");
        }
        if (!eulaAccepted(instance)) {
            throw new McException("the EULA has not been accepted for this instance");
        }
        checkJava(instance);

        if (!PTY_SUPPORTED) {
            launchPipe(instance);
            return;
        }

        Map<String, String> environment = new HashMap<>(System.getenv());
        TerminalTarget target = new TerminalTarget(BRAND, id,
                commandFactory.command(instance), manager.instanceDirectory(id), environment);

        CompletableFuture<Void> ready = new CompletableFuture<>();
        readiness.put(id, ready);
        states.put(id, State.STARTING);
        errors.remove(id);

        TerminalSession session;
        try {
            session = terminals.open(target, PtySize.DEFAULT);
        } catch (IOException e) {
            readiness.remove(id);
            states.put(id, State.FAILED);
            errors.put(id, "failed to start the server process: " + e.getMessage());
            throw new McException("Failed to start the server for " + id, e);
        }

        session.attach(new ReadinessSink(ready));
        session.onExit(() -> {
            CompletableFuture<Void> pending = readiness.remove(id);
            boolean readyReached = pending != null && pending.isDone() && !pending.isCompletedExceptionally();
            State previous = states.getOrDefault(id, State.STOPPED);
            if (previous == State.FAILED) {
                // The readiness timeout already recorded why this server failed.
                return;
            }
            if (!readyReached && previous != State.STOPPING) {
                states.put(id, State.FAILED);
                errors.put(id, "the server exited before it was ready (code " + session.exitCode() + ")");
            } else {
                states.put(id, State.STOPPED);
            }
        });

        ready.orTimeout(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS).whenComplete((ignored, error) -> {
            if (error != null) {
                states.put(id, State.FAILED);
                errors.put(id, "the server did not become ready within "
                        + READY_TIMEOUT_SECONDS + " seconds");
                session.terminate(Duration.ofSeconds(2));
                return;
            }
            states.put(id, State.RUNNING);
            LOG.info("Minecraft instance " + id + " is ready");
        });
    }

    /// Asks the server to stop (`stop` on stdin) and kills it when it hangs.
    ///
    /// @param id the instance id
    /// @throws McException when it is not running
    public void stop(String id) throws McException {
        if (!PTY_SUPPORTED) {
            PipeServer pipe = pipes.get(id);
            if (pipe == null) {
                throw new McException("the server for " + id + " is not running");
            }
            states.put(id, State.STOPPING);
            writePipe(pipe, "stop\n");
            CompletableFuture<Void> exited = new CompletableFuture<>();
            pipe.process.onExit().thenRun(() -> exited.complete(null));
            exited.orTimeout(STOP_GRACE_SECONDS, TimeUnit.SECONDS).whenComplete((ignored, error) -> {
                if (error != null) {
                    LOG.warning("Minecraft instance " + id + " did not stop within "
                            + STOP_GRACE_SECONDS + "s; killing it");
                    pipe.process.destroyForcibly();
                }
            });
            return;
        }
        TerminalSession session = terminals.find(BRAND, id)
                .orElseThrow(() -> new McException("the server for " + id + " is not running"));
        states.put(id, State.STOPPING);
        try {
            OutputStream input = session.input();
            synchronized (input) {
                input.write("stop\n".getBytes(StandardCharsets.US_ASCII));
                input.flush();
            }
        } catch (IOException e) {
            throw new McException("Failed to write `stop` to the server for " + id, e);
        }
        CompletableFuture<Void> exited = new CompletableFuture<>();
        session.onExit(() -> exited.complete(null));
        exited.orTimeout(STOP_GRACE_SECONDS, TimeUnit.SECONDS).whenComplete((ignored, error) -> {
            if (error != null) {
                LOG.warning("Minecraft instance " + id + " did not stop within "
                        + STOP_GRACE_SECONDS + "s; killing it");
                session.terminate(Duration.ofSeconds(2));
            }
        });
    }

    /// Kills the server process group.
    ///
    /// @param id the instance id
    /// @throws McException when it is not running
    public void kill(String id) throws McException {
        PipeServer pipe = pipes.get(id);
        if (pipe != null) {
            states.put(id, State.STOPPING);
            pipe.process.destroyForcibly();
            return;
        }
        TerminalSession session = terminals.find(BRAND, id)
                .orElseThrow(() -> new McException("the server for " + id + " is not running"));
        states.put(id, State.STOPPING);
        session.terminate(Duration.ofSeconds(2));
    }

    /// The console shape of a running instance: a real terminal on POSIX, a
    /// log+command box on Windows, `null` when nothing is running.
    ///
    /// @param id the instance id
    /// @return `"pty"`, `"pipe"` or `null`
    public @Nullable String consoleMode(String id) {
        if (terminals.find(BRAND, id).isPresent()) {
            return "pty";
        }
        return pipes.containsKey(id) ? "pipe" : null;
    }

    /// Writes one console line to the running server.
    ///
    /// @param id   the instance id
    /// @param line the command (a newline is appended)
    /// @throws McException when the server is not running
    public void sendCommand(String id, String line) throws McException {
        TerminalSession session = terminals.find(BRAND, id).orElse(null);
        if (session != null) {
            try {
                OutputStream input = session.input();
                synchronized (input) {
                    input.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                    input.flush();
                }
            } catch (IOException e) {
                throw new McException("Failed to write to the server console", e);
            }
            return;
        }
        PipeServer pipe = pipes.get(id);
        if (pipe == null) {
            throw new McException("the server for " + id + " is not running");
        }
        writePipe(pipe, line + "\n");
    }

    private void launchPipe(McInstance instance) throws McException {
        String id = instance.id();
        Process process;
        try {
            Path directory = manager.instanceDirectory(id);
            Files.createDirectories(directory);
            ProcessBuilder builder = new ProcessBuilder(commandFactory.command(instance));
            builder.directory(directory.toFile());
            builder.redirectErrorStream(true);
            builder.environment().putAll(System.getenv());
            process = builder.start();
        } catch (IOException e) {
            states.put(id, State.FAILED);
            errors.put(id, "failed to start the server process: " + e.getMessage());
            throw new McException("Failed to start the server for " + id, e);
        }
        CompletableFuture<Void> ready = new CompletableFuture<>();
        readiness.put(id, ready);
        states.put(id, State.STARTING);
        errors.remove(id);

        PipeServer pipe = new PipeServer(process, manager.logFile(instance));
        pipes.put(id, pipe);
        process.onExit().thenRun(() -> finish(id, process.exitValue(), ready));
        VirtualThreads.start(() -> pumpPipe(pipe, ready));
        ready.orTimeout(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS).whenComplete((ignored, error) -> {
            if (error != null) {
                states.put(id, State.FAILED);
                errors.put(id, "the server did not become ready within "
                        + READY_TIMEOUT_SECONDS + " seconds");
                process.destroyForcibly();
                return;
            }
            states.put(id, State.RUNNING);
            LOG.info("Minecraft instance " + id + " is ready (pipe console)");
        });
    }

    private void pumpPipe(PipeServer pipe, CompletableFuture<Void> ready) {
        try {
            java.nio.file.Files.createDirectories(pipe.log.getParent());
            try (java.io.BufferedReader in = new java.io.BufferedReader(
                         new java.io.InputStreamReader(pipe.process.getInputStream(), StandardCharsets.UTF_8));
                 java.io.BufferedWriter log = java.nio.file.Files.newBufferedWriter(pipe.log,
                         StandardCharsets.UTF_8,
                         java.nio.file.StandardOpenOption.CREATE,
                         java.nio.file.StandardOpenOption.APPEND)) {
                String line;
                while ((line = in.readLine()) != null) {
                    log.write(line);
                    log.newLine();
                    log.flush();
                    if (!ready.isDone() && line.contains("Done (")) {
                        ready.complete(null);
                    }
                }
            }
        } catch (IOException e) {
            LOG.warning("Console pump ended for " + pipe.log, e);
        }
    }

    private void finish(String id, int exitCode, CompletableFuture<Void> ready) {
        pipes.remove(id);
        CompletableFuture<Void> pending = readiness.remove(id);
        boolean readyReached = pending != null && pending.isDone() && !pending.isCompletedExceptionally();
        State previous = states.getOrDefault(id, State.STOPPED);
        if (previous == State.FAILED) {
            return;
        }
        if (!readyReached && previous != State.STOPPING) {
            states.put(id, State.FAILED);
            errors.put(id, "the server exited before it was ready (code " + exitCode + ")");
        } else {
            states.put(id, State.STOPPED);
        }
    }

    private static void writePipe(PipeServer pipe, String text) throws McException {
        try {
            OutputStream stdin = pipe.process.getOutputStream();
            synchronized (stdin) {
                stdin.write(text.getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            }
        } catch (IOException e) {
            throw new McException("Failed to write to the server console", e);
        }
    }

    private record PipeServer(Process process, Path log) {
    }

    private boolean eulaAccepted(McInstance instance) {
        try {
            return Files.readString(manager.eulaFile(instance), StandardCharsets.UTF_8)
                    .strip().equals("eula=true");
        } catch (IOException e) {
            return false;
        }
    }

    /// Refuses a Java too old for the version **before** spawning anything.
    ///
    /// The version metadata carries `javaVersion.majorVersion` (written into
    /// `version.json` by [McVersions]); the panel runs `<java> -version` once and
    /// caches the result. When either side is unknown the check is skipped —
    /// the server's own error is then the fallback, not a wrong refusal.
    private void checkJava(McInstance instance) throws McException {
        if (versionsRoot == null || javaBin == null) {
            return;
        }
        int required = requiredJava(instance.version());
        if (required <= 0) {
            return;
        }
        int actual = javaMajor();
        if (actual > 0 && actual < required) {
            throw new McException("Minecraft " + instance.version() + " needs Java " + required
                    + " or newer, but `" + javaBin + "` is Java " + actual
                    + "; point mc.java_path at a newer JDK");
        }
    }

    private int requiredJava(String version) {
        Path metadata = versionsRoot.resolve(version).resolve("version.json");
        if (!Files.isRegularFile(metadata)) {
            return 0;
        }
        try {
            com.google.gson.JsonObject record =
                    org.jackhuang.hmcl.util.gson.JsonUtils.fromJsonFile(metadata,
                            com.google.gson.JsonObject.class);
            return record != null && record.has("javaMajor") ? record.get("javaMajor").getAsInt() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private int javaMajor() {
        String bin = javaBin;
        if (bin == null) {
            return 0;
        }
        return JAVA_MAJOR.computeIfAbsent(bin, candidate -> {
            try {
                Process process = new ProcessBuilder(candidate, "-version")
                        .redirectErrorStream(true)
                        .start();
                String output;
                try (var in = process.getInputStream()) {
                    output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    return 0;
                }
                java.util.regex.Matcher matcher =
                        java.util.regex.Pattern.compile("version \"(\\d+)").matcher(output);
                return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
            } catch (Exception e) {
                return 0;
            }
        });
    }

    /// Watches the output for the readiness line. Vanilla prints
    /// `Done (1.234s)! For help, type "help"`; a version that rewords it will
    /// time out rather than hang forever, and the error says so.
    private static final class ReadinessSink implements TerminalSink {
        private static final String MARKER = "Done (";
        private final CompletableFuture<Void> ready;
        private final StringBuilder partial = new StringBuilder();

        private ReadinessSink(CompletableFuture<Void> ready) {
            this.ready = ready;
        }

        @Override
        public void data(byte[] bytes, int offset, int length) {
            if (ready.isDone()) {
                return;
            }
            partial.append(new String(bytes, offset, length, StandardCharsets.UTF_8));
            int newline;
            while ((newline = partial.indexOf("\n")) >= 0) {
                String line = partial.substring(0, newline);
                partial.delete(0, newline + 1);
                if (line.contains(MARKER)) {
                    ready.complete(null);
                    return;
                }
            }
            // A server that prints without a newline must not grow this forever.
            if (partial.length() > 16 * 1024) {
                partial.delete(0, partial.length() - 4 * 1024);
            }
        }

        @Override
        public void exited(int code) {
            // The runtime's onExit listener decides what an early exit means.
        }
    }
}