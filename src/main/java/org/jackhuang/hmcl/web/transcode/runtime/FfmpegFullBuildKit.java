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

import org.jackhuang.hmcl.dsh.BootstrapEnvironment;
import org.jackhuang.hmcl.util.ProcessTree;
import org.jackhuang.hmcl.util.logging.Logger;
import org.jackhuang.hmcl.util.platform.Executables;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jackhuang.hmcl.util.platform.Privileges;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.stream.Stream;

/// Builds a **full-codec** ffmpeg from pinned sources, including the dependency
/// libraries that a bare host does not have. The result is a static ffmpeg (the
/// libav libraries and every codec library are `.a`); glibc itself remains
/// dynamic, as it does in every practical "static ffmpeg" build.
///
/// The catalogue pins versions and hashes; this class owns the commands.
/// A replaced catalogue can select another version or hash, but cannot turn a
/// build into arbitrary code execution: every dependency id maps to a fixed
/// recipe below, and the panel never runs a script from the archive.
///
/// The build runs as the agent uid. Downloads and archive extraction happen as
/// the panel (root in a container), then the tree is handed to the agent before
/// `configure`/`make`. Only the finished `bin/ffmpeg` and `bin/ffprobe` are
/// copied into the runtime directory.
@NotNullByDefault
public final class FfmpegFullBuildKit {

    private static final Logger LOG = Logger.LOG;
    public static final String RECIPE = "full-static-v1";

    private static final long DEFAULT_MINIMUM_FREE_BYTES = 5L * 1024 * 1024 * 1024;
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(20);
    private static final Duration DEP_CONFIGURE_TIMEOUT = Duration.ofMinutes(15);
    private static final Duration DEP_MAKE_TIMEOUT = Duration.ofMinutes(40);
    private static final Duration FFMPEG_CONFIGURE_TIMEOUT = Duration.ofMinutes(20);
    private static final Duration FFMPEG_MAKE_TIMEOUT = Duration.ofMinutes(80);
    private static final Duration INSTALL_TIMEOUT = Duration.ofMinutes(15);

    private final Path toolsDir;
    private final FfmpegRuntimeManager runtimes;
    private final FfmpegToolchainManager toolchains;
    private final Path logsDir;
    private final long minimumFreeBytes;
    private final int jobs;
    private final Duration dependencyMakeTimeout;
    private final Duration ffmpegMakeTimeout;

    private volatile java.util.function.Supplier<String> sourceMirror = () -> "";

    public FfmpegFullBuildKit(Path toolsDir, FfmpegRuntimeManager runtimes,
                              FfmpegToolchainManager toolchains, Path logsDir) {
        this(toolsDir, runtimes, toolchains, logsDir, DEFAULT_MINIMUM_FREE_BYTES,
                Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(), 4)),
                DEP_MAKE_TIMEOUT, FFMPEG_MAKE_TIMEOUT);
    }

    /// Test seam: short timeouts and a fake tools directory.
    FfmpegFullBuildKit(Path toolsDir, FfmpegRuntimeManager runtimes,
                       FfmpegToolchainManager toolchains, Path logsDir,
                       long minimumFreeBytes, int jobs,
                       Duration dependencyMakeTimeout, Duration ffmpegMakeTimeout) {
        this.toolsDir = toolsDir.toAbsolutePath().normalize();
        this.runtimes = runtimes;
        this.toolchains = toolchains;
        this.logsDir = logsDir.toAbsolutePath().normalize();
        this.minimumFreeBytes = minimumFreeBytes;
        this.jobs = Math.max(1, jobs);
        this.dependencyMakeTimeout = dependencyMakeTimeout;
        this.ffmpegMakeTimeout = ffmpegMakeTimeout;
    }

    public void setSourceMirror(java.util.function.Supplier<String> mirror) {
        this.sourceMirror = mirror == null ? () -> "" : mirror;
    }

    public Path logFor(String buildId) {
        return logsDir.resolve("build-full-" + safe(buildId) + ".log");
    }

    /// Downloads, builds and activates one full-build recipe.
    public FfmpegBuildKit.BuildResult build(FfmpegFullBuildCatalog.Entry entry, Path versionDir,
                                            String buildId,
                                            FfmpegRuntimeManager.Progress progress,
                                            BooleanSupplier cancelled,
                                            FfmpegToolchainManager.Preference preference)
            throws IOException {
        if (OperatingSystem.CURRENT_OS != OperatingSystem.LINUX) {
            throw new IOException("ffmpeg_full_build_unsupported_platform:"
                    + " the managed full build is implemented for Linux only");
        }
        validate(entry, versionDir);
        Files.createDirectories(toolsDir);
        checkDisk(Math.max(minimumFreeBytes, entry.diskBytes()));

        FfmpegToolchainManager.Toolchain toolchain = toolchains.resolveForBuild(
                (message, fraction) -> progress.stage(message, fraction * 0.02), cancelled, preference);
        if (!toolchain.usable()) {
            throw new IOException(toolchain.missingMessage());
        }

        String versionId = versionDir.getFileName().toString();
        BuildLog log = new BuildLog(logFor(buildId), progress);
        Path work = toolsDir.resolve(".build-" + safe(buildId) + "-" + UUID.randomUUID());
        Path prefix = work.resolve("prefix");
        Path sourceRoot = work.resolve("src");
        Path downloads = work.resolve("downloads");
        Path temp = work.resolve("tmp");
        boolean activated = false;
        try {
            Files.createDirectories(prefix);
            Files.createDirectories(sourceRoot);
            Files.createDirectories(downloads);
            Files.createDirectories(temp);
            Privileges.handTreeToAgent(work);
            Privileges.prepareAgentDirectory(prefix, Privileges.AGENT_SHARED_DIRECTORY);
            Privileges.prepareAgentDirectory(temp, Privileges.AGENT_SHARED_DIRECTORY);

            log.stage("full build " + entry.id() + " (" + entry.profile() + ", static)", 0.02);
            ToolBins bins = prepareTools(entry.dependencies(), work, temp, toolchain,
                    log, cancelled);
            Map<String, String> environment = environment(toolchain, prefix, bins, temp);

            List<FfmpegFullBuildCatalog.Dependency> codecs = entry.dependencies().stream()
                    .filter(dependency -> "codec".equals(dependency.kind()))
                    .toList();
            int index = 0;
            for (FfmpegFullBuildCatalog.Dependency dependency : codecs) {
                ensureNotCancelled(cancelled);
                double start = 0.05 + 0.58 * (index / (double) codecs.size());
                double end = 0.05 + 0.58 * ((index + 1) / (double) codecs.size());
                log.stage("dependency " + dependency.id() + " (" + (index + 1) + "/"
                        + codecs.size() + ")", start);
                Path archive = download(dependency, downloads, start, end,
                        progress, cancelled);
                Path destination = sourceRoot.resolve(dependency.id());
                log.line("unpacking " + dependency.id() + " (" + dependency.archive() + ")");
                FfmpegArchive.extract(archive, destination);
                Privileges.handTreeToAgent(destination);
                buildDependency(dependency, destination, prefix, environment, temp,
                        toolchain, log, cancelled, start, end);
                Files.deleteIfExists(archive);
                index++;
            }

            ensureNotCancelled(cancelled);
            log.stage("downloading ffmpeg " + entry.ffmpegVersion(), 0.66);
            Path ffmpegArchive = downloadRaw("ffmpeg", entry.ffmpegUrl(), entry.ffmpegSha256(),
                    entry.ffmpegArchive(), downloads, 0.66, 0.70, progress, cancelled,
                    List.of());
            Path ffmpegSource = sourceRoot.resolve("ffmpeg");
            FfmpegArchive.extract(ffmpegArchive, ffmpegSource);
            Privileges.handTreeToAgent(ffmpegSource);
            Files.deleteIfExists(ffmpegArchive);

            Path configure = findShallowest(ffmpegSource, "configure");
            if (configure == null) {
                throw new IOException("ffmpeg_full_build_failed: the ffmpeg source has no ./configure");
            }
            FfmpegRuntimeManager.makeExecutable(configure);
            Path checkout = configure.getParent();
            List<String> configureArgs = configureArgs(prefix, entry.features(),
                    hasX86Asm(bins, toolchain), entry.gpl());
            List<String> configureCommand = new ArrayList<>();
            configureCommand.add("./" + configure.getFileName());
            configureCommand.addAll(configureArgs);
            log.stage("configure ffmpeg", 0.72);
            log.line("$ " + String.join(" ", configureCommand));
            run(configureCommand, checkout, environment, temp, FFMPEG_CONFIGURE_TIMEOUT,
                    "ffmpeg configure", log, cancelled);

            String make = toolchain.pathOf("make");
            if (make == null) {
                throw new IOException("ffmpeg_build_toolchain_missing: make was not found");
            }
            log.stage("make ffmpeg -j" + jobs, 0.78);
            run(List.of(make, "-j" + jobs), checkout, environment, temp, ffmpegMakeTimeout,
                    "ffmpeg make", log, cancelled);
            log.stage("install ffmpeg", 0.90);
            run(List.of(make, "install"), checkout, environment, temp, INSTALL_TIMEOUT,
                    "ffmpeg make install", log, cancelled);
            ensureNotCancelled(cancelled);

            Path installedFfmpeg = prefix.resolve("bin").resolve(FfmpegRuntimeManager.exe("ffmpeg"));
            Path installedProbe = prefix.resolve("bin").resolve(FfmpegRuntimeManager.exe("ffprobe"));
            if (!Files.isRegularFile(installedFfmpeg) || !Files.isRegularFile(installedProbe)) {
                throw new IOException("ffmpeg_full_build_failed: make install produced no ffmpeg/ffprobe");
            }
            FfmpegRuntimeManager.makeExecutable(installedFfmpeg);
            FfmpegRuntimeManager.makeExecutable(installedProbe);

            log.stage("probing the new binary", 0.94);
            FfmpegProbe.FfmpegInfo info = FfmpegProbe.probe(
                    new FfmpegLocator.FfmpegPaths(installedFfmpeg, installedProbe));
            if (!info.available()) {
                throw new IOException("ffmpeg_full_build_failed: "
                        + (info.error() == null ? "the built ffmpeg did not answer" : info.error()));
            }
            verifyEncoders(installedFfmpeg, temp, entry.features(), log, cancelled);
            log.line("built " + info.version() + " with " + info.encoders().size() + " encoders");

            FfmpegRuntimeManager.RuntimeRecord record = new FfmpegRuntimeManager.RuntimeRecord();
            record.id = versionId;
            record.version = entry.ffmpegVersion();
            record.catalogId = entry.id();
            record.major = FfmpegCatalog.Entry.inferMajor(entry.ffmpegVersion());
            record.source = "ffmpeg.org";
            record.license = entry.gpl() ? "GPL-2.0-or-later" : "LGPL-2.1-or-later";
            record.sha256 = entry.ffmpegSha256().toLowerCase(Locale.ROOT);
            record.installedAt = Instant.now().toString();
            record.archive = "full-source:" + entry.ffmpegArchive();
            record.featuresDeclared = entry.features();
            record.featuresProbed = FfmpegRuntimeManager.probedFeatures(entry.features(), info);
            record.probeVersion = info.version();
            record.builtFrom = entry.ffmpegUrl();
            record.buildRecipe = RECIPE;
            record.toolchain = toolchain.describe() + " + cmake " + bins.cmakeVersion()
                    + " + nasm " + bins.nasmVersion();
            record.notes = notes(entry, bins, record.featuresProbed);
            if (log.logFile() != null) {
                record.notes = record.notes + "; log: " + log.logFile();
            }

            Path staging = toolsDir.resolve("." + versionId + ".installing-" + UUID.randomUUID());
            try {
                Files.createDirectories(staging.resolve("bin"));
                copyBinaries(prefix, staging);
                FfmpegRuntimeManager.writeRuntime(staging, record);
                FfmpegArchive.deleteTree(versionDir);
                move(staging, versionDir);
            } finally {
                FfmpegArchive.deleteTree(staging);
            }

            log.stage("activating " + entry.ffmpegVersion(), 0.98);
            runtimes.activate(versionId);
            activated = true;
            runtimes.gc(2);
            log.stage("full ffmpeg " + entry.ffmpegVersion() + " built and activated", 1.0);
            log.line("runtime.json: " + versionDir.resolve("runtime.json"));
            return new FfmpegBuildKit.BuildResult(
                    new FfmpegRuntimeManager.Installed(versionId, versionDir, record, true),
                    log.logFile(), Set.copyOf(entry.features()), Set.of(),
                    record.toolchain);
        } catch (CancellationException e) {
            log.line("cancelled");
            throw e;
        } catch (IOException e) {
            log.line("failed: " + e.getMessage());
            throw e;
        } finally {
            if (!activated) {
                FfmpegArchive.deleteTree(versionDir);
            }
            FfmpegArchive.deleteTree(work);
            log.close();
        }
    }

    // --------------------------------------------------------------- tools --

    private record ToolBins(@Nullable Path cmake, @Nullable Path nasm,
                            String cmakeVersion, String nasmVersion) {
    }

    /// Downloads/installs the build tools into a cache that survives builds.
    /// CMake is a pinned upstream binary; nasm is built from its pinned source.
    private ToolBins prepareTools(List<FfmpegFullBuildCatalog.Dependency> dependencies,
                                  Path work, Path temp,
                                  FfmpegToolchainManager.Toolchain toolchain,
                                  BuildLog log, BooleanSupplier cancelled) throws IOException {
        Path cache = toolsDir.resolve(".build-tools");
        Files.createDirectories(cache);
        Path cmake = null;
        Path nasm = null;
        String cmakeVersion = "";
        String nasmVersion = "";
        for (FfmpegFullBuildCatalog.Dependency dependency : dependencies) {
            if (!"tool".equals(dependency.kind())) {
                continue;
            }
            ensureNotCancelled(cancelled);
            Path installed = cache.resolve(dependency.id() + "-" + safe(dependency.version()));
            if ("cmake".equals(dependency.id())) {
                cmake = installed.resolve("bin").resolve("cmake");
                if (!Files.isRegularFile(cmake)) {
                    cmake = installCmake(dependency, installed, work, temp, toolchain, log, cancelled);
                }
                cmakeVersion = dependency.version();
            } else if ("nasm".equals(dependency.id())) {
                nasm = installed.resolve("bin").resolve("nasm");
                if (!Files.isRegularFile(nasm)) {
                    nasm = installNasm(dependency, installed, work, temp, toolchain, log, cancelled);
                }
                nasmVersion = dependency.version();
            } else {
                throw new IOException("ffmpeg_full_build_tool_unsupported: " + dependency.id());
            }
        }
        return new ToolBins(cmake, nasm, cmakeVersion, nasmVersion);
    }

    private Path installCmake(FfmpegFullBuildCatalog.Dependency dependency, Path target,
                              Path work, Path temp, FfmpegToolchainManager.Toolchain toolchain,
                              BuildLog log, BooleanSupplier cancelled) throws IOException {
        Path archive = download(dependency, work.resolve("downloads"), 0.02, 0.03,
                log.progress(), cancelled);
        Path staging = work.resolve("tool-cmake-" + UUID.randomUUID());
        try {
            log.stage("unpacking cmake " + dependency.version(), 0.03);
            FfmpegArchive.extract(archive, staging);
            Path binary = find(staging, path -> path.getFileName().toString().equals("cmake")
                    && path.getParent() != null
                    && path.getParent().getFileName().toString().equals("bin"));
            if (binary == null) {
                throw new IOException("ffmpeg_full_build_failed: the cmake archive has no bin/cmake");
            }
            Path root = binary.getParent().getParent();
            FfmpegRuntimeManager.makeExecutable(binary);
            Files.createDirectories(target.getParent());
            FfmpegArchive.deleteTree(target);
            move(root, target);
            log.line("cmake " + dependency.version() + " cached at " + target);
            return target.resolve("bin").resolve("cmake");
        } finally {
            Files.deleteIfExists(archive);
            FfmpegArchive.deleteTree(staging);
        }
    }

    private Path installNasm(FfmpegFullBuildCatalog.Dependency dependency, Path target,
                             Path work, Path temp, FfmpegToolchainManager.Toolchain toolchain,
                             BuildLog log, BooleanSupplier cancelled) throws IOException {
        Path archive = download(dependency, work.resolve("downloads"), 0.02, 0.03,
                log.progress(), cancelled);
        Path staging = work.resolve("tool-nasm-" + UUID.randomUUID());
        try {
            log.stage("unpacking nasm " + dependency.version(), 0.03);
            FfmpegArchive.extract(archive, staging);
            Path configure = findShallowest(staging, "configure");
            if (configure == null) {
                throw new IOException("ffmpeg_full_build_failed: the nasm archive has no ./configure");
            }
            FfmpegRuntimeManager.makeExecutable(configure);
            Path source = configure.getParent();
            Path out = staging.resolve("out");
            Files.createDirectories(out);
            Privileges.handTreeToAgent(staging);
            Privileges.prepareAgentDirectory(out, Privileges.AGENT_SHARED_DIRECTORY);
            Map<String, String> env = new LinkedHashMap<>(toolchain.environment());
            env.put("LC_ALL", "C");
            env.put("LANG", "C");
            run(List.of("./configure", "--prefix=" + out), source, env, temp,
                    DEP_CONFIGURE_TIMEOUT, "nasm configure", log, cancelled);
            String make = toolchain.pathOf("make");
            if (make == null) {
                throw new IOException("ffmpeg_build_toolchain_missing: make was not found");
            }
            run(List.of(make, "-j" + jobs), source, env, temp, dependencyMakeTimeout,
                    "nasm make", log, cancelled);
            run(List.of(make, "install"), source, env, temp, INSTALL_TIMEOUT,
                    "nasm install", log, cancelled);
            Files.createDirectories(target.getParent());
            FfmpegArchive.deleteTree(target);
            move(out, target);
            log.line("nasm " + dependency.version() + " cached at " + target);
            return target.resolve("bin").resolve("nasm");
        } finally {
            Files.deleteIfExists(archive);
            FfmpegArchive.deleteTree(staging);
        }
    }

    // ----------------------------------------------------------- dependencies --

    private void buildDependency(FfmpegFullBuildCatalog.Dependency dependency, Path source,
                                 Path prefix, Map<String, String> environment, Path temp,
                                 FfmpegToolchainManager.Toolchain toolchain, BuildLog log,
                                 BooleanSupplier cancelled, double start, double end)
            throws IOException {
        String id = dependency.id();
        log.stage("building " + id + " " + dependency.version(), start + (end - start) * 0.25);
        switch (id) {
            case "libogg" -> autotools(source, prefix, environment, temp, toolchain, log, cancelled,
                    List.of("--disable-shared", "--enable-static"));
            case "libvorbis" -> autotools(source, prefix, environment, temp, toolchain, log, cancelled,
                    List.of("--disable-shared", "--enable-static", "--with-ogg=" + prefix));
            case "opus" -> autotools(source, prefix, environment, temp, toolchain, log, cancelled,
                    List.of("--disable-shared", "--enable-static", "--disable-doc",
                            "--disable-extra-programs"));
            case "lame" -> autotools(source, prefix, environment, temp, toolchain, log, cancelled,
                    List.of("--disable-shared", "--enable-static", "--disable-frontend"));
            case "libvpx" -> autotools(source, prefix, environment, temp, toolchain, log, cancelled,
                    List.of("--disable-shared", "--enable-static", "--disable-examples",
                            "--disable-tools", "--disable-docs", "--disable-unit-tests",
                            "--enable-vp8", "--enable-vp9", "--enable-vp9-highbitdepth",
                            "--enable-pic"));
            case "x264" -> autotools(source, prefix, environment, temp, toolchain, log, cancelled,
                    List.of("--disable-cli", "--enable-static", "--disable-shared",
                            "--enable-pic", "--disable-opencl"));
            case "x265" -> cmake(source, prefix, environment, temp, toolchain, log, cancelled,
                    List.of("-DENABLE_SHARED=OFF", "-DENABLE_CLI=OFF", "-DENABLE_ASSEMBLY=ON",
                            "-DENABLE_PKG_CONFIG=ON"));
            case "libaom" -> cmake(source, prefix, environment, temp, toolchain, log, cancelled,
                    List.of("-DENABLE_TESTS=0", "-DENABLE_EXAMPLES=0", "-DENABLE_TOOLS=0",
                            "-DENABLE_DOCS=0"));
            case "svtav1" -> cmake(source, prefix, environment, temp, toolchain, log, cancelled,
                    List.of("-DBUILD_APPS=OFF", "-DENABLE_TESTING=OFF", "-DCOMPILE_C_ONLY=OFF"));
            default -> throw new IOException("ffmpeg_full_build_dependency_unsupported: " + id);
        }
        log.stage("built " + id, end);
    }

    private void autotools(Path source, Path prefix, Map<String, String> environment,
                           Path temp, FfmpegToolchainManager.Toolchain toolchain,
                           BuildLog log, BooleanSupplier cancelled, List<String> extraArgs)
            throws IOException {
        Path configure = findShallowest(source, "configure");
        if (configure == null) {
            throw new IOException("ffmpeg_full_build_failed: the dependency has no ./configure");
        }
        FfmpegRuntimeManager.makeExecutable(configure);
        Path checkout = configure.getParent();
        List<String> command = new ArrayList<>();
        command.add("./" + configure.getFileName());
        command.add("--prefix=" + prefix);
        command.addAll(extraArgs);
        log.line("$ " + String.join(" ", command));
        run(command, checkout, environment, temp, DEP_CONFIGURE_TIMEOUT,
                "configure " + checkout.getFileName(), log, cancelled);
        String make = toolchain.pathOf("make");
        if (make == null) {
            throw new IOException("ffmpeg_build_toolchain_missing: make was not found");
        }
        run(List.of(make, "-j" + jobs), checkout, environment, temp, dependencyMakeTimeout,
                "make " + checkout.getFileName(), log, cancelled);
        run(List.of(make, "install"), checkout, environment, temp, INSTALL_TIMEOUT,
                "install " + checkout.getFileName(), log, cancelled);
    }

    private void cmake(Path source, Path prefix, Map<String, String> environment,
                       Path temp, FfmpegToolchainManager.Toolchain toolchain,
                       BuildLog log, BooleanSupplier cancelled, List<String> extraArgs)
            throws IOException {
        Path cmake = environmentCmake(environment);
        if (cmake == null) {
            throw new IOException("ffmpeg_full_build_failed: cmake is unavailable");
        }
        Path cmakeLists = findShallowest(source, "CMakeLists.txt");
        if (cmakeLists == null) {
            throw new IOException("ffmpeg_full_build_failed: the dependency has no CMakeLists.txt");
        }
        Path cmakeSource = cmakeLists.getParent();
        Path build = source.resolve("build");
        Files.createDirectories(build);
        List<String> configure = new ArrayList<>(List.of(
                cmake.toString(), "-S", cmakeSource.toString(), "-B", build.toString(),
                "-G", "Unix Makefiles",
                "-DCMAKE_INSTALL_PREFIX=" + prefix,
                "-DCMAKE_BUILD_TYPE=Release",
                "-DCMAKE_POSITION_INDEPENDENT_CODE=ON",
                "-DBUILD_SHARED_LIBS=OFF"));
        configure.addAll(extraArgs);
        log.line("$ " + String.join(" ", configure));
        run(configure, source, environment, temp, DEP_CONFIGURE_TIMEOUT,
                "cmake configure " + source.getFileName(), log, cancelled);
        run(List.of(cmake.toString(), "--build", build.toString(), "--parallel", Integer.toString(jobs)),
                source, environment, temp, dependencyMakeTimeout,
                "cmake build " + source.getFileName(), log, cancelled);
        run(List.of(cmake.toString(), "--install", build.toString()),
                source, environment, temp, INSTALL_TIMEOUT,
                "cmake install " + source.getFileName(), log, cancelled);
    }

    private static @Nullable Path environmentCmake(Map<String, String> environment) {
        String path = environment.get("PATH");
        if (path == null) {
            return null;
        }
        for (String entry : path.split(java.io.File.pathSeparator)) {
            Path candidate = Path.of(entry).resolve("cmake");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- ffmpeg --

    /// The pure part of the recipe; package-visible so a test can assert the argv.
    static List<String> configureArgs(Path prefix, List<String> features, boolean x86asm,
                                      boolean gpl) {
        List<String> args = new ArrayList<>(List.of(
                "--prefix=" + prefix,
                "--disable-doc",
                "--disable-debug",
                "--disable-shared",
                "--enable-static",
                "--disable-autodetect",
                "--pkg-config-flags=--static",
                "--extra-cflags=-I" + prefix + "/include",
                "--extra-ldflags=-L" + prefix + "/lib -static-libstdc++ -static-libgcc",
                "--extra-libs=-lm -lpthread -ldl"));
        if (!x86asm) {
            args.add("--disable-x86asm");
        }
        if (gpl) {
            args.add("--enable-gpl");
        }
        Set<String> lower = new LinkedHashSet<>();
        for (String feature : features) {
            lower.add(feature.toLowerCase(Locale.ROOT));
        }
        for (Map.Entry<String, List<String>> entry : FEATURE_FLAGS.entrySet()) {
            if (lower.contains(entry.getKey())) {
                args.addAll(entry.getValue());
            }
        }
        return List.copyOf(args);
    }

    private static final Map<String, List<String>> FEATURE_FLAGS = featureFlags();

    private static Map<String, List<String>> featureFlags() {
        Map<String, List<String>> flags = new LinkedHashMap<>();
        flags.put("libx264", List.of("--enable-libx264"));
        flags.put("libx265", List.of("--enable-libx265"));
        flags.put("libvpx", List.of("--enable-libvpx"));
        flags.put("libaom", List.of("--enable-libaom"));
        flags.put("libsvtav1", List.of("--enable-libsvtav1"));
        flags.put("libopus", List.of("--enable-libopus"));
        flags.put("libmp3lame", List.of("--enable-libmp3lame"));
        flags.put("libvorbis", List.of("--enable-libvorbis"));
        flags.put("libvmaf", List.of("--enable-libvmaf"));
        flags.put("vaapi", List.of("--enable-vaapi"));
        flags.put("qsv", List.of("--enable-libvpl"));
        flags.put("nvenc", List.of("--enable-nvenc", "--enable-nvdec"));
        return java.util.Collections.unmodifiableMap(flags);
    }

    private void verifyEncoders(Path ffmpeg, Path temp, List<String> features,
                                BuildLog log, BooleanSupplier cancelled) throws IOException {
        verifyOneFrame(ffmpeg, temp, cancelled);
        Map<String, List<String>> tests = Map.of(
                "libx264", List.of("-c:v", "libx264", "-preset", "ultrafast"),
                "libx265", List.of("-c:v", "libx265", "-preset", "ultrafast"),
                "libvpx", List.of("-c:v", "libvpx-vp9", "-deadline", "realtime", "-cpu-used", "8"),
                "libaom", List.of("-c:v", "libaom-av1", "-cpu-used", "8"),
                "libsvtav1", List.of("-c:v", "libsvtav1", "-preset", "12"));
        for (String feature : features) {
            List<String> encoderArgs = tests.get(feature);
            if (encoderArgs == null) {
                continue;
            }
            List<String> command = new ArrayList<>(List.of(
                    ffmpeg.toString(), "-hide_banner", "-v", "error", "-threads", "1",
                    "-f", "lavfi", "-i", "color=c=black:s=64x64:d=0.04",
                    "-frames:v", "1"));
            command.addAll(encoderArgs);
            command.addAll(List.of("-f", "null", "-"));
            log.line("checking " + feature);
            RunResult result = runCaptured(command, temp, Duration.ofSeconds(60), cancelled);
            if (result.exitCode() != 0) {
                throw new IOException("ffmpeg_full_build_failed: " + feature
                        + " failed its one-frame encode: " + tail(result.output()));
            }
        }
    }

    private void verifyOneFrame(Path ffmpeg, Path temp, BooleanSupplier cancelled) throws IOException {
        ensureNotCancelled(cancelled);
        List<String> command = List.of(ffmpeg.toString(),
                "-hide_banner", "-v", "error",
                "-f", "lavfi", "-i", "color=c=black:s=64x64:d=0.04",
                "-frames:v", "1", "-c:v", "mpeg4", "-f", "null", "-");
        RunResult result = runCaptured(command, temp, Duration.ofSeconds(20), cancelled);
        if (result.exitCode() != 0) {
            throw new IOException("ffmpeg_full_build_failed: the lavfi encode failed with code "
                    + result.exitCode() + tail(result.output()));
        }
    }

    // --------------------------------------------------------------- helpers --

    private Map<String, String> environment(FfmpegToolchainManager.Toolchain toolchain,
                                            Path prefix, ToolBins bins, Path temp) {
        Map<String, String> environment = new LinkedHashMap<>(toolchain.environment());
        String separator = OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS ? ";" : ":";
        StringBuilder path = new StringBuilder();
        if (bins.cmake() != null) {
            path.append(bins.cmake().getParent()).append(separator);
        }
        if (bins.nasm() != null) {
            path.append(bins.nasm().getParent()).append(separator);
        }
        path.append(prefix.resolve("bin")).append(separator);
        String oldPath = environment.get("PATH");
        if (oldPath == null || oldPath.isBlank()) {
            oldPath = System.getenv("PATH");
        }
        if (oldPath != null && !oldPath.isBlank()) {
            path.append(oldPath);
        }
        environment.put("PATH", path.toString());
        List<String> pkgConfig = new ArrayList<>();
        pkgConfig.add(prefix.resolve("lib").resolve("pkgconfig").toString());
        pkgConfig.add(prefix.resolve("lib64").resolve("pkgconfig").toString());
        pkgConfig.add(prefix.resolve("share").resolve("pkgconfig").toString());
        String oldPkgConfig = environment.get("PKG_CONFIG_PATH");
        if (oldPkgConfig != null && !oldPkgConfig.isBlank()) {
            pkgConfig.add(oldPkgConfig);
        }
        environment.put("PKG_CONFIG_PATH", String.join(separator, pkgConfig));
        environment.put("CMAKE_PREFIX_PATH", prefix.toString());
        environment.put("CFLAGS", "-O2 -fPIC -I" + prefix + "/include");
        environment.put("CXXFLAGS", "-O2 -fPIC -I" + prefix + "/include");
        environment.put("LDFLAGS", "-L" + prefix + "/lib");
        environment.put("LC_ALL", "C");
        environment.put("LANG", "C");
        environment.put("TMPDIR", temp.toString());
        return environment;
    }

    private Path download(FfmpegFullBuildCatalog.Dependency dependency, Path directory,
                          double start, double end, FfmpegRuntimeManager.Progress progress,
                          BooleanSupplier cancelled) throws IOException {
        return downloadRaw(dependency.id(), dependency.url(), dependency.sha256(),
                dependency.archive(), directory, start, end, progress, cancelled,
                dependency.mirrors());
    }

    private Path downloadRaw(String id, String url, String sha256, String archive,
                             Path directory, double start, double end,
                             FfmpegRuntimeManager.Progress progress,
                             BooleanSupplier cancelled, List<String> mirrors)
            throws IOException {
        ensureNotCancelled(cancelled);
        Path target = directory.resolve(safe(id) + suffix(archive));
        IOException failure = null;
        for (String candidate : FfmpegDownload.candidates(sourceMirror.get(), url, mirrors)) {
            FfmpegInstaller.validateUrl(candidate);
            try {
                FfmpegDownload.download(candidate, target, DOWNLOAD_TIMEOUT,
                        (message, fraction) -> progress.stage(message,
                                start + (end - start) * fraction), cancelled);
                FfmpegDownload.verifySha(target, sha256);
                return target;
            } catch (IOException e) {
                failure = e;
                Files.deleteIfExists(target);
                LOG.info("ffmpeg full build: download from " + candidate + " failed: "
                        + e.getMessage());
            }
        }
        throw new IOException("ffmpeg_download_failed: every source for " + id
                + " failed" + (failure == null ? "" : ": " + failure.getMessage()), failure);
    }

    private void run(List<String> command, Path cwd, Map<String, String> environment,
                     Path temp, Duration timeout, String phase, BuildLog log,
                     BooleanSupplier cancelled) throws IOException {
        ensureNotCancelled(cancelled);
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
        env.putAll(environment);
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
            throw new IOException("ffmpeg_full_build_failed: could not start " + phase + ": "
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
        }, "ffmpeg-full-build-output");
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
                    throw new IOException("ffmpeg_full_build_failed: " + phase
                            + " timed out after " + timeout.toMinutes() + " min");
                }
            }
            try {
                reader.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (process.exitValue() != 0) {
                throw new IOException("ffmpeg_full_build_failed: " + phase
                        + " exited with code " + process.exitValue() + tail(log.tail()));
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
        }, "ffmpeg-full-build-probe");
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
                    throw new IOException("ffmpeg_full_build_failed: the built binary timed out");
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

    private static boolean hasX86Asm(ToolBins bins, FfmpegToolchainManager.Toolchain toolchain) {
        return bins.nasm() != null || toolchain.hasX86Asm();
    }

    private void copyBinaries(Path prefix, Path staging) throws IOException {
        Path sourceBin = prefix.resolve("bin");
        Path targetBin = staging.resolve("bin");
        Files.createDirectories(targetBin);
        try (Stream<Path> stream = Files.list(sourceBin)) {
            for (Path file : stream.toList()) {
                Files.copy(file, targetBin.resolve(file.getFileName()),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        }
        FfmpegRuntimeManager.makeExecutable(
                targetBin.resolve(FfmpegRuntimeManager.exe("ffmpeg")));
        FfmpegRuntimeManager.makeExecutable(
                targetBin.resolve(FfmpegRuntimeManager.exe("ffprobe")));
    }

    private static String notes(FfmpegFullBuildCatalog.Entry entry, ToolBins bins,
                                List<String> probed) {
        StringBuilder notes = new StringBuilder("full static source build of ")
                .append(entry.ffmpegVersion()).append(" from ").append(entry.ffmpegUrl())
                .append("; profile ").append(entry.profile())
                .append("; cmake ").append(bins.cmakeVersion())
                .append("; nasm ").append(bins.nasmVersion())
                .append("; dependencies:");
        for (FfmpegFullBuildCatalog.Dependency dependency : entry.dependencies()) {
            if ("codec".equals(dependency.kind())) {
                notes.append(' ').append(dependency.id()).append('-').append(dependency.version());
            }
        }
        notes.append("; probed ").append(probed.size()).append('/')
                .append(entry.features().size()).append(" declared features");
        return notes.toString();
    }

    private void validate(FfmpegFullBuildCatalog.Entry entry, Path versionDir) throws IOException {
        if (entry == null) {
            throw new IOException("ffmpeg_no_candidate: unknown full build");
        }
        Path normalized = versionDir.toAbsolutePath().normalize();
        if (!normalized.getParent().equals(toolsDir) || normalized.equals(toolsDir)) {
            throw new IOException("ffmpeg_artifact_invalid: the version directory must be a"
                    + " direct child of " + toolsDir);
        }
        if (Files.isRegularFile(normalized.resolve("runtime.json"))) {
            throw new IOException("ffmpeg_already_installed: " + normalized.getFileName());
        }
    }

    private void checkDisk(long required) throws IOException {
        if (required <= 0) {
            return;
        }
        try {
            long usable = Files.getFileStore(toolsDir).getUsableSpace();
            if (usable < required) {
                throw new IOException("ffmpeg_build_disk_full: a full build needs about "
                        + (required / (1024 * 1024 * 1024)) + " GiB free, " + toolsDir
                        + " has " + (usable / (1024 * 1024)) + " MiB");
            }
        } catch (java.nio.file.FileSystemException e) {
            LOG.info("ffmpeg full build: free-space check skipped for " + toolsDir + ": "
                    + e.getMessage());
        }
    }

    private static void ensureNotCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("cancelled");
        }
    }

    private static @Nullable Path find(Path root, Predicate<Path> predicate) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile).filter(predicate).findFirst().orElse(null);
        }
    }

    /// The shallowest file with this name. `Files.walk` visits a directory's subtree
    /// before its later siblings, so a plain "first match" can return a nested
    /// `CMakeLists.txt` (e.g. SVT-AV1's gstreamer-plugin) instead of the project root.
    private static @Nullable Path findShallowest(Path root, String name) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals(name))
                    .min(java.util.Comparator.comparingInt(path -> root.relativize(path).getNameCount()))
                    .orElse(null);
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

    private static String suffix(String archive) {
        String lower = archive == null ? "" : archive.toLowerCase(Locale.ROOT);
        String normalized = lower.startsWith(".") ? lower.substring(1) : lower;
        if (normalized.equals("tar.xz") || normalized.equals("txz")
                || lower.endsWith(".tar.xz") || lower.endsWith(".txz")) {
            return ".tar.xz";
        }
        if (normalized.equals("tar.gz") || normalized.equals("tgz")
                || lower.endsWith(".tar.gz") || lower.endsWith(".tgz")) {
            return ".tar.gz";
        }
        if (normalized.equals("zip") || lower.endsWith(".zip")) {
            return ".zip";
        }
        return ".tar";
    }

    private static String safe(String value) {
        return value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9._+-]", "_");
    }

    private static String tail(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String[] lines = value.split("\\R");
        int from = Math.max(0, lines.length - 12);
        StringBuilder result = new StringBuilder(": ");
        for (int i = from; i < lines.length; i++) {
            if (result.length() > 2) {
                result.append(" | ");
            }
            result.append(lines[i]);
        }
        return result.toString();
    }

    private record RunResult(int exitCode, String output) {
    }

    /// Append-only build log with a bounded tail for error messages. `stage` also
    /// updates the install job's progress, so the page sees the same phases the
    /// log records.
    private static final class BuildLog {
        private final Path file;
        private final FfmpegRuntimeManager.Progress progress;
        private final Deque<String> tail = new ArrayDeque<>();
        private BufferedWriter writer;

        BuildLog(Path file, FfmpegRuntimeManager.Progress progress) {
            this.file = file;
            this.progress = progress;
            try {
                Files.createDirectories(file.getParent());
                writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException e) {
                LOG.warning("ffmpeg full build: could not open log " + file + ": " + e.getMessage());
                writer = null;
            }
        }

        FfmpegRuntimeManager.Progress progress() {
            return progress;
        }

        synchronized void stage(String message, double fraction) {
            progress.stage(message, fraction);
            line("== " + message);
        }

        synchronized void line(String line) {
            tail.addLast(line);
            while (tail.size() > 80) {
                tail.removeFirst();
            }
            if (writer == null) {
                return;
            }
            try {
                writer.write(line);
                writer.newLine();
                writer.flush();
            } catch (IOException ignored) {
            }
        }

        synchronized String tail() {
            return String.join("\n", tail);
        }

        Path logFile() {
            return writer == null ? null : file;
        }

        synchronized void close() {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignored) {
                }
                writer = null;
            }
        }
    }
}