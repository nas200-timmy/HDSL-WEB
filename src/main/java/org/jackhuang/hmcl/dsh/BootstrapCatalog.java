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
/// The built-in catalogue lives in the jar (`/bootstrap/bootstrap.json`).
/// There is deliberately no operator override: a remote catalogue would be a
/// second SSRF surface, and an artifact URL is a trust decision.
@NotNullByDefault
public final class BootstrapCatalog {

    private static final String RESOURCE = "/bootstrap/bootstrap.json";

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

    /// The whole catalogue.
    public record Catalog(int schema, String updatedAt,
                          @Nullable Ca ca, List<Runtime> runtimes) {
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
            if (schema != 1) {
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
            return new Catalog(schema, updatedAt, ca, runtimes);
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