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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The installer's Hermes-specific decision points, none of which needs the
/// network: where the toolchain versions come from, what the lock rewrite does
/// to a package URL, which file in a CPython archive is the interpreter, and
/// what the pruning keeps.
class HermesInstallTest {

    @TempDir
    Path dir;

    @Test
    void versionsAndArtifactsComeFromTheReleasesOwnLock() throws Exception {
        Path release = dir.resolve("release");
        Files.createDirectories(release.resolve("pm"));
        String platform = BrandInstaller.hermesPlatform();
        if (platform == null) {
            return; // a platform upstream ships no artifacts for; nothing to assert
        }
        Files.writeString(release.resolve("pm/lock.json"), """
                {"schema":1,"packages":{
                  "uv":{"version":"0.13.5","artifacts":{"%s":{"sha256":"aa","url":"https://x/uv.tar.gz"}}},
                  "python":{"version":"3.14.9+20261021","artifacts":{"%s":{"sha256":"bb","url":"https://x/py.tar.gz"}}}
                }}
                """.formatted(platform, platform), StandardCharsets.UTF_8);

        BrandInstaller.HermesLock lock = BrandInstaller.readHermesLock(release);

        assertEquals("0.13.5", lock.uvVersion());
        assertEquals("aa", lock.uv().sha256());
        assertEquals("3.14.9+20261021", lock.pythonVersion());
        assertEquals("https://x/py.tar.gz", lock.python().url());
    }

    @Test
    void aLockWithoutThisPlatformIsReportedInsteadOfGuessed() throws IOException {
        Path release = dir.resolve("release");
        Files.createDirectories(release.resolve("pm"));
        Files.writeString(release.resolve("pm/lock.json"), """
                {"packages":{"uv":{"version":"0.13.5","artifacts":{}},
                 "python":{"version":"3.14.9","artifacts":{}}}}
                """, StandardCharsets.UTF_8);

        if (BrandInstaller.hermesPlatform() != null) {
            assertThrows(BrandException.class, () -> BrandInstaller.readHermesLock(release));
        }
    }

    @Test
    void theLockRewriteKeepsThePathAndOnlyChangesTheHost() {
        assertEquals("https://mirror.example.com", BrandInstaller.originOf("https://mirror.example.com/simple"));
        assertEquals("https://mirror.example.com:8080",
                BrandInstaller.originOf("https://mirror.example.com:8080/simple/"));
        assertEquals("", BrandInstaller.originOf("not a url"));
    }

    @Test
    void theInterpreterIsFoundWherePythonBuildStandalonePutsIt() throws IOException {
        Path python = dir.resolve("3.14.9").resolve("python").resolve("bin");
        Files.createDirectories(python);
        Files.createFile(python.resolve("python3.14"));
        Files.createFile(python.resolve("python3.15")); // a second minor must not confuse it

        Path found = BrandInstaller.findInterpreter(dir.resolve("3.14.9"));

        assertNotNull(found);
        assertTrue(found.getFileName().toString().matches("python3\\.1[45]"), found.toString());
        assertNull(BrandInstaller.findInterpreter(dir.resolve("absent")));
    }

    @Test
    void pruningKeepsTheSourceAndTheVenvs() throws IOException {
        Path target = dir.resolve("release");
        Path brandRoot = dir.resolve("hermes");
        Path products = brandRoot.resolve("products/0.22.5");
        for (String kept : new String[]{"hermes_cli/web_dist/index.html", "hermes_cli/tui_dist/entry.js",
                ".venv/bin/hermes", "install-stamp.json", "uv.lock"}) {
            Files.createDirectories(target.resolve(kept).getParent());
            Files.writeString(target.resolve(kept), "x", StandardCharsets.UTF_8);
        }
        for (String pruned : new String[]{"node_modules/a.js", "web/node_modules/b.js",
                "ui-tui/node_modules/c.js", "apps/desktop/node_modules/d.js"}) {
            Files.createDirectories(target.resolve(pruned).getParent());
            Files.writeString(target.resolve(pruned), "x", StandardCharsets.UTF_8);
        }
        Files.createDirectories(products.resolve("icons"));
        Files.writeString(products.resolve("icons/logo.png"), "x", StandardCharsets.UTF_8);

        BrandInstaller.pruneHermesBuild(target, brandRoot, products);

        assertTrue(Files.isRegularFile(target.resolve("hermes_cli/web_dist/index.html")));
        assertTrue(Files.isRegularFile(target.resolve(".venv/bin/hermes")));
        assertTrue(Files.isRegularFile(target.resolve("install-stamp.json")));
        assertEquals(false, Files.exists(target.resolve("node_modules")));
        assertEquals(false, Files.exists(target.resolve("web/node_modules")));
        assertEquals(false, Files.exists(target.resolve("apps/desktop/node_modules")));
        assertEquals(false, Files.exists(products));
    }
}
