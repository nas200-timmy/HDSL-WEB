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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// The ZCode adapter must start the distribution's TUI entry — never the web
/// mode, never through a shell.
class ZcodeTuiTest {

    @TempDir
    Path packageDir;

    @Test
    void missingEntryFailsLoudly() {
        IOException expected = assertThrows(IOException.class, () -> ZcodeTui.target(
                packageDir, packageDir.resolve("data"), packageDir.resolve("workspace"), "z1", "node"));
        assertTrue(expected.getMessage().contains("bin/zcode.mjs"), expected.getMessage());
    }

    @Test
    void aConfiguredNodeThatDoesNotExistFailsLoudly() throws Exception {
        Path entry = packageDir.resolve("bin/zcode.mjs");
        Files.createDirectories(entry.getParent());
        Files.writeString(entry, "console.log('zcode');\n");
        Path agent = packageDir.resolve("agent/zcode.cjs");
        Files.createDirectories(agent.getParent());
        Files.writeString(agent, "// agent\n");

        IOException expected = assertThrows(IOException.class, () -> ZcodeTui.target(
                packageDir, packageDir.resolve("data"), packageDir.resolve("workspace"), "z1",
                packageDir.resolve("no-such-node").toString()));
        assertTrue(expected.getMessage().contains("zcode.nodePath"), expected.getMessage());
    }

    /// A distribution built for the web only has `server/`, `web/` and the
    /// runner; the TUI lives in `agent/zcode.cjs`. Starting that one would fail
    /// inside the PTY, so the adapter refuses it up front.
    @Test
    void aWebOnlyDistributionIsRefusedWithTheReason() throws Exception {
        Path entry = packageDir.resolve("bin/zcode.mjs");
        Files.createDirectories(entry.getParent());
        Files.writeString(entry, "// runner\n");

        IOException expected = assertThrows(IOException.class, () -> ZcodeTui.target(
                packageDir, packageDir.resolve("data"), packageDir.resolve("workspace"), "z1", "node"));
        assertTrue(expected.getMessage().contains("agent/zcode.cjs"), expected.getMessage());
    }

    /// The web mode's flags must not leak into the terminal: `--web` in a PTY
    /// would start a second server, `--port 0` would print a readiness line
    /// nobody reads, and `--no-token` belongs to the proxied mount.
    @Test
    void buildsTheTuiCommandWithoutTheWebFlags() throws Exception {
        assumeTrue(OperatingSystem.CURRENT_OS != OperatingSystem.WINDOWS,
                "POSIX execute bit is what makes a file a program");
        Path entry = packageDir.resolve("bin/zcode.mjs");
        Files.createDirectories(entry.getParent());
        Files.writeString(entry, "// runner\n");
        Path agent = packageDir.resolve("agent/zcode.cjs");
        Files.createDirectories(agent.getParent());
        Files.writeString(agent, "// agent\n");
        Path node = packageDir.resolve("node");
        Files.writeString(node, "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(node, PosixFilePermissions.fromString("rwx------"));

        Path data = packageDir.resolve("data");
        Path workspace = packageDir.resolve("workspace");
        TerminalTarget target = ZcodeTui.target(packageDir, data, workspace, "z1", node.toString());

        assertEquals(ZcodeTui.BRAND, target.brand());
        assertEquals("z1", target.instanceId());
        assertEquals(2, target.command().size(), "argv is node + entry: " + target.command());
        assertTrue(target.command().get(0).contains("node"), target.command().get(0));
        assertEquals(entry.toString(), target.command().get(1));
        assertFalse(target.command().contains("--web"), target.command().toString());
        assertFalse(target.command().contains("--host"), target.command().toString());
        assertFalse(target.command().contains("--port"), target.command().toString());
        assertFalse(target.command().contains("--no-token"), target.command().toString());
        assertEquals(workspace, target.cwd());
        assertEquals(data.toString(), target.environment().get("ZCODE_DATA_BASE_DIR"));
        // The session database lives under HOME, so the TUI inherits it: the
        // web process does the same, and both must list the same sessions.
        assertEquals(System.getenv("HOME"), target.environment().get("HOME"));
        assertEquals("xterm-256color", target.environment().get("TERM"));
        assertEquals("truecolor", target.environment().get("COLORTERM"));
    }
}