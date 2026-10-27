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

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jackhuang.hmcl.web.auth.AuthFilter;
import org.jackhuang.hmcl.web.auth.AuthService;
import org.jackhuang.hmcl.web.config.DeveloperGuardScope;
import org.jackhuang.hmcl.web.config.ServerConfig;
import org.jackhuang.hmcl.web.emergency.EmergencyStop;
import org.jackhuang.hmcl.web.http.Json;
import org.jackhuang.hmcl.web.http.RequestSecurity;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;

/// The two gates in front of everything an instance exposes.
///
/// **Emergency stop** comes first: while it is engaged, `/i/<id>/*` and the
/// state-changing instance APIs answer `503 emergency_stop`, whatever the
/// caller's elevation. Reads (the instance list, logs, the developer page)
/// stay open — otherwise nobody could see what happened or release the stop.
///
/// **Developer guard** ([ServerConfig#developerGuard]) is the second: when the
/// configured scope covers the request, it needs the developer elevation.
/// Both the browser's session elevation and a bearer token minted by
/// `POST /api/developer/elevate` count; the token exists so a host script or an
/// inspection agent does not have to hold a cookie jar.
///
/// Neither gate touches work that is already running. An open instance page
/// keeps running (its own requests start failing), a terminal stays attached and
/// an agent process is untouched: **the guard is a door, not a circuit
/// breaker**. Only the emergency stop kills processes.
///
/// Mounted after [AuthFilter] on `/api/*` and `/i/*`, so an unauthenticated
/// request is answered 401 by the session gate rather than 403 here.
@NotNullByDefault
public final class DeveloperGuardFilter implements Filter {

    private final ServerConfig config;
    private final DeveloperAuth devAuth;
    private final DeveloperElevation elevation;
    private final AuthService auth;
    private final EmergencyStop emergency;
    private final BooleanSupplier tlsActive;

    public DeveloperGuardFilter(ServerConfig config, DeveloperAuth devAuth, DeveloperElevation elevation,
                                AuthService auth, EmergencyStop emergency, BooleanSupplier tlsActive) {
        this.config = config;
        this.devAuth = devAuth;
        this.elevation = elevation;
        this.auth = auth;
        this.emergency = emergency;
        this.tlsActive = tlsActive;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest http) || !(response instanceof HttpServletResponse answer)) {
            chain.doFilter(request, response);
            return;
        }
        String path = http.getRequestURI();
        String method = http.getMethod();
        boolean navigation = isNavigation(http);

        if (emergency.isEngaged() && blockedByEmergency(path, method)) {
            refuse(answer, navigation, path, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "emergency_stop",
                    "紧急停机中：实例网页、终端与实例启动都被冻结。冷却结束或重启面板后才能恢复。",
                    "The panel is in emergency stop: instance pages, terminals and instance starts are frozen.");
            return;
        }

        DeveloperGuardScope scope = config.developerGuard.scope;
        if (scope == DeveloperGuardScope.OFF || !gated(scope, path, method)) {
            chain.doFilter(request, response);
            return;
        }
        // A developer who has not even set a password cannot be asked for one;
        // say so instead of looping on "wrong password".
        if (!devAuth.isSet()) {
            refuse(answer, navigation, path, HttpServletResponse.SC_CONFLICT, "developer_password_not_set",
                    "开发者守卫已开启，但还没有设置开发者密码。请先在「设置 → 开发者模式」里设置。",
                    "The developer guard is on but no developer password is set.");
            return;
        }
        // Elevation is only ever obtained over HTTPS (see DeveloperApiServlet),
        // so on a plain-HTTP panel a gated request can never be satisfied. Say
        // which policy refused it rather than asking for a password that cannot
        // work.
        if (!RequestSecurity.isSecure(http, tlsActive.getAsBoolean(), config)) {
            refuse(answer, navigation, path, HttpServletResponse.SC_FORBIDDEN, "tls_required",
                    "面板不在 HTTPS 下：开发者模式不可用。请先在「设置 → HTTPS 证书」里启用。",
                    "The panel is not on HTTPS, so developer mode is unavailable.");
            return;
        }
        if (elevation.require(key(http), sessionAlive(http)) || elevation.requireToken(bearerToken(http))) {
            chain.doFilter(request, response);
            return;
        }
        refuse(answer, navigation, path, HttpServletResponse.SC_FORBIDDEN, "developer_elevation_required",
                "需要开发者密码：请先在面板里提权，然后刷新这个页面。",
                "The developer password is required: elevate in the panel, then reload.");
    }

    /// Whether the guard's scope covers this request.
    ///
    /// Pure and static where it can be: the tests drive it directly instead of
    /// standing up a server.
    static boolean gated(DeveloperGuardScope scope, String path, String method) {
        if (scope == DeveloperGuardScope.OFF) {
            return false;
        }
        if (DeveloperGuardScope.alwaysAllowed(path)) {
            return false;
        }
        if (path.startsWith("/i/")) {
            return scope.coversProxy();
        }
        if (!path.startsWith("/api/")) {
            return false;
        }
        if (scope.coversAllApi()) {
            return true;
        }
        return scope.coversInstanceActions() && DeveloperGuardScope.instanceMutation(path, method);
    }

    /// Whether the emergency stop refuses this request.
    ///
    /// Instance pages and the mutation verbs on the instance family: the same
    /// set that can start a process or write a file. Reads stay open, and the
    /// developer routes stay open — the status has to be readable, and the
    /// release button has to reach the server.
    static boolean blockedByEmergency(String path, String method) {
        if (path.startsWith("/i/")) {
            return true;
        }
        if (!path.startsWith("/api/")) {
            return false;
        }
        if (DeveloperGuardScope.alwaysAllowed(path)) {
            return false;
        }
        return DeveloperGuardScope.instanceMutation(path, method);
    }

    /// Answers the refusal: a small HTML page for a browser navigation (a JSON
    /// body would read as a broken page), JSON for everything else.
    private void refuse(HttpServletResponse response, boolean navigation, String path,
                        int status, String code, String zh, String en) throws IOException {
        if (navigation && path.startsWith("/i/")) {
            response.setStatus(status);
            response.setContentType("text/html; charset=utf-8");
            response.setHeader("Cache-Control", "no-store");
            String panel = (config.basePath.isEmpty() ? "" : config.basePath) + "/";
            try (PrintWriter out = new PrintWriter(response.getOutputStream(), false, StandardCharsets.UTF_8)) {
                out.write("<!doctype html><html lang=\"zh\"><head><meta charset=\"utf-8\">"
                        + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                        + "<title>HDSL-web</title></head><body style=\"font:14px/1.6 system-ui, sans-serif;"
                        + "max-width:40rem;margin:12vh auto;padding:0 1.2rem;color:#1b1b1f\">");
                out.write("<h1 style=\"font-size:1.25rem\">" + escape(zh) + "</h1>");
                out.write("<p style=\"color:#555\">" + escape(en) + "</p>");
                out.write("<p><a href=\"" + escape(panel) + "\">返回面板</a></p>");
                out.write("</body></html>");
            }
            return;
        }
        Json.error(response, status, code + ": " + en);
    }

    /// Whether this looks like a browser navigation, which gets the HTML page.
    private static boolean isNavigation(HttpServletRequest request) {
        String mode = request.getHeader("Sec-Fetch-Mode");
        if (mode != null) {
            return mode.equalsIgnoreCase("navigate");
        }
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains("text/html");
    }

    /// `Authorization: Bearer <token>`, or an empty string.
    private static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || header.length() < 8) {
            return "";
        }
        if (!header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return "";
        }
        return header.substring(7).trim();
    }

    private boolean sessionAlive(HttpServletRequest request) {
        Object token = request.getAttribute(AuthFilter.SESSION_TOKEN_ATTRIBUTE);
        return !(token instanceof String value) || value.isBlank() || auth.isAlive(value);
    }

    private static String key(HttpServletRequest request) {
        Object token = request.getAttribute(AuthFilter.SESSION_TOKEN_ATTRIBUTE);
        return DeveloperElevation.key(token instanceof String value ? value : null, request.getRemoteAddr());
    }

    /// Minimal HTML escaping for the two strings above (both are ours, but the
    /// page builder must not depend on that).
    private static String escape(@Nullable String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
