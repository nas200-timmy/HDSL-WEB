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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/// The environment a child process needs when the image is a minimal one.
///
/// Four things a managed bundle provides and an image does not:
///
/// - `PATH` gets the closure's `bin`, where `setpriv` lives;
/// - `LD_LIBRARY_PATH` gets the closure's `lib`, where `libstdc++`, `libgcc_s`
///   and `libatomic` live — without it a Node.js binary that links them exits
///   127 before it prints a version;
/// - `SSL_CERT_FILE` and its siblings point npm, git, Python requests and the
///   agents' own TLS stacks at the panel's `cacert.pem`;
/// - the interpreter [PythonResolver] chose is named in `PYTHON`,
///   `npm_config_python` and `UV_PYTHON`; only a panel-managed interpreter also
///   goes on `PATH`, and only when no usable system Python exists, so the host's
///   `python3` is never shadowed;
/// - an installed [BuildToolchainManager] bundle puts its `bin` first and names
///   `CC`, `CXX`, `MAKE`, `AR`, `LD` and `PKG_CONFIG`, so a terminal, a brand
///   install and a ZCode build all see the same compiler. A variable the
///   operator already set to a non-blank value is left alone.
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
        PythonResolver.Interpreter python = PythonResolver.resolve();
        BuildToolchainManager.Toolchain build = BuildToolchainManager.currentInstalled();
        return additions(runtime == null ? null : runtime.directory(),
                ca == null ? null : ca.file(),
                python,
                build == null ? null : build.binDir(),
                build == null || build.directory() == null ? null : build.directory().resolve("lib"),
                build == null ? Map.of() : build.environment(),
                System.getenv(), Files::exists, true);
    }

    /// Applies the additions to a mutable environment map.
    ///
    /// `PATH` and `LD_LIBRARY_PATH` are prepended rather than replaced, and a
    /// variable an operator already set to a usable value is left alone.
    ///
    /// @param environment the map to amend
    public static void apply(Map<String, String> environment) {
        apply(environment, true);
    }

    /// Applies the additions, optionally leaving Python alone.
    ///
    /// @param environment   the map to amend
    /// @param includePython whether to name an interpreter; an app that brings
    ///                      its own passes `false`
    public static void apply(Map<String, String> environment, boolean includePython) {
        Installed runtime = BootstrapRuntimeManager.currentRuntimeClosure();
        BootstrapRuntimeManager.CaInstalled ca = BootstrapRuntimeManager.currentCaBundle();
        PythonResolver.Interpreter python = PythonResolver.resolve();
        BuildToolchainManager.Toolchain build = BuildToolchainManager.currentInstalled();
        environment.putAll(additions(runtime == null ? null : runtime.directory(),
                ca == null ? null : ca.file(),
                python,
                build == null ? null : build.binDir(),
                build == null || build.directory() == null ? null : build.directory().resolve("lib"),
                build == null ? Map.of() : build.environment(),
                environment, Files::exists, includePython));
    }

    /// Applies the additions to a child process.
    ///
    /// @param builder the process builder
    public static void apply(ProcessBuilder builder) {
        apply(builder.environment(), true);
    }

    /// The same, with Python left to the child.
    ///
    /// @param builder       the child process
    /// @param includePython whether to name an interpreter
    public static void apply(ProcessBuilder builder, boolean includePython) {
        apply(builder.environment(), includePython);
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
        return additions(runtimeDirectory, caFile, null, null, null, Map.of(), base, exists);
    }

    /// The pure form including the shared Python runtime.
    ///
    /// @param runtimeDirectory the active runtime closure's version directory,
    ///                         or `null`
    /// @param caFile           the active `cacert.pem`, or `null`
    /// @param python           the resolved interpreter, or `null`
    /// @param base             the environment the additions are computed against
    /// @param exists           how to ask whether a path exists
    /// @return the variables to add or replace
    static Map<String, String> additions(@Nullable Path runtimeDirectory, @Nullable Path caFile,
                                         @Nullable PythonResolver.Interpreter python,
                                         Map<String, String> base, Predicate<Path> exists) {
        return additions(runtimeDirectory, caFile, python, null, null, Map.of(),
                base, exists);
    }

    /// The pure form including the build toolchain, the runtime closure and the
    /// shared CPython.
    ///
    /// @param runtimeDirectory the active runtime closure's version directory,
    ///                         or `null`
    /// @param caFile           the active `cacert.pem`, or `null`
    /// @param python           the resolved interpreter, or `null`
    /// @param buildBin         the build toolchain's `bin/`, or `null`
    /// @param buildLib         the build toolchain's `lib/`, or `null`
    /// @param buildVariables   the build toolchain's `CC`/`CXX`/`MAKE`/… map
    /// @param base             the environment the additions are computed against
    /// @param exists           how to ask whether a path exists
    /// @return the variables to add or replace
    static Map<String, String> additions(@Nullable Path runtimeDirectory, @Nullable Path caFile,
                                         @Nullable PythonResolver.Interpreter python,
                                         @Nullable Path buildBin, @Nullable Path buildLib,
                                         Map<String, String> buildVariables,
                                         Map<String, String> base, Predicate<Path> exists) {
        return additions(runtimeDirectory, caFile, python, buildBin, buildLib, buildVariables,
                base, exists, true);
    }

    /// The same, with Python included or not.
    ///
    /// @param includePython whether to name an interpreter at all
    static Map<String, String> additions(@Nullable Path runtimeDirectory, @Nullable Path caFile,
                                         @Nullable PythonResolver.Interpreter python,
                                         @Nullable Path buildBin, @Nullable Path buildLib,
                                         Map<String, String> buildVariables,
                                         Map<String, String> base, Predicate<Path> exists,
                                         boolean includePython) {
        Map<String, String> additions = new LinkedHashMap<>();
        List<Path> pathPrefixes = new ArrayList<>();
        if (buildBin != null && exists.test(buildBin)) {
            pathPrefixes.add(buildBin);
        }
        if (buildLib != null && exists.test(buildLib)) {
            additions.put("LD_LIBRARY_PATH", prepend(buildLib, base.get("LD_LIBRARY_PATH")));
        }
        for (String name : List.of("CC", "CXX", "MAKE", "AR", "LD", "PKG_CONFIG",
                "ZIG_GLOBAL_CACHE_DIR")) {
            putIfUnusable(additions, base, name, buildVariables.get(name));
        }
        if (runtimeDirectory != null) {
            Path bin = runtimeDirectory.resolve("bin");
            Path lib = runtimeDirectory.resolve("lib");
            if (exists.test(bin) && (exists.test(bin.resolve("setpriv"))
                    || exists.test(bin.resolve("bash")))) {
                pathPrefixes.add(bin);
            }
            if (exists.test(lib)) {
                String existing = additions.get("LD_LIBRARY_PATH");
                additions.put("LD_LIBRARY_PATH", prepend(lib,
                        existing != null ? existing : base.get("LD_LIBRARY_PATH")));
            }
        }
        if (includePython && python != null && python.usable()
                && python.origin() != PythonResolver.Origin.OPERATOR
                && !PythonResolver.hasInterpreter(base, exists)) {
            Path interpreter = python.executable();
            if (python.origin() == PythonResolver.Origin.MANAGED) {
                // Reaching MANAGED means no usable system interpreter exists, so
                // putting it on PATH shadows nothing. A SYSTEM choice never does:
                // a build script that says `python3` keeps finding the host's.
                pathPrefixes.add(interpreter.getParent());
            }
            putIfUnusable(additions, base, "PYTHON", interpreter.toString());
            putIfUnusable(additions, base, "npm_config_python", interpreter.toString());
            putIfUnusable(additions, base, "UV_PYTHON", interpreter.toString());
        }
        if (!pathPrefixes.isEmpty()) {
            String path = base.get("PATH");
            for (int i = pathPrefixes.size() - 1; i >= 0; i--) {
                path = prepend(pathPrefixes.get(i), path);
            }
            additions.put("PATH", path);
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
                                      String name, @Nullable String value) {
        if (value == null || value.isBlank()) {
            return;
        }
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