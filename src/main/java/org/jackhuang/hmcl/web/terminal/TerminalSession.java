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

import org.jackhuang.hmcl.util.platform.VirtualThreads;
import org.jackhuang.hmcl.web.terminal.pty.Pty;
import org.jackhuang.hmcl.web.terminal.pty.PtySize;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

/// One running agent TUI, detached from any viewer.
///
/// The PTY process belongs to the session, not to a WebSocket: closing the
/// browser only [detach]es. The session keeps the last [replayBytes] of output
/// and hands it to the next viewer, so a refresh comes back to the same screen
/// — while nothing about the agent is restarted.
///
/// When the child exits, the pump drains the last bytes, marks the session
/// [State#EXITED], tells every attached sink the exit code, and answers
/// [#waitFor]. Input is refused from that moment on: the terminal can never be
/// handed to anything else.
@NotNullByDefault
public final class TerminalSession {

    public enum State {
        RUNNING,
        EXITED
    }

    private final String instanceId;
    private final Pty pty;
    private final byte[] replay;
    private final Object lock = new Object();
    private final List<TerminalSink> sinks = new ArrayList<>();
    private final List<Runnable> exitListeners = new CopyOnWriteArrayList<>();
    private final CountDownLatch exited = new CountDownLatch(1);
    private final Thread pump;
    private int replayCount;
    private volatile State state = State.RUNNING;
    private volatile int exitCode = Integer.MIN_VALUE;

    /// @param replayBytes how much output is kept for a reattach
    public TerminalSession(String instanceId, Pty pty, int replayBytes) {
        if (replayBytes < 0) {
            throw new IllegalArgumentException("negative replay size");
        }
        this.instanceId = instanceId;
        this.pty = pty;
        this.replay = new byte[replayBytes];
        this.pump = VirtualThreads.start(this::pump);
    }

    public String instanceId() {
        return instanceId;
    }

    public State state() {
        return state;
    }

    /// The child's exit code once [State#EXITED], `Integer.MIN_VALUE` before.
    public int exitCode() {
        return exitCode;
    }

    /// Bytes typed at the terminal.
    public OutputStream input() {
        return pty.input();
    }

    /// Tells the child the viewer's terminal changed size.
    public void resize(PtySize size) {
        pty.resize(size);
    }

    /// Attaches a viewer, replaying what it missed first.
    ///
    /// The snapshot and the registration happen under the same lock the pump
    /// broadcasts under, so a viewer sees every byte exactly once: either in
    /// the snapshot or live, never both and never neither.
    public void attach(TerminalSink sink) {
        synchronized (lock) {
            if (state == State.RUNNING) {
                if (replayCount > 0) {
                    byte[] snapshot = Arrays.copyOf(replay, replayCount);
                    sink.data(snapshot, 0, snapshot.length);
                }
                sinks.add(sink);
                return;
            }
        }
        sink.exited(exitCode);
    }

    /// Removes a viewer. The agent keeps running.
    public void detach(TerminalSink sink) {
        synchronized (lock) {
            sinks.remove(sink);
        }
    }

    /// Ends the child's process group, waiting up to `grace` for the exit.
    public void terminate(Duration grace) {
        pty.terminate(grace);
        try {
            // The pump reports the exit; give it the same grace again so a
            // caller that terminates and closes sees a settled session.
            exited.await(Math.max(1, grace.toMillis()) + 500, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /// Runs `listener` once the child is gone. Used by the manager to drop the
    /// instance → session mapping without polling.
    public void onExit(Runnable listener) {
        if (state == State.EXITED) {
            listener.run();
            return;
        }
        exitListeners.add(listener);
        // The pump may have finished between the check and the add; the
        // listener must still run exactly once.
        if (state == State.EXITED && exitListeners.remove(listener)) {
            listener.run();
        }
    }

    /// Waits for the child and returns its exit code.
    public int waitFor() {
        try {
            exited.await();
            return exitCode;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    /// Terminates the child and releases the terminal.
    public void close() {
        terminate(Duration.ofSeconds(2));
        pty.close();
    }

    private void pump() {
        byte[] buffer = new byte[16 * 1024];
        try (InputStream output = pty.output()) {
            int n;
            while ((n = output.read(buffer, 0, buffer.length)) >= 0) {
                broadcast(buffer, 0, n);
            }
        } catch (IOException ignored) {
            // The PTY was closed under us (shutdown or a broken child); the
            // exit path below still runs and reports it.
        } finally {
            int code = pty.waitFor();
            exitCode = code;
            state = State.EXITED;
            synchronized (lock) {
                for (TerminalSink sink : sinks) {
                    sink.exited(code);
                }
                sinks.clear();
            }
            exited.countDown();
            for (Runnable listener : exitListeners) {
                listener.run();
            }
        }
    }

    private void broadcast(byte[] data, int offset, int length) {
        synchronized (lock) {
            appendReplay(data, offset, length);
            for (TerminalSink sink : sinks) {
                sink.data(data, offset, length);
            }
        }
    }

    /// Keeps the most recent bytes, dropping the oldest when full.
    private void appendReplay(byte[] data, int offset, int length) {
        if (replay.length == 0) {
            return;
        }
        if (length >= replay.length) {
            System.arraycopy(data, offset + length - replay.length, replay, 0, replay.length);
            replayCount = replay.length;
            return;
        }
        if (replayCount + length > replay.length) {
            int drop = replayCount + length - replay.length;
            System.arraycopy(replay, drop, replay, 0, replayCount - drop);
            replayCount -= drop;
        }
        System.arraycopy(data, offset, replay, replayCount, length);
        replayCount += length;
    }
}
