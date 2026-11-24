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
package org.jackhuang.hmcl.dsh;

import org.jackhuang.hmcl.util.platform.Architecture;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/// The pinned `uv` release and the digest that makes it trustworthy.
///
/// A `uv` executable reaches the panel through three transports: GitHub's
/// `uv-<triple>.tar.gz`, PyPI's `uv-<version>-<platform>.whl`, and a mirror's
/// copy of that same wheel. They are one executable in three wrappers, so the
/// pin is the **executable's** SHA-256 rather than any archive's: one constant
/// per platform covers every transport, and a wheel served by a mirror is
/// trusted for exactly the bytes it contains — no more.
///
/// A platform with no entry here is refused rather than downloaded. That is
/// deliberate: the first link of a bootstrap chain is the trust check, and a
/// link that says "verify unless inconvenient" is not a link.
@NotNullByDefault
public final class UvRuntime {

    /// The `uv` release the panel installs. Changing it requires new digests.
    public static final String VERSION = "0.7.13";

    /// The SHA-256 of the `uv` executable itself, per platform.
    ///
    /// Obtained from PyPI's declared wheel digests and confirmed against the
    /// extracted binary (`uv-0.7.13.data/scripts/uv`), cross-checked against
    /// the same release served by a second index.
    private static final Map<String, String> BINARY_SHA256 = Map.of(
            "linux-x64", "04e7399b45054f5ae4239ed60cd579311daafdd8d43e0e6ac01003436f19eaac",
            "linux-arm64", "da9b140ed024e03d1750b6ec4f9902ee1542bd5e44beb14b7ae336d24610bbf5",
            "macos-x64", "a07b8eadd2c241b408b935c12ff603765c21da06255e64ff8485bf8720e106de",
            "macos-arm64", "ab10743159870e1a6e8eb87197eb9a42f086f21bf3cd840b9cfbd73ab23e08b6",
            "windows-x64", "e004f18282750aebe5c8a1a6dedba544ede1d7adc724e34b97834414222f4ef0",
            "windows-arm64", "162206ef9b8ceabe17af83e601bff5f1d78e0a58085d173e2365eda3b532ed05");

    private UvRuntime() {
    }

    /// `linux-x64`, `windows-arm64`, and so on.
    public static String platformTag() {
        String os = switch (OperatingSystem.CURRENT_OS) {
            case LINUX -> "linux";
            case WINDOWS -> "windows";
            case MACOS -> "macos";
            default -> OperatingSystem.CURRENT_OS.name().toLowerCase(java.util.Locale.ROOT);
        };
        String arch = switch (Architecture.CURRENT_ARCH) {
            case X86_64 -> "x64";
            case ARM64 -> "arm64";
            default -> Architecture.CURRENT_ARCH.name()
                    .toLowerCase(java.util.Locale.ROOT);
        };
        return os + "-" + arch;
    }

    /// The pinned digest for this platform, or `null` when none is pinned.
    public static @Nullable String binarySha256() {
        return binarySha256(platformTag());
    }

    /// The pinned digest for a named platform, or `null`.
    static @Nullable String binarySha256(String tag) {
        return BINARY_SHA256.get(tag);
    }

    /// `uv.exe` on Windows, `uv` everywhere else.
    public static String executableName() {
        return OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS ? "uv.exe" : "uv";
    }

    /// `<toolchains>/uv`, next to the runtime closure and the CPython line.
    public static Path root() {
        return BootstrapRuntimeManager.toolchainsRoot().resolve("uv");
    }

    /// `<toolchains>/uv/<version>/uv`, the verified copy every brand shares.
    public static Path sharedBinary() {
        return root().resolve(VERSION).resolve(executableName());
    }

    /// Whether a wheel file name carries a `uv` build this platform can run.
    ///
    /// The Linux x86_64 wheel is `manylinux`; the aarch64 wheel is tagged for
    /// both glibc and musl, which is fine — it carries one binary that runs on
    /// either loader. A Windows wheel is `win_amd64`, and a macOS one names the
    /// deployment target and the architecture.
    public static boolean matchesWheel(String filename) {
        if (!filename.startsWith("uv-" + VERSION + "-") || !filename.endsWith(".whl")) {
            return false;
        }
        return switch (OperatingSystem.CURRENT_OS) {
            case WINDOWS -> filename.contains(isArm() ? "win_arm64" : "win_amd64");
            case MACOS -> filename.contains("macosx")
                    && filename.contains(isArm() ? "arm64" : "x86_64");
            default -> filename.contains("manylinux")
                    && filename.contains(isArm() ? "aarch64" : "x86_64");
        };
    }

    private static boolean isArm() {
        return Architecture.CURRENT_ARCH == Architecture.ARM64;
    }

    /// The GitHub release triple for this platform, or `null` where GitHub is
    /// not a transport (Windows publishes `.zip` there, not `.tar.gz`).
    public static @Nullable String githubTriple() {
        String arch = isArm() ? "aarch64" : "x86_64";
        return switch (OperatingSystem.CURRENT_OS) {
            case LINUX -> arch + "-unknown-linux-gnu";
            case MACOS -> arch + "-apple-darwin";
            default -> null;
        };
    }

    /// Checks a `uv` executable against the pin.
    ///
    /// @param binary the extracted executable
    /// @param source what produced it, for the message
    /// @throws IOException `uv_unpinned_platform` when this platform has no
    ///                     digest, or `uv_sha256_mismatch` when the bytes differ
    public static void verify(Path binary, String source) throws IOException {
        verify(binary, source, binarySha256(), platformTag());
    }

    /// The same check with the digest and platform named, so a test can drive
    /// the pinned, unpinned and mismatched branches on one machine.
    static void verify(Path binary, String source, @Nullable String expected, String platform)
            throws IOException {
        if (expected == null) {
            throw new IOException("uv_unpinned_platform: no SHA-256 is pinned for "
                    + platform + ", so uv " + VERSION
                    + " would be installed unverified; add its digest to UvRuntime first");
        }
        if (!Files.isRegularFile(binary)) {
            throw new IOException("uv_missing: " + binary + " is not a file (source: " + source + ")");
        }
        String actual = BootstrapRuntimeManager.sha256(binary);
        if (!actual.equals(expected)) {
            throw new IOException("uv_sha256_mismatch: " + source
                    + " expected=" + expected + " actual=" + actual);
        }
    }

    /// The same check without an exception, for a cache hit.
    public static boolean verified(Path binary) {
        try {
            verify(binary, "cached uv");
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /// The one-line status the doctor prints.
    public static String summary() {
        Path binary = sharedBinary();
        if (!Files.isRegularFile(binary)) {
            return VERSION + " pinned, not installed (" + platformTag() + ")";
        }
        return VERSION + " " + (verified(binary) ? "[pinned digest ok]" : "[DIGEST MISMATCH]")
                + " " + binary;
    }
}