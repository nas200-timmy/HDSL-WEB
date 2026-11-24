/*
 * HDSL-web
 * Copyright (C) 2026  HDSL-web contributors
 *
 * This program is free software; you can redistribute it and/or modify
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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// The helper executable ZCode's Windows agent spawner cannot live without.
///
/// On Windows, spawning an agent does not go through `child_process.spawn`: the
/// server first locates `zcode-runtime-host.exe` (upstream
/// `services/src/windows-runtime-host`, a CMake C++ project that wraps the spawn
/// in a job object with a control pipe) and throws
/// "Windows runtime host is missing. Prepare the runtime assets before starting
/// CLI." when none of its six candidates exists. The whole path is gated on
/// `process.platform === "win32"`, so a Linux-hosted panel never sees it — which
/// is exactly why this stayed invisible until a bare **Windows** deployment
/// tried to start an agent (measured 2026-11 against a panel-built 3.18.2).
/// Upstream's own `pnpm build:zcode` does not stage the asset either: it is
/// compiled from `scripts/windows-runtime-host/*.cpp` during the desktop /
/// remote-asset packaging steps, which a bare CLI build never runs.
///
/// The panel therefore provisions the asset itself. Two halves:
///
/// [provision] runs during [ZcodeBuilder]'s build, while the unpacked sources
/// are still on disk. It needs a MinGW-w64 C++ compiler; resolution order is
/// the panel's build toolchain (`<data>/toolchains/build/w64devkit-*`, the
/// w64devkit the build already installed), the `HDSL_ZCODE_MINGW` directory,
/// the panel's own toolchain cache (`<data>/hdsl/toolchains/mingw`), the
/// `PATH`, and — as the last resort — a download of llvm-mingw's latest
/// Windows x86_64 zip (override the URL with `HDSL_ZCODE_MINGW_URL` for
/// mirrors). The compile itself is the two
/// `add_executable` lines of upstream's CMakeLists reproduced verbatim
/// (`-std=c++17 -municode -static`), so no CMake and no upstream patch are
/// involved. MSVC is deliberately still not an option: unlike that several-GB
/// admin install, a mingw zip is a plain extraction.
///
/// [environment] runs at instance launch: when the asset exists, the launch
/// injects `ZCODE_SERVER_RUNTIME_ROOT`, which is candidate #3 in upstream's
/// `resolveHelper` — the one lookup that does not depend on the Electron
/// layout or the process's cwd.
///
/// Everything here degrades to "nothing": a failed or skipped provisioning
/// never fails the build and never blocks a launch — Web and TUI keep working,
/// only starting an agent on Windows keeps answering with upstream's own
/// error, same as before this class existed.
@NotNullByDefault
public final class ZcodeWindowsRuntimeHost {

    /// What upstream's `resolveHelper` appends to the root this variable names.
    private static final String TOOLS_RELATIVE = "tools/runtime-host";

    /// The two executables upstream spawns; both must exist before launch.
    private static final String HOST_EXE = "zcode-runtime-host.exe";
    private static final String HELPER_EXE = "zcode-installer-process-helper.exe";

    /// Compiler candidates, most specific first: a prefixed cross name pins the
    /// target even when the toolchain also ships unprefixed drivers.
    private static final List<String> COMPILER_CANDIDATES = List.of(
            "x86_64-w64-mingw32-g++.exe",
            "g++.exe",
            "x86_64-w64-mingw32-clang++.exe",
            "clang++.exe");

    /// The operator's own toolchain wins over everything else.
    private static final String ENV_TOOLCHAIN = "HDSL_ZCODE_MINGW";

    /// A mirror for the llvm-mingw zip, for networks where GitHub is slow.
    private static final String ENV_TOOLCHAIN_URL = "HDSL_ZCODE_MINGW_URL";

    /// Upstream's release feed; the asset picked below is the Windows x86_64
    /// build, whose clang targets both x64 and arm64, so one download serves
    /// every Windows host this panel is likely to meet.
    private static final String LLVM_MINGW_RELEASES_API =
            "https://api.github.com/repos/mstorsjo/llvm-mingw/releases/latest";
    private static final String LLVM_MINGW_ASSET_HINT = "ucrt-x86_64.zip";

    private static final Duration COMPILE_TIMEOUT = Duration.ofMinutes(5);

    private ZcodeWindowsRuntimeHost() {
    }

    /// The directory holding the staged asset: `<zcodeRoot>/assets`.
    public static Path assetsDir(Path zcodeRoot) {
        return zcodeRoot.resolve("assets");
    }

    /// Whether both executables are staged under the upstream layout.
    public static boolean isAvailable(Path zcodeRoot) {
        Path tools = assetsDir(zcodeRoot).resolve(TOOLS_RELATIVE);
        return Files.isRegularFile(tools.resolve(HOST_EXE))
                && Files.isRegularFile(tools.resolve(HELPER_EXE));
    }

    /// The environment additions for a ZCode server launch: the runtime root
    /// when the asset is staged, nothing otherwise.
    public static Map<String, String> environment(Path zcodeRoot) {
        if (!isAvailable(zcodeRoot)) {
            return Map.of();
        }
        return Map.of("ZCODE_SERVER_RUNTIME_ROOT", assetsDir(zcodeRoot).toString());
    }

    /// Compiles the runtime host from the unpacked build sources into
    /// `<zcodeRoot>/assets`, unless it is already there or cannot be provided.
    ///
    /// Never throws: provisioning is an enhancement, not a gate — the build
    /// stays green without it, and the failure reason goes to the log.
    ///
    /// @param sources  the unpacked ZCode source tree
    /// @param zcodeRoot `<data>/zcode`
    /// @param log      the build log
    public static void provision(Path sources, Path zcodeRoot, Consumer<String> log) {
        provision(sources, zcodeRoot, log, null);
    }

    /// The same, with the panel's build toolchain named as the first place to
    /// look for a compiler.
    ///
    /// @param sources  the unpacked ZCode source tree
    /// @param zcodeRoot `<data>/zcode`
    /// @param log      the build log
    /// @param buildToolchainBin the `bin/` of the toolchain the build resolved,
    ///                          or `null`
    public static void provision(Path sources, Path zcodeRoot, Consumer<String> log,
                                 @Nullable Path buildToolchainBin) {
        if (OperatingSystem.CURRENT_OS != OperatingSystem.WINDOWS) {
            log.accept("非 Windows 宿主：runtime host 只在 win32 上被使用，跳过制备");
            return;
        }
        if (isAvailable(zcodeRoot)) {
            log.accept("runtime host 资产已存在：" + assetsDir(zcodeRoot));
            return;
        }
        Path cppDir = sources.resolve("scripts").resolve("windows-runtime-host");
        if (!Files.isRegularFile(cppDir.resolve("main.cpp"))
                || !Files.isRegularFile(cppDir.resolve("installer-process-helper.cpp"))) {
            log.accept("该版本源码没有 scripts/windows-runtime-host（上游改过布局或更旧），跳过制备");
            return;
        }
        Optional<Path> compiler = findCompiler(zcodeRoot, log, buildToolchainBin);
        if (compiler.isEmpty()) {
            log.accept("!! 没有可用的 MinGW C++ 编译器，runtime host 未制备：Windows 上启动 agent 仍会报"
                    + " “Windows runtime host is missing”。可用 " + ENV_TOOLCHAIN
                    + " 指向一个 mingw 目录，或配置 " + ENV_TOOLCHAIN_URL + " 走镜像下载");
            return;
        }
        try {
            compile(compiler.get(), cppDir, zcodeRoot, log);
        } catch (Exception e) {
            LOG.warning("Could not provision the ZCode Windows runtime host", e);
            log.accept("!! runtime host 制备失败：" + e.getMessage()
                    + "（不影响本次构建；Windows agent 启动仍会报缺资产）");
        }
    }

    /// Compiles the two executables and stages them with a `build.json` digest,
    /// mirroring what upstream's own build script writes beside them.
    private static void compile(Path compiler, Path cppDir, Path zcodeRoot, Consumer<String> log)
            throws IOException, InterruptedException {
        Path out = Files.createTempDirectory("zcode-runtime-host-");
        try {
            String driver = compiler.getFileName().toString();
            log.accept("编译 runtime host：" + driver + "（" + compiler + "）");
            runCompiler(compiler, cppDir, out.resolve(HOST_EXE), "main.cpp", log);
            runCompiler(compiler, cppDir, out.resolve(HELPER_EXE), "installer-process-helper.cpp", log);

            Path tools = assetsDir(zcodeRoot).resolve(TOOLS_RELATIVE);
            Files.createDirectories(tools);
            Files.copy(out.resolve(HOST_EXE), tools.resolve(HOST_EXE),
                    StandardCopyOption.REPLACE_EXISTING);
            Files.copy(out.resolve(HELPER_EXE), tools.resolve(HELPER_EXE),
                    StandardCopyOption.REPLACE_EXISTING);
            JsonObject manifest = new JsonObject();
            manifest.addProperty("generator", "HDSL panel, " + driver + ", -std=c++17 -municode -static");
            manifest.addProperty("binary", sha256(tools.resolve(HOST_EXE)));
            manifest.addProperty("installerProcessHelper", sha256(tools.resolve(HELPER_EXE)));
            Files.writeString(tools.resolve("build.json"), manifest.toString(), StandardCharsets.UTF_8);
            log.accept("runtime host 已制备到 " + tools + "（x64；-municode -static，仅依赖 KERNEL32）");
        } finally {
            ZcodeBuilder.deleteRecursively(out);
        }
    }

    /// One `add_executable` line of upstream's CMakeLists, reproduced: C++17,
    /// Unicode entry point, statically linked — a helper that must run on any
    /// Windows box has no business carrying a mingw DLL dependency.
    private static void runCompiler(Path compiler, Path cppDir, Path output, String source,
                                    Consumer<String> log) throws IOException, InterruptedException {
        List<String> command = List.of(compiler.toString(),
                "-std=c++17", "-O2", "-municode", "-static",
                "-o", output.toString(), cppDir.resolve(source).toString());
        log.accept("$ " + String.join(" ", command));
        Process process = new ProcessBuilder(command)
                .directory(cppDir.toFile())
                .redirectErrorStream(true)
                .start();
        try (var reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.accept("[mingw] " + line);
            }
        }
        if (!process.waitFor(COMPILE_TIMEOUT.toMinutes(), TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IOException(source + " did not finish within " + COMPILE_TIMEOUT.toMinutes() + " minutes");
        }
        if (process.exitValue() != 0 || !Files.isRegularFile(output)) {
            throw new IOException(source + " exited with " + process.exitValue());
        }
    }

    /// Resolves a MinGW C++ compiler: operator override, panel cache, PATH, then
    /// a download of llvm-mingw into the panel's toolchain cache.
    private static Optional<Path> findCompiler(Path zcodeRoot, Consumer<String> log,
                                               @Nullable Path buildToolchainBin) {
        if (buildToolchainBin != null) {
            Optional<Path> built = findCompilerIn(buildToolchainBin);
            if (built.isPresent()) {
                return built;
            }
        }
        String override = System.getenv(ENV_TOOLCHAIN);
        if (override != null && !override.isBlank()) {
            Optional<Path> found = findCompilerIn(Path.of(override));
            if (found.isPresent()) {
                return found;
            }
            log.accept(ENV_TOOLCHAIN + " 指向的目录里没有 " + COMPILER_CANDIDATES + "：" + override);
        }
        Optional<Path> cached = findCompilerIn(toolchainCache(zcodeRoot));
        if (cached.isPresent()) {
            return cached;
        }
        String pathVariable = System.getenv("PATH");
        if (pathVariable == null || pathVariable.isBlank()) {
            return downloadToolchain(zcodeRoot, log);
        }
        Optional<Path> onPath = java.util.Arrays.stream(
                        pathVariable.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
                .filter(entry -> !entry.isBlank())
                .map(Path::of)
                .map(ZcodeWindowsRuntimeHost::findCompilerIn)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
        if (onPath.isPresent()) {
            return onPath;
        }
        return downloadToolchain(zcodeRoot, log);
    }

    /// Test hook: [findCompilerIn] under a name tests may call without reflection.
    static Optional<Path> compilerInForTest(Path directory) {
        return findCompilerIn(directory);
    }

    /// Looks for a compiler executable below a toolchain directory, tolerating
    /// every layout in the wild (winlibs and nuwen nest differently from
    /// llvm-mingw): a recursive walk for the known driver names.
    private static Optional<Path> findCompilerIn(Path directory) {
        if (!Files.isDirectory(directory)) {
            return Optional.empty();
        }
        try (var stream = Files.walk(directory, 6)) {
            List<Path> files = stream.filter(Files::isRegularFile).toList();
            for (String name : COMPILER_CANDIDATES) {
                Optional<Path> hit = files.stream()
                        .filter(file -> file.getFileName().toString().equalsIgnoreCase(name))
                        .findFirst();
                if (hit.isPresent()) {
                    return hit;
                }
            }
        } catch (IOException | UncheckedIOException | SecurityException e) {
            // PATH 里常有当前账号读不了的目录（Windows 上是
            // C:\Windows\System32\Com\dmp 这类），而 Files.walk 的失败是在
            // 流迭代时以 UncheckedIOException 抛出的——只 catch IOException
            // 会让一个无关目录把整个 ZCode runtime 制备打死。
            LOG.debug("Could not search " + directory + " for a mingw compiler: " + e.getMessage());
        }
        return Optional.empty();
    }

    /// The panel's own toolchain home, beside the managed Node runtimes:
    /// `<data>/hdsl/toolchains/mingw` (the same `<data>` the runtimes live in —
    /// see [MarkdownSsr]).
    private static Path toolchainCache(Path zcodeRoot) {
        return zcodeRoot.getParent().resolve("hdsl").resolve("toolchains").resolve("mingw");
    }

    /// Downloads llvm-mingw (Windows x86_64, UCRT) and unpacks it into the
    /// toolchain cache. GitHub's latest-release API answers which zip is current
    /// so this code does not carry a stale version pin; `HDSL_ZCODE_MINGW_URL`
    /// overrides the download entirely, which is what mirrors are for.
    private static Optional<Path> downloadToolchain(Path zcodeRoot, Consumer<String> log) {
        try {
            String url = System.getenv(ENV_TOOLCHAIN_URL);
            if (url == null || url.isBlank()) {
                url = resolveLatestLlvmMingwAsset(log);
            }
            if (url == null) {
                return Optional.empty();
            }
            Path cache = toolchainCache(zcodeRoot);
            Files.createDirectories(cache);
            Path zip = Files.createTempFile("llvm-mingw", ".zip");
            try {
                log.accept("下载 MinGW 工具链（只需一次，约 300 MB，缓存于 " + cache + "）：" + url);
                HttpClient client = HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .connectTimeout(java.time.Duration.ofSeconds(60))
                        .build();
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(java.time.Duration.ofMinutes(30))
                        .GET()
                        .build();
                HttpResponse<Path> response = client.send(request,
                        HttpResponse.BodyHandlers.ofFile(zip));
                if (response.statusCode() / 100 != 2) {
                    log.accept("!! 工具链下载返回 HTTP " + response.statusCode());
                    return Optional.empty();
                }
                log.accept("解包工具链…");
                unzip(zip, cache);
            } finally {
                Files.deleteIfExists(zip);
            }
            return findCompilerIn(cache);
        } catch (Exception e) {
            LOG.warning("Could not download a mingw toolchain for the ZCode runtime host", e);
            log.accept("!! 工具链下载/解包失败：" + e.getMessage());
            return Optional.empty();
        }
    }

    /// The browser URL of the Windows x86_64 asset on llvm-mingw's latest
    /// release, or `null` when the answer cannot be read.
    private static @Nullable String resolveLatestLlvmMingwAsset(Consumer<String> log) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(java.time.Duration.ofSeconds(30))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(LLVM_MINGW_RELEASES_API))
                    .timeout(java.time.Duration.ofMinutes(2))
                    .header("Accept", "application/vnd.github+json")
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.accept("!! 读取 llvm-mingw 发行列表返回 HTTP " + response.statusCode()
                        + "（可用 " + ENV_TOOLCHAIN_URL + " 指定镜像）");
                return null;
            }
            JsonArray assets = JsonParser.parseString(response.body())
                    .getAsJsonObject().getAsJsonArray("assets");
            for (var element : assets) {
                JsonObject asset = element.getAsJsonObject();
                String name = asset.get("name").getAsString();
                if (name.contains(LLVM_MINGW_ASSET_HINT) && name.endsWith(".zip")) {
                    return asset.get("browser_download_url").getAsString();
                }
            }
            log.accept("!! llvm-mingw 最新发行里没有 " + LLVM_MINGW_ASSET_HINT
                    + "（上游改了命名？可用 " + ENV_TOOLCHAIN_URL + " 指定）");
            return null;
        } catch (Exception e) {
            LOG.warning("Could not resolve the latest llvm-mingw release", e);
            log.accept("!! 读取 llvm-mingw 发行列表失败：" + e.getMessage()
                    + "（可用 " + ENV_TOOLCHAIN_URL + " 指定镜像）");
            return null;
        }
    }

    /// Streams a zip into a directory, replacing what a previous attempt left.
    /// Java's own reader is enough — the toolchain zip needs no external tool,
    /// which on a bare Windows box is exactly the point.
    private static void unzip(Path zip, Path target) throws IOException {
        Files.createDirectories(target);
        try (InputStream file = Files.newInputStream(zip);
             ZipInputStream entries = new ZipInputStream(file, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = entries.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                // A toolchain archive is trusted, but ".." costs nothing to refuse.
                Path destination = target.resolve(name).normalize();
                if (!destination.startsWith(target)) {
                    throw new IOException("the toolchain zip names a path outside its tree: " + name);
                }
                Files.createDirectories(destination.getParent());
                try (OutputStream out = Files.newOutputStream(destination)) {
                    entries.transferTo(out);
                }
            }
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[1 << 16];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable", e);
        }
    }

}
