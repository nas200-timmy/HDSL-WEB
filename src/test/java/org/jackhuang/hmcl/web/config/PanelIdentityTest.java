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
package org.jackhuang.hmcl.web.config;

import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PanelIdentityTest {

    @TempDir
    Path dataDir;

    @Test
    void createsAndReusesOneStableId() throws Exception {
        String first = PanelIdentity.loadOrCreate(dataDir);
        assertTrue(first.matches("[0-9a-f]{32}"), first);
        assertEquals(first, PanelIdentity.loadOrCreate(dataDir));
        assertEquals(first, PanelIdentity.current());
        assertTrue(Files.isRegularFile(dataDir.resolve("instance-id")));
    }

    @Test
    void aMalformedIdIsRegenerated() throws Exception {
        Files.writeString(dataDir.resolve("instance-id"), "not-a-real-id\n");
        String regenerated = PanelIdentity.loadOrCreate(dataDir);
        assertTrue(regenerated.matches("[0-9a-f]{32}"), regenerated);
        assertNotEquals("not-a-real-id", regenerated);
        assertEquals(regenerated, PanelIdentity.loadOrCreate(dataDir));
    }

    @Test
    void theFileIsOwnerOnlyOnPosix() throws Exception {
        Assumptions.assumeFalse(OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS,
                "POSIX permissions are not meaningful on Windows");
        PanelIdentity.loadOrCreate(dataDir);
        org.jackhuang.hmcl.util.PosixPermissions.assertOwnerOnly(
                dataDir.resolve("instance-id"), "instance-id must be 0600");
    }
}