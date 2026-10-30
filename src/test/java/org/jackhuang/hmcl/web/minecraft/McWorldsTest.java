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
package org.jackhuang.hmcl.web.minecraft;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McWorldsTest {

    private static McInstance setup(Path dir, McInstanceManager manager) throws Exception {
        McInstance instance = manager.create("survival", "1.21.4", 25565, 25575, 1024, "world", null);
        Path world = manager.worldDirectory(instance);
        Files.createDirectories(world);
        Files.writeString(world.resolve("level.dat"), "original");
        Files.writeString(world.resolve("region.mca"), "region");
        return instance;
    }

    @Test
    void exportThenImportRestoresTheWorldAndKeepsASafetyBackup(@TempDir Path dir) throws Exception {
        McInstanceManager manager = new McInstanceManager(dir.resolve("instances"));
        McInstance instance = setup(dir, manager);
        McBackups backups = new McBackups(dir.resolve("backups"));

        ByteArrayOutputStream exported = new ByteArrayOutputStream();
        McWorlds.export(instance, manager, exported);
        assertTrue(exported.size() > 0);

        Files.writeString(manager.worldDirectory(instance).resolve("level.dat"), "changed");
        String safety = McWorlds.importZip(instance, manager, backups,
                new ByteArrayInputStream(exported.toByteArray()));

        assertNotNull(safety);
        assertEquals("original", Files.readString(manager.worldDirectory(instance).resolve("level.dat")));
        assertEquals(1, backups.list(instance.id()).size(), "the safety snapshot is kept");
    }

    @Test
    void aFlatZipIsAccepted(@TempDir Path dir) throws Exception {
        McInstanceManager manager = new McInstanceManager(dir.resolve("instances"));
        McInstance instance = setup(dir, manager);
        McBackups backups = new McBackups(dir.resolve("backups"));

        ByteArrayOutputStream flat = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(flat)) {
            zip.putNextEntry(new ZipEntry("level.dat"));
            zip.write("flat".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        McWorlds.importZip(instance, manager, backups, new ByteArrayInputStream(flat.toByteArray()));
        assertEquals("flat", Files.readString(manager.worldDirectory(instance).resolve("level.dat")));
    }

    @Test
    void aWorldZippedFromInsideItsDirectoryIsAccepted(@TempDir Path dir) throws Exception {
        McInstanceManager manager = new McInstanceManager(dir.resolve("instances"));
        McInstance instance = setup(dir, manager);
        McBackups backups = new McBackups(dir.resolve("backups"));

        ByteArrayOutputStream contents = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(contents)) {
            zip.putNextEntry(new ZipEntry("level.dat"));
            zip.write("inside".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("region/r.0.0.mca"));
            zip.write(new byte[] {1, 2, 3});
            zip.closeEntry();
        }
        McWorlds.importZip(instance, manager, backups, new ByteArrayInputStream(contents.toByteArray()));
        Path world = manager.worldDirectory(instance);
        assertEquals("inside", Files.readString(world.resolve("level.dat")));
        assertTrue(Files.isRegularFile(world.resolve("region/r.0.0.mca")));
    }

    @Test
    void anUnsafeEntryIsRefusedAndTheWorldIsUntouched(@TempDir Path dir) throws Exception {
        McInstanceManager manager = new McInstanceManager(dir.resolve("instances"));
        McInstance instance = setup(dir, manager);
        McBackups backups = new McBackups(dir.resolve("backups"));

        ByteArrayOutputStream evil = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(evil)) {
            zip.putNextEntry(new ZipEntry("../escaped.txt"));
            zip.write("nope".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        assertThrows(McException.class,
                () -> McWorlds.importZip(instance, manager, backups,
                        new ByteArrayInputStream(evil.toByteArray())));
        assertEquals("original", Files.readString(manager.worldDirectory(instance).resolve("level.dat")));
        assertFalse(Files.exists(dir.resolve("escaped.txt")));
    }

    @Test
    void anEmptyZipIsRefused(@TempDir Path dir) throws Exception {
        McInstanceManager manager = new McInstanceManager(dir.resolve("instances"));
        McInstance instance = setup(dir, manager);
        McBackups backups = new McBackups(dir.resolve("backups"));
        assertThrows(McException.class, () -> McWorlds.importZip(instance, manager, backups,
                new ByteArrayInputStream(new byte[0])));
    }

    /// A zip packed under a *different* top-level directory used to be silently
    /// nested (`world/oldworld/…`), and the server then generated a fresh world
    /// because `world/level.dat` was missing. Refusing with the name is the only
    /// outcome that does not lose the import without saying so.
    @Test
    void aZipPackedUnderAnotherDirectoryNameIsRefused(@TempDir Path dir) throws Exception {
        McInstanceManager manager = new McInstanceManager(dir.resolve("instances"));
        McInstance instance = setup(dir, manager);
        McBackups backups = new McBackups(dir.resolve("backups"));

        ByteArrayOutputStream other = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(other)) {
            zip.putNextEntry(new ZipEntry("oldworld/level.dat"));
            zip.write("data".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        McException error = assertThrows(McException.class, () -> McWorlds.importZip(instance, manager, backups,
                new ByteArrayInputStream(other.toByteArray())));
        assertTrue(error.getMessage().contains("oldworld"));
        assertTrue(error.getMessage().contains("world"));
    }

    @Test
    void theExportedZipNamesEntriesUnderTheWorldDirectory(@TempDir Path dir) throws Exception {
        McInstanceManager manager = new McInstanceManager(dir.resolve("instances"));
        McInstance instance = setup(dir, manager);
        ByteArrayOutputStream exported = new ByteArrayOutputStream();
        McWorlds.export(instance, manager, exported);
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(exported.toByteArray()))) {
            ZipEntry entry = zip.getNextEntry();
            assertNotNull(entry);
            assertTrue(entry.getName().startsWith("world/"), entry.getName());
        }
    }
}