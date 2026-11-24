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

import org.jackhuang.hmcl.dsh.BuildToolchainManager;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The ffmpeg view of the general build toolchain: the same compiler, plus the
/// development-package probes `configure` needs.
class FfmpegToolchainManagerTest {

    @TempDir
    Path temp;

    @Test
    void aPortableBuildToolchainIsSeenByTheFfmpegBuilder() throws Exception {
        Assumptions.assumeFalse(OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS,
                "the prepared bundle's wrappers are POSIX scripts");
        Path root = temp.resolve("toolchains/build");
        Path version = root.resolve(BuildToolchainManager.LINUX_VERSION);
        Path bin = version.resolve("bin");
        Files.createDirectories(bin);
        Files.createDirectories(version.resolve("zig"));
        Files.writeString(version.resolve("zig/zig"), "#!/bin/sh\nexit 0\n",
                StandardCharsets.UTF_8);
        for (String name : List.of("cc", "c++", "make", "ar", "ranlib", "ld",
                "pkg-config", "nasm")) {
            executable(bin.resolve(name));
        }
        BuildToolchainManager builds = new BuildToolchainManager(root, "");
        FfmpegToolchainManager manager = new FfmpegToolchainManager(
                temp.resolve("toolchains"), builds);

        FfmpegToolchainManager.Toolchain toolchain = manager.detect();

        assertTrue(toolchain.usable(), toolchain.missingMessage());
        assertTrue(toolchain.portable());
        assertEquals(bin.resolve("cc").toString(), toolchain.pathOf("cc"));
        assertEquals(bin.resolve("make").toString(), toolchain.pathOf("make"));
        assertTrue(toolchain.environment().get("PATH").startsWith(bin.toString()));
        assertTrue(toolchain.describe().contains("portable"), toolchain.describe());
    }

    private static void executable(Path file) throws IOException {
        Files.writeString(file, "#!/bin/sh\nexit 0\n", StandardCharsets.UTF_8);
        assertTrue(file.toFile().setExecutable(true, false));
    }
}
