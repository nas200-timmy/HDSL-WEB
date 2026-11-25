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

import kala.compress.archivers.tar.TarArchiveEntry;
import kala.compress.archivers.tar.TarArchiveOutputStream;
import org.jackhuang.hmcl.dsh.BuildToolchainManager;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        // Through the same call the installer makes, not a bare `createSymbolicLink`: a
        // symlink needs a privilege an ordinary Windows account does not have, and the
        // production code writes a pointer file there instead. Setting the fixture up with
        // the production call is what keeps this test about the ordering rather than about
        // which platform it ran on.
        org.jackhuang.hmcl.util.io.Links.pointAt(root.resolve("current"), current);

        List<BrandInstaller.Release> releases = BrandInstaller.releases(Brand.KIMI, root);

        assertEquals(List.of("2.1.1", "2.1.0", "2.0.2"),
                releases.stream().map(BrandInstaller.Release::version).toList());
        assertTrue(releases.get(0).current());
        assertTrue(!releases.get(1).current());
    }

    @Test
    void numericOrderingAppliesToReleasesToo() throws IOException {
        install(Brand.OPENCODE, "1.9.0");
        install(Brand.OPENCODE, "1.18.0");

        List<BrandInstaller.Release> releases = BrandInstaller.releases(Brand.OPENCODE, root);

        assertEquals(List.of("1.18.0", "1.9.0"),
                releases.stream().map(BrandInstaller.Release::version).toList());
    }

    @Test
    void packageDirExistsOnlyForInstalledVersions() throws IOException {
        install(Brand.KIMI, "2.1.1");

        assertTrue(BrandInstaller.packageDir(Brand.KIMI, root, "2.1.1") != null);
        assertNull(BrandInstaller.packageDir(Brand.KIMI, root, "2.1.0"));
    }

    /// Git brands prove their install differently: no executable, but the
    /// built web app must be there.
    @Test
    void gitBrandMarkerIsTheBuiltWebApp() throws IOException {
        install(Brand.CLAUDE_WEB, "0.2.1");

        assertTrue(BrandInstaller.packageDir(Brand.CLAUDE_WEB, root, "0.2.1") != null);
        assertNull(BrandInstaller.packageDir(Brand.CLAUDE_WEB, root, "0.2.0"));
    }

    /// The source zip GitHub serves has everything under one `<repo>-<tag>/`
    /// root; unpacking drops that prefix so the release directory IS the
    /// project root.
    @Test
    void sourceZipUnpacksWithoutItsRootFolder() throws IOException, BrandException {
        byte[] zip = zipOf("claude-web-0.2.1/package.json", "{}",
                "claude-web-0.2.1/server/src/index.ts", "// entry",
                "claude-web-0.2.1/web/dist/index.html", "<html>");
        Path target = root.resolve("zip-target");

        BrandInstaller.extractZip(new java.io.ByteArrayInputStream(zip), target);

        assertTrue(Files.exists(target.resolve("package.json")));
        assertTrue(Files.exists(target.resolve("server/src/index.ts")));
        assertTrue(Files.exists(target.resolve("web/dist/index.html")));
        assertTrue(!Files.exists(target.resolve("claude-web-0.2.1")));
        assertEquals("{}", Files.readString(target.resolve("package.json")));
    }

    @Test
    void sourceZipRefusesEntriesThatEscapeTheTarget() throws IOException {
        byte[] zip = zipOf("claude-web-0.2.1/../../escaped.txt", "gotcha");
        Path target = root.resolve("zip-target");

        assertThrows(BrandException.class,
                () -> BrandInstaller.extractZip(new java.io.ByteArrayInputStream(zip), target));
    }

    /// A tar member name is only half of the escape: the link target of a
    /// symbolic link is the other half, and it is just as untrusted. The panel
    /// unpacks these as root, so a link to `../../../etc/cron.d/x` followed by
    /// a member written through it is a root-owned file anywhere on the host.
    @Test
    void aTarLinkThatLeavesTheDestinationIsRefused() throws IOException {
        for (String link : List.of("../../escaped", "/etc/passwd", "inside/../../escaped")) {
            Path archive = root.resolve("link-" + Math.abs(link.hashCode()) + ".tar.gz");
            try (OutputStream out = Files.newOutputStream(archive);
                 GZIPOutputStream gzip = new GZIPOutputStream(out);
                 TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
                TarArchiveEntry entry = new TarArchiveEntry("evil",
                        kala.compress.archivers.tar.TarConstants.LF_SYMLINK);
                entry.setLinkName(link);
                entry.setMode(0777);
                tar.putArchiveEntry(entry);
                tar.closeArchiveEntry();
            }
            Path target = root.resolve("tar-target-" + Math.abs(link.hashCode()));

            BrandException thrown = assertThrows(BrandException.class,
                    () -> BrandInstaller.extractTarGz(archive, target, () -> false), link);
            assertTrue(thrown.getMessage().contains("Refusing"), thrown.getMessage());
            assertFalse(Files.exists(root.resolve("escaped")), link + " escaped");
        }
    }

    /// A link that stays inside is the normal case — CPython's `bin/python3` is
    /// one — so refusing everything would break every Hermes install.
    @Test
    void aTarLinkInsideTheDestinationIsKept() throws IOException, BrandException {
        Path archive = root.resolve("good-link.tar.gz");
        try (OutputStream out = Files.newOutputStream(archive);
             GZIPOutputStream gzip = new GZIPOutputStream(out);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            byte[] body = "real".getBytes(StandardCharsets.US_ASCII);
            TarArchiveEntry file = new TarArchiveEntry("python3.12");
            file.setSize(body.length);
            file.setMode(0755);
            tar.putArchiveEntry(file);
            tar.write(body);
            tar.closeArchiveEntry();

            TarArchiveEntry link = new TarArchiveEntry("python3",
                    kala.compress.archivers.tar.TarConstants.LF_SYMLINK);
            link.setLinkName("python3.12");
            link.setMode(0777);
            tar.putArchiveEntry(link);
            tar.closeArchiveEntry();
        }
        Path target = root.resolve("good-link-target");

        BrandInstaller.extractTarGz(archive, target, () -> false);

        assertTrue(Files.isSymbolicLink(target.resolve("python3")));
        assertEquals("real", Files.readString(target.resolve("python3")));
    }

    /// The uv archive is unpacked by the panel's own tar reader, not by a
    /// system `tar` a minimal image does not have. The production helper is
    /// called directly, so this fails if the extraction path ever stops
    /// producing `uv-<triple>/uv`.
    @Test
    void theUvTarballIsUnpackedInJava() throws IOException, BrandException {
        Path archive = root.resolve("uv.tar.gz");
        try (OutputStream out = Files.newOutputStream(archive);
             GZIPOutputStream gzip = new GZIPOutputStream(out);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            TarArchiveEntry entry = new TarArchiveEntry("uv-x86_64-unknown-linux-gnu/uv");
            byte[] body = "#!/bin/sh\nexit 0\n".getBytes(StandardCharsets.US_ASCII);
            entry.setSize(body.length);
            entry.setMode(0755);
            tar.putArchiveEntry(entry);
            tar.write(body);
            tar.closeArchiveEntry();
        }
        Path toolchain = root.resolve("toolchain");

        Path uv = BrandInstaller.unpackUv(archive, toolchain, "x86_64-unknown-linux-gnu");

        assertTrue(Files.isRegularFile(uv));
        assertTrue(Files.isExecutable(uv));
        assertEquals(toolchain.resolve("uv-x86_64-unknown-linux-gnu").resolve("uv"), uv);
    }

    /// A source build resolves the panel's build toolchain and gets its
    /// compiler variables; a portable-archive install never asks for one.
    @Test
    void aSourceBuildGetsTheBuildToolchainsEnvironment() throws Exception {
        Path bin = root.resolve("fake-build-bin");
        Files.createDirectories(bin);
        for (String name : List.of("cc", "c++", "make", "pkg-config", "nasm")) {
            Path script = bin.resolve(name);
            Files.writeString(script, "#!/bin/sh\nexit 0\n", StandardCharsets.UTF_8);
            script.toFile().setExecutable(true, false);
            Files.writeString(bin.resolve(name + ".exe"), "stub", StandardCharsets.UTF_8);
        }
        BuildToolchainManager manager = new BuildToolchainManager(
                root.resolve("toolchains/build"), bin.toString());

        BuildToolchainManager.Toolchain toolchain = BrandInstaller.ensureSourceBuildToolchain(
                "Octop", line -> {
                }, manager);

        assertTrue(toolchain.usable(), toolchain.missingMessage());
        String cc = OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS
                ? bin.resolve("cc.exe").toString() : bin.resolve("cc").toRealPath().toString();
        assertEquals(cc, toolchain.environment().get("CC"));
        assertEquals(OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS
                        ? bin.resolve("make.exe").toString() : bin.resolve("make").toRealPath().toString(),
                toolchain.environment().get("MAKE"));
    }

    /// The failure is one the page can act on, not "install failed".
    @Test
    void aMissingSourceBuildToolchainIsAnActionableError() throws Exception {
        Path notADirectory = root.resolve("not-a-directory");
        Files.writeString(notADirectory, "x", StandardCharsets.UTF_8);
        BuildToolchainManager manager = new BuildToolchainManager(notADirectory, "");

        BrandException error = assertThrows(BrandException.class,
                () -> BrandInstaller.ensureSourceBuildToolchain("Octop", line -> {
                }, manager));

        assertTrue(error.getMessage().startsWith("build_toolchain_missing:"), error.getMessage());
        assertTrue(error.getMessage().contains("gcc/g++/make"), error.getMessage());
    }

    /// A minimal zip holding the given path/content pairs.
    private static byte[] zipOf(String... pathsAndContents) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(bytes)) {
            for (int i = 0; i < pathsAndContents.length; i += 2) {
                zip.putNextEntry(new java.util.zip.ZipEntry(pathsAndContents[i]));
                zip.write(pathsAndContents[i + 1].getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    @Test
    void defaultPackageIsTheCurrentLinkElseTheNewest() throws IOException {
        assertNull(BrandInstaller.defaultPackage(Brand.KIMI, root));

        Path older = install(Brand.KIMI, "2.0.2");
        Path newest = install(Brand.KIMI, "2.1.1");
        // No link yet: the newest release is the default.
        assertEquals(newest.toRealPath(), BrandInstaller.defaultPackage(Brand.KIMI, root).toRealPath());

        // The pointer wins when it exists, even pointing at an older release.
        org.jackhuang.hmcl.util.io.Links.pointAt(root.resolve("current"), older);
        assertEquals(older.toRealPath(), BrandInstaller.defaultPackage(Brand.KIMI, root).toRealPath());
    }

    /// Creates one fake installed release: a directory holding only the marker
    /// the installer checks for — the executable (npm brands) or the built web
    /// app (git brands).
    private Path install(Brand brand, String version) throws IOException {
        Path release = Files.createDirectories(root.resolve("releases").resolve(version));
        Path marker = release.resolve(brand.installMarker());
        Files.createDirectories(marker.getParent());
        Files.createFile(marker);
        return release;
    }
}
