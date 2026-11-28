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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class FfmpegDownloadCandidatesTest {

    private static final String OFFICIAL =
            "https://github.com/FFmpeg-Builds/FFmpeg-Builds/releases/download/tag/a.tar.xz";

    @Test
    public void aPrefixIsTriedFirstThenTheOfficialUrl() {
        assertEquals(List.of("https://gh-proxy.com/" + OFFICIAL, OFFICIAL),
                FfmpegDownload.candidates("https://gh-proxy.com/", OFFICIAL, List.of()));
    }

    @Test
    public void catalogueMirrorsSitBetweenThePrefixAndTheOfficialUrl() {
        assertEquals(List.of("https://m/" + OFFICIAL, "https://mirror.example/a.tar.xz", OFFICIAL),
                FfmpegDownload.candidates("https://m/", OFFICIAL,
                        List.of("https://mirror.example/a.tar.xz")));
    }

    @Test
    public void noPrefixMeansTheCatalogueOrder() {
        assertEquals(List.of("https://mirror.example/a.tar.xz", OFFICIAL),
                FfmpegDownload.candidates("", OFFICIAL,
                        List.of("https://mirror.example/a.tar.xz")));
        assertEquals(List.of("https://mirror.example/a.tar.xz", OFFICIAL),
                FfmpegDownload.candidates("   ", OFFICIAL,
                        List.of("https://mirror.example/a.tar.xz")));
    }

    @Test
    public void duplicatesCollapseKeepingTheFirstChoice() {
        assertEquals(List.of("https://m/" + OFFICIAL, OFFICIAL),
                FfmpegDownload.candidates("https://m/", OFFICIAL, List.of(OFFICIAL)));
    }
}
