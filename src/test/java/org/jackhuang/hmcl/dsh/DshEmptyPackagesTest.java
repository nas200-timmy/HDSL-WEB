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
package org.jackhuang.hmcl.dsh;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// The half-installed tree, which looks installed from every cheap angle.
///
/// `bin.js` is one file: it is written early and survives an interrupted install, so the
/// only cheap proof of a usable tree is the store behind it. A tree whose package
/// directories are empty starts, then answers every session request with
/// `waiting for workingDirectory` — the plugin that provides that service is the one
/// whose dependency is missing.
class DshEmptyPackagesTest {

    @TempDir
    Path temp;

    @Test
    void aCompleteTreeHasNoHoles() throws Exception {
        Path dsh = temp.resolve("dsh");
        packageDir(dsh, "zod@4.6.7", "zod", true);
        packageDir(dsh, "@deepseek-ai+dsh-agent@0.2.1", "@deepseek-ai/dsh-agent", true);

        assertEquals(0, DshVersionManager.emptyPackages(dsh));
    }

    @Test
    void anEmptyPackageDirectoryIsCounted() throws Exception {
        Path dsh = temp.resolve("dsh");
        packageDir(dsh, "zod@4.4.3", "zod", true);
        packageDir(dsh, "zod@4.6.7", "zod", false);

        assertEquals(1, DshVersionManager.emptyPackages(dsh));
    }

    @Test
    void aScopedPackageIsCountedToo() throws Exception {
        Path dsh = temp.resolve("dsh");
        packageDir(dsh, "@deepseek-ai+dsh-working-di_x", "@deepseek-ai/dsh-working-directory", false);

        assertEquals(1, DshVersionManager.emptyPackages(dsh));
    }

    @Test
    void aTreeWithNoStoreHasNothingToReport() {
        // Not a pnpm layout at all: not evidence of a broken install.
        assertEquals(0, DshVersionManager.emptyPackages(temp.resolve("nowhere")));
    }

    /// Builds `.pnpm/<id>/node_modules/<name>/package.json`, or leaves the directory empty
    /// when `complete` is false — exactly the shape an interrupted install leaves behind.
    private static void packageDir(Path dsh, String id, String name, boolean complete) throws Exception {
        Path directory = dsh.resolve("node_modules").resolve(".pnpm").resolve(id)
                .resolve("node_modules").resolve(name);
        Files.createDirectories(directory);
        if (complete) {
            Files.writeString(directory.resolve("package.json"), "{\"name\":\"" + name + "\"}");
        }
    }
}
