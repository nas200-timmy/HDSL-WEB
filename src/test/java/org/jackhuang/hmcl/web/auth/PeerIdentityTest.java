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
package org.jackhuang.hmcl.web.auth;

import org.jackhuang.hmcl.web.terminal.TerminalManager;
import org.jackhuang.hmcl.web.terminal.TerminalSession;
import org.jackhuang.hmcl.web.terminal.TerminalTarget;
import org.jackhuang.hmcl.web.terminal.pty.PtySize;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The kernel-derived half of the fatal-operation gate.
///
/// The roots come from a real terminal (a real PTY, a real pid); the `/proc`
/// walk is faked so the test names its own caller pid. What this pins is the
/// contract the gate leans on: a descendant of a root is that root's
/// instance, the root itself is, and a chain that reaches the panel or breaks
/// identifies nobody — never somebody else.
class PeerIdentityTest {

    private static final Map<String, String> ENV = Map.of("TERM", "dumb");

    /// `/proc` as the test wants it: a chosen inode, a chosen owner, and a
    /// chosen parent map.
    private static final class FakeProc implements PeerIdentity.Proc {
        @Nullable Long inode;
        @Nullable Long owner;
        final Map<Long, Long> parents = new HashMap<>();
        int inodeCalls;

        @Override
        public @Nullable Long callerInode(int callerPort, int panelPort) {
            inodeCalls++;
            return inode;
        }

        @Override
        public @Nullable Long socketOwner(long inode) {
            return owner;
        }

        @Override
        public @Nullable Long parentPid(long pid) {
            return parents.get(pid);
        }
    }

    /// A terminal that stays alive long enough to be a root: `read line`
    /// blocks until the session is terminated.
    private static TerminalSession openProbe(TerminalManager terminals, String brand, String id)
            throws Exception {
        TerminalTarget target = new TerminalTarget(brand, id,
                List.of("/bin/sh", "-c", "read line"), Path.of("/tmp"), ENV);
        return terminals.open(target, new PtySize(80, 24));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void aDescendantOfARootIsThatRootsInstance() throws Exception {
        try (TerminalManager terminals = new TerminalManager()) {
            TerminalSession session = openProbe(terminals, "kimi", "probe");
            long root = session.pid();
            assertTrue(root > 0, "the terminal must name its root pid");

            FakeProc proc = new FakeProc();
            proc.inode = 5L;
            proc.owner = 999L;
            proc.parents.put(999L, root);
            PeerIdentity identity = new PeerIdentity(terminals, proc);

            assertEquals(new PeerIdentity.Ref("kimi", "probe"), identity.derive(40000, 3080));
            assertEquals("kimi/probe", new PeerIdentity.Ref("kimi", "probe").wire());
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void theRootProcessItselfIsIdentified() throws Exception {
        try (TerminalManager terminals = new TerminalManager()) {
            TerminalSession session = openProbe(terminals, "zcode", "probe");
            long root = session.pid();

            FakeProc proc = new FakeProc();
            proc.inode = 5L;
            proc.owner = root;
            PeerIdentity identity = new PeerIdentity(terminals, proc);

            assertEquals(new PeerIdentity.Ref("zcode", "probe"), identity.derive(40000, 3080));
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void aChainThatReachesThePanelIdentifiesNobody() throws Exception {
        try (TerminalManager terminals = new TerminalManager()) {
            openProbe(terminals, "kimi", "probe");

            FakeProc proc = new FakeProc();
            proc.inode = 5L;
            proc.owner = 999L;
            // The caller's parent is the panel itself: not an instance, and
            // never a licence.
            proc.parents.put(999L, ProcessHandle.current().pid());
            PeerIdentity identity = new PeerIdentity(terminals, proc);

            assertNull(identity.derive(40000, 3080));
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void aBrokenChainOrAMissingSocketIdentifiesNobody() throws Exception {
        try (TerminalManager terminals = new TerminalManager()) {
            openProbe(terminals, "kimi", "probe");

            FakeProc broken = new FakeProc();
            broken.inode = 5L;
            broken.owner = 999L;
            // No parent on record: the process is gone or unreadable.
            assertNull(new PeerIdentity(terminals, broken).derive(40000, 3080));

            FakeProc noSocket = new FakeProc();
            noSocket.owner = 999L;
            assertNull(new PeerIdentity(terminals, noSocket).derive(40000, 3080));

            FakeProc noOwner = new FakeProc();
            noOwner.inode = 5L;
            assertNull(new PeerIdentity(terminals, noOwner).derive(40000, 3080));
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void aDerivedIdentityIsCachedPerPort() throws Exception {
        try (TerminalManager terminals = new TerminalManager()) {
            TerminalSession session = openProbe(terminals, "kimi", "probe");

            FakeProc proc = new FakeProc();
            proc.inode = 5L;
            proc.owner = session.pid();
            PeerIdentity identity = new PeerIdentity(terminals, proc);

            assertEquals(new PeerIdentity.Ref("kimi", "probe"), identity.derive(40000, 3080));
            assertEquals(new PeerIdentity.Ref("kimi", "probe"), identity.derive(40000, 3080));
            assertEquals(1, proc.inodeCalls, "the second call on one port must reuse the answer");
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void terminalOpenReflectsTheSession() throws Exception {
        try (TerminalManager terminals = new TerminalManager()) {
            openProbe(terminals, "kimi", "probe");
            PeerIdentity identity = new PeerIdentity(terminals, new FakeProc());

            assertTrue(identity.terminalOpen("kimi", "probe"));
            assertFalse(identity.terminalOpen("kimi", "other"));
            assertFalse(identity.terminalOpen("dsh", "probe"));
        }
    }

    /// The parsing half of [PeerIdentity.LinuxProc], against synthetic
    /// `/proc/net/tcp` lines: the caller's side matches, the panel's own side
    /// of the same connection does not, and a port is the little-endian hex
    /// after the last colon.
    @Test
    void linuxProcParsingFindsTheCallerSideOnly() {
        String callerSide = "  10: 0100007F:9C40 0100007F:0C08 01 00000000:00000000 02:0007 00000000 0 0 176883 2 0000000000000000 100 0 0 10 0";
        // The panel's end of the same connection: local 3080, remote 40000.
        String panelSide = "  11: 0100007F:0C08 0100007F:9C40 01 00000000:00000000 02:0007 00000000 0 0 199999 2 0000000000000000 100 0 0 10 0";
        // Same ports, not established: a TIME_WAIT leftover must not answer.
        String timeWait = "  12: 0100007F:9C40 0100007F:0C08 06 00000000:00000000 00:0000 00000000 0 0 111111 2 0000000000000000 100 0 0 10 0";

        assertEquals(176883L, PeerIdentity.LinuxProc.scan(List.of(panelSide, timeWait, callerSide), 40000, 3080));
        // The panel's own end of the connection never answers a lookup for the
        // caller's: its local port is the panel's, not the caller's.
        assertNull(PeerIdentity.LinuxProc.scan(List.of(panelSide), 40000, 3080));
        assertNull(PeerIdentity.LinuxProc.scan(List.of(panelSide, timeWait), 40000, 3080));

        assertEquals(0x9C40, PeerIdentity.LinuxProc.portOf("0100007F:9C40"));
        assertEquals(0x0C08, PeerIdentity.LinuxProc.portOf("0100007F:0C08"));
        assertEquals(-1, PeerIdentity.LinuxProc.portOf("0100007F"));
        assertEquals(-1, PeerIdentity.LinuxProc.portOf("0100007F:"));
        assertEquals(-1, PeerIdentity.LinuxProc.portOf("0100007F:GGGG"));
    }
}
