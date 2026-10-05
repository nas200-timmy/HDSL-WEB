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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Builds ZCode distributions inside the panel's data directory.
///
/// There is no official ZCode CLI distribution to download (the desktop apps
/// are the only release artifacts), so the category builds its own: this class
/// downloads the source tarball for a tag or branch, applies [ZcodePatch] so the
/// web client works behind the panel's `/i/<id>/` proxy, runs `pnpm install` and
/// `pnpm build:zcode`, verifies the `sha256` the build recorded, and installs
/// the result as `releases/<version>/` with `current` pointing at it.
///
/// It is a long, heavy job — a full monorepo install plus three builds, minutes
/// to tens of minutes and gigabytes of scratch space — so it runs on its own
/// thread with one build at a time, reports through [Status], and writes every
/// line of the child processes' output to `build/build.log` (the interface tails
/// it). The scratch tree is removed when the build ends, unless
/// `keepSources` says otherwise.
///
/// Everything is in-memory apart from the releases and the log: a panel restart
/// mid-build loses the build, which is accepted for an experimental category.
@NotNullByDefault
public final class ZcodeBuilder {

    /// What the panel can be told about the builder.
    public enum State {
        IDLE, RUNNING, DONE, ERROR
    }

    /// The builder's state, the version it is working on, its current step, how
    /// far along that is, and — for `error` — why it stopped.
    public record Status(State state, String version, String message, double fraction, @Nullable String error) {

        static final Status IDLE = new Status(State.IDLE, "", "", 0, null);
    }

    /// One installed release, as the interface lists it.
    public record Release(String version, String path, long builtAt, boolean current) {
    }

    /// Everything the builder needs that is not the data directory.
    ///
    /// @param root        `<dataDir>/zcode`
    /// @param buildBin    a directory prepended to `PATH` for the build — the
    ///                    Node 24 install (`/opt/node24/bin` in the image);
    ///                    empty keeps the panel's own `PATH`
    /// @param pnpm        the pnpm executable to run (tests point it at a stub)
    /// @param sourceUrl   a URL or path template for the source tarball; `%s`
    ///                    is the version (or branch) name
    /// @param keepSources whether to keep the scratch tree after the build
    public record Settings(Path root, String buildBin, String pnpm, String sourceUrl, boolean keepSources) {
    }

    private static final int LOG_TAIL_LINES = 400;

    private static final AtomicReference<Status> STATUS = new AtomicReference<>(Status.IDLE);
    private static final Object LOCK = new Object();
    private static volatile @Nullable Path logFile;

    private ZcodeBuilder() {
    }

    /// The build's state right now.
    public static Status status() {
        return STATUS.get();
    }

    /// Starts a build, unless one is already running.
    ///
    /// @param settings where to build and with what
    /// @param version  the upstream tag (`v3.14.3`) or branch (`main`) to build
    /// @return the state the build just entered
    /// @throws ZcodeException when the version is not a plausible ref name or a
    /// build is already running
    public static Status start(Settings settings, String version) throws ZcodeException {
        String ref = version == null ? "" : version.trim();
        if (ref.isEmpty() || ref.length() > 64 || !ref.matches("[A-Za-z0-9._/-]+")) {
            throw new ZcodeException("version must be an upstream tag or branch name (letters, digits, . _ - /)");
        }
        synchronized (LOCK) {
            if (STATUS.get().state() == State.RUNNING) {
                throw new ZcodeException("a ZCode build is already running");
            }
            STATUS.set(new Status(State.RUNNING, ref, "准备构建目录", 0.01, null));
            Thread thread = new Thread(() -> run(settings, ref), "zcode-build");
            thread.setDaemon(true);
            thread.start();
            return STATUS.get();
        }
    }

    /// The tail of the build log, for the interface.
    ///
    /// @param lines how many trailing lines to return
    /// @return the lines, empty when no build has run yet
    public static List<String> tailLog(int lines) {
        Path file = logFile;
        if (file == null || !Files.isRegularFile(file)) {
            return List.of();
        }
        try (var stream = Files.lines(file, StandardCharsets.UTF_8)) {
            List<String> all = stream.toList();
            return all.subList(Math.max(0, all.size() - Math.max(1, lines)), all.size());
        } catch (IOException e) {
            LOG.warning("Could not read the ZCode build log " + file, e);
            return List.of();
        }
    }

    /// The installed releases, newest first, each flagged with whether the
    /// `current` link resolves to it.
    public static List<Release> releases(Path root) {
        Path releases = root.resolve("releases");
        Path currentLink = root.resolve("current");
        List<Release> found = new ArrayList<>();
        try (var stream = Files.list(releases)) {
            for (Path dir : stream.filter(Files::isDirectory).toList()) {
                if (!Files.isRegularFile(dir.resolve("bin/zcode.mjs"))) {
                    continue;
                }
                found.add(new Release(dir.getFileName().toString(), dir.toString(),
                        dir.toFile().lastModified(), samePath(dir, currentLink)));
            }
        } catch (IOException e) {
            return List.of();
        }
        found.sort(Comparator.comparingLong(Release::builtAt).reversed());
        return List.copyOf(found);
    }

    /// Whether the `current` link points at `release`. Both sides are resolved:
    /// the link's own path is not the release's path, so a plain comparison
    /// would never match.
    private static boolean samePath(Path release, Path link) {
        try {
            return Files.exists(link) && release.toRealPath().equals(link.toRealPath());
        } catch (IOException e) {
            return false;
        }
    }

    /// The package directory to run: the `current` link when it resolves to a
    /// package, else the newest installed release, else `current` itself (which
    /// will not exist, so the interface shows "not installed").
    public static Path currentPackage(Path root) {
        Path current = root.resolve("current");
        if (Files.isRegularFile(current.resolve("bin/zcode.mjs"))) {
            return current;
        }
        List<Release> installed = releases(root);
        if (!installed.isEmpty()) {
            return Path.of(installed.get(0).path());
        }
        return current;
    }

    // ------------------------------------------------------------------ build --

    private static void run(Settings settings, String ref) {
        Path work = settings.root().resolve("build");
        Path sources = work.resolve("src");
        Path tarball = work.resolve("source.tar.gz");
        Path outDir = work.resolve("out");
        Path log = work.resolve("build.log");
        logFile = log;
        try {
            Files.createDirectories(work);
            Files.writeString(log, "", StandardCharsets.UTF_8);
            deleteRecursively(sources);
            deleteRecursively(outDir);

            Step step = new Step(log, ref);
            step.set(0.03, "清理旧目录");
            deleteRecursively(settings.root().resolve("current"));

            String url = String.format(settings.sourceUrl(), ref);
            step.set(0.06, "下载源码：" + url);
            download(url, tarball);

            step.set(0.15, "解包源码");
            Files.createDirectories(sources);
            run(step, settings, work, List.of("tar", "-xzf", tarball.toString(),
                    "-C", sources.toString(), "--strip-components=1"), Duration.ofMinutes(10));

            step.set(0.22, "应用反代补丁（" + ZcodePatch.size() + " 处）");
            ZcodePatch.apply(sources);

            // Two build-only knobs, env-only like the rest of this category:
            //
            // - the registry is the panel's own (`NPM_CONFIG_REGISTRY`, which the
            //   entrypoint already writes into pnpm's config.yaml) — passing it
            //   explicitly makes the build independent of that file, and is the
            //   one lever that matters on a network far from npmjs.org;
            // - the platform filter goes into the checkout's `.npmrc`: upstream's
            //   lockfile lists every platform's optional dependencies, so
            //   Windows/macOS/other-architecture tarballs are pure download cost
            //   on a NAS. `HDSL_ZCODE_ALL_PLATFORMS=1` turns it off.
            String registry = System.getenv("NPM_CONFIG_REGISTRY");
            List<String> install = new ArrayList<>(List.of(settings.pnpm(), "install"));
            if (registry != null && !registry.isBlank()) {
                registry = registry.trim();
                install.add("--registry=" + registry);
            }
            if (!"1".equals(System.getenv("HDSL_ZCODE_ALL_PLATFORMS"))) {
                String osName = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
                String platformOs = osName.contains("mac") ? "darwin" : osName.startsWith("win") ? "win32" : "linux";
                String platformCpu = System.getProperty("os.arch", "").toLowerCase(java.util.Locale.ROOT)
                        .matches("aarch64|arm64") ? "arm64" : "x64";
                String filter = "{\"os\":[\"" + platformOs + "\"],\"cpu\":[\"" + platformCpu + "\"]"
                        + ("linux".equals(platformOs) ? ",\"libc\":[\"glibc\"]" : "") + "}";
                Files.writeString(sources.resolve(".npmrc"),
                        System.lineSeparator() + "supportedArchitectures=" + filter + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                step.log("platform filter: " + filter);
            }
            step.set(0.28, "安装依赖：pnpm install（最慢的一步"
                    + (registry == null ? "" : "，registry=" + registry) + "）");
            run(step, settings, sources, install, Duration.ofMinutes(90));

            step.set(0.68, "构建发行包：pnpm build:zcode");
            run(step, settings, sources, List.of(settings.pnpm(), "build:zcode",
                    "--out-dir", outDir.toString(),
                    "--base-url", "https://example.invalid/zcode/"), Duration.ofMinutes(90));

            step.set(0.93, "安装发行包");
            String installed = install(outDir, settings.root().resolve("releases"));

            step.set(1.0, "完成：" + installed);
            STATUS.set(new Status(State.DONE, ref, "构建完成：" + installed, 1.0, null));
            LOG.info("ZCode " + ref + " built and installed as " + installed);
        } catch (ZcodeException e) {
            fail(ref, e.getMessage());
        } catch (Exception e) {
            fail(ref, e.toString());
            LOG.warning("The ZCode build of " + ref + " failed", e);
        } finally {
            if (!settings.keepSources()) {
                try {
                    deleteRecursively(sources);
                    Files.deleteIfExists(tarball);
                    deleteRecursively(outDir);
                } catch (IOException e) {
                    LOG.warning("Could not clean up the ZCode build tree", e);
                }
            }
        }
    }

    private static void fail(String ref, @Nullable String reason) {
        String message = reason == null ? "构建失败" : reason;
        STATUS.set(new Status(State.ERROR, ref, message, 0, message));
        LOG.warning("The ZCode build of " + ref + " failed: " + message);
    }

    /// Downloads the source tarball — over HTTP(S), or copied from a local path
    /// or `file:` URL, which is how tests and offline mirrors supply it.
    private static void download(String url, Path target) throws ZcodeException {
        try {
            if (url.startsWith("file:")) {
                Files.copy(Path.of(URI.create(url)), target, StandardCopyOption.REPLACE_EXISTING);
                return;
            }
            if (!url.contains("://")) {
                Files.copy(Path.of(url), target, StandardCopyOption.REPLACE_EXISTING);
                return;
            }
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(60))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(60))
                    .GET()
                    .build();
            HttpResponse<Path> response = client.send(request,
                    HttpResponse.BodyHandlers.ofFile(target));
            if (response.statusCode() / 100 != 2) {
                throw new ZcodeException("downloading " + url + " returned HTTP " + response.statusCode()
                        + " — check the tag or branch name");
            }
        } catch (IOException e) {
            throw new ZcodeException("could not download the ZCode source from " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ZcodeException("the source download was interrupted", e);
        }
    }

    /// Installs the built distribution: reads the `latest.json` the build wrote,
    /// verifies the recorded `sha256`, unpacks the tarball into
    /// `releases/<version>/` and points `current` at it.
    ///
    /// @return the installed version
    private static String install(Path outDir, Path releases) throws ZcodeException {
        Path latest = outDir.resolve("latest.json");
        String version;
        Path tarball;
        String expectedSha;
        try {
            JsonObject json = JsonParser.parseString(Files.readString(latest, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            version = json.get("version").getAsString();
            expectedSha = json.has("sha256") ? json.get("sha256").getAsString() : "";
            tarball = outDir.resolve("releases").resolve(version).resolve(json.get("tarball").getAsString());
        } catch (Exception e) {
            throw new ZcodeException("the build did not produce a readable latest.json in " + outDir, e);
        }
        if (!Files.isRegularFile(tarball)) {
            Path releasesDir = outDir.resolve("releases").resolve(version);
            try (var stream = Files.list(releasesDir)) {
                tarball = stream.filter(p -> p.getFileName().toString().endsWith(".tar.gz")).findFirst()
                        .orElseThrow(() -> new ZcodeException("no release tarball below " + releasesDir));
            } catch (IOException e) {
                throw new ZcodeException("no release tarball for " + version + " below " + releasesDir, e);
            }
        }
        if (!expectedSha.isEmpty() && !sha256(tarball).equalsIgnoreCase(expectedSha)) {
            throw new ZcodeException("the built tarball's sha256 does not match latest.json — refusing to install");
        }

        Path target = releases.resolve(version);
        try {
            deleteRecursively(target);
            Files.createDirectories(target);
            Process tar = new ProcessBuilder("tar", "-xzf", tarball.toString(), "-C", target.toString(),
                    "--strip-components=1")
                    .redirectErrorStream(true)
                    .start();
            tar.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            if (!tar.waitFor(5, java.util.concurrent.TimeUnit.MINUTES) || tar.exitValue() != 0) {
                throw new ZcodeException("unpacking the built tarball failed");
            }
            if (!Files.isRegularFile(target.resolve("bin/zcode.mjs"))) {
                throw new ZcodeException("the built tarball has no bin/zcode.mjs — unexpected layout");
            }
            Path current = releases.getParent().resolve("current");
            Files.deleteIfExists(current);
            Files.createSymbolicLink(current, target);
        } catch (IOException e) {
            throw new ZcodeException("could not install the built ZCode distribution", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ZcodeException("unpacking the built tarball was interrupted", e);
        }
        return version;
    }

    private static String sha256(Path file) throws ZcodeException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(file)) {
                byte[] buffer = new byte[1 << 16];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new ZcodeException("could not hash " + file, e);
        }
    }

    /// Runs a child process, copying its merged output into the build log.
    private static void run(Step step, Settings settings, Path workingDirectory, List<String> command,
                            Duration timeout) throws ZcodeException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDirectory.toFile());
        builder.redirectErrorStream(true);
        if (!settings.buildBin().isBlank()) {
            String path = builder.environment().getOrDefault("PATH", "");
            builder.environment().put("PATH", settings.buildBin() + ":" + path);
        }
        step.log("$ " + String.join(" ", command));
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new ZcodeException("could not run " + command.get(0) + " — is it installed?", e);
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                step.log(line);
            }
        } catch (IOException e) {
            throw new ZcodeException("could not read the output of " + command.get(0), e);
        }
        try {
            if (!process.waitFor(timeout.toSeconds(), java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new ZcodeException(command.get(0) + " did not finish within " + timeout.toMinutes() + " minutes");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new ZcodeException("the build was interrupted", e);
        }
        if (process.exitValue() != 0) {
            throw new ZcodeException(command.get(0) + " exited with " + process.exitValue() + " — see the build log");
        }
    }

    /// A step in the build: its own progress writer, appending every line to the
    /// shared log with a timestamp so the interface's tail reads sensibly.
    private static final class Step {

        private final java.io.BufferedWriter writer;
        private final String ref;

        Step(Path log, String ref) {
            this.ref = ref;
            try {
                this.writer = Files.newBufferedWriter(log, StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        void set(double fraction, String message) {
            STATUS.set(new Status(State.RUNNING, ref, message, fraction, null));
            log("=== " + message);
        }

        void log(String line) {
            try {
                synchronized (writer) {
                    writer.write(line);
                    writer.newLine();
                    writer.flush();
                }
            } catch (IOException ignored) {
                // The log is diagnostics; a write failure must not fail the build.
            }
        }
    }

    private static void deleteRecursively(@Nullable Path directory) throws IOException {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (var walk = Files.walk(directory)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
