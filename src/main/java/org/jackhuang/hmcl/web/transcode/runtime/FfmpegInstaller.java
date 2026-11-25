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

import com.google.gson.annotations.SerializedName;
import org.jackhuang.hmcl.util.ProcessTree;
import org.jackhuang.hmcl.web.transcode.TranscodeModule;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/// Installs ffmpeg at runtime for deployments that do not ship it.
///
/// Three paths, in the order the settings page offers them: a static build downloaded
/// to the panel's data volume (Windows/macOS and Linux), the distribution package
/// manager (container root), and a source build (last resort). Every path writes into
/// `<data>/transcode/tools/ffmpeg`, so the result survives a container rebuild and the
/// panel never depends on a system path it cannot see.
@NotNullByDefault
public final class FfmpegInstaller {

    public enum State {
        @SerializedName("running") RUNNING,
        @SerializedName("done") DONE,
        @SerializedName("failed") FAILED,
        @SerializedName("cancelled") CANCELLED
    }

    public static final class InstallJob {
        public String id = "";
        public String kind = "";
        public State state = State.RUNNING;
        public double progress = 0;
        public String message = "";
        public String log = "";
        public String logPath = "";
        public @Nullable String error;
        public long createdAt = System.currentTimeMillis();
        public long endedAt = -1;
        public transient @Nullable Process process;
    }

    private final TranscodeModule module;
    private final Path toolsDir;
    private final FfmpegRuntimeManager runtimes;
    private final Map<String, InstallJob> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "transcode-installer");
        thread.setDaemon(true);
        return thread;
    });

    public FfmpegInstaller(TranscodeModule module) {
        this.module = module;
        this.toolsDir = module.dataDir().resolve("tools").resolve("ffmpeg");
        this.runtimes = module.runtimeManager();
    }

    public FfmpegRuntimeManager runtimes() {
        return runtimes;
    }

    /// Installs one catalogue entry and activates it. `id` must exist in the
    /// current catalogue; there is no "latest" here.
    public InstallJob startArtifact(String id) throws IOException {
        FfmpegCatalog.Entry entry = module.catalog().find(id);
        if (entry == null) {
            throw new IOException("ffmpeg_no_candidate: unknown catalog id " + id);
        }
        if (!entry.isBinary()) {
            throw new IOException("ffmpeg_source_unsupported: source builds are not part of this stage");
        }
        FfmpegCapabilities.Host host = FfmpegCapabilities.detect();
        if (!entry.matches(host.os(), host.arch(), host.libc())) {
            throw new IOException("ffmpeg_unsupported_platform: " + entry.id()
                    + " is for " + entry.os() + "/" + entry.arch() + "/" + entry.libc()
                    + ", this host is " + host.describe());
        }
        InstallJob job = newJob("artifact");
        executor.execute(() -> runArtifact(job, entry));
        return job;
    }

    /// Lets the selector choose an entry for this host using `auto`.
    public InstallJob startAuto(Set<String> requiredFeatures) throws IOException {
        return startAuto(requiredFeatures, "auto");
    }

    /// Lets the selector choose an entry for this host and version line
    /// (`auto`, `7.1`, `8.1`). An explicit line is never silently replaced.
    public InstallJob startAuto(Set<String> requiredFeatures, @Nullable String major) throws IOException {
        FfmpegSelector.Recommendation recommendation = FfmpegSelector.select(
                module.catalog(), FfmpegCapabilities.detect(), requiredFeatures, major);
        InstallJob job = newJob("auto");
        job.log = "selected " + recommendation.entry().id() + ": " + recommendation.reason() + "\n";
        executor.execute(() -> runArtifact(job, recommendation.entry()));
        return job;
    }

    /// Operator-supplied URL. An explicit SHA-256 is required; the catalogue's
    /// pinned hash is what makes "auto" trustworthy, and a custom URL has no
    /// catalogue entry to borrow one from.
    public InstallJob startCustom(String url, String sha256, @Nullable String version) throws IOException {
        if (url == null || url.isBlank()) {
            throw new IOException("url is required");
        }
        if (sha256 == null || !sha256.matches("[0-9a-fA-F]{64}")) {
            throw new IOException("a 64-hex SHA-256 is required for a custom URL");
        }
        validateUrl(url);
        String id = "custom-" + sha256.substring(0, 12).toLowerCase(java.util.Locale.ROOT);
        InstallJob job = newJob("custom");
        executor.execute(() -> runArtifact(job, FfmpegCatalog.Entry.custom(id, url, sha256, version)));
        return job;
    }

    private InstallJob newJob(String kind) {
        InstallJob job = new InstallJob();
        job.id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        job.kind = kind;
        jobs.put(job.id, job);
        return job;
    }

    private void runArtifact(InstallJob job, FfmpegCatalog.Entry entry) {
        try {
            runtimes.install(entry, (message, fraction) -> {
                if (job.state == State.CANCELLED) {
                    throw new CancellationException("cancelled");
                }
                job.message = message;
                job.progress = fraction;
                log(job, message + "\n");
            }, () -> job.state == State.CANCELLED);
            if (job.state != State.CANCELLED) {
                job.state = State.DONE;
                job.progress = 1;
                job.message = "done";
                job.endedAt = System.currentTimeMillis();
                module.runtime().refresh();
            }
        } catch (CancellationException e) {
            job.state = State.CANCELLED;
            job.message = "cancelled";
            job.endedAt = System.currentTimeMillis();
        } catch (Exception e) {
            if (job.state != State.CANCELLED) {
                job.state = State.FAILED;
                job.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                job.message = "failed";
                job.endedAt = System.currentTimeMillis();
            }
        }
    }

    public InstallJob start(String kind, @Nullable String url, @Nullable String sha256,
                            @Nullable String version) throws IOException {
        if (!List.of("static", "package", "compile").contains(kind)) {
            throw new IOException("kind must be static, package or compile");
        }
        if ("compile".equals(kind)) {
            // The old compile path ran the system `tar` and copied a possibly
            // shared build's binaries. It now resolves the pinned source entry
            // by URL and delegates to the same FfmpegBuildKit the new API uses.
            FfmpegCatalog.Entry source = module.catalog().entries().stream()
                    .filter(entry -> entry.isSource() && entry.url().equals(url))
                    .findFirst()
                    .orElseThrow(() -> new IOException("ffmpeg_source_requires_catalog: "
                            + "source builds use a pinned ffmpeg.org entry (7.1 or 8.1);"
                            + " a user-supplied source URL is not accepted"));
            return startSourceEntry(source, Set.of(), "legacy compile → " + source.id());
        }
        if ("static".equals(kind)) {
            if (url == null || url.isBlank()) {
                throw new IOException("url is required for " + kind);
            }
            validateUrl(url);
            if (sha256 == null || !sha256.matches("[0-9a-fA-F]{64}")) {
                throw new IOException("ffmpeg_checksum_required: a custom URL must supply"
                        + " its own 64-hex SHA-256; sidecar hashes are no longer trusted");
            }
        }
        Files.createDirectories(toolsDir);
        InstallJob job = new InstallJob();
        job.id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        job.kind = kind;
        jobs.put(job.id, job);
        executor.execute(() -> run(job, url, sha256, version));
        return job;
    }

    public Optional<InstallJob> job(String id) {
        return Optional.ofNullable(jobs.get(id));
    }

    public boolean cancel(String id) {
        InstallJob job = jobs.get(id);
        if (job == null || job.state != State.RUNNING) {
            return false;
        }
        job.state = State.CANCELLED;
        job.message = "cancelled";
        job.endedAt = System.currentTimeMillis();
        Process process = job.process;
        if (process != null) {
            ProcessTree.terminate(process, 2);
        }
        return true;
    }

    private void run(InstallJob job, @Nullable String url, @Nullable String sha256, @Nullable String version) {
        try {
            switch (job.kind) {
                case "static" -> installStatic(job, url, sha256, version);
                case "package" -> installPackage(job);
                default -> throw new IOException("unknown kind " + job.kind);
            }
            if (job.state == State.RUNNING) {
                job.state = State.DONE;
                job.progress = 1;
                job.message = "done";
                job.endedAt = System.currentTimeMillis();
                module.runtime().refresh();
            }
        } catch (Exception e) {
            if (job.state != State.CANCELLED) {
                job.state = State.FAILED;
                job.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                job.message = "failed";
                job.endedAt = System.currentTimeMillis();
            }
        }
    }

    private void installStatic(InstallJob job, String url, String sha256, @Nullable String version)
            throws Exception {
        String selectedVersion = version == null || version.isBlank()
                ? "static-" + System.currentTimeMillis() : version;
        String expected = sha256.toLowerCase(java.util.Locale.ROOT);
        String id = "custom-" + expected.substring(0, 12);
        FfmpegCatalog.Entry entry = FfmpegCatalog.Entry.custom(id, url, expected, selectedVersion);
        runtimes.install(entry, (message, fraction) -> {
            if (job.state == State.CANCELLED) {
                throw new CancellationException("cancelled");
            }
            job.message = message;
            job.progress = fraction;
            log(job, message + "\n");
        });
        log(job, "installed " + selectedVersion + " via the runtime manager\n");
    }

    private void installPackage(InstallJob job) throws Exception {
        if (Files.isRegularFile(toolsDir.resolve("current.json"))) {
            throw new IOException("ffmpeg: a managed runtime is active; switch or remove it"
                    + " before using the package manager");
        }
        String command;
        List<String> args;
        if (commandExists("apt-get")) {
            command = "apt-get";
            args = List.of("install", "-y", "--no-install-recommends", "ffmpeg");
        } else if (commandExists("dnf")) {
            command = "dnf";
            args = List.of("install", "-y", "ffmpeg");
        } else if (commandExists("zypper")) {
            command = "zypper";
            args = List.of("--non-interactive", "install", "ffmpeg-7");
        } else {
            throw new IOException("no supported package manager (apt-get/dnf/zypper)");
        }
        List<String> full = new ArrayList<>();
        full.add(command);
        full.addAll(args);
        log(job, "running " + String.join(" ", full));
        runProcess(job, full, null);
        log(job, "package manager finished; run 重新探测");
    }

    /// Selects a pinned source entry by version line (`auto`, `7.1`, `8.1`) and
    /// starts a build job. An explicit line never falls through to another one.
    public InstallJob startSource(Set<String> features, @Nullable String major) throws IOException {
        return startSource(features, major, FfmpegToolchainManager.Preference.AUTO);
    }

    /// The same, with an explicit compiler choice for the build.
    public InstallJob startSource(Set<String> features, @Nullable String major,
                                  FfmpegToolchainManager.Preference compiler) throws IOException {
        FfmpegSelector.Recommendation recommendation = FfmpegSelector.selectSource(
                module.catalog(), FfmpegCapabilities.detect(), features, major);
        return startSourceEntry(recommendation.entry(), features, recommendation.reason(), compiler);
    }

    /// Starts the source entry an operator picked from the catalogue.
    public InstallJob startSourceArtifact(String id, Set<String> features) throws IOException {
        return startSourceArtifact(id, features, FfmpegToolchainManager.Preference.AUTO);
    }

    /// The same, with an explicit compiler choice for the build.
    public InstallJob startSourceArtifact(String id, Set<String> features,
                                          FfmpegToolchainManager.Preference compiler)
            throws IOException {
        FfmpegCatalog.Entry entry = module.catalog().find(id);
        if (entry == null) {
            throw new IOException("ffmpeg_no_candidate: unknown catalog id " + id);
        }
        if (!entry.isSource()) {
            throw new IOException("ffmpeg_artifact_invalid: " + id + " is not a source entry");
        }
        FfmpegCapabilities.Host host = FfmpegCapabilities.detect();
        if (!entry.matches(host.os(), host.arch(), host.libc())) {
            throw new IOException("ffmpeg_unsupported_platform: " + id + " is for "
                    + entry.os() + "/" + entry.arch() + "/" + entry.libc());
        }
        return startSourceEntry(entry, features, "source build " + id, compiler);
    }

    /// Starts a managed full-codec static build. The recipe builds its own
    /// dependency stack (x264/x265/libvpx/libaom/svt-av1/opus/lame/vorbis) and
    /// the cmake/nasm tools; the host only needs a C/C++ compiler and make.
    public InstallJob startFullSource(String id,
                                      FfmpegToolchainManager.Preference compiler)
            throws IOException {
        FfmpegFullBuildCatalog.Entry entry = module.fullBuildCatalog().find(id);
        if (entry == null) {
            throw new IOException("ffmpeg_no_candidate: unknown full build " + id);
        }
        if (org.jackhuang.hmcl.util.platform.OperatingSystem.CURRENT_OS
                != org.jackhuang.hmcl.util.platform.OperatingSystem.LINUX) {
            throw new IOException("ffmpeg_full_build_unsupported_platform:"
                    + " the managed full build is implemented for Linux only");
        }
        String versionId = FfmpegRuntimeManager.safeId(entry.id());
        Path versionDir = toolsDir.resolve(versionId);
        if (Files.isRegularFile(versionDir.resolve("runtime.json"))) {
            throw new IOException("ffmpeg_already_installed: " + versionId
                    + "; delete the installed version before rebuilding");
        }
        InstallJob job = newJob("full-source");
        job.logPath = module.fullBuildKit().logFor(job.id).toString();
        job.log = "full build " + id + " (" + entry.profile() + ")\n";
        executor.execute(() -> runFullSource(job, entry, versionDir, compiler));
        return job;
    }

    private void runFullSource(InstallJob job, FfmpegFullBuildCatalog.Entry entry,
                               Path versionDir,
                               FfmpegToolchainManager.Preference compiler) {
        try {
            FfmpegBuildKit.BuildResult result = module.fullBuildKit().build(
                    entry, versionDir, job.id,
                    (message, fraction) -> {
                        if (job.state != State.CANCELLED) {
                            job.message = message;
                            job.progress = fraction;
                            log(job, message + "\n");
                        }
                    },
                    () -> job.state == State.CANCELLED, compiler);
            if (job.state != State.CANCELLED) {
                job.state = State.DONE;
                job.progress = 1;
                job.message = "done";
                job.endedAt = System.currentTimeMillis();
                log(job, "built " + entry.ffmpegVersion() + " with "
                        + result.featuresEnabled().size() + " features"
                        + (result.logFile() == null ? "" : "; log: " + result.logFile()) + "\n");
                module.runtime().refresh();
            }
        } catch (CancellationException e) {
            job.state = State.CANCELLED;
            job.message = "cancelled";
            job.endedAt = System.currentTimeMillis();
        } catch (Exception e) {
            if (job.state != State.CANCELLED) {
                job.state = State.FAILED;
                job.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                job.message = "failed";
                job.endedAt = System.currentTimeMillis();
            }
        }
    }

    private InstallJob startSourceEntry(FfmpegCatalog.Entry entry, Set<String> features,
                                        String reason) throws IOException {
        return startSourceEntry(entry, features, reason,
                FfmpegToolchainManager.Preference.AUTO);
    }

    private InstallJob startSourceEntry(FfmpegCatalog.Entry entry, Set<String> features,
                                        String reason,
                                        FfmpegToolchainManager.Preference compiler)
            throws IOException {
        String versionId = FfmpegRuntimeManager.safeId("source-" + entry.ffmpegVersion()
                + "-" + (entry.buildRecipe().isEmpty() ? "default" : entry.buildRecipe()));
        Path versionDir = toolsDir.resolve(versionId);
        if (Files.isRegularFile(versionDir.resolve("runtime.json"))) {
            throw new IOException("ffmpeg_already_installed: " + versionId
                    + "; delete the installed version before rebuilding");
        }
        InstallJob job = newJob("source");
        job.logPath = module.buildKit().logFor(job.id).toString();
        job.log = reason + "\n";
        executor.execute(() -> runSource(job, entry, versionDir, features, compiler));
        return job;
    }

    private void runSource(InstallJob job, FfmpegCatalog.Entry entry, Path versionDir,
                           Set<String> features,
                           FfmpegToolchainManager.Preference compiler) {
        try {
            FfmpegBuildKit.BuildResult result = module.buildKit().build(
                    entry, versionDir, job.id, features,
                    (message, fraction) -> {
                        // Called from the build thread and the output reader;
                        // cancellation is observed by the BooleanSupplier below,
                        // not by throwing out of a reader thread.
                        if (job.state != State.CANCELLED) {
                            job.message = message;
                            job.progress = fraction;
                            log(job, message + "\n");
                        }
                    },
                    () -> job.state == State.CANCELLED, compiler);
            if (job.state != State.CANCELLED) {
                job.state = State.DONE;
                job.progress = 1;
                job.message = "done";
                job.endedAt = System.currentTimeMillis();
                log(job, "built " + entry.ffmpegVersion() + " with "
                        + result.featuresEnabled().size() + " features"
                        + (result.logFile() == null ? "" : "; log: " + result.logFile()) + "\n");
                module.runtime().refresh();
            }
        } catch (CancellationException e) {
            job.state = State.CANCELLED;
            job.message = "cancelled";
            job.endedAt = System.currentTimeMillis();
        } catch (Exception e) {
            if (job.state != State.CANCELLED) {
                job.state = State.FAILED;
                job.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                job.message = "failed";
                job.endedAt = System.currentTimeMillis();
            }
        }
    }

    private void runProcess(InstallJob job, List<String> command, @Nullable Path cwd) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        if (cwd != null) {
            builder.directory(cwd.toFile());
        }
        Process process = builder.start();
        job.process = process;
        // Read on another thread. Reading to EOF and only then asking whether the
        // process is still alive means the timeout can never fire: a configure
        // script waiting for input, or a make that wedged, holds the pipe open
        // and the read never returns. It also meant no line reached the page
        // while it happened, so a build that was working looked like a hang.
        Thread reader = new Thread(() -> {
            try (BufferedReader lines = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) {
                    log(job, line + "\n");
                }
            } catch (IOException ignored) {
                // The child ended and closed the pipe; that is the normal exit.
            }
        }, "ffmpeg-install-reader");
        reader.setDaemon(true);
        reader.start();
        if (!process.waitFor(30, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IOException("command timed out: " + String.join(" ", command));
        }
        // Give the reader a moment to drain what the child wrote last, so the
        // log the page shows ends with the build's own words, not mid-line.
        reader.join(5_000);
        job.process = null;
        if (job.state == State.CANCELLED) {
            throw new IOException("cancelled");
        }
        if (process.exitValue() != 0) {
            throw new IOException("command failed with code " + process.exitValue()
                    + ": " + String.join(" ", command));
        }
    }

    private static boolean commandExists(String name) {
        return org.jackhuang.hmcl.util.platform.Executables.resolve(name).isPresent();
    }

    static void validateUrl(String url) throws IOException {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (RuntimeException e) {
            throw new IOException("invalid URL");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IOException("only https URLs are accepted");
        }
        String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
        // IPv6 literals arrive bracketed from URI.getHost().
        String literal = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1) : host;
        if ("localhost".equals(literal)) {
            throw new IOException("private/loopback URLs are refused");
        }
        if (looksLikeLiteral(literal)) {
            try {
                if (isPrivateAddress(java.net.InetAddress.getByName(literal))) {
                    throw new IOException("private/loopback URLs are refused");
                }
            } catch (java.net.UnknownHostException e) {
                throw new IOException("invalid host literal: " + host);
            }
        }
    }

    /// Whether a host is written as an address rather than a name.
    ///
    /// IPv6 (`[::1]`), dotted IPv4, the three- and two-part forms
    /// (`127.1`), and the single-number forms Java also parses
    /// (`2130706433`) are all literal. A DNS name is left to the downloader,
    /// which resolves it before the request; this method stays offline so a
    /// unit test does not need a resolver.
    private static boolean looksLikeLiteral(String host) {
        return host.indexOf(':') >= 0
                || host.matches("\\d+(\\.\\d+){0,3}")
                || host.matches("0[xX][0-9a-fA-F]+");
    }

    /// Whether an address is loopback, link-local, site-local, multicast or
    /// otherwise not on the public internet.
    static boolean isPrivateAddress(java.net.InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        // IPv6 unique-local fc00::/7.
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }

    private static void log(InstallJob job, String text) {
        job.log = job.log + text;
        if (job.log.length() > 200_000) {
            job.log = job.log.substring(job.log.length() - 200_000);
        }
    }

}