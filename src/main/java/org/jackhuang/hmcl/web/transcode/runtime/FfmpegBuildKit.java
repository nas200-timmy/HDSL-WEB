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

import org.jackhuang.hmcl.util.ProcessTree;
import org.jackhuang.hmcl.util.logging.Logger;
import org.jackhuang.hmcl.util.platform.Executables;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jackhuang.hmcl.dsh.BootstrapEnvironment;
import org.jackhuang.hmcl.util.platform.Privileges;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/// Builds ffmpeg from the pinned source tarball and installs the result through
/// [FfmpegRuntimeManager], so a source build lands in the same ledger, can be
/// rolled back and can be deleted like a downloaded artifact.
///
/// The recipe is intentionally narrow. It is the panel's baseline, not a shell:
/// `configure` is generated from detected development packages, never accepted
/// from an operator, and every download is a fixed HTTPS URL with a pinned
/// SHA-256. A missing `--enable-libx264` is a limitation recorded in
/// `runtime.json`, not a silently enabled encoder.
///
/// Layout of one build:
///
/// ```text
/// <tools>/.build-<id>-<uuid>/
///   src/                extracted tarball
///   prefix/             make install target
///   tmp/                TMPDIR for configure/make
/// <tools>/.download-build-<id>.tar.xz
/// <logs>/build-<id>.log
/// ```
///
/// Nothing under `<tools>/<id>` exists until the binary passed `-version`,
/// `-encoders`, `-hwaccels` and a one-frame lavfi encode, so a failed or
/// cancelled job cannot be mistaken for a legacy runtime.
@NotNullByDefault
public final class FfmpegBuildKit {

    private static final Logger LOG = Logger.LOG;
    /// A deployment mirror prefix for source archives; empty for the official
    /// URL only. A supplier so a settings change applies without a restart.
    private volatile java.util.function.Supplier<String> sourceMirror = () -> "";

    public void setSourceMirror(java.util.function.Supplier<String> mirror) {
        this.sourceMirror = mirror == null ? () -> "" : mirror;
    }

    private static final long DEFAULT_MINIMUM_FREE_BYTES = 3L * 1024 * 1024 * 1024;
    public static final String SUPPORTED_RECIPE = "gpl-static-v1";

    /// Feature → configure switch. The order is the order the settings page and
    /// `runtime.json` list them, so a test can assert the argv deterministically.
    private static final Map<String, List<String>> FEATURE_FLAGS = featureFlags();

    public record BuildResult(
            FfmpegRuntimeManager.Installed installed,
            Path logFile,
            Set<String> featuresEnabled,
            Set<String> featuresMissing,
            String toolchain) {
    }

    private record RunResult(int exitCode, String output) {
    }

    private final Path toolsDir;
    private final FfmpegRuntimeManager runtimes;
    private final FfmpegToolchainManager toolchains;
    private final Path logsDir;
    private final long minimumFreeBytes;
    private final Duration configureTimeout;
    private final Duration makeTimeout;
    private final Duration installTimeout;
    private final int jobs;

    public FfmpegBuildKit(Path toolsDir, FfmpegRuntimeManager runtimes,
                          FfmpegToolchainManager toolchains, Path logsDir) {
        this(toolsDir, runtimes, toolchains, logsDir, DEFAULT_MINIMUM_FREE_BYTES,
                Duration.ofMinutes(15), Duration.ofMinutes(90), Duration.ofMinutes(15),
                Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(), 4)));
    }

    /// Test seam: a fake source tree needs no 3 GB free-space assertion and does
    /// not want to wait fifteen minutes for a script that is meant to fail.
    FfmpegBuildKit(Path toolsDir, FfmpegRuntimeManager runtimes,
                   FfmpegToolchainManager toolchains, Path logsDir,
                   long minimumFreeBytes, Duration configureTimeout,
                   Duration makeTimeout, Duration installTimeout, int jobs) {
        this.toolsDir = toolsDir.toAbsolutePath().normalize();
        this.runtimes = runtimes;
        this.toolchains = toolchains;
        this.logsDir = logsDir.toAbsolutePath().normalize();
        this.minimumFreeBytes = minimumFreeBytes;
        this.configureTimeout = configureTimeout;
        this.makeTimeout = makeTimeout;
        this.installTimeout = installTimeout;
        this.jobs = Math.max(1, jobs);
    }

    /// Where the build for `buildId` writes its log. The job exposes the same
    /// path before the first line exists, so the settings page can link to it.
    public Path logFor(String buildId) {
        return logsDir.resolve("build-" + safe(buildId) + ".log");
    }

    /// Downloads, builds and activates one `kind=source` entry.
    ///
    /// @param entry             the source catalogue entry
    /// @param versionDir        the final `<tools>/<id>` directory; must be a direct child
    /// @param buildId           short id used for the log and temporary names
    /// @param requestedFeatures optional subset; `null`/empty enables every detected feature
    public BuildResult build(FfmpegCatalog.Entry entry, Path versionDir, String buildId,
                             @Nullable Set<String> requestedFeatures,
                             FfmpegRuntimeManager.Progress progress,
                             BooleanSupplier cancelled) throws IOException {
        return build(entry, versionDir, buildId, requestedFeatures, progress, cancelled,
                FfmpegToolchainManager.Preference.AUTO);
    }

    /// The same, with an explicit compiler choice (`system` / `portable`).
    public BuildResult build(FfmpegCatalog.Entry entry, Path versionDir, String buildId,
                             @Nullable Set<String> requestedFeatures,
                             FfmpegRuntimeManager.Progress progress,
                             BooleanSupplier cancelled,
                             FfmpegToolchainManager.Preference preference) throws IOException {
        validateEntry(entry, versionDir);
        Files.createDirectories(toolsDir);
        checkDisk();

        FfmpegToolchainManager.Toolchain toolchain = toolchains.resolveForBuild(
                (message, fraction) -> progress.stage(message, fraction * 0.10), cancelled,
                preference);
        if (!toolchain.usable()) {
            throw new IOException(toolchain.missingMessage());
        }

        Path archive = toolsDir.resolve(".download-build-" + safe(buildId)
                + suffix(entry.archive()));
        try {
            java.util.List<String> candidates = FfmpegDownload.candidates(
                    sourceMirror.get(), entry.url(), entry.mirrors());
            String used = downloadFirst(candidates, archive, cancelled, progress);
            LOG.info("ffmpeg source: " + entry.id() + " downloaded from " + used);
            return buildFromArchive(archive, entry, versionDir, toolchain, buildId,
                    requestedFeatures, progress, cancelled);
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    /// Tries each candidate until one delivers; the archive is deleted between
    /// attempts so a partial file cannot be mistaken for a complete one.
    private static String downloadFirst(java.util.List<String> candidates, Path archive,
                                        BooleanSupplier cancelled,
                                        FfmpegRuntimeManager.Progress progress)
            throws IOException {
        IOException failure = null;
        for (String candidate : candidates) {
            progress.stage("downloading " + candidate, 0.10);
            try {
                FfmpegDownload.download(candidate, archive, Duration.ofMinutes(10),
                        (message, fraction) -> progress.stage(message, 0.10 + fraction * 0.10),
                        cancelled);
                return candidate;
            } catch (IOException e) {
                LOG.info("ffmpeg source download from " + candidate + " failed: " + e.getMessage());
                failure = e;
                Files.deleteIfExists(archive);
            }
        }
        throw new IOException("ffmpeg_download_failed: every source failed ("
                + String.join(", ", candidates) + ")"
                + (failure == null ? "" : ": " + failure.getMessage()), failure);
    }

    /// The orchestration below the download. Package-visible so tests can drive a
    /// local archive without an HTTP server; production only calls it from
    /// [#build].
    BuildResult buildFromArchive(Path archive, FfmpegCatalog.Entry entry, Path versionDir,
                                 FfmpegToolchainManager.Toolchain toolchain, String buildId,
                                 @Nullable Set<String> requestedFeatures,
                                 FfmpegRuntimeManager.Progress progress,
                                 BooleanSupplier cancelled) throws IOException {
        validateEntry(entry, versionDir);
        checkDisk();
        if (!toolchain.usable()) {
            throw new IOException(toolchain.missingMessage());
        }
        if (!Files.isRegularFile(archive)) {
            throw new IOException("ffmpeg_build_failed: the source archive is missing: " + archive);
        }
        FfmpegDownload.verifySha(archive, entry.sha256());

        String versionId = versionDir.getFileName().toString();
        BuildLog log = new BuildLog(logFor(buildId), progress);
        Path work = toolsDir.resolve(".build-" + safe(buildId) + "-" + UUID.randomUUID());
        Path sourceRoot = work.resolve("src");
        Path prefix = work.resolve("prefix");
        Path temp = work.resolve("tmp");
        boolean activated = false;
        try {
            log.stage("source " + entry.ffmpegVersion() + " (" + entry.url() + ")", 0.20);
            Files.createDirectories(sourceRoot);
            Files.createDirectories(prefix);
            Files.createDirectories(temp);
            log.stage("unpacking " + entry.archive() + " (Java, no system tar)", 0.21);
            FfmpegArchive.extract(archive, sourceRoot);
            Path configure = FfmpegArchive.find(sourceRoot, "configure");
            if (configure == null) {
                throw new IOException("ffmpeg_build_failed: the source archive has no ./configure");
            }
            Path checkout = configure.getParent();
            FfmpegRuntimeManager.makeExecutable(configure);
            // configure/make run as the agent inside the container; the tree the
            // panel just extracted as root must be writable by that uid.
            Privileges.handTreeToAgent(work);
            Privileges.prepareAgentDirectory(prefix, Privileges.AGENT_SHARED_DIRECTORY);
            Privileges.prepareAgentDirectory(temp, Privileges.AGENT_SHARED_DIRECTORY);

            Set<String> available = lower(toolchain.features());
            Set<String> requested = lower(requestedFeatures);
            Set<String> enabled = new LinkedHashSet<>();
            for (String feature : FEATURE_FLAGS.keySet()) {
                if (available.contains(feature)
                        && (requested.isEmpty() || requested.contains(feature))) {
                    enabled.add(feature);
                }
            }
            Set<String> missingRequested = new LinkedHashSet<>(requested);
            missingRequested.removeAll(enabled);
            if (!missingRequested.isEmpty()) {
                log.line("requested features not available on this toolchain: "
                        + String.join(", ", missingRequested));
            }
            boolean gpl = enabled.contains("libx264") || enabled.contains("libx265");
            List<String> configureArgs = configureArgs(prefix, enabled, toolchain.hasX86Asm(),
                    OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS);

            List<String> configureCommand = new ArrayList<>();
            if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
                String sh = toolchain.pathOf("sh");
                if (sh == null) {
                    throw new IOException("ffmpeg_build_toolchain_missing: sh.exe is required"
                            + " to run ffmpeg's POSIX configure script on Windows");
                }
                configureCommand.add(sh);
                configureCommand.add(configure.getFileName().toString());
            } else {
                configureCommand.add("./" + configure.getFileName());
            }
            configureCommand.addAll(configureArgs);
            log.stage("configure", 0.25);
            log.line("$ " + String.join(" ", configureCommand));
            runBuildProcess(configureCommand, checkout, toolchain, temp, configureTimeout,
                    "configure", log, cancelled);
            if (disableUnavailableSysctl(checkout)) {
                log.line("HAVE_SYSCTL disabled: the portable libc links sysctl but omits"
                        + " sys/sysctl.h; Linux uses sched_getaffinity/sysconf instead");
            }

            String make = toolchain.pathOf("make");
            if (make == null) {
                throw new IOException("ffmpeg_build_toolchain_missing: make was not found");
            }
            log.stage("make -j" + jobs, 0.30);
            runBuildProcess(List.of(make, "-j" + jobs), checkout, toolchain, temp,
                    makeTimeout, "make", log, cancelled);

            log.stage("make install", 0.86);
            runBuildProcess(List.of(make, "install"), checkout, toolchain, temp,
                    installTimeout, "make install", log, cancelled);

            if (cancelled.getAsBoolean()) {
                throw new CancellationException("cancelled");
            }
            Path installedFfmpeg = prefix.resolve("bin").resolve(FfmpegRuntimeManager.exe("ffmpeg"));
            Path installedProbe = prefix.resolve("bin").resolve(FfmpegRuntimeManager.exe("ffprobe"));
            if (!Files.isRegularFile(installedFfmpeg) || !Files.isRegularFile(installedProbe)) {
                throw new IOException("ffmpeg_build_failed: make install produced no ffmpeg/ffprobe pair");
            }
            FfmpegRuntimeManager.makeExecutable(installedFfmpeg);
            FfmpegRuntimeManager.makeExecutable(installedProbe);

            // A MinGW build may import libgcc/libwinpthread; the probe runs
            // outside the toolchain PATH, so copy those DLLs next to the binary
            // before asking it to start.
            if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
                copyWindowsDlls(prefix.resolve("bin"), toolchain);
            }
            log.stage("probing the new binary", 0.90);
            String ffprobeVersion;
            try {
                ffprobeVersion = FfmpegProbe.run(installedProbe,
                        List.of("-hide_banner", "-version"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("ffmpeg_install_interrupted", e);
            }
            if (ffprobeVersion == null || ffprobeVersion.isBlank()) {
                throw new IOException("ffmpeg_build_failed: the built ffprobe did not answer");
            }
            FfmpegProbe.FfmpegInfo info = FfmpegProbe.probe(
                    new FfmpegLocator.FfmpegPaths(installedFfmpeg, installedProbe));
            if (!info.available()) {
                throw new IOException("ffmpeg_build_failed: " + (info.error() == null
                        ? "the built ffmpeg did not answer its probe" : info.error()));
            }
            verifyOneFrame(installedFfmpeg, temp, cancelled);
            log.line("built " + info.version());

            List<String> declared = new ArrayList<>(enabled);
            FfmpegRuntimeManager.RuntimeRecord record = new FfmpegRuntimeManager.RuntimeRecord();
            record.id = versionId;
            record.version = entry.ffmpegVersion();
            record.catalogId = entry.id();
            record.major = entry.major();
            record.source = "ffmpeg.org";
            record.license = gpl ? "GPL-2.0-or-later" : entry.license();
            record.sha256 = entry.sha256().toLowerCase(Locale.ROOT);
            record.installedAt = Instant.now().toString();
            record.archive = "source:" + entry.archive();
            record.featuresDeclared = List.copyOf(declared);
            record.featuresProbed = FfmpegRuntimeManager.probedFeatures(declared, info);
            record.probeVersion = info.version();
            record.builtFrom = entry.url();
            record.buildRecipe = entry.buildRecipe().isEmpty() ? SUPPORTED_RECIPE : entry.buildRecipe();
            record.toolchain = toolchain.describe();
            record.notes = notes(entry, toolchain, enabled, missingRequested, gpl);
            if (log.logFile() != null) {
                record.notes = record.notes.isEmpty() ? "log: " + log.logFile()
                        : record.notes + "; log: " + log.logFile();
            }

            Path staging = toolsDir.resolve("." + versionId + ".installing-" + UUID.randomUUID());
            try {
                Files.createDirectories(staging.resolve("bin"));
                copyBinaries(prefix, staging, toolchain);
                FfmpegRuntimeManager.writeRuntime(staging, record);
                FfmpegArchive.deleteTree(versionDir);
                move(staging, versionDir);
            } finally {
                FfmpegArchive.deleteTree(staging);
            }

            log.stage("activating " + entry.ffmpegVersion(), 0.97);
            runtimes.activate(versionId);
            activated = true;
            runtimes.gc(2);
            log.stage("ffmpeg " + entry.ffmpegVersion() + " built and activated", 1.0);
            log.line("runtime.json: " + versionDir.resolve("runtime.json"));
            return new BuildResult(new FfmpegRuntimeManager.Installed(
                    versionId, versionDir, record, true), log.logFile(),
                    Set.copyOf(enabled), Set.copyOf(missingRequested), toolchain.describe());
        } catch (CancellationException e) {
            log.line("cancelled");
            throw e;
        } catch (IOException e) {
            log.line("failed: " + e.getMessage());
            throw e;
        } finally {
            if (!activated) {
                // A failed/cancelled build leaves no <id>/bin pair behind: the
                // runtime manager would otherwise register it as a legacy install.
                FfmpegArchive.deleteTree(versionDir);
            }
            FfmpegArchive.deleteTree(work);
            log.close();
        }
    }

    /// The pure part of the recipe: prefix, reproducibility flags, optional GPL
    /// and codec switches. Public-package so tests can assert the argv without a
    /// compiler.
    static List<String> configureArgs(Path prefix, Set<String> features, boolean x86asm, boolean windows) {
        List<String> args = new ArrayList<>();
        args.add("--prefix=" + prefix);
        args.add("--disable-doc");
        args.add("--disable-debug");
        args.add("--disable-shared");
        args.add("--enable-static");
        // Without this, a host with a stray lib* would silently change the build
        // between two machines; every external dependency is explicit below.
        args.add("--disable-autodetect");
        if (!x86asm) {
            args.add("--disable-x86asm");
        }
        Set<String> lower = lower(features);
        boolean gpl = lower.contains("libx264") || lower.contains("libx265");
        if (gpl) {
            args.add("--enable-gpl");
        }
        for (Map.Entry<String, List<String>> entry : FEATURE_FLAGS.entrySet()) {
            if (lower.contains(entry.getKey())) {
                args.addAll(entry.getValue());
            }
        }
        if (windows) {
            // w64devkit's `uname -s` is busybox-w32 and answers `Windows_NT`,
            // which ffmpeg lowercases to `windows_nt` and rejects as an unknown
            // OS. Pin the target explicitly instead of trusting that probe.
            args.add("--target-os=mingw32");
            args.add("--extra-ldflags=-static");
        }
        return List.copyOf(args);
    }

    /// Zig's bundled libc exports `sysctl`, so FFmpeg's `check_func sysctl`
/// succeeds and `config.h` says `HAVE_SYSCTL 1` — but Zig's Linux headers do
/// not ship `sys/sysctl.h`, and `libavutil/cpu.c` includes it unconditionally.
/// Linux does not need sysctl for the CPU count (sched_getaffinity/sysconf
/// come first), so turn it off after configure. Returns whether it changed.
    static boolean disableUnavailableSysctl(Path checkout) throws IOException {
        if (OperatingSystem.CURRENT_OS != OperatingSystem.LINUX) {
            return false;
        }
        Path config = checkout.resolve("config.h");
        if (!Files.isRegularFile(config)) {
            return false;
        }
        String text = Files.readString(config, StandardCharsets.UTF_8);
        if (!text.contains("#define HAVE_SYSCTL 1")) {
            return false;
        }
        Files.writeString(config,
                text.replace("#define HAVE_SYSCTL 1", "#define HAVE_SYSCTL 0"),
                StandardCharsets.UTF_8);
        return true;
    }

    private static Map<String, List<String>> featureFlags() {
        Map<String, List<String>> flags = new LinkedHashMap<>();
        flags.put("libx264", List.of("--enable-libx264"));
        flags.put("libx265", List.of("--enable-libx265"));
        flags.put("libvpx", List.of("--enable-libvpx"));
        flags.put("libaom", List.of("--enable-libaom"));
        flags.put("libsvtav1", List.of("--enable-libsvtav1"));
        flags.put("libvmaf", List.of("--enable-libvmaf"));
        flags.put("vaapi", List.of("--enable-vaapi"));
        flags.put("qsv", List.of("--enable-libvpl"));
        flags.put("nvenc", List.of("--enable-nvenc", "--enable-nvdec"));
        return java.util.Collections.unmodifiableMap(flags);
    }

    private void runBuildProcess(List<String> command, Path cwd,
                                 FfmpegToolchainManager.Toolchain toolchain,
                                 Path temp, Duration timeout, String phase,
                                 BuildLog log, BooleanSupplier cancelled) throws IOException {
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("cancelled");
        }
        List<String> argv = new ArrayList<>(command);
        if (OperatingSystem.CURRENT_OS != OperatingSystem.WINDOWS) {
            String nice = Executables.resolve("nice").map(Path::toString).orElse(null);
            if (nice != null) {
                List<String> niced = new ArrayList<>();
                niced.add(nice);
                niced.add("-n");
                niced.add("10");
                niced.addAll(argv);
                argv = niced;
            }
        }
        ProcessBuilder builder = BootstrapEnvironment.agentProcess(argv)
                .directory(cwd.toFile())
                .redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        env.putAll(toolchain.environment());
        env.put("LC_ALL", "C");
        env.put("LANG", "C");
        if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
            env.put("TEMP", temp.toString());
            env.put("TMP", temp.toString());
        } else {
            env.put("TMPDIR", temp.toString());
        }
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new IOException("ffmpeg_build_failed: could not start " + phase + ": "
                    + e.getMessage(), e);
        }
        log.line("$ " + String.join(" ", command));
        Thread reader = new Thread(() -> {
            try (BufferedReader lines = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) {
                    log.line(line);
                }
            } catch (IOException ignored) {
            }
        }, "ffmpeg-build-output");
        reader.setDaemon(true);
        reader.start();
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                if (cancelled.getAsBoolean()) {
                    ProcessTree.terminate(process, 2);
                    throw new CancellationException("cancelled");
                }
                if (System.nanoTime() > deadline) {
                    ProcessTree.terminate(process, 2);
                    throw new IOException("ffmpeg_build_failed: " + phase + " timed out after "
                            + timeout.toMinutes() + " min");
                }
            }
            try {
                reader.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (process.exitValue() != 0) {
                throw new IOException("ffmpeg_build_failed: " + phase + " exited with code "
                        + process.exitValue() + tail(log.tail()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ProcessTree.terminate(process, 2);
            throw new IOException("ffmpeg_install_interrupted", e);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private void verifyOneFrame(Path ffmpeg, Path temp, BooleanSupplier cancelled) throws IOException {
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("cancelled");
        }
        List<String> command = List.of(ffmpeg.toString(),
                "-hide_banner", "-v", "error",
                "-f", "lavfi", "-i", "color=c=black:s=64x64:d=0.04",
                "-frames:v", "1", "-c:v", "mpeg4", "-f", "null", "-");
        RunResult result = runCaptured(command, temp, Duration.ofSeconds(20), cancelled);
        if (result.exitCode() != 0) {
            throw new IOException("ffmpeg_build_failed: the one-frame lavfi encode failed with code "
                    + result.exitCode() + tail(result.output()));
        }
    }

    /// Runs a short probe and captures its output.
    ///
    /// As the agent, like the build itself: both the binary under test and the
    /// toolchain's `objdump` live in trees the panel handed to that uid, so a
    /// root run here would execute whatever the agent last wrote. Probing as the
    /// agent makes the file's owner and the process's uid the same party.
    private static RunResult runCaptured(List<String> command, Path cwd, Duration timeout,
                                         BooleanSupplier cancelled) throws IOException {
        ProcessBuilder builder = BootstrapEnvironment.agentProcess(command)
                .directory(cwd.toFile())
                .redirectErrorStream(true);
        Process process = builder.start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                in.transferTo(output);
            } catch (IOException ignored) {
            }
        }, "ffmpeg-build-probe");
        reader.setDaemon(true);
        reader.start();
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                if (cancelled.getAsBoolean()) {
                    ProcessTree.terminate(process, 2);
                    throw new CancellationException("cancelled");
                }
                if (System.nanoTime() > deadline) {
                    ProcessTree.terminate(process, 2);
                    throw new IOException("ffmpeg_build_failed: the built binary timed out");
                }
            }
            try {
                reader.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new RunResult(process.exitValue(), output.toString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ProcessTree.terminate(process, 2);
            throw new IOException("ffmpeg_install_interrupted", e);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /// Copies `prefix/bin` plus the MinGW runtime DLLs the binary imports. Static
    /// libav means there is no `libav*.so` to miss; the DLLs are what a MinGW
    /// `ffmpeg.exe` otherwise needs from the toolchain directory.
    private static void copyBinaries(Path prefix, Path staging,
                                     FfmpegToolchainManager.Toolchain toolchain) throws IOException {
        Path sourceBin = prefix.resolve("bin");
        Path targetBin = staging.resolve("bin");
        Files.createDirectories(targetBin);
        try (Stream<Path> stream = Files.list(sourceBin)) {
            for (Path file : stream.toList()) {
                Files.copy(file, targetBin.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        FfmpegRuntimeManager.makeExecutable(targetBin.resolve(FfmpegRuntimeManager.exe("ffmpeg")));
        FfmpegRuntimeManager.makeExecutable(targetBin.resolve(FfmpegRuntimeManager.exe("ffprobe")));
        Path prefixLib = prefix.resolve("lib");
        if (Files.isDirectory(prefixLib)) {
            // Kept only if a future recipe enables shared libav; for the default
            // static recipe this directory holds .a files that are not needed at
            // runtime and would multiply the installed size.
            boolean hasShared = false;
            try (Stream<Path> libs = Files.list(prefixLib)) {
                hasShared = libs.anyMatch(path -> {
                    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                    return name.contains(".so") || name.endsWith(".dll") || name.endsWith(".dylib");
                });
            }
            if (hasShared) {
                copyTree(prefixLib, staging.resolve("lib"));
            }
        }
        if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
            copyWindowsDlls(staging.resolve("bin"), toolchain);
        }
    }

    private static void copyWindowsDlls(Path bin, FfmpegToolchainManager.Toolchain toolchain) {
        String objdump = toolchain.pathOf("objdump");
        if (objdump == null) {
            copyCommonDlls(bin, toolchain);
            return;
        }
        Set<String> names = new LinkedHashSet<>();
        try {
            RunResult result = runCaptured(List.of(objdump, "-p",
                    bin.resolve("ffmpeg.exe").toString()), bin, Duration.ofSeconds(20), () -> false);
            for (String line : result.output().split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("DLL Name:")) {
                    names.add(trimmed.substring("DLL Name:".length()).trim());
                }
            }
        } catch (IOException e) {
            LOG.warning("ffmpeg build: objdump could not list DLL imports: " + e.getMessage());
        }
        if (names.isEmpty()) {
            copyCommonDlls(bin, toolchain);
            return;
        }
        Path toolBin = toolchain.binDir();
        if (toolBin == null) {
            return;
        }
        for (String name : names) {
            if (isSystemDll(name)) {
                continue;
            }
            Path candidate = toolBin.resolve(name);
            if (Files.isRegularFile(candidate) && !Files.exists(bin.resolve(name))) {
                try {
                    Files.copy(candidate, bin.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    LOG.warning("ffmpeg build: could not copy " + name + ": " + e.getMessage());
                }
            }
        }
    }

    private static void copyCommonDlls(Path bin, FfmpegToolchainManager.Toolchain toolchain) {
        Path toolBin = toolchain.binDir();
        if (toolBin == null) {
            return;
        }
        for (String name : List.of("libgcc_s_seh-1.dll", "libgcc_s_dw2-1.dll",
                "libstdc++-6.dll", "libwinpthread-1.dll", "libssp-0.dll")) {
            Path candidate = toolBin.resolve(name);
            if (Files.isRegularFile(candidate) && !Files.exists(bin.resolve(name))) {
                try {
                    Files.copy(candidate, bin.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static boolean isSystemDll(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return Set.of("kernel32.dll", "user32.dll", "gdi32.dll", "advapi32.dll", "shell32.dll",
                "ole32.dll", "oleaut32.dll", "ws2_32.dll", "secur32.dll", "bcrypt.dll",
                "crypt32.dll", "msvcrt.dll", "ucrtbase.dll", "ntdll.dll", "winmm.dll",
                "version.dll", "shlwapi.dll", "userenv.dll", "psapi.dll").contains(lower);
    }

    private static String notes(FfmpegCatalog.Entry entry,
                                FfmpegToolchainManager.Toolchain toolchain,
                                Set<String> enabled,
                                Set<String> missingRequested,
                                boolean gpl) {
        StringBuilder notes = new StringBuilder();
        notes.append("source build of ").append(entry.ffmpegVersion())
                .append(" from ").append(entry.url()).append("; ")
                .append(toolchain.describe()).append("; ");
        notes.append(gpl
                ? "GPL enabled because libx264/libx265 are linked"
                : "LGPL build: no GPL-only external encoder was enabled");
        if (enabled.isEmpty()) {
            notes.append("; minimal build: no external codec development packages were detected");
        }
        if (!missingRequested.isEmpty()) {
            notes.append("; requested but unavailable: ").append(String.join(", ", missingRequested));
        }
        notes.append("; libav is static; external codec libraries may still be shared on Linux");
        return notes.toString();
    }

    private void validateEntry(FfmpegCatalog.Entry entry, Path versionDir) throws IOException {
        if (entry == null || !entry.isSource()) {
            throw new IOException("ffmpeg_source_unsupported: the entry is not a source recipe");
        }
        if (entry.url() == null || entry.url().isBlank()) {
            throw new IOException("ffmpeg_artifact_invalid: the source entry has no URL");
        }
        FfmpegInstaller.validateUrl(entry.url());
        if (entry.sha256() == null || !entry.sha256().matches("[0-9a-fA-F]{64}")) {
            throw new IOException("ffmpeg_artifact_invalid: the source entry has no pinned SHA-256");
        }
        if (!entry.buildRecipe().isEmpty() && !SUPPORTED_RECIPE.equals(entry.buildRecipe())) {
            throw new IOException("ffmpeg_source_unsupported: unknown build recipe '"
                    + entry.buildRecipe() + "' (this panel implements " + SUPPORTED_RECIPE + ")");
        }
        Path normalized = versionDir.toAbsolutePath().normalize();
        if (!normalized.getParent().equals(toolsDir) || normalized.equals(toolsDir)) {
            throw new IOException("ffmpeg_artifact_invalid: the version directory must be a direct child of "
                    + toolsDir);
        }
        if (Files.isRegularFile(normalized.resolve("runtime.json"))) {
            throw new IOException("ffmpeg_already_installed: " + normalized.getFileName());
        }
    }

    private void checkDisk() throws IOException {
        if (minimumFreeBytes <= 0) {
            return;
        }
        try {
            long usable = Files.getFileStore(toolsDir).getUsableSpace();
            if (usable < minimumFreeBytes) {
                throw new IOException("ffmpeg_disk_full: a source build needs about "
                        + (minimumFreeBytes / (1024 * 1024 * 1024)) + " GiB free, "
                        + toolsDir + " has " + (usable / (1024 * 1024)) + " MiB");
            }
        } catch (java.nio.file.FileSystemException e) {
            // A virtual filesystem may not report usable space; do not turn that
            // into a failed build.
            LOG.info("ffmpeg build: free-space check skipped for " + toolsDir + ": " + e.getMessage());
        }
    }

    private static void move(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path path : stream.toList()) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static String suffix(@Nullable String archive) {
        String value = archive == null ? "" : archive.toLowerCase(Locale.ROOT);
        return switch (value) {
            case "zip" -> ".zip";
            case "tar" -> ".tar";
            case "tar.gz", "tgz" -> ".tar.gz";
            case "tar.xz", "txz" -> ".tar.xz";
            default -> ".bin";
        };
    }

    private static String safe(String value) {
        String safe = value == null ? "" : value.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.isBlank()) {
            safe = "build";
        }
        return safe.length() > 80 ? safe.substring(0, 80) : safe;
    }

    private static Set<String> lower(@Nullable Iterable<String> values) {
        Set<String> result = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    result.add(value.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return result;
    }

    private static String tail(@Nullable String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String[] lines = trimmed.split("\\R");
        int start = Math.max(0, lines.length - 12);
        StringBuilder tail = new StringBuilder(": ");
        for (int i = start; i < lines.length; i++) {
            if (i > start) {
                tail.append(" | ");
            }
            tail.append(lines[i]);
        }
        return tail.toString();
    }

    /// Writes every line to `<logs>/build-<id>.log` and mirrors it to the job's
    /// progress sink. The file is what survives a panel restart; the job log is
    /// what the settings page polls.
    private static final class BuildLog {
        private final @Nullable Path file;
        private final FfmpegRuntimeManager.Progress progress;
        private final Deque<String> tail = new ArrayDeque<>();
        private java.io.BufferedWriter writer;
        private double fraction;

        BuildLog(Path file, FfmpegRuntimeManager.Progress progress) {
            this.progress = progress;
            Path prepared = null;
            try {
                Files.createDirectories(file.getParent());
                this.writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
                prepared = file;
                writer.write("# ffmpeg source build " + Instant.now() + "\n");
                writer.flush();
            } catch (IOException e) {
                LOG.warning("ffmpeg build: could not write " + file + ": " + e.getMessage());
            }
            this.file = prepared;
        }

        @Nullable Path logFile() {
            return file;
        }

        synchronized void stage(String message, double fraction) {
            this.fraction = fraction;
            progress.stage(message, fraction);
            line("== " + message);
        }

        synchronized void line(String message) {
            if (message == null) {
                return;
            }
            synchronized (tail) {
                for (String part : message.split("\\R")) {
                    tail.addLast(part);
                    while (tail.size() > 40) {
                        tail.removeFirst();
                    }
                }
            }
            if (writer != null) {
                try {
                    writer.write(message);
                    writer.write('\n');
                    writer.flush();
                } catch (IOException ignored) {
                }
            }
            // The settings page's job log tails through `progress`; keep its
            // fraction where the last stage left it instead of resetting it.
            progress.stage(message.length() > 400 ? message.substring(0, 400) : message, fraction);
        }

        synchronized String tail() {
            synchronized (tail) {
                return String.join("\n", tail);
            }
        }

        synchronized void close() {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}