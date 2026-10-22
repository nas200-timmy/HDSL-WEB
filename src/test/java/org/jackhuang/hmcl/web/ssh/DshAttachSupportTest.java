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

import org.apache.sshd.server.command.Command;
import org.jackhuang.hmcl.dsh.DshHomeMode;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshInstanceManager;
import org.jackhuang.hmcl.util.logging.Logger;
import org.jackhuang.hmcl.util.platform.VirtualThreads;
import org.jackhuang.hmcl.web.auth.UserStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The M3 attach: spawn spec (env, cwd, argv), resume flag, exit-code
/// propagation to the shell's exit callback, tree cleanup on disconnect,
/// the one-attach-per-home rule, and the friendly refusals. The real
/// harness is stood in by `src/test/resources/fake-dsh/fake-dsh` — same
/// contract on argv and stdout, none of the agent — swapped in through the
/// package-private spawn hook, the way fake-pnpm serves the install tests.
///
/// Instances are created in the shared per-JVM launcher home (the gradle
/// `test` task points `hdsl.home` at `build/test-home`) and removed after
/// each test.
class DshAttachSupportTest {

    /// Resolved against the test JVM's working directory (the project root,
    /// as under gradle) — the child's cwd is the instance home, so a
    /// relative path would not find the script.
    private static final String FAKE_DSH = Path.of("src/test/resources/fake-dsh/fake-dsh")
            .toAbsolutePath().normalize().toString();
    private static final String PANEL_PASSWORD = "panel-password-1";
    private static final String DEV_PASSWORD = "dev-password-1";
    private static final String UUID = "11111111-2222-3333-4444-555555555555";

    @TempDir
    Path dataDir;

    private final DshAttachSupport support = new DshAttachSupport();
    private final List<String> created = new ArrayList<>();

    @AfterEach
    void removeInstances() throws Exception {
        for (String id : created) {
            DshInstanceManager.delete(id);
        }
        created.clear();
    }

    // ------------------------------------------------------------ fixtures --

    /// An installed instance: manifest, home, and the entry script the plan
    /// probes for. The script itself is never executed — the spawn hook
    /// points the child at the fake.
    private DshInstance newInstance(String id, boolean installed) throws Exception {
        DshInstance instance = DshInstanceManager.create(id, "0.0.1-test", "web",
                dataDir.resolve(id + "-workspace"), DshHomeMode.ISOLATED, null, List.of(), Map.of());
        created.add(id);
        if (installed) {
            Path bin = instance.dshEntryPoint();
            Files.createDirectories(bin.getParent());
            Files.writeString(bin, "// fake entry point; the spawn hook swaps the command\n");
        }
        return instance;
    }

    /// Points the spawn at the fake-dsh script and pins its mode.
    private void hookFakeDsh(String mode, int exitCode) {
        support.spawnHook(builder -> {
            List<String> command = builder.command();
            List<String> rewritten = new ArrayList<>();
            rewritten.add("sh");
            rewritten.add(FAKE_DSH);
            rewritten.addAll(command.subList(2, command.size()));
            builder.command(rewritten);
            builder.environment().put("FAKE_DSH_EXIT", String.valueOf(exitCode));
            if (mode != null) {
                builder.environment().put("FAKE_DSH_MODE", mode);
            }
            return builder;
        });
    }

    /// A connected pipe pair standing in for the channel's stdin: the read
    /// end goes to attach (its pump blocks there, exactly like a live
    /// channel), the write end stays with the test; closing it is the
    /// channel disconnecting.
    private record StdinPipe(PipedInputStream read, java.io.PipedOutputStream write) {
        static StdinPipe open() throws Exception {
            PipedInputStream read = new PipedInputStream();
            return new StdinPipe(read, new java.io.PipedOutputStream(read));
        }
    }

    private DeveloperAuth authWithPassword() throws Exception {
        UserStore users = UserStore.open(dataDir, Map.of(UserStore.ENV_ADMIN_PASSWORD, PANEL_PASSWORD),
                Logger.LOG);
        DeveloperAuth auth = DeveloperAuth.open(dataDir);
        auth.set(DEV_PASSWORD, users);
        return auth;
    }

    private static String pidOf(String output) {
        for (String line : output.split("\n")) {
            if (line.startsWith("pid=")) {
                return line.substring("pid=".length()).trim();
            }
        }
        throw new IllegalStateException("no pid line in: " + output);
    }

    // ---------------------------------------------------------------- tests --

    @Test
    void attachPrintsEnvironmentAndArguments() throws Exception {
        DshInstance instance = newInstance("m3-fake", true);
        hookFakeDsh("print", 0);
        StdinPipe stdin = StdinPipe.open();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int code = support.attach("dsh", "m3-fake", stdin.read(), out, null);

        assertEquals(0, code, "a clean child answers a clean session");
        String text = out.toString(StandardCharsets.UTF_8);
        Path home = instance.homeDirectory();
        assertTrue(text.contains("cwd=" + home), "the child runs in the instance home: " + text);
        assertTrue(text.contains("DSH_HOME=" + home), "DSH_HOME matches the home: " + text);
        assertTrue(text.contains("argv1=tui"), "the surface is tui: " + text);
        assertTrue(text.contains("TERM=xterm-256color"), "no pty on the channel → the default term: " + text);
        stdin.write.close();
        stdin.read.close();
    }

    @Test
    void sessionChoiceAddsResume() throws Exception {
        newInstance("m3-resume", true);
        hookFakeDsh("print", 0);
        StdinPipe stdin = StdinPipe.open();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int code = support.attach("session", "m3-resume:" + UUID, stdin.read(), out, null);

        assertEquals(0, code);
        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("argv1=tui"), text);
        assertTrue(text.contains("argv2=--resume"), text);
        assertTrue(text.contains("argv3=" + UUID), text);
        stdin.write.close();
        stdin.read.close();
    }

    @Test
    void processExitCodeReachesTheExitCallback() throws Exception {
        newInstance("m3-exit", true);
        hookFakeDsh("print", 3);
        MenuShellFactory factory = new MenuShellFactory(authWithPassword(),
                () -> List.of(new MenuShellFactory.Entry("dsh", "m3-exit", "m3-exit")),
                List::of, List::of, support, null, null);

        // The channel's stdin stays open across the whole session — closing
        // it is the disconnect, which only happens after the assertions.
        Command command = factory.createShell(null);
        StdinPipe stdin = StdinPipe.open();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        CountDownLatch exited = new CountDownLatch(1);
        AtomicInteger exitCode = new AtomicInteger(Integer.MIN_VALUE);
        command.setInputStream(stdin.read());
        command.setOutputStream(stdout);
        command.setErrorStream(new ByteArrayOutputStream());
        command.setExitCallback((code, message, closeImmediately) -> {
            exitCode.set(code);
            exited.countDown();
        });
        command.start(null, null);
        stdin.write().write((DEV_PASSWORD + "\n1\n").getBytes(StandardCharsets.UTF_8));
        stdin.write().flush();

        assertTrue(exited.await(20, TimeUnit.SECONDS), "the session ends with the child");
        assertEquals(3, exitCode.get(), "the child's code is the session's code: " + stdout);
        String terminal = stdout.toString(StandardCharsets.UTF_8);
        int renders = terminal.split("1\\) m3-exit", -1).length - 1;
        assertEquals(1, renders, "a spent attachment does not loop back to the menu: " + terminal);
        stdin.write().close();
        stdin.read().close();
    }

    @Test
    void disconnectKillsTheWholeTree() throws Exception {
        newInstance("m3-tree", true);
        hookFakeDsh("sleep", 0);
        StdinPipe stdin = StdinPipe.open();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        Thread worker = VirtualThreads.start(() -> {
            try {
                support.attach("dsh", "m3-tree", stdin.read(), out, null);
            } catch (IOException ignored) {
                // The channel side broke first; the pump's cleanup runs all the same.
            }
        });
        ProcessHandle root = waitForPid(out);
        ProcessHandle child = waitForChild(root);
        assertTrue(root.isAlive() && child.isAlive(), "the fake dsh and its child are up");

        stdin.write().close();
        worker.join(20_000);

        waitForDeath(root, "the root died with the channel");
        waitForDeath(child, "the child went with the tree");
        stdin.read().close();
    }

    @Test
    void secondAttachToTheSameHomeIsRefused() throws Exception {
        newInstance("m3-busy", true);
        hookFakeDsh("sleep", 0);
        StdinPipe first = StdinPipe.open();
        ByteArrayOutputStream firstOut = new ByteArrayOutputStream();

        Thread worker = VirtualThreads.start(() -> {
            try {
                support.attach("dsh", "m3-busy", first.read(), firstOut, null);
            } catch (IOException ignored) {
                // Same as above: the refusal path never throws, the cleanup covers the rest.
            }
        });
        waitForPid(firstOut);

        ByteArrayOutputStream secondOut = new ByteArrayOutputStream();
        int code = support.attach("dsh", "m3-busy", new ByteArrayInputStream(new byte[0]), secondOut, null);

        assertEquals(-1, code);
        assertTrue(secondOut.toString(StandardCharsets.UTF_8).contains("已有终端会话"), secondOut.toString());

        first.write().close();
        worker.join(20_000);
        first.read().close();
    }

    @Test
    void notInstalledIsAFriendlyLine() throws Exception {
        newInstance("m3-bare", false);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int code = support.attach("dsh", "m3-bare", new ByteArrayInputStream(new byte[0]), out, null);

        assertEquals(-1, code);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("无法启动"), out.toString());
    }

    @Test
    void unknownInstanceIsAFriendlyLine() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int code = support.attach("dsh", "m3-absent", new ByteArrayInputStream(new byte[0]), out, null);

        assertEquals(-1, code);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("不存在"), out.toString());
    }

    @Test
    void brandIsDeferredExplicitly() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int code = support.attach("brand", "kimi:abc", new ByteArrayInputStream(new byte[0]), out, null);

        assertEquals(-1, code);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("后续版本提供"), out.toString());
    }

    /// Reads the fake's startup block until the pid line appears.
    private static ProcessHandle waitForPid(ByteArrayOutputStream out) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            String text = out.toString(StandardCharsets.UTF_8);
            if (text.contains("pid=")) {
                return ProcessHandle.of(Long.parseLong(pidOf(text))).orElseThrow();
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("the fake never printed its pid: " + out);
    }

    /// The fake spawns its `sleep 3600` child just after printing its pid —
    /// wait for that child to exist and snapshot the handle before the kill,
    /// because a dead root's descendant list is not the question anymore.
    private static ProcessHandle waitForChild(ProcessHandle root) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            ProcessHandle child = root.descendants().filter(ProcessHandle::isAlive).findFirst().orElse(null);
            if (child != null) {
                return child;
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("the fake never spawned its child");
    }

    private static void waitForDeath(ProcessHandle handle, String what) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (handle.isAlive() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertFalse(handle.isAlive(), what);
    }
}
