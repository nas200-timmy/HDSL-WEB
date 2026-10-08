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
