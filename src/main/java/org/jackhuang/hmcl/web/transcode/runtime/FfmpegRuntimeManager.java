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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jackhuang.hmcl.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/// Installs, activates and rolls back ffmpeg artifacts the panel manages.
///
/// The layout is deliberately versioned: a download never overwrites the
/// currently working runtime, and `current` is a pointer written only after the
/// new binary passed its probe. This is the part the old installer lacked; it
/// copied the two binaries over `current/bin` and left a partially-installed
/// runtime behind if the second copy failed.
///
/// `<data>/transcode/tools/ffmpeg/`
///   `<id>/bin/ffmpeg` + `ffprobe` + `runtime.json`
///   `current.json` — `{"version": "<id>"}` (atomic)
///   `current` — POSIX symlink to `<id>` (best effort; pointer is authoritative)
///   `state.json` — `{"current": "<id>", "previous": "<id>"}`
@NotNullByDefault
public final class FfmpegRuntimeManager {

    private static final Logger LOG = Logger.LOG;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String POINTER = "current.json";
    private static final String STATE = "state.json";

    /// Progress sink; `fraction` is 0..1 and is advisory.
    public interface Progress {
        void stage(String message, double fraction);
    }

    /// What `runtime.json` records. Gson fills public fields directly; adding a
    /// field is backwards compatible because older records simply leave it null.
    public static final class RuntimeRecord {
        public int schema = 1;
        public String id = "";
        public String version = "";
        public String catalogId = "";
        public String major = "";
        public String source = "";
        public String license = "";
        public String sha256 = "";
        public String installedAt = "";
        public String archive = "";
        public List<String> featuresDeclared = List.of();
        public List<String> featuresProbed = List.of();
        public String probeVersion = "";
        public String notes = "";
        /// Source builds only: where the tarball came from and which recipe ran.
        public String builtFrom = "";
        public String buildRecipe = "";
        public String toolchain = "";
    }

    public record Installed(String id, Path directory, RuntimeRecord runtime, boolean current) {
    }

    private static final class State {
        public String current;
        public String previous;
        public String updatedAt;
    }

    private final Path toolsDir;
    private final FfmpegCatalog.Catalog catalog;
    private final Object lock = new Object();

    public FfmpegRuntimeManager(Path toolsDir, FfmpegCatalog.Catalog catalog) {
        this.toolsDir = toolsDir.toAbsolutePath().normalize();
        this.catalog = catalog;
    }

    public Path toolsDir() {
        return toolsDir;
    }

    public FfmpegCatalog.Catalog catalog() {
        return catalog;
    }

    public List<Installed> installed() {
        List<Installed> result = new ArrayList<>();
        if (!Files.isDirectory(toolsDir)) {
            return result;
        }
        String current = currentId();
        try (Stream<Path> entries = Files.list(toolsDir)) {
            for (Path directory : entries.filter(Files::isDirectory).toList()) {
                String id = directory.getFileName().toString();
                if (id.startsWith(".") || id.equals("current")) {
                    continue;
                }
                RuntimeRecord record = readRuntime(directory);
                if (record == null) {
                    if (!Files.isRegularFile(directory.resolve("bin").resolve(exe("ffmpeg")))) {
                        continue;
                    }
                    record = legacyRecord(id);
                }
                result.add(new Installed(id, directory, record, id.equals(current)));
            }
        } catch (IOException e) {
            LOG.warning("Failed to list ffmpeg runtimes in " + toolsDir, e);
        }
        result.sort(Comparator
                .comparing((Installed installed) -> nullSafe(installed.runtime().installedAt))
                .reversed()
                .thenComparing(Installed::id));
        return result;
    }

    public @Nullable Installed current() {
        String id = currentId();
        if (id != null) {
            Path directory = toolsDir.resolve(id).normalize();
            if (directory.startsWith(toolsDir) && Files.isDirectory(directory)) {
                RuntimeRecord record = readRuntime(directory);
                return new Installed(id, directory, record == null ? legacyRecord(id) : record, true);
            }
        }
        Path legacy = toolsDir.resolve("current");
        if (Files.isDirectory(legacy) && Files.isRegularFile(legacy.resolve("bin").resolve(exe("ffmpeg")))) {
            return new Installed("legacy", legacy, legacyRecord("legacy"), true);
        }
        return null;
    }

    /// Downloads and installs one catalogue entry, then activates it.
    public Installed install(FfmpegCatalog.Entry entry, Progress progress) throws IOException {
        synchronized (lock) {
            if (!entry.isBinary()) {
                throw new IOException("ffmpeg_source_unsupported: source builds are not part of this stage");
            }
            if (entry.url() == null || entry.url().isBlank()) {
                throw new IOException("ffmpeg_artifact_invalid: the entry has no URL");
            }
            if (entry.sha256() == null || !entry.sha256().matches("[0-9a-fA-F]{64}")) {
                throw new IOException("ffmpeg_artifact_invalid: the entry has no pinned SHA-256");
            }
            FfmpegInstaller.validateUrl(entry.url());

            String id = safeId(entry.id());
            Path versionDir = toolsDir.resolve(id).normalize();
            if (!versionDir.startsWith(toolsDir)) {
                throw new IOException("ffmpeg_artifact_invalid: id escapes the tools directory");
            }
            if (Files.isRegularFile(versionDir.resolve("runtime.json"))) {
                throw new IOException("ffmpeg_already_installed: " + id);
            }
            Files.createDirectories(toolsDir);
            // A previous interrupted attempt is not a usable runtime.
            FfmpegArchive.deleteTree(versionDir);

            Path download = Files.createTempFile(toolsDir, ".download-", suffix(entry.archive()));
            try {
                progress.stage("downloading " + entry.url(), 0);
                try {
                    download(entry, download, progress);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("ffmpeg_install_interrupted", e);
                }
                progress.stage("verifying SHA-256", 0.6);
                verifySha(download, entry.sha256());
                return installArchive(download, entry, progress);
            } finally {
                Files.deleteIfExists(download);
            }
        }
    }

    /// Installs from an already-downloaded archive. Package-visible so the tests
    /// can exercise extraction/activation without an HTTP server; `install` is
    /// the only production caller.
    Installed installArchive(Path archive, FfmpegCatalog.Entry entry, Progress progress) throws IOException {
        synchronized (lock) {
            if (!entry.isBinary()) {
                throw new IOException("ffmpeg_source_unsupported: source builds are not part of this stage");
            }
            if (entry.sha256() == null || !entry.sha256().matches("[0-9a-fA-F]{64}")) {
                throw new IOException("ffmpeg_artifact_invalid: the entry has no pinned SHA-256");
            }
            String id = safeId(entry.id());
            Path versionDir = toolsDir.resolve(id).normalize();
            if (!versionDir.startsWith(toolsDir)) {
                throw new IOException("ffmpeg_artifact_invalid: id escapes the tools directory");
            }
            if (Files.isRegularFile(versionDir.resolve("runtime.json"))) {
                throw new IOException("ffmpeg_already_installed: " + id);
            }
            Files.createDirectories(toolsDir);
            FfmpegArchive.deleteTree(versionDir);

            Path staging = toolsDir.resolve("." + id + ".installing-" + UUID.randomUUID());
            boolean activated = false;
            try {
                progress.stage("verifying SHA-256", 0.6);
                verifySha(archive, entry.sha256());
                progress.stage("unpacking " + entry.archive(), 0.62);
                Files.createDirectories(staging);
                FfmpegArchive.extract(archive, staging);

                Path ffmpeg = FfmpegArchive.find(staging, "ffmpeg");
                Path ffprobe = FfmpegArchive.find(staging, "ffprobe");
                if (ffmpeg == null || ffprobe == null) {
                    throw new IOException("ffmpeg_artifact_invalid: the archive has no ffmpeg/ffprobe pair");
                }
                installTree(staging, versionDir, ffmpeg, ffprobe);

                Path installedFfmpeg = versionDir.resolve("bin").resolve(exe("ffmpeg"));
                Path installedProbe = versionDir.resolve("bin").resolve(exe("ffprobe"));
                if (!Files.isRegularFile(installedFfmpeg) || !Files.isRegularFile(installedProbe)) {
                    throw new IOException("ffmpeg_artifact_invalid: ffmpeg/ffprobe are missing after extraction");
                }
                makeExecutable(installedFfmpeg);
                makeExecutable(installedProbe);

                progress.stage("probing the new runtime", 0.85);
                FfmpegProbe.FfmpegInfo info = FfmpegProbe.probe(
                        new FfmpegLocator.FfmpegPaths(installedFfmpeg, installedProbe));
                if (!info.available()) {
                    throw new IOException("ffmpeg_unusable: "
                            + (info.error() == null ? "probe failed" : info.error()));
                }

                RuntimeRecord record = new RuntimeRecord();
                record.id = id;
                record.version = entry.ffmpegVersion();
                record.catalogId = entry.id();
                record.major = entry.major();
                record.source = entry.id().startsWith("custom-") ? "custom" : "catalog";
                record.license = entry.license();
                record.sha256 = entry.sha256().toLowerCase(Locale.ROOT);
                record.installedAt = Instant.now().toString();
                record.archive = entry.archive();
                record.featuresDeclared = List.copyOf(entry.features());
                record.featuresProbed = probedFeatures(entry.features(), info);
                record.probeVersion = info.version();
                record.notes = entry.notes();
                writeRuntime(versionDir, record);

                progress.stage("activating " + entry.ffmpegVersion(), 0.95);
                activate(id);
                activated = true;
                gc(2);
                progress.stage("ffmpeg " + entry.ffmpegVersion() + " installed", 1);
                return new Installed(id, versionDir, record, true);
            } finally {
                FfmpegArchive.deleteTree(staging);
                if (!activated) {
                    // A failed or cancelled install must not leave a directory
                    // that `installed()` later mistakes for a legacy runtime.
                    FfmpegArchive.deleteTree(versionDir);
                }
            }
        }
    }

    /// Switches `current` to an already installed version.
    public void activate(String id) throws IOException {
        synchronized (lock) {
            String safe = safeId(id);
            Path directory = toolsDir.resolve(safe).normalize();
            if (!directory.startsWith(toolsDir) || !Files.isDirectory(directory)) {
                throw new IOException("ffmpeg_not_installed: " + id);
            }
            Path ffmpeg = directory.resolve("bin").resolve(exe("ffmpeg"));
            Path ffprobe = directory.resolve("bin").resolve(exe("ffprobe"));
            if (!Files.isRegularFile(ffmpeg) || !Files.isRegularFile(ffprobe)) {
                throw new IOException("ffmpeg_not_installed: " + id + " has no binary pair");
            }
            State state = readState();
            String old = state.current != null ? state.current : currentId();
            writePointer(safe);
            writeSymlink(directory);
            state.current = safe;
            if (old != null && !old.equals(safe)) {
                state.previous = old;
            }
            state.updatedAt = Instant.now().toString();
            try {
                writeState(state);
            } catch (IOException e) {
                // `current.json` is the authoritative pointer; losing `previous`
                // only costs the rollback shortcut, while failing the install
                // after the pointer moved would leave a dangling current.
                LOG.warning("ffmpeg: could not write " + STATE + ": " + e.getMessage());
            }
        }
    }

    /// Returns to the previously active version, if there is one.
    public void rollback() throws IOException {
        synchronized (lock) {
            State state = readState();
            String previous = state.previous;
            if (previous == null || previous.isBlank() || previous.equals(state.current)) {
                throw new IOException("ffmpeg_no_rollback: no previous runtime");
            }
            activate(previous);
        }
    }

    public void delete(String id) throws IOException {
        synchronized (lock) {
            String safe = safeId(id);
            State state = readState();
            if (safe.equals(state.current) || safe.equals(currentId())) {
                throw new IOException("ffmpeg_in_use: " + id + " is the active runtime");
            }
            Path directory = toolsDir.resolve(safe).normalize();
            if (!directory.startsWith(toolsDir) || !Files.isDirectory(directory)) {
                throw new IOException("ffmpeg_not_installed: " + id);
            }
            FfmpegArchive.deleteTree(directory);
            if (safe.equals(state.previous)) {
                state.previous = null;
                writeState(state);
            }
        }
    }

    /// Deletes old, unreferenced versions. Keeps `keep` newest besides current
    /// and previous; returns how many were removed.
    public int gc(int keep) {
        synchronized (lock) {
            State state = readState();
            Set<String> pinned = new java.util.HashSet<>();
            String active = currentId();
            if (active != null) {
                pinned.add(active);
            }
            if (state.current != null) {
                pinned.add(state.current);
            }
            if (state.previous != null) {
                pinned.add(state.previous);
            }
            List<Installed> candidates = new ArrayList<>();
            for (Installed installed : installed()) {
                if (!pinned.contains(installed.id())) {
                    candidates.add(installed);
                }
            }
            int removed = 0;
            for (int i = Math.max(0, keep); i < candidates.size(); i++) {
                FfmpegArchive.deleteTree(candidates.get(i).directory());
                removed++;
            }
            return removed;
        }
    }

    /// The version id `current` points at, or `null`.
    public @Nullable String currentId() {
        String pointer = readPointer();
        if (pointer != null && !pointer.isBlank()) {
            return pointer;
        }
        State state = readState();
        if (state.current != null && !state.current.isBlank()) {
            return state.current;
        }
        Path current = toolsDir.resolve("current");
        if (Files.isSymbolicLink(current)) {
            try {
                Path target = Files.readSymbolicLink(current);
                return target.getFileName() == null ? null : target.getFileName().toString();
            } catch (IOException ignored) {
                return null;
            }
        }
        if (Files.isDirectory(current) && Files.isRegularFile(current.resolve("bin").resolve(exe("ffmpeg")))) {
            return "legacy";
        }
        return null;
    }

    /// Moves the extracted tree so `<versionDir>/bin/ffmpeg` exists.
    private static void installTree(Path staging, Path versionDir, Path ffmpeg, Path ffprobe) throws IOException {
        Path binDir = ffmpeg.getParent();
        Path root = binDir != null && "bin".equals(binDir.getFileName().toString())
                ? binDir.getParent() : null;
        if (root != null && root.equals(staging)) {
            move(staging, versionDir);
            return;
        }
        if (root != null && root.startsWith(staging) && !Files.exists(versionDir)) {
            move(root, versionDir);
            FfmpegArchive.deleteTree(staging);
            return;
        }
        Path targetBin = versionDir.resolve("bin");
        Files.createDirectories(targetBin);
        Files.copy(ffmpeg, targetBin.resolve(ffmpeg.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(ffprobe, targetBin.resolve(ffprobe.getFileName()), StandardCopyOption.REPLACE_EXISTING);
    }

    private static void move(Path source, Path target) throws IOException {
        if (Files.exists(target)) {
            throw new IOException("target already exists: " + target);
        }
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }

    private void download(FfmpegCatalog.Entry entry, Path target, Progress progress)
            throws IOException, InterruptedException {
        URI uri = URI.create(entry.url());
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(10))
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        FfmpegInstaller.validateUrl(response.uri().toString());
        if (response.statusCode() != 200) {
            throw new IOException("download returned HTTP " + response.statusCode());
        }
        long total = response.headers().firstValueAsLong("content-length").orElse(-1);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IOException("SHA-256 is not available", e);
        }
        try (InputStream in = new BufferedInputStream(response.body());
             var out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[1 << 16];
            long copied = 0;
            int lastBucket = -1;
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                copied += read;
                if (total > 0) {
                    int bucket = (int) (copied * 100 / total);
                    if (bucket != lastBucket) {
                        lastBucket = bucket;
                        progress.stage("downloading " + copied / (1024 * 1024) + " / "
                                + total / (1024 * 1024) + " MiB", Math.min(0.58, 0.58 * copied / total));
                    }
                }
            }
        }
    }

    private static void verifySha(Path file, String expected) throws IOException {
        String actual;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[1 << 16];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            actual = HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IOException("could not compute SHA-256", e);
        }
        if (!actual.equalsIgnoreCase(expected)) {
            throw new IOException("ffmpeg_checksum_mismatch: expected " + expected + ", got " + actual);
        }
    }

    static List<String> probedFeatures(List<String> declared, FfmpegProbe.FfmpegInfo info) {
        List<String> result = new ArrayList<>();
        for (String feature : declared) {
            String needle = feature.toLowerCase(Locale.ROOT);
            boolean present = info.encoders().stream().anyMatch(value -> value.toLowerCase(Locale.ROOT).contains(needle))
                    || info.hwaccels().stream().anyMatch(value -> value.toLowerCase(Locale.ROOT).contains(needle))
                    || info.filters().stream().anyMatch(value -> value.toLowerCase(Locale.ROOT).contains(needle));
            if (present) {
                result.add(feature);
            }
        }
        return List.copyOf(result);
    }

    private static RuntimeRecord legacyRecord(String id) {
        RuntimeRecord record = new RuntimeRecord();
        record.id = id;
        record.version = id;
        record.source = "legacy";
        record.license = "unknown";
        record.installedAt = "";
        record.notes = "installed before the runtime manager; not verified against a catalogue";
        return record;
    }

    private @Nullable RuntimeRecord readRuntime(Path directory) {
        Path file = directory.resolve("runtime.json");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            RuntimeRecord record = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), RuntimeRecord.class);
            if (record == null) {
                return null;
            }
            if (record.featuresDeclared == null) {
                record.featuresDeclared = List.of();
            }
            if (record.featuresProbed == null) {
                record.featuresProbed = List.of();
            }
            if (record.major == null) {
                record.major = "";
            }
            return record;
        } catch (IOException | RuntimeException e) {
            LOG.warning("Could not read " + file + ": " + e.getMessage());
            return null;
        }
    }

    /// Source builds write the same record shape as downloaded artifacts. It is
    /// package-visible so `FfmpegBuildKit` cannot invent a second ledger.
    static void writeRuntime(Path directory, RuntimeRecord record) throws IOException {
        atomicWrite(directory.resolve("runtime.json"), GSON.toJson(record));
    }

    private void writePointer(String id) throws IOException {
        JsonObject object = new JsonObject();
        object.addProperty("version", id);
        atomicWrite(toolsDir.resolve(POINTER), object.toString());
    }

    private @Nullable String readPointer() {
        Path file = toolsDir.resolve(POINTER);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            JsonObject object = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            return object.has("version") && object.get("version").isJsonPrimitive()
                    ? object.get("version").getAsString() : null;
        } catch (IOException | RuntimeException e) {
            LOG.warning("Could not read " + file + ": " + e.getMessage());
            return null;
        }
    }

    private void writeSymlink(Path directory) {
        Path current = toolsDir.resolve("current");
        Path temporary = toolsDir.resolve(".current-" + UUID.randomUUID());
        try {
            Files.createSymbolicLink(temporary, directory);
            if (Files.isSymbolicLink(current)) {
                try {
                    Files.move(temporary, current, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.deleteIfExists(current);
                    Files.move(temporary, current, StandardCopyOption.REPLACE_EXISTING);
                }
            } else if (Files.exists(current)) {
                // A legacy `current` directory is kept as history; the pointer
                // already makes the new runtime authoritative.
                Path legacy = toolsDir.resolve("legacy-" + System.currentTimeMillis());
                Files.move(current, legacy, StandardCopyOption.ATOMIC_MOVE);
                Files.move(temporary, current, StandardCopyOption.ATOMIC_MOVE);
            } else {
                Files.move(temporary, current, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (IOException | UnsupportedOperationException e) {
            // Windows may not allow symlinks; `current.json` is the authoritative
            // pointer and `FfmpegLocator` reads it.
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
            }
            LOG.info("ffmpeg: could not create the `current` symlink; using current.json ("
                    + e.getMessage() + ")");
        }
    }

    private State readState() {
        Path file = toolsDir.resolve(STATE);
        if (!Files.isRegularFile(file)) {
            return new State();
        }
        try {
            State state = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), State.class);
            return state == null ? new State() : state;
        } catch (IOException | RuntimeException e) {
            LOG.warning("Could not read " + file + ": " + e.getMessage());
            return new State();
        }
    }

    private void writeState(State state) throws IOException {
        atomicWrite(toolsDir.resolve(STATE), GSON.toJson(state));
    }

    private static void atomicWrite(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
        Files.writeString(temporary, content, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void makeExecutable(Path file) {
        try {
            var permissions = Files.getPosixFilePermissions(file);
            permissions.add(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
            permissions.add(java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE);
            permissions.add(java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(file, permissions);
        } catch (Exception ignored) {
            // Windows decides by extension.
        }
    }

    static String exe(String name) {
        return org.jackhuang.hmcl.util.platform.OperatingSystem.CURRENT_OS
                == org.jackhuang.hmcl.util.platform.OperatingSystem.WINDOWS
                ? name + ".exe" : name;
    }

    private static String suffix(String archive) {
        String value = archive == null ? "" : archive.toLowerCase(Locale.ROOT);
        return switch (value) {
            case "zip" -> ".zip";
            case "tar" -> ".tar";
            case "tar.gz", "tgz" -> ".tar.gz";
            case "tar.xz", "txz" -> ".tar.xz";
            default -> ".bin";
        };
    }

    /// Keeps ids to a path-safe alphabet; a catalogue id is metadata, not a path.
    static String safeId(String id) {
        String value = id == null ? "" : id.trim().replaceAll("[^A-Za-z0-9._-]", "_");
        if (value.isBlank()) {
            value = "ffmpeg";
        }
        return value.length() > 80 ? value.substring(0, 80) : value;
    }

    private static String nullSafe(@Nullable String value) {
        return value == null ? "" : value;
    }
}