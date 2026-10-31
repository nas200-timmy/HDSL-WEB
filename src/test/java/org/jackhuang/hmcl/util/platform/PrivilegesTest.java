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
    void handingAFileToTheAgentDoesNothingWhereNobodyWasDropped(@TempDir Path directory) throws Exception {
        // The rule under test is the side effect nobody expects: on a bare deployment the panel
        // is the same person the agent is, so an ownership change here would be the panel taking
        // somebody's file. Handing over only has meaning where children were dropped.
        Path file = Files.writeString(directory.resolve("config.yaml"), "registry: https://a.example\n");
        java.util.Optional<java.util.Set<java.nio.file.attribute.PosixFilePermission>> before =
                org.jackhuang.hmcl.util.PosixPermissions.of(file);

        boolean handed = Privileges.handToAgent(
                file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-rw-r--"));

        if (Privileges.dropsChildren()) {
            assertTrue(handed, "inside the container the file goes to the agent");
            return;
        }
        assertFalse(handed, "outside the container nothing is handed over");
        assertEquals(before, org.jackhuang.hmcl.util.PosixPermissions.of(file),
                "and the mode is left as it was");
    }

    @Test
    void handingOverChangesTheModeAsWellAsTheOwner(@TempDir Path directory) throws Exception {
        // The container's numbers are out of a test's reach, but the two calls they turn into
        // are not: chown to a uid, then chmod. Driving it with the uid the test already is keeps
        // it legal without privileges and still covers the part that can be got wrong — the
        // order. `chown` clears the setgid bit, so a mode set before it is a mode lost.
        Path file = Files.writeString(directory.resolve("config.yaml"), "registry: https://a.example\n");
        if (org.jackhuang.hmcl.util.PosixPermissions.of(file).isEmpty()) {
            return; // no mode bits on this platform: nothing this test can assert
        }
        int uid = Privileges.euid();
        int gid = egid();
        if (uid < 0 || gid < 0) {
            return;
        }

        assertTrue(Privileges.handTo(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-rw-r--"),
                uid, gid));

        assertEquals(java.util.Set.of(
                        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                        java.nio.file.attribute.PosixFilePermission.GROUP_READ,
                        java.nio.file.attribute.PosixFilePermission.GROUP_WRITE,
                        java.nio.file.attribute.PosixFilePermission.OTHERS_READ),
                org.jackhuang.hmcl.util.PosixPermissions.of(file).orElseThrow(),
                "the agent's pnpm has to be able to read it, and `pnpm config set` to rewrite it");
        assertEquals(uid, ((Number) Files.getAttribute(file, "unix:uid")).intValue());
        assertEquals(gid, ((Number) Files.getAttribute(file, "unix:gid")).intValue());
    }

    /// The group id of this process, for the test above.
    ///
    /// @return the gid, or `-1` where the platform has no such notion
    private static int egid() {
        try {
            return (int) Files.getAttribute(Path.of("/proc/self"), "unix:gid");
        } catch (Exception e) {
            try {
                return (int) new com.sun.security.auth.module.UnixSystem().getGid();
            } catch (Exception | LinkageError ignored) {
                return -1;
            }
        }
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
