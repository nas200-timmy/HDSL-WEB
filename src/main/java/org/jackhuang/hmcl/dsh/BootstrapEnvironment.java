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

import org.jackhuang.hmcl.dsh.BootstrapRuntimeManager.Installed;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/// The environment a child process needs when the image is a minimal one.
///
/// Three things a managed bundle provides and an image does not:
///
/// - `PATH` gets the closure's `bin`, where `setpriv` lives;
/// - `LD_LIBRARY_PATH` gets the closure's `lib`, where `libstdc++`, `libgcc_s`
///   and `libatomic` live — without it a Node.js binary that links them exits
///   127 before it prints a version;
/// - `SSL_CERT_FILE` and its siblings point npm, git, Python requests and the
///   agents' own TLS stacks at the panel's `cacert.pem`.
///
/// `SHELL` is only set when a real shell exists. A child that is told
/// `SHELL=/bin/bash` on an image without bash does not fall back; it fails when
/// it tries to run it. The panel therefore says nothing rather than something
/// untrue, which is the same rule the terminal adapters follow.
///
/// Nothing here writes to `System.getenv`. The additions are computed from a
/// map the caller passes in, so two children can be given different base
/// environments without one seeing the other's changes.
@NotNullByDefault
public final class BootstrapEnvironment {

    private BootstrapEnvironment() {
    }

    /// The additions that would be made to the panel's own environment.
    ///
    /// @return the variables to add or replace; never `null`
    public static Map<String, String> additions() {
        Installed runtime = BootstrapRuntimeManager.currentRuntimeClosure();
        BootstrapRuntimeManager.CaInstalled ca = BootstrapRuntimeManager.currentCaBundle();
        return additions(runtime == null ? null : runtime.directory(),
                ca == null ? null : ca.file(), System.getenv(), Files::exists);
    }

    /// Applies the additions to a mutable environment map.
    ///
    /// `PATH` and `LD_LIBRARY_PATH` are prepended rather than replaced, and a
    /// variable an operator already set to a usable value is left alone.
    ///
    /// @param environment the map to amend
    public static void apply(Map<String, String> environment) {
        Installed runtime = BootstrapRuntimeManager.currentRuntimeClosure();
        BootstrapRuntimeManager.CaInstalled ca = BootstrapRuntimeManager.currentCaBundle();
        environment.putAll(additions(runtime == null ? null : runtime.directory(),
                ca == null ? null : ca.file(), environment, Files::exists));
    }

    /// Applies the additions to a child process.
    ///
    /// @param builder the process builder
    public static void apply(ProcessBuilder builder) {
        apply(builder.environment());
    }

    /// The pure form, with the bundle directories and the filesystem test named
    /// so a test can drive every combination without a network or `/etc`.
    ///
    /// @param runtimeDirectory the active runtime closure's version directory,
    ///                         or `null`
    /// @param caFile           the active `cacert.pem`, or `null`
    /// @param base             the environment the additions are computed against
    /// @param exists           how to ask whether a path exists
    /// @return the variables to add or replace
    static Map<String, String> additions(@Nullable Path runtimeDirectory, @Nullable Path caFile,
                                         Map<String, String> base, Predicate<Path> exists) {
        Map<String, String> additions = new LinkedHashMap<>();
        if (runtimeDirectory != null) {
            Path bin = runtimeDirectory.resolve("bin");
            Path lib = runtimeDirectory.resolve("lib");
            if (exists.test(bin) && (exists.test(bin.resolve("setpriv"))
                    || exists.test(bin.resolve("bash")))) {
                additions.put("PATH", prepend(bin, base.get("PATH")));
            }
            if (exists.test(lib)) {
                additions.put("LD_LIBRARY_PATH", prepend(lib, base.get("LD_LIBRARY_PATH")));
            }
        }
        if (caFile != null && exists.test(caFile)) {
            String path = caFile.toString();
            putIfUnusable(additions, base, "SSL_CERT_FILE", path);
            putIfUnusable(additions, base, "CURL_CA_BUNDLE", path);
            putIfUnusable(additions, base, "REQUESTS_CA_BUNDLE", path);
            putIfUnusable(additions, base, "GIT_SSL_CAINFO", path);
            putIfUnusable(additions, base, "NODE_EXTRA_CA_CERTS", path);
        }
        String configured = base.get("SHELL");
        if (configured != null && !configured.isBlank() && exists.test(Path.of(configured))) {
            // An operator's working shell is not replaced by the panel's guess.
            return additions;
        }
        Path bundled = runtimeDirectory == null
                ? null : runtimeDirectory.resolve("bin").resolve("bash");
        if (bundled != null && exists.test(bundled)) {
            additions.put("SHELL", bundled.toString());
        } else {
            Path systemBash = Path.of("/bin/bash");
            if (exists.test(systemBash)) {
                additions.put("SHELL", systemBash.toString());
            }
        }
        return additions;
    }

    private static void putIfUnusable(Map<String, String> additions, Map<String, String> base,
                                      String name, String value) {
        String existing = base.get(name);
        if (existing == null || existing.isBlank()) {
            additions.put(name, value);
        }
    }

    /// Puts `directory` in front of a path list without duplicating it.
    static String prepend(Path directory, @Nullable String existing) {
        String prefix = directory.toString();
        if (existing == null || existing.isBlank()) {
            return prefix;
        }
        if (existing.equals(prefix) || existing.startsWith(prefix + File.pathSeparator)) {
            return existing;
        }
        return prefix + File.pathSeparator + existing;
    }
}