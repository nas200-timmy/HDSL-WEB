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

import kala.compress.archivers.tar.TarArchiveEntry;
import kala.compress.archivers.tar.TarArchiveOutputStream;
import kala.compress.archivers.tar.TarConstants;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The shared CPython cache without a network.
///
/// Every archive is a fake carrying the same shape python-build-standalone
/// publishes (`python/bin/python3` as a link to the real file). What is checked
/// is the part that has no other way in: digest verification, the `python/`
/// member being dropped, links being recreated rather than copied, the `current`
/// pointer, reuse without a second fetch, and the promise that a failed install
/// leaves no directory behind.
class PythonRuntimeManagerTest {

    @TempDir
    Path temp;

    private static final String PYTHON_SCRIPT = "#!/bin/sh\nexit 0\n";

    @Test
    void installsATarGzAndMakesItCurrent() throws Exception {
        requirePosixInterpreter();
        Path archive = tarGz();
        String sha = BootstrapRuntimeManager.sha256(archive);
        Path root = temp.resolve("toolchains/python");
        PythonRuntimeManager manager = new PythonRuntimeManager(root);

        PythonRuntimeManager.Installed installed = manager.install("test-3.12",
                artifact(archive, sha), copyOf(archive, new AtomicInteger()), BootstrapRuntimeManager.Progress.NONE);

        assertEquals("test-3.12", installed.version());
        Path version = root.resolve("test-3.12");
        assertEquals(version.resolve("bin/python3"), installed.interpreter());
        assertTrue(Files.isRegularFile(installed.interpreter()));
        assertTrue(Files.isSymbolicLink(version.resolve("bin/python3")),
                "the distribution's symlink is what sys.executable resolves through");
        assertTrue(Files.isRegularFile(version.resolve("bin/python3.12")));
        assertEquals("python/" + "3.12", Files.readString(version.resolve("lib/marker")),
                "the archive's python/ member must be dropped");
        assertTrue(Files.isRegularFile(version.resolve(PythonRuntimeManager.RECORD)));
        assertTrue(Files.isRegularFile(root.resolve(PythonRuntimeManager.POINTER)));
        assertEquals("test-3.12", manager.current().version());
        assertEquals(version.resolve("bin/python3"), manager.current().interpreter());
    }

    @Test
    void anInstalledVersionIsReusedWithoutAFetch() throws Exception {
        requirePosixInterpreter();
        Path archive = tarGz();
        String sha = BootstrapRuntimeManager.sha256(archive);
        Path root = temp.resolve("toolchains/python");
        PythonRuntimeManager manager = new PythonRuntimeManager(root);
        AtomicInteger fetches = new AtomicInteger();

        manager.install("3.12.16", artifact(archive, sha), copyOf(archive, fetches),
                BootstrapRuntimeManager.Progress.NONE);
        PythonRuntimeManager.Installed again = manager.install("3.12.16",
                artifact(archive, sha), copyOf(archive, fetches), BootstrapRuntimeManager.Progress.NONE);

        assertEquals("3.12.16", again.version());
        assertEquals(1, fetches.get(), "the second ensure must not download again");
    }

    @Test
    void aLockStyleVersionWithABuildTagIsAUsableDirectoryName() throws Exception {
        requirePosixInterpreter();
        Path archive = tarGz();
        String sha = BootstrapRuntimeManager.sha256(archive);
        Path root = temp.resolve("toolchains/python");
        PythonRuntimeManager manager = new PythonRuntimeManager(root);
        String version = "3.14.9+20261021";

        PythonRuntimeManager.Installed installed = manager.install(version,
                new PythonRuntimeManager.Artifact("https://example.invalid/cpython-3.14.9+20261021-x.tar.gz",
                        sha, Files.size(archive), "tar.gz"),
                copyOf(archive, new AtomicInteger()), BootstrapRuntimeManager.Progress.NONE);

        assertEquals(version, installed.directory().getFileName().toString());
        assertEquals("20261021", installed.record().build);
        assertEquals(version, manager.current().version());
    }

    @Test
    void aChecksumMismatchLeavesNeitherAVersionNorStaging() throws Exception {
        Path archive = tarGz();
        Path root = temp.resolve("toolchains/python");
        PythonRuntimeManager manager = new PythonRuntimeManager(root);

        assertThrows(IOException.class, () -> manager.install("broken",
                artifact(archive, "b".repeat(64)), copyOf(archive, new AtomicInteger()),
                BootstrapRuntimeManager.Progress.NONE));

        assertFalse(Files.exists(root.resolve("broken")));
        assertFalse(Files.exists(root.resolve(PythonRuntimeManager.POINTER)));
        assertNoStaging(root);
    }

    @Test
    void anArchiveWithoutAnInterpreterLeavesNothing() throws Exception {
        Path archive = tarGz(Map.of("python/lib/marker", "x".getBytes(StandardCharsets.UTF_8)), Map.of());
        String sha = BootstrapRuntimeManager.sha256(archive);
        Path root = temp.resolve("toolchains/python");
        PythonRuntimeManager manager = new PythonRuntimeManager(root);

        assertThrows(IOException.class, () -> manager.install("no-interpreter",
                artifact(archive, sha), copyOf(archive, new AtomicInteger()),
                BootstrapRuntimeManager.Progress.NONE));

        assertFalse(Files.exists(root.resolve("no-interpreter")));
        assertFalse(Files.exists(root.resolve(PythonRuntimeManager.POINTER)));
        assertNoStaging(root);
    }

    @Test
    void aZipArchiveInstallsWithItsBinariesExecutable() throws Exception {
        requirePosixInterpreter();
        Path archive = zip();
        String sha = BootstrapRuntimeManager.sha256(archive);
        Path root = temp.resolve("toolchains/python");
        PythonRuntimeManager manager = new PythonRuntimeManager(root);

        PythonRuntimeManager.Installed installed = manager.install("windows-layout",
                new PythonRuntimeManager.Artifact("https://example.invalid/fake.zip", sha, Files.size(archive), "zip"),
                copyOf(archive, new AtomicInteger()), BootstrapRuntimeManager.Progress.NONE);

        assertTrue(Files.isRegularFile(installed.interpreter()));
        assertTrue(Files.isExecutable(installed.interpreter()),
                "a zip carries no mode bits; bin/ is made executable explicitly");
        assertEquals("zip", installed.record().archive);
    }

    @Test
    void anArtifactMustBePinnedHttps() {
        assertThrows(IllegalArgumentException.class, () -> new PythonRuntimeManager.Artifact(
                "http://example.invalid/py.tar.gz", "a".repeat(64), 0, "tar.gz"));
        assertThrows(IllegalArgumentException.class, () -> new PythonRuntimeManager.Artifact(
                "https://example.invalid/latest/py.tar.gz", "a".repeat(64), 0, "tar.gz"));
        assertThrows(IllegalArgumentException.class, () -> new PythonRuntimeManager.Artifact(
                "https://example.invalid/py.tar.gz", "abc", 0, "tar.gz"));
    }

    @Test
    void relocatingAMirrorKeepsTheDigest() {
        PythonRuntimeManager.Artifact canonical = new PythonRuntimeManager.Artifact(
                PythonRuntimeManager.RELEASE_BASE + "/" + PythonRuntimeManager.BUILD_TAG
                        + "/cpython-3.12.16+x.tar.gz",
                "c".repeat(64), 123, "tar.gz");

        PythonRuntimeManager.Artifact mirrored = PythonRuntimeManager.relocated(canonical,
                "https://mirror.example.com/python-build-standalone/");

        assertTrue(mirrored.url().startsWith("https://mirror.example.com/python-build-standalone/"
                        + PythonRuntimeManager.BUILD_TAG + "/cpython-3.12.16+x.tar.gz"),
                mirrored.url());
        assertEquals(canonical.sha256(), mirrored.sha256());
        assertEquals(canonical.size(), mirrored.size());
    }

    @Test
    void applyToPrependsPathAndNamesTheInterpreter() throws Exception {
        Path root = temp.resolve("toolchains/python");
        Path bin = root.resolve("3.12.16/bin");
        Files.createDirectories(bin);
        Path interpreter = bin.resolve("python3");
        Files.writeString(interpreter, PYTHON_SCRIPT, StandardCharsets.UTF_8);
        interpreter.toFile().setExecutable(true, false);

        Map<String, String> environment = new java.util.HashMap<>();
        environment.put("PATH", "/usr/bin:/bin");

        PythonRuntimeManager.applyTo(environment, root);

        assertEquals(bin + java.io.File.pathSeparator + "/usr/bin:/bin", environment.get("PATH"));
        assertEquals(interpreter.toString(), environment.get("PYTHON"));
        assertEquals(interpreter.toString(), environment.get("npm_config_python"));
    }

    @Test
    void anOperatorsPythonIsNotReplaced() throws Exception {
        Path root = temp.resolve("toolchains/python");
        Path bin = root.resolve("3.12.16/bin");
        Files.createDirectories(bin);
        Path interpreter = bin.resolve("python3");
        Files.writeString(interpreter, PYTHON_SCRIPT, StandardCharsets.UTF_8);

        Map<String, String> environment = new java.util.HashMap<>();
        environment.put("PYTHON", "/opt/python/bin/python3");
        environment.put("npm_config_python", "/opt/python/bin/python3");

        PythonRuntimeManager.applyTo(environment, root);

        assertEquals("/opt/python/bin/python3", environment.get("PYTHON"));
        assertEquals("/opt/python/bin/python3", environment.get("npm_config_python"));
    }

    /// The fake interpreter is a `#!/bin/sh` script, which only the POSIX
    /// validation can execute. The Windows archive has the same layout
    /// (`python.exe` at the root); its install is exercised on Windows itself.
    private static void requirePosixInterpreter() {
        Assumptions.assumeFalse(OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS,
                "the fake interpreter is a shell script");
    }

    private static PythonRuntimeManager.Artifact artifact(Path archive, String sha) throws IOException {
        return new PythonRuntimeManager.Artifact("https://example.invalid/fake.tar.gz", sha,
                Files.size(archive), "tar.gz");
    }

    private static BootstrapRuntimeManager.ArtifactSource copyOf(Path archive, AtomicInteger fetches) {
        return (url, target) -> {
            fetches.incrementAndGet();
            Files.copy(archive, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        };
    }

    private Path tarGz() throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("python/bin/python3.12", PYTHON_SCRIPT.getBytes(StandardCharsets.UTF_8));
        files.put("python/lib/marker", "python/3.12".getBytes(StandardCharsets.UTF_8));
        return tarGz(files, Map.of("python/bin/python3", "python3.12"));
    }

    private Path tarGz(Map<String, byte[]> files, Map<String, String> links) throws IOException {
        Path archive = temp.resolve("fake-" + System.nanoTime() + ".tar.gz");
        try (OutputStream out = Files.newOutputStream(archive);
             GZIPOutputStream gzip = new GZIPOutputStream(out);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                TarArchiveEntry tarEntry = new TarArchiveEntry(entry.getKey());
                tarEntry.setSize(entry.getValue().length);
                tarEntry.setMode(entry.getKey().endsWith("python3.12") ? 0755 : 0644);
                tar.putArchiveEntry(tarEntry);
                tar.write(entry.getValue());
                tar.closeArchiveEntry();
            }
            for (Map.Entry<String, String> link : links.entrySet()) {
                TarArchiveEntry tarEntry = new TarArchiveEntry(link.getKey(), TarConstants.LF_SYMLINK);
                tarEntry.setLinkName(link.getValue());
                tarEntry.setMode(0777);
                tar.putArchiveEntry(tarEntry);
                tar.closeArchiveEntry();
            }
        }
        return archive;
    }

    private Path zip() throws IOException {
        Path archive = temp.resolve("fake-" + System.nanoTime() + ".zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("python/bin/python3"));
            zip.write(PYTHON_SCRIPT.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("python/lib/marker"));
            zip.write("python/3.12".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return archive;
    }

    private static void assertNoStaging(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> entries = Files.list(root)) {
            List<Path> leftovers = entries.filter(path -> path.getFileName().toString().contains(".installing-"))
                    .toList();
            assertTrue(leftovers.isEmpty(), "staging directories were left behind: " + leftovers);
        }
    }
}