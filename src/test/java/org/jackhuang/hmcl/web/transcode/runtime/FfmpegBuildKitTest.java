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
package org.jackhuang.hmcl.web.transcode.runtime;

import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FfmpegBuildKitTest {

    @TempDir
    Path temp;

    @Test
    void configureArgsAreDeterministicAndRespectMissingTooling() {
        Path prefix = temp.resolve("prefix");
        List<String> args = FfmpegBuildKit.configureArgs(prefix,
                Set.of("libx264", "vaapi", "qsv", "nvenc"), false, false);

        assertTrue(args.contains("--prefix=" + prefix), args.toString());
        assertTrue(args.contains("--disable-doc"));
        assertTrue(args.contains("--disable-debug"));
        assertTrue(args.contains("--disable-shared"));
        assertTrue(args.contains("--enable-static"));
        assertTrue(args.contains("--disable-autodetect"));
        assertTrue(args.contains("--disable-x86asm"));
        assertTrue(args.contains("--enable-gpl"));
        assertTrue(args.contains("--enable-libx264"));
        assertTrue(args.contains("--enable-vaapi"));
        assertTrue(args.contains("--enable-libvpl"));
        assertTrue(args.contains("--enable-nvenc"));
        assertTrue(args.contains("--enable-nvdec"));
        assertFalse(args.contains("--enable-libx265"));
        assertFalse(args.contains("--enable-libvmaf"));
        assertFalse(args.contains("--extra-ldflags=-static"));
    }

    @Test
    void configureArgsOnlyEnableGplWhenAGplEncoderIsPresent() {
        List<String> lgpl = FfmpegBuildKit.configureArgs(temp.resolve("p"),
                Set.of("libvpx", "libaom"), true, false);
        assertFalse(lgpl.contains("--enable-gpl"));
        assertTrue(lgpl.contains("--enable-libvpx"));
        assertTrue(lgpl.contains("--enable-libaom"));
        assertFalse(lgpl.contains("--disable-x86asm"));

        List<String> windows = FfmpegBuildKit.configureArgs(temp.resolve("p"),
                Set.of("libx265"), true, true);
        assertTrue(windows.contains("--enable-gpl"));
        assertTrue(windows.contains("--extra-ldflags=-static"));
        // w64devkit's busybox-w32 `uname -s` reports Windows_NT, which ffmpeg
        // rejects; the Windows build must name its target OS.
        assertTrue(windows.contains("--target-os=mingw32"));
    }

    @Test
    void missingToolchainMessageNamesTheActionablePackages() {
        FfmpegToolchainManager.Toolchain toolchain = new FfmpegToolchainManager.Toolchain(
                "linux", "x64", null, false, false,
                List.of(new FfmpegToolchainManager.Tool("cc", null, ""),
                        new FfmpegToolchainManager.Tool("make", null, "")),
                List.of(new FfmpegToolchainManager.Dependency("libx264", "x264", "libx264-dev",
                        false, "")),
                List.of("cc", "make", "libx264-dev"), Map.of(),
                "Install build-essential pkg-config nasm.");

        String message = toolchain.missingMessage();
        assertTrue(message.startsWith("ffmpeg_build_toolchain_missing:"), message);
        assertTrue(message.contains("cc"), message);
        assertTrue(message.contains("make"), message);
        assertTrue(message.contains("libx264-dev"), message);
        assertTrue(message.contains("build-essential"), message);
    }

    @Test
    void fakeSourceTreeBuildsWritesRuntimeJsonAndActivates() throws Exception {
        Assumptions.assumeFalse(OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS,
                "the fake configure/make/ffmpeg scripts are POSIX shell scripts");
        Path tools = temp.resolve("tools/ffmpeg");
        FfmpegRuntimeManager manager = new FfmpegRuntimeManager(tools, FfmpegCatalog.Catalog.empty());
        FfmpegBuildKit kit = newKit(tools, manager);
        Path archive = zipSource("ok");
        FfmpegCatalog.Entry entry = entry(archive);
        FfmpegToolchainManager.Toolchain toolchain = fakeToolchain("ok");
        Path versionDir = tools.resolve("source-7.1.5-gpl-static-v1");

        FfmpegBuildKit.BuildResult result = kit.buildFromArchive(archive, entry,
                versionDir, toolchain, "jobok", Set.of("libx264", "vaapi"),
                (message, fraction) -> {
                }, () -> false);

        assertEquals("source-7.1.5-gpl-static-v1", result.installed().id());
        assertTrue(Files.isRegularFile(versionDir.resolve("bin").resolve("ffmpeg")));
        assertTrue(Files.isExecutable(versionDir.resolve("bin").resolve("ffmpeg")));
        assertTrue(Files.isRegularFile(versionDir.resolve("runtime.json")));
        assertEquals("source-7.1.5-gpl-static-v1", manager.current().id());

        FfmpegRuntimeManager.RuntimeRecord record = manager.current().runtime();
        assertEquals("ffmpeg.org", record.source);
        assertEquals("7.1", record.major);
        assertEquals("7.1.5", record.version);
        assertEquals(entry.sha256(), record.sha256);
        assertEquals("gpl-static-v1", record.buildRecipe);
        assertEquals("GPL-2.0-or-later", record.license);
        assertTrue(record.featuresDeclared.contains("libx264"));
        assertFalse(record.featuresDeclared.contains("libx265"));
        assertTrue(record.featuresProbed.contains("libx264"));
        assertTrue(record.featuresProbed.contains("vaapi"));
        assertTrue(record.notes.contains("GPL enabled"), record.notes);

        assertNotNull(result.logFile());
        assertTrue(Files.isRegularFile(result.logFile()));
        String log = Files.readString(result.logFile());
        assertTrue(log.contains("fake configure"), log);
        assertTrue(log.contains("fake make"), log);
    }

    @Test
    void failedBuildLeavesNoVersionDirectoryAndKeepsItsLog() throws Exception {
        Assumptions.assumeFalse(OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS,
                "the fake configure/make/ffmpeg scripts are POSIX shell scripts");
        Path tools = temp.resolve("tools-fail/ffmpeg");
        FfmpegRuntimeManager manager = new FfmpegRuntimeManager(tools, FfmpegCatalog.Catalog.empty());
        FfmpegBuildKit kit = newKit(tools, manager);
        Path archive = zipSource("fail");
        FfmpegCatalog.Entry entry = entry(archive);
        Path versionDir = tools.resolve("source-7.1.5-fail");

        IOException error = assertThrows(IOException.class, () -> kit.buildFromArchive(
                archive, entry, versionDir, fakeToolchain("fail"), "jobfail", Set.of(),
                (message, fraction) -> {
                }, () -> false));

        assertTrue(error.getMessage().contains("ffmpeg_build_failed"), error.getMessage());
        assertTrue(error.getMessage().contains("make"), error.getMessage());
        assertFalse(Files.exists(versionDir));
        assertTrue(manager.installed().isEmpty());
        Path log = kit.logFor("jobfail");
        assertTrue(Files.isRegularFile(log));
        assertTrue(Files.readString(log).contains("simulated failure"), Files.readString(log));
    }

    @Test
    void cancellationKillsTheBuildAndCleansUp() throws Exception {
        Assumptions.assumeFalse(OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS,
                "the fake configure/make/ffmpeg scripts are POSIX shell scripts");
        Path tools = temp.resolve("tools-cancel/ffmpeg");
        FfmpegRuntimeManager manager = new FfmpegRuntimeManager(tools, FfmpegCatalog.Catalog.empty());
        FfmpegBuildKit kit = newKit(tools, manager);
        Path archive = zipSource("sleep");
        FfmpegCatalog.Entry entry = entry(archive);
        Path versionDir = tools.resolve("source-7.1.5-cancel");
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Thread canceller = new Thread(() -> {
            try {
                Thread.sleep(800);
            } catch (InterruptedException ignored) {
                return;
            }
            cancelled.set(true);
        });
        canceller.start();

        assertThrows(CancellationException.class, () -> kit.buildFromArchive(
                archive, entry, versionDir, fakeToolchain("sleep"), "jobcancel", Set.of(),
                (message, fraction) -> {
                }, cancelled::get));
        canceller.join(2_000);

        assertFalse(Files.exists(versionDir));
        assertTrue(manager.installed().isEmpty());
        Path log = kit.logFor("jobcancel");
        assertTrue(Files.isRegularFile(log));
        assertTrue(Files.readString(log).contains("cancelled"), Files.readString(log));
    }

    private FfmpegBuildKit newKit(Path tools, FfmpegRuntimeManager manager) {
        return new FfmpegBuildKit(tools, manager,
                new FfmpegToolchainManager(temp.resolve("toolchains")),
                temp.resolve("logs"), 0,
                Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofSeconds(30), 1);
    }

    private FfmpegToolchainManager.Toolchain fakeToolchain(String mode) throws IOException {
        Path bin = temp.resolve("fake-toolchain-" + mode + "/bin");
        Files.createDirectories(bin);
        Path make = bin.resolve("make");
        Files.writeString(make, """
                #!/bin/sh
                echo "fake make $*"
                mode="${FAKE_MAKE_MODE:-ok}"
                if [ "$mode" = "fail" ]; then
                  echo "simulated failure" >&2
                  exit 7
                fi
                if [ "$mode" = "sleep" ]; then
                  sleep 30
                  exit 0
                fi
                for arg in "$@"; do
                  if [ "$arg" = "install" ]; then
                    prefix="$(cat fake-prefix)"
                    mkdir -p "$prefix/bin"
                    cp ./fake-ffmpeg "$prefix/bin/ffmpeg"
                    cp ./fake-ffprobe "$prefix/bin/ffprobe"
                    exit 0
                  fi
                done
                exit 0
                """, StandardCharsets.UTF_8);
        makeExecutable(make);
        Path nasm = bin.resolve("nasm");
        Files.writeString(nasm, "#!/bin/sh\nexit 0\n", StandardCharsets.UTF_8);
        makeExecutable(nasm);
        return new FfmpegToolchainManager.Toolchain(
                "linux", "x64", bin, true, false,
                List.of(new FfmpegToolchainManager.Tool("cc", make.toString(), "fake"),
                        new FfmpegToolchainManager.Tool("c++", make.toString(), "fake"),
                        new FfmpegToolchainManager.Tool("make", make.toString(), "fake"),
                        new FfmpegToolchainManager.Tool("nasm", nasm.toString(), "fake")),
                List.of(new FfmpegToolchainManager.Dependency("libx264", "x264", "libx264-dev",
                                true, "fake"),
                        new FfmpegToolchainManager.Dependency("libx265", "x265", "libx265-dev",
                                false, ""),
                        new FfmpegToolchainManager.Dependency("vaapi", "libva", "libva-dev",
                                true, "fake")),
                List.of(), Map.of("MAKE", make.toString(), "FAKE_MAKE_MODE", mode),
                "fake toolchain");
    }

    private FfmpegCatalog.Entry entry(Path archive) throws Exception {
        String sha = sha256(archive);
        return new FfmpegCatalog.Entry("ffmpeg-source-test", "any", "any", "any",
                "7.1.5", "LGPL-2.1-or-later",
                List.of("libx264", "libx265", "vaapi"),
                "https://ffmpeg.org/releases/ffmpeg-7.1.5.tar.xz",
                sha, Files.size(archive), "zip", "test",
                "source", "ffmpeg.org", "7.1", "source", "", List.of(), "",
                "gpl-static-v1");
    }

    private Path zipSource(String mode) throws Exception {
        Path archive = temp.resolve("source-ffmpeg-source-test-" + mode + ".zip");
        Path scripts = temp.resolve("scripts-" + mode);
        Files.createDirectories(scripts);
        Path configure = scripts.resolve("configure");
        Path ffmpeg = scripts.resolve("fake-ffmpeg");
        Path ffprobe = scripts.resolve("fake-ffprobe");
        Files.writeString(configure, """
                #!/bin/sh
                prefix=""
                for arg in "$@"; do
                  case "$arg" in
                    --prefix=*) prefix="${arg#--prefix=}";;
                  esac
                done
                echo "$prefix" > fake-prefix
                echo "fake configure $*"
                exit 0
                """, StandardCharsets.UTF_8);
        Files.writeString(ffmpeg, """
                #!/bin/sh
                case "$*" in
                  *"-encoders"*) echo " V....D libx264              H.264"; echo " V....D libx265              H.265";;
                  *"-hwaccels"*) echo "vaapi";;
                  *"-filters"*) echo " TSC scale              Scale";;
                  *) echo "ffmpeg version 7.1.5-test";;
                esac
                """, StandardCharsets.UTF_8);
        Files.writeString(ffprobe, "#!/bin/sh\necho \"ffprobe version 7.1.5-test\"\n",
                StandardCharsets.UTF_8);

        try (OutputStream out = Files.newOutputStream(archive);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            put(zip, "ffmpeg-7.1.5/configure", configure);
            put(zip, "ffmpeg-7.1.5/fake-ffmpeg", ffmpeg);
            put(zip, "ffmpeg-7.1.5/fake-ffprobe", ffprobe);
        }
        return archive;
    }

    private static void put(ZipOutputStream zip, String name, Path file) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        Files.copy(file, zip);
        zip.closeEntry();
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void makeExecutable(Path file) throws IOException {
        try {
            var permissions = Files.getPosixFilePermissions(file);
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(file, permissions);
        } catch (UnsupportedOperationException ignored) {
        }
    }
}