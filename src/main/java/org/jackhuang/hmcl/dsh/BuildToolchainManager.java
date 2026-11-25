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

import org.jackhuang.hmcl.util.ProcessTree;
import org.jackhuang.hmcl.util.io.DownloadSourcePicker;
import org.jackhuang.hmcl.util.logging.Logger;
import org.jackhuang.hmcl.util.platform.Architecture;
import org.jackhuang.hmcl.util.platform.Executables;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jackhuang.hmcl.util.platform.Privileges;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import kala.compress.archivers.tar.TarArchiveEntry;
import kala.compress.archivers.tar.TarArchiveInputStream;
import org.tukaani.xz.XZInputStream;

/// The C/C++ toolchain a source build needs, installed beside the runtimes.
///
/// A bare Windows machine has an OpenJDK and nothing else: no Visual Studio, no
/// `gcc`, no `make`. A slim Linux image has a kernel and a shell. Both are
/// supported deployment targets, and ZCode, Octop, Hermes and ffmpeg all have
/// native dependencies whose source builds stop at `cc: not found`.
///
/// The manager keeps that problem in one place:
///
/// - **Windows x64** installs the pinned w64devkit 2.11.0 self-extractor under
///   `<data>/toolchains/build/w64devkit-2.11.0/`. It is the same artifact the
///   ffmpeg builder already used; here it is the general toolchain (gcc, g++,
///   make, ar, ld, sh, objdump) rather than an ffmpeg-specific detail.
/// - **Linux x64** uses the host's `cc`/`c++`/`make`/`pkg-config`/`nasm` when
///   they are present. When they are not, it installs a portable combination:
///   Zig (`zig cc` / `zig c++` behind `cc`/`c++` wrappers), GNU make, pkgconf
///   and nasm from Debian 11 packages fetched by content hash from
///   `snapshot.debian.org`.
/// - **macOS** uses the Xcode command line tools (`cc`, `c++`, `make`) when
///   they are present; no portable bundle is pinned.
/// - Every other platform is reported as unavailable with an actionable error.
///   The panel does not invent a URL or a hash for a target it never verified.
///
/// Layout, matching [BootstrapRuntimeManager] and [PythonRuntimeManager]:
///
/// ```text
/// <data>/toolchains/build/<version>/
///   bin/                 cc, c++, make, pkgconf, nasm, ar, ranlib (portable)
///   zig/                 the Zig distribution, when the Linux fallback is used
///   lib/                 libpkgconf.so.3, when the Linux fallback is used
///   toolchain.json       provenance of every installed artifact
/// <data>/toolchains/build/current.json
/// <data>/toolchains/build/current -> <version>
/// ```
///
/// A version directory is filled in staging, the record is written last, and
/// only then is it published. A failed or cancelled install removes both the
/// staging directory and the half-written version.
///
/// The shared CPython is not downloaded here: [PythonRuntimeManager] owns it.
/// When one is installed, its `bin`, `PYTHON` and `npm_config_python` are part
/// of the environment this class hands to node-gyp, uv and the build scripts.
///
/// Nothing changes the panel's own `System.getenv`. [#applyTo] only amends the
/// map a child process is about to receive.
@NotNullByDefault
public final class BuildToolchainManager {

    private static final Logger LOG = Logger.LOG;

    /// The portable MinGW build, from the catalogue. A version, never `latest`.
    public static String windowsPortableVersion() {
        return windowsCatalog().version();
    }

    /// The directory a w64devkit tree is published under.
    public static String windowsVersion() {
        return "w64devkit-" + windowsPortableVersion();
    }

    /// Where the pinned w64devkit self-extractor lives.
    public static String windowsPortableUrl() {
        return windowsCatalog().url();
    }

    /// The digest of that archive.
    public static String windowsPortableSha256() {
        return windowsCatalog().sha256();
    }

    /// Zig's version, which also names the portable Linux directory.
    public static String zigVersion() {
        return linuxCatalog().version();
    }

    /// The directory the portable Linux combination is published under.
    ///
    /// x64 keeps the name it has always had, so an existing install is not
    /// downloaded again; arm64 appends its architecture, which is also what
    /// keeps a shared `<data>` from mixing the two.
    public static String linuxVersion() {
        return "zig-" + zigVersion() + "+debian11"
                + ("x64".equals(hostArch()) ? "" : "-" + hostArch());
    }

    /// Whether a portable toolchain exists for this platform.
    ///
    /// Linux arm64 joined when its pins were added; Windows stays x64, where
    /// w64devkit is the only supply chain with a pinned digest.
    static boolean portableSupported() {
        return switch (OperatingSystem.CURRENT_OS) {
            case LINUX -> Architecture.CURRENT_ARCH == Architecture.X86_64
                    || Architecture.CURRENT_ARCH == Architecture.ARM64;
            case WINDOWS -> Architecture.CURRENT_ARCH == Architecture.X86_64;
            default -> false;
        };
    }

    private static BootstrapCatalog.Toolchain toolchainCatalog() {
        BootstrapCatalog.Toolchain toolchain = BootstrapCatalog.current().toolchain();
        if (toolchain == null) {
            throw new IllegalStateException(
                    "bootstrap_catalog_incomplete: the catalogue pins no build toolchain");
        }
        return toolchain;
    }

    private static BootstrapCatalog.Windows windowsCatalog() {
        BootstrapCatalog.Windows windows = toolchainCatalog().windows();
        if (windows == null) {
            throw new IllegalStateException(
                    "bootstrap_catalog_incomplete: the catalogue pins no Windows toolchain");
        }
        return windows;
    }

    private static BootstrapCatalog.Linux linuxCatalog() {
        BootstrapCatalog.Linux linux = toolchainCatalog().linux();
        if (linux == null) {
            throw new IllegalStateException(
                    "bootstrap_catalog_incomplete: the catalogue pins no Linux toolchain");
        }
        return linux;
    }

    private static final String RECORD = "toolchain.json";
    private static final String POINTER = "current.json";
    private static final long CACHE_MILLIS = 60_000;
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(4);
    private static final Duration EXTRACT_TIMEOUT = Duration.ofMinutes(10);
    /// A source that sends nothing for this long is abandoned for the next
    /// candidate. The speed probe only sees the first 512 KB; a connection
    /// that stalls after that would otherwise hold the install for the full
    /// request timeout (20 minutes). The window is deliberately generous:
    /// a slow CDN can pause for tens of seconds without being dead.
    private static final Duration DOWNLOAD_STALL = Duration.ofSeconds(90);

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private static final Object DEFAULT_LOCK = new Object();
    private static volatile @Nullable BuildToolchainManager defaultManager;

    /// Samples the catalogue's mirrors before each artifact download and
    /// remembers the winner; see [DownloadSourcePicker].
    private final DownloadSourcePicker sourcePicker = new DownloadSourcePicker();

    /// One program of a resolved toolchain.
    public record Tool(String name, @Nullable String path, String version) {
        public boolean present() {
            return path != null && !path.isBlank();
        }
    }

    /// One pinned download.
    ///
    /// @param name     the artifact's name in the provenance record
    /// @param url      a pinned https address
    /// @param sha256   the SHA-256 of the exact bytes
    /// @param size     the expected size, or `0` when it is not stated
    /// @param archive  `deb`, `tar.xz`, `tar.gz`, `tar` or `zip`
    /// @param includes the `.deb` members to extract, empty for a full archive
    /// @param mirrors  alternative origins for the same pinned bytes
    record Artifact(String name, String url, String sha256, long size, String archive,
                    List<BootstrapCatalog.Include> includes, List<String> mirrors) {
        Artifact {
            BootstrapCatalog.validateHttps(url);
            includes = List.copyOf(includes);
            mirrors = BootstrapCatalog.validateMirrors(mirrors);
        }

        /// The pre-mirror shape, for callers that only have the official URL.
        Artifact(String name, String url, String sha256, long size, String archive,
                 List<BootstrapCatalog.Include> includes) {
            this(name, url, sha256, size, archive, includes, List.of());
        }
    }

    /// One resolved toolchain.
    ///
    /// @param os          `windows`, `linux` or `macos`
    /// @param arch        `x64`, `arm64`
    /// @param directory   the version directory, or `null` for a system toolchain
    /// @param binDir      the `bin/` to prepend to `PATH`, or `null`
    /// @param usable      whether every required program is present
    /// @param portable    whether this is an installed bundle
    /// @param tools       the resolved programs
    /// @param missing     required programs that are absent
    /// @param environment the variables a child should receive
    /// @param detail      one or two sentences of context for the settings page
    public record Toolchain(
            String os,
            String arch,
            @Nullable Path directory,
            @Nullable Path binDir,
            boolean usable,
            boolean portable,
            List<Tool> tools,
            List<String> missing,
            Map<String, String> environment,
            String detail) {

        public Toolchain {
            tools = List.copyOf(tools);
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

        /// Adds this toolchain to a child's environment.
        ///
        /// `PATH` and `LD_LIBRARY_PATH` are prepended, so an operator's existing
        /// entries keep working behind the panel's. `CC`, `CXX`, `MAKE`, `AR`,
        /// `LD`, `PKG_CONFIG`, `PYTHON` and `npm_config_python` are only set
        /// when the map has no non-blank value of its own.
        ///
        /// @param environment the child's map, amended in place
        public void applyTo(Map<String, String> environment) {
            if (binDir != null) {
                String existing = environment.get("PATH");
                environment.put("PATH", prepend(binDir, existing == null ? System.getenv("PATH") : existing));
            }
            for (String name : List.of("CC", "CXX", "MAKE", "AR", "LD", "PKG_CONFIG",
                    "ZIG_GLOBAL_CACHE_DIR")) {
                putIfUnusable(environment, name, this.environment.get(name));
            }
            String lib = this.environment.get("LD_LIBRARY_PATH");
            if (lib != null && !lib.isBlank()) {
                // Only the bundle's own `lib/` is added here. The map's value may
                // already carry the panel's inherited path, which would otherwise
                // be duplicated every time a child is launched.
                int separator = lib.indexOf(java.io.File.pathSeparatorChar);
                String bundleLib = separator < 0 ? lib : lib.substring(0, separator);
                environment.put("LD_LIBRARY_PATH",
                        prepend(Path.of(bundleLib), environment.get("LD_LIBRARY_PATH")));
            }
            putIfUnusable(environment, "PYTHON", this.environment.get("PYTHON"));
            putIfUnusable(environment, "npm_config_python", this.environment.get("npm_config_python"));
        }

        /// A short line for the settings page and the build log.
        public String describe() {
            if (!portable) {
                return os + "/" + arch + " (system toolchain)";
            }
            String label = "windows".equals(os)
                    ? "portable w64devkit " + windowsPortableVersion()
                    : "portable zig " + zigVersion() + " + GNU make 4.3 + pkgconf 1.7.4"
                    + (has("nasm") ? " + nasm 2.15.05" : "");
            return os + "/" + arch + " (" + label + " at " + directory + ")";
        }

        /// The error text a caller shows when no toolchain could be provided.
        ///
        /// It names the programs that are missing and the one concrete action
        /// that fixes it; "configure failed" is the failure mode this class
        /// exists to prevent.
        public String missingMessage() {
            StringBuilder message = new StringBuilder("build_toolchain_missing: ");
            if (!usable) {
                message.append("no usable C/C++ toolchain for ").append(os).append('/').append(arch);
            }
            if (!missing.isEmpty()) {
                message.append(": missing ").append(String.join(", ", missing));
            }
            message.append('.');
            if (!detail.isBlank()) {
                message.append(' ').append(detail);
            }
            return message.toString();
        }
    }

    /// What `toolchain.json` records.
    public static final class ToolchainRecord {
        public int schema = 1;
        public String version = "";
        public String os = "";
        public String arch = "";
        public String installedAt = "";
        public List<ArtifactRecord> artifacts = List.of();
    }

    /// One artifact's provenance.
    public static final class ArtifactRecord {
        public String name = "";
        public String url = "";
        public String sha256 = "";
        public long size;
        public String archive = "";
    }

    private record Cached(long at, Toolchain toolchain) {
    }

    private final Path root;
    private final @Nullable String systemPath;
    private final BootstrapRuntimeManager.ArtifactSource source;
    private final Object lock = new Object();
    private volatile @Nullable Cached detectCache;
    private volatile @Nullable Cached systemCache;

    /// @param root the `<data>/toolchains/build` directory
    public BuildToolchainManager(Path root) {
        this(root, System.getenv("PATH"), new HttpSource());
    }

    /// A manager that probes an explicit `PATH` instead of the panel's.
    ///
    /// The two-argument form exists for isolated deployments and tests: it is
    /// the only way to decide whether the system already has a compiler
    /// without changing the JVM's own environment.
    ///
    /// @param root       the `<data>/toolchains/build` directory
    /// @param systemPath the `PATH` to probe, or `null` for none
    public BuildToolchainManager(Path root, @Nullable String systemPath) {
        this(root, systemPath, new HttpSource());
    }

    BuildToolchainManager(Path root, @Nullable String systemPath,
                          BootstrapRuntimeManager.ArtifactSource source) {
        this.root = root.toAbsolutePath().normalize();
        this.systemPath = systemPath;
        this.source = source;
    }

    public Path root() {
        return root;
    }

    /// `<data>/toolchains/build`, next to the runtime closure and CPython.
    public static Path defaultRoot() {
        return BootstrapRuntimeManager.toolchainsRoot().resolve("build");
    }

    public static BuildToolchainManager defaultManager() {
        BuildToolchainManager manager = defaultManager;
        if (manager == null) {
            synchronized (DEFAULT_LOCK) {
                manager = defaultManager;
                if (manager == null) {
                    manager = new BuildToolchainManager(defaultRoot());
                    defaultManager = manager;
                }
            }
        }
        return manager;
    }

    /// The portable toolchain that is installed right now, or `null`.
    ///
    /// This never installs and never runs a program: it is what
    /// [BootstrapEnvironment] calls for every child, so a missing bundle must
    /// not turn into a download or a `gcc --version` probe.
    public @Nullable Toolchain installed() {
        return installedUncached();
    }

    /// The same, through the panel's default manager.
    public static @Nullable Toolchain currentInstalled() {
        try {
            return defaultManager().installed();
        } catch (RuntimeException e) {
            LOG.warning("Could not read the build toolchains: " + e.getMessage());
            return null;
        }
    }

    /// The toolchain a build should use: the system one when it is usable,
    /// otherwise the installed portable one, otherwise a description of what
    /// is missing.
    public Toolchain detect() {
        Cached cached = detectCache;
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.at < CACHE_MILLIS) {
            return cached.toolchain();
        }
        Toolchain toolchain = detectUncached();
        detectCache = new Cached(System.currentTimeMillis(), toolchain);
        return toolchain;
    }

    /// The host's own compiler toolchain, without the portable fallback.
    ///
    /// Used when a caller explicitly asked for the system compiler: an
    /// unusable answer must be reported, not silently replaced by a download.
    ///
    /// @return the system toolchain, usable or not
    public Toolchain systemToolchain() {
        Cached cached = systemCache;
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.at < CACHE_MILLIS) {
            return cached.toolchain();
        }
        Toolchain toolchain = OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS
                ? windowsToolchain(null)
                : posixSystem();
        systemCache = new Cached(now, toolchain);
        return toolchain;
    }

    /// Whether this platform has a pinned portable toolchain.
    public static boolean portableAvailable() {
        return portableSupported();
    }

    /// Makes sure a build has a usable toolchain, installing the pinned
    /// portable one when the host has none.
    ///
    /// @return a usable toolchain
    /// @throws IOException `build_toolchain_missing` when this platform has no
    ///                     pinned bundle or the install failed
    public Toolchain ensureForBuild() throws IOException {
        return ensureForBuild(BootstrapRuntimeManager.Progress.NONE, () -> false);
    }

    /// The same, with progress and cancellation.
    ///
    /// @param progress  a progress sink
    /// @param cancelled whether the caller has given up
    /// @return a usable toolchain
    /// @throws IOException `build_toolchain_missing` when nothing can be provided
    public Toolchain ensureForBuild(BootstrapRuntimeManager.Progress progress,
                                    BooleanSupplier cancelled) throws IOException {
        Toolchain current = detect();
        if (current.usable()) {
            return current;
        }
        if (!portableSupported()) {
            throw new IOException(current.missingMessage());
        }
        try {
            installPortable(progress, cancelled);
        } catch (IOException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (message.startsWith("build_toolchain_")) {
                throw e;
            }
            throw new IOException(current.missingMessage() + " The install failed: " + message, e);
        }
        detectCache = null;
        Toolchain installed = detect();
        if (!installed.usable()) {
            throw new IOException(installed.missingMessage());
        }
        return installed;
    }

    /// Downloads and publishes the pinned portable toolchain.
    ///
    /// Windows gets w64devkit; Linux gets the Zig/make/pkgconf/nasm
    /// combination. Both are x64-only, and both are installed atomically: the
    /// directory a build could pick never contains a half-written tree.
    ///
    /// @param progress  a progress sink
    /// @param cancelled whether the caller has given up
    /// @return the installed toolchain
    /// @throws IOException when this platform has no pinned bundle or the
    ///                     download, verification or extraction fails
    public Toolchain installPortable(BootstrapRuntimeManager.Progress progress,
                                     BooleanSupplier cancelled) throws IOException {
        synchronized (lock) {
            if (!portableSupported()) {
                throw new IOException("build_toolchain_missing: no portable build toolchain is pinned for "
                        + hostOs() + "/" + hostArch()
                        + " (Linux x64/arm64 and Windows x64 are); install gcc, g++, make,"
                        + " pkg-config and nasm with the distribution package manager");
            }
            if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
                return installWindows(progress, cancelled);
            }
            if (OperatingSystem.CURRENT_OS == OperatingSystem.LINUX) {
                return installLinux(linuxArtifacts(), progress, cancelled);
            }
            throw new IOException("build_toolchain_missing: no portable build toolchain is pinned for "
                    + hostOs() + "/" + hostArch()
                    + "; install gcc, g++, make, pkg-config and nasm with the distribution"
                    + " package manager");
        }
    }

    // --------------------------------------------------------------- resolve --

    private Toolchain detectUncached() {
        if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
            Toolchain system = windowsToolchain(null);
            if (system.usable()) {
                return system;
            }
            Toolchain portable = installedWindows();
            if (portable != null) {
                return portable;
            }
            return new Toolchain(system.os(), system.arch(), null, null, false, false,
                    system.tools(), system.missing(), system.environment(),
                    "A portable w64devkit " + windowsPortableVersion()
                            + " can be installed when a source build starts.");
        }
        if (OperatingSystem.CURRENT_OS == OperatingSystem.LINUX) {
            Toolchain system = posixSystem();
            if (system.usable()) {
                return system;
            }
            Toolchain portable = installedLinux();
            if (portable != null) {
                return portable;
            }
            return new Toolchain(system.os(), system.arch(), null, null, false, false,
                    system.tools(), system.missing(), system.environment(),
                    "The panel can install a portable Zig + GNU make + pkgconf"
                            + " combination when a source build starts; the distribution's"
                            + " build-essential pkg-config nasm remains the preferred route.");
        }
        if (OperatingSystem.CURRENT_OS == OperatingSystem.MACOS) {
            Toolchain system = posixSystem();
            if (system.usable()) {
                return system;
            }
            return new Toolchain(system.os(), system.arch(), null, null, false, false,
                    system.tools(), system.missing(), system.environment(),
                    "No portable build toolchain is pinned for macOS; install the Xcode"
                            + " command line tools (xcode-select --install) and nasm.");
        }
        List<String> missing = List.of("cc", "c++", "make");
        return new Toolchain(hostOs(), hostArch(), null, null, false, false, List.of(),
                missing, Map.of(), "This platform has no pinned portable build toolchain;"
                        + " install a C/C++ compiler and make with the system package manager.");
    }

    private @Nullable Toolchain installedUncached() {
        if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
            return installedWindows();
        }
        if (OperatingSystem.CURRENT_OS == OperatingSystem.LINUX) {
            return installedLinux();
        }
        return null;
    }

    private Toolchain posixSystem() {
        List<Tool> tools = new ArrayList<>();
        tools.add(resolve("cc", "cc", "gcc", "clang"));
        tools.add(resolve("c++", "c++", "g++", "clang++"));
        tools.add(resolve("make", "make", "gmake"));
        tools.add(resolve("pkg-config", "pkg-config", "pkgconf"));
        tools.add(resolve("nasm", "nasm"));
        tools.add(resolve("yasm", "yasm"));
        tools.add(resolve("ar", "ar", "llvm-ar"));
        tools.add(resolve("ld", "ld", "ld.lld"));
        return posixFromTools(tools, null, false);
    }

    private Toolchain posixFromTools(List<Tool> tools, @Nullable Path binDir, boolean portable) {
        List<String> missing = new ArrayList<>();
        for (String required : List.of("cc", "c++", "make")) {
            if (!has(tools, required)) {
                missing.add(required);
            }
        }
        boolean usable = has(tools, "cc") && has(tools, "c++") && has(tools, "make");
        Map<String, String> environment = new LinkedHashMap<>();
        if (binDir != null) {
            environment.put("PATH", prepend(binDir, System.getenv("PATH")));
        }
        putIf(environment, "CC", pathOf(tools, "cc"));
        putIf(environment, "CXX", pathOf(tools, "c++"));
        putIf(environment, "MAKE", pathOf(tools, "make"));
        putIf(environment, "AR", pathOf(tools, "ar"));
        putIf(environment, "LD", pathOf(tools, "ld"));
        putIf(environment, "PKG_CONFIG", pathOf(tools, "pkg-config"));
        if (binDir != null) {
            Path version = binDir.getParent();
            Path lib = version.resolve("lib");
            if (Files.isDirectory(lib)) {
                environment.put("LD_LIBRARY_PATH", prepend(lib, System.getenv("LD_LIBRARY_PATH")));
            }
            // Zig writes its compiler cache under `$HOME` by default. In the
            // container the build runs as the agent, whose home may not be
            // writable in every deployment; the data directory is.
            Path zigCache = version.getParent().resolve("cache/zig");
            putIf(environment, "ZIG_GLOBAL_CACHE_DIR", zigCache.toString());
        }
        PythonRuntimeManager.applyTo(environment);
        String detail = portable
                ? "The portable Linux combination is installed under "
                + (binDir == null ? root : binDir.getParent())
                + "; it provides the compiler, make, pkgconf and nasm, but no development"
                + " headers for external libraries."
                : "Install the build tools with the distribution package manager"
                + " (Debian/Ubuntu: build-essential pkg-config nasm); the panel never runs sudo."
                + (has(tools, "nasm") || has(tools, "yasm") ? "" : " Without nasm, x86 assembly is disabled.");
        return new Toolchain(hostOs(), hostArch(), binDir == null ? null : binDir.getParent(),
                binDir, usable, portable, tools, missing, environment, detail);
    }

    Toolchain windowsToolchain(@Nullable Path bin) {
        List<Tool> tools = new ArrayList<>();
        if (bin != null) {
            String version = "w64devkit " + windowsPortableVersion();
            tools.add(toolAt("gcc", bin.resolve("gcc.exe"), Map.of(), version));
            tools.add(toolAt("g++", bin.resolve("g++.exe"), Map.of(), version));
            tools.add(toolAt("make", firstExisting(bin.resolve("mingw32-make.exe"),
                    bin.resolve("make.exe")), Map.of(), version));
            tools.add(toolAt("ar", bin.resolve("ar.exe"), Map.of(), version));
            tools.add(toolAt("ld", bin.resolve("ld.exe"), Map.of(), version));
            tools.add(toolAt("pkg-config", firstExisting(bin.resolve("pkg-config.exe"),
                    bin.resolve("pkgconf.exe")), Map.of(), version));
            tools.add(toolAt("sh", bin.resolve("sh.exe"), Map.of(), version));
            tools.add(toolAt("objdump", bin.resolve("objdump.exe"), Map.of(), version));
            tools.add(toolAt("nasm", bin.resolve("nasm.exe"), Map.of(), version));
            tools.add(toolAt("yasm", bin.resolve("yasm.exe"), Map.of(), version));
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
        Map<String, String> environment = new LinkedHashMap<>();
        if (bin != null) {
            environment.put("PATH", prepend(bin, System.getenv("PATH")));
        }
        putIf(environment, "CC", pathOf(tools, "gcc"));
        putIf(environment, "CXX", pathOf(tools, "g++"));
        putIf(environment, "MAKE", pathOf(tools, "make"));
        putIf(environment, "AR", pathOf(tools, "ar"));
        putIf(environment, "LD", pathOf(tools, "ld"));
        putIf(environment, "PKG_CONFIG", pathOf(tools, "pkg-config"));
        PythonRuntimeManager.applyTo(environment);
        String detail = bin != null
                ? "Portable w64devkit " + windowsPortableVersion() + " is installed under "
                + bin.getParent() + "; external codec libraries are usually absent, so a"
                + " Windows source build is a minimal build."
                : "No MinGW toolchain was found on PATH; the pinned portable w64devkit"
                + " " + windowsPortableVersion() + " can be installed on demand.";
        return new Toolchain(hostOs(), hostArch(), bin == null ? null : bin.getParent(), bin,
                usable, bin != null, tools, missing, environment, detail);
    }

    private @Nullable Toolchain installedWindows() {
        Path bin = windowsBin(root.resolve(windowsVersion()));
        if (bin == null) {
            // The ffmpeg builder's pre-priority-4 location; adopting it means an
            // installation that already works is not downloaded twice.
            Path legacy = root.getParent() == null ? null
                    : root.getParent().resolve("mingw").resolve(windowsPortableVersion());
            if (legacy != null) {
                bin = windowsBin(legacy);
            }
        }
        if (bin == null) {
            return null;
        }
        Toolchain toolchain = windowsToolchain(bin);
        return toolchain.usable() ? toolchain : null;
    }

    private @Nullable Toolchain installedLinux() {
        Path version = root.resolve(linuxVersion());
        Path bin = version.resolve("bin");
        if (!Files.isRegularFile(version.resolve("zig/zig"))
                || !Files.isRegularFile(bin.resolve("cc"))
                || !Files.isRegularFile(bin.resolve("c++"))
                || !Files.isRegularFile(bin.resolve("make"))
                || !Privileges.useAsRoot(version, bin.resolve("cc"))) {
            return null;
        }
        List<Tool> tools = new ArrayList<>();
        tools.add(toolAt("cc", bin.resolve("cc"), Map.of(), "zig " + zigVersion()));
        tools.add(toolAt("c++", bin.resolve("c++"), Map.of(), "zig " + zigVersion()));
        tools.add(toolAt("make", bin.resolve("make"), Map.of(), "GNU make 4.3"));
        tools.add(toolAt("ar", bin.resolve("ar"), Map.of(), "zig " + zigVersion()));
        tools.add(toolAt("ranlib", bin.resolve("ranlib"), Map.of(), "zig " + zigVersion()));
        tools.add(toolAt("ld", bin.resolve("ld"), Map.of(), "zig " + zigVersion()));
        tools.add(toolAt("pkg-config", firstExisting(bin.resolve("pkg-config"), bin.resolve("pkgconf")),
                Map.of(), "pkgconf 1.7.4"));
        tools.add(toolAt("nasm", bin.resolve("nasm"), Map.of(), "nasm 2.15.05"));
        return posixFromTools(tools, bin, true);
    }

    // --------------------------------------------------------------- install --

    private Toolchain installWindows(BootstrapRuntimeManager.Progress progress,
                                     BooleanSupplier cancelled) throws IOException {
        Toolchain existing = installedWindows();
        if (existing != null) {
            BootstrapTrace.reused("toolchain", windowsVersion(), existing.directory());
            return existing;
        }
        BootstrapRuntimeManager.ensureToolchainsRoot();
        Privileges.keepRootOwnedDirectory(root);

        Path target = root.resolve(windowsVersion());
        Path download = root.resolve(".w64devkit-" + UUID.randomUUID() + ".7z.exe");
        Path extract = root.resolve(".w64devkit-extract-" + UUID.randomUUID());
        boolean installed = false;
        try {
            Files.createDirectories(extract);
            Privileges.keepRootOwnedDirectory(extract);
            progress.stage("downloading portable w64devkit " + windowsPortableVersion(), 0.05);
            BootstrapCatalog.Windows windows = windowsCatalog();
            fetch(new Artifact("w64devkit", windows.url(), windows.sha256(), 0, "7z.exe",
                    List.of(), windows.mirrors()), download, cancelled);
            progress.stage("extracting portable w64devkit", 0.6);
            RunResult result = run(List.of(download.toString(), "-y", "-o" + extract),
                    Map.of(), EXTRACT_TIMEOUT, cancelled);
            if (result.exitCode() != 0) {
                throw new IOException("build_toolchain_install_failed: the w64devkit self-extractor"
                        + " exited with " + result.exitCode() + ": " + tail(result.output()));
            }
            Path bin = windowsBin(extract);
            if (bin == null) {
                throw new IOException("build_toolchain_install_failed: the w64devkit archive"
                        + " contains no gcc.exe");
            }
            Path tree = bin.getParent();
            publish(tree, target, windowsVersion(), List.of(
                    artifactRecord("w64devkit", windowsPortableUrl(), windowsPortableSha256(),
                            0, "7z.exe")));
            Toolchain toolchain = installedWindows();
            if (toolchain == null) {
                throw new IOException("build_toolchain_missing: w64devkit was extracted but no"
                        + " usable gcc/g++/make/ar/ld was found under " + target);
            }
            String failure = smoke(toolchain);
            if (failure != null) {
                throw new IOException("build_toolchain_install_failed: w64devkit was extracted"
                        + " and matched its digest but does not run on this host (" + failure + ")");
            }
            installed = true;
            return toolchain;
        } finally {
            Files.deleteIfExists(download);
            BootstrapRuntimeManager.deleteTree(extract);
            if (!installed) {
                BootstrapRuntimeManager.deleteTree(target);
            }
        }
    }

    private Toolchain installLinux(List<Artifact> artifacts, BootstrapRuntimeManager.Progress progress,
                                   BooleanSupplier cancelled) throws IOException {
        Toolchain existing = installedLinux();
        if (existing != null) {
            BootstrapTrace.reused("toolchain", linuxVersion(), existing.directory());
            return existing;
        }
        BootstrapRuntimeManager.ensureToolchainsRoot();
        Privileges.keepRootOwnedDirectory(root);

        Path target = root.resolve(linuxVersion());
        Path staging = root.resolve("." + linuxVersion() + ".installing-" + UUID.randomUUID());
        Path downloads = staging.resolve(".downloads");
        boolean installed = false;
        try {
            Files.createDirectories(staging);
            Privileges.keepRootOwnedDirectory(staging);
            Files.createDirectories(downloads);
            Privileges.keepRootOwnedDirectory(downloads);
            // Only Zig's cache is the agent's scratch space. The toolchain's
            // own `bin/` stays root-owned: the panel runs `cc`, `make` and the
            // Zig wrappers as root during a brand source install, so a tree an
            // instance could rewrite is a tree an instance could put a program
            // in for root to run.
            Files.createDirectories(root.resolve("cache/zig"));
            Privileges.prepareAgentDirectory(root.resolve("cache/zig"),
                    Privileges.AGENT_SHARED_DIRECTORY);
            List<ArtifactRecord> records = new ArrayList<>();
            double fraction = 0.05;
            for (Artifact artifact : artifacts) {
                progress.stage("downloading " + artifact.name(), fraction);
                Path file = downloads.resolve(artifact.name() + suffix(artifact.archive()));
                fetch(artifact, file, cancelled);
                records.add(artifactRecord(artifact.name(), artifact.url(), artifact.sha256(),
                        artifact.size(), artifact.archive()));
                fraction = Math.min(0.75, fraction + 0.12);
            }

            progress.stage("unpacking the portable build toolchain", 0.78);
            for (Artifact artifact : artifacts) {
                Path file = downloads.resolve(artifact.name() + suffix(artifact.archive()));
                if (artifact.archive().equals("deb")) {
                    BootstrapArchive.extract(file, "deb", artifact.includes(), staging);
                } else {
                    extractArchive(file, artifact.archive(), staging.resolve("zig"), artifact.name());
                }
            }
            BootstrapRuntimeManager.deleteTree(downloads);
            writeLinuxWrappers(staging);
            progress.stage("publishing the portable build toolchain", 0.95);
            publish(staging, target, linuxVersion(), records);
            Toolchain toolchain = installedLinux();
            if (toolchain == null) {
                throw new IOException("build_toolchain_missing: the portable Linux toolchain was"
                        + " unpacked but cc/c++/make are not startable under " + target);
            }
            String failure = smoke(toolchain);
            if (failure != null) {
                throw new IOException("build_toolchain_install_failed: the portable Linux toolchain"
                        + " unpacked and matched its digests but does not run on this host ("
                        + failure + "); these binaries need a newer glibc than this system has");
            }
            installed = true;
            return toolchain;
        } finally {
            BootstrapRuntimeManager.deleteTree(staging);
            if (!installed) {
                BootstrapRuntimeManager.deleteTree(target);
            }
        }
    }

    /// The pinned Linux artifacts, in install order, from the catalogue.
    static List<Artifact> linuxArtifacts() {
        List<Artifact> artifacts = new ArrayList<>();
        for (BootstrapCatalog.ToolchainArtifact pinned : linuxCatalog().artifacts(hostArch())) {
            artifacts.add(new Artifact(pinned.name(), pinned.url(), pinned.sha256(),
                    pinned.size(), pinned.archive(), pinned.includes(), pinned.mirrors()));
        }
        return artifacts;
    }

    /// The `cc`/`c++`/`ar`/`ranlib`/`ld` files that make Zig look like a
    /// conventional GCC toolchain.
    ///
    /// The wrappers resolve Zig through their own directory, so they keep
    /// working after the staging tree is renamed into a version directory.
    /// `zig cc` targets the host by default and reads the host's headers; the
    /// make/pkgconf binaries are ordinary Debian 11 executables and only need
    /// `libpkgconf.so.3` from `../lib`.
    private static void writeLinuxWrappers(Path staging) throws IOException {
        Path bin = staging.resolve("bin");
        Files.createDirectories(bin);
        Path zig = findZig(staging);
        if (zig == null) {
            throw new IOException("build_toolchain_install_failed: the Zig archive contains no"
                    + " `zig` executable");
        }
        String relativeZig = bin.relativize(zig).toString().replace('\\', '/');
        writeWrapper(bin.resolve("cc"), relativeZig, "cc");
        writeWrapper(bin.resolve("c++"), relativeZig, "c++");
        // CPython's sysconfig records the GCC name it was built with, and
        // several setup.py builds invoke `gcc` directly rather than `cc`.
        writeWrapper(bin.resolve("gcc"), relativeZig, "cc");
        writeWrapper(bin.resolve("g++"), relativeZig, "c++");
        writeWrapper(bin.resolve("ar"), relativeZig, "ar");
        writeWrapper(bin.resolve("ranlib"), relativeZig, "ranlib");
        // `zig ld.lld` is the linker driver; it is optional because cc already
        // links through Zig. A failed subcommand only means LD is not set.
        writeWrapper(bin.resolve("ld"), relativeZig, "ld.lld");
        Path pkgconf = bin.resolve("pkgconf");
        if (Files.isRegularFile(pkgconf)) {
            Path alias = bin.resolve("pkg-config");
            try {
                Files.createSymbolicLink(alias, Path.of("pkgconf"));
            } catch (IOException | UnsupportedOperationException e) {
                Files.writeString(alias, "#!/bin/sh\nexec \"$(dirname \"$0\")/pkgconf\" \"$@\"\n",
                        StandardCharsets.UTF_8);
                makeExecutable(alias);
            }
        }
        makeExecutable(bin.resolve("make"));
        makeExecutable(bin.resolve("nasm"));
        makeExecutable(pkgconf);
        makeExecutable(staging.resolve("lib").resolve("libpkgconf.so.3.0.0"));
        makeExecutable(zig);
    }

    private static void writeWrapper(Path target, String relativeExecutable, String subcommand)
            throws IOException {
        String script = "#!/bin/sh\n"
                + "here=$(CDPATH= cd -- \"$(dirname -- \"$0\")\" && pwd)\n"
                + "exec \"$here/" + relativeExecutable + "\" " + subcommand + " \"$@\"\n";
        Files.writeString(target, script, StandardCharsets.UTF_8);
        makeExecutable(target);
    }

    private static @Nullable Path findZig(Path staging) {
        for (String relative : List.of("zig/zig", "zig")) {
            Path candidate = staging.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    static void extractArchive(Path archive, String kind, Path destination, String name)
            throws IOException {
        Path rootDir = destination.toAbsolutePath().normalize();
        Files.createDirectories(rootDir);
        if ("zip".equals(kind)) {
            extractZip(archive, rootDir);
            return;
        }
        InputStream file = new BufferedInputStream(Files.newInputStream(archive));
        try {
            boolean gzip = "tar.gz".equals(kind) || "tgz".equals(kind);
            boolean xz = "tar.xz".equals(kind) || "txz".equals(kind);
            InputStream data = gzip ? new GZIPInputStream(file)
                    : xz ? new XZInputStream(file) : file;
            extractTar(data, rootDir, name);
        } finally {
            file.close();
        }
    }

    private static void extractTar(InputStream data, Path rootDir, String name) throws IOException {
        try (TarArchiveInputStream tar = new TarArchiveInputStream(data)) {
            boolean strip = true;
            boolean decided = false;
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                String raw = normalize(entry.getName());
                if (raw == null) {
                    continue;
                }
                if (!decided) {
                    strip = true;
                    decided = true;
                }
                String relative = strip ? stripTop(raw) : raw;
                if (relative == null || relative.isEmpty()) {
                    continue;
                }
                Path target = resolveInside(rootDir, relative);
                if (target == null) {
                    throw new IOException("build_toolchain_install_failed: " + name
                            + " names a path outside its directory: " + raw);
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                if (entry.isSymbolicLink()) {
                    String link = entry.getLinkName();
                    if (link == null || link.isBlank() || Path.of(link).isAbsolute()) {
                        throw new IOException("build_toolchain_install_failed: " + name
                                + " links " + raw + " to an unusable target");
                    }
                    Path resolved = target.getParent().resolve(link).normalize();
                    if (!resolved.startsWith(rootDir)) {
                        throw new IOException("build_toolchain_install_failed: " + name
                                + " links " + raw + " outside its directory");
                    }
                    Files.deleteIfExists(target);
                    try {
                        Files.createSymbolicLink(target, Path.of(link));
                    } catch (IOException | UnsupportedOperationException e) {
                        // A copied target is as usable as the link for everything
                        // this toolchain runs.
                        Files.copy(resolved, target, StandardCopyOption.REPLACE_EXISTING,
                                StandardCopyOption.COPY_ATTRIBUTES);
                        makeExecutable(target);
                    }
                    continue;
                }
                if (entry.isLink()) {
                    String link = entry.getLinkName();
                    Path source = link == null ? null : resolveInside(rootDir, stripTop(normalize(link)));
                    if (source == null || !Files.isRegularFile(source)) {
                        throw new IOException("build_toolchain_install_failed: " + name
                                + " hard-links " + raw + " to a missing member");
                    }
                    try {
                        Files.createLink(target, source);
                    } catch (IOException | UnsupportedOperationException e) {
                        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING,
                                StandardCopyOption.COPY_ATTRIBUTES);
                        makeExecutable(target);
                    }
                    continue;
                }
                try (OutputStream out = Files.newOutputStream(target,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE)) {
                    tar.transferTo(out);
                }
                if ((entry.getMode() & 0111) != 0) {
                    makeExecutable(target);
                }
            }
        }
    }

    private static void extractZip(Path archive, Path rootDir) throws IOException {
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(
                new BufferedInputStream(Files.newInputStream(archive)))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String raw = normalize(entry.getName());
                if (raw == null) {
                    continue;
                }
                String relative = stripTop(raw);
                if (relative == null || relative.isEmpty()) {
                    continue;
                }
                Path target = resolveInside(rootDir, relative);
                if (target == null) {
                    throw new IOException("build_toolchain_install_failed: the archive names"
                            + " a path outside its directory: " + raw);
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (OutputStream out = Files.newOutputStream(target,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE)) {
                    zip.transferTo(out);
                }
                makeExecutable(target);
            }
        }
    }

    /// Writes the version directory atomically and moves `current` to it.
    private void publish(Path tree, Path target, String version, List<ArtifactRecord> artifacts)
            throws IOException {
        ToolchainRecord record = new ToolchainRecord();
        record.version = version;
        record.os = hostOs();
        record.arch = hostArch();
        record.installedAt = Instant.now().toString();
        record.artifacts = List.copyOf(artifacts);
        Files.writeString(tree.resolve(RECORD), new com.google.gson.GsonBuilder()
                .setPrettyPrinting().create().toJson(record), StandardCharsets.UTF_8);

        BootstrapRuntimeManager.deleteTree(target);
        try {
            Files.move(tree, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            copyTree(tree, target);
            BootstrapRuntimeManager.deleteTree(tree);
        }
        // Root executes `cc`, `make` and the Zig wrappers from here during a
        // brand source install. Keep the tree out of the agent's reach; only
        // `cache/zig` is shared, and it lives outside this directory.
        Privileges.keepRootExecutable(target);
        activate(version);
        detectCache = null;
        for (ArtifactRecord artifact : record.artifacts) {
            BootstrapTrace.installed("toolchain", artifact.name, artifact.url,
                    "sha256:" + artifact.sha256, target);
        }
    }

    private void activate(String version) throws IOException {
        Path directory = root.resolve(version).normalize();
        if (!directory.startsWith(root) || !Files.isDirectory(directory)) {
            throw new IOException("build_toolchain_missing: " + version + " was not installed");
        }
        com.google.gson.JsonObject pointer = new com.google.gson.JsonObject();
        pointer.addProperty("version", version);
        Path pointerFile = root.resolve(POINTER);
        Path temporary = root.resolve("." + POINTER + ".tmp-" + UUID.randomUUID());
        Files.writeString(temporary, pointer.toString(), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, pointerFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, pointerFile, StandardCopyOption.REPLACE_EXISTING);
        }
        Path current = root.resolve("current");
        Path link = root.resolve(".current-" + UUID.randomUUID());
        try {
            Files.createSymbolicLink(link, directory);
            if (Files.isSymbolicLink(current) || !Files.exists(current)) {
                Files.move(link, current, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } else {
                Files.deleteIfExists(current);
                Files.move(link, current, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (IOException | UnsupportedOperationException e) {
            try {
                Files.deleteIfExists(link);
            } catch (IOException ignored) {
            }
            LOG.info("build toolchain: could not create the `current` symlink; using current.json ("
                    + e.getMessage() + ")");
        }
    }

    // -------------------------------------------------------------- download --

    /// Downloads one pinned artifact, trying the fastest usable origin first.
    ///
    /// The catalogue's digest is the trust anchor: a mirror can be slower or
    /// broken, but it cannot be accepted with different bytes. The probe only
    /// decides which URL is attempted first; every attempt is size- and
    /// SHA-256-checked, and a failed one is deleted before the next.
    ///
    /// @param artifact the pinned artifact
    /// @param target   where the bytes land
    /// @param cancelled polled between steps
    /// @throws IOException when every candidate fails
    private void fetch(Artifact artifact, Path target, BooleanSupplier cancelled)
            throws IOException {
        DownloadSourcePicker.Choice choice = sourcePicker.choose(
                artifact.url(), artifact.mirrors(), artifact.size(), magicOf(artifact.archive()));
        BootstrapTrace.note("toolchain", artifact.name() + " source: " + hostOf(choice.url())
                + " (" + choice.reason() + ")");
        if (choice.probes().size() > 1) {
            for (DownloadSourcePicker.Probe probe : choice.probes()) {
                LOG.info("build toolchain: " + artifact.name() + " " + probe.url() + " -> "
                        + (probe.ok() ? probe.speedKBps() + " KB/s" : probe.error()));
            }
        }
        IOException failure = null;
        for (String url : candidates(artifact.url(), artifact.mirrors(), choice.url())) {
            try {
                fetchOne(url, target, artifact.sha256(), artifact.size(), cancelled);
                sourcePicker.recordOutcome(url, true, null);
                return;
            } catch (CancellationException e) {
                throw e;
            } catch (IOException e) {
                Files.deleteIfExists(target);
                sourcePicker.recordOutcome(url, false, e.getMessage());
                LOG.info("build toolchain: " + artifact.name() + " from " + url
                        + " failed: " + e.getMessage());
                failure = e;
            }
        }
        throw new IOException("build_toolchain_download_failed: every source for "
                + artifact.name() + " failed"
                + (failure == null ? "" : ": " + failure.getMessage()), failure);
    }

    /// The candidate order for the download: the chosen source first, then the
    /// rest of the catalogue's list (mirrors, then official), without duplicates.
    private static List<String> candidates(String official, List<String> mirrors,
                                           String chosen) {
        java.util.LinkedHashSet<String> ordered = new java.util.LinkedHashSet<>();
        ordered.add(chosen);
        ordered.addAll(DownloadSourcePicker.candidates(official, mirrors));
        return List.copyOf(ordered);
    }

    private void fetchOne(String url, Path target, String sha256, long size,
                          BooleanSupplier cancelled) throws IOException {
        BootstrapCatalog.validateHttps(url);
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("cancelled");
        }
        try {
            source.fetch(url, target);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("build_toolchain_interrupted", e);
        }
        if (cancelled.getAsBoolean()) {
            Files.deleteIfExists(target);
            throw new CancellationException("cancelled");
        }
        long actualSize = Files.size(target);
        if (size > 0 && actualSize != size) {
            Files.deleteIfExists(target);
            throw new IOException("build_toolchain_download_invalid: " + url + " should be "
                    + size + " bytes, got " + actualSize);
        }
        String actual = BootstrapRuntimeManager.sha256(target);
        if (!actual.equalsIgnoreCase(sha256)) {
            Files.deleteIfExists(target);
            throw new IOException("build_toolchain_checksum_mismatch: " + url + " should have"
                    + " SHA-256 " + sha256 + ", got " + actual);
        }
    }

    /// The first bytes every candidate for this archive type must serve.
    ///
    /// This is what rejects a mirror that answers an HTML portal page with
    /// HTTP 200 — the failure mode a speed probe alone would reward.
    private static byte @Nullable [] magicOf(String archive) {
        return switch (archive == null ? "" : archive.toLowerCase(java.util.Locale.ROOT)) {
            case "tar.xz", "txz" -> new byte[]{(byte) 0xFD, '7', 'z', 'X', 'Z', 0};
            case "tar.gz", "tgz" -> new byte[]{0x1F, (byte) 0x8B};
            case "deb" -> new byte[]{'!', '<', 'a', 'r', 'c', 'h', '>', '\n'};
            case "zip" -> new byte[]{'P', 'K', 3, 4};
            case "7z.exe" -> new byte[]{'M', 'Z'};
            default -> null;
        };
    }

    private static String hostOf(String url) {
        try {
            String host = java.net.URI.create(url).getHost();
            return host == null || host.isBlank() ? url : host;
        } catch (RuntimeException e) {
            return url;
        }
    }

    /// Streams one pinned artifact over https.
    private static final class HttpSource implements BootstrapRuntimeManager.ArtifactSource {
        @Override
        public void fetch(String url, Path target) throws IOException, InterruptedException {
            BootstrapCatalog.validateHttps(url);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(20))
                    .GET()
                    .build();
            HttpResponse<InputStream> response = HTTP.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            BootstrapCatalog.validateHttps(response.uri().toString());
            if (response.statusCode() != 200) {
                try (InputStream ignored = response.body()) {
                    // Closed; the body is not the error message.
                }
                throw new IOException("build_toolchain_download_failed: HTTP "
                        + response.statusCode() + " for " + url);
            }
            Privileges.createPrivateFile(target);
            java.util.concurrent.atomic.AtomicBoolean finished =
                    new java.util.concurrent.atomic.AtomicBoolean();
            java.util.concurrent.atomic.AtomicBoolean stalled =
                    new java.util.concurrent.atomic.AtomicBoolean();
            java.util.concurrent.atomic.AtomicReference<InputStream> stream =
                    new java.util.concurrent.atomic.AtomicReference<>();
            Thread watchdog = new Thread(() -> {
                long lastSize = -1;
                long lastChange = System.nanoTime();
                try {
                    while (!finished.get()) {
                        Thread.sleep(2000);
                        if (finished.get()) {
                            return;
                        }
                        long size;
                        try {
                            size = Files.exists(target) ? Files.size(target) : 0;
                        } catch (IOException e) {
                            size = lastSize;
                        }
                        long now = System.nanoTime();
                        if (size > lastSize) {
                            lastSize = size;
                            lastChange = now;
                        } else if (now - lastChange > DOWNLOAD_STALL.toNanos()) {
                            stalled.set(true);
                            InputStream in = stream.get();
                            if (in != null) {
                                try {
                                    in.close();
                                } catch (IOException ignored) {
                                    // The read loop reports the stall anyway.
                                }
                            }
                            return;
                        }
                    }
                } catch (InterruptedException ignored) {
                    // The fetch finished and interrupted us.
                }
            }, "build-toolchain-download-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
            try (InputStream in = new BufferedInputStream(response.body());
                 OutputStream out = Files.newOutputStream(target)) {
                stream.set(in);
                byte[] buffer = new byte[1 << 16];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, read);
                }
            } catch (IOException e) {
                if (stalled.get()) {
                    throw new IOException("build_toolchain_download_stalled: " + url
                            + " sent no bytes for " + DOWNLOAD_STALL.toSeconds() + " s", e);
                }
                throw e;
            } finally {
                finished.set(true);
                watchdog.interrupt();
            }
        }
    }

    // ---------------------------------------------------------------- probes --

    private Tool resolve(String name, String... candidates) {
        for (String candidate : candidates) {
            Optional<Path> path = Executables.resolve(candidate, systemPath);
            if (path.isPresent()) {
                return new Tool(name, path.get().toString(), versionOf(path.get(), Map.of()));
            }
        }
        return new Tool(name, null, "");
    }

    private static Tool toolAt(String name, @Nullable Path path, Map<String, String> environment,
                               String version) {
        if (path == null || !Files.isRegularFile(path)) {
            return new Tool(name, null, "");
        }
        if (!version.isBlank()) {
            return new Tool(name, path.toString(), version);
        }
        return new Tool(name, path.toString(), versionOf(path, environment));
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

    private static @Nullable Path firstExisting(Path... paths) {
        for (Path path : paths) {
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        return null;
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

    // --------------------------------------------------------------- helpers --

    private static String prepend(Path directory, @Nullable String existing) {
        String prefix = directory.toString();
        if (existing == null || existing.isBlank()) {
            return prefix;
        }
        if (existing.equals(prefix) || existing.startsWith(prefix + java.io.File.pathSeparator)) {
            return existing;
        }
        return prefix + java.io.File.pathSeparator + existing;
    }

    private static void putIf(Map<String, String> map, String key, @Nullable String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }

    private static void putIfUnusable(Map<String, String> environment, String name,
                                      @Nullable String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String existing = environment.get(name);
        if (existing == null || existing.isBlank()) {
            environment.put(name, value);
        }
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

    private static String suffix(String archive) {
        return switch (archive == null ? "" : archive) {
            case "deb" -> ".deb";
            case "zip" -> ".zip";
            case "tar" -> ".tar";
            case "tar.gz", "tgz" -> ".tar.gz";
            case "tar.xz", "txz" -> ".tar.xz";
            default -> ".bin";
        };
    }

    private static ArtifactRecord artifactRecord(String name, String url, String sha256,
                                                 long size, String archive) {
        ArtifactRecord record = new ArtifactRecord();
        record.name = name;
        record.url = url;
        record.sha256 = sha256;
        record.size = size;
        record.archive = archive;
        return record;
    }

    private static @Nullable Path windowsBin(Path tree) {
        if (!Files.isDirectory(tree)) {
            return null;
        }
        try (Stream<Path> stream = Files.walk(tree, 5)) {
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

    private static void makeExecutable(Path file) {
        try {
            file.toFile().setExecutable(true, false);
        } catch (RuntimeException ignored) {
            // Windows has no mode bits.
        }
    }

    private static String normalize(@Nullable String name) {
        String value = name == null ? "" : name.replace('\\', '/');
        while (value.startsWith("./")) {
            value = value.substring(2);
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.isEmpty() || value.startsWith("/") || value.equals("..")
                || value.startsWith("../") || value.contains("/../")) {
            return null;
        }
        return value;
    }

    private static @Nullable String stripTop(@Nullable String name) {
        if (name == null) {
            return null;
        }
        int slash = name.indexOf('/');
        return slash < 0 ? null : name.substring(slash + 1);
    }

    private static @Nullable Path resolveInside(Path root, @Nullable String relative) {
        if (relative == null || relative.isEmpty()) {
            return null;
        }
        Path candidate = root.resolve(relative).normalize();
        return candidate.startsWith(root) ? candidate : null;
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path path : stream.toList()) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private record RunResult(int exitCode, String output) {
    }

    /// Runs the installed toolchain once, so a bundle that verified but cannot
    /// start is refused at install time.
    ///
    /// Being present and being runnable are different questions, and only the
    /// first one was asked. The pinned Linux bundle's `make` and `pkgconf` are
    /// Debian 11 executables needing GLIBC 2.27, and Zig needs 2.14: on an
    /// older glibc — or on musl — they unpack cleanly, match their digests, and
    /// then die in the loader. The panel called that a finished install and
    /// injected the result into every build, so the failure surfaced later as
    /// "the build script is broken" with nothing pointing at the toolchain.
    ///
    /// Only `cc`, `c++` and `make` are asked: they are the three a source build
    /// cannot proceed without, and each answers `--version` in milliseconds.
    ///
    /// @param toolchain the toolchain just published
    /// @return the first program that would not run, or `null` when all answered
    static @Nullable String smoke(Toolchain toolchain) {
        for (String name : List.of("cc", "c++", "make")) {
            String program = toolchain.pathOf(name);
            if (program == null) {
                // w64devkit ships `gcc.exe`/`g++.exe`, not `cc.exe`/`c++.exe`;
                // the Linux portable toolchain is the one that has `cc`. Asking
                // only for the POSIX names made a perfectly good Windows
                // install delete itself after the download and the extract.
                program = switch (name) {
                    case "cc" -> toolchain.pathOf("gcc");
                    case "c++" -> toolchain.pathOf("g++");
                    default -> null;
                };
            }
            if (program == null) {
                return name + " is not in the bundle";
            }
            try {
                RunResult result = run(List.of(program, "--version"), Map.of(),
                        Duration.ofSeconds(20), () -> false);
                if (result.exitCode() != 0) {
                    return name + " exited " + result.exitCode() + ": " + tail(result.output());
                }
            } catch (IOException e) {
                return name + " could not be started: " + e.getMessage();
            }
        }
        return null;
    }

    private static RunResult run(List<String> command, Map<String, String> environment,
                                 Duration timeout, BooleanSupplier cancelled) throws IOException {
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("cancelled");
        }
        ProcessBuilder builder = new ProcessBuilder(Privileges.asAgent(command))
                .redirectErrorStream(true);
        builder.environment().putAll(environment);
        // The probe is started through `setpriv`; on a minimal image that is
        // the closure's copy, which needs the closure's library path.
        BootstrapEnvironment.apply(builder, false);
        Process process = builder.start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                in.transferTo(output);
            } catch (IOException ignored) {
            }
        }, "build-toolchain-reader");
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
                    throw new IOException("build_toolchain_install_failed: command timed out: "
                            + String.join(" ", command));
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
            throw new IOException("build_toolchain_interrupted", e);
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

    /// Package-visible test seam: installs an explicit artifact list, so the
    /// Linux combination's publication, `current` pointer and failure cleanup
    /// can run against fake archives without a network.
    void installLinuxArtifacts(List<Artifact> artifacts,
                               BootstrapRuntimeManager.Progress progress,
                               BooleanSupplier cancelled) throws IOException {
        synchronized (lock) {
            BootstrapRuntimeManager.ensureToolchainsRoot();
            Privileges.keepRootOwnedDirectory(root);
            installLinux(artifacts, progress, cancelled);
        }
    }
}