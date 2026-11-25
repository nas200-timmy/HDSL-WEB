/*
 * HDSL
 * Copyright (C) 2026  HDSL contributors
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
package org.jackhuang.hmcl.dsh;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class UvRuntimeTest {

    @Test
    public void everyPlatformHasAPinnedDigest() throws Exception {
        for (String tag : List.of("linux-x64", "linux-arm64", "macos-x64", "macos-arm64",
                "windows-x64", "windows-arm64")) {
            Path fake = Files.createTempFile("uv-" + tag, ".bin");
            try {
                Files.writeString(fake, "not uv");
                IOException e = assertThrows(IOException.class,
                        () -> UvRuntime.verify(fake, "test", UvRuntime.binarySha256(tag), tag));
                assertTrue(e.getMessage().startsWith("uv_sha256_mismatch"), e.getMessage());
                assertTrue(e.getMessage().contains("expected="), e.getMessage());
            } finally {
                Files.deleteIfExists(fake);
            }
        }
    }

    @Test
    public void unpinnedPlatformIsRefusedRatherThanDownloaded() {
        IOException e = assertThrows(IOException.class,
                () -> UvRuntime.verify(Path.of("uv"), "test", null, "linux-riscv64"));
        assertTrue(e.getMessage().startsWith("uv_unpinned_platform"), e.getMessage());
        assertTrue(e.getMessage().contains("linux-riscv64"), e.getMessage());
    }

    @Test
    public void matchingDigestPasses(@TempDir Path dir) throws Exception {
        Path binary = dir.resolve("uv");
        Files.writeString(binary, "a uv stand-in");
        String actual = BootstrapRuntimeManager.sha256(binary);
        UvRuntime.verify(binary, "test", actual, "test-tag");
    }

    @Test
    public void missingFileIsNamed() {
        IOException e = assertThrows(IOException.class,
                () -> UvRuntime.verify(Path.of("/nonexistent/uv"), "test", "0".repeat(64), "tag"));
        assertTrue(e.getMessage().startsWith("uv_missing"), e.getMessage());
    }

    @Test
    public void thisPlatformIsPinned() {
        assertNotNull(UvRuntime.binarySha256(), "every supported OS/arch pair has a digest: "
                + UvRuntime.platformTag());
        assertFalse(UvRuntime.version().isBlank());
    }

    @Test
    public void wheelNamesAreMatchedByPlatform() {
        boolean arm = UvRuntime.platformTag().endsWith("arm64");
        boolean musl = PythonRuntimeManager.isMusl(java.nio.file.Files::exists);
        String name = switch (org.jackhuang.hmcl.util.platform.OperatingSystem.CURRENT_OS) {
            case WINDOWS -> "uv-" + UvRuntime.version() + "-py3-none-"
                    + (arm ? "win_arm64" : "win_amd64") + ".whl";
            case MACOS -> "uv-" + UvRuntime.version() + "-py3-none-macosx_11_0_"
                    + (arm ? "arm64" : "x86_64") + ".whl";
            default -> "uv-" + UvRuntime.version() + "-py3-none-"
                    + (musl ? "musllinux_1_1_" : "manylinux_2_17_")
                    + (arm ? "aarch64" : "x86_64")
                    + (musl ? "" : ".manylinux2014_" + (arm ? "aarch64" : "x86_64")) + ".whl";
        };
        assertTrue(UvRuntime.matchesWheel(name), name);
        assertFalse(UvRuntime.matchesWheel("uv-0.0.1-py3-none-any.whl"));
        assertFalse(UvRuntime.matchesWheel("uv-" + UvRuntime.version() + "-py3-none-any.whl"));
        if (org.jackhuang.hmcl.util.platform.OperatingSystem.CURRENT_OS
                == org.jackhuang.hmcl.util.platform.OperatingSystem.LINUX) {
            // The other libc's wheel is a different binary: its digest would
            // check out and the loader would then refuse it.
            String other = "uv-" + UvRuntime.version() + "-py3-none-"
                    + (musl ? "manylinux_2_17_x86_64.manylinux2014_x86_64.whl"
                            : "musllinux_1_1_x86_64.whl");
            assertEquals(musl, UvRuntime.matchesWheel(other), other);
        }
    }

    @Test
    public void versionIsExact() {
        assertEquals("0.7.13", UvRuntime.version());
    }

    @Test
    public void theSharedCopyLivesUnderToolchainsAndNamesThePlatformExecutable() {
        java.nio.file.Path binary = UvRuntime.sharedBinary();
        assertEquals(UvRuntime.root().resolve(UvRuntime.version()).resolve(
                UvRuntime.executableName()), binary);
        assertTrue(binary.toString().contains("toolchains"), binary.toString());
        assertTrue(UvRuntime.root().getFileName().toString().equals("uv"), binary.toString());
    }

    @Test
    public void anUnverifiedCacheEntryIsNotTrusted(@TempDir java.nio.file.Path dir)
            throws Exception {
        java.nio.file.Path fake = dir.resolve("uv");
        java.nio.file.Files.writeString(fake, "not the pinned bytes");
        assertFalse(UvRuntime.verified(fake));
        assertTrue(UvRuntime.summary().contains("DIGEST MISMATCH")
                || UvRuntime.summary().contains("not installed"),
                UvRuntime.summary());
    }
}
