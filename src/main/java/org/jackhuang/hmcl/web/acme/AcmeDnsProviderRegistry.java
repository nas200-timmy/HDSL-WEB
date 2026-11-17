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

import org.jetbrains.annotations.NotNullByDefault;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/// The DNS providers the ACME service can drive.
///
/// Only `implemented` entries are selectable in the UI. The rest describe the
/// planned surface ("Tier 2/3") so the settings page and documentation can
/// keep the names stable while the adapters land one by one.
@NotNullByDefault
public final class AcmeDnsProviderRegistry {

    /// One credential field shown by the UI. `secret` fields are write-only.
    /// `hint` is a one-line explanation rendered under the input; empty when
    /// the label already says everything.
    public record Field(String key, String label, boolean secret, boolean required, String hint) {
        public Field(String key, String label, boolean secret, boolean required) {
            this(key, label, secret, required, HINTS.getOrDefault(key, ""));
        }
    }

    /// Keyed by credential key: the same key means the same thing across
    /// providers. Declared before `PROVIDERS` because that list is built with
    /// the constructor above.
    private static final Map<String, String> HINTS = Map.ofEntries(
            Map.entry("zone",
                    "填你在 DNS 服务商托管的主域名，如 example.com；不要填 www.example.com。"
                            + "签发时面板会在它的 _acme-challenge 下自动写 TXT 记录。"
                            + "Cloudflare 可留空，由面板自动探测。"),
            Map.entry("zoneId", "Route 53 的 Hosted Zone ID，形如 Z1D633PJN98FT9。"),
            Map.entry("region", "Route 53 区域，通常填 us-east-1。"),
            Map.entry("apiToken", "建议只授予 DNS 记录编辑权限，不要用全局 Key。"),
            Map.entry("accessKeyId", "建议使用只允许改 DNS 记录的子账号。"),
            Map.entry("server", "支持 RFC 2136 的 DNS 服务器地址，如 192.0.2.53。"),
            Map.entry("tsigName", "TSIG 密钥名，通常形如 key.example.com。"),
            Map.entry("subdomain", "acme-dns 分配给你的子域名。"),
            Map.entry("apiUrl", "acme-dns 服务地址，如 https://auth.acme-dns.io。"));

    public record Info(String id, String label, String group, boolean implemented,
                       List<Field> fields) {
    }

    private static final List<Info> PROVIDERS = List.of(
            new Info("cloudflare", "Cloudflare", "Tier 1", true, List.of(
                    new Field("apiToken", "API Token", true, true),
                    new Field("zone", "Zone（主域名，可留空）", false, false))),
            new Info("aliyun", "阿里云 DNS", "Tier 1", true, List.of(
                    new Field("accessKeyId", "AccessKey ID", false, true),
                    new Field("accessKeySecret", "AccessKey Secret", true, true),
                    new Field("zone", "Zone（主域名）", false, true))),
            new Info("dnspod", "DNSPod / 腾讯云", "Tier 1", true, List.of(
                    new Field("token", "API Token", true, true),
                    new Field("zone", "Zone（主域名）", false, true))),
            new Info("huawei", "华为云 DNS", "Tier 1", false, List.of(
                    new Field("accessKeyId", "AccessKey ID", false, true),
                    new Field("accessKeySecret", "AccessKey Secret", true, true),
                    new Field("zone", "Zone（主域名）", false, true))),
            new Info("route53", "AWS Route 53", "Tier 2", false, List.of(
                    new Field("accessKeyId", "Access Key ID", false, true),
                    new Field("secretAccessKey", "Secret Access Key", true, true),
                    new Field("region", "Region", false, true),
                    new Field("zoneId", "Hosted Zone ID", false, true))),
            new Info("godaddy", "GoDaddy", "Tier 2", true, List.of(
                    new Field("apiKey", "API Key", true, true),
                    new Field("apiSecret", "API Secret", true, true),
                    new Field("zone", "Zone（主域名）", false, true))),
            new Info("namecheap", "Namecheap", "Tier 2", false, List.of(
                    new Field("apiUser", "API User", false, true),
                    new Field("apiKey", "API Key", true, true),
                    new Field("username", "Username", false, true))),
            new Info("digitalocean", "DigitalOcean", "Tier 2", true, List.of(
                    new Field("token", "Personal Access Token", true, true),
                    new Field("zone", "Zone（主域名）", false, true))),
            new Info("vultr", "Vultr", "Tier 2", true, List.of(
                    new Field("apiKey", "API Key", true, true),
                    new Field("zone", "Zone（主域名）", false, true))),
            new Info("gandi", "Gandi", "Tier 3", false, List.of(
                    new Field("token", "Personal Access Token", true, true))),
            new Info("porkbun", "Porkbun", "Tier 3", false, List.of(
                    new Field("apiKey", "API Key", true, true),
                    new Field("secretApiKey", "Secret API Key", true, true))),
            new Info("rfc2136", "RFC 2136 / nsupdate", "Tier 3", false, List.of(
                    new Field("server", "DNS Server", false, true),
                    new Field("zone", "Zone（主域名）", false, true),
                    new Field("tsigName", "TSIG Key Name", false, true),
                    new Field("tsigSecret", "TSIG Secret", true, true))),
            new Info("acme-dns", "acme-dns", "Tier 3", false, List.of(
                    new Field("apiUrl", "API URL", false, true),
                    new Field("username", "Username", false, true),
                    new Field("password", "Password", true, true),
                    new Field("subdomain", "Subdomain", false, true)))
    );

    private AcmeDnsProviderRegistry() {
    }

    public static List<Info> all() {
        return PROVIDERS;
    }

    public static List<Info> implemented() {
        return PROVIDERS.stream().filter(Info::implemented).toList();
    }

    public static Info find(String id) {
        if (id == null) {
            return null;
        }
        String wanted = id.trim().toLowerCase(Locale.ROOT);
        for (Info info : PROVIDERS) {
            if (info.id().equals(wanted)) {
                return info;
            }
        }
        return null;
    }

    /// Builds a provider from the write-only credential map.
    ///
    /// @throws AcmeDnsException when the provider is unknown, not implemented,
    ///                          or a required field is missing
    public static AcmeDnsProvider create(String id, Map<String, String> credentials)
            throws AcmeDnsException {
        Info info = find(id);
        if (info == null) {
            throw new AcmeDnsException("unknown DNS provider: " + id);
        }
        if (!info.implemented()) {
            throw new AcmeDnsException("DNS provider is not implemented yet: " + id);
        }
        Map<String, String> values = new LinkedHashMap<>();
        if (credentials != null) {
            for (Map.Entry<String, String> entry : credentials.entrySet()) {
                values.put(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
            }
        }
        for (Field field : info.fields()) {
            if (field.required() && values.getOrDefault(field.key(), "").isBlank()) {
                throw new AcmeDnsException("DNS provider `" + info.label()
                        + "` is missing `" + field.key() + "`");
            }
        }
        return switch (info.id()) {
            case "cloudflare" -> new CloudflareDnsProvider(
                    values.get("apiToken"), values.get("zone"));
            case "aliyun" -> new AliyunDnsProvider(
                    values.get("accessKeyId"), values.get("accessKeySecret"), values.get("zone"));
            case "dnspod" -> new DnspodDnsProvider(
                    values.get("token"), values.get("zone"));
            case "godaddy" -> new GoDaddyDnsProvider(
                    values.get("apiKey"), values.get("apiSecret"), values.get("zone"));
            case "digitalocean" -> new DigitalOceanDnsProvider(
                    values.get("token"), values.get("zone"));
            case "vultr" -> new VultrDnsProvider(
                    values.get("apiKey"), values.get("zone"));
            default -> throw new AcmeDnsException("DNS provider is not implemented yet: " + id);
        };
    }
}
