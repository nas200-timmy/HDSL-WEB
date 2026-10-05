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
package org.jackhuang.hmcl.web.zcode;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.io.FileUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Creates, enumerates, edits and removes [ZcodeInstance]s, and injects the
/// provider credentials ZCode picks up on its own.
///
/// Each instance is stored as `zcode/instances/<id>/instance.json` under the
/// server data directory — the same manifest style as
/// [org.jackhuang.hmcl.dsh.DshInstanceManager], in a directory tree of its own
/// so the experimental category never touches dsh state. The manager itself is
/// bound to the instances root at construction time because the root comes
/// from the server config rather than from the launcher's home directory.
///
/// An instance directory also holds everything the process owns at runtime:
/// `data/` (passed to ZCode as `ZCODE_DATA_BASE_DIR`, so each instance's
/// config, sessions and credentials are private), `workspace/` (the default
/// workspace) and `logs/zcode.log` (the process's merged stdout and stderr).
///
/// **Experimental.** Credential injection writes the personal provider config
/// ZCode reads from `<ZCODE_DATA_BASE_DIR>/.zcode/v2/provider_config.json`.
/// That file's shape is reverse-engineered from the ZCode v3.14.3 sources
/// (`packages/provider-node/src/provider-config-file-codec.ts` and
/// `packages/provider/src/config/rule-data-schema.ts`) and is **not a
/// documented, stable contract** — a future ZCode can rename or restructure it,
/// in which case the injection silently stops doing anything. Best effort,
/// by design.
@NotNullByDefault
public final class ZcodeInstanceManager {

    /// The manifest file inside an instance directory.
    public static final String MANIFEST_NAME = "instance.json";

    /// The directory inside an instance directory that becomes the process's
    /// `ZCODE_DATA_BASE_DIR`; ZCode keeps its own state at `.zcode/` below it.
    public static final String DATA_DIR_NAME = "data";

    /// The default working directory the instance is launched with.
    public static final String WORKSPACE_DIR_NAME = "workspace";

    /// The process log: merged stdout and stderr, truncated at every launch.
    public static final String LOG_FILE_NAME = "zcode.log";

    /// The provider config this panel writes when an API key is configured,
    /// relative to the instance's data directory.
    public static final String PROVIDER_CONFIG_FILE = ".zcode/v2/provider_config.json";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path root;

    /// Binds a manager to the `zcode/instances` directory.
    ///
    /// @param root the instances root
    public ZcodeInstanceManager(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /// The directory every instance lives under.
    ///
    /// @return the instances root
    public Path root() {
        return root;
    }

    /// Lists every readable instance, newest first.
    ///
    /// Directories without a manifest, or with an unreadable one, are skipped
    /// rather than failing the whole listing.
    ///
    /// @return the known instances
    public List<ZcodeInstance> list() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<ZcodeInstance> instances = new ArrayList<>();
        try (Stream<Path> entries = Files.list(root)) {
            for (Path directory : entries.filter(Files::isDirectory).toList()) {
                ZcodeInstance instance = read(directory);
                if (instance != null) {
                    instances.add(instance);
                }
            }
        } catch (IOException e) {
            LOG.warning("Failed to enumerate ZCode instances in " + root, e);
        }
        instances.sort(Comparator.comparingLong(ZcodeInstance::createdAt).reversed());
        return instances;
    }

    /// Finds an instance by id.
    ///
    /// @param id the instance id
    /// @return the instance, or `null` when it does not exist or is unreadable
    public @Nullable ZcodeInstance find(String id) {
        if (id == null || id.isBlank() || id.contains("/") || id.contains("\\")
                || id.equals(".") || id.equals("..")) {
            return null;
        }
        return read(root.resolve(id));
    }

    /// Creates and persists a new instance.
    ///
    /// The instance receives a random id, a random access token, its own
    /// `data/` and `workspace/` directories, and — when an API key is given —
    /// its first provider config. The workspace is created eagerly so a later
    /// launch cannot fail merely because the directory is missing.
    ///
    /// @param name    the display name; must not be blank
    /// @param baseUrl the provider base URL, or `null`/blank for the default
    /// @param apiKey  the provider API key, or `null`/blank for none
    /// @return the created instance
    /// @throws ZcodeException when the name is blank or the instance cannot be written
    public ZcodeInstance create(String name, @Nullable String baseUrl, @Nullable String apiKey)
            throws ZcodeException {
        String normalized = name == null ? "" : name.trim();
        if (normalized.isEmpty()) {
            throw new ZcodeException("A ZCode instance needs a name");
        }

        String id = newTokenLike(9);
        Path directory = root.resolve(id);
        ZcodeInstance instance = new ZcodeInstance(id, normalized,
                workspaceDirectoryOf(directory).toString(),
                baseUrl, apiKey, newTokenLike(32), 0, System.currentTimeMillis());

        try {
            Files.createDirectories(dataDirectoryOf(directory));
            Files.createDirectories(workspaceDirectoryOf(directory));
            Files.createDirectories(logDirectoryOf(directory));
        } catch (IOException e) {
            throw new ZcodeException("Failed to create " + directory, e);
        }

        write(instance);
        try {
            writeProviderConfig(instance);
        } catch (ZcodeException e) {
            LOG.warning("Could not inject the provider config for " + id, e);
        }
        LOG.info("Created experimental ZCode instance " + id + " (" + normalized + ")");
        return instance;
    }

    /// Overwrites an existing instance definition.
    ///
    /// @param instance the instance to persist
    /// @throws ZcodeException when the manifest cannot be written
    public void update(ZcodeInstance instance) throws ZcodeException {
        if (!Files.isDirectory(instanceDirectory(instance.id()))) {
            throw new ZcodeException("ZCode instance " + instance.id() + " does not exist");
        }
        write(instance);
    }

    /// Removes an instance and everything it owns.
    ///
    /// Unlike a shared dsh home, a ZCode instance keeps all its state inside
    /// its own directory, so deleting the directory deletes the whole instance.
    ///
    /// @param id the instance id
    /// @throws ZcodeException when the instance does not exist or cannot be removed
    public void delete(String id) throws ZcodeException {
        ZcodeInstance instance = find(id);
        if (instance == null) {
            throw new ZcodeException("ZCode instance " + id + " does not exist");
        }
        Path directory = instanceDirectory(id);
        if (!directory.normalize().startsWith(root)) {
            throw new ZcodeException("Refusing to delete " + directory + " because it is outside the ZCode instances directory");
        }
        try {
            FileUtils.deleteDirectory(directory);
        } catch (IOException e) {
            throw new ZcodeException("Failed to remove " + directory, e);
        }
        LOG.info("Removed experimental ZCode instance " + id);
    }

    /// The directory holding an instance's manifest and runtime state.
    ///
    /// @param id the instance id
    /// @return the instance directory
    public Path instanceDirectory(String id) {
        return root.resolve(id);
    }

    /// The directory passed to the process as `ZCODE_DATA_BASE_DIR`.
    ///
    /// @param instance the instance
    /// @return the data directory
    public Path dataDirectory(ZcodeInstance instance) {
        return dataDirectoryOf(instanceDirectory(instance.id()));
    }

    /// The working directory the process is launched with.
    ///
    /// @param instance the instance
    /// @return the workspace directory
    public Path workspaceDirectory(ZcodeInstance instance) {
        return workspaceDirectoryOf(instanceDirectory(instance.id()));
    }

    /// The file the process's merged output is appended to.
    ///
    /// @param instance the instance
    /// @return the log file
    public Path logFile(ZcodeInstance instance) {
        return logDirectoryOf(instanceDirectory(instance.id())).resolve(LOG_FILE_NAME);
    }

    /// Reads the last lines of an instance's process log.
    ///
    /// @param instance the instance
    /// @param maxLines the most lines to return
    /// @return the trailing log lines, oldest first; empty when there is no log
    /// @throws IOException when the log exists but cannot be read
    public List<String> tailLog(ZcodeInstance instance, int maxLines) throws IOException {
        Path log = logFile(instance);
        if (!Files.isRegularFile(log)) {
            return List.of();
        }
        try (var lines = Files.lines(log, StandardCharsets.UTF_8)) {
            List<String> all = lines.toList();
            return List.copyOf(all.subList(Math.max(0, all.size() - maxLines), all.size()));
        }
    }

    /// Writes the personal provider config ZCode reads on startup, when the
    /// instance carries an API key. With no key the method writes nothing and
    /// keeps whatever ZCode created on its own.
    ///
    /// The file is written atomically (a sibling temp file, then an atomic
    /// move) so a crash mid-write cannot leave a truncated config behind a
    /// live process.
    ///
    /// The shape mirrors what ZCode v3.14.3 itself writes (see the class
    /// comment). One verified detail: `personalModelConfigRulesSchema` is
    /// `.strict()` and **requires both** `providerModelRules` and
    /// `manualProviderModelRules`, which is why an empty `providerModelRules`
    /// is spelled out instead of omitted.
    ///
    /// @param instance the instance
    /// @throws ZcodeException when a key is configured but the file cannot be written
    public void writeProviderConfig(ZcodeInstance instance) throws ZcodeException {
        String apiKey = instance.apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return;
        }

        JsonObject access = new JsonObject();
        access.addProperty("type", "api-key");
        access.addProperty("apiKey", apiKey);

        JsonObject api = new JsonObject();
        api.addProperty("type", "openai-chat-completions");
        api.addProperty("baseUrl", instance.baseUrlOrDefault());

        JsonObject config = new JsonObject();
        config.addProperty("group", "standard-personal");
        config.add("access", access);
        config.add("api", api);
        config.add("personalModelIds", new JsonArray());
        config.addProperty("visibility", "visible");

        JsonObject rule = new JsonObject();
        rule.addProperty("providerId", "hdsl-injected");
        rule.add("config", config);

        JsonArray providerRules = new JsonArray();
        providerRules.add(rule);
        JsonObject providerConfigRules = new JsonObject();
        providerConfigRules.add("providerRules", providerRules);

        JsonObject modelConfigRules = new JsonObject();
        modelConfigRules.add("providerModelRules", new JsonArray());
        modelConfigRules.add("manualProviderModelRules", new JsonArray());

        JsonObject inner = new JsonObject();
        inner.add("providerConfigRules", providerConfigRules);
        inner.add("modelConfigRules", modelConfigRules);

        JsonObject body = new JsonObject();
        body.addProperty("schemaVersion", 1);
        body.add("config", inner);

        Path file = dataDirectory(instance).resolve(PROVIDER_CONFIG_FILE);
        try {
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
            try {
                Files.writeString(temp, JsonUtils.GSON.toJson(body), StandardCharsets.UTF_8);
                try {
                    Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new ZcodeException("Failed to write the ZCode provider config for " + instance.id(), e);
        }
    }

    private Path dataDirectoryOf(Path directory) {
        return directory.resolve(DATA_DIR_NAME);
    }

    private Path workspaceDirectoryOf(Path directory) {
        return directory.resolve(WORKSPACE_DIR_NAME);
    }

    private Path logDirectoryOf(Path directory) {
        return directory.resolve("logs");
    }

    /// Mints a random base64url token of the given byte length — the same
    /// construction [org.jackhuang.hmcl.web.auth.AuthService] uses for sessions.
    private static String newTokenLike(int bytes) {
        byte[] random = new byte[bytes];
        RANDOM.nextBytes(random);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    /// Reads the manifest inside an instance directory.
    ///
    /// @param directory the candidate instance directory
    /// @return the instance, or `null` when there is no readable manifest
    private static @Nullable ZcodeInstance read(Path directory) {
        Path manifest = directory.resolve(MANIFEST_NAME);
        if (!Files.isRegularFile(manifest)) {
            return null;
        }
        try {
            ZcodeInstance instance = JsonUtils.fromJsonFile(manifest, ZcodeInstance.class);
            if (instance == null || instance.id() == null || instance.token() == null) {
                return null;
            }
            return instance;
        } catch (Exception e) {
            LOG.warning("Failed to read ZCode instance manifest " + manifest, e);
            return null;
        }
    }

    /// Writes an instance manifest.
    ///
    /// @param instance the instance to persist
    /// @throws ZcodeException when the manifest cannot be written
    private void write(ZcodeInstance instance) throws ZcodeException {
        try {
            Path directory = instanceDirectory(instance.id());
            Files.createDirectories(directory);
            JsonUtils.writeToJsonFile(directory.resolve(MANIFEST_NAME), instance);
        } catch (IOException e) {
            throw new ZcodeException("Failed to write the ZCode instance manifest for " + instance.id(), e);
        }
    }
}
