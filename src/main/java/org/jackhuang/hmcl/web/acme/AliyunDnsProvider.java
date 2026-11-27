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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/// Aliyun DNS (AliDNS) DNS-01 writer.
///
/// Credentials: `accessKeyId`, `accessKeySecret`, `zone` (the registered
/// domain, e.g. `example.com`; required). The AccessKey should be a RAM user
/// limited to `AliyunDNSFullAccess` or a narrower policy for the zone.
///
/// AliDNS uses the legacy RPC signature (HMAC-SHA1, sorted query). The
/// implementation keeps the canonical query construction in one place so the
/// test can pin it.
@NotNullByDefault
public final class AliyunDnsProvider implements AcmeDnsProvider {

    private static final String DEFAULT_BASE = "https://alidns.aliyuncs.com/";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private final String accessKeyId;
    private final String accessKeySecret;
    private final String zone;
    private final HttpClient http;
    private final String base;
    private final Map<String, String> recordIds = new ConcurrentHashMap<>();

    public AliyunDnsProvider(String accessKeyId, String accessKeySecret, String zone) {
        this(accessKeyId, accessKeySecret, zone,
                HttpClient.newBuilder().connectTimeout(TIMEOUT).build(), DEFAULT_BASE);
    }

    AliyunDnsProvider(String accessKeyId, String accessKeySecret, String zone,
                      HttpClient http, String base) {
        this.accessKeyId = accessKeyId;
        this.accessKeySecret = accessKeySecret;
        this.zone = zone == null ? "" : stripDot(zone.trim());
        this.http = http;
        this.base = base.endsWith("/") ? base : base + "/";
    }

    @Override
    public String id() {
        return "aliyun";
    }

    @Override
    public void present(String fqdn, String value) throws AcmeDnsException {
        requireZone();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("Action", "AddDomainRecord");
        params.put("DomainName", zone);
        params.put("RR", relativeName(fqdn));
        params.put("Type", "TXT");
        params.put("Value", value);
        params.put("TTL", "600");

        JsonObject answer = call(params);
        JsonElement id = answer.get("RecordId");
        if (id == null || id.isJsonNull()) {
            throw new AcmeDnsException("AliDNS did not return a record id");
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
        Map<String, String> params = new LinkedHashMap<>();
        params.put("Action", "DeleteDomainRecord");
        params.put("RecordId", recordId);
        try {
            call(params);
        } catch (AcmeDnsException e) {
            if (!e.getMessage().contains("DomainRecordNotBelongToUser")
                    && !e.getMessage().contains("RecordNotFound")) {
                throw e;
            }
        }
    }

    private void requireZone() throws AcmeDnsException {
        if (zone.isBlank()) {
            throw new AcmeDnsException("AliDNS requires the `zone` credential (e.g. example.com)");
        }
    }

    /// AliDNS wants the part left of the zone (`_acme-challenge`), not a full
    /// name — and it rejects a trailing dot outright (`SubDomainInvalid.RR`),
    /// which is why the name is normalized here as well as in the ACME client.
    private String relativeName(String fqdn) {
        String name = stripDot(fqdn);
        if (name.equals(zone)) {
            return "@";
        }
        String suffix = "." + zone;
        return name.endsWith(suffix) ? name.substring(0, name.length() - suffix.length()) : name;
    }

    private static String stripDot(String value) {
        return value.endsWith(".") ? value.substring(0, value.length() - 1) : value;
    }

    private @Nullable String findRecordId(String fqdn, String value) throws AcmeDnsException {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("Action", "DescribeDomainRecords");
        params.put("DomainName", zone);
        params.put("RRKeyWord", relativeName(fqdn));
        params.put("Type", "TXT");
        params.put("ValueKeyWord", value);
        JsonObject answer = call(params);
        JsonElement records = answer.get("DomainRecords");
        if (records == null || !records.isJsonObject()) {
            return null;
        }
        JsonElement record = records.getAsJsonObject().get("Record");
        if (record == null || !record.isJsonArray()) {
            return null;
        }
        JsonArray array = record.getAsJsonArray();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            JsonElement id = object.get("RecordId");
            if (id != null && !id.isJsonNull()) {
                return id.getAsString();
            }
        }
        return null;
    }

    private JsonObject call(Map<String, String> actionParams) throws AcmeDnsException {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("Format", "JSON");
        params.put("Version", "2015-01-09");
        params.put("AccessKeyId", accessKeyId);
        params.put("SignatureMethod", "HMAC-SHA1");
        params.put("Timestamp", TIMESTAMP.format(Instant.now()));
        params.put("SignatureVersion", "1.0");
        params.put("SignatureNonce", UUID.randomUUID().toString());
        params.putAll(actionParams);

        String canonical = canonicalQuery(params);
        String stringToSign = "GET&%2F&" + percentEncode(canonical);
        params.put("Signature", signature(stringToSign));

        String query = canonicalQuery(params);
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "?" + query))
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new AcmeDnsException("AliDNS request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AcmeDnsException("AliDNS request was interrupted", e);
        }
        JsonObject json = parse(response.body());
        if (response.statusCode() / 100 != 2 || json.has("Code")) {
            String code = json.has("Code") ? json.get("Code").getAsString() : String.valueOf(response.statusCode());
            String message = json.has("Message") ? json.get("Message").getAsString() : "request failed";
            throw new AcmeDnsException("AliDNS " + actionParams.get("Action") + " failed ("
                    + code + "): " + message);
        }
        return json;
    }

    private String signature(String stringToSign) throws AcmeDnsException {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec((accessKeySecret + "&").getBytes(StandardCharsets.UTF_8),
                    "HmacSHA1"));
            return Base64.getEncoder().encodeToString(
                    mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new AcmeDnsException("AliDNS signature failed", e);
        }
    }

    /// Sorted `key=value` pairs, each side RFC 3986 percent-encoded.
    static String canonicalQuery(Map<String, String> params) {
        List<Map.Entry<String, String>> entries = new ArrayList<>(params.entrySet());
        entries.sort(Comparator.comparing(Map.Entry::getKey));
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : entries) {
            if (builder.length() > 0) {
                builder.append('&');
            }
            builder.append(percentEncode(entry.getKey()))
                    .append('=')
                    .append(percentEncode(entry.getValue()));
        }
        return builder.toString();
    }

    /// RFC 3986 percent-encoding with the AliDNS quirks (space is %20, `*`
    /// is %2A, `~` stays literal).
    static String percentEncode(String value) {
        String encoded = URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
        return encoded;
    }

    private static JsonObject parse(String body) throws AcmeDnsException {
        try {
            JsonElement tree = JsonParser.parseString(body == null ? "" : body);
            return tree.isJsonObject() ? tree.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            throw new AcmeDnsException("AliDNS returned a non-JSON response", e);
        }
    }

    private static String key(String fqdn, String value) {
        return fqdn + "\n" + value;
    }

    @Override
    public void test() throws AcmeDnsException {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("Action", "DescribeDomains");
        params.put("PageSize", "1");
        call(params);
    }
}
