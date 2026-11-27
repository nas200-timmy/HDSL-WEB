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
                    "不用在这里填：签发时会自动使用证书的主域名（zone）。"
                            + "Cloudflare 可留空，由面板自动探测。"),
            Map.entry("zoneId", "Route 53 的 Hosted Zone ID，形如 Z1D633PJN98FT9。"),
            Map.entry("region", "Route 53 区域，通常填 us-east-1。"),
            Map.entry("apiToken", "建议只授予 DNS 记录编辑权限，不要用全局 Key。"),
            Map.entry("accessKeyId", "建议使用只允许改 DNS 记录的子账号。"),
            Map.entry("server", "支持 RFC 2136 的 DNS 服务器地址，如 192.0.2.53。"),
            Map.entry("tsigName", "TSIG 密钥名，通常形如 key.example.com。"),
            Map.entry("subdomain", "acme-dns 分配给你的子域名。"),
            Map.entry("apiUrl", "acme-dns 服务地址，如 https://auth.acme-dns.io。"));

    /// Per-provider "where do I get this and what is the least privilege"
    /// paragraph. Kept next to the registry so the settings page can render it
    /// without another source of truth.
    private static final Map<String, String> PROVIDER_HINTS = Map.ofEntries(
            Map.entry("cloudflare",
                    "Cloudflare 控制台 → My Profile → API Tokens → Create Token；"
                            + "权限只需 Zone → DNS → Edit，Zone Resources 限定到要签发的域名。"
                            + "不要用 Global API Key（账号级权限）。"),
            Map.entry("aliyun",
                    "阿里云 RAM 控制台 → 用户 → 创建子用户并勾选「编程访问」，"
                            + "只授予 AliyunDNSFullAccess（或自定义只含 DNS 解析读写的策略）；"
                            + "不要用主账号 AccessKey。"),
            Map.entry("dnspod",
                    "DNSPod 控制台 → 用户中心 → 密钥管理 → 创建 Token（格式 id,token）。"
                            + "这不是腾讯云 SecretId/SecretKey；腾讯云密钥请选"
                            + "「腾讯云 DNSPod（API 3.0）」。"),
            Map.entry("tencent",
                    "腾讯云控制台 → 访问管理 → API 密钥管理 → 新建密钥；"
                            + "建议绑定只允许 DNSPod 域名解析读写的 CAM 子用户策略，"
                            + "不要用主账号密钥；测试凭证会调用 DescribeDomainList。"),
            Map.entry("huawei",
                    "华为云控制台 → 我的凭证 → 访问密钥；建议创建只授权 DNS 的 IAM 子用户。"),
            Map.entry("route53",
                    "AWS IAM → 用户 → 安全凭据 → 创建访问密钥；策略只授予 Route 53 所需的"
                            + " ChangeResourceRecordSets / ListHostedZones / ListResourceRecordSets。"),
            Map.entry("godaddy",
                    "GoDaddy 开发者后台 → API Keys；只有 Production Key 能修改正式域名解析。"),
            Map.entry("namecheap",
                    "Namecheap → Profile → Tools → API Access；必须把面板的公网出口 IP 加入"
                            + " Whitelisted IPs，否则一律拒绝。"),
            Map.entry("digitalocean",
                    "DigitalOcean 控制台 → API → Tokens → Generate New Token；"
                            + "可只给 DNS 读写范围。"),
            Map.entry("vultr",
                    "Vultr 控制台 → Account → API Keys；建议为本面板单独创建一把 Key。"),
            Map.entry("gandi",
                    "Gandi 控制台 → 账户设置 → Personal Access Token；权限限定到 DNS。"),
            Map.entry("porkbun",
                    "Porkbun 控制台 → API Access；先开启 API Access，再创建 API Key/Secret。"),
            Map.entry("rfc2136",
                    "在 DNS 服务器上创建只允许更新 _acme-challenge 记录的 TSIG key；"
                            + "不要复用管理员 key。"),
            Map.entry("acme-dns",
                    "acme-dns 服务注册后得到 username/password；它只处理 ACME challenge，"
                            + "权限天然很小。"));

    public record Info(String id, String label, String group, boolean implemented,
                       List<Field> fields) {
        /// One paragraph shown above the credential fields: which credential
        /// type this provider expects, where to create it, and the minimum
        /// permission. Empty for providers without a written guide.
        public String credentialHint() {
            return PROVIDER_HINTS.getOrDefault(id, "");
        }
    }

    private static final List<Info> PROVIDERS = List.of(
            new Info("cloudflare", "Cloudflare", "Tier 1", true, List.of(
                    new Field("apiToken", "API Token", true, true),
                    new Field("zone", "Zone（由证书决定，可留空）", false, false))),
            new Info("aliyun", "阿里云 DNS", "Tier 1", true, List.of(
                    new Field("accessKeyId", "AccessKey ID", false, true),
                    new Field("accessKeySecret", "AccessKey Secret", true, true),
                    new Field("zone", "Zone（由证书决定）", false, false))),
            new Info("dnspod", "DNSPod（旧版 Token）", "Tier 1", true, List.of(
                    new Field("token", "API Token", true, true),
                    new Field("zone", "Zone（由证书决定）", false, false))),
            new Info("tencent", "腾讯云 DNSPod（API 3.0）", "Tier 1", true, List.of(
                    new Field("secretId", "SecretId", false, true),
                    new Field("secretKey", "SecretKey", true, true))),
            new Info("huawei", "华为云 DNS", "Tier 1", false, List.of(
                    new Field("accessKeyId", "AccessKey ID", false, true),
                    new Field("accessKeySecret", "AccessKey Secret", true, true),
                    new Field("zone", "Zone（由证书决定）", false, false))),
            new Info("route53", "AWS Route 53", "Tier 2", false, List.of(
                    new Field("accessKeyId", "Access Key ID", false, true),
                    new Field("secretAccessKey", "Secret Access Key", true, true),
                    new Field("region", "Region", false, true),
                    new Field("zoneId", "Hosted Zone ID", false, true))),
            new Info("godaddy", "GoDaddy", "Tier 2", true, List.of(
                    new Field("apiKey", "API Key", true, true),
                    new Field("apiSecret", "API Secret", true, true),
                    new Field("zone", "Zone（由证书决定）", false, false))),
            new Info("namecheap", "Namecheap", "Tier 2", false, List.of(
                    new Field("apiUser", "API User", false, true),
                    new Field("apiKey", "API Key", true, true),
                    new Field("username", "Username", false, true))),
            new Info("digitalocean", "DigitalOcean", "Tier 2", true, List.of(
                    new Field("token", "Personal Access Token", true, true),
                    new Field("zone", "Zone（由证书决定）", false, false))),
            new Info("vultr", "Vultr", "Tier 2", true, List.of(
                    new Field("apiKey", "API Key", true, true),
                    new Field("zone", "Zone（由证书决定）", false, false))),
            new Info("gandi", "Gandi", "Tier 3", false, List.of(
                    new Field("token", "Personal Access Token", true, true))),
            new Info("porkbun", "Porkbun", "Tier 3", false, List.of(
                    new Field("apiKey", "API Key", true, true),
                    new Field("secretApiKey", "Secret API Key", true, true))),
            new Info("rfc2136", "RFC 2136 / nsupdate", "Tier 3", false, List.of(
                    new Field("server", "DNS Server", false, true),
                    new Field("zone", "Zone（由证书决定）", false, false),
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
            case "tencent" -> new TencentDnsProvider(
                    values.get("secretId"), values.get("secretKey"), values.get("zone"));
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
