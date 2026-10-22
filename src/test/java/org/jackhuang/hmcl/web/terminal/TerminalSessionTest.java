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

import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jackhuang.hmcl.web.terminal.pty.Pty;
import org.jackhuang.hmcl.web.terminal.pty.PtySize;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// The session outlives its viewer: detaching keeps the agent running, the
/// replay buffer brings the screen back, and the exit path refuses input.
class TerminalSessionTest {

    private static final Map<String, String> ENV = Map.of(
            "PATH", "/usr/local/bin:/usr/bin:/bin",
            "TERM", "xterm-256color");

    @BeforeAll
    static void linuxOnly() {
        assumeTrue(OperatingSystem.CURRENT_OS == OperatingSystem.LINUX, "PTY tests run on Linux");
    }

    @Test
    void detachKeepsTheAgentAndReattachReplays() throws Exception {
        Pty pty = Pty.spawn(List.of("/bin/cat"), Path.of("/tmp"), ENV, new PtySize(80, 24));
        TerminalSession session = new TerminalSession("i1", pty, 4096);
        try {
            Collector first = new Collector();
            session.attach(first);
            session.input().write("hello\n".getBytes(StandardCharsets.UTF_8));
            session.input().flush();
            first.await("hello", Duration.ofSeconds(5));

            session.detach(first);
            session.input().write("world\n".getBytes(StandardCharsets.UTF_8));
            session.input().flush();
            Thread.sleep(150);

            Collector second = new Collector();
            session.attach(second);
            second.await("world", Duration.ofSeconds(5));
            assertTrue(second.text().contains("hello"), "the replay kept the earlier output: " + second.text());
            assertEquals(TerminalSession.State.RUNNING, session.state());
        } finally {
            session.close();
        }
    }

    @Test
    void exitIsReportedToEverySinkAndRefusesInput() throws Exception {
        Pty pty = Pty.spawn(List.of("/bin/sh", "-c", "printf bye; sleep 0.2"), Path.of("/tmp"), ENV,
                new PtySize(80, 24));
        TerminalSession session = new TerminalSession("i2", pty, 4096);
        Collector sink = new Collector();
        session.attach(sink);
        try {
            assertEquals(0, session.waitFor());
            assertTrue(sink.awaitExit(Duration.ofSeconds(5)), "the sink is told the exit code");
            assertEquals(0, sink.exitCode());
            assertEquals(TerminalSession.State.EXITED, session.state());
            assertTrue(sink.text().contains("bye"), "the last output was drained: " + sink.text());

            Collector late = new Collector();
            session.attach(late);
            assertTrue(late.awaitExit(Duration.ofSeconds(1)), "a late viewer is told the session is gone");
            assertEquals(0, late.exitCode());

            assertThrows(java.io.IOException.class,
                    () -> session.input().write("x".getBytes(StandardCharsets.UTF_8)),
                    "the terminal refuses input once the agent is gone");
        } finally {
            session.close();
        }
    }

    private static final class Collector implements TerminalSink {

        private final StringBuilder text = new StringBuilder();
        private final java.util.concurrent.CountDownLatch exit = new java.util.concurrent.CountDownLatch(1);
        private volatile int exitCode = Integer.MIN_VALUE;

        @Override
        public synchronized void data(byte[] bytes, int offset, int length) {
            text.append(new String(bytes, offset, length, StandardCharsets.UTF_8));
            notifyAll();
        }

        @Override
        public void exited(int code) {
            exitCode = code;
            exit.countDown();
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

        boolean awaitExit(Duration timeout) throws InterruptedException {
            return exit.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        int exitCode() {
            return exitCode;
        }

        synchronized String text() {
            return text.toString();
        }
    }
}
