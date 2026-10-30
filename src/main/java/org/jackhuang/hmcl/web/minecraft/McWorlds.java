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

import org.jackhuang.hmcl.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/// Importing and exporting a world as a zip.
///
/// Both directions happen **while the server is stopped**: a world copied while
/// the server writes it is a zip of a moving target. Importing first snapshots
/// the current world (via [McBackups]) and stages the new one beside it, then
/// swaps the directories — a failed extract never leaves the instance with half
/// a world.
///
/// The zip may name entries `world/…` (as the export writes them) or relative
/// to the world root. Absolute paths and `..` are refused, not sanitised.
@NotNullByDefault
public final class McWorlds {

    /// How large an import may be. A world is usually far smaller; this only
    /// stops a mistaken upload from filling the disk.
    public static final long MAX_IMPORT_BYTES = 2L * 1024 * 1024 * 1024;

    private static final Logger LOG = Logger.LOG;

    private McWorlds() {
    }

    /// Streams the world as a zip.
    ///
    /// @param instance the instance
    /// @param manager  the instance manager
    /// @param out      the response body
    /// @throws McException when the world cannot be read or written
    public static void export(McInstance instance, McInstanceManager manager, OutputStream out) throws McException {
        Path world = manager.worldDirectory(instance);
        if (!Files.isDirectory(world)) {
            throw new McException("the world `" + instance.levelName() + "` does not exist yet");
        }
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            try (var walk = Files.walk(world)) {
                for (Path path : walk.filter(Files::isRegularFile).sorted().toList()) {
                    String name = instance.levelName() + "/"
                            + world.relativize(path).toString().replace('\\', '/');
                    zip.putNextEntry(new ZipEntry(name));
                    Files.copy(path, zip);
                    zip.closeEntry();
                }
            }
        } catch (IOException e) {
            throw new McException("Failed to export the world: " + e.getMessage(), e);
        }
    }

    /// Replaces the world with the zip, after snapshotting the current one.
    ///
    /// @param instance the instance
    /// @param manager  the instance manager
    /// @param backups  where the safety snapshot goes
    /// @param in       the uploaded zip
    /// @return the safety snapshot's name, or `null` when there was no old world
    /// @throws McException when the zip is invalid, too large, or cannot be unpacked
    public static String importZip(McInstance instance, McInstanceManager manager,
                                   McBackups backups, InputStream in) throws McException {
        Path instanceDir = manager.instanceDirectory(instance.id());
        Path staging = instanceDir.resolve(".world-import");
        Path archive = instanceDir.resolve(".world-import.zip");
        try {
            Files.createDirectories(staging);
            long copied = copyCapped(in, archive);
            String stripPrefix = instance.levelName() + "/";
            int entries = 0;
            try (InputStream file = Files.newInputStream(archive); ZipInputStream zip = new ZipInputStream(file)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    String name = entry.getName().replace('\\', '/');
                    if (name.startsWith("/") || name.contains("..") || name.isBlank()) {
                        throw new McException("the world zip contains an unsafe entry: " + name);
                    }
                    if (!name.startsWith(stripPrefix)) {
                        int slash = name.indexOf('/');
                        if (slash > 0
                                && name.substring(slash + 1).equals("level.dat")
                                && !name.substring(0, slash).equals(instance.levelName())) {
                            // A zip packed under another world directory used to be nested
                            // (`world/oldworld/…`), after which the server found no
                            // `world/level.dat` and generated a fresh world — a silent data
                            // loss. Name the mismatch instead.
                            throw new McException("the world zip is packed under `"
                                    + name.substring(0, slash) + "/`, but this instance's world"
                                    + " directory is `" + instance.levelName() + "/`; set the"
                                    + " instance's world directory name to match, or repack the zip");
                        }
                        // Accept a world that was zipped without its directory.
                        name = instance.levelName() + "/" + name;
                    }
                    Path target = staging.resolve(name.substring(stripPrefix.length())).normalize();
                    if (!target.startsWith(staging)) {
                        throw new McException("the world zip contains an unsafe entry: " + entry.getName());
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(target);
                        continue;
                    }
                    Files.createDirectories(target.getParent());
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                    entries++;
                }
            }
            if (entries == 0) {
                throw new McException("the world zip is empty");
            }

            String safety = null;
            Path world = manager.worldDirectory(instance);
            if (Files.isDirectory(world)) {
                safety = backups.create(instance, manager).name();
            }
            Path old = instanceDir.resolve(".world-old");
            deleteTree(old);
            if (Files.isDirectory(world)) {
                Files.move(world, old);
            }
            Files.move(staging, world);
            deleteTree(old);
            LOG.info("Imported a world into " + instance.id() + " (" + entries + " files, "
                    + copied + " bytes)");
            return safety;
        } catch (IOException e) {
            throw new McException("Failed to import the world: " + e.getMessage(), e);
        } finally {
            try {
                deleteTree(staging);
                Files.deleteIfExists(archive);
            } catch (IOException ignored) {
                // Best effort: a leftover staging directory is not worth failing the import.
            }
        }
    }

    private static long copyCapped(InputStream in, Path target) throws IOException, McException {
        long copied = 0;
        try (OutputStream out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                copied += read;
                if (copied > MAX_IMPORT_BYTES) {
                    throw new McException("the world zip is larger than the panel accepts");
                }
                out.write(buffer, 0, read);
            }
        }
        return copied;
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}