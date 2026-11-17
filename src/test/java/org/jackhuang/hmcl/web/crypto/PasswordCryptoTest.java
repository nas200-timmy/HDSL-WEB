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
package org.jackhuang.hmcl.web.crypto;

import com.google.gson.JsonObject;
import org.bouncycastle.crypto.agreement.X25519Agreement;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PasswordCryptoTest {

    @Test
    void decryptsTheBrowserEnvelope() throws Exception {
        PasswordCrypto crypto = new PasswordCrypto();
        JsonObject descriptor = new JsonObject();
        crypto.writeDescriptor(descriptor);
        String keyId = descriptor.get("keyId").getAsString();
        byte[] serverPublic = Base64.getUrlDecoder().decode(descriptor.get("publicKey").getAsString());

        X25519PrivateKeyParameters clientPrivate = new X25519PrivateKeyParameters(new SecureRandom());
        byte[] clientPublic = clientPrivate.generatePublicKey().getEncoded();
        X25519Agreement agreement = new X25519Agreement();
        agreement.init(clientPrivate);
        byte[] shared = new byte[32];
        agreement.calculateAgreement(new X25519PublicKeyParameters(serverPublic, 0), shared, 0);

        HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA256Digest());
        hkdf.init(new HKDFParameters(shared, keyId.getBytes(StandardCharsets.UTF_8),
                PasswordCrypto.HKDF_INFO.getBytes(StandardCharsets.UTF_8)));
        byte[] key = new byte[32];
        hkdf.generateBytes(key, 0, key.length);

        byte[] nonce = new byte[12];
        new SecureRandom().nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("ChaCha20-Poly1305");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "ChaCha20"), new IvParameterSpec(nonce));
        cipher.updateAAD(PasswordCrypto.HKDF_INFO.getBytes(StandardCharsets.UTF_8));
        byte[] ciphertext = cipher.doFinal("correct horse battery staple".getBytes(StandardCharsets.UTF_8));

        JsonObject envelope = new JsonObject();
        envelope.addProperty("v", "1");
        envelope.addProperty("keyId", keyId);
        envelope.addProperty("clientPublicKey", b64(clientPublic));
        envelope.addProperty("nonce", b64(nonce));
        envelope.addProperty("ciphertext", b64(ciphertext));
        assertEquals("correct horse battery staple", crypto.decode(envelope));
    }

    @Test
    void plaintextAndTampering() {
        PasswordCrypto crypto = new PasswordCrypto();
        assertEquals("plain", crypto.decode(new com.google.gson.JsonPrimitive("plain")));
        JsonObject wrong = new JsonObject();
        wrong.addProperty("v", "1");
        wrong.addProperty("keyId", "wrong");
        wrong.addProperty("clientPublicKey", b64(new byte[32]));
        wrong.addProperty("nonce", b64(new byte[12]));
        wrong.addProperty("ciphertext", b64(new byte[16]));
        assertThrows(IllegalArgumentException.class, () -> crypto.decode(wrong));
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
