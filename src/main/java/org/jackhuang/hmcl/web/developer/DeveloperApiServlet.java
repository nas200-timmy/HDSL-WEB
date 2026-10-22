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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jackhuang.hmcl.web.auth.AuthFilter;
import org.jackhuang.hmcl.web.auth.AuthService;
import org.jackhuang.hmcl.web.http.Json;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.function.BooleanSupplier;

/// The developer mode's own door: `POST /api/developer/elevate` and
/// `GET /api/developer/status`.
///
/// It lives outside the SSH package on purpose: the web terminal needs the
/// same second factor, and the default build still carries this endpoint. The
/// SSH settings servlet keeps its `/api/ssh/elevate` route for compatibility,
/// backed by the same [DeveloperElevation] instance.
///
/// Elevation requires TLS: the password is a secret and the panel already
/// refuses to manage the second factor over cleartext elsewhere.
@NotNullByDefault
public final class DeveloperApiServlet extends HttpServlet {

    private final DeveloperAuth devAuth;
    private final DeveloperElevation elevation;
    private final AuthService auth;
    private final BooleanSupplier tlsActive;

    public DeveloperApiServlet(DeveloperAuth devAuth, DeveloperElevation elevation,
                               AuthService auth, BooleanSupplier tlsActive) {
        this.devAuth = devAuth;
        this.elevation = elevation;
        this.auth = auth;
        this.tlsActive = tlsActive;
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getPathInfo();
        if ("/status".equals(path) && "GET".equals(request.getMethod())) {
            JsonObject body = new JsonObject();
            body.addProperty("devPasswordSet", devAuth.isSet());
            body.addProperty("elevated", elevation.isElevated(keyOf(request), sessionAlive(request)));
            Json.write(response, body);
            return;
        }
        if ("/elevate".equals(path) && "POST".equals(request.getMethod())) {
            elevate(request, response);
            return;
        }
        Json.error(response, HttpServletResponse.SC_NOT_FOUND, "not found");
    }

    private void elevate(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!tlsActive.getAsBoolean()) {
            Json.error(response, HttpServletResponse.SC_CONFLICT,
                    "tls_required: the developer password is only accepted over HTTPS");
            return;
        }
        String password = readPassword(request);
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
                    "developer password is not set; set it in the SSH settings first");
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
        Json.write(response, body);
    }

    private static String readPassword(HttpServletRequest request) throws IOException {
        try (InputStreamReader reader = new InputStreamReader(request.getInputStream(), StandardCharsets.UTF_8)) {
            JsonObject body = JsonParser.parseReader(reader).getAsJsonObject();
            return body.has("password") && !body.get("password").isJsonNull()
                    ? body.get("password").getAsString()
                    : null;
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
