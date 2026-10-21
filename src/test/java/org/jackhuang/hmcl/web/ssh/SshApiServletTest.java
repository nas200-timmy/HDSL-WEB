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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.future.AuthFuture;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.util.buffer.ByteArrayBuffer;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.jackhuang.hmcl.util.PosixPermissions;
import org.jackhuang.hmcl.web.TestSupport;
import org.jackhuang.hmcl.web.auth.UserStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The SSH management surface and the channel behind it, over real HTTP(S)
/// and real SSH: the toggle hot-starts/stops the listener (and the port dies
/// with it), the HTTPS and elevation guards answer 409/403, the developer
/// password lifecycle (set/change/clear, three-strikes lock) holds, key
/// management round-trips, and a MINA client actually logs in — by key, by
/// generated key, and by panel password once the switch is on.
///
/// TLS is on from boot (a self-signed identity is generated) because every
/// sensitive endpoint demands it; plain-HTTP behaviour is exercised in its
/// own test. Authentication itself is disabled — legal on this loopback
/// bind — which is also what gives the elevation map its anonymous key.
class SshApiServletTest {

    private static final String PANEL_PASSWORD = "panel-password-1";
    private static final String DEV_PASSWORD = "dev-password-1";
    private static final String DEV_PASSWORD_2 = "dev-password-2";

    @TempDir
    Path dataDir;

    /// TLS from boot; the SSH channel per `sshEnabled`; ephemeral SSH port.
    private TestSupport.RunningServer startServer(boolean sshEnabled) throws Exception {
        return TestSupport.start(dataDir, Map.of(UserStore.ENV_ADMIN_PASSWORD, PANEL_PASSWORD), config -> {
            config.bindHost = "127.0.0.1";
            config.auth.disabled = true;
            config.https.enabled = true;
            config.ssh.enabled = sshEnabled;
            config.ssh.port = 0;
        });
    }

    private static String base(TestSupport.RunningServer running) {
        return "https://127.0.0.1:" + running.port();
    }

    // ------------------------------------------------------------------ tests --

    @Test
    void statusDescribesTheDisabledChannel() throws Exception {
        try (TestSupport.RunningServer running = startServer(false)) {
            HttpClient client = TestSupport.trustAllClient();
            HttpResponse<String> status = get(client, base(running) + "/api/ssh/status");
            assertEquals(200, status.statusCode(), status.body());
            JsonObject body = json(status);
            assertFalse(body.get("enabled").getAsBoolean());
            assertFalse(body.get("running").getAsBoolean());
            assertEquals(-1, body.get("port").getAsInt());
            assertTrue(body.get("supported").getAsBoolean(), "the test host must be a supported platform");
            assertEquals("supported", body.get("verdict").getAsString());
            assertTrue(body.get("https").getAsBoolean(), "TLS is live from boot");
            assertFalse(body.get("passwordAuth").getAsBoolean());
            assertFalse(body.get("devPasswordSet").getAsBoolean());
            assertFalse(body.get("elevated").getAsBoolean());
            assertEquals(0, body.get("connections").getAsInt());
            assertTrue(body.get("hostKeyFingerprint").isJsonNull(), "no listener, no fingerprint");

            // The doctor carries the platform row and the state row.
            HttpResponse<String> doctor = get(client, base(running) + "/api/doctor");
            assertEquals(200, doctor.statusCode(), doctor.body());
            JsonObject platform = null;
            JsonObject channel = null;
            for (var element : json(doctor).getAsJsonArray("checks")) {
                JsonObject check = element.getAsJsonObject();
                if ("ssh-platform".equals(check.get("id").getAsString())) {
                    platform = check;
                }
                if ("ssh".equals(check.get("id").getAsString())) {
                    channel = check;
                }
            }
            assertNotNull(platform, "the doctor has the SSH platform row");
            assertEquals("ok", platform.get("status").getAsString());
            assertNotNull(channel, "the doctor has the SSH state row");
            assertEquals("ok", channel.get("status").getAsString());
            assertTrue(channel.get("detail").getAsString().contains("已关闭"), channel.get("detail").getAsString());
        }
    }

    @Test
    void tlsGuardAnswers409OnPlainHttp() throws Exception {
        // Plain HTTP panel: the guard, not the network, is what says no.
        try (TestSupport.RunningServer running = TestSupport.start(dataDir, Map.of(), config -> {
            config.bindHost = "127.0.0.1";
            config.auth.disabled = true;
        })) {
            HttpClient client = TestSupport.client();
            String base = running.baseUrl();
            HttpResponse<String> status = get(client, base + "/api/ssh/status");
            assertEquals(200, status.statusCode(), "status itself is not guarded");
            assertFalse(json(status).get("https").getAsBoolean());

            assertTlsRequired(post(client, base + "/api/ssh/elevate", "{\"password\":\"x\"}"));
            assertTlsRequired(post(client, base + "/api/ssh/dev-password",
                    "{\"adminPassword\":\"a\",\"password\":\"dev-password-9\",\"confirm\":\"dev-password-9\"}"));
            assertTlsRequired(post(client, base + "/api/ssh/toggle", "{\"enabled\":true}"));
            assertTlsRequired(post(client, base + "/api/ssh/keys", "{\"key\":\"ssh-ed25519 AAAA\"}"));
            assertTlsRequired(post(client, base + "/api/ssh/generate-keypair", "{}"));
            assertTlsRequired(post(client, base + "/api/ssh/password-auth", "{\"enabled\":true}"));
        }
    }

    @Test
    void developerPasswordLifecycleAndElevation() throws Exception {
        try (TestSupport.RunningServer running = startServer(false)) {
            HttpClient client = TestSupport.trustAllClient();
            String base = base(running);

            // Setting needs the panel password.
            HttpResponse<String> wrongAdmin = post(client, base + "/api/ssh/dev-password",
                    "{\"adminPassword\":\"nope-nope\",\"password\":\"" + DEV_PASSWORD + "\","
                            + "\"confirm\":\"" + DEV_PASSWORD + "\"}");
            assertEquals(403, wrongAdmin.statusCode(), wrongAdmin.body());

            HttpResponse<String> set = post(client, base + "/api/ssh/dev-password",
                    "{\"adminPassword\":\"" + PANEL_PASSWORD + "\",\"password\":\"" + DEV_PASSWORD + "\","
                            + "\"confirm\":\"" + DEV_PASSWORD + "\"}");
            assertEquals(200, set.statusCode(), set.body());
            assertTrue(json(set).get("devPasswordSet").getAsBoolean());
            PosixPermissions.assertOwnerOnly(dataDir.resolve("auth").resolve(DeveloperAuth.FILE_NAME),
                    "the developer hash must be 0600");

            // A wrong guess is a 401; a right one elevates for 15 minutes.
            HttpResponse<String> wrong = post(client, base + "/api/ssh/elevate", "{\"password\":\"wrong-password\"}");
            assertEquals(401, wrong.statusCode(), wrong.body());
            HttpResponse<String> elevated = post(client, base + "/api/ssh/elevate",
                    "{\"password\":\"" + DEV_PASSWORD + "\"}");
            assertEquals(200, elevated.statusCode(), elevated.body());
            Instant expiresAt = Instant.parse(json(elevated).get("expiresAt").getAsString());
            Duration ttl = Duration.between(Instant.now(), expiresAt);
            assertTrue(ttl.toMinutes() >= 14 && ttl.toMinutes() <= 15, "elevation lives 15 minutes: " + ttl);
            assertTrue(json(get(client, base + "/api/ssh/status")).get("elevated").getAsBoolean());

            // Changing requires the current developer password and resets
            // every elevation.
            HttpResponse<String> wrongCurrent = post(client, base + "/api/ssh/dev-password",
                    "{\"adminPassword\":\"" + PANEL_PASSWORD + "\",\"password\":\"" + DEV_PASSWORD_2 + "\","
                            + "\"confirm\":\"" + DEV_PASSWORD_2 + "\",\"currentDevPassword\":\"wrong-password\"}");
            assertEquals(403, wrongCurrent.statusCode(), wrongCurrent.body());

            HttpResponse<String> changed = post(client, base + "/api/ssh/dev-password",
                    "{\"adminPassword\":\"" + PANEL_PASSWORD + "\",\"password\":\"" + DEV_PASSWORD_2 + "\","
                            + "\"confirm\":\"" + DEV_PASSWORD_2 + "\",\"currentDevPassword\":\"" + DEV_PASSWORD + "\"}");
            assertEquals(200, changed.statusCode(), changed.body());
            assertFalse(json(get(client, base + "/api/ssh/status")).get("elevated").getAsBoolean(),
                    "a change kills every elevation");
            assertEquals(401, post(client, base + "/api/ssh/elevate",
                    "{\"password\":\"" + DEV_PASSWORD + "\"}").statusCode());
            assertEquals(200, post(client, base + "/api/ssh/elevate",
                    "{\"password\":\"" + DEV_PASSWORD_2 + "\"}").statusCode());

            // Clearing needs the panel password and leaves the channel
            // telling callers to set one first.
            HttpResponse<String> clearWrong = post(client, base + "/api/ssh/dev-password/clear",
                    "{\"adminPassword\":\"nope-nope\"}");
            assertEquals(403, clearWrong.statusCode(), clearWrong.body());
            HttpResponse<String> cleared = post(client, base + "/api/ssh/dev-password/clear",
                    "{\"adminPassword\":\"" + PANEL_PASSWORD + "\"}");
            assertEquals(200, cleared.statusCode(), cleared.body());
            assertFalse(json(cleared).get("devPasswordSet").getAsBoolean());
            assertFalse(Files.exists(dataDir.resolve("auth").resolve(DeveloperAuth.FILE_NAME)));
            HttpResponse<String> elevateUnset = post(client, base + "/api/ssh/elevate",
                    "{\"password\":\"" + DEV_PASSWORD_2 + "\"}");
            assertEquals(409, elevateUnset.statusCode(), elevateUnset.body());
        }
    }

    @Test
    void developerPasswordPolicyAndThreeStrikesLock() throws Exception {
        try (TestSupport.RunningServer running = startServer(false)) {
            HttpClient client = TestSupport.trustAllClient();
            String base = base(running);

            HttpResponse<String> mismatch = post(client, base + "/api/ssh/dev-password",
                    "{\"adminPassword\":\"" + PANEL_PASSWORD + "\",\"password\":\"" + DEV_PASSWORD + "\","
                            + "\"confirm\":\"different-password\"}");
            assertEquals(400, mismatch.statusCode(), mismatch.body());

            HttpResponse<String> sameAsPanel = post(client, base + "/api/ssh/dev-password",
                    "{\"adminPassword\":\"" + PANEL_PASSWORD + "\",\"password\":\"" + PANEL_PASSWORD + "\","
                            + "\"confirm\":\"" + PANEL_PASSWORD + "\"}");
            assertEquals(400, sameAsPanel.statusCode(), sameAsPanel.body());

            HttpResponse<String> shortPassword = post(client, base + "/api/ssh/dev-password",
                    "{\"adminPassword\":\"" + PANEL_PASSWORD + "\",\"password\":\"tiny\",\"confirm\":\"tiny\"}");
            assertEquals(400, shortPassword.statusCode(), shortPassword.body());

            HttpResponse<String> set = post(client, base + "/api/ssh/dev-password",
                    "{\"adminPassword\":\"" + PANEL_PASSWORD + "\",\"password\":\"" + DEV_PASSWORD + "\","
                            + "\"confirm\":\"" + DEV_PASSWORD + "\"}");
            assertEquals(200, set.statusCode(), set.body());

            // Two wrong guesses are 401s; the third trips the lock, and from
            // then on even the right password is a 429.
            assertEquals(401, post(client, base + "/api/ssh/elevate", "{\"password\":\"guess-1\"}").statusCode());
            assertEquals(401, post(client, base + "/api/ssh/elevate", "{\"password\":\"guess-2\"}").statusCode());
            assertEquals(429, post(client, base + "/api/ssh/elevate", "{\"password\":\"guess-3\"}").statusCode());
            HttpResponse<String> locked = post(client, base + "/api/ssh/elevate",
                    "{\"password\":\"" + DEV_PASSWORD + "\"}");
            assertEquals(429, locked.statusCode(), locked.body());
        }
    }

    @Test
    void keyManagementNeedsElevationAndValidates() throws Exception {
        try (TestSupport.RunningServer running = startServer(false)) {
            HttpClient client = TestSupport.trustAllClient();
            String base = base(running);

            HttpResponse<String> withoutElevation = get(client, base + "/api/ssh/keys");
            assertEquals(403, withoutElevation.statusCode(), withoutElevation.body());

            setDeveloperPassword(client, base);
            elevate(client, base);

            JsonObject valid = new JsonObject();
            valid.addProperty("key", keyLine("ssh-ed25519", 32, "laptop"));
            assertEquals(200, post(client, base + "/api/ssh/keys", valid.toString()).statusCode());

            JsonObject duplicate = new JsonObject();
            duplicate.addProperty("key", keyLine("ssh-ed25519", 32, "same key, new comment"));
            assertEquals(400, post(client, base + "/api/ssh/keys", duplicate.toString()).statusCode());

            JsonObject multiline = new JsonObject();
            multiline.addProperty("key", keyLine("ssh-ed25519", 32, "one") + "\n" + keyLine("ssh-ed25519", 64, "two"));
            assertEquals(400, post(client, base + "/api/ssh/keys", multiline.toString()).statusCode());

            JsonObject unknownAlgorithm = new JsonObject();
            unknownAlgorithm.addProperty("key", keyLine("ssh-dss", 64, "legacy"));
            assertEquals(400, post(client, base + "/api/ssh/keys", unknownAlgorithm.toString()).statusCode());

            JsonObject badBase64 = new JsonObject();
            badBase64.addProperty("key", "ssh-ed25519 !!!not-base64!!! broken");
            assertEquals(400, post(client, base + "/api/ssh/keys", badBase64.toString()).statusCode());

            HttpResponse<String> listed = get(client, base + "/api/ssh/keys");
            assertEquals(200, listed.statusCode(), listed.body());
            JsonObject one = json(listed).getAsJsonArray("keys").get(0).getAsJsonObject();
            assertEquals("ssh-ed25519", one.get("algorithm").getAsString());
            assertEquals("laptop", one.get("comment").getAsString());
            assertEquals(1, one.get("index").getAsInt());
            assertTrue(one.get("fingerprintSHA256").getAsString().startsWith("SHA256:"));

            assertEquals(200, delete(client, base + "/api/ssh/keys", "{\"index\":1}").statusCode());
            assertEquals(0, json(get(client, base + "/api/ssh/keys")).getAsJsonArray("keys").size());
            assertEquals(400, delete(client, base + "/api/ssh/keys", "{\"index\":7}").statusCode());
        }
    }

    @Test
    void publicKeyLoginWorksTheSkeletonAnswersAndTheToggleOwnsThePort() throws Exception {
        try (TestSupport.RunningServer running = startServer(true)) {
            HttpClient http = TestSupport.trustAllClient();
            String base = base(running);

            JsonObject status = json(get(http, base + "/api/ssh/status"));
            assertTrue(status.get("enabled").getAsBoolean());
            assertTrue(status.get("running").getAsBoolean());
            int port = status.get("port").getAsInt();
            assertTrue(port > 0, "the listener took an ephemeral port");
            assertTrue(status.get("hostKeyFingerprint").getAsString().startsWith("SHA256:"));
            assertTrue(Files.isRegularFile(dataDir.resolve("ssh").resolve(SshService.HOST_KEY_FILE)),
                    "the host key is persisted");

            // Upload the test key through the API, the way the settings page does.
            setDeveloperPassword(http, base);
            elevate(http, base);
            KeyPair testKey = generateKeyPair("Ed25519");
            JsonObject upload = new JsonObject();
            upload.addProperty("key", publicLine(testKey));
            HttpResponse<String> added = post(http, base + "/api/ssh/keys", upload.toString());
            assertEquals(200, added.statusCode(), added.body());

            SshClient sshClient = null;
            try {
                sshClient = freshClient();
                ClientSession session = authenticatedSession(sshClient, port, testKey);

                // The skeleton says its line and exits 0.
                ChannelShell shell = session.createShellChannel();
                ByteArrayOutputStream shellOut = new ByteArrayOutputStream();
                shell.setOut(shellOut);
                shell.open().verify(15, TimeUnit.SECONDS);
                waitFor(() -> shell.getExitStatus() != null, Duration.ofSeconds(15));
                assertEquals(Integer.valueOf(0), shell.getExitStatus());
                assertTrue(shellOut.toString(StandardCharsets.UTF_8).contains("已就绪"),
                        "the skeleton must greet the session: " + shellOut);
                shell.close(false);
                session.close(false);
            } finally {
                if (sshClient != null) {
                    sshClient.stop();
                }
            }

            // Toggling off kills the port for real, toggling on brings it back.
            HttpResponse<String> off = post(http, base + "/api/ssh/toggle", "{\"enabled\":false}");
            assertEquals(200, off.statusCode(), off.body());
            JsonObject offJson = json(off);
            assertFalse(offJson.get("running").getAsBoolean());
            assertEquals(-1, offJson.get("port").getAsInt());
            assertThrows(ConnectException.class, () -> tryTcp(port), "a dead channel answers with RST, not silence");
            assertFalse(json(get(http, base + "/api/ssh/status")).get("running").getAsBoolean());

            HttpResponse<String> on = post(http, base + "/api/ssh/toggle", "{\"enabled\":true}");
            assertEquals(200, on.statusCode(), on.body());
            assertTrue(json(on).get("running").getAsBoolean());
            assertTrue(json(get(http, base + "/api/ssh/status")).get("port").getAsInt() > 0);

            // The whole lifecycle is in the audit trail.
            List<String> audit = Files.readAllLines(auditFile());
            assertTrue(audit.stream().anyMatch(line -> line.contains("\"action\":\"ssh.enable\"")),
                    "ssh.enable must be audited: " + audit);
            assertTrue(audit.stream().anyMatch(line -> line.contains("\"action\":\"ssh.disable\"")),
                    "ssh.disable must be audited: " + audit);
            assertTrue(audit.stream().anyMatch(line -> line.contains("\"action\":\"ssh.connect\"")),
                    "ssh.connect must be audited: " + audit);
            assertTrue(audit.stream().anyMatch(line -> line.contains("\"action\":\"ssh.disconnect\"")),
                    "ssh.disconnect must be audited: " + audit);
            assertTrue(audit.stream().anyMatch(line -> line.contains("\"action\":\"ssh.key-add\"")),
                    "ssh.key-add must be audited: " + audit);
            assertTrue(audit.stream().anyMatch(line -> line.contains("\"action\":\"ssh.toggle\"")),
                    "ssh.toggle must be audited: " + audit);
        }
    }

    @Test
    void passwordLoginIsOffByDefaultAndHotSwitches() throws Exception {
        try (TestSupport.RunningServer running = startServer(true)) {
            HttpClient http = TestSupport.trustAllClient();
            String base = base(running);
            int port = json(get(http, base + "/api/ssh/status")).get("port").getAsInt();

            // Default: the panel password is not an SSH password.
            SshClient client = null;
            try {
                client = freshClient();
                ClientSession rejected = client.connect("admin", "127.0.0.1", port)
                        .verify(15, TimeUnit.SECONDS).getSession();
                rejected.addPasswordIdentity(PANEL_PASSWORD);
                AuthFuture rejectedAuth = rejected.auth();
                assertTrue(rejectedAuth.await(15, TimeUnit.SECONDS));
                assertFalse(rejectedAuth.isSuccess(), "password auth must fail while the switch is off");
                rejected.close(false);
            } finally {
                if (client != null) {
                    client.stop();
                }
            }

            // Flip the switch: the warning rides along, the yaml records it,
            // and the very same password now logs in.
            setDeveloperPassword(http, base);
            elevate(http, base);
            HttpResponse<String> on = post(http, base + "/api/ssh/password-auth", "{\"enabled\":true}");
            assertEquals(200, on.statusCode(), on.body());
            assertTrue(json(on).get("warning").getAsString().contains("爆破"),
                    "turning password auth on must carry the brute-force warning");
            assertTrue(Files.readString(dataDir.resolve("server.yaml")).contains("password_auth: true"));

            try {
                client = freshClient();
                ClientSession accepted = client.connect("admin", "127.0.0.1", port)
                        .verify(15, TimeUnit.SECONDS).getSession();
                accepted.addPasswordIdentity(PANEL_PASSWORD);
                accepted.auth().verify(15, TimeUnit.SECONDS);
                accepted.close(false);
            } finally {
                if (client != null) {
                    client.stop();
                }
            }
        }
    }

    @Test
    void generatedKeypairLogsInAndThePrivateKeyNeverHitsDisk() throws Exception {
        try (TestSupport.RunningServer running = startServer(true)) {
            HttpClient http = TestSupport.trustAllClient();
            String base = base(running);
            int port = json(get(http, base + "/api/ssh/status")).get("port").getAsInt();
            setDeveloperPassword(http, base);
            elevate(http, base);

            HttpResponse<String> generated = post(http, base + "/api/ssh/generate-keypair", "{}");
            assertEquals(200, generated.statusCode(), generated.body());
            JsonObject body = json(generated);
            String publicLine = body.get("publicKey").getAsString();
            String privatePem = body.get("privateKey").getAsString();
            assertTrue(publicLine.startsWith("ssh-rsa "), publicLine);
            assertTrue(privatePem.contains("PRIVATE KEY-----"), privatePem);
            assertEquals("openssh", body.get("format").getAsString());
            assertTrue(body.get("fingerprint").getAsString().startsWith("SHA256:"));

            // The public half is authorized; no private half exists anywhere.
            List<String> authorized = Files.readAllLines(
                    dataDir.resolve("ssh").resolve(AuthorizedKeys.FILE_NAME));
            assertTrue(authorized.contains(publicLine), authorized.toString());
            try (Stream<Path> files = Files.list(dataDir.resolve("ssh"))) {
                List<String> names = files.map(path -> path.getFileName().toString()).sorted().toList();
                assertEquals(List.of(AuthorizedKeys.FILE_NAME, SshService.HOST_KEY_FILE), names,
                        "only the authorized keys and the host key may exist on disk");
            }

            // The one-time private key actually works: parse it, log in with it.
            KeyPair parsed = parsePem(privatePem);
            SshClient client = null;
            try {
                client = freshClient();
                ClientSession session = authenticatedSession(client, port, parsed);
                session.close(false);
            } finally {
                if (client != null) {
                    client.stop();
                }
            }
        }
    }

    // --------------------------------------------------------------- helpers --

    private void setDeveloperPassword(HttpClient client, String base) throws Exception {
        HttpResponse<String> set = post(client, base + "/api/ssh/dev-password",
                "{\"adminPassword\":\"" + PANEL_PASSWORD + "\",\"password\":\"" + DEV_PASSWORD + "\","
                        + "\"confirm\":\"" + DEV_PASSWORD + "\"}");
        assertEquals(200, set.statusCode(), set.body());
    }

    private void elevate(HttpClient client, String base) throws Exception {
        HttpResponse<String> elevated = post(client, base + "/api/ssh/elevate",
                "{\"password\":\"" + DEV_PASSWORD + "\"}");
        assertEquals(200, elevated.statusCode(), elevated.body());
    }

    private static void assertTlsRequired(HttpResponse<String> response) {
        assertEquals(409, response.statusCode(), response.body());
        assertTrue(json(response).get("error").getAsString().contains("tls_required"),
                response.body());
    }

    private static KeyPair generateKeyPair(String algorithm) throws Exception {
        return KeyPairGenerator.getInstance(algorithm).generateKeyPair();
    }

    /// The OpenSSH single-line form of a JDK key, built the way the wire format asks.
    private static String publicLine(KeyPair pair) {
        ByteArrayBuffer wire = new ByteArrayBuffer();
        wire.putPublicKey(pair.getPublic());
        String algorithm = switch (pair.getPublic().getAlgorithm()) {
            case "EdDSA", "Ed25519" -> "ssh-ed25519";
            case "RSA" -> "ssh-rsa";
            default -> throw new IllegalArgumentException("test key algorithm: " + pair.getPublic().getAlgorithm());
        };
        return algorithm + " " + Base64.getEncoder().encodeToString(wire.getCompactData()) + " test-key";
    }

    private static String keyLine(String algorithm, int blobBytes, String comment) {
        byte[] blob = new byte[blobBytes];
        for (int i = 0; i < blob.length; i++) {
            blob[i] = (byte) (17 + i);
        }
        return algorithm + " " + Base64.getEncoder().encodeToString(blob) + " " + comment;
    }

    private static SshClient freshClient() throws Exception {
        SshClient client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        client.start();
        return client;
    }

    private static ClientSession authenticatedSession(SshClient client, int port, KeyPair key) throws Exception {
        ClientSession session = client.connect("hdsl", "127.0.0.1", port)
                .verify(15, TimeUnit.SECONDS).getSession();
        session.addPublicKeyIdentity(key);
        session.auth().verify(15, TimeUnit.SECONDS);
        return session;
    }

    private static KeyPair parsePem(String pem) throws Exception {
        Object parsed;
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            parsed = parser.readObject();
        }
        JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
        if (parsed instanceof PEMKeyPair pair) {
            return converter.getKeyPair(pair);
        }
        throw new IllegalStateException("unexpected PEM payload: " + parsed.getClass());
    }

    private static void tryTcp(int port) throws Exception {
        try (var socket = new java.net.Socket("127.0.0.1", port)) {
            // Connected — the port is alive, which the caller forbids.
        }
    }

    private interface Probe {
        boolean met() throws Exception;
    }

    private static void waitFor(Probe probe, Duration timeout) throws Exception {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (probe.met()) {
                return;
            }
            Thread.sleep(50);
        }
    }

    private Path auditFile() throws Exception {
        try (Stream<Path> files = Files.list(dataDir.resolve("logs"))) {
            Path file = files.filter(path -> path.getFileName().toString().startsWith("audit-")).findFirst()
                    .orElseThrow(() -> new IllegalStateException("no audit file under " + dataDir.resolve("logs")));
            return file;
        }
    }

    private static HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(HttpClient client, String url, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> delete(HttpClient client, String url, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json; charset=utf-8")
                .method("DELETE", HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static JsonObject json(HttpResponse<String> response) {
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }
}
