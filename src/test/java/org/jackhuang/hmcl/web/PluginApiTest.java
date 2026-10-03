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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.jackhuang.hmcl.dsh.DshInstanceManager;
import org.jackhuang.hmcl.dsh.DshProcessManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// The plugin REST surface over real HTTP with a plugin-capable stub dsh:
/// the catalogue (served, cached, refreshed, failed), install/remove task
/// flows, a local `.tgz` upload, and the build-script approval dance —
/// `waiting_approval` → `POST /api/tasks/{id}/approve` → done, on both answers.
class PluginApiTest {

    private static final String VERSION = "0.1.7-rc.1";

    private final List<String> created = new ArrayList<>();
    private final List<HttpServer> stubs = new ArrayList<>();

    @TempDir
    Path dataDir;

    @AfterEach
    void tearDown() throws Exception {
        System.clearProperty("hdsl.pluginCatalog");
        for (HttpServer stub : stubs) {
            stub.stop(0);
        }
        stubs.clear();
        for (String id : created) {
            DshProcessManager.stop(id);
            if (DshInstanceManager.exists(id)) {
                DshInstanceManager.delete(id);
            }
        }
        created.clear();
    }

    private TestSupport.RunningServer startServer() throws Exception {
        return TestSupport.start(dataDir, Map.of(), config -> {
            config.bindHost = "127.0.0.1";
            config.auth.disabled = true;
        });
    }

    private static boolean pnpmAvailable() {
        try {
            Process process = new ProcessBuilder("pnpm", "--version").redirectErrorStream(true).start();
            return process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void assumePluginTooling() {
        NodeDshStub.assumeNode();
        assumeTrue(pnpmAvailable(), "pnpm is required on PATH for plugin management");
    }

    // ---------------------------------------------------------------- catalog --

    private HttpServer catalogStub(String body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/plugins.json", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        stubs.add(server);
        return server;
    }

    private static final String CATALOG_BODY = """
            {"updated":"2026-01-01","plugins":[
              {"name":"alpha","owner":"someone","url":"https://github.com/someone/alpha",
               "category":"tools","description":{"en":"A plugin","zh":"一个插件"},
               "npm":"@someone/alpha","version":"1.2.3","stars":42,"downloads":1000,
               "added":"2025-01-01"}
            ]}
            """;

    @Test
    void catalogIsServedCachedAndRefreshed() throws Exception {
        HttpServer stub = catalogStub(CATALOG_BODY);
        System.setProperty("hdsl.pluginCatalog",
                "http://127.0.0.1:" + stub.getAddress().getPort() + "/plugins.json");
        try (TestSupport.RunningServer running = startServer()) {
            HttpClient client = TestSupport.client();
            String base = running.baseUrl();

            HttpResponse<String> first = get(client, base + "/api/plugins/catalog");
            assertEquals(200, first.statusCode(), first.body());
            JsonArray plugins = json(first).getAsJsonArray("plugins");
            assertEquals(1, plugins.size());
            JsonObject alpha = plugins.get(0).getAsJsonObject();
            assertEquals("alpha", alpha.get("name").getAsString());
            assertEquals("someone", alpha.get("owner").getAsString());
            assertEquals("https://github.com/someone/alpha", alpha.get("url").getAsString());
            assertEquals("tools", alpha.get("category").getAsString());
            assertEquals("A plugin", alpha.get("description").getAsString());
            assertEquals("一个插件", alpha.get("descriptionZh").getAsString());
            assertEquals("@someone/alpha", alpha.get("npm").getAsString());
            assertEquals("1.2.3", alpha.get("version").getAsString());
            assertEquals(42, alpha.get("stars").getAsInt());
            assertEquals(1000, alpha.get("downloads").getAsInt());
            assertTrue(alpha.get("tarball").isJsonNull());
            assertEquals("2025-01-01", alpha.get("added").getAsString());

            // The upstream now speaks garbage; the ten-minute cache hides that.
            stub.stop(0);
            stubs.remove(stub);
            HttpServer garbage = catalogStub("this is not a catalogue");
            System.setProperty("hdsl.pluginCatalog",
                    "http://127.0.0.1:" + garbage.getAddress().getPort() + "/plugins.json");
            HttpResponse<String> cached = get(client, base + "/api/plugins/catalog");
            assertEquals(200, cached.statusCode(), "the in-memory cache must answer inside its TTL");
            assertEquals("alpha", json(cached).getAsJsonArray("plugins").get(0)
                    .getAsJsonObject().get("name").getAsString());

            // A forced refresh really asks upstream, and its failure is a 502.
            HttpResponse<String> refreshed = get(client, base + "/api/plugins/catalog?refresh=1");
            assertEquals(502, refreshed.statusCode(), refreshed.body());
            assertNotNull(json(refreshed).get("error"));
        }
    }

    // ------------------------------------------------- install / remove / list --

    private String createInstance(HttpClient client, String base, String name) throws Exception {
        HttpResponse<String> response = post(client, base + "/api/instances",
                "{\"name\":\"" + name + "\",\"version\":\"" + VERSION + "\",\"autoInstall\":false}");
        assertEquals(201, response.statusCode(), response.body());
        String id = json(response).getAsJsonObject("instance").get("id").getAsString();
        created.add(id);
        return id;
    }

    @Test
    void pluginInstallRemoveAndListLifecycle() throws Exception {
        assumePluginTooling();
        try (TestSupport.RunningServer running = startServer()) {
            HttpClient client = TestSupport.client();
            String base = running.baseUrl();
            String id = createInstance(client, base, "plugins");

            // Not installed yet: installs are a conflict.
            HttpResponse<String> refused = post(client, base + "/api/instances/" + id + "/plugins/install",
                    "{\"specs\":[\"@acme/tool\"]}");
            assertEquals(409, refused.statusCode());

            NodeDshStub.installPluginCapable(DshInstanceManager.find(id), false);

            // An empty specs array is a 400.
            assertEquals(400, post(client, base + "/api/instances/" + id + "/plugins/install",
                    "{\"specs\":[]}").statusCode());

            HttpResponse<String> install = post(client, base + "/api/instances/" + id + "/plugins/install",
                    "{\"specs\":[\"@acme/tool@1.2.3\",\"plain\"]}");
            assertEquals(202, install.statusCode(), install.body());
            String taskId = json(install).get("taskId").getAsString();
            JsonObject task = awaitTask(client, base, taskId);
            assertEquals("done", task.get("state").getAsString(),
                    "install task must finish: " + task);

            HttpResponse<String> listed = get(client, base + "/api/instances/" + id + "/plugins");
            assertEquals(200, listed.statusCode());
            JsonArray plugins = json(listed).getAsJsonArray("plugins");
            assertEquals(2, plugins.size());
            JsonObject pinned = findPlugin(plugins, "@acme/tool");
            assertNotNull(pinned, "the pinned spec must be a dependency");
            assertEquals("1.2.3", pinned.get("version").getAsString());
            JsonObject plain = findPlugin(plugins, "plain");
            assertNotNull(plain);
            assertEquals("*", plain.get("version").getAsString());
            assertTrue(json(listed).has("bundles"));

            HttpResponse<String> remove = post(client, base + "/api/instances/" + id + "/plugins/remove",
                    "{\"specs\":[\"@acme/tool\"]}");
            assertEquals(202, remove.statusCode());
            JsonObject removed = awaitTask(client, base, json(remove).get("taskId").getAsString());
            assertEquals("done", removed.get("state").getAsString(), "remove task must finish");

            JsonArray after = json(get(client, base + "/api/instances/" + id + "/plugins"))
                    .getAsJsonArray("plugins");
            assertEquals(1, after.size());
            assertEquals("plain", after.get(0).getAsJsonObject().get("name").getAsString());
        }
    }

    private static JsonObject findPlugin(JsonArray plugins, String name) {
        for (int i = 0; i < plugins.size(); i++) {
            JsonObject plugin = plugins.get(i).getAsJsonObject();
            if (name.equals(plugin.get("name").getAsString())) {
                return plugin;
            }
        }
        return null;
    }

    // ------------------------------------------------------------- local upload --

    @Test
    void localPluginUploadInstallsFromTheInstancesOwnCopy() throws Exception {
        assumePluginTooling();
        try (TestSupport.RunningServer running = startServer()) {
            HttpClient client = TestSupport.client();
            String base = running.baseUrl();
            String id = createInstance(client, base, "local-plugins");
            NodeDshStub.installPluginCapable(DshInstanceManager.find(id), false);

            byte[] tgz = pack("dsh-example", "1.2.3", "./cordis.patch.yml");
            HttpResponse<String> upload = multipart(client, base + "/api/instances/" + id + "/plugins/local",
                    "file", "whatever-the-user-called-it.tgz", tgz);
            assertEquals(202, upload.statusCode(), upload.body());
            JsonObject task = awaitTask(client, base, json(upload).get("taskId").getAsString());
            assertEquals("done", task.get("state").getAsString(), "upload install must finish: " + task);

            // The profile records the instance's own copy as a file: dependency.
            JsonArray plugins = json(get(client, base + "/api/instances/" + id + "/plugins"))
                    .getAsJsonArray("plugins");
            assertEquals(1, plugins.size());
            JsonObject installed = plugins.get(0).getAsJsonObject();
            assertTrue(installed.get("version").getAsString().startsWith("file:"),
                    "a local plugin is recorded as a file: dependency: " + installed);
            assertTrue(installed.get("version").getAsString().contains("dsh-example"),
                    "the instance's own copy is named after the package: " + installed);

            // The copy really is inside the instance.
            assertTrue(Files.isRegularFile(DshInstanceManager.find(id).instanceDirectory()
                    .resolve("plugins").resolve("dsh-example-1.2.3.tgz")));

            // A non-archive upload is a 400, not a failed task.
            HttpResponse<String> bad = multipart(client, base + "/api/instances/" + id + "/plugins/local",
                    "file", "notes.zip", "not a plugin".getBytes(StandardCharsets.UTF_8));
            assertEquals(400, bad.statusCode());
        }
    }

    // ---------------------------------------------------------- approval dance --

    @Test
    void buildScriptApprovalParksTheTaskUntilAllowed() throws Exception {
        assumePluginTooling();
        try (TestSupport.RunningServer running = startServer()) {
            HttpClient client = TestSupport.client();
            String base = running.baseUrl();
            String id = createInstance(client, base, "approval");
            NodeDshStub.installPluginCapable(DshInstanceManager.find(id), true);

            HttpResponse<String> install = post(client, base + "/api/instances/" + id + "/plugins/install",
                    "{\"specs\":[\"node-pty\"]}");
            assertEquals(202, install.statusCode());
            String taskId = json(install).get("taskId").getAsString();

            // The task parks with the pending keys spelled out.
            JsonObject parked = awaitTaskState(client, base, taskId, "waiting_approval");
            JsonObject approval = parked.getAsJsonObject("approval");
            assertNotNull(approval, "a parked task must carry its approval: " + parked);
            assertEquals("build-scripts", approval.get("kind").getAsString());
            JsonArray keys = approval.getAsJsonArray("keys");
            assertEquals(1, keys.size());
            assertEquals("node-pty", keys.get(0).getAsString());

            // Approve endpoints validate their input.
            assertEquals(404, post(client, base + "/api/tasks/no-such-task/approve",
                    "{\"allow\":true}").statusCode());
            assertEquals(400, post(client, base + "/api/tasks/" + taskId + "/approve", "{}").statusCode());

            HttpResponse<String> approved = post(client, base + "/api/tasks/" + taskId + "/approve",
                    "{\"allow\":true}");
            assertEquals(200, approved.statusCode(), approved.body());
            JsonObject done = awaitTask(client, base, taskId);
            assertEquals("done", done.get("state").getAsString(), "the retry must finish: " + done);

            // The answer was written into the profile's workspace file.
            Path workspace = DshInstanceManager.find(id).homeDirectory()
                    .resolve("profiles").resolve("web").resolve("pnpm-workspace.yaml");
            assertTrue(Files.readString(workspace).contains("node-pty: true"));

            // Approving a finished task is a conflict.
            assertEquals(409, post(client, base + "/api/tasks/" + taskId + "/approve",
                    "{\"allow\":true}").statusCode());

            // And the plugin is really installed.
            JsonArray plugins = json(get(client, base + "/api/instances/" + id + "/plugins"))
                    .getAsJsonArray("plugins");
            assertNotNull(findPlugin(plugins, "node-pty"));
            assertFalse(findPlugin(plugins, "node-pty").has("pending")
                            && findPlugin(plugins, "node-pty").get("pending").getAsBoolean(),
                    "an answered script is no longer pending");
        }
    }

    @Test
    void buildScriptDenialCompletesTheInstallWithoutRunningScripts() throws Exception {
        assumePluginTooling();
        try (TestSupport.RunningServer running = startServer()) {
            HttpClient client = TestSupport.client();
            String base = running.baseUrl();
            String id = createInstance(client, base, "denial");
            NodeDshStub.installPluginCapable(DshInstanceManager.find(id), true);

            HttpResponse<String> install = post(client, base + "/api/instances/" + id + "/plugins/install",
                    "{\"specs\":[\"node-pty\"]}");
            String taskId = json(install).get("taskId").getAsString();
            awaitTaskState(client, base, taskId, "waiting_approval");

            HttpResponse<String> denied = post(client, base + "/api/tasks/" + taskId + "/approve",
                    "{\"allow\":false}");
            assertEquals(200, denied.statusCode(), denied.body());
            JsonObject done = awaitTask(client, base, taskId);
            assertEquals("done", done.get("state").getAsString(),
                    "NEVER semantics install the package without running its scripts: " + done);

            Path workspace = DshInstanceManager.find(id).homeDirectory()
                    .resolve("profiles").resolve("web").resolve("pnpm-workspace.yaml");
            assertTrue(Files.readString(workspace).contains("node-pty: false"));
        }
    }

    // ----------------------------------------------------------------- helpers --

    private static JsonObject awaitTask(HttpClient client, String base, String taskId) throws Exception {
        JsonObject task = awaitTaskState(client, base, taskId, null);
        assertNotNull(task);
        return task;
    }

    /// Polls the task until it leaves `running` (any terminal or waiting
    /// state), or until it reaches `wanted` when one is named.
    private static JsonObject awaitTaskState(HttpClient client, String base, String taskId,
                                             String wanted) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        JsonObject last = null;
        while (System.nanoTime() < deadline) {
            HttpResponse<String> poll = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/tasks/" + taskId)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, poll.statusCode());
            last = json(poll);
            String state = last.get("state").getAsString();
            if (wanted != null) {
                if (wanted.equals(state)) {
                    return last;
                }
            } else if (!"running".equals(state) && !"waiting_approval".equals(state)) {
                return last;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("task " + taskId + " never reached the wanted state; last: " + last);
    }

    private static HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(HttpClient client, String url, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> multipart(HttpClient client, String url, String field,
                                                  String fileName, byte[] content) throws Exception {
        String boundary = "----hdsl-test-boundary";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field
                + "\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: application/gzip\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(content);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return client.send(HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static JsonObject json(HttpResponse<String> response) {
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    /// Builds a gzipped tar holding a `package/package.json`, the shape
    /// [org.jackhuang.hmcl.dsh.DshLocalPlugins] reads.
    private static byte[] pack(String name, String version, String patch) throws Exception {
        String manifest = """
                {"name":"%s","version":"%s"%s}
                """.formatted(name, version,
                patch == null ? "" : ",\"dsh\":{\"bundle\":{\"patch\":\"" + patch + "\"}}");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(entry("package/package.json", manifest.getBytes(StandardCharsets.UTF_8)));
            gzip.write(new byte[1024]);
        }
        return out.toByteArray();
    }

    private static byte[] entry(String name, byte[] body) {
        byte[] block = new byte[512];
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(nameBytes, 0, block, 0, Math.min(nameBytes.length, 100));
        byte[] size = Long.toOctalString(body.length).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(size, 0, block, 124, size.length);
        block[156] = '0';
        for (int i = 148; i < 156; i++) {
            block[i] = ' ';
        }
        int padding = (512 - (body.length % 512)) % 512;
        byte[] result = new byte[512 + body.length + padding];
        System.arraycopy(block, 0, result, 0, 512);
        System.arraycopy(body, 0, result, 512, body.length);
        return result;
    }
}
