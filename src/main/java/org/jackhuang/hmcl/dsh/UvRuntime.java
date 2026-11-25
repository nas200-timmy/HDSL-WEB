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

    /// The `uv` release the panel installs, from the bootstrap catalogue.
    ///
    /// The release and every digest live in `/bootstrap/bootstrap.json`; an
    /// operator can point the panel at another catalogue with
    /// `HDSL_BOOTSTRAP_CATALOG`, which is how a pin rotates without a release.
    public static String version() {
        return uv().version();
    }

    private static BootstrapCatalog.Uv uv() {
        BootstrapCatalog.Uv uv = BootstrapCatalog.current().uv();
        if (uv == null) {
            throw new IllegalStateException(
                    "bootstrap_catalog_incomplete: the catalogue pins no uv release");
        }
        return uv;
    }

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
        return uv().sha256(tag);
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
        return root().resolve(version()).resolve(executableName());
    }

    /// Whether a wheel file name carries a `uv` build this platform can run.
    ///
    /// Linux publishes two families and they are not interchangeable:
    /// `manylinux` links glibc, `musllinux` links musl. The glibc wheel on
    /// Alpine passes its digest check and then dies on the loader, which is a
    /// worse answer than "no wheel" because the digest said it was right. So
    /// the libc is decided first, by the same probe
    /// [PythonRuntimeManager#isMusl] uses. A Windows wheel is `win_amd64`, and
    /// a macOS one names the deployment target and the architecture.
    public static boolean matchesWheel(String filename) {
        if (!filename.startsWith("uv-" + version() + "-") || !filename.endsWith(".whl")) {
            return false;
        }
        return switch (OperatingSystem.CURRENT_OS) {
            case WINDOWS -> filename.contains(isArm() ? "win_arm64" : "win_amd64");
            case MACOS -> filename.contains("macosx")
                    && filename.contains(isArm() ? "arm64" : "x86_64");
            default -> filename.contains(PythonRuntimeManager.isMusl(java.nio.file.Files::exists)
                    ? "musllinux" : "manylinux")
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
                    + platform + ", so uv " + version()
                    + " would be installed unverified; add its digest to the bootstrap catalogue");
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

    /// [#verified] for a caller that only *reports* the answer.
    ///
    /// `/api/bootstrap` asks this on every poll and the doctor on every run,
    /// and each answer is a SHA-256 of the whole binary — tens of megabytes per
    /// request for a page that is usually only being looked at. The key carries
    /// everything that could change the answer, so a replaced binary is noticed:
    /// size, modification time and the pinned digest.
    ///
    /// Callers that are about to *execute* the binary keep using [#verified]:
    /// the cache is for display, and the gate in front of an `exec` should cost
    /// what it costs.
    ///
    /// @param binary the shared copy
    /// @return whether its digest matches the pin
    public static boolean verifiedCached(Path binary) {
        String key = verificationKey(binary);
        if (key != null && key.equals(verifiedKey)) {
            return verifiedAnswer;
        }
        boolean answer = verified(binary);
        if (key != null) {
            verifiedAnswer = answer;
            verifiedKey = key;
        }
        return answer;
    }

    /// Everything that could invalidate a cached answer, or `null` when the
    /// file cannot be read and nothing may be cached.
    static @Nullable String verificationKey(Path binary) {
        try {
            java.nio.file.attribute.BasicFileAttributes attributes = Files.readAttributes(binary,
                    java.nio.file.attribute.BasicFileAttributes.class);
            return binary.toAbsolutePath().normalize() + "|" + attributes.size() + "|"
                    + attributes.lastModifiedTime().toMillis() + "|" + binarySha256();
        } catch (IOException e) {
            return null;
        }
    }

    /// The last display answer [#verifiedCached] gave, and what it was keyed on.
    private static volatile @Nullable String verifiedKey;
    private static volatile boolean verifiedAnswer;

    /// The one-line status the doctor prints.
    public static String summary() {
        if (binarySha256() == null) {
            return version() + " has no digest for " + platformTag()
                    + "; uv is refused rather than installed unverified";
        }
        Path binary = sharedBinary();
        if (!Files.isRegularFile(binary)) {
            return version() + " pinned, not installed (" + platformTag() + ")";
        }
        return version() + " " + (verifiedCached(binary) ? "[pinned digest ok]" : "[DIGEST MISMATCH]")
                + " " + binary;
    }
}