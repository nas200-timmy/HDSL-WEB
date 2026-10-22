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
package org.jackhuang.hmcl.web.terminal;

import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// The adapters must produce an argv whose first element is the interpreter
/// or the agent binary — never a shell and never a `.bin` shim.
class BrandTuiTest {

    @TempDir
    Path packageDir;

    @Test
    void kimiResolvesNodeAndTheJsEntry() throws Exception {
        Path dist = packageDir.resolve("node_modules/@moonshot-ai/kimi-code/dist");
        Path entry = dist.resolve("main.mjs");
        Files.createDirectories(dist);
        Files.writeString(entry, "#!/usr/bin/env node\n");
        Files.setPosixFilePermissions(entry, PosixFilePermissions.fromString("rwx------"));
        Path bin = packageDir.resolve("node_modules/.bin");
        Files.createDirectories(bin);
        Files.createSymbolicLink(bin.resolve("kimi"), entry);

        TerminalTarget target = new KimiTui().target(packageDir, packageDir.resolve("home"),
                packageDir.resolve("workspace"), "kimi-1", null);

        assertEquals("kimi", target.brand());
        assertEquals(2, target.command().size());
        assertTrue(target.command().get(0).endsWith("node"), "argv[0] is node: " + target.command());
        assertEquals(entry.toRealPath().toString(), target.command().get(1));
        assertEquals(packageDir.resolve("home").toString(), target.environment().get("HOME"));
        assertEquals("xterm-256color", target.environment().get("TERM"));
    }

    @Test
    void kimiResumeAddsTheFlag() throws Exception {
        Path dist = packageDir.resolve("node_modules/@moonshot-ai/kimi-code/dist");
        Files.createDirectories(dist);
        Path entry = dist.resolve("main.mjs");
        Files.writeString(entry, "#!/usr/bin/env node\n");
        Files.setPosixFilePermissions(entry, PosixFilePermissions.fromString("rwx------"));
        Files.createDirectories(packageDir.resolve("node_modules/.bin"));
        Files.createSymbolicLink(packageDir.resolve("node_modules/.bin/kimi"), entry);

        TerminalTarget target = new KimiTui().target(packageDir, packageDir.resolve("home"),
                packageDir.resolve("workspace"), "kimi-1", "session-42");

        assertEquals(List.of("--resume", "session-42"),
                target.command().subList(target.command().size() - 2, target.command().size()));
    }

    @Test
    void kimiWithoutAnEntryPointFailsLoudly() {
        IOException expected = assertThrows(IOException.class, () -> new KimiTui().target(
                packageDir, packageDir.resolve("home"), packageDir.resolve("workspace"), "kimi-1", null));
        assertTrue(expected.getMessage().contains("kimi entry point missing"), expected.getMessage());
    }

    @Test
    void opencodeResolvesTheNativeBinaryNotTheLauncher() throws Exception {
        assumeTrue(OperatingSystem.CURRENT_OS == OperatingSystem.LINUX, "binary name is per platform");
        String arch = System.getProperty("os.arch", "");
        String name = "@opencode/cli-linux-" + ("aarch64".equals(arch) || "arm64".equals(arch) ? "arm64" : "x64");
        Path binary = packageDir.resolve("node_modules").resolve(name).resolve("bin").resolve("opencode");
        Files.createDirectories(binary.getParent());
        Files.writeString(binary, "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwx------"));

        TerminalTarget target = new OpencodeTui().target(packageDir, packageDir.resolve("home"),
                packageDir.resolve("workspace"), "oc-1", "ses_123");

        assertEquals(binary.toString(), target.command().get(0));
        assertEquals(List.of("--session", "ses_123"),
                target.command().subList(target.command().size() - 2, target.command().size()));
    }

    @Test
    void opencodeWithoutABinaryFailsLoudly() {
        assumeTrue(OperatingSystem.CURRENT_OS == OperatingSystem.LINUX, "binary name is per platform");
        IOException expected = assertThrows(IOException.class, () -> new OpencodeTui().target(
                packageDir, packageDir.resolve("home"), packageDir.resolve("workspace"), "oc-1", null));
        assertTrue(expected.getMessage().contains("opencode native binary missing"), expected.getMessage());
    }
}
