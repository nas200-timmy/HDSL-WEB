/*
 * HDSL-web
 * Copyright (C) 2026  HDSL-web contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.jackhuang.hmcl.web.transcode.runtime;

import kala.compress.archivers.tar.TarArchiveEntry;
import kala.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.XZOutputStream;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FfmpegArchiveTest {

    @TempDir
    Path temp;

    @Test
    void extractsTarXzWithoutSystemTar() throws Exception {
        Path archive = temp.resolve("ffmpeg.tar.xz");
        try (OutputStream out = Files.newOutputStream(archive);
             XZOutputStream xz = new XZOutputStream(out, new LZMA2Options());
             TarArchiveOutputStream tar = new TarArchiveOutputStream(xz)) {
            tarFile(tar, "ffmpeg-test/bin/ffmpeg", "#!/bin/sh\necho ffmpeg\n");
            tarFile(tar, "ffmpeg-test/bin/ffprobe", "#!/bin/sh\necho ffprobe\n");
        }
        Path destination = temp.resolve("out");
        FfmpegArchive.extract(archive, destination);
        assertTrue(Files.isRegularFile(destination.resolve("ffmpeg-test/bin/ffmpeg")));
        assertEquals("#!/bin/sh\necho ffprobe\n",
                Files.readString(destination.resolve("ffmpeg-test/bin/ffprobe")));
    }

    @Test
    void refusesZipEntriesThatEscapeTheDestination() throws Exception {
        Path archive = temp.resolve("escape.zip");
        try (OutputStream out = Files.newOutputStream(archive);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("../escape.txt"));
            zip.write("bad".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        assertThrows(IOException.class, () -> FfmpegArchive.extract(archive, temp.resolve("out")));
    }

    private static void tarFile(TarArchiveOutputStream tar, String name, String content) throws IOException {
        byte[] body = content.getBytes(StandardCharsets.UTF_8);
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(body.length);
        tar.putArchiveEntry(entry);
        tar.write(body);
        tar.closeArchiveEntry();
    }
}