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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CertificateStoreTest {

    @TempDir
    Path dataDir;

    @Test
    void entriesSurviveAReopen() throws Exception {
        CertificateStore store = CertificateStore.open(dataDir);
        CertificateStore.Entry entry = store.create("example.com", List.of("example.com", "www.example.com"));
        store.update(entry.id, target -> {
            target.email = "ops@example.com";
            target.renewBeforeDays = 30;
        });

        CertificateStore reopened = CertificateStore.open(dataDir);
        CertificateStore.Entry loaded = reopened.find(entry.id);
        assertNotNull(loaded);
        assertEquals("example.com", loaded.apex);
        assertEquals(List.of("example.com", "www.example.com"), loaded.domains);
        assertEquals("ops@example.com", loaded.email);
        assertEquals(java.util.Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(dataDir.resolve("certs").resolve(CertificateStore.FILE_NAME)));
    }

    @Test
    void onlyOneEntryIsActive() throws Exception {
        CertificateStore store = CertificateStore.open(dataDir);
        CertificateStore.Entry first = store.create("example.com", List.of("example.com"));
        CertificateStore.Entry second = store.create("example.com", List.of("example.com"));

        store.setActive(first.id);
        store.setActive(second.id);

        assertFalse(store.find(first.id).active);
        assertTrue(store.find(second.id).active);
        assertEquals(second.id, store.active().id);
    }

    @Test
    void everyApexHasItsOwnDefault() throws Exception {
        CertificateStore store = CertificateStore.open(dataDir);
        CertificateStore.Entry first = store.create("example.com", List.of("example.com"));
        CertificateStore.Entry other = store.create("example.net", List.of("example.net"));

        store.setActive(first.id);
        store.setActive(other.id);

        assertTrue(store.find(first.id).active, "a different apex must not clear this default");
        assertTrue(store.find(other.id).active);
        assertEquals(2, store.activeEntries().size());
    }

    @Test
    void migrationMarkerKeepsAnEmptyRegistryEmpty() throws Exception {
        CertificateStore store = CertificateStore.open(dataDir);
        assertFalse(store.isMigrated());
        store.markMigrated();

        CertificateStore reopened = CertificateStore.open(dataDir);
        assertTrue(reopened.isMigrated());
        assertTrue(reopened.isEmpty());
    }

    @Test
    void pendingTracksDesiredVersusIssuedNames() throws Exception {
        CertificateStore store = CertificateStore.open(dataDir);
        CertificateStore.Entry entry = store.create("example.com", List.of("example.com"));

        assertTrue(CertificateService.pending(entry), "never issued yet");

        store.recordIssued(entry.id, List.of("example.com"), null, null);
        assertFalse(CertificateService.pending(store.find(entry.id)));

        store.update(entry.id, target -> target.domains = List.of("example.com", "www.example.com"));
        assertTrue(CertificateService.pending(store.find(entry.id)), "adding a name needs a reissue");
    }

    @Test
    void domainsMustBelongToTheApex() {
        assertNull(CertificateService.validateDomains("example.com",
                List.of("example.com", "www.example.com", "*.example.com")));
        assertNotNull(CertificateService.validateDomains("example.com", List.of("example.net")));
        assertNotNull(CertificateService.validateDomains("example.com", List.of("*.*.example.com")));
        assertNull(CertificateService.validateDomains("example.com", List.of("a.b.example.com")));
    }
}
