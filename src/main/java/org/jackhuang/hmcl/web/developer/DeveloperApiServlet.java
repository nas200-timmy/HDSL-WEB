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
package org.jackhuang.hmcl.web.developer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jackhuang.hmcl.web.audit.AuditLog;
import org.jackhuang.hmcl.web.auth.AuthFilter;
import org.jackhuang.hmcl.web.auth.AuthService;
import org.jackhuang.hmcl.web.auth.LoginThrottle;
import org.jackhuang.hmcl.web.auth.UserStore;
import org.jackhuang.hmcl.web.config.ServerConfig;
import org.jackhuang.hmcl.web.config.ServerConfigWriter;
import org.jackhuang.hmcl.web.emergency.EmergencyStop;
import org.jackhuang.hmcl.web.http.Json;
import org.jackhuang.hmcl.web.http.RequestSecurity;
import org.jackhuang.hmcl.web.instance.InstanceRuntime;
import org.jackhuang.hmcl.web.terminal.TerminalManager;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;

/// The developer mode's own door: the second factor behind the web terminal.
///
/// `POST /api/developer/elevate` proves the password; `GET /api/developer/status`
/// answers whether the caller is elevated (and when that expires) and whether
/// the Web Shell is on; `POST /api/developer/extend` stretches an existing
/// elevation to [DeveloperElevation.EXTENDED_TTL]; `POST /api/developer/revoke`
/// drops it early; `POST /api/developer/password` sets or changes it (current
/// developer password + panel password confirmation, like the TLS settings
/// route); `POST /api/developer/password/clear` removes it;
/// `POST /api/developer/web-shell` flips the instance-terminal switch.
///
/// The routes are backed by [DeveloperElevation] and [DeveloperAuth], which
/// the terminal's own gate shares.
@NotNullByDefault
public final class DeveloperApiServlet extends HttpServlet {

    private final DeveloperAuth devAuth;
    private final DeveloperElevation elevation;
    private final AuthService auth;
    private final ServerConfig config;
    private final BooleanSupplier tlsActive;
    private final EmergencyStop emergency;
    private final TerminalManager terminals;
    private final InstanceRuntime runtime;
    /// Failed panel-password confirmations, per source address — the second
    /// confirmation must not become an unthrottled oracle for the panel
    /// password.
    private final LoginThrottle adminPasswordThrottle = new LoginThrottle();
    /// Whether the "plain HTTP" warning has already been written this boot.
    private final java.util.concurrent.atomic.AtomicBoolean insecureWarned =
            new java.util.concurrent.atomic.AtomicBoolean();

    public DeveloperApiServlet(DeveloperAuth devAuth, DeveloperElevation elevation,
                               AuthService auth, ServerConfig config, BooleanSupplier tlsActive,
                               EmergencyStop emergency, TerminalManager terminals, InstanceRuntime runtime) {
        this.devAuth = devAuth;
        this.elevation = elevation;
        this.auth = auth;
        this.config = config;
        this.tlsActive = tlsActive;
        this.emergency = emergency;
        this.terminals = terminals;
        this.runtime = runtime;
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getPathInfo();
        String method = request.getMethod();
        if ("/status".equals(path) && "GET".equals(method)) {
            String key = keyOf(request);
            JsonObject body = new JsonObject();
            body.addProperty("devPasswordSet", devAuth.isSet());
            body.addProperty("elevated", elevation.isElevated(key, sessionAlive(request)));
            Instant expiry = elevation.expiryOf(key, sessionAlive(request));
            if (expiry != null) {
                body.addProperty("expiresAt", expiry.toString());
            }
            body.addProperty("webShellEnabled", config.webShell.enabled);
            body.addProperty("https",
                    RequestSecurity.isSecure(request, tlsActive.getAsBoolean(), config));
            body.addProperty("guardScope", config.developerGuard.scope.wireName());
            body.add("estop", estopState());
            Json.write(response, body);
            return;
        }
        if ("/guard".equals(path) && "POST".equals(method)) {
            setGuard(request, response);
            return;
        }
        if ("/estop/preview".equals(path) && "GET".equals(method)) {
            estopPreview(request, response);
            return;
        }
        if ("/estop/engage".equals(path) && "POST".equals(method)) {
            estopEngage(request, response);
            return;
        }
        if ("/estop/release".equals(path) && "POST".equals(method)) {
            estopRelease(request, response);
            return;
        }
        if ("/elevate".equals(path) && "POST".equals(method)) {
            elevate(request, response);
            return;
        }
        if ("/extend".equals(path) && "POST".equals(method)) {
            extend(request, response);
            return;
        }
        if ("/revoke".equals(path) && "POST".equals(method)) {
            revoke(request, response);
            return;
        }
        if ("/password".equals(path) && "POST".equals(method)) {
            setPassword(request, response);
            return;
        }
        if ("/password/clear".equals(path) && "POST".equals(method)) {
            clearPassword(request, response);
            return;
        }
        if ("/web-shell".equals(path) && "POST".equals(method)) {
            setWebShell(request, response);
            return;
        }
        Json.error(response, HttpServletResponse.SC_NOT_FOUND, "not found");
    }

    private void elevate(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!requireTls(request, response)) {
            return;
        }
        String password = stringField(readJson(request), "password");
        if (password == null) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "password is required");
            return;
        }
        String key = keyOf(request);
        Instant now = Instant.now();
        if (elevation.isLocked(key, now)) {
            Json.error(response, 429, "too many failed attempts; this session is locked for "
                    + DeveloperElevation.LOCK.toMinutes() + " minutes");
            return;
        }
        if (!devAuth.isSet()) {
            Json.error(response, HttpServletResponse.SC_CONFLICT,
                    "developer_password_not_set: set it before opening a terminal");
            return;
        }
        if (!devAuth.verify(password)) {
            boolean locked = elevation.recordFailure(key, now);
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
        JsonObject body = new JsonObject();
        body.addProperty("elevated", true);
        body.addProperty("expiresAt", now.plus(DeveloperElevation.TTL).toString());
        // A bearer token for callers that cannot keep the session cookie: a host
        // script, an inspection agent, CI. Same TTL and sliding rules as the
        // cookie elevation, in memory only, and killed by a password change.
        body.addProperty("token", elevation.mintToken());
        Json.write(response, body);
    }

    /// The developer page's 「延长提权」: an already-elevated session spends
    /// its ticket's single extension coupon and stretches itself to
    /// [DeveloperElevation.EXTENDED_TTL] without re-typing the password. The
    /// coupon is one-shot by design — a second stretch takes a fresh password
    /// proof — and extending requires the elevation it lengthens, otherwise
    /// the endpoint would be a second, password-less mint.
    private void extend(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!requireTls(request, response)) {
            return;
        }
        String key = keyOf(request);
        if (!elevation.require(key, sessionAlive(request))) {
            Json.error(response, HttpServletResponse.SC_FORBIDDEN,
                    "developer_elevation_required: elevate with the developer password first");
            return;
        }
        Instant expiry = elevation.extend(key, DeveloperElevation.EXTENDED_TTL);
        if (expiry == null) {
            // require() 刚放行并滑动过有效期，能走到这的只有「延长过一次」。
            Json.error(response, HttpServletResponse.SC_FORBIDDEN,
                    "developer_elevation_extended: 这次提权已经延长过一次，重新输入开发者密码后可再次延长");
            return;
        }
        AuditLog.log(AuditLog.Channel.WEB, "developer.elevation", "extend", "success",
                actorOf(request), request.getRemoteAddr());
        JsonObject body = new JsonObject();
        body.addProperty("elevated", true);
        body.addProperty("expiresAt", expiry.toString());
        Json.write(response, body);
    }

    /// The developer page's 「停止提权」: drops this session's elevation
    /// immediately, ahead of its expiry. Deliberately **not** gated on the
    /// elevation itself — de-escalation must work even when (or exactly
    /// because) the elevation just expired, and clearing an absent key is a
    /// no-op by nature.
    private void revoke(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!requireTls(request, response)) {
            return;
        }
        String key = keyOf(request);
        elevation.clear(key);
        AuditLog.log(AuditLog.Channel.WEB, "developer.elevation", "revoke", "success",
                actorOf(request), request.getRemoteAddr());
        JsonObject body = new JsonObject();
        body.addProperty("elevated", false);
        Json.write(response, body);
    }

    private void setPassword(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!requireTls(request, response)) {
            return;
        }
        JsonObject body = readJson(request);
        String adminPassword = stringField(body, "adminPassword");
        String password = stringField(body, "password");
        String confirm = stringField(body, "confirm");
        String current = stringField(body, "currentDevPassword");
        if (adminPassword == null || password == null || confirm == null) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST,
                    "adminPassword, password and confirm are required");
            return;
        }
        UserStore users = auth.userStore();
        if (!confirmPanelPassword(request, response, users, adminPassword)) {
            return;
        }
        boolean changing = devAuth.isSet();
        if (changing && (current == null || !devAuth.verify(current))) {
            Json.error(response, HttpServletResponse.SC_FORBIDDEN,
                    "the current developer password does not match");
            return;
        }
        if (!password.equals(confirm)) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "password and confirm do not match");
            return;
        }
        try {
            devAuth.set(password, users);
        } catch (IllegalArgumentException e) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
            return;
        } catch (IOException e) {
            Json.error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "the developer password could not be stored: " + e.getMessage());
            return;
        }
        // A new secret means every elevation minted against the old one dies.
        elevation.clearAll();
        JsonObject answer = new JsonObject();
        answer.addProperty("ok", true);
        answer.addProperty("devPasswordSet", true);
        Json.write(response, answer);
    }

    private void clearPassword(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!requireTls(request, response)) {
            return;
        }
        String adminPassword = stringField(readJson(request), "adminPassword");
        if (adminPassword == null) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "adminPassword is required");
            return;
        }
        if (!confirmPanelPassword(request, response, auth.userStore(), adminPassword)) {
            return;
        }
        try {
            devAuth.clear();
        } catch (IOException e) {
            Json.error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "the developer password could not be cleared: " + e.getMessage());
            return;
        }
        elevation.clearAll();
        JsonObject answer = new JsonObject();
        answer.addProperty("ok", true);
        answer.addProperty("devPasswordSet", false);
        Json.write(response, answer);
    }

    /// Flips the Web Shell switch. Requires the developer elevation (the same
    /// proof a terminal needs) and HTTPS, because this is the switch that
    /// decides whether an RCE-equivalent entry is reachable at all.
    private void setWebShell(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!requireTls(request, response)) {
            return;
        }
        if (!elevation.require(keyOf(request), sessionAlive(request))) {
            Json.error(response, HttpServletResponse.SC_FORBIDDEN,
                    "developer_elevation_required: elevate with the developer password first");
            return;
        }
        Boolean enabled = booleanField(readJson(request), "enabled");
        if (enabled == null) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "enabled is required");
            return;
        }
        try {
            ServerConfigWriter.updateWebShell(config.dataDir, enabled);
        } catch (IOException e) {
            Json.error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "the web shell switch could not be stored: " + e.getMessage());
            return;
        }
        config.webShell.enabled = enabled;
        AuditLog.log(AuditLog.Channel.WEB, "developer.web-shell", enabled ? "on" : "off", "success",
                actorOf(request), request.getRemoteAddr());
        JsonObject answer = new JsonObject();
        answer.addProperty("ok", true);
        answer.addProperty("enabled", enabled);
        Json.write(response, answer);
    }

    private static @Nullable String actorOf(HttpServletRequest request) {
        Object session = request.getAttribute(AuthFilter.SESSION_ATTRIBUTE);
        return session instanceof AuthService.Session value ? value.username() : null;
    }

    /// Flips the developer guard's scope (docs/web-shell.md §4.1).
    ///
    /// Same proof as the Web Shell switch — TLS and an elevation — because this
    /// is the switch that decides how far an elevation reaches. Turning it on
    /// without a developer password is refused: nobody could clear it again.
    private void setGuard(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!requireTls(request, response)) {
            return;
        }
        if (!elevation.require(keyOf(request), sessionAlive(request))) {
            Json.error(response, HttpServletResponse.SC_FORBIDDEN,
                    "developer_elevation_required: elevate with the developer password first");
            return;
        }
        org.jackhuang.hmcl.web.config.DeveloperGuardScope scope =
                org.jackhuang.hmcl.web.config.DeveloperGuardScope.parse(stringField(readJson(request), "scope"));
        if (scope == null) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST,
                    "scope must be one of off, proxy, instance, all");
            return;
        }
        if (scope != org.jackhuang.hmcl.web.config.DeveloperGuardScope.OFF && !devAuth.isSet()) {
            // Without a password the gate could never be satisfied, so the
            // instances would be unreachable until someone edits server.yaml.
            Json.error(response, HttpServletResponse.SC_CONFLICT,
                    "developer_password_not_set: set the developer password before turning the guard on");
            return;
        }
        try {
            ServerConfigWriter.updateDeveloperGuard(config.dataDir, scope);
        } catch (IOException e) {
            Json.error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "the developer guard could not be stored: " + e.getMessage());
            return;
        }
        config.developerGuard.scope = scope;
        AuditLog.log(AuditLog.Channel.WEB, "developer.guard", scope.wireName(), "success",
                actorOf(request), request.getRemoteAddr());
        JsonObject answer = new JsonObject();
        answer.addProperty("ok", true);
        answer.addProperty("scope", scope.wireName());
        Json.write(response, answer);
    }

    /// The emergency stop's state, for `/status` and the banners.
    private JsonObject estopState() {
        JsonObject state = new JsonObject();
        boolean engaged = emergency.isEngaged();
        state.addProperty("engaged", engaged);
        if (!engaged) {
            state.addProperty("remainingSeconds", 0);
            return state;
        }
        Instant engagedAt = emergency.engagedAt();
        Instant releasesAt = emergency.releasesAt();
        if (engagedAt != null) {
            state.addProperty("engagedAt", engagedAt.toString());
        }
        if (releasesAt != null) {
            state.addProperty("releasesAt", releasesAt.toString());
        }
        state.addProperty("remainingSeconds", Math.max(0, emergency.remaining(Instant.now()).toSeconds()));
        String reason = emergency.reason();
        if (reason != null && !reason.isBlank()) {
            state.addProperty("reason", reason);
        }
        return state;
    }

    /// What the E-Stop is about to kill, so the confirmation can list it.
    ///
    /// The list is a snapshot: an instance started between this call and the
    /// engage is still killed, because the gate closes before the killing
    /// starts.
    private void estopPreview(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!requireTls(request, response)) {
            return;
        }
        if (!elevation.require(keyOf(request), sessionAlive(request))) {
            Json.error(response, HttpServletResponse.SC_FORBIDDEN,
                    "developer_elevation_required: elevate with the developer password first");
            return;
        }
        JsonObject body = new JsonObject();
        body.addProperty("engagable", !emergency.isEngaged());
        body.add("estop", estopState());

        JsonArray instances = new JsonArray();
        int processes = 0;
        for (org.jackhuang.hmcl.dsh.DshProcess process : runtime.runningProcesses()) {
            JsonObject row = new JsonObject();
            row.addProperty("id", process.instance().id());
            row.addProperty("brand", "dsh");
            row.addProperty("state", runtime.stateOf(process.instance().id()));
            row.addProperty("pids", process.processTreeSize());
            instances.add(row);
            processes += process.processTreeSize();
        }
        JsonArray terminalRows = new JsonArray();
        int terminals = 0;
        for (org.jackhuang.hmcl.web.terminal.TerminalManager.Tracked terminal : this.terminals.tracked()) {
            JsonObject row = new JsonObject();
            row.addProperty("brand", terminal.brand());
            row.addProperty("instance", terminal.instanceId());
            row.addProperty("running", terminal.running());
            terminalRows.add(row);
            if (terminal.running()) {
                terminals++;
            }
        }
        body.add("instances", instances);
        body.add("terminals", terminalRows);
        body.addProperty("processCount", processes);
        body.addProperty("terminalCount", terminals);
        Json.write(response, body);
    }

    /// The brake: state first, then the work (see [EmergencyStop]).
    private void estopEngage(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!requireTls(request, response)) {
            return;
        }
        if (!elevation.require(keyOf(request), sessionAlive(request))) {
            Json.error(response, HttpServletResponse.SC_FORBIDDEN,
                    "developer_elevation_required: elevate with the developer password first");
            return;
        }
        JsonObject input = readJson(request);
        if (!"ENGAGE-EMERGENCY-STOP".equals(stringField(input, "confirm"))) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST,
                    "confirmation_required: send {\"confirm\":\"ENGAGE-EMERGENCY-STOP\"}");
            return;
        }
        String reason = stringField(input, "reason");
        boolean engagedNow = emergency.engage(String.valueOf(actorOf(request)), reason, Instant.now());
        if (!engagedNow) {
            // Already engaged: the hour must not restart, and nothing is killed
            // a second time.
            JsonObject answer = estopState();
            answer.addProperty("alreadyEngaged", true);
            Json.write(response, answer);
            return;
        }
        int terminalsClosed = terminals.terminateAll();
        int processes = runtime.killAllForcibly();
        AuditLog.log(AuditLog.Channel.WEB, "estop.engage",
                "terminals=" + terminalsClosed + " processes=" + processes
                        + (reason == null || reason.isBlank() ? "" : " reason=" + reason),
                "success", actorOf(request), request.getRemoteAddr());
        JsonObject answer = estopState();
        answer.addProperty("terminals", terminalsClosed);
        answer.addProperty("processes", processes);
        Json.write(response, answer);
    }

    /// Lifts the stop, but only after the hour has run out.
    private void estopRelease(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!requireTls(request, response)) {
            return;
        }
        if (!elevation.require(keyOf(request), sessionAlive(request))) {
            Json.error(response, HttpServletResponse.SC_FORBIDDEN,
                    "developer_elevation_required: elevate with the developer password first");
            return;
        }
        Instant now = Instant.now();
        Instant endedAt = emergency.release(now);
        if (endedAt == null) {
            JsonObject refusal = new JsonObject();
            refusal.addProperty("error", emergency.isEngaged()
                    ? "estop_cooldown: the emergency stop cannot be released yet"
                    : "estop_not_engaged: the panel is not in emergency stop");
            refusal.addProperty("remainingSeconds", emergency.remaining(now).toSeconds());
            Json.write(response, HttpServletResponse.SC_CONFLICT, refusal);
            return;
        }
        AuditLog.log(AuditLog.Channel.WEB, "estop.release",
                "held for " + java.time.Duration.between(endedAt, now).toMinutes() + " minutes",
                "success", actorOf(request), request.getRemoteAddr());
        JsonObject answer = new JsonObject();
        answer.addProperty("engaged", false);
        Json.write(response, answer);
    }

    /// Confirms the panel password with a per-address budget, answering the
    /// refusal itself.
    private boolean confirmPanelPassword(HttpServletRequest request, HttpServletResponse response,
                                         UserStore users, String adminPassword) throws IOException {
        String address = request.getRemoteAddr();
        String key = address == null || address.isBlank() ? "unknown" : address;
        Instant now = Instant.now();
        if (adminPasswordThrottle.isBlocked(key, now)) {
            refuse(response, adminPasswordThrottle.block());
            return false;
        }
        UserStore.User admin = users.admin();
        if (admin != null && users.verify(admin.username(), adminPassword)) {
            adminPasswordThrottle.clear(key);
            return true;
        }
        Duration refused = adminPasswordThrottle.recordFailure(key, Instant.now());
        if (refused.isZero()) {
            Json.error(response, HttpServletResponse.SC_FORBIDDEN, "the panel password does not match");
        } else {
            refuse(response, refused);
        }
        return false;
    }

    private static void refuse(HttpServletResponse response, Duration refusedFor) throws IOException {
        response.setHeader("Retry-After", Long.toString(Math.max(1, refusedFor.toSeconds())));
        Json.error(response, 429, "too many failed panel-password attempts; try again later");
    }

    /// Whether the developer surfaces may proceed on this request.
    ///
    /// Default: **warn, do not block.** A panel served over plain HTTP is an
    /// operator who decided the wire is not a threat; refusing to let them in
    /// would leave them unable to repair the panel that is right there. The
    /// warning is logged once per boot (below) and repeated in the UI.
    ///
    /// `security.require_https_for_developer: true` restores the v0.5.94
    /// behaviour: `409 tls_required`, and the developer password never travels
    /// in cleartext.
    private boolean requireTls(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (RequestSecurity.isSecure(request, tlsActive.getAsBoolean(), config)) {
            return true;
        }
        if (!config.security.requireHttpsForDeveloper) {
            warnInsecureOnce();
            return true;
        }
        Json.error(response, HttpServletResponse.SC_CONFLICT,
                "tls_required: the developer password is only accepted over HTTPS");
        return false;
    }

    /// One line per boot: the operator should see it in the log even if nobody
    /// reads the page.
    private void warnInsecureOnce() {
        if (insecureWarned.compareAndSet(false, true)) {
            org.jackhuang.hmcl.util.logging.Logger.LOG.warning(
                    "Developer mode over plain HTTP: the developer password and terminal input are readable "
                            + "on the wire. Enable HTTPS, or set security.require_https_for_developer: true "
                            + "to refuse instead.");
        }
    }

    private static @Nullable JsonObject readJson(HttpServletRequest request) throws IOException {
        try (InputStreamReader reader = new InputStreamReader(request.getInputStream(), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static @Nullable String stringField(@Nullable JsonObject body, String field) {
        return body != null && body.has(field) && !body.get(field).isJsonNull()
                ? body.get(field).getAsString()
                : null;
    }

    private static @Nullable Boolean booleanField(@Nullable JsonObject body, String field) {
        if (body == null || !body.has(field) || body.get(field).isJsonNull()) {
            return null;
        }
        try {
            return body.get(field).getAsBoolean();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private boolean sessionAlive(HttpServletRequest request) {
        Object token = request.getAttribute(AuthFilter.SESSION_TOKEN_ATTRIBUTE);
        return !(token instanceof String value) || value.isBlank() || auth.isAlive(value);
    }

    private static String keyOf(HttpServletRequest request) {
        Object token = request.getAttribute(AuthFilter.SESSION_TOKEN_ATTRIBUTE);
        return DeveloperElevation.key(token instanceof String value ? value : null, request.getRemoteAddr());
    }
}
