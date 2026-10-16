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
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Installs and enumerates the tool releases of one [Brand].
///
/// Two source flavours:
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

    private static final String RELEASES_DIR = "releases";
    private static final String CURRENT_LINK = "current";
    private static final String INSTALL_LOG = "install.log";

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
        if (Files.exists(target.resolve(brand.installMarker()))) {
            message.accept(brand.sourceLabel() + " " + version + " is already installed");
            pointCurrentAt(brandRoot, target);
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

        List<String> command = List.of(
                pnpm, "add", "--dir", target.toString(),
                brand.npmPackage() + "@" + version,
                "--reporter=append-only",
                "--registry=" + registry,
                "--config.dangerously-allow-all-builds=true");
        message.accept("Installing " + brand.npmPackage() + " " + version);
        LOG.info("Installing " + brand.npmPackage() + " " + version + ": " + String.join(" ", command));

        Path log = brandRoot.resolve(INSTALL_LOG);
        try (OutputStreamAppend output = new OutputStreamAppend(log);
                BufferedReader reader = run(command, target, runtime.pathEnvironment(), output)) {
            StringBuilder tail = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
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

    /// Fetches the source zip over HTTPS. Kept separate from the unpacking so
    /// the retry loop can wrap the download alone.
    private static java.io.InputStream download(String url) throws BrandException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .header("Accept", "application/zip")
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
                org.jackhuang.hmcl.util.platform.Executables.startable(command));
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

        int exitCode() throws InterruptedException {
            return process.waitFor();
        }

        @Override
        public void close() throws IOException {
            stream.close();
        }
    }
}
