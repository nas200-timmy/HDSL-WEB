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
package org.jackhuang.hmcl.web.brand;

import org.jackhuang.hmcl.dsh.DshNodeRuntime;
import org.jackhuang.hmcl.dsh.NpmRegistry;
import org.jackhuang.hmcl.util.io.FileUtils;
import org.jackhuang.hmcl.util.io.Links;
import org.jackhuang.hmcl.util.platform.Architecture;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Installs and enumerates the tool releases of one [Brand].
///
/// Three source flavours:
///
/// - **npm brands** install a published package with the same command the dsh
///   installer uses, minus the lockstep machinery:
///   ```
///   pnpm add --dir <releases>/<version> <pkg>@<version> \
///       --reporter=append-only --registry=<effective> \
///       --config.dangerously-allow-all-builds=true
///   ```
/// - **GitHub brands** download the release tag's source zip over HTTPS
///   (`codeload.github.com/<slug>/zip/refs/tags/<tag>`, retried; no `git`
///   involved — the git transport to github.com is cut mid-handshake on this
///   deployment's network), unpack it, then `npm ci -w server -w web` (only
///   the workspaces the web mode needs — the desktop workspace's Electron
///   binary is never downloaded), then `npm run build -w web`.
/// - **Octop** downloads the prebuilt portable archive from GitHub Releases
///   (CPython 3.12 + every Python dependency included) and replaces only its
///   dashboard with the patched SPA bundled in this jar.
///
/// Two npm details are load-bearing:
///
/// - `--config.dangerously-allow-all-builds=true` — pnpm 10 and newer refuse to
///   run dependency lifecycle scripts by default, and Kimi Code's `postinstall`
///   is what downloads its native binary. Without the flag the install
///   "succeeds" into a prefix with no working executable.
/// - `--registry` is the panel's effective download source, so a mirror-first
///   deployment installs from the mirror like everything else. (Kimi Code's
///   postinstall still reaches its vendor CDN for the binary; fully offline
///   installs of that brand are impossible, which the docs say.)
///
/// Each version lands in `releases/<version>/`; a `current` symlink points at
/// the newest successful install and is what new instances default to.
@NotNullByDefault
public final class BrandInstaller {

    /// One installed release, as listed by the panel.
    public record Release(String version, String path, boolean current) {
    }

    static final String RELEASES_DIR = "releases";
    private static final String CURRENT_LINK = "current";
    private static final String INSTALL_LOG = "install.log";
    /// Pinned uv release for the source fallback. `latest/download` redirects
    /// through a host that times out on this network; a pinned tag serves the
    /// asset directly.
    private static final String UV_VERSION = "0.7.13";

    /// The HTTP client for GitHub source downloads — its own instance, with a
    /// longer connect timeout than the catalog's (an 11 MB body in the default
    /// 15 s budget would be tight on a slow route).
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private BrandInstaller() {
    }

    /// Installs one version of the brand's package into `releases/<version>`.
    ///
    /// Idempotent: an already-installed version reports done without running
    /// the installer again. The merged output is appended to
    /// `<brandRoot>/install.log`.
    ///
    /// @param brand the brand
    /// @param version the exact version to install
    /// @param brandRoot the brand's directory under the data directory
    /// @param message progress sink for the task record
    /// @throws BrandException when a required tool is missing, the install
    ///                          fails, or the installed release is incomplete
    public static void install(Brand brand, String version, Path brandRoot, Consumer<String> message)
            throws BrandException {
        Path releases = brandRoot.resolve(RELEASES_DIR);
        Path target = releases.resolve(version);
        boolean installed = Files.exists(target.resolve(brand.installMarker()));
        if (installed && brand == Brand.OCTOP) {
            // A source-built release has the same marker; asking for the
            // package layout must replace it, not report "already installed".
            installed = "package".equals(markerMode(target));
        }
        if (installed) {
            message.accept(brand.sourceLabel() + " " + version + " is already installed");
            pointCurrentAt(brandRoot, target);
            return;
        }
        if (brand == Brand.OCTOP) {
            installOctop(brand, version, brandRoot, target, message);
            pointCurrentAt(brandRoot, target);
            message.accept("Installed " + brand.sourceLabel() + " " + version);
            LOG.info("Installed " + brand.sourceLabel() + " " + version + " into " + target);
            return;
        }
        if (brand.source() == Brand.Source.GITHUB) {
            installGitHub(brand, version, brandRoot, target, message);
            pointCurrentAt(brandRoot, target);
            message.accept("Installed " + brand.sourceLabel() + " " + version);
            LOG.info("Installed " + brand.sourceLabel() + " " + version + " into " + target);
            return;
        }

        Path bin = target.resolve(brand.installMarker());
        DshNodeRuntime runtime = DshNodeRuntime.detect()
                .or(DshNodeRuntime::detectManaged)
                .orElseThrow(() -> new BrandException("Node.js was not found on PATH and no panel-managed runtime "
                        + "is installed; installing " + brand.npmPackage() + " needs one. "
                        + "Open Settings -> Node.js for a one-click managed runtime, or install Node.js yourself."));
        String pnpm = runtime.pnpm() == null
                ? "pnpm"
                : runtime.pnpm().toString();
        String registry = NpmRegistry.effective().registry();

        try {
            Files.createDirectories(target);
            // pnpm add wants a package.json to extend; give it a minimal private one.
            Path manifest = target.resolve("package.json");
            if (!Files.exists(manifest)) {
                Files.writeString(manifest,
                        "{\"name\":\"hdsl-brand-" + brand.id() + "\",\"private\":true}\n",
                        StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new BrandException("Failed to prepare " + target, e);
        }

        List<String> command = new ArrayList<>(List.of(
                pnpm, "add", "--dir", target.toString(),
                brand.npmPackage() + "@" + version,
                "--reporter=append-only",
                "--registry=" + registry,
                "--config.dangerously-allow-all-builds=true"));
        // pnpm's default (isolated) layout leaves a package's own dependencies as links
        // beside it inside `.pnpm`, and on Windows those links are not created for a
        // package that ships a `node_modules` directory of its own — Kimi Code does.
        // The install then reports success while the tool cannot resolve `ws` at all
        // (measured on Windows 10, pnpm 11.28.6: `ERR_MODULE_NOT_FOUND: ws`). A hoisted
        // layout puts the dependencies in the project's `node_modules`, which is what
        // these tools were written against; POSIX keeps the isolated layout it had.
        if (org.jackhuang.hmcl.util.platform.OperatingSystem.CURRENT_OS
                == org.jackhuang.hmcl.util.platform.OperatingSystem.WINDOWS) {
            command.add("--config.node-linker=hoisted");
        }
        message.accept("Installing " + brand.npmPackage() + " " + version);
        LOG.info("Installing " + brand.npmPackage() + " " + version + ": " + String.join(" ", command));

        Path log = brandRoot.resolve(INSTALL_LOG);
        try (OutputStreamAppend output = new OutputStreamAppend(log);
                BufferedReader reader = run(command, target, runtime.pathEnvironment(), output)) {
            StringBuilder tail = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.writeLine(line);
                if (tail.length() > 0) {
                    tail.append(" | ");
                }
                tail.append(line);
                if (tail.length() > 2000) {
                    tail.delete(0, tail.length() - 1000);
                }
                message.accept(line);
            }
            int code = output.exitCode();
            if (code != 0) {
                throw new BrandException(brand.npmPackage() + " " + version + " failed to install (exit " + code
                        + "): " + tail);
            }
        } catch (IOException e) {
            throw new BrandException("Failed to run pnpm for " + brand.npmPackage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrandException("Installing " + brand.npmPackage() + " was interrupted", e);
        }

        if (!Files.exists(bin)) {
            throw new BrandException(brand.npmPackage() + " " + version
                    + " installed without its executable (" + bin + "); the package's install scripts may have "
                    + "been skipped or failed — see " + log);
        }
        pointCurrentAt(brandRoot, target);
        message.accept("Installed " + brand.npmPackage() + " " + version);
        LOG.info("Installed " + brand.npmPackage() + " " + version + " into " + target);
    }

    /// The GitHub-brand install: download the tag's source zip, unpack it,
    /// install the server and web workspaces, build the web app.
    ///
    /// Deliberately not `git clone`: on this deployment's network github.com's
    /// git transport is cut mid-handshake routinely, while codeload serves the
    /// whole source in about a second and a half — a plain, retryable HTTPS
    /// download that also removes the `git` dependency entirely.
    ///
    /// The desktop workspace is deliberately **not** installed: nothing in it
    /// serves the web UI, and its Electron dependency would download a binary
    /// the panel never runs. (`ELECTRON_SKIP_BINARY_DOWNLOAD` is set anyway as
    /// insurance against a future lockfile that pulls it in through another
    /// workspace.)
    private static void installGitHub(Brand brand, String version, Path brandRoot, Path target,
                                      Consumer<String> message) throws BrandException {
        DshNodeRuntime runtime = DshNodeRuntime.detect()
                .or(DshNodeRuntime::detectManaged)
                .orElseThrow(() -> new BrandException("Node.js was not found on PATH and no panel-managed runtime "
                        + "is installed; installing " + brand.sourceLabel() + " needs one. "
                        + "Open Settings -> Node.js for a one-click managed runtime, or install Node.js yourself."));
        String registry = NpmRegistry.effective().registry();
        Path log = brandRoot.resolve(INSTALL_LOG);

        // A leftover half-unpacked attempt would mix with the new sources.
        if (Files.exists(target)) {
            FileUtils.deleteDirectoryQuietly(target);
        }

        String url = "https://codeload.github.com/" + brand.githubSlug()
                + "/zip/refs/tags/" + brand.releaseTag(version);
        BrandException downloadFailure = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            if (attempt > 1) {
                message.accept("Download did not finish; retrying (attempt " + attempt + " of 3)");
            }
            try {
                message.accept("Downloading " + url);
                LOG.info("Downloading " + url + " (attempt " + attempt + " of 3)");
                extractZip(download(url), target);
                downloadFailure = null;
                break;
            } catch (BrandException e) {
                downloadFailure = e;
            }
        }
        if (downloadFailure != null) {
            throw downloadFailure;
        }

        java.util.Map<String, String> environment = new java.util.HashMap<>(runtime.pathEnvironment());
        environment.put("ELECTRON_SKIP_BINARY_DOWNLOAD", "1");
        // `--include-workspace-root` is load-bearing: the engine package
        // (`claude-web-engine`, the community Claude Code build that speaks
        // OpenAI/Gemini provider protocols) is a dependency of the ROOT
        // project, not of the server workspace — installing only the two
        // workspaces leaves it out, the app silently falls back to the
        // official Claude Code, and every OpenAI-type provider stops working
        // (measured 2026-10-12 on 0.2.1: the startup banner reported `runtime:
        // claude 2.1.283`, the SDK's own binary).
        // The engine tarball is an absolute GitHub URL, and this network drops
        // github.com mid-transfer routinely: npm's own retries plus a whole-run
        // retry loop. `npm ci` rebuilds node_modules from scratch, so a failed
        // run leaves nothing half-installed to reason about.
        List<String> npmCi = List.of("npm", "ci", "-w", "server", "-w", "web", "--include-workspace-root",
                "--registry=" + registry,
                "--fetch-retries=5", "--fetch-retry-maxtimeout=120000", "--fetch-timeout=600000");
        BrandException ciFailure = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            if (attempt > 1) {
                message.accept("Dependency install did not finish; retrying (attempt " + attempt + " of 3)");
            }
            try {
                runChecked(npmCi, target, environment, log, message, "installing dependencies");
                ciFailure = null;
                break;
            } catch (BrandException e) {
                ciFailure = e;
            }
        }
        if (ciFailure != null) {
            throw ciFailure;
        }
        runChecked(List.of("npm", "run", "build", "-w", "web"),
                target, environment, log, message, "building the web app");

        if (!Files.exists(target.resolve(brand.installMarker()))) {
            throw new BrandException(brand.sourceLabel() + " " + version + " built without its web app ("
                    + target.resolve(brand.installMarker()) + ") — see " + log);
        }
    }

    /// The Octop install: a prebuilt portable archive from the GitHub release,
    /// then the patched dashboard on top.
    ///
    /// The archive already carries CPython 3.12 and every Python dependency, so
    /// the panel does not need a Python toolchain (or Docker) to install it.
    /// Only the SPA is replaced: upstream builds for `/`, while this instance
    /// is mounted at `/i/<token>/` and needs the runtime-base patch.
    private static void installOctop(Brand brand, String version, Path brandRoot, Path target,
                                     Consumer<String> message) throws BrandException {
        String platform = octopPlatform();
        String asset = "Octop-portable-" + platform + "-" + version + ".zip";
        String base = System.getenv("OCTOP_RELEASE_BASE");
        if (base == null || base.isBlank()) {
            base = "https://github.com/" + brand.githubSlug() + "/releases/download";
        }
        String url = base.replaceAll("/$", "") + "/" + brand.releaseTag(version) + "/" + asset;

        if (Files.exists(target)) {
            FileUtils.deleteDirectoryQuietly(target);
        }
        message.accept("Downloading " + url);
        LOG.info("Downloading Octop portable archive " + url);
        extractZip(download(url), target);
        markExecutable(target.resolve("runtime/bin"));

        Path dashboard = target.resolve("packages/octop/dashboard");
        if (!Files.isDirectory(dashboard)) {
            throw new BrandException("Octop archive has no packages/octop/dashboard: " + target);
        }
        FileUtils.deleteDirectoryQuietly(dashboard);
        String resource = "/octop/octop-dashboard-" + version + ".zip";
        try (InputStream patched = BrandInstaller.class.getResourceAsStream(resource)) {
            if (patched == null) {
                throw new BrandException("This panel build has no patched Octop dashboard for " + version
                        + " (" + resource + "); use the version bundled with this panel");
            }
            extractZip(patched, dashboard);
        } catch (IOException e) {
            throw new BrandException("Could not unpack the patched Octop dashboard: " + e.getMessage(), e);
        }
        if (!Files.exists(dashboard.resolve("index.html"))) {
            throw new BrandException("Octop " + version + " installed without its dashboard ("
                    + dashboard.resolve("index.html") + ")");
        }
        writeMarker(target, "package", version);
        message.accept("Installed Octop " + version + " with the patched dashboard");
    }

    /// The last-resort source build: tag archive → dashboard patch/build →
    /// `uv sync` (CPython 3.12 + dependencies) → `.venv/bin/octop`.
    ///
    /// Only Linux/macOS. Windows keeps the portable path: compiling the Python
    /// dependency tree there needs a toolchain the panel deliberately does not
    /// manage, and the portable archive already works.
    static void installOctopSource(Brand brand, String version, Path brandRoot, Path target,
                                   Consumer<String> message) throws BrandException {
        // Keep the previous release until the new build is complete: a failed
        // source build must not take a working package install with it.
        Path backup = target.resolveSibling(target.getFileName() + ".previous");
        try {
            if (Files.exists(backup)) {
                FileUtils.deleteDirectoryQuietly(backup);
            }
            if (Files.exists(target)) {
                Files.move(target, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new BrandException("Could not stage the Octop source build: " + e.getMessage(), e);
        }
        boolean complete = false;
        try {
            installOctopSourceInto(brand, version, brandRoot, target, message);
            complete = true;
        } finally {
            if (complete) {
                FileUtils.deleteDirectoryQuietly(backup);
            } else {
                FileUtils.deleteDirectoryQuietly(target);
                if (Files.exists(backup)) {
                    try {
                        Files.move(backup, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        LOG.warning("Could not restore the previous Octop release at " + target, e);
                    }
                }
            }
        }
    }

    private static void installOctopSourceInto(Brand brand, String version, Path brandRoot, Path target,
                                               Consumer<String> message) throws BrandException {
        if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
            throw new BrandException("local source compilation is not supported on Windows; "
                    + "turn sourceBuild off and use the prebuilt portable archive");
        }
        if (Files.exists(target)) {
            FileUtils.deleteDirectoryQuietly(target);
        }
        String url = "https://codeload.github.com/" + brand.githubSlug()
                + "/zip/refs/tags/" + brand.releaseTag(version);
        message.accept("Downloading source " + url);
        extractZip(download(url), target);

        Path patch = target.resolve(".hdsl-octop.patch");
        try (InputStream in = BrandInstaller.class.getResourceAsStream("/octop/octop-runtime-base.patch")) {
            if (in == null) {
                throw new BrandException("This panel build has no Octop dashboard patch");
            }
            Files.copy(in, patch, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new BrandException("Could not write the Octop dashboard patch: " + e.getMessage(), e);
        }
        Path log = brandRoot.resolve(INSTALL_LOG);
        runChecked(List.of("git", "apply", "--whitespace=nowarn", patch.toString()),
                target, Map.of(), log, message, "applying the dashboard patch");

        String registry = NpmRegistry.effective().registry();
        Path dashboard = target.resolve("dashboard");
        Map<String, String> npmEnv = new HashMap<>();
        npmEnv.put("NPM_CONFIG_REGISTRY", registry);
        runChecked(List.of("npm", "ci", "--no-audit", "--no-fund", "--registry=" + registry),
                dashboard, npmEnv, log, message, "installing dashboard dependencies");
        npmEnv.put("VITE_BASE_PATH", "./");
        npmEnv.put("NODE_OPTIONS", "--max-old-space-size=2048");
        runChecked(List.of("npm", "run", "build:docker"),
                dashboard, npmEnv, log, message, "building the dashboard");
        // The built SPA is under src/octop/dashboard; the 4+ GiB dependency
        // tree is no longer needed by the runtime or a future launch.
        FileUtils.deleteDirectoryQuietly(dashboard.resolve("node_modules"));

        Path uv = ensureUv(brandRoot, log, message);
        Map<String, String> pythonEnv = new HashMap<>();
        pythonEnv.put("UV_CACHE_DIR", brandRoot.resolve("cache/uv").toString());
        pythonEnv.put("UV_PYTHON_INSTALL_DIR", brandRoot.resolve("toolchain/python").toString());
        pythonEnv.put("UV_LINK_MODE", "copy");
        pythonEnv.put("PYTHONDONTWRITEBYTECODE", "1");
        runChecked(List.of(uv.toString(), "sync", "--frozen", "--no-dev", "--python", "3.12"),
                target, pythonEnv, log, message, "installing the Python environment");

        Path octop = target.resolve(".venv/bin/octop");
        if (!Files.isRegularFile(octop)) {
            throw new BrandException("source build produced no .venv/bin/octop in " + target);
        }
        writeMarker(target, "source", version);
        message.accept("Installed Octop " + version + " from source");
    }

    /// Finds a usable `uv`: PATH first, then a per-brand copy downloaded from
    /// the official release tarball. The copy lives under the persisted brand
    /// directory, so a later reinstall does not fetch it again.
    private static Path ensureUv(Path brandRoot, Path log, Consumer<String> message) throws BrandException {
        Path toolchain = brandRoot.resolve("toolchain");
        Path binary = toolchain.resolve("uv");
        if (Files.isRegularFile(binary)) {
            return binary;
        }
        try {
            Process probe = new ProcessBuilder("uv", "--version")
                    .redirectErrorStream(true).start();
            if (probe.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) && probe.exitValue() == 0) {
                return Path.of("uv");
            }
            probe.destroyForcibly();
        } catch (IOException | InterruptedException ignored) {
            if (ignored instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        // PyPI first: its wheel contains the same binary, and it is reachable on
        // networks where github.com's release-asset host times out.
        try {
            return installUvFromPyPI(toolchain, binary, message);
        } catch (BrandException e) {
            message.accept("PyPI uv unavailable (" + e.getMessage() + "); trying GitHub");
        }
        String arch = switch (Architecture.CURRENT_ARCH) {
            case X86_64 -> "x86_64";
            case ARM64 -> "aarch64";
            default -> throw new BrandException("no uv release for " + Architecture.CURRENT_ARCH);
        };
        String url = "https://github.com/astral-sh/uv/releases/download/" + UV_VERSION + "/uv-"
                + arch + "-unknown-linux-gnu.tar.gz";
        Path archive = toolchain.resolve("uv.tar.gz");
        try {
            Files.createDirectories(toolchain);
            message.accept("Downloading uv " + url);
            try (InputStream in = download(url)) {
                Files.copy(in, archive, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            runChecked(List.of("tar", "-xzf", archive.toString(), "-C", toolchain.toString()),
                    toolchain, Map.of(), log, message, "unpacking uv");
            Path extracted = toolchain.resolve("uv-" + arch + "-unknown-linux-gnu").resolve("uv");
            if (!Files.isRegularFile(extracted)) {
                throw new BrandException("uv archive did not contain uv-" + arch + "-unknown-linux-gnu/uv");
            }
            Files.move(extracted, binary, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.setPosixFilePermissions(binary,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
            Files.deleteIfExists(archive);
            return binary;
        } catch (IOException e) {
            throw new BrandException("Could not install uv: " + e.getMessage(), e);
        }
    }

    /// Downloads the uv wheel from PyPI and extracts `uv` from
    /// `uv-<version>.data/scripts/uv`. The wheel is the same static binary as
    /// the GitHub tarball, but PyPI's CDN is reachable where GitHub's
    /// release-asset redirect is not.
    private static Path installUvFromPyPI(Path toolchain, Path binary, Consumer<String> message)
            throws BrandException {
        String arch = switch (Architecture.CURRENT_ARCH) {
            case X86_64 -> "x86_64";
            case ARM64 -> "aarch64";
            default -> throw new BrandException("no uv wheel for " + Architecture.CURRENT_ARCH);
        };
        String jsonUrl = "https://pypi.org/pypi/uv/" + UV_VERSION + "/json";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(jsonUrl))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new BrandException("PyPI answered " + response.statusCode() + " for " + jsonUrl);
            }
            JsonObject document = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonArray urls = document.getAsJsonArray("urls");
            String chosen = null;
            for (com.google.gson.JsonElement element : urls) {
                JsonObject file = element.getAsJsonObject();
                String filename = file.get("filename").getAsString();
                if (filename.endsWith(".whl") && filename.contains("manylinux")
                        && filename.contains(arch)) {
                    chosen = file.get("url").getAsString();
                    break;
                }
            }
            if (chosen == null) {
                throw new BrandException("no manylinux " + arch + " uv wheel on PyPI");
            }
            Files.createDirectories(toolchain);
            Path wheel = toolchain.resolve("uv.whl");
            message.accept("Downloading uv from PyPI " + chosen);
            try (InputStream in = download(chosen)) {
                Files.copy(in, wheel, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(wheel))) {
                ZipEntry entry;
                boolean found = false;
                while ((entry = zip.getNextEntry()) != null) {
                    if (!entry.isDirectory() && entry.getName().endsWith("/uv")) {
                        Files.copy(zip, binary, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    throw new BrandException("uv wheel did not contain scripts/uv");
                }
            }
            Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwxr-xr-x"));
            Files.deleteIfExists(wheel);
            return binary;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrandException("uv download was interrupted", e);
        } catch (IOException e) {
            throw new BrandException("Could not fetch uv from PyPI: " + e.getMessage(), e);
        }
    }

    /// The marker both Octop installers write: one existence check proves the
    /// release is complete, regardless of package-vs-source layout.
    private static String markerMode(Path target) {
        try {
            String json = Files.readString(target.resolve("octop-install.json"), StandardCharsets.UTF_8);
            int index = json.indexOf("\"mode\"");
            if (index < 0) {
                return "";
            }
            int colon = json.indexOf(':', index);
            int first = json.indexOf('"', colon + 1);
            int last = json.indexOf('"', first + 1);
            return first >= 0 && last > first ? json.substring(first + 1, last) : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static void writeMarker(Path target, String mode, String version) throws BrandException {
        String json = "{\"mode\":\"" + mode + "\",\"version\":\"" + version + "\"}\n";
        try {
            Files.writeString(target.resolve("octop-install.json"), json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BrandException("Could not write the Octop install marker: " + e.getMessage(), e);
        }
    }

    /// Portable archives are unpacked with `Files.copy`, which does not carry
    /// Unix mode bits. The bundled interpreter must be executable; mark every
    /// file in `runtime/bin` (python3.12, python3, helper shims).
    private static void markExecutable(Path directory) throws BrandException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> entries = Files.list(directory)) {
            for (Path entry : entries.toList()) {
                if (!Files.isRegularFile(entry)) {
                    continue;
                }
                try {
                    Files.setPosixFilePermissions(entry, PosixFilePermissions.fromString("rwxr-xr-x"));
                } catch (UnsupportedOperationException ignored) {
                    // Windows: no POSIX mode; the executable bit is implicit.
                    return;
                }
            }
        } catch (IOException e) {
            throw new BrandException("Could not make Octop's bundled Python executable: " + e.getMessage(), e);
        }
    }

    /// The platform suffix in Octop's portable asset names
    /// (`linux-amd64`, `windows-arm64`, `darwin-amd64`).
    static String octopPlatform() throws BrandException {
        String os = switch (OperatingSystem.CURRENT_OS) {
            case LINUX -> "linux";
            case WINDOWS -> "windows";
            case MACOS -> "darwin";
            default -> throw new BrandException("Octop has no portable build for "
                    + OperatingSystem.CURRENT_OS);
        };
        String arch = switch (Architecture.CURRENT_ARCH) {
            case X86_64 -> "amd64";
            case ARM64 -> "arm64";
            default -> throw new BrandException("Octop has no portable build for "
                    + Architecture.CURRENT_ARCH);
        };
        return os + "-" + arch;
    }

    /// Fetches the source zip over HTTPS. Kept separate from the unpacking so
    /// the retry loop can wrap the download alone.
    private static java.io.InputStream download(String url) throws BrandException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .header("Accept", "*/*")
                .GET()
                .build();
        try {
            HttpResponse<java.io.InputStream> response =
                    CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                throw new BrandException("Download answered " + response.statusCode() + ": " + url);
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrandException("Download was interrupted: " + url, e);
        } catch (java.io.IOException e) {
            throw new BrandException("Download failed: " + url + " — " + e.getMessage(), e);
        }
    }

    /// Unpacks a GitHub source zip into `target`, dropping the archive's single
    /// root folder (`<repo>-<tag>/…`). Entry paths are resolved against the
    /// target and rejected when they escape it (zip-slip).
    ///
    /// Package-private so the unpacking is testable with an in-memory zip.
    static void extractZip(java.io.InputStream zip, Path target) throws BrandException {
        try {
            Files.createDirectories(target);
            Path root = target.toAbsolutePath().normalize();
            try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(
                    new java.io.BufferedInputStream(zip))) {
                java.util.zip.ZipEntry entry;
                while ((entry = in.getNextEntry()) != null) {
                    String name = entry.getName();
                    int slash = name.indexOf('/');
                    if (slash < 0) {
                        // The archive's single root directory entry itself.
                        continue;
                    }
                    String relative = name.substring(slash + 1);
                    if (relative.isEmpty()) {
                        continue;
                    }
                    Path destination = root.resolve(relative).normalize();
                    if (!destination.startsWith(root)) {
                        throw new BrandException("Archive entry escapes the target: " + name);
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(destination);
                        continue;
                    }
                    Files.createDirectories(destination.getParent());
                    Files.copy(in, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (java.io.IOException e) {
            throw new BrandException("Could not unpack the source archive: " + e.getMessage(), e);
        }
    }

    /// Runs one install command, streaming its merged output to the log and
    /// the progress sink, and fails the install on a nonzero exit.
    private static void runChecked(List<String> command, Path directory, java.util.Map<String, String> environment,
                                   Path log, Consumer<String> message, String what) throws BrandException {
        message.accept("Running: " + String.join(" ", command));
        LOG.info("Brand install step (" + what + "): " + String.join(" ", command));
        try (OutputStreamAppend output = new OutputStreamAppend(log);
                BufferedReader reader = run(command, directory, environment, output)) {
            StringBuilder tail = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.writeLine(line);
                if (tail.length() > 0) {
                    tail.append(" | ");
                }
                tail.append(line);
                if (tail.length() > 2000) {
                    tail.delete(0, tail.length() - 1000);
                }
                message.accept(line);
            }
            int code = output.exitCode();
            if (code != 0) {
                throw new BrandException("Install step failed (" + what + ", exit " + code + "): " + tail);
            }
        } catch (IOException e) {
            throw new BrandException("Install step failed (" + what + "): " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrandException("Install step was interrupted (" + what + ")", e);
        }
    }

    /// Whether this panel build ships a patched Octop dashboard for a version.
    /// The version picker filters on it, so a user cannot select an upstream
    /// tag whose SPA would render at the wrong base (and fail under the mount).
    public static boolean hasBundledOctopDashboard(String version) {
        return BrandInstaller.class.getResource("/octop/octop-dashboard-" + version + ".zip") != null;
    }

    /// The installed releases, newest version first.
    ///
    /// @param brandRoot the brand's directory
    /// @return the releases
    public static List<Release> releases(Path brandRoot) {
        Path releases = brandRoot.resolve(RELEASES_DIR);
        if (!Files.isDirectory(releases)) {
            return List.of();
        }
        Path current = currentLink(brandRoot);
        List<Release> found = new ArrayList<>();
        try (Stream<Path> entries = Files.list(releases)) {
            for (Path directory : entries.filter(Files::isDirectory).toList()) {
                String version = directory.getFileName().toString();
                found.add(new Release(version, directory.toString(),
                        current != null && current.equals(directory)));
            }
        } catch (IOException e) {
            LOG.warning("Failed to enumerate releases in " + releases, e);
        }
        found.sort(Comparator.comparing(Release::version, newestFirst()));
        return found;
    }

    /// The directory holding the given installed version, or `null` when that
    /// version is not installed.
    ///
    /// @param brand the brand
    /// @param brandRoot the brand's directory
    /// @param version the version
    /// @return the prefix, or `null`
    public static @Nullable Path packageDir(Brand brand, Path brandRoot, String version) {
        Path candidate = brandRoot.resolve(RELEASES_DIR).resolve(version);
        return Files.exists(candidate.resolve(brand.installMarker()))
                ? candidate
                : null;
    }

    /// The release a new instance should default to: the `current` link's
    /// target, else the newest installed release, else `null`.
    ///
    /// @param brand the brand
    /// @param brandRoot the brand's directory
    /// @return the default package directory, or `null` when nothing is installed
    public static @Nullable Path defaultPackage(Brand brand, Path brandRoot) {
        Path current = currentLink(brandRoot);
        if (current != null && Files.exists(current.resolve(brand.installMarker()))) {
            return current;
        }
        List<Release> releases = releases(brandRoot);
        return releases.isEmpty() ? null : Path.of(releases.get(0).path());
    }

    /// The tail of the install log, for the panel's log view.
    ///
    /// @param brandRoot the brand's directory
    /// @param maxLines the most lines to return
    /// @return the trailing lines, oldest first
    public static List<String> tailLog(Path brandRoot, int maxLines) {
        Path log = brandRoot.resolve(INSTALL_LOG);
        if (!Files.isRegularFile(log)) {
            return List.of();
        }
        try (var lines = Files.lines(log, StandardCharsets.UTF_8)) {
            List<String> all = lines.toList();
            return List.copyOf(all.subList(Math.max(0, all.size() - maxLines), all.size()));
        } catch (IOException e) {
            LOG.warning("Failed to read " + log, e);
            return List.of();
        }
    }

    /// Points the `current` pointer at the given release, replacing whatever was there.
    /// Best effort: a broken pointer only affects the default for new instances, never an
    /// instance's pinned version.
    ///
    /// A link where the system permits one, a pointer file where it does not — which is
    /// the ordinary Windows case, and the reason this used to log a warning on a machine
    /// that had done nothing wrong.
    private static void pointCurrentAt(Path brandRoot, Path target) {
        Path link = brandRoot.resolve(CURRENT_LINK);
        try {
            Links.pointAt(link, target);
        } catch (IOException e) {
            LOG.warning("Could not point " + link + " at " + target, e);
        }
    }

    /// The release the `current` pointer resolves to, or `null`.
    private static @Nullable Path currentLink(Path brandRoot) {
        return Links.pointedAt(brandRoot.resolve(CURRENT_LINK)).orElse(null);
    }

    /// Runs one command in a directory, teeing its merged output to the log.
    private static BufferedReader run(List<String> command, Path directory,
                                      java.util.Map<String, String> environment, OutputStreamAppend output)
            throws IOException, InterruptedException {
        // `npm` and `pnpm` are `npm.cmd` / `pnpm.cmd` on Windows, and the bare names do
        // not name a file there — the clone's dependency step and build step are both
        // program names that have to be completed before they can be started.
        ProcessBuilder builder = new ProcessBuilder(
                org.jackhuang.hmcl.util.platform.Executables.startable(command, environment.get("PATH")));
        builder.directory(directory.toFile());
        builder.environment().putAll(environment);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        output.attach(process);
        return new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    }

    /// Numeric component-wise ordering, newest first.
    private static Comparator<String> newestFirst() {
        return (left, right) -> {
            String[] a = left.split("\\.");
            String[] b = right.split("\\.");
            for (int i = 0; i < Math.max(a.length, b.length); i++) {
                int x = i < a.length ? parsePart(a[i]) : 0;
                int y = i < b.length ? parsePart(b[i]) : 0;
                if (x != y) {
                    return Integer.compare(y, x);
                }
            }
            return right.compareTo(left);
        };
    }

    private static int parsePart(String part) {
        try {
            return Integer.parseInt(part.replaceAll("[^0-9].*$", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /// An output stream appended to a log file, wired to a process's merged
    /// output so every install line lands in the panel-visible log.
    private static final class OutputStreamAppend implements AutoCloseable {
        private final java.io.OutputStream stream;
        private Process process;

        OutputStreamAppend(Path log) throws IOException {
            Files.createDirectories(log.getParent());
            this.stream = Files.newOutputStream(log,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }

        void attach(Process process) {
            this.process = process;
        }

        /// Appends one line to the install log, flushed so the log view follows the
        /// install as it happens rather than after the process exits.
        ///
        /// @param line the line, without its terminator
        /// @throws IOException when the log cannot be written
        void writeLine(String line) throws IOException {
            stream.write((line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
            stream.flush();
        }

        int exitCode() throws InterruptedException {
            return process.waitFor();
        }

        @Override
        public void close() throws IOException {
            stream.close();
        }
    }
}
