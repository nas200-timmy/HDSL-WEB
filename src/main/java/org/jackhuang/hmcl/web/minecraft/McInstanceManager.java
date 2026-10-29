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

import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/// The instances root of the Minecraft surface: one directory per server, a
/// manifest inside it, and the files the game expects beside the manifest.
///
/// The manager owns only the panel's bookkeeping. `server.jar`, the world and
/// the logs are ordinary files the server itself writes; the panel reads them
/// when it needs to and never treats their absence as "the instance is gone".
@NotNullByDefault
public final class McInstanceManager {

    /// The panel-side manifest.
    public static final String MANIFEST_NAME = "instance.json";
    /// The game's own configuration.
    public static final String PROPERTIES_NAME = "server.properties";
    /// The Mojang EULA marker; written only after the owner accepted.
    public static final String EULA_NAME = "eula.txt";
    /// The server program, downloaded per version.
    public static final String SERVER_JAR_NAME = "server.jar";
    public static final String WORLD_DIR_NAME = "world";
    public static final String LOG_DIR_NAME = "logs";
    public static final String LOG_NAME = "latest.log";

    private static final Logger LOG = Logger.LOG;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path root;

    /// @param root the directory that holds every instance (`<data>/minecraft/instances`)
    public McInstanceManager(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /// The instances root.
    ///
    /// @return the root
    public Path root() {
        return root;
    }

    /// Lists every readable instance, newest first.
    ///
    /// @return the instances
    public List<McInstance> list() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<McInstance> instances = new ArrayList<>();
        try (Stream<Path> entries = Files.list(root)) {
            for (Path directory : entries.filter(Files::isDirectory).toList()) {
                McInstance instance = read(directory);
                if (instance != null) {
                    instances.add(instance);
                }
            }
        } catch (IOException e) {
            LOG.warning("Failed to enumerate Minecraft instances in " + root, e);
        }
        instances.sort(Comparator.comparingLong(McInstance::createdAt).reversed());
        return instances;
    }

    /// Finds one instance.
    ///
    /// @param id the instance id
    /// @return the instance, or `null` when it does not exist or is unreadable
    public @Nullable McInstance find(String id) {
        if (id == null || id.isBlank() || id.contains("/") || id.contains("\\")
                || id.equals(".") || id.equals("..")) {
            return null;
        }
        return read(root.resolve(id));
    }

    /// Creates an instance and writes its manifest and a default
    /// `server.properties` (the game would write the same defaults on first
    /// start; writing them now makes the ports visible in the panel).
    ///
    /// @param name      the display name
    /// @param version   the Minecraft version to install later
    /// @param port      the game port
    /// @param rconPort  the RCON port
    /// @param memoryMb  the heap size
    /// @param levelName the world directory name
    /// @param seed      the world seed, or `null` for a random one
    /// @return the created instance
    /// @throws McException when the ports are taken or the files cannot be written
    public McInstance create(String name, String version, int port, int rconPort,
                             int memoryMb, String levelName, @Nullable String seed) throws McException {
        McPorts.validate(port, rconPort);
        if (portTaken(port)) {
            throw new McException("port " + port + " is already used by another instance");
        }
        if (portTaken(rconPort)) {
            throw new McException("port " + rconPort + " is already used by another instance");
        }

        String id = uniqueId(name);
        long now = System.currentTimeMillis();
        McInstance instance;
        try {
            instance = new McInstance(id, name == null ? "" : name.trim(), version.trim(),
                    port, rconPort, memoryMb, levelName, seed, now);
        } catch (IllegalArgumentException e) {
            throw new McException(e.getMessage(), e);
        }

        Path directory = instanceDirectory(id);
        try {
            Files.createDirectories(directory);
            Files.createDirectories(directory.resolve(LOG_DIR_NAME));
            write(instance);
            defaultProperties(instance).save(propertiesFile(instance));
        } catch (IOException e) {
            throw new McException("Failed to create " + directory, e);
        }
        LOG.info("Created Minecraft instance " + id + " (" + instance.name() + ", " + instance.version() + ")");
        return instance;
    }

    /// Persists an edited instance.
    ///
    /// @param instance the instance
    /// @throws McException when it does not exist or cannot be written
    public void update(McInstance instance) throws McException {
        if (!Files.isDirectory(instanceDirectory(instance.id()))) {
            throw new McException("Minecraft instance " + instance.id() + " does not exist");
        }
        write(instance);
    }

    /// Removes an instance and everything it owns.
    ///
    /// The caller is responsible for stopping the server and taking a backup
    /// first; this method only refuses to delete outside the root.
    ///
    /// @param id the instance id
    /// @throws McException when the instance does not exist or cannot be removed
    public void delete(String id) throws McException {
        McInstance instance = find(id);
        if (instance == null) {
            throw new McException("Minecraft instance " + id + " does not exist");
        }
        Path directory = instanceDirectory(id);
        if (!directory.normalize().startsWith(root)) {
            throw new McException("Refusing to delete " + directory + ": it is outside the instances directory");
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw new McException("Failed to remove " + directory, e);
        }
        LOG.info("Removed Minecraft instance " + id);
    }

    /// Whether a port is already claimed by any instance (game or RCON).
    ///
    /// @param port the port
    /// @return whether it is taken
    public boolean portTaken(int port) {
        for (McInstance instance : list()) {
            if (instance.port() == port || instance.rconPort() == port) {
                return true;
            }
        }
        return false;
    }

    /// The directory holding an instance.
    ///
    /// @param id the instance id
    /// @return the directory
    public Path instanceDirectory(String id) {
        return root.resolve(id);
    }

    /// The world directory.
    ///
    /// @param instance the instance
    /// @return the directory
    public Path worldDirectory(McInstance instance) {
        return instanceDirectory(instance.id()).resolve(instance.levelName());
    }

    /// The game's `server.properties`.
    ///
    /// @param instance the instance
    /// @return the file
    public Path propertiesFile(McInstance instance) {
        return instanceDirectory(instance.id()).resolve(PROPERTIES_NAME);
    }

    /// The EULA marker.
    ///
    /// @param instance the instance
    /// @return the file
    public Path eulaFile(McInstance instance) {
        return instanceDirectory(instance.id()).resolve(EULA_NAME);
    }

    /// The server program.
    ///
    /// @param instance the instance
    /// @return the file
    public Path serverJar(McInstance instance) {
        return instanceDirectory(instance.id()).resolve(SERVER_JAR_NAME);
    }

    /// The log the server writes.
    ///
    /// @param instance the instance
    /// @return the file
    public Path logFile(McInstance instance) {
        return instanceDirectory(instance.id()).resolve(LOG_DIR_NAME).resolve(LOG_NAME);
    }

    /// Reads `server.properties`, answering the managed defaults when the file
    /// is absent (a just-created instance, or one whose file was deleted).
    ///
    /// @param instance the instance
    /// @return the properties
    /// @throws McException when the file exists but cannot be read
    public McProperties properties(McInstance instance) throws McException {
        try {
            McProperties properties = McProperties.load(propertiesFile(instance));
            if (properties.asMap().isEmpty()) {
                return defaultProperties(instance);
            }
            return properties;
        } catch (IOException e) {
            throw new McException("Failed to read " + propertiesFile(instance), e);
        }
    }

    /// Writes `server.properties`.
    ///
    /// @param instance   the instance
    /// @param properties the properties to write
    /// @throws McException when the file cannot be written
    public void saveProperties(McInstance instance, McProperties properties) throws McException {
        try {
            properties.save(propertiesFile(instance));
        } catch (IOException e) {
            throw new McException("Failed to write " + propertiesFile(instance), e);
        }
    }

    /// Writes the Mojang EULA marker.
    ///
    /// Only call this after the owner accepted: the file is the operator's
    /// technical record of that acceptance, not the acceptance itself.
    ///
    /// @param instance the instance
    /// @throws McException when the file cannot be written
    public void acceptEula(McInstance instance) throws McException {
        try {
            Files.writeString(eulaFile(instance), "eula=true\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new McException("Failed to write " + eulaFile(instance), e);
        }
    }

    /// The last lines of the server log, when the server has written one.
    ///
    /// @param instance the instance
    /// @param maxLines how many lines to keep
    /// @return the lines, oldest first
    /// @throws IOException when the file cannot be read
    public List<String> tailLog(McInstance instance, int maxLines) throws IOException {
        Path log = logFile(instance);
        if (!Files.isRegularFile(log)) {
            return List.of();
        }
        List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
        int from = Math.max(0, lines.size() - Math.max(0, maxLines));
        return List.copyOf(lines.subList(from, lines.size()));
    }

    /// The properties a first start begins from: the two ports the panel
    /// allocated, RCON off (it is optional, §18), online mode on, and a random
    /// RCON password so enabling it later is not a plaintext surprise.
    private McProperties defaultProperties(McInstance instance) {
        McProperties properties = McProperties.empty();
        properties.set("server-port", String.valueOf(instance.port()));
        properties.set("rcon.port", String.valueOf(instance.rconPort()));
        properties.set("enable-rcon", "false");
        properties.set("rcon.password", randomPassword());
        properties.set("online-mode", "true");
        properties.set("white-list", "false");
        properties.set("enforce-whitelist", "false");
        properties.set("level-name", instance.levelName());
        properties.set("level-seed", instance.seed() == null ? "" : instance.seed());
        properties.set("motd", "A Minecraft Server");
        properties.set("max-players", "20");
        return properties;
    }

    private static String randomPassword() {
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /// A filesystem-safe id derived from the name, made unique by a numeric
    /// suffix. Chinese names collapse to `mc` — the id is internal, the display
    /// name keeps the user's text.
    private String uniqueId(@Nullable String name) {
        String base = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        StringBuilder slug = new StringBuilder();
        for (int i = 0; i < base.length() && slug.length() < 24; i++) {
            char c = base.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_') {
                slug.append(c);
            } else if (c == ' ' && !slug.isEmpty() && slug.charAt(slug.length() - 1) != '-') {
                slug.append('-');
            }
        }
        String candidate = slug.toString().replaceAll("-+$", "");
        if (candidate.isEmpty() || candidate.equals(".") || candidate.equals("..")) {
            candidate = "mc";
        }
        String id = candidate;
        int suffix = 2;
        while (Files.exists(instanceDirectory(id))) {
            id = candidate + "-" + suffix++;
        }
        return id;
    }

    private static @Nullable McInstance read(Path directory) {
        Path manifest = directory.resolve(MANIFEST_NAME);
        if (!Files.isRegularFile(manifest)) {
            return null;
        }
        try {
            McInstance instance = JsonUtils.fromJsonFile(manifest, McInstance.class);
            if (instance == null || instance.id() == null) {
                return null;
            }
            return instance;
        } catch (Exception e) {
            LOG.warning("Failed to read the Minecraft instance manifest " + manifest, e);
            return null;
        }
    }

    private void write(McInstance instance) throws McException {
        try {
            Path directory = instanceDirectory(instance.id());
            Files.createDirectories(directory);
            Path target = directory.resolve(MANIFEST_NAME);
            Path temp = Files.createTempFile(directory, "instance-manifest", ".json");
            try {
                JsonUtils.writeToJsonFile(temp, instance);
                move(temp, target);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new McException("Failed to write the instance manifest for " + instance.id(), e);
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}