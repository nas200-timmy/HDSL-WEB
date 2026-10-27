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

import com.google.gson.JsonParser;
import org.jackhuang.hmcl.web.TestSupport;
import org.jackhuang.hmcl.web.config.DeveloperGuardScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Boots a real panel and checks that the guard is actually wired — the filter
/// has to be on `/api/*` and `/i/*`, after the session gate, or the policy that
/// [DeveloperGuardFilterTest] pins would never run.
///
/// Since v0.5.95 developer mode works over plain HTTP (warn, not block), so
/// these tests can elevate over HTTP and prove the gate **opens** for an
/// elevated caller — not just that it refuses an anonymous one. The strict
/// transport policy (`security.require_https_for_developer: true`) is asserted
/// separately, because it still answers `tls_required`.
class DeveloperGuardGateTest {

    @TempDir
    Path dataDir;

    private static final String DEV_PASSWORD = "dev-password";

    private TestSupport.RunningServer start(DeveloperGuardScope scope) throws Exception {
        return start(scope, false);
    }

    private TestSupport.RunningServer start(DeveloperGuardScope scope, boolean tlsOnly) throws Exception {
        // A developer password has to exist, or the gate answers
        // developer_password_not_set (409), which is a different assertion.
        org.jackhuang.hmcl.web.auth.UserStore users = org.jackhuang.hmcl.web.auth.UserStore.open(
                dataDir, Map.of("HDSL_ADMIN_PASSWORD", "panel-password"), org.jackhuang.hmcl.util.logging.Logger.LOG);
        DeveloperAuth.open(dataDir).set(DEV_PASSWORD, users);
        return TestSupport.start(dataDir, Map.of(), config -> {
            config.bindHost = "127.0.0.1";
            config.auth.disabled = true;
            config.developerGuard.scope = scope;
            config.security.requireHttpsForDeveloper = tlsOnly;
        });
    }

    private static HttpResponse<String> send(TestSupport.RunningServer running, String method, String path)
            throws Exception {
        return send(running, method, path, null);
    }

    private static HttpResponse<String> send(TestSupport.RunningServer running, String method, String path,
                                             @org.jetbrains.annotations.Nullable String accept) throws Exception {
        HttpClient client = TestSupport.client();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(running.baseUrl() + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (accept != null) {
            builder.header("Accept", accept);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /// Proves the developer password for this (loopback, auth-disabled) caller,
    /// which is the key the elevation is filed under.
    private static void elevate(TestSupport.RunningServer running) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(running.baseUrl() + "/api/developer/elevate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"password\":\"" + DEV_PASSWORD + "\"}"))
                .build();
        HttpResponse<String> response = TestSupport.client().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
    }

    private static String error(HttpResponse<String> response) {
        return JsonParser.parseString(response.body()).getAsJsonObject().get("error").getAsString();
    }

    @Test
    void offLeavesTheInstanceApiAlone() throws Exception {
        try (TestSupport.RunningServer running = start(DeveloperGuardScope.OFF)) {
            HttpResponse<String> response = send(running, "GET", "/api/instances");
            assertEquals(200, response.statusCode(), response.body());
        }
    }

    @Test
    void proxyGatesTheInstanceWebPagesUntilElevated() throws Exception {
        try (TestSupport.RunningServer running = start(DeveloperGuardScope.PROXY)) {
            // A browser navigation (`Accept: text/html`) gets the small HTML page
            // rather than a JSON body that would read as a broken page.
            HttpResponse<String> page = send(running, "GET", "/i/whatever/", "text/html,application/xhtml+xml");
            assertEquals(403, page.statusCode());
            assertTrue(page.headers().firstValue("content-type").orElse("").contains("text/html"),
                    page.headers().toString());

            // The instance's own fetch/XHR gets JSON.
            HttpResponse<String> api = send(running, "GET", "/i/whatever/api/foo");
            assertEquals(403, api.statusCode());
            assertTrue(error(api).startsWith("developer_elevation_required"), error(api));

            // The instance list is not the proxy: it keeps working.
            assertEquals(200, send(running, "GET", "/api/instances").statusCode());

            // After the password the gate opens: the proxy answers for itself
            // ("instance not found"), proving the request reached it.
            elevate(running);
            HttpResponse<String> after = send(running, "GET", "/i/whatever/api/foo");
            assertEquals(404, after.statusCode(), after.body());
            assertEquals("instance not found", error(after));
        }
    }

    @Test
    void instanceGatesTheMutationVerbsOnly() throws Exception {
        try (TestSupport.RunningServer running = start(DeveloperGuardScope.INSTANCE)) {
            HttpResponse<String> write = send(running, "POST", "/api/instances/no-such-instance/start");
            assertEquals(403, write.statusCode());
            assertTrue(error(write).startsWith("developer_elevation_required"), error(write));
            // Reads stay open, which is what keeps the list usable behind the gate.
            assertEquals(200, send(running, "GET", "/api/instances").statusCode());

            // Elevated: the request reaches the servlet, which reports the
            // missing instance instead of the gate refusing.
            elevate(running);
            HttpResponse<String> after = send(running, "POST", "/api/instances/no-such-instance/start");
            assertTrue(after.statusCode() != 403, after.body());
        }
    }

    @Test
    void allGatesEvenTheReadsButNotTheDeveloperDoor() throws Exception {
        try (TestSupport.RunningServer running = start(DeveloperGuardScope.ALL)) {
            assertEquals(403, send(running, "GET", "/api/instances").statusCode());
            // Without these the panel could not explain itself or be unlocked.
            assertEquals(200, send(running, "GET", "/api/developer/status").statusCode());
            assertEquals(200, send(running, "GET", "/api/health").statusCode());

            elevate(running);
            assertEquals(200, send(running, "GET", "/api/instances").statusCode());
        }
    }

    @Test
    void theStrictTransportPolicyStillRefusesPlainHttp() throws Exception {
        // Opt-in: `security.require_https_for_developer: true` restores the
        // v0.5.94 behaviour, and the refusal names the transport rather than the
        // password.
        try (TestSupport.RunningServer running = start(DeveloperGuardScope.PROXY, true)) {
            HttpResponse<String> api = send(running, "GET", "/i/whatever/api/foo");
            assertEquals(403, api.statusCode());
            assertTrue(error(api).startsWith("tls_required"), error(api));
        }
    }
}
