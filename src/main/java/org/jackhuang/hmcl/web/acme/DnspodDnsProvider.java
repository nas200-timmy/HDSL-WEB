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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/// DNSPod / Tencent Cloud DNS-01 writer.
///
/// Credentials: `token` (DNSPod API token, usually `id,token`) and `zone`
/// (the registered domain, e.g. `example.com`; required).
@NotNullByDefault
public final class DnspodDnsProvider implements AcmeDnsProvider {

    private static final String DEFAULT_BASE = "https://dnsapi.cn/";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final String token;
    private final String zone;
    private final HttpClient http;
    private final String base;
    private final Map<String, String> recordIds = new ConcurrentHashMap<>();
    private volatile @Nullable String domainId;

    public DnspodDnsProvider(String token, String zone) {
        this(token, zone, HttpClient.newBuilder().connectTimeout(TIMEOUT).build(), DEFAULT_BASE);
    }

    DnspodDnsProvider(String token, String zone, HttpClient http, String base) {
        this.token = token;
        this.zone = zone == null ? "" : zone.trim();
        this.http = http;
        this.base = base.endsWith("/") ? base : base + "/";
    }

    @Override
    public String id() {
        return "dnspod";
    }

    @Override
    public void present(String fqdn, String value) throws AcmeDnsException {
        requireZone();
        String id = domainId();
        Map<String, String> form = new LinkedHashMap<>();
        form.put("domain_id", id);
        form.put("sub_domain", relativeName(fqdn));
        form.put("record_type", "TXT");
        form.put("record_line", "默认");
        form.put("value", value);
        form.put("ttl", "600");
        JsonObject answer = call("Record.Create", form);
        JsonElement record = answer.get("record");
        if (record == null || !record.isJsonObject()) {
            throw new AcmeDnsException("DNSPod did not return the created record");
        }
        JsonElement recordId = record.getAsJsonObject().get("id");
        if (recordId == null || recordId.isJsonNull()) {
            throw new AcmeDnsException("DNSPod did not return a record id");
        }
        recordIds.put(key(fqdn, value), recordId.getAsString());
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
        Map<String, String> form = new LinkedHashMap<>();
        form.put("domain_id", domainId());
        form.put("record_id", recordId);
        try {
            call("Record.Remove", form);
        } catch (AcmeDnsException e) {
            if (!e.getMessage().contains("记录不存在")
                    && !e.getMessage().contains("Record not found")) {
                throw e;
            }
        }
    }

    private void requireZone() throws AcmeDnsException {
        if (zone.isBlank()) {
            throw new AcmeDnsException("DNSPod requires the `zone` credential (e.g. example.com)");
        }
    }

    private String domainId() throws AcmeDnsException {
        String cached = domainId;
        if (cached != null) {
            return cached;
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("domain", zone);
        JsonObject answer = call("Domain.Info", form);
        JsonElement domain = answer.get("domain");
        if (domain == null || !domain.isJsonObject()) {
            throw new AcmeDnsException("DNSPod did not return domain information for " + zone);
        }
        JsonElement id = domain.getAsJsonObject().get("id");
        if (id == null || id.isJsonNull()) {
            throw new AcmeDnsException("DNSPod did not return a domain id for " + zone);
        }
        cached = id.getAsString();
        domainId = cached;
        return cached;
    }

    private String relativeName(String fqdn) {
        if (fqdn.equals(zone)) {
            return "@";
        }
        String suffix = "." + zone;
        return fqdn.endsWith(suffix) ? fqdn.substring(0, fqdn.length() - suffix.length()) : fqdn;
    }

    private @Nullable String findRecordId(String fqdn, String value) throws AcmeDnsException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("domain_id", domainId());
        form.put("sub_domain", relativeName(fqdn));
        form.put("record_type", "TXT");
        form.put("keyword", value);
        JsonObject answer = call("Record.List", form);
        JsonElement records = answer.get("records");
        if (records == null || !records.isJsonArray()) {
            return null;
        }
        for (JsonElement element : records.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonElement id = element.getAsJsonObject().get("id");
            if (id != null && !id.isJsonNull()) {
                return id.getAsString();
            }
        }
        return null;
    }

    private JsonObject call(String action, Map<String, String> form) throws AcmeDnsException {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("login_token", token);
        fields.put("format", "json");
        fields.put("lang", "en");
        fields.put("error_on_empty", "no");
        fields.putAll(form);
        String body = encodeForm(fields);
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + action))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new AcmeDnsException("DNSPod request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AcmeDnsException("DNSPod request was interrupted", e);
        }
        JsonObject json = parse(response.body());
        JsonElement status = json.get("status");
        if (response.statusCode() / 100 != 2 || status == null || !status.isJsonObject()) {
            throw new AcmeDnsException("DNSPod " + action + " failed ("
                    + response.statusCode() + "): " + errorMessage(json));
        }
        JsonElement code = status.getAsJsonObject().get("code");
        if (code == null || !code.isJsonPrimitive() || !"1".equals(code.getAsString())) {
            throw new AcmeDnsException("DNSPod " + action + " failed: " + errorMessage(json));
        }
        return json;
    }

    private static String encodeForm(Map<String, String> fields) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (builder.length() > 0) {
                builder.append('&');
            }
            builder.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return builder.toString();
    }

    private static JsonObject parse(String body) throws AcmeDnsException {
        try {
            JsonElement tree = JsonParser.parseString(body == null ? "" : body);
            return tree.isJsonObject() ? tree.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            throw new AcmeDnsException("DNSPod returned a non-JSON response", e);
        }
    }

    private static String errorMessage(JsonObject json) {
        JsonElement status = json.get("status");
        if (status != null && status.isJsonObject()) {
            JsonElement message = status.getAsJsonObject().get("message");
            if (message != null && !message.isJsonNull()) {
                return message.getAsString();
            }
        }
        return "unknown error";
    }

    private static String key(String fqdn, String value) {
        return fqdn + "\n" + value;
    }
}
