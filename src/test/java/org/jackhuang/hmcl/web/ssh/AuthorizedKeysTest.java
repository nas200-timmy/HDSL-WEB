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
package org.jackhuang.hmcl.web.ssh;

import org.apache.sshd.common.util.buffer.ByteArrayBuffer;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.jackhuang.hmcl.util.PosixPermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// The authorized-keys file: what [AuthorizedKeys.add] accepts and refuses,
/// list/remove semantics, and the contains() predicate the publickey
/// authenticator runs on every login.
class AuthorizedKeysTest {

    @TempDir
    Path dataDir;

    private Path keyFile() {
        return dataDir.resolve(AuthorizedKeys.DIR_NAME).resolve(AuthorizedKeys.FILE_NAME);
    }

    /// One syntactically valid line: the blob is any decodable bytes here —
    /// add() validates shape, not key material.
    private static String line(String algorithm, byte[] blob, String comment) {
        return algorithm + " " + Base64.getEncoder().encodeToString(blob) + " " + comment;
    }

    private static byte[] bytes(int count, int seed) {
        byte[] blob = new byte[count];
        for (int i = 0; i < count; i++) {
            blob[i] = (byte) (seed + i);
        }
        return blob;
    }

    @Test
    void aMissingFileIsAnEmptyList() throws Exception {
        AuthorizedKeys keys = AuthorizedKeys.open(dataDir);
        assertTrue(keys.list().isEmpty());
        // sshd only wire-encodes i2p EdDSA keys; a bare JCA "Ed25519" lookup
        // yields a JDK-native EdEC key it cannot encode.
        assertFalse(keys.contains(SecurityUtils.getKeyPairGenerator("EdDSA").generateKeyPair().getPublic()));
    }

    @Test
    void acceptedAlgorithmsLandAndList() throws Exception {
        AuthorizedKeys keys = AuthorizedKeys.open(dataDir);
        keys.add(line("ssh-ed25519", bytes(32, 1), "laptop"));
        keys.add(line("ssh-rsa", bytes(256, 2), "desktop"));
        keys.add(line("ecdsa-sha2-nistp384", bytes(96, 3), "phone"));

        List<AuthorizedKeys.Entry> entries = keys.list();
        assertEquals(3, entries.size());
        assertEquals(1, entries.get(0).index());
        assertEquals("ssh-ed25519", entries.get(0).algorithm());
        assertEquals("laptop", entries.get(0).comment());
        assertEquals("ssh-rsa", entries.get(1).algorithm());
        assertEquals("desktop", entries.get(1).comment());
        assertTrue(entries.get(0).fingerprintSHA256().startsWith("SHA256:"),
                "fingerprints are OpenSSH SHA256: " + entries.get(0).fingerprintSHA256());

        List<String> raw = Files.readAllLines(keyFile());
        assertEquals(3, raw.size());
    }

    @Test
    void theFileIsOwnerOnlyWherePosixExists() throws Exception {
        AuthorizedKeys keys = AuthorizedKeys.open(dataDir);
        keys.add(line("ssh-ed25519", bytes(32, 4), "laptop"));

        assumeTrue(PosixPermissions.of(keyFile()).isPresent(), "POSIX mode bits exist on this platform");
        PosixPermissions.assertOwnerOnly(keyFile(), "authorized_keys must be 0600");
    }

    @Test
    void malformedLinesAreRefusedBeforeTheFileIsTouched() throws Exception {
        AuthorizedKeys keys = AuthorizedKeys.open(dataDir);
        assertThrows(IllegalArgumentException.class,
                () -> keys.add(line("ssh-ed25519", bytes(32, 5), "ok") + "\n" + line("ssh-ed25519", bytes(32, 6), "evil")),
                "one line means one line");
        assertThrows(IllegalArgumentException.class,
                () -> keys.add(line("ssh-dss", bytes(64, 7), "legacy")), "unknown algorithms are refused");
        assertThrows(IllegalArgumentException.class,
                () -> keys.add("ssh-ed25519 !!!not-base64!!! broken"), "undecodable blobs are refused");
        assertThrows(IllegalArgumentException.class, () -> keys.add("ssh-ed25519"), "a bare algorithm is not a key");
        assertThrows(IllegalArgumentException.class, () -> keys.add("not a key at all"));
        assertFalse(Files.exists(keyFile()), "nothing valid was added, so nothing may exist");
    }

    @Test
    void duplicateBlobsAreRefused() throws Exception {
        AuthorizedKeys keys = AuthorizedKeys.open(dataDir);
        keys.add(line("ssh-ed25519", bytes(32, 8), "first"));
        assertThrows(IllegalArgumentException.class,
                () -> keys.add(line("ssh-ed25519", bytes(32, 8), "same key, other comment")),
                "the blob is the identity; a second copy is a duplicate");
        assertEquals(1, keys.list().size());
    }

    @Test
    void removeDeletesTheLineAtItsIndex() throws Exception {
        AuthorizedKeys keys = AuthorizedKeys.open(dataDir);
        keys.add(line("ssh-ed25519", bytes(32, 9), "one"));
        keys.add(line("ssh-ed25519", bytes(32, 10), "two"));
        keys.add(line("ssh-ed25519", bytes(32, 11), "three"));

        keys.remove(2);
        List<AuthorizedKeys.Entry> remaining = keys.list();
        assertEquals(2, remaining.size());
        assertEquals("one", remaining.get(0).comment());
        assertEquals("three", remaining.get(1).comment());

        assertThrows(IllegalArgumentException.class, () -> keys.remove(99), "no such line");
        assertThrows(IllegalArgumentException.class, () -> keys.remove(0), "indexes are 1-based");
    }

    @Test
    void containsMatchesWireEncodedKeys() throws Exception {
        KeyPairGenerator generator = SecurityUtils.getKeyPairGenerator("EdDSA");
        KeyPair authorized = generator.generateKeyPair();
        KeyPair stranger = generator.generateKeyPair();

        ByteArrayBuffer wire = new ByteArrayBuffer();
        wire.putPublicKey(authorized.getPublic());
        String publicLine = "ssh-ed25519 " + Base64.getEncoder().encodeToString(wire.getCompactData()) + " laptop";

        AuthorizedKeys keys = AuthorizedKeys.open(dataDir);
        keys.add(publicLine);
        assertTrue(keys.contains(authorized.getPublic()), "the authorized key passes");
        assertFalse(keys.contains(stranger.getPublic()), "any other key fails");
    }
}
