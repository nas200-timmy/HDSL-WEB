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
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.shell.ShellFactory;
import org.jackhuang.hmcl.web.developer.DeveloperAuth;
import org.jackhuang.hmcl.web.audit.AuditLog;
import org.jackhuang.hmcl.web.auth.LoginThrottle;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// The M2 panel shell (docs/ssh-terminal-channel.md, milestone M2): the TUI
/// gate behind SSH authentication and, behind it, the numbered instance menu.
///
/// A session that has passed SSH key/password authentication lands at the
/// **gate**: one line asking for the developer password. Three wrong answers
/// end the connection (audited); no developer password set at all prints
/// where to set one (the web settings) and ends the connection too. A passed
/// gate reaches the **menu** — three sections, one continuous numbering,
/// line-oriented ANSI (no full screen, no arrow keys):
///
/// ```
/// dsh 实例
///   1) dsh-1
/// 品牌实例
///   2) kimi: 实例名
/// 恢复 dsh 会话
///   3) [web 使用中] 克隆仓库并分析改进点   (12:38)
/// q) 退出
/// >
/// ```
///
/// A choice is answered through [AttachSupport] ([DshAttachSupport] since
/// M3), which blocks for the whole attachment and answers the session exit
/// code; `-1` brings the menu back.
///
/// Hardening (docs/ssh-terminal-channel.md §M4): the gate has a 120-second
/// idle timeout — a connected client that never answers is cut and audited
/// `ssh.idle-timeout` — and **every wrong answer** spends a shared budget,
/// not just connections that burn all three. Fifteen wrong answers spend it;
/// the next one (the sixteenth) refuses the gate for 30 minutes for
/// everybody, from any address ([GATE_BUDGET_KEY] is a single key on
/// purpose), and the peer that spent it also has its SSH logins refused for
/// the same stretch — so hanging up after one guess buys neither a free guess
/// nor a fresh budget.
///
/// Known v1 tradeoff, deliberate: the server side of the channel has no PTY,
/// so the developer password **echoes** as the client types it — the prompt
/// says so (`(输入将回显)`). v2 gains a PTY (docs/ssh-terminal-channel.md
/// §风险登记). Everything echoed back that came from disk — instance names,
/// session titles — passes [sanitize] first so a control sequence in stored
/// data cannot inject escapes into the client's terminal.
@NotNullByDefault
public final class MenuShellFactory implements ShellFactory {

    /// Wrong gate answers before the connection is dropped.
    public static final int MAX_GATE_FAILURES = 3;

    /// The key the gate's cross-connection budget is kept under. One key for
    /// every peer on purpose: spending the budget anywhere refuses the gate
    /// everywhere, so reconnecting from another address buys nothing.
    static final String GATE_BUDGET_KEY = "gate";

    /// One selectable dsh or brand row.
    ///
    /// @param kind  `dsh` or `brand`
    /// @param id    the instance id, or `brand:instance`
    /// @param label what the menu shows
    public record Entry(String kind, String id, String label) {
    }

    private final DeveloperAuth devAuth;
    private final Supplier<List<Entry>> dshInstances;
    private final Supplier<List<Entry>> brandInstances;
    private final Supplier<List<SessionCatalog.Entry>> sessions;
    private final @Nullable AttachSupport attach;
    private final @Nullable LoginThrottle gateThrottle;
    /// Called with the peer address when the gate's budget is spent, so the
    /// channel can refuse SSH logins from that address for the throttle's
    /// block (the composition root wires this to [SshService]).
    private final @Nullable Consumer<String> banPeer;

    /// How long the gate may sit unanswered before the watchdog ends the
    /// session (docs/ssh-terminal-channel.md §M4). Package-private setter so
    /// the tests can shrink it.
    private volatile Duration gateIdleTimeout = Duration.ofSeconds(120);

    public MenuShellFactory(DeveloperAuth devAuth,
                            Supplier<List<Entry>> dshInstances,
                            Supplier<List<Entry>> brandInstances,
                            Supplier<List<SessionCatalog.Entry>> sessions,
                            @Nullable AttachSupport attach,
                            @Nullable LoginThrottle gateThrottle,
                            @Nullable Consumer<String> banPeer) {
        this.devAuth = devAuth;
        this.dshInstances = dshInstances;
        this.brandInstances = brandInstances;
        this.sessions = sessions;
        this.attach = attach;
        this.gateThrottle = gateThrottle;
        this.banPeer = banPeer;
    }

    /// Package-private test hook; see the field.
    void gateIdleTimeout(Duration timeout) {
        gateIdleTimeout = timeout;
    }

    /// One watchdog thread for every shell session; a scheduled task only
    /// ever touches its own session, so sharing is safe.
    private static final java.util.concurrent.ScheduledExecutorService SCHEDULER =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "hdsl-ssh-gate-watchdog");
                thread.setDaemon(true);
                return thread;
            });

    /// The peer's IP for the gate throttle — the same resolution
    /// [org.jackhuang.hmcl.web.ssh.SshService] uses for its audit trail,
    /// with one honest fallback: no channel (unit tests) keys on "unknown".
    static String peerAddress(@Nullable ChannelSession channel) {
        if (channel == null) {
            return "unknown";
        }
        try {
            SocketAddress remote = channel.getServerSession().getRemoteAddress();
            if (remote instanceof InetSocketAddress inet) {
                InetAddress address = inet.getAddress();
                return address == null ? inet.getHostString() : address.getHostAddress();
            }
            return String.valueOf(remote);
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    @Override
    public Command createShell(ChannelSession channel) {
        return new MenuCommand();
    }

    /// Strips everything from `text` that could hijack the terminal it is
    /// printed into: C0 controls except `\n`, DEL, ESC-led escape sequences
    /// (CSI and friends) and the C1 control range. Text between sequences
    /// survives, so a title like `ESC[31m红色ESC[0m` degrades to `红色`
    /// instead of vanishing.
    public static String sanitize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int length = text.length();
        StringBuilder clean = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                clean.append(c);
                continue;
            }
            if (c == 0x1B) {
                // Escape sequence: CSI is ESC '[' parameter/intermediate
                // bytes then one final byte 0x40-0x7E; anything else is
                // ESC, optional intermediates, one final byte.
                int end = i + 1;
                if (end < length && text.charAt(end) == '[') {
                    end++;
                    while (end < length) {
                        char s = text.charAt(end);
                        if (s >= 0x40 && s <= 0x7E) {
                            end++;
                            break;
                        }
                        if (s < 0x20 || s > 0x3F) {
                            break;
                        }
                        end++;
                    }
                } else {
                    while (end < length && text.charAt(end) >= 0x20 && text.charAt(end) <= 0x2F) {
                        end++;
                    }
                    if (end < length && text.charAt(end) >= 0x30 && text.charAt(end) <= 0x7E) {
                        end++;
                    }
                }
                i = end - 1;
                continue;
            }
            if (c < 0x20 || c == 0x7F || (c >= 0x80 && c <= 0x9F)) {
                continue;
            }
            clean.append(c);
        }
        return clean.toString();
    }

    /// One live shell session. MINA asks `start` to spawn a thread
    /// ([CommandLifecycle]), so the gate + menu loop runs on a worker and the
    /// exit callback fires from there.
    private final class MenuCommand implements Command, Runnable {

        private @Nullable InputStream in;
        private @Nullable OutputStream out;
        private @Nullable OutputStream err;
        private @Nullable ExitCallback callback;
        private @Nullable ChannelSession channel;
        private @Nullable Thread worker;
        /// Set by the watchdog when it ends the session; read by the session
        /// loop to answer a non-zero exit for the cut.
        private volatile boolean idleTimedOut;
        /// Whether the session is still before the gate — the watchdog only
        /// acts inside this window.
        private volatile boolean gatePhase;

        @Override
        public void setInputStream(InputStream in) {
            this.in = in;
        }

        @Override
        public void setOutputStream(OutputStream out) {
            this.out = out;
        }

        @Override
        public void setErrorStream(OutputStream err) {
            this.err = err;
        }

        @Override
        public void setExitCallback(ExitCallback callback) {
            this.callback = callback;
        }

        @Override
        public void start(ChannelSession channel, Environment env) {
            this.channel = channel;
            Thread thread = new Thread(this, "hdsl-ssh-menu");
            thread.setDaemon(true);
            worker = thread;
            thread.start();
        }

        @Override
        public void run() {
            int code = session();
            ExitCallback exit = callback;
            if (exit != null) {
                try {
                    exit.onExit(code);
                } catch (RuntimeException ignored) {
                    // The channel may already be gone; there is nobody to tell.
                }
            }
        }

        @Override
        public void destroy(ChannelSession channel) {
            Thread thread = worker;
            if (thread != null) {
                thread.interrupt();
            }
        }

        /// The whole conversation: gate, then menu until the client leaves.
        /// An [IOException] anywhere here is a disconnected client, which is
        /// a normal end, not an error worth a stack trace.
        private int session() {
            InputStream input = in;
            OutputStream output = out;
            if (input == null || output == null) {
                return 0;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            // The idle timeout guards only the gate phase (the read of the
            // developer password): a client that connects and then sits there
            // must not hold the line forever, and an attach in progress is a
            // real session, not an idle one.
            gatePhase = true;
            ScheduledFuture<?> watchdog = SCHEDULER.schedule(
                    () -> idleTimeout(input, output), gateIdleTimeout.toMillis(), TimeUnit.MILLISECONDS);
            try {
                int gate = gate(reader, output);
                if (gate >= 0) {
                    return gate;
                }
                gatePhase = false;
                watchdog.cancel(false);
                return menu(reader, input, output);
            } catch (IOException e) {
                return idleTimedOut ? 1 : 0;
            } finally {
                gatePhase = false;
                watchdog.cancel(false);
            }
        }

        /// What the watchdog does when the gate stays quiet for too long:
        /// say so, audit it, and end the session from every side at once —
        /// the channel close unblocks a production read, the stream close
        /// covers a plain pipe, the interrupt is the last resort.
        private void idleTimeout(InputStream input, OutputStream output) {
            if (!gatePhase) {
                return;
            }
            idleTimedOut = true;
            AuditLog.log(AuditLog.Channel.SSH, "ssh.idle-timeout", null, "blocked", null, null);
            try {
                line(output, "空闲超时，连接已断开。");
            } catch (IOException ignored) {
                // Nobody is reading anyway.
            }
            ChannelSession ch = channel;
            if (ch != null) {
                try {
                    ch.close(true);
                } catch (RuntimeException ignored) {
                    // The session is ending; a failed close changes nothing.
                }
            }
            try {
                input.close();
            } catch (IOException | RuntimeException ignored) {
                // Same.
            }
            Thread thread = worker;
            if (thread != null) {
                thread.interrupt();
            }
        }

        /// The developer-password gate. Wrong answers are counted per
        /// connection (three end it); every connection that reaches its third
        /// is also recorded once against the peer's IP in the gate throttle,
        /// so an attacker reconnecting for three more guesses spends the
        /// shared budget instead of getting a fresh one. See
        /// [org.jackhuang.hmcl.web.ssh.SshService] for the throttle's
        /// semantics in this role.
        ///
        /// @return the exit code to end the session with, or -1 when the gate
        /// passed and the menu may run
        private int gate(BufferedReader reader, OutputStream output) throws IOException {
            if (!devAuth.isSet()) {
                line(output, "开发者密码未设置。请先在网页端「设置 → SSH 通道」中设置开发者密码，"
                        + "然后再从 SSH 终端进入。");
                return 0;
            }
            String peer = peerAddress(channel);
            LoginThrottle throttle = gateThrottle;
            if (throttle != null && throttle.isBlocked(GATE_BUDGET_KEY, Instant.now())) {
                AuditLog.log(AuditLog.Channel.SSH, "ssh.gate", null, "blocked", null, peer);
                line(output, bannedFor(throttle));
                return 1;
            }
            int failures = 0;
            while (true) {
                output.write("开发者密码: (输入将回显) ".getBytes(StandardCharsets.UTF_8));
                output.flush();
                String answer = reader.readLine();
                if (answer == null) {
                    return 0;
                }
                if (devAuth.verify(answer.trim())) {
                    AuditLog.log(AuditLog.Channel.SSH, "ssh.gate", null, "success", null, peer);
                    if (throttle != null) {
                        throttle.clear(GATE_BUDGET_KEY);
                    }
                    return -1;
                }
                failures++;
                // Every wrong answer spends the gate's budget, whether or not
                // the connection lives to see its third strike: a client that
                // hangs up after one guess must not get that guess for free.
                Duration refused = throttle == null
                        ? Duration.ZERO
                        : throttle.recordFailure(GATE_BUDGET_KEY, Instant.now());
                AuditLog.log(AuditLog.Channel.SSH, "ssh.gate", null,
                        refused.isZero() ? "failure" : "blocked", null, peer);
                if (!refused.isZero()) {
                    // The budget is spent: the channel also refuses this peer's
                    // SSH logins for the same stretch, so reconnecting to spend
                    // the next budget is not possible either.
                    if (banPeer != null) {
                        banPeer.accept(peer);
                    }
                    line(output, bannedFor(throttle));
                    return 1;
                }
                if (failures >= MAX_GATE_FAILURES) {
                    line(output, "失败次数过多，连接已断开。");
                    return 1;
                }
                line(output, "密码错误，请重试（还剩 " + (MAX_GATE_FAILURES - failures) + " 次机会）。");
            }
        }

        /// What a refused gate says: the refusal length and the fact that
        /// moving to another address does not shorten it.
        private String bannedFor(LoginThrottle throttle) {
            return "失败次数过多，已封禁 " + throttle.block().toMinutes() + " 分钟（换 IP 也需等待）。";
        }

        /// The menu loop: render, read a choice, either leave with the
        /// attach's session code or loop again. EOF and `q` leave with 0.
        ///
        /// @return the exit code the session ends with
        private int menu(BufferedReader reader, InputStream input, OutputStream output) throws IOException {
            while (true) {
                List<Entry> dshRows = supply(dshInstances);
                List<Entry> brandRows = supply(brandInstances);
                List<SessionCatalog.Entry> sessionRows = supply(sessions);
                render(output, dshRows, brandRows, sessionRows);

                output.write("> ".getBytes(StandardCharsets.UTF_8));
                output.flush();
                String answer = reader.readLine();
                if (answer == null) {
                    return 0;
                }
                String choice = answer.trim();
                if (choice.equalsIgnoreCase("q") || choice.equalsIgnoreCase("exit")) {
                    return 0;
                }
                Integer number = parseChoice(choice);
                int total = dshRows.size() + brandRows.size() + sessionRows.size();
                if (number == null || number < 1 || number > total) {
                    line(output, "无效输入，请输入菜单编号或 q。");
                    continue;
                }
                int code = select(number, dshRows, brandRows, sessionRows, input, output);
                if (code >= 0) {
                    return code;
                }
            }
        }

        /// Resolves one menu number and hands the channel to [AttachSupport].
        /// A failed attachment says so and the menu comes back; the menu must
        /// not die of a target's problem.
        ///
        /// @return the attach's session code, or `-1` when the menu continues
        private int select(int number, List<Entry> dshRows, List<Entry> brandRows,
                           List<SessionCatalog.Entry> sessionRows, InputStream input, OutputStream output) {
            int index = number - 1;
            String kind;
            String id;
            if (index < dshRows.size()) {
                kind = "dsh";
                id = dshRows.get(index).id();
            } else if ((index -= dshRows.size()) < brandRows.size()) {
                kind = "brand";
                id = brandRows.get(index).id();
            } else {
                index -= brandRows.size();
                kind = "session";
                id = sessionRows.get(index).id();
            }
            AttachSupport hook = attach;
            if (hook == null) {
                lineQuiet(output, "终端接入未启用。");
                return -1;
            }
            try {
                return hook.attach(kind, id, input, output, channel);
            } catch (IOException | RuntimeException e) {
                String message = e.getMessage();
                lineQuiet(output, "attach 失败：" + sanitize(message == null ? e.toString() : message));
                return -1;
            }
        }
    }

    private static void render(OutputStream output, List<Entry> dshRows, List<Entry> brandRows,
                               List<SessionCatalog.Entry> sessionRows) throws IOException {
        StringBuilder screen = new StringBuilder();
        screen.append("\r\n");
        int number = 1;
        screen.append("dsh 实例\r\n");
        if (dshRows.isEmpty()) {
            screen.append("  (无)\r\n");
        }
        for (Entry row : dshRows) {
            row(screen, number++, sanitize(row.label()));
        }
        screen.append("品牌实例\r\n");
        if (brandRows.isEmpty()) {
            screen.append("  (无)\r\n");
        }
        for (Entry row : brandRows) {
            row(screen, number++, sanitize(row.label()));
        }
        screen.append("恢复 dsh 会话\r\n");
        if (sessionRows.isEmpty()) {
            screen.append("  (无)\r\n");
        }
        for (SessionCatalog.Entry row : sessionRows) {
            row(screen, number++, sessionLabel(row));
        }
        screen.append("q) 退出\r\n");
        output.write(screen.toString().getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static void row(StringBuilder screen, int number, String label) {
        screen.append("  ").append(number).append(") ").append(label).append("\r\n");
    }

    private static String sessionLabel(SessionCatalog.Entry entry) {
        StringBuilder label = new StringBuilder();
        if (entry.inUse()) {
            label.append("[web 使用中] ");
        }
        label.append(sanitize(entry.title()));
        label.append("   (").append(formatActivity(entry.activity())).append(')');
        return label.toString();
    }

    private static String formatActivity(Instant activity) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate day = activity.atZone(zone).toLocalDate();
        String pattern = day.equals(LocalDate.now(zone)) ? "HH:mm" : "MM-dd HH:mm";
        return DateTimeFormatter.ofPattern(pattern, Locale.ROOT).format(activity.atZone(zone));
    }

    private static void line(OutputStream output, String text) throws IOException {
        output.write((text + "\r\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    /// [line] for call sites that have promised not to throw: a terminal
    /// that is already gone says nothing.
    private static void lineQuiet(OutputStream output, String text) {
        try {
            line(output, text);
        } catch (IOException e) {
            // The terminal is already gone; nothing left to tell.
        }
    }

    private static @Nullable Integer parseChoice(String choice) {
        try {
            return Integer.valueOf(choice);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /// A data supplier must never take the session down: a broken instance
    /// list shows an empty section, nothing more.
    private static <T> List<T> supply(Supplier<List<T>> supplier) {
        try {
            List<T> rows = supplier.get();
            return rows == null ? List.of() : rows;
        } catch (RuntimeException e) {
            LOG.warning("SSH menu data source failed", e);
            return List.of();
        }
    }
}
