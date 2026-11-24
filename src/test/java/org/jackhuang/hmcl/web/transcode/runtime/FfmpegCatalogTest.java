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

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FfmpegCatalogTest {

    private static String entry(String id, String libc, String version, String... features) {
        String sha = "a".repeat(64);
        String featureJson = String.join(",", java.util.Arrays.stream(features)
                .map(value -> "\"" + value + "\"")
                .toList());
        return """
                {
                  "id": "%s",
                  "os": "linux",
                  "arch": "x64",
                  "libc": "%s",
                  "ffmpegVersion": "%s",
                  "license": "GPL-3.0",
                  "features": [%s],
                  "url": "https://example.com/ffmpeg.tar.xz",
                  "sha256": "%s",
                  "archive": "tar.xz"
                }
                """.formatted(id, libc, version, featureJson, sha);
    }

    @Test
    void parsesAValidCatalogueAndSkipsBrokenEntries() {
        String json = """
                {
                  "schema": 1,
                  "updatedAt": "2026-11-24",
                  "entries": [
                    %s,
                    {
                      "id": "bad",
                      "os": "linux",
                      "arch": "x64",
                      "url": "https://example.com/bad.zip",
                      "sha256": "nope"
                    },
                    %s
                  ]
                }
                """.formatted(entry("first", "glibc", "7.1.5", "libx264"), entry("first", "glibc", "7.1.4"));
        FfmpegCatalog.Catalog catalog = FfmpegCatalog.parse(json, "test");
        assertEquals(1, catalog.entries().size());
        assertEquals("first", catalog.entries().get(0).id());
        assertNotNull(catalog.find("first"));
        assertTrue(catalog.find("missing") == null);
    }

    @Test
    void builtinCatalogueIsPackaged() {
        FfmpegCatalog.Catalog catalog = FfmpegCatalog.load(null);
        assertFalse(catalog.entries().isEmpty());
        assertNotNull(catalog.find("linux-x64-glibc-gpl-btbn-8.1.3-2026-10"));
    }

    @Test
    void matchesPlatformAndLibc() {
        FfmpegCatalog.Entry entry = FfmpegCatalog.parse(
                "{\"schema\":1,\"entries\":[" + entry("linux-glibc", "glibc", "7.1.5") + "]}",
                "test").entries().get(0);
        assertTrue(entry.matches("linux", "x64", "glibc"));
        assertFalse(entry.matches("linux", "x64", "musl"));
        assertFalse(entry.matches("windows", "x64", "glibc"));
    }

    @Test
    void selectorPrefersFeatureCoverageThenVersion() throws Exception {
        FfmpegCatalog.Entry broad = FfmpegCatalog.parse(
                "{\"schema\":1,\"entries\":[" + entry("broad", "glibc", "7.1.5", "libx264", "vaapi") + "]}",
                "test").entries().get(0);
        FfmpegCatalog.Entry narrow = FfmpegCatalog.parse(
                "{\"schema\":1,\"entries\":[" + entry("narrow", "glibc", "7.1.6", "libx265") + "]}",
                "test").entries().get(0);
        FfmpegSelector.Recommendation selected = FfmpegSelector.select(
                new FfmpegCatalog.Catalog(1, "", List.of(narrow, broad)),
                new FfmpegCapabilities.Host("linux", "x64", "glibc"),
                Set.of("libx264"));
        assertEquals("broad", selected.entry().id());
        assertTrue(selected.reason().contains("features 1/1"));
    }

    @Test
    void selectorReportsMissingPlatform() {
        FfmpegCatalog.Catalog empty = new FfmpegCatalog.Catalog(1, "", List.of());
        assertThrows(java.io.IOException.class, () -> FfmpegSelector.select(
                empty, new FfmpegCapabilities.Host("linux", "x64", "glibc"), Set.of()));
    }
}