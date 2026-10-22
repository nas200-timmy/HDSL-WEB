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
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.sshd.common.util.buffer.ByteArrayBuffer;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil;
import org.bouncycastle.crypto.util.PrivateKeyFactory;
import org.bouncycastle.openssl.jcajce.JcaMiscPEMGenerator;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.jackhuang.hmcl.web.audit.AuditLog;
import org.jackhuang.hmcl.web.auth.AuthFilter;
import org.jackhuang.hmcl.web.auth.AuthService;
import org.jackhuang.hmcl.web.developer.DeveloperAuth;
import org.jackhuang.hmcl.web.developer.DeveloperElevation;
import org.jackhuang.hmcl.web.auth.LoginThrottle;
import org.jackhuang.hmcl.web.auth.UserStore;
import org.jackhuang.hmcl.web.config.ServerConfig;
import org.jackhuang.hmcl.web.config.ServerConfigWriter;
import org.jackhuang.hmcl.web.http.Json;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/// The SSH channel's management surface, mapped at `/api/ssh/*`:
///
/// - `GET    /api/ssh/status` — everything the settings section renders:
///   `{enabled, running, bind, port, verdict, supported, https, passwordAuth,
///   hostKeyFingerprint, devPasswordSet, connections, elevated}`
/// - `POST   /api/ssh/elevate` `{password}` — prove the developer password,
///   elevate **this session** for 15 minutes (sliding, renewed by every
///   sensitive call). Three wrong guesses per session lock it for 10 minutes.
/// - `POST   /api/ssh/dev-password` `{adminPassword, password, confirm,
///   currentDevPassword?}` — set or change the developer password. The admin
///   password proves it is the owner; the current developer password is also
///   required once one exists. Any set/change kills every elevation.
/// - `POST   /api/ssh/dev-password/clear` `{adminPassword}` — remove it.
/// - `POST   /api/ssh/toggle` `{enabled}` — the switch: persists, then hot
///   starts/stops the listener.
/// - `POST   /api/ssh/bind` `{bind}` — the listener's bind address. Persists,
///   then restarts a running listener on the new address; a bind that cannot
///   listen is rolled back to the previous one.
/// - `GET    /api/ssh/keys` — the authorized keys (elevation required).
/// - `POST   /api/ssh/keys` `{key}` — add one pasted OpenSSH public key.
/// - `DELETE /api/ssh/keys` `{index}` — remove one.
/// - `POST   /api/ssh/generate-keypair` — server-side RSA-3072 pair; the
///   private key is answered **once** and never written to disk, the public
///   line lands in `authorized_keys`.
/// - `POST   /api/ssh/password-auth` `{enabled}` — the panel-password switch.
///
/// Two guards stand in front of every sensitive route. The HTTPS guard:
/// developer-password set/change, elevation, key upload, key generation, the
/// toggle and the password switch answer 409 unless the panel's own connector
/// speaks TLS right now — the server-side TLS state decides, never a request
/// header. The elevation guard: the same routes (plus the key list) answer
/// 403 until the session has proved the developer password.
///
/// Elevation is tracked per session. The elevation key is the session's
/// username; a panel running with authentication disabled (only legal on a
/// loopback bind) has no session object, so those requests key on the remote
/// address instead — loopback-only, and the tests' way in.
@NotNullByDefault
public final class SshApiServlet extends HttpServlet {

    /// What the panel answers when `password-auth` is being turned on.
    private static final String PASSWORD_AUTH_WARNING =
            "口令登录开启后，面板口令可直接用于 SSH 登录，爆破风险显著上升；请确认网络层有额外保护（回环/内网/防火墙）。";

    private final SshService ssh;
    private final DeveloperAuth devAuth;
    private final DeveloperElevation elevation;
    private final ServerConfig config;
    private final AuthService auth;
    private final BooleanSupplier tlsActive;

    /// Failed panel-password confirmations on the developer-password routes,
    /// per source address. The confirmation guards the second factor, so it
    /// must not double as an unthrottled oracle for the panel password.
    private final LoginThrottle adminPasswordThrottle = new LoginThrottle();

    public SshApiServlet(SshService ssh, DeveloperAuth devAuth, DeveloperElevation elevation,
                         ServerConfig config, AuthService auth, BooleanSupplier tlsActive) {
        this.ssh = ssh;
        this.devAuth = devAuth;
        this.elevation = elevation;
        this.config = config;
        this.auth = auth;
        this.tlsActive = tlsActive;
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getPathInfo();
        if (path == null || path.isEmpty() || "/".equals(path)) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "not found");
            return;
        }
        String method = request.getMethod();
        switch (method) {
            case "GET" -> {
                if ("/status".equals(path)) {
                    status(request, response);
                } else if ("/keys".equals(path)) {
                    keys(request, response);
                } else {
                    Json.error(response, HttpServletResponse.SC_NOT_FOUND, "not found");
                }
            }
            case "POST" -> {
                switch (path) {
                    case "/elevate" -> elevate(request, response);
                    case "/dev-password" -> devPassword(request, response);
                    case "/dev-password/clear" -> devPasswordClear(request, response);
                    case "/toggle" -> toggle(request, response);
                    case "/bind" -> bind(request, response);
                    case "/keys" -> addKey(request, response);
                    case "/generate-keypair" -> generateKeypair(request, response);
                    case "/password-auth" -> passwordAuth(request, response);
                    default -> Json.error(response, HttpServletResponse.SC_NOT_FOUND, "not found");
                }
            }
            case "DELETE" -> {
                if ("/keys".equals(path)) {
                    removeKey(request, response);
                } else {
                    Json.error(response, HttpServletResponse.SC_NOT_FOUND, "not found");
                }
            }
            default -> Json.error(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "method not allowed");
        }
    }

    // ---------------------------------------------------------------- status --

    private void status(HttpServletRequest request, HttpServletResponse response) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("enabled", config.ssh.enabled);
        body.addProperty("running", ssh.isRunning());
        body.addProperty("bind", config.ssh.bindHost);
        body.addProperty("port", ssh.activePort());
        body.addProperty("verdict", verdictText());
        body.addProperty("supported", PlatformGuard.supported());
        body.addProperty("https", tlsActive.getAsBoolean());
        body.addProperty("passwordAuth", config.ssh.passwordAuth);
        body.addProperty("hostKeyFingerprint", ssh.hostKeyFingerprint());
        body.addProperty("devPasswordSet", devAuth.isSet());
        body.addProperty("connections", ssh.connections());
        body.addProperty("elevated", isElevated(keyOf(request)));
        Json.writePreservingNulls(response, body);
    }

    // --------------------------------------------------------------- elevate --

    private void elevate(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!requireHttps(request, response, "ssh.elevate")) {
            return;
        }
        JsonObject body = jsonBody(request);
        String password = body == null ? null : stringField(body, "password");
        if (password == null) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "request body must carry the developer password");
            return;
        }
        String key = keyOf(request);
        Instant now = Instant.now();
        if (elevation.isLocked(key, now)) {
            audit(request, "ssh.elevate", null, "blocked");
            Json.error(response, 429, "too many failed attempts; this session is locked for "
                    + DeveloperElevation.LOCK.toMinutes() + " minutes");
            return;
        }
        if (!devAuth.isSet()) {
            audit(request, "ssh.elevate", null, "failure");
            Json.error(response, HttpServletResponse.SC_CONFLICT, "developer password is not set; set it first");
            return;
        }
        if (!devAuth.verify(password)) {
            boolean locked = elevation.recordFailure(key, now);
            audit(request, "ssh.elevate", null, "failure");
            if (locked) {
                Json.error(response, 429, "too many failed attempts; this session is locked for "
                        + DeveloperElevation.LOCK.toMinutes() + " minutes");
            } else {
                Json.error(response, HttpServletResponse.SC_UNAUTHORIZED, "invalid developer password");
            }
            return;
        }
        elevation.elevate(key);
        elevation.clearFailures(key);
        audit(request, "ssh.elevate", null, "success");
        JsonObject answer = new JsonObject();
        answer.addProperty("elevated", true);
        answer.addProperty("expiresAt", now.plus(DeveloperElevation.TTL).toString());
        Json.write(response, answer);
    }

    /// The gate in front of every sensitive route: passes and slides the
    /// elevation forward; otherwise answers 403 and audits the blocked call.
    ///
    /// Elevation lives in [DeveloperElevation] and is keyed by the session
    /// token, so logging that session out — or revoking it with a password
    /// change — ends its elevation in the same instant, and one browser
    /// session's elevation is never inherited by another.
    private boolean requireElevation(HttpServletRequest request, HttpServletResponse response, String action)
            throws IOException {
        if (elevation.require(keyOf(request), sessionAlive(request))) {
            return true;
        }
        audit(request, action, null, "failure");
        Json.error(response, HttpServletResponse.SC_FORBIDDEN, "developer elevation required");
        return false;
    }

    private boolean isElevated(String key) {
        return elevation.isElevated(key, sessionAlive(key));
    }

    /// Whether the session a request carries is still alive. Requests without
    /// a session (authentication disabled, loopback-only) have nothing to
    /// outlive.
    private boolean sessionAlive(HttpServletRequest request) {
        Object token = request.getAttribute(AuthFilter.SESSION_TOKEN_ATTRIBUTE);
        return !(token instanceof String value) || value.isBlank() || auth.isAlive(value);
    }

    /// The same check when only the key is at hand: `token:<token>` embeds the
    /// session, anonymous keys have none.
    private boolean sessionAlive(String key) {
        if (!key.startsWith("token:")) {
            return true;
        }
        return auth.isAlive(key.substring("token:".length()));
    }

    private static String keyOf(HttpServletRequest request) {
        Object token = request.getAttribute(AuthFilter.SESSION_TOKEN_ATTRIBUTE);
        return DeveloperElevation.key(token instanceof String value ? value : null, request.getRemoteAddr());
    }

    // ---------------------------------------------------------- dev password --

    private void devPassword(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!requireHttps(request, response, "ssh.dev-password")) {
            return;
        }
        JsonObject body = jsonBody(request);
        String adminPassword = body == null ? null : stringField(body, "adminPassword");
        String password = body == null ? null : stringField(body, "password");
        String confirm = body == null ? null : stringField(body, "confirm");
        String current = body == null ? null : stringField(body, "currentDevPassword");
        if (adminPassword == null || password == null || confirm == null) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST,
                    "adminPassword, password and confirm are required");
            return;
        }
        UserStore users = auth.userStore();
        if (!confirmPanelPassword(request, response, adminPassword, "ssh.dev-password")) {
            return;
        }
        boolean changing = devAuth.isSet();
        if (changing && (current == null || !devAuth.verify(current))) {
            audit(request, "ssh.dev-password", null, "failure");
            Json.error(response, HttpServletResponse.SC_FORBIDDEN, "the current developer password does not match");
            return;
        }
        if (!password.equals(confirm)) {
            audit(request, "ssh.dev-password", null, "failure");
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "password and confirm do not match");
            return;
        }
        try {
            devAuth.set(password, users);
        } catch (IllegalArgumentException e) {
            audit(request, "ssh.dev-password", null, "failure");
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
            return;
        } catch (IOException e) {
            audit(request, "ssh.dev-password", null, "failure");
            Json.error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "the developer password could not be stored: " + e.getMessage());
            return;
        }
        // A new secret means every elevation minted against the old one dies.
        elevation.clearAll();
        audit(request, "ssh.dev-password", changing ? "change" : "set", "success");
        JsonObject answer = new JsonObject();
        answer.addProperty("ok", true);
        answer.addProperty("devPasswordSet", true);
        Json.write(response, answer);
    }

    /// Confirms the panel password with a per-address budget, answering the
    /// refusal itself.
    ///
    /// The developer-password routes are the only place the panel password is
    /// asked for outside the sign-in endpoint. Without a budget here this
    /// endpoint is an unthrottled oracle for the same secret — and every guess
    /// costs a PBKDF2 derivation, so it is also a CPU lever.
    private boolean confirmPanelPassword(HttpServletRequest request, HttpServletResponse response,
                                         String adminPassword, String action) throws IOException {
        String address = request.getRemoteAddr();
        String key = address == null || address.isBlank() ? "unknown" : address;
        Instant now = Instant.now();
        if (adminPasswordThrottle.isBlocked(key, now)) {
            audit(request, action, null, "blocked");
            refuseDeveloperAttempts(response, adminPasswordThrottle.block());
            return false;
        }
        UserStore users = auth.userStore();
        UserStore.User admin = users.admin();
        if (admin != null && users.verify(admin.username(), adminPassword)) {
            adminPasswordThrottle.clear(key);
            return true;
        }
        Duration refused = adminPasswordThrottle.recordFailure(key, Instant.now());
        audit(request, action, null, refused.isZero() ? "failure" : "blocked");
        if (refused.isZero()) {
            Json.error(response, HttpServletResponse.SC_FORBIDDEN, "the panel password does not match");
        } else {
            refuseDeveloperAttempts(response, refused);
        }
        return false;
    }

    private static void refuseDeveloperAttempts(HttpServletResponse response, Duration refusedFor)
            throws IOException {
        response.setHeader("Retry-After", Long.toString(Math.max(1, refusedFor.toSeconds())));
        Json.error(response, 429, "too many failed panel-password attempts; try again later");
    }

    private void devPasswordClear(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!requireHttps(request, response, "ssh.dev-password")) {
            return;
        }
        JsonObject body = jsonBody(request);
        String adminPassword = body == null ? null : stringField(body, "adminPassword");
        if (adminPassword == null) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "adminPassword is required");
            return;
        }
        if (!confirmPanelPassword(request, response, adminPassword, "ssh.dev-password")) {
            return;
        }
        try {
            devAuth.clear();
        } catch (IOException e) {
            audit(request, "ssh.dev-password", null, "failure");
            Json.error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "the developer password could not be cleared: " + e.getMessage());
            return;
        }
        elevation.clearAll();
        audit(request, "ssh.dev-password", "clear", "success");
        JsonObject answer = new JsonObject();
        answer.addProperty("ok", true);
        answer.addProperty("devPasswordSet", false);
        Json.write(response, answer);
    }

    // ---------------------------------------------------------------- toggle --

    private void toggle(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!requireHttps(request, response, "ssh.toggle")) {
            return;
        }
        if (!PlatformGuard.supported()) {
            audit(request, "ssh.toggle", null, "blocked");
            Json.error(response, HttpServletResponse.SC_CONFLICT, "platform_unsupported: "
                    + "the SSH channel is not supported on this platform");
            return;
        }
        if (!requireElevation(request, response, "ssh.toggle")) {
            return;
        }
        JsonObject body = jsonBody(request);
        Boolean enabled = body == null ? null : booleanField(body, "enabled");
        if (enabled == null) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "request body must carry a boolean `enabled`");
            return;
        }
        config.ssh.enabled = enabled;
        try {
            ServerConfigWriter.updateSsh(config.dataDir, config.ssh);
        } catch (IOException | RuntimeException e) {
            audit(request, "ssh.toggle", null, "failure");
            Json.error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "server.yaml could not be updated: " + e.getMessage());
            return;
        }
        if (enabled) {
            ssh.start();
        } else {
            ssh.stop();
        }
        audit(request, "ssh.toggle", String.valueOf(enabled), "success");
        JsonObject answer = new JsonObject();
        answer.addProperty("ok", true);
        answer.addProperty("enabled", config.ssh.enabled);
        answer.addProperty("running", ssh.isRunning());
        answer.addProperty("port", ssh.activePort());
        Json.write(response, answer);
    }

    // ------------------------------------------------------------------ bind --

    /// Moves the listener to another bind address. The address is persisted
    /// only after the new listener came up, and a failed bind restores the
    /// previous address so a typo never leaves the channel down.
    private void bind(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!requireHttps(request, response, "ssh.bind")) {
            return;
        }
        if (!PlatformGuard.supported()) {
            audit(request, "ssh.bind", null, "blocked");
            Json.error(response, HttpServletResponse.SC_CONFLICT, "platform_unsupported: "
                    + "the SSH channel is not supported on this platform");
            return;
        }
        if (!requireElevation(request, response, "ssh.bind")) {
            return;
        }
        JsonObject body = jsonBody(request);
        String requested = body == null ? null : stringField(body, "bind");
        if (requested == null || requested.isBlank()) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "request body must carry a non-empty `bind`");
            return;
        }
        requested = requested.trim();
        if (!validBindHost(requested)) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST,
                    "`bind` must be an IP address or host name no longer than 253 characters");
            return;
        }
        boolean wasRunning = ssh.isRunning();
        String previous = config.ssh.bindHost;
        config.ssh.bindHost = requested;
        if (wasRunning) {
            ssh.stop();
            ssh.start();
            if (!ssh.isRunning()) {
                config.ssh.bindHost = previous;
                ssh.start();
                audit(request, "ssh.bind", requested, "failure");
                Json.error(response, HttpServletResponse.SC_BAD_REQUEST,
                        "could not listen on " + requested + "; the previous address is still in use");
                return;
            }
        }
        try {
            ServerConfigWriter.updateSsh(config.dataDir, config.ssh);
        } catch (IOException | RuntimeException e) {
            config.ssh.bindHost = previous;
            if (wasRunning) {
                ssh.stop();
                ssh.start();
            }
            audit(request, "ssh.bind", requested, "failure");
            Json.error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "server.yaml could not be updated: " + e.getMessage());
            return;
        }
        audit(request, "ssh.bind", requested, "success");
        JsonObject answer = new JsonObject();
        answer.addProperty("ok", true);
        answer.addProperty("bind", config.ssh.bindHost);
        answer.addProperty("running", ssh.isRunning());
        answer.addProperty("port", ssh.activePort());
        Json.write(response, answer);
    }

    // ------------------------------------------------------------------ keys --

    private void keys(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!requireElevation(request, response, "ssh.keys")) {
            return;
        }
        List<AuthorizedKeys.Entry> entries;
        try {
            entries = ssh.authorizedKeys().list();
        } catch (IOException e) {
            audit(request, "ssh.keys", null, "failure");
            Json.error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "authorized_keys could not be read: " + e.getMessage());
            return;
        }
        JsonObject answer = new JsonObject();
        answer.add("keys", Json.GSON.toJsonTree(entries));
        Json.write(response, answer);
    }

    private void addKey(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!requireHttps(request, response, "ssh.key-add")) {
            return;
        }
        if (!requireElevation(request, response, "ssh.key-add")) {
            return;
        }
        JsonObject body = jsonBody(request);
        String key = body == null ? null : stringField(body, "key");
        if (key == null) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "request body must carry the public key line");
            return;
        }
        try {
            String fingerprint = AuthorizedKeys.fingerprint(key);
            ssh.authorizedKeys().add(key);
            audit(request, "ssh.key-add", fingerprint, "success");
        } catch (IllegalArgumentException e) {
            audit(request, "ssh.key-add", null, "failure");
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
            return;
        } catch (IOException e) {
            audit(request, "ssh.key-add", null, "failure");
            Json.error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "authorized_keys could not be updated: " + e.getMessage());
            return;
        }
        Json.write(response, java.util.Map.of("ok", true));
    }

    private void removeKey(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!requireElevation(request, response, "ssh.key-remove")) {
            return;
        }
        JsonObject body = jsonBody(request);
        Integer index = body == null ? null : intField(body, "index");
        if (index == null) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "request body must carry the key index");
            return;
        }
        try {
            ssh.authorizedKeys().remove(index);
            audit(request, "ssh.key-remove", String.valueOf(index), "success");
        } catch (IllegalArgumentException e) {
            audit(request, "ssh.key-remove", null, "failure");
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
            return;
        } catch (IOException e) {
            audit(request, "ssh.key-remove", null, "failure");
            Json.error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "authorized_keys could not be updated: " + e.getMessage());
            return;
        }
        Json.write(response, java.util.Map.of("ok", true));
    }

    // ------------------------------------------------------------ key generation --

    private void generateKeypair(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!requireHttps(request, response, "ssh.keygen")) {
            return;
        }
        if (!requireElevation(request, response, "ssh.keygen")) {
            return;
        }
        KeyPair pair;
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(3072);
            pair = generator.generateKeyPair();
        } catch (NoSuchAlgorithmException | RuntimeException e) {
            Json.error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "key generation failed: " + e.getMessage());
            return;
        }

        String privateKeyPem;
        String format;
        try {
            AsymmetricKeyParameter parameters = PrivateKeyFactory.createKey(pair.getPrivate().getEncoded());
            byte[] raw = OpenSSHPrivateKeyUtil.encodePrivateKey(parameters);
            // RSA encodes to PKCS#1 (what OpenSSH's classic "RSA PRIVATE KEY"
            // PEM is); anything else comes out as openssh-key-v1. OpenSSH's
            // client accepts both.
            boolean openSshV1 = raw.length > "openssh-key-v1".length()
                    && startsWith(raw, "openssh-key-v1\0".getBytes(StandardCharsets.US_ASCII));
            privateKeyPem = armor(openSshV1 ? "OPENSSH PRIVATE KEY" : "RSA PRIVATE KEY", raw);
            format = "openssh";
        } catch (Exception e) {
            // The contract's fallback: plain PKCS#8, still loadable by ssh -i.
            privateKeyPem = pkcs8Pem(pair.getPrivate());
            format = "pkcs8";
        }

        PublicKey publicKey = pair.getPublic();
        ByteArrayBuffer wire = new ByteArrayBuffer();
        wire.putPublicKey(publicKey);
        byte[] blob = wire.getCompactData();
        String publicLine = "ssh-rsa " + Base64.getEncoder().encodeToString(blob) + " hdsl-web-generated";
        String fingerprint;
        try {
            ssh.authorizedKeys().add(publicLine);
            fingerprint = AuthorizedKeys.fingerprint(publicLine);
        } catch (IllegalArgumentException | IOException e) {
            audit(request, "ssh.keygen", null, "failure");
            Json.error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "the generated key could not be authorized: " + e.getMessage());
            return;
        }
        audit(request, "ssh.keygen", fingerprint, "success");
        JsonObject answer = new JsonObject();
        answer.addProperty("publicKey", publicLine);
        answer.addProperty("privateKey", privateKeyPem);
        answer.addProperty("fingerprint", fingerprint);
        answer.addProperty("format", format);
        Json.write(response, answer);
    }

    // ----------------------------------------------------------- password auth --

    private void passwordAuth(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!requireHttps(request, response, "ssh.password-auth")) {
            return;
        }
        if (!requireElevation(request, response, "ssh.password-auth")) {
            return;
        }
        JsonObject body = jsonBody(request);
        Boolean enabled = body == null ? null : booleanField(body, "enabled");
        if (enabled == null) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "request body must carry a boolean `enabled`");
            return;
        }
        config.ssh.passwordAuth = enabled;
        try {
            ServerConfigWriter.updateSsh(config.dataDir, config.ssh);
        } catch (IOException | RuntimeException e) {
            audit(request, "ssh.password-auth", null, "failure");
            Json.error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "server.yaml could not be updated: " + e.getMessage());
            return;
        }
        // The offered user-auth methods are fixed when the listener starts,
        // so a running one restarts to add or drop "password".
        ssh.restartIfRunning();
        audit(request, "ssh.password-auth", String.valueOf(enabled), "success");
        JsonObject answer = new JsonObject();
        answer.addProperty("ok", true);
        answer.addProperty("passwordAuth", config.ssh.passwordAuth);
        if (enabled) {
            answer.addProperty("warning", PASSWORD_AUTH_WARNING);
        }
        Json.write(response, answer);
    }

    // ----------------------------------------------------------------- guards --

    /// The HTTPS guard: sensitive operations only exist while the panel's own
    /// connector speaks TLS (docs/ssh-terminal-channel.md, threat model row
    /// "敏感操作"). The check is the server's TLS state, never a header.
    private boolean requireHttps(HttpServletRequest request, HttpServletResponse response, String action)
            throws IOException {
        if (tlsActive.getAsBoolean()) {
            return true;
        }
        audit(request, action, null, "blocked");
        Json.error(response, HttpServletResponse.SC_CONFLICT,
                "tls_required: this operation needs the panel to be serving HTTPS; enable TLS first");
        return false;
    }

    private void audit(HttpServletRequest request, String action, @Nullable String object, String outcome) {
        AuditLog.log(AuditLog.Channel.WEB, action, object, outcome, actorOf(request), request.getRemoteAddr());
    }

    /// The session's username for the audit trail, or null when the request
    /// carries no session (authentication disabled).
    private static @Nullable String actorOf(HttpServletRequest request) {
        Object session = request.getAttribute(AuthFilter.SESSION_ATTRIBUTE);
        return session instanceof AuthService.Session s ? s.username() : null;
    }

    // --------------------------------------------------------------- helpers --

    private static @Nullable JsonObject jsonBody(HttpServletRequest request) throws IOException {
        try {
            return JsonParser.parseString(new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            return null;
        }
    }

    private static @Nullable String stringField(JsonObject body, String name) {
        var element = body.get(name);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                ? element.getAsString() : null;
    }

    private static @Nullable Boolean booleanField(JsonObject body, String name) {
        var element = body.get(name);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()
                ? element.getAsBoolean() : null;
    }

    private static @Nullable Integer intField(JsonObject body, String name) {
        var element = body.get(name);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()
                ? element.getAsInt() : null;
    }

    /// A bind value is an IPv4/IPv6 literal or a host name. Keep the character
    /// set tight — whitespace or path syntax must never reach sshd.
    private static boolean validBindHost(String host) {
        if (host.isEmpty() || host.length() > 253) {
            return false;
        }
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '.' || c == ':' || c == '-' || c == '_' || c == '%')) {
                return false;
            }
        }
        return true;
    }

    private static String verdictText() {
        return switch (PlatformGuard.verdict()) {
            case SUPPORTED -> "supported";
            case SUPPORTED_UNVERIFIED -> "supported_unverified";
            case UNSUPPORTED -> "unsupported";
        };
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /// One PEM armor block, 70-column base64 like OpenSSH writes it.
    private static String armor(String type, byte[] der) {
        String encoded = Base64.getMimeEncoder(70, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + encoded + "\n-----END " + type + "-----";
    }

    private static String pkcs8Pem(PrivateKey privateKey) throws IOException {
        StringWriter out = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(out)) {
            writer.writeObject(new JcaMiscPEMGenerator(privateKey));
        }
        return out.toString();
    }
}
