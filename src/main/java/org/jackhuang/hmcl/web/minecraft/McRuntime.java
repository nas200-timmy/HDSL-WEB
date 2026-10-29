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

    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final Map<String, String> errors = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Void>> readiness = new ConcurrentHashMap<>();

    /// The production wiring: `java -Xmx<memory>M -jar server.jar nogui`.
    ///
    /// @param manager  the instance manager
    /// @param terminals the terminal registry
    /// @param feature  the feature switch (start is refused while disabled or locked)
    /// @param javaBin  the java executable
    public McRuntime(McInstanceManager manager, TerminalManager terminals,
                     McFeatureState feature, String javaBin) {
        this(manager, terminals, feature, instance -> List.of(
                javaBin,
                "-Xms512M",
                "-Xmx" + instance.memoryMb() + "M",
                "-jar",
                McInstanceManager.SERVER_JAR_NAME,
                "nogui"));
    }

    /// The same, with an injectable argv for tests.
    ///
    /// @param manager        the instance manager
    /// @param terminals      the terminal registry
    /// @param feature        the feature switch
    /// @param commandFactory the argv builder
    public McRuntime(McInstanceManager manager, TerminalManager terminals,
                     McFeatureState feature, CommandFactory commandFactory) {
        this.manager = manager;
        this.terminals = terminals;
        this.feature = feature;
        this.commandFactory = commandFactory;
    }

    /// The state of an instance.
    ///
    /// @param id the instance id
    /// @return the status
    public Status status(String id) {
        TerminalSession session = terminals.find(BRAND, id).orElse(null);
        State state = states.getOrDefault(id, State.STOPPED);
        if (session == null && (state == State.STARTING || state == State.RUNNING || state == State.STOPPING)) {
            // The registry drops a session that exited on its own; the maps follow.
            state = State.STOPPED;
        }
        return new Status(state, errors.get(id));
    }

    /// Whether the instance's server process is alive.
    ///
    /// @param id the instance id
    /// @return whether a session exists
    public boolean isRunning(String id) {
        return terminals.find(BRAND, id).isPresent();
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
        if (!Files.isRegularFile(manager.serverJar(instance))) {
            throw new McException(McInstanceManager.SERVER_JAR_NAME + " is not installed for this instance");
        }
        if (!eulaAccepted(instance)) {
            throw new McException("the EULA has not been accepted for this instance");
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
        TerminalSession session = terminals.find(BRAND, id)
                .orElseThrow(() -> new McException("the server for " + id + " is not running"));
        states.put(id, State.STOPPING);
        session.terminate(Duration.ofSeconds(2));
    }

    private boolean eulaAccepted(McInstance instance) {
        try {
            return Files.readString(manager.eulaFile(instance), StandardCharsets.UTF_8)
                    .strip().equals("eula=true");
        } catch (IOException e) {
            return false;
        }
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