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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/// Tencent Cloud DNSPod (API 3.0, TC3-HMAC-SHA256) DNS-01 writer.
///
/// Credentials: `secretId`, `secretKey` (Tencent Cloud API keys), and `zone`
/// (the registered domain; injected from the certificate's apex at issuance).
/// The key pair is account-scoped: use a CAM sub-user whose policy only allows
/// the DNSPod record actions this panel performs.
@NotNullByDefault
public final class TencentDnsProvider implements AcmeDnsProvider {

    private static final String DEFAULT_BASE = "https://dnspod.tencentcloudapi.com";
    private static final String SERVICE = "dnspod";
    private static final String VERSION = "2021-03-23";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private final String secretId;
    private final String secretKey;
    private final String zone;
    private final HttpClient http;
    private final String base;
    private final Map<String, String> recordIds = new ConcurrentHashMap<>();

    public TencentDnsProvider(String secretId, String secretKey, String zone) {
        this(secretId, secretKey, zone,
                HttpClient.newBuilder().connectTimeout(TIMEOUT).build(), DEFAULT_BASE);
    }

    TencentDnsProvider(String secretId, String secretKey, String zone,
                       HttpClient http, String base) {
        this.secretId = secretId;
        this.secretKey = secretKey;
        this.zone = zone == null ? "" : zone.trim();
        this.http = http;
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    @Override
    public String id() {
        return "tencent";
    }

    @Override
    public void test() throws AcmeDnsException {
        JsonObject payload = new JsonObject();
        payload.addProperty("Offset", 0);
        payload.addProperty("Limit", 1);
        call("DescribeDomainList", payload);
    }

    @Override
    public void present(String fqdn, String value) throws AcmeDnsException {
        requireZone();
        JsonObject payload = new JsonObject();
        payload.addProperty("Domain", zone);
        payload.addProperty("SubDomain", relativeName(fqdn));
        payload.addProperty("RecordType", "TXT");
        payload.addProperty("RecordLine", "默认");
        payload.addProperty("Value", value);
        payload.addProperty("TTL", 600);
        JsonObject answer = call("CreateRecord", payload);
        JsonElement recordId = answer.get("RecordId");
        if (recordId == null || recordId.isJsonNull()) {
            throw new AcmeDnsException("Tencent DNSPod did not return the created record id");
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
        JsonObject payload = new JsonObject();
        payload.addProperty("Domain", zone);
        try {
            payload.addProperty("RecordId", Long.parseLong(recordId));
        } catch (NumberFormatException e) {
            payload.addProperty("RecordId", recordId);
        }
        try {
            call("DeleteRecord", payload);
        } catch (AcmeDnsException e) {
            String message = e.getMessage();
            if (!message.contains("ResourceNotFound") && !message.contains("记录不存在")
                    && !message.contains("not exist")) {
                throw e;
            }
        }
    }

    private void requireZone() throws AcmeDnsException {
        if (zone.isBlank()) {
            throw new AcmeDnsException("Tencent DNSPod requires the `zone` credential (e.g. example.com)");
        }
    }

    private String relativeName(String fqdn) {
        if (fqdn.equals(zone)) {
            return "@";
        }
        String suffix = "." + zone;
        return fqdn.endsWith(suffix) ? fqdn.substring(0, fqdn.length() - suffix.length()) : fqdn;
    }

    private @Nullable String findRecordId(String fqdn, String value) throws AcmeDnsException {
        JsonObject payload = new JsonObject();
        payload.addProperty("Domain", zone);
        payload.addProperty("SubDomain", relativeName(fqdn));
        payload.addProperty("RecordType", "TXT");
        JsonObject answer = call("DescribeRecordList", payload);
        JsonElement records = answer.get("RecordList");
        if (records == null || !records.isJsonArray()) {
            return null;
        }
        for (JsonElement element : records.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject record = element.getAsJsonObject();
            JsonElement recordValue = record.get("Value");
            JsonElement recordId = record.get("RecordId");
            if (recordValue != null && recordValue.isJsonPrimitive()
                    && value.equals(recordValue.getAsString())
                    && recordId != null && !recordId.isJsonNull()) {
                return recordId.getAsString();
            }
        }
        return null;
    }

    private static String key(String fqdn, String value) {
        return fqdn + "\u0000" + value;
    }

    private JsonObject call(String action, JsonObject payload) throws AcmeDnsException {
        long timestamp = Instant.now().getEpochSecond();
        String date = DATE.format(Instant.ofEpochSecond(timestamp));
        String body = payload.toString();
        String host = signedHost();
        String contentType = "application/json; charset=utf-8";
        String canonicalHeaders = "content-type:" + contentType + "\nhost:" + host + "\n";
        String signedHeaders = "content-type;host";
        String canonicalRequest = "POST\n/\n\n" + canonicalHeaders + "\n" + signedHeaders + "\n"
                + sha256Hex(body);
        String scope = date + "/" + SERVICE + "/tc3_request";
        String stringToSign = "TC3-HMAC-SHA256\n" + timestamp + "\n" + scope + "\n"
                + sha256Hex(canonicalRequest);
        byte[] secretDate = hmac(("TC3" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        byte[] secretService = hmac(secretDate, SERVICE);
        byte[] secretSigning = hmac(secretService, "tc3_request");
        String signature = hex(hmac(secretSigning, stringToSign));
        String authorization = "TC3-HMAC-SHA256 Credential=" + secretId + "/" + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;

        HttpRequest request = HttpRequest.newBuilder(URI.create(base))
                .timeout(TIMEOUT)
                .header("Authorization", authorization)
                .header("Content-Type", contentType)
                .header("X-TC-Action", action)
                .header("X-TC-Version", VERSION)
                .header("X-TC-Timestamp", Long.toString(timestamp))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new AcmeDnsException("Tencent DNSPod request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AcmeDnsException("Tencent DNSPod request was interrupted", e);
        }
        JsonObject json = parse(response.body());
        JsonElement answerElement = json.get("Response");
        if (response.statusCode() / 100 != 2 || answerElement == null || !answerElement.isJsonObject()) {
            throw new AcmeDnsException("Tencent DNSPod " + action + " failed ("
                    + response.statusCode() + "): " + errorMessage(json));
        }
        JsonObject answer = answerElement.getAsJsonObject();
        JsonElement error = answer.get("Error");
        if (error != null && error.isJsonObject()) {
            JsonObject object = error.getAsJsonObject();
            String code = object.has("Code") ? object.get("Code").getAsString() : "Error";
            String message = object.has("Message") ? object.get("Message").getAsString() : "request failed";
            throw new AcmeDnsException("Tencent DNSPod " + action + " failed ("
                    + code + "): " + message);
        }
        return answer;
    }

    private String signedHost() {
        URI uri = URI.create(base);
        int port = uri.getPort();
        return port == -1 || port == 443 ? uri.getHost() : uri.getHost() + ":" + port;
    }

    private static byte[] hmac(byte[] key, String data) throws AcmeDnsException {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new AcmeDnsException("Tencent DNSPod signature failed", e);
        }
    }

    private static String sha256Hex(String data) throws AcmeDnsException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hex(digest.digest(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new AcmeDnsException("Tencent DNSPod signature failed", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }

    private static JsonObject parse(String body) throws AcmeDnsException {
        try {
            JsonElement tree = JsonParser.parseString(body == null ? "" : body);
            return tree.isJsonObject() ? tree.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            throw new AcmeDnsException("Tencent DNSPod returned a non-JSON response", e);
        }
    }

    private static String errorMessage(JsonObject json) {
        JsonElement response = json.get("Response");
        if (response != null && response.isJsonObject()) {
            JsonElement error = response.getAsJsonObject().get("Error");
            if (error != null && error.isJsonObject()) {
                JsonElement message = error.getAsJsonObject().get("Message");
                if (message != null && !message.isJsonNull()) {
                    return message.getAsString();
                }
            }
        }
        return "request failed";
    }
}
