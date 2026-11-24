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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FfmpegCapabilitiesTest {

    @Test
    void parsesGlibcFromLddVersion() {
        String output = """
                ldd (Debian GLIBC 2.36-9+deb12u10) 2.36
                Copyright (C) 2022 Free Software Foundation, Inc.
                """;
        assertEquals("2.36", FfmpegCapabilities.parseLibcVersion("glibc", output));
    }

    @Test
    void parsesGlibcFromGetconf() {
        assertEquals("2.28", FfmpegCapabilities.parseLibcVersion("glibc", "glibc 2.28\n"));
    }

    @Test
    void parsesMuslVersion() {
        String output = """
                musl libc (x86_64)
                Version 1.2.5
                Dynamic Program Loader
                """;
        assertEquals("1.2.5", FfmpegCapabilities.parseLibcVersion("musl", output));
    }

    @Test
    void unparseableLibcOutputStaysEmpty() {
        assertEquals("", FfmpegCapabilities.parseLibcVersion("glibc", ""));
        assertEquals("", FfmpegCapabilities.parseLibcVersion("glibc", "command not found"));
        assertEquals("", FfmpegCapabilities.parseLibcVersion("musl", "musl libc (x86_64)"));
    }

    @Test
    void minGlibcComparesNumericSegments() {
        FfmpegCapabilities.Host host = new FfmpegCapabilities.Host("linux", "x64", "glibc", "2.36");
        assertTrue(FfmpegCapabilities.supportsMinGlibc(host, "2.28"));
        assertTrue(FfmpegCapabilities.supportsMinGlibc(host, "2.36"));
        assertTrue(FfmpegCapabilities.supportsMinGlibc(host, ""));

        FfmpegCapabilities.Host older = new FfmpegCapabilities.Host("linux", "x64", "glibc", "2.9");
        assertFalse(FfmpegCapabilities.supportsMinGlibc(older, "2.28"));

        FfmpegCapabilities.Host unknown = new FfmpegCapabilities.Host("linux", "x64", "glibc");
        assertFalse(FfmpegCapabilities.supportsMinGlibc(unknown, "2.28"));
    }

    @Test
    void nonLinuxHostsDoNotNeedGlibc() {
        FfmpegCapabilities.Host windows = new FfmpegCapabilities.Host("windows", "x64", "none");
        assertTrue(FfmpegCapabilities.supportsMinGlibc(windows, ""));
        assertFalse(FfmpegCapabilities.supportsMinGlibc(windows, "2.28"));
        assertEquals("windows/x64/none", windows.describe());
    }
}