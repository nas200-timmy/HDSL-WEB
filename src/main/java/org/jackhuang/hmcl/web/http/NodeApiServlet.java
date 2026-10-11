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
package org.jackhuang.hmcl.web.http;

import com.google.gson.JsonObject;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jackhuang.hmcl.dsh.DshNodeRuntime;
import org.jackhuang.hmcl.dsh.NodeRelease;
import org.jackhuang.hmcl.dsh.NodeRuntime;
import org.jackhuang.hmcl.dsh.NodeRuntimeManager;
import org.jackhuang.hmcl.dsh.NodeSource;
import org.jackhuang.hmcl.web.task.TaskService;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/// The Node.js toolchain for bare deployments: reports the system probe and
/// the panel-managed runtimes, and installs/uninstalls the latter — the
/// one-click path a machine without Node.js uses to become able to install
/// dsh versions, plugins and brand tools (`Settings -> Node.js`).
///
/// - `GET  /api/node`           — `{system, managed[], requirement}`
/// - `POST /api/node/install`   — `{version?, source?}` → `{taskId}`;
///                                an empty version picks the newest release
///                                the harness supports
/// - `POST /api/node/uninstall` — `{version}` → `{ok}`
///
/// Managed runtimes live under `<data>/hdsl/runtimes` and are picked up
/// automatically by every toolchain consumer when PATH has no `node`.
@NotNullByDefault
public final class NodeApiServlet extends HttpServlet {

    private final TaskService tasks;

    public NodeApiServlet(TaskService tasks) {
        this.tasks = tasks;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        JsonObject body = new JsonObject();
        DshNodeRuntime system = DshNodeRuntime.detect().orElse(null);
        JsonObject systemJson = new JsonObject();
        if (system != null) {
            systemJson.addProperty("node", system.node().toString());
            systemJson.addProperty("nodeVersion", system.nodeVersion());
            if (system.npm() != null) {
                systemJson.addProperty("npm", system.npm().toString());
                systemJson.addProperty("npmVersion", system.npmVersion());
            }
            if (system.pnpm() != null) {
                systemJson.addProperty("pnpm", system.pnpm().toString());
                systemJson.addProperty("pnpmVersion", system.pnpmVersion());
            }
        }
        body.add("system", system == null ? com.google.gson.JsonNull.INSTANCE : systemJson);
        List<NodeRuntime> managed = NodeRuntimeManager.listInstalled();
        body.add("managed", Json.GSON.toJsonTree(managed.stream().map(runtime -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("version", runtime.version());
            entry.addProperty("suitable", DshNodeRuntime.isSupportedNodeVersion(runtime.version()));
            entry.addProperty("node", runtime.node().toString());
            Path pnpm = runtime.directory().resolve("bin").resolve("pnpm");
            entry.addProperty("pnpm", java.nio.file.Files.isExecutable(pnpm));
            return entry;
        }).toList()));
        body.addProperty("requirement", DshNodeRuntime.requirement());
        Json.write(response, body);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getPathInfo();
        if (path == null || path.equals("/") || path.equals("/install")) {
            install(request, response);
            return;
        }
        if (path.equals("/uninstall")) {
            uninstall(request, response);
            return;
        }
        Json.error(response, HttpServletResponse.SC_NOT_FOUND, "unknown endpoint");
    }

    private void install(HttpServletRequest request, HttpServletResponse response) throws IOException {
        JsonObject body = parseBody(request);
        String version = body == null || !body.has("version") ? null : body.get("version").getAsString().trim();
        // 面板用户群主要在国内：默认 npmmirror，官方源可作退路（索引查询本身双源互备）。
        NodeSource source = body != null && body.has("source")
                ? NodeSource.of(body.get("source").getAsString())
                : NodeSource.MIRROR;

        // 进度回调里要用任务句柄，而句柄在 submit 之后才存在——经典 holder 模式
        TaskService.Task[] holder = new TaskService.Task[1];
        holder[0] = tasks.submit("node-install", null, () -> {
            String picked = version == null || version.isEmpty() ? newestSupported(source) : version;
            NodeRuntime installed = NodeRuntimeManager.install(picked, source, stage -> {
                TaskService.Task task = holder[0];
                if (task != null) {
                    task.update(stage, -1);
                }
            });
            return "Installed Node.js " + installed.version();
        });
        Json.write(response, java.util.Map.of("taskId", holder[0].id()));
    }

    private void uninstall(HttpServletRequest request, HttpServletResponse response) throws IOException {
        JsonObject body = parseBody(request);
        if (body == null || !body.has("version")) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "version is required");
            return;
        }
        try {
            NodeRuntimeManager.uninstall(body.get("version").getAsString().trim());
        } catch (org.jackhuang.hmcl.dsh.DshException e) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
            return;
        }
        Json.write(response, java.util.Map.of("ok", true));
    }

    /// Reads the JSON body, tolerating an empty one.
    private static @Nullable JsonObject parseBody(HttpServletRequest request) throws IOException {
        String raw = request.getReader().lines().collect(java.util.stream.Collectors.joining());
        return raw.isBlank() ? null : Json.GSON.fromJson(raw, JsonObject.class);
    }

    /// The newest published release the harness supports, from the release
    /// index (tries the chosen source, then the other).
    private static String newestSupported(NodeSource source) throws Exception {
        List<NodeRelease> releases = NodeRuntimeManager.fetchReleases(source);
        return releases.stream()
                .map(NodeRelease::version)
                .filter(DshNodeRuntime::isSupportedNodeVersion)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "no supported Node.js release found (" + DshNodeRuntime.requirement() + ")"));
    }
}
