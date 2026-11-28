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

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FfmpegFullBuildCatalogTest {

    @Test
    void theEmbeddedCatalogueHasBothProfilesAndPinnedBytes() {
        FfmpegFullBuildCatalog.Catalog catalog = FfmpegFullBuildCatalog.load();
        assertFalse(catalog.entries().isEmpty());
        FfmpegFullBuildCatalog.Entry gpl = catalog.find("full-gpl-9.1");
        FfmpegFullBuildCatalog.Entry lgpl = catalog.find("full-lgpl-9.1");
        assertNotNull(gpl);
        assertNotNull(lgpl);
        assertTrue(gpl.gpl());
        assertFalse(lgpl.gpl());
        assertTrue(gpl.sha256Pinned());
        assertTrue(lgpl.sha256Pinned());
        assertTrue(gpl.dependencies().stream().anyMatch(dep -> dep.id().equals("x264")));
        assertFalse(lgpl.dependencies().stream().anyMatch(dep -> dep.id().equals("x264")));
        assertTrue(gpl.dependencies().stream().allMatch(dep
                -> dep.sha256().matches("[0-9a-f]{64}")));
    }

    @Test
    void theFullRecipeRequestsAStaticLinkAndTheDeclaredEncoders() {
        Path prefix = Path.of("/tmp/prefix");
        List<String> gpl = FfmpegFullBuildKit.configureArgs(prefix,
                List.of("libx264", "libx265", "libvpx", "libaom", "libsvtav1",
                        "libopus", "libmp3lame", "libvorbis"), true, true);
        assertTrue(gpl.contains("--enable-gpl"));
        assertTrue(gpl.contains("--enable-libx264"));
        assertTrue(gpl.contains("--enable-libx265"));
        assertTrue(gpl.contains("--enable-libvpx"));
        assertTrue(gpl.contains("--enable-libaom"));
        assertTrue(gpl.contains("--enable-libsvtav1"));
        assertTrue(gpl.contains("--enable-libopus"));
        assertTrue(gpl.contains("--enable-libmp3lame"));
        assertTrue(gpl.contains("--enable-libvorbis"));
        assertTrue(gpl.contains("--pkg-config-flags=--static"));
        assertTrue(gpl.contains("--extra-ldflags=-L" + prefix + "/lib -static-libstdc++ -static-libgcc"));
        assertFalse(gpl.contains("--disable-x86asm"));

        List<String> lgpl = FfmpegFullBuildKit.configureArgs(prefix,
                List.of("libvpx", "libaom"), true, false);
        assertFalse(lgpl.contains("--enable-gpl"));
        assertFalse(lgpl.contains("--enable-libx264"));
        assertEquals(1L, lgpl.stream().filter(arg -> arg.equals("--enable-libvpx")).count());
    }
}