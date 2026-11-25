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
import org.jackhuang.hmcl.util.logging.Logger;
import org.jackhuang.hmcl.util.platform.Architecture;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jackhuang.hmcl.util.platform.Privileges;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedInputStream;
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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/// The one CPython every child process may use.
///
/// node-gyp, `pnpm install`, `npm ci` and uv's Python discovery all want an
/// interpreter, and a minimal image has none. Downloading one per brand — which
/// is what Octop's `uv sync --python 3.12` and Hermes' own toolchain directory
/// did — put a 300 MB copy inside every release and made two brands unable to
/// share the same bytes.
///
/// This manager owns `<data>/toolchains/python/<version>/` instead:
///
/// - the default **3.12** line, pinned to a python-build-standalone
///   `install_only` archive with its SHA-256, shared by ZCode and node-gyp;
/// - whatever version a brand's own lockfile pins (Hermes ships 3.14.x), passed
///   in as an explicit artifact so the lock's URL and digest remain the source
///   of truth. The first brand to install it wins; the next one reuses it.
///
/// The layout is deliberately the same shape as [BootstrapRuntimeManager]: a
/// staging directory is filled, the record is written last, the directory is
/// published, and only then does `current` move. A failed install deletes its
/// staging directory and the version it was writing, so a half-unpacked tree is
/// never mistaken for an interpreter.
///
/// Archives are unpacked in Java. python-build-standalone's `install_only`
/// archive has a single `python/` top-level member; that member is dropped so
/// `<root>/bin/python3` (or `<root>/python.exe` on Windows) is where callers
/// expect it. Symbolic links are recreated rather than copied — the archive's
/// `python3 -> python3.12` is what `sys.executable` and shebangs resolve
/// through.
@NotNullByDefault
public final class PythonRuntimeManager {

    private static final Logger LOG = Logger.LOG;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /// The file that annotates an installed interpreter.
    public static final String RECORD = "python.json";

    /// The pointer naming the active version.
    public static final String POINTER = "current.json";

    /// The stable line ZCode and node-gyp build against, from the catalogue.
    ///
    /// The version, the python-build-standalone tag, the release base and the
    /// per-platform digests all live in `/bootstrap/bootstrap.json`; an
    /// operator can point at another catalogue with `HDSL_BOOTSTRAP_CATALOG`.
    public static String defaultVersion() {
        return python().version();
    }

    /// The python-build-standalone release tag the default line is pinned to.
    static String buildTag() {
        return python().build();
    }

    /// Where python-build-standalone publishes its releases.
    public static String releaseBase() {
        return python().releaseBase();
    }

    /// The pinned archive for this platform, or `null` when none is pinned.
    static @Nullable Artifact pinnedArtifact() {
        BootstrapCatalog.PythonArtifact pinned = python().artifact(platformTag());
        return pinned == null ? null
                : new Artifact(python().url(pinned), pinned.sha256(), pinned.size(),
                pinned.archive());
    }

    private static BootstrapCatalog.Python python() {
        BootstrapCatalog.Python python = BootstrapCatalog.current().python();
        if (python == null) {
            throw new IllegalStateException(
                    "bootstrap_catalog_incomplete: the catalogue pins no CPython line");
        }
        return python;
    }

    /// One pinned archive.
    ///
    /// @param url     an https address
    /// @param sha256  the SHA-256 of the exact bytes
    /// @param size    the expected size, or `0` when it is not stated
    /// @param archive `tar.gz`, `tgz` or `zip`
    public record Artifact(String url, String sha256, long size, String archive) {
        public Artifact {
            if (url == null || url.isBlank()) {
                throw new IllegalArgumentException("a Python artifact needs a URL");
            }
            if (url.contains("?") || url.contains("#")) {
                throw new IllegalArgumentException("a Python artifact URL must not carry a query or fragment");
            }
            BootstrapCatalog.validateHttps(url);
            if (sha256 == null || !sha256.trim().matches("(?i)[0-9a-f]{64}")) {
                throw new IllegalArgumentException("a Python artifact needs a pinned SHA-256");
            }
            sha256 = sha256.trim().toLowerCase(Locale.ROOT);
            if (size < 0) {
                throw new IllegalArgumentException("a Python artifact has a negative size");
            }
            archive = archive == null || archive.isBlank()
                    ? (url.toLowerCase(Locale.ROOT).endsWith(".zip") ? "zip" : "tar.gz")
                    : archive.trim().toLowerCase(Locale.ROOT);
            if (!archive.equals("zip") && !archive.equals("tar.gz") && !archive.equals("tgz")
                    && !archive.equals("tar")) {
                throw new IllegalArgumentException("unsupported Python archive: " + archive);
            }
        }
    }

    /// What `python.json` records.
    public static final class PythonRecord {
        public int schema = 1;
        public String version = "";
        public String os = "";
        public String arch = "";
        public String build = "";
        public String url = "";
        public String sha256 = "";
        public long size;
        public String archive = "";
        public String interpreter = "";
        public String installedAt = "";
    }

    /// An installed interpreter.
    ///
    /// @param version     the directory name
    /// @param directory   `<data>/toolchains/python/<version>`
    /// @param interpreter the runnable `python3` / `python.exe`
    /// @param record      what `python.json` says, or a synthetic record for a
    ///                    directory someone provisioned by hand
    /// @param current     whether the pointer names it
    public record Installed(String version, Path directory, Path interpreter,
                            PythonRecord record, boolean current) {
    }

    /// The mirror the deployment named, resolved once: an invalid value would
    /// otherwise be re-read and re-warned on every child process.
    private static final @Nullable String MIRROR_BASE = mirrorBase();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private static final Object DEFAULT_LOCK = new Object();
    private static volatile @Nullable PythonRuntimeManager defaultManager;

    /// One install lock per root, not per manager instance.
    ///
    /// A second `PythonRuntimeManager` for the same directory is created on
    /// purpose (a ZCode build may name its own shared root). Two instance-level
    /// locks let both installs run at once; the loser's cleanup then deleted
    /// the tree the winner had just published.
    private static final java.util.concurrent.ConcurrentHashMap<Path, Object> ROOT_LOCKS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final Path root;

    private Object installLock() {
        return ROOT_LOCKS.computeIfAbsent(root, ignored -> new Object());
    }

    /// @param root the `<data>/toolchains/python` directory
    public PythonRuntimeManager(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    /// `<data>/toolchains/python`, next to the runtime closure.
    public static Path defaultRoot() {
        return BootstrapRuntimeManager.toolchainsRoot().resolve("python");
    }

    public static PythonRuntimeManager defaultManager() {
        PythonRuntimeManager manager = defaultManager;
        if (manager == null) {
            synchronized (DEFAULT_LOCK) {
                manager = defaultManager;
                if (manager == null) {
                    manager = new PythonRuntimeManager(defaultRoot());
                    defaultManager = manager;
                }
            }
        }
        return manager;
    }

    /// The pinned default line, or `null` on a platform this build does not pin.
    ///
    /// A musl system (Alpine and its relatives) is reported as unpinned rather
    /// than handed a glibc archive: the binary would install and then fail at
    /// exec with a loader error that reads like a corrupt download.
    public static @Nullable Artifact defaultArtifact() {
        if (isMusl(Files::exists)) {
            return null;
        }
        Artifact canonical = pinnedArtifact();
        if (canonical == null) {
            return null;
        }
        return MIRROR_BASE == null ? canonical : relocated(canonical, MIRROR_BASE);
    }

    /// Whether this is a musl Linux, where the pinned glibc archive cannot run.
    static boolean isMusl(java.util.function.Predicate<Path> exists) {
        if (OperatingSystem.CURRENT_OS != OperatingSystem.LINUX) {
            return false;
        }
        return exists.test(Path.of("/etc/alpine-release"))
                || exists.test(Path.of("/lib/ld-musl-x86_64.so.1"))
                || exists.test(Path.of("/lib/ld-musl-aarch64.so.1"));
    }

    /// The same archive served from another base, e.g. the NJU GitHub mirror.
    ///
    /// The digest is unchanged: a mirror that serves different bytes fails
    /// verification rather than becoming trusted.
    ///
    /// @param artifact the canonical archive
    /// @param base     an https base replacing
    ///                 `https://github.com/astral-sh/python-build-standalone/releases/download`
    /// @return the same bytes at the new address
    public static Artifact relocated(Artifact artifact, String base) {
        String normalized = base == null ? "" : base.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        String asset = assetName(artifact.url());
        String url = normalized + "/" + python().build() + "/" + asset;
        BootstrapCatalog.validateHttps(url);
        return new Artifact(url, artifact.sha256(), artifact.size(), artifact.archive());
    }

    /// The archive's file name.
    public static String assetName(String url) {
        int slash = url.lastIndexOf('/');
        return slash < 0 ? url : url.substring(slash + 1);
    }

    /// The python-build-standalone tag in an asset name
    /// (`cpython-3.14.9+20261021-x86_64-…`), or `""` when it is not one.
    static String buildOf(String url) {
        String name = assetName(url);
        int plus = name.indexOf('+');
        int dash = plus < 0 ? -1 : name.indexOf('-', plus + 1);
        if (plus < 0 || dash < 0) {
            return "";
        }
        String build = name.substring(plus + 1, dash);
        return build.matches("\\d{8}") ? build : "";
    }

    /// `UV_PYTHON_INSTALL_MIRROR` (the deployment's existing knob) or
    /// `HDSL_PYTHON_MIRROR`, when either is a usable https base.
    static @Nullable String mirrorBase() {
        for (String name : List.of("UV_PYTHON_INSTALL_MIRROR", "HDSL_PYTHON_MIRROR")) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) {
                continue;
            }
            String normalized = value.trim();
            while (normalized.endsWith("/")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            try {
                URI uri = URI.create(normalized);
                if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                        || uri.getUserInfo() != null || uri.getQuery() != null
                        || uri.getFragment() != null
                        || normalized.toLowerCase(Locale.ROOT).contains("latest")) {
                    throw new IllegalArgumentException("not a pinned https base");
                }
                return normalized;
            } catch (RuntimeException e) {
                LOG.warning(name + " is not usable as a Python mirror (" + e.getMessage()
                        + "); using the canonical address");
            }
        }
        return null;
    }

    private static String platformTag() {
        String os = switch (OperatingSystem.CURRENT_OS) {
            case LINUX -> "linux";
            case WINDOWS -> "windows";
            case MACOS -> "macos";
            default -> OperatingSystem.CURRENT_OS.name().toLowerCase(Locale.ROOT);
        };
        String arch = switch (Architecture.CURRENT_ARCH) {
            case X86_64 -> "x64";
            case ARM64 -> "arm64";
            default -> Architecture.CURRENT_ARCH.name().toLowerCase(Locale.ROOT);
        };
        return os + "-" + arch;
    }

    // ------------------------------------------------------------------- use --

    /// The active interpreter, or `null` when none is installed.
    public @Nullable Installed current() {
        String version = pointer();
        if (version != null) {
            Installed pointed = find(version);
            if (pointed != null) {
                return new Installed(pointed.version(), pointed.directory(),
                        pointed.interpreter(), pointed.record(), true);
            }
        }
        for (Installed candidate : installed()) {
            return candidate;
        }
        return null;
    }

    /// One version, or `null` when the directory has no interpreter.
    public @Nullable Installed find(String version) {
        if (!usableSegment(version)) {
            return null;
        }
        Path directory = safeChild(root, version);
        if (!Files.isDirectory(directory)) {
            return null;
        }
        Path interpreter = interpreterIn(directory);
        if (interpreter == null) {
            return null;
        }
        if (!Privileges.useAsRoot(directory, interpreter)) {
            LOG.warning("python: refusing " + directory
                    + " because it is not root-owned and sealed; reinstall it");
            return null;
        }
        PythonRecord record = readRecord(directory);
        if (record == null) {
            record = legacyRecord(version, directory, interpreter);
        } else if (record.interpreter == null || record.interpreter.isBlank()) {
            record.interpreter = relative(directory, interpreter);
        }
        return new Installed(version, directory, interpreter, record,
                version.equals(pointer()));
    }

    /// Every version with an interpreter, newest first.
    public List<Installed> installed() {
        List<Installed> result = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return result;
        }
        try (Stream<Path> entries = Files.list(root)) {
            for (Path directory : entries.filter(Files::isDirectory).toList()) {
                String version = directory.getFileName().toString();
                if (version.startsWith(".") || version.equals("current")) {
                    continue;
                }
                Installed candidate = find(version);
                if (candidate != null) {
                    result.add(candidate);
                }
            }
        } catch (IOException e) {
            LOG.warning("Failed to list Python runtimes in " + root, e);
        }
        result.sort(Comparator.comparing(Installed::version,
                BootstrapRuntimeManager.versionOrder()).reversed());
        return result;
    }

    /// Installs and activates the default 3.12 line when it is absent.
    public Installed ensureDefault() throws IOException {
        return install(defaultVersion());
    }

    /// Installs and activates `version` from the pinned catalogue.
    ///
    /// @throws IOException when this platform has no pinned archive or the
    ///                     download fails
    public Installed install(String version) throws IOException {
        Installed existing = find(version);
        if (existing != null) {
            activate(version);
            BootstrapTrace.reused("python", version, existing.directory());
            return new Installed(existing.version(), existing.directory(),
                    existing.interpreter(), existing.record(), true);
        }
        Artifact artifact = defaultVersion().equals(version) ? defaultArtifact() : null;
        if (artifact == null) {
            String reason;
            if (isMusl(Files::exists)) {
                reason = " (this is a musl system and only the glibc build is pinned;"
                        + " install python3 from the distribution instead)";
            } else if (OperatingSystem.CURRENT_OS == OperatingSystem.LINUX
                    || OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
                reason = "";
            } else {
                reason = " (only Linux x64 and Windows x64 are pinned)";
            }
            throw new IOException("python_runtime_unavailable: no pinned CPython " + version
                    + " archive for " + platformTag() + reason);
        }
        return install(version, artifact, new HttpSource(), (message, fraction) -> {
        });
    }

    /// Installs and activates one version.
    ///
    /// An existing version is reused without touching the network, even when
    /// the caller supplies a different artifact: the lock still says what the
    /// bytes are, but the bytes are already here.
    ///
    /// @param version  the directory name (a brand's lock value is fine, the
    ///                 `+` of a build tag is a legal path segment)
    /// @param artifact where the bytes come from when a download is needed
    /// @param source   the fetcher
    /// @param progress a progress sink
    /// @return the installed interpreter, now current
    /// @throws IOException when verification, extraction, validation or
    ///                     activation fails
    public Installed install(String version, Artifact artifact,
                             BootstrapRuntimeManager.ArtifactSource source,
                             BootstrapRuntimeManager.Progress progress) throws IOException {
        if (!usableSegment(version)) {
            throw new IOException("python_runtime_invalid: '" + version + "' is not a usable version name");
        }
        synchronized (installLock()) {
            Installed existing = find(version);
            if (existing != null) {
                if (artifact.sha256() != null && existing.record().sha256 != null
                        && !artifact.sha256().equalsIgnoreCase(existing.record().sha256)) {
                    BootstrapTrace.note("python", "reusing " + version + " although this caller"
                            + " pinned a different digest; the bytes already on disk win");
                }
                activate(version);
                BootstrapTrace.reused("python", version, existing.directory());
                return new Installed(existing.version(), existing.directory(),
                        existing.interpreter(), existing.record(), true);
            }
            Path versionDir = safeChild(root, version);
            BootstrapRuntimeManager.ensureToolchainsRoot();
            Privileges.keepRootOwnedDirectory(root);
            // A previous interrupted attempt is not an interpreter.
            BootstrapRuntimeManager.deleteStaleStaging(root);
            BootstrapRuntimeManager.deleteTree(versionDir);
            Path staging = root.resolve("." + version + ".installing-" + UUID.randomUUID());
            boolean activated = false;
            try {
                Files.createDirectories(staging);
                // Root-owned while the bytes arrive: a staging tree the agent
                // can rewrite is one it can tamper with before the move.
                Privileges.keepRootOwnedDirectory(staging);
                progress.stage("downloading CPython " + version, 0.05);
                Path download = staging.resolve(".python.download");
                try {
                    source.fetch(artifact.url(), download);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("python_runtime_interrupted", e);
                }
                progress.stage("verifying CPython " + version, 0.45);
                verify(download, artifact.sha256(), artifact.size());
                progress.stage("unpacking CPython " + version, 0.6);
                extract(download, artifact.archive(), staging);
                Files.deleteIfExists(download);

                Path interpreter = interpreterIn(staging);
                if (interpreter == null) {
                    throw new IOException("python_runtime_invalid: the archive holds no interpreter"
                            + " (expected bin/python3 or python.exe)");
                }
                verifyRuns(interpreter);
                String platform = platformTag();
                PythonRecord record = new PythonRecord();
                record.version = version;
                record.os = platform.substring(0, platform.indexOf('-'));
                record.arch = platform.substring(platform.indexOf('-') + 1);
                record.build = buildOf(artifact.url());
                record.url = artifact.url();
                record.sha256 = artifact.sha256();
                record.size = artifact.size();
                record.archive = artifact.archive();
                record.interpreter = relative(staging, interpreter);
                record.installedAt = Instant.now().toString();
                writeJson(staging.resolve(RECORD), GSON.toJson(record));

                progress.stage("activating CPython " + version, 0.9);
                try {
                    move(staging, versionDir);
                } catch (IOException e) {
                    // Another manager for the same directory may have
                    // published while this one downloaded. A complete tree is
                    // the outcome the caller asked for; deleting it here was
                    // how the loser used to erase the winner's install.
                    Installed raced = e.getMessage() != null
                            && e.getMessage().startsWith("target already exists")
                            ? find(version) : null;
                    if (raced != null) {
                        activate(version);
                        activated = true;
                        BootstrapTrace.reused("python", version, raced.directory());
                        return new Installed(raced.version(), raced.directory(),
                                raced.interpreter(), raced.record(), true);
                    }
                    throw e;
                }
                // The panel runs this interpreter as root (probe, `uv sync`,
                // node-gyp); the agent must not be able to replace its files.
                Privileges.keepRootExecutable(versionDir);
                activate(version);
                activated = true;
                progress.stage("CPython " + version + " installed", 1);
                BootstrapTrace.installed("python", version, artifact.url(),
                        "sha256:" + artifact.sha256(), versionDir);
                return new Installed(version, versionDir, versionDir.resolve(record.interpreter),
                        record, true);
            } finally {
                BootstrapRuntimeManager.deleteTree(staging);
                if (!activated) {
                    BootstrapRuntimeManager.deleteTree(versionDir);
                }
            }
        }
    }

    /// Puts the resolved interpreter into a child environment.
    ///
    /// The choice belongs to [PythonResolver]: an operator's value wins, a
    /// usable system interpreter is used as-is, and the shared one below this
    /// class is only reached when neither exists. That is also the only case
    /// where `PATH` is touched, so a host's `python3` is not shadowed.
    public static void applyTo(Map<String, String> environment) {
        PythonResolver.applyTo(environment);
    }

    /// The same, with the root named — ZCode builds may keep their shared
    /// interpreter somewhere other than the panel's default root.
    public static void applyTo(Map<String, String> environment, Path root) {
        Installed installed = new PythonRuntimeManager(root).current();
        if (installed != null) {
            applyTo(environment, installed);
        }
    }

    private static void applyTo(Map<String, String> environment, Installed installed) {
        Path bin = installed.interpreter().getParent();
        String existing = environment.get("PATH");
        if (existing == null || existing.isBlank()) {
            environment.put("PATH", bin.toString());
        } else if (!existing.equals(bin.toString())
                && !existing.startsWith(bin + java.io.File.pathSeparator)) {
            environment.put("PATH", bin + java.io.File.pathSeparator + existing);
        }
        putIfUnusable(environment, "PYTHON", installed.interpreter().toString());
        putIfUnusable(environment, "npm_config_python", installed.interpreter().toString());
    }

    private static void putIfUnusable(Map<String, String> environment, String name, String value) {
        String existing = environment.get(name);
        if (existing == null || existing.isBlank()) {
            environment.put(name, value);
        }
    }

    /// The shared interpreter the panel itself uses, or `null`.
    public static @Nullable Installed currentShared() {
        try {
            return defaultManager().current();
        } catch (RuntimeException e) {
            LOG.warning("Could not read the shared Python runtimes: " + e.getMessage());
            return null;
        }
    }

    /// Installs the default line into the panel's shared root.
    public static Installed ensureShared() throws IOException {
        return defaultManager().ensureDefault();
    }

    /// Installs one version into the panel's shared root.
    ///
    /// @param version  the directory name
    /// @param artifact where the bytes come from when a download is needed
    /// @param source   the fetcher
    /// @param progress a progress sink
    /// @return the installed interpreter, now current
    /// @throws IOException when the install fails
    public static Installed ensure(String version, Artifact artifact,
                                   BootstrapRuntimeManager.ArtifactSource source,
                                   BootstrapRuntimeManager.Progress progress) throws IOException {
        return defaultManager().install(version, artifact, source, progress);
    }

    /// Installs one version from its pinned catalogue entry into the shared root.
    ///
    /// @param version the directory name; only the default line is catalogued
    /// @return the installed interpreter, now current
    /// @throws IOException when the install fails
    public static Installed ensure(String version) throws IOException {
        return defaultManager().install(version);
    }

    // -------------------------------------------------------------- archives --

    /// Unpacks `install_only` into `destination`, dropping the archive's
    /// `python/` member.
    static void extract(Path archive, String kind, Path destination) throws IOException {
        Path root = destination.toAbsolutePath().normalize();
        Files.createDirectories(root);
        if (kind.equals("zip")) {
            extractZip(archive, root);
        } else if (kind.equals("tar") || kind.equals("tar.gz") || kind.equals("tgz")) {
            extractTar(archive, kind, root);
        } else {
            throw new IOException("unsupported Python archive: " + kind);
        }
    }

    private static void extractTar(Path archive, String kind, Path root) throws IOException {
        try (InputStream file = new BufferedInputStream(Files.newInputStream(archive));
             InputStream data = kind.equals("tar") ? file : new GZIPInputStream(file);
             kala.compress.archivers.tar.TarArchiveInputStream tar =
                     new kala.compress.archivers.tar.TarArchiveInputStream(data)) {
            boolean decided = false;
            boolean strip = false;
            kala.compress.archivers.tar.TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                String name = normalize(entry.getName());
                if (name == null) {
                    continue;
                }
                if (!decided) {
                    strip = name.equals("python") || name.startsWith("python/");
                    decided = true;
                }
                String relative = strip ? stripPython(name) : name;
                if (relative == null || relative.isEmpty()) {
                    continue;
                }
                Path target = resolveInside(root, relative);
                if (target == null) {
                    throw new IOException("the archive names a path outside the runtime: " + name);
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                if (entry.isSymbolicLink()) {
                    String link = entry.getLinkName() == null ? null : entry.getLinkName().trim();
                    if (link != null && strip) {
                        link = stripPython(link);
                    }
                    if (link == null || link.isEmpty()) {
                        throw new IOException("the archive gives " + name + " no link target");
                    }
                    if (Path.of(link).isAbsolute()) {
                        throw new IOException("the archive links " + name + " to an absolute path");
                    }
                    Path resolved = target.getParent().resolve(link).normalize();
                    if (!resolved.startsWith(root)) {
                        throw new IOException("the archive links " + name + " outside the runtime");
                    }
                    Files.deleteIfExists(target);
                    Files.createSymbolicLink(target, Path.of(link));
                    continue;
                }
                if (entry.isLink()) {
                    String link = entry.getLinkName() == null ? null : entry.getLinkName().trim();
                    if (link != null && strip) {
                        link = stripPython(link);
                    }
                    Path source = link == null ? null : resolveInside(root, link);
                    if (source == null || !Files.isRegularFile(source)) {
                        throw new IOException("the archive hard-links " + name + " to a missing member");
                    }
                    try {
                        Files.createLink(target, source);
                    } catch (IOException | UnsupportedOperationException e) {
                        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
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

    private static void extractZip(Path archive, Path root) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(
                new BufferedInputStream(Files.newInputStream(archive)))) {
            boolean decided = false;
            boolean strip = false;
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = normalize(entry.getName());
                if (name == null) {
                    continue;
                }
                if (!decided) {
                    strip = name.equals("python") || name.startsWith("python/");
                    decided = true;
                }
                String relative = strip ? stripPython(name) : name;
                if (relative == null || relative.isEmpty()) {
                    continue;
                }
                Path target = resolveInside(root, relative);
                if (target == null) {
                    throw new IOException("the archive names a path outside the runtime: " + name);
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
                String fileName = target.getFileName().toString();
                if (relative.startsWith("bin/") || fileName.toLowerCase(Locale.ROOT).endsWith(".exe")) {
                    makeExecutable(target);
                }
            }
        }
    }

    private static void makeExecutable(Path target) {
        try {
            target.toFile().setExecutable(true, false);
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

    private static @Nullable String stripPython(@Nullable String name) {
        if (name == null) {
            return null;
        }
        if (name.equals("python")) {
            return "";
        }
        return name.startsWith("python/") ? name.substring("python/".length()) : name;
    }

    private static @Nullable Path resolveInside(Path root, String relative) {
        Path candidate = root.resolve(relative).normalize();
        return candidate.startsWith(root) ? candidate : null;
    }

    /// The interpreter inside a runtime root.
    ///
    /// Both layouts are recognised: the stripped one this manager writes
    /// (`bin/python3`, `python.exe`) and an archive unpacked without dropping
    /// its `python/` member.
    ///
    /// @param root the version directory or a staging directory
    /// @return the interpreter, or `null`
    static @Nullable Path interpreterIn(Path root) {
        if (!Files.isDirectory(root)) {
            return null;
        }
        for (String relative : List.of(
                "bin/python3", "bin/python", "python.exe",
                "python/bin/python3", "python/bin/python", "python/python.exe")) {
            Path candidate = root.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static void verify(Path file, String expected, long size) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("python_download_invalid: " + file + " was not downloaded");
        }
        long actualSize = Files.size(file);
        if (size > 0 && actualSize != size) {
            throw new IOException("python_size_mismatch: expected " + size + " bytes, got " + actualSize);
        }
        String actual = BootstrapRuntimeManager.sha256(file);
        if (!actual.equalsIgnoreCase(expected)) {
            throw new IOException("python_checksum_mismatch: expected " + expected + ", got " + actual);
        }
    }

    /// Proves the interpreter starts, and repairs the one Windows failure that
    /// is not the archive's fault.
    ///
    /// python-build-standalone's Windows builds link the MSVC runtime. On a
    /// machine with only a JDK the first missing thing is `VCRUNTIME140.dll`,
    /// which reads like "the download is broken" while it is really "the image
    /// has no Visual C++ runtime". The JDK is itself an MSVC product and often
    /// carries a copy, and Windows searches the application directory before
    /// `System32`, so dropping one next to `python.exe` fixes it without
    /// touching the system. When no copy exists the refusal names the DLL
    /// instead of reporting a generic exit code.
    ///
    /// @param interpreter the extracted interpreter
    /// @throws IOException `python_runtime_vcruntime_missing` when the DLL is
    ///                     absent and nothing could be copied, `python_runtime_invalid`
    ///                     for any other startup failure
    static void verifyRuns(Path interpreter) throws IOException {
        ProcessResult result = runPythonOnce(interpreter);
        if (result.exitCode() == 0) {
            return;
        }
        String tail = tail(result.output());
        if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS
                && mentionsVcruntime(result.output())) {
            List<Path> copied = installWindowsRuntime(interpreter.getParent(),
                    vcruntimeCandidates(Path.of(System.getProperty("java.home")),
                            System.getenv("SystemRoot")));
            if (!copied.isEmpty()) {
                BootstrapTrace.note("python", "copied the MSVC runtime next to python.exe: "
                        + String.join(", ", copied.stream().map(Path::toString).toList()));
                ProcessResult retried = runPythonOnce(interpreter);
                if (retried.exitCode() == 0) {
                    return;
                }
                result = retried;
                tail = tail(retried.output());
            }
            throw new IOException("python_runtime_vcruntime_missing: " + interpreter
                    + " cannot start because VCRUNTIME140.dll is not present."
                    + " No copy was found beside the JDK either; install the Microsoft Visual C++"
                    + " Redistributable, or place vcruntime140.dll next to python.exe."
                    + (tail.isEmpty() ? "" : " Output: " + tail));
        }
        throw new IOException("python_runtime_invalid: " + interpreter + " exited with "
                + result.exitCode() + (tail.isEmpty() ? "" : ": " + tail));
    }

    /// One `-c pass` run, with the timeout the old inline check used.
    private static ProcessResult runPythonOnce(Path interpreter) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(interpreter.toString(), "-c", "pass");
        builder.redirectErrorStream(true);
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new IOException("python_runtime_invalid: " + interpreter + " cannot be started: "
                    + e.getMessage(), e);
        }
        try {
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("python_runtime_invalid: " + interpreter
                        + " did not answer within 60 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("python_runtime_interrupted", e);
        }
        try (InputStream in = process.getInputStream()) {
            return new ProcessResult(process.exitValue(),
                    new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /// The last 400 characters of an output block.
    static String tail(String output) {
        String stripped = output == null ? "" : output.strip();
        return stripped.length() > 400 ? stripped.substring(stripped.length() - 400) : stripped;
    }

    /// Whether a startup failure is the MSVC runtime one.
    static boolean mentionsVcruntime(String output) {
        return output != null && output.toLowerCase(java.util.Locale.ROOT).contains("vcruntime");
    }

    /// Where a redistributable MSVC runtime may already exist.
    ///
    /// The JDK first: Temurin and friends are built with MSVC and their `bin`
    /// sometimes carries the runtime. `System32` is the fallback, where an
    /// installed redistributable puts it.
    ///
    /// @param javaHome   `java.home`
    /// @param systemRoot `%SystemRoot%`, or `null`
    /// @return existing candidate files, one per name
    static List<Path> vcruntimeCandidates(Path javaHome, @Nullable String systemRoot) {
        List<Path> directories = new ArrayList<>();
        directories.add(javaHome.resolve("bin"));
        directories.add(javaHome);
        if (systemRoot != null && !systemRoot.isBlank()) {
            directories.add(Path.of(systemRoot, "System32"));
        }
        List<Path> found = new ArrayList<>();
        for (String name : List.of("vcruntime140.dll", "vcruntime140_1.dll", "msvcp140.dll")) {
            for (Path directory : directories) {
                Path candidate = directory.resolve(name);
                if (Files.isRegularFile(candidate)) {
                    found.add(candidate);
                    break;
                }
            }
        }
        return found;
    }

    /// Copies those files next to the interpreter, skipping ones already there.
    ///
    /// @param directory  the interpreter's directory
    /// @param candidates the files `vcruntimeCandidates` found
    /// @return what was copied
    static List<Path> installWindowsRuntime(Path directory, List<Path> candidates)
            throws IOException {
        List<Path> copied = new ArrayList<>();
        Files.createDirectories(directory);
        for (Path source : candidates) {
            Path target = directory.resolve(source.getFileName().toString());
            if (Files.isRegularFile(target)) {
                continue;
            }
            Files.copy(source, target);
            copied.add(target);
        }
        return copied;
    }

    private record ProcessResult(int exitCode, String output) {
    }

    // ------------------------------------------------------------- activation --

    private void activate(String version) throws IOException {
        Path directory = safeChild(root, version);
        if (!Files.isDirectory(directory)) {
            throw new IOException("python_runtime_missing: " + version);
        }
        JsonObject pointer = new JsonObject();
        pointer.addProperty("version", version);
        writeJson(root.resolve(POINTER), pointer.toString());
        writeSymlink(directory);
        // Every caller of `activate` has just made a different interpreter the
        // answer, and `PythonResolver` caches its choice for a minute. Without
        // this, a fresh install reported "no Python" for up to 60s — long
        // enough for the task that triggered it to fail on its own success.
        PythonResolver.resetCache();
    }

    private void writeSymlink(Path directory) {
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
            LOG.info("python: could not create the `current` symlink; using current.json ("
                    + e.getMessage() + ")");
        }
    }

    private @Nullable String pointer() {
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

    private @Nullable PythonRecord readRecord(Path directory) {
        Path file = directory.resolve(RECORD);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            PythonRecord record = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                    PythonRecord.class);
            return record == null || record.version == null || record.version.isBlank()
                    ? null : record;
        } catch (IOException | RuntimeException e) {
            LOG.warning("Could not read " + file + ": " + e.getMessage());
            return null;
        }
    }

    private PythonRecord legacyRecord(String version, Path directory, Path interpreter) {
        PythonRecord record = new PythonRecord();
        record.version = version;
        record.os = OperatingSystem.CURRENT_OS.name().toLowerCase(Locale.ROOT);
        record.arch = Architecture.CURRENT_ARCH.name().toLowerCase(Locale.ROOT);
        record.interpreter = relative(directory, interpreter);
        record.installedAt = "";
        return record;
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

    private static boolean usableSegment(String version) {
        return version != null && !version.isBlank() && !version.equals(".")
                && !version.equals("..") && !version.startsWith(".")
                && !version.contains("/") && !version.contains("\\")
                && version.indexOf('\0') < 0;
    }

    private static Path safeChild(Path root, String version) {
        Path directory = root.resolve(version).normalize();
        if (!directory.startsWith(root) || directory.equals(root)) {
            throw new IllegalArgumentException("Python version escapes its root: " + version);
        }
        return directory;
    }

    // --------------------------------------------------------------- download --

    /// Streams one pinned archive over https.
    static final class HttpSource implements BootstrapRuntimeManager.ArtifactSource {
        @Override
        public void fetch(String url, Path target) throws IOException, InterruptedException {
            BootstrapCatalog.validateHttps(url);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(10))
                    .GET()
                    .build();
            HttpResponse<InputStream> response = HTTP.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            BootstrapCatalog.validateHttps(response.uri().toString());
            if (response.statusCode() != 200) {
                try (InputStream ignored = response.body()) {
                    // Closed; the body is not the error message.
                }
                throw new IOException("python_download_failed: HTTP " + response.statusCode()
                        + " for " + url);
            }
            Privileges.createPrivateFile(target);
            try (InputStream in = new BufferedInputStream(response.body());
                 OutputStream out = Files.newOutputStream(target)) {
                byte[] buffer = new byte[1 << 16];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, read);
                }
            }
        }
    }
}