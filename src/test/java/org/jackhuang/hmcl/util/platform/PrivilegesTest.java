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
package org.jackhuang.hmcl.util.platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// When the panel drops its children to the image's uid, and when it must not.
///
/// The rule that matters is the "must not" half: on a bare host uid 1000 belongs
/// to whoever the administrator made it, so dropping an agent to that number
/// would hand it somebody else's account. The decision therefore requires a
/// container **and** root, and both are named as inputs here so the tests do not
/// depend on the machine they run on.
class PrivilegesTest {

    @Test
    void aMarkerFileSaysContainer(@TempDir Path directory) throws Exception {
        Path marker = Files.createFile(directory.resolve("dockerenv"));
        assertTrue(Privileges.detect(marker, directory.resolve("absent"), directory.resolve("no-cgroup")));
    }

    @Test
    void podmansMarkerCountsToo(@TempDir Path directory) throws Exception {
        Path marker = Files.createFile(directory.resolve("containerenv"));
        assertTrue(Privileges.detect(directory.resolve("absent"), marker, directory.resolve("no-cgroup")));
    }

    @Test
    void aCgroupPathThatNamesARuntimeSaysContainer(@TempDir Path directory) throws Exception {
        Path cgroup = Files.writeString(directory.resolve("cgroup"),
                "0::/docker/0123456789abcdef\n");
        assertTrue(Privileges.detect(directory.resolve("absent"), directory.resolve("absent"), cgroup));

        Path systemd = Files.writeString(directory.resolve("cgroup-systemd"),
                "0::/system.slice/hdsl-web.service\n");
        assertFalse(Privileges.detect(directory.resolve("absent"), directory.resolve("absent"), systemd));
    }

    @Test
    void nothingReadableMeansNotAContainer(@TempDir Path directory) {
        assertFalse(Privileges.detect(directory.resolve("absent"), directory.resolve("absent"),
                directory.resolve("absent")));
    }

    @Test
    void droppingPrefixesSetprivAndKeepsTheCommandIntact() {
        List<String> command = List.of("/opt/node/bin/node", "--version");

        List<String> wrapped = Privileges.wrap(command, true);

        assertEquals("--reuid=" + Privileges.AGENT_UID, wrapped.get(1), wrapped.toString());
        assertEquals("--regid=" + Privileges.AGENT_UID, wrapped.get(2), wrapped.toString());
        assertEquals("--init-groups", wrapped.get(3), wrapped.toString());
        assertEquals("--", wrapped.get(4), wrapped.toString());
        assertEquals(command, wrapped.subList(5, wrapped.size()),
                "the agent's argv must arrive unchanged after the separator");
        assertTrue(wrapped.get(0).endsWith("setpriv"), wrapped.get(0));
    }

    @Test
    void notDroppingHandsTheCommandBackUntouched() {
        List<String> command = List.of("/opt/node/bin/node", "--version");
        assertEquals(command, Privileges.wrap(command, false));
        assertEquals(List.of(), Privileges.wrap(List.of(), true));
    }

    @Test
    void theAgentUidIsConfigurableBecauseSomeStorageProtectsOnlyOtherNumbers() {
        assertEquals(1000, Privileges.agentUid(null));
        assertEquals(1000, Privileges.agentUid("  "));
        assertEquals(1001, Privileges.agentUid("1001"));
        assertEquals(1000, Privileges.agentUid("not-a-number"), "a typo must not become uid 0");
        assertEquals(1000, Privileges.agentUid("0"));
        assertEquals(1000, Privileges.agentUid("70000"));
    }

    @Test
    void theBannerNoteOnlySpeaksForRootOutsideAContainer() {
        // Whatever this machine is, the note must agree with the predicate the
        // code actually uses — that is the part a regression would break.
        if (Privileges.rootOutsideContainer()) {
            assertTrue(Privileges.bannerNote().contains("root"));
        } else {
            assertNull(Privileges.bannerNote());
        }
    }
}
