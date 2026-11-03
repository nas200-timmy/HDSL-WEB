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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The agent credential: a bearer token minted from an elevated session, and
/// the two walls that keep it from doing what no agent should do.
///
/// The token has no browser behind it, so it has to pass the session gate on
/// its own — which is exactly why every rule here lives in the panel's root
/// process and not in the plugin: an agent can rewrite the plugin.
class AgentGrantApiTest {

    private static final String PANEL_PASSWORD = "panel-password-1";
    private static final String DEV_PASSWORD = "dev-password-1";

    @TempDir
    Path dataDir;

    @Test
    void aGrantReachesOnlyWhatItWasGivenAndCanBeRevoked() throws Exception {
        try (TestSupport.RunningServer running = start()) {
            HttpClient client = TestSupport.trustAllClient();
            String base = "https://127.0.0.1:" + running.port();
            String cookie = elevatedSession(client, base);

            // A read grant: minted from the operator's elevation, scoped to reads.
            HttpResponse<String> minted = post(client, base + "/api/developer/agent-token",
                    "{\"label\":\"probe\",\"scopes\":[\"read\"],\"instanceId\":\"inst-a\",\"ttlSeconds\":600}",
                    cookie);
            assertEquals(200, minted.statusCode(), minted.body());
            JsonObject mint = json(minted);
            String token = mint.get("token").getAsString();
            String id = mint.get("id").getAsString();
            assertTrue(mint.get("scopes").toString().contains("read"));

            // Reads work with no cookie at all — that is the point of the token.
            assertEquals(200, getWithBearer(client, base + "/api/instances", token).statusCode());

            // Writes do not: the grant never named the class.
            HttpResponse<String> launch = post(client, base + "/api/instances/inst-a/launch", "{}", null, token);
            assertEquals(403, launch.statusCode(), launch.body());
            assertTrue(launch.body().contains("agent_scope_denied"));

            // The credential routes are closed to agents outright, whatever
            // they were granted: a token that can mint tokens is a token that
            // survives its own revocation.
            HttpResponse<String> grants = getWithBearer(client, base + "/api/developer/agent-grants", token);
            assertEquals(403, grants.statusCode(), grants.body());

            // The operator sees it, and sees no token in the listing.
            HttpResponse<String> listed = get(client, base + "/api/developer/agent-grants", cookie);
            assertEquals(200, listed.statusCode(), listed.body());
            assertTrue(listed.body().contains("\"label\":\"probe\""), listed.body());
            assertFalse(listed.body().contains(token), "the listing must never carry the token");

            HttpResponse<String> revoked = delete(client, base + "/api/developer/agent-grants/" + id, cookie);
            assertEquals(200, revoked.statusCode(), revoked.body());

            // Revocation is immediate: the next request is just unauthenticated.
            assertEquals(401, getWithBearer(client, base + "/api/instances", token).statusCode());
            assertEquals(404, delete(client, base + "/api/developer/agent-grants/" + id, cookie).statusCode());
        }
    }

    @Test
    void anAgentCannotStopAnything() throws Exception {
        try (TestSupport.RunningServer running = start()) {
            HttpClient client = TestSupport.trustAllClient();
            String base = "https://127.0.0.1:" + running.port();
            String cookie = elevatedSession(client, base);

            HttpResponse<String> minted = post(client, base + "/api/developer/agent-token",
                    "{\"label\":\"probe\",\"scopes\":[\"read\"]}", cookie);
            assertEquals(200, minted.statusCode(), minted.body());
            String token = json(minted).get("token").getAsString();

            // Writes are not "denied for this one instance" — they are not in the
            // grant at all. A read-only token cannot stop its own instance or any
            // other, which is what makes the self-kill structurally impossible.
            HttpResponse<String> suicide = post(client, base + "/api/instances/inst-a/stop", "{}", null, token);
            assertEquals(403, suicide.statusCode(), suicide.body());
            assertTrue(suicide.body().contains("agent_scope_denied"), suicide.body());

            HttpResponse<String> other = post(client, base + "/api/instances/inst-b/stop", "{}", null, token);
            assertEquals(403, other.statusCode(), other.body());

            // Reading the list still works.
            assertEquals(200, getWithBearer(client, base + "/api/instances", token).statusCode());
        }
    }

    @Test
    void theGrantCannotClaimAScopeThisBuildDoesNotKnow() throws Exception {
        try (TestSupport.RunningServer running = start()) {
            HttpClient client = TestSupport.trustAllClient();
            String base = "https://127.0.0.1:" + running.port();
            String cookie = elevatedSession(client, base);

            HttpResponse<String> refused = post(client, base + "/api/developer/agent-token",
                    "{\"label\":\"probe\",\"scopes\":[\"root\"]}", cookie);
            assertEquals(400, refused.statusCode(), refused.body());
            assertTrue(refused.body().contains("unknown_scope"));
        }
    }


    @Test
    void anAgentIsRefusedByItsScopeBeforeAnyPasswordWall() throws Exception {
        try (TestSupport.RunningServer running = start()) {
            HttpClient client = TestSupport.trustAllClient();
            String base = "https://127.0.0.1:" + running.port();
            String cookie = elevatedSession(client, base);

            HttpResponse<String> minted = post(client, base + "/api/developer/agent-token",
                    "{\"label\":\"probe\",\"scopes\":[\"read\"]}", cookie);
            assertEquals(200, minted.statusCode(), minted.body());
            String token = json(minted).get("token").getAsString();

            // `console/input` is one of the routes whose servlet asks for
            // elevation. An agent must be stopped by its scope, with a code that
            // says why, not told to enter a developer password it can never have.
            HttpResponse<String> console = post(client, base + "/api/mc/instances/nope/console/input",
                    "{\"line\":\"list\"}", null, token);
            assertEquals(403, console.statusCode(), console.body());
            assertTrue(console.body().contains("agent_scope_denied"), console.body());
            assertFalse(console.body().contains("developer_elevation_required"), console.body());
        }
    }

    /// 这张票能读面板，但读不到两样东西：浏览器通道，和凭据本身。
    @Test
    void theProxyAndAGeneratedCredentialStayOutOfReach() throws Exception {
        try (TestSupport.RunningServer running = start()) {
            HttpClient client = TestSupport.trustAllClient();
            String base = "https://127.0.0.1:" + running.port();
            String cookie = elevatedSession(client, base);

            HttpResponse<String> minted = post(client, base + "/api/developer/agent-token",
                    "{\"label\":\"probe\",\"scopes\":[\"read\"]}", cookie);
            assertEquals(200, minted.statusCode(), minted.body());
            String token = json(minted).get("token").getAsString();

            // `/i/*` is the browser's proxy into an instance, not an API. A
            // `read` grant must not turn it into "a GET, so allowed".
            HttpResponse<String> proxy = getWithBearer(client, base + "/i/whatever/", token);
            assertEquals(403, proxy.statusCode(), proxy.body());
            assertTrue(proxy.body().contains("agent_scope_denied"), proxy.body());

            // Octop's generated admin password is a credential. The first-run
            // window returns it without elevation, so only the scope gate can
            // keep it out of a model's context.
            HttpResponse<String> credential = getWithBearer(client,
                    base + "/api/brands/octop/instances/nope/admin-password", token);
            assertEquals(403, credential.statusCode(), credential.body());
            assertTrue(credential.body().contains("agent_scope_denied"), credential.body());

            // The gate must read the same decoded path the servlet routes on.
            // `%61dmin-password` is not that name in the raw URI, but the
            // dispatcher decodes it back into the closed route.
            HttpResponse<String> encoded = getWithBearer(client,
                    base + "/api/brands/octop/instances/nope/%61dmin-password", token);
            assertEquals(403, encoded.statusCode(), encoded.body());
            assertTrue(encoded.body().contains("agent_scope_denied"), encoded.body());
        }
    }

    /// 票是借来的权力：出借人走了（登出），票就不能再用。
    @Test
    void loggingOutEndsTheGrantItAuthorized() throws Exception {
        try (TestSupport.RunningServer running = start()) {
            HttpClient client = TestSupport.trustAllClient();
            String base = "https://127.0.0.1:" + running.port();
            String cookie = elevatedSession(client, base);

            HttpResponse<String> minted = post(client, base + "/api/developer/agent-token",
                    "{\"label\":\"probe\",\"scopes\":[\"read\"],\"ttlSeconds\":3600}", cookie);
            assertEquals(200, minted.statusCode(), minted.body());
            String token = json(minted).get("token").getAsString();
            assertEquals(200, getWithBearer(client, base + "/api/instances", token).statusCode());

            assertEquals(200, post(client, base + "/api/auth/logout", "{}", cookie).statusCode());

            // The grant's own expiry is an hour away; the window that lent it
            // the authority is gone, so the next call is just unauthenticated.
            assertEquals(401, getWithBearer(client, base + "/api/instances", token).statusCode());
        }
    }

    @Test
    void scopesAreClassifiedHereAndDenyByDefault() {
        // Reads are reads; the credential-leaking ones are closed.
        assertEquals(AgentScopes.READ, AgentScopes.required("GET", "/api/instances"));
        assertTrue(AgentScopes.forbidden("GET", "/api/instances/inst-a/open"));
        assertTrue(AgentScopes.forbidden("GET", "/api/exports/x.dspack"));
        assertTrue(AgentScopes.forbidden("POST", "/api/developer/agent-token"));
        assertTrue(AgentScopes.forbidden("POST", "/api/auth/password"));
        // A generated brand admin password is a credential, and the first-run
        // window answers it with no elevation at all — the gateway has to close
        // it, because a servlet-side check never runs.
        assertTrue(AgentScopes.forbidden("GET", "/api/brands/octop/instances/x/admin-password"));
        assertTrue(AgentScopes.forbidden("POST", "/api/brands/octop/instances/x/admin-password"));
        // A trailing slash is the same route: the dispatcher ignores it, so the
        // gate must too.
        assertTrue(AgentScopes.forbidden("GET", "/api/brands/octop/instances/x/admin-password/"));

        // The scope model stops at the API boundary: the reverse proxy and the
        // event streams are browser channels, never "a GET, so a read".
        assertNull(AgentScopes.required("GET", "/i/inst-a/"));
        assertNull(AgentScopes.required("GET", "/ws/events"));
        // Read-only: the instance lists and one entry in them are the whole
        // surface. Nothing an agent can call changes state, which is also why it
        // can never stop the instance it is running in — that route is not on
        // the menu, not merely refused by a special case.
        assertEquals(AgentScopes.READ, AgentScopes.required("GET", "/api/instances"));
        assertEquals(AgentScopes.READ, AgentScopes.required("GET", "/api/instances/a"));
        assertEquals(AgentScopes.READ, AgentScopes.required("GET", "/api/instances/a/"));
        assertEquals(AgentScopes.READ, AgentScopes.required("HEAD", "/api/instances"));
        assertEquals(AgentScopes.READ, AgentScopes.required("GET", "/api/brands/kimi/instances"));
        assertEquals(AgentScopes.READ, AgentScopes.required("GET", "/api/brands/kimi/instances/a"));
        assertEquals(AgentScopes.READ, AgentScopes.required("GET", "/api/zcode/instances"));
        assertEquals(AgentScopes.READ, AgentScopes.required("GET", "/api/zcode/instances/a"));

        // Nothing deeper and nothing else: logs, plugins, accounts, settings,
        // health and every mutation are outside the grant.
        assertNull(AgentScopes.required("GET", "/api/instances/a/logs"));
        assertNull(AgentScopes.required("GET", "/api/instances/a/plugins"));
        assertNull(AgentScopes.required("GET", "/api/accounts"));
        assertNull(AgentScopes.required("GET", "/api/settings/registry"));
        assertNull(AgentScopes.required("GET", "/api/health"));
        assertNull(AgentScopes.required("POST", "/api/instances"));
        assertNull(AgentScopes.required("DELETE", "/api/instances/a"));
        assertNull(AgentScopes.required("POST", "/api/instances/a/stop"));
        assertNull(AgentScopes.required("POST", "/api/instances/a/launch"));
        assertNull(AgentScopes.required("POST", "/api/instances/a/terminal"));
        assertNull(AgentScopes.required("POST", "/api/brands/kimi/instances/a/stop"));
        assertNull(AgentScopes.required("DELETE", "/api/zcode/instances/a"));

        // A route nobody classified is closed, not open.
        assertNull(AgentScopes.required("POST", "/api/something-new"));

        // One class, on purpose.
        assertEquals(Set.of(AgentScopes.READ), AgentScopes.KNOWN);

        // Defaults are read-only.
        assertEquals(Set.of(AgentScopes.READ), AgentScopes.DEFAULT);
    }

    private TestSupport.RunningServer start() throws Exception {
        return TestSupport.start(dataDir,
                Map.of(UserStore.ENV_ADMIN_PASSWORD, PANEL_PASSWORD), config -> {
                    config.bindHost = "127.0.0.1";
                    config.https.enabled = true;
                });
    }

    private static String elevatedSession(HttpClient client, String base) throws Exception {
        HttpResponse<String> login = post(client, base + "/api/auth/login",
                "{\"username\":\"admin\",\"password\":\"" + PANEL_PASSWORD + "\"}", null);
        assertEquals(200, login.statusCode(), login.body());
        String cookie = login.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
        assertEquals(200, post(client, base + "/api/developer/password",
                "{\"adminPassword\":\"" + PANEL_PASSWORD + "\",\"password\":\"" + DEV_PASSWORD
                        + "\",\"confirm\":\"" + DEV_PASSWORD + "\"}", cookie).statusCode());
        assertEquals(200, post(client, base + "/api/developer/elevate",
                "{\"password\":\"" + DEV_PASSWORD + "\"}", cookie).statusCode());
        return cookie;
    }

    private static HttpResponse<String> get(HttpClient client, String url, String cookie) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).header("Cookie", cookie).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> getWithBearer(HttpClient client, String url, String bearer)
            throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url))
                        .header("Authorization", "Bearer " + bearer).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> delete(HttpClient client, String url, String cookie) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url))
                        .header("Cookie", cookie).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(HttpClient client, String url, String body, String cookie)
            throws Exception {
        return post(client, url, body, cookie, null);
    }

    private static HttpResponse<String> post(HttpClient client, String url, String body,
                                             String cookie, String bearer) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonObject json(HttpResponse<String> response) {
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }
}
