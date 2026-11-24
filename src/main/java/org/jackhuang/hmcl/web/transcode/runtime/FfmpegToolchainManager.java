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
import org.jackhuang.hmcl.util.platform.Architecture;
import org.jackhuang.hmcl.util.platform.Executables;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jackhuang.hmcl.util.platform.Privileges;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
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
import java.util.stream.Stream;

/// Detects the C toolchain a source build needs, and — on Windows only —
/// installs one fixed portable MinGW when the host has none.
///
/// The default path is deliberately boring: use whatever `cc`/`make`/`pkg-config`
/// the operator already has, report exactly what is missing, and never run a
/// system package manager. The only thing this class downloads is w64devkit, at a
/// pinned HTTPS URL with a pinned SHA-256, because a Windows machine without
/// Visual Studio otherwise has no way to build ffmpeg at all.
///
/// Nothing here changes a system-wide setting. The resolved `PATH`/`CC`/`CXX`/
/// `MAKE` values are handed to one child process by [FfmpegBuildKit].
@NotNullByDefault
public final class FfmpegToolchainManager {

    /// The portable MinGW build. It is a version, never `latest`: the same panel
    /// must ask GitHub for the same bytes tomorrow.
    public static final String WINDOWS_PORTABLE_VERSION = "2.11.0";
    public static final String WINDOWS_PORTABLE_URL =
            "https://github.com/skeeto/w64devkit/releases/download/v2.11.0/w64devkit-x64-2.11.0.7z.exe";
    public static final String WINDOWS_PORTABLE_SHA256 =
            "54c2bb9962e8a30c8270e34c5efc3d5fee579f53087fa7d435b95fbba2af7b2b";

    private static final long CACHE_MILLIS = 60_000;
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(4);
    private static final Duration EXTRACT_TIMEOUT = Duration.ofMinutes(10);

    private record DepSpec(String feature, String module, String packageName) {
    }

    /// The development packages the Linux recipe knows how to use, in the order
    /// `configure` would be given them. Package names are Debian/Ubuntu; the
    /// message says so rather than pretending every distribution agrees.
    private static final List<DepSpec> DEPENDENCIES = List.of(
            new DepSpec("libx264", "x264", "libx264-dev"),
            new DepSpec("libx265", "x265", "libx265-dev"),
            new DepSpec("libvpx", "vpx", "libvpx-dev"),
            new DepSpec("libaom", "aom", "libaom-dev"),
            new DepSpec("libsvtav1", "SvtAv1Enc", "libsvtav1-dev"),
            new DepSpec("libvmaf", "libvmaf", "libvmaf-dev"),
            new DepSpec("vaapi", "libva", "libva-dev"),
            new DepSpec("qsv", "vpl", "libvpl-dev"),
            new DepSpec("nvenc", "ffnvcodec", "nv-codec-headers"));

    public record Tool(String name, @Nullable String path, String version) {
        public boolean present() {
            return path != null && !path.isBlank();
        }
    }

    public record Dependency(String feature, String module, String packageName,
                             boolean present, String version) {
    }

    /// One resolved toolchain. `binDir` is non-null only for a portable toolchain
    /// whose `bin/` has to be prepended to `PATH`; a system toolchain leaves the
    /// inherited `PATH` alone.
    public record Toolchain(
            String os,
            String arch,
            @Nullable Path binDir,
            boolean usable,
            boolean portable,
            List<Tool> tools,
            List<Dependency> dependencies,
            List<String> missing,
            Map<String, String> environment,
            String detail) {

        public Toolchain {
            tools = List.copyOf(tools);
            dependencies = List.copyOf(dependencies);
            missing = List.copyOf(missing);
            environment = Map.copyOf(environment);
            detail = detail == null ? "" : detail;
        }

        public boolean has(String name) {
            return tools.stream().anyMatch(tool -> tool.name().equals(name) && tool.present());
        }

        public @Nullable String pathOf(String name) {
            for (Tool tool : tools) {
                if (tool.name().equals(name) && tool.present()) {
                    return tool.path();
                }
            }
            return null;
        }

        /// Whether the assembler fast path (`nasm`/`yasm`) is available. Without
        /// it, x86 builds pass `--disable-x86asm` instead of failing.
        public boolean hasX86Asm() {
            return has("nasm") || has("yasm");
        }

        /// External features whose development package was detected. The build
        /// may still fail to link one of them; the probe after `make install` is
        /// what turns this into `featuresProbed`.
        public Set<String> features() {
            Set<String> result = new LinkedHashSet<>();
            for (Dependency dependency : dependencies) {
                if (dependency.present()) {
                    result.add(dependency.feature());
                }
            }
            return Set.copyOf(result);
        }

        /// The error text the settings page shows before `configure` runs. It
        /// names the missing program/package; a bare "configure failed" is the
        /// failure mode this class exists to prevent.
        public String missingMessage() {
            List<String> required = new ArrayList<>();
            List<String> packages = new ArrayList<>();
            for (String item : missing) {
                if (item.contains(":")) {
                    packages.add(item);
                } else {
                    required.add(item);
                }
            }
            StringBuilder message = new StringBuilder("ffmpeg_build_toolchain_missing:");
            if (!required.isEmpty()) {
                message.append(" missing ").append(String.join(", ", required)).append(".");
            }
            if (!packages.isEmpty()) {
                message.append(" development packages: ").append(String.join(", ", packages)).append(".");
            }
            if (!detail.isBlank()) {
                message.append(' ').append(detail);
            }
            return message.toString();
        }

        public String describe() {
            return os + "/" + arch + (portable ? " (portable MinGW " + WINDOWS_PORTABLE_VERSION + ")"
                    : " (system toolchain)");
        }
    }

    private record Cached(long at, Toolchain toolchain) {
    }

    private record RunResult(int exitCode, String output) {
    }

    private final Path toolchainsDir;
    private final Object lock = new Object();
    private volatile @Nullable Cached cache;

    public FfmpegToolchainManager(Path toolchainsDir) {
        this.toolchainsDir = toolchainsDir.toAbsolutePath().normalize();
    }

    public Path toolchainsDir() {
        return toolchainsDir;
    }

    /// The cached answer. `pkg-config` probes are a handful of short-lived
    /// processes; repeating them on every settings-page request is noise, not
    /// information. [#refresh] is for the moment after an install.
    public Toolchain detect() {
        Cached cached = cache;
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.at < CACHE_MILLIS) {
            return cached.toolchain();
        }
        return refresh();
    }

    public Toolchain refresh() {
        Toolchain toolchain = detectUncached();
        cache = new Cached(System.currentTimeMillis(), toolchain);
        return toolchain;
    }

    /// The toolchain a build should use. On Windows, a missing system toolchain
    /// triggers the pinned portable MinGW download; elsewhere the caller gets the
    /// detected answer and decides whether the missing pieces are fatal.
    public Toolchain resolveForBuild(FfmpegRuntimeManager.Progress progress,
                                     BooleanSupplier cancelled) throws IOException {
        Toolchain current = detect();
        if (current.usable()) {
            return current;
        }
        if (OperatingSystem.CURRENT_OS != OperatingSystem.WINDOWS) {
            return current;
        }
        Toolchain portable = portableToolchain();
        if (portable.usable()) {
            return portable;
        }
        installPortable(progress, cancelled);
        return refresh();
    }

    /// Downloads and extracts the pinned w64devkit into
    /// `<data>/toolchains/mingw/<version>/`. Windows-only by design: no other
    /// platform needs a bundled compiler, and offering one would create a second
    /// code-execution surface for no benefit.
    public Toolchain installPortable(FfmpegRuntimeManager.Progress progress,
                                     BooleanSupplier cancelled) throws IOException {
        synchronized (lock) {
            if (OperatingSystem.CURRENT_OS != OperatingSystem.WINDOWS) {
                throw new IOException("ffmpeg_unsupported_platform: portable MinGW is Windows-only");
            }
            if (Architecture.SYSTEM_ARCH != Architecture.X86_64) {
                throw new IOException("ffmpeg_unsupported_platform: the portable MinGW build is x64-only"
                        + " (host is " + Architecture.SYSTEM_ARCH + ")");
            }
            Toolchain existing = portableToolchain();
            if (existing.usable()) {
                return refresh();
            }

            Path parent = toolchainsDir.resolve("mingw");
            Path target = parent.resolve(WINDOWS_PORTABLE_VERSION);
            Files.createDirectories(parent);
            Privileges.prepareAgentDirectory(parent, Privileges.AGENT_SHARED_DIRECTORY);

            Path download = toolchainsDir.resolve(".mingw-" + UUID.randomUUID() + ".7z.exe");
            Path extract = toolchainsDir.resolve(".mingw-extract-" + UUID.randomUUID());
            boolean installed = false;
            try {
                Files.createDirectories(extract);
                Privileges.handToAgent(extract, Privileges.AGENT_SHARED_DIRECTORY);
                progress.stage("downloading portable MinGW " + WINDOWS_PORTABLE_VERSION, 0.05);
                FfmpegDownload.download(WINDOWS_PORTABLE_URL, download, Duration.ofMinutes(10),
                        progress, cancelled);
                progress.stage("verifying the MinGW SHA-256", 0.55);
                FfmpegDownload.verifySha(download, WINDOWS_PORTABLE_SHA256);

                progress.stage("extracting portable MinGW", 0.6);
                RunResult result = run(List.of(download.toString(), "-y", "-o" + extract),
                        null, EXTRACT_TIMEOUT, cancelled);
                if (result.exitCode() != 0) {
                    throw new IOException("the w64devkit self-extractor failed with code "
                            + result.exitCode() + ": " + tail(result.output()));
                }

                Path bin = findBin(extract);
                if (bin == null) {
                    throw new IOException("the w64devkit archive contains no gcc.exe");
                }
                Path root = bin.getParent();
                if (target.toFile().exists()) {
                    FfmpegArchive.deleteTree(target);
                }
                try {
                    Files.move(root, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException e) {
                    copyTree(root, target);
                    FfmpegArchive.deleteTree(root);
                }
                installed = true;
            } finally {
                Files.deleteIfExists(download);
                FfmpegArchive.deleteTree(extract);
                if (!installed) {
                    // A half-extracted directory must never be mistaken for a
                    // usable toolchain.
                    FfmpegArchive.deleteTree(target);
                }
            }
            cache = null;
            Toolchain toolchain = refresh();
            if (!toolchain.usable()) {
                cache = null;
                FfmpegArchive.deleteTree(target);
                throw new IOException(toolchain.missingMessage());
            }
            return toolchain;
        }
    }

    private Toolchain detectUncached() {
        if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
            Toolchain system = windowsToolchain(null);
            if (system.usable()) {
                return system;
            }
            Toolchain portable = portableToolchain();
            if (portable.usable()) {
                return portable;
            }
            return new Toolchain(system.os(), system.arch(), system.binDir(), false, false,
                    system.tools(), system.dependencies(), system.missing(),
                    system.environment(),
                    "A portable MinGW " + WINDOWS_PORTABLE_VERSION
                            + " can be downloaded when the build starts.");
        }
        return posixToolchain();
    }

    private Toolchain posixToolchain() {
        List<Tool> tools = new ArrayList<>();
        tools.add(resolve("cc", "cc", "gcc", "clang"));
        tools.add(resolve("c++", "c++", "g++", "clang++"));
        tools.add(resolve("make", "make", "gmake"));
        Tool pkgConfig = resolve("pkg-config", "pkg-config", "pkgconf");
        tools.add(pkgConfig);
        tools.add(resolve("nasm", "nasm"));
        tools.add(resolve("yasm", "yasm"));

        List<String> missing = new ArrayList<>();
        for (String required : List.of("cc", "c++", "make")) {
            if (!has(tools, required)) {
                missing.add(required);
            }
        }
        boolean usable = has(tools, "cc") && has(tools, "c++") && has(tools, "make");
        List<Dependency> dependencies = new ArrayList<>();
        if (pkgConfig.present()) {
            String pkg = pkgConfig.path();
            for (DepSpec spec : DEPENDENCIES) {
                boolean present = pkgConfigExists(pkg, spec.module());
                String version = present ? pkgConfigVersion(pkg, spec.module()) : "";
                dependencies.add(new Dependency(spec.feature(), spec.module(), spec.packageName(),
                        present, version));
                if (!present) {
                    missing.add(spec.packageName());
                }
            }
        } else {
            missing.add("pkg-config");
            for (DepSpec spec : DEPENDENCIES) {
                dependencies.add(new Dependency(spec.feature(), spec.module(), spec.packageName(),
                        false, ""));
                missing.add(spec.packageName());
            }
        }
        if (!has(tools, "nasm") && !has(tools, "yasm")) {
            // Optional: x86 asm is a speed-up, not a correctness requirement.
            missing.add("nasm");
        }
        Map<String, String> environment = new LinkedHashMap<>();
        putIf(environment, "CC", pathOf(tools, "cc"));
        putIf(environment, "CXX", pathOf(tools, "c++"));
        putIf(environment, "MAKE", pathOf(tools, "make"));
        putIf(environment, "PKG_CONFIG", pathOf(tools, "pkg-config"));
        String detail = "Install the build tools with the distribution package manager"
                + " (Debian/Ubuntu: build-essential pkg-config nasm); the panel never runs sudo.";
        return new Toolchain(hostOs(), hostArch(), null, usable, false, tools, dependencies,
                missing, environment, detail);
    }

    private Toolchain windowsToolchain(@Nullable Path portableRoot) {
        Path bin = portableRoot;
        List<Tool> tools = new ArrayList<>();
        if (bin != null) {
            tools.add(toolAt("gcc", bin.resolve("gcc.exe")));
            tools.add(toolAt("g++", bin.resolve("g++.exe")));
            tools.add(toolAt("make", firstExisting(bin.resolve("mingw32-make.exe"), bin.resolve("make.exe"))));
            tools.add(toolAt("ar", bin.resolve("ar.exe")));
            tools.add(toolAt("ld", bin.resolve("ld.exe")));
            tools.add(toolAt("pkg-config", firstExisting(bin.resolve("pkg-config.exe"), bin.resolve("pkgconf.exe"))));
            tools.add(toolAt("sh", bin.resolve("sh.exe")));
            tools.add(toolAt("objdump", bin.resolve("objdump.exe")));
            tools.add(toolAt("nasm", bin.resolve("nasm.exe")));
            tools.add(toolAt("yasm", bin.resolve("yasm.exe")));
        } else {
            tools.add(resolve("gcc", "gcc", "x86_64-w64-mingw32-gcc"));
            tools.add(resolve("g++", "g++", "x86_64-w64-mingw32-g++"));
            tools.add(resolve("make", "mingw32-make", "make"));
            tools.add(resolve("ar", "ar", "x86_64-w64-mingw32-ar"));
            tools.add(resolve("ld", "ld", "x86_64-w64-mingw32-ld"));
            tools.add(resolve("pkg-config", "pkg-config", "pkgconf"));
            tools.add(resolve("sh", "sh"));
            tools.add(resolve("objdump", "objdump"));
            tools.add(resolve("nasm", "nasm"));
            tools.add(resolve("yasm", "yasm"));
        }

        List<String> missing = new ArrayList<>();
        for (String required : List.of("gcc", "g++", "make", "ar", "ld")) {
            if (!has(tools, required)) {
                missing.add(required);
            }
        }
        boolean usable = has(tools, "gcc") && has(tools, "g++") && has(tools, "make")
                && has(tools, "ar") && has(tools, "ld");
        if (!has(tools, "pkg-config")) {
            missing.add("pkg-config");
        }
        if (!has(tools, "nasm") && !has(tools, "yasm")) {
            missing.add("nasm");
        }
        List<Dependency> dependencies = new ArrayList<>();
        String pkgConfig = pathOf(tools, "pkg-config");
        if (pkgConfig != null) {
            for (DepSpec spec : DEPENDENCIES) {
                boolean present = pkgConfigExists(pkgConfig, spec.module());
                dependencies.add(new Dependency(spec.feature(), spec.module(), spec.packageName(),
                        present, present ? pkgConfigVersion(pkgConfig, spec.module()) : ""));
            }
        } else {
            for (DepSpec spec : DEPENDENCIES) {
                dependencies.add(new Dependency(spec.feature(), spec.module(), spec.packageName(),
                        false, ""));
            }
        }

        Map<String, String> environment = new LinkedHashMap<>();
        if (bin != null) {
            String path = System.getenv("PATH");
            environment.put("PATH", bin + (path == null || path.isBlank()
                    ? "" : java.io.File.pathSeparator + path));
        }
        putIf(environment, "CC", pathOf(tools, "gcc"));
        putIf(environment, "CXX", pathOf(tools, "g++"));
        putIf(environment, "MAKE", pathOf(tools, "make"));
        putIf(environment, "AR", pathOf(tools, "ar"));
        putIf(environment, "LD", pathOf(tools, "ld"));
        putIf(environment, "PKG_CONFIG", pathOf(tools, "pkg-config"));
        String detail = bin != null
                ? "Portable MinGW " + WINDOWS_PORTABLE_VERSION + " is installed under "
                + bin.getParent() + "."
                : "No MinGW toolchain was found on PATH; the pinned portable MinGW can be downloaded on demand."
                + " External codec libraries are usually absent, so a Windows source build is a minimal build.";
        return new Toolchain(hostOs(), hostArch(), bin, usable, bin != null, tools, dependencies,
                missing, environment, detail);
    }

    private Toolchain portableToolchain() {
        Path root = toolchainsDir.resolve("mingw").resolve(WINDOWS_PORTABLE_VERSION);
        if (!Files.isDirectory(root)) {
            return windowsToolchain(null);
        }
        Path bin = findBin(root);
        if (bin == null) {
            return windowsToolchain(null);
        }
        return windowsToolchain(bin);
    }

    private static @Nullable Path findBin(Path root) {
        try (Stream<Path> stream = Files.walk(root, 5)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals("gcc.exe"))
                    .map(Path::getParent)
                    .filter(parent -> parent != null && "bin".equals(parent.getFileName().toString()))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static Tool resolve(String name, String... candidates) {
        for (String candidate : candidates) {
            Optional<Path> path = Executables.resolve(candidate);
            if (path.isPresent()) {
                return new Tool(name, path.get().toString(), versionOf(path.get()));
            }
        }
        return new Tool(name, null, "");
    }

    private static Tool toolAt(String name, @Nullable Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return new Tool(name, null, "");
        }
        return new Tool(name, path.toString(), versionOf(path));
    }

    private static @Nullable Path firstExisting(Path... paths) {
        for (Path path : paths) {
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        return null;
    }

    private static String versionOf(Path executable) {
        try {
            RunResult result = run(List.of(executable.toString(), "--version"), null,
                    PROBE_TIMEOUT, () -> false);
            if (result.exitCode() != 0) {
                return "";
            }
            return result.output().lines().map(String::trim)
                    .filter(line -> !line.isEmpty()).findFirst().orElse("");
        } catch (IOException e) {
            return "";
        }
    }

    private static boolean pkgConfigExists(String pkgConfig, String module) {
        try {
            return run(List.of(pkgConfig, "--exists", module), null, PROBE_TIMEOUT, () -> false)
                    .exitCode() == 0;
        } catch (IOException e) {
            return false;
        }
    }

    private static String pkgConfigVersion(String pkgConfig, String module) {
        try {
            RunResult result = run(List.of(pkgConfig, "--modversion", module), null,
                    PROBE_TIMEOUT, () -> false);
            return result.exitCode() == 0 ? result.output().trim() : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static boolean has(List<Tool> tools, String name) {
        return tools.stream().anyMatch(tool -> tool.name().equals(name) && tool.present());
    }

    private static @Nullable String pathOf(List<Tool> tools, String name) {
        for (Tool tool : tools) {
            if (tool.name().equals(name) && tool.present()) {
                return tool.path();
            }
        }
        return null;
    }

    private static void putIf(Map<String, String> map, String key, @Nullable String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }

    private static String hostOs() {
        return OperatingSystem.CURRENT_OS.name().toLowerCase(Locale.ROOT);
    }

    private static String hostArch() {
        return switch (Architecture.SYSTEM_ARCH) {
            case X86_64 -> "x64";
            case ARM64 -> "arm64";
            case X86 -> "x86";
            case ARM32 -> "arm32";
            default -> Architecture.SYSTEM_ARCH.name().toLowerCase(Locale.ROOT);
        };
    }

    /// Runs one probe/installer process. Output is captured rather than streamed:
    /// every caller is short, and the tail is what an error message needs. The
    /// process runs as the agent inside the container, with the same kill-tree
    /// semantics as a build.
    private static RunResult run(List<String> command, @Nullable Path directory,
                                 Duration timeout, BooleanSupplier cancelled) throws IOException {
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("cancelled");
        }
        ProcessBuilder builder = new ProcessBuilder(Privileges.asAgent(command)).redirectErrorStream(true);
        if (directory != null) {
            builder.directory(directory.toFile());
        }
        Process process = builder.start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                in.transferTo(output);
            } catch (IOException ignored) {
            }
        }, "ffmpeg-toolchain-reader");
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
                    throw new IOException("command timed out: " + String.join(" ", command));
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

    private static String tail(String output) {
        String trimmed = output == null ? "" : output.trim();
        return trimmed.length() <= 400 ? trimmed : "…" + trimmed.substring(trimmed.length() - 400);
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
}