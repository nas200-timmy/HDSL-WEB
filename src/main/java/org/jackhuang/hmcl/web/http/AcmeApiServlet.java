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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jackhuang.hmcl.web.acme.AcmeCa;
import org.jackhuang.hmcl.web.acme.AcmeCredentialStore;
import org.jackhuang.hmcl.web.acme.AcmeDnsProviderRegistry;
import org.jackhuang.hmcl.web.acme.AcmeService;
import org.jackhuang.hmcl.web.audit.AuditLog;
import org.jackhuang.hmcl.web.auth.AuthFilter;
import org.jackhuang.hmcl.web.auth.AuthService;
import org.jackhuang.hmcl.web.auth.LoginThrottle;
import org.jackhuang.hmcl.web.auth.UserStore;
import org.jackhuang.hmcl.web.config.ServerConfig;
import org.jackhuang.hmcl.web.config.ServerConfigWriter;
import org.jackhuang.hmcl.web.developer.DeveloperElevation;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/// `/api/settings/acme/*` — the panel's ACME settings.
///
/// Reads are session-gated by [AuthFilter]. Mutations require the developer
/// password (elevation) and HTTPS; credential writes additionally require the
/// panel password, because the DNS/EAB values are bearer credentials.
@NotNullByDefault
public final class AcmeApiServlet extends HttpServlet {

    private static final int MAX_BODY_BYTES = 16 * 1024;

    private final ServerConfig config;
    private final Supplier<@Nullable AcmeService> service;
    private final AuthService auth;
    private final DeveloperElevation elevation;
    private final BooleanSupplier tlsActive;
    private final LoginThrottle panelPasswordThrottle = new LoginThrottle();

    public AcmeApiServlet(ServerConfig config, Supplier<@Nullable AcmeService> service,
                          AuthService auth, DeveloperElevation elevation,
                          BooleanSupplier tlsActive) {
        this.config = config;
        this.service = service;
        this.auth = auth;
        this.elevation = elevation;
        this.tlsActive = tlsActive;
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getPathInfo();
        String method = request.getMethod();
        if (path == null || path.isEmpty() || "/".equals(path)) {
            if ("GET".equals(method)) {
                describe(response);
            } else if ("PUT".equals(method)) {
                updateConfig(request, response);
            } else {
                Json.error(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "use GET or PUT");
            }
            return;
        }
        switch (path) {
            case "/credentials" -> {
                if ("PUT".equals(method)) {
                    updateCredentials(request, response);
                } else if ("DELETE".equals(method)) {
                    clearCredentials(request, response);
                } else {
                    Json.error(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "use PUT or DELETE");
                }
            }
            case "/issue" -> {
                if ("POST".equals(method)) {
                    issueNow(request, response);
                } else {
                    Json.error(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "use POST");
                }
            }
            case "/disable" -> {
                if ("POST".equals(method)) {
                    disable(request, response);
                } else {
                    Json.error(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "use POST");
                }
            }
            default -> Json.error(response, HttpServletResponse.SC_NOT_FOUND, "not found");
        }
    }

    // --------------------------------------------------------------- describe --

    private void describe(HttpServletResponse response) throws IOException {
        ServerConfig.AcmeConfig acme = config.https.acme;
        AcmeService live = service.get();
        JsonObject body = new JsonObject();
        body.addProperty("available", live != null);
        body.addProperty("enabled", acme.enabled);
        body.addProperty("ca", acme.ca);
        body.addProperty("directoryUrl", acme.directoryUrl);
        body.addProperty("email", acme.email);
        body.add("domains", strings(acme.domains));
        body.addProperty("provider", acme.provider);
        body.addProperty("renewBeforeDays", acme.renewBeforeDays);
        body.addProperty("useStaging", acme.useStaging);
        body.addProperty("acceptTerms", acme.acceptTerms);

        JsonArray cas = new JsonArray();
        for (AcmeCa.Info ca : AcmeCa.all()) {
            JsonObject row = new JsonObject();
            row.addProperty("id", ca.id());
            row.addProperty("displayName", ca.displayName());
            row.addProperty("requiresEab", ca.requiresEab());
            row.addProperty("supportsWildcard", ca.supportsWildcard());
            row.addProperty("termsUrl", ca.termsUrl());
            row.addProperty("rootHint", ca.rootHint());
            row.addProperty("custom", ca.custom());
            cas.add(row);
        }
        body.add("cas", cas);

        JsonArray providers = new JsonArray();
        for (AcmeDnsProviderRegistry.Info provider : AcmeDnsProviderRegistry.all()) {
            JsonObject row = new JsonObject();
            row.addProperty("id", provider.id());
            row.addProperty("label", provider.label());
            row.addProperty("group", provider.group());
            row.addProperty("implemented", provider.implemented());
            JsonArray fields = new JsonArray();
            for (AcmeDnsProviderRegistry.Field field : provider.fields()) {
                JsonObject f = new JsonObject();
                f.addProperty("key", field.key());
                f.addProperty("label", field.label());
                f.addProperty("secret", field.secret());
                f.addProperty("required", field.required());
                fields.add(f);
            }
            row.add("fields", fields);
            providers.add(row);
        }
        body.add("providers", providers);

        AcmeCredentialStore credentials = live == null ? null : live.credentials();
        body.addProperty("credentialConfigured", credentials != null && credentials.configured());
        body.addProperty("eabConfigured", credentials != null && credentials.eabConfigured());
        body.addProperty("credentialProvider", credentials == null ? null : credentials.provider());
        body.addProperty("credentialsUpdatedAt", credentials == null ? null : credentials.updatedAt());

        if (live != null) {
            AcmeService.Status status = live.status();
            JsonObject s = new JsonObject();
            s.addProperty("state", status.state().name().toLowerCase(java.util.Locale.ROOT));
            s.addProperty("lastError", status.lastError());
            s.addProperty("lastAttempt", status.lastAttempt() == null ? null : status.lastAttempt().toString());
            s.addProperty("nextAttempt", status.nextAttempt() == null ? null : status.nextAttempt().toString());
            s.addProperty("expires", status.expires() == null ? null : status.expires().toString());
            s.addProperty("source", status.source());
            body.add("status", s);
        }
        Json.writePreservingNulls(response, body);
    }

    // ----------------------------------------------------------------- config --

    private void updateConfig(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!requireTls(request, response) || !requireElevation(request, response)) {
            return;
        }
        JsonObject body = readBody(request, response);
        if (body == null) {
            return;
        }
        ServerConfig.AcmeConfig next = new ServerConfig.AcmeConfig();
        next.enabled = bool(body, "enabled", config.https.acme.enabled);
        next.ca = string(body, "ca", config.https.acme.ca);
        next.directoryUrl = string(body, "directoryUrl", config.https.acme.directoryUrl);
        next.email = string(body, "email", config.https.acme.email);
        next.provider = string(body, "provider", config.https.acme.provider);
        next.renewBeforeDays = integer(body, "renewBeforeDays", config.https.acme.renewBeforeDays);
        next.useStaging = bool(body, "useStaging", config.https.acme.useStaging);
        next.acceptTerms = bool(body, "acceptTerms", config.https.acme.acceptTerms);
        if (body.has("domains") && body.get("domains").isJsonArray()) {
            for (JsonElement element : body.getAsJsonArray("domains")) {
                if (!element.isJsonPrimitive()) {
                    Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "domains must be strings");
                    return;
                }
                String domain = element.getAsString().trim().toLowerCase(java.util.Locale.ROOT);
                if (!domain.isEmpty() && !next.domains.contains(domain)) {
                    next.domains.add(domain);
                }
            }
        } else {
            next.domains.addAll(config.https.acme.domains);
        }
        String error = validate(next);
        if (error != null) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, error);
            return;
        }

        config.https.acme.enabled = next.enabled;
        config.https.acme.ca = next.ca;
        config.https.acme.directoryUrl = next.directoryUrl;
        config.https.acme.email = next.email;
        config.https.acme.provider = next.provider;
        config.https.acme.renewBeforeDays = next.renewBeforeDays;
        config.https.acme.useStaging = next.useStaging;
        config.https.acme.acceptTerms = next.acceptTerms;
        config.https.acme.domains.clear();
        config.https.acme.domains.addAll(next.domains);
        try {
            ServerConfigWriter.updateAcme(config.dataDir, config.https.acme);
        } catch (IOException e) {
            Json.error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "ACME settings are live but server.yaml could not be updated: " + e.getMessage());
            return;
        }
        AcmeService live = service.get();
        if (live != null) {
            if (config.https.acme.enabled) {
                live.start();
            } else {
                live.stop();
            }
        }
        AuditLog.log(AuditLog.Channel.WEB, "acme.config", config.https.acme.provider, "success",
                actorOf(request), request.getRemoteAddr());
        describe(response);
    }

    private @Nullable String validate(ServerConfig.AcmeConfig acme) {
        if (acme.renewBeforeDays < 7 || acme.renewBeforeDays > 60) {
            return "renewBeforeDays must be between 7 and 60";
        }
        AcmeCa.Info ca = AcmeCa.find(acme.ca);
        if (ca == null) {
            return "unknown CA: " + acme.ca;
        }
        if (ca.custom() && acme.directoryUrl.isBlank()) {
            return "custom CA requires directoryUrl";
        }
        AcmeDnsProviderRegistry.Info provider = AcmeDnsProviderRegistry.find(acme.provider);
        if (provider == null) {
            return "unknown DNS provider: " + acme.provider;
        }
        if (!provider.implemented()) {
            return "DNS provider is not implemented yet: " + acme.provider;
        }
        if (!acme.enabled) {
            return null;
        }
        if (acme.email.isBlank() || !acme.email.contains("@")) {
            return "email is required";
        }
        if (!acme.acceptTerms) {
            return "acceptTerms must be true";
        }
        List<String> domains = new ArrayList<>();
        for (String domain : acme.domains) {
            if (domain != null && !domain.isBlank()) {
                domains.add(domain.trim());
            }
        }
        if (domains.isEmpty()) {
            return "at least one domain is required";
        }
        if (domains.size() > 20) {
            return "at most 20 domains are supported";
        }
        for (String domain : domains) {
            if (!isCertificateName(domain)) {
                return "invalid DNS name: " + domain;
            }
        }
        return null;
    }

    // ------------------------------------------------------------- credentials --

    private void updateCredentials(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!requireTls(request, response) || !requireElevation(request, response)) {
            return;
        }
        JsonObject body = readBody(request, response);
        if (body == null) {
            return;
        }
        if (!requirePanelPassword(request, response, string(body, "panelPassword", ""))) {
            return;
        }
        AcmeService live = service.get();
        if (live == null) {
            Json.error(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "ACME service is not available; check the data directory permissions");
            return;
        }
        String provider = string(body, "provider", config.https.acme.provider);
        AcmeDnsProviderRegistry.Info info = AcmeDnsProviderRegistry.find(provider);
        if (info == null || !info.implemented()) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST,
                    "unknown or unimplemented DNS provider: " + provider);
            return;
        }
        Map<String, String> dns = new LinkedHashMap<>();
        if (body.has("dns") && body.get("dns").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : body.getAsJsonObject("dns").entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    dns.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
        }
        AcmeCredentialStore stored = live.credentials();
        Map<String, String> existingDns = provider.equals(stored.provider())
                ? stored.dnsCredentials() : Map.of();
        for (AcmeDnsProviderRegistry.Field field : info.fields()) {
            String value = dns.get(field.key());
            if (value == null || value.isBlank()) {
                String previous = existingDns.get(field.key());
                if (previous != null && !previous.isBlank()) {
                    dns.put(field.key(), previous);
                    value = previous;
                }
            }
            if (field.required() && (value == null || value.isBlank())) {
                Json.error(response, HttpServletResponse.SC_BAD_REQUEST,
                        "missing credential field: " + field.key());
                return;
            }
        }
        Map<String, String> eab = new LinkedHashMap<>();
        if (body.has("eab") && body.get("eab").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : body.getAsJsonObject("eab").entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    eab.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
        }
        if (eab.isEmpty() && stored.eabConfigured()) {
            eab.putAll(stored.eabCredentials());
        }
        if (!eab.isEmpty() && (eab.get("kid") == null || eab.get("hmacKey") == null)) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST,
                    "EAB requires both kid and hmacKey");
            return;
        }
        try {
            live.credentials().save(provider, dns, eab);
        } catch (IOException e) {
            Json.error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "could not write the ACME credential store: " + e.getMessage());
            return;
        }
        AuditLog.log(AuditLog.Channel.WEB, "acme.credentials.set", provider, "success",
                actorOf(request), request.getRemoteAddr());
        JsonObject answer = new JsonObject();
        answer.addProperty("ok", true);
        answer.addProperty("credentialConfigured", true);
        answer.addProperty("eabConfigured", !eab.isEmpty());
        answer.addProperty("provider", provider);
        Json.write(response, answer);
    }

    private void clearCredentials(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!requireTls(request, response) || !requireElevation(request, response)) {
            return;
        }
        JsonObject body = readBody(request, response);
        if (body == null) {
            return;
        }
        if (!requirePanelPassword(request, response, string(body, "panelPassword", ""))) {
            return;
        }
        AcmeService live = service.get();
        if (live == null) {
            Json.error(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "ACME service is not available");
            return;
        }
        try {
            live.credentials().clear();
        } catch (IOException e) {
            Json.error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "could not clear the ACME credential store: " + e.getMessage());
            return;
        }
        AuditLog.log(AuditLog.Channel.WEB, "acme.credentials.clear", null, "success",
                actorOf(request), request.getRemoteAddr());
        Json.write(response, Map.of("ok", true, "credentialConfigured", false, "eabConfigured", false));
    }

    // ------------------------------------------------------------- operations --

    private void issueNow(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!requireTls(request, response) || !requireElevation(request, response)) {
            return;
        }
        AcmeService live = service.get();
        if (live == null) {
            Json.error(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "ACME service is not available");
            return;
        }
        if (!config.https.acme.enabled) {
            Json.error(response, HttpServletResponse.SC_CONFLICT, "ACME is disabled");
            return;
        }
        AcmeService.Status status = live.requestIssue();
        AuditLog.log(AuditLog.Channel.WEB, "acme.issue", config.https.acme.provider,
                status.state() == AcmeService.State.ERROR ? "failed" : "accepted",
                actorOf(request), request.getRemoteAddr());
        response.setStatus(HttpServletResponse.SC_ACCEPTED);
        writeStatus(response, status);
    }

    private void disable(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!requireTls(request, response) || !requireElevation(request, response)) {
            return;
        }
        config.https.acme.enabled = false;
        try {
            ServerConfigWriter.updateAcme(config.dataDir, config.https.acme);
        } catch (IOException e) {
            Json.error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "could not persist the ACME switch: " + e.getMessage());
            return;
        }
        AcmeService live = service.get();
        if (live != null) {
            live.disable();
        }
        AuditLog.log(AuditLog.Channel.WEB, "acme.disable", null, "success",
                actorOf(request), request.getRemoteAddr());
        describe(response);
    }

    private void writeStatus(HttpServletResponse response, AcmeService.Status status) throws IOException {
        JsonObject answer = new JsonObject();
        answer.addProperty("ok", status.state() != AcmeService.State.ERROR);
        answer.addProperty("accepted", status.state() == AcmeService.State.ISSUING);
        answer.addProperty("state", status.state().name().toLowerCase(java.util.Locale.ROOT));
        answer.addProperty("lastError", status.lastError());
        answer.addProperty("expires", status.expires() == null ? null : status.expires().toString());
        answer.addProperty("nextAttempt", status.nextAttempt() == null ? null : status.nextAttempt().toString());
        Json.writePreservingNulls(response, answer);
    }

    // --------------------------------------------------------------- security --

    private boolean requireTls(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (RequestSecurity.isSecure(request, tlsActive.getAsBoolean(), config)) {
            return true;
        }
        Json.error(response, HttpServletResponse.SC_CONFLICT,
                "tls_required: ACME credentials are only accepted over HTTPS");
        return false;
    }

    private boolean requireElevation(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!AuthFilter.elevationSatisfied(request, elevation, keyOf(request), sessionAlive(request))) {
            Json.error(response, HttpServletResponse.SC_FORBIDDEN,
                    "developer_elevation_required: elevate with the developer password first");
            return false;
        }
        return true;
    }

    private boolean requirePanelPassword(HttpServletRequest request, HttpServletResponse response,
                                         String password) throws IOException {
        if (password == null || password.isBlank()) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "panelPassword is required");
            return false;
        }
        String address = request.getRemoteAddr();
        String key = address == null || address.isBlank() ? "unknown" : address;
        Instant now = Instant.now();
        if (panelPasswordThrottle.isBlocked(key, now)) {
            response.setHeader("Retry-After", Long.toString(Math.max(1, panelPasswordThrottle.block().toSeconds())));
            Json.error(response, 429, "too many failed panel-password attempts; try again later");
            return false;
        }
        UserStore users = auth.userStore();
        UserStore.User admin = users.admin();
        if (admin != null && users.verify(admin.username(), password)) {
            panelPasswordThrottle.clear(key);
            return true;
        }
        Duration refused = panelPasswordThrottle.recordFailure(key, Instant.now());
        if (refused.isZero()) {
            Json.error(response, HttpServletResponse.SC_FORBIDDEN, "the panel password does not match");
        } else {
            response.setHeader("Retry-After", Long.toString(Math.max(1, refused.toSeconds())));
            Json.error(response, 429, "too many failed panel-password attempts; try again later");
        }
        return false;
    }

    private boolean sessionAlive(HttpServletRequest request) {
        Object token = request.getAttribute(AuthFilter.SESSION_TOKEN_ATTRIBUTE);
        return !(token instanceof String value) || value.isBlank() || auth.isAlive(value);
    }

    private static String keyOf(HttpServletRequest request) {
        Object token = request.getAttribute(AuthFilter.SESSION_TOKEN_ATTRIBUTE);
        return DeveloperElevation.key(token instanceof String value ? value : null, request.getRemoteAddr());
    }

    private static @Nullable String actorOf(HttpServletRequest request) {
        Object session = request.getAttribute(AuthFilter.SESSION_ATTRIBUTE);
        return session instanceof AuthService.Session s ? s.username() : null;
    }

    // ---------------------------------------------------------------- helpers --

    private static JsonArray strings(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    private static String string(JsonObject body, String key, String fallback) {
        JsonElement value = body.get(key);
        return value == null || value.isJsonNull() || !value.isJsonPrimitive()
                ? fallback : value.getAsString();
    }

    private static boolean bool(JsonObject body, String key, boolean fallback) {
        JsonElement value = body.get(key);
        return value == null || value.isJsonNull() || !value.isJsonPrimitive()
                ? fallback : value.getAsBoolean();
    }

    private static int integer(JsonObject body, String key, int fallback) {
        JsonElement value = body.get(key);
        return value == null || value.isJsonNull() || !value.isJsonPrimitive()
                ? fallback : value.getAsInt();
    }

    private static @Nullable JsonObject readBody(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        byte[] bytes = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) {
            Json.error(response, 413, "body too large");
            return null;
        }
        if (bytes.length == 0) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "a JSON body is required");
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "a JSON object is required");
                return null;
            }
            return parsed.getAsJsonObject();
        } catch (RuntimeException e) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "malformed JSON");
            return null;
        }
    }

    private static boolean isCertificateName(String name) {
        String base = name.startsWith("*.") ? name.substring(2) : name;
        if (base.isEmpty() || base.contains(":") || base.contains("/") || !base.contains(".")
                || base.matches("\\d+(\\.\\d+)+")) {
            return false;
        }
        for (String label : base.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63
                    || !label.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")) {
                return false;
            }
        }
        return true;
    }
}
