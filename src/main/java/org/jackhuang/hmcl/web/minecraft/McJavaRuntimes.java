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
import kala.compress.archivers.tar.TarArchiveEntry;
import kala.compress.archivers.tar.TarArchiveInputStream;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.logging.Logger;
import org.jackhuang.hmcl.util.platform.Architecture;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jackhuang.hmcl.util.platform.Platform;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/// The Java runtimes the panel owns, for Minecraft servers.
///
/// **Why the panel installs its own Java.** A server version names the Java it
/// needs (`javaVersion.majorVersion` in the version metadata); modern versions
/// want 21 or 25 while the host may carry nothing, or only an old JDK. Pointing
/// `mc.java_path` at a hand-installed JDK is fine for an operator who has one,
/// but not an answer a container user can follow. So the panel can fetch an
/// Adoptium JDK into `<data>/minecraft/java/<feature>/` — versioned, isolated,
/// auditable and removed with one call, exactly like the Node runtimes it
/// already owns (`NodeRuntimeManager`). `mc.java_path` still wins outright:
/// naming a JDK is a decision, and the panel does not overrule it.
///
/// **Where they live.** `<data>/minecraft/java`, not `runtimes/`: the Node
/// manager enumerates everything under `runtimes/` as a Node build, and a
/// directory called `21` there is noise at best. Minecraft-only things live
/// under `minecraft/` (versions, backups, feature.json), and this follows.
///
/// **What is verified.** Adoptium publishes a SHA-256 for every package and the
/// panel checks it before anything is unpacked; the archive is then inspected
/// for a `bin/java` at the expected depth, because a proxy's HTML error page
/// saved under the right name is the classic way a "downloaded runtime" turns
/// out to be junk.
@NotNullByDefault
public final class McJavaRuntimes {

    /// Adoptium's public API; `HDSL_JAVA_API` overrides it for mirrors.
    public static final URI DEFAULT_API = URI.create("https://api.adoptium.net");
    /// How long the release metadata is reused before it is fetched again.
    private static final Duration METADATA_TTL = Duration.ofMinutes(10);
    /// A JDK package is ~200 MB unpacked to ~300 MB; anything past this is not one.
    private static final long MAX_PACKAGE_BYTES = 512L * 1024 * 1024;

    private static final Logger LOG = Logger.LOG;

    /// One runtime the panel installed.
    ///
    /// @param feature the Java feature release (`21`), which is also the
    ///                directory name
    /// @param path    the `java` executable
    /// @param version the full version Adoptium reported (`21.0.5+11`), or
    ///                `null` for a directory written before this was recorded
    /// @param bytes   the unpacked size on disk, or `0` when unknown
    public record Installed(int feature, Path path, @Nullable String version, long bytes) {

        public Installed(int feature, Path path) {
            this(feature, path, null, 0);
        }
    }

    /// One downloadable JDK package.
    ///
    /// @param feature the Java feature release
    /// @param version the full version string Adoptium reports (`21.0.5+11`)
    /// @param url     where the archive lives
    /// @param sha256  the package's SHA-256, lowercase hex
    /// @param size    the package size in bytes, or `-1` when unknown
    public record Release(int feature, String version, String url, String sha256, long size) {
    }

    private record Cached(List<Release> releases, Instant at) {
    }

    private final Path root;
    private final URI api;
    private final HttpClient client;
    private volatile @Nullable Cached cached;

    /// The manager for `<data>/minecraft/java`.
    ///
    /// @param root the directory that holds one subdirectory per feature release
    public McJavaRuntimes(Path root) {
        this(root, apiFromEnvironment());
    }

    /// The same, with an overridable API base (mirrors, tests).
    ///
    /// @param root the runtimes directory
    /// @param api  the Adoptium-compatible API base
    public McJavaRuntimes(Path root, URI api) {
        this.root = root.toAbsolutePath().normalize();
        this.api = api;
        // Adoptium answers with a 302 to `objects.githubusercontent.com` for
        // every package, so a client that does not follow redirects turns
        // "install a JDK" into "HTTP 302" on the first try. `NORMAL` follows
        // http→https and https→https, and refuses https→http.
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    private static URI apiFromEnvironment() {
        String override = System.getenv("HDSL_JAVA_API");
        if (override != null && !override.isBlank()) {
            try {
                return URI.create(override.strip());
            } catch (IllegalArgumentException e) {
                LOG.warning("Ignoring HDSL_JAVA_API=`" + override + "`: " + e.getMessage());
            }
        }
        return DEFAULT_API;
    }

    /// The directory the runtimes live in.
    ///
    /// @return the absolute root
    public Path root() {
        return root;
    }

    /// Every runtime the panel installed, oldest feature first.
    ///
    /// @return the installed runtimes
    public List<Installed> listInstalled() {
        List<Installed> found = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return found;
        }
        try (var entries = Files.list(root)) {
            for (Path directory : entries.filter(Files::isDirectory).toList()) {
                Path java = javaBinOf(directory);
                if (java == null) {
                    continue;
                }
                String name = directory.getFileName().toString();
                try {
                    int feature = Integer.parseInt(name);
                    JsonObject record = readRecord(directory);
                    String version = record == null ? null : string(record, "version");
                    long bytes = record == null ? 0 : record.has("bytes")
                            ? record.get("bytes").getAsLong() : 0;
                    found.add(new Installed(feature, java, version, bytes));
                } catch (NumberFormatException e) {
                    LOG.debug("Ignoring `" + name + "` in " + root + ": not a Java feature release");
                }
            }
        } catch (IOException e) {
            LOG.warning("Failed to enumerate installed Java runtimes in " + root, e);
        }
        found.sort(Comparator.comparingInt(Installed::feature));
        return found;
    }

    private static @Nullable JsonObject readRecord(Path directory) {
        Path file = directory.resolve("runtime.json");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return JsonUtils.fromJsonFile(file, JsonObject.class);
        } catch (Exception e) {
            LOG.warning("Ignoring the unreadable runtime record " + file, e);
            return null;
        }
    }

    private static @Nullable String string(JsonObject object, String name) {
        return object.has(name) && object.get(name).isJsonPrimitive()
                ? object.get(name).getAsString() : null;
    }

    /// The executable of the installed runtime for one feature release.    ///
    /// @param feature the feature release
    /// @return the `java` executable, or `null` when it is not installed
    public @Nullable Path findInstalled(int feature) {
        Path java = javaBinOf(root.resolve(Integer.toString(feature)));
        return java == null ? null : java.toAbsolutePath();
    }

    /// The release to download for a feature release.
    ///
    /// @param feature the feature release
    /// @return the package metadata
    /// @throws McException when the API cannot be read or publishes no package
    public Release resolve(int feature) throws McException {
        if (feature < 8 || feature > 40) {
            throw new McException("unsupported Java feature release " + feature);
        }
        Cached current = cached;
        if (current != null && Instant.now().isBefore(current.at().plus(METADATA_TTL))) {
            for (Release release : current.releases()) {
                if (release.feature() == feature) {
                    return release;
                }
            }
        }
        List<Release> releases = fetchReleases();
        cached = new Cached(releases, Instant.now());
        for (Release release : releases) {
            if (release.feature() == feature) {
                return release;
            }
        }
        throw new McException("Adoptium publishes no JDK " + feature + " for "
                + platformLabel());
    }

    /// Every feature release Adoptium publishes for this platform.
    ///
    /// @return the releases, newest first
    /// @throws McException when the API cannot be read
    public List<Release> available() throws McException {
        Cached current = cached;
        if (current != null && Instant.now().isBefore(current.at().plus(METADATA_TTL))) {
            return current.releases();
        }
        List<Release> releases = fetchReleases();
        cached = new Cached(releases, Instant.now());
        return releases;
    }

    /// Downloads and unpacks one JDK into `<root>/<feature>/`.
    ///
    /// The archive is unpacked into a temporary directory beside the target and
    /// only then moved into place, so a failed download never leaves a
    /// half-unpacked runtime that later looks installed.
    ///
    /// @param feature  the feature release
    /// @param progress the download sink, or `null`
    /// @return the installed runtime
    /// @throws McException when the download, the hash check or the unpack fails
    public Installed install(int feature, @Nullable McVersions.Progress progress) throws McException {
        Release release = resolve(feature);
        Path target = root.resolve(Integer.toString(feature));
        Path staging = null;
        Path packageFile = null;
        try {
            Files.createDirectories(root);
            // The server runs as a different uid and has to reach `bin/java`
            // through this directory. `createDirectories` honours the umask, so
            // an image built with 077 would leave it 0700 and the JDK installed
            // but unreachable — the mode is set on purpose, ownership is not
            // touched (the tree stays the panel's, like the Node runtimes).
            setMode(root, "rwxr-xr-x");
            packageFile = Files.createTempFile(root, "jdk-" + feature, ".part");
            download(release, packageFile, progress);
            String actual = sha256Of(packageFile);
            if (!actual.equalsIgnoreCase(release.sha256())) {
                throw new McException("the JDK " + feature + " download failed its SHA-256 check (expected "
                        + release.sha256() + ", got " + actual + ")");
            }
            staging = Files.createTempDirectory(root, "jdk-" + feature + ".tmp");
            unpack(packageFile, staging, release.url());
            Path executable = javaBinOf(staging);
            if (executable == null) {
                throw new McException("the JDK " + feature + " archive has no bin/java"
                        + " (it unpacked, but not into the expected shape)");
            }
            deleteRecursively(target);
            Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
            staging = null;
            // An archive's entries are written with the panel's umask, not the
            // mode the distribution shipped: `bin/java` came out 0644 and could
            // not be executed by anyone — root included, because `execve` wants
            // at least one execute bit. This tree belongs to the panel and the
            // server runs as a different uid, so the modes are set explicitly
            // here instead of trusted from the archive headers.
            normalizeModes(target);
            long bytes = sizeOf(target);
            JsonObject record = new JsonObject();
            record.addProperty("feature", feature);
            record.addProperty("version", release.version());
            record.addProperty("url", release.url());
            record.addProperty("sha256", release.sha256());
            record.addProperty("bytes", bytes);
            record.addProperty("installedAt", Instant.now().toString());
            JsonUtils.writeToJsonFile(target.resolve("runtime.json"), record);
            LOG.info("Installed Java " + release.version() + " into " + target);
            Path installed = javaBinOf(target);
            if (installed == null) {
                throw new McException("the JDK " + feature + " did not unpack into " + target);
            }
            return new Installed(feature, installed, release.version(), bytes);
        } catch (IOException e) {
            throw new McException("Failed to install Java " + feature + ": " + e.getMessage(), e);
        } finally {
            if (packageFile != null) {
                try {
                    Files.deleteIfExists(packageFile);
                } catch (IOException e) {
                    LOG.warning("Could not delete " + packageFile, e);
                }
            }
            if (staging != null) {
                deleteRecursively(staging);
            }
        }
    }

    /// Removes one installed runtime.
    ///
    /// @param feature the feature release
    /// @return whether anything was removed
    public boolean remove(int feature) {
        Path target = root.resolve(Integer.toString(feature));
        if (!Files.isDirectory(target)) {
            return false;
        }
        deleteRecursively(target);
        return true;
    }

    /// The Java a Minecraft version wants, when the version metadata does not
    /// say (third-party cores) or cannot be read.
    ///
    /// The table is the documented requirement of the vanilla releases; a
    /// third-party fork follows the version it forked from, which is why the
    /// same table applies to Paper/Fabric/Forge. Anything unparseable asks for
    /// 21: modern enough for every release the panel offers, and the panel's own
    /// JVM on the official image already satisfies it.
    ///
    /// @param version the Minecraft version (`1.20.5`, `1.21`, `1.8.9`)
    /// @return the feature release the server needs
    public static int recommendFor(String version) {
        String cleaned = version == null ? "" : version.strip();
        Matcher matcher = Pattern.compile("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?").matcher(cleaned);
        if (!matcher.find()) {
            return 21;
        }
        int major;
        int minor;
        int patch = 0;
        try {
            major = Integer.parseInt(matcher.group(1));
            minor = Integer.parseInt(matcher.group(2));
            if (matcher.group(3) != null) {
                patch = Integer.parseInt(matcher.group(3));
            }
        } catch (NumberFormatException e) {
            return 21;
        }
        if (major != 1) {
            // The 26.x line (and whatever follows) needs Java 25.
            return 25;
        }
        if (minor >= 22) {
            return 25;
        }
        if (minor == 21) {
            return 21;
        }
        if (minor == 20) {
            return patch >= 5 ? 21 : 17;
        }
        if (minor >= 18) {
            return 17;
        }
        if (minor == 17) {
            return 16;
        }
        return 8;
    }

    private List<Release> fetchReleases() throws McException {
        String base = api.toString().replaceAll("/+$", "");
        JsonElement info = fetchJson(URI.create(base + "/v3/info/available_releases"));
        JsonArray features = info.isJsonObject() && info.getAsJsonObject().has("available_releases")
                && info.getAsJsonObject().get("available_releases").isJsonArray()
                ? info.getAsJsonObject().getAsJsonArray("available_releases") : null;
        if (features == null) {
            throw new McException(base + "/v3/info/available_releases did not answer a feature list");
        }
        List<Integer> numbers = new ArrayList<>();
        for (JsonElement element : features) {
            if (element.isJsonPrimitive()) {
                try {
                    numbers.add(element.getAsInt());
                } catch (RuntimeException ignored) {
                    // A non-numeric entry is not a feature release.
                }
            }
        }
        numbers.sort(Comparator.reverseOrder());
        String os = platformOs();
        String arch = platformArch();
        List<Release> releases = new ArrayList<>();
        for (int feature : numbers) {
            if (feature < 8) {
                continue;
            }
            URI uri = URI.create(base + "/v3/assets/latest/" + feature + "/hotspot?os=" + os
                    + "&architecture=" + arch + "&image_type=jdk");
            JsonElement root;
            try {
                root = fetchJson(uri);
            } catch (McException e) {
                // One missing feature release is not a broken API: skip it and
                // let the others answer (Adoptium has gaps for old releases).
                LOG.debug("No JDK " + feature + " for " + os + "/" + arch + ": " + e.getMessage());
                continue;
            }
            JsonObject asset = firstAsset(root);
            if (asset == null) {
                continue;
            }
            JsonObject binary = object(asset, "binary");
            JsonObject pkg = binary == null ? null : object(binary, "package");
            if (pkg == null) {
                continue;
            }
            String url = string(pkg, "link");
            String checksum = string(pkg, "checksum");
            if (url == null || checksum == null || checksum.isBlank()) {
                continue;
            }
            String version = "";
            JsonObject versionObject = object(asset, "version");
            if (versionObject != null) {
                String semver = string(versionObject, "semver");
                version = semver == null ? "" : semver;
            }
            long size = pkg.has("size") ? pkg.get("size").getAsLong() : -1;
            releases.add(new Release(feature, version, url, checksum.toLowerCase(Locale.ROOT), size));
        }
        releases.sort(Comparator.comparingInt(Release::feature).reversed());
        return List.copyOf(releases);
    }

    /// `assets/latest/<feature>` answers an array (newer API) or an object with
    /// an `assets` array (older mirrors); both mean the same thing.
    private static @Nullable JsonObject firstAsset(JsonElement root) {
        if (root.isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray()) {
                if (element.isJsonObject()) {
                    return element.getAsJsonObject();
                }
            }
            return null;
        }
        if (root.isJsonObject()) {
            JsonObject object = root.getAsJsonObject();
            if (object.has("assets") && object.get("assets").isJsonArray()) {
                for (JsonElement element : object.getAsJsonArray("assets")) {
                    if (element.isJsonObject()) {
                        return element.getAsJsonObject();
                    }
                }
            }
            return object;
        }
        return null;
    }

    private void download(Release release, Path target, @Nullable McVersions.Progress progress)
            throws McException {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(release.url()))
                    .timeout(Duration.ofMinutes(30))
                    .header("User-Agent", "HDSL-web")
                    .GET()
                    .build();
            HttpResponse<InputStream> answer = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (answer.statusCode() != 200) {
                throw new McException("the JDK download answered HTTP " + answer.statusCode());
            }
            long lastReport = 0;
            long copied = 0;
            try (InputStream in = answer.body(); OutputStream out = Files.newOutputStream(target)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    copied += read;
                    if (copied > MAX_PACKAGE_BYTES) {
                        throw new McException("the JDK download is larger than the panel accepts");
                    }
                    out.write(buffer, 0, read);
                    if (progress != null && copied - lastReport >= 1024 * 1024) {
                        lastReport = copied;
                        progress.update(copied, release.size());
                    }
                }
            }
            if (progress != null) {
                progress.update(copied, release.size());
            }
            if (release.size() >= 0 && copied != release.size()) {
                throw new McException("the JDK download is " + copied + " bytes, the API says "
                        + release.size());
            }
        } catch (IOException e) {
            throw new McException("Failed to download the JDK: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McException("the JDK download was interrupted", e);
        }
    }

    /// Makes an unpacked runtime runnable by the user the server runs as.
    ///
    /// Directories `0755`, launchers under `bin/` and `lib/jspawnhelper` `0755`,
    /// everything else `0644`. Ownership is deliberately left alone: like the
    /// Node runtimes, this tree belongs to the panel and must not be rewritable
    /// by the process it starts. That also keeps it out of the agent hand-over
    /// path — root-owned `0755` is the correct state, not an offender.
    private static void normalizeModes(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            for (Path path : walk.toList()) {
                if (Files.isSymbolicLink(path)) {
                    continue;
                }
                boolean runnable = Files.isDirectory(path) || isLauncher(path);
                setMode(path, runnable ? "rwxr-xr-x" : "rw-r--r--");
            }
        }
    }

    private static boolean isLauncher(Path path) {
        Path parent = path.getParent();
        if (parent != null && "bin".equals(parent.getFileName().toString())) {
            return true;
        }
        return "jspawnhelper".equals(path.getFileName().toString());
    }

    private static void setMode(Path path, String mode) {
        try {
            Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString(mode));
        } catch (UnsupportedOperationException e) {
            // Windows: there is no mode to set, and NTFS has no execute bit.
        } catch (IOException e) {
            LOG.warning("Could not set the mode of " + path + " to " + mode, e);
        }
    }

    /// The unpacked size of a runtime, for the settings page.
    private static long sizeOf(Path root) {
        long[] total = {0};
        try (var walk = Files.walk(root)) {
            for (Path path : walk.toList()) {
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                try {
                    total[0] += Files.size(path);
                } catch (IOException ignored) {
                    // A file that vanished mid-walk costs its size, not the answer.
                }
            }
        } catch (IOException e) {
            LOG.warning("Could not measure " + root, e);
        }
        return total[0];
    }

    /// Unpacks a JDK archive into `target`, dropping the single top-level
    /// directory the distribution ships (`.zip` and `.tar.gz` both do).
    private static void unpack(Path archive, Path target, String url) throws McException {
        try {
            if (url.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                unpackZip(archive, target);
            } else {
                unpackTarGz(archive, target);
            }
        } catch (IOException e) {
            throw new McException("Failed to unpack the JDK: " + e.getMessage(), e);
        }
    }

    private static void unpackZip(Path archive, Path target) throws IOException {
        String prefix = null;
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (prefix == null) {
                    prefix = topLevel(name);
                }
                Path relative = relative(name, prefix);
                if (relative == null) {
                    continue;
                }
                Path destination = safeResolve(target, relative);
                if (entry.isDirectory()) {
                    Files.createDirectories(destination);
                    continue;
                }
                Files.createDirectories(destination.getParent());
                try (InputStream in = zip.getInputStream(entry);
                     OutputStream out = Files.newOutputStream(destination)) {
                    in.transferTo(out);
                }
            }
        }
    }

    private static void unpackTarGz(Path archive, Path target) throws IOException {
        String prefix = null;
        try (TarArchiveInputStream tar = new TarArchiveInputStream(
                new GZIPInputStream(Files.newInputStream(archive)))) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                String name = entry.getName();
                if (prefix == null) {
                    prefix = topLevel(name);
                }
                Path relative = relative(name, prefix);
                if (relative == null) {
                    continue;
                }
                Path destination = safeResolve(target, relative);
                if (entry.isDirectory()) {
                    Files.createDirectories(destination);
                    continue;
                }
                Files.createDirectories(destination.getParent());
                try (OutputStream out = Files.newOutputStream(destination)) {
                    tar.transferTo(out);
                }
            }
        }
    }

    private static String topLevel(String name) {
        String cleaned = name.replace('\\', '/');
        while (cleaned.startsWith("./")) {
            cleaned = cleaned.substring(2);
        }
        while (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }
        int slash = cleaned.indexOf('/');
        return slash < 0 ? cleaned : cleaned.substring(0, slash);
    }

    private static @Nullable Path relative(String name, @Nullable String prefix) {
        String cleaned = name.replace('\\', '/');
        while (cleaned.startsWith("./")) {
            cleaned = cleaned.substring(2);
        }
        while (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.isBlank() || cleaned.equals(prefix)) {
            return null;
        }
        if (prefix != null && !prefix.isEmpty()) {
            if (!cleaned.startsWith(prefix + "/")) {
                return null;
            }
            cleaned = cleaned.substring(prefix.length() + 1);
        }
        return cleaned.isBlank() ? null : Path.of(cleaned);
    }

    private static Path safeResolve(Path root, Path relative) throws IOException {
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root.normalize())) {
            throw new IOException("the archive contains a path outside its root: " + relative);
        }
        return resolved;
    }

    private static @Nullable Path javaBinOf(Path runtimeDirectory) {
        for (String name : List.of("java", "java.exe")) {
            Path candidate = runtimeDirectory.resolve("bin").resolve(name);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static void deleteRecursively(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (var walk = Files.walk(directory)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            LOG.warning("Could not remove " + directory, e);
        }
    }

    private static String platformOs() {
        return switch (OperatingSystem.CURRENT_OS) {
            case WINDOWS -> "windows";
            case MACOS -> "mac";
            default -> "linux";
        };
    }

    private static String platformArch() {
        return switch (Platform.SYSTEM_PLATFORM.arch()) {
            case X86_64 -> "x64";
            case ARM64 -> "aarch64";
            default -> "x64";
        };
    }

    private static String platformLabel() {
        return platformOs() + "/" + platformArch();
    }

    private JsonElement fetchJson(URI uri) throws McException {
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

    private static @Nullable JsonObject object(JsonObject parent, String name) {
        return parent.has(name) && parent.get(name).isJsonObject()
                ? parent.getAsJsonObject(name) : null;
    }

    private static String sha256Of(Path file) throws McException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new McException("Failed to hash " + file, e);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new McException("SHA-256 is not available in this JVM", e);
        }
    }

    /// Whether this platform can run a JDK the panel downloads.
    ///
    /// @return `true` when Adoptium publishes for it
    public static boolean supported() {
        Architecture arch = Platform.SYSTEM_PLATFORM.arch();
        return arch == Architecture.X86_64 || arch == Architecture.ARM64;
    }
}
