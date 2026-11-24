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

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FfmpegSelectorTest {

    private static FfmpegCapabilities.Host linuxHost(String glibc) {
        return new FfmpegCapabilities.Host("linux", "x64", "glibc", glibc);
    }

    @Test
    void autoPrefersTheHighestSatisfiableMajor() throws Exception {
        FfmpegCatalog.Catalog catalog = catalog(
                entry("seven", "7.1.5", "2.28", "libx264"),
                entry("eight", "8.1.3", "2.28", "libx264"));

        FfmpegSelector.Recommendation selected = FfmpegSelector.select(
                catalog, linuxHost("2.36"), Set.of());

        assertEquals("eight", selected.entry().id());
        assertTrue(selected.reason().contains("major 8.1"), selected.reason());
        assertTrue(selected.reason().contains("features 0/0"), selected.reason());
        assertTrue(selected.reason().contains("vendor btbn"), selected.reason());
        assertTrue(selected.reason().contains("minGlibc 2.28 satisfied"), selected.reason());
    }

    @Test
    void autoFallsBackWhenTheHighestMajorCannotCoverRequiredFeatures() throws Exception {
        FfmpegCatalog.Catalog catalog = catalog(
                entry("seven", "7.1.5", "2.28", "libx264", "libvmaf"),
                entry("eight", "8.1.3", "2.28", "libx264"));

        FfmpegSelector.Recommendation selected = FfmpegSelector.select(
                catalog, linuxHost("2.36"), Set.of("libvmaf"));

        assertEquals("seven", selected.entry().id());
        assertTrue(selected.reason().contains("major 7.1"), selected.reason());
        assertTrue(selected.reason().contains("fell back to 7.1"), selected.reason());
        assertTrue(selected.reason().contains("8.1"), selected.reason());
        assertTrue(selected.reason().contains("features 1/1"), selected.reason());
    }

    @Test
    void explicitMajorNeverSilentlySwitches() {
        FfmpegCatalog.Catalog catalog = catalog(entry("eight", "8.1.3", "2.28", "libx264"));

        IOException error = assertThrows(IOException.class, () -> FfmpegSelector.select(
                catalog, linuxHost("2.36"), Set.of(), "7.1"));

        assertTrue(error.getMessage().contains("请求的 major 7.1"), error.getMessage());
        assertTrue(error.getMessage().contains("没有可用条目"), error.getMessage());
    }

    @Test
    void explicitMajorSelectsOnlyThatLine() throws Exception {
        FfmpegCatalog.Catalog catalog = catalog(
                entry("seven", "7.1.5", "2.28", "libx264"),
                entry("eight", "8.1.3", "2.28", "libx264"));

        FfmpegSelector.Recommendation selected = FfmpegSelector.select(
                catalog, linuxHost("2.36"), Set.of("libx264"), "7.1");

        assertEquals("seven", selected.entry().id());
        assertTrue(selected.reason().contains("major 7.1 (requested)"), selected.reason());
    }

    @Test
    void minGlibcIncompatibleEntryIsExcluded() throws Exception {
        FfmpegCatalog.Catalog catalog = catalog(
                entry("seven", "7.1.5", "2.28", "libx264"),
                entry("eight", "8.1.3", "2.34", "libx264"));

        FfmpegSelector.Recommendation selected = FfmpegSelector.select(
                catalog, linuxHost("2.28"), Set.of());

        assertEquals("seven", selected.entry().id());
        assertTrue(selected.reason().contains("8.1"), selected.reason());
    }

    @Test
    void minGlibcUsesNumericSegments() {
        FfmpegCatalog.Catalog catalog = catalog(entry("eight", "8.1.3", "2.28", "libx264"));

        assertThrows(IOException.class, () -> FfmpegSelector.select(
                catalog, linuxHost("2.9"), Set.of()));
    }

    @Test
    void unknownHostLibcVersionDoesNotRecommendMinGlibcEntry() {
        FfmpegCatalog.Catalog catalog = catalog(entry("eight", "8.1.3", "2.28", "libx264"));

        IOException error = assertThrows(IOException.class, () -> FfmpegSelector.select(
                catalog, new FfmpegCapabilities.Host("linux", "x64", "glibc"), Set.of()));

        assertTrue(error.getMessage().contains("host libc version unknown"), error.getMessage());
    }

    @Test
    void newestVersionWithinTheChosenMajorWins() throws Exception {
        FfmpegCatalog.Catalog catalog = catalog(
                entry("eight-old", "8.1.2", "2.28", "libx264"),
                entry("eight-new", "8.1.3", "2.28", "libx264"));

        FfmpegSelector.Recommendation selected = FfmpegSelector.select(
                catalog, linuxHost("2.36"), Set.of());

        assertEquals("eight-new", selected.entry().id());
    }

    @Test
    void oldThreeArgumentOverloadStillWorks() throws Exception {
        FfmpegCatalog.Catalog catalog = catalog(
                entry("narrow", "7.1.6", "2.28", "libx265"),
                entry("broad", "7.1.5", "2.28", "libx264", "vaapi"));

        FfmpegSelector.Recommendation selected = FfmpegSelector.select(
                catalog, linuxHost("2.36"), Set.of("libx264"));

        assertEquals("broad", selected.entry().id());
        assertTrue(selected.reason().contains("features 1/1"), selected.reason());
    }

    @Test
    void sourceSelectionHonoursMajorAndFeatures() throws Exception {
        FfmpegCatalog.Catalog catalog = catalog(
                sourceEntry("seven-source", "7.1.5", "7.1", "libx264"),
                sourceEntry("eight-source", "8.1.3", "8.1", "libx264", "libvmaf"));
        FfmpegCapabilities.Host host = linuxHost("2.36");

        assertEquals("eight-source", FfmpegSelector.selectSource(
                catalog, host, Set.of(), "auto").entry().id());
        assertEquals("seven-source", FfmpegSelector.selectSource(
                catalog, host, Set.of(), "7.1").entry().id());
        assertEquals("eight-source", FfmpegSelector.selectSource(
                catalog, host, Set.of("libvmaf"), "auto").entry().id());
        assertTrue(FfmpegSelector.selectSource(catalog, host, Set.of(), "auto")
                .reason().contains("source build"));
    }

    @Test
    void explicitSourceMajorNeverSilentlySwitches() {
        FfmpegCatalog.Catalog catalog = catalog(
                sourceEntry("eight-source", "8.1.3", "8.1", "libx264"));
        IOException error = assertThrows(IOException.class, () -> FfmpegSelector.selectSource(
                catalog, linuxHost("2.36"), Set.of(), "7.1"));
        assertTrue(error.getMessage().contains("on major 7.1"), error.getMessage());
    }

    @Test
    void sourceSelectionReportsUnavailableFeatures() {
        FfmpegCatalog.Catalog catalog = catalog(
                sourceEntry("eight-source", "8.1.3", "8.1", "libx264"));
        IOException error = assertThrows(IOException.class, () -> FfmpegSelector.selectSource(
                catalog, linuxHost("2.36"), Set.of("libvmaf"), "auto"));
        assertTrue(error.getMessage().contains("missing libvmaf"), error.getMessage());
    }

    private static FfmpegCatalog.Catalog catalog(FfmpegCatalog.Entry... entries) {
        return new FfmpegCatalog.Catalog(1, "", List.of(entries));
    }

    private static FfmpegCatalog.Entry sourceEntry(String id, String version, String major,
                                                    String... features) {
        String featureJson = String.join(",", Arrays.stream(features)
                .map(value -> "\"" + value + "\"")
                .toList());
        String json = """
                {
                  "schema": 1,
                  "entries": [
                    {
                      "id": "%s",
                      "os": "any",
                      "arch": "any",
                      "libc": "any",
                      "ffmpegVersion": "%s",
                      "license": "LGPL-2.1-or-later",
                      "kind": "source",
                      "vendor": "ffmpeg.org",
                      "major": "%s",
                      "variant": "source",
                      "features": [%s],
                      "url": "https://ffmpeg.org/releases/ffmpeg-%s.tar.xz",
                      "sha256": "%s",
                      "archive": "tar.xz",
                      "buildRecipe": "gpl-static-v1"
                    }
                  ]
                }
                """.formatted(id, version, major, featureJson, version, "a".repeat(64));
        return FfmpegCatalog.parse(json, "test").entries().get(0);
    }

    private static FfmpegCatalog.Entry entry(String id, String version, String minGlibc,
                                             String... features) {
        String featureJson = String.join(",", Arrays.stream(features)
                .map(value -> "\"" + value + "\"")
                .toList());
        String json = """
                {
                  "schema": 1,
                  "entries": [
                    {
                      "id": "%s",
                      "os": "linux",
                      "arch": "x64",
                      "libc": "glibc",
                      "ffmpegVersion": "%s",
                      "license": "GPL-3.0",
                      "kind": "binary",
                      "vendor": "btbn",
                      "variant": "gpl",
                      "minGlibc": "%s",
                      "features": [%s],
                      "url": "https://example.com/ffmpeg.tar.xz",
                      "sha256": "%s",
                      "archive": "tar.xz"
                    }
                  ]
                }
                """.formatted(id, version, minGlibc, featureJson, "a".repeat(64));
        return FfmpegCatalog.parse(json, "test").entries().get(0);
    }
}