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

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// A servlet that lets an exception escape used to hand the caller Jetty's
/// HTML error page; [ApiGuardFilter] makes it the JSON shape the API contract
/// promises.
class ApiGuardFilterTest {

    @Test
    void uncaughtExceptionBecomesJson500() throws Exception {
        Server server = new Server(0);
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.addServlet(new ServletHolder(new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                throw new NullPointerException("boom");
            }
        }), "/api/throw");
        context.addFilter(new FilterHolder(new ApiGuardFilter()), "/api/*",
                EnumSet.of(DispatcherType.REQUEST));
        server.setHandler(context);
        server.start();
        try {
            int port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/throw")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(500, response.statusCode());
            assertEquals("{\"error\":\"internal error\"}", response.body());
            assertTrue(response.headers().firstValue("Content-Type").orElse("")
                    .startsWith("application/json"));
        } finally {
            server.stop();
        }
    }

    /// The cap exists because every JSON handler reads its whole body into memory. It must
    /// not swallow the multipart uploads on the same mapping — a pack is documented at
    /// 200 MiB, a plugin at 50 MiB, and both stream to a staging file instead.
    @Test
    void oversizedJsonIsRejectedButMultipartStillReachesTheHandler() throws Exception {
        Server server = new Server(0);
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.addServlet(new ServletHolder(new HttpServlet() {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.setStatus(200);
                resp.getWriter().write("reached");
            }
        }), "/api/upload");
        context.addFilter(new FilterHolder(new ApiGuardFilter()), "/api/*",
                EnumSet.of(DispatcherType.REQUEST));
        server.setHandler(context);
        server.start();
        try {
            int port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
            HttpClient client = HttpClient.newHttpClient();
            URI upload = URI.create("http://127.0.0.1:" + port + "/api/upload");

            HttpResponse<String> json = client.send(HttpRequest.newBuilder(upload)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[2 * 1024 * 1024]))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(413, json.statusCode(), "a 2 MiB JSON body must be refused before the handler");

            // The handlers parse whatever they are handed, so a body mislabelled as text must
            // not slip past a cap that keys on Content-Type.
            HttpResponse<String> mislabelled = client.send(HttpRequest.newBuilder(upload)
                    .header("Content-Type", "text/plain")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[2 * 1024 * 1024]))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(413, mislabelled.statusCode(), "mislabelling the body must not bypass the cap");

            HttpResponse<String> multipart = client.send(HttpRequest.newBuilder(upload)
                    .header("Content-Type", "multipart/form-data; boundary=XyZ")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody(2 * 1024 * 1024)))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, multipart.statusCode(), "a 2 MiB multipart upload must reach the handler");
        } finally {
            server.stop();
        }
    }

    private static byte[] multipartBody(int payloadBytes) {
        byte[] head = ("--XyZ\r\nContent-Disposition: form-data; name=\"file\"; filename=\"x.dspack\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] tail = "\r\n--XyZ--\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] body = new byte[head.length + payloadBytes + tail.length];
        System.arraycopy(head, 0, body, 0, head.length);
        System.arraycopy(tail, 0, body, head.length + payloadBytes, tail.length);
        return body;
    }
}
