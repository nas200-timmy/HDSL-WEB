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
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/// The pinned recipe catalogue for a **managed full-codec source build**.
///
/// Unlike [FfmpegCatalog], an entry here is not one downloadable artifact: it is a
/// FFmpeg source tarball plus the dependency sources the panel must build first
/// (x264/x265/libvpx/libaom/libsvtav1/opus/lame/ogg/vorbis, and the nasm/cmake build
/// tools). The JSON only selects versions and pins hashes; the actual `configure` /
/// `cmake` invocations live in [FfmpegBuildKit] so a replaced catalogue cannot turn
/// into arbitrary code execution.
///
/// Two profiles ship: `gpl` (adds x264/x265/xvid-class GPL encoders) and `lgpl`
/// (permissive dependencies only). `nonfree` is deliberately absent.
@NotNullByDefault
public final class FfmpegFullBuildCatalog {

    private static final String RESOURCE = "/transcode/ffmpeg-full-builds.json";
    private static final Logger LOG = Logger.LOG;
    private static final Set<String> PROFILES = Set.of("gpl", "lgpl");
    private static final Set<String> ARCHIVES = Set.of("tar", "tar.gz", "tgz", "tar.xz", "txz", "zip");

    private FfmpegFullBuildCatalog() {
    }

    /// One pinned dependency source. `kind` is `tool` (nasm/cmake) or `codec`.
    public record Dependency(String id, String kind, String version, String url,
                             String sha256, String archive, List<String> mirrors) {
        public Dependency {
            mirrors = List.copyOf(mirrors);
        }
    }

    /// One full-build recipe. `features` is the declaration the finished binary is
    /// expected to expose; the post-build probe turns it into `featuresProbed`.
    public record Entry(String id, String profile, String ffmpegVersion,
                        String ffmpegUrl, String ffmpegSha256, String ffmpegArchive,
                        List<Dependency> dependencies, List<String> features,
                        long downloadBytes, int buildMinutes, long diskBytes) {
        public Entry {
            dependencies = List.copyOf(dependencies);
            features = List.copyOf(features);
        }

        public boolean gpl() {
            return "gpl".equals(profile);
        }

        public boolean sha256Pinned() {
            return ffmpegSha256 != null && ffmpegSha256.matches("[0-9a-f]{64}");
        }
    }

    public record Catalog(List<Entry> entries) {
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
    }

    public static Catalog load() {
        try (InputStream in = FfmpegFullBuildCatalog.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                LOG.warning("ffmpeg full-build catalogue missing: " + RESOURCE);
                return new Catalog(List.of());
            }
            JsonObject root = JsonParser.parseString(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            List<Entry> entries = new ArrayList<>();
            JsonArray array = root.has("entries") ? root.getAsJsonArray("entries") : new JsonArray();
            for (JsonElement element : array) {
                try {
                    entries.add(parseEntry(element.getAsJsonObject()));
                } catch (RuntimeException e) {
                    LOG.warning("ffmpeg full-build entry skipped: " + e.getMessage());
                }
            }
            return new Catalog(entries);
        } catch (IOException | RuntimeException e) {
            LOG.warning("ffmpeg full-build catalogue could not be read: " + e.getMessage());
            return new Catalog(List.of());
        }
    }

    private static Entry parseEntry(JsonObject object) {
        String id = string(object, "id");
        String profile = string(object, "profile").toLowerCase(Locale.ROOT);
        if (!PROFILES.contains(profile)) {
            throw new IllegalArgumentException("profile must be gpl or lgpl for '" + id + "'");
        }
        String ffmpegVersion = string(object, "ffmpegVersion");
        JsonObject ffmpeg = object.getAsJsonObject("ffmpeg");
        String ffmpegUrl = string(ffmpeg, "url");
        String ffmpegSha = string(ffmpeg, "sha256").toLowerCase(Locale.ROOT);
        String ffmpegArchive = string(ffmpeg, "archive");
        validateSource(id, ffmpegUrl, ffmpegSha, ffmpegArchive);

        List<Dependency> dependencies = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        JsonArray deps = object.has("dependencies") ? object.getAsJsonArray("dependencies") : new JsonArray();
        for (JsonElement element : deps) {
            JsonObject dep = element.getAsJsonObject();
            String depId = string(dep, "id");
            if (!seen.add(depId)) {
                throw new IllegalArgumentException("duplicate dependency '" + depId + "' in '" + id + "'");
            }
            String kind = string(dep, "kind");
            if (!kind.equals("tool") && !kind.equals("codec")) {
                throw new IllegalArgumentException("dependency kind must be tool or codec: " + depId);
            }
            String url = string(dep, "url");
            String sha = string(dep, "sha256").toLowerCase(Locale.ROOT);
            String archive = string(dep, "archive");
            validateSource(depId, url, sha, archive);
            List<String> mirrors = new ArrayList<>();
            if (dep.has("mirrors")) {
                for (JsonElement mirror : dep.getAsJsonArray("mirrors")) {
                    mirrors.add(mirror.getAsString());
                }
            }
            dependencies.add(new Dependency(depId, kind, string(dep, "version"), url, sha, archive, mirrors));
        }
        if (dependencies.isEmpty()) {
            throw new IllegalArgumentException("full build '" + id + "' has no dependencies");
        }
        List<String> features = new ArrayList<>();
        if (object.has("features")) {
            for (JsonElement feature : object.getAsJsonArray("features")) {
                features.add(feature.getAsString());
            }
        }
        long downloadBytes = object.has("downloadBytes") ? object.get("downloadBytes").getAsLong() : 0;
        int buildMinutes = object.has("buildMinutes") ? object.get("buildMinutes").getAsInt() : 0;
        long diskBytes = object.has("diskBytes") ? object.get("diskBytes").getAsLong() : 0;
        return new Entry(id, profile, ffmpegVersion, ffmpegUrl, ffmpegSha, ffmpegArchive,
                dependencies, features, downloadBytes, buildMinutes, diskBytes);
    }

    private static void validateSource(String id, String url, String sha, String archive) {
        if (url == null || !url.startsWith("https://")) {
            throw new IllegalArgumentException("source '" + id + "' must use https");
        }
        if (sha == null || !sha.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("source '" + id + "' needs a 64-hex SHA-256");
        }
        if (!ARCHIVES.contains(archive.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("source '" + id + "' has unsupported archive " + archive);
        }
    }

    private static String string(JsonObject object, String name) {
        return object.has(name) && object.get(name).isJsonPrimitive()
                ? object.get(name).getAsString() : "";
    }
}
