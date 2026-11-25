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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jackhuang.hmcl.util.io.SafePaths;
import org.jackhuang.hmcl.util.logging.Logger;
import org.jackhuang.hmcl.util.platform.Architecture;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jackhuang.hmcl.util.platform.Platform;
import org.jackhuang.hmcl.util.platform.Privileges;
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
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Stream;

/// Installs the small artifacts that let a minimal image run what the panel ships.
///
/// Two independent bundles:
///
/// - a **runtime closure** under `<data>/toolchains/runtime/<version>/` — the
///   shared libraries Node.js links and `setpriv` with its `libcap-ng`. Node's
///   official Linux build links `libatomic.so.1` and `libstdc++.so.6`; a
///   `debian:*-slim` image has neither, so a managed runtime unpacks, registers
///   and then exits 127 on its first run;
/// - a **CA bundle** under `<data>/toolchains/ca/<version>/` — one
///   `cacert.pem` every child is pointed at, so npm and the agents trust the
///   same roots on an image whose `/etc/ssl` is empty or stale.
///
/// The layout mirrors [org.jackhuang.hmcl.web.transcode.runtime.FfmpegRuntimeManager]:
/// a version directory is filled in a staging directory, the record is written
/// last, and only then is it published and `current` moved. A failed or
/// interrupted install deletes its staging directory and never becomes a
/// version the resolver can pick.
///
/// Nothing is discovered at run time: every URL and hash comes from
/// [BootstrapCatalog], which ships in the jar. The only network input is the
/// bytes at those pinned addresses.
@NotNullByDefault
public final class BootstrapRuntimeManager {

    private static final Logger LOG = Logger.LOG;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /// The file that annotates an installed runtime bundle.
    static final String RUNTIME_RECORD = "runtime.json";

    /// The file that annotates an installed CA bundle.
    static final String CA_RECORD = "ca.json";

    /// The pointer naming the active version of either bundle.
    static final String POINTER = "current.json";

    /// The libraries a Node.js binary links and a minimal image leaves out.
    static final List<String> NODE_LIBRARIES = List.of(
            "libstdc++.so.6", "libgcc_s.so.1", "libatomic.so.1");

    /// The `setpriv` half of the closure and the library it needs.
    static final List<String> SETPRIV_FILES = List.of("bin/setpriv", "lib/libcap-ng.so.0");

    /// The uid the image gives to `hdsl`; the identity patch uses the same number.
    private static final int AGENT_UID = Privileges.AGENT_UID;

    /// The user name the container image uses. A passwd entry for another name
    /// with the same uid is left alone; only a missing uid is filled in.
    private static final String AGENT_NAME = "hdsl";

    /// Progress sink; `fraction` is 0..1 and advisory.
    public interface Progress {
        void stage(String message, double fraction);

        /// A sink that says nothing.
        Progress NONE = (message, fraction) -> {
        };
    }

    /// Where one artifact's bytes come from.
    ///
    /// Production uses [#HttpSource]; a test passes a lambda that copies a
    /// local file, so verification, extraction, activation and cleanup are all
    /// exercised without a network or a multi-megabyte download.
    public interface ArtifactSource {
        void fetch(String url, Path target) throws IOException, InterruptedException;
    }

    /// What `runtime.json` records.
    public static final class RuntimeRecord {
        public int schema = 1;
        public String version = "";
        public String os = "";
        public String arch = "";
        public String glibc = "";
        public String installedAt = "";
        public List<ArtifactRecord> artifacts = List.of();
    }

    /// One artifact's provenance.
    public static final class ArtifactRecord {
        public String name = "";
        public String archive = "";
        public String url = "";
        public String sha256 = "";
        public long size;
    }

    /// What `ca.json` records.
    public static final class CaRecord {
        public int schema = 1;
        public String version = "";
        public String file = "";
        public String url = "";
        public String sha256 = "";
        public long size;
        public String installedAt = "";
    }

    /// An installed runtime closure.
    public record Installed(String version, Path directory, RuntimeRecord record, boolean current) {
    }

    /// An installed CA bundle.
    public record CaInstalled(String version, Path directory, Path file, CaRecord record,
                              boolean current) {
    }

    /// The outcome of the passwd/group patch.
    ///
    /// @param applicable whether this deployment is one the patch is for
    /// @param changed    whether a file was actually written
    /// @param detail     why nothing happened, or which files changed
    public record IdentityResult(boolean applicable, boolean changed, String detail) {
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private static final Object DEFAULT_LOCK = new Object();
    private static volatile @Nullable BootstrapRuntimeManager defaultManager;

    private final Path dataRoot;
    private final Path runtimeRoot;
    private final Path caRoot;
    private final BootstrapCatalog.Catalog catalog;
    private final Object lock = new Object();

    /// @param dataRoot the `<data>/toolchains` directory
    /// @param catalog  the pinned catalog
    public BootstrapRuntimeManager(Path dataRoot, BootstrapCatalog.Catalog catalog) {
        this.dataRoot = dataRoot.toAbsolutePath().normalize();
        this.runtimeRoot = this.dataRoot.resolve("runtime");
        this.caRoot = this.dataRoot.resolve("ca");
        this.catalog = catalog;
    }

    public Path dataRoot() {
        return dataRoot;
    }

    public Path runtimeRoot() {
        return runtimeRoot;
    }

    public Path caRoot() {
        return caRoot;
    }

    public BootstrapCatalog.Catalog catalog() {
        return catalog;
    }

    /// `<data>/toolchains`, next to the launcher's own data directory.
    public static Path toolchainsRoot() {
        Path parent = DshPaths.ROOT.getParent();
        return (parent == null ? DshPaths.ROOT : parent).resolve("toolchains");
    }

    static BootstrapRuntimeManager defaultManager() {
        BootstrapRuntimeManager manager = defaultManager;
        if (manager == null) {
            synchronized (DEFAULT_LOCK) {
                manager = defaultManager;
                if (manager == null) {
                    manager = new BootstrapRuntimeManager(toolchainsRoot(),
                            BootstrapCatalog.current());
                    defaultManager = manager;
                }
            }
        }
        return manager;
    }

    /// Every installed runtime bundle, newest first.
    public List<Installed> installed() {
        return installedUnder(runtimeRoot, RUNTIME_RECORD);
    }

    private List<Installed> installedUnder(Path root, String recordFile) {
        List<Installed> result = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return result;
        }
        String current = pointer(root);
        try (Stream<Path> entries = Files.list(root)) {
            for (Path directory : entries.filter(Files::isDirectory).toList()) {
                String version = directory.getFileName().toString();
                if (version.startsWith(".") || version.equals("current")) {
                    continue;
                }
                RuntimeRecord record = readRuntimeRecord(directory, recordFile);
                if (record == null) {
                    if (!Files.isRegularFile(directory.resolve("lib").resolve("libstdc++.so.6"))) {
                        continue;
                    }
                    record = legacyRecord(version);
                }
                result.add(new Installed(version, directory, record, version.equals(current)));
            }
        } catch (IOException e) {
            LOG.warning("Failed to list bootstrap bundles in " + root, e);
        }
        result.sort(Comparator.comparing(Installed::version).reversed());
        return result;
    }

    /// The active runtime bundle, or `null` when none is installed.
    public @Nullable Installed current() {
        return current(runtimeRoot);
    }

    private @Nullable Installed current(Path root) {
        String version = pointer(root);
        if (version != null) {
            Path directory = root.resolve(version).normalize();
            if (directory.startsWith(root) && Files.isDirectory(directory)) {
                RuntimeRecord record = readRuntimeRecord(directory,
                        root.equals(runtimeRoot) ? RUNTIME_RECORD : CA_RECORD);
                if (record != null) {
                    return new Installed(version, directory, record, true);
                }
            }
        }
        Path link = root.resolve("current");
        if (Files.isSymbolicLink(link)) {
            try {
                Path target = Files.readSymbolicLink(link);
                String name = target.getFileName() == null ? null : target.getFileName().toString();
                if (name != null) {
                    Path directory = root.resolve(name).normalize();
                    RuntimeRecord record = readRuntimeRecord(directory,
                            root.equals(runtimeRoot) ? RUNTIME_RECORD : CA_RECORD);
                    if (record != null) {
                        return new Installed(name, directory, record, true);
                    }
                }
            } catch (IOException ignored) {
                // A broken link is not a runtime.
            }
        }
        for (Installed candidate : installedUnder(root,
                root.equals(runtimeRoot) ? RUNTIME_RECORD : CA_RECORD)) {
            return candidate;
        }
        return null;
    }

    /// Installs the runtime closure for this host when it is not installed yet.
    ///
    /// @return the active closure
    /// @throws IOException when this platform has no pinned closure or the
    ///                     download/extraction fails
    public Installed ensureRuntime() throws IOException {
        Installed current = current();
        if (current != null) {
            // Not only on the first install: /etc is exactly what a container
            // rebuild discards, while /data survives. Repairing the passwd/group
            // entries once, at install time, left a rebuilt container without
            // them — `setpriv --init-groups` then had no entry to resolve and
            // instances failed to start over something the panel had already
            // fixed once. The check is three early returns plus a file read.
            ensureIdentity();
            return current;
        }
        Platform platform = Platform.SYSTEM_PLATFORM;
        String os = osTag(platform);
        String arch = archTag(platform.arch());
        BootstrapCatalog.Runtime runtime = catalog.runtime(os, arch);
        if (runtime == null) {
            throw new IOException("bootstrap_runtime_unavailable: the catalog has no runtime"
                    + " closure for " + os + "/" + arch);
        }
        if (!runtime.available()) {
            throw new IOException("bootstrap_runtime_unavailable: " + os + "/" + arch
                    + " is marked unavailable" + (runtime.notes().isBlank()
                    ? "" : " (" + runtime.notes() + ")"));
        }
        Installed installed;
        try {
            installed = installRuntime(runtime, new HttpSource(), Progress.NONE);
        } catch (IOException e) {
            // Two requests can both find `current()` empty and race into the
            // install; the record check inside the lock catches the loser, and
            // "somebody else already installed it" is success. Reporting it as
            // a failure made a healthy install show up as a failed task.
            Installed raced = current();
            if (raced != null && e.getMessage() != null
                    && e.getMessage().startsWith("bootstrap_runtime_installed")) {
                ensureIdentity();
                return raced;
            }
            throw e;
        }
        registerSetpriv(installed);
        // The closure install is the one moment the panel owns the filesystem
        // it can also use to give `setpriv` a passwd entry to resolve.
        ensureIdentity();
        return installed;
    }

    /// Fills in the passwd/group entries `setpriv` and `getpwuid` want.
    ///
    /// Never throws and never repairs anything that is already right, so it is
    /// safe on the hot path as well as after an install.
    private void ensureIdentity() {
        IdentityResult identity = ensureAgentIdentity();
        if (identity.applicable() && !identity.changed()) {
            LOG.info("bootstrap: " + identity.detail());
        }
    }

    /// Installs one runtime bundle.
    ///
    /// @param runtime  the catalog entry
    /// @param source   where the artifacts come from
    /// @param progress a progress sink
    /// @return the installed bundle, now current
    /// @throws IOException when verification, extraction or activation fails
    public Installed installRuntime(BootstrapCatalog.Runtime runtime, ArtifactSource source,
                                    Progress progress) throws IOException {
        synchronized (lock) {
            if (!runtime.available()) {
                throw new IOException("bootstrap_runtime_unavailable: " + runtime.version());
            }
            if (runtime.artifacts().isEmpty()) {
                throw new IOException("bootstrap_artifact_invalid: " + runtime.version()
                        + " has no artifacts");
            }
            Path versionDir = safeChild(runtimeRoot, runtime.version());
            if (Files.isRegularFile(versionDir.resolve(RUNTIME_RECORD))) {
                throw new IOException("bootstrap_runtime_installed: " + runtime.version());
            }
            Files.createDirectories(runtimeRoot);
            // A previous interrupted attempt is not a usable bundle.
            deleteTree(versionDir);
            Path staging = runtimeRoot.resolve("." + runtime.version() + ".installing-"
                    + UUID.randomUUID());
            boolean activated = false;
            try {
                Files.createDirectories(staging);
                int index = 0;
                for (BootstrapCatalog.Artifact artifact : runtime.artifacts()) {
                    index++;
                    progress.stage("downloading " + artifact.name(), 0.1 * index
                            / runtime.artifacts().size());
                    Path download = staging.resolve("." + safeName(artifact.name()) + ".download");
                    try {
                        source.fetch(artifact.url(), download);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("bootstrap_install_interrupted", e);
                    }
                    progress.stage("verifying " + artifact.name(), 0.1 + 0.4 * index
                            / runtime.artifacts().size());
                    verify(download, artifact.sha256(), artifact.size());
                    progress.stage("unpacking " + artifact.name(), 0.5 + 0.3 * index
                            / runtime.artifacts().size());
                    BootstrapArchive.extract(download, artifact.archive(), artifact.includes(),
                            staging);
                    Files.deleteIfExists(download);
                }
                verifyRequiredFiles(staging);
                RuntimeRecord record = new RuntimeRecord();
                record.version = runtime.version();
                record.os = runtime.os();
                record.arch = runtime.arch();
                record.glibc = runtime.glibc();
                record.installedAt = Instant.now().toString();
                List<ArtifactRecord> artifacts = new ArrayList<>();
                for (BootstrapCatalog.Artifact artifact : runtime.artifacts()) {
                    ArtifactRecord entry = new ArtifactRecord();
                    entry.name = artifact.name();
                    entry.archive = artifact.archive();
                    entry.url = artifact.url();
                    entry.sha256 = artifact.sha256();
                    entry.size = artifact.size();
                    artifacts.add(entry);
                }
                record.artifacts = List.copyOf(artifacts);
                writeJson(staging.resolve(RUNTIME_RECORD), GSON.toJson(record));

                progress.stage("activating " + runtime.version(), 0.9);
                move(staging, versionDir);
                activate(runtimeRoot, runtime.version(), RUNTIME_RECORD);
                activated = true;
                progress.stage("runtime closure " + runtime.version() + " installed", 1);
                for (ArtifactRecord artifact : artifacts) {
                    BootstrapTrace.installed("closure", artifact.name, artifact.url,
                            "snapshot-sha256:" + artifact.sha256, versionDir);
                }
                return new Installed(runtime.version(), versionDir, record, true);
            } finally {
                deleteTree(staging);
                if (!activated) {
                    // A failed install must not leave a directory the resolver
                    // later mistakes for a bundle.
                    deleteTree(versionDir);
                }
            }
        }
    }

    /// The active CA bundle, or `null` when none is installed.
    public @Nullable CaInstalled currentCa() {
        String version = pointer(caRoot);
        Path directory = null;
        if (version != null) {
            Path candidate = caRoot.resolve(version).normalize();
            if (candidate.startsWith(caRoot) && Files.isDirectory(candidate)) {
                directory = candidate;
            }
        }
        if (directory == null) {
            directory = fallbackCaDirectory();
            if (directory == null) {
                return null;
            }
            version = directory.getFileName().toString();
        }
        CaRecord record = readCaRecord(directory);
        if (record == null) {
            return null;
        }
        Path file = SafePaths.resolveInside(directory, record.file);
        if (file == null) {
            LOG.warning("bootstrap: the CA record names a path outside " + directory);
            return null;
        }
        if (!Files.isRegularFile(file)) {
            return null;
        }
        return new CaInstalled(version, directory, file, record, true);
    }

    private @Nullable Path fallbackCaDirectory() {
        if (!Files.isDirectory(caRoot)) {
            return null;
        }
        try (Stream<Path> entries = Files.list(caRoot)) {
            return entries.filter(Files::isDirectory)
                    .filter(path -> !path.getFileName().toString().startsWith(".")
                            && !path.getFileName().toString().equals("current"))
                    .filter(path -> Files.isRegularFile(path.resolve(CA_RECORD)))
                    .max(Comparator.comparing(path -> path.getFileName().toString()))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /// Installs the pinned CA bundle when it is not installed yet.
    ///
    /// @return the active bundle
    /// @throws IOException when the catalog has no CA bundle or the download fails
    public CaInstalled ensureCa() throws IOException {
        CaInstalled current = currentCa();
        if (current != null) {
            return current;
        }
        BootstrapCatalog.Ca ca = catalog.ca();
        if (ca == null) {
            throw new IOException("bootstrap_ca_unavailable: the catalog has no CA bundle");
        }
        return installCa(ca, new HttpSource(), Progress.NONE);
    }

    /// Installs one CA bundle.
    ///
    /// @param ca       the catalog entry
    /// @param source   where the bytes come from
    /// @param progress a progress sink
    /// @return the installed bundle, now current
    /// @throws IOException when verification or activation fails
    public CaInstalled installCa(BootstrapCatalog.Ca ca, ArtifactSource source, Progress progress)
            throws IOException {
        synchronized (lock) {
            Path versionDir = safeChild(caRoot, ca.version());
            if (Files.isRegularFile(versionDir.resolve(CA_RECORD))) {
                throw new IOException("bootstrap_ca_installed: " + ca.version());
            }
            Files.createDirectories(caRoot);
            deleteTree(versionDir);
            Path staging = caRoot.resolve("." + ca.version() + ".installing-" + UUID.randomUUID());
            boolean activated = false;
            try {
                Files.createDirectories(staging);
                Path target = staging.resolve(ca.fileName());
                progress.stage("downloading the CA bundle", 0);
                try {
                    source.fetch(ca.url(), target);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("bootstrap_install_interrupted", e);
                }
                progress.stage("verifying the CA bundle", 0.6);
                verify(target, ca.sha256(), ca.size());
                CaRecord record = new CaRecord();
                record.version = ca.version();
                record.file = ca.fileName();
                record.url = ca.url();
                record.sha256 = ca.sha256();
                record.size = ca.size();
                record.installedAt = Instant.now().toString();
                writeJson(staging.resolve(CA_RECORD), GSON.toJson(record));
                progress.stage("activating the CA bundle", 0.9);
                move(staging, versionDir);
                activate(caRoot, ca.version(), CA_RECORD);
                activated = true;
                BootstrapTrace.installed("ca", ca.version(), ca.url(),
                        "sha256:" + ca.sha256(), versionDir);
                progress.stage("CA bundle " + ca.version() + " installed", 1);
                return new CaInstalled(ca.version(), versionDir,
                        versionDir.resolve(ca.fileName()), record, true);
            } finally {
                deleteTree(staging);
                if (!activated) {
                    deleteTree(versionDir);
                }
            }
        }
    }

    private void activate(Path root, String version, String recordFile) throws IOException {
        Path directory = safeChild(root, version);
        if (!Files.isRegularFile(directory.resolve(recordFile))) {
            throw new IOException("bootstrap_not_installed: " + version);
        }
        JsonObject pointer = new JsonObject();
        pointer.addProperty("version", version);
        writeJson(root.resolve(POINTER), pointer.toString());
        writeSymlink(root, directory);
    }

    private void writeSymlink(Path root, Path directory) {
        Path current = root.resolve("current");
        Path temporary = root.resolve(".current-" + UUID.randomUUID());
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
                Path previous = root.resolve("legacy-" + System.currentTimeMillis());
                Files.move(current, previous, StandardCopyOption.ATOMIC_MOVE);
                Files.move(temporary, current, StandardCopyOption.ATOMIC_MOVE);
            } else {
                Files.move(temporary, current, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (IOException | UnsupportedOperationException e) {
            // `current.json` is authoritative; the link is a convenience.
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
            }
            LOG.info("bootstrap: could not create the `current` symlink; using current.json ("
                    + e.getMessage() + ")");
        }
    }

    private void verifyRequiredFiles(Path staging) throws IOException {
        for (String library : NODE_LIBRARIES) {
            Path file = staging.resolve("lib").resolve(library);
            if (!Files.isRegularFile(file)) {
                throw new IOException("bootstrap_artifact_invalid: the bundle has no lib/"
                        + library);
            }
        }
        for (String required : SETPRIV_FILES) {
            Path file = staging.resolve(required);
            if (!Files.isRegularFile(file)) {
                throw new IOException("bootstrap_artifact_invalid: the bundle has no " + required);
            }
        }
        Path setpriv = staging.resolve("bin").resolve("setpriv");
        if (!Files.isExecutable(setpriv)) {
            throw new IOException("bootstrap_artifact_invalid: bin/setpriv is not executable");
        }
    }

    private static void verify(Path file, String expected, long size) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("bootstrap_artifact_invalid: " + file + " was not downloaded");
        }
        long actualSize = Files.size(file);
        if (size > 0 && actualSize != size) {
            throw new IOException("bootstrap_size_mismatch: expected " + size
                    + " bytes, got " + actualSize);
        }
        String actual = sha256(file);
        if (!actual.equalsIgnoreCase(expected)) {
            throw new IOException("bootstrap_checksum_mismatch: expected " + expected
                    + ", got " + actual);
        }
    }

    static String sha256(Path file) throws IOException {
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
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable", e);
        }
    }

    private @Nullable RuntimeRecord readRuntimeRecord(Path directory, String recordFile) {
        Path file = directory.resolve(recordFile);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            RuntimeRecord record = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                    RuntimeRecord.class);
            if (record == null) {
                return null;
            }
            if (record.artifacts == null) {
                record.artifacts = List.of();
            }
            return record;
        } catch (IOException | RuntimeException e) {
            LOG.warning("Could not read " + file + ": " + e.getMessage());
            return null;
        }
    }

    private @Nullable CaRecord readCaRecord(Path directory) {
        Path file = directory.resolve(CA_RECORD);
        if (!Files.isRegularFile(file)) {
            // A directory laid down by hand is not trusted: the record is what
            // names the file and its hash.
            return null;
        }
        try {
            CaRecord record = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                    CaRecord.class);
            if (record == null || record.file == null || record.file.isBlank()) {
                return null;
            }
            return record;
        } catch (IOException | RuntimeException e) {
            LOG.warning("Could not read " + file + ": " + e.getMessage());
            return null;
        }
    }

    private static RuntimeRecord legacyRecord(String version) {
        RuntimeRecord record = new RuntimeRecord();
        record.version = version;
        record.os = "linux";
        record.arch = archTag(Architecture.CURRENT_ARCH);
        record.installedAt = "";
        return record;
    }

    private @Nullable String pointer(Path root) {
        Path file = root.resolve(POINTER);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            JsonObject object = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            return object.has("version") && object.get("version").isJsonPrimitive()
                    ? object.get("version").getAsString() : null;
        } catch (IOException | RuntimeException e) {
            LOG.warning("Could not read " + file + ": " + e.getMessage());
            return null;
        }
    }

    private static void writeJson(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
        Files.writeString(temporary, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
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

    private static Path safeChild(Path root, String version) {
        Path directory = root.resolve(version).normalize();
        if (!directory.startsWith(root) || directory.equals(root)) {
            throw new IllegalArgumentException("bootstrap version escapes its root: " + version);
        }
        return directory;
    }

    private static String safeName(String name) {
        String value = name == null ? "artifact" : name.replaceAll("[^A-Za-z0-9._-]", "_");
        return value.isBlank() ? "artifact" : value;
    }

    static void deleteTree(@Nullable Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(candidate -> {
                try {
                    Files.deleteIfExists(candidate);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    /// The OS tag the catalogue uses.
    static String osTag(Platform platform) {
        return switch (platform.os()) {
            case LINUX -> "linux";
            case WINDOWS -> "windows";
            case MACOS -> "macos";
            case FREEBSD -> "freebsd";
            default -> platform.os().name().toLowerCase(Locale.ROOT);
        };
    }

    /// The architecture tag the catalogue uses.
    static String archTag(Architecture arch) {
        return switch (arch) {
            case X86_64 -> "x64";
            case ARM64 -> "arm64";
            default -> arch.name().toLowerCase(Locale.ROOT);
        };
    }

    // ---------------------------------------------------------------- libraries

    /// Where a shared library may be found on this host.
    ///
    /// The managed closure comes first, then the multiarch and legacy library
    /// directories. The list is not a search algorithm: a library is "available"
    /// when the file exists in one of them, which is what the dynamic loader
    /// would find for the common case and is cheap enough to ask on every install.
    public static List<Path> nodeLibrarySearchDirectories() {
        List<Path> directories = new ArrayList<>();
        Installed managed = currentRuntimeClosure();
        if (managed != null) {
            directories.add(managed.directory().resolve("lib"));
        }
        String triplet = switch (Architecture.CURRENT_ARCH) {
            case X86_64 -> "x86_64-linux-gnu";
            case ARM64 -> "aarch64-linux-gnu";
            default -> "";
        };
        if (!triplet.isEmpty()) {
            directories.add(Path.of("/lib", triplet));
            directories.add(Path.of("/usr/lib", triplet));
            directories.add(Path.of("/usr/local/lib", triplet));
        }
        directories.add(Path.of("/lib64"));
        directories.add(Path.of("/usr/lib64"));
        directories.add(Path.of("/lib"));
        directories.add(Path.of("/usr/lib"));
        directories.add(Path.of("/usr/local/lib"));
        return directories;
    }

    /// The Node.js libraries that are not visible on this host.
    ///
    /// @return the missing file names, empty when every one is available
    public static List<String> missingNodeLibraries() {
        return missingNodeLibraries(nodeLibrarySearchDirectories(), Files::exists);
    }

    /// The pure form, with the search path named so a test can drive it.
    ///
    /// @param directories where to look
    /// @param exists      how to ask whether a file is there
    /// @return the missing file names
    static List<String> missingNodeLibraries(List<Path> directories, Predicate<Path> exists) {
        List<String> missing = new ArrayList<>();
        for (String library : NODE_LIBRARIES) {
            boolean present = false;
            for (Path directory : directories) {
                if (exists.test(directory.resolve(library))) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                missing.add(library);
            }
        }
        return missing;
    }

    /// Whether every Node.js library is available, system or managed.
    public static boolean nodeLibrariesAvailable() {
        return missingNodeLibraries().isEmpty();
    }

    /// The managed runtime closure, or `null` when none is installed.
    public static @Nullable Installed currentRuntimeClosure() {
        try {
            Installed current = defaultManager().current();
            if (current != null) {
                registerSetpriv(current);
            }
            return current;
        } catch (RuntimeException e) {
            LOG.warning("Could not read the bootstrap runtime catalog: " + e.getMessage());
            return null;
        }
    }

    /// Hands the closure's `setpriv` to the privilege helper.
    ///
    /// `ProcessBuilder` resolves a bare program name against the panel's own
    /// `PATH`, not the child's, so a minimal image needs the absolute path here.
    /// A registration is cheap and idempotent; it is skipped when the file is
    /// missing or not executable.
    private static void registerSetpriv(Installed installed) {
        Path setpriv = installed.directory().resolve("bin").resolve("setpriv");
        if (Files.isExecutable(setpriv)) {
            Privileges.registerBootstrapSetpriv(setpriv);
        }
    }

    /// Registers the closure's `setpriv` at startup.
    ///
    /// Called once when the panel boots so the first agent launch does not have
    /// to wait for something else to ask about the closure.
    public static void registerBootstrapTools() {
        currentRuntimeClosure();
    }

    /// The managed CA bundle, or `null` when none is installed.
    public static @Nullable CaInstalled currentCaBundle() {
        try {
            return defaultManager().currentCa();
        } catch (RuntimeException e) {
            LOG.warning("Could not read the bootstrap CA catalog: " + e.getMessage());
            return null;
        }
    }

    /// Installs the pinned runtime closure for this host if it is absent.
    ///
    /// @return the active closure
    /// @throws IOException when no closure is pinned or the install fails
    public static Installed ensureRuntimeClosure() throws IOException {
        return defaultManager().ensureRuntime();
    }

    /// Installs the pinned CA bundle if it is absent.
    ///
    /// @return the active bundle
    /// @throws IOException when no CA bundle is pinned or the install fails
    public static CaInstalled ensureCaBundle() throws IOException {
        return defaultManager().ensureCa();
    }

    // ----------------------------------------------------------------- identity

    /// Fills in the `hdsl` passwd/group entries a minimal image may lack.
    ///
    /// The numeric `setpriv` call ([Privileges#asAgent]) no longer needs these
    /// entries, but a tool that calls `getpwuid` does. The patch is deliberately
    /// narrow: only inside a container, only as root, only when the uid or gid is
    /// absent, and every existing line is preserved. A backup is written beside
    /// the original before anything is replaced.
    ///
    /// @return what happened; never throws
    public static IdentityResult ensureAgentIdentity() {
        if (OperatingSystem.CURRENT_OS != OperatingSystem.LINUX) {
            return new IdentityResult(false, false, "the identity patch is Linux-only");
        }
        if (!Privileges.inContainer()) {
            return new IdentityResult(false, false, "not inside a container");
        }
        if (!Privileges.isRoot()) {
            return new IdentityResult(false, false, "the panel is not root");
        }
        Path passwd = Path.of("/etc/passwd");
        Path group = Path.of("/etc/group");
        try {
            IdentityResult result = patchIdentityFiles(passwd, group, AGENT_UID, AGENT_NAME);
            if (result.changed()) {
                LOG.info("bootstrap: " + result.detail());
            }
            return result;
        } catch (IOException e) {
            LOG.warning("bootstrap: could not add the agent's passwd/group entries: "
                    + e.getMessage());
            return new IdentityResult(true, false, e.getMessage());
        }
    }

    /// The passwd/group patch with the files named, so a test can drive it in a
    /// temporary directory instead of writing `/etc`.
    ///
    /// @param passwd   the passwd file (may not exist)
    /// @param group    the group file (may not exist)
    /// @param uid      the uid to add when absent
    /// @param userName the account name
    /// @return what happened
    /// @throws IOException when a file could not be written
    static IdentityResult patchIdentityFiles(Path passwd, Path group, int uid, String userName)
            throws IOException {
        String passwdBefore = Files.isRegularFile(passwd) ? Files.readString(passwd) : "";
        String groupBefore = Files.isRegularFile(group) ? Files.readString(group) : "";
        @Nullable String passwdAfter = patchPasswd(passwdBefore, uid, userName);
        @Nullable String groupAfter = patchGroup(groupBefore, uid, userName);
        List<String> changed = new ArrayList<>();
        if (passwdAfter != null) {
            backup(passwd);
            writeIdentityFile(passwd, passwdAfter);
            changed.add(passwd.toString());
        }
        if (groupAfter != null) {
            backup(group);
            writeIdentityFile(group, groupAfter);
            changed.add(group.toString());
        }
        if (changed.isEmpty()) {
            return new IdentityResult(true, false,
                    "the agent's uid and gid already have passwd/group entries");
        }
        return new IdentityResult(true, true, "added the agent's entries to "
                + String.join(", ", changed));
    }

    /// The passwd file with an entry appended for `uid`, or `null` when one exists.
    ///
    /// @param content  the current contents
    /// @param uid      the uid
    /// @param userName the account name
    /// @return the new contents, or `null` when nothing needs to change
    static @Nullable String patchPasswd(String content, int uid, String userName) {
        if (uid == 0 || !validName(userName) || hasNumericField(content, 2, uid)) {
            return null;
        }
        return appendLine(content, userName + ":x:" + uid + ":" + uid
                + ":HDSL agent:/home/" + userName + ":/bin/bash");
    }

    /// The group file with an entry appended for `gid`, or `null` when one exists.
    static @Nullable String patchGroup(String content, int gid, String userName) {
        if (gid == 0 || !validName(userName) || hasNumericField(content, 2, gid)) {
            return null;
        }
        return appendLine(content, userName + ":x:" + gid + ":");
    }

    private static boolean validName(String name) {
        return name != null && name.matches("[a-z_][a-z0-9_-]{0,31}");
    }

    private static boolean hasNumericField(String content, int field, int number) {
        String wanted = Integer.toString(number);
        for (String line : content.split("\\R")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] fields = line.split(":", -1);
            if (fields.length > field && fields[field].trim().equals(wanted)
                    && !fields[0].isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static String appendLine(String content, String line) {
        if (content.isEmpty()) {
            return line + "\n";
        }
        return content.endsWith("\n") ? content + line + "\n" : content + "\n" + line + "\n";
    }

    private static void backup(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return;
        }
        Path backup = file.resolveSibling(file.getFileName() + ".hdsl-bak-"
                + Instant.now().toEpochMilli());
        Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void writeIdentityFile(Path file, String content) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp-" + UUID.randomUUID());
        Files.writeString(temporary, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        try {
            Files.setPosixFilePermissions(temporary,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
        } catch (UnsupportedOperationException ignored) {
            // Windows has no /etc/passwd to patch.
        }
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ----------------------------------------------------------------- download

    /// Streams one pinned artifact over https.
    ///
    /// Redirects are followed only while they stay on https; the response's
    /// final URI is validated again, because a redirect is a second address the
    /// catalogue did not name.
    static final class HttpSource implements ArtifactSource {
        @Override
        public void fetch(String url, Path target) throws IOException {
            BootstrapCatalog.validateHttps(url);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(10))
                    .GET()
                    .build();
            HttpResponse<InputStream> response;
            try {
                response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("bootstrap_download_interrupted", e);
            }
            BootstrapCatalog.validateHttps(response.uri().toString());
            if (response.statusCode() != 200) {
                try (InputStream ignored = response.body()) {
                    // Closed; the body is not the error message.
                }
                throw new IOException("bootstrap_download_failed: HTTP " + response.statusCode()
                        + " for " + url);
            }
            try (InputStream in = new BufferedInputStream(response.body());
                 var out = Files.newOutputStream(target)) {
                byte[] buffer = new byte[1 << 16];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, read);
                }
            }
        }
    }
}