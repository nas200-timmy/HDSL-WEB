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
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /// The plugin channel is a reserved slot: it answers the frozen shape with
    /// `supported: false` and no plugins, and still 404s for an instance that
    /// does not exist.
    @Test
    void pluginChannelIsReservedButEmpty() throws Exception {
        try (TestSupport.RunningServer running = start()) {
            // A release counts as installed once its marker file exists — the
            // panel never inspects the tool itself.
            Path release = dataDir.resolve("brands/kimi/releases/2.1.1");
            Files.createDirectories(release.resolve("node_modules/.bin"));
            Files.writeString(release.resolve("node_modules/.bin/kimi"), "#!/bin/sh\n");

            HttpResponse<String> created = post(TestSupport.client(), running,
                    "/api/brands/kimi/instances", "{\"name\":\"Kimi Code\",\"version\":\"2.1.1\"}");
            assertEquals(201, created.statusCode(), created.body());
            String id = JsonParser.parseString(created.body()).getAsJsonObject()
                    .getAsJsonObject("instance").get("id").getAsString();

            HttpResponse<String> response = get(running, "/api/brands/kimi/instances/" + id + "/plugins");
            assertEquals(200, response.statusCode(), response.body());
            JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
            assertFalse(body.get("supported").getAsBoolean());
            assertTrue(body.getAsJsonArray("plugins").isEmpty());

            assertEquals(404, get(running, "/api/brands/kimi/instances/nope/plugins").statusCode());
        }
    }

    /// Claude Web ships two Claude Code kernels; the picker asks this endpoint so
    /// it never offers one that would fail. Other categories answer 404: they
    /// have exactly one TUI and nothing to choose.
    @Test
    void claudeWebListsItsTwoKernels() throws Exception {
        try (TestSupport.RunningServer running = start()) {
            Path release = dataDir.resolve("brands/claude-web/releases/0.3.2");
            Files.createDirectories(release.resolve("web/dist"));
            Files.writeString(release.resolve("web/dist/index.html"), "<!doctype html>\n");

            HttpResponse<String> created = post(TestSupport.client(), running,
                    "/api/brands/claude-web/instances", "{\"name\":\"Workbench\",\"version\":\"0.3.2\"}");
            assertEquals(201, created.statusCode(), created.body());
            String id = JsonParser.parseString(created.body()).getAsJsonObject()
                    .getAsJsonObject("instance").get("id").getAsString();

            HttpResponse<String> response = get(running, "/api/brands/claude-web/instances/" + id + "/terminals");
            assertEquals(200, response.statusCode(), response.body());
            JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
            assertEquals(2, body.getAsJsonArray("kernels").size(), response.body());
            assertEquals("community", body.getAsJsonArray("kernels").get(0)
                    .getAsJsonObject().get("id").getAsString());
            assertEquals("official", body.getAsJsonArray("kernels").get(1)
                    .getAsJsonObject().get("id").getAsString());

            // A category with one TUI has no kernel list.
            Path kimi = dataDir.resolve("brands/kimi/releases/2.1.1");
            Files.createDirectories(kimi.resolve("node_modules/.bin"));
            Files.writeString(kimi.resolve("node_modules/.bin/kimi"), "#!/bin/sh\n");
            HttpResponse<String> kimiCreated = post(TestSupport.client(), running,
                    "/api/brands/kimi/instances", "{\"name\":\"Kimi\",\"version\":\"2.1.1\"}");
            assertEquals(201, kimiCreated.statusCode(), kimiCreated.body());
            String kimiId = JsonParser.parseString(kimiCreated.body()).getAsJsonObject()
                    .getAsJsonObject("instance").get("id").getAsString();
            assertEquals(404, get(running, "/api/brands/kimi/instances/" + kimiId + "/terminals").statusCode());
        }
    }

    private static HttpResponse<String> get(TestSupport.RunningServer running, String path)
            throws Exception {
        return TestSupport.client().send(HttpRequest.newBuilder(
                        URI.create(running.baseUrl() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private TestSupport.RunningServer start() throws Exception {
        return TestSupport.start(dataDir, Map.of(), config -> {
            config.bindHost = "127.0.0.1";
            config.auth.disabled = true;
        });
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
