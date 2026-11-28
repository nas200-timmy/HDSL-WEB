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
package org.jackhuang.hmcl.web.plain;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jackhuang.hmcl.dsh.DshAccount;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.web.TestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The no-JavaScript surface: forms only, one page per action, the same turn
/// manager behind it. A text browser posts a form and reloads; nothing here
/// needs a script or a socket.
class PlainChatTest {

    @Test
    void theFallbackSurfaceAnswersWithoutScript(@TempDir Path dataDir) throws Exception {
        try (TestSupport.RunningServer running = TestSupport.start(dataDir, Map.of(),
                config -> {
                    config.auth.disabled = true;
                    config.bindHost = "127.0.0.1";
                })) {
            HttpResponse<String> home = get(running, "/plain");
            assertEquals(200, home.statusCode(), home.body());
            assertTrue(home.body().contains("会话"), home.body());
            assertTrue(home.body().contains("新建会话"), home.body());

            HttpResponse<String> old = get(running, "/plain/old");
            assertEquals(200, old.statusCode());
            assertTrue(old.body().contains("浏览器太旧"), old.body());
            assertTrue(old.body().contains("继续用纯文本版"), old.body());

            // The SPA entry carries the gate, so a browser without fetch/WebSocket/flex
            // never loads the bundle; it lands on the chat-capable plain surface.
            HttpResponse<String> index = get(running, "/chat");
            assertEquals(200, index.statusCode());
            assertTrue(index.body().contains("\"plain\""), index.body());
            assertFalse(index.body().contains("plain/old"), index.body());
        }
    }

    @Test
    void textBrowsersAndIeSkipTheSpaShell(@TempDir Path dataDir) throws Exception {
        try (TestSupport.RunningServer running = TestSupport.start(dataDir, Map.of(),
                config -> {
                    config.auth.disabled = true;
                    config.bindHost = "127.0.0.1";
                })) {
            HttpResponse<String> w3m = getWithUa(running, "/chat", "w3m/0.5.3+git20230121");
            assertEquals(302, w3m.statusCode(), w3m.body());
            assertEquals("/plain", w3m.headers().firstValue("Location").orElseThrow());

            HttpResponse<String> lynx = getWithUa(running, "/", "Lynx/2.9.0 libwww-FM/2.14");
            assertEquals(302, lynx.statusCode());
            assertEquals("/plain", lynx.headers().firstValue("Location").orElseThrow());

            HttpResponse<String> ie = getWithUa(running, "/",
                    "Mozilla/5.0 (Windows NT 6.1; Trident/7.0; rv:11.0) like Gecko");
            assertEquals(302, ie.statusCode());
            assertEquals("/plain", ie.headers().firstValue("Location").orElseThrow());

            HttpResponse<String> chrome = getWithUa(running, "/",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0 Safari/537.36");
            assertEquals(200, chrome.statusCode(), chrome.body());
            assertTrue(chrome.body().contains("\"plain\""), "the inline gate still ships to modern browsers");
        }
    }

    @Test
    void thePlainLoginFormMintsTheSessionCookie(@TempDir Path dataDir) throws Exception {
        try (TestSupport.RunningServer running = TestSupport.start(dataDir,
                Map.of(org.jackhuang.hmcl.web.auth.UserStore.ENV_ADMIN_PASSWORD, "plain-pass"),
                config -> config.bindHost = "127.0.0.1")) {
            HttpResponse<String> form = get(running, "/plain");
            assertEquals(200, form.statusCode(), form.body());
            assertTrue(form.body().contains("name=\"username\""), form.body());
            assertTrue(form.body().contains("name=\"password\""), form.body());

            HttpResponse<String> wrong = postForm(running, "/plain/login",
                    "username=admin&password=nope");
            assertEquals(200, wrong.statusCode(), wrong.body());
            assertTrue(wrong.body().contains("不对"), wrong.body());

            HttpResponse<String> ok = postForm(running, "/plain/login",
                    "username=admin&password=plain-pass");
            assertEquals(303, ok.statusCode(), ok.body());
            assertEquals("/plain", ok.headers().firstValue("Location").orElseThrow());
            String cookie = ok.headers().firstValue("set-cookie").orElseThrow();
            assertTrue(cookie.contains(org.jackhuang.hmcl.web.auth.SessionCookies.name() + "="), cookie);
            assertTrue(cookie.contains("SameSite=Strict"), cookie);

            HttpResponse<String> home = getWithCookie(running, "/plain", cookie.split(";")[0]);
            assertEquals(200, home.statusCode(), home.body());
            assertTrue(home.body().contains("会话"), home.body());
        }
    }

    @Test
    void aFormTurnLandsOnTheSameStore(@TempDir Path dataDir) throws Exception {
        HttpServer provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/v1/chat/completions", PlainChatTest::stream);
        provider.start();
        DshAccount account = new DshAccount(
                DshAccount.AccountKind.THIRD_PARTY, "plain-fake", "test-key",
                "http://127.0.0.1:" + provider.getAddress().getPort() + "/v1",
                null, "fake-model", null);
        SettingsManager.settings().getAccounts().add(account);
        try (TestSupport.RunningServer running = TestSupport.start(dataDir, Map.of(),
                config -> {
                    config.auth.disabled = true;
                    config.bindHost = "127.0.0.1";
                })) {
            HttpResponse<String> created = postForm(running, "/plain/new",
                    "assistantId=default");
            assertEquals(303, created.statusCode(), created.body());
            String location = created.headers().firstValue("Location").orElseThrow();
            assertTrue(location.startsWith("/plain/s/"), location);
            assertTrue(location.endsWith("#bottom"), location);
            String sessionId = location.substring("/plain/s/".length()).replace("#bottom", "");
            // Pin the session to the fake account: other test classes share the
            // SettingsManager, and a default account from one of them would send
            // this turn to a different endpoint.
            patchSession(running, sessionId, "{\"account\":\"plain-fake\",\"model\":\"fake-model\"}");

            HttpResponse<String> sent = postForm(running, "/plain/s/" + sessionId + "/send",
                    "text=" + URLEncoder.encode("hello", StandardCharsets.UTF_8) + "&auto=1");
            assertEquals(303, sent.statusCode(), sent.body());
            assertTrue(sent.headers().firstValue("Location").orElseThrow().endsWith("?auto=1#bottom"));

            String page = waitFor(running, sessionId, "hello there", Duration.ofSeconds(15));
            assertTrue(page.contains("助手"), page);
            assertTrue(page.contains("hello there"), page);
            // The composer is a plain form; a text browser can post it again.
            assertTrue(page.contains("name=\"text\""), page);
            assertTrue(page.contains("action=\"/plain/s/" + sessionId + "/send\""), page);
            // The IE11 visual layer uses the same avatar/bubble model as the SPA,
            // but real tables keep w3m's reading order and avoid broken flexbox.
            assertTrue(page.contains("class=\"chat-line user\""), page);
            assertTrue(page.contains("class=\"chat-line assistant\""), page);
            assertTrue(page.contains("class=\"chat-bubble\""), page);
            assertTrue(page.contains("class=\"chat-composer\""), page);
            assertTrue(page.contains("class=\"turn-sep\""), page);
        } finally {
            SettingsManager.settings().getAccounts().remove(account);
            provider.stop(0);
        }
    }

    private static void stream(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (var output = exchange.getResponseBody()) {
            output.write(("data: {\"choices\":[{\"delta\":{\"content\":\"hello \"},\"finish_reason\":null}]}\n\n")
                    .getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.write(("data: {\"choices\":[{\"delta\":{\"content\":\"there\"},\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":2}}\n\n")
                    .getBytes(StandardCharsets.UTF_8));
            output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
    }

    private static String waitFor(TestSupport.RunningServer running, String sessionId,
                                  String needle, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        String last = "";
        while (System.nanoTime() < deadline) {
            last = get(running, "/plain/s/" + sessionId).body();
            if (last.contains(needle)) {
                return last;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("the answer never appeared; last page: " + last);
    }

    @Test
    void theLegacyChatPageCarriesTheNewControls(@TempDir Path dataDir) throws Exception {
        try (TestSupport.RunningServer running = TestSupport.start(dataDir, Map.of(),
                config -> {
                    config.auth.disabled = true;
                    config.bindHost = "127.0.0.1";
                })) {
            HttpResponse<String> created = postForm(running, "/plain/new", "assistantId=default");
            assertEquals(303, created.statusCode(), created.body());
            String location = created.headers().firstValue("Location").orElseThrow();
            String sessionId = location.substring("/plain/s/".length()).replace("#bottom", "");

            HttpResponse<String> page = get(running, "/plain/s/" + sessionId);
            assertEquals(200, page.statusCode(), page.body());
            assertTrue(page.body().contains("enctype=\"multipart/form-data\""), page.body());
            assertTrue(page.body().contains("name=\"file\""), page.body());
            assertTrue(page.body().contains("name=\"reasoningLevel\""), page.body());
            assertTrue(page.body().contains("/rename"), page.body());
            assertTrue(page.body().contains("/delete"), page.body());

            HttpResponse<String> regen = postForm(running,
                    "/plain/s/" + sessionId + "/regenerate", "");
            assertEquals(303, regen.statusCode(), regen.body());
            assertTrue(regen.headers().firstValue("Location").orElseThrow().contains("error=no_reply"),
                    regen.headers().toString());

            HttpResponse<String> renamed = postForm(running,
                    "/plain/s/" + sessionId + "/rename",
                    "title=" + URLEncoder.encode("纯文本会话", StandardCharsets.UTF_8));
            assertEquals(303, renamed.statusCode(), renamed.body());
            assertTrue(get(running, "/plain/s/" + sessionId).body().contains("纯文本会话"));

            assertEquals(303, postForm(running,
                    "/plain/s/" + sessionId + "/reasoning", "reasoningLevel=medium").statusCode());
            assertTrue(get(running, "/plain/s/" + sessionId).body().contains("value=\"medium\" selected"));

            assertEquals(303, postForm(running, "/plain/s/" + sessionId + "/stop", "").statusCode());

            HttpResponse<String> unconfirmed = postForm(running,
                    "/plain/s/" + sessionId + "/delete", "");
            assertEquals(303, unconfirmed.statusCode(), unconfirmed.body());
            assertTrue(unconfirmed.headers().firstValue("Location").orElseThrow().contains("error=confirm"));

            HttpResponse<String> deleted = postForm(running,
                    "/plain/s/" + sessionId + "/delete", "confirm=yes");
            assertEquals(303, deleted.statusCode(), deleted.body());
            assertEquals("/plain", deleted.headers().firstValue("Location").orElseThrow());
            assertFalse(get(running, "/plain").body().contains("纯文本会话"));
        }
    }

    private static HttpResponse<String> get(TestSupport.RunningServer running, String path)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(running.baseUrl() + path))
                .timeout(Duration.ofSeconds(10)).GET().build();
        return TestSupport.client().send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static HttpResponse<String> getWithCookie(TestSupport.RunningServer running, String path,
                                                      String cookie) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(running.baseUrl() + path))
                .timeout(Duration.ofSeconds(10)).header("Cookie", cookie).GET().build();
        return TestSupport.client().send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static HttpResponse<String> getWithUa(TestSupport.RunningServer running, String path,
                                                  String userAgent) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(running.baseUrl() + path))
                .timeout(Duration.ofSeconds(10)).header("User-Agent", userAgent).GET().build();
        return TestSupport.client().send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static void patchSession(TestSupport.RunningServer running, String sessionId,
                                     String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        running.baseUrl() + "/api/chat/sessions/" + sessionId))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = TestSupport.client().send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode(), response.body());
    }

    private static HttpResponse<String> postForm(TestSupport.RunningServer running, String path,
                                                 String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(running.baseUrl() + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return TestSupport.client().send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}