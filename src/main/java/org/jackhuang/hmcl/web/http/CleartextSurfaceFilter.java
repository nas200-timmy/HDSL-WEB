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
package org.jackhuang.hmcl.web.http;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jackhuang.hmcl.web.auth.AuthFilter;
import org.jackhuang.hmcl.web.config.ServerConfig;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.util.function.BooleanSupplier;

/// Refuses surfaces that cannot carry the application-layer session on a
/// cleartext remote connection.
///
/// `/i/*` (agent web pages), `/plain/*` (no-JS surface) and `/ws/*` (event
/// stream) cannot attach the optional password/chat envelope to every
/// navigation or frame. On a non-loopback cleartext request they are closed;
/// HTTPS, a trusted TLS-terminating proxy, or loopback remain available.
/// Loopback is exempt because it is the local test/SSH-tunnel path; it is not
/// a network boundary.
@NotNullByDefault
public final class CleartextSurfaceFilter implements Filter {

    private final ServerConfig config;
    private final BooleanSupplier tlsActive;

    public CleartextSurfaceFilter(ServerConfig config, BooleanSupplier tlsActive) {
        this.config = config;
        this.tlsActive = tlsActive;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest http) || !(response instanceof HttpServletResponse out)) {
            chain.doFilter(request, response);
            return;
        }
        if (RequestSecurity.isSecure(http, tlsActive.getAsBoolean(), config)
                || AuthFilter.isLoopback(http.getRemoteAddr())) {
            chain.doFilter(request, response);
            return;
        }
        Json.error(out, HttpServletResponse.SC_FORBIDDEN,
                "https_only: this surface requires HTTPS (or a trusted TLS proxy); "
                        + "use SSH local port forwarding if the panel has no certificate");
    }
}
