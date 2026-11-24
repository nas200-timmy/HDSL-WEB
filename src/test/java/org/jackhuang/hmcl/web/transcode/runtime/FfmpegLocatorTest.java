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
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
}