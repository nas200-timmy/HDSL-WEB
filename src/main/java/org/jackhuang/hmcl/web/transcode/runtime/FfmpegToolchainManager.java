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

import org.jackhuang.hmcl.dsh.BuildToolchainManager;
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
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/// Detects the C toolchain an ffmpeg source build needs.
///
/// The compiler half is delegated to [BuildToolchainManager]: on Windows that
/// is the pinned w64devkit, on Linux the host's `cc`/`make` or the portable
/// Zig/make/pkgconf combination. This class adds the ffmpeg-specific half —
/// which development packages `configure` can enable, and what the settings
/// page should say when they are absent.
///
/// Nothing here changes a system-wide setting. The resolved `PATH`/`CC`/`CXX`/
/// `MAKE` values are handed to one child process by [FfmpegBuildKit].
@NotNullByDefault
public final class FfmpegToolchainManager {

    /// The pinned w64devkit, as the bootstrap catalogue names it.
    public static String windowsPortableVersion() {
        return BuildToolchainManager.windowsPortableVersion();
    }

    public static String windowsPortableUrl() {
        return BuildToolchainManager.windowsPortableUrl();
    }

    public static String windowsPortableSha256() {
        return BuildToolchainManager.windowsPortableSha256();
    }

    private static final long CACHE_MILLIS = 60_000;
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(4);

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

    /// One resolved toolchain. `binDir` is non-null only for a portable
    /// toolchain whose `bin/` has to be prepended to `PATH`; a system toolchain
    /// leaves the inherited `PATH` alone.
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
            if (!portable) {
                return os + "/" + arch + " (system toolchain)";
            }
            String label = OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS
                    ? "portable MinGW w64devkit " + windowsPortableVersion()
                    : "portable Linux build toolchain (Zig + GNU make)";
            return os + "/" + arch + " (" + label + ")";
        }
    }

    private record Cached(long at, Toolchain toolchain) {
    }

    private record RunResult(int exitCode, String output) {
    }

    private final Path toolchainsDir;
    private final BuildToolchainManager builds;
    private final Object lock = new Object();
    private volatile @Nullable Cached cache;

    public FfmpegToolchainManager(Path toolchainsDir) {
        this(toolchainsDir, new BuildToolchainManager(
                toolchainsDir.toAbsolutePath().normalize().resolve("build")));
    }

    /// Test seam: the general toolchain manager named explicitly, so a test can
    /// drive the ffmpeg view with a fake system `PATH` or a prepared bundle.
    FfmpegToolchainManager(Path toolchainsDir, BuildToolchainManager builds) {
        this.toolchainsDir = toolchainsDir.toAbsolutePath().normalize();
        this.builds = builds;
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
        Toolchain toolchain = ffmpegToolchain(builds.detect());
        cache = new Cached(System.currentTimeMillis(), toolchain);
        return toolchain;
    }

    /// The toolchain a build should use. A missing system toolchain triggers
    /// the portable install — w64devkit on Windows, Zig + make + pkgconf on
    /// Linux x64 — and the caller gets the resolved answer.
    public Toolchain resolveForBuild(FfmpegRuntimeManager.Progress progress,
                                     BooleanSupplier cancelled) throws IOException {
        Toolchain current = detect();
        if (current.usable()) {
            return current;
        }
        try {
            BuildToolchainManager.Toolchain base = builds.detect();
            if (!base.usable()) {
                base = builds.ensureForBuild(
                        (message, fraction) -> progress.stage(message, fraction * 0.10),
                        cancelled);
            } else if (!base.portable()
                    && OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
                // The host has a compiler but ffmpeg's configure also needs a
                // POSIX shell; w64devkit is the pinned answer.
                builds.installPortable(
                        (message, fraction) -> progress.stage(message, fraction * 0.10),
                        cancelled);
                base = builds.detect();
            }
            cache = null;
            Toolchain resolved = ffmpegToolchain(base);
            cache = new Cached(System.currentTimeMillis(), resolved);
            if (!resolved.usable()) {
                throw new IOException(resolved.missingMessage());
            }
            return resolved;
        } catch (IOException e) {
            throw new IOException(ffmpegMessage(e), e);
        }
    }

    /// Installs the portable build toolchain and returns the ffmpeg view of it.
    ///
    /// @throws IOException when this platform has no pinned bundle or the
    ///                     install failed
    public Toolchain installPortable(FfmpegRuntimeManager.Progress progress,
                                     BooleanSupplier cancelled) throws IOException {
        if (OperatingSystem.CURRENT_OS != OperatingSystem.WINDOWS
                && OperatingSystem.CURRENT_OS != OperatingSystem.LINUX) {
            throw new IOException("ffmpeg_unsupported_platform: the portable build toolchain is"
                    + " pinned for Windows and Linux only");
        }
        // Linux arm64 joined when its pins were added; Windows stays x64,
        // where w64devkit is the only supply chain with a pinned digest.
        boolean supported = Architecture.SYSTEM_ARCH == Architecture.X86_64
                || (OperatingSystem.CURRENT_OS == OperatingSystem.LINUX
                && Architecture.SYSTEM_ARCH == Architecture.ARM64);
        if (!supported) {
            throw new IOException("ffmpeg_unsupported_platform: the portable build toolchain is"
                    + " pinned for x64 and Linux arm64 (host is "
                    + Architecture.SYSTEM_ARCH + ")");
        }
        synchronized (lock) {
            try {
                builds.installPortable((message, fraction) -> progress.stage(message, fraction),
                        cancelled);
            } catch (IOException e) {
                throw new IOException(ffmpegMessage(e), e);
            }
            cache = null;
            Toolchain toolchain = refresh();
            if (!toolchain.usable()) {
                cache = null;
                throw new IOException(toolchain.missingMessage());
            }
            return toolchain;
        }
    }

    // ---------------------------------------------------------------- resolve --

    /// Adds ffmpeg's view on top of a [BuildToolchainManager] answer: the
    /// development packages `configure` may enable, and ffmpeg's own minimum
    /// set (`sh` on Windows, `ar`/`ld` for the MinGW link).
    private Toolchain ffmpegToolchain(BuildToolchainManager.Toolchain base) {
        boolean windows = OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS;
        Map<String, String> environment = new LinkedHashMap<>(base.environment());

        List<Tool> tools = new ArrayList<>();
        if (windows) {
            tools.add(fromBase(base, "gcc", "gcc", environment));
            tools.add(fromBase(base, "g++", "g++", environment));
            tools.add(fromBase(base, "make", "make", environment));
            tools.add(fromBase(base, "ar", "ar", environment));
            tools.add(fromBase(base, "ld", "ld", environment));
            tools.add(fromBase(base, "pkg-config", "pkg-config", environment));
            tools.add(fromBase(base, "sh", "sh", environment));
            tools.add(fromBase(base, "objdump", "objdump", environment));
            tools.add(fromBase(base, "nasm", "nasm", environment));
            tools.add(fromBase(base, "yasm", "yasm", environment));
        } else {
            tools.add(fromBase(base, "cc", "cc", environment));
            tools.add(fromBase(base, "c++", "c++", environment));
            tools.add(fromBase(base, "make", "make", environment));
            tools.add(fromBase(base, "pkg-config", "pkg-config", environment));
            tools.add(fromBase(base, "nasm", "nasm", environment));
            tools.add(fromBase(base, "yasm", "yasm", environment));
        }

        List<String> required = windows
                ? List.of("gcc", "g++", "make", "ar", "ld", "sh")
                : List.of("cc", "c++", "make");
        List<String> missing = new ArrayList<>();
        for (String name : required) {
            if (!has(tools, name)) {
                missing.add(name);
            }
        }
        boolean usable = missing.isEmpty();
        if (!has(tools, "pkg-config")) {
            missing.add("pkg-config");
        }
        if (!has(tools, "nasm") && !has(tools, "yasm")) {
            // Optional: x86 asm is a speed-up, not a correctness requirement.
            missing.add("nasm");
        }

        List<Dependency> dependencies = new ArrayList<>();
        String pkgConfig = pathOf(tools, "pkg-config");
        if (pkgConfig != null) {
            for (DepSpec spec : DEPENDENCIES) {
                boolean present = pkgConfigExists(pkgConfig, spec.module(), environment);
                String version = present ? pkgConfigVersion(pkgConfig, spec.module(), environment) : "";
                dependencies.add(new Dependency(spec.feature(), spec.module(), spec.packageName(),
                        present, version));
                if (!present) {
                    missing.add(spec.packageName());
                }
            }
        } else {
            for (DepSpec spec : DEPENDENCIES) {
                dependencies.add(new Dependency(spec.feature(), spec.module(), spec.packageName(),
                        false, ""));
                missing.add(spec.packageName());
            }
        }

        String detail = base.detail().isBlank()
                ? "External codec development packages are usually absent on a bare host,"
                + " so a source build is a minimal build."
                : base.detail() + " External codec development packages may be absent, so a"
                + " source build can be a minimal build.";
        return new Toolchain(hostOs(), hostArch(), base.binDir(), usable, base.portable(),
                tools, dependencies, missing, environment, detail);
    }

    private Tool fromBase(BuildToolchainManager.Toolchain base, String name, String baseName,
                          Map<String, String> environment) {
        String path = base.pathOf(baseName);
        String version = "";
        for (BuildToolchainManager.Tool tool : base.tools()) {
            if (tool.name().equals(baseName) && tool.present()) {
                version = tool.version();
                break;
            }
        }
        if (path == null) {
            Tool resolved = resolve(name, environment, switch (name) {
                case "cc" -> new String[]{"cc", "gcc", "clang"};
                case "c++" -> new String[]{"c++", "g++", "clang++"};
                case "make" -> new String[]{"make", "gmake"};
                case "pkg-config" -> new String[]{"pkg-config", "pkgconf"};
                default -> new String[]{name};
            });
            path = resolved.path();
            if (version.isBlank()) {
                version = resolved.version();
            }
        }
        if (path == null) {
            return new Tool(name, null, "");
        }
        if (version.isBlank()) {
            version = versionOf(Path.of(path), environment);
        }
        return new Tool(name, path, version);
    }

    private static String ffmpegMessage(IOException error) {
        String message = error.getMessage() == null ? "" : error.getMessage().trim();
        if (message.startsWith("ffmpeg_build_toolchain_missing:")) {
            return message;
        }
        String prefix = "build_toolchain_missing:";
        if (message.startsWith(prefix)) {
            message = message.substring(prefix.length()).trim();
        }
        return "ffmpeg_build_toolchain_missing: " + message;
    }

    private static Tool resolve(String name, Map<String, String> environment, String... candidates) {
        for (String candidate : candidates) {
            Optional<Path> path = Executables.resolve(candidate);
            if (path.isPresent()) {
                return new Tool(name, path.get().toString(), versionOf(path.get(), environment));
            }
        }
        return new Tool(name, null, "");
    }

    private static String versionOf(Path executable, Map<String, String> environment) {
        try {
            RunResult result = run(List.of(executable.toString(), "--version"), environment,
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

    private static boolean pkgConfigExists(String pkgConfig, String module,
                                           Map<String, String> environment) {
        try {
            return run(List.of(pkgConfig, "--exists", module), environment, PROBE_TIMEOUT,
                    () -> false).exitCode() == 0;
        } catch (IOException e) {
            return false;
        }
    }

    private static String pkgConfigVersion(String pkgConfig, String module,
                                           Map<String, String> environment) {
        try {
            RunResult result = run(List.of(pkgConfig, "--modversion", module), environment,
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

    private static String hostOs() {
        return OperatingSystem.CURRENT_OS.name().toLowerCase(Locale.ROOT);
    }

    private static String hostArch() {
        return switch (Architecture.CURRENT_ARCH) {
            case X86_64 -> "x64";
            case ARM64 -> "arm64";
            case X86 -> "x86";
            case ARM32 -> "arm32";
            default -> Architecture.CURRENT_ARCH.name().toLowerCase(Locale.ROOT);
        };
    }

    /// Runs one probe process. Output is captured rather than streamed: every
    /// caller is short, and the tail is what an error message needs. The
    /// process runs as the agent inside the container, with the same kill-tree
    /// semantics as a build.
    private static RunResult run(List<String> command, Map<String, String> environment,
                                 Duration timeout, BooleanSupplier cancelled) throws IOException {
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("cancelled");
        }
        ProcessBuilder builder = new ProcessBuilder(Privileges.asAgent(command)).redirectErrorStream(true);
        builder.environment().putAll(environment);
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
}