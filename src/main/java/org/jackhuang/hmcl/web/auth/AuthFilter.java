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
package org.jackhuang.hmcl.web.auth;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jackhuang.hmcl.web.http.Json;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Optional;

/// The session gate in front of `/api/*` and `/i/*`.
///
/// The public data endpoints are `POST /api/auth/login` (that is how a
/// session comes to exist), `GET /api/auth/status` and `POST /api/auth/setup`
/// (the first-run setup pair: the status tells the UI whether a user exists,
/// the setup creates the first user and is itself guarded against running
/// twice) and `GET /api/health` (liveness probes must work without
/// credentials). Everything else needs a valid `hdsl_session` cookie;
/// otherwise the answer is 401 `{"error":"unauthorized"}`. A verified request
/// carries the session as the `session` request attribute for downstream
/// handlers.
///
/// Static UI assets are not behind this filter on purpose: the login page
/// must be reachable before a session exists, and the shell carries no
/// sensitive information — this is the engineering reading of the plan's
/// "everything needs a session": all data planes do, the UI shell does not.
@NotNullByDefault
public final class AuthFilter implements Filter {

    public static final String SESSION_COOKIE = "hdsl_session";
    public static final String SESSION_ATTRIBUTE = "session";

    public static final String LOGIN_PATH = "/api/auth/login";
    public static final String STATUS_PATH = "/api/auth/status";
    public static final String SETUP_PATH = "/api/auth/setup";
    public static final String HEALTH_PATH = "/api/health";

    private final AuthService authService;
    private final boolean authDisabled;
    private final java.util.function.BooleanSupplier secureCookies;
    private final int cookieMaxAgeSeconds;

    /// @param authDisabled  when true, the filter passes everything through
    ///                      without a session (only legal on loopback binds)
    /// @param secureCookies whether the session cookie gets the `Secure` flag;
    ///                      read per cookie, because the certificate upload can
    ///                      switch the main connector to TLS at runtime and a
    ///                      login after the switch must mint a Secure cookie
    /// @param ttlSeconds    session TTL, also the cookie Max-Age
    public AuthFilter(AuthService authService, boolean authDisabled,
                      java.util.function.BooleanSupplier secureCookies, int ttlSeconds) {
        this.authService = authService;
        this.authDisabled = authDisabled;
        this.secureCookies = secureCookies;
        this.cookieMaxAgeSeconds = ttlSeconds;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;

        if (authDisabled || isPublic(req)) {
            chain.doFilter(request, response);
            return;
        }

        String token = cookieValue(req, SESSION_COOKIE);
        Optional<AuthService.Session> session = token == null ? Optional.empty() : authService.verify(token);
        if (session.isPresent()) {
            req.setAttribute(SESSION_ATTRIBUTE, session.get());
            chain.doFilter(request, response);
        } else {
            Json.error(resp, HttpServletResponse.SC_UNAUTHORIZED, "unauthorized");
        }
    }

    /// Builds the session cookie. Shared with the login endpoint so the
    /// attributes can never drift apart between the two places that set it.
    public Cookie sessionCookie(String token) {
        Cookie cookie = new Cookie(SESSION_COOKIE, token);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setMaxAge(cookieMaxAgeSeconds);
        cookie.setSecure(secureCookies.getAsBoolean());
        cookie.setAttribute("SameSite", "Strict");
        return cookie;
    }

    /// A cookie with the same name/path and zero Max-Age, which is what makes
    /// the browser drop it.
    public Cookie clearedSessionCookie() {
        Cookie cookie = new Cookie(SESSION_COOKIE, "");
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setMaxAge(0);
        cookie.setSecure(secureCookies.getAsBoolean());
        cookie.setAttribute("SameSite", "Strict");
        return cookie;
    }

    private static boolean isPublic(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        return ("POST".equals(method) && LOGIN_PATH.equals(path))
                || ("GET".equals(method) && STATUS_PATH.equals(path))
                || ("POST".equals(method) && SETUP_PATH.equals(path))
                || ("GET".equals(method) && HEALTH_PATH.equals(path));
    }

    private static @Nullable String cookieValue(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
