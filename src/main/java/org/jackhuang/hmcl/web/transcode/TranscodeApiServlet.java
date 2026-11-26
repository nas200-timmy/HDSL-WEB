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
package org.jackhuang.hmcl.web.transcode;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.web.audit.AuditLog;
import org.jackhuang.hmcl.web.auth.AuthFilter;
import org.jackhuang.hmcl.web.auth.AuthService;
import org.jackhuang.hmcl.web.config.ServerConfig;
import org.jackhuang.hmcl.web.config.ServerConfigLoader;
import org.jackhuang.hmcl.web.config.ServerConfigWriter;
import org.jackhuang.hmcl.web.developer.DeveloperAuth;
import org.jackhuang.hmcl.web.developer.DeveloperElevation;
import org.jackhuang.hmcl.web.http.Json;
import org.jackhuang.hmcl.web.http.RequestSecurity;
import org.jackhuang.hmcl.web.transcode.media.MediaBrowser;
import org.jackhuang.hmcl.web.transcode.preset.PresetSchema;
import org.jackhuang.hmcl.web.transcode.preset.PresetStore;
import org.jackhuang.hmcl.web.transcode.preset.PresetTransfer;
import org.jackhuang.hmcl.web.transcode.preset.TranscodePreset;
import org.jackhuang.hmcl.web.transcode.runtime.FfmpegCapabilities;
import org.jackhuang.hmcl.web.transcode.runtime.FfmpegToolchainManager;
import org.jackhuang.hmcl.web.transcode.runtime.FfmpegInstaller;
import org.jackhuang.hmcl.web.transcode.runtime.FfmpegFullBuildCatalog;
import org.jackhuang.hmcl.web.transcode.runtime.FfmpegCatalog;
import org.jackhuang.hmcl.web.transcode.runtime.FfmpegLocator;
import org.jackhuang.hmcl.web.transcode.runtime.FfmpegProbe;
import org.jackhuang.hmcl.web.transcode.runtime.FfmpegRuntimeManager;
import org.jackhuang.hmcl.web.transcode.runtime.FfmpegSelector;
import org.jackhuang.hmcl.web.transcode.runtime.HardwareProbe;
import org.jackhuang.hmcl.web.transcode.command.CommandBuilder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/// The transcode feature's own REST surface. At this stage it only owns the
/// enable flow; queue, presets, media and tools are added by their own commits.
///
/// The gate in front of this servlet is [TranscodeFeatureFilter]; while the
/// feature is disabled only the routes below are reachable, and everything else
/// is answered 404 by the filter.
@NotNullByDefault
public final class TranscodeApiServlet extends HttpServlet {

    private final TranscodeModule module;
    private final ServerConfig config;
    private final TranscodeFeatureState feature;
    private final AuthService auth;
    private final DeveloperAuth developerAuth;
    private final DeveloperElevation elevation;
    private final BooleanSupplier tlsActive;

    public TranscodeApiServlet(TranscodeModule module,
                               AuthService auth, DeveloperAuth developerAuth,
                               DeveloperElevation elevation, BooleanSupplier tlsActive) {
        this.module = module;
        this.config = module.config();
        this.feature = module.feature();
        this.auth = auth;
        this.developerAuth = developerAuth;
        this.elevation = elevation;
        this.tlsActive = tlsActive;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = path(request);
        if ("/feature".equals(path)) {
            featureJson(response);
            return;
        }
        if ("/terms".equals(path)) {
            termsJson(response);
            return;
        }
        if ("/capabilities".equals(path)) {
            capabilities(response);
            return;
        }
        if ("/media/list".equals(path)) {
            mediaList(request, response);
            return;
        }
        if ("/media/probe".equals(path)) {
            mediaProbe(request, response);
            return;
        }
        if ("/media/scan".equals(path)) {
            mediaScan(request, response);
            return;
        }
        if ("/presets".equals(path)) {
            presets(response);
            return;
        }
        if ("/presets/schema".equals(path)) {
            Json.write(response, PresetSchema.json(module.runtime().info().encoders()));
            return;
        }
        if (path.startsWith("/presets/")) {
            preset(request, response, path.substring("/presets/".length()));
            return;
        }
        if ("/queue".equals(path)) {
            Json.write(response, module.queue().snapshot());
            return;
        }
        if (path.startsWith("/queue/jobs/")) {
            queueJobGet(request, response, path.substring("/queue/jobs/".length()));
            return;
        }
        if (path.startsWith("/tools/quality/")) {
            qualityGet(request, response, path.substring("/tools/quality/".length()));
            return;
        }
        if ("/runtime".equals(path)) {
            runtimeJson(response, request.getParameter("major"));
            return;
        }
        if ("/runtime/catalog".equals(path)) {
            catalogJson(response);
            return;
        }
        if ("/runtime/source".equals(path)) {
            sourceJson(response);
            return;
        }
        if ("/runtime/full-builds".equals(path)) {
            fullBuildsJson(response);
            return;
        }
        if ("/runtime/installs".equals(path)) {
            installsJson(response);
            return;
        }
        if (path.startsWith("/runtime/jobs/")) {
            runtimeGet(response, path.substring("/runtime/jobs/".length()));
            return;
        }
        if (path.startsWith("/runtime/install/")) {
            runtimeGet(response, path.substring("/runtime/install/".length()));
            return;
        }
        Json.error(response, HttpServletResponse.SC_NOT_FOUND, "not found");
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = path(request);
        switch (path) {
            case "/feature/enable" -> enable(request, response);
            case "/feature/decline" -> decline(request, response);
            case "/feature/disable" -> disable(request, response);
            case "/presets" -> savePreset(request, response);
            case "/preview" -> preview(request, response);
            case "/plan" -> plan(request, response);
            case "/presets/import" -> importPreset(request, response);
            case "/queue/start" -> {
                module.queue().startQueue();
                Json.write(response, module.queue().snapshot());
            }
            case "/queue/pause" -> {
                module.queue().pauseQueue();
                Json.write(response, module.queue().snapshot());
            }
            case "/queue/stop" -> {
                module.queue().stopAll();
                Json.write(response, module.queue().snapshot());
            }
            case "/queue/clear" -> {
                int removed = module.queue().clearTerminal();
                audit("transcode.queue.clear", Integer.toString(removed), "success", request);
                Json.write(response, module.queue().snapshot());
            }
            case "/queue/action" -> queueBatchAction(request, response);
            case "/queue/reorder" -> queueReorder(request, response);
            case "/queue/sync-preset" -> queueSyncPreset(request, response);
            case "/queue/jobs" -> submitJob(request, response);
            case "/tools/merge" -> mergeTool(request, response);
            case "/tools/mux" -> muxTool(request, response);
            case "/tools/extract" -> extractTool(request, response);
            case "/tools/quality" -> qualityTool(request, response);
            case "/runtime/install" -> runtimeInstall(request, response);
            case "/runtime/select" -> runtimeSelect(request, response);
            case "/runtime/rollback" -> runtimeRollback(request, response);
            case "/capabilities/probe" -> capabilityProbe(request, response);
            default -> {
                if (path.startsWith("/queue/jobs/")) {
                    queueJobAction(request, response, path.substring("/queue/jobs/".length()));
                } else if (path.startsWith("/runtime/jobs/") && path.endsWith("/cancel")) {
                    String rest = path.substring("/runtime/jobs/".length(),
                            path.length() - "/cancel".length());
                    runtimeCancel(response, rest);
                } else if (path.startsWith("/runtime/install/") && path.endsWith("/cancel")) {
                    String rest = path.substring("/runtime/install/".length(),
                            path.length() - "/cancel".length());
                    runtimeCancel(response, rest);
                } else if (path.startsWith("/tools/quality/") && path.endsWith("/cancel")) {
                    String rest = path.substring("/tools/quality/".length(),
                            path.length() - "/cancel".length());
                    qualityCancel(response, rest);
                } else {
                    Json.error(response, HttpServletResponse.SC_NOT_FOUND, "not found");
                }
            }
        }
    }

    @Override
    protected void doPut(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = path(request);
        if ("/runtime/default".equals(path)) {
            runtimeDefault(request, response);
            return;
        }
        if (!"/settings".equals(path)) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "not found");
            return;
        }
        JsonObject body = readJson(request);
        if (body == null) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "request body must be a JSON object");
            return;
        }
        // A download mirror decides where every later install fetches its
        // bytes from. The digest still protects integrity, but the choice is
        // an execution-shaped knob and belongs behind the same door as the
        // install it feeds.
        boolean changesMirror = body.has("binaryMirror") || body.has("sourceMirror")
                || body.has("toolchainMirror");
        if (changesMirror && !requireHumanElevation(request, response)) {
            return;
        }
        String policy = stringField(body, "failurePolicy");
        if (policy == null || !List.of("ask", "timeout-auto", "full-auto").contains(policy)) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST,
                    "failurePolicy must be ask, timeout-auto or full-auto");
            return;
        }
        int timeout = intField(body, "failureTimeoutMinutes");
        if (body.has("failureTimeoutMinutes") && (timeout < 1 || timeout > 1440)) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST,
                    "failureTimeoutMinutes must be between 1 and 1440");
            return;
        }
        String binaryMirror = config.transcode.binaryMirror;
        String sourceMirror = config.transcode.sourceMirror;
        String toolchainMirror = config.transcode.toolchainMirror;
        try {
            if (body.has("binaryMirror")) {
                binaryMirror = ServerConfigLoader.mirrorPrefix(stringField(body, "binaryMirror"),
                        "binaryMirror");
            }
            if (body.has("sourceMirror")) {
                sourceMirror = ServerConfigLoader.mirrorPrefix(stringField(body, "sourceMirror"),
                        "sourceMirror");
            }
            if (body.has("toolchainMirror")) {
                toolchainMirror = ServerConfigLoader.mirrorPrefix(
                        stringField(body, "toolchainMirror"), "toolchainMirror");
            }
        } catch (ServerConfigLoader.ConfigException e) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
            return;
        }
        try {
            ServerConfigWriter.setTranscodeMirrors(config.dataDir, binaryMirror, sourceMirror,
                    toolchainMirror);
            config.transcode.binaryMirror = binaryMirror;
            config.transcode.sourceMirror = sourceMirror;
            config.transcode.toolchainMirror = toolchainMirror;
            ServerConfigWriter.setTranscodeFailurePolicy(config.dataDir, policy,
                    body.has("failureTimeoutMinutes") ? timeout : config.transcode.failureTimeoutMinutes);
            config.transcode.failurePolicy = policy;
            if (body.has("failureTimeoutMinutes")) {
                config.transcode.failureTimeoutMinutes = timeout;
            }
            // The mirror values are part of what changed, so they belong in
            // the record; "policy/timeout" alone could not answer "was the
            // download source rewritten?" after the fact.
            String audited = policy + "/" + config.transcode.failureTimeoutMinutes
                    + (changesMirror ? " mirrors=" + binaryMirror + "," + sourceMirror
                            + "," + toolchainMirror : "");
            audit("transcode.settings.update", audited, "success", request);
            featureJson(response);
        } catch (IOException e) {
            Json.error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @Override
    protected void doDelete(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = path(request);
        if (path.startsWith("/queue/jobs/")) {
            String id = path.substring("/queue/jobs/".length());
            if (id.isBlank() || id.contains("/")) {
                Json.error(response, HttpServletResponse.SC_NOT_FOUND, "not found");
                return;
            }
            if (!module.queue().remove(id)) {
                Json.error(response, HttpServletResponse.SC_CONFLICT,
                        "the job is not in a state that allows this action");
                return;
            }
            audit("transcode.queue.remove", id, "success", request);
            Json.write(response, module.queue().snapshot());
            return;
        }
        if (path.startsWith("/runtime/")) {
            if (!requireHumanElevation(request, response)) {
                return;
            }
            String id = path.substring("/runtime/".length());
            if (id.isBlank() || id.contains("/")) {
                Json.error(response, HttpServletResponse.SC_NOT_FOUND, "not found");
                return;
            }
            try {
                module.runtimeManager().delete(id);
                audit("transcode.runtime.delete", id, "success", request);
                Json.write(response, Map.of("ok", true));
            } catch (IOException e) {
                Json.error(response, HttpServletResponse.SC_CONFLICT, e.getMessage());
            }
            return;
        }
        if (!path.startsWith("/presets/")) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "not found");
            return;
        }
        String id = path.substring("/presets/".length());
        try {
            if (!module.presets().delete(id)) {
                Json.error(response, HttpServletResponse.SC_NOT_FOUND, "preset not found or read-only");
                return;
            }
            JsonObject body = new JsonObject();
            body.addProperty("ok", true);
            Json.write(response, body);
        } catch (IllegalArgumentException e) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (IOException e) {
            Json.error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    private static String path(HttpServletRequest request) {
        String path = request.getPathInfo();
        if (path == null || path.isBlank()) {
            return "/";
        }
        return path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    private void featureJson(HttpServletResponse response) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("state", feature.state().name());
        body.addProperty("enabled", feature.enabled());
        body.addProperty("termsVersion", TranscodeTerms.VERSION);
        body.addProperty("termsTextHash", TranscodeTerms.hash());
        Path media = TranscodePaths.mediaRoot(config);
        body.addProperty("mediaRoot", media.toString());
        body.addProperty("mediaRootExists", Files.isDirectory(media));
        body.addProperty("mediaRootWritable", Files.isDirectory(media) && Files.isWritable(media));
        body.addProperty("maxConcurrent", config.transcode.maxConcurrent);
        body.addProperty("maxQueue", config.transcode.maxQueue);
        body.addProperty("failurePolicy", config.transcode.failurePolicy);
        body.addProperty("failureTimeoutMinutes", config.transcode.failureTimeoutMinutes);
        body.addProperty("binaryMirror", config.transcode.binaryMirror);
        body.addProperty("sourceMirror", config.transcode.sourceMirror);
        body.addProperty("toolchainMirror", config.transcode.toolchainMirror);
        body.addProperty("autoResume", config.transcode.autoResume);
        feature.consent().ifPresent(record -> {
            JsonObject consent = new JsonObject();
            consent.addProperty("termsVersion", record.termsVersion());
            consent.addProperty("termsTextHash", record.termsTextHash());
            consent.addProperty("acceptedAt", record.acceptedAt());
            consent.addProperty("acceptedBy", record.acceptedBy());
            consent.addProperty("acceptedFrom", record.acceptedFrom());
            consent.addProperty("ackMethod", record.ackMethod());
            consent.addProperty("uiVersion", record.uiVersion());
            consent.addProperty("clientUa", record.clientUa());
            consent.addProperty("buildCommit", record.buildCommit());
            consent.addProperty("sawDeclineOption", record.sawDeclineOption());
            body.add("consent", consent);
        });
        Json.write(response, body);
    }

    private void termsJson(HttpServletResponse response) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("version", TranscodeTerms.VERSION);
        body.addProperty("text", TranscodeTerms.text());
        body.addProperty("hash", TranscodeTerms.hash());
        Json.write(response, body);
    }

    private void capabilities(HttpServletResponse response) throws IOException {
        FfmpegProbe.FfmpegInfo info = module.runtime().info();
        JsonObject body = new JsonObject();
        body.addProperty("available", info.available());
        body.addProperty("version", info.version());
        body.addProperty("ffmpeg", info.ffmpeg() == null ? null : info.ffmpeg().toString());
        body.addProperty("ffprobe", info.ffprobe() == null ? null : info.ffprobe().toString());
        body.addProperty("error", info.error());
        body.addProperty("probedAt", info.probedAt());
        JsonArray encoders = new JsonArray();
        info.encoders().forEach(encoders::add);
        body.add("encoders", encoders);
        JsonArray hwaccels = new JsonArray();
        info.hwaccels().forEach(hwaccels::add);
        body.add("hwaccels", hwaccels);
        JsonArray filters = new JsonArray();
        info.filters().forEach(filters::add);
        body.add("filters", filters);
        Path media = module.mediaRoot();
        body.addProperty("mediaRoot", media.toString());
        body.addProperty("mediaRootExists", Files.isDirectory(media));
        body.addProperty("mediaRootWritable", Files.isDirectory(media) && Files.isWritable(media));
        body.addProperty("maxConcurrent", config.transcode.maxConcurrent);
        body.addProperty("maxQueue", config.transcode.maxQueue);
        Json.write(response, body);
    }

    private void mediaList(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String relative = request.getParameter("path");
        int page = intParameter(request, "page", 0);
        int pageSize = intParameter(request, "pageSize", 200);
        try {
            MediaBrowser.Listing listing = module.media().list(module.mediaRoot(), relative, page, pageSize);
            Json.write(response, listing);
        } catch (IOException e) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        }
    }

    private void mediaProbe(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String relative = request.getParameter("path");
        if (relative == null || relative.isBlank()) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "path is required");
            return;
        }
        try {
            Json.write(response, module.media().probe(module.mediaRoot(), relative));
        } catch (IOException e) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Json.error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "probe interrupted");
        }
    }

    private void mediaScan(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String relative = request.getParameter("path");
        boolean recursive = !"0".equals(request.getParameter("recursive"));
        int limit = intParameter(request, "limit", 2000);
        try {
            Json.write(response, module.media().scan(module.mediaRoot(), relative, recursive, limit));
        } catch (IOException e) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        }
    }

    private static int intParameter(HttpServletRequest request, String name, int fallback) {
        String value = request.getParameter(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void presets(HttpServletResponse response) throws IOException {
        JsonArray array = new JsonArray();
        for (PresetStore.PresetEntry entry : module.presets().list()) {
            JsonObject item = new JsonObject();
            item.addProperty("id", entry.id());
            item.addProperty("builtin", entry.builtin());
            item.addProperty("name", entry.preset().name);
            item.addProperty("description", entry.preset().description);
            array.add(item);
        }
        JsonObject body = new JsonObject();
        body.add("presets", array);
        Json.write(response, body);
    }

    private void preset(HttpServletRequest request, HttpServletResponse response, String id) throws IOException {
        if (id.endsWith("/export")) {
            exportPreset(response, id.substring(0, id.length() - "/export".length()),
                    request.getParameter("format"));
            return;
        }
        var entry = module.presets().get(id);
        if (entry.isEmpty()) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "preset not found");
            return;
        }
        Json.write(response, entry.get());
    }

    private void exportPreset(HttpServletResponse response, String id, String format) throws IOException {
        var entry = module.presets().get(id);
        if (entry.isEmpty()) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "preset not found");
            return;
        }
        if ("3fui".equalsIgnoreCase(format)) {
            Json.write(response, PresetTransfer.to3fui(entry.get().preset()));
        } else {
            Json.write(response, entry.get().preset());
        }
    }

    private void importPreset(HttpServletRequest request, HttpServletResponse response) throws IOException {
        JsonObject body = readJson(request);
        if (body == null) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "request body must be a JSON object");
            return;
        }
        JsonObject data = body.has("data") && body.get("data").isJsonObject()
                ? body.getAsJsonObject("data") : body;
        String id = stringField(body, "id");
        if (id == null || id.isBlank()) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "id is required");
            return;
        }
        try {
            TranscodePreset preset = PresetTransfer.looksLike3fui(data)
                    ? PresetTransfer.from3fui(data)
                    : Json.GSON.fromJson(data, TranscodePreset.class);
            if (preset == null) {
                throw new IllegalArgumentException("preset is not an object");
            }
            String name = stringField(body, "name");
            if (name != null && !name.isBlank()) {
                preset.name = name;
            }
            PresetStore.PresetEntry saved = module.presets().save(id.trim(), preset);
            audit("transcode.preset.import", id.trim(), "success", request);
            Json.write(response, saved);
        } catch (IllegalArgumentException e) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (IOException e) {
            Json.error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    private void savePreset(HttpServletRequest request, HttpServletResponse response) throws IOException {
        JsonObject body = readJson(request);
        if (body == null) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "request body must be a JSON object");
            return;
        }
        String id = stringField(body, "id");
        if (id == null || id.isBlank()) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "id is required");
            return;
        }
        if (!body.has("preset") || !body.get("preset").isJsonObject()) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "preset is required");
            return;
        }
        try {
            TranscodePreset preset = Json.GSON.fromJson(body.getAsJsonObject("preset"), TranscodePreset.class);
            PresetStore.PresetEntry saved = module.presets().save(id.trim(), preset);
            audit("transcode.preset.save", id.trim(), "success", request);
            Json.write(response, saved);
        } catch (IllegalArgumentException e) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (IOException e) {
            Json.error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    private void preview(HttpServletRequest request, HttpServletResponse response) throws IOException {
        JsonObject body = readJson(request);
        if (body == null) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "request body must be a JSON object");
            return;
        }
        String inputPath = stringField(body, "input");
        boolean placeholder = inputPath == null || inputPath.isBlank();
        try {
            TranscodePreset preset = resolvePreset(body);
            preset.validate();
            Path input;
            Path output;
            Path chapters = null;
            Path subtitle = null;
            if (placeholder) {
                // The parameter panel asks for a command before a file is chosen: the
                // argv is what matters, so build it with the same placeholders 3FUI shows.
                input = Path.of("<输入文件>");
                output = Path.of("<输出文件>");
            } else {
                input = module.media().resolve(module.mediaRoot(), inputPath);
                String requestedDir = stringField(body, "outputDir");
                String presetDir = preset.outputDir;
                Path outputDir;
                if (requestedDir != null && !requestedDir.isBlank()) {
                    outputDir = module.media().resolveForWrite(module.mediaRoot(), requestedDir);
                } else if (presetDir != null && !presetDir.isBlank()) {
                    outputDir = module.media().resolveForWrite(module.mediaRoot(), presetDir);
                } else {
                    outputDir = input.getParent();
                }
                output = CommandBuilder.outputPath(preset, input, outputDir);
                chapters = preset.chaptersFile == null || preset.chaptersFile.isBlank()
                        ? null : module.media().resolve(module.mediaRoot(), preset.chaptersFile);
                subtitle = preset.subtitleFile == null || preset.subtitleFile.isBlank()
                        ? null : module.media().resolve(module.mediaRoot(), preset.subtitleFile);
            }
            List<List<String>> passes = CommandBuilder.buildPasses(preset, input, output, chapters, subtitle);
            List<String> args = passes.get(passes.size() - 1);
            JsonArray array = new JsonArray();
            args.forEach(array::add);
            JsonObject result = new JsonObject();
            result.addProperty("input", placeholder ? "<输入文件>" : input.toString());
            result.addProperty("output", placeholder ? "<输出文件>" : output.toString());
            result.add("args", array);
            result.addProperty("command", passes.size() == 1
                    ? CommandBuilder.display(args)
                    : "# 第一遍（分析）\n" + CommandBuilder.display(passes.get(0))
                            + "\n\n# 第二遍（出片）\n" + CommandBuilder.display(passes.get(1)));
            Json.write(response, result);
        } catch (IllegalArgumentException e) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (IOException e) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        }
    }

    /// Plans output paths for a batch without submitting anything. The preparation list
    /// uses it to show the exact file the server will write, including collision suffixes.
    private void plan(HttpServletRequest request, HttpServletResponse response) throws IOException {
        JsonObject body = readJson(request);
        if (body == null) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "request body must be a JSON object");
            return;
        }
        try {
            TranscodePreset preset = resolvePreset(body);
            preset.validate();
            Path mediaRoot = module.mediaRoot();
            String requestedDir = stringField(body, "outputDir");
            Path outputDir = requestedDir == null || requestedDir.isBlank()
                    ? null : module.media().resolveForWrite(mediaRoot, requestedDir);
            Set<Path> reserved = new LinkedHashSet<>();
            JsonArray items = new JsonArray();
            JsonArray inputs = body.has("inputs") && body.get("inputs").isJsonArray()
                    ? body.getAsJsonArray("inputs") : new JsonArray();
            for (var element : inputs) {
                if (!element.isJsonPrimitive()) {
                    continue;
                }
                String relative = element.getAsString();
                JsonObject item = new JsonObject();
                item.addProperty("input", relative);
                try {
                    Path input = module.media().resolve(mediaRoot, relative);
                    Path directory = outputDir == null ? input.getParent() : outputDir;
                    Path output = CommandBuilder.outputPath(preset, input, directory, reserved);
                    reserved.add(output);
                    item.addProperty("inputAbsolute", input.toString());
                    item.addProperty("output", mediaRoot.relativize(output).toString().replace('\\', '/'));
                    item.addProperty("outputAbsolute", output.toString());
                    item.addProperty("exists", Files.exists(output));
                } catch (IOException | IllegalArgumentException e) {
                    item.addProperty("error", e.getMessage());
                }
                items.add(item);
            }
            JsonObject result = new JsonObject();
            result.addProperty("mediaRoot", mediaRoot.toString());
            result.addProperty("outputDir", outputDir == null ? "" : outputDir.toString());
            result.add("items", items);
            Json.write(response, result);
        } catch (IllegalArgumentException e) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (IOException e) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        }
    }

    private TranscodePreset resolvePreset(JsonObject body) {
        String presetId = stringField(body, "presetId");
        if (presetId != null && !presetId.isBlank()) {
            return module.presets().get(presetId)
                    .orElseThrow(() -> new IllegalArgumentException("preset not found: " + presetId))
                    .preset();
        }
        if (body.has("preset") && body.get("preset").isJsonObject()) {
            TranscodePreset preset = Json.GSON.fromJson(body.getAsJsonObject("preset"), TranscodePreset.class);
            if (preset == null) {
                throw new IllegalArgumentException("preset is not an object");
            }
            return preset;
        }
        throw new IllegalArgumentException("presetId or preset is required");
    }

    private void mergeTool(HttpServletRequest request, HttpServletResponse response) throws IOException {
        JsonObject body = readJson(request);
        if (body == null) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "request body must be a JSON object");
            return;
        }
        try {
            JsonObject job = module.tools().merge(
                    stringArray(body, "inputs"), stringField(body, "output"));
            audit("transcode.tool.merge", job.get("id").getAsString(), "success", request);
            Json.write(response, job);
        } catch (IOException | IllegalArgumentException e) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        }
    }

    private void muxTool(HttpServletRequest request, HttpServletResponse response) throws IOException {
        JsonObject body = readJson(request);
        if (body == null) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "request body must be a JSON object");
            return;
        }
        try {
            JsonObject job = module.tools().mux(
                    stringArray(body, "inputs"), stringArray(body, "maps"), stringField(body, "output"));
            audit("transcode.tool.mux", job.get("id").getAsString(), "success", request);
            Json.write(response, job);
        } catch (IOException | IllegalArgumentException e) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        }
    }

    private void extractTool(HttpServletRequest request, HttpServletResponse response) throws IOException {
        JsonObject body = readJson(request);
        if (body == null) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "request body must be a JSON object");
            return;
        }
        try {
            JsonObject job = module.tools().extract(
                    stringField(body, "input"),
                    stringField(body, "type"),
                    intField(body, "index"),
                    stringField(body, "output"));
            audit("transcode.tool.extract", job.get("id").getAsString(), "success", request);
            Json.write(response, job);
        } catch (IOException | IllegalArgumentException e) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        }
    }

    private static java.util.List<String> stringArray(JsonObject body, String name) {
        if (!body.has(name) || !body.get(name).isJsonArray()) {
            return java.util.List.of();
        }
        java.util.List<String> result = new java.util.ArrayList<>();
        for (var element : body.getAsJsonArray(name)) {
            if (element.isJsonPrimitive()) {
                result.add(element.getAsString());
            }
        }
        return result;
    }

    private void capabilityProbe(HttpServletRequest request, HttpServletResponse response) throws IOException {
        JsonObject body = readJson(request);
        String encoder = body == null ? null : stringField(body, "encoder");
        if (encoder == null || encoder.isBlank()) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "encoder is required");
            return;
        }
        Json.write(response, HardwareProbe.probe(module, encoder));
    }

    private void runtimeInstall(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!requireHumanElevation(request, response)) {
            return;
        }
        JsonObject body = readJson(request);
        if (body == null) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "request body must be a JSON object");
            return;
        }
        try {
            FfmpegToolchainManager.Preference compiler =
                    FfmpegToolchainManager.Preference.parse(stringField(body, "compiler"));
            FfmpegInstaller.InstallJob job;
            String mode = stringField(body, "mode");
            if (mode != null && !mode.isBlank()) {
                job = switch (mode) {
                    case "auto" -> module.installer().startAuto(features(body), stringField(body, "major"));
                    case "artifact" -> module.installer().startArtifact(requiredField(body, "id"));
                    case "custom" -> module.installer().startCustom(
                            requiredField(body, "url"),
                            requiredField(body, "sha256"),
                            stringField(body, "version"));
                    case "package" -> module.installer().start("package", null, null, null);
                    case "source" -> {
                        String id = stringField(body, "id");
                        yield id != null && !id.isBlank()
                                ? module.installer().startSourceArtifact(id, features(body), compiler)
                                : module.installer().startSource(features(body),
                                        stringField(body, "major"), compiler);
                    }
                    case "full-source" -> module.installer().startFullSource(
                            requiredField(body, "id"), compiler);
                    default -> throw new IOException("unknown ffmpeg install mode: " + mode);
                };
            } else {
                // Backwards compatible with the old settings page: it posts
                // `kind`, not `mode`.
                job = module.installer().start(
                        stringField(body, "kind"),
                        stringField(body, "url"),
                        stringField(body, "sha256"),
                        stringField(body, "version"));
            }
            audit("transcode.runtime.install", job.kind, "success", request);
            Json.write(response, job);
        } catch (IOException | IllegalArgumentException e) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        }
    }

    private void runtimeSelect(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!requireHumanElevation(request, response)) {
            return;
        }
        JsonObject body = readJson(request);
        String version = body == null ? null : stringField(body, "version");
        if (version == null || version.isBlank()) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "version is required");
            return;
        }
        try {
            module.runtimeManager().activate(version);
            module.runtime().refresh();
            audit("transcode.runtime.select", version, "success", request);
            runtimeJson(response);
        } catch (IOException e) {
            Json.error(response, HttpServletResponse.SC_CONFLICT, e.getMessage());
        }
    }

    /// Chooses the runtime the transcode queue should use (`auto`, `system`, or
    /// an installed id). Changing this is an execution-shaped decision, so it
    /// takes the same developer elevation as selecting an installed runtime.
    private void runtimeDefault(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        if (!requireHumanElevation(request, response)) {
            return;
        }
        JsonObject body = readJson(request);
        String value = body == null ? null : stringField(body, "value");
        if (value == null || value.isBlank()) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "value is required");
            return;
        }
        String normalized;
        try {
            normalized = ServerConfigLoader.defaultRuntime(value, "default_runtime");
        } catch (ServerConfigLoader.ConfigException e) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
            return;
        }
        if (!"auto".equals(normalized) && !"system".equals(normalized)) {
            boolean installed = module.runtimeManager().installed().stream()
                    .anyMatch(item -> item.id().equals(normalized));
            if (!installed) {
                Json.error(response, HttpServletResponse.SC_BAD_REQUEST,
                        "runtime_not_installed: " + normalized);
                return;
            }
        }
        try {
            ServerConfigWriter.setTranscodeDefaultRuntime(config.dataDir, normalized);
            config.transcode.defaultRuntime = normalized;
            module.runtime().refresh();
            audit("transcode.runtime.default", normalized, "success", request);
            runtimeJson(response);
        } catch (IOException e) {
            Json.error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    private void runtimeRollback(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!requireHumanElevation(request, response)) {
            return;
        }
        try {
            module.runtimeManager().rollback();
            module.runtime().refresh();
            audit("transcode.runtime.rollback", "", "success", request);
            runtimeJson(response);
        } catch (IOException e) {
            Json.error(response, HttpServletResponse.SC_CONFLICT, e.getMessage());
        }
    }

    private void runtimeJson(HttpServletResponse response) throws IOException {
        runtimeJson(response, "auto");
    }

    private void runtimeJson(HttpServletResponse response, @Nullable String preferredMajor) throws IOException {
        FfmpegRuntimeManager manager = module.runtimeManager();
        FfmpegCapabilities.Host host = FfmpegCapabilities.detect();
        JsonObject body = new JsonObject();
        JsonObject hostJson = new JsonObject();
        hostJson.addProperty("os", host.os());
        hostJson.addProperty("arch", host.arch());
        hostJson.addProperty("libc", host.libc());
        hostJson.addProperty("libcVersion", host.libcVersion());
        body.add("host", hostJson);

        FfmpegRuntimeManager.Installed current = manager.current();
        body.add("current", current == null ? null : installedJson(current));
        JsonArray installed = new JsonArray();
        for (FfmpegRuntimeManager.Installed entry : manager.installed()) {
            installed.add(installedJson(entry));
        }
        body.add("installed", installed);

        body.addProperty("default", config.transcode.defaultRuntime == null
                ? "auto" : config.transcode.defaultRuntime);
        FfmpegLocator.Resolution resolution = FfmpegLocator.resolve(config);
        JsonObject resolved = new JsonObject();
        resolved.addProperty("kind", resolution.kind());
        if (resolution.id() != null) {
            resolved.addProperty("id", resolution.id());
        }
        resolved.addProperty("ffmpeg", resolution.ffmpeg() == null
                ? "" : resolution.ffmpeg().toString());
        resolved.addProperty("ffprobe", resolution.ffprobe() == null
                ? "" : resolution.ffprobe().toString());
        body.add("resolved", resolved);

        Path systemFfmpeg = FfmpegLocator.systemExecutable("ffmpeg");
        Path systemFfprobe = FfmpegLocator.systemExecutable("ffprobe");
        JsonObject system = new JsonObject();
        system.addProperty("ffmpeg", systemFfmpeg == null ? "" : systemFfmpeg.toString());
        system.addProperty("ffprobe", systemFfprobe == null ? "" : systemFfprobe.toString());
        if (systemFfmpeg != null && systemFfprobe != null) {
            FfmpegProbe.FfmpegInfo info = FfmpegProbe.probe(
                    new FfmpegLocator.FfmpegPaths(systemFfmpeg, systemFfprobe));
            system.addProperty("available", info.available());
            system.addProperty("version", info.version());
            system.addProperty("error", info.error() == null ? "" : info.error());
        } else {
            system.addProperty("available", false);
            system.addProperty("version", "");
            system.addProperty("error", "ffmpeg/ffprobe not found on PATH");
        }
        body.add("system", system);

        JsonArray compilers = new JsonArray();
        for (FfmpegToolchainManager.CompilerChoice choice : module.toolchains().compilers()) {
            JsonObject item = new JsonObject();
            item.addProperty("id", choice.id());
            item.addProperty("label", choice.label());
            item.addProperty("available", choice.available());
            item.addProperty("detail", choice.detail());
            compilers.add(item);
        }
        body.add("compilers", compilers);

        JsonArray catalog = new JsonArray();
        for (FfmpegCatalog.Entry entry : module.catalog().entries()) {
            catalog.add(entryJson(entry));
        }
        body.add("catalog", catalog);

        JsonArray majors = new JsonArray();
        for (String major : module.catalog().majors()) {
            majors.add(major);
        }
        body.add("majors", majors);

        try {
            FfmpegSelector.Recommendation recommendation = FfmpegSelector.select(
                    module.catalog(), host, Set.of(), preferredMajor);
            JsonObject recommended = new JsonObject();
            recommended.add("entry", entryJson(recommendation.entry()));
            recommended.addProperty("reason", recommendation.reason());
            body.add("recommended", recommended);
        } catch (IOException e) {
            body.add("recommended", null);
            body.addProperty("recommendationError", e.getMessage());
        }
        Json.write(response, body);
    }

    /// Read-only build precheck for the settings page: the source catalogue
    /// entries plus what this host is missing. Starting the build still goes
    /// through POST /runtime/install with `mode=source` and developer elevation.
    private void sourceJson(HttpServletResponse response) throws IOException {
        FfmpegCapabilities.Host host = FfmpegCapabilities.detect();
        JsonObject body = new JsonObject();
        JsonObject hostJson = new JsonObject();
        hostJson.addProperty("os", host.os());
        hostJson.addProperty("arch", host.arch());
        hostJson.addProperty("libc", host.libc());
        hostJson.addProperty("libcVersion", host.libcVersion());
        body.add("host", hostJson);

        JsonArray candidates = new JsonArray();
        for (FfmpegCatalog.Entry entry : module.catalog().entries()) {
            if (entry.isSource()) {
                candidates.add(entryJson(entry));
            }
        }
        body.add("candidates", candidates);

        FfmpegToolchainManager.Toolchain toolchain = module.toolchains().detect();
        body.add("toolchain", toolchainJson(toolchain));
        boolean portableFallback = host.os().equals("windows") && host.arch().equals("x64");
        body.addProperty("buildable", candidates.size() > 0 && (toolchain.usable() || portableFallback));
        body.addProperty("portableFallback", portableFallback);
        Json.write(response, body);
    }

    /// The managed full-codec recipes and whether this host can start one. The
    /// installer still downloads cmake/nasm itself; the precheck only needs the
    /// base compiler, make and disk space.
    private void fullBuildsJson(HttpServletResponse response) throws IOException {
        JsonObject body = new JsonObject();
        JsonArray entries = new JsonArray();
        for (FfmpegFullBuildCatalog.Entry entry : module.fullBuildCatalog().entries()) {
            JsonObject item = new JsonObject();
            item.addProperty("id", entry.id());
            item.addProperty("profile", entry.profile());
            item.addProperty("ffmpegVersion", entry.ffmpegVersion());
            item.addProperty("license", entry.gpl() ? "GPL-2.0-or-later" : "LGPL-2.1-or-later");
            item.addProperty("downloadBytes", entry.downloadBytes());
            item.addProperty("buildMinutes", entry.buildMinutes());
            item.addProperty("diskBytes", entry.diskBytes());
            JsonArray features = new JsonArray();
            entry.features().forEach(features::add);
            item.add("features", features);
            JsonArray dependencies = new JsonArray();
            for (FfmpegFullBuildCatalog.Dependency dependency : entry.dependencies()) {
                JsonObject dep = new JsonObject();
                dep.addProperty("id", dependency.id());
                dep.addProperty("kind", dependency.kind());
                dep.addProperty("version", dependency.version());
                dependencies.add(dep);
            }
            item.add("dependencies", dependencies);
            entries.add(item);
        }
        body.add("entries", entries);
        // The full build compiles its own dependency stack, so the only host
        // requirement is a C/C++ compiler + make + pkg-config (cmake, nasm and
        // every codec library are built by the kit). If the host has none, the
        // portable Zig/w64devkit bundle is a valid answer — availability must
        // not hinge on the system compiler alone.
        FfmpegToolchainManager.Toolchain toolchain = module.toolchains().detect();
        boolean systemUsable = toolchain.usable();
        boolean portableAvailable = module.toolchains().compilers().stream()
                .anyMatch(choice -> "portable".equals(choice.id()) && choice.available());
        boolean available = systemUsable || portableAvailable;
        body.addProperty("available", available);
        body.addProperty("systemUsable", systemUsable);
        body.addProperty("portableAvailable", portableAvailable);
        body.addProperty("reason", available ? ""
                : "需要 cc / c++ / make / pkg-config，或此平台可安装的便携工具链"
                + "（Zig / w64devkit）；cmake、nasm 与编码库由面板自建");
        Json.write(response, body);
    }

    private static JsonObject toolchainJson(FfmpegToolchainManager.Toolchain toolchain) {
        JsonObject object = new JsonObject();
        object.addProperty("os", toolchain.os());
        object.addProperty("arch", toolchain.arch());
        object.addProperty("usable", toolchain.usable());
        object.addProperty("portable", toolchain.portable());
        object.addProperty("binDir", toolchain.binDir() == null ? "" : toolchain.binDir().toString());
        object.addProperty("detail", toolchain.detail());
        object.addProperty("x86asm", toolchain.hasX86Asm());
        JsonArray tools = new JsonArray();
        for (FfmpegToolchainManager.Tool tool : toolchain.tools()) {
            JsonObject item = new JsonObject();
            item.addProperty("name", tool.name());
            item.addProperty("path", tool.path() == null ? "" : tool.path());
            item.addProperty("version", tool.version());
            item.addProperty("present", tool.present());
            tools.add(item);
        }
        object.add("tools", tools);
        JsonArray dependencies = new JsonArray();
        for (FfmpegToolchainManager.Dependency dependency : toolchain.dependencies()) {
            JsonObject item = new JsonObject();
            item.addProperty("feature", dependency.feature());
            item.addProperty("module", dependency.module());
            item.addProperty("package", dependency.packageName());
            item.addProperty("present", dependency.present());
            item.addProperty("version", dependency.version());
            dependencies.add(item);
        }
        object.add("dependencies", dependencies);
        object.add("missing", strings(toolchain.missing()));
        JsonArray features = new JsonArray();
        toolchain.features().forEach(features::add);
        object.add("features", features);
        return object;
    }

    private void catalogJson(HttpServletResponse response) throws IOException {
        JsonArray catalog = new JsonArray();
        for (FfmpegCatalog.Entry entry : module.catalog().entries()) {
            catalog.add(entryJson(entry));
        }
        JsonObject body = new JsonObject();
        body.add("entries", catalog);
        Json.write(response, body);
    }

    private static JsonObject installedJson(FfmpegRuntimeManager.Installed installed) {
        FfmpegRuntimeManager.RuntimeRecord record = installed.runtime();
        JsonObject object = new JsonObject();
        object.addProperty("id", installed.id());
        object.addProperty("version", record.version);
        object.addProperty("catalogId", record.catalogId);
        object.addProperty("major", record.major == null ? "" : record.major);
        object.addProperty("source", record.source);
        object.addProperty("license", record.license);
        object.addProperty("sha256", record.sha256);
        object.addProperty("installedAt", record.installedAt);
        object.addProperty("archive", record.archive);
        object.addProperty("probeVersion", record.probeVersion);
        object.addProperty("notes", record.notes);
        object.addProperty("builtFrom", record.builtFrom == null ? "" : record.builtFrom);
        object.addProperty("buildRecipe", record.buildRecipe == null ? "" : record.buildRecipe);
        object.addProperty("toolchain", record.toolchain == null ? "" : record.toolchain);
        object.addProperty("current", installed.current());
        object.add("featuresDeclared", strings(record.featuresDeclared));
        object.add("featuresProbed", strings(record.featuresProbed));
        return object;
    }

    private static JsonObject entryJson(FfmpegCatalog.Entry entry) {
        JsonObject object = new JsonObject();
        object.addProperty("id", entry.id());
        object.addProperty("os", entry.os());
        object.addProperty("arch", entry.arch());
        object.addProperty("libc", entry.libc());
        object.addProperty("ffmpegVersion", entry.ffmpegVersion());
        object.addProperty("license", entry.license());
        object.addProperty("url", entry.url());
        object.addProperty("sha256", entry.sha256());
        object.addProperty("size", entry.size());
        object.addProperty("archive", entry.archive());
        object.addProperty("notes", entry.notes());
        object.addProperty("kind", entry.kind());
        object.addProperty("vendor", entry.vendor());
        object.addProperty("major", entry.major());
        object.addProperty("variant", entry.variant());
        object.addProperty("minGlibc", entry.minGlibc());
        object.addProperty("signatureUrl", entry.signatureUrl());
        object.addProperty("buildRecipe", entry.buildRecipe());
        object.add("mirrors", strings(entry.mirrors()));
        object.add("features", strings(entry.features()));
        return object;
    }

    private static JsonArray strings(List<String> values) {
        JsonArray array = new JsonArray();
        if (values != null) {
            values.forEach(array::add);
        }
        return array;
    }

    private static Set<String> features(JsonObject body) {
        Set<String> result = new LinkedHashSet<>();
        if (body.has("features") && body.get("features").isJsonArray()) {
            for (var element : body.getAsJsonArray("features")) {
                if (element.isJsonPrimitive()) {
                    result.add(element.getAsString());
                }
            }
        }
        return result;
    }

    private static String requiredField(JsonObject body, String name) {
        String value = stringField(body, name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    /// Running and recently finished installs, so a page switch can reattach to a
/// download instead of showing "nothing is happening" and starting another.
private void installsJson(HttpServletResponse response) throws IOException {
        JsonArray jobs = new JsonArray();
        for (FfmpegInstaller.InstallJob job : module.installer().jobs()) {
            jobs.add(Json.GSON.toJsonTree(job));
        }
        JsonObject body = new JsonObject();
        body.add("jobs", jobs);
        Json.write(response, body);
    }

    private void runtimeGet(HttpServletResponse response, String id) throws IOException {
        var job = module.installer().job(id);
        if (job.isEmpty()) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "install job not found");
            return;
        }
        Json.write(response, job.get());
    }

    private void runtimeCancel(HttpServletResponse response, String id) throws IOException {
        var job = module.installer().job(id);
        if (job.isEmpty()) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "install job not found");
            return;
        }
        module.installer().cancel(id);
        Json.write(response, job.get());
    }

    private void qualityTool(HttpServletRequest request, HttpServletResponse response) throws IOException {
        JsonObject body = readJson(request);
        if (body == null) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "request body must be a JSON object");
            return;
        }
        try {
            var job = module.quality().start(
                    stringField(body, "reference"),
                    stringField(body, "encoded"),
                    stringArray(body, "metrics"),
                    stringField(body, "start"),
                    stringField(body, "duration"));
            audit("transcode.tool.quality", job.id, "success", request);
            Json.write(response, job);
        } catch (IOException | IllegalArgumentException e) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        }
    }

    private void qualityGet(HttpServletRequest request, HttpServletResponse response, String rest)
            throws IOException {
        var job = module.quality().job(rest);
        if (job.isEmpty()) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "quality job not found");
            return;
        }
        Json.write(response, job.get());
    }

    private void qualityCancel(HttpServletResponse response, String id) throws IOException {
        var job = module.quality().job(id);
        if (job.isEmpty()) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "quality job not found");
            return;
        }
        module.quality().cancel(id);
        Json.write(response, job.get());
    }

    private void queueJobGet(HttpServletRequest request, HttpServletResponse response, String rest)
            throws IOException {
        String[] parts = rest.split("/");
        if (parts.length == 1) {
            var job = module.queue().jobJson(parts[0]);
            if (job.isEmpty()) {
                Json.error(response, HttpServletResponse.SC_NOT_FOUND, "job not found");
                return;
            }
            Json.write(response, job.get());
            return;
        }
        if (parts.length == 2 && "log".equals(parts[1])) {
            if (module.queue().jobJson(parts[0]).isEmpty()) {
                Json.error(response, HttpServletResponse.SC_NOT_FOUND, "job not found");
                return;
            }
            int tail = intParameter(request, "tail", 500);
            JsonObject body = new JsonObject();
            body.addProperty("id", parts[0]);
            body.addProperty("log", module.queue().log(parts[0], tail));
            Json.write(response, body);
            return;
        }
        Json.error(response, HttpServletResponse.SC_NOT_FOUND, "not found");
    }

    private void submitJob(HttpServletRequest request, HttpServletResponse response) throws IOException {
        JsonObject body = readJson(request);
        if (body == null) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "request body must be a JSON object");
            return;
        }
        String input = stringField(body, "input");
        if (input == null || input.isBlank()) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "input is required");
            return;
        }
        try {
            TranscodePreset preset = resolvePreset(body);
            String presetId = stringField(body, "presetId");
            String outputDir = stringField(body, "outputDir");
            JsonObject job = module.queue().submit(input, outputDir, presetId, preset);
            audit("transcode.queue.submit", job.get("id").getAsString(), "success", request);
            Json.write(response, job);
        } catch (IllegalArgumentException e) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (IOException e) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        }
    }

    private void queueJobAction(HttpServletRequest request, HttpServletResponse response, String rest)
            throws IOException {
        String[] parts = rest.split("/");
        if (parts.length != 2) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "not found");
            return;
        }
        String id = parts[0];
        try {
            boolean changed;
            if ("decision".equals(parts[1])) {
                JsonObject body = readJson(request);
                String action = body == null ? null : stringField(body, "action");
                if (action == null || action.isBlank()) {
                    Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "action is required");
                    return;
                }
                changed = module.queue().decide(id, action);
            } else if ("reset-preset".equals(parts[1])) {
                JsonObject body = readJson(request);
                if (body == null) {
                    Json.error(response, HttpServletResponse.SC_BAD_REQUEST,
                            "request body must be a JSON object");
                    return;
                }
                TranscodePreset replacement = resolvePreset(body);
                replacement.validate();
                changed = module.queue().resetPreset(id, stringField(body, "presetId"), replacement);
            } else {
                changed = switch (parts[1]) {
                    case "start" -> module.queue().startJob(id);
                    case "cancel" -> module.queue().cancel(id);
                    case "retry" -> module.queue().retry(id);
                    case "pause" -> module.queue().pause(id);
                    case "resume" -> module.queue().resume(id);
                    default -> false;
                };
            }
            if (!changed) {
                Json.error(response, HttpServletResponse.SC_CONFLICT, "the job is not in a state that allows this action");
                return;
            }
            audit("transcode.queue." + parts[1], id, "success", request);
            Json.write(response, module.queue().snapshot());
        } catch (IllegalArgumentException e) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (IOException e) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        }
    }

    /// One request, many jobs. Each id is handled on its own so a single refusal (a job
    /// that is still running) does not sink the batch; the response stays 200 and the
    /// caller reads `results` to explain what was skipped and why.
    private void queueBatchAction(HttpServletRequest request, HttpServletResponse response) throws IOException {
        JsonObject body = readJson(request);
        if (body == null) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST,
                    "request body must be a JSON object");
            return;
        }
        String action = stringField(body, "action");
        java.util.List<String> ids = stringArray(body, "ids");
        if (action == null || action.isBlank() || ids.isEmpty()) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "action and ids are required");
            return;
        }
        JsonArray results = new JsonArray();
        int applied = 0;
        for (String id : ids) {
            JsonObject item = new JsonObject();
            item.addProperty("id", id);
            boolean changed;
            try {
                changed = switch (action) {
                    case "start" -> module.queue().startJob(id);
                    case "cancel", "stop" -> module.queue().cancel(id);
                    case "retry" -> module.queue().retry(id);
                    case "pause" -> module.queue().pause(id);
                    case "resume" -> module.queue().resume(id);
                    case "remove" -> module.queue().remove(id);
                    default -> false;
                };
            } catch (IOException | IllegalArgumentException e) {
                item.addProperty("ok", false);
                item.addProperty("reason", e.getMessage() == null
                        ? e.getClass().getSimpleName() : e.getMessage());
                results.add(item);
                continue;
            }
            item.addProperty("ok", changed);
            if (changed) {
                applied++;
            } else {
                item.addProperty("reason", "the job is not in a state that allows this action");
            }
            results.add(item);
        }
        audit("transcode.queue." + action, applied + "/" + ids.size(), "success", request);
        JsonObject answer = new JsonObject();
        answer.addProperty("ok", applied == ids.size());
        answer.addProperty("applied", applied);
        answer.add("results", results);
        answer.add("queue", module.queue().snapshot());
        Json.write(response, answer);
    }

    /// The whole order in one shot: partial reorders would be ambiguous while other
    /// clients keep submitting, so a list that does not cover every job is refused.
    private void queueReorder(HttpServletRequest request, HttpServletResponse response) throws IOException {
        JsonObject body = readJson(request);
        if (body == null) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST,
                    "request body must be a JSON object");
            return;
        }
        java.util.List<String> ids = stringArray(body, "ids");
        if (ids.isEmpty()) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "ids is required");
            return;
        }
        if (!module.queue().reorder(ids)) {
            Json.error(response, HttpServletResponse.SC_CONFLICT,
                    "the order must list every job once and keep running jobs in place");
            return;
        }
        audit("transcode.queue.reorder", Integer.toString(ids.size()), "success", request);
        Json.write(response, module.queue().snapshot());
    }

    /// Batch form of `reset-preset`: only not-yet-started jobs are re-pointed, and the
    /// counts say how many were touched versus skipped (tool job / already running).
    private void queueSyncPreset(HttpServletRequest request, HttpServletResponse response) throws IOException {
        JsonObject body = readJson(request);
        if (body == null) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST,
                    "request body must be a JSON object");
            return;
        }
        try {
            TranscodePreset preset = resolvePreset(body);
            preset.validate();
            JsonObject result = module.queue().syncPreset(stringField(body, "presetId"), preset,
                    stringArray(body, "ids"));
            audit("transcode.queue.sync-preset", result.get("updated").getAsString(), "success", request);
            result.add("queue", module.queue().snapshot());
            Json.write(response, result);
        } catch (IllegalArgumentException e) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        }
    }

    private void enable(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!requireElevation(request, response)) {
            return;
        }
        JsonObject body = readJson(request);
        if (body == null) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "request body must be a JSON object");
            return;
        }
        boolean termsAcknowledged = booleanField(body, "termsAcknowledged");
        boolean sawDecline = booleanField(body, "sawDeclineOption");
        int termsVersion = intField(body, "termsVersion");
        String termsTextHash = stringField(body, "termsTextHash");
        String uiVersion = stringField(body, "uiVersion");

        if (!termsAcknowledged) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST,
                    "the transcode notice must be acknowledged");
            return;
        }
        if (!sawDecline) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST,
                    "the decline option must have been visible (sawDeclineOption)");
            return;
        }
        if (termsVersion != TranscodeTerms.VERSION) {
            Json.error(response, HttpServletResponse.SC_CONFLICT,
                    "the notice changed to v" + TranscodeTerms.VERSION + "; show it again");
            return;
        }
        if (termsTextHash == null || !TranscodeTerms.hash().equals(termsTextHash)) {
            Json.error(response, HttpServletResponse.SC_CONFLICT,
                    "the notice text does not match what this build offers");
            return;
        }
        if (uiVersion == null || uiVersion.isBlank()) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "uiVersion is required");
            return;
        }

        TranscodeConsent record = new TranscodeConsent(
                termsVersion,
                termsTextHash,
                Instant.now().toString(),
                actorOf(request) == null ? "unknown" : actorOf(request),
                request.getRemoteAddr(),
                "checkbox",
                uiVersion,
                request.getHeader("User-Agent"),
                Metadata.VERSION,
                true);
        feature.enable(record);
        module.queue().start();
        audit("transcode.feature.enable", "terms v" + termsVersion + " (" + termsTextHash + ")", "success", request);
        featureJson(response);
    }

    private void decline(HttpServletRequest request, HttpServletResponse response) throws IOException {
        audit("transcode.feature.decline", "transcode feature", "success", request);
        featureJson(response);
    }

    private void disable(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!requireElevation(request, response)) {
            return;
        }
        if (module.queue().activeCount() > 0 && !"1".equals(request.getParameter("force"))) {
            Json.error(response, HttpServletResponse.SC_CONFLICT,
                    "transcode_busy: stop the queue or pass force=1 to stop it and disable");
            return;
        }
        module.queue().stop();
        feature.disable();
        audit("transcode.feature.disable", "transcode feature", "success", request);
        featureJson(response);
    }

    /// Installing/switching ffmpeg executes a downloaded binary, so an agent
    /// ticket is not enough even when its scope covers transcode. The runtime
    /// page is a human-session operation.
    private boolean requireHumanElevation(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (request.getAttribute(AuthFilter.AGENT_GRANT_ATTRIBUTE) != null) {
            Json.error(response, HttpServletResponse.SC_FORBIDDEN,
                    "ffmpeg_install_human_only: agent tickets cannot install or switch ffmpeg");
            return false;
        }
        return requireElevation(request, response);
    }

    /// The same second-password check the Minecraft surface uses: only over TLS,
    /// only with a configured developer password, and satisfied by a session
    /// elevation or a bearer token. Agents are let through by
    /// [AuthFilter#elevationSatisfied], but the route itself is closed to them by
    /// [org.jackhuang.hmcl.web.developer.AgentScopes] (unmapped POSTs are denied).
    private boolean requireElevation(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!RequestSecurity.isSecure(request, tlsActive.getAsBoolean(), config)) {
            Json.error(response, HttpServletResponse.SC_FORBIDDEN,
                    "tls_required: the developer elevation is only accepted over HTTPS");
            return false;
        }
        if (!developerAuth.isSet()) {
            Json.error(response, HttpServletResponse.SC_CONFLICT,
                    "developer_password_not_set: set the developer password before enabling transcoding");
            return false;
        }
        if (AuthFilter.elevationSatisfied(request, elevation, keyOf(request), sessionAlive(request))) {
            return true;
        }
        Json.error(response, HttpServletResponse.SC_FORBIDDEN,
                "developer_elevation_required: elevate with the developer password first");
        return false;
    }

    private String keyOf(HttpServletRequest request) {
        Object token = request.getAttribute(AuthFilter.SESSION_TOKEN_ATTRIBUTE);
        return DeveloperElevation.key(token instanceof String value ? value : null, request.getRemoteAddr());
    }

    private boolean sessionAlive(HttpServletRequest request) {
        Object token = request.getAttribute(AuthFilter.SESSION_TOKEN_ATTRIBUTE);
        return !(token instanceof String value) || value.isBlank() || auth.isAlive(value);
    }

    private static @Nullable String actorOf(HttpServletRequest request) {
        Object session = request.getAttribute(AuthFilter.SESSION_ATTRIBUTE);
        return session instanceof AuthService.Session s ? s.username() : null;
    }

    private void audit(String action, String object, String outcome, HttpServletRequest request) {
        AuditLog.log(AuditLog.Channel.WEB, action, object, outcome, actorOf(request), request.getRemoteAddr());
    }

    private static @Nullable JsonObject readJson(HttpServletRequest request) throws IOException {
        String text = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            return JsonParser.parseString(text).getAsJsonObject();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static @Nullable String stringField(JsonObject body, String name) {
        return body.has(name) && body.get(name).isJsonPrimitive() ? body.get(name).getAsString() : null;
    }

    private static boolean booleanField(JsonObject body, String name) {
        return body.has(name) && body.get(name).isJsonPrimitive() && body.get(name).getAsBoolean();
    }

    private static int intField(JsonObject body, String name) {
        try {
            return body.has(name) && body.get(name).isJsonPrimitive() ? body.get(name).getAsInt() : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }
}