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
import org.jackhuang.hmcl.web.terminal.pty.PtySize;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// One terminal per instance, and a terminated one can be reopened.
class TerminalManagerTest {

    private static final Map<String, String> ENV = Map.of(
            "PATH", "/usr/local/bin:/usr/bin:/bin",
            "TERM", "xterm-256color");

    @BeforeAll
    static void linuxOnly() {
        assumeTrue(OperatingSystem.CURRENT_OS == OperatingSystem.LINUX, "PTY tests run on Linux");
    }

    @Test
    void aSecondTerminalForTheSameInstanceIsRefused() throws Exception {
        try (TerminalManager manager = new TerminalManager(1024)) {
            TerminalSession first = manager.open(target("i1"), new PtySize(80, 24));
            assertTrue(manager.find("i1").isPresent());
            assertThrows(TerminalBusyException.class,
                    () -> manager.open(target("i1"), new PtySize(80, 24)));
            assertTrue(first.state() == TerminalSession.State.RUNNING);
        }
    }

    @Test
    void terminateEndsTheSessionAndAllowsReopening() throws Exception {
        try (TerminalManager manager = new TerminalManager(1024)) {
            manager.open(target("i2"), new PtySize(80, 24));
            assertTrue(manager.terminate("i2"));
            awaitNoSession(manager, "i2");
            TerminalSession reopened = manager.open(target("i2"), new PtySize(80, 24));
            assertTrue(reopened.state() == TerminalSession.State.RUNNING);
        }
    }

    @Test
    void closingTheManagerEndsEveryTerminal() throws Exception {
        TerminalManager manager = new TerminalManager(1024);
        TerminalSession one = manager.open(target("i3"), new PtySize(80, 24));
        TerminalSession two = manager.open(target("i4"), new PtySize(80, 24));
        manager.close();
        assertTrue(one.waitFor() != Integer.MIN_VALUE);
        assertTrue(two.waitFor() != Integer.MIN_VALUE);
        assertTrue(manager.size() == 0);
    }

    private static TerminalTarget target(String instanceId) {
        return new TerminalTarget("test", instanceId, List.of("/bin/cat"),
                Path.of("/tmp"), ENV);
    }

    private static void awaitNoSession(TerminalManager manager, String instanceId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (manager.find(instanceId).isPresent() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(manager.find(instanceId).isEmpty(), "the session should be gone");
    }
}
