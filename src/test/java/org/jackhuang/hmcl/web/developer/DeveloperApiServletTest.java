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
package org.jackhuang.hmcl.web.developer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jackhuang.hmcl.web.TestSupport;
import org.jackhuang.hmcl.web.auth.UserStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The developer second factor: the password routes and the elevation endpoint
/// are always present, and the web terminal depends on both.
class DeveloperApiServletTest {

    private static final String PANEL_PASSWORD = "panel-password-1";
    private static final String DEV_PASSWORD = "dev-password-1";

    @TempDir
    Path dataDir;

    @Test
    void passwordLifecycleAndElevationWork() throws Exception {
        try (TestSupport.RunningServer running = TestSupport.start(dataDir,
                Map.of(UserStore.ENV_ADMIN_PASSWORD, PANEL_PASSWORD), config -> {
                    config.bindHost = "127.0.0.1";
                    config.https.enabled = true;
                })) {
            HttpClient client = TestSupport.trustAllClient();
            String base = "https://127.0.0.1:" + running.port();
            String cookie = login(client, base);

            // No developer password yet: elevate says so, status says so.
            HttpResponse<String> notSet = post(client, base + "/api/developer/elevate",
                    "{\"password\":\"" + DEV_PASSWORD + "\"}", cookie);
            assertEquals(409, notSet.statusCode(), notSet.body());
            assertTrue(json(notSet).get("error").getAsString().contains("developer_password_not_set"));

            // Set it with the panel password as confirmation.
            HttpResponse<String> set = post(client, base + "/api/developer/password",
                    "{\"adminPassword\":\"" + PANEL_PASSWORD + "\",\"password\":\"" + DEV_PASSWORD
                            + "\",\"confirm\":\"" + DEV_PASSWORD + "\"}", cookie);
            assertEquals(200, set.statusCode(), set.body());
            assertTrue(json(set).get("devPasswordSet").getAsBoolean());

            JsonObject status = json(get(client, base + "/api/developer/status", cookie));
            assertTrue(status.get("devPasswordSet").getAsBoolean());
            assertFalse(status.get("elevated").getAsBoolean());

            // The password elevates this session.
            HttpResponse<String> elevated = post(client, base + "/api/developer/elevate",
                    "{\"password\":\"" + DEV_PASSWORD + "\"}", cookie);
            assertEquals(200, elevated.statusCode(), elevated.body());
            assertTrue(json(get(client, base + "/api/developer/status", cookie)).get("elevated").getAsBoolean());

            // Clearing needs the panel password and drops every elevation.
            HttpResponse<String> cleared = post(client, base + "/api/developer/password/clear",
                    "{\"adminPassword\":\"" + PANEL_PASSWORD + "\"}", cookie);
            assertEquals(200, cleared.statusCode(), cleared.body());
            assertFalse(json(get(client, base + "/api/developer/status", cookie)).get("elevated").getAsBoolean());
        }
    }

    private static String login(HttpClient client, String base) throws Exception {
        HttpResponse<String> response = post(client, base + "/api/auth/login",
                "{\"username\":\"admin\",\"password\":\"" + PANEL_PASSWORD + "\"}", null);
        assertEquals(200, response.statusCode(), response.body());
        return response.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
    }

    private static HttpResponse<String> get(HttpClient client, String url, String cookie) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).GET();
        builder.header("Cookie", cookie);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(HttpClient client, String url, String body, String cookie)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonObject json(HttpResponse<String> response) {
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }
}
