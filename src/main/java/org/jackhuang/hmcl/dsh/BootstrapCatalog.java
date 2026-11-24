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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jackhuang.hmcl.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/// The pinned artifacts that make a minimal image able to run the panel's tools.
///
/// Two bundles live here:
///
/// - **runtime closure** — the shared libraries Node.js links (`libstdc++.so.6`,
///   `libgcc_s.so.1`, `libatomic.so.1`), plus `setpriv` and the `libcap-ng` it
///   needs. Debian's `slim` images ship none of them, so a managed Node
///   installation unpacks and then fails to start with `exit 127`.
/// - **CA bundle** — `cacert.pem`, so npm, git and the agents trust the same
///   roots regardless of what the image has under `/etc/ssl`.
///
/// The catalogue is **data, not discovery**. Every entry names a fixed URL and
/// the SHA-256 of the exact bytes; nothing here says `latest`, and the panel
/// never asks a publisher what the current build is. The runtime closure is
/// built from Debian 11 (`bullseye`) packages fetched through
/// `snapshot.debian.org`'s content-addressed `/file/<sha1>` URLs, which is why
/// the URLs cannot start serving different bytes later.
///
/// The catalogue lives in the jar (`/bootstrap/bootstrap.json`). An operator
/// may point at another copy with `HDSL_BOOTSTRAP_CATALOG` (or the
/// `hdsl.bootstrap.catalog` system property); a rejected override is logged
/// and the embedded copy is used, and [Origin] says which one is in force —
/// a silent switch between the two is the one thing this must not do.
///
/// The override exists for air-gapped mirrors and for rotating a pin without a
/// new release. It is not a trust upgrade: a catalogue fetched from a
/// different channel than the artifacts is only as trustworthy as that
/// channel, which is why the embedded copy stays the default and the
/// override is always named out loud.
@NotNullByDefault
public final class BootstrapCatalog {

    private static final Logger LOG = Logger.LOG;

    private static final String RESOURCE = "/bootstrap/bootstrap.json";

    /// The environment variable an operator sets to use another catalogue.
    public static final String ENV_CATALOG = "HDSL_BOOTSTRAP_CATALOG";

    /// The system property with the same meaning, for tests and launchers.
    public static final String PROPERTY_CATALOG = "hdsl.bootstrap.catalog";

    /// The loaded catalogue, and which one it is.
    private static volatile @Nullable Catalog current;
    private static volatile Origin origin = new Origin("embedded", "");

    private BootstrapCatalog() {
    }

    /// One archive member to pull out of an artifact, and where it belongs.
    ///
    /// Debian packages put libraries under `usr/lib/<triplet>/`; the closure
    /// wants them flat in `lib/`. `prefix` names the member (a versioned
    /// library's `.so.6` symlink and its `.so.6.0.28` target both match), and
    /// `destination` is a directory relative to the installed bundle.
    ///
    /// @param prefix      the archive path prefix to match
    /// @param destination the bundle-relative directory, e.g. `lib` or `bin`
    public record Include(String prefix, String destination) {
        public Include {
            prefix = normalizePrefix(prefix);
            destination = normalizeDestination(destination);
        }
    }

    /// One downloadable artifact and the members the closure takes from it.
    ///
    /// @param name      a short identifier used in logs and the ledger
    /// @param archive   `deb`, `zip`, `tar`, `tar.gz` or `tar.xz`
    /// @param url       an https URL pinned by the catalogue
    /// @param sha256    the SHA-256 of the exact bytes
    /// @param size      the expected size, or `0` when not stated
    /// @param includes  the members to extract
    public record Artifact(String name, String archive, String url, String sha256, long size,
                           List<Include> includes) {
        public Artifact {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("an artifact needs a name");
            }
            archive = archive == null ? "" : archive.trim().toLowerCase(Locale.ROOT);
            if (!archive.equals("deb") && !archive.equals("zip")
                    && !archive.equals("tar") && !archive.equals("tar.gz")
                    && !archive.equals("tgz") && !archive.equals("tar.xz")
                    && !archive.equals("txz")) {
                throw new IllegalArgumentException("unsupported artifact archive: " + archive);
            }
            validateHttps(url);
            if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "artifact " + name + " has no pinned lowercase SHA-256");
            }
            if (size < 0) {
                throw new IllegalArgumentException("artifact " + name + " has a negative size");
            }
            includes = includes == null ? List.of() : List.copyOf(includes);
            if (includes.isEmpty()) {
                throw new IllegalArgumentException("artifact " + name + " extracts nothing");
            }
        }
    }

    /// One runtime closure for an OS/architecture pair.
    ///
    /// `available` is explicit rather than inferred from an empty artifact list:
    /// an arm64 entry that was not pinned must say so, not look like a partial
    /// x64 entry.
    public record Runtime(String version, String os, String arch, String glibc,
                          boolean available, String notes, List<Artifact> artifacts) {
        public Runtime {
            if (version == null || !version.matches("[A-Za-z0-9._-]{1,80}")) {
                throw new IllegalArgumentException("invalid bootstrap runtime version: " + version);
            }
            os = os == null ? "" : os.trim().toLowerCase(Locale.ROOT);
            arch = arch == null ? "" : arch.trim().toLowerCase(Locale.ROOT);
            glibc = glibc == null ? "" : glibc.trim();
            notes = notes == null ? "" : notes;
            artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
            if (available && artifacts.isEmpty()) {
                throw new IllegalArgumentException(
                        "runtime " + version + " is available but has no artifacts");
            }
        }

        public boolean matches(String hostOs, String hostArch) {
            return os.equals(hostOs) && arch.equals(hostArch);
        }
    }

    /// The CA bundle every child process is pointed at.
    public record Ca(String version, String url, String sha256, long size, String fileName) {
        public Ca {
            if (version == null || !version.matches("[A-Za-z0-9._-]{1,80}")) {
                throw new IllegalArgumentException("invalid CA bundle version: " + version);
            }
            validateHttps(url);
            if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("the CA bundle has no pinned lowercase SHA-256");
            }
            if (size < 0) {
                throw new IllegalArgumentException("the CA bundle has a negative size");
            }
            fileName = fileName == null || fileName.isBlank() ? "cacert.pem" : fileName.trim();
            if (fileName.contains("/") || fileName.contains("\\") || fileName.equals("..")) {
                throw new IllegalArgumentException("invalid CA bundle file name: " + fileName);
            }
        }
    }

    /// The pinned `uv` release and the digest of its executable per platform.
    ///
    /// The pin is the executable, not the archive: the wheel, the GitHub
    /// tarball and a mirror's copy of that wheel are three transports for one
    /// binary.
    public record Uv(String version, List<UvBinary> binaries) {
        public Uv {
            if (version == null || !version.matches("[A-Za-z0-9._-]{1,40}")) {
                throw new IllegalArgumentException("invalid uv version: " + version);
            }
            binaries = binaries == null ? List.of() : List.copyOf(binaries);
            if (binaries.isEmpty()) {
                throw new IllegalArgumentException("uv " + version + " pins no platform");
            }
        }

        /// The pinned executable digest for a platform, or `null`.
        public @Nullable String sha256(String platform) {
            for (UvBinary binary : binaries) {
                if (binary.platform().equals(platform)) {
                    return binary.sha256();
                }
            }
            return null;
        }
    }

    /// One platform's uv executable digest.
    public record UvBinary(String platform, String sha256) {
        public UvBinary {
            validatePlatform(platform);
            validateSha256(sha256, "uv " + platform);
        }
    }

    /// The pinned CPython line and where its archives live.
    public record Python(String version, String build, String releaseBase,
                         List<PythonArtifact> artifacts) {
        public Python {
            if (version == null || !version.matches("[A-Za-z0-9._-]{1,40}")) {
                throw new IllegalArgumentException("invalid Python version: " + version);
            }
            if (build == null || !build.matches("\\d{8}")) {
                throw new IllegalArgumentException("invalid python-build-standalone tag: " + build);
            }
            validateHttps(releaseBase);
            artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        }

        /// The archive for a platform, or `null`.
        public @Nullable PythonArtifact artifact(String platform) {
            for (PythonArtifact artifact : artifacts) {
                if (artifact.platform().equals(platform)) {
                    return artifact;
                }
            }
            return null;
        }

        /// The absolute URL of one archive.
        public String url(PythonArtifact artifact) {
            return releaseBase + "/" + build + "/" + artifact.asset();
        }
    }

    /// One platform's CPython archive.
    public record PythonArtifact(String platform, String asset, String sha256, long size,
                                 String archive) {
        public PythonArtifact {
            validatePlatform(platform);
            if (asset == null || asset.isBlank() || asset.contains("/") || asset.contains("..")
                    || asset.toLowerCase(Locale.ROOT).contains("latest")) {
                throw new IllegalArgumentException("invalid Python asset name: " + asset);
            }
            validateSha256(sha256, "CPython " + platform);
            if (size < 0) {
                throw new IllegalArgumentException("CPython " + platform + " has a negative size");
            }
            validateArchive(asset, archive);
        }
    }

    /// The portable build toolchains: w64devkit on Windows, Zig plus Debian
    /// utilities on Linux.
    public record Toolchain(@Nullable Windows windows, @Nullable Linux linux) {
    }

    /// The portable MinGW build.
    public record Windows(String version, String url, String sha256) {
        public Windows {
            if (version == null || !version.matches("[A-Za-z0-9._-]{1,40}")) {
                throw new IllegalArgumentException("invalid w64devkit version: " + version);
            }
            validateHttps(url);
            validateSha256(sha256, "w64devkit " + version);
        }
    }

    /// The portable Linux combination.
    public record Linux(String version, List<ToolchainArtifact> artifacts) {
        public Linux {
            if (version == null || !version.matches("[A-Za-z0-9._+-]{1,40}")) {
                throw new IllegalArgumentException("invalid portable toolchain version: " + version);
            }
            artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
            if (artifacts.isEmpty()) {
                throw new IllegalArgumentException("the portable Linux toolchain pins no artifact");
            }
        }
    }

    /// One toolchain archive. Unlike [Artifact], `includes` may be empty: Zig is
    /// unpacked whole rather than having named members pulled out.
    public record ToolchainArtifact(String name, String archive, String url, String sha256,
                                    long size, List<Include> includes) {
        public ToolchainArtifact {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("a toolchain artifact needs a name");
            }
            validateHttps(url);
            validateSha256(sha256, name);
            if (size < 0) {
                throw new IllegalArgumentException(name + " has a negative size");
            }
            validateArchive(name, archive);
            includes = includes == null ? List.of() : List.copyOf(includes);
        }
    }

    /// The whole catalogue.
    public record Catalog(int schema, String updatedAt, @Nullable Ca ca,
                          List<Runtime> runtimes, @Nullable Uv uv, @Nullable Python python,
                          @Nullable Toolchain toolchain) {
        public Catalog {
            updatedAt = updatedAt == null ? "" : updatedAt;
            runtimes = runtimes == null ? List.of() : List.copyOf(runtimes);
        }

        /// The runtime closure pinned for a host, or `null` when none is.
        ///
        /// @param os   the host OS, e.g. `linux`
        /// @param arch the host architecture, e.g. `x64`
        /// @return the entry, or `null`
        public @Nullable Runtime runtime(String os, String arch) {
            String normalizedOs = os.toLowerCase(Locale.ROOT);
            String normalizedArch = arch.toLowerCase(Locale.ROOT);
            for (Runtime runtime : runtimes) {
                if (runtime.matches(normalizedOs, normalizedArch)) {
                    return runtime;
                }
            }
            return null;
        }

        /// Reads the catalogue shipped in the jar.
        ///
        /// @return the built-in catalogue
        /// @throws IllegalStateException when the resource is missing or invalid
        public static Catalog builtin() {
            try (InputStream in = BootstrapCatalog.class.getResourceAsStream(RESOURCE)) {
                if (in == null) {
                    throw new IllegalStateException("Missing resource " + RESOURCE);
                }
                return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), RESOURCE);
            } catch (IOException e) {
                throw new IllegalStateException("Could not read " + RESOURCE, e);
            }
        }

        /// Reads a catalogue from a file. Used by tests and by the resource loader.
        ///
        /// @param file the JSON file
        /// @return the catalogue
        /// @throws IOException when the file cannot be read
        public static Catalog read(Path file) throws IOException {
            return parse(Files.readString(file, StandardCharsets.UTF_8), file.toString());
        }

        /// Parses and validates a catalogue.
        ///
        /// @param json   the JSON text
        /// @param source what the text came from, for the error message
        /// @return the catalogue
        /// @throws IllegalArgumentException when a field is missing or unsafe
        public static Catalog parse(String json, String source) {
            JsonElement parsed;
            try {
                parsed = JsonParser.parseString(json);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Invalid bootstrap catalogue in " + source, e);
            }
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("Bootstrap catalogue in " + source + " is not an object");
            }
            JsonObject object = parsed.getAsJsonObject();
            int schema = object.has("schema") ? object.get("schema").getAsInt() : 0;
            if (schema != 1 && schema != 2) {
                throw new IllegalArgumentException(
                        "Unsupported bootstrap catalogue schema " + schema + " in " + source);
            }
            String updatedAt = string(object, "updatedAt");
            Ca ca = null;
            if (object.has("ca") && object.get("ca").isJsonObject()) {
                JsonObject value = object.getAsJsonObject("ca");
                ca = new Ca(string(value, "version"), string(value, "url"), string(value, "sha256"),
                        value.has("size") ? value.get("size").getAsLong() : 0,
                        string(value, "fileName"));
            }
            List<Runtime> runtimes = new ArrayList<>();
            if (object.has("runtimes") && object.get("runtimes").isJsonArray()) {
                JsonArray array = object.getAsJsonArray("runtimes");
                for (JsonElement element : array) {
                    if (!element.isJsonObject()) {
                        throw new IllegalArgumentException("A bootstrap runtime entry is not an object");
                    }
                    runtimes.add(parseRuntime(element.getAsJsonObject()));
                }
            }
            Uv uv = null;
            if (object.has("uv") && object.get("uv").isJsonObject()) {
                uv = parseUv(object.getAsJsonObject("uv"));
            }
            Python python = null;
            if (object.has("python") && object.get("python").isJsonObject()) {
                python = parsePython(object.getAsJsonObject("python"));
            }
            Toolchain toolchain = null;
            if (object.has("toolchain") && object.get("toolchain").isJsonObject()) {
                toolchain = parseToolchain(object.getAsJsonObject("toolchain"));
            }
            return new Catalog(schema, updatedAt, ca, runtimes, uv, python, toolchain);
        }

        private static Uv parseUv(JsonObject object) {
            List<UvBinary> binaries = new ArrayList<>();
            if (object.has("binaries") && object.get("binaries").isJsonArray()) {
                for (JsonElement element : object.getAsJsonArray("binaries")) {
                    JsonObject binary = element.getAsJsonObject();
                    binaries.add(new UvBinary(string(binary, "platform"), string(binary, "sha256")));
                }
            }
            return new Uv(string(object, "version"), binaries);
        }

        private static Python parsePython(JsonObject object) {
            List<PythonArtifact> artifacts = new ArrayList<>();
            if (object.has("artifacts") && object.get("artifacts").isJsonArray()) {
                for (JsonElement element : object.getAsJsonArray("artifacts")) {
                    JsonObject artifact = element.getAsJsonObject();
                    artifacts.add(new PythonArtifact(string(artifact, "platform"),
                            string(artifact, "asset"), string(artifact, "sha256"),
                            artifact.has("size") ? artifact.get("size").getAsLong() : 0,
                            string(artifact, "archive")));
                }
            }
            return new Python(string(object, "version"), string(object, "build"),
                    string(object, "releaseBase"), artifacts);
        }

        private static Toolchain parseToolchain(JsonObject object) {
            Windows windows = null;
            if (object.has("windows") && object.get("windows").isJsonObject()) {
                JsonObject value = object.getAsJsonObject("windows");
                windows = new Windows(string(value, "version"), string(value, "url"),
                        string(value, "sha256"));
            }
            Linux linux = null;
            if (object.has("linux") && object.get("linux").isJsonObject()) {
                JsonObject value = object.getAsJsonObject("linux");
                List<ToolchainArtifact> artifacts = new ArrayList<>();
                if (value.has("artifacts") && value.get("artifacts").isJsonArray()) {
                    for (JsonElement element : value.getAsJsonArray("artifacts")) {
                        JsonObject artifact = element.getAsJsonObject();
                        List<Include> includes = new ArrayList<>();
                        if (artifact.has("includes") && artifact.get("includes").isJsonArray()) {
                            for (JsonElement item : artifact.getAsJsonArray("includes")) {
                                JsonObject include = item.getAsJsonObject();
                                includes.add(new Include(string(include, "prefix"),
                                        string(include, "destination")));
                            }
                        }
                        artifacts.add(new ToolchainArtifact(string(artifact, "name"),
                                string(artifact, "archive"), string(artifact, "url"),
                                string(artifact, "sha256"),
                                artifact.has("size") ? artifact.get("size").getAsLong() : 0,
                                includes));
                    }
                }
                linux = new Linux(string(value, "version"), artifacts);
            }
            return new Toolchain(windows, linux);
        }

        private static Runtime parseRuntime(JsonObject object) {
            List<Artifact> artifacts = new ArrayList<>();
            if (object.has("artifacts") && object.get("artifacts").isJsonArray()) {
                for (JsonElement element : object.getAsJsonArray("artifacts")) {
                    artifacts.add(parseArtifact(element.getAsJsonObject()));
                }
            }
            boolean available = !object.has("available") || object.get("available").getAsBoolean();
            return new Runtime(string(object, "version"), string(object, "os"), string(object, "arch"),
                    string(object, "glibc"), available, string(object, "notes"), artifacts);
        }

        private static Artifact parseArtifact(JsonObject object) {
            List<Include> includes = new ArrayList<>();
            if (object.has("includes") && object.get("includes").isJsonArray()) {
                for (JsonElement element : object.getAsJsonArray("includes")) {
                    JsonObject include = element.getAsJsonObject();
                    includes.add(new Include(string(include, "prefix"), string(include, "destination")));
                }
            }
            return new Artifact(string(object, "name"), string(object, "archive"), string(object, "url"),
                    string(object, "sha256"), object.has("size") ? object.get("size").getAsLong() : 0,
                    includes);
        }

        private static String string(JsonObject object, String name) {
            JsonElement element = object.get(name);
            return element != null && element.isJsonPrimitive() ? element.getAsString() : "";
        }
    }

    /// The catalogue in force: the embedded copy, or the operator's override.
    ///
    /// Read once. The override is named in [Origin] so the doctor and the logs
    /// can say which one produced a pin — an operator who pointed at a stale
    /// file should be able to see that without reading source.
    public static Catalog current() {
        Catalog value = current;
        if (value != null) {
            return value;
        }
        synchronized (BootstrapCatalog.class) {
            if (current != null) {
                return current;
            }
            Path override = overrideFile();
            if (override != null) {
                try {
                    Catalog parsed = Catalog.read(override);
                    requireComplete(parsed, override.toString());
                    origin = new Origin("operator", override.toString());
                    LOG.info("bootstrap: catalog=operator path=" + override);
                    current = parsed;
                    return parsed;
                } catch (IOException | RuntimeException e) {
                    origin = new Origin("embedded", "the operator catalogue at " + override
                            + " was rejected: " + e.getMessage());
                    LOG.warning("bootstrap: catalog=operator path=" + override + " rejected ("
                            + e.getMessage() + "); using the embedded catalogue");
                }
            } else {
                origin = new Origin("embedded", "");
            }
            current = Catalog.builtin();
            return current;
        }
    }

    /// An operator catalogue must pin every bundle the panel uses.
    ///
    /// A half-filled catalogue is the worst of both: the panel would keep
    /// working until the one missing section was needed, then fail deep inside
    /// an install. An operator who wants to override one pin copies the
    /// embedded file and edits that field.
    ///
    /// @param catalog the parsed override
    /// @param source  where it came from, for the message
    /// @throws IllegalArgumentException when a section is missing
    static void requireComplete(Catalog catalog, String source) {
        List<String> missing = new ArrayList<>();
        if (catalog.uv() == null) {
            missing.add("uv");
        }
        if (catalog.python() == null) {
            missing.add("python");
        }
        if (catalog.toolchain() == null) {
            missing.add("toolchain");
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("the catalogue at " + source
                    + " pins no " + String.join(", ", missing)
                    + "; copy the embedded catalogue and edit the field you need");
        }
    }

    /// Which catalogue is in force and why.
    public static Origin origin() {
        current();
        return origin;
    }

    /// Where the override comes from, or `null`.
    static @Nullable Path overrideFile() {
        String env = System.getenv(ENV_CATALOG);
        if (env != null && !env.isBlank()) {
            return Path.of(env.trim());
        }
        String property = System.getProperty(PROPERTY_CATALOG);
        return property == null || property.isBlank() ? null : Path.of(property.trim());
    }

    /// Forgets the loaded catalogue, so a test can point at another file.
    static void reset() {
        synchronized (BootstrapCatalog.class) {
            current = null;
            origin = new Origin("embedded", "");
        }
    }

    /// Which catalogue answered: `embedded`, or `operator` with its path.
    ///
    /// @param kind   `embedded` or `operator`
    /// @param detail the path, or why an override was not used
    public record Origin(String kind, String detail) {
        public String describe() {
            return detail.isEmpty() ? kind : kind + " " + detail;
        }
    }

    /// Rejects anything but an https URL with a host.
    ///
    /// The catalogue is the only source of artifact URLs, so this is a guard
    /// against a malformed entry rather than against a user-supplied address —
    /// but it is the same rule the downloader needs, and keeping it here means
    /// a bad entry fails when the catalogue is read.
    ///
    /// @param url the URL
    static void validateHttps(@Nullable String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("artifact URL is empty");
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid artifact URL: " + url, e);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("artifact URL must be https: " + url);
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("artifact URL has no host: " + url);
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("artifact URL must not carry credentials: " + url);
        }
        if (url.toLowerCase(Locale.ROOT).contains("latest")) {
            throw new IllegalArgumentException("artifact URLs must be pinned, not moving: " + url);
        }
    }

    /// A `linux-x64` style platform key.
    static void validatePlatform(@Nullable String platform) {
        if (platform == null || !platform.matches("[a-z0-9]+-[a-z0-9]+")) {
            throw new IllegalArgumentException("invalid platform key: " + platform);
        }
    }

    /// A lowercase SHA-256.
    static void validateSha256(@Nullable String sha256, String what) {
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(what + " has no pinned lowercase SHA-256");
        }
    }

    /// An archive kind the extractors understand.
    static void validateArchive(String what, @Nullable String archive) {
        String value = archive == null ? "" : archive.trim().toLowerCase(Locale.ROOT);
        if (!value.equals("deb") && !value.equals("zip") && !value.equals("tar")
                && !value.equals("tar.gz") && !value.equals("tgz")
                && !value.equals("tar.xz") && !value.equals("txz")) {
            throw new IllegalArgumentException("unsupported archive for " + what + ": " + archive);
        }
    }

    private static String normalizePrefix(String prefix) {
        String value = prefix == null ? "" : prefix.trim().replace('\\', '/');
        while (value.startsWith("./")) {
            value = value.substring(2);
        }
        if (value.startsWith("/") || value.isEmpty() || value.contains("..")) {
            throw new IllegalArgumentException("invalid archive member prefix: " + prefix);
        }
        return value;
    }

    private static String normalizeDestination(String destination) {
        String value = destination == null ? "" : destination.trim().replace('\\', '/');
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.isEmpty() || value.startsWith("/") || value.contains("..")
                || value.matches(".*[^A-Za-z0-9._/-].*")) {
            throw new IllegalArgumentException("invalid bundle destination: " + destination);
        }
        return value;
    }
}