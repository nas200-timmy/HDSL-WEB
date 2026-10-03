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
package org.jackhuang.hmcl.web;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshInstanceManager;
import org.jackhuang.hmcl.dsh.DshProcess;
import org.jackhuang.hmcl.dsh.DshProcessManager;
import org.jackhuang.hmcl.setting.GameDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The reverse proxy in front of a stub dsh: prefix stripping, Host
/// passthrough, Set-Cookie path rewriting, the 301 for a bare mount, the 404
/// and 502 answers, SSE streaming, and the WebSocket relay with ping/pong.
///
/// The "dsh" is a real child process running a node stub (see [NodeDshStub]);
/// the HTTP behaviors are served by the stub itself, and the WebSocket echo
/// backend is a Jetty server inside the test, bound to the port the stub
/// announces.
class ProxyBehaviorTest {

    private static final String INSTANCE = "proxy-behavior-test";

    private final List<Runnable> cleanup = new ArrayList<>();

    @TempDir
    Path dataDir;

    @AfterEach
    void tearDown() throws Exception {
        DshProcessManager.stop(INSTANCE);
        if (DshInstanceManager.exists(INSTANCE)) {
            DshInstanceManager.delete(INSTANCE);
        }
        for (Runnable task : cleanup) {
            task.run();
        }
        cleanup.clear();
    }

    private TestSupport.RunningServer startServer() throws Exception {
        TestSupport.RunningServer running = TestSupport.start(dataDir, Map.of(),
                config -> {
                    config.bindHost = "127.0.0.1";
                    config.auth.disabled = true;
                });
        cleanup.add(() -> {
            try {
                running.close();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        return running;
    }

    private DshInstance createInstance() throws Exception {
        return DshInstanceManager.create(INSTANCE, "0.1.7-rc.1", "web",
                GameDirectory.defaultDirectory().directory(),
                org.jackhuang.hmcl.dsh.DshHomeMode.ISOLATED, null,
                List.of(), Map.of());
    }

    private static HttpRequest.Builder request(String url, String cookie, String host) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url));
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        if (host != null) {
            builder.header("Host", host);
        }
        return builder;
    }

    // ------------------------------------------------------------------ HTTP --

    @Test
    void unknownInstanceIs404AndStoppedInstanceIs502AndBareMountRedirects() throws Exception {
        NodeDshStub.assumeNode();
        try (TestSupport.RunningServer running = startServer()) {
            HttpClient client = TestSupport.client();

            HttpResponse<String> unknown = client.send(
                    request(running.baseUrl() + "/i/no-such-instance/api/foo", null, null).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(404, unknown.statusCode());
            assertEquals("instance not found",
                    JsonParser.parseString(unknown.body()).getAsJsonObject().get("error").getAsString());

            createInstance();
            HttpResponse<String> stopped = client.send(
                    request(running.baseUrl() + "/i/" + INSTANCE + "/api/foo", null, null).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(502, stopped.statusCode());
            assertEquals("instance not running",
                    JsonParser.parseString(stopped.body()).getAsJsonObject().get("error").getAsString());

            HttpResponse<String> bare = client.send(
                    request(running.baseUrl() + "/i/" + INSTANCE + "?token=abc", null, null).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(301, bare.statusCode());
            assertEquals("/i/" + INSTANCE + "/?token=abc",
                    bare.headers().firstValue("location").orElseThrow());
        }
    }

    @Test
    void stripsPrefixPassesHostAndRewritesCookiePath() throws Exception {
        NodeDshStub.assumeNode();
        try (TestSupport.RunningServer running = startServer()) {
            HttpClient client = TestSupport.client();
            DshInstance instance = createInstance();
            NodeDshStub.install(instance, true);
            DshProcess process = DshProcessManager.launch(instance);
            try {
                awaitReady(process);

                // Prefix stripping: the backend sees `/echo`, not the mount.
                HttpResponse<String> echo = client.send(
                        request(running.baseUrl() + "/i/" + INSTANCE + "/echo?a=1", null, null).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, echo.statusCode());
                JsonObject echoed = JsonParser.parseString(echo.body()).getAsJsonObject();
                assertEquals("/echo?a=1", echoed.get("path").getAsString());

                // Host passthrough: dsh compares Origin to Host, so the value
                // must arrive verbatim.
                HttpResponse<String> hosted = client.send(
                        request(running.baseUrl() + "/i/" + INSTANCE + "/echo", null, "panel.example.com:8080")
                                .header("Origin", "http://panel.example.com:8080")
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                JsonObject hostEcho = JsonParser.parseString(hosted.body()).getAsJsonObject();
                assertEquals("panel.example.com:8080", hostEcho.get("host").getAsString());
                assertEquals("http://panel.example.com:8080", hostEcho.get("origin").getAsString());

                // Request bodies stream through.
                HttpResponse<String> posted = client.send(
                        request(running.baseUrl() + "/i/" + INSTANCE + "/echo", null, null)
                                .POST(HttpRequest.BodyPublishers.ofString("hello upstream")).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals("hello upstream",
                        JsonParser.parseString(posted.body()).getAsJsonObject().get("body").getAsString());

                // Set-Cookie `Path=/` is rewritten onto the mount.
                HttpResponse<String> cookieResponse = client.send(
                        request(running.baseUrl() + "/i/" + INSTANCE + "/cookie", null, null).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                List<String> cookies = cookieResponse.headers().allValues("set-cookie");
                String joined = String.join("\n", cookies);
                assertTrue(joined.contains("x=1; Path=/i/" + INSTANCE + "/"), joined);
                assertTrue(joined.contains("y=2; Path=/i/" + INSTANCE + "/; HttpOnly"), joined);
                assertFalse(joined.contains("Path=/;"), joined);
                assertFalse(joined.contains("Path=/\n"), joined);
            } finally {
                process.stop();
            }
        }
    }

    @Test
    void sseStreamsThroughTheProxy() throws Exception {
        NodeDshStub.assumeNode();
        try (TestSupport.RunningServer running = startServer()) {
            HttpClient client = TestSupport.client();
            DshInstance instance = createInstance();
            NodeDshStub.install(instance, true);
            DshProcess process = DshProcessManager.launch(instance);
            try {
                awaitReady(process);
                HttpResponse<java.io.InputStream> sse = client.send(
                        request(running.baseUrl() + "/i/" + INSTANCE + "/sse", null, null).GET().build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                assertEquals(200, sse.statusCode());
                assertEquals("text/event-stream", sse.headers().firstValue("content-type").orElseThrow());
                String body = new String(sse.body().readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(body.contains("data: one"));
                assertTrue(body.contains("data: two"));
                assertTrue(body.contains("data: three"));
            } finally {
                process.stop();
            }
        }
    }

    // -------------------------------------------------------------------- WS --

    @Test
    void websocketUpgradeIsRelayedWithFramesAndPingPong() throws Exception {
        NodeDshStub.assumeNode();
        DshInstance instance = createInstance();
        int backendPort = instance.portOrDefault();

        try (TestSupport.RunningServer running = startServer()) {
            // The passive stub announces readiness on the instance's port
            // without binding it; the backend binds that port once the launch
            // (whose port check needs it free) is through.
            NodeDshStub.install(instance, false);
            DshProcess process = DshProcessManager.launch(instance);
            try {
                awaitReady(process);

                // The WebSocket echo backend, on the port the readiness line
                // announced.
                Server backend = new Server();
                ServerConnector connector = new ServerConnector(backend);
                connector.setHost("127.0.0.1");
                connector.setPort(backendPort);
                backend.addConnector(connector);
                List<Map.Entry<String, String>> seenHeaders = new ArrayList<>();
                backend.setHandler(WebSocketUpgradeHandler.from(backend, container ->
                        container.addMapping("/ws-echo", (upgradeRequest, upgradeResponse, callback) -> {
                            upgradeRequest.getHeaders().forEach(field ->
                                    seenHeaders.add(Map.entry(field.getName(), field.getValue())));
                            return new EchoSocket();
                        })));
                backend.start();
                cleanup.add(() -> {
                    try {
                        backend.stop();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

                WebSocketClient wsClient = new WebSocketClient();
                wsClient.start();
                cleanup.add(() -> {
                    try {
                        wsClient.stop();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

                BlockingQueue<String> texts = new LinkedBlockingQueue<>();
                BlockingQueue<ByteBuffer> pings = new LinkedBlockingQueue<>();
                BlockingQueue<ByteBuffer> pongs = new LinkedBlockingQueue<>();
                CountDownLatch closed = new CountDownLatch(1);
                BrowserRecording browserRecording = new BrowserRecording(texts, pings, pongs, closed);
                ClientUpgradeRequest upgrade = new ClientUpgradeRequest();
                upgrade.setHeader("Origin", "http://panel.example.com");
                upgrade.setHeader("Cookie", "flavor=chocolate");
                Session browser = wsClient.connect(browserRecording,
                        URI.create("ws://127.0.0.1:" + running.port() + "/i/" + INSTANCE + "/ws-echo"),
                        upgrade).get(10, TimeUnit.SECONDS);

                // Text round trip.
                browser.sendText("hello", Callback.NOOP);
                assertEquals("echo:hello", texts.poll(10, TimeUnit.SECONDS));

                // The handshake headers travelled: Origin and Cookie arrive at
                // the backend verbatim.
                assertTrue(seenHeaders.stream().anyMatch(entry ->
                        entry.getKey().equalsIgnoreCase("Origin")
                                && entry.getValue().equals("http://panel.example.com")));
                assertTrue(seenHeaders.stream().anyMatch(entry ->
                        entry.getKey().equalsIgnoreCase("Cookie")
                                && entry.getValue().equals("flavor=chocolate")));

                // A browser ping is ponged locally and forwarded upstream; the
                // backend's reply comes back as a pong through the relay.
                browser.sendPing(ByteBuffer.wrap("marco".getBytes(StandardCharsets.UTF_8)), Callback.NOOP);
                assertEquals("marco", ascii(pongs.poll(10, TimeUnit.SECONDS)));

                // A backend ping reaches the browser through the relay.
                browser.sendText("pingme", Callback.NOOP);
                assertEquals("polo", ascii(pings.poll(10, TimeUnit.SECONDS)));

                browser.close();
                assertTrue(closed.await(10, TimeUnit.SECONDS));
            } finally {
                process.stop();
            }
        }
    }

    // ---------------------------------------------------------------- helpers --

    private static void awaitReady(DshProcess process) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (process.webUrl().isPresent()) {
                return;
            }
            Thread.sleep(50);
        }
        assertNotNull(process.webUrl().orElse(null), "the stub never printed its readiness line");
    }

    private static String ascii(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static ByteBuffer copy(ByteBuffer source) {
        ByteBuffer copy = ByteBuffer.allocate(source.remaining());
        copy.put(source.duplicate());
        copy.flip();
        return copy;
    }

    /// The backend WebSocket: echoes text, answers pings, and sends a ping
    /// back when asked. Public for the same method-handle reason as
    /// [BrowserRecording].
    public static final class EchoSocket implements Session.Listener.AutoDemanding {
        private Session session;

        @Override
        public void onWebSocketOpen(Session session) {
            this.session = session;
        }

        @Override
        public void onWebSocketText(String message) {
            if ("pingme".equals(message)) {
                session.sendPing(ByteBuffer.wrap("polo".getBytes(StandardCharsets.UTF_8)), Callback.NOOP);
                return;
            }
            session.sendText("echo:" + message, Callback.NOOP);
        }

        @Override
        public void onWebSocketPing(ByteBuffer payload) {
            session.sendPong(copy(payload), Callback.NOOP);
        }
    }

    /// The browser side of the relay test, recording what arrives. Named and
    /// public because Jetty's client binds endpoint methods through method
    /// handles, which cannot see into anonymous classes.
    public static final class BrowserRecording implements Session.Listener.AutoDemanding {
        private final BlockingQueue<String> texts;
        private final BlockingQueue<ByteBuffer> pings;
        private final BlockingQueue<ByteBuffer> pongs;
        private final CountDownLatch closed;

        public BrowserRecording(BlockingQueue<String> texts, BlockingQueue<ByteBuffer> pings,
                                BlockingQueue<ByteBuffer> pongs, CountDownLatch closed) {
            this.texts = texts;
            this.pings = pings;
            this.pongs = pongs;
            this.closed = closed;
        }

        @Override
        public void onWebSocketText(String message) {
            texts.add(message);
        }

        @Override
        public void onWebSocketPing(ByteBuffer payload) {
            pings.add(copy(payload));
        }

        @Override
        public void onWebSocketPong(ByteBuffer payload) {
            pongs.add(copy(payload));
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason) {
            closed.countDown();
        }
    }
}
