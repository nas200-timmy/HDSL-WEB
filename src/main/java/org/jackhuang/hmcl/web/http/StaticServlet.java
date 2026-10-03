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

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.Map;

/// Serves the SPA shell from the classpath `web/` directory (packaged from
/// `src/main/resources/web`, later the React build output).
///
/// Any path that is not a real file — `/login`, `/instances/3`, a stale
/// hashed asset name after a deploy — falls back to `index.html` so client
/// side routing always gets a page to boot from. The resources themselves are
/// unauthenticated by design: the UI shell carries no data, and the login
/// page must be reachable before a session exists. All data planes live
/// behind [org.jackhuang.hmcl.web.auth.AuthFilter].
@NotNullByDefault
public final class StaticServlet extends HttpServlet {

    private static final String RESOURCE_ROOT = "web/";
    private static final String INDEX = "index.html";

    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("mjs", "text/javascript; charset=utf-8"),
            Map.entry("json", "application/json; charset=utf-8"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("txt", "text/plain; charset=utf-8"),
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"));

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = normalize(requestPath(request));
        URL resource = find(path);
        if (resource == null) {
            resource = find(INDEX); // SPA fallback
            // The content type follows what is actually sent, so client-side
            // routes (e.g. `/login`) still come back as text/html.
            path = INDEX;
        }
        if (resource == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.getWriter().write("not found");
            return;
        }

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(contentType(path));
        // Phase 1 ships a hand-written placeholder shell; assets are rebuilt
        // per deploy and must never be cached across versions.
        response.setHeader("Cache-Control", "no-cache");
        URLConnection connection = resource.openConnection();
        response.setContentLengthLong(connection.getContentLengthLong());
        try (InputStream in = connection.getInputStream()) {
            in.transferTo(response.getOutputStream());
        }
    }

    /// The request path to resolve against the classpath.
    ///
    /// This servlet is mounted at "/" — the default mapping — and the servlet
    /// spec says a default-mapped servlet receives **no** path info: the whole
    /// path arrives as the servlet path and `getPathInfo()` is null. Reading
    /// only `getPathInfo()` therefore resolved every request to index.html and
    /// served the HTML shell for the JS/CSS bundles too (the panel rendered as
    /// a black page). Fall back through servletPath and the raw URI so the
    /// lookup works under any mapping.
    private static String requestPath(HttpServletRequest request) {
        String path = request.getPathInfo();
        if (path == null) {
            path = request.getServletPath();
        }
        if (path == null || path.isEmpty()) {
            path = request.getRequestURI();
        }
        return path;
    }

    /// Resolves `path` under the classpath `web/` root, rejecting traversal.
    private static URL find(String path) {
        if (path.isEmpty() || path.equals("/")) {
            path = INDEX;
        }
        return StaticServlet.class.getClassLoader().getResource(RESOURCE_ROOT + path);
    }

    /// Collapses `.`/`..` segments and rejects anything that would escape the
    /// root. `..` that cannot be resolved (more ups than downs) means the
    /// path was malicious.
    private static String normalize(String pathInfo) {
        if (pathInfo == null || pathInfo.isEmpty() || "/".equals(pathInfo)) {
            return INDEX;
        }
        StringBuilder cleaned = new StringBuilder(pathInfo.length());
        int depth = 0;
        for (String segment : pathInfo.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                depth--;
                if (depth < 0) {
                    return INDEX;
                }
                int last = cleaned.lastIndexOf("/");
                cleaned.setLength(last <= 0 ? 0 : last);
                continue;
            }
            depth++;
            cleaned.append('/').append(segment);
        }
        return cleaned.isEmpty() ? INDEX : cleaned.substring(1);
    }

    private static String contentType(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0) {
            return "application/octet-stream";
        }
        return CONTENT_TYPES.getOrDefault(path.substring(dot + 1).toLowerCase(), "application/octet-stream");
    }
}
