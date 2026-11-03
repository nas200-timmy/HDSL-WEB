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
    /// The raw token behind [SESSION_ATTRIBUTE]. The developer-mode elevation
    /// is keyed by it, so that logging one session out logs its elevation out
    /// too.
    public static final String SESSION_TOKEN_ATTRIBUTE = "session.token";
    /// The [org.jackhuang.hmcl.web.developer.DeveloperElevation.AgentGrant]
    /// behind an agent bearer token, once this filter has admitted one.
    public static final String AGENT_GRANT_ATTRIBUTE = "session.agent";
    public static final String LOGIN_PATH = "/api/auth/login";
    public static final String STATUS_PATH = "/api/auth/status";
    public static final String SETUP_PATH = "/api/auth/setup";
    public static final String HEALTH_PATH = "/api/health";
    /// The agent password's door. Reachable without a session on purpose: a
    /// curl one-liner must be able to use the lesser secret, and it grants
    /// strictly less than this cookie ever would.
    public static final String AGENT_AUTH_PATH = "/api/developer/agent-auth";

    private final AuthService authService;
    private final boolean authDisabled;
    private final java.util.function.Predicate<HttpServletRequest> secureRequest;
    private final int cookieMaxAgeSeconds;
    /// The grants behind agent bearer tokens: an agent has no browser, so the
    /// cookie is not a door it can use.
    private final org.jackhuang.hmcl.web.developer.DeveloperElevation elevation;
    /// 会话 Cookie 的 Path：根挂载是 `/`，子路径挂载是挂载点本身。
    /// 同一个域名下挂两个面板（`/a/`、`/b/`）时，Path=/ 的同名 Cookie 会互相覆盖，
    /// 表现为「登录 A 之后 B 掉线」。
    private final String cookiePath;

    /// @param authDisabled  when true, the filter passes everything through
    ///                      without a session (only legal on loopback binds)
    /// @param secureRequest whether this request's browser leg is HTTPS; read
    ///                      per cookie, because the certificate upload can
    ///                      switch the main connector to TLS at runtime and a
    ///                      login after the switch must mint a Secure cookie.
    ///                      A TLS-terminating proxy counts when the server is
    ///                      told to trust `X-Forwarded-Proto`.
    /// @param ttlSeconds    session TTL, also the cookie Max-Age
    /// @param basePath      the mount point the panel is served under ("" for
    ///                      the root). The cookie is scoped to it, so two panels
    ///                      mounted on one origin (`/a/` and `/b/`) do not
    ///                      overwrite each other's session.
    /// @param elevation     the agent grants behind bearer tokens
    public AuthFilter(AuthService authService, boolean authDisabled,
                      java.util.function.Predicate<HttpServletRequest> secureRequest, int ttlSeconds,
                      String basePath,
                      org.jackhuang.hmcl.web.developer.DeveloperElevation elevation) {
        this.authService = authService;
        this.authDisabled = authDisabled;
        this.secureRequest = secureRequest;
        this.cookieMaxAgeSeconds = ttlSeconds;
        this.cookiePath = basePath == null || basePath.isBlank() ? "/" : basePath;
        this.elevation = elevation;
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
            req.setAttribute(SESSION_TOKEN_ATTRIBUTE, token);
            chain.doFilter(request, response);
            return;
        }

        Boolean agent = admitAgent(req, resp);
        if (agent != null) {
            if (agent) {
                chain.doFilter(request, response);
            }
            return;
        }
        Json.error(resp, HttpServletResponse.SC_UNAUTHORIZED, "unauthorized");
    }

    /// Admits a request carrying an agent bearer token, or answers the refusal.
    ///
    /// This is the panel's own gate for callers that are not a browser: the
    /// grant behind the token says **what** may be reached
    /// ([org.jackhuang.hmcl.web.developer.AgentScopes]), and the two refusals
    /// below say **what never may be**, whatever the grant claims.
    ///
    /// The session attribute is filled with a synthetic `agent:<label>`
    /// session so every existing handler and the audit trail name the agent
    /// rather than the operator whose password minted the token. That is the
    /// whole point of the label: `audit.jsonl` must be able to answer "who did
    /// this" without a second table.
    ///
    /// @param req  the request
    /// @param resp the response to write a refusal to
    /// @return `null` when no agent token is present, `true` when the request
    ///         may continue, `false` when a refusal has been written
    private @Nullable Boolean admitAgent(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String header = req.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String bearer = header.substring(7).trim();
        var grant = elevation.agentGrant(bearer);
        if (grant == null) {
            // Unknown, expired and revoked tokens all read as "not an agent":
            // the caller gets the same 401 as a missing cookie, so a probe
            // cannot tell a wrong token from a dead one.
            return null;
        }
        String method = req.getMethod();
        String path = req.getRequestURI();
        if (org.jackhuang.hmcl.web.developer.AgentScopes.forbidden(method, path)) {
            Json.error(resp, HttpServletResponse.SC_FORBIDDEN, "agent_scope_denied: this route is closed to agents");
            return false;
        }
        String needed = org.jackhuang.hmcl.web.developer.AgentScopes.required(method, path);
        if (needed == null || !grant.scopes().contains(needed)) {
            Json.error(resp, HttpServletResponse.SC_FORBIDDEN,
                    "agent_scope_denied: " + (needed == null ? "this route has no agent scope" : needed));
            return false;
        }
        if (org.jackhuang.hmcl.web.developer.AgentScopes.selfFatal(method, path, grant.instanceId())) {
            // The tool call could never return: the process serving it is the
            // one being stopped. Refusing is honest; queueing would promise an
            // executor that no longer exists.
            Json.error(resp, HttpServletResponse.SC_CONFLICT,
                    "self_operation_refused: an agent cannot stop or take over its own instance");
            return false;
        }
        req.setAttribute(SESSION_ATTRIBUTE, new AuthService.Session("agent:" + grant.label(), grant.expiresAt()));
        req.setAttribute(AGENT_GRANT_ATTRIBUTE, grant);
        return true;
    }

    /// Whether this request was admitted by an agent grant rather than a
    /// browser session.
    ///
    /// A route's own `requireElevation` exists to police **people**: it asks for
    /// the developer password. An agent never has one, and never should — its
    /// authority is the grant's scope, which this filter has already enforced
    /// before the servlet runs. So the per-action check reads this and steps
    /// aside, rather than handing the agent a "elevate first" it can never
    /// satisfy.
    ///
    /// The *closed* routes are unaffected: `AgentScopes.forbidden` refuses them
    /// here, long before any servlet sees the request.
    ///
    /// @param request the request
    /// @return whether the request carries an admitted agent grant
    public static boolean isAgent(HttpServletRequest request) {
        return request.getAttribute(AGENT_GRANT_ATTRIBUTE) != null;
    }

    /// Builds the session cookie. Shared with the login endpoint so the
    /// attributes can never drift apart between the two places that set it.
    ///
    /// @param request the request the cookie will answer, for `Secure`
    public Cookie sessionCookie(HttpServletRequest request, String token) {
        Cookie cookie = new Cookie(SESSION_COOKIE, token);
        cookie.setPath(cookiePath);
        cookie.setHttpOnly(true);
        cookie.setMaxAge(cookieMaxAgeSeconds);
        cookie.setSecure(secureRequest.test(request));
        cookie.setAttribute("SameSite", "Strict");
        return cookie;
    }

    /// A cookie with the same name/path and zero Max-Age, which is what makes
    /// the browser drop it.
    ///
    /// @param request the request the cookie will answer, for `Secure`
    public Cookie clearedSessionCookie(HttpServletRequest request) {
        Cookie cookie = new Cookie(SESSION_COOKIE, "");
        cookie.setPath(cookiePath);
        cookie.setHttpOnly(true);
        cookie.setMaxAge(0);
        cookie.setSecure(secureRequest.test(request));
        cookie.setAttribute("SameSite", "Strict");
        return cookie;
    }

    private static boolean isPublic(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        return ("POST".equals(method) && LOGIN_PATH.equals(path))
                || ("GET".equals(method) && STATUS_PATH.equals(path))
                || ("POST".equals(method) && SETUP_PATH.equals(path))
                || ("GET".equals(method) && HEALTH_PATH.equals(path))
                || ("POST".equals(method) && AGENT_AUTH_PATH.equals(path));
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
