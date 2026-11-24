/*
 * HDSL
 * Copyright (C) 2026  HDSL contributors
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
package org.jackhuang.hmcl.dsh;

import kala.compress.archivers.ar.ArArchiveEntry;
import kala.compress.archivers.ar.ArArchiveInputStream;
import kala.compress.archivers.tar.TarArchiveEntry;
import kala.compress.archivers.tar.TarArchiveInputStream;
import org.jackhuang.hmcl.util.io.Links;
import org.jackhuang.hmcl.util.io.SafePaths;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.tukaani.xz.XZInputStream;

/// Extracts the few members a bootstrap artifact contributes, without `ar`, `dpkg` or `tar`.
///
/// The panel may be the only thing on the machine, and the closure is needed
/// *because* the image is minimal — shelling out to tools a slim image does not
/// ship is not an option. Everything here is Java: kala-compress reads AR and
/// TAR, `xz`/`gzip` do the decompression, and the archive never decides where a
/// member lands. A member is written only when its archive path matches an
/// [#BootstrapCatalog.Include], so extracting `util-linux` does not write a
/// single file the closure does not use.
///
/// Symbolic links (Debian's libraries carry the usual `.so.N -> .so.N.M.K`
/// pair) are collected and created after the archive has been read, so a link
/// whose target appears later in the stream still resolves. A link that points
/// outside the destination is not created.
@NotNullByDefault
final class BootstrapArchive {

    private BootstrapArchive() {
    }

    /// Extracts the selected members of one artifact into `destination`.
    ///
    /// @param archive     the downloaded artifact
    /// @param kind        `deb`, `zip`, `tar`, `tar.gz` or `tar.xz`
    /// @param includes    the members to take
    /// @param destination the bundle's staging directory
    /// @throws IOException when the archive is malformed, a member escapes the
    ///                     destination, or an include matched nothing
    static void extract(Path archive, String kind, List<BootstrapCatalog.Include> includes,
                        Path destination) throws IOException {
        int[] matched = new int[includes.size()];
        Path root = destination.toAbsolutePath().normalize();
        Files.createDirectories(root);
        switch (kind) {
            case "deb" -> extractDeb(archive, includes, root, matched);
            case "zip" -> extractZip(archive, includes, root, matched);
            case "tar", "tar.gz", "tgz", "tar.xz", "txz" ->
                    extractTarFile(archive, kind, includes, root, matched);
            default -> throw new IOException("unsupported artifact archive: " + kind);
        }
        for (int i = 0; i < matched.length; i++) {
            if (matched[i] == 0) {
                throw new IOException("the artifact does not contain " + includes.get(i).prefix());
            }
        }
    }

    private static void extractDeb(Path archive, List<BootstrapCatalog.Include> includes,
                                   Path root, int[] matched) throws IOException {
        try (InputStream file = new BufferedInputStream(Files.newInputStream(archive));
             ArArchiveInputStream ar = new ArArchiveInputStream(file)) {
            ArArchiveEntry entry;
            while ((entry = ar.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.equals("data.tar.xz") || name.equals("data.tar.gz")
                        || name.equals("data.tar")) {
                    extractTarStream(decompress(name, ar), includes, root, matched);
                    return;
                }
            }
        }
        throw new IOException("the deb has no data.tar member");
    }

    private static void extractTarFile(Path archive, String kind,
                                       List<BootstrapCatalog.Include> includes,
                                       Path root, int[] matched) throws IOException {
        try (InputStream file = new BufferedInputStream(Files.newInputStream(archive))) {
            InputStream data = kind.equals("tar.gz") || kind.equals("tgz")
                    ? new GZIPInputStream(file)
                    : kind.equals("tar.xz") || kind.equals("txz")
                    ? new XZInputStream(file)
                    : file;
            extractTarStream(data, includes, root, matched);
        }
    }

    private static InputStream decompress(String name, InputStream stream) throws IOException {
        if (name.endsWith(".xz")) {
            return new XZInputStream(stream);
        }
        if (name.endsWith(".gz")) {
            return new GZIPInputStream(stream);
        }
        return stream;
    }

    private static void extractTarStream(InputStream stream,
                                         List<BootstrapCatalog.Include> includes,
                                         Path root, int[] matched) throws IOException {
        List<Link> links = new ArrayList<>();
        try (TarArchiveInputStream tar = new TarArchiveInputStream(stream)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                String name = normalize(entry.getName());
                if (name == null) {
                    continue;
                }
                int include = match(name, includes);
                if (include < 0) {
                    continue;
                }
                BootstrapCatalog.Include selected = includes.get(include);
                Path destination = destination(root, selected, name);
                if (entry.isDirectory()) {
                    SafePaths.createDirectories(root, destination);
                    matched[include]++;
                } else if (entry.isSymbolicLink() || entry.isLink()) {
                    String link = entry.getLinkName();
                    if (link == null || link.isBlank()) {
                        continue;
                    }
                    links.add(new Link(destination, Path.of(link)));
                    matched[include]++;
                } else if (entry.isFile()) {
                    SafePaths.createDirectories(root, destination.getParent());
                    Files.copy(tar, destination, StandardCopyOption.REPLACE_EXISTING);
                    if ((entry.getMode() & 0b001_001_001) != 0) {
                        destination.toFile().setExecutable(true, false);
                    }
                    matched[include]++;
                }
            }
        }
        for (Link link : links) {
            Path target = link.target();
            if (target.isAbsolute()) {
                throw new IOException("the archive links " + link.path() + " to " + target
                        + ", which is outside the bundle");
            }
            if (!inside(link.path().getParent(), target)) {
                throw new IOException("the archive links " + link.path() + " to " + target
                        + ", which leaves the bundle");
            }
            if (!Files.exists(link.path().getParent().resolve(target).normalize())) {
                throw new IOException("the archive links " + link.path() + " to a missing "
                        + target);
            }
            Links.createOrCopy(link.path(), target);
        }
    }

    private static void extractZip(Path archive, List<BootstrapCatalog.Include> includes,
                                   Path root, int[] matched) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(
                new BufferedInputStream(Files.newInputStream(archive)))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = normalize(entry.getName());
                if (name == null) {
                    continue;
                }
                int include = match(name, includes);
                if (include < 0) {
                    continue;
                }
                BootstrapCatalog.Include selected = includes.get(include);
                Path destination = destination(root, selected, name);
                if (entry.isDirectory()) {
                    SafePaths.createDirectories(root, destination);
                } else {
                    SafePaths.createDirectories(root, destination.getParent());
                    Files.copy(zip, destination, StandardCopyOption.REPLACE_EXISTING);
                    if ("bin".equals(selected.destination())) {
                        destination.toFile().setExecutable(true, false);
                    }
                }
                matched[include]++;
            }
        }
    }

    private static int match(String name, List<BootstrapCatalog.Include> includes) {
        for (int i = 0; i < includes.size(); i++) {
            if (name.equals(includes.get(i).prefix()) || name.startsWith(includes.get(i).prefix())) {
                return i;
            }
        }
        return -1;
    }

    private static Path destination(Path root, BootstrapCatalog.Include include, String member)
            throws IOException {
        String base = member.substring(member.lastIndexOf('/') + 1);
        if (base.isEmpty()) {
            throw new IOException("the archive names a member without a file name: " + member);
        }
        Path relative = Path.of(include.destination()).resolve(base);
        Path resolved = SafePaths.resolveInside(root, relative);
        if (resolved == null) {
            throw new IOException("the archive member " + member + " would land outside the bundle");
        }
        return resolved;
    }

    private static String normalize(String name) {
        String value = name == null ? "" : name.replace('\\', '/');
        while (value.startsWith("./")) {
            value = value.substring(2);
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.isEmpty() || value.startsWith("/") || value.equals("..")
                || value.contains("../")) {
            return null;
        }
        return value;
    }

    private static boolean inside(Path directory, Path target) {
        Path resolved = directory.resolve(target).normalize();
        Path absoluteDirectory = directory.toAbsolutePath().normalize();
        return resolved.toAbsolutePath().normalize().startsWith(absoluteDirectory);
    }

    private record Link(Path path, Path target) {
    }
}