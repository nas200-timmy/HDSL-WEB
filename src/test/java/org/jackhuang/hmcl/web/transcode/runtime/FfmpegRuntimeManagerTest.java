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

import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FfmpegRuntimeManagerTest {

    @TempDir
    Path temp;

    @Test
    void installsActivatesRollsBackAndDeletes() throws Exception {
        Assumptions.assumeFalse(OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS,
                "the fake ffmpeg/ffprobe scripts are POSIX shell scripts");
        Path archive = zipWithFakeFfmpeg();
        String sha = sha256(archive);
        Path tools = temp.resolve("ffmpeg");
        FfmpegRuntimeManager manager = new FfmpegRuntimeManager(tools, FfmpegCatalog.Catalog.empty());

        FfmpegCatalog.Entry first = entry("test-7.1", "7.1-test", sha);
        FfmpegRuntimeManager.Installed installed = manager.installArchive(archive, first, (message, fraction) -> {
        });
        assertEquals("test-7.1", installed.id());
        assertEquals("test-7.1", manager.current().id());
        assertTrue(Files.isRegularFile(tools.resolve("test-7.1").resolve("bin").resolve("ffmpeg")));
        assertTrue(Files.isRegularFile(tools.resolve("test-7.1").resolve("runtime.json")));
        assertTrue(manager.current().runtime().featuresProbed.contains("libx264"));
        assertTrue(manager.current().runtime().featuresProbed.contains("vaapi"));

        FfmpegCatalog.Entry second = entry("test-7.2", "7.2-test", sha);
        manager.installArchive(archive, second, (message, fraction) -> {
        });
        assertEquals("test-7.2", manager.current().id());
        assertEquals("7.1-test", manager.installed().stream()
                .filter(item -> item.id().equals("test-7.1"))
                .findFirst().orElseThrow().runtime().version);

        manager.rollback();
        assertEquals("test-7.1", manager.current().id());

        FfmpegCatalog.Entry third = entry("test-7.3", "7.3-test", sha);
        manager.installArchive(archive, third, (message, fraction) -> {
        });
        manager.gc(0);
        // The rollback above made test-7.1 current and then test-7.3 current;
        // test-7.2 is the one no longer referenced.
        assertFalse(Files.exists(tools.resolve("test-7.2")));
        assertTrue(Files.exists(tools.resolve("test-7.1")));
        assertTrue(Files.exists(tools.resolve("test-7.3")));

        assertThrows(IOException.class, () -> manager.delete("test-7.3"));
        manager.activate("test-7.1");
        manager.delete("test-7.3");
        assertFalse(Files.exists(tools.resolve("test-7.3")));
        assertEquals("test-7.1", manager.current().id());
    }

    @Test
    void checksumMismatchLeavesNoVersion() throws Exception {
        Assumptions.assumeFalse(OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS,
                "the fake ffmpeg/ffprobe scripts are POSIX shell scripts");
        Path archive = zipWithFakeFfmpeg();
        Path tools = temp.resolve("ffmpeg-mismatch");
        FfmpegRuntimeManager manager = new FfmpegRuntimeManager(tools, FfmpegCatalog.Catalog.empty());
        FfmpegCatalog.Entry entry = entry("bad", "7.1-test", "b".repeat(64));
        assertThrows(IOException.class, () -> manager.installArchive(archive, entry, (message, fraction) -> {
        }));
        assertFalse(Files.exists(tools.resolve("bad")));
    }

    @Test
    void sourceEntriesAreNotInstallable() {
        FfmpegCatalog.Entry source = FfmpegCatalog.parse("""
                {
                  "schema": 1,
                  "entries": [
                    {
                      "id": "linux-source-7.1",
                      "os": "linux",
                      "arch": "x64",
                      "libc": "glibc",
                      "kind": "source",
                      "ffmpegVersion": "7.1.5",
                      "license": "GPL-3.0",
                      "features": ["libx264"]
                    }
                  ]
                }
                """, "test").entries().get(0);
        Path tools = temp.resolve("ffmpeg-source");
        FfmpegRuntimeManager manager = new FfmpegRuntimeManager(tools, FfmpegCatalog.Catalog.empty());
        IOException error = assertThrows(IOException.class, () -> manager.installArchive(
                temp.resolve("no-such-archive.tar.xz"), source, (message, fraction) -> {
                }));
        assertTrue(error.getMessage().contains("ffmpeg_source_unsupported"), error.getMessage());
    }

    private Path zipWithFakeFfmpeg() throws Exception {
        Path archive = temp.resolve("ffmpeg.zip");
        Files.createDirectories(temp.resolve("scripts"));
        Path ffmpeg = temp.resolve("scripts/ffmpeg");
        Path ffprobe = temp.resolve("scripts/ffprobe");
        Files.writeString(ffmpeg, """
                #!/bin/sh
                case "$*" in
                  *"-encoders"*) echo " V....D libx264              H.264"; echo " V....D libx265              H.265";;
                  *"-hwaccels"*) echo "vaapi";;
                  *"-filters"*) echo " TSC scale              Scale";;
                  *) echo "ffmpeg version 7.1-test";;
                esac
                """, StandardCharsets.UTF_8);
        Files.writeString(ffprobe, "#!/bin/sh\necho \"ffprobe version 7.1-test\"\n", StandardCharsets.UTF_8);
        makeExecutable(ffmpeg);
        makeExecutable(ffprobe);
        try (OutputStream out = Files.newOutputStream(archive);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("ffmpeg-test/bin/ffmpeg"));
            Files.copy(ffmpeg, zip);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("ffmpeg-test/bin/ffprobe"));
            Files.copy(ffprobe, zip);
            zip.closeEntry();
        }
        return archive;
    }

    private static FfmpegCatalog.Entry entry(String id, String version, String sha) {
        return new FfmpegCatalog.Entry(id, "linux", "x64", "glibc", version, "GPL-3.0",
                List.of("libx264", "libx265", "vaapi"), "https://example.com/ffmpeg.zip",
                sha, 0, "zip", "test");
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void makeExecutable(Path file) throws IOException {
        try {
            var permissions = Files.getPosixFilePermissions(file);
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(file, permissions);
        } catch (UnsupportedOperationException ignored) {
        }
    }
}