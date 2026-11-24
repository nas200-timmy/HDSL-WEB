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

import kala.compress.archivers.ar.ArArchiveEntry;
import kala.compress.archivers.ar.ArArchiveOutputStream;
import kala.compress.archivers.tar.TarArchiveEntry;
import kala.compress.archivers.tar.TarArchiveOutputStream;
import kala.compress.archivers.tar.TarConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Installing the bootstrap bundles without a network.
///
/// Every archive is a fake: an AR container with a `data.tar.gz` carrying the
/// five members the closure needs. What is being checked is the part that has
/// no other way in — verification, selective extraction, the `current` pointer,
/// and the promise that a failed install leaves no directory behind. Downloading
/// real Debian packages here would make the test slow and its result depend on
/// `snapshot.debian.org`.
class BootstrapRuntimeManagerTest {

    @TempDir
    Path temp;

    @Test
    void installsTheClosureAndMakesItCurrent() throws Exception {
        Path deb = fakeDeb();
        String sha = BootstrapRuntimeManager.sha256(deb);
        BootstrapCatalog.Runtime runtime = runtime("test-closure", sha, Files.size(deb));
        Path data = temp.resolve("toolchains");
        BootstrapRuntimeManager manager = new BootstrapRuntimeManager(data,
                BootstrapCatalog.Catalog.parse(catalog(runtime), "test"));

        BootstrapRuntimeManager.Installed installed = manager.installRuntime(runtime,
                copyOf(deb), BootstrapRuntimeManager.Progress.NONE);

        assertEquals("test-closure", installed.version());
        Path version = data.resolve("runtime").resolve("test-closure");
        assertTrue(Files.isRegularFile(version.resolve("lib").resolve("libstdc++.so.6")));
        assertTrue(Files.isSymbolicLink(version.resolve("lib").resolve("libstdc++.so.6")),
                "the distribution's symlink is what the dynamic loader looks up");
        assertTrue(Files.isRegularFile(version.resolve("lib").resolve("libstdc++.so.6.0.28")));
        assertTrue(Files.isRegularFile(version.resolve("lib").resolve("libgcc_s.so.1")));
        assertTrue(Files.isRegularFile(version.resolve("lib").resolve("libatomic.so.1")));
        assertTrue(Files.isRegularFile(version.resolve("lib").resolve("libcap-ng.so.0")));
        assertTrue(Files.isExecutable(version.resolve("bin").resolve("setpriv")));
        assertTrue(Files.isRegularFile(version.resolve("runtime.json")));
        assertTrue(Files.isRegularFile(data.resolve("runtime").resolve("current.json")));
        assertEquals("test-closure", manager.current().version());
        assertTrue(Files.isSymbolicLink(data.resolve("runtime").resolve("current")));
    }

    @Test
    void aChecksumMismatchLeavesNoVersionAndNoStaging() throws Exception {
        Path deb = fakeDeb();
        BootstrapCatalog.Runtime runtime = runtime("broken", "b".repeat(64), 0);
        Path data = temp.resolve("toolchains");
        BootstrapRuntimeManager manager = new BootstrapRuntimeManager(data,
                BootstrapCatalog.Catalog.parse(catalog(runtime), "test"));

        assertThrows(IOException.class, () -> manager.installRuntime(runtime, copyOf(deb),
                BootstrapRuntimeManager.Progress.NONE));

        assertFalse(Files.exists(data.resolve("runtime").resolve("broken")));
        assertFalse(Files.exists(data.resolve("runtime").resolve("current.json")));
        assertNoStaging(data.resolve("runtime"));
    }

    @Test
    void anArchiveMissingARequiredMemberLeavesNothing() throws Exception {
        Path deb = fakeDebWithoutSetpriv();
        BootstrapCatalog.Runtime runtime = runtime("incomplete",
                BootstrapRuntimeManager.sha256(deb), Files.size(deb));
        Path data = temp.resolve("toolchains");
        BootstrapRuntimeManager manager = new BootstrapRuntimeManager(data,
                BootstrapCatalog.Catalog.parse(catalog(runtime), "test"));

        assertThrows(IOException.class, () -> manager.installRuntime(runtime, copyOf(deb),
                BootstrapRuntimeManager.Progress.NONE));

        assertFalse(Files.exists(data.resolve("runtime").resolve("incomplete")));
        assertFalse(Files.exists(data.resolve("runtime").resolve("current.json")));
        assertNoStaging(data.resolve("runtime"));
    }

    @Test
    void aLinkThatLeavesTheBundleIsRefused() throws Exception {
        Path deb = fakeDeb(true, Map.of(
                "lib/x86_64-linux-gnu/libstdc++.so.6", "../../../../etc/passwd"));
        BootstrapCatalog.Runtime runtime = runtime("escaping",
                BootstrapRuntimeManager.sha256(deb), Files.size(deb));
        Path data = temp.resolve("toolchains");
        BootstrapRuntimeManager manager = new BootstrapRuntimeManager(data,
                BootstrapCatalog.Catalog.parse(catalog(runtime), "test"));

        assertThrows(IOException.class, () -> manager.installRuntime(runtime, copyOf(deb),
                BootstrapRuntimeManager.Progress.NONE));
        assertFalse(Files.exists(data.resolve("runtime").resolve("escaping")));
    }

    @Test
    void installsTheCaBundleAndReadsItBack() throws Exception {
        byte[] pem = ("-----BEGIN CERTIFICATE-----\nfake\n"
                + "-----END CERTIFICATE-----\n").getBytes(StandardCharsets.US_ASCII);
        Path source = temp.resolve("cacert.pem");
        Files.write(source, pem);
        BootstrapCatalog.Ca ca = new BootstrapCatalog.Ca("2026-11-05",
                "https://example.test/cacert.pem", BootstrapRuntimeManager.sha256(source),
                pem.length, "cacert.pem");
        Path data = temp.resolve("toolchains");
        BootstrapRuntimeManager manager = new BootstrapRuntimeManager(data,
                BootstrapCatalog.Catalog.parse(catalog(ca), "test"));

        BootstrapRuntimeManager.CaInstalled installed = manager.installCa(ca, copyOf(source),
                BootstrapRuntimeManager.Progress.NONE);

        assertEquals("2026-11-05", installed.version());
        assertEquals(pem.length, Files.size(installed.file()));
        assertTrue(Files.isRegularFile(data.resolve("ca").resolve("current.json")));
        assertEquals(installed.file(), manager.currentCa().file());
        assertTrue(Files.isSymbolicLink(data.resolve("ca").resolve("current")));

        // The second call is a read, not another download.
        BootstrapRuntimeManager.CaInstalled again = manager.ensureCa();
        assertEquals(installed.file(), again.file());
    }

    @Test
    void aCaChecksumMismatchLeavesNothing() throws Exception {
        Path source = temp.resolve("cacert.pem");
        Files.writeString(source, "not the pinned bytes");
        BootstrapCatalog.Ca ca = new BootstrapCatalog.Ca("2026-11-05",
                "https://example.test/cacert.pem", "c".repeat(64), 0, "cacert.pem");
        Path data = temp.resolve("toolchains");
        BootstrapRuntimeManager manager = new BootstrapRuntimeManager(data,
                BootstrapCatalog.Catalog.parse(catalog(ca), "test"));

        assertThrows(IOException.class,
                () -> manager.installCa(ca, copyOf(source), BootstrapRuntimeManager.Progress.NONE));
        assertFalse(Files.exists(data.resolve("ca").resolve("2026-11-05")));
        assertFalse(Files.exists(data.resolve("ca").resolve("current.json")));
        assertNoStaging(data.resolve("ca"));
    }

    @Test
    void theBuiltinCatalogIsPinnedAndHonestAboutArm64() {
        BootstrapCatalog.Catalog catalog = BootstrapCatalog.Catalog.builtin();
        assertEquals(1, catalog.schema());

        BootstrapCatalog.Ca ca = catalog.ca();
        assertNotNull(ca);
        assertEquals("427c064216761a1e72a8b30f0d954d08cb6e4435a82091fb865afd20c8497285",
                ca.sha256());
        assertEquals(200116, ca.size());
        assertTrue(ca.url().startsWith("https://curl.se/ca/cacert-2026-11-05.pem"));

        BootstrapCatalog.Runtime x64 = catalog.runtime("linux", "x64");
        assertNotNull(x64);
        assertTrue(x64.available());
        assertEquals(5, x64.artifacts().size());
        for (BootstrapCatalog.Artifact artifact : x64.artifacts()) {
            assertTrue(artifact.url().startsWith("https://"), artifact.url());
            assertTrue(artifact.sha256().matches("[0-9a-f]{64}"), artifact.name());
            assertTrue(artifact.size() > 0, artifact.name());
        }

        BootstrapCatalog.Runtime arm64 = catalog.runtime("linux", "arm64");
        assertNotNull(arm64);
        assertFalse(arm64.available(), "no arm64 package was verified, so it must not look available");
        assertTrue(arm64.artifacts().isEmpty());
    }

    @Test
    void theCatalogueRefusesAnUnpinnedOrPlainHttpArtifact() {
        String good = """
                {"schema":1,"runtimes":[{"version":"v","os":"linux","arch":"x64","artifacts":[
                  {"name":"a","archive":"deb","url":"%s","sha256":"%s","size":1,
                   "includes":[{"prefix":"usr/bin/setpriv","destination":"bin"}]}]}]}
                """;
        String sha = "a".repeat(64);
        assertTrue(BootstrapCatalog.Catalog.parse(good.formatted("https://example.test/a.deb", sha),
                "test").runtime("linux", "x64").available());

        assertThrows(IllegalArgumentException.class, () -> BootstrapCatalog.Catalog.parse(
                good.formatted("http://example.test/a.deb", sha), "test"));
        assertThrows(IllegalArgumentException.class, () -> BootstrapCatalog.Catalog.parse(
                good.formatted("https://example.test/latest/a.deb", sha), "test"));
        assertThrows(IllegalArgumentException.class, () -> BootstrapCatalog.Catalog.parse(
                good.formatted("https://example.test/a.deb", ""), "test"));
    }

    @Test
    void thePasswdAndGroupPatchOnlyAppendsWhatIsMissing(@TempDir Path directory) throws Exception {
        Path passwd = directory.resolve("passwd");
        Path group = directory.resolve("group");
        Files.writeString(passwd, "root:x:0:0:root:/root:/bin/sh\n");
        Files.writeString(group, "root:x:0:\nrender:x:109:\n");

        BootstrapRuntimeManager.IdentityResult first = BootstrapRuntimeManager.patchIdentityFiles(
                passwd, group, 1000, "hdsl");
        assertTrue(first.applicable());
        assertTrue(first.changed());
        String passwdAfter = Files.readString(passwd);
        assertTrue(passwdAfter.startsWith("root:x:0:0:root:/root:/bin/sh\n"),
                "existing lines are preserved");
        assertTrue(passwdAfter.contains("hdsl:x:1000:1000:HDSL agent:/home/hdsl:/bin/bash"));
        String groupAfter = Files.readString(group);
        assertTrue(groupAfter.startsWith("root:x:0:\nrender:x:109:\n"));
        assertTrue(groupAfter.contains("hdsl:x:1000:"));
        assertTrue(Files.list(directory).anyMatch(path ->
                path.getFileName().toString().startsWith("passwd.hdsl-bak-")));

        BootstrapRuntimeManager.IdentityResult second = BootstrapRuntimeManager.patchIdentityFiles(
                passwd, group, 1000, "hdsl");
        assertTrue(second.applicable());
        assertFalse(second.changed(), "a second run must not rewrite or duplicate");
        assertEquals(passwdAfter, Files.readString(passwd));
        assertEquals(groupAfter, Files.readString(group));
    }

    @Test
    void thePatchAcceptsAnExistingDifferentNameWithTheSameUid() {
        assertEquals(null, BootstrapRuntimeManager.patchPasswd(
                "someone:x:1000:1000::/home/someone:/bin/sh\n", 1000, "hdsl"));
        assertEquals(null, BootstrapRuntimeManager.patchGroup("other:x:1000:\n", 1000, "hdsl"));
        assertTrue(BootstrapRuntimeManager.patchPasswd("root:x:0:0::/root:/bin/sh\n", 1000, "hdsl")
                .contains("hdsl:x:1000:"));
        assertTrue(BootstrapRuntimeManager.patchGroup("", 1000, "hdsl").contains("hdsl:x:1000:"));
    }

    @Test
    void missingLibrariesAreAnswerableWithoutTheRealFilesystem(@TempDir Path directory) {
        List<Path> search = List.of(directory);
        assertEquals(List.of("libstdc++.so.6", "libgcc_s.so.1", "libatomic.so.1"),
                BootstrapRuntimeManager.missingNodeLibraries(search, path -> false));

        assertTrue(write(directory.resolve("libstdc++.so.6")));
        assertEquals(List.of("libgcc_s.so.1", "libatomic.so.1"),
                BootstrapRuntimeManager.missingNodeLibraries(search, Files::exists));

        assertTrue(write(directory.resolve("libgcc_s.so.1")));
        assertTrue(write(directory.resolve("libatomic.so.1")));
        assertTrue(BootstrapRuntimeManager.missingNodeLibraries(search, Files::exists).isEmpty());
    }

    private static boolean write(Path file) {
        try {
            Files.writeString(file, "fake");
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void assertNoStaging(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (var entries = Files.list(root)) {
            assertFalse(entries.anyMatch(path -> path.getFileName().toString().startsWith(".")),
                    "a failed install must not leave a staging directory");
        }
    }

    private static BootstrapRuntimeManager.ArtifactSource copyOf(Path source) {
        return (url, target) -> Files.copy(source, target);
    }

    private static BootstrapCatalog.Runtime runtime(String version, String sha, long size) {
        List<BootstrapCatalog.Include> includes = List.of(
                new BootstrapCatalog.Include("lib/x86_64-linux-gnu/libstdc++.so.6", "lib"),
                new BootstrapCatalog.Include("lib/x86_64-linux-gnu/libgcc_s.so.1", "lib"),
                new BootstrapCatalog.Include("lib/x86_64-linux-gnu/libatomic.so.1", "lib"),
                new BootstrapCatalog.Include("lib/x86_64-linux-gnu/libcap-ng.so.0", "lib"),
                new BootstrapCatalog.Include("usr/bin/setpriv", "bin"));
        return new BootstrapCatalog.Runtime(version, "linux", "x64", "2.18", true, "",
                List.of(new BootstrapCatalog.Artifact("fake", "deb",
                        "https://example.test/fake.deb", sha, size, includes)));
    }

    private static String catalog(BootstrapCatalog.Runtime runtime) {
        return "{\"schema\":1,\"runtimes\":[]}";
    }

    private static String catalog(BootstrapCatalog.Ca ca) {
        return "{\"schema\":1,\"ca\":{\"version\":\"" + ca.version() + "\",\"url\":\""
                + ca.url() + "\",\"sha256\":\"" + ca.sha256() + "\",\"size\":" + ca.size()
                + ",\"fileName\":\"cacert.pem\"},\"runtimes\":[]}";
    }

    /// An AR + data.tar.gz carrying exactly the five members the closure needs.
    private Path fakeDeb() throws IOException {
        return fakeDeb(true);
    }

    private Path fakeDebWithoutSetpriv() throws IOException {
        return fakeDeb(false);
    }

    private Path fakeDeb(boolean withSetpriv) throws IOException {
        return fakeDeb(withSetpriv, Map.of(
                "lib/x86_64-linux-gnu/libstdc++.so.6", "libstdc++.so.6.0.28"));
    }

    private Path fakeDeb(boolean withSetpriv, Map<String, String> links) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("lib/x86_64-linux-gnu/libstdc++.so.6.0.28", new byte[]{1});
        files.put("lib/x86_64-linux-gnu/libgcc_s.so.1", new byte[]{2});
        files.put("lib/x86_64-linux-gnu/libatomic.so.1", new byte[]{3});
        files.put("lib/x86_64-linux-gnu/libcap-ng.so.0", new byte[]{4});
        if (withSetpriv) {
            files.put("usr/bin/setpriv", "#!/bin/sh\nexit 0\n".getBytes(StandardCharsets.US_ASCII));
        }
        Path dataTar = temp.resolve("data.tar.gz");
        try (OutputStream out = Files.newOutputStream(dataTar);
             GZIPOutputStream gzip = new GZIPOutputStream(out);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                TarArchiveEntry tarEntry = new TarArchiveEntry("./" + entry.getKey());
                tarEntry.setSize(entry.getValue().length);
                tarEntry.setMode(entry.getKey().endsWith("setpriv") ? 0755 : 0644);
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

        Path deb = temp.resolve("fake-" + withSetpriv + ".deb");
        try (ArArchiveOutputStream ar = new ArArchiveOutputStream(Files.newOutputStream(deb))) {
            ar.setLongFileMode(ArArchiveOutputStream.LONGFILE_BSD);
            byte[] binary = "2.0\n".getBytes(StandardCharsets.US_ASCII);
            ArArchiveEntry header = new ArArchiveEntry("debian-binary", binary.length);
            ar.putArchiveEntry(header);
            ar.write(binary);
            ar.closeArchiveEntry();
            byte[] data = Files.readAllBytes(dataTar);
            ArArchiveEntry archive = new ArArchiveEntry("data.tar.gz", data.length);
            ar.putArchiveEntry(archive);
            ar.write(data);
            ar.closeArchiveEntry();
            ar.finish();
        }
        return deb;
    }
}