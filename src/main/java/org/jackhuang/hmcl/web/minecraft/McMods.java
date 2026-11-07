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
package org.jackhuang.hmcl.web.minecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jackhuang.hmcl.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipFile;

/// Plugins and mods inside one instance: the files on disk, and installing from
/// Modrinth.
///
/// **Disabled means renamed.** A plugin or mod is enabled by its name ending in
/// `.jar`; the panel disables one by renaming it to `.jar.disabled`, which is
/// what the loaders ignore and what a human can undo by hand. No sidecar state,
/// no database.
///
/// **The folder follows the core.** Paper/Folia/Purpur load from `plugins/`,
/// Fabric from `mods/`; vanilla loads neither, so the panel refuses instead of
/// writing files nothing will read.
///
/// **Downloads come from Modrinth, at runtime, with the published hash.** The
/// jar is verified against SHA-512 before it lands in the instance; a file that
/// does not match is deleted, not kept "just in case".
@NotNullByDefault
public final class McMods {

    /// Modrinth's public API; `HDSL_MODRINTH_API` overrides it for mirrors.
    public static final URI DEFAULT_API = URI.create("https://api.modrinth.com/v2");
    /// A mod jar is megabytes; anything past this is not one.
    private static final long MAX_JAR_BYTES = 256L * 1024 * 1024;

    private static final Logger LOG = Logger.LOG;

    /// One file in `mods/` or `plugins/`.
    ///
    /// @param name     the file name as it is on disk
    /// @param enabled  whether the loader will read it (`.jar` vs `.jar.disabled`)
    /// @param bytes    the file size
    /// @param clientOnly whether every descriptor inside the jar marks it client-side
    public record Entry(String name, boolean enabled, long bytes, boolean clientOnly) {
    }

    /// One search hit from Modrinth.
    ///
    /// @param projectId the project id
    /// @param slug      the URL slug
    /// @param title     the display name
    /// @param author    the author
    /// @param downloads total downloads
    /// @param description one-line summary
    public record Hit(String projectId, String slug, String title, String author,
                      long downloads, String description) {
    }

    /// What the instance's core loads.
    ///
    /// @param kind   `mods` or `plugins`
    /// @param loader the Modrinth loader name, or `null` when there is none
    public record Folder(String kind, @Nullable String loader) {
    }

    private final URI api;
    private final HttpClient client;

    public McMods() {
        this(apiFromEnvironment());
    }

    /// The same, with an overridable API base (mirrors, tests).
    ///
    /// @param api the Modrinth-compatible API base
    public McMods(URI api) {
        this.api = api;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    private static URI apiFromEnvironment() {
        String override = System.getenv("HDSL_MODRINTH_API");
        if (override != null && !override.isBlank()) {
            try {
                return URI.create(override.strip());
            } catch (IllegalArgumentException e) {
                LOG.warning("Ignoring HDSL_MODRINTH_API=`" + override + "`: " + e.getMessage());
            }
        }
        return DEFAULT_API;
    }

    /// The folder a core loads from, or `null` when it loads neither.
    ///
    /// @param core the core id
    /// @return the folder description, or `null` for vanilla
    public static @Nullable Folder folderOf(String core) {
        return switch (core) {
            case "paper" -> new Folder("plugins", "paper");
            case "folia" -> new Folder("plugins", "folia");
            case "purpur" -> new Folder("plugins", "purpur");
            case "fabric" -> new Folder("mods", "fabric");
            default -> null;
        };
    }

    /// Validates a caller-supplied folder name.
    ///
    /// @param kind `mods` or `plugins`
    /// @return the normalised name
    /// @throws McException when it is neither
    public static String requireKind(String kind) throws McException {
        if (kind == null) {
            throw new McException("kind must be `mods` or `plugins`");
        }
        String normalised = kind.trim().toLowerCase(Locale.ROOT);
        if (!normalised.equals("mods") && !normalised.equals("plugins")) {
            throw new McException("kind must be `mods` or `plugins`");
        }
        return normalised;
    }

    /// Lists the jars and disabled jars in one folder.
    ///
    /// @param instanceDir the instance directory
    /// @param kind        `mods` or `plugins`
    /// @return the entries, enabled first and then by name
    /// @throws McException when the folder cannot be read
    public List<Entry> list(Path instanceDir, String kind) throws McException {
        String folder = requireKind(kind);
        Path directory = instanceDir.resolve(folder);
        List<Entry> entries = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return entries;
        }
        try (var stream = Files.list(directory)) {
            for (Path file : stream.filter(Files::isRegularFile).sorted().toList()) {
                String name = file.getFileName().toString();
                boolean enabled;
                if (name.endsWith(".jar")) {
                    enabled = true;
                } else if (name.endsWith(".jar.disabled")) {
                    enabled = false;
                } else {
                    continue;
                }
                entries.add(new Entry(name, enabled, Files.size(file), clientOnly(file)));
            }
        } catch (IOException e) {
            throw new McException("Failed to read " + directory + ": " + e.getMessage(), e);
        }
        entries.sort(Comparator.comparing(Entry::enabled).reversed()
                .thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(entries);
    }

    /// Turns one file on or off by renaming it.
    ///
    /// @param instanceDir the instance directory
    /// @param kind        `mods` or `plugins`
    /// @param name        the file name as listed
    /// @param enabled     the wanted state
    /// @return the new name
    /// @throws McException when the file is not there or cannot be renamed
    public String toggle(Path instanceDir, String kind, String name, boolean enabled) throws McException {
        Path file = resolve(instanceDir, kind, name);
        String current = file.getFileName().toString();
        boolean isEnabled = current.endsWith(".jar");
        if (isEnabled == enabled) {
            return current;
        }
        String next = enabled
                ? current.substring(0, current.length() - ".disabled".length())
                : current + ".disabled";
        Path target = file.resolveSibling(next);
        if (Files.exists(target)) {
            throw new McException("`" + next + "` already exists");
        }
        try {
            Files.move(file, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                Files.move(file, target);
            } catch (IOException again) {
                throw new McException("Failed to rename `" + current + "`: " + again.getMessage(), again);
            }
        }
        return next;
    }

    /// Deletes one file.
    ///
    /// @param instanceDir the instance directory
    /// @param kind        `mods` or `plugins`
    /// @param name        the file name
    /// @throws McException when the file is not there or cannot be removed
    public void delete(Path instanceDir, String kind, String name) throws McException {
        Path file = resolve(instanceDir, kind, name);
        try {
            if (!Files.deleteIfExists(file)) {
                throw new McException("`" + name + "` is not there");
            }
        } catch (IOException e) {
            throw new McException("Failed to delete `" + name + "`: " + e.getMessage(), e);
        }
    }

    /// Stores an uploaded jar under its given name, refusing to overwrite.
    ///
    /// @param instanceDir the instance directory
    /// @param kind        `mods` or `plugins`
    /// @param name        the file name (must end in `.jar`)
    /// @param in          the body
    /// @return the stored name
    /// @throws McException when the name is wrong, the file exists, or writing fails
    public String upload(Path instanceDir, String kind, String name, InputStream in) throws McException {
        String folder = requireKind(kind);
        String safe = requireJarName(name);
        Path directory = instanceDir.resolve(folder);
        Path target = directory.resolve(safe);
        if (!target.normalize().startsWith(directory.normalize())) {
            throw new McException("invalid file name");
        }
        try {
            Files.createDirectories(directory);
            if (Files.exists(target)) {
                throw new McException("`" + safe + "` already exists; rename or delete it first");
            }
            Path temp = Files.createTempFile(directory, "upload", ".part");
            try {
                long copied = 0;
                try (OutputStream out = Files.newOutputStream(temp)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(buffer)) >= 0) {
                        copied += read;
                        if (copied > MAX_JAR_BYTES) {
                            throw new McException("the upload is larger than the panel accepts");
                        }
                        out.write(buffer, 0, read);
                    }
                }
                requireZip(temp);
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }
            return safe;
        } catch (IOException e) {
            throw new McException("Failed to store `" + safe + "`: " + e.getMessage(), e);
        }
    }

    /// Searches Modrinth for something the instance can load.
    ///
    /// @param query   the search text
    /// @param folder  what the instance loads
    /// @param version the instance's game version
    /// @return the hits, best first
    /// @throws McException when the API cannot be read
    public List<Hit> search(String query, Folder folder, String version) throws McException {
        String projectType = folder.kind().equals("plugins") ? "plugin" : "mod";
        StringBuilder url = new StringBuilder(base())
                .append("/search?limit=20&index=relevance&query=")
                .append(URLEncoder.encode(query == null ? "" : query, StandardCharsets.UTF_8));
        List<String> facets = new ArrayList<>();
        facets.add("[\"project_type:" + projectType + "\"]");
        if (folder.loader() != null) {
            facets.add("[\"categories:" + folder.loader() + "\"]");
        }
        if (version != null && !version.isBlank()) {
            facets.add("[\"versions:" + version.trim() + "\"]");
        }
        url.append("&facets=").append(URLEncoder.encode("[" + String.join(",", facets) + "]",
                StandardCharsets.UTF_8));
        JsonObject root = fetchJson(URI.create(url.toString()));
        JsonArray hits = root.has("hits") && root.get("hits").isJsonArray()
                ? root.getAsJsonArray("hits") : new JsonArray();
        List<Hit> results = new ArrayList<>();
        for (JsonElement element : hits) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject hit = element.getAsJsonObject();
            String projectId = string(hit, "project_id");
            String slug = string(hit, "slug");
            String title = string(hit, "title");
            if (projectId == null || title == null) {
                continue;
            }
            String author = string(hit, "author");
            long downloads = hit.has("downloads") ? hit.get("downloads").getAsLong() : 0;
            String description = string(hit, "description");
            results.add(new Hit(projectId, slug == null ? projectId : slug, title,
                    author == null ? "" : author, downloads, description == null ? "" : description));
        }
        return List.copyOf(results);
    }

    /// Installs the newest compatible version of a project into the instance.
    ///
    /// @param instanceDir the instance directory
    /// @param folder      what the instance loads
    /// @param projectId   the Modrinth project id
    /// @param version     the game version
    /// @return the stored file name
    /// @throws McException when no compatible version exists, the download
    ///                      fails, or the hash does not match
    public String install(Path instanceDir, Folder folder, String projectId, String version)
            throws McException {
        StringBuilder url = new StringBuilder(base())
                .append("/project/").append(URLEncoder.encode(projectId, StandardCharsets.UTF_8))
                .append("/version");
        List<String> params = new ArrayList<>();
        if (folder.loader() != null) {
            params.add("loaders=" + URLEncoder.encode("[\"" + folder.loader() + "\"]", StandardCharsets.UTF_8));
        }
        if (version != null && !version.isBlank()) {
            params.add("game_versions=" + URLEncoder.encode("[\"" + version.trim() + "\"]",
                    StandardCharsets.UTF_8));
        }
        if (!params.isEmpty()) {
            url.append("?").append(String.join("&", params));
        }
        JsonElement root = fetchJsonElement(URI.create(url.toString()));
        JsonArray versions = root.isJsonArray() ? root.getAsJsonArray() : null;
        if (versions == null || versions.isEmpty()) {
            throw new McException("no version of " + projectId + " works with this core and game version");
        }
        JsonObject first = versions.get(0).getAsJsonObject();
        JsonArray files = first.has("files") && first.get("files").isJsonArray()
                ? first.getAsJsonArray("files") : new JsonArray();
        if (files.isEmpty()) {
            throw new McException("the project's version publishes no file");
        }
        JsonObject file = files.get(0).getAsJsonObject();
        String filename = string(file, "filename");
        String download = string(file, "url");
        if (filename == null || download == null) {
            throw new McException("the project's version publishes an incomplete file");
        }
        String sha512 = null;
        if (file.has("hashes") && file.get("hashes").isJsonObject()) {
            sha512 = string(file.getAsJsonObject("hashes"), "sha512");
        }
        String safe = requireJarName(filename);
        Path directory = instanceDir.resolve(folder.kind());
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new McException("Failed to create " + directory + ": " + e.getMessage(), e);
        }
        Path target = directory.resolve(safe);
        try {
            Path temp = Files.createTempFile(directory, "mod", ".part");
            try {
                download(download, temp, "SHA-512", sha512);
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new McException("Failed to install `" + safe + "`: " + e.getMessage(), e);
        }
        return safe;
    }

    private void download(String url, Path target, String algorithm, @Nullable String digest)
            throws McException {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(15))
                    .header("User-Agent", "HDSL-web")
                    .GET()
                    .build();
            HttpResponse<InputStream> answer = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (answer.statusCode() != 200) {
                throw new McException("the " + url + " download answered HTTP " + answer.statusCode());
            }
            MessageDigest hasher = digest == null ? null : MessageDigest.getInstance(algorithm);
            long copied = 0;
            try (InputStream in = answer.body(); OutputStream out = Files.newOutputStream(target)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    copied += read;
                    if (copied > MAX_JAR_BYTES) {
                        throw new McException("the download is larger than the panel accepts");
                    }
                    if (hasher != null) {
                        hasher.update(buffer, 0, read);
                    }
                    out.write(buffer, 0, read);
                }
            }
            if (hasher != null) {
                String actual = HexFormat.of().formatHex(hasher.digest());
                if (!actual.equalsIgnoreCase(digest)) {
                    throw new McException("the download failed its " + algorithm + " check");
                }
            }
            requireZip(target);
        } catch (IOException e) {
            throw new McException("Failed to download " + url + ": " + e.getMessage(), e);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new McException(algorithm + " is not available in this JVM", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McException("the download was interrupted", e);
        }
    }

    /// Resolves one file name inside the instance's folder, refusing escapes.
    private static Path resolve(Path instanceDir, String kind, String name) throws McException {
        String folder = requireKind(kind);
        String safe = requireJarName(name);
        Path directory = instanceDir.resolve(folder).normalize();
        Path file = directory.resolve(safe).normalize();
        if (!file.startsWith(directory) || !Files.isRegularFile(file)) {
            throw new McException("`" + safe + "` is not there");
        }
        return file;
    }

    /// File names the loaders read: a jar, optionally with `.disabled` suffixed.
    private static String requireJarName(@Nullable String name) throws McException {
        if (name == null || name.isBlank() || name.contains("/") || name.contains("\\")
                || name.contains("..") || name.indexOf('\0') >= 0) {
            throw new McException("invalid file name");
        }
        String safe = name.strip();
        if (!safe.endsWith(".jar") && !safe.endsWith(".jar.disabled")) {
            throw new McException("only `.jar` files belong here");
        }
        return safe;
    }

    private static void requireZip(Path file) throws McException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] magic = in.readNBytes(4);
            if (magic.length < 4 || magic[0] != 'P' || magic[1] != 'K') {
                throw new McException("the downloaded file is not a jar (zip) archive");
            }
        } catch (IOException e) {
            throw new McException("Failed to inspect " + file, e);
        }
    }

    /// Whether a jar says, by its own metadata, that it is client-side only.
    ///
    /// This reads the descriptor the loader will read (`fabric.mod.json`,
    /// `mods.toml`, `quilt.mod.json`); a jar with none of them is reported as
    /// not client-only, because guessing would disable something that works.
    private static boolean clientOnly(Path file) {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            var fabric = zip.getEntry("fabric.mod.json");
            if (fabric != null) {
                String text = new String(zip.getInputStream(fabric).readAllBytes(), StandardCharsets.UTF_8);
                JsonObject root = JsonParser.parseString(text).getAsJsonObject();
                JsonObject environment = root.has("environment") && root.get("environment").isJsonObject()
                        ? root.getAsJsonObject("environment") : null;
                if (environment != null) {
                    String client = string(environment, "client");
                    if ("required".equals(client)) {
                        return true;
                    }
                    String server = string(environment, "server");
                    if ("unsupported".equals(server)) {
                        return true;
                    }
                }
            }
            var quilt = zip.getEntry("quilt.mod.json");
            if (quilt != null) {
                String text = new String(zip.getInputStream(quilt).readAllBytes(), StandardCharsets.UTF_8);
                return text.contains("\"minecraft\"") && text.contains("\"client\"");
            }
            var toml = zip.getEntry("META-INF/mods.toml");
            if (toml == null) {
                toml = zip.getEntry("META-INF/neoforge.mods.toml");
            }
            if (toml != null) {
                String text = new String(zip.getInputStream(toml).readAllBytes(), StandardCharsets.UTF_8);
                return text.toLowerCase(Locale.ROOT).contains("side = \"client\"")
                        || text.toLowerCase(Locale.ROOT).contains("side=\"client\"");
            }
        } catch (IOException | RuntimeException e) {
            // A jar that cannot be read is not evidence either way.
            return false;
        }
        return false;
    }

    private String base() {
        return api.toString().replaceAll("/+$", "");
    }

    private JsonObject fetchJson(URI uri) throws McException {
        JsonElement element = fetchJsonElement(uri);
        if (!element.isJsonObject()) {
            throw new McException(uri + " did not answer a JSON object");
        }
        return element.getAsJsonObject();
    }

    private JsonElement fetchJsonElement(URI uri) throws McException {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "HDSL-web")
                    .GET()
                    .build();
            HttpResponse<String> answer = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (answer.statusCode() != 200) {
                throw new McException(uri + " answered HTTP " + answer.statusCode());
            }
            return JsonParser.parseString(answer.body());
        } catch (IOException e) {
            throw new McException("Failed to read " + uri + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McException("Reading " + uri + " was interrupted", e);
        } catch (RuntimeException e) {
            throw new McException(uri + " did not answer JSON", e);
        }
    }

    private static @Nullable String string(JsonObject object, String name) {
        return object.has(name) && object.get(name).isJsonPrimitive()
                ? object.get(name).getAsString() : null;
    }
}
