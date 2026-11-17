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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bouncycastle.crypto.agreement.X25519Agreement;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/// One-shot password-field encryption for HTTP deployments.
///
/// The browser generates an ephemeral X25519 key, agrees with the process-wide
/// key returned by `GET /api/crypto/password-key`, derives a 32-byte key with
/// HKDF-SHA256, and encrypts the password with ChaCha20-Poly1305. This is not a
/// transport channel: it protects password fields against passive sniffing,
/// not against an active MITM that can replace the JS/public key.
///
/// Server keys live in memory and rotate on every start. A keyId lets the
/// browser retry after a restart; old envelopes are refused rather than
/// silently decrypted with a new key.
@NotNullByDefault
public final class PasswordCrypto {

    public static final String VERSION = "1";
    public static final String HKDF_INFO = "hdsl-web-password-v1";
    private static final byte[] AAD = HKDF_INFO.getBytes(StandardCharsets.UTF_8);
    private static final int NONCE_BYTES = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String keyId;
    private final X25519PrivateKeyParameters privateKey;
    private final byte[] publicKey;

    public PasswordCrypto() {
        this.privateKey = new X25519PrivateKeyParameters(RANDOM);
        this.publicKey = privateKey.generatePublicKey().getEncoded();
        byte[] id = new byte[8];
        RANDOM.nextBytes(id);
        this.keyId = Base64.getUrlEncoder().withoutPadding().encodeToString(id);
    }

    public String keyId() {
        return keyId;
    }

    /// The public descriptor for the browser. Contains no secret.
    public void writeDescriptor(JsonObject target) {
        target.addProperty("v", VERSION);
        target.addProperty("keyId", keyId);
        target.addProperty("publicKey", base64(publicKey));
    }

    /// Decodes a password field. A plain string is accepted for curl and for
    /// clients that have not adopted the envelope; an object must be a valid
    /// v1 envelope.
    ///
    /// @throws IllegalArgumentException when the envelope is malformed,
    ///                                  references another key, or fails AEAD
    public String decode(@Nullable JsonElement field) {
        if (field == null || field.isJsonNull()) {
            throw new IllegalArgumentException("password is required");
        }
        if (field.isJsonPrimitive()) {
            return field.getAsString();
        }
        if (!field.isJsonObject()) {
            throw new IllegalArgumentException("password must be a string or an encrypted object");
        }
        JsonObject envelope = field.getAsJsonObject();
        if (!VERSION.equals(string(envelope, "v"))) {
            throw new IllegalArgumentException("unsupported password envelope version");
        }
        if (!keyId.equals(string(envelope, "keyId"))) {
            throw new IllegalArgumentException("password key has rotated; reload the page and retry");
        }
        byte[] clientPublic = unbase64(string(envelope, "clientPublicKey"), 32, "clientPublicKey");
        byte[] nonce = unbase64(string(envelope, "nonce"), NONCE_BYTES, "nonce");
        byte[] ciphertext = unbase64(string(envelope, "ciphertext"), -1, "ciphertext");
        try {
            X25519Agreement agreement = new X25519Agreement();
            agreement.init(privateKey);
            byte[] shared = new byte[32];
            agreement.calculateAgreement(new X25519PublicKeyParameters(clientPublic, 0), shared, 0);
            byte[] key = hkdf(shared);
            Cipher cipher = Cipher.getInstance("ChaCha20-Poly1305");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "ChaCha20"),
                    new IvParameterSpec(nonce));
            cipher.updateAAD(AAD);
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalArgumentException("encrypted password could not be decrypted", e);
        }
    }

    private byte[] hkdf(byte[] shared) {
        HKDFBytesGenerator generator = new HKDFBytesGenerator(new SHA256Digest());
        generator.init(new HKDFParameters(shared, keyId.getBytes(StandardCharsets.UTF_8), AAD));
        byte[] key = new byte[32];
        generator.generateBytes(key, 0, key.length);
        return key;
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            throw new IllegalArgumentException("encrypted password is missing `" + key + "`");
        }
        return value.getAsString();
    }

    private static byte[] unbase64(String value, int expected, String field) {
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(value);
            if (expected >= 0 && bytes.length != expected) {
                throw new IllegalArgumentException("encrypted password field `" + field + "` has the wrong length");
            }
            if (bytes.length == 0) {
                throw new IllegalArgumentException("encrypted password field `" + field + "` is empty");
            }
            return bytes;
        } catch (IllegalArgumentException e) {
            throw e;
        }
    }

    private static String base64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
