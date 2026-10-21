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

import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.jackhuang.hmcl.util.logging.Logger;
import org.jackhuang.hmcl.web.auth.UserStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The M2 panel shell: the TUI gate (set/unset/wrong×3), the three-section
/// menu it leads to, the control-character filter on everything stored that
/// gets echoed back, and the exit paths. Each case drives a real [Command]
/// from [MenuShellFactory.createShell] with bytes on stdin — no network,
/// exactly how MINA documents the Command contract.
class MenuShellFactoryTest {

    private static final String PANEL_PASSWORD = "panel-password-1";
    private static final String DEV_PASSWORD = "dev-password-1";

    @TempDir
    Path dataDir;

    /// One finished session: what the exit callback saw and what the
    /// terminal received.
    private record Session(int exitCode, String output) {
    }

    private DeveloperAuth authWithPassword() throws Exception {
        UserStore users = UserStore.open(dataDir, Map.of(UserStore.ENV_ADMIN_PASSWORD, PANEL_PASSWORD),
                Logger.LOG);
        DeveloperAuth auth = DeveloperAuth.open(dataDir);
        auth.set(DEV_PASSWORD, users);
        return auth;
    }

    private MenuShellFactory factory(DeveloperAuth auth,
                                     List<MenuShellFactory.Entry> dsh,
                                     List<MenuShellFactory.Entry> brands,
                                     List<SessionCatalog.Entry> sessions) {
        return factory(auth, dsh, brands, sessions, new PrintingAttachSupport(), null);
    }

    private MenuShellFactory factory(DeveloperAuth auth,
                                     List<MenuShellFactory.Entry> dsh,
                                     List<MenuShellFactory.Entry> brands,
                                     List<SessionCatalog.Entry> sessions,
                                     AttachSupport attach,
                                     org.jackhuang.hmcl.web.auth.LoginThrottle gateThrottle) {
        return new MenuShellFactory(auth, () -> dsh, () -> brands, () -> sessions, attach, gateThrottle);
    }

    /// The acknowledgement stand-in the menu tests drive: prints the choice,
    /// answers `-1`, and the menu comes back. (Production attaches through
    /// DshAttachSupport; that path is covered by DshAttachSupportTest.)
    private static final class PrintingAttachSupport implements AttachSupport {
        @Override
        public int attach(String kind, String id, InputStream in, OutputStream out, ChannelSession channel)
                throws IOException {
            byte[] line = ("已选择 " + kind + ":" + id + "。\r\n").getBytes(StandardCharsets.UTF_8);
            out.write(line);
            out.flush();
            return -1;
        }
    }

    /// Feeds `input` to a fresh shell and waits for the exit callback.
    private Session run(MenuShellFactory factory, String input) throws Exception {
        return run(factory, new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
    }

    /// Drives a fresh shell from `stdin` — which may stay open, like a real
    /// channel — and waits for the exit callback.
    private Session run(MenuShellFactory factory, InputStream stdin) throws Exception {
        Command command = factory.createShell(null);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        CountDownLatch exited = new CountDownLatch(1);
        AtomicInteger exitCode = new AtomicInteger(Integer.MIN_VALUE);
        command.setInputStream(stdin);
        command.setOutputStream(stdout);
        command.setErrorStream(stderr);
        command.setExitCallback((code, message, closeImmediately) -> {
            exitCode.set(code);
            exited.countDown();
        });
        command.start(null, null);
        assertTrue(exited.await(15, TimeUnit.SECONDS), "the shell should exit on its own");
        return new Session(exitCode.get(), stdout.toString(StandardCharsets.UTF_8));
    }

    // ----------------------------------------------------------------- gate --

    @Test
    void gateSuccessShowsAllThreeSections() throws Exception {
        MenuShellFactory factory = factory(authWithPassword(),
                List.of(new MenuShellFactory.Entry("dsh", "dsh-1", "dsh-1")),
                List.of(new MenuShellFactory.Entry("brand", "kimi:abc", "kimi: 测试实例")),
                List.of());
        Session session = run(factory, DEV_PASSWORD + "\nq\n");
        assertEquals(0, session.exitCode());
        assertTrue(session.output.contains("dsh 实例"), "dsh section heading: " + session.output);
        assertTrue(session.output.contains("dsh-1"), "the dsh instance name: " + session.output);
        assertTrue(session.output.contains("品牌实例"), "brand section heading: " + session.output);
        assertTrue(session.output.contains("kimi: 测试实例"), "the brand instance: " + session.output);
        assertTrue(session.output.contains("恢复 dsh 会话"), "session section heading: " + session.output);
    }

    @Test
    void threeWrongPasswordsDisconnectWithNonZeroExit() throws Exception {
        MenuShellFactory factory = factory(authWithPassword(), List.of(), List.of(), List.of());
        Session session = run(factory, "wrong-1\nwrong-2\nwrong-3\n");
        assertNotEquals(0, session.exitCode(), "the refused gate must not exit cleanly");
        assertTrue(session.output.contains("失败次数过多"), "the refusal says why: " + session.output);
    }

    @Test
    void wrongPasswordTwiceThenRightOneReachesTheMenu() throws Exception {
        MenuShellFactory factory = factory(authWithPassword(),
                List.of(new MenuShellFactory.Entry("dsh", "dsh-1", "dsh-1")), List.of(), List.of());
        Session session = run(factory, "wrong-1\nwrong-2\n" + DEV_PASSWORD + "\nq\n");
        assertEquals(0, session.exitCode());
        assertTrue(session.output.contains("密码错误"), "a wrong answer says so: " + session.output);
        assertTrue(session.output.contains("dsh-1"), "the menu still arrives: " + session.output);
    }

    @Test
    void unsetDeveloperPasswordPointsAtTheWebSettings() throws Exception {
        DeveloperAuth unset = DeveloperAuth.open(dataDir);
        MenuShellFactory factory = factory(unset, List.of(), List.of(), List.of());
        Session session = run(factory, "anything\n");
        assertEquals(0, session.exitCode());
        assertTrue(session.output.contains("网页端"), "the message names the web UI: " + session.output);
        assertFalse(session.output.contains("dsh 实例"), "the menu never renders: " + session.output);
    }

    // ------------------------------------------------------------------- M4 --

    @Test
    void idleGateIsCutByTheWatchdog() throws Exception {
        MenuShellFactory factory = factory(authWithPassword(), List.of(), List.of(), List.of());
        factory.gateIdleTimeout(java.time.Duration.ofMillis(300));
        // A channel that says nothing at all: connected, open, silent.
        PipedInputStream stdin = new PipedInputStream();
        java.io.PipedOutputStream feed = new java.io.PipedOutputStream(stdin);

        Session session = run(factory, stdin);

        assertEquals(1, session.exitCode(), "an idle gate is a cut, not a clean exit");
        assertTrue(session.output.contains("空闲超时"), "the cut says why: " + session.output);
        feed.close();
    }

    @Test
    void aSpentConnectionRecordsOnceButDoesNotBlockTheIpYet() throws Exception {
        org.jackhuang.hmcl.web.auth.LoginThrottle throttle =
                new org.jackhuang.hmcl.web.auth.LoginThrottle();
        MenuShellFactory factory = factory(authWithPassword(),
                List.of(new MenuShellFactory.Entry("dsh", "dsh-1", "dsh-1")), List.of(), List.of(),
                new PrintingAttachSupport(), throttle);

        // One connection burns all three answers: it dies, and the IP earns
        // exactly one record — not nearly the throttle's blocking budget.
        Session first = run(factory, "wrong-1\nwrong-2\nwrong-3\n");
        assertNotEquals(0, first.exitCode());
        assertTrue(first.output.contains("连接已断开"), first.output);

        // The next connection from the same peer (no channel → "unknown")
        // passes the gate and reaches the menu.
        Session second = run(factory, DEV_PASSWORD + "\nq\n");
        assertEquals(0, second.exitCode());
        assertTrue(second.output.contains("dsh 实例"), "one spent connection does not block the IP: "
                + second.output);
    }

    @Test
    void aBlockedIpIsRefusedBeforeThePrompt() throws Exception {
        org.jackhuang.hmcl.web.auth.LoginThrottle throttle =
                new org.jackhuang.hmcl.web.auth.LoginThrottle();
        MenuShellFactory factory = factory(authWithPassword(),
                List.of(new MenuShellFactory.Entry("dsh", "dsh-1", "dsh-1")), List.of(), List.of(),
                new PrintingAttachSupport(), throttle);
        // The gate records one entry per SPENT connection: fifteen seeds plus
        // one triple-failure connection cross the throttle's budget, so the
        // NEXT connection is refused at the door.
        java.time.Instant now = java.time.Instant.now();
        for (int i = 0; i < org.jackhuang.hmcl.web.auth.LoginThrottle.MAX_FAILURES; i++) {
            throttle.recordFailure("unknown", now);
        }
        Session spent = run(factory, "wrong-1\nwrong-2\nwrong-3\n");
        assertTrue(spent.output.contains("连接已断开"), spent.output);

        Session refused = run(factory, DEV_PASSWORD + "\n");
        assertEquals(1, refused.exitCode());
        assertTrue(refused.output.contains("请稍后再试"), "the blocked peer is told to wait: " + refused.output);
        assertFalse(refused.output.contains("dsh 实例"), "the menu never renders for a blocked peer: "
                + refused.output);
    }

    // ------------------------------------------------------------------ menu --

    @Test
    void quitExitsZero() throws Exception {
        MenuShellFactory factory = factory(authWithPassword(), List.of(), List.of(), List.of());
        assertEquals(0, run(factory, DEV_PASSWORD + "\nq\n").exitCode());
        assertEquals(0, run(factory, DEV_PASSWORD + "\nexit\n").exitCode(), "exit is an alias of q");
        assertEquals(0, run(factory, DEV_PASSWORD + "\n").exitCode(), "EOF at the menu is a friendly exit");
        assertEquals(0, run(factory, "").exitCode(), "EOF at the gate is a friendly exit");
    }

    @Test
    void invalidInputStaysInTheMenu() throws Exception {
        MenuShellFactory factory = factory(authWithPassword(),
                List.of(new MenuShellFactory.Entry("dsh", "dsh-1", "dsh-1")), List.of(), List.of());
        Session session = run(factory, DEV_PASSWORD + "\nhello\n0\n99\n\nq\n");
        assertEquals(0, session.exitCode());
        assertTrue(session.output.contains("无效输入"), "junk answers are called out: " + session.output);
        assertTrue(session.output.contains("dsh-1"), "the menu survives junk input: " + session.output);
    }

    @Test
    void choosingANumberPrintsTheStubAndComesBack() throws Exception {
        MenuShellFactory factory = factory(authWithPassword(),
                List.of(new MenuShellFactory.Entry("dsh", "dsh-1", "dsh-1")), List.of(), List.of());
        Session session = run(factory, DEV_PASSWORD + "\n1\nq\n");
        assertEquals(0, session.exitCode());
        assertTrue(session.output.contains("已选择 dsh:dsh-1"), "the stub names the choice: " + session.output);
        assertTrue(session.output.contains("1) dsh-1"), "the menu renders again behind the stub: " + session.output);
    }

    @Test
    void numberingIsContinuousAcrossSections() throws Exception {
        MenuShellFactory factory = factory(authWithPassword(),
                List.of(new MenuShellFactory.Entry("dsh", "dsh-1", "dsh-1"),
                        new MenuShellFactory.Entry("dsh", "dsh-2", "dsh-2")),
                List.of(new MenuShellFactory.Entry("brand", "kimi:abc", "kimi: x")),
                List.of());
        Session session = run(factory, DEV_PASSWORD + "\n3\nq\n");
        assertEquals(0, session.exitCode());
        assertTrue(session.output.contains("1) dsh-1"), session.output);
        assertTrue(session.output.contains("2) dsh-2"), session.output);
        assertTrue(session.output.contains("3) kimi: x"), "brand rows continue the numbering: " + session.output);
        assertTrue(session.output.contains("已选择 brand:kimi:abc"), session.output);
    }

    @Test
    void emptySectionsSaySo() throws Exception {
        MenuShellFactory factory = factory(authWithPassword(), List.of(), List.of(), List.of());
        Session session = run(factory, DEV_PASSWORD + "\nq\n");
        assertEquals(0, session.exitCode());
        assertTrue(session.output.contains("(无)"), "empty sections are explicit: " + session.output);
    }

    @Test
    void sessionRowShowsTitleTimeAndLockFlag() throws Exception {
        SessionCatalog.Entry active = new SessionCatalog.Entry("dsh-1", "11111111-2222-3333-4444-555555555555",
                "克隆仓库并分析改进点", Instant.now(), true);
        SessionCatalog.Entry idle = new SessionCatalog.Entry("dsh-1", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                "另一个标题", Instant.now().minusSeconds(3600), false);
        MenuShellFactory factory = factory(authWithPassword(), List.of(), List.of(), List.of(active, idle));
        Session session = run(factory, DEV_PASSWORD + "\nq\n");
        assertEquals(0, session.exitCode());
        assertTrue(session.output.contains("[web 使用中] 克隆仓库并分析改进点"), session.output);
        assertTrue(session.output.contains("另一个标题"), session.output);
    }

    // -------------------------------------------------------------- sanitize --

    @Test
    void controlCharactersInStoredTextNeverReachTheTerminal() throws Exception {
        // A title trying to paint the terminal red, an instance name carrying
        // a raw NUL, a brand label carrying an OSC title sequence.
        String poisonedTitle = "\u001b[31m红色\u001b[0m标题";
        SessionCatalog.Entry session = new SessionCatalog.Entry("dsh-1", "11111111-2222-3333-4444-555555555555",
                poisonedTitle, Instant.now(), false);
        MenuShellFactory.Entry brand = new MenuShellFactory.Entry("brand", "kimi:abc", "kimi: \u001b]0;pwn\u001b\\");
        MenuShellFactory factory = factory(authWithPassword(),
                List.of(new MenuShellFactory.Entry("dsh", "dsh-2", "dsh-\u001f2")),
                List.of(brand), List.of(session));
        Session ran = run(factory, DEV_PASSWORD + "\nq\n");
        assertEquals(0, ran.exitCode());
        assertFalse(ran.output.contains("\u001b"), "no escape byte anywhere: " + escape(ran.output));
        assertTrue(ran.output.contains("红色标题"), "the visible text survives: " + escape(ran.output));
        assertTrue(ran.output.contains("dsh-2"), escape(ran.output));
    }

    @Test
    void sanitizeStripsControlsKeepsNewlineAndText() {
        assertEquals("ab\ncd", MenuShellFactory.sanitize("a\u0000b\nc\u007fd"));
        assertEquals("红色标题", MenuShellFactory.sanitize("\u001b[31m红色\u001b[0m标题"));
        assertEquals("plain", MenuShellFactory.sanitize("plain"));
        assertEquals("", MenuShellFactory.sanitize(""));
        assertEquals("", MenuShellFactory.sanitize(null));
    }

    /// Renders control bytes visibly so a failure message stays printable.
    private static String escape(String text) {
        StringBuilder visible = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                visible.append(String.format("\\u%04x", (int) c));
            } else {
                visible.append(c);
            }
        }
        return visible.toString();
    }
}
