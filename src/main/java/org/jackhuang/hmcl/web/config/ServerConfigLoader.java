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
package org.jackhuang.hmcl.web.config;

import org.jackhuang.hmcl.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.exceptions.MarkedYamlEngineException;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/// Loads the server configuration from `$HDSL_DATA/server.yaml` and overlays
/// the environment on top. Environment variables beat the file, which beats
/// the built-in defaults.
///
/// The yaml schema is fully optional; see [ServerConfig] for the defaults.
/// A malformed file or an invalid value fails startup with a message that
/// names the offending line or field — a server must not silently start with
/// half a configuration.
@NotNullByDefault
public final class ServerConfigLoader {
    /// Name of the environment variable holding the data directory.
    public static final String ENV_DATA_DIR = "HDSL_DATA";
    public static final String ENV_PORT = "HDSL_PORT";
    public static final String ENV_BIND_HOST = "HDSL_BIND_HOST";
    public static final String ENV_HTTPS = "HDSL_HTTPS";
    public static final String ENV_ZCODE_PACKAGE = "HDSL_ZCODE_PACKAGE";
    public static final String ENV_ZCODE_NODE = "HDSL_ZCODE_NODE";
    public static final String ENV_ZCODE_BUILD_BIN = "HDSL_ZCODE_BUILD_BIN";
    public static final String ENV_ZCODE_PNPM = "HDSL_ZCODE_PNPM";
    public static final String ENV_ZCODE_SOURCE_URL = "HDSL_ZCODE_SOURCE_URL";
    public static final String ENV_ZCODE_KEEP_SOURCES = "HDSL_ZCODE_KEEP_SOURCES";

    public static final String YAML_FILE_NAME = "server.yaml";

    /// The data directory when `HDSL_DATA` is not set.
    public static final String DEFAULT_DATA_DIR = "./data";

    private ServerConfigLoader() {
    }

    /// Thrown when `server.yaml` or the environment carries an invalid value.
    /// The message is complete enough to print and exit on.
    public static final class ConfigException extends RuntimeException {
        public ConfigException(String message) {
            super(message);
        }

        public ConfigException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /// Resolves the data directory from the environment and loads the config.
    public static ServerConfig load(Map<String, String> env, Logger logger) {
        Path dataDir = Path.of(env.getOrDefault(ENV_DATA_DIR, DEFAULT_DATA_DIR)).toAbsolutePath().normalize();
        return load(dataDir, env, logger);
    }

    /// Loads the configuration rooted at `dataDir`, overlaying `env`.
    public static ServerConfig load(Path dataDir, Map<String, String> env, Logger logger) {
        ServerConfig config = new ServerConfig();
        config.dataDir = dataDir.toAbsolutePath().normalize();

        Path yamlFile = config.dataDir.resolve(YAML_FILE_NAME);
        if (Files.exists(yamlFile)) {
            applyYaml(config, yamlFile, logger);
        }
        applyEnvironment(config, env);
        validate(config);
        return config;
    }

    private static void applyYaml(ServerConfig config, Path yamlFile, Logger logger) {
        LoadSettings settings = LoadSettings.builder().setLabel(yamlFile.toString()).build();
        Load load = new Load(settings);

        Map<String, Object> root;
        try (InputStream in = Files.newInputStream(yamlFile)) {
            Object parsed = load.loadFromInputStream(in);
            if (parsed == null) {
                return; // empty file = all defaults
            }
            if (!(parsed instanceof Map<?, ?> map)) {
                throw new ConfigException("Failed to parse " + yamlFile + ": the root of the document must be a mapping");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) map;
            root = cast;
        } catch (MarkedYamlEngineException e) {
            String mark = e.getProblemMark()
                    .map(m -> " at line " + m.getLine() + ", column " + m.getColumn())
                    .orElse("");
            String problem = Optional.ofNullable(e.getProblem()).orElse("syntax error");
            throw new ConfigException("Failed to parse " + yamlFile + mark + ": " + problem, e);
        } catch (YamlEngineException e) {
            throw new ConfigException("Failed to parse " + yamlFile + ": " + e.getMessage(), e);
        } catch (IOException e) {
            throw new ConfigException("Failed to read " + yamlFile + ": " + e.getMessage(), e);
        }

        if (root.containsKey("bind_host")) {
            config.bindHost = stringField(root, "bind_host");
        }
        if (root.containsKey("port")) {
            config.port = intField(root, "port");
        }
        if (root.containsKey("public_base_url")) {
            config.publicBaseUrl = stringField(root, "public_base_url");
        }
        if (root.containsKey("https")) {
            applyHttps(config, mapField(root, "https"));
        }
        if (root.containsKey("auth")) {
            applyAuth(config, mapField(root, "auth"));
        }
        for (String key : root.keySet()) {
            switch (key) {
                case "bind_host", "port", "public_base_url", "https", "auth" -> {
                }
                default -> logger.warning(yamlFile + ": unknown key `" + key + "` ignored");
            }
        }
    }

    private static void applyHttps(ServerConfig config, Map<String, Object> https) {
        ServerConfig.HttpsConfig target = config.https;
        if (https.containsKey("enabled")) {
            target.enabled = booleanField(https, "enabled");
        }
        if (https.containsKey("cert_file")) {
            target.certFile = stringField(https, "cert_file");
        }
        if (https.containsKey("key_file")) {
            target.keyFile = stringField(https, "key_file");
        }
        if (https.containsKey("pkcs12_file")) {
            target.pkcs12File = stringField(https, "pkcs12_file");
        }
        if (https.containsKey("pkcs12_password")) {
            target.pkcs12Password = stringField(https, "pkcs12_password");
        }
        if (https.containsKey("redirect_http")) {
            target.redirectHttp = booleanField(https, "redirect_http");
        }
        if (https.containsKey("redirect_port")) {
            target.redirectPort = intField(https, "redirect_port");
        }
    }

    private static void applyAuth(ServerConfig config, Map<String, Object> auth) {
        ServerConfig.AuthConfig target = config.auth;
        if (auth.containsKey("disabled")) {
            target.disabled = booleanField(auth, "disabled");
        }
        if (auth.containsKey("session_ttl_hours")) {
            target.sessionTtlHours = intField(auth, "session_ttl_hours");
        }
    }

    private static void applyEnvironment(ServerConfig config, Map<String, String> env) {
        if (env.containsKey(ENV_PORT)) {
            config.port = parseInt(env.get(ENV_PORT), ENV_PORT);
        }
        if (env.containsKey(ENV_BIND_HOST)) {
            config.bindHost = env.get(ENV_BIND_HOST);
        }
        if (env.containsKey(ENV_HTTPS)) {
            String value = env.get(ENV_HTTPS);
            config.https.enabled = switch (value) {
                case "true" -> true;
                case "false" -> false;
                default -> throw new ConfigException(ENV_HTTPS + " must be `true` or `false`, got `" + value + "`");
            };
        }
        if (env.containsKey(ENV_ZCODE_PACKAGE)) {
            config.zcode.packageDir = env.get(ENV_ZCODE_PACKAGE);
        }
        if (env.containsKey(ENV_ZCODE_NODE)) {
            config.zcode.nodePath = env.get(ENV_ZCODE_NODE);
        }
        if (env.containsKey(ENV_ZCODE_BUILD_BIN)) {
            config.zcode.buildBin = env.get(ENV_ZCODE_BUILD_BIN);
        }
        if (env.containsKey(ENV_ZCODE_PNPM)) {
            config.zcode.pnpm = env.get(ENV_ZCODE_PNPM);
        }
        if (env.containsKey(ENV_ZCODE_SOURCE_URL)) {
            config.zcode.sourceUrl = env.get(ENV_ZCODE_SOURCE_URL);
        }
        if (env.containsKey(ENV_ZCODE_KEEP_SOURCES)) {
            config.zcode.keepSources = Boolean.parseBoolean(env.get(ENV_ZCODE_KEEP_SOURCES).trim());
        }
    }

    private static void validate(ServerConfig config) {
        if (config.port < 0 || config.port > 65535) {
            throw new ConfigException("port must be between 0 and 65535, got " + config.port);
        }
        if (config.https.redirectPort < 1 || config.https.redirectPort > 65535) {
            throw new ConfigException("https.redirect_port must be between 1 and 65535, got " + config.https.redirectPort);
        }
        if (config.auth.sessionTtlHours <= 0) {
            throw new ConfigException("auth.session_ttl_hours must be positive, got " + config.auth.sessionTtlHours);
        }
        if (!config.https.enabled && config.https.redirectHttp) {
            throw new ConfigException("https.redirect_http requires https.enabled; a plain-HTTP server cannot redirect to HTTPS");
        }
        boolean hasCert = !config.https.certFile.isBlank();
        boolean hasKey = !config.https.keyFile.isBlank();
        if (hasCert != hasKey) {
            throw new ConfigException("https.cert_file and https.key_file must be configured together (PEM certificate chain + private key)");
        }
        if (config.auth.disabled && !isLoopback(config.bindHost)) {
            throw new ConfigException("auth.disabled is only allowed when the server binds a loopback address; bind_host is `"
                    + config.bindHost + "`");
        }
    }

    private static boolean isLoopback(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) {
                return false;
            }
            for (InetAddress address : addresses) {
                if (!address.isLoopbackAddress()) {
                    return false;
                }
            }
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    // ------------------------------------------------------------ yaml fields --

    private static String stringField(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof String s) {
            return s;
        }
        throw new ConfigException("field `" + key + "`: expected a string, got " + describe(value));
    }

    private static int intField(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Integer i) {
            return i;
        }
        if (value instanceof Long l) {
            return Math.toIntExact(l);
        }
        throw new ConfigException("field `" + key + "`: expected an integer, got " + describe(value));
    }

    private static boolean booleanField(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        throw new ConfigException("field `" + key + "`: expected `true` or `false`, got " + describe(value));
    }

    private static Map<String, Object> mapField(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Map<?, ?> nested) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) nested;
            return cast;
        }
        throw new ConfigException("field `" + key + "`: expected a mapping, got " + describe(value));
    }

    private static int parseInt(String text, String source) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            throw new ConfigException(source + " must be an integer, got `" + text + "`", e);
        }
    }

    private static String describe(Object value) {
        if (value == null) {
            return "nothing";
        }
        if (value instanceof String s) {
            return "`" + s + "`";
        }
        return "a " + value.getClass().getSimpleName() + " (" + value + ")";
    }
}
