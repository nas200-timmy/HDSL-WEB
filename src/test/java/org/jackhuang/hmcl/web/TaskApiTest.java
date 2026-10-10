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
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.jackhuang.hmcl.web.event.EventBus;
import org.jackhuang.hmcl.web.http.TasksApiServlet;
import org.jackhuang.hmcl.web.task.TaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The task system: submission, progress, the done and failed endings, the
/// per-instance install mutex, cancellation, and the REST surface over
/// `/api/tasks` — including one real (but doomed) install submission.
class TaskApiTest {

    private static final String INSTANCE = "task-api-test";

    @TempDir
    Path dataDir;

    @AfterEach
    void tearDown() throws Exception {
        org.jackhuang.hmcl.dsh.DshProcessManager.stop(INSTANCE);
        if (org.jackhuang.hmcl.dsh.DshInstanceManager.exists(INSTANCE)) {
            org.jackhuang.hmcl.dsh.DshInstanceManager.delete(INSTANCE);
        }
    }

    // ------------------------------------------------------------- TaskService --

    @Test
    void taskLifecycleDoneAndFailedAndProgress() throws Exception {
        EventBus bus = new EventBus();
        TaskService tasks = new TaskService(bus);

        // The done path: the work's return value becomes the message.
        TaskService.Task done = tasks.submit("probe", null, () -> "all good");
        await(() -> !"running".equals(tasks.get(done.id()).orElseThrow().state()));
        TaskService.TaskInfo doneInfo = tasks.get(done.id()).orElseThrow();
        assertEquals("done", doneInfo.state());
        assertEquals("all good", doneInfo.message());
        assertEquals(1.0, doneInfo.fraction());

        // The failed path: the exception message is the error.
        TaskService.Task failed = tasks.submit("probe", null, () -> {
            throw new org.jackhuang.hmcl.dsh.DshException("boom");
        });
        await(() -> !"running".equals(tasks.get(failed.id()).orElseThrow().state()));
        TaskService.TaskInfo failedInfo = tasks.get(failed.id()).orElseThrow();
        assertEquals("failed", failedInfo.state());
        assertEquals("boom", failedInfo.error());

        // Progress updates flow into the snapshot and onto the bus.
        CountDownLatch event = new CountDownLatch(1);
        AtomicReference<JsonObject> payload = new AtomicReference<>();
        String newestId;
        try (EventBus.Subscription ignored = bus.subscribe("tasks", (topic, json) -> {
            payload.set(json);
            event.countDown();
        })) {
            TaskService.Task slow = tasks.submit("install", INSTANCE, () -> {
                Thread.sleep(100);
                return "installed";
            });
            newestId = slow.id();
            slow.update("resolved 40", 0.4);
            // The submit publishes one event; the update publishes another.
            // Drain until the progress event arrives.
            JsonObject json = null;
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < deadline) {
                JsonObject candidate = payload.get();
                if (candidate != null && "resolved 40".equals(candidate.get("message").getAsString())) {
                    json = candidate;
                    break;
                }
                if (event.await(200, TimeUnit.MILLISECONDS)) {
                    candidate = payload.get();
                    if (candidate != null && "resolved 40".equals(candidate.get("message").getAsString())) {
                        json = candidate;
                        break;
                    }
                }
            }
            assertNotNull(json, "no task event with the progress message");
            assertEquals("task", json.get("type").getAsString());
            assertEquals(INSTANCE, json.get("instance").getAsString());
            assertEquals("resolved 40", json.get("message").getAsString());
            assertEquals(0.4, json.get("fraction").getAsDouble());
            assertEquals("running", json.get("state").getAsString());

            // One install per instance: the mutex answers the second submit.
            assertThrows(TaskService.InstanceBusyException.class,
                    () -> tasks.submit("install", INSTANCE, () -> "second"));
            await(() -> !"running".equals(tasks.get(slow.id()).orElseThrow().state()));
            assertEquals("done", tasks.get(slow.id()).orElseThrow().state());
        }

        // The list reports every task, newest first.
        List<TaskService.TaskInfo> all = tasks.list();
        assertTrue(all.size() >= 3);
        assertEquals(newestId, all.get(0).id());
    }

    @Test
    void cancelMarksTheTaskAndTheWorkStops() throws Exception {
        EventBus bus = new EventBus();
        TaskService tasks = new TaskService(bus);
        CountDownLatch started = new CountDownLatch(1);
        TaskService.Task[] holder = new TaskService.Task[1];
        holder[0] = tasks.submit("probe", null, () -> {
            started.countDown();
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < deadline && !holder[0].isCancelled()) {
                Thread.sleep(10);
            }
            return holder[0].isCancelled() ? "stopped early" : "ran to the end";
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertTrue(tasks.cancel(holder[0].id()));
        await(() -> !"running".equals(tasks.get(holder[0].id()).orElseThrow().state()));
        assertEquals("done", tasks.get(holder[0].id()).orElseThrow().state());
        assertEquals("stopped early", tasks.get(holder[0].id()).orElseThrow().message());
        // Cancelling a finished task is a no-op; an unknown id too.
        assertTrue(!tasks.cancel(holder[0].id()));
        assertTrue(!tasks.cancel("no-such-task"));
    }

    /// 回归：取消一个任务只杀它自己的进程树。两个任务各跑一个 `sleep`，
    /// 取消 A 时 B 的进程必须活着（旧实现 stopRunning() 无差别全杀，
    /// MAX_INSTALLS=2 下取消一个实例的安装会弄死另一个实例的安装）。
    @Test
    void cancelKillsOnlyTheCancelledTasksProcess() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                org.jackhuang.hmcl.util.platform.OperatingSystem.CURRENT_OS
                        == org.jackhuang.hmcl.util.platform.OperatingSystem.LINUX,
                "uses the sleep(1) program");
        EventBus bus = new EventBus();
        TaskService tasks = new TaskService(bus);
        CountDownLatch bothStarted = new CountDownLatch(2);
        TaskService.Task[] holder = new TaskService.Task[2];
        for (int i = 0; i < 2; i++) {
            int n = i;
            holder[i] = tasks.submit("probe", null, () -> {
                bothStarted.countDown();
                // Mirrors the installers: a destroyed process is a non-zero
                // exit, and the work reports failure on it.
                var result = org.jackhuang.hmcl.dsh.DshCommand.run(List.of("sleep", "30"));
                if (!result.isSuccess()) {
                    throw new IllegalStateException("killed with exit " + result.exitCode());
                }
                return "slept";
            });
        }
        assertTrue(bothStarted.await(5, TimeUnit.SECONDS));
        // Both processes must be registered before the cancellation lands,
        // otherwise the regression (killing the sibling) cannot be observed.
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (org.jackhuang.hmcl.dsh.DshCommand.runningCount() < 2
                && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(2, org.jackhuang.hmcl.dsh.DshCommand.runningCount(),
                "both sleep processes must be registered before cancelling");

        assertTrue(tasks.cancel(holder[0].id()));
        // The sibling's process tree must survive the cancellation.
        Thread.sleep(500);
        assertEquals("running", tasks.get(holder[1].id()).orElseThrow().state(),
                "cancelling task A must not kill task B's process");
        assertEquals(1, org.jackhuang.hmcl.dsh.DshCommand.runningCount(),
                "only the cancelled task's process may be stopped");

        assertTrue(tasks.cancel(holder[1].id()));
        await(() -> !"running".equals(tasks.get(holder[0].id()).orElseThrow().state())
                && !"running".equals(tasks.get(holder[1].id()).orElseThrow().state()));
        assertEquals("cancelled", tasks.get(holder[0].id()).orElseThrow().message());
        assertEquals("cancelled", tasks.get(holder[1].id()).orElseThrow().message());
    }

    // ------------------------------------------------------------------- REST --

    @Test
    void installSubmissionFailsAndReportsThroughTheApi() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(NodeDshStub.nodeAvailable(),
                "node on PATH is required for the install path");
        try (TestSupport.RunningServer running = TestSupport.start(dataDir, Map.of(), config -> {
            config.bindHost = "127.0.0.1";
            config.auth.disabled = true;
        })) {
            HttpClient client = TestSupport.client();
            String base = running.baseUrl();

            HttpResponse<String> created = post(client, base + "/api/instances",
                    "{\"name\":\"" + INSTANCE + "\",\"version\":\"0.1.7-rc.1\",\"autoInstall\":false}");
            assertEquals(201, created.statusCode());

            // A version that cannot exist: the install task must fail, not
            // hang, and the task endpoint tells the story. (No real dsh is
            // installed; pnpm fails on the registry or the name, both of
            // which are the failure path this test wants.)
            HttpResponse<String> submitted = post(client, base + "/api/instances/" + INSTANCE + "/install",
                    "{\"version\":\"0.0.0-does-not-exist\"}");
            assertEquals(202, submitted.statusCode());
            String taskId = JsonParser.parseString(submitted.body()).getAsJsonObject().get("taskId").getAsString();
            assertNotNull(taskId);

            long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
            String state = "running";
            String error = null;
            while (System.nanoTime() < deadline && state.equals("running")) {
                Thread.sleep(200);
                HttpResponse<String> poll = client.send(
                        HttpRequest.newBuilder(URI.create(base + "/api/tasks/" + taskId)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, poll.statusCode());
                JsonObject json = JsonParser.parseString(poll.body()).getAsJsonObject();
                state = json.get("state").getAsString();
                error = json.has("error") ? json.get("error").getAsString() : null;
            }
            assertEquals("failed", state, "install of a fake version must fail, not hang");
            assertNotNull(error);

            // The task list contains it.
            HttpResponse<String> list = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/tasks")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, list.statusCode());
            assertTrue(list.body().contains(taskId));

            // The instance detail reports the failed install.
            HttpResponse<String> detail = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/instances/" + INSTANCE)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonObject progress = JsonParser.parseString(detail.body()).getAsJsonObject()
                    .getAsJsonObject("installProgress");
            assertEquals("failed", progress.get("state").getAsString());

            // Unknown tasks are 404.
            HttpResponse<String> unknown = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/tasks/no-such")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(404, unknown.statusCode());
        }
    }

    @Test
    void cancelEndpointStopsRunningWorkAndRefusesTheRest() throws Exception {
        EventBus bus = new EventBus();
        TaskService tasks = new TaskService(bus);

        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setHost("127.0.0.1");
        connector.setPort(0);
        server.addConnector(connector);
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.addServlet(new ServletHolder(new TasksApiServlet(tasks)), "/api/tasks/*");
        server.setHandler(context);
        server.start();
        try {
            HttpClient client = TestSupport.client();
            String base = "http://127.0.0.1:" + connector.getLocalPort();

            CountDownLatch started = new CountDownLatch(1);
            TaskService.Task[] holder = new TaskService.Task[1];
            holder[0] = tasks.submit("probe", INSTANCE, () -> {
                started.countDown();
                long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
                while (System.nanoTime() < deadline && !holder[0].isCancelled()) {
                    Thread.sleep(10);
                }
                return holder[0].isCancelled() ? "stopped early" : "ran to the end";
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            String taskId = holder[0].id();

            // A running task cancels: 200 {ok: true}, and the work observes it.
            HttpResponse<String> cancelled = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/tasks/" + taskId + "/cancel"))
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, cancelled.statusCode());
            assertTrue(JsonParser.parseString(cancelled.body()).getAsJsonObject().get("ok").getAsBoolean());
            await(() -> !"running".equals(tasks.get(taskId).orElseThrow().state()));
            assertEquals("stopped early", tasks.get(taskId).orElseThrow().message());

            // A finished task is a 409, an unknown one a 404.
            HttpResponse<String> finished = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/tasks/" + taskId + "/cancel"))
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(409, finished.statusCode());
            HttpResponse<String> unknown = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/tasks/no-such-task/cancel"))
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(404, unknown.statusCode());
        } finally {
            server.stop();
        }
    }

    private static HttpResponse<String> post(HttpClient client, String url, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline && !condition.getAsBoolean()) {
            Thread.sleep(50);
        }
        assertTrue(condition.getAsBoolean(), "condition never became true");
    }
}
