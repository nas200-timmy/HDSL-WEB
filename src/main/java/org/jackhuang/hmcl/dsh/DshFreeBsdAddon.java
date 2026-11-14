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

import com.google.gson.JsonObject;
import kala.compress.archivers.tar.TarArchiveEntry;
import kala.compress.archivers.tar.TarArchiveInputStream;
import org.jackhuang.hmcl.util.platform.Architecture;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

/// **实验手段**：在 FreeBSD 上从源码构建 dsh 缺失的原生模块。
///
/// dsh 0.2.x 依赖 `node-addon-require-builtin`，这个包没有 FreeBSD 二进制，
/// 上游的 `runtime_context/platform.cc` 也没有 FreeBSD 分支——但缺失的只是两个
/// `#elif`：Linux x86_64 的实现本来就是 `dlsym(RTLD_DEFAULT, ...)` 加 SysV 调用，
/// FreeBSD 同样成立。于是这里做最原始的事：拉取**固定 commit** 的源码、校验
/// SHA-256、打上两条分支、用 node-gyp 编译、把产物放进面板缓存。
///
/// 只在操作员显式点击时执行一次。构建成功后 `dsh` 才被允许在 FreeBSD 上安装，
/// 安装完成后 [#inject] 把产物复制进实例的依赖树（pnpm 每次安装都会重写
/// `node_modules`，所以注入发生在安装之后）。
///
/// @see DshVersionManager#installableOn(OperatingSystem)
@NotNullByDefault
public final class DshFreeBsdAddon {

    /// Pinned upstream commit. Moving this is a deliberate act: a different tree
    /// is code that will run in every dsh instance on this host.
    static final String COMMIT = "a58d3599a2e551d7d706d1ff796c9fd4bbaff63c";
    static final String SOURCE_URL =
            "https://codeload.github.com/deepseek-ai/dsh-node-addon-require-builtin/tar.gz/" + COMMIT;
    /// SHA-256 of the tarball above, measured once when the pin was chosen.
    static final String SOURCE_SHA256 =
            "286616f15e4f12013988e45ad27a69b4968a9f059c8bd351e412505230577948";
    static final String BINARY = "require_builtin.node";
    /// The loader looks here: `<package>/build/napi/<abi>-<platform>-<arch>/`.
    static final String ABI_TAG = "napi-v9-freebsd-x64";

    /// The build is single-flight: a second click joins the running one.
    private static final Object LOCK = new Object();
    private static final AtomicReference<Build> BUILD = new AtomicReference<>(Build.idle());

    private DshFreeBsdAddon() {
    }

    /// One build's state, as the settings card shows it.
    ///
    /// @param state   `idle`, `running`, `done` or `failed`
    /// @param message one line describing where it is
    /// @param log     the tail of the child processes' output
    /// @param error   why it failed, or `null`
    public record Build(String state, String message, String log, @Nullable String error) {
        static Build idle() {
            return new Build("idle", prepared() ? "已构建" : "未构建", "", null);
        }
    }

    /// Whether this escape hatch applies: FreeBSD on x86-64, the one path the
    /// upstream code shares with Linux.
    public static boolean applicable() {
        return OperatingSystem.CURRENT_OS == OperatingSystem.FREEBSD
                && Architecture.CURRENT_ARCH == Architecture.X86_64;
    }

    /// Where the built binary lives, shared by every instance.
    public static Path binary() {
        return DshPaths.ROOT.resolve("freebsd-addon").resolve(ABI_TAG).resolve(BINARY);
    }

    /// Whether a usable binary has been built.
    public static boolean prepared() {
        return Files.isRegularFile(binary());
    }

    /// The current status, for `GET /api/dsh/freebsd-addon`.
    public static JsonObject status() {
        Build build = BUILD.get();
        JsonObject json = new JsonObject();
        json.addProperty("applicable", applicable());
        json.addProperty("prepared", prepared());
        json.addProperty("state", build.state());
        json.addProperty("message", build.message());
        json.addProperty("log", build.log());
        if (build.error() != null) {
            json.addProperty("error", build.error());
        }
        json.addProperty("commit", COMMIT);
        json.addProperty("sourceUrl", SOURCE_URL);
        return json;
    }

    /// Starts a build in the background. Idempotent while one is running.
    public static synchronized void start() {
        if (!applicable() || prepared()) {
            return;
        }
        if ("running".equals(BUILD.get().state())) {
            return;
        }
        BUILD.set(new Build("running", "准备源码", "", null));
        Thread thread = new Thread(DshFreeBsdAddon::runBuild, "dsh-freebsd-addon-build");
        thread.setDaemon(true);
        thread.start();
    }

    /// Copies the prepared binary into an installed dsh tree. pnpm rewrites
    /// `node_modules` on every install, so this runs after each one.
    ///
    /// @param dshDirectory the instance's `dsh` directory
    /// @throws IOException when the binary exists but cannot be copied
    public static void inject(Path dshDirectory) throws IOException {
        if (!prepared() || !applicable()) {
            return;
        }
        Path pnpm = dshDirectory.resolve("node_modules").resolve(".pnpm");
        if (!Files.isDirectory(pnpm)) {
            return;
        }
        try (var entries = Files.newDirectoryStream(pnpm, "node-addon-require-builtin@*")) {
            for (Path entry : entries) {
                Path packageDir = entry.resolve("node_modules").resolve("node-addon-require-builtin");
                if (!Files.isDirectory(packageDir)) {
                    continue;
                }
                Path target = packageDir.resolve("build").resolve("napi").resolve(ABI_TAG).resolve(BINARY);
                Files.createDirectories(target.getParent());
                Files.copy(binary(), target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void runBuild() {
        List<String> log = new ArrayList<>();
        Consumer<String> sink = line -> {
            synchronized (log) {
                log.add(line);
                while (log.size() > 400) {
                    log.remove(0);
                }
                BUILD.set(new Build("running", line, tail(log), null));
            }
        };
        try {
            DshNodeRuntime runtime = DshNodeRuntime.detectForNpm()
                    .orElseThrow(() -> new DshException(
                            "需要带 npm 的 Node.js 才能构建（面板托管运行时或 PATH 中的 Node）"));
            Path work = DshPaths.ROOT.resolve("freebsd-addon").resolve("work");
            deleteTree(work);
            Files.createDirectories(work);

            Path archive = work.resolve("source.tar.gz");
            sink.accept("下载源码 " + SOURCE_URL);
            download(archive);
            String digest = sha256(archive);
            if (!SOURCE_SHA256.equalsIgnoreCase(digest)) {
                throw new DshException("源码校验失败：期望 " + SOURCE_SHA256 + "，实际 " + digest
                        + "。上游 commit 变了？不要跳过校验。");
            }
            sink.accept("校验通过 " + digest);

            Path source = extract(archive, work.resolve("src"));
            patch(source, sink);

            Path nativeDir = findNativeDir(source);
            sink.accept("安装构建依赖 node-addon-api");
            run(List.of(runtime.npm().toString(), "install", "node-addon-api@^8.9.0",
                    "--no-save", "--ignore-scripts",
                    "--registry=" + NpmRegistry.effective().registry()), nativeDir, runtime, null, sink);

            Path nodeGyp = nodeGypOf(runtime);
            sink.accept("编译原生模块 " + nodeGyp);
            run(List.of(runtime.node().toString(), nodeGyp.toString(), "rebuild",
                    "-Dnarb_backend=napi"), nativeDir, runtime,
                    java.util.Map.of("CXXFLAGS", "-DNARB_PRODUCT=1"), sink);

            Path built = nativeDir.resolve("build").resolve("Release").resolve(BINARY);
            if (!Files.isRegularFile(built)) {
                throw new DshException("编译结束但没有找到 " + built);
            }
            Files.createDirectories(binary().getParent());
            Files.copy(built, binary(), StandardCopyOption.REPLACE_EXISTING);
            deleteTree(work);
            BUILD.set(new Build("done", "构建完成，可以安装 dsh 了", "", null));
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            BUILD.set(new Build("failed", "构建失败", tail(log), message));
        }
    }

    /// Inserts the two FreeBSD branches into a checked-out tree.
    ///
    /// @param source the extracted repository root
    /// @param log    where to say what was patched
    /// @throws IOException when the expected anchors are missing
    static void patch(Path source, Consumer<String> log) throws IOException {
        Path context = source.resolve("packages/native/src/runtime_context/platform.cc");
        Path probe = source.resolve("packages/native/src/runtime_probe/platform.cc");

        String contextText = Files.readString(context);
        String contextAnchor = "#elif defined(__linux__) && defined(__x86_64__)\n"
                + "  return ReadLinuxX64CurrentV8Context(isolate, symbols);";
        String contextPatch = contextAnchor + "\n"
                + "#elif defined(__FreeBSD__) && defined(__x86_64__)\n"
                + "  // The SysV x86-64 ABI and dlsym(RTLD_DEFAULT) are the same here.\n"
                + "  return ReadDirectCurrentV8Context(\"freebsd-x64\", isolate, symbols);";
        if (!contextText.contains(contextAnchor)) {
            throw new IOException("context reader anchor missing in " + context);
        }
        if (!contextText.contains("defined(__FreeBSD__)")) {
            Files.writeString(context, contextText.replace(contextAnchor, contextPatch));
            log.accept("已打补丁：runtime_context/platform.cc");
        }

        String probeText = Files.readString(probe);
        String probeAnchor = "#elif defined(__linux__) && defined(__x86_64__)\n"
                + "  return ParseLinuxX64BuiltinModuleRequireGetterOffset(getter);";
        String probePatch = probeAnchor + "\n"
                + "#elif defined(__FreeBSD__) && defined(__x86_64__)\n"
                + "  return ParseLinuxX64BuiltinModuleRequireGetterOffset(getter);";
        if (!probeText.contains(probeAnchor)) {
            throw new IOException("getter parser anchor missing in " + probe);
        }
        if (!probeText.contains("defined(__FreeBSD__)")) {
            Files.writeString(probe, probeText.replace(probeAnchor, probePatch));
            log.accept("已打补丁：runtime_probe/platform.cc");
        }
    }

    private static Path findNativeDir(Path source) throws IOException {
        try (var entries = Files.newDirectoryStream(source, "packages")) {
            for (Path ignored : entries) {
                Path nativeDir = source.resolve("packages").resolve("native");
                if (Files.isRegularFile(nativeDir.resolve("binding.gyp"))) {
                    return nativeDir;
                }
            }
        }
        throw new IOException("源码里没有 packages/native/binding.gyp");
    }

    /// `<node>/bin/npm` is a symlink into `lib/node_modules/npm`; node-gyp ships
    /// inside that npm, and using it avoids downloading a second copy.
    static Path nodeGypOf(DshNodeRuntime runtime) throws IOException {
        if (runtime.npm() == null) {
            throw new IOException("this Node.js has no npm");
        }
        Path npm = runtime.npm().toRealPath();
        Path gyp = npm.getParent().getParent().resolve("node_modules")
                .resolve("node-gyp").resolve("bin").resolve("node-gyp.js");
        if (!Files.isRegularFile(gyp)) {
            throw new IOException("npm 里没有 node-gyp：" + gyp);
        }
        return gyp;
    }

    private static void download(Path target) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(SOURCE_URL))
                .timeout(Duration.ofMinutes(5))
                .header("User-Agent", org.jackhuang.hmcl.Metadata.NAME + "/"
                        + org.jackhuang.hmcl.Metadata.VERSION)
                .GET().build();
        HttpResponse<Path> response = client.send(request,
                HttpResponse.BodyHandlers.ofFile(target));
        if (response.statusCode() != 200) {
            throw new IOException("下载源码失败：HTTP " + response.statusCode());
        }
    }

    private static Path extract(Path archive, Path target) throws IOException {
        Files.createDirectories(target);
        Path root = null;
        try (InputStream raw = Files.newInputStream(archive);
             GZIPInputStream gzip = new GZIPInputStream(new BufferedInputStream(raw));
             TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.contains("..")) {
                    throw new IOException("源码包含可疑路径：" + name);
                }
                Path out = target.resolve(name).normalize();
                if (!out.startsWith(target)) {
                    throw new IOException("源码包含越界路径：" + name);
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(tar, out, StandardCopyOption.REPLACE_EXISTING);
                }
                if (root == null) {
                    int slash = name.indexOf('/');
                    root = target.resolve(slash < 0 ? name : name.substring(0, slash));
                }
            }
        }
        if (root == null || !Files.isDirectory(root)) {
            throw new IOException("源码包是空的");
        }
        return root;
    }

    private static void run(List<String> command, Path directory, DshNodeRuntime runtime,
                            @Nullable java.util.Map<String, String> extraEnvironment,
                            Consumer<String> log) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile());
        builder.redirectErrorStream(true);
        builder.environment().putAll(runtime.pathEnvironment());
        if (extraEnvironment != null) {
            builder.environment().putAll(extraEnvironment);
        }
        Process process = builder.start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.accept(line);
            }
        }
        int code = process.waitFor();
        if (code != 0) {
            throw new IOException(String.join(" ", command) + " 退出码 " + code);
        }
    }

    private static String sha256(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String tail(List<String> log) {
        synchronized (log) {
            int from = Math.max(0, log.size() - 40);
            return String.join("\n", log.subList(from, log.size()));
        }
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            for (Path item : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        }
    }
}