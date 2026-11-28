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
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// What the panel can tell about the host before choosing an ffmpeg build.
///
/// This stage answers the catalogue's three platform keys — OS, architecture
/// and libc — plus the libc version used by `minGlibc`. Device and encoder
/// discovery is deliberately a later layer: those probes start processes and
/// are expensive, while this answer is cheap and is needed even to show the
/// catalogue.
@NotNullByDefault
public final class FfmpegCapabilities {

    private static final Pattern DOTTED_VERSION = Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+)?)");
    private static final Pattern NUMERIC_VERSION = Pattern.compile("\\d+(?:\\.\\d+)*");

    private FfmpegCapabilities() {
    }

    /// The catalogue's platform key.
    ///
    /// `libcVersion` is the glibc/musl version (`2.36`, `1.2.5`), or an empty
    /// string when it does not apply (Windows, macOS) or could not be parsed.
    /// It is deliberately never guessed from the distribution name.
    public record Host(String os, String arch, String libc, String libcVersion) {
        public Host {
            os = os.toLowerCase(Locale.ROOT);
            arch = arch.toLowerCase(Locale.ROOT);
            libc = libc.toLowerCase(Locale.ROOT);
            libcVersion = libcVersion == null ? "" : libcVersion.trim();
        }

        /// Compatibility constructor for callers that do not know the libc
        /// version (old catalogues, tests); it behaves like "unknown".
        public Host(String os, String arch, String libc) {
            this(os, arch, libc, "");
        }

        public String describe() {
            return os + "/" + arch + "/" + libc;
        }

        /// `glibc 2.36` / `musl` / `none`, for selector reasons.
        public String libcSummary() {
            return libcVersion.isEmpty() ? libc : libc + " " + libcVersion;
        }
    }

    public static Host detect() {
        Platform platform = Platform.SYSTEM_PLATFORM;
        String libc = libcName(platform.os());
        return new Host(
                platform.os().name().toLowerCase(Locale.ROOT),
                archName(platform.arch()),
                libc,
                libcVersion(platform.os(), libc));
    }

    /// Checks a catalogue entry's `minGlibc` against the host.
    ///
    /// An entry without `minGlibc` always passes. When the host did not expose
    /// its libc version the entry is **not** recommended: we cannot prove the
    /// binary runs, and guessing is exactly the kind of silent downgrade the
    /// catalogue exists to prevent. The selector reports that reason.
    public static boolean supportsMinGlibc(Host host, @Nullable String minGlibc) {
        String minimum = minGlibc == null ? "" : minGlibc.trim();
        if (minimum.isEmpty()) {
            return true;
        }
        if (!"glibc".equalsIgnoreCase(host.libc())) {
            return false;
        }
        String actual = host.libcVersion();
        if (actual.isEmpty() || !isNumericVersion(actual) || !isNumericVersion(minimum)) {
            return false;
        }
        return compareNumericVersion(actual, minimum) >= 0;
    }

    /// `ldd --version` / `getconf GNU_LIBC_VERSION` / musl output → version.
    ///
    /// The parser is pure so it can be tested without the host's `ldd`; the
    /// process plumbing lives in [#libcVersion].
    static String parseLibcVersion(@Nullable String libc, @Nullable String output) {
        if (output == null || output.isBlank()) {
            return "";
        }
        String normalizedLibc = libc == null ? "" : libc.toLowerCase(Locale.ROOT);
        if ("musl".equals(normalizedLibc)) {
            Matcher version = Pattern.compile("(?i)version\\s+(\\d+(?:\\.\\d+)+)").matcher(output);
            if (version.find()) {
                return version.group(1);
            }
        }
        // glibc's first line ends with the authoritative version, e.g.
        // `ldd (Debian GLIBC 2.36-9+deb12u10) 2.36`; taking the last dotted
        // number avoids the package suffix.
        String firstLine = output.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .findFirst()
                .orElse("");
        String version = lastDottedVersion(firstLine);
        if (!version.isEmpty()) {
            return version;
        }
        Matcher named = Pattern.compile("(?i)(?:glibc|version)\\s+(\\d+(?:\\.\\d+)+)").matcher(output);
        return named.find() ? named.group(1) : "";
    }

    private static String libcVersion(OperatingSystem os, String libc) {
        if (os != OperatingSystem.LINUX || (!"glibc".equals(libc) && !"musl".equals(libc))) {
            return "";
        }
        if ("musl".equals(libc)) {
            return parseLibcVersion("musl", commandOutput("ldd", "--version"));
        }
        String version = parseLibcVersion("glibc", commandOutput("ldd", "--version"));
        if (version.isEmpty()) {
            version = parseLibcVersion("glibc", commandOutput("getconf", "GNU_LIBC_VERSION"));
        }
        return version;
    }

    private static String commandOutput(String... command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "";
            }
            try (InputStream in = process.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static String lastDottedVersion(String line) {
        Matcher matcher = DOTTED_VERSION.matcher(line);
        String last = "";
        while (matcher.find()) {
            last = matcher.group(1);
        }
        return last;
    }

    private static boolean isNumericVersion(String value) {
        return NUMERIC_VERSION.matcher(value).matches();
    }

    private static int compareNumericVersion(String left, String right) {
        String[] leftParts = left.split("\\.");
        String[] rightParts = right.split("\\.");
        int length = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < length; i++) {
            int leftPart = i < leftParts.length ? Integer.parseInt(leftParts[i]) : 0;
            int rightPart = i < rightParts.length ? Integer.parseInt(rightParts[i]) : 0;
            int result = Integer.compare(leftPart, rightPart);
            if (result != 0) {
                return result;
            }
        }
        return 0;
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