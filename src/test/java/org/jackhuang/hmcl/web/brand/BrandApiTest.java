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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jackhuang.hmcl.web.TestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The REST surface of the brand categories over a real (loopback, auth-off)
/// server, with fake tool executables standing in for the installed releases:
/// one lifecycle per brand, covering create → launch → readiness → open →
/// logs → guards → stop → delete.
class BrandApiTest {

    @TempDir
    Path dataDir;

    @org.junit.jupiter.api.BeforeEach
    void resetPorts() {
        BrandPortRegistry.clear();
    }

    @Test
    void unknownBrandAnswers404() throws Exception {
        try (TestSupport.RunningServer running = start()) {
            HttpResponse<String> response = get(running, "/api/brands/nope/versions");
            assertEquals(404, response.statusCode(), response.body());
        }
    }

    /// The whole point of an own origin is that the browser talks to the
    /// instance exactly as it would to `localhost:<port>`: no panel session,
    /// no cookies, no rewriting. The panel's auth being enabled must not
    /// change that — a host-scoped cookie would 401 every LAN-IP visit.
    @Test
    void theOriginPortIsAPlainPassThroughEvenWithPanelAuthOn() throws Exception {
        Path release = dataDir.resolve("brands").resolve(Brand.OPENCODE.id())
                .resolve("releases").resolve("1.18.35");
        Path bin = release.resolve("node_modules/.bin/" + Brand.OPENCODE.binName());
        Files.createDirectories(bin.getParent());
        Files.writeString(bin,
                "#!/bin/sh\nexec node -e \"console.log('\\u001b[94m\\u001b[1m  Web interface:     "
                        + "\\u001b[0m http://127.0.0.1:45657/');"
                        + " require('http').createServer((q,s)=>s.end('served')).listen(45657);"
                        + " setInterval(()=>{},1e6)\"\n",
                StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(bin, PosixFilePermissions.fromString("rwxr-xr-x"));
        BrandPortRegistry.clear();

        try (TestSupport.RunningServer running = TestSupport.start(dataDir,
                Map.of("HDSL_ADMIN_PASSWORD", "brand-edge-pass"), config -> config.bindHost = "127.0.0.1")) {
            HttpClient client = TestSupport.client();
            // Log in first: the API itself is gated as always.
            HttpResponse<String> login = post(client, running, "/api/auth/login",
                    "{\"username\":\"admin\",\"password\":\"brand-edge-pass\"}");
            assertEquals(200, login.statusCode(), login.body());
            String session = login.headers().allValues("Set-Cookie").stream()
                    .filter(c -> c.startsWith("hdsl_session="))
                    .findFirst().orElseThrow();
            String cookie = session.substring(0, session.indexOf(';'));

            HttpResponse<String> created = requestWithCookie(client, "POST", running,
                    "/api/brands/opencode/instances", "{\"name\":\"edge\"}", cookie);
            assertEquals(201, created.statusCode(), created.body());
            String id = JsonParser.parseString(created.body()).getAsJsonObject()
                    .getAsJsonObject("instance").get("id").getAsString();
            assertEquals(200, requestWithCookie(client, "POST", running,
                    "/api/brands/opencode/instances/" + id + "/launch", null, cookie).statusCode());

            HttpResponse<String> open = requestWithCookie(client, "GET", running,
                    "/api/brands/opencode/instances/" + id + "/open", null, cookie);
            int publicPort = Integer.parseInt(JsonParser.parseString(open.body()).getAsJsonObject()
                    .get("url").getAsString().replaceAll(".*:(\\d+)/$", "$1"));

            // No cookie, no anything — straight through to the instance. The
            // path deliberately collides with a panel mapping (`/api/*` is
            // behind the panel's AuthFilter): the edge filter must run before
            // it, or OpenCode's own `/api/...` calls 401 here.
            HttpResponse<String> plain = client.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + publicPort + "/api/session?limit=5"))
                    .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, plain.statusCode(), plain.body());
            assertEquals("served", plain.body());
        }
    }

    private static HttpResponse<String> requestWithCookie(HttpClient client, String method,
                                                          TestSupport.RunningServer running, String path,
                                                          String json, String cookie) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(running.baseUrl() + path))
                .header("Cookie", cookie);
        if (json == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(json))
                    .header("Content-Type", "application/json");
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void versionsEndpointHasItsShapeEvenWithoutNetwork() throws Exception {
        try (TestSupport.RunningServer running = start()) {
            HttpResponse<String> response = get(running, "/api/brands/kimi/versions");
            assertEquals(200, response.statusCode(), response.body());
            JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
            assertTrue(body.has("versions"), body.toString());
            assertTrue(body.has("latest"), body.toString());
            assertTrue(body.has("releases"), body.toString());
        }
    }

    @Test
    void kimiLifecycleOverHttp() throws Exception {
        // The fake announces port 45654 and actually binds it — the runtime
        // treats "ready" as "the announced port accepts connections", not
        // merely "the line was printed".
        lifecycle(Brand.KIMI, "2.1.1",
                "#!/bin/sh\nexec node -e \"console.log('Local:    http://127.0.0.1:45654/');"
                        + " require('net').createServer().listen(45654); setInterval(()=>{},1e6)\"\n",
                "45654");
    }

    @Test
    void opencodeLifecycleOverHttp() throws Exception {
        // The banner carries ANSI colour even when piped — the readiness match
        // must survive it. OpenCode routes by URL path, so unlike Kimi it is
        // published on an origin of its own: the fake serves real HTTP, and
        // the test drives the instance through its public port.
        lifecycle(Brand.OPENCODE, "1.18.35",
                "#!/bin/sh\nexec node -e \"console.log('\\u001b[94m\\u001b[1m  Web interface:     "
                        + "\\u001b[0m http://127.0.0.1:45655/');"
                        + " require('http').createServer((q,s)=>s.end('ok')).listen(45655);"
                        + " setInterval(()=>{},1e6)\"\n",
                "45655");
    }

    private void lifecycle(Brand brand, String version, String fakeBin, String expectedPort) throws Exception {
        Path release = dataDir.resolve("brands").resolve(brand.id())
                .resolve("releases").resolve(version);
        Path bin = release.resolve("node_modules/.bin/" + brand.binName());
        Files.createDirectories(bin.getParent());
        Files.writeString(bin, fakeBin, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(bin, PosixFilePermissions.fromString("rwxr-xr-x"));

        try (TestSupport.RunningServer running = start()) {
            HttpClient client = TestSupport.client();

            // Create needs an installed version.
            HttpResponse<String> noVersion = post(client, running,
                    "/api/brands/" + brand.id() + "/instances", "{\"name\":\"demo\"}");
            assertEquals(201, noVersion.statusCode(), noVersion.body());
            String id = JsonParser.parseString(noVersion.body()).getAsJsonObject()
                    .getAsJsonObject("instance").get("id").getAsString();

            // Unknown versions are refused.
            HttpResponse<String> badPatch = request(client, "PATCH", running,
                    "/api/brands/" + brand.id() + "/instances/" + id,
                    "{\"version\":\"9.9.9\"}");
            assertEquals(400, badPatch.statusCode(), badPatch.body());

            // Rename is fine while stopped.
            HttpResponse<String> renamed = request(client, "PATCH", running,
                    "/api/brands/" + brand.id() + "/instances/" + id, "{\"name\":\"renamed\"}");
            assertEquals(200, renamed.statusCode(), renamed.body());

            // Launch: the fake binary announces port 1 and stays alive.
            HttpResponse<String> launch = request(client, "POST", running,
                    "/api/brands/" + brand.id() + "/instances/" + id + "/launch", null);
            assertEquals(200, launch.statusCode(), launch.body());
            assertEquals("running", JsonParser.parseString(launch.body()).getAsJsonObject()
                    .get("state").getAsString(), launch.body());

            // Guards while running.
            assertEquals(409, request(client, "PATCH", running,
                    "/api/brands/" + brand.id() + "/instances/" + id,
                    "{\"version\":\"" + version + "\"}").statusCode());
            assertEquals(409, request(client, "DELETE", running,
                    "/api/brands/" + brand.id() + "/instances/" + id, null).statusCode());

            // Open: mountable brands answer with the shared mount; path-routed
            // brands (OpenCode) with an absolute URL on their own origin.
            HttpResponse<String> open = get(running, "/api/brands/" + brand.id() + "/instances/" + id + "/open");
            assertEquals(200, open.statusCode(), open.body());
            String openUrl = JsonParser.parseString(open.body()).getAsJsonObject().get("url").getAsString();
            if (brand.needsOwnOrigin()) {
                assertTrue(openUrl.startsWith("http://127.0.0.1:"), openUrl);
                int publicPort = Integer.parseInt(openUrl.substring(openUrl.lastIndexOf(':') + 1, openUrl.length() - 1));
                // The origin actually serves the instance through the edge filter.
                HttpResponse<String> viaOrigin = client.send(HttpRequest.newBuilder(
                                URI.create(openUrl + "global/health")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, viaOrigin.statusCode(), viaOrigin.body());
                assertEquals("ok", viaOrigin.body());
                // The port is in the registry and persisted in the manifest.
                assertEquals(id, BrandPortRegistry.ownerOf(publicPort));
                HttpResponse<String> listed2 = get(running, "/api/brands/" + brand.id() + "/instances/" + id);
                assertEquals(publicPort, JsonParser.parseString(listed2.body()).getAsJsonObject()
                        .getAsJsonObject("instance").get("publicPort").getAsInt());
            } else {
                assertEquals("/i/" + id + "/", openUrl);
            }

            // The instance is listed with its brand tag.
            HttpResponse<String> list = get(running, "/api/brands/" + brand.id() + "/instances");
            JsonObject listed = JsonParser.parseString(list.body()).getAsJsonObject()
                    .getAsJsonArray("instances").get(0).getAsJsonObject();
            assertEquals(brand.id(), listed.get("brand").getAsString());
            assertEquals(version, listed.get("version").getAsString());
            assertEquals("running", listed.get("state").getAsString());

            // Logs carry the readiness line.
            HttpResponse<String> logs = get(running,
                    "/api/brands/" + brand.id() + "/instances/" + id + "/logs?tail=20");
            assertTrue(JsonParser.parseString(logs.body()).getAsJsonObject()
                    .getAsJsonArray("lines").toString().contains("127.0.0.1:" + expectedPort), logs.body());

            // Stop, then delete.
            HttpResponse<String> stop = request(client, "POST", running,
                    "/api/brands/" + brand.id() + "/instances/" + id + "/stop", null);
            assertEquals(200, stop.statusCode(), stop.body());
            assertEquals(204, request(client, "DELETE", running,
                    "/api/brands/" + brand.id() + "/instances/" + id, null).statusCode());
        }
    }

    private TestSupport.RunningServer start() throws Exception {
        return TestSupport.start(dataDir, Map.of(), config -> {
            config.bindHost = "127.0.0.1";
            config.auth.disabled = true;
        });
    }

    private static HttpResponse<String> get(TestSupport.RunningServer running, String path)
            throws Exception {
        return TestSupport.client().send(HttpRequest.newBuilder(
                        URI.create(running.baseUrl() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(HttpClient client, TestSupport.RunningServer running,
                                             String path, String json) throws Exception {
        return request(client, "POST", running, path, json);
    }

    private static HttpResponse<String> request(HttpClient client, String method,
                                                TestSupport.RunningServer running, String path,
                                                String json) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(running.baseUrl() + path));
        if (json == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(json))
                    .header("Content-Type", "application/json");
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
