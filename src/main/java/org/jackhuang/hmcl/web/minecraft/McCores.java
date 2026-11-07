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
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

/// The server cores the panel can run, and the one download each needs.
///
/// **Vanilla is the baseline; the others are what people actually run.** Paper,
/// Folia and Purpur publish a JSON API with a per-build hash, so the panel
/// verifies exactly what it downloaded. Fabric publishes no hash for its
/// launcher jar; that jar is checked to be a zip and nothing more, which is
/// stated here rather than pretended otherwise.
///
/// **The panel talks to the upstream projects directly.** There is no aggregator
/// in the middle: a dead third party would otherwise take the whole catalogue
/// with it, and the hashes would be somebody else's word. Each base URL is
/// overridable (`HDSL_PAPER_API`, `HDSL_PURPUR_API`, `HDSL_FABRIC_API`) for a
/// mirror, exactly like `HDSL_MC_MANIFEST` is for Mojang.
///
/// **Downloads are shared.** One `cores/<core>/<version>/server.jar` serves every
/// instance that wants it, and the instance's jar is a hard link (a copy where
/// the filesystem refuses links, e.g. across volumes on Windows).
@NotNullByDefault
public final class McCores {

    /// Paper's API also answers Folia; the two differ only in the project id.
    public static final URI DEFAULT_PAPER_API = URI.create("https://api.papermc.io");
    /// Purpur runs the same API shape under its own host.
    public static final URI DEFAULT_PURPUR_API = URI.create("https://api.purpurmc.org");
    /// Fabric's meta API; the server launcher jar comes from it directly.
    public static final URI DEFAULT_FABRIC_API = URI.create("https://meta.fabricmc.net");

    /// The cores this class installs itself; `vanilla` is [McVersions].
    public static final List<String> THIRD_PARTY = List.of("paper", "folia", "purpur", "fabric");

    private static final Logger LOG = Logger.LOG;
    private static final long MAX_JAR_BYTES = 512L * 1024 * 1024;

    /// One core, as the create form shows it.
    ///
    /// @param id       the core id
    /// @param label    the human name
    /// @param versions the game versions it publishes, newest first
    public record CoreInfo(String id, String label, List<String> versions) {
    }

    /// One verified download.
    private record Artifact(String url, String algorithm, String digest, long size, String build) {
    }

    private final McVersions vanilla;
    private final Path coresRoot;
    private final URI paperApi;
    private final URI purpurApi;
    private final URI fabricApi;
    private final HttpClient client;

    /// @param vanilla   the Mojang catalogue, reused for the `vanilla` core
    /// @param coresRoot the shared directory (`<data>/minecraft/cores`)
    public McCores(McVersions vanilla, Path coresRoot) {
        this(vanilla, coresRoot, DEFAULT_PAPER_API, DEFAULT_PURPUR_API, DEFAULT_FABRIC_API);
    }

    /// The same, with overridable API bases (mirrors, tests).
    ///
    /// @param vanilla   the Mojang catalogue
    /// @param coresRoot the shared directory
    /// @param paperApi  PaperMC's API base
    /// @param purpurApi Purpur's API base
    /// @param fabricApi Fabric's meta API base
    public McCores(McVersions vanilla, Path coresRoot, URI paperApi, URI purpurApi, URI fabricApi) {
        this.vanilla = vanilla;
        this.coresRoot = coresRoot.toAbsolutePath().normalize();
        this.paperApi = paperApi;
        this.purpurApi = purpurApi;
        this.fabricApi = fabricApi;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    /// Whether an id names a core this panel knows.
    ///
    /// @param core the core id
    /// @return whether it is `vanilla` or one of [THIRD_PARTY]
    public static boolean known(@Nullable String core) {
        return McInstance.CORE_VANILLA.equals(core) || (core != null && THIRD_PARTY.contains(core));
    }

    /// Whether an id names a core that is not Mojang's.
    ///
    /// @param core the core id
    /// @return whether the id is one of [THIRD_PARTY]
    public static boolean isThirdParty(@Nullable String core) {
        return core != null && THIRD_PARTY.contains(core);
    }

    /// The display name of a core.
    ///
    /// @param core the core id
    /// @return the label
    public static String labelOf(String core) {
        return switch (core) {
            case "paper" -> "Paper";
            case "folia" -> "Folia";
            case "purpur" -> "Purpur";
            case "fabric" -> "Fabric";
            default -> "Vanilla";
        };
    }

    /// The catalogue the create form shows.
    ///
    /// Third-party cores are only listed when the operator has enabled them:
    /// while the second gate is off the feature must look like it does not
    /// exist (`docs/minecraft.md` §5.5).
    ///
    /// @param thirdPartyEnabled whether the third-party gate is on
    /// @return vanilla first, then the enabled cores
    public List<CoreInfo> catalog(boolean thirdPartyEnabled) {
        List<CoreInfo> cores = new ArrayList<>();
        List<String> vanillaVersions;
        try {
            vanillaVersions = vanilla.list().stream()
                    .filter(entry -> "release".equals(entry.type()))
                    .map(McVersions.VersionEntry::id)
                    .toList();
        } catch (McException e) {
            LOG.warning("Could not read the vanilla version list: " + e.getMessage());
            vanillaVersions = List.of();
        }
        cores.add(new CoreInfo(McInstance.CORE_VANILLA, "Vanilla", vanillaVersions));
        if (!thirdPartyEnabled) {
            return List.copyOf(cores);
        }
        for (String core : THIRD_PARTY) {
            try {
                cores.add(new CoreInfo(core, labelOf(core), versions(core)));
            } catch (McException e) {
                // One upstream being unreachable must not hide the others; the
                // entry stays with an empty list and the error is logged.
                LOG.warning("Could not read the " + core + " version list: " + e.getMessage());
                cores.add(new CoreInfo(core, labelOf(core), List.of()));
            }
        }
        return List.copyOf(cores);
    }

    /// The game versions one core publishes, newest first.
    ///
    /// @param core the core id
    /// @return the versions
    /// @throws McException when the core is unknown or its API cannot be read
    public List<String> versions(String core) throws McException {
        return switch (core) {
            case McInstance.CORE_VANILLA -> vanilla.list().stream()
                    .filter(entry -> "release".equals(entry.type()))
                    .map(McVersions.VersionEntry::id)
                    .toList();
            case "paper" -> paperVersions("paper");
            case "folia" -> paperVersions("folia");
            case "purpur" -> purpurVersions();
            case "fabric" -> fabricVersions();
            default -> throw new McException("unknown server core `" + core + "`");
        };
    }

    /// Installs one version of the instance's core and points the instance at it.
    ///
    /// @param instance the instance
    /// @param manager  the instance manager
    /// @param version  the game version
    /// @param progress the download sink, or `null`
    /// @return the instance with its version updated
    /// @throws McException when the core is unknown, the download fails or a
    ///                      hash does not match
    public McInstance install(McInstance instance, McInstanceManager manager, String version,
                              @Nullable McVersions.Progress progress) throws McException {
        String core = instance.core();
        if (McInstance.CORE_VANILLA.equals(core)) {
            return vanilla.install(instance, manager, version);
        }
        if (!THIRD_PARTY.contains(core)) {
            throw new McException("unknown server core `" + core + "`");
        }
        Artifact artifact = resolve(core, version);
        Path shared = coresRoot.resolve(core).resolve(version).resolve(McInstanceManager.SERVER_JAR_NAME);
        if (!reusable(shared, artifact)) {
            download(artifact, shared, progress);
        }
        link(shared, manager.serverJar(instance));
        McInstance updated = instance.withVersion(version);
        manager.update(updated);
        return updated;
    }

    /// The artifact for one core version, without downloading it.
    ///
    /// @param core    the core id
    /// @param version the game version
    /// @return where it is and what hash it must match
    /// @throws McException when the core or the version is unknown
    Artifact resolve(String core, String version) throws McException {
        return switch (core) {
            case "paper" -> paperArtifact("paper", version);
            case "folia" -> paperArtifact("folia", version);
            case "purpur" -> purpurArtifact(version);
            case "fabric" -> fabricArtifact(version);
            default -> throw new McException("unknown server core `" + core + "`");
        };
    }

    // ------------------------------------------------------------------ paper --

    private List<String> paperVersions(String project) throws McException {
        URI uri = URI.create(base(paperApi) + "/v2/projects/" + project);
        JsonObject root = fetchJson(uri);
        List<String> versions = stringList(root, "versions");
        if (versions.isEmpty()) {
            throw new McException(uri + " lists no " + project + " versions");
        }
        return versions;
    }

    private Artifact paperArtifact(String project, String version) throws McException {
        URI uri = URI.create(base(paperApi) + "/v2/projects/" + project + "/versions/" + version);
        JsonObject root = fetchJson(uri);
        JsonArray builds = root.has("builds") && root.get("builds").isJsonArray()
                ? root.getAsJsonArray("builds") : new JsonArray();
        // The last build that actually published a jar: Paper keeps failed
        // builds in the list, and an entry without `downloads.application` is
        // exactly that.
        for (int i = builds.size() - 1; i >= 0; i--) {
            if (!builds.get(i).isJsonObject()) {
                continue;
            }
            JsonObject build = builds.get(i).getAsJsonObject();
            JsonObject downloads = build.has("downloads") && build.get("downloads").isJsonObject()
                    ? build.getAsJsonObject("downloads") : null;
            JsonObject application = downloads != null && downloads.has("application")
                    && downloads.get("application").isJsonObject()
                    ? downloads.getAsJsonObject("application") : null;
            if (application == null) {
                continue;
            }
            String name = string(application, "name");
            String sha256 = string(application, "sha256");
            if (name == null || sha256 == null || sha256.isBlank()) {
                continue;
            }
            String buildNumber = build.has("build")
                    ? build.get("build").getAsString() : "0";
            String url = base(paperApi) + "/v2/projects/" + project + "/versions/" + version
                    + "/builds/" + buildNumber + "/downloads/" + name;
            long size = -1;
            return new Artifact(url, "SHA-256", sha256.toLowerCase(Locale.ROOT), size, buildNumber);
        }
        throw new McException(project + " has no downloadable build for " + version);
    }

    // ----------------------------------------------------------------- purpur --

    private List<String> purpurVersions() throws McException {
        URI uri = URI.create(base(purpurApi) + "/v2/purpur");
        JsonObject root = fetchJson(uri);
        List<String> versions = stringList(root, "versions");
        if (versions.isEmpty()) {
            throw new McException(uri + " lists no purpur versions");
        }
        return versions;
    }

    private Artifact purpurArtifact(String version) throws McException {
        URI meta = URI.create(base(purpurApi) + "/v2/purpur/" + version);
        JsonObject root = fetchJson(meta);
        JsonObject builds = root.has("builds") && root.get("builds").isJsonObject()
                ? root.getAsJsonObject("builds") : null;
        String build = builds == null ? null : string(builds, "latest");
        if (build == null || build.isBlank()) {
            throw new McException("purpur has no build for " + version);
        }
        JsonObject details = fetchJson(URI.create(base(purpurApi) + "/v2/purpur/"
                + version + "/" + build));
        String md5 = string(details, "md5");
        if (md5 == null || md5.isBlank()) {
            throw new McException("purpur does not publish an md5 for " + version + "/" + build);
        }
        String url = base(purpurApi) + "/v2/purpur/" + version + "/" + build + "/download";
        return new Artifact(url, "MD5", md5.toLowerCase(Locale.ROOT), -1, build);
    }

    // ----------------------------------------------------------------- fabric --

    private List<String> fabricVersions() throws McException {
        URI uri = URI.create(base(fabricApi) + "/v2/versions/game");
        JsonElement root = fetchJsonElement(uri);
        if (!root.isJsonArray()) {
            throw new McException(uri + " did not answer a version list");
        }
        List<String> stable = new ArrayList<>();
        List<String> all = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            String version = string(entry, "version");
            if (version == null) {
                continue;
            }
            all.add(version);
            boolean isStable = entry.has("stable") && entry.get("stable").isJsonPrimitive()
                    && entry.get("stable").getAsBoolean();
            if (isStable) {
                stable.add(version);
            }
        }
        List<String> versions = stable.isEmpty() ? all : stable;
        if (versions.isEmpty()) {
            throw new McException(uri + " lists no game versions");
        }
        return versions;
    }

    private Artifact fabricArtifact(String version) throws McException {
        String loader = latestFabricLoader();
        String url = base(fabricApi) + "/v2/versions/loader/" + version + "/" + loader
                + "/server/jar";
        // Fabric publishes no hash for the launcher jar; the download is checked
        // to be a zip and nothing more (stated in the class comment).
        return new Artifact(url, "", "", -1, loader);
    }

    private String latestFabricLoader() throws McException {
        URI uri = URI.create(base(fabricApi) + "/v2/versions/loader");
        JsonElement root = fetchJsonElement(uri);
        if (!root.isJsonArray()) {
            throw new McException(uri + " did not answer a loader list");
        }
        String newest = null;
        for (JsonElement element : root.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            String version = string(entry, "version");
            if (version == null || version.isBlank()) {
                continue;
            }
            boolean isStable = entry.has("stable") && entry.get("stable").isJsonPrimitive()
                    && entry.get("stable").getAsBoolean();
            if (isStable) {
                return version;
            }
            if (newest == null) {
                newest = version;
            }
        }
        if (newest == null) {
            throw new McException(uri + " lists no fabric loader");
        }
        return newest;
    }

    // ---------------------------------------------------------------- downloads --

    private void download(Artifact artifact, Path target, @Nullable McVersions.Progress progress)
            throws McException {
        try {
            Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), "core", ".jar.part");
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(artifact.url()))
                        .timeout(Duration.ofMinutes(30))
                        .header("User-Agent", "HDSL-web")
                        .GET()
                        .build();
                HttpResponse<InputStream> answer = client.send(request,
                        HttpResponse.BodyHandlers.ofInputStream());
                if (answer.statusCode() != 200) {
                    throw new McException("the " + artifact.url() + " download answered HTTP "
                            + answer.statusCode());
                }
                MessageDigest digest = artifact.algorithm().isEmpty()
                        ? null : MessageDigest.getInstance(artifact.algorithm());
                long copied = 0;
                long lastReport = 0;
                try (InputStream in = answer.body(); OutputStream out = Files.newOutputStream(temp)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(buffer)) >= 0) {
                        copied += read;
                        if (copied > MAX_JAR_BYTES) {
                            throw new McException("the server download is larger than the panel accepts");
                        }
                        if (digest != null) {
                            digest.update(buffer, 0, read);
                        }
                        out.write(buffer, 0, read);
                        if (progress != null && copied - lastReport >= 1024 * 1024) {
                            lastReport = copied;
                            progress.update(copied, artifact.size());
                        }
                    }
                }
                if (digest != null) {
                    String actual = HexFormat.of().formatHex(digest.digest());
                    if (!actual.equalsIgnoreCase(artifact.digest())) {
                        throw new McException("the server download failed its " + artifact.algorithm()
                                + " check (expected " + artifact.digest() + ", got " + actual + ")");
                    }
                }
                requireZip(temp);
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                JsonObject record = new JsonObject();
                record.addProperty("core", target.getNameCount() > 2
                        ? target.getParent().getParent().getFileName().toString() : "");
                record.addProperty("version", target.getParent().getFileName().toString());
                record.addProperty("build", artifact.build());
                record.addProperty("url", artifact.url());
                if (digest != null) {
                    record.addProperty(artifact.algorithm().equalsIgnoreCase("MD5") ? "md5" : "sha256",
                            artifact.digest());
                }
                record.addProperty("size", copied);
                record.addProperty("javaMajor",
                        McJavaRuntimes.recommendFor(target.getParent().getFileName().toString()));
                JsonUtils.writeToJsonFile(target.getParent().resolve("version.json"), record);
                if (progress != null) {
                    progress.update(copied, artifact.size());
                }
                LOG.info("Downloaded " + artifact.url() + " (" + copied + " bytes)");
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new McException("Failed to download the server jar: " + e.getMessage(), e);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new McException(artifact.algorithm() + " is not available in this JVM", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McException("the server download was interrupted", e);
        }
    }

    private boolean reusable(Path shared, Artifact artifact) {
        if (!Files.isRegularFile(shared)) {
            return false;
        }
        try {
            if (!artifact.algorithm().isEmpty()) {
                String actual = digestOf(shared, artifact.algorithm());
                if (!actual.equalsIgnoreCase(artifact.digest())) {
                    return false;
                }
            }
            requireZip(shared);
            return true;
        } catch (McException | IOException e) {
            return false;
        }
    }

    private static void requireZip(Path file) throws McException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] magic = in.readNBytes(4);
            if (magic.length < 4 || magic[0] != 'P' || magic[1] != 'K') {
                throw new McException("the downloaded server.jar is not a zip archive");
            }
        } catch (IOException e) {
            throw new McException("Failed to inspect " + file, e);
        }
    }

    private static void link(Path shared, Path instanceJar) throws McException {
        try {
            Files.deleteIfExists(instanceJar);
            try {
                Files.createLink(instanceJar, shared);
            } catch (IOException | UnsupportedOperationException e) {
                Files.copy(shared, instanceJar, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new McException("Failed to place server.jar in the instance: " + e.getMessage(), e);
        }
    }

    private static String digestOf(Path file, String algorithm) throws McException, IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new McException(algorithm + " is not available in this JVM", e);
        }
    }

    // ------------------------------------------------------------------ plumbing --

    private static String base(URI uri) {
        return uri.toString().replaceAll("/+$", "");
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

    private static List<String> stringList(JsonObject root, String name) {
        if (!root.has(name) || !root.get(name).isJsonArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray(name)) {
            if (element.isJsonPrimitive()) {
                values.add(element.getAsString());
            }
        }
        return values;
    }

    private static @Nullable String string(JsonObject object, String name) {
        return object.has(name) && object.get(name).isJsonPrimitive()
                ? object.get(name).getAsString() : null;
    }
}
