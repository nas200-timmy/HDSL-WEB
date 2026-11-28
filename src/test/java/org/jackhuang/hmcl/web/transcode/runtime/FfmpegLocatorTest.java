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

import org.jackhuang.hmcl.web.config.ServerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FfmpegLocatorTest {

    @TempDir
    Path temp;

    @Test
    void followsTheRuntimeManagerPointerBeforeLegacyCurrent() throws Exception {
        Path tools = temp.resolve("transcode/tools/ffmpeg");
        Path version = tools.resolve("managed-7.1/bin");
        Files.createDirectories(version);
        Files.writeString(version.resolve("ffmpeg"), "managed");
        Files.writeString(version.resolve("ffprobe"), "managed");
        Files.writeString(tools.resolve("current.json"), "{\"version\":\"managed-7.1\"}");

        Path legacy = tools.resolve("current/bin");
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("ffmpeg"), "legacy");
        Files.writeString(legacy.resolve("ffprobe"), "legacy");

        ServerConfig config = new ServerConfig();
        config.dataDir = temp;
        FfmpegLocator.FfmpegPaths paths = FfmpegLocator.locate(config);
        assertNotNull(paths);
        assertEquals(version.resolve("ffmpeg").toAbsolutePath().normalize(), paths.ffmpeg());
        assertEquals(version.resolve("ffprobe").toAbsolutePath().normalize(), paths.ffprobe());
    }

    @Test
    void anExplicitDefaultRuntimeWinsAndNeverFallsBack() throws Exception {
        Path tools = temp.resolve("transcode/tools/ffmpeg");
        Path version = tools.resolve("managed-8.1/bin");
        Files.createDirectories(version);
        Files.writeString(version.resolve("ffmpeg"), "managed");
        Files.writeString(version.resolve("ffprobe"), "managed");
        Files.writeString(tools.resolve("current.json"), "{\"version\":\"managed-8.1\"}");

        ServerConfig config = new ServerConfig();
        config.dataDir = temp;
        config.transcode.defaultRuntime = "managed-8.1";
        FfmpegLocator.FfmpegPaths paths = FfmpegLocator.locate(config);
        assertNotNull(paths);
        assertEquals(version.resolve("ffmpeg").toAbsolutePath().normalize(), paths.ffmpeg());
        assertEquals("managed", FfmpegLocator.resolve(config).kind());
        assertEquals("managed-8.1", FfmpegLocator.resolve(config).id());

        config.transcode.defaultRuntime = "not-installed";
        assertNull(FfmpegLocator.locate(config), "a missing explicit choice must not fall back");
        assertEquals("missing", FfmpegLocator.resolve(config).kind());
        assertEquals("not-installed", FfmpegLocator.resolve(config).id());
    }

    @Test
    void autoReportsTheManagedCurrentRuntime() throws Exception {
        Path tools = temp.resolve("transcode/tools/ffmpeg");
        Path version = tools.resolve("managed-7.1/bin");
        Files.createDirectories(version);
        Files.writeString(version.resolve("ffmpeg"), "managed");
        Files.writeString(version.resolve("ffprobe"), "managed");
        Files.writeString(tools.resolve("current.json"), "{\"version\":\"managed-7.1\"}");

        ServerConfig config = new ServerConfig();
        config.dataDir = temp;
        config.transcode.defaultRuntime = "auto";
        FfmpegLocator.Resolution resolution = FfmpegLocator.resolve(config);
        assertEquals("managed", resolution.kind());
        assertEquals("managed-7.1", resolution.id());
        assertTrue(resolution.ffmpeg().startsWith(tools));
    }

    @Test
    void systemPreferenceDoesNotUseAManagedRuntime() throws Exception {
        Path tools = temp.resolve("transcode/tools/ffmpeg");
        Path version = tools.resolve("managed-7.1/bin");
        Files.createDirectories(version);
        Files.writeString(version.resolve("ffmpeg"), "managed");
        Files.writeString(version.resolve("ffprobe"), "managed");

        ServerConfig config = new ServerConfig();
        config.dataDir = temp;
        config.transcode.defaultRuntime = "system";
        FfmpegLocator.FfmpegPaths paths = FfmpegLocator.locate(config);
        if (paths != null) {
            assertFalse(paths.ffmpeg().startsWith(tools),
                    "system preference must not pick a managed runtime");
        }
    }
}