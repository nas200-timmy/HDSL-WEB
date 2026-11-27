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

/// Vultr DNS-01 writer.
///
/// Credentials: `apiKey` and `zone` (the domain, e.g. `example.com`; required).
@NotNullByDefault
public final class VultrDnsProvider implements AcmeDnsProvider {

    private static final String DEFAULT_BASE = "https://api.vultr.com/v2";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final String apiKey;
    private final String zone;
    private final HttpClient http;
    private final String base;
    private final Map<String, String> recordIds = new ConcurrentHashMap<>();

    public VultrDnsProvider(String apiKey, String zone) {
        this(apiKey, zone, HttpClient.newBuilder().connectTimeout(TIMEOUT).build(), DEFAULT_BASE);
    }

    VultrDnsProvider(String apiKey, String zone, HttpClient http, String base) {
        this.apiKey = apiKey;
        this.zone = zone == null ? "" : zone.trim();
        this.http = http;
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    @Override
    public String id() {
        return "vultr";
    }

    @Override
    public void present(String fqdn, String value) throws AcmeDnsException {
        requireZone();
        JsonObject body = new JsonObject();
        body.addProperty("name", relativeName(fqdn));
        body.addProperty("type", "TXT");
        body.addProperty("data", value);
        body.addProperty("ttl", 600);
        JsonObject answer = request("POST", path() + "/records", body.toString());
        JsonElement record = answer.get("record");
        JsonElement id = record == null || !record.isJsonObject() ? null : record.getAsJsonObject().get("id");
        if (id == null || id.isJsonNull()) {
            throw new AcmeDnsException("Vultr did not return the created record");
        }
        recordIds.put(key(fqdn, value), id.getAsString());
    }

    @Override
    public void cleanup(String fqdn, String value) throws AcmeDnsException {
        requireZone();
        String recordId = recordIds.remove(key(fqdn, value));
        if (recordId == null) {
            recordId = findRecordId(fqdn, value);
        }
        if (recordId == null) {
            return;
        }
        try {
            request("DELETE", path() + "/records/" + recordId, null);
        } catch (AcmeDnsException e) {
            if (!e.getMessage().contains("404") && !e.getMessage().contains("not found")) {
                throw e;
            }
        }
    }

    private void requireZone() throws AcmeDnsException {
        if (zone.isBlank()) {
            throw new AcmeDnsException("Vultr requires the `zone` credential (e.g. example.com)");
        }
    }

    private String path() {
        return "/domains/" + URLEncoder.encode(zone, StandardCharsets.UTF_8);
    }

    private String relativeName(String fqdn) {
        if (fqdn.equals(zone)) {
            return "@";
        }
        String suffix = "." + zone;
        return fqdn.endsWith(suffix) ? fqdn.substring(0, fqdn.length() - suffix.length()) : fqdn;
    }

    private @Nullable String findRecordId(String fqdn, String value) throws AcmeDnsException {
        JsonObject answer = request("GET", path() + "/records", null);
        JsonElement records = answer.get("records");
        if (records == null || !records.isJsonArray()) {
            return null;
        }
        for (JsonElement element : records.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject record = element.getAsJsonObject();
            JsonElement name = record.get("name");
            JsonElement data = record.get("data");
            JsonElement id = record.get("id");
            if (name != null && name.isJsonPrimitive()
                    && relativeName(fqdn).equals(name.getAsString())
                    && data != null && data.isJsonPrimitive() && value.equals(data.getAsString())
                    && id != null && !id.isJsonNull()) {
                return id.getAsString();
            }
        }
        return null;
    }

    private JsonObject request(String method, String path, @Nullable String body) throws AcmeDnsException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + apiKey)
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
            throw new AcmeDnsException("Vultr request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AcmeDnsException("Vultr request was interrupted", e);
        }
        JsonObject json = parse(response.body());
        if (response.statusCode() / 100 != 2) {
            throw new AcmeDnsException("Vultr " + method + " " + path + " failed ("
                    + response.statusCode() + "): " + errorMessage(json));
        }
        return json;
    }

    private static JsonObject parse(String body) throws AcmeDnsException {
        try {
            JsonElement tree = JsonParser.parseString(body == null ? "" : body);
            return tree.isJsonObject() ? tree.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            throw new AcmeDnsException("Vultr returned a non-JSON response", e);
        }
    }

    private static String errorMessage(JsonObject json) {
        JsonElement message = json.get("message");
        return message != null && !message.isJsonNull() ? message.getAsString() : "unknown error";
    }

    private static String key(String fqdn, String value) {
        return fqdn + "\n" + value;
    }

    @Override
    public void test() throws AcmeDnsException {
        request("GET", "/v2/domains?per_page=1", null);
    }
}
