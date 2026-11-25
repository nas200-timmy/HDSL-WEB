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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// A real dependency-stack build. Opt-in: it downloads ~100 MB and spends tens of
/// minutes compiling, which is not something a normal test run should do.
@EnabledIfEnvironmentVariable(named = "HDSL_FULL_BUILD", matches = "1")
class FfmpegFullBuildIT {

    @Test
    @Timeout(7200)
    void buildsAGplBinaryWithStaticCodecs() throws Exception {
        Path root = Path.of(System.getProperty("hdsl.full.build.dir",
                "/tmp/hdsl-full-build-test"));
        Files.createDirectories(root);
        FfmpegArchive.deleteTree(root.resolve("logs"));
        Path tools = root.resolve("tools").resolve("ffmpeg");
        FfmpegCatalog.Catalog catalog = FfmpegCatalog.load(null);
        FfmpegRuntimeManager runtimes = new FfmpegRuntimeManager(tools, catalog);
        FfmpegToolchainManager toolchains = new FfmpegToolchainManager(root.resolve("toolchains"));
        FfmpegFullBuildKit kit = new FfmpegFullBuildKit(tools, runtimes, toolchains,
                root.resolve("logs"));
        FfmpegFullBuildCatalog.Entry entry = FfmpegFullBuildCatalog.load().find("full-gpl-9.1");
        assertTrue(entry != null, "full-gpl-9.1 must be in the catalogue");
        Path versionDir = tools.resolve(entry.id());
        FfmpegBuildKit.BuildResult result = kit.build(entry, versionDir, "it",
                (message, fraction) -> System.out.println("[" + Math.round(fraction * 100)
                        + "%] " + message),
                () -> false, FfmpegToolchainManager.Preference.AUTO);
        assertEquals(Set.copyOf(entry.features()), result.featuresEnabled());
        assertTrue(Files.isRegularFile(versionDir.resolve("bin").resolve("ffmpeg")));
        assertTrue(Files.isRegularFile(versionDir.resolve("runtime.json")));
        System.out.println("full build installed at " + versionDir);
    }
}