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
import org.jackhuang.hmcl.web.developer.DeveloperAuth;
import org.jackhuang.hmcl.web.developer.DeveloperElevation;
import org.jackhuang.hmcl.web.http.Json;
import org.jackhuang.hmcl.web.http.RequestSecurity;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.function.BooleanSupplier;

/// The transcode feature's own REST surface. At this stage it only owns the
/// enable flow; queue, presets, media and tools are added by their own commits.
///
/// The gate in front of this servlet is [TranscodeFeatureFilter]; while the
/// feature is disabled only the routes below are reachable, and everything else
/// is answered 404 by the filter.
@NotNullByDefault
public final class TranscodeApiServlet extends HttpServlet {

    private final ServerConfig config;
    private final TranscodeFeatureState feature;
    private final AuthService auth;
    private final DeveloperAuth developerAuth;
    private final DeveloperElevation elevation;
    private final BooleanSupplier tlsActive;

    public TranscodeApiServlet(ServerConfig config, TranscodeFeatureState feature,
                               AuthService auth, DeveloperAuth developerAuth,
                               DeveloperElevation elevation, BooleanSupplier tlsActive) {
        this.config = config;
        this.feature = feature;
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
        Json.error(response, HttpServletResponse.SC_NOT_FOUND, "not found");
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = path(request);
        switch (path) {
            case "/feature/enable" -> enable(request, response);
            case "/feature/decline" -> decline(request, response);
            case "/feature/disable" -> disable(request, response);
            default -> Json.error(response, HttpServletResponse.SC_NOT_FOUND, "not found");
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
        feature.disable();
        audit("transcode.feature.disable", "transcode feature", "success", request);
        featureJson(response);
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