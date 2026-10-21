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
package org.jackhuang.hmcl.web.ssh;

import org.apache.sshd.server.Environment;
import org.apache.sshd.server.Signal;
import org.apache.sshd.server.StandardEnvironment;
import org.apache.sshd.server.channel.ChannelSession;
import org.jackhuang.hmcl.dsh.DshException;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshInstanceManager;
import org.jackhuang.hmcl.dsh.DshLauncher;
import org.jackhuang.hmcl.util.ProcessTree;
import org.jackhuang.hmcl.web.audit.AuditLog;
import org.jackhuang.hmcl.web.instance.InstanceRuntime;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

/// The M3 attach behind a menu choice (docs/ssh-terminal-channel.md §M3):
/// spawns `dsh tui [--resume <uuid>]` for the chosen dsh instance and pumps
/// bytes between the child and the SSH channel until either side ends.
///
/// The spawn spec is [DshLauncher.plan] — the exact entry the web launch
/// uses — so the account route in the profile patch, the provider key in
/// the environment, the instance's own Node runtime ahead on `PATH`, and
/// `DSH_HOME` are whatever a web launch would produce; only the surface is
/// swapped for `tui` and the working directory is the instance's home
/// (which is also the `DSH_HOME`, so `dsh tui --resume` finds the sessions
/// the menu listed). No port is settled, no readiness line is awaited — a
/// deliberately thinner launch. Brand instances answer with a deferred
/// note: their CLIs are a later milestone, and `dsh tui`'s way of asking
/// does not translate to them anyway.
///
/// v1 runs the child on plain pipes, as the menu promises: `dsh tui`
/// degrades to a dumb-terminal rendering, and a full-screen app that
/// insists on tty ioctls misbehaves — the documented trade, v2 gains a
/// real PTY. `TERM` and the initial `COLUMNS`/`LINES` travel from the
/// channel's pty request (defaulting `TERM` to `xterm-256color`), and a
/// window-change request is forwarded as `SIGWINCH` to the process tree,
/// best effort. When the channel breaks first the whole tree is taken
/// down with [ProcessTree] — a TUI left behind would hold the home and
/// the terminal nobody is reading.
///
/// One attachment per home at a time, keyed by the home's real path: two
/// menus choosing the same instance would interleave their bytes on one
/// terminal, and the harness's own session lock deserves a clear answer.
@NotNullByDefault
public final class DshAttachSupport implements AttachSupport {

    /// How long a tree gets to die politely before it is forced.
    private static final long GRACE_SECONDS = 5;

    /// Homes an attachment is currently holding, by normalized real path.
    private final Set<String> busyHomes = ConcurrentHashMap.newKeySet();

    /// Test seam, package-private: rewrites the process about to be spawned.
    /// The fake-dsh fixture points the command at itself and switches its
    /// mode through the environment; production never sets it.
    private volatile @Nullable UnaryOperator<ProcessBuilder> spawnHook;

    @Override
    public int attach(String kind, String id, InputStream in, OutputStream out, ChannelSession channel)
            throws IOException {
        return switch (kind) {
            case "dsh" -> attachInstance(id, null, in, out, channel, kind + ":" + id);
            case "session" -> {
                int split = id.indexOf(':');
                if (split <= 0 || split == id.length() - 1) {
                    line(out, "会话编号无法解析：" + MenuShellFactory.sanitize(id));
                    yield -1;
                }
                yield attachInstance(id.substring(0, split), id.substring(split + 1),
                        in, out, channel, kind + ":" + id);
            }
            case "brand" -> {
                line(out, "品牌实例的终端接入将在后续版本提供。");
                yield -1;
            }
            default -> {
                line(out, "未知的目标类型：" + MenuShellFactory.sanitize(kind));
                yield -1;
            }
        };
    }

    /// Refusals print one line and answer `-1`, which brings the menu back.
    private int attachInstance(String instanceId, @Nullable String resume, InputStream in, OutputStream out,
                               ChannelSession channel, String auditObject) throws IOException {
        DshInstance instance = DshInstanceManager.find(instanceId);
        if (instance == null) {
            line(out, "实例 " + MenuShellFactory.sanitize(instanceId) + " 不存在。");
            return -1;
        }
        Path home;
        try {
            home = instance.homeDirectory().toAbsolutePath().normalize();
        } catch (DshException | RuntimeException e) {
            line(out, "无法确定实例 home：" + MenuShellFactory.sanitize(message(e)));
            return -1;
        }
        if (!busyHomes.add(home.toString())) {
            line(out, "该实例已有终端会话。");
            return -1;
        }
        try {
            return spawnAndPump(instance, resume, home, in, out, channel, auditObject);
        } finally {
            busyHomes.remove(home.toString());
        }
    }

    /// Builds the plan (the account-injection half of a web launch, reused
    /// whole), swaps the surface for `tui`, spawns and pumps. Every failure
    /// before the process exists is a one-line refusal, not an exception.
    private int spawnAndPump(DshInstance instance, @Nullable String resume, Path home,
                             InputStream in, OutputStream out, ChannelSession channel, String auditObject)
            throws IOException {
        DshLauncher.LaunchPlan plan;
        try {
            plan = DshLauncher.plan(instance, InstanceRuntime.accountOf(instance));
        } catch (DshException | RuntimeException e) {
            // Not installed (no dsh directory), Node runtime missing — the
            // launcher's words are the right words for a person at a terminal.
            line(out, "无法启动：" + MenuShellFactory.sanitize(message(e)));
            return -1;
        }

        List<String> command = new ArrayList<>(
                List.of(plan.command().get(0), plan.command().get(1), "tui"));
        if (resume != null) {
            command.add("--resume");
            command.add(resume);
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(home.toFile());
        builder.environment().putAll(plan.environment());
        applyPtyEnvironment(builder, channel);
        UnaryOperator<ProcessBuilder> hook = spawnHook;
        if (hook != null) {
            builder = hook.apply(builder);
        }

        Process process;
        try {
            process = builder.start();
        } catch (IOException | RuntimeException e) {
            line(out, "无法启动：" + MenuShellFactory.sanitize(message(e)));
            return -1;
        }
        AuditLog.log(AuditLog.Channel.SSH, "ssh.attach", auditObject, "success", null, null);
        forwardSignals(channel, process);
        return pump(process, in, out, auditObject);
    }

    /// Pumps both directions until the process ends or the channel side
    /// breaks. Returns the exit code the session should end with: the
    /// process's own code, or `0` when the channel broke first (the tree is
    /// already gone; the dead channel shows no exit status anyway).
    private int pump(Process process, InputStream in, OutputStream out, String auditObject) {
        Object outLock = new Object();
        AtomicBoolean channelDriven = new AtomicBoolean();

        Thread.startVirtualThread(() -> {
            try {
                in.transferTo(process.getOutputStream());
                process.getOutputStream().close();
            } catch (IOException | RuntimeException ignored) {
                // The channel broke or the process is gone — both are this
                // pump's normal end.
            }
            if (process.isAlive()) {
                // Stdin ended while the process lives: the session is
                // leaving, so the whole tree goes with it.
                channelDriven.set(true);
                ProcessTree.terminate(process, GRACE_SECONDS);
            }
        });
        Thread stdout = Thread.startVirtualThread(() -> pumpStream(process.getInputStream(), out, outLock));
        Thread stderr = Thread.startVirtualThread(() -> pumpStream(process.getErrorStream(), out, outLock));

        int code;
        try {
            code = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ProcessTree.terminate(process, 0);
            code = 0;
        }
        // The child is done: its streams hit EOF and the pumps drain what is
        // left. Waiting for them here makes attach() deterministic for its
        // caller — when it returns, everything the child wrote has reached
        // the terminal.
        try {
            stdout.join(5000);
            stderr.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (channelDriven.get()) {
            AuditLog.log(AuditLog.Channel.SSH, "ssh.detach", auditObject, "success", null, null);
            return 0;
        }
        if (code != 0) {
            AuditLog.log(AuditLog.Channel.SSH, "ssh.attach", auditObject, "failure", null, null);
        }
        return code;
    }

    /// One direction of the byte pump: whole chunks, written under a shared
    /// lock so stdout and stderr do not interleave mid-line on the terminal.
    private static void pumpStream(InputStream from, OutputStream to, Object outLock) {
        byte[] buffer = new byte[8192];
        try {
            int read;
            while ((read = from.read(buffer)) != -1) {
                synchronized (outLock) {
                    to.write(buffer, 0, read);
                    to.flush();
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // The process died or the channel broke — normal end.
        }
    }

    /// `TERM` and the initial console size from the channel's pty request;
    /// `TERM` defaults to `xterm-256color` when the client asked for no pty
    /// (or the menu stage never saw one). Everything here is best effort.
    private static void applyPtyEnvironment(ProcessBuilder builder, @Nullable ChannelSession channel) {
        Map<String, String> child = builder.environment();
        if (channel == null) {
            child.put("TERM", "xterm-256color");
            return;
        }
        try {
            Map<String, String> env = channel.getEnvironment().getEnv();
            String term = env.get(Environment.ENV_TERM);
            child.put("TERM", term == null || term.isBlank() ? "xterm-256color" : term);
            String columns = env.get(Environment.ENV_COLUMNS);
            if (columns != null) {
                child.put("COLUMNS", columns);
            }
            String lines = env.get(Environment.ENV_LINES);
            if (lines != null) {
                child.put("LINES", lines);
            }
        } catch (RuntimeException e) {
            child.put("TERM", "xterm-256color");
        }
    }

    /// A window-change request reaches the child as SIGWINCH, best effort:
    /// the process tree is told by pid, and a failure to say so is not an
    /// incident worth a log line per keystroke.
    private static void forwardSignals(@Nullable ChannelSession channel, Process process) {
        if (channel == null) {
            return;
        }
        try {
            StandardEnvironment environment = channel.getEnvironment();
            environment.addSignalListener((ch, signal) -> {
                if (signal == Signal.WINCH && process.isAlive()) {
                    signalWinch(process);
                }
            }, List.of(Signal.WINCH));
        } catch (RuntimeException ignored) {
            // Signals are a courtesy; the channel without them still pumps.
        }
    }

    private static void signalWinch(Process process) {
        try {
            new ProcessBuilder("kill", "-WINCH", Long.toString(process.pid()))
                    .start()
                    .waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException ignored) {
            // Best effort by contract.
        }
    }

    private static String message(Throwable error) {
        String message = error.getMessage();
        return message == null ? error.toString() : message;
    }

    private static void line(OutputStream output, String text) throws IOException {
        output.write((text + "\r\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    /// Package-private test seam; see the field.
    void spawnHook(@Nullable UnaryOperator<ProcessBuilder> hook) {
        this.spawnHook = hook;
    }
}
