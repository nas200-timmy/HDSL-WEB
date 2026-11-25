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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jackhuang.hmcl.util.logging.Logger;
import org.jackhuang.hmcl.util.versioning.VersionNumber;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// The pinned ffmpeg artifact catalogue.
///
/// The catalogue is data, not discovery: an entry is a build somebody verified
/// (platform, archive hash, licence, feature set) before it was added. Nothing
/// here resolves "latest" or asks a publisher what the current build is; a
/// moving URL would make the same panel install different bytes tomorrow.
///
/// The built-in catalogue lives in the jar. A deployment can point
/// `transcode.ffmpeg_catalog` at a local JSON file to add/override entries
/// without rebuilding the panel; remote catalogue URLs are deliberately not
/// supported.
@NotNullByDefault
public final class FfmpegCatalog {

    private static final String RESOURCE = "/transcode/ffmpeg-catalog.json";
    private static final Logger LOG = Logger.LOG;

    private FfmpegCatalog() {
    }

    /// One verified artifact.
    ///
    /// `features` is a **declaration**: it says what the build is expected to
    /// contain, not what the panel observed. The installation probe is what
    /// turns a declaration into `featuresProbed`.
    ///
    /// The second half of the record is the v2 shape: a version line (`7.1`,
    /// `8.1`), the build vendor/variant, the minimum glibc, mirrors and the
    /// source-build recipe. They have defaults so a v1 catalogue keeps loading:
    /// `kind` defaults to `binary`, `vendor`/`variant`/`minGlibc` to empty,
    /// `mirrors` to an empty list, and `major` is inferred from `ffmpegVersion`
    /// (`8.1.3-27-…` → `8.1`).
    public record Entry(
            String id,
            String os,
            String arch,
            String libc,
            String ffmpegVersion,
            String license,
            List<String> features,
            String url,
            String sha256,
            long size,
            String archive,
            String notes,
            String kind,
            String vendor,
            String major,
            String variant,
            String minGlibc,
            List<String> mirrors,
            String signatureUrl,
            String buildRecipe) {

        public Entry {
            features = features == null ? List.of() : List.copyOf(features);
            kind = blankTo(kind, "binary").toLowerCase(Locale.ROOT);
            if (!"binary".equals(kind) && !"source".equals(kind)) {
                throw new IllegalArgumentException("kind must be binary or source");
            }
            vendor = nullToEmpty(vendor);
            major = nullToEmpty(major);
            if (major.isEmpty()) {
                major = inferMajor(ffmpegVersion);
            }
            variant = nullToEmpty(variant);
            minGlibc = nullToEmpty(minGlibc);
            mirrors = mirrors == null ? List.of() : List.copyOf(mirrors);
            signatureUrl = nullToEmpty(signatureUrl);
            buildRecipe = nullToEmpty(buildRecipe);
        }

        /// The pre-v2 constructor. It keeps direct callers and the old tests
        /// source-compatible while filling the new fields with their defaults.
        public Entry(String id, String os, String arch, String libc, String ffmpegVersion,
                     String license, List<String> features, String url, String sha256, long size,
                     String archive, String notes) {
            this(id, os, arch, libc, ffmpegVersion, license, features, url, sha256, size,
                    archive, notes, "binary", "", "", "", "", List.of(), "", "");
        }

        public boolean isBinary() {
            return "binary".equals(kind);
        }

        public boolean isSource() {
            return "source".equals(kind);
        }

        /// Whether this entry can run on the host. `any` in the catalogue is a
        /// deliberate wildcard; an empty libc means "not applicable" (Windows,
        /// macOS) and matches the host's `none`.
        public boolean matches(String hostOs, String hostArch, String hostLibc) {
            return matchesPart(os, hostOs)
                    && matchesPart(arch, hostArch)
                    && matchesPart(libc, hostLibc);
        }

        private static boolean matchesPart(String declared, String actual) {
            if (declared == null || declared.isBlank() || "any".equalsIgnoreCase(declared)) {
                return true;
            }
            return declared.equalsIgnoreCase(actual);
        }

        /// A catalogue entry for an operator-supplied URL. The caller is
        /// responsible for requiring an explicit SHA-256; this factory only
        /// fills the platform fields the selector would otherwise need.
        public static Entry custom(String id, String url, String sha256, String ffmpegVersion) {
            FfmpegCapabilities.Host host = FfmpegCapabilities.detect();
            return new Entry(id, host.os(), host.arch(), host.libc(),
                    ffmpegVersion == null || ffmpegVersion.isBlank() ? id : ffmpegVersion,
                    "custom", List.of(), url, sha256, 0,
                    archiveFrom(url), "operator-supplied");
        }

        /// `8.1.3-27-g8dca1586f4` → `8.1`, `n7.1.5` → `7.1`.
        public static String inferMajor(@Nullable String ffmpegVersion) {
            if (ffmpegVersion == null) {
                return "";
            }
            Matcher matcher = Pattern.compile("^\\D*(\\d+)\\.(\\d+)").matcher(ffmpegVersion.trim());
            return matcher.find() ? matcher.group(1) + "." + matcher.group(2) : "";
        }

        private static String nullToEmpty(@Nullable String value) {
            return value == null ? "" : value.trim();
        }

        private static String blankTo(@Nullable String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value.trim();
        }
    }

    public record Catalog(int schema, String updatedAt, List<Entry> entries) {
        public Catalog {
            entries = List.copyOf(entries);
        }

        public @Nullable Entry find(String id) {
            for (Entry entry : entries) {
                if (entry.id().equals(id)) {
                    return entry;
                }
            }
            return null;
        }

        /// Distinct version lines present in the catalogue, oldest first.
        public List<String> majors() {
            List<String> majors = new ArrayList<>();
            for (Entry entry : entries) {
                String major = entry.major();
                if (!major.isBlank() && !majors.contains(major)) {
                    majors.add(major);
                }
            }
            majors.sort(Comparator.comparing(VersionNumber::asVersion));
            return List.copyOf(majors);
        }

        public static Catalog empty() {
            return new Catalog(1, "", List.of());
        }
    }

    /// Loads the built-in catalogue, or `override` when it is a readable file.
    ///
    /// A malformed entry is skipped with a warning rather than taking the whole
    /// surface down: a typo in one artifact must not make the panel unstartable.
    ///
    /// An override that cannot be read *at all* falls back to the built-in copy
    /// for the same reason, one level up. This catalogue exists so that there is
    /// always something to fetch — binaries first, a source build after that —
    /// and answering with `Catalog.empty()` here reintroduced the one failure
    /// the whole chain is there to prevent: a transcode page with no ffmpeg on
    /// offer while the built-in copy sat unused inside the jar. It also matches
    /// [org.jackhuang.hmcl.dsh.BootstrapCatalog], whose contract is the same and
    /// which logs the override and uses the embedded copy.
    public static Catalog load(@Nullable Path override) {
        if (override != null && Files.isRegularFile(override)) {
            try {
                Catalog parsed = parse(Files.readString(override, StandardCharsets.UTF_8),
                        override.toString());
                if (!parsed.entries().isEmpty()) {
                    return parsed;
                }
                // A syntactically valid file whose entries were all rejected is
                // the same dead end as an unreadable one: the page offers
                // nothing to install while the built-in copy sits in the jar.
                LOG.warning("The ffmpeg catalogue " + override + " lists no usable entries;"
                        + " using the built-in catalogue instead");
            } catch (IOException | RuntimeException e) {
                LOG.warning("Could not read the ffmpeg catalogue " + override + ": " + e.getMessage()
                        + "; using the built-in catalogue instead");
            }
        }
        try (InputStream in = FfmpegCatalog.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                LOG.warning("The built-in ffmpeg catalogue is missing: " + RESOURCE);
                return Catalog.empty();
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), RESOURCE);
        } catch (IOException | RuntimeException e) {
            LOG.warning("Could not read the built-in ffmpeg catalogue: " + e.getMessage());
            return Catalog.empty();
        }
    }

    public static Catalog parse(String json, String origin) {
        JsonElement rootElement;
        try {
            rootElement = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(origin + " is not valid JSON: " + e.getMessage(), e);
        }
        if (!rootElement.isJsonObject()) {
            throw new IllegalArgumentException(origin + " must contain a JSON object");
        }
        JsonObject root = rootElement.getAsJsonObject();
        int schema = root.has("schema") && root.get("schema").isJsonPrimitive()
                ? root.get("schema").getAsInt() : 1;
        if (schema != 1) {
            throw new IllegalArgumentException(origin + " has unsupported schema " + schema);
        }
        String updatedAt = string(root, "updatedAt");
        List<Entry> entries = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        if (root.has("entries") && root.get("entries").isJsonArray()) {
            JsonArray array = root.getAsJsonArray("entries");
            for (JsonElement element : array) {
                if (!element.isJsonObject()) {
                    LOG.warning("Skipping a non-object ffmpeg catalogue entry in " + origin);
                    continue;
                }
                try {
                    Entry entry = parseEntry(element.getAsJsonObject());
                    if (!ids.add(entry.id())) {
                        LOG.warning("Skipping a duplicate ffmpeg catalogue id '" + entry.id() + "' in " + origin);
                        continue;
                    }
                    entries.add(entry);
                } catch (IllegalArgumentException e) {
                    LOG.warning("Skipping an invalid ffmpeg catalogue entry in " + origin + ": " + e.getMessage());
                }
            }
        }
        return new Catalog(schema, updatedAt, entries);
    }

    private static Entry parseEntry(JsonObject object) {
        String id = required(object, "id");
        String os = required(object, "os").toLowerCase(Locale.ROOT);
        String arch = required(object, "arch").toLowerCase(Locale.ROOT);
        String libc = string(object, "libc").toLowerCase(Locale.ROOT);
        if (libc.isEmpty()) {
            libc = "none";
        }
        String ffmpegVersion = string(object, "ffmpegVersion");
        if (ffmpegVersion.isEmpty()) {
            ffmpegVersion = id;
        }
        String license = string(object, "license");
        if (license.isEmpty()) {
            license = "unknown";
        }
        String kind = string(object, "kind").toLowerCase(Locale.ROOT);
        if (kind.isEmpty()) {
            kind = "binary";
        }
        if (!"binary".equals(kind) && !"source".equals(kind)) {
            throw new IllegalArgumentException("kind must be binary or source for '" + id + "'");
        }

        String url = string(object, "url");
        String sha256 = string(object, "sha256");
        long size = object.has("size") && object.get("size").isJsonPrimitive()
                ? object.get("size").getAsLong() : 0;
        String archive = string(object, "archive").toLowerCase(Locale.ROOT);

        if ("binary".equals(kind)) {
            if (url.isEmpty()) {
                throw new IllegalArgumentException("missing url for '" + id + "'");
            }
            validateUrl(url);
            if (sha256.isEmpty()) {
                throw new IllegalArgumentException("missing sha256 for '" + id + "'");
            }
            if (!sha256.matches("[0-9a-fA-F]{64}")) {
                throw new IllegalArgumentException("sha256 for '" + id + "' must be 64 hex digits");
            }
            if (archive.isEmpty()) {
                archive = archiveFrom(url);
            }
            validateArchive(id, archive);
        } else {
            // A source entry describes how M2 would build ffmpeg. It is kept in
            // the same catalogue but has no downloaded artifact yet, so URL,
            // hash and archive are optional; when present they still validate.
            if (!url.isEmpty()) {
                validateUrl(url);
            }
            if (!sha256.isEmpty() && !sha256.matches("[0-9a-fA-F]{64}")) {
                throw new IllegalArgumentException("sha256 for '" + id + "' must be 64 hex digits");
            }
            if (archive.isEmpty() && !url.isEmpty()) {
                archive = archiveFrom(url);
            }
            if (!archive.isEmpty()) {
                validateArchive(id, archive);
            }
        }

        List<String> features = new ArrayList<>();
        if (object.has("features") && object.get("features").isJsonArray()) {
            for (JsonElement feature : object.getAsJsonArray("features")) {
                if (feature.isJsonPrimitive()) {
                    String value = feature.getAsString().trim();
                    if (!value.isEmpty()) {
                        features.add(value);
                    }
                }
            }
        }
        List<String> mirrors = new ArrayList<>();
        if (object.has("mirrors") && object.get("mirrors").isJsonArray()) {
            for (JsonElement mirror : object.getAsJsonArray("mirrors")) {
                if (mirror.isJsonPrimitive()) {
                    String value = mirror.getAsString().trim();
                    if (!value.isEmpty()) {
                        validateUrl(value);
                        mirrors.add(value);
                    }
                }
            }
        }
        String major = string(object, "major");
        if (major.isEmpty()) {
            major = Entry.inferMajor(ffmpegVersion);
        }
        String signatureUrl = string(object, "signatureUrl");
        if (!signatureUrl.isEmpty()) {
            validateUrl(signatureUrl);
        }
        return new Entry(id, os, arch, libc, ffmpegVersion, license, features,
                url, sha256, size, archive, string(object, "notes"),
                kind, string(object, "vendor"), major, string(object, "variant"),
                string(object, "minGlibc"), mirrors, signatureUrl,
                string(object, "buildRecipe"));
    }

    private static void validateArchive(String id, String archive) {
        if (!Set.of("zip", "tar", "tar.gz", "tgz", "tar.xz", "txz").contains(archive)) {
            throw new IllegalArgumentException("unsupported archive '" + archive + "' for '" + id + "'");
        }
    }

    /// Mirrors [FfmpegInstaller#validateUrl] without making the catalogue depend
    /// on the installer's job machinery.
    private static void validateUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid url");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException("only https URLs are accepted");
        }
    }

    private static String required(JsonObject object, String name) {
        String value = string(object, name);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("missing " + name);
        }
        return value;
    }

    private static String string(JsonObject object, String name) {
        JsonElement element = object.get(name);
        return element != null && element.isJsonPrimitive() ? element.getAsString().trim() : "";
    }

    private static String archiveFrom(String url) {
        String path = URI.create(url).getPath();
        if (path == null) {
            return "zip";
        }
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".tar.xz")) {
            return "tar.xz";
        }
        if (lower.endsWith(".tar.gz")) {
            return "tar.gz";
        }
        if (lower.endsWith(".tgz")) {
            return "tgz";
        }
        if (lower.endsWith(".zip")) {
            return "zip";
        }
        if (lower.endsWith(".tar")) {
            return "tar";
        }
        return "zip";
    }
}