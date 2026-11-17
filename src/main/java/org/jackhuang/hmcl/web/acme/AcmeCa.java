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
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/// The ACME certificate authorities this build knows how to talk to.
///
/// The id is what `https.acme.ca` stores; the directory URL is what acme4j
/// uses. `custom` is the escape hatch for Pebble, step-ca and enterprise
/// directories, and requires the operator to fill `directory_url`.
///
/// ACME only issues **DV** certificates. OV/EV certificates still need a
/// commercial CA and manual identity validation; the display names here are
/// CA brands, not a promise of a validation class.
@NotNullByDefault
public final class AcmeCa {

    /// One known CA. `directoryUrl` is empty only for `custom`.
    public record Info(String id, String displayName, String directoryUrl,
                       String stagingDirectoryUrl, boolean requiresEab,
                       boolean supportsWildcard, String termsUrl, String rootHint) {
        public boolean custom() {
            return id.equals("custom");
        }
    }

    private static final List<Info> CAS = List.of(
            new Info("letsencrypt", "Let's Encrypt",
                    "https://acme-v02.api.letsencrypt.org/directory",
                    "https://acme-staging-v02.api.letsencrypt.org/directory",
                    false, true,
                    "https://letsencrypt.org/repository/",
                    "ISRG Root X1；Firefox 115 已内置信任"),
            new Info("letsencrypt-staging", "Let's Encrypt Staging",
                    "https://acme-staging-v02.api.letsencrypt.org/directory",
                    null,
                    false, true,
                    "https://letsencrypt.org/repository/",
                    "测试证书，浏览器不信任；不要用于生产"),
            new Info("zerossl", "ZeroSSL",
                    "https://acme.zerossl.com/v2/DV90",
                    null,
                    true, true,
                    "https://zerossl.com/terms/",
                    "USERTrust 根；Firefox 115 通常已信任"),
            new Info("google", "Google Trust Services",
                    "https://dv.acme-v02.api.pki.goog/directory",
                    null,
                    true, true,
                    "https://pki.goog/repository/",
                    "GTS Root；请确认 Firefox 115 的 NSS 版本信任"),
            new Info("buypass", "Buypass Go",
                    "https://api.buypass.com/acme/directory",
                    null,
                    false, true,
                    "https://www.buypass.com/ssl/resources/acme-terms",
                    "Buypass 根；服务可用性以 CA 公告为准"),
            new Info("harica", "HARICA",
                    "https://acme-v02.api.harica.gr/directory",
                    null,
                    true, true,
                    "https://www.harica.gr/en/Products/SSL",
                    "HARICA 根；通常需要机构/个人验证"),
            new Info("sslcom", "SSL.com",
                    "https://acme.ssl.com/sslcom-dv-rsa",
                    null,
                    true, true,
                    "https://www.ssl.com/legal/terms/",
                    "SSL.com 根；请确认 Firefox 115 信任"),
            new Info("custom", "自定义 ACME 目录",
                    "", null,
                    false, true,
                    "",
                    "Pebble / step-ca / 企业 ACME；根证书由你自行保证")
    );

    private AcmeCa() {
    }

    public static List<Info> all() {
        return CAS;
    }

    public static @Nullable Info find(@Nullable String id) {
        if (id == null) {
            return null;
        }
        String wanted = id.trim().toLowerCase(Locale.ROOT);
        for (Info ca : CAS) {
            if (ca.id().equals(wanted)) {
                return ca;
            }
        }
        return null;
    }

    /// The directory URL for a configured CA, honoring the explicit override
    /// and the Let's Encrypt staging switch.
    ///
    /// @throws IllegalArgumentException when the id is unknown or a custom CA
    ///                                  has no directory URL
    public static String directory(@Nullable String id, @Nullable String override,
                                   boolean useStaging) {
        Info ca = find(id);
        if (ca == null) {
            throw new IllegalArgumentException("unknown ACME CA: " + id);
        }
        if (override != null && !override.isBlank()) {
            return override.trim();
        }
        if (ca.custom()) {
            throw new IllegalArgumentException("ca=custom requires directory_url");
        }
        if (useStaging && ca.stagingDirectoryUrl() != null) {
            return ca.stagingDirectoryUrl();
        }
        return ca.directoryUrl();
    }
}
