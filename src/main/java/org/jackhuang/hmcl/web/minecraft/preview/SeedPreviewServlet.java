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
package org.jackhuang.hmcl.web.minecraft.preview;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jackhuang.hmcl.web.http.Json;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/// The seed-preview endpoints, mounted at `/api/mc/preview/`.
///
/// The panel already has one gate in front of every `/api/mc/*` request (the
/// Minecraft feature switch) and one in front of every `/api/*` request (the
/// session). This servlet adds no authentication of its own — it adds the rule
/// that a preview can only ever be *its own* job, because the id is a random
/// handle and the request never names a path on disk.
@NotNullByDefault
public final class SeedPreviewServlet extends HttpServlet {

    private static final java.util.regex.Pattern ID =
            java.util.regex.Pattern.compile("[0-9a-f]{6,16}");

    private static final java.util.regex.Pattern VERSION =
            java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}");

    private final SeedPreviewService service;

    public SeedPreviewServlet(SeedPreviewService service) {
        this.service = service;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        handle(request, response, "GET");
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        handle(request, response, "POST");
    }

    @Override
    protected void doDelete(HttpServletRequest request, HttpServletResponse response) throws IOException {
        handle(request, response, "DELETE");
    }

    private void handle(HttpServletRequest request, HttpServletResponse response, String method) throws IOException {
        String path = request.getPathInfo();
        if (path == null || path.isEmpty() || "/".equals(path)) {
            if ("POST".equals(method)) {
                submit(request, response);
                return;
            }
            Json.error(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "POST a PreviewSpec here");
            return;
        }
        String rest = path.substring(1);
        if ("capabilities".equals(rest) && "GET".equals(method)) {
            capabilities(request, response);
            return;
        }
        if ("history".equals(rest) && "GET".equals(method)) {
            history(response);
            return;
        }
        if (rest.startsWith("history/")) {
            historyEntry(response, method, rest.substring("history/".length()));
            return;
        }
        if (rest.endsWith(".png") && "GET".equals(method)) {
            image(response, rest.substring(0, rest.length() - 4));
            return;
        }
        if (rest.endsWith(".json") && "GET".equals(method)) {
            meta(response, rest.substring(0, rest.length() - 5));
            return;
        }
        if ("DELETE".equals(method)) {
            if (!service.cancel(rest)) {
                Json.error(response, HttpServletResponse.SC_NOT_FOUND, "no running preview " + rest);
                return;
            }
            Json.write(response, java.util.Map.of("ok", true));
            return;
        }
        if ("GET".equals(method)) {
            PreviewJob job = service.job(rest);
            if (job == null) {
                Json.error(response, HttpServletResponse.SC_NOT_FOUND, "no preview " + rest);
                return;
            }
            Json.write(response, job.toJson());
            return;
        }
        Json.error(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "not supported");
    }

    private void capabilities(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String version = request.getParameter("version");
        if (version == null || !validVersion(version)) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "a Minecraft version id is required");
            return;
        }
        Json.write(response, service.capabilities(version.trim()));
    }

    private void submit(HttpServletRequest request, HttpServletResponse response) throws IOException {
        JsonObject body;
        try (InputStream in = request.getInputStream()) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            body = text.isBlank() ? new JsonObject() : JsonParser.parseString(text).getAsJsonObject();
        } catch (RuntimeException e) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "malformed JSON body");
            return;
        }
        String version = string(body, "version");
        String seed = string(body, "seed");
        if (version == null || !validVersion(version)) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "a Minecraft version id is required");
            return;
        }
        if (seed == null || seed.isBlank()) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "seed is required");
            return;
        }
        // The seed goes into `server.properties`, one `key=value` per line: a
        // newline would let it write a second property.
        if (seed.length() > PreviewSpec.MAX_SEED_LENGTH || seed.indexOf('\n') >= 0 || seed.indexOf('\r') >= 0) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "seed is too long or contains a newline");
            return;
        }
        int radius = clamp(integer(body, "radius", 512), PreviewSpec.MIN_RADIUS, PreviewSpec.MAX_RADIUS);
        int y = clamp(integer(body, "biomeY", PreviewSpec.DEFAULT_BIOME_Y), -64, 320);
        PreviewJob job = service.submit(new PreviewSpec(version.trim(), seed.trim(), radius, y));
        Json.write(response, HttpServletResponse.SC_ACCEPTED, job.toJson());
    }

    /// The results still on disk, newest first. This is what "试过的种子"
    /// shows; it survives a page reload because it is the cache itself.
    private void history(HttpServletResponse response) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("limit", SeedPreviewService.MAX_HISTORY);
        com.google.gson.JsonArray entries = new com.google.gson.JsonArray();
        for (JsonObject entry : service.history()) {
            entries.add(entry);
        }
        body.add("entries", entries);
        Json.write(response, body);
    }

    private void historyEntry(HttpServletResponse response, String method, String rest) throws IOException {
        String id;
        boolean png = false;
        boolean json = false;
        if (rest.endsWith(".png")) {
            id = rest.substring(0, rest.length() - 4);
            png = true;
        } else if (rest.endsWith(".json")) {
            id = rest.substring(0, rest.length() - 5);
            json = true;
        } else {
            id = rest;
        }
        if (!ID.matcher(id).matches()) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "a preview id is required");
            return;
        }
        if ("DELETE".equals(method)) {
            if (!service.deleteCached(id)) {
                Json.error(response, HttpServletResponse.SC_NOT_FOUND, "no cached preview " + id);
                return;
            }
            Json.write(response, java.util.Map.of("ok", true));
            return;
        }
        if (!"GET".equals(method)) {
            Json.error(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "not supported");
            return;
        }
        Path directory = service.cachedDirectory(id);
        if (directory == null) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "no cached preview " + id);
            return;
        }
        Path file = directory.resolve(png ? "preview.png" : json ? "meta.json" : "meta.json");
        if (!Files.isRegularFile(file)) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "no cached preview " + id);
            return;
        }
        if (png) {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("image/png");
            response.setHeader("Cache-Control", "private, max-age=86400");
            response.setContentLengthLong(Files.size(file));
            Files.copy(file, response.getOutputStream());
        } else {
            Json.write(response, JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)));
        }
    }

    private void image(HttpServletResponse response, String id) throws IOException {
        PreviewJob job = service.job(id);
        Path file = job == null ? null : job.image();
        if (file == null || !Files.isRegularFile(file)) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "no image for " + id);
            return;
        }
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("image/png");
        // A finished preview is immutable: the cache key is the whole request.
        response.setHeader("Cache-Control", "private, max-age=86400");
        response.setContentLengthLong(Files.size(file));
        Files.copy(file, response.getOutputStream());
    }

    private void meta(HttpServletResponse response, String id) throws IOException {
        PreviewJob job = service.job(id);
        Path file = job == null ? null : job.meta();
        if (file == null || !Files.isRegularFile(file)) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "no metadata for " + id);
            return;
        }
        Json.write(response, JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)));
    }

    /// The version id becomes a directory name in the preview cache, so it must
    /// not contain a separator or a dot segment. The manifest lookup would
    /// reject a bogus id anyway, but the path is built before that lookup runs.
    private static boolean validVersion(String version) {
        return VERSION.matcher(version.trim()).matches();
    }

    private static @Nullable String string(JsonObject body, String name) {
        return body.has(name) && body.get(name).isJsonPrimitive() ? body.get(name).getAsString() : null;
    }

    private static int integer(JsonObject body, String name, int fallback) {
        try {
            return body.has(name) && body.get(name).isJsonPrimitive() ? body.get(name).getAsInt() : fallback;
        } catch (NumberFormatException | UnsupportedOperationException e) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /// Lower-cased for logs; the version itself is validated by the manifest.
    static String normalise(String version) {
        return version.trim().toLowerCase(Locale.ROOT);
    }
}
