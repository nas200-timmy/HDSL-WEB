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
package org.jackhuang.hmcl.web.transcode.runtime;

import kala.compress.archivers.ArchiveEntry;
import org.jackhuang.hmcl.util.io.Links;
import org.jackhuang.hmcl.util.io.SafePaths;
import org.jackhuang.hmcl.util.tree.ArchiveFileTree;
import org.jackhuang.hmcl.util.tree.TarFileTree;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.tukaani.xz.XZInputStream;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/// Archive extraction for ffmpeg artifacts, without a system `tar`.
///
/// The panel may be the only thing on the machine; a static ffmpeg build is a
/// `.tar.xz`, and "run `tar`" is not an assumption it can make. The same rules
/// as Node's installer apply: no member may land outside the destination, and a
/// symbolic link is recreated only when its target stays inside the tree.
@NotNullByDefault
public final class FfmpegArchive {

    private FfmpegArchive() {
    }

    public static void extract(Path archive, Path destination) throws IOException {
        String name = archive.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".zip")) {
            extractZip(archive, destination);
            return;
        }
        if (name.endsWith(".tar.xz") || name.endsWith(".txz")) {
            Path tar = Files.createTempFile("hdsl-ffmpeg", ".tar");
            try {
                try (InputStream in = Files.newInputStream(archive);
                     XZInputStream xz = new XZInputStream(in)) {
                    Files.copy(xz, tar, StandardCopyOption.REPLACE_EXISTING);
                }
                extractTar(tar, destination);
            } finally {
                Files.deleteIfExists(tar);
            }
            return;
        }
        extractTar(archive, destination);
    }

    private static void extractTar(Path archive, Path destination) throws IOException {
        try (TarFileTree tree = TarFileTree.open(archive)) {
            extract(tree, tree.getRoot(), destination, destination);
        }
    }

    private static <R, E extends ArchiveEntry> void extract(ArchiveFileTree<R, E> tree,
                                                            ArchiveFileTree.Dir<E> dir,
                                                            Path target,
                                                            Path root) throws IOException {
        for (var entry : dir.getFiles().entrySet()) {
            Path destination = target.resolve(entry.getKey());
            E archiveEntry = entry.getValue();
            if (tree.isLink(archiveEntry)) {
                Path link = Path.of(tree.getLink(archiveEntry));
                if (!link.isAbsolute() && SafePaths.resolveInside(root, target.resolve(link)) != null) {
                    SafePaths.createDirectories(root, destination.getParent());
                    Links.createOrCopy(destination, link);
                } else {
                    SafePaths.createDirectories(root, destination.getParent());
                    tree.extractTo(archiveEntry, destination);
                }
                continue;
            }
            SafePaths.createDirectories(root, destination.getParent());
            tree.extractTo(archiveEntry, destination);
            if (tree.isExecutable(archiveEntry)) {
                destination.toFile().setExecutable(true, false);
            }
        }
        for (var subDirectory : dir.getSubDirs().entrySet()) {
            Path destination = target.resolve(subDirectory.getKey());
            SafePaths.createDirectories(root, destination);
            extract(tree, subDirectory.getValue(), destination, root);
        }
    }

    private static void extractZip(Path archive, Path destination) throws IOException {
        Path root = destination.toAbsolutePath().normalize();
        SafePaths.createDirectories(root, root);
        try (ZipInputStream in = new ZipInputStream(
                new BufferedInputStream(Files.newInputStream(archive)))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                Path target = SafePaths.resolveArchiveEntry(root, entry.getName());
                if (target == null) {
                    throw new IOException("the archive names a path outside its own root: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    SafePaths.createDirectories(root, target);
                    continue;
                }
                SafePaths.createDirectories(root, target.getParent());
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /// Finds `ffmpeg` / `ffprobe` (or their `.exe` forms) under an extracted tree.
    public static @Nullable Path find(Path root, String name) throws IOException {
        try (var stream = Files.walk(root, 12)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        String file = path.getFileName().toString();
                        return file.equals(name) || file.equals(name + ".exe");
                    })
                    .findFirst()
                    .orElse(null);
        }
    }

    public static void deleteTree(@Nullable Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(candidate -> {
                try {
                    Files.deleteIfExists(candidate);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}