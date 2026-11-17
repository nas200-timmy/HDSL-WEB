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
package org.jackhuang.hmcl.web.acme;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/// Cloudflare DNS-01 writer.
///
/// Credentials: `apiToken` (required), `zone` (optional; the longest matching
/// zone is discovered when omitted). The token should be scoped to
/// `Zone.DNS:Edit` for the target zone only.
@NotNullByDefault
public final class CloudflareDnsProvider implements AcmeDnsProvider {

    private static final String DEFAULT_BASE = "https://api.cloudflare.com/client/v4";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final String apiToken;
    private final @Nullable String configuredZone;
    private final HttpClient http;
    private final String base;
    private final Map<String, String> recordIds = new ConcurrentHashMap<>();

    public CloudflareDnsProvider(String apiToken, @Nullable String configuredZone) {
        this(apiToken, configuredZone, HttpClient.newBuilder().connectTimeout(TIMEOUT).build(), DEFAULT_BASE);
    }

    CloudflareDnsProvider(String apiToken, @Nullable String configuredZone,
                          HttpClient http, String base) {
        this.apiToken = apiToken;
        this.configuredZone = configuredZone == null || configuredZone.isBlank() ? null : configuredZone.trim();
        this.http = http;
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    @Override
    public String id() {
        return "cloudflare";
    }

    @Override
    public void present(String fqdn, String value) throws AcmeDnsException {
        String zoneId = resolveZoneId(fqdn);
        JsonObject body = new JsonObject();
        body.addProperty("type", "TXT");
        body.addProperty("name", fqdn);
        body.addProperty("content", value);
        body.addProperty("ttl", 60);

        JsonObject answer = request("POST", "/zones/" + zoneId + "/dns_records", body.toString());
        JsonElement result = answer.get("result");
        if (result == null || !result.isJsonObject()) {
            throw new AcmeDnsException("Cloudflare did not return the created DNS record");
        }
        JsonElement id = result.getAsJsonObject().get("id");
        if (id == null || id.isJsonNull()) {
            throw new AcmeDnsException("Cloudflare did not return a record id");
        }
        recordIds.put(key(fqdn, value), id.getAsString());
    }

    @Override
    public void cleanup(String fqdn, String value) throws AcmeDnsException {
        String recordId = recordIds.remove(key(fqdn, value));
        String zoneId = resolveZoneId(fqdn);
        if (recordId == null) {
            recordId = findRecordId(zoneId, fqdn, value);
        }
        if (recordId == null) {
            return;
        }
        try {
            request("DELETE", "/zones/" + zoneId + "/dns_records/" + recordId, null);
        } catch (AcmeDnsException e) {
            // A record that is already gone is not a cleanup failure.
            if (!e.getMessage().contains("404")) {
                throw e;
            }
        }
    }

    private String resolveZoneId(String fqdn) throws AcmeDnsException {
        if (configuredZone != null) {
            return zoneIdByName(configuredZone);
        }
        String candidate = fqdn.startsWith("_acme-challenge.")
                ? fqdn.substring("_acme-challenge.".length()) : fqdn;
        while (true) {
            String id = zoneIdOrNull(candidate);
            if (id != null) {
                return id;
            }
            int dot = candidate.indexOf('.');
            if (dot < 0 || candidate.indexOf('.', dot + 1) < 0) {
                throw new AcmeDnsException("Cloudflare could not find a zone for " + fqdn
                        + "; set the `zone` credential explicitly");
            }
            candidate = candidate.substring(dot + 1);
        }
    }

    private String zoneIdByName(String zone) throws AcmeDnsException {
        String id = zoneIdOrNull(zone);
        if (id == null) {
            throw new AcmeDnsException("Cloudflare zone not found: " + zone);
        }
        return id;
    }

    private @Nullable String zoneIdOrNull(String zone) throws AcmeDnsException {
        String query = URLEncoder.encode(zone, StandardCharsets.UTF_8);
        JsonObject answer = request("GET", "/zones?name=" + query, null);
        JsonElement result = answer.get("result");
        if (result == null || !result.isJsonArray()) {
            return null;
        }
        JsonArray array = result.getAsJsonArray();
        if (array.isEmpty()) {
            return null;
        }
        JsonElement id = array.get(0).getAsJsonObject().get("id");
        return id == null || id.isJsonNull() ? null : id.getAsString();
    }

    private @Nullable String findRecordId(String zoneId, String fqdn, String value) throws AcmeDnsException {
        String query = "?type=TXT&name=" + URLEncoder.encode(fqdn, StandardCharsets.UTF_8)
                + "&content=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
        JsonObject answer = request("GET", "/zones/" + zoneId + "/dns_records" + query, null);
        JsonElement result = answer.get("result");
        if (result == null || !result.isJsonArray() || result.getAsJsonArray().isEmpty()) {
            return null;
        }
        JsonElement id = result.getAsJsonArray().get(0).getAsJsonObject().get("id");
        return id == null || id.isJsonNull() ? null : id.getAsString();
    }

    private JsonObject request(String method, String path, @Nullable String body) throws AcmeDnsException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + apiToken)
                .header("Accept", "application/json");
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new AcmeDnsException("Cloudflare DNS request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AcmeDnsException("Cloudflare DNS request was interrupted", e);
        }
        JsonObject json = parse(response.body());
        if (response.statusCode() / 100 != 2 || !json.has("success")
                || !json.get("success").isJsonPrimitive() || !json.get("success").getAsBoolean()) {
            throw new AcmeDnsException("Cloudflare DNS " + method + " " + path
                    + " failed (" + response.statusCode() + "): " + errorMessage(json));
        }
        return json;
    }

    private static JsonObject parse(String body) throws AcmeDnsException {
        try {
            JsonElement tree = JsonParser.parseString(body == null ? "" : body);
            return tree.isJsonObject() ? tree.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            throw new AcmeDnsException("Cloudflare returned a non-JSON response", e);
        }
    }

    private static String errorMessage(JsonObject json) {
        JsonElement errors = json.get("errors");
        if (errors != null && errors.isJsonArray() && !errors.getAsJsonArray().isEmpty()) {
            JsonElement first = errors.getAsJsonArray().get(0);
            if (first.isJsonObject()) {
                JsonElement message = first.getAsJsonObject().get("message");
                if (message != null && !message.isJsonNull()) {
                    return message.getAsString();
                }
            }
        }
        JsonElement message = json.get("message");
        return message != null && !message.isJsonNull() ? message.getAsString() : "unknown error";
    }

    private static String key(String fqdn, String value) {
        return fqdn + "\n" + value;
    }
}
