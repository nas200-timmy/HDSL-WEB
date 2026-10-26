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
package org.jackhuang.hmcl.dsh;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// The plugins this panel ships inside its own jar.
///
/// A first-party plugin is an ordinary npm package under `plugins/` in this
/// repository. The build packs each one into an npm-layout tarball, hashes it and
/// writes `/first-party-plugins/index.json` next to it, all of which end up in the
/// jar's resources. This class is the read side of that index: it answers which
/// packages are bundled, at which version, and writes the tarball out when the
/// install route asks for a local specification.
///
/// The point is the offline install. `dsh plugin add file:<tarball>` resolves from
/// the file system, so a panel instance can install its terminal interface on a
/// machine with no registry route — which is the normal state of a deployment
/// behind a firewall, and the reason the panel exists at all.
@NotNullByDefault
public final class FirstPartyPlugins {

    /// Where the build puts the index inside the jar.
    static final String INDEX_RESOURCE = "/first-party-plugins/index.json";

    /// One bundled plugin, as the build described it.
    ///
    /// @param name    the npm package name
    /// @param version the package version
    /// @param file    the tarball's file name inside `/first-party-plugins/`
    /// @param sha256  the tarball's digest, or `null` when the index did not record one
    public record Bundled(String name, String version, String file, @Nullable String sha256) {

        /// The classpath resource holding the tarball.
        ///
        /// @return the absolute resource path
        public String resourcePath() {
            return "/first-party-plugins/" + file;
        }
    }

    /// Loaded once: the index belongs to the running jar and cannot change under it.
    private static volatile @Nullable List<Bundled> loaded;

    private FirstPartyPlugins() {
    }

    /// Every bundled plugin, in the order the build listed them.
    ///
    /// @return the entries, empty when this build shipped none
    public static List<Bundled> all() {
        List<Bundled> cached = loaded;
        if (cached != null) {
            return cached;
        }
        synchronized (FirstPartyPlugins.class) {
            if (loaded == null) {
                loaded = readIndex();
            }
            return loaded;
        }
    }

    /// Looks a bundle up by package name, case-insensitively.
    ///
    /// @param packageName the npm package name, or `null`
    /// @return the entry, or empty when this build ships no such plugin
    public static Optional<Bundled> find(@Nullable String packageName) {
        if (packageName == null || packageName.isBlank()) {
            return Optional.empty();
        }
        String wanted = packageName.trim();
        for (Bundled bundled : all()) {
            if (bundled.name().equalsIgnoreCase(wanted)) {
                return Optional.of(bundled);
            }
        }
        return Optional.empty();
    }

    /// Whether this build ships the named plugin.
    ///
    /// @param packageName the npm package name
    /// @return whether it is bundled
    public static boolean isBundled(@Nullable String packageName) {
        return find(packageName).isPresent();
    }

    /// Writes a bundled plugin's tarball out under `directory`.
    ///
    /// The digest is verified against the index before the file is written, so a
    /// truncated or repacked jar is reported here rather than as a pnpm error the
    /// user cannot read.
    ///
    /// @param packageName the npm package name
    /// @param directory   the directory to write into; created when missing
    /// @return the tarball's path
    /// @throws DshException when the plugin is not bundled, the resource is gone, or
    ///                      the digest does not match
    public static Path materialize(String packageName, Path directory) throws DshException {
        Bundled bundled = find(packageName).orElseThrow(() -> new DshException(
                packageName + " 不在这个面板自带的插件清单里。"));
        byte[] bytes;
        try (InputStream in = FirstPartyPlugins.class.getResourceAsStream(bundled.resourcePath())) {
            if (in == null) {
                throw new DshException("这个面板打出来的 jar 里没有 " + bundled.file()
                        + "（自带插件清单提到了它，资源却不在）。请重新构建面板。");
            }
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new DshException("读取自带插件 " + bundled.file() + " 失败", e);
        }
        String recorded = bundled.sha256();
        if (recorded != null && !recorded.isBlank()) {
            String actual = sha256(bytes);
            if (!recorded.equalsIgnoreCase(actual)) {
                throw new DshException("自带插件 " + bundled.file() + " 的摘要不对（清单 "
                        + recorded + "，实际 " + actual + "）。面板的 jar 可能被改动过。");
            }
        }
        try {
            Files.createDirectories(directory);
            Path target = directory.resolve(bundled.file());
            Files.write(target, bytes);
            return target;
        } catch (IOException e) {
            throw new DshException("写入自带插件 " + bundled.file() + " 失败", e);
        }
    }

    /// Reads and validates the index from the classpath.
    private static List<Bundled> readIndex() {
        try (InputStream in = FirstPartyPlugins.class.getResourceAsStream(INDEX_RESOURCE)) {
            if (in == null) {
                // A plain `gradle build` in an IDE can run classes without the
                // resources task; an empty list keeps the panel working and says so.
                LOG.info("No first-party plugin index in this build (" + INDEX_RESOURCE + ')');
                return List.of();
            }
            JsonElement parsed = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                LOG.warning("Malformed first-party plugin index: not an object");
                return List.of();
            }
            JsonArray plugins = parsed.getAsJsonObject().getAsJsonArray("plugins");
            if (plugins == null) {
                return List.of();
            }
            List<Bundled> entries = new ArrayList<>();
            for (JsonElement element : plugins) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject json = element.getAsJsonObject();
                String name = string(json, "name");
                String version = string(json, "version");
                String file = string(json, "file");
                if (name == null || version == null || file == null) {
                    LOG.warning("Skipping an incomplete first-party plugin entry: " + json);
                    continue;
                }
                entries.add(new Bundled(name, version, file, string(json, "sha256")));
            }
            return List.copyOf(entries);
        } catch (IOException | RuntimeException e) {
            LOG.warning("Could not read the first-party plugin index", e);
            return List.of();
        }
    }

    /// A string member, or `null` when absent or not a primitive.
    private static @Nullable String string(JsonObject json, String field) {
        JsonElement element = json.get(field);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    /// The lower-case hex SHA-256 of a byte array.
    static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                builder.append(Character.forDigit((value >> 4) & 0xf, 16));
                builder.append(Character.forDigit(value & 0xf, 16));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            // Every Java platform is required to ship SHA-256.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
