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
package org.jackhuang.hmcl.web.acme;

import org.jackhuang.hmcl.util.logging.Logger;
import org.jackhuang.hmcl.web.config.ServerConfig;
import org.jackhuang.hmcl.web.config.ServerConfigLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Pins the non-secret ACME configuration contract. DNS tokens and EAB keys
/// are deliberately not part of this file.
class AcmeConfigTest {

    @TempDir
    Path dataDir;

    private ServerConfig load(Map<String, String> env) {
        return ServerConfigLoader.load(dataDir, env, Logger.LOG);
    }

    @Test
    void defaultsAreNonDestructive() {
        ServerConfig config = load(Map.of());
        ServerConfig.AcmeConfig acme = config.https.acme;
        assertEquals(false, acme.enabled);
        assertEquals("letsencrypt", acme.ca);
        assertEquals("cloudflare", acme.provider);
        assertEquals(30, acme.renewBeforeDays);
        assertEquals(false, acme.useStaging);
        assertEquals(false, acme.acceptTerms);
        assertTrue(acme.domains.isEmpty());
    }

    @Test
    void yamlFieldsAndWildcardAreRead() throws Exception {
        Files.writeString(dataDir.resolve("server.yaml"), """
                public_base_url: https://example.com
                https:
                  acme:
                    enabled: true
                    ca: custom
                    directory_url: https://acme.example.com/directory
                    email: ops@example.com
                    domains:
                      - example.com
                      - "*.example.com"
                    provider: dnspod
                    renew_before_days: 21
                    use_staging: true
                    accept_terms: true
                """);
        ServerConfig.AcmeConfig acme = load(Map.of()).https.acme;
        assertTrue(acme.enabled);
        assertEquals("custom", acme.ca);
        assertEquals("https://acme.example.com/directory", acme.directoryUrl);
        assertEquals("dnspod", acme.provider);
        assertEquals(21, acme.renewBeforeDays);
        assertTrue(acme.useStaging);
        assertTrue(acme.acceptTerms);
        assertEquals(2, acme.domains.size());
        assertTrue(acme.domains.contains("*.example.com"));
    }

    @Test
    void emptyDomainsFallBackToPublicBaseUrlHost() throws Exception {
        Files.writeString(dataDir.resolve("server.yaml"), """
                public_base_url: https://panel.example.com
                https:
                  acme:
                    enabled: true
                    email: ops@example.com
                    accept_terms: true
                """);
        ServerConfig.AcmeConfig acme = load(Map.of()).https.acme;
        assertEquals(java.util.List.of("panel.example.com"), acme.domains);
    }

    @Test
    void invalidDomainIsRejected() throws Exception {
        Files.writeString(dataDir.resolve("server.yaml"), """
                https:
                  acme:
                    enabled: true
                    email: ops@example.com
                    accept_terms: true
                    domains: ["192.168.1.10"]
                """);
        assertThrows(ServerConfigLoader.ConfigException.class, () -> load(Map.of()));
    }

    @Test
    void customCaNeedsDirectoryUrl() throws Exception {
        Files.writeString(dataDir.resolve("server.yaml"), """
                https:
                  acme:
                    enabled: true
                    ca: custom
                    email: ops@example.com
                    accept_terms: true
                    domains: [example.com]
                """);
        assertThrows(ServerConfigLoader.ConfigException.class, () -> load(Map.of()));
    }

    @Test
    void plannedProviderIsRejectedUntilImplemented() throws Exception {
        Files.writeString(dataDir.resolve("server.yaml"), """
                https:
                  acme:
                    enabled: true
                    email: ops@example.com
                    accept_terms: true
                    domains: [example.com]
                    provider: huawei
                """);
        assertThrows(ServerConfigLoader.ConfigException.class, () -> load(Map.of()));
    }

    @Test
    void renewWindowIsBoundedEvenWhenDisabled() throws Exception {
        Files.writeString(dataDir.resolve("server.yaml"), """
                https:
                  acme:
                    renew_before_days: 3
                """);
        assertThrows(ServerConfigLoader.ConfigException.class, () -> load(Map.of()));
    }
}
