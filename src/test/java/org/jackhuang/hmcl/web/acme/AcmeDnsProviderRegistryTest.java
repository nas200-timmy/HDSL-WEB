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

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcmeDnsProviderRegistryTest {

    @Test
    void tierOneProvidersAreImplemented() {
        assertTrue(AcmeDnsProviderRegistry.find("cloudflare").implemented());
        assertTrue(AcmeDnsProviderRegistry.find("aliyun").implemented());
        assertTrue(AcmeDnsProviderRegistry.find("dnspod").implemented());
        assertTrue(AcmeDnsProviderRegistry.find("tencent").implemented());
        assertFalse(AcmeDnsProviderRegistry.find("huawei").implemented());
    }

    @Test
    void createRejectsUnknownAndPlannedProviders() {
        assertThrows(AcmeDnsException.class,
                () -> AcmeDnsProviderRegistry.create("nope", Map.of()));
        assertThrows(AcmeDnsException.class,
                () -> AcmeDnsProviderRegistry.create("huawei", Map.of("accessKeyId", "a")));
    }

    @Test
    void requiredFieldsAreCheckedBeforeConstruction() {
        assertThrows(AcmeDnsException.class,
                () -> AcmeDnsProviderRegistry.create("cloudflare", Map.of()));
        assertThrows(AcmeDnsException.class,
                () -> AcmeDnsProviderRegistry.create("aliyun", Map.of("accessKeyId", "a")));
    }

    @Test
    void tencentIsConstructedFromItsApiKeys() throws Exception {
        AcmeDnsProvider provider = AcmeDnsProviderRegistry.create("tencent",
                Map.of("secretId", "sid", "secretKey", "skey", "zone", "example.com"));
        assertEquals("tencent", provider.id());
    }

    @Test
    void cloudflareIsConstructedFromItsCredentials() throws Exception {
        AcmeDnsProvider provider = AcmeDnsProviderRegistry.create("cloudflare",
                Map.of("apiToken", "token", "zone", "example.com"));
        assertNotNull(provider);
        assertEquals("cloudflare", provider.id());
    }
}
