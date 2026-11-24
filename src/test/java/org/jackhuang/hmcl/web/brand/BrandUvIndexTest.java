/*
 * HDSL
 * Copyright (C) 2026  HDSL contributors
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
package org.jackhuang.hmcl.web.brand;

import org.jackhuang.hmcl.dsh.UvRuntime;
import org.jackhuang.hmcl.util.platform.Architecture;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public class BrandUvIndexTest {

    private static String wheelForThisPlatform() {
        boolean arm = Architecture.CURRENT_ARCH == Architecture.ARM64;
        return switch (OperatingSystem.CURRENT_OS) {
            case WINDOWS -> "uv-" + UvRuntime.version() + "-py3-none-"
                    + (arm ? "win_arm64" : "win_amd64") + ".whl";
            case MACOS -> "uv-" + UvRuntime.version() + "-py3-none-macosx_11_0_"
                    + (arm ? "arm64" : "x86_64") + ".whl";
            default -> "uv-" + UvRuntime.version() + "-py3-none-manylinux_2_17_"
                    + (arm ? "aarch64" : "x86_64") + ".manylinux2014_"
                    + (arm ? "aarch64" : "x86_64") + ".whl";
        };
    }

    @Test
    public void fragmentDigestIsKept() {
        String name = wheelForThisPlatform();
        String sha = "a".repeat(64);
        BrandInstaller.UvWheelLink link = BrandInstaller.uvWheelLink(
                "https://mirror.example/simple/uv/",
                "../../packages/ab/cd/" + name + "#sha256=" + sha);
        assertNotNull(link);
        assertEquals(sha, link.sha256());
        assertEquals("https://mirror.example/packages/ab/cd/" + name, link.url());
    }

    @Test
    public void missingDigestStaysNull() {
        BrandInstaller.UvWheelLink link = BrandInstaller.uvWheelLink(
                "https://mirror.example/simple/uv/", "./" + wheelForThisPlatform());
        assertNotNull(link);
        assertNull(link.sha256());
    }

    @Test
    public void otherVersionsAndPlatformsAreIgnored() {
        assertNull(BrandInstaller.uvWheelLink("https://m/simple/uv/",
                "uv-0.0.1-py3-none-any.whl#sha256=" + "b".repeat(64)));
        assertNull(BrandInstaller.uvWheelLink("https://m/simple/uv/",
                "uv-" + UvRuntime.version() + "-py3-none-any.whl"));
        assertNull(BrandInstaller.uvWheelLink("https://m/simple/uv/",
                "../../packages/x/y/requests-2.0.0-py3-none-any.whl"));
    }

    @Test
    public void plainFragmentIsDroppedWithoutADigest() {
        BrandInstaller.UvWheelLink link = BrandInstaller.uvWheelLink(
                "https://m/simple/uv/", wheelForThisPlatform() + "#egg=uv");
        assertNotNull(link);
        assertNull(link.sha256());
    }
}
