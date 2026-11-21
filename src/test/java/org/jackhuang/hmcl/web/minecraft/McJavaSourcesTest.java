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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McJavaSourcesTest {

    @Test
    void rewritesTemurinReleaseUrlsToTuna() {
        assertEquals(
                "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/21/jdk/x64/linux/"
                        + "OpenJDK21U-jdk_x64_linux_hotspot_21.0.13_9.tar.gz",
                McJavaSources.rewrite(McJavaSources.TUNA_ADOPTIUM,
                        "https://github.com/adoptium/temurin21-binaries/releases/download/"
                                + "jdk-21.0.13%2B9/OpenJDK21U-jdk_x64_linux_hotspot_21.0.13_9.tar.gz"));
        assertEquals(
                "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/8/jre/x64/windows/"
                        + "OpenJDK8U-jre_x64_windows_hotspot_8.0.452.1.zip",
                McJavaSources.rewrite(McJavaSources.TUNA_ADOPTIUM,
                        "https://github.com/adoptium/temurin8-binaries/releases/download/"
                                + "jdk8u452-b01/OpenJDK8U-jre_x64_windows_hotspot_8.0.452.1.zip"));
        assertEquals(
                "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/aarch64/mac/"
                        + "OpenJDK17U-jdk_aarch64_mac_hotspot_17.0.13_11.tar.gz",
                McJavaSources.rewrite(McJavaSources.TUNA_ADOPTIUM,
                        "https://github.com/adoptium/temurin17-binaries/releases/download/"
                                + "jdk-17.0.13%2B11/OpenJDK17U-jdk_aarch64_mac_hotspot_17.0.13_11.tar.gz"));
    }

    @Test
    void refusesWhatTunaDoesNotCarry() {
        // Huawei answers 200+HTML for /adoptium/…; an arch TUNA lacks must
        // not produce a rewritten URL either.
        assertNull(McJavaSources.rewrite(McJavaSources.TUNA_ADOPTIUM,
                "https://github.com/adoptium/temurin8-binaries/releases/download/"
                        + "jdk8u452-b01/OpenJDK8U-jdk_arm_linux_hotspot_8u452b01.tar.gz"));
        assertNull(McJavaSources.rewrite(McJavaSources.TUNA_ADOPTIUM,
                "https://github.com/adoptium/temurin21-binaries/releases/download/"
                        + "jdk-21.0.13%2B9/not-a-temurin-name.tar.gz"));
        // Another vendor's GitHub release is not ours to rewrite.
        assertNull(McJavaSources.rewrite(McJavaSources.TUNA_ADOPTIUM,
                "https://github.com/microsoft/openjdk21-binaries/releases/download/"
                        + "jdk-21.0.13/OpenJDK21U-jdk_x64_linux_hotspot_21.0.13.tar.gz"));
    }

    @Test
    void rewritesOpenjdkGaUrlsToHuawei() {
        assertEquals(
                "https://mirrors.huaweicloud.com/openjdk/25.0.2/openjdk-25.0.2_linux-x64_bin.tar.gz",
                McJavaSources.rewrite(McJavaSources.HUAWEI_OPENJDK,
                        "https://download.java.net/java/GA/jdk25.0.2/8c3d0a2b/openjdk-25.0.2_linux-x64_bin.tar.gz"));
        assertEquals(
                "https://mirrors.huaweicloud.com/openjdk/21.0.8/openjdk-21.0.8_windows-x64_bin.zip",
                McJavaSources.rewrite(McJavaSources.HUAWEI_OPENJDK,
                        "https://download.java.net/java/GA/jdk21.0.8/abcd/openjdk-21.0.8_windows-x64_bin.zip"));
        assertEquals(
                "https://mirrors.huaweicloud.com/openjdk/25.0.2/openjdk-25.0.2_macos-aarch64_bin.tar.gz",
                McJavaSources.rewrite(McJavaSources.HUAWEI_OPENJDK,
                        "https://download.java.net/java/GA/jdk25.0.2/xyz/openjdk-25.0.2_macos-aarch64_bin.tar.gz"));
    }

    @Test
    void refusesWhatHuaweiDoesNotCarry() {
        // Early-access naming never appears for GA, and must not rewrite.
        assertNull(McJavaSources.rewrite(McJavaSources.HUAWEI_OPENJDK,
                "https://download.java.net/openjdk/jdk26/ri/openjdk-26-ea%2B10_linux-x64_bin.tar.gz"));
        // aka.ms / GitHub URLs for Microsoft builds have no huawei mirror.
        assertNull(McJavaSources.rewrite(McJavaSources.HUAWEI_OPENJDK,
                "https://aka.ms/download-jdk/microsoft-jdk-21.0.13-linux-x64.tar.gz"));
    }

    @Test
    void officialPassesThroughAndListsArePerVendor() {
        String url = "https://github.com/adoptium/temurin21-binaries/releases/download/x/y.tar.gz";
        assertEquals(url, McJavaSources.rewrite(McJavaSources.OFFICIAL, url));

        assertTrue(McJavaSources.candidates(McJavaRuntimes.Vendor.TEMURIN)
                .contains(McJavaSources.TUNA_ADOPTIUM));
        assertTrue(McJavaSources.candidates(McJavaRuntimes.Vendor.OPENJDK)
                .contains(McJavaSources.HUAWEI_OPENJDK));
        // Microsoft has no domestic mirror: only the official source.
        assertEquals(1, McJavaSources.candidates(McJavaRuntimes.Vendor.MICROSOFT).size());
        assertEquals(McJavaSources.OFFICIAL,
                McJavaSources.candidates(McJavaRuntimes.Vendor.MICROSOFT).get(0));
    }
}
