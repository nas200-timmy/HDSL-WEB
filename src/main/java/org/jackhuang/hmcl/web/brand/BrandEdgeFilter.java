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
package org.jackhuang.hmcl.web.brand;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Set;

/// The edge for brand instances whose web client cannot live under a subpath
/// mount ([Brand.needsOwnOrigin] — OpenCode routes by `location.pathname` and
/// renders a blank shell on any subpath, mount or not, verified 2026-10).
///
/// The panel publishes one port per such instance ([BrandPortRegistry], a
/// fixed range outside the dsh pool, persisted in the manifest). Requests that
/// arrive on one of those ports are proxied to the owning instance's loopback
/// port **verbatim** — path untouched, page unrewritten, no shim, and **no
/// panel session gate**: the port is a straight nginx-style front for the
/// instance, so the browser reaches it exactly as it would reach
/// `localhost:<port>`, from any host name (a session cookie is host-scoped and
/// would 401 every LAN-IP visit). What protects the port is the network: it is
/// not published unless the operator forwards it, and the instance's own auth
/// (when it has one, e.g. OpenCode's `OPENCODE_SERVER_PASSWORD`) still applies
/// on top.
///
/// This is a servlet *filter* mapped at `/*` so it can decline: a request on
/// any other port falls through to the normal panel chain untouched.
@NotNullByDefault
public final class BrandEdgeFilter implements Filter {

    static {
        // java.net.http refuses restricted headers (Host among them) unless
        // named before any client is built — the same switch
        // InstanceProxyServlet sets. Host passes through because some servers
        // answer absolute redirects from it; the brands here do not check it.
        System.setProperty("jdk.httpclient.allowRestrictedHeaders", "host");
    }

    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "proxy-connection", "transfer-encoding",
            "upgrade", "te", "trailer", "content-length", "host");

    private static final int BUFFER_SIZE = 8192;

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest http) || !(response instanceof HttpServletResponse resp)) {
            chain.doFilter(request, response);
            return;
        }
        String owner = BrandPortRegistry.ownerOf(http.getLocalPort());
        if (owner == null) {
            chain.doFilter(request, response);
            return;
        }
        int target = BrandRuntime.isRunning(owner) ? BrandRuntime.portOf(owner) : 0;
        if (target <= 0) {
            resp.setStatus(502);
            resp.setContentType("application/json; charset=utf-8");
            resp.getOutputStream().write("{\"error\":\"instance not running\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return;
        }
        proxy(http, resp, target);
    }

    /// Forwards the request as it arrived — method, path, query, headers —
    /// and streams the answer back as it came. No rewriting of any kind: this
    /// origin belongs to the app, so what it serves is already right.
    private void proxy(HttpServletRequest request, HttpServletResponse response, int target)
            throws IOException {
        String path = request.getRequestURI();
        String query = request.getQueryString();
        URI uri = URI.create("http://127.0.0.1:" + target + path + (query == null ? "" : "?" + query));

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri);
        Enumeration<String> names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            Enumeration<String> values = request.getHeaders(name);
            while (values.hasMoreElements()) {
                builder.header(name, values.nextElement());
            }
        }
        byte[] body = request.getInputStream().readAllBytes();
        builder.method(request.getMethod(), body.length == 0
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body));

        HttpResponse<InputStream> upstream;
        try {
            upstream = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            response.setStatus(502);
            return;
        }
        response.setStatus(upstream.statusCode());
        upstream.headers().map().forEach((name, values) -> {
            if (HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT))) {
                return;
            }
            values.forEach(value -> response.addHeader(name, value));
        });
        try (InputStream in = upstream.body(); OutputStream out = response.getOutputStream()) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
                out.flush();
            }
        }
    }
}
