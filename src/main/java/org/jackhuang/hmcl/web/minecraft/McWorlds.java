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
import org.jetbrains.annotations.Nullable;

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

    /// How large an upload may be. A 5 GB world plus its staging copy and a safety backup
    /// fits; the number only stops a mistaken upload from filling the disk.
    public static final long MAX_IMPORT_BYTES = 16L * 1024 * 1024 * 1024;

    /// How many bytes one import may unpack. A zip bomb is a wrong file, not a world.
    static final long MAX_EXTRACTED_BYTES = 32L * 1024 * 1024 * 1024;

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

    /// Where an upload is staged before the task unpacks it.
    ///
    /// The request writes here and returns a task id; unpacking a 5 GB world is minutes of
    /// disk work that no HTTP response should wait for.
    ///
    /// @param manager  the instance manager
    /// @param instance the instance
    /// @return the archive path
    public static Path archiveFile(McInstanceManager manager, McInstance instance) {
        return manager.instanceDirectory(instance.id()).resolve(".world-import.zip");
    }

    /// Progress of an unpack.
    public interface Progress {
        /// @param files files written so far
        /// @param total entries in the archive
        /// @param bytes bytes written so far
        void unpacked(int files, int total, long bytes);
    }

    /// Saves the uploaded body next to the instance, enforcing the size cap.
    ///
    /// @param in      the request body
    /// @param archive where to write it
    /// @return how many bytes were written
    /// @throws McException when the upload exceeds what the panel accepts
    /// @throws IOException when it cannot be written
    public static long save(InputStream in, Path archive) throws McException, IOException {
        Files.createDirectories(archive.getParent());
        return copyCapped(in, archive, MAX_IMPORT_BYTES);
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
        Path archive = archiveFile(manager, instance);
        try {
            save(in, archive);
        } catch (McException e) {
            deleteQuietly(archive);
            throw e;
        } catch (IOException e) {
            deleteQuietly(archive);
            throw new McException("Failed to save the uploaded world: " + e.getMessage(), e);
        }
        return importArchive(instance, manager, backups, archive, null);
    }

    /// Unpacks an archive already on disk and swaps it in.
    ///
    /// @param instance the instance
    /// @param manager  the instance manager
    /// @param backups  where the safety snapshot goes
    /// @param archive  the uploaded zip
    /// @param progress called as entries land, or `null`
    /// @return the safety snapshot's name, or `null` when there was no old world
    /// @throws McException when the zip is invalid, too large, or cannot be unpacked
    public static String importArchive(McInstance instance, McInstanceManager manager,
                                       McBackups backups, Path archive,
                                       @Nullable Progress progress) throws McException {
        return importArchive(instance, manager, backups, archive, progress, MAX_EXTRACTED_BYTES);
    }

    /// The same, with the unpack limit named so a test can drive it.
    ///
    /// @param instance     the instance
    /// @param manager      the instance manager
    /// @param backups      where the safety snapshot goes
    /// @param archive      the uploaded zip
    /// @param progress     called as entries land, or `null`
    /// @param maxExtracted how many bytes the unpack may write
    /// @return the safety snapshot's name, or `null` when there was no old world
    /// @throws McException when the zip is invalid, too large, or cannot be unpacked
    static String importArchive(McInstance instance, McInstanceManager manager, McBackups backups,
                                Path archive, @Nullable Progress progress, long maxExtracted)
            throws McException {
        Path instanceDir = manager.instanceDirectory(instance.id());
        Path staging = instanceDir.resolve(".world-import");
        try {
            long archiveBytes = Files.size(archive);
            requireFreeSpace(instanceDir, archiveBytes);
            Files.createDirectories(staging);
            String stripPrefix = instance.levelName() + "/";
            int entries = 0;
            long extracted = 0;
            int total;
            // `ZipFile` rather than `ZipInputStream`: the central directory gives the entry
            // count up front, which is what makes a percentage possible at all. The 5 GB case
            // reads the directory once and streams each entry exactly as before.
            try (var zip = new java.util.zip.ZipFile(archive.toFile(), java.nio.charset.StandardCharsets.UTF_8)) {
                total = zip.size();
                var iteration = zip.entries();
                while (iteration.hasMoreElements()) {
                    var entry = iteration.nextElement();
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
                    try (InputStream entryIn = zip.getInputStream(entry);
                         OutputStream out = Files.newOutputStream(target)) {
                        extracted = copyEntry(entryIn, out, extracted, maxExtracted);
                    }
                    entries++;
                    if (progress != null && (entries % 64 == 0 || entries == total)) {
                        progress.unpacked(entries, total, extracted);
                    }
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
                    + extracted + " bytes)");
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

    /// Refuses an unpack the disk cannot hold.
    ///
    /// The peak is the archive, the unpacked tree, and (when a world is replaced) a safety zip
    /// of the old one. Three times the archive plus a gigabyte is a floor, not a promise — the
    /// point is to say no before a nearly-full disk turns a 5 GB import into a partial tree.
    ///
    /// @param instanceDir  where the unpack goes
    /// @param archiveBytes the archive's size
    /// @throws McException when there is clearly not enough room
    public static void requireFreeSpace(Path instanceDir, long archiveBytes) throws McException {
        try {
            long usable = Files.getFileStore(instanceDir).getUsableSpace();
            long needed = archiveBytes * 3 + 1024L * 1024 * 1024;
            if (usable < needed) {
                throw new McException("not enough free space for this import: about "
                        + (needed / (1024 * 1024)) + " MB needed, " + (usable / (1024 * 1024))
                        + " MB available in " + instanceDir);
            }
        } catch (IOException e) {
            // A filesystem that cannot answer is not a reason to refuse; the write will say so.
            LOG.warning("Could not read free space for " + instanceDir, e);
        }
    }

    /// Copies one entry, counting what the unpack has written.
    ///
    /// @param in      the entry
    /// @param out     the file
    /// @param already bytes written before this entry
    /// @param limit   the most this import may write
    /// @return bytes written including this entry
    /// @throws IOException  when reading or writing fails
    /// @throws McException when the limit is crossed
    private static long copyEntry(InputStream in, OutputStream out, long already, long limit)
            throws IOException, McException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            already += read;
            if (already > limit) {
                throw new McException("the world unpacks to more than the panel accepts ("
                        + (limit / (1024 * 1024 * 1024)) + " GiB)");
            }
            out.write(buffer, 0, read);
        }
        return already;
    }

    private static long copyCapped(InputStream in, Path target, long limit) throws IOException, McException {
        long copied = 0;
        try (OutputStream out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                copied += read;
                if (copied > limit) {
                    throw new McException("the world zip is larger than the panel accepts ("
                            + (limit / (1024 * 1024 * 1024)) + " GiB)");
                }
                out.write(buffer, 0, read);
            }
        }
        return copied;
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best effort.
        }
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