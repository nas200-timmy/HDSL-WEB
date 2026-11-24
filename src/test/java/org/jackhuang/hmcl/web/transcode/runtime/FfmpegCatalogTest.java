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
        assertEquals(List.of("7.1", "8.1", "9.1"), catalog.majors());
        assertEquals(10, catalog.entries().size());
        assertEquals(3, catalog.entries().stream().filter(FfmpegCatalog.Entry::isSource).count());
    }

    @Test
    void builtinCatalogueDescribesEachPlatformHonestly() {
        FfmpegCatalog.Catalog catalog = FfmpegCatalog.load(null);
        FfmpegCatalog.Entry linuxX64 = catalog.find("linux-x64-glibc-gpl-btbn-8.1.3-2026-10");
        FfmpegCatalog.Entry linuxArm64 = catalog.find("linux-arm64-glibc-gpl-btbn-8.1.3-2026-10");
        FfmpegCatalog.Entry windowsX64 = catalog.find("windows-x64-gpl-btbn-8.1.3-2026-10");
        assertNotNull(linuxX64);
        assertNotNull(linuxArm64);
        assertNotNull(windowsX64);

        assertEquals("2.28", linuxX64.minGlibc());
        assertEquals("2.28", linuxArm64.minGlibc());
        assertEquals("", windowsX64.minGlibc());
        assertEquals("btbn", linuxX64.vendor());
        assertEquals("gpl", linuxX64.variant());
        assertEquals("binary", linuxX64.kind());

        assertTrue(linuxX64.features().containsAll(List.of("vaapi", "qsv", "nvenc")));
        assertFalse(linuxArm64.features().contains("vaapi"));
        assertFalse(linuxArm64.features().contains("qsv"));
        assertTrue(linuxArm64.features().contains("nvenc"));
        assertTrue(windowsX64.features().containsAll(List.of("qsv", "nvenc", "amf")));
    }

    @Test
    void builtinSourceEntriesArePinnedAndPlatformIndependent() {
        FfmpegCatalog.Catalog catalog = FfmpegCatalog.load(null);
        FfmpegCatalog.Entry seven = catalog.find("ffmpeg-source-7.1.5-gpl-static-v1");
        FfmpegCatalog.Entry eight = catalog.find("ffmpeg-source-8.1.3-gpl-static-v1");
        assertNotNull(seven);
        assertNotNull(eight);

        assertEquals("source", seven.kind());
        assertEquals("ffmpeg.org", seven.vendor());
        assertEquals("source", seven.variant());
        assertEquals("7.1", seven.major());
        assertEquals("8.1", eight.major());
        assertEquals("any", seven.os());
        assertEquals("any", seven.arch());
        assertEquals("any", seven.libc());
        assertEquals("gpl-static-v1", seven.buildRecipe());
        assertEquals("https://ffmpeg.org/releases/ffmpeg-7.1.5.tar.xz", seven.url());
        assertEquals("https://ffmpeg.org/releases/ffmpeg-7.1.5.tar.xz.asc", seven.signatureUrl());
        assertEquals("de668509caf9e35e3cd162473441fdb29538c6d96ed080292b3cf9e6fc5d558f",
                seven.sha256());
        assertEquals("7138d28c96d9d3e3af4ee3d8cad72741f8ffb40da90c1112235dea3ecd3178a3",
                eight.sha256());
        assertEquals(11050340, seven.size());
        assertEquals(11732036, eight.size());
        // `mirrors` are candidates the downloader tries, so they must serve the
        // same bytes. The GitHub archive is a different archive and stays a
        // manual comparison in the notes instead.
        assertTrue(seven.mirrors().isEmpty(), seven.mirrors().toString());
        assertTrue(eight.mirrors().isEmpty(), eight.mirrors().toString());
        FfmpegCatalog.Entry nine = catalog.find("ffmpeg-source-9.1-gpl-static-v1");
        assertNotNull(nine);
        assertEquals("9.1", nine.major());
        assertEquals("https://ffmpeg.org/releases/ffmpeg-9.1.tar.xz", nine.url());
        assertEquals(12557532, nine.size());
        assertTrue(seven.matches("linux", "x64", "glibc"));
        assertTrue(seven.matches("windows", "x64", "none"));
        assertTrue(seven.matches("macos", "arm64", "none"));
        assertFalse(seven.features().isEmpty());
    }

    @Test
    void parsesV2FieldsAndInfersMajor() {
        String json = """
                {
                  "schema": 1,
                  "entries": [
                    {
                      "id": "linux-x64-v2",
                      "os": "linux",
                      "arch": "x64",
                      "libc": "glibc",
                      "ffmpegVersion": "8.1.3-27-g8dca1586f4",
                      "license": "GPL-3.0",
                      "kind": "binary",
                      "vendor": "btbn",
                      "variant": "gpl",
                      "minGlibc": "2.28",
                      "mirrors": ["https://mirror.example/ffmpeg.tar.xz"],
                      "signatureUrl": "https://example.com/ffmpeg.sig",
                      "buildRecipe": "",
                      "features": ["libx264"],
                      "url": "https://example.com/ffmpeg.tar.xz",
                      "sha256": "%s",
                      "size": 123,
                      "archive": "tar.xz"
                    }
                  ]
                }
                """.formatted("a".repeat(64));
        FfmpegCatalog.Entry entry = FfmpegCatalog.parse(json, "test").entries().get(0);
        assertEquals("binary", entry.kind());
        assertEquals("btbn", entry.vendor());
        assertEquals("8.1", entry.major());
        assertEquals("gpl", entry.variant());
        assertEquals("2.28", entry.minGlibc());
        assertEquals(List.of("https://mirror.example/ffmpeg.tar.xz"), entry.mirrors());
        assertEquals("https://example.com/ffmpeg.sig", entry.signatureUrl());
        assertEquals("", entry.buildRecipe());
    }

    @Test
    void sourceEntriesParseWithoutArtifactFields() {
        String json = """
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
                      "vendor": "self",
                      "variant": "gpl",
                      "features": ["libx264", "libvmaf"],
                      "buildRecipe": "configure:--disable-shared --enable-static"
                    }
                  ]
                }
                """;
        FfmpegCatalog.Entry entry = FfmpegCatalog.parse(json, "test").entries().get(0);
        assertEquals("source", entry.kind());
        assertEquals("7.1", entry.major());
        assertEquals("", entry.url());
        assertEquals("", entry.sha256());
        assertEquals("", entry.archive());
        assertEquals("configure:--disable-shared --enable-static", entry.buildRecipe());
        assertFalse(entry.isBinary());
    }

    @Test
    void legacyEntriesKeepV2Defaults() {
        FfmpegCatalog.Entry entry = FfmpegCatalog.parse(
                "{\"schema\":1,\"entries\":[" + entry("legacy", "glibc", "7.1.5") + "]}",
                "test").entries().get(0);
        assertEquals("binary", entry.kind());
        assertEquals("", entry.vendor());
        assertEquals("7.1", entry.major());
        assertEquals("", entry.variant());
        assertEquals("", entry.minGlibc());
        assertEquals(List.of(), entry.mirrors());
        assertEquals("", entry.signatureUrl());
        assertEquals("", entry.buildRecipe());
        assertTrue(entry.isBinary());
    }

    @Test
    void rejectsUnknownKind() {
        String json = """
                {
                  "schema": 1,
                  "entries": [
                    {
                      "id": "bad-kind",
                      "os": "linux",
                      "arch": "x64",
                      "libc": "glibc",
                      "kind": "script",
                      "ffmpegVersion": "7.1.5",
                      "url": "https://example.com/ffmpeg.zip",
                      "sha256": "%s"
                    }
                  ]
                }
                """.formatted("a".repeat(64));
        assertTrue(FfmpegCatalog.parse(json, "test").entries().isEmpty());
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

    @Test
    public void theGyanWindowsBuildIsAPinnedZip() {
        FfmpegCatalog.Entry entry = FfmpegCatalog.load(null)
                .find("windows-x64-gpl-gyan-9.1-essentials");
        assertNotNull(entry);
        assertEquals("windows", entry.os());
        assertEquals("x64", entry.arch());
        assertEquals("9.1", entry.major());
        assertEquals("zip", entry.archive());
        assertEquals(119467219, entry.size());
        assertTrue(entry.url().startsWith("https://www.gyan.dev/ffmpeg/builds/packages/"),
                entry.url());
        assertTrue(entry.sha256().matches("[0-9a-f]{64}"), entry.sha256());
        assertTrue(entry.features().contains("libx264"), entry.features().toString());
    }
}
