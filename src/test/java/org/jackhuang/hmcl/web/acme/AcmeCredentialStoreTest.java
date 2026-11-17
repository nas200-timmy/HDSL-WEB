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
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Owner-only storage for the DNS/EAB credentials. The values must survive a
/// restart, but never leak through the API or a log line.
class AcmeCredentialStoreTest {

    @TempDir
    Path dataDir;

    @Test
    void savesLoadsAndClears() throws Exception {
        AcmeCredentialStore store = AcmeCredentialStore.open(dataDir);
        assertFalse(store.configured());
        assertNull(store.provider());

        store.save("cloudflare", Map.of("apiToken", "secret-token", "zone", "example.com"),
                Map.of("kid", "kid-1", "hmacKey", "hmac-1"));
        assertTrue(store.configured());
        assertTrue(store.eabConfigured());
        assertEquals("cloudflare", store.provider());
        assertEquals("secret-token", store.dnsCredentials().get("apiToken"));

        AcmeCredentialStore reopened = AcmeCredentialStore.open(dataDir);
        assertTrue(reopened.configured());
        assertEquals("cloudflare", reopened.provider());
        assertEquals("hmac-1", reopened.eabCredentials().get("hmacKey"));

        reopened.clear();
        assertFalse(reopened.configured());
        assertFalse(reopened.eabConfigured());
        assertFalse(Files.exists(dataDir.resolve("auth").resolve(AcmeCredentialStore.FILE_NAME)));
    }

    @Test
    void fileIsOwnerOnly() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "POSIX permission views unavailable");
        AcmeCredentialStore store = AcmeCredentialStore.open(dataDir);
        store.save("cloudflare", Map.of("apiToken", "x"), Map.of());

        Path file = dataDir.resolve("auth").resolve(AcmeCredentialStore.FILE_NAME);
        PosixFileAttributes attrs = Files.readAttributes(file, PosixFileAttributes.class);
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                attrs.permissions());
    }

    @Test
    void malformedFileFailsLoudly() throws Exception {
        Path auth = Files.createDirectories(dataDir.resolve("auth"));
        Files.writeString(auth.resolve(AcmeCredentialStore.FILE_NAME), "{\"provider\":\"cloudflare\"}");
        assertThrows(java.io.IOException.class, () -> AcmeCredentialStore.open(dataDir));
    }

    @Test
    void missingProviderOrDnsIsRejected() throws Exception {
        AcmeCredentialStore store = AcmeCredentialStore.open(dataDir);
        assertThrows(IllegalArgumentException.class, () -> store.save("", Map.of("x", "y"), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> store.save("cloudflare", Map.of(), Map.of()));
    }
}
