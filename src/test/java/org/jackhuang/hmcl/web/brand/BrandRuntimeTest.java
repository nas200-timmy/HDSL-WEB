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
package org.jackhuang.hmcl.web.brand;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Launch-command construction and the readiness-line handling of the brand
/// runtime — the two places where an upstream flag drift would break launching
/// silently.
class BrandRuntimeTest {

    @TempDir
    Path dir;

    @Test
    void kimiCommandCarriesTheProxyContract() throws IOException {
        Path pkg = prefix(Brand.KIMI);
        BrandInstance instance = new BrandInstance("abc", "kimi", "demo", "2.1.1", 0, 0, 1);

        List<String> command = BrandRuntime.command(Brand.KIMI, pkg, instance, "dsh.example.com");

        assertEquals(pkg.resolve("node_modules/.bin/kimi").toRealPath().toString(), command.get(0));
        assertTrue(command.contains("web"), command.toString());
        assertTrue(command.contains("--no-open"), command.toString());
        // Kimi picks its own ephemeral port and announces it — port 0 here.
        assertEquals("0", command.get(command.indexOf("--port") + 1), command.toString());
        assertTrue(command.contains("--host"), command.toString());
        // The DNS-rebinding allowlist carries the browser-facing host.
        assertEquals("dsh.example.com", command.get(command.indexOf("--allowed-host") + 1), command.toString());
        assertTrue(command.contains("--dangerous-bypass-auth"), command.toString());
    }

    @Test
    void kimiCommandOmitsTheAllowlistWithoutAHost() throws IOException {
        Path pkg = prefix(Brand.KIMI);
        BrandInstance instance = new BrandInstance("abc", "kimi", "demo", "2.1.1", 0, 0, 1);

        List<String> command = BrandRuntime.command(Brand.KIMI, pkg, instance, null);

        assertFalse(command.contains("--allowed-host"), command.toString());
    }

    @Test
    void opencodeCommandUsesAPanelAllocatedPort() throws IOException {
        Path pkg = prefix(Brand.OPENCODE);
        BrandInstance instance = new BrandInstance("abc", "opencode", "demo", "1.18.35", 0, 0, 1);

        List<String> command = BrandRuntime.command(Brand.OPENCODE, pkg, instance, null);

        int port = Integer.parseInt(command.get(command.indexOf("--port") + 1));
        assertTrue(port > 0, "a real pre-allocated port, not 0: " + command);
        assertEquals("127.0.0.1", command.get(command.indexOf("--hostname") + 1), command.toString());
    }

    @Test
    void commandRefusesAPrefixWithoutTheExecutable() {
        BrandInstance instance = new BrandInstance("abc", "kimi", "demo", "2.1.1", 0, 0, 1);

        assertThrows(IOException.class,
                () -> BrandRuntime.command(Brand.KIMI, dir, instance, null));
    }

    @Test
    void ansiEscapesAreStrippedBeforeMatching() {
        String banner = "\u001B[94m\u001B[1m  Web interface:     \u001B[0m http://127.0.0.1:45678/";
        String stripped = BrandRuntime.stripAnsi(banner);

        assertEquals("  Web interface:      http://127.0.0.1:45678/", stripped);
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("Web interface:\\s+http://127\\.0\\.0\\.1:(\\d+)/")
                        .matcher(stripped);
        assertTrue(matcher.find());
        assertEquals("45678", matcher.group(1));
    }

    @Test
    void stripAnsiLeavesPlainLinesAlone() {
        assertEquals("Local: http://127.0.0.1:35849/",
                BrandRuntime.stripAnsi("Local: http://127.0.0.1:35849/"));
    }

    /// Creates a fake installed prefix holding only the executable the command
    /// builder looks for.
    private Path prefix(Brand brand) throws IOException {
        Path pkg = Files.createDirectories(dir.resolve(brand.id() + "-rel"));
        Path bin = pkg.resolve("node_modules/.bin/" + brand.binName());
        Files.createDirectories(bin.getParent());
        Files.createFile(bin);
        return pkg;
    }
}
