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
package org.jackhuang.hmcl.web.terminal;

import org.jackhuang.hmcl.web.audit.AuditLog;
import org.jackhuang.hmcl.web.terminal.pty.Pty;
import org.jackhuang.hmcl.web.terminal.pty.PtySize;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/// The registry of running terminals: one per instance, at most.
///
/// The manager owns the lifecycle the web layer cannot be trusted with:
/// opening is refused while the instance already has a running terminal,
/// terminating goes through [TerminalSession#terminate] (process group), and
/// a session that exits on its own removes itself. Closing the manager ends
/// every terminal — the panel is the parent process, not a bystander.
@NotNullByDefault
public final class TerminalManager implements AutoCloseable {

    /// How much output a reattaching viewer gets back.
    public static final int DEFAULT_REPLAY_BYTES = 256 * 1024;

    private final Map<String, TerminalSession> sessions = new ConcurrentHashMap<>();
    private final int replayBytes;

    public TerminalManager() {
        this(DEFAULT_REPLAY_BYTES);
    }

    public TerminalManager(int replayBytes) {
        if (replayBytes < 0) {
            throw new IllegalArgumentException("negative replay size");
        }
        this.replayBytes = replayBytes;
    }

    /// Starts a terminal for `target`.
    ///
    /// @throws TerminalBusyException when the instance already has one running
    /// @throws IOException when the PTY or the agent cannot be started
    public synchronized TerminalSession open(TerminalTarget target, PtySize size) throws IOException {
        TerminalSession existing = sessions.get(target.instanceId());
        if (existing != null) {
            if (existing.state() == TerminalSession.State.RUNNING) {
                throw new TerminalBusyException(target.instanceId());
            }
            sessions.remove(target.instanceId(), existing);
        }
        Pty pty = Pty.spawn(target.command(), target.cwd(), target.environment(), size);
        TerminalSession session = new TerminalSession(target.instanceId(), pty, replayBytes);
        sessions.put(target.instanceId(), session);
        AuditLog.log(AuditLog.Channel.TERMINAL, "terminal.open", target.instanceId(), "success", null, null);
        session.onExit(() -> {
            sessions.remove(target.instanceId(), session);
            AuditLog.log(AuditLog.Channel.TERMINAL, "terminal.exit", target.instanceId(),
                    String.valueOf(session.exitCode()), null, null);
        });
        return session;
    }

    /// The running terminal of an instance, if any.
    public Optional<TerminalSession> find(String instanceId) {
        return Optional.ofNullable(sessions.get(instanceId));
    }

    /// Kills the instance's terminal, if one is running.
    ///
    /// @return whether there was a session to kill
    public boolean terminate(String instanceId) {
        TerminalSession session = sessions.remove(instanceId);
        if (session == null) {
            return false;
        }
        session.terminate(java.time.Duration.ofSeconds(2));
        AuditLog.log(AuditLog.Channel.TERMINAL, "terminal.kill", instanceId, "success", null, null);
        return true;
    }

    /// How many terminals are tracked (running or just exited).
    public int size() {
        return sessions.size();
    }

    /// Ends every terminal. Called when the panel stops.
    @Override
    public void close() {
        for (TerminalSession session : sessions.values()) {
            try {
                session.close();
            } catch (RuntimeException ignored) {
                // A terminal that is already gone must not keep the others
                // alive during shutdown.
            }
        }
        sessions.clear();
    }
}
