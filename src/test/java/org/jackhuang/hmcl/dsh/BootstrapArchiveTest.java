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

import kala.compress.archivers.tar.TarArchiveEntry;
import kala.compress.archivers.tar.TarArchiveOutputStream;
import kala.compress.archivers.tar.TarConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BootstrapArchiveTest {

    /// A tar hard link names another member of the same archive, not a path
    /// relative to its own directory. Recreating it as a symbolic link produced
    /// a target that only resolved by accident, and the existence check then
    /// failed with "links ... to a missing ..." on a valid archive.
    @Test
    void aTarHardLinkBecomesARealFile(@TempDir Path dir) throws Exception {
        Path archive = dir.resolve("closure.tar.gz");
        byte[] body = "real contents".getBytes(StandardCharsets.US_ASCII);
        try (OutputStream out = Files.newOutputStream(archive);
             GZIPOutputStream gzip = new GZIPOutputStream(out);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            TarArchiveEntry file = new TarArchiveEntry("./usr/lib/libfoo.so.1.2.3");
            file.setSize(body.length);
            file.setMode(0644);
            tar.putArchiveEntry(file);
            tar.write(body);
            tar.closeArchiveEntry();

            TarArchiveEntry link = new TarArchiveEntry("./usr/lib/libfoo.so.1", TarConstants.LF_LINK);
            link.setLinkName("./usr/lib/libfoo.so.1.2.3");
            link.setMode(0644);
            tar.putArchiveEntry(link);
            tar.closeArchiveEntry();
        }

        Path root = dir.resolve("out");
        BootstrapArchive.extract(archive, "tar.gz",
                List.of(new BootstrapCatalog.Include("./usr/lib/", "lib")), root);

        Path linked = root.resolve("lib").resolve("libfoo.so.1");
        assertTrue(Files.isRegularFile(linked, java.nio.file.LinkOption.NOFOLLOW_LINKS),
                "a hard link must land as a real file, not a symlink");
        assertEquals("real contents", Files.readString(linked));
    }
}
