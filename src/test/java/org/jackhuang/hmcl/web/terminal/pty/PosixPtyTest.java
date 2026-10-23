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
package org.jackhuang.hmcl.web.terminal.pty;

import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// The POSIX PTY glue: bytes round-trip, the size reaches the child (including
/// a resize), exit codes and signals come back, and terminating the terminal
/// takes the whole process group with it.
///
/// These tests spawn real processes through `/bin/sh` (the shell is the test's,
/// never the production path's) and only run on the platforms the glue
/// supports. macOS shares the code but has never been run here, so it is
/// skipped rather than silently "covered".
class PosixPtyTest {

    private static final Map<String, String> ENV = Map.of(
            "PATH", "/usr/local/bin:/usr/bin:/bin",
            "TERM", "xterm-256color",
            "LANG", "C.UTF-8");

    private static final Path TMP = Path.of("/tmp");

    @BeforeAll
    static void linuxOnly() {
        assumeTrue(OperatingSystem.CURRENT_OS == OperatingSystem.LINUX,
                "the PTY glue is exercised on Linux; macOS is unverified");
    }

    @Test
    void bytesRoundTrip() throws Exception {
        try (Pty pty = Pty.spawn(List.of("/bin/cat"), TMP, ENV, new PtySize(80, 24))) {
            Collector output = new Collector(pty.output());
            pty.input().write("hello\n".getBytes(StandardCharsets.UTF_8));
            pty.input().flush();
            output.await("hello", Duration.ofSeconds(5));
            assertTrue(output.text().contains("hello"), output.text());
        }
    }

    @Test
    void theChildSeesTheInitialSize() throws Exception {
        try (Pty pty = Pty.spawn(List.of("/bin/sh", "-c", "stty size"), TMP, ENV, new PtySize(80, 24))) {
            Collector output = new Collector(pty.output());
            output.await("24 80", Duration.ofSeconds(5));
            assertEquals(0, pty.waitFor());
        }
    }

    @Test
    void resizeReachesTheChild() throws Exception {
        try (Pty pty = Pty.spawn(
                List.of("/bin/sh", "-c", "sleep 0.5; stty size"), TMP, ENV, new PtySize(80, 24))) {
            Collector output = new Collector(pty.output());
            Thread.sleep(150);
            pty.resize(new PtySize(120, 40));
            output.await("40 120", Duration.ofSeconds(5));
            assertEquals(0, pty.waitFor());
        }
    }

    /// A resize must also *signal* the child. The pty has no controlling
    /// terminal (nothing calls TIOCSCTTY), so the kernel has no foreground
    /// process group to notify: without an explicit SIGWINCH a running TUI
    /// keeps painting its old width — which a reattach from a different window
    /// turns into wrapped, cascading output.
    @Test
    void resizeSignalsTheChild() throws Exception {
        try (Pty pty = Pty.spawn(
                List.of("/bin/sh", "-c", "trap 'echo GOT-WINCH $(stty size)' WINCH; while :; do sleep 0.1; done"),
                TMP, ENV, new PtySize(80, 24))) {
            Collector output = new Collector(pty.output());
            Thread.sleep(250);
            pty.resize(new PtySize(120, 40));
            output.await("GOT-WINCH 40 120", Duration.ofSeconds(5));
        }
    }

    @Test
    void exitCodeComesBack() throws Exception {
        try (Pty pty = Pty.spawn(List.of("/bin/sh", "-c", "exit 7"), TMP, ENV, new PtySize(80, 24))) {
            assertEquals(7, pty.waitFor());
        }
    }

    @Test
    void aSignalIsReportedAs128PlusTheSignal() throws Exception {
        try (Pty pty = Pty.spawn(List.of("/bin/sh", "-c", "kill -TERM $$"), TMP, ENV, new PtySize(80, 24))) {
            assertEquals(128 + 15, pty.waitFor());
        }
    }

    @Test
    void terminateTakesTheWholeProcessGroup() throws Exception {
        Pty pty = Pty.spawn(List.of("/bin/sh", "-c", "sleep 300 & echo $!; wait"), TMP, ENV,
                new PtySize(80, 24));
        try {
            Collector output = new Collector(pty.output());
            output.await("\n", Duration.ofSeconds(5));
            Matcher matcher = Pattern.compile("(\\d+)").matcher(output.text());
            assertTrue(matcher.find(), "the child printed its pid: " + output.text());
            long childPid = Long.parseLong(matcher.group(1));
            assertTrue(ProcessHandle.of(childPid).isPresent(), "the background child is running");

            pty.terminate(Duration.ofSeconds(2));

            assertTrue(waitUntilGone(childPid, Duration.ofSeconds(5)),
                    "the background child went with the process group");
        } finally {
            pty.close();
        }
    }

    @Test
    void outputEndsWhenTheChildExits() throws Exception {
        try (Pty pty = Pty.spawn(List.of("/bin/sh", "-c", "printf done"), TMP, ENV, new PtySize(80, 24))) {
            Collector output = new Collector(pty.output());
            output.await("done", Duration.ofSeconds(5));
            assertEquals(0, pty.waitFor());
            assertTrue(output.awaitEnd(Duration.ofSeconds(5)), "the pump reaches EOF");
            assertEquals(-1, pty.output().read(), "no bytes after the child is gone");
        }
    }

    @Test
    void openingAndClosingDoesNotLeakFileDescriptors() throws Exception {
        long before = fileDescriptors();
        for (int i = 0; i < 20; i++) {
            try (Pty pty = Pty.spawn(List.of("/bin/true"), TMP, ENV, new PtySize(80, 24))) {
                assertEquals(0, pty.waitFor());
            }
        }
        long after = fileDescriptors();
        assertTrue(after - before <= 2, "fd count grew from " + before + " to " + after);
    }

    private static long fileDescriptors() throws Exception {
        try (Stream<Path> files = Files.list(Path.of("/proc/self/fd"))) {
            return files.count();
        }
    }

    private static boolean waitUntilGone(long pid, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (ProcessHandle.of(pid).isEmpty()) {
                return true;
            }
            Thread.sleep(20);
        }
        return ProcessHandle.of(pid).isEmpty();
    }

    /// Reads the terminal in the background so the tests can wait for text
    /// with a deadline instead of blocking forever on a read.
    private static final class Collector {

        private final StringBuilder text = new StringBuilder();
        private final Thread thread;
        private volatile boolean ended;

        Collector(InputStream input) {
            thread = new Thread(() -> {
                byte[] buffer = new byte[4096];
                try {
                    int n;
                    while ((n = input.read(buffer, 0, buffer.length)) >= 0) {
                        synchronized (this) {
                            text.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
                            notifyAll();
                        }
                    }
                } catch (Exception ignored) {
                    // The test's terminal is closed; the collector ends here.
                }
                ended = true;
            }, "pty-test-collector");
            thread.setDaemon(true);
            thread.start();
        }

        synchronized void await(String needle, Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (!text.toString().contains(needle)) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new AssertionError("timed out waiting for '" + needle + "' in: " + text);
                }
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
        }

        synchronized boolean awaitEnd(Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (!ended) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
            return true;
        }

        synchronized String text() {
            return text.toString();
        }
    }
}
