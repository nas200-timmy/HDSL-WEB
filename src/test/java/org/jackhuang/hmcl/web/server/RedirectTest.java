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
package org.jackhuang.hmcl.web.server;

import com.google.gson.JsonParser;
import org.jackhuang.hmcl.web.TestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// With `https.redirect_http` on, a second plain-HTTP connector answers every
/// request with 308 to the same host/path over the HTTPS port.
class RedirectTest {

    @TempDir
    Path dataDir;

    @Test
    void plainHttpPortRedirectsToHttps() throws Exception {
        int redirectPort = TestSupport.freePort();
        try (TestSupport.RunningServer running = TestSupport.start(dataDir, Map.of(),
                config -> {
                    config.bindHost = "127.0.0.1";
                    config.https.enabled = true;
                    config.https.redirectHttp = true;
                    config.https.redirectPort = redirectPort;
                })) {
            int mainPort = running.port();
            assertEquals(redirectPort, running.server().getRedirectPort());

            var client = TestSupport.trustAllClient();
            HttpResponse<String> redirect = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + redirectPort + "/some/path?token=1"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(308, redirect.statusCode());
            assertEquals("https://127.0.0.1:" + mainPort + "/some/path?token=1",
                    redirect.headers().firstValue("location").orElseThrow(),
                    "the Location must keep the Host (minus port), the HTTPS port and the path");

            // The main connector still speaks TLS and serves the API.
            HttpResponse<String> health = client.send(
                    HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + mainPort + "/api/health"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode());
            assertEquals("ok", JsonParser.parseString(health.body()).getAsJsonObject().get("status").getAsString());
        }
    }
}
