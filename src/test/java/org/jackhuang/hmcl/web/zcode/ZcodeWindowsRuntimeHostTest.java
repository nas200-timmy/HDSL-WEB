/*
 * HDSL-web
 * Copyright (C) 2026  HDSL-web contributors
 *
 * This program is free software; you can redistribute it and/or modify
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
package org.jackhuang.hmcl.web.zcode;

import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The Windows runtime-host half of the fix for "Windows runtime host is
/// missing": the launch injects `ZCODE_SERVER_RUNTIME_ROOT` only when both
/// executables are staged, and provisioning never touches a non-Windows host
/// (the helper is win32-gated upstream, so a Linux panel neither needs nor
/// can even test-compile it — that half is exercised by hand on a real
/// Windows build).
class ZcodeWindowsRuntimeHostTest {

    @TempDir
    Path dir;

    @Test
    void noAssetMeansNoEnvironment() {
        assertFalse(ZcodeWindowsRuntimeHost.isAvailable(dir));
        assertEquals(Map.of(), ZcodeWindowsRuntimeHost.environment(dir));
    }

    @Test
    void stagedAssetBecomesTheRuntimeRoot() throws IOException {
        Path tools = ZcodeWindowsRuntimeHost.assetsDir(dir).resolve("tools/runtime-host");
        Files.createDirectories(tools);
        // One exe alone is not enough — upstream spawns the installer helper too.
        Files.writeString(tools.resolve("zcode-runtime-host.exe"), "");
        assertFalse(ZcodeWindowsRuntimeHost.isAvailable(dir));
        Files.writeString(tools.resolve("zcode-installer-process-helper.exe"), "");
        assertTrue(ZcodeWindowsRuntimeHost.isAvailable(dir));
        assertEquals(Map.of("ZCODE_SERVER_RUNTIME_ROOT",
                        ZcodeWindowsRuntimeHost.assetsDir(dir).toString()),
                ZcodeWindowsRuntimeHost.environment(dir));
    }

    @Test
    void provisioningIsASilentNoOpOffWindows() throws IOException {
        Path sources = Files.createDirectories(dir.resolve("src"));
        List<String> log = new ArrayList<>();
        ZcodeWindowsRuntimeHost.provision(sources, dir, log::add);
        if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
            // On Windows this run had no sources to compile from; either way it
            // must not throw, and the reason goes to the log.
            assertFalse(log.isEmpty());
        } else {
            assertFalse(ZcodeWindowsRuntimeHost.isAvailable(dir));
            assertTrue(log.stream().anyMatch(line -> line.contains("非 Windows 宿主")));
        }
    }

    @Test
    void compilerSearchWalksNestedToolchainLayouts() throws IOException {
        Path nested = Files.createDirectories(
                dir.resolve("toolchain").resolve("x86_64-w64-mingw32").resolve("bin"));
        assertEquals(Optional.empty(), ZcodeWindowsRuntimeHost
                .compilerInForTest(dir.resolve("toolchain")));
        Files.writeString(nested.resolve("x86_64-w64-mingw32-g++.exe"), "");
        assertTrue(ZcodeWindowsRuntimeHost
                .compilerInForTest(dir.resolve("toolchain"))
                .filter(found -> found.getFileName().toString()
                        .equals("x86_64-w64-mingw32-g++.exe"))
                .isPresent());
    }
}
