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

import kala.compress.archivers.ar.ArArchiveEntry;
import kala.compress.archivers.ar.ArArchiveOutputStream;
import kala.compress.archivers.tar.TarArchiveEntry;
import kala.compress.archivers.tar.TarArchiveOutputStream;
import kala.compress.archivers.tar.TarConstants;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.XZOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// The build toolchain without a network or a real compiler.
///
/// Every archive is a fake with the shape the publisher uses: a single top-level
/// directory for Zig, and `usr/bin/...` members inside a `.deb` for make,
/// pkgconf and nasm. What is checked is the part that has no other way in:
/// system tools winning over the bundle, the wrapper scripts exec'ing Zig,
/// `current` moving after a complete install, and a failed install leaving
/// neither a version nor a staging directory.
class BuildToolchainManagerTest {

    @TempDir
    Path temp;

    private final Map<String, Path> fixtures = new LinkedHashMap<>();

    @Test
    void systemToolsArePreferredAndNoBundleIsDownloaded() throws Exception {
        Assumptions.assumeFalse(OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS,
                "the POSIX naming rules are what this test exercises");
        Path bin = temp.resolve("system-bin");
        Files.createDirectories(bin);
        for (String name : List.of("cc", "c++", "make", "pkg-config", "nasm")) {
            executable(bin.resolve(name), "#!/bin/sh\necho fake-" + name + "-1.0\n");
        }
        Path root = temp.resolve("build");
        BuildToolchainManager manager = new BuildToolchainManager(root, bin.toString());

        BuildToolchainManager.Toolchain toolchain = manager.detect();

        assertTrue(toolchain.usable(), toolchain.missingMessage());
        assertFalse(toolchain.portable(), "the host already has a compiler");
        assertEquals(bin.resolve("cc").toRealPath().toString(), toolchain.environment().get("CC"));
        assertEquals(bin.resolve("make").toRealPath().toString(), toolchain.environment().get("MAKE"));
        assertFalse(Files.exists(root.resolve("current.json")),
                "the system path must not create a portable bundle");
    }

    @Test
    void aPortableLinuxCombinationInstallsAtomicallyAndBecomesCurrent() throws Exception {
        Assumptions.assumeFalse(OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS,
                "the wrappers are POSIX shell scripts and the current pointer is a symlink");
        Path root = temp.resolve("build");
        List<BuildToolchainManager.Artifact> artifacts = fakeLinuxArtifacts();
        BuildToolchainManager manager = new BuildToolchainManager(root, "",
                copySource(new AtomicInteger()));

        manager.installLinuxArtifacts(artifacts, BootstrapRuntimeManager.Progress.NONE, () -> false);

        BuildToolchainManager.Toolchain toolchain = manager.installed();
        assertNotNull(toolchain);
        assertTrue(toolchain.usable(), toolchain.missingMessage());
        assertTrue(toolchain.portable());

        Path version = root.resolve(BuildToolchainManager.linuxVersion());
        assertTrue(Files.isDirectory(version));
        assertTrue(Files.isRegularFile(version.resolve("bin/make")));
        assertTrue(Files.isRegularFile(version.resolve("bin/nasm")));
        assertTrue(Files.isRegularFile(version.resolve("bin/pkg-config")));
        assertTrue(Files.isRegularFile(version.resolve("lib/libpkgconf.so.3.0.0")));
        assertTrue(Files.isSymbolicLink(version.resolve("lib/libpkgconf.so.3")),
                "the Debian .so link is recreated rather than copied");

        String cc = Files.readString(version.resolve("bin/cc"), StandardCharsets.UTF_8);
        assertTrue(cc.contains("exec \"$here/../zig/zig\" cc \"$@\""), cc);
        String cxx = Files.readString(version.resolve("bin/c++"), StandardCharsets.UTF_8);
        assertTrue(cxx.contains("exec \"$here/../zig/zig\" c++ \"$@\""), cxx);

        assertEquals(version.resolve("bin/cc").toString(), toolchain.environment().get("CC"));
        assertEquals(version.resolve("bin/c++").toString(), toolchain.environment().get("CXX"));
        assertEquals(version.resolve("bin/make").toString(), toolchain.environment().get("MAKE"));
        assertEquals(version.resolve("bin/ar").toString(), toolchain.environment().get("AR"));
        assertTrue(toolchain.environment().get("PATH").startsWith(version.resolve("bin").toString()));
        assertTrue(toolchain.describe().contains("portable zig"), toolchain.describe());
        assertTrue(Files.isRegularFile(root.resolve("current.json")));
        assertTrue(Files.isSymbolicLink(root.resolve("current")));
        assertNoStaging(root);
    }

    @Test
    void aSecondEnsureReusesTheInstalledVersionWithoutFetching() throws Exception {
        Assumptions.assumeFalse(OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS);
        Path root = temp.resolve("build");
        AtomicInteger fetches = new AtomicInteger();
        List<BuildToolchainManager.Artifact> artifacts = fakeLinuxArtifacts();
        BuildToolchainManager manager = new BuildToolchainManager(root, "",
                copySource(fetches));

        manager.installLinuxArtifacts(artifacts, BootstrapRuntimeManager.Progress.NONE, () -> false);
        int afterFirst = fetches.get();
        manager.installLinuxArtifacts(artifacts, BootstrapRuntimeManager.Progress.NONE, () -> false);

        assertEquals(afterFirst, fetches.get(), "an installed version must not be downloaded again");
    }

    @Test
    void aChecksumMismatchLeavesNeitherAVersionNorStaging() throws Exception {
        Assumptions.assumeFalse(OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS);
        Path root = temp.resolve("build");
        Path zig = fakeZigArchive(temp.resolve("zig.tar.xz"));
        fixtures.put("https://example.invalid/zig.tar.xz", zig);
        BuildToolchainManager.Artifact broken = new BuildToolchainManager.Artifact(
                "zig", "https://example.invalid/zig.tar.xz", "b".repeat(64), 0, "tar.xz",
                List.of());
        BuildToolchainManager manager = new BuildToolchainManager(root, "",
                copySource(new AtomicInteger()));

        IOException error = assertThrows(IOException.class, () -> manager.installLinuxArtifacts(
                List.of(broken), BootstrapRuntimeManager.Progress.NONE, () -> false));

        assertTrue(error.getMessage().contains("build_toolchain_checksum_mismatch"),
                error.getMessage());
        assertFalse(Files.exists(root.resolve(BuildToolchainManager.linuxVersion())));
        assertFalse(Files.exists(root.resolve("current.json")));
        assertNoStaging(root);
    }

    @Test
    void theMissingMessageNamesTheProgramsAndTheAction() {
        BuildToolchainManager.Toolchain toolchain = new BuildToolchainManager.Toolchain(
                "linux", "arm64", null, null, false, false, List.of(),
                List.of("cc", "c++", "make"), Map.of(),
                "No portable bundle is pinned for linux/arm64; install build-essential.");

        String message = toolchain.missingMessage();

        assertTrue(message.startsWith("build_toolchain_missing:"), message);
        assertTrue(message.contains("cc, c++, make"), message);
        assertTrue(message.contains("build-essential"), message);
        assertTrue(message.contains("linux/arm64"), message);
    }

    @Test
    void resolveForBuildReportsAnActionableErrorWhenNothingCanBeInstalled() throws Exception {
        Assumptions.assumeFalse(OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS);
        Path root = temp.resolve("build");
        Path notADirectory = temp.resolve("not-a-directory");
        Files.writeString(notADirectory, "x");
        BuildToolchainManager manager = new BuildToolchainManager(notADirectory, "");

        IOException error = assertThrows(IOException.class, manager::ensureForBuild);

        assertTrue(error.getMessage().startsWith("build_toolchain_missing:"), error.getMessage());
        assertTrue(error.getMessage().contains("cc, c++, make")
                        || error.getMessage().contains("not-a-directory"),
                error.getMessage());
    }

    @Test
    void aWindowsBundleResolvesEveryProgramWithoutRunningIt() throws Exception {
        Path bin = temp.resolve("w64devkit/bin");
        Files.createDirectories(bin);
        for (String name : List.of("gcc.exe", "g++.exe", "mingw32-make.exe", "ar.exe",
                "ld.exe", "sh.exe", "objdump.exe", "pkg-config.exe", "nasm.exe")) {
            Files.writeString(bin.resolve(name), "stub");
        }
        BuildToolchainManager manager = new BuildToolchainManager(temp.resolve("build"), "");

        BuildToolchainManager.Toolchain toolchain = manager.windowsToolchain(bin);

        assertTrue(toolchain.usable(), toolchain.missingMessage());
        assertTrue(toolchain.portable());
        assertEquals(bin.resolve("gcc.exe").toString(), toolchain.environment().get("CC"));
        assertEquals(bin.resolve("g++.exe").toString(), toolchain.environment().get("CXX"));
        assertEquals(bin.resolve("mingw32-make.exe").toString(), toolchain.environment().get("MAKE"));
        assertEquals(bin.resolve("ar.exe").toString(), toolchain.environment().get("AR"));
        assertEquals(bin.resolve("ld.exe").toString(), toolchain.environment().get("LD"));
        assertEquals(bin.resolve("pkg-config.exe").toString(), toolchain.environment().get("PKG_CONFIG"));
        assertEquals(bin.resolve("sh.exe").toString(), toolchain.pathOf("sh"));
        assertTrue(toolchain.environment().get("PATH").startsWith(bin.toString()));
    }

    @Test
    void applyToLeavesAnOperatorsCompilerAndPathEntryAlone() {
        Path bin = temp.resolve("toolchain/bin");
        BuildToolchainManager.Toolchain toolchain = new BuildToolchainManager.Toolchain(
                "linux", "x64", bin.getParent(), bin, true, true,
                List.of(new BuildToolchainManager.Tool("cc", bin.resolve("cc").toString(), "zig")),
                List.of(), Map.of(
                        "CC", bin.resolve("cc").toString(),
                        "CXX", bin.resolve("c++").toString(),
                        "MAKE", bin.resolve("make").toString(),
                        "LD_LIBRARY_PATH", bin.getParent().resolve("lib").toString()),
                "fake");

        Map<String, String> environment = new HashMap<>();
        environment.put("CC", "/opt/operator/cc");
        environment.put("PATH", "/usr/bin:/bin");
        environment.put("LD_LIBRARY_PATH", "/opt/lib");

        toolchain.applyTo(environment);

        assertEquals("/opt/operator/cc", environment.get("CC"),
                "an operator's compiler is not replaced");
        assertEquals(bin + java.io.File.pathSeparator + "/usr/bin:/bin", environment.get("PATH"));
        assertEquals(bin.getParent().resolve("lib") + java.io.File.pathSeparator + "/opt/lib",
                environment.get("LD_LIBRARY_PATH"));
        assertNotNull(environment.get("CXX"));
    }

    // --------------------------------------------------------------- fixtures --

    /// The fake artifacts the Linux installer expects, with the real pinned
    /// URLs (the source only ever copies the local fake bytes).
    private List<BuildToolchainManager.Artifact> fakeLinuxArtifacts() throws IOException {
        fixtures.clear();
        Path zig = fakeZigArchive(temp.resolve("zig.tar.xz"));
        Path make = fakeDeb(temp.resolve("make.deb"), Map.of(
                "usr/bin/make", "#!/bin/sh\necho fake-make\n".getBytes(StandardCharsets.UTF_8)), Map.of());
        Path pkgconf = fakeDeb(temp.resolve("pkgconf.deb"), Map.of(
                "usr/bin/pkgconf", "#!/bin/sh\necho fake-pkgconf\n".getBytes(StandardCharsets.UTF_8)), Map.of());
        Path libpkgconf = fakeDeb(temp.resolve("libpkgconf.deb"), Map.of(
                "usr/lib/x86_64-linux-gnu/libpkgconf.so.3.0.0",
                "fake-library".getBytes(StandardCharsets.UTF_8)),
                Map.of("usr/lib/x86_64-linux-gnu/libpkgconf.so.3",
                        "libpkgconf.so.3.0.0"));
        Path nasm = fakeDeb(temp.resolve("nasm.deb"), Map.of(
                "usr/bin/nasm", "#!/bin/sh\necho fake-nasm\n".getBytes(StandardCharsets.UTF_8)), Map.of());
        return List.of(
                withFixture(artifact("zig", zig, "tar.xz", List.of()), zig),
                withFixture(artifact("make", make, "deb",
                        List.of(new BootstrapCatalog.Include("usr/bin/make", "bin"))), make),
                withFixture(artifact("pkgconf", pkgconf, "deb",
                        List.of(new BootstrapCatalog.Include("usr/bin/pkgconf", "bin"))), pkgconf),
                withFixture(artifact("libpkgconf3", libpkgconf, "deb",
                        List.of(new BootstrapCatalog.Include(
                                "usr/lib/x86_64-linux-gnu/libpkgconf.so.3", "lib"))), libpkgconf),
                withFixture(artifact("nasm", nasm, "deb",
                        List.of(new BootstrapCatalog.Include("usr/bin/nasm", "bin"))), nasm));
    }

    private BuildToolchainManager.Artifact withFixture(BuildToolchainManager.Artifact artifact,
                                                       Path file) {
        fixtures.put(artifact.url(), file);
        return artifact;
    }

    private static BuildToolchainManager.Artifact artifact(
            String name, Path archive, String kind, List<BootstrapCatalog.Include> includes)
            throws IOException {
        return new BuildToolchainManager.Artifact(name,
                "https://example.invalid/" + name + "." + kind,
                BootstrapRuntimeManager.sha256(archive), Files.size(archive), kind, includes);
    }

    private BootstrapRuntimeManager.ArtifactSource copySource(AtomicInteger fetches) {
        return (url, target) -> {
            Path source = fixtures.get(url);
            if (source == null) {
                throw new IOException("no fixture for " + url);
            }
            fetches.incrementAndGet();
            Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        };
    }

    /// A `.tar.xz` with the single top-level directory Zig publishes.
    private static Path fakeZigArchive(Path archive) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("zig-x86_64-linux-test/zig",
                "#!/bin/sh\nexit 0\n".getBytes(StandardCharsets.UTF_8));
        files.put("zig-x86_64-linux-test/lib/marker",
                "lib".getBytes(StandardCharsets.UTF_8));
        try (OutputStream out = Files.newOutputStream(archive);
             XZOutputStream xz = new XZOutputStream(out, new LZMA2Options());
             TarArchiveOutputStream tar = new TarArchiveOutputStream(xz)) {
            for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                TarArchiveEntry tarEntry = new TarArchiveEntry(entry.getKey());
                tarEntry.setSize(entry.getValue().length);
                tarEntry.setMode(entry.getKey().endsWith("/zig") ? 0755 : 0644);
                tar.putArchiveEntry(tarEntry);
                tar.write(entry.getValue());
                tar.closeArchiveEntry();
            }
        }
        return archive;
    }

    /// A minimal `.deb`: an ar archive with `data.tar.xz`, which is all
    /// [BootstrapArchive] reads.
    private static Path fakeDeb(Path archive, Map<String, byte[]> files,
                                Map<String, String> links) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        try (XZOutputStream xz = new XZOutputStream(data, new LZMA2Options());
             TarArchiveOutputStream tar = new TarArchiveOutputStream(xz)) {
            for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                TarArchiveEntry tarEntry = new TarArchiveEntry("./" + entry.getKey());
                tarEntry.setSize(entry.getValue().length);
                tarEntry.setMode(entry.getKey().startsWith("usr/bin/") ? 0755 : 0644);
                tar.putArchiveEntry(tarEntry);
                tar.write(entry.getValue());
                tar.closeArchiveEntry();
            }
            for (Map.Entry<String, String> link : links.entrySet()) {
                TarArchiveEntry tarEntry = new TarArchiveEntry("./" + link.getKey(),
                        TarConstants.LF_SYMLINK);
                tarEntry.setLinkName(link.getValue());
                tarEntry.setMode(0777);
                tar.putArchiveEntry(tarEntry);
                tar.closeArchiveEntry();
            }
        }
        try (ArArchiveOutputStream ar = new ArArchiveOutputStream(Files.newOutputStream(archive))) {
            writeAr(ar, "debian-binary", "2.0\n".getBytes(StandardCharsets.UTF_8));
            writeAr(ar, "data.tar.xz", data.toByteArray());
        }
        return archive;
    }

    private static void writeAr(ArArchiveOutputStream ar, String name, byte[] body)
            throws IOException {
        ArArchiveEntry entry = new ArArchiveEntry(name, body.length);
        ar.putArchiveEntry(entry);
        ar.write(body);
        ar.closeArchiveEntry();
    }

    private static void executable(Path file, String body) throws IOException {
        Files.writeString(file, body, StandardCharsets.UTF_8);
        assertTrue(file.toFile().setExecutable(true, false));
    }

    private static void assertNoStaging(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return;
        }
        List<String> names = new ArrayList<>();
        try (var entries = Files.list(root)) {
            entries.forEach(path -> names.add(path.getFileName().toString()));
        }
        assertTrue(names.stream().noneMatch(name -> name.startsWith(".")),
                "no staging directory may survive: " + names);
    }
    /// Unpacking and matching a digest is not the same as running.
    ///
    /// The pinned Linux `make`/`pkgconf` are Debian 11 executables needing
    /// GLIBC 2.27 and Zig needs 2.14; on an older host (or musl) they unpack
    /// cleanly, verify, and then die in the loader. That used to be an install
    /// reported as finished, with the broken toolchain injected into every
    /// build. The three programs a build cannot start without are now run once
    /// before the bundle is accepted.
    @Test
    void aBundleThatUnpacksButCannotStartIsRefused(@TempDir java.nio.file.Path dir) throws Exception {
        assumeTrue(OperatingSystem.CURRENT_OS != OperatingSystem.WINDOWS,
                "the fixture is a shell script");
        java.nio.file.Path good = dir.resolve("cc");
        java.nio.file.Files.writeString(good, "#!/bin/sh\necho 'cc 1.0'\n");
        assertTrue(good.toFile().setExecutable(true), "the fixture has to be executable");
        java.nio.file.Path broken = dir.resolve("make");
        java.nio.file.Files.writeString(broken, "#!/bin/sh\n"
                + "echo 'make: /lib64/libc.so.6: version `GLIBC_2.27` not found' >&2\n"
                + "exit 127\n");
        assertTrue(broken.toFile().setExecutable(true), "the fixture has to be executable");

        assertNull(BuildToolchainManager.smoke(toolchain(good, good, good)),
                "a bundle where every program answers is accepted");

        String failure = BuildToolchainManager.smoke(toolchain(good, good, broken));
        assertNotNull(failure, "a program that cannot start must be reported");
        assertTrue(failure.contains("make"), failure);
        assertTrue(failure.contains("127"), failure);
    }

    /// The two large downloads (Zig and w64devkit) must carry alternates: the
    /// upstream is one slow host, and the speed picker only has something to
    /// choose between when the catalogue names alternatives.
    @Test
    void theEmbeddedToolchainPinsMirrorsForTheLargeArchives() {
        BootstrapCatalog.Toolchain toolchain = BootstrapCatalog.current().toolchain();
        assertNotNull(toolchain);
        for (String arch : List.of("x64", "arm64")) {
            BootstrapCatalog.ToolchainArtifact zig = toolchain.linux().artifacts(arch).stream()
                    .filter(artifact -> artifact.name().equals("zig"))
                    .findFirst().orElseThrow();
            assertFalse(zig.mirrors().isEmpty(), "no Zig mirror for " + arch);
            for (String mirror : zig.mirrors()) {
                assertTrue(mirror.startsWith("https://"), mirror);
            }
            String fileArch = arch.equals("arm64") ? "aarch64" : "x86_64";
            assertEquals("https://ziglang.org/download/0.14.1/"
                    + "zig-" + fileArch + "-linux-0.14.1.tar.xz", zig.url());
        }
        assertNotNull(toolchain.windows());
        assertFalse(toolchain.windows().mirrors().isEmpty());
    }

    @Test
    void aNonHttpsMirrorIsRejectedWithTheCatalogue() {
        assertThrows(IllegalArgumentException.class, () ->
                new BootstrapCatalog.ToolchainArtifact("zig", "tar.xz",
                        "https://official.example/zig.tar.xz", "0".repeat(64), 1,
                        List.of(), List.of("http://mirror.example/zig.tar.xz")));
    }

    private static BuildToolchainManager.Toolchain toolchain(java.nio.file.Path cc,
                                                             java.nio.file.Path cxx,
                                                             java.nio.file.Path make) {
        return new BuildToolchainManager.Toolchain("linux", "x64", null, null, true, true,
                List.of(new BuildToolchainManager.Tool("cc", cc.toString(), "1.0"),
                        new BuildToolchainManager.Tool("c++", cxx.toString(), "1.0"),
                        new BuildToolchainManager.Tool("make", make.toString(), "4.3")),
                List.of(), Map.of(), "");
    }
}
