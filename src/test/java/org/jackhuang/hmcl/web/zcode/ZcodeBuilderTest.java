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
package org.jackhuang.hmcl.web.zcode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The in-panel ZCode build: a local "source tarball" dressed in the layout the
/// patch expects, a stub `pnpm` that answers `install` and `build:zcode`, and
/// the real verify-and-install path in between — so the pipeline (download →
/// unpack → patch → install deps → build → sha256 check → `releases/<v>/` +
/// `current`) runs end to end without network or a real monorepo.
class ZcodeBuilderTest {

    @TempDir
    Path dir;

    @Test
    void buildsAndInstallsAReleaseFromALocalSourceTarball() throws Exception {
        copy(Path.of("src/test/resources/fake-zcode-source"), dir.resolve("srcroot"));
        Path tarball = dir.resolve("zcode-source.tar.gz");
        run(List.of("tar", "-czf", tarball.toString(), "-C", dir.toString(), "srcroot"));

        ZcodeBuilder.Settings settings = new ZcodeBuilder.Settings(
                dir.resolve("root"),
                "",
                fakePnpm(),
                tarball.toString(),
                false);

        ZcodeBuilder.start(settings, "main");
        ZcodeBuilder.Status status = awaitTerminal(120);
        assertEquals(ZcodeBuilder.State.DONE, status.state(),
                status.message() + " | log: " + String.join("\n", ZcodeBuilder.tailLog(40)));

        Path current = ZcodeBuilder.currentPackage(dir.resolve("root"));
        assertTrue(Files.isRegularFile(current.resolve("bin/zcode.mjs")), current.toString());
        assertTrue(Files.readString(current.resolve("package.json")).contains("9.9.9"), current.toString());

        List<ZcodeBuilder.Release> releases = ZcodeBuilder.releases(dir.resolve("root"));
        assertEquals(1, releases.size());
        assertEquals("9.9.9", releases.get(0).version());
        assertTrue(releases.get(0).current(), "the built release must be the current one");

        // The scratch tree is cleaned up unless keepSources asked otherwise.
        assertFalse(Files.exists(dir.resolve("root/build/src")));
        assertFalse(Files.exists(dir.resolve("root/build/source.tar.gz")));
    }

    @Test
    void keepsTheScratchTreeWhenAsked() throws Exception {
        copy(Path.of("src/test/resources/fake-zcode-source"), dir.resolve("srcroot"));
        Path tarball = dir.resolve("zcode-source.tar.gz");
        run(List.of("tar", "-czf", tarball.toString(), "-C", dir.toString(), "srcroot"));

        ZcodeBuilder.Settings settings = new ZcodeBuilder.Settings(
                dir.resolve("root"), "", fakePnpm(), tarball.toString(), true);

        ZcodeBuilder.start(settings, "main");
        assertEquals(ZcodeBuilder.State.DONE, awaitTerminal(120).state());
        // …and the patch is visible in the tree it kept.
        String vite = Files.readString(dir.resolve("root/build/src/packages/web/vite.config.ts"));
        assertTrue(vite.contains("base: \"./\","), vite);
        // …as is the platform filter, appended to the checkout's .npmrc so a
        // mirror the sources already configure survives.
        String npmrc = Files.readString(dir.resolve("root/build/src/.npmrc"));
        assertTrue(npmrc.contains("supportedArchitectures="), npmrc);
    }

    @Test
    void aMissingSourceTarballEndsInError() throws Exception {
        ZcodeBuilder.Settings settings = new ZcodeBuilder.Settings(
                dir.resolve("root"), "", fakePnpm(), dir.resolve("missing.tar.gz").toString(), false);

        ZcodeBuilder.start(settings, "v9.9.9");
        ZcodeBuilder.Status status = awaitTerminal(60);
        assertEquals(ZcodeBuilder.State.ERROR, status.state());
        assertNotNull(status.error());
    }

    @Test
    void rejectsARefNameThatIsNotARefName() throws Exception {
        ZcodeBuilder.Settings settings = new ZcodeBuilder.Settings(
                dir.resolve("root"), "", fakePnpm(), "%s", false);
        assertThrows(ZcodeException.class, () -> ZcodeBuilder.start(settings, "main; rm -rf /"));
    }

    /// The stub `pnpm`, copied somewhere writable with its executable bit set:
    /// a test must not depend on the checkout's own mode — this repository has
    /// `core.fileMode` off, so the bit is not even recorded in git.
    private String fakePnpm() throws IOException {
        Path target = dir.resolve("fake-pnpm/pnpm");
        Files.createDirectories(target.getParent());
        Files.copy(Path.of("src/test/resources/fake-zcode-build/pnpm"), target,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        target.toFile().setExecutable(true, false);
        return target.toAbsolutePath().toString();
    }

    private static ZcodeBuilder.Status awaitTerminal(long seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            ZcodeBuilder.Status status = ZcodeBuilder.status();
            if (status.state() == ZcodeBuilder.State.DONE || status.state() == ZcodeBuilder.State.ERROR) {
                return status;
            }
            Thread.sleep(50);
        }
        return ZcodeBuilder.status();
    }

    private static void run(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
        assertTrue(process.waitFor(1, TimeUnit.MINUTES) && process.exitValue() == 0,
                "command failed: " + String.join(" ", command));
    }

    private static void copy(Path from, Path to) throws IOException {
        try (var walk = Files.walk(from)) {
            for (Path path : walk.toList()) {
                Path target = to.resolve(from.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target);
                }
            }
        }
    }
}
