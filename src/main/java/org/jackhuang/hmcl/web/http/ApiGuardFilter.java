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
import org.jackhuang.hmcl.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;

/// Catches whatever a servlet lets escape. An NPE in a handler is a 500 like
/// any other, but Jetty's default error page is HTML and `/api/*` speaks JSON
/// — one unhandled exception used to hand the frontend an HTML page where a
/// JSON body was the contract. Mapped over `/api/*`; a committed response can
/// no longer be rewritten and is rethrown so the container still sees it.
@NotNullByDefault
public final class ApiGuardFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        try {
            chain.doFilter(request, response);
        } catch (Throwable t) {
            if (!(response instanceof HttpServletResponse http) || http.isCommitted()) {
                rethrow(t);
                return;
            }
            String path = request instanceof HttpServletRequest req ? req.getRequestURI() : String.valueOf(request);
            Logger.LOG.warning("Uncaught exception at " + path + ": " + t);
            Json.error(http, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "internal error");
        }
    }

    /// Rethrows what [#doFilter] is declared to throw; anything else becomes a
    /// [ServletException] so the container still reports the failure.
    private static void rethrow(Throwable t) throws IOException, ServletException {
        if (t instanceof IOException io) {
            throw io;
        }
        if (t instanceof ServletException servletException) {
            throw servletException;
        }
        if (t instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (t instanceof Error error) {
            throw error;
        }
        throw new ServletException(t);
    }
}
