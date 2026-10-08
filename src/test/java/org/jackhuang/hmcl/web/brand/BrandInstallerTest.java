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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The release-directory bookkeeping of the brand installer: enumeration,
/// newest-first ordering, current-link resolution — the parts that do not
/// need a real pnpm run (that half is covered by the end-to-end run).
class BrandInstallerTest {

    @TempDir
    Path root;

    @Test
    void releasesAreNewestFirstAndFlagTheCurrentLink() throws IOException {
        install(Brand.KIMI, "2.0.2");
        Path current = install(Brand.KIMI, "2.1.1");
        install(Brand.KIMI, "2.1.0");
        Files.deleteIfExists(root.resolve("current"));
        Files.createSymbolicLink(root.resolve("current"),
                root.relativize(current));

        List<BrandInstaller.Release> releases = BrandInstaller.releases(root);

        assertEquals(List.of("2.1.1", "2.1.0", "2.0.2"),
                releases.stream().map(BrandInstaller.Release::version).toList());
        assertTrue(releases.get(0).current());
        assertTrue(!releases.get(1).current());
    }

    @Test
    void numericOrderingAppliesToReleasesToo() throws IOException {
        install(Brand.OPENCODE, "1.9.0");
        install(Brand.OPENCODE, "1.18.0");

        List<BrandInstaller.Release> releases = BrandInstaller.releases(root);

        assertEquals(List.of("1.18.0", "1.9.0"),
                releases.stream().map(BrandInstaller.Release::version).toList());
    }

    @Test
    void packageDirExistsOnlyForInstalledVersions() throws IOException {
        install(Brand.KIMI, "2.1.1");

        assertTrue(BrandInstaller.packageDir(Brand.KIMI, root, "2.1.1") != null);
        assertNull(BrandInstaller.packageDir(Brand.KIMI, root, "2.1.0"));
    }

    @Test
    void defaultPackageIsTheCurrentLinkElseTheNewest() throws IOException {
        assertNull(BrandInstaller.defaultPackage(Brand.KIMI, root));

        Path older = install(Brand.KIMI, "2.0.2");
        Path newest = install(Brand.KIMI, "2.1.1");
        // No link yet: the newest release is the default.
        assertEquals(newest.toRealPath(), BrandInstaller.defaultPackage(Brand.KIMI, root).toRealPath());

        // The link wins when it exists, even pointing at an older release.
        Files.createSymbolicLink(root.resolve("current"), root.relativize(older));
        assertEquals(older.toRealPath(), BrandInstaller.defaultPackage(Brand.KIMI, root).toRealPath());
    }

    /// Creates one fake installed release: a directory holding only the
    /// executable the installer checks for.
    private Path install(Brand brand, String version) throws IOException {
        Path release = Files.createDirectories(root.resolve("releases").resolve(version));
        Path bin = release.resolve("node_modules/.bin/" + brand.binName());
        Files.createDirectories(bin.getParent());
        Files.createFile(bin);
        return release;
    }
}
