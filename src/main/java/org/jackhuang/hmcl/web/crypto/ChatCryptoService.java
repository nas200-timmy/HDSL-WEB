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
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/// A per-tab chat channel. The browser and server agree on two ChaCha20 keys
/// with X25519 + HKDF; REST chat JSON and `chat:*` WebSocket frames are wrapped
/// in `{"v":1,"n":"…","ct":"…"}`. The channel is memory-only and expires; a
/// refresh performs a new handshake.
@NotNullByDefault
public final class ChatCryptoService {

    public static final String INFO = "hdsl-web-chat-v1";
    private static final byte[] AAD = INFO.getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration TTL = Duration.ofHours(12);
    private static final int MAX_CHANNELS = 1024;

    public record Descriptor(String channelId, byte[] serverPublicKey, Instant expiresAt) {
    }

    public static final class Channel {
        private final String id;
        private final byte[] c2s;
        private final byte[] s2c;
        private final Instant expiresAt;

        private Channel(String id, byte[] c2s, byte[] s2c, Instant expiresAt) {
            this.id = id;
            this.c2s = c2s;
            this.s2c = s2c;
            this.expiresAt = expiresAt;
        }

        public String id() {
            return id;
        }

        public Instant expiresAt() {
            return expiresAt;
        }

        private byte[] key(boolean clientToServer) {
            return clientToServer ? c2s : s2c;
        }
    }

    private final Map<String, Channel> channels = new ConcurrentHashMap<>();

    /// Starts a channel from the browser's ephemeral X25519 public key.
    public Descriptor start(byte[] clientPublicKey) {
        if (clientPublicKey == null || clientPublicKey.length != 32) {
            throw new IllegalArgumentException("clientPublicKey must be 32 bytes");
        }
        cleanup();
        String id = randomId();
        X25519PrivateKeyParameters serverPrivate = new X25519PrivateKeyParameters(RANDOM);
        byte[] serverPublic = serverPrivate.generatePublicKey().getEncoded();
        byte[] shared = agree(serverPrivate, clientPublicKey);
        byte[] keys = hkdf(shared, id);
        byte[] c2s = java.util.Arrays.copyOfRange(keys, 0, 32);
        byte[] s2c = java.util.Arrays.copyOfRange(keys, 32, 64);
        Instant expiresAt = Instant.now().plus(TTL);
        channels.put(id, new Channel(id, c2s, s2c, expiresAt));
        return new Descriptor(id, serverPublic, expiresAt);
    }

    /// Whether this exact channel is still the one the server holds.
    ///
    /// A WebSocket binds a `Channel` object at `chat-hello`; when the channel
    /// is evicted or expires, REST re-handshakes and the browser switches to
    /// a new key, while the socket keeps the old object. Pushing with it made
    /// the client fail to decrypt and drop the frame silently. The send path
    /// asks this first and tells the client to bind again instead.
    ///
    /// @param channel the object a socket bound earlier
    /// @return whether the map still holds it and it has not expired
    public boolean isCurrent(@Nullable Channel channel) {
        if (channel == null) {
            return false;
        }
        if (Instant.now().isAfter(channel.expiresAt)) {
            return false;
        }
        return channels.get(channel.id()) == channel;
    }

    public @Nullable Channel channel(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        Channel channel = channels.get(id);
        if (channel == null) {
            return null;
        }
        if (Instant.now().isAfter(channel.expiresAt)) {
            channels.remove(id, channel);
            return null;
        }
        return channel;
    }

    public JsonObject encrypt(Channel channel, boolean clientToServer, byte[] plaintext) {
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("ChaCha20-Poly1305");
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(channel.key(clientToServer), "ChaCha20"),
                    new IvParameterSpec(nonce));
            cipher.updateAAD(AAD);
            byte[] ciphertext = cipher.doFinal(plaintext);
            JsonObject envelope = new JsonObject();
            envelope.addProperty("v", PasswordCrypto.VERSION);
            envelope.addProperty("n", base64(nonce));
            envelope.addProperty("ct", base64(ciphertext));
            return envelope;
        } catch (Exception e) {
            throw new IllegalStateException("chat encryption failed", e);
        }
    }

    public byte[] decrypt(Channel channel, boolean clientToServer, JsonObject envelope) {
        try {
            byte[] nonce = Base64.getUrlDecoder().decode(envelope.get("n").getAsString());
            byte[] ciphertext = Base64.getUrlDecoder().decode(envelope.get("ct").getAsString());
            Cipher cipher = Cipher.getInstance("ChaCha20-Poly1305");
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(channel.key(clientToServer), "ChaCha20"),
                    new IvParameterSpec(nonce));
            cipher.updateAAD(AAD);
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw new IllegalArgumentException("chat payload could not be decrypted", e);
        }
    }

    private static byte[] agree(X25519PrivateKeyParameters privateKey, byte[] publicKey) {
        X25519Agreement agreement = new X25519Agreement();
        agreement.init(privateKey);
        byte[] shared = new byte[32];
        agreement.calculateAgreement(new X25519PublicKeyParameters(publicKey, 0), shared, 0);
        return shared;
    }

    private static byte[] hkdf(byte[] shared, String channelId) {
        HKDFBytesGenerator generator = new HKDFBytesGenerator(new SHA256Digest());
        generator.init(new HKDFParameters(shared, channelId.getBytes(StandardCharsets.UTF_8), AAD));
        byte[] keys = new byte[64];
        generator.generateBytes(keys, 0, keys.length);
        return keys;
    }

    private void cleanup() {
        Instant now = Instant.now();
        channels.entrySet().removeIf(entry -> now.isAfter(entry.getValue().expiresAt));
        while (channels.size() >= MAX_CHANNELS) {
            String first = channels.keySet().iterator().next();
            channels.remove(first);
        }
    }

    private static String randomId() {
        byte[] id = new byte[16];
        RANDOM.nextBytes(id);
        return base64(id);
    }

    private static String base64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
