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
package org.jackhuang.hmcl.web.minecraft;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// JDK download sources: named origins that can serve the *bytes* of a
/// resolved release.
///
/// A mirror never changes what gets installed. The catalogue — version, size
/// and the SHA-256 the download is verified against — always comes from the
/// official API ([McJavaRuntimes] keeps using `api.adoptium.net` /
/// `api.foojay.io`). A source here only rewrites the package URL so the same
/// bytes can come from a domestic mirror instead of GitHub or
/// `download.java.net`.
///
/// Every entry below was verified by hand: the URL answers real archive bytes
/// (gzip / zip magic), and a missing file answers 404 instead of a HTML page.
/// Huawei's `/adoptium/` path is *not* an Adoptium mirror (every URL answers
/// 200 with the mirror portal's HTML), so it is deliberately absent.
@NotNullByDefault
public final class McJavaSources {

    /// One origin. `host` is informational (shown in the UI and logs).
    public record Source(String id, String label, @Nullable String host,
                         List<McJavaRuntimes.Vendor> vendors) {

        public boolean serves(McJavaRuntimes.Vendor vendor) {
            return vendors.contains(vendor);
        }
    }

    /// 清华大学 TUNA 镜像站，同步 Adoptium / Temurin 的发布包。
    public static final Source TUNA_ADOPTIUM = new Source(
            "tuna-adoptium", "清华 TUNA 镜像（国内）",
            "mirrors.tuna.tsinghua.edu.cn",
            List.of(McJavaRuntimes.Vendor.TEMURIN));

    /// 华为云镜像站，同步 OpenJDK 上游的 GA 参考构建。
    public static final Source HUAWEI_OPENJDK = new Source(
            "huawei-openjdk", "华为云镜像（国内）",
            "mirrors.huaweicloud.com",
            List.of(McJavaRuntimes.Vendor.OPENJDK));

    /// The distribution's own host (GitHub releases, download.java.net, …).
    /// Always present in a candidate list: when the network can reach it, no
    /// third party is involved.
    public static final Source OFFICIAL = new Source(
            "official", "官方源", null,
            List.of(McJavaRuntimes.Vendor.values()));

    private static final List<Source> ALL = List.of(TUNA_ADOPTIUM, HUAWEI_OPENJDK, OFFICIAL);

    /// Every source that can serve `vendor`, domestic mirrors first.
    public static List<Source> candidates(McJavaRuntimes.Vendor vendor) {
        List<Source> result = new ArrayList<>();
        for (Source source : ALL) {
            if (source != OFFICIAL && source.serves(vendor)) {
                result.add(source);
            }
        }
        result.add(OFFICIAL);
        return result;
    }

    public static @Nullable Source byId(@Nullable String id) {
        if (id == null) {
            return null;
        }
        for (Source source : ALL) {
            if (source.id().equals(id)) {
                return source;
            }
        }
        return null;
    }

    /// Rewrites an official package URL for `source`. Returns `null` when the
    /// source cannot serve it (wrong vendor, URL from a different host family,
    /// or an os/arch the mirror does not carry) — callers then keep the
    /// official URL. Never returns the input unchanged for a mirror.
    public static @Nullable String rewrite(Source source, String officialUrl) {
        if (source == OFFICIAL) {
            return officialUrl;
        }
        if (source == TUNA_ADOPTIUM) {
            return rewriteTuna(officialUrl);
        }
        if (source == HUAWEI_OPENJDK) {
            return rewriteHuawei(officialUrl);
        }
        return null;
    }

    /// `https://github.com/adoptium/temurin{N}-binaries/releases/download/{tag}/{file}`
    /// → `https://mirrors.tuna.tsinghua.edu.cn/Adoptium/{N}/{image}/{arch}/{os}/{file}`.
    /// The filename carries `image_arch_os`, which is exactly the mirror's
    /// directory layout, so no table is needed for those three parts.
    private static @Nullable String rewriteTuna(String url) {
        Matcher matcher = Pattern.compile(
                        "https://github\\.com/adoptium/temurin(\\d+)-binaries/releases/download/[^/]+/([^/]+)$")
                .matcher(url);
        if (!matcher.find()) {
            return null;
        }
        int feature;
        try {
            feature = Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
        String file = matcher.group(2);
        Matcher name = Pattern.compile(
                        "OpenJDK\\d+U-(jdk|jre)_([a-z0-9]+)_([a-z0-9-]+)_hotspot_[0-9][0-9._+]*\\.(?:tar\\.gz|zip)")
                .matcher(file);
        if (!name.matches()) {
            return null;
        }
        String image = name.group(1);
        String arch = name.group(2);
        String os = name.group(3);
        if (!arch.equals("x64") && !arch.equals("aarch64")) {
            return null;
        }
        String mirrorOs = switch (os) {
            case "linux", "windows", "mac" -> os;
            default -> null;
        };
        if (mirrorOs == null) {
            return null;
        }
        return "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/" + feature + "/" + image
                + "/" + arch + "/" + mirrorOs + "/" + file;
    }

    /// `https://download.java.net/.../openjdk-{version}_{os}-{arch}_bin.{ext}`
    /// → `https://mirrors.huaweicloud.com/openjdk/{version}/openjdk-{version}_{os}-{arch}_bin.{ext}`.
    /// Only the GA naming (`openjdk-25.0.2_linux-x64_bin.tar.gz`) is handled;
    /// early-access names do not appear here because the catalogue asks for GA.
    private static @Nullable String rewriteHuawei(String url) {
        Matcher matcher = Pattern.compile(
                        "https://download\\.java\\.net/\\S+/(openjdk-(\\d[0-9.]*))_([a-z]+)-([a-z0-9]+)_bin\\.(tar\\.gz|zip)$")
                .matcher(url);
        if (!matcher.find()) {
            return null;
        }
        String version = matcher.group(2);
        String os = matcher.group(3);
        String arch = matcher.group(4);
        String ext = matcher.group(5);
        if (!os.equals("linux") && !os.equals("macos") && !os.equals("windows")) {
            return null;
        }
        if (!arch.equals("x64") && !arch.equals("aarch64")) {
            return null;
        }
        return "https://mirrors.huaweicloud.com/openjdk/" + version
                + "/openjdk-" + version + "_" + os + "-" + arch + "_bin." + ext;
    }

    private McJavaSources() {
    }
}
