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
import org.jackhuang.hmcl.web.acme.AcmeCa;
import org.jackhuang.hmcl.web.acme.AcmeDnsProviderRegistry;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.exceptions.MarkedYamlEngineException;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
    public static final String ENV_BASE_PATH = "HDSL_BASE_PATH";
    public static final String ENV_HTTPS = "HDSL_HTTPS";
    public static final String ENV_TRUST_FORWARDED_PROTO = "HDSL_TRUST_FORWARDED_PROTO";
    public static final String ENV_TRUSTED_PROXIES = "HDSL_TRUSTED_PROXIES";
    public static final String ENV_WEB_SHELL_ENABLED = "HDSL_WEB_SHELL_ENABLED";
    public static final String ENV_DEVELOPER_GUARD = "HDSL_DEVELOPER_GUARD";
    public static final String ENV_AGENT_REMOTE_NEEDS_SESSION = "HDSL_AGENT_REMOTE_NEEDS_SESSION";
    public static final String ENV_AGENT_LOOPBACK_ONLY = "HDSL_AGENT_LOOPBACK_ONLY";
    public static final String ENV_ZCODE_PACKAGE = "HDSL_ZCODE_PACKAGE";
    public static final String ENV_ZCODE_NODE = "HDSL_ZCODE_NODE";
    public static final String ENV_ZCODE_BUILD_BIN = "HDSL_ZCODE_BUILD_BIN";
    public static final String ENV_ZCODE_PNPM = "HDSL_ZCODE_PNPM";
    public static final String ENV_ZCODE_SOURCE_URL = "HDSL_ZCODE_SOURCE_URL";
    public static final String ENV_ZCODE_KEEP_SOURCES = "HDSL_ZCODE_KEEP_SOURCES";
    public static final String ENV_MC_PORT_START = "HDSL_MC_PORT_START";
    public static final String ENV_MC_PORT_END = "HDSL_MC_PORT_END";
    public static final String ENV_MC_MAX_INSTANCES = "HDSL_MC_MAX_INSTANCES";
    public static final String ENV_MC_MAX_MEMORY_MB = "HDSL_MC_MAX_MEMORY_MB";
    public static final String ENV_MC_JAVA_PATH = "HDSL_MC_JAVA_PATH";
    public static final String ENV_MEDIA_ROOT = "HDSL_MEDIA_ROOT";
    public static final String ENV_FFMPEG_PATH = "HDSL_FFMPEG_PATH";
    public static final String ENV_TRANSCODE_MAX_CONCURRENT = "HDSL_TRANSCODE_MAX_CONCURRENT";
    public static final String ENV_TRANSCODE_MAX_QUEUE = "HDSL_TRANSCODE_MAX_QUEUE";
    public static final String ENV_TRANSCODE_FAILURE_POLICY = "HDSL_TRANSCODE_FAILURE_POLICY";
    public static final String ENV_TRANSCODE_FAILURE_TIMEOUT_MINUTES = "HDSL_TRANSCODE_FAILURE_TIMEOUT_MINUTES";
    public static final String ENV_TRANSCODE_AUTO_RESUME = "HDSL_TRANSCODE_AUTO_RESUME";

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
        if (root.containsKey("web_shell")) {
            applyWebShell(config, mapField(root, "web_shell"));
        }
        if (root.containsKey("developer_guard")) {
            applyDeveloperGuard(config, mapField(root, "developer_guard"));
        }
        if (root.containsKey("agent")) {
            applyAgent(config, mapField(root, "agent"));
        }
        if (root.containsKey("mc")) {
            applyMc(config, mapField(root, "mc"));
        }
        if (root.containsKey("transcode")) {
            applyTranscode(config, mapField(root, "transcode"));
        }
        if (root.containsKey("logs")) {
            applyLogs(config, mapField(root, "logs"));
        }
        if (root.containsKey("network")) {
            applyNetwork(config, mapField(root, "network"));
        }
        if (root.containsKey("plain")) {
            applyPlain(config, mapField(root, "plain"));
        }
        for (String key : root.keySet()) {
            switch (key) {
                case "bind_host", "port", "public_base_url", "https", "auth", "web_shell",
                        "developer_guard", "agent", "mc", "transcode", "logs", "network",
                        "plain" -> {
                }
                default -> logger.warning(yamlFile + ": unknown key `" + key + "` ignored");
            }
        }
    }

    /// `network:` — 出站 TLS 的一次性授权截止时间。
    ///
    /// 写进来的必须是 ISO-8601 时刻；解析不了就拒绝启动，而不是悄悄当成"严格"或
    /// "永久"——两个方向都是安全上的错答案。
    private static void applyPlain(ServerConfig config, Map<String, Object> section) {
        if (section.containsKey("enhanced")) {
            config.plain.enhanced = booleanField(section, "enhanced");
        }
        if (section.containsKey("ssr_workers")) {
            int workers = intField(section, "ssr_workers");
            if (workers < 1 || workers > 16) {
                throw new ConfigException("plain.ssr_workers must be between 1 and 16, got " + workers);
            }
            config.plain.ssrWorkers = workers;
        }
    }

    /// `network.trusted_proxies`：YAML 列表或逗号分隔的精确地址。空值就是默认的
    /// "不信任何反代"。只接受地址本身，不接受网段——同机 nginx/Caddy 才是这条
    /// 配置要服务的形态，网段只会把"谁可以替客户端作证"放大到无法审计。
    private static java.util.Set<String> proxyAddresses(Object raw) {
        java.util.LinkedHashSet<String> addresses = new java.util.LinkedHashSet<>();
        if (raw instanceof java.util.List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    addresses.add(String.valueOf(item).trim());
                }
            }
        } else if (raw != null) {
            for (String item : String.valueOf(raw).split(",")) {
                addresses.add(item.trim());
            }
        }
        addresses.removeIf(String::isBlank);
        return java.util.Collections.unmodifiableSet(addresses);
    }

    private static void applyNetwork(ServerConfig config, Map<String, Object> section) {
        if (section.containsKey("accept_unverified_until")) {
            String raw = stringField(section, "accept_unverified_until");
            if (raw == null || raw.isBlank()) {
                config.network.acceptUnverifiedUntil = null;
            } else {
                try {
                    config.network.acceptUnverifiedUntil = java.time.Instant.parse(raw.trim());
                } catch (java.time.format.DateTimeParseException e) {
                    throw new ConfigException("network.accept_unverified_until is not an ISO-8601 instant: `"
                            + raw + "`");
                }
            }
        }
        if (section.containsKey("trusted_proxies")) {
            config.network.trustedProxies = proxyAddresses(section.get("trusted_proxies"));
        }
        for (String key : section.keySet()) {
            switch (key) {
                case "accept_unverified_until", "trusted_proxies" -> {
                }
                default -> throw new ConfigException(
                        "network: unknown key `" + key
                                + "` (only `accept_unverified_until` and `trusted_proxies` are accepted)");
            }
        }
    }

    /// `logs:` — 保留窗口。只能比 [LogRetention#HARD_CAP_DAYS] 短；越界直接拒绝，
    /// 免得一个手滑的 `3650` 悄悄把「最多三年」变成永久保留。
    private static void applyLogs(ServerConfig config, Map<String, Object> section) {
        if (section.containsKey("audit_days")) {
            config.logs.auditDays = retentionDays(section, "audit_days");
        }
        if (section.containsKey("panel_days")) {
            config.logs.panelDays = retentionDays(section, "panel_days");
        }
        for (String key : section.keySet()) {
            switch (key) {
                case "audit_days", "panel_days" -> {
                }
                default -> throw new ConfigException(
                        "logs: unknown key `" + key + "` (only `audit_days` and `panel_days` are accepted)");
            }
        }
    }

    private static int retentionDays(Map<String, Object> section, String key) {
        int days = intField(section, key);
        int floor = org.jackhuang.hmcl.web.logs.LogRetention.FLOOR_DAYS;
        int cap = org.jackhuang.hmcl.web.logs.LogRetention.HARD_CAP_DAYS;
        if (days < floor || days > cap) {
            throw new ConfigException("logs." + key + " must be between " + floor
                    + " and " + cap + " days, got " + days);
        }
        return days;
    }

    private static void applyWebShell(ServerConfig config, Map<String, Object> webShell) {
        if (webShell.containsKey("enabled")) {
            config.webShell.enabled = booleanField(webShell, "enabled");
        }
    }

    private static void applyDeveloperGuard(ServerConfig config, Map<String, Object> section) {
        if (section.containsKey("scope")) {
            config.developerGuard.scope = guardScope(stringField(section, "scope"), "developer_guard.scope");
        }
        for (String key : section.keySet()) {
            if (!key.equals("scope")) {
                throw new ConfigException("developer_guard: unknown key `" + key + "` (only `scope` is accepted)");
            }
        }
    }

    /// `agent:` — how far a ticket reaches (docs/panel-agent.md).
    private static void applyAgent(ServerConfig config, Map<String, Object> section) {
        if (section.containsKey("loopback_only")) {
            config.agent.loopbackOnly = booleanField(section, "loopback_only");
        }
        if (section.containsKey("remote_needs_session")) {
            config.agent.remoteNeedsSession = booleanField(section, "remote_needs_session");
        }
        for (String key : section.keySet()) {
            if (!key.equals("loopback_only") && !key.equals("remote_needs_session")) {
                throw new ConfigException(
                        "agent: unknown key `" + key + "` (only `loopback_only` and `remote_needs_session` are accepted)");
            }
        }
    }

    private static DeveloperGuardScope guardScope(String value, String label) {
        DeveloperGuardScope scope = DeveloperGuardScope.parse(value);
        if (scope == null) {
            throw new ConfigException(label + " must be `off`, `proxy`, `instance` or `all`, got `" + value + "`");
        }
        return scope;
    }

    /// `mc:` in `server.yaml`: the bounds of what the Minecraft surface may
    /// create. The feature switch itself is not here — it is a runtime decision
    /// the owner makes in the panel, not a deployment key.
    private static void applyMc(ServerConfig config, Map<String, Object> section) {
        if (section.containsKey("port_start")) {
            config.mc.portStart = intField(section, "port_start");
        }
        if (section.containsKey("port_end")) {
            config.mc.portEnd = intField(section, "port_end");
        }
        if (section.containsKey("max_instances")) {
            config.mc.maxInstances = intField(section, "max_instances");
        }
        if (section.containsKey("max_memory_mb")) {
            config.mc.maxMemoryMb = intField(section, "max_memory_mb");
        }
        if (section.containsKey("java_path")) {
            config.mc.javaPath = stringField(section, "java_path");
        }
        for (String key : section.keySet()) {
            switch (key) {
                case "port_start", "port_end", "max_instances", "max_memory_mb", "java_path" -> {
                }
                default -> throw new ConfigException("mc: unknown key `" + key + "`");
            }
        }
    }

    /// `transcode:` in `server.yaml`: deployment-controlled limits. The feature
    /// switch is not here (it is the runtime `feature.json`); a copied config must
    /// not turn transcoding on by itself.
    private static void applyTranscode(ServerConfig config, Map<String, Object> section) {
        ServerConfig.TranscodeConfig target = config.transcode;
        if (section.containsKey("media_root")) {
            target.mediaRoot = stringField(section, "media_root");
        }
        if (section.containsKey("ffmpeg_path")) {
            target.ffmpegPath = stringField(section, "ffmpeg_path");
        }
        if (section.containsKey("max_concurrent")) {
            target.maxConcurrent = intField(section, "max_concurrent");
            if (target.maxConcurrent < 1 || target.maxConcurrent > 8) {
                throw new ConfigException("transcode.max_concurrent must be between 1 and 8");
            }
        }
        if (section.containsKey("max_queue")) {
            target.maxQueue = intField(section, "max_queue");
            if (target.maxQueue < 1 || target.maxQueue > 1000) {
                throw new ConfigException("transcode.max_queue must be between 1 and 1000");
            }
        }
        if (section.containsKey("failure_policy")) {
            target.failurePolicy = failurePolicy(stringField(section, "failure_policy"), "transcode.failure_policy");
        }
        if (section.containsKey("failure_timeout_minutes")) {
            target.failureTimeoutMinutes = intField(section, "failure_timeout_minutes");
            if (target.failureTimeoutMinutes < 1 || target.failureTimeoutMinutes > 1440) {
                throw new ConfigException("transcode.failure_timeout_minutes must be between 1 and 1440");
            }
        }
        if (section.containsKey("auto_resume")) {
            target.autoResume = booleanField(section, "auto_resume");
        }
        for (String key : section.keySet()) {
            switch (key) {
                case "media_root", "ffmpeg_path", "max_concurrent", "max_queue",
                        "failure_policy", "failure_timeout_minutes", "auto_resume" -> {
                }
                default -> throw new ConfigException("transcode: unknown key `" + key + "`");
            }
        }
    }

    private static String failurePolicy(@Nullable String value, String label) {
        if (value == null) {
            throw new ConfigException(label + " is required");
        }
        return switch (value) {
            case "ask", "timeout-auto", "full-auto" -> value;
            default -> throw new ConfigException(label
                    + " must be `ask`, `timeout-auto` or `full-auto`, got `" + value + "`");
        };
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
        if (https.containsKey("redirect_same_port")) {
            target.redirectSamePort = booleanField(https, "redirect_same_port");
        }
        if (https.containsKey("redirect_port")) {
            target.redirectPort = intField(https, "redirect_port");
        }
        if (https.containsKey("trust_forwarded_proto")) {
            target.trustForwardedProto = booleanField(https, "trust_forwarded_proto");
        }
        if (https.containsKey("acme")) {
            applyAcme(target.acme, mapField(https, "acme"));
        }
    }

    private static void applyAcme(ServerConfig.AcmeConfig target, Map<String, Object> acme) {
        if (acme.containsKey("enabled")) {
            target.enabled = booleanField(acme, "enabled");
        }
        if (acme.containsKey("ca")) {
            target.ca = stringField(acme, "ca");
        }
        if (acme.containsKey("directory_url")) {
            target.directoryUrl = stringField(acme, "directory_url");
        }
        if (acme.containsKey("email")) {
            target.email = stringField(acme, "email");
        }
        if (acme.containsKey("domains")) {
            Object value = acme.get("domains");
            if (!(value instanceof java.util.List<?> list)) {
                throw new ConfigException("https.acme.domains must be a list of DNS names");
            }
            target.domains.clear();
            for (Object item : list) {
                if (!(item instanceof String domain)) {
                    throw new ConfigException("https.acme.domains must contain only strings");
                }
                target.domains.add(domain);
            }
        }
        if (acme.containsKey("provider")) {
            target.provider = stringField(acme, "provider");
        }
        if (acme.containsKey("credential_id")) {
            target.credentialId = stringField(acme, "credential_id");
        }
        if (acme.containsKey("renew_before_days")) {
            target.renewBeforeDays = intField(acme, "renew_before_days");
        }
        if (acme.containsKey("use_staging")) {
            target.useStaging = booleanField(acme, "use_staging");
        }
        if (acme.containsKey("accept_terms")) {
            target.acceptTerms = booleanField(acme, "accept_terms");
        }
        for (String key : acme.keySet()) {
            switch (key) {
                case "enabled", "ca", "directory_url", "email", "domains",
                        "provider", "credential_id", "renew_before_days", "use_staging", "accept_terms" -> {
                }
                default -> throw new ConfigException("https.acme: unknown key `" + key + "`");
            }
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
        if (env.containsKey(ENV_BASE_PATH)) {
            config.basePath = env.get(ENV_BASE_PATH);
        }
        if (env.containsKey(ENV_HTTPS)) {
            String value = env.get(ENV_HTTPS);
            config.https.enabled = switch (value) {
                case "true" -> true;
                case "false" -> false;
                default -> throw new ConfigException(ENV_HTTPS + " must be `true` or `false`, got `" + value + "`");
            };
        }
        if (env.containsKey(ENV_TRUST_FORWARDED_PROTO)) {
            String value = env.get(ENV_TRUST_FORWARDED_PROTO);
            config.https.trustForwardedProto = switch (value) {
                case "true" -> true;
                case "false" -> false;
                default -> throw new ConfigException(ENV_TRUST_FORWARDED_PROTO
                        + " must be `true` or `false`, got `" + value + "`");
            };
        }
        if (env.containsKey(ENV_TRUSTED_PROXIES)) {
            config.network.trustedProxies = proxyAddresses(env.get(ENV_TRUSTED_PROXIES));
        }
        if (env.containsKey(ENV_WEB_SHELL_ENABLED)) {
            String value = env.get(ENV_WEB_SHELL_ENABLED);
            config.webShell.enabled = switch (value) {
                case "true" -> true;
                case "false" -> false;
                default -> throw new ConfigException(ENV_WEB_SHELL_ENABLED
                        + " must be `true` or `false`, got `" + value + "`");
            };
        }
        if (env.containsKey(ENV_DEVELOPER_GUARD)) {
            config.developerGuard.scope = guardScope(env.get(ENV_DEVELOPER_GUARD), ENV_DEVELOPER_GUARD);
        }
        if (env.containsKey(ENV_AGENT_LOOPBACK_ONLY)) {
            String value = env.get(ENV_AGENT_LOOPBACK_ONLY);
            config.agent.loopbackOnly = switch (value) {
                case "true" -> true;
                case "false" -> false;
                default -> throw new ConfigException(ENV_AGENT_LOOPBACK_ONLY
                        + " must be `true` or `false`, got `" + value + "`");
            };
        }
        if (env.containsKey(ENV_AGENT_REMOTE_NEEDS_SESSION)) {
            String value = env.get(ENV_AGENT_REMOTE_NEEDS_SESSION);
            config.agent.remoteNeedsSession = switch (value) {
                case "true" -> true;
                case "false" -> false;
                default -> throw new ConfigException(ENV_AGENT_REMOTE_NEEDS_SESSION
                        + " must be `true` or `false`, got `" + value + "`");
            };
        }
        if (env.containsKey(ENV_MC_PORT_START)) {
            config.mc.portStart = parseInt(env.get(ENV_MC_PORT_START), ENV_MC_PORT_START);
        }
        if (env.containsKey(ENV_MC_PORT_END)) {
            config.mc.portEnd = parseInt(env.get(ENV_MC_PORT_END), ENV_MC_PORT_END);
        }
        if (env.containsKey(ENV_MC_MAX_INSTANCES)) {
            config.mc.maxInstances = parseInt(env.get(ENV_MC_MAX_INSTANCES), ENV_MC_MAX_INSTANCES);
        }
        if (env.containsKey(ENV_MC_MAX_MEMORY_MB)) {
            config.mc.maxMemoryMb = parseInt(env.get(ENV_MC_MAX_MEMORY_MB), ENV_MC_MAX_MEMORY_MB);
        }
        if (env.containsKey(ENV_MC_JAVA_PATH)) {
            config.mc.javaPath = env.get(ENV_MC_JAVA_PATH);
        }
        if (env.containsKey(ENV_MEDIA_ROOT)) {
            config.transcode.mediaRoot = env.get(ENV_MEDIA_ROOT);
        }
        if (env.containsKey(ENV_FFMPEG_PATH)) {
            config.transcode.ffmpegPath = env.get(ENV_FFMPEG_PATH);
        }
        if (env.containsKey(ENV_TRANSCODE_MAX_CONCURRENT)) {
            config.transcode.maxConcurrent = parseInt(env.get(ENV_TRANSCODE_MAX_CONCURRENT),
                    ENV_TRANSCODE_MAX_CONCURRENT);
            if (config.transcode.maxConcurrent < 1 || config.transcode.maxConcurrent > 8) {
                throw new ConfigException(ENV_TRANSCODE_MAX_CONCURRENT + " must be between 1 and 8");
            }
        }
        if (env.containsKey(ENV_TRANSCODE_MAX_QUEUE)) {
            config.transcode.maxQueue = parseInt(env.get(ENV_TRANSCODE_MAX_QUEUE), ENV_TRANSCODE_MAX_QUEUE);
            if (config.transcode.maxQueue < 1 || config.transcode.maxQueue > 1000) {
                throw new ConfigException(ENV_TRANSCODE_MAX_QUEUE + " must be between 1 and 1000");
            }
        }
        if (env.containsKey(ENV_TRANSCODE_FAILURE_POLICY)) {
            config.transcode.failurePolicy = failurePolicy(env.get(ENV_TRANSCODE_FAILURE_POLICY),
                    ENV_TRANSCODE_FAILURE_POLICY);
        }
        if (env.containsKey(ENV_TRANSCODE_FAILURE_TIMEOUT_MINUTES)) {
            config.transcode.failureTimeoutMinutes = parseInt(env.get(ENV_TRANSCODE_FAILURE_TIMEOUT_MINUTES),
                    ENV_TRANSCODE_FAILURE_TIMEOUT_MINUTES);
            if (config.transcode.failureTimeoutMinutes < 1 || config.transcode.failureTimeoutMinutes > 1440) {
                throw new ConfigException(ENV_TRANSCODE_FAILURE_TIMEOUT_MINUTES
                        + " must be between 1 and 1440");
            }
        }
        if (env.containsKey(ENV_TRANSCODE_AUTO_RESUME)) {
            String value = env.get(ENV_TRANSCODE_AUTO_RESUME);
            config.transcode.autoResume = switch (value) {
                case "true" -> true;
                case "false" -> false;
                default -> throw new ConfigException(ENV_TRANSCODE_AUTO_RESUME
                        + " must be `true` or `false`, got `" + value + "`");
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
        // 归一化：根 = ""，子路径 = "/panel"（无尾斜杠）。不许带引号字符——
        // 这个值会被原样注进 index.html 的 <base href> 和 JS 字符串里。
        String base = config.basePath == null ? "" : config.basePath.trim();
        if (base.equals("/")) {
            base = "";
        }
        if (!base.isEmpty()) {
            if (!base.startsWith("/")) {
                base = "/" + base;
            }
            if (base.endsWith("/")) {
                base = base.substring(0, base.length() - 1);
            }
            if (base.contains("\\") || base.contains("\"") || base.contains("<") || base.contains("'")) {
                throw new ConfigException("base_path must not contain quotes, apostrophes, backslashes or angle brackets, got `"
                        + config.basePath + "`");
            }
        }
        config.basePath = base;
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
        validateAcme(config);
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

    /// ACME settings are validated even when disabled, so a typo in
    /// `renew_before_days` or a planned-but-unimplemented provider fails at
    /// boot instead of at the first renewal.
    private static void validateAcme(ServerConfig config) {
        ServerConfig.AcmeConfig acme = config.https.acme;
        if (acme.renewBeforeDays < 7 || acme.renewBeforeDays > 60) {
            throw new ConfigException("https.acme.renew_before_days must be between 7 and 60, got "
                    + acme.renewBeforeDays);
        }
        if (!acme.enabled) {
            return;
        }
        if (acme.email.isBlank() || !acme.email.contains("@")) {
            throw new ConfigException("https.acme.email is required when ACME is enabled");
        }
        if (!acme.acceptTerms) {
            throw new ConfigException("https.acme.accept_terms must be true before issuing a certificate");
        }
        AcmeCa.Info ca = AcmeCa.find(acme.ca);
        if (ca == null) {
            throw new ConfigException("https.acme.ca is unknown: `" + acme.ca + "`");
        }
        if (ca.custom() && acme.directoryUrl.isBlank()) {
            throw new ConfigException("https.acme.ca=custom requires https.acme.directory_url");
        }
        AcmeDnsProviderRegistry.Info provider = AcmeDnsProviderRegistry.find(acme.provider);
        if (provider == null) {
            throw new ConfigException("https.acme.provider is unknown: `" + acme.provider + "`");
        }
        if (!provider.implemented()) {
            throw new ConfigException("https.acme.provider `" + acme.provider
                    + "` is not implemented in this build yet");
        }

        Set<String> domains = new LinkedHashSet<>();
        for (String raw : acme.domains) {
            if (raw == null) {
                continue;
            }
            String domain = raw.trim().toLowerCase(java.util.Locale.ROOT);
            if (domain.endsWith(".")) {
                domain = domain.substring(0, domain.length() - 1);
            }
            if (!domain.isEmpty()) {
                domains.add(domain);
            }
        }
        if (domains.isEmpty()) {
            String host = hostOf(config.publicBaseUrl);
            if (host != null && !host.isBlank()) {
                domains.add(host.toLowerCase(java.util.Locale.ROOT));
            }
        }
        if (domains.isEmpty()) {
            throw new ConfigException("https.acme.domains is empty and public_base_url has no host; "
                    + "set at least one certificate name");
        }
        if (domains.size() > 20) {
            throw new ConfigException("https.acme.domains supports at most 20 names, got " + domains.size());
        }
        for (String domain : domains) {
            if (!isCertificateName(domain)) {
                throw new ConfigException("https.acme.domains contains an invalid DNS name: `" + domain + "`");
            }
        }
        acme.domains.clear();
        acme.domains.addAll(domains);
    }

    /// `example.com`, `www.example.com`, `*.example.com` and punycode names
    /// are accepted; IP literals and names without a dot are not.
    private static boolean isCertificateName(String name) {
        String base = name.startsWith("*.") ? name.substring(2) : name;
        if (base.isEmpty() || base.contains(":") || base.contains("/")) {
            return false;
        }
        if (!base.contains(".") || base.matches("\\d+(\\.\\d+)+")) {
            return false;
        }
        for (String label : base.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63
                    || !label.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")) {
                return false;
            }
        }
        return true;
    }

    private static @Nullable String hostOf(@Nullable String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(url.trim());
            return uri.getHost();
        } catch (IllegalArgumentException e) {
            return null;
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
