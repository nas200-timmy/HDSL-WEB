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

class ChatCryptoServiceTest {

    @Test
    void bothDirectionsMatchTheBrowserDerivation() throws Exception {
        ChatCryptoService service = new ChatCryptoService();
        X25519PrivateKeyParameters clientPrivate = new X25519PrivateKeyParameters(new SecureRandom());
        ChatCryptoService.Descriptor descriptor = service.start(clientPrivate.generatePublicKey().getEncoded());
        ChatCryptoService.Channel channel = service.channel(descriptor.channelId());

        byte[] shared = new byte[32];
        X25519Agreement agreement = new X25519Agreement();
        agreement.init(clientPrivate);
        agreement.calculateAgreement(new X25519PublicKeyParameters(descriptor.serverPublicKey(), 0), shared, 0);
        HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA256Digest());
        hkdf.init(new HKDFParameters(shared,
                descriptor.channelId().getBytes(StandardCharsets.UTF_8),
                ChatCryptoService.INFO.getBytes(StandardCharsets.UTF_8)));
        byte[] keys = new byte[64];
        hkdf.generateBytes(keys, 0, keys.length);
        byte[] c2s = java.util.Arrays.copyOfRange(keys, 0, 32);
        byte[] s2c = java.util.Arrays.copyOfRange(keys, 32, 64);

        JsonObject request = service.encrypt(channel, true,
                "hello".getBytes(StandardCharsets.UTF_8));
        assertEquals("hello", new String(service.decrypt(channel, true, request), StandardCharsets.UTF_8));

        JsonObject response = encrypt(s2c, "reply");
        assertEquals("reply", new String(service.decrypt(channel, false, response), StandardCharsets.UTF_8));

        JsonObject clientResponse = encrypt(c2s, "client-reply");
        assertEquals("client-reply", new String(service.decrypt(channel, true, clientResponse),
                StandardCharsets.UTF_8));
    }

    private static JsonObject encrypt(byte[] key, String text) throws Exception {
        byte[] nonce = new byte[12];
        new SecureRandom().nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("ChaCha20-Poly1305");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "ChaCha20"), new IvParameterSpec(nonce));
        cipher.updateAAD(ChatCryptoService.INFO.getBytes(StandardCharsets.UTF_8));
        byte[] ciphertext = cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));
        JsonObject envelope = new JsonObject();
        envelope.addProperty("v", "1");
        envelope.addProperty("n", Base64.getUrlEncoder().withoutPadding().encodeToString(nonce));
        envelope.addProperty("ct", Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext));
        return envelope;
    }
}
