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
package org.jackhuang.hmcl.web.transcode.runtime;

import org.jackhuang.hmcl.util.platform.Architecture;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jackhuang.hmcl.util.platform.Platform;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/// What the panel can tell about the host before choosing an ffmpeg build.
///
/// This first stage only answers the catalogue's three keys — OS, architecture
/// and libc. Device and encoder discovery is deliberately a later layer: those
/// probes start processes and are expensive, while this answer is cheap and is
/// needed even to show the catalogue.
@NotNullByDefault
public final class FfmpegCapabilities {

    private FfmpegCapabilities() {
    }

    /// The catalogue's platform key.
    public record Host(String os, String arch, String libc) {
        public Host {
            os = os.toLowerCase(Locale.ROOT);
            arch = arch.toLowerCase(Locale.ROOT);
            libc = libc.toLowerCase(Locale.ROOT);
        }

        public String describe() {
            return os + "/" + arch + "/" + libc;
        }
    }

    public static Host detect() {
        Platform platform = Platform.SYSTEM_PLATFORM;
        return new Host(
                platform.os().name().toLowerCase(Locale.ROOT),
                archName(platform.arch()),
                libcName(platform.os()));
    }

    private static String archName(Architecture arch) {
        return switch (arch) {
            case X86_64 -> "x64";
            case ARM64 -> "arm64";
            case X86 -> "x86";
            case ARM32 -> "arm32";
            default -> arch.name().toLowerCase(Locale.ROOT);
        };
    }

    private static String libcName(OperatingSystem os) {
        if (os == OperatingSystem.WINDOWS || os == OperatingSystem.MACOS) {
            return "none";
        }
        if (os == OperatingSystem.FREEBSD) {
            return "libc";
        }
        // Alpine and other musl distributions advertise it with a loader. The
        // glob is checked before shelling out to `ldd --version`: it is cheaper,
        // and it does not need a PATH that a bare image may not have.
        for (String loader : new String[]{
                "/lib/ld-musl-x86_64.so.1",
                "/lib/ld-musl-aarch64.so.1",
                "/lib/ld-musl-armhf.so.1",
                "/lib/ld-musl-i386.so.1"}) {
            if (Files.isRegularFile(Path.of(loader))) {
                return "musl";
            }
        }
        if (Files.isRegularFile(Path.of("/etc/alpine-release"))) {
            return "musl";
        }
        return "glibc";
    }
}