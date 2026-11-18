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
import org.jackhuang.hmcl.web.acme.AcmeCa;
import org.jackhuang.hmcl.web.acme.AcmeDnsProviderRegistry;
import org.jackhuang.hmcl.web.acme.CertificateService;
import org.jackhuang.hmcl.web.acme.CertificateStore;
import org.jackhuang.hmcl.web.audit.AuditLog;
import org.jackhuang.hmcl.web.auth.AuthFilter;
import org.jackhuang.hmcl.web.auth.AuthService;
import org.jackhuang.hmcl.web.config.ServerConfig;
import org.jackhuang.hmcl.web.developer.DeveloperElevation;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/// Certificate management API, grouped by apex domain.
///
/// - `GET    /api/settings/certificates`            — list + per-entry status
/// - `POST   /api/settings/certificates`            — create an entry
/// - `PUT    /api/settings/certificates/{id}`       — update desired state
/// - `DELETE /api/settings/certificates/{id}`       — delete (not while active)
/// - `POST   /api/settings/certificates/{id}/issue`
/// - `POST   /api/settings/certificates/{id}/activate`
///
/// Writes require HTTPS + developer elevation. Domain names and provider ids
/// are not secrets; DNS credentials stay behind the existing ACME endpoint.
@NotNullByDefault
public final class CertificatesApiServlet extends HttpServlet {

    private static final int MAX_BODY_BYTES = 32 * 1024;

    private final ServerConfig config;
    private final CertificateStore store;
    private final Supplier<@Nullable CertificateService> service;
    private final AuthService auth;
    private final DeveloperElevation elevation;
    private final BooleanSupplier tlsActive;

    public CertificatesApiServlet(ServerConfig config, CertificateStore store,
                                  Supplier<@Nullable CertificateService> service,
                                  AuthService auth, DeveloperElevation elevation,
                                  BooleanSupplier tlsActive) {
        this.config = config;
        this.store = store;
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
            } else if ("POST".equals(method)) {
                create(request, response);
            } else {
                Json.error(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "use GET or POST");
            }
            return;
        }
        String[] segments = path.substring(1).split("/");
        String id = segments.length > 0 ? segments[0] : "";
        if (segments.length == 1) {
            switch (method) {
                case "PUT" -> update(request, response, id);
                case "DELETE" -> delete(request, response, id);
                default -> Json.error(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "use PUT or DELETE");
            }
            return;
        }
        if (segments.length == 2 && "issue".equals(segments[1]) && "POST".equals(method)) {
            issue(request, response, id);
            return;
        }
        if (segments.length == 2 && "activate".equals(segments[1]) && "POST".equals(method)) {
            activate(request, response, id);
            return;
        }
        Json.error(response, HttpServletResponse.SC_NOT_FOUND, "not found");
    }

    // --------------------------------------------------------------- describe --

    private void describe(HttpServletResponse response) throws IOException {
        CertificateService live = service.get();
        JsonObject body = new JsonObject();
        body.addProperty("available", live != null);
        CertificateStore.Entry active = store.active();
        body.addProperty("activeId", active == null ? null : active.id);
        JsonArray entries = new JsonArray();
        for (CertificateStore.Entry entry : store.all()) {
            entries.add(entryJson(entry, live));
        }
        body.add("entries", entries);
        Json.writePreservingNulls(response, body);
    }

    private JsonObject entryJson(CertificateStore.Entry entry, @Nullable CertificateService live) {
        JsonObject row = new JsonObject();
        row.addProperty("id", entry.id);
        row.addProperty("apex", entry.apex);
        row.add("domains", strings(entry.domains));
        row.add("issuedDomains", strings(entry.issuedDomains));
        row.addProperty("pending", CertificateService.pending(entry));
        row.addProperty("source", entry.source == CertificateStore.Source.ACME ? "acme" : "upload");
        row.addProperty("active", entry.active);
        row.addProperty("enabled", entry.enabled);
        row.addProperty("ca", entry.ca);
        row.addProperty("email", entry.email);
        row.addProperty("provider", entry.provider);
        row.addProperty("credentialId", entry.credentialId);
        row.addProperty("renewBeforeDays", entry.renewBeforeDays);
        row.addProperty("staging", entry.staging);
        row.addProperty("acceptTerms", entry.acceptTerms);
        row.addProperty("notAfter", entry.notAfter);
        row.addProperty("lastError", entry.lastError);
        row.addProperty("lastAttempt", entry.lastAttempt);
        row.addProperty("updatedAt", entry.updatedAt);
        row.addProperty("hasFiles", Files.isRegularFile(store.certPath(entry.id))
                && Files.isRegularFile(store.keyPath(entry.id)));
        X509Certificate leaf = readLeaf(store.certPath(entry.id));
        if (leaf != null) {
            row.addProperty("subject", leaf.getSubjectX500Principal().getName());
            row.addProperty("issuer", leaf.getIssuerX500Principal().getName());
            JsonArray sans = new JsonArray();
            try {
                var names = leaf.getSubjectAlternativeNames();
                if (names != null) {
                    for (List<?> name : names) {
                        if (Integer.valueOf(2).equals(name.get(0)) && name.get(1) instanceof String dns) {
                            sans.add("DNS:" + dns);
                        }
                    }
                }
            } catch (Exception ignored) {
                // A certificate without readable SANs still exposes the rest.
            }
            row.add("sans", sans);
            row.addProperty("notAfter", leaf.getNotAfter().toInstant().toString());
            row.addProperty("fingerprintSha256", sha256(leaf));
        }
        if (live != null) {
            CertificateService.Status status = live.status(entry.id);
            JsonObject s = new JsonObject();
            s.addProperty("state", status.state());
            s.addProperty("phase", status.phase());
            s.addProperty("lastError", status.lastError());
            s.addProperty("lastAttempt", status.lastAttempt() == null ? null : status.lastAttempt().toString());
            s.addProperty("nextAttempt", status.nextAttempt() == null ? null : status.nextAttempt().toString());
            s.addProperty("notAfter", status.notAfter() == null ? null : status.notAfter().toString());
            row.add("status", s);
        }
        return row;
    }

    // ------------------------------------------------------------------ write --

    private void create(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!requireWrite(request, response)) {
            return;
        }
        JsonObject body = readBody(request, response);
        if (body == null) {
            return;
        }
        List<String> domains = domains(body);
        // The apex is a grouping key, not something the operator should type:
        // derive it from the names. A cert spanning unrelated main domains is
        // rejected by validateDomains() below.
        String apex = string(body, "apex", "").trim().toLowerCase(Locale.ROOT);
        if (apex.isBlank()) {
            apex = org.jackhuang.hmcl.web.acme.CertificateMigration.apexOf(domains, null);
        }
        String error = validate(apex, domains, body);
        if (error != null) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, error);
            return;
        }
        CertificateStore.Entry entry = store.create(apex, domains);
        String groupApex = apex;
        boolean hasDefault = store.all().stream()
                .anyMatch(item -> item.apex.equals(groupApex) && item.active);
        if (!hasDefault) {
            store.setActive(entry.id);
            entry = store.find(entry.id);
        }
        CertificateStore.Entry updated = store.update(entry.id, target -> apply(target, body));
        AuditLog.log(AuditLog.Channel.WEB, "certificate.create", apex, "success",
                actorOf(request), request.getRemoteAddr());
        CertificateService live = service.get();
        Json.writePreservingNulls(response, entryJson(updated, live));
    }

    private void update(HttpServletRequest request, HttpServletResponse response, String id) throws IOException {
        if (!requireWrite(request, response)) {
            return;
        }
        CertificateStore.Entry existing = store.find(id);
        if (existing == null) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "unknown certificate");
            return;
        }
        JsonObject body = readBody(request, response);
        if (body == null) {
            return;
        }
        List<String> domains = body.has("domains") ? domains(body) : existing.domains;
        // The apex is the grouping key: it is fixed at creation, and changing
        // it silently would move the entry between groups in the UI.
        String apex = existing.apex;
        String error = validate(apex, domains, body);
        if (error != null) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, error);
            return;
        }
        CertificateStore.Entry updated = store.update(id, target -> {
            target.domains = new ArrayList<>(domains);
            apply(target, body);
        });
        AuditLog.log(AuditLog.Channel.WEB, "certificate.update", updated.apex, "success",
                actorOf(request), request.getRemoteAddr());
        Json.writePreservingNulls(response, entryJson(updated, service.get()));
    }

    private void delete(HttpServletRequest request, HttpServletResponse response, String id) throws IOException {
        if (!requireWrite(request, response)) {
            return;
        }
        CertificateStore.Entry entry = store.find(id);
        if (entry == null) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "unknown certificate");
            return;
        }
        if (entry.active) {
            Json.error(response, HttpServletResponse.SC_CONFLICT,
                    "this certificate is active; activate another one first");
            return;
        }
        store.delete(id);
        Path dir = config.dataDir.resolve("certs").resolve(id);
        if (Files.isDirectory(dir)) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // The registry entry is gone; a leftover file is harmless.
                    }
                });
            }
        }
        AuditLog.log(AuditLog.Channel.WEB, "certificate.delete", entry.apex, "success",
                actorOf(request), request.getRemoteAddr());
        describe(response);
    }

    private void issue(HttpServletRequest request, HttpServletResponse response, String id) throws IOException {
        if (!requireWrite(request, response)) {
            return;
        }
        CertificateService live = service.get();
        if (live == null) {
            Json.error(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "the certificate service is not initialized");
            return;
        }
        CertificateStore.Entry entry = store.find(id);
        if (entry == null) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "unknown certificate");
            return;
        }
        try {
            CertificateService.Status status = live.requestIssue(id);
            AuditLog.log(AuditLog.Channel.WEB, "certificate.issue", entry.apex, "accepted",
                    actorOf(request), request.getRemoteAddr());
            JsonObject body = new JsonObject();
            body.addProperty("id", status.id());
            body.addProperty("state", status.state());
            body.addProperty("phase", status.phase());
            body.add("entries", entriesJson(live));
            Json.writePreservingNulls(response, body);
        } catch (IOException e) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        }
    }

    private void activate(HttpServletRequest request, HttpServletResponse response, String id) throws IOException {
        if (!requireWrite(request, response)) {
            return;
        }
        CertificateService live = service.get();
        if (live == null) {
            Json.error(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "the certificate service is not initialized");
            return;
        }
        CertificateStore.Entry entry = store.find(id);
        if (entry == null) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "unknown certificate");
            return;
        }
        try {
            live.activate(id);
            AuditLog.log(AuditLog.Channel.WEB, "certificate.activate", entry.apex, "success",
                    actorOf(request), request.getRemoteAddr());
            describe(response);
        } catch (IOException e) {
            Json.error(response, HttpServletResponse.SC_CONFLICT, e.getMessage());
        }
    }

    private JsonArray entriesJson(CertificateService live) {
        JsonArray array = new JsonArray();
        for (CertificateStore.Entry entry : store.all()) {
            array.add(entryJson(entry, live));
        }
        return array;
    }

    private void apply(CertificateStore.Entry target, JsonObject body) {
        target.ca = string(body, "ca", target.ca);
        target.email = string(body, "email", target.email);
        target.provider = string(body, "provider", target.provider);
        target.credentialId = string(body, "credentialId", target.credentialId);
        target.renewBeforeDays = integer(body, "renewBeforeDays", target.renewBeforeDays);
        target.staging = bool(body, "staging", target.staging);
        target.acceptTerms = bool(body, "acceptTerms", target.acceptTerms);
        target.enabled = bool(body, "enabled", target.enabled);
    }

    private @Nullable String validate(String apex, List<String> domains, JsonObject body) {
        if (domains.isEmpty()) {
            return "at least one domain is required";
        }
        if (domains.size() > 100) {
            return "at most 100 domains are supported";
        }
        String domainError = CertificateService.validateDomains(apex, domains);
        if (domainError != null) {
            return domainError;
        }
        for (String domain : domains) {
            if (!isCertificateName(domain)) {
                return "invalid DNS name: " + domain;
            }
        }
        int renew = integer(body, "renewBeforeDays", 30);
        if (renew < 7 || renew > 60) {
            return "renewBeforeDays must be between 7 and 60";
        }
        String ca = string(body, "ca", "letsencrypt");
        if (AcmeCa.find(ca) == null) {
            return "unknown CA: " + ca;
        }
        String provider = string(body, "provider", "cloudflare");
        AcmeDnsProviderRegistry.Info info = AcmeDnsProviderRegistry.find(provider);
        if (info == null) {
            return "unknown DNS provider: " + provider;
        }
        if (!info.implemented()) {
            return "DNS provider is not implemented yet: " + provider;
        }
        return null;
    }

    // ---------------------------------------------------------------- helpers --

    private boolean requireWrite(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!RequestSecurity.isSecure(request, tlsActive.getAsBoolean(), config)) {
            Json.error(response, HttpServletResponse.SC_CONFLICT,
                    "tls_required: certificate changes are only accepted over HTTPS");
            return false;
        }
        if (!AuthFilter.elevationSatisfied(request, elevation, keyOf(request), sessionAlive(request))) {
            Json.error(response, HttpServletResponse.SC_FORBIDDEN,
                    "developer_elevation_required: elevate with the developer password first");
            return false;
        }
        return true;
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

    private static List<String> domains(JsonObject body) {
        List<String> result = new ArrayList<>();
        JsonElement element = body.get("domains");
        if (element == null || !element.isJsonArray()) {
            return result;
        }
        for (JsonElement item : element.getAsJsonArray()) {
            if (item.isJsonPrimitive()) {
                String value = item.getAsString().trim().toLowerCase(Locale.ROOT);
                if (value.endsWith(".")) {
                    value = value.substring(0, value.length() - 1);
                }
                if (!value.isEmpty() && !result.contains(value)) {
                    result.add(value);
                }
            }
        }
        return result;
    }

    private static JsonArray strings(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    private static String string(JsonObject body, String key, String fallback) {
        JsonElement value = body.get(key);
        return value == null || value.isJsonNull() || !value.isJsonPrimitive()
                ? fallback : value.getAsString().trim();
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

    private static @Nullable X509Certificate readLeaf(Path certPath) {
        if (!Files.isRegularFile(certPath)) {
            return null;
        }
        try (var in = Files.newInputStream(certPath)) {
            return (X509Certificate) java.security.cert.CertificateFactory.getInstance("X.509")
                    .generateCertificate(in);
        } catch (Exception e) {
            return null;
        }
    }

    private static String sha256(X509Certificate certificate) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(certificate.getEncoded());
            StringBuilder hex = new StringBuilder();
            for (byte value : digest) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (Exception e) {
            return "";
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
