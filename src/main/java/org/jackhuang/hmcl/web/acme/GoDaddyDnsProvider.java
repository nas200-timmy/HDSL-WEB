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

/// GoDaddy DNS-01 writer.
///
/// GoDaddy's record API replaces **all** records at a type/name, so this
/// adapter reads the existing TXT set, appends/removes only its own value, and
/// writes the set back. Deleting every TXT record at `_acme-challenge` would
/// otherwise wipe an operator's unrelated verification records.
///
/// Credentials: `apiKey`, `apiSecret`, `zone` (required).
@NotNullByDefault
public final class GoDaddyDnsProvider implements AcmeDnsProvider {

    private static final String DEFAULT_BASE = "https://api.godaddy.com/v1";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final String apiKey;
    private final String apiSecret;
    private final String zone;
    private final HttpClient http;
    private final String base;

    public GoDaddyDnsProvider(String apiKey, String apiSecret, String zone) {
        this(apiKey, apiSecret, zone,
                HttpClient.newBuilder().connectTimeout(TIMEOUT).build(), DEFAULT_BASE);
    }

    GoDaddyDnsProvider(String apiKey, String apiSecret, String zone,
                       HttpClient http, String base) {
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.zone = zone == null ? "" : zone.trim();
        this.http = http;
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    @Override
    public String id() {
        return "godaddy";
    }

    @Override
    public void present(String fqdn, String value) throws AcmeDnsException {
        requireZone();
        JsonArray current = records(fqdn);
        for (JsonElement element : current) {
            if (element.isJsonObject()) {
                JsonElement data = element.getAsJsonObject().get("data");
                if (data != null && data.isJsonPrimitive() && value.equals(data.getAsString())) {
                    return;
                }
            }
        }
        JsonObject record = new JsonObject();
        record.addProperty("data", value);
        record.addProperty("ttl", 600);
        current.add(record);
        put(fqdn, current);
    }

    @Override
    public void cleanup(String fqdn, String value) throws AcmeDnsException {
        requireZone();
        JsonArray current = records(fqdn);
        JsonArray remaining = new JsonArray();
        boolean removed = false;
        for (JsonElement element : current) {
            if (element.isJsonObject()) {
                JsonElement data = element.getAsJsonObject().get("data");
                if (data != null && data.isJsonPrimitive() && value.equals(data.getAsString())) {
                    removed = true;
                    continue;
                }
            }
            remaining.add(element);
        }
        if (!removed) {
            return;
        }
        if (remaining.isEmpty()) {
            delete(fqdn);
        } else {
            put(fqdn, remaining);
        }
    }

    private void requireZone() throws AcmeDnsException {
        if (zone.isBlank()) {
            throw new AcmeDnsException("GoDaddy requires the `zone` credential (e.g. example.com)");
        }
    }

    private String path(String fqdn) {
        return "/domains/" + URLEncoder.encode(zone, StandardCharsets.UTF_8)
                + "/records/TXT/" + URLEncoder.encode(relativeName(fqdn), StandardCharsets.UTF_8);
    }

    private String relativeName(String fqdn) {
        if (fqdn.equals(zone)) {
            return "@";
        }
        String suffix = "." + zone;
        return fqdn.endsWith(suffix) ? fqdn.substring(0, fqdn.length() - suffix.length()) : fqdn;
    }

    private JsonArray records(String fqdn) throws AcmeDnsException {
        HttpResponse<String> response = send("GET", path(fqdn), null);
        if (response.statusCode() == 404) {
            return new JsonArray();
        }
        if (response.statusCode() / 100 != 2) {
            throw new AcmeDnsException("GoDaddy GET " + path(fqdn) + " failed ("
                    + response.statusCode() + "): " + errorMessage(response.body()));
        }
        try {
            JsonElement tree = JsonParser.parseString(response.body());
            return tree.isJsonArray() ? tree.getAsJsonArray() : new JsonArray();
        } catch (RuntimeException e) {
            throw new AcmeDnsException("GoDaddy returned a non-JSON response", e);
        }
    }

    private void put(String fqdn, JsonArray records) throws AcmeDnsException {
        HttpResponse<String> response = send("PUT", path(fqdn), records.toString());
        if (response.statusCode() / 100 != 2) {
            throw new AcmeDnsException("GoDaddy PUT " + path(fqdn) + " failed ("
                    + response.statusCode() + "): " + errorMessage(response.body()));
        }
    }

    private void delete(String fqdn) throws AcmeDnsException {
        HttpResponse<String> response = send("DELETE", path(fqdn), null);
        if (response.statusCode() / 100 != 2 && response.statusCode() != 404) {
            throw new AcmeDnsException("GoDaddy DELETE " + path(fqdn) + " failed ("
                    + response.statusCode() + "): " + errorMessage(response.body()));
        }
    }

    private HttpResponse<String> send(String method, String path, @Nullable String body)
            throws AcmeDnsException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(TIMEOUT)
                .header("Authorization", "sso-key " + apiKey + ":" + apiSecret)
                .header("Accept", "application/json");
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        try {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new AcmeDnsException("GoDaddy request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AcmeDnsException("GoDaddy request was interrupted", e);
        }
    }

    private static String errorMessage(String body) {
        try {
            JsonElement tree = JsonParser.parseString(body == null ? "" : body);
            if (tree.isJsonObject()) {
                JsonElement message = tree.getAsJsonObject().get("message");
                if (message != null && !message.isJsonNull()) {
                    return message.getAsString();
                }
            }
        } catch (RuntimeException ignored) {
            // Fall through.
        }
        return "unknown error";
    }

    @Override
    public void test() throws AcmeDnsException {
        HttpResponse<String> response = send("GET", "/v1/domains?limit=1", null);
        if (response.statusCode() / 100 != 2) {
            throw new AcmeDnsException("GoDaddy credential check failed ("
                    + response.statusCode() + "): " + errorMessage(response.body()));
        }
    }
}
