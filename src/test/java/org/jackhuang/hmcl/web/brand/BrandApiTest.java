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

    @Test
    void unknownBrandAnswers404() throws Exception {
        try (TestSupport.RunningServer running = start()) {
            HttpResponse<String> response = get(running, "/api/brands/nope/versions");
            assertEquals(404, response.statusCode(), response.body());
        }
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
        // must survive it.
        lifecycle(Brand.OPENCODE, "1.18.35",
                "#!/bin/sh\nexec node -e \"console.log('\\u001b[94m\\u001b[1m  Web interface:     "
                        + "\\u001b[0m http://127.0.0.1:45655/');"
                        + " require('net').createServer().listen(45655); setInterval(()=>{},1e6)\"\n",
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

            // Open points at the shared mount.
            HttpResponse<String> open = get(running, "/api/brands/" + brand.id() + "/instances/" + id + "/open");
            assertEquals(200, open.statusCode(), open.body());
            assertEquals("/i/" + id + "/",
                    JsonParser.parseString(open.body()).getAsJsonObject().get("url").getAsString());

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
