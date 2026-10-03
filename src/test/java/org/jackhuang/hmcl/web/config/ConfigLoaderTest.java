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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Pins the declared configuration contract: defaults when nothing is
/// configured, every yaml field when it is, environment precedence over the
/// file, and loud failure (with the line number) on a broken file.
class ConfigLoaderTest {

    @TempDir
    Path dataDir;

    private ServerConfig load(Map<String, String> env) {
        return ServerConfigLoader.load(dataDir, env, Logger.LOG);
    }

    @Test
    void defaultsWhenNothingIsConfigured() {
        ServerConfig config = load(Map.of());

        assertEquals(Path.of("./data").toAbsolutePath().normalize().toString(),
                ServerConfigLoader.load(Map.of(), Logger.LOG).dataDir.toString(),
                "HDSL_DATA defaults to ./data");
        assertEquals("0.0.0.0", config.bindHost);
        assertEquals(3080, config.port);
        assertFalse(config.https.enabled);
        assertFalse(config.https.redirectHttp);
        assertEquals(80, config.https.redirectPort);
        assertTrue(config.https.certFile.isBlank() && config.https.keyFile.isBlank() && config.https.pkcs12File.isBlank());
        assertEquals("", config.publicBaseUrl);
        assertFalse(config.auth.disabled);
        assertEquals(168, config.auth.sessionTtlHours);
    }

    @Test
    void everyYamlFieldIsRead() throws Exception {
        Files.writeString(dataDir.resolve("server.yaml"), """
                bind_host: 127.0.0.1
                port: 12345
                public_base_url: https://dsh.example.com
                https:
                  enabled: true
                  cert_file: certs/fullchain.pem
                  key_file: certs/privkey.pem
                  pkcs12_file: certs/server.p12
                  pkcs12_password: "secret"
                  redirect_http: true
                  redirect_port: 8080
                auth:
                  disabled: false
                  session_ttl_hours: 24
                """);

        ServerConfig config = load(Map.of());

        assertEquals("127.0.0.1", config.bindHost);
        assertEquals(12345, config.port);
        assertEquals("https://dsh.example.com", config.publicBaseUrl);
        assertTrue(config.https.enabled);
        assertEquals("certs/fullchain.pem", config.https.certFile);
        assertEquals("certs/privkey.pem", config.https.keyFile);
        assertEquals("certs/server.p12", config.https.pkcs12File);
        assertEquals("secret", config.https.pkcs12Password);
        assertTrue(config.https.redirectHttp);
        assertEquals(8080, config.https.redirectPort);
        assertFalse(config.auth.disabled);
        assertEquals(24, config.auth.sessionTtlHours);
    }

    @Test
    void environmentBeatsTheFile() throws Exception {
        Files.writeString(dataDir.resolve("server.yaml"), """
                bind_host: 0.0.0.0
                port: 4000
                https:
                  enabled: false
                """);

        ServerConfig config = load(Map.of(
                ServerConfigLoader.ENV_PORT, "9999",
                ServerConfigLoader.ENV_BIND_HOST, "127.0.0.1",
                ServerConfigLoader.ENV_HTTPS, "true"));

        assertEquals(9999, config.port);
        assertEquals("127.0.0.1", config.bindHost);
        assertTrue(config.https.enabled);
    }

    @Test
    void hdslHttpsRejectsAnythingButTrueOrFalse() {
        ServerConfigLoader.ConfigException e = assertThrows(ServerConfigLoader.ConfigException.class,
                () -> load(Map.of(ServerConfigLoader.ENV_HTTPS, "yes")));
        assertTrue(e.getMessage().contains("HDSL_HTTPS"), e.getMessage());
    }

    @Test
    void brokenYamlReportsTheLine() throws Exception {
        Files.writeString(dataDir.resolve("server.yaml"), """
                bind_host: 0.0.0.0
                port: [1, 2
                """);

        ServerConfigLoader.ConfigException e = assertThrows(ServerConfigLoader.ConfigException.class, () -> load(Map.of()));
        assertTrue(e.getMessage().contains("line 2"), "the message must name the line: " + e.getMessage());
    }

    @Test
    void wrongYamlTypeNamesTheField() throws Exception {
        Files.writeString(dataDir.resolve("server.yaml"), """
                port: not-a-number
                """);

        ServerConfigLoader.ConfigException e = assertThrows(ServerConfigLoader.ConfigException.class, () -> load(Map.of()));
        assertTrue(e.getMessage().contains("port"), e.getMessage());
    }

    @Test
    void authDisabledNeedsALoopbackBind() throws Exception {
        Files.writeString(dataDir.resolve("server.yaml"), """
                bind_host: 0.0.0.0
                auth:
                  disabled: true
                """);

        ServerConfigLoader.ConfigException e = assertThrows(ServerConfigLoader.ConfigException.class, () -> load(Map.of()));
        assertTrue(e.getMessage().contains("auth.disabled"), e.getMessage());
    }

    @Test
    void authDisabledIsAcceptedOnLoopback() throws Exception {
        Files.writeString(dataDir.resolve("server.yaml"), """
                bind_host: 127.0.0.1
                auth:
                  disabled: true
                """);

        ServerConfig config = load(Map.of());
        assertTrue(config.auth.disabled);
    }

    @Test
    void redirectHttpRequiresHttps() throws Exception {
        Files.writeString(dataDir.resolve("server.yaml"), """
                https:
                  redirect_http: true
                """);

        ServerConfigLoader.ConfigException e = assertThrows(ServerConfigLoader.ConfigException.class, () -> load(Map.of()));
        assertTrue(e.getMessage().contains("redirect_http"), e.getMessage());
    }

    @Test
    void halfConfiguredPemIsAnError() throws Exception {
        Files.writeString(dataDir.resolve("server.yaml"), """
                https:
                  enabled: true
                  cert_file: certs/fullchain.pem
                """);

        ServerConfigLoader.ConfigException e = assertThrows(ServerConfigLoader.ConfigException.class, () -> load(Map.of()));
        assertTrue(e.getMessage().contains("cert_file"), e.getMessage());
    }
}
