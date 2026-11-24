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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jackhuang.hmcl.util.logging.Logger;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/// The interpreter a child process should use, and where it came from.
///
/// Four levels, in this order, and the order is the policy:
///
/// 1. **operator** — `HDSL_PYTHON`, then `PYTHON`. An explicit choice wins, but
///    it has to run: a value that does not is reported and stepped over rather
///    than passed on to fail later inside node-gyp.
/// 2. **system** — the first `python3`/`python` on `PATH` that answers the probe.
///    On Windows the Store's `python.exe` reparse point is skipped: it exists,
///    it is not an interpreter, and asking it for a version opens a shop page.
/// 3. **managed** — the shared CPython [PythonRuntimeManager] installed, if any.
/// 4. **none**.
///
/// Nothing here installs anything. A download is an explicit install action
/// (a brand's source build, the Node page), never a side effect of looking for
/// an interpreter. That is also why the managed level sits *after* the system
/// one: a host that already has a usable Python should never grow a second one.
///
/// `PATH` is only amended when the managed level is reached, which by then means
/// no usable system interpreter exists. Otherwise a build script that says
/// `python3` would silently move to the panel's copy — the one thing this class
/// exists to avoid.
@NotNullByDefault
public final class PythonResolver {

    private static final Logger LOG = Logger.LOG;

    /// Where the interpreter came from.
    public enum Origin {
        /// Set by the operator in `HDSL_PYTHON`/`PYTHON`.
        OPERATOR,
        /// Found on `PATH`.
        SYSTEM,
        /// Installed by [PythonRuntimeManager] under `<data>/toolchains/python`.
        MANAGED,
        /// Nothing usable.
        NONE
    }

    /// One interpreter, with the capabilities the callers actually need.
    ///
    /// @param origin           where it came from
    /// @param executable       the interpreter, or `null` for [Origin#NONE]
    /// @param version          `major.minor.patch`, or `""`
    /// @param venv             whether `import venv` works — Debian splits
    ///                         `python3-venv` out, so this is not implied
    /// @param externallyManaged whether the interpreter is PEP 668 protected
    /// @param detail           why this one, or why nothing was found
    public record Interpreter(Origin origin, @Nullable Path executable, String version,
                              boolean venv, boolean externallyManaged, String detail) {

        public boolean usable() {
            return origin != Origin.NONE && executable != null;
        }

        /// The one-line form the doctor prints.
        public String describe() {
            if (!usable()) {
                return "none (" + detail + ")";
            }
            return version + " " + executable + " [" + origin.name().toLowerCase(Locale.ROOT) + "]"
                    + (detail.isEmpty() ? "" : " (" + detail + ")");
        }
    }

    private static final Interpreter NONE =
            new Interpreter(Origin.NONE, null, "", false, false, "no interpreter found");

    /// One round trip for everything: version, the real executable, and the two
    /// capabilities that decide whether `venv`/`pip` work at all.
    private static final String PROBE = "import json,sys,sysconfig,os,importlib.util;"
            + "stdlib=sysconfig.get_path('stdlib') or '';"
            + "print(json.dumps({'v':[sys.version_info[0],sys.version_info[1],sys.version_info[2]],"
            + "'exe':sys.executable,"
            + "'venv':importlib.util.find_spec('venv') is not None,"
            + "'em':bool(stdlib) and os.path.exists(os.path.join(stdlib,'EXTERNALLY-MANAGED'))}))";

    /// A minimum of 3.8, which is the floor node-gyp's current releases accept.
    private static final int MIN_MAJOR = 3;
    private static final int MIN_MINOR = 8;

    private static final long PROBE_TIMEOUT_MILLIS = 5_000;
    private static final long CACHE_MILLIS = 60_000;

    private static volatile @Nullable Interpreter cached;
    private static volatile long cachedAt;

    private PythonResolver() {
    }

    /// The current choice, recomputed at most once a minute.
    ///
    /// Cached because [BootstrapEnvironment] asks on every child process and a
    /// probe is a process launch: a terminal start should not pay for one.
    public static Interpreter resolve() {
        Interpreter value = cached;
        if (value != null && System.currentTimeMillis() - cachedAt < CACHE_MILLIS) {
            return value;
        }
        synchronized (PythonResolver.class) {
            value = cached;
            if (value != null && System.currentTimeMillis() - cachedAt < CACHE_MILLIS) {
                return value;
            }
            Interpreter computed = compute();
            cached = computed;
            cachedAt = System.currentTimeMillis();
            return computed;
        }
    }

    /// Forgets the cached choice, e.g. after an install or in a test.
    static void resetCache() {
        synchronized (PythonResolver.class) {
            cached = null;
            cachedAt = 0;
        }
    }

    private static Interpreter compute() {
        String operatorDetail = "";
        String configured = firstNonBlank(System.getenv("HDSL_PYTHON"), System.getenv("PYTHON"));
        if (configured != null) {
            Path located = locate(configured);
            Interpreter probed = located == null ? null : probe(Origin.OPERATOR, located);
            if (probed != null) {
                return probed;
            }
            operatorDetail = "the operator's HDSL_PYTHON/PYTHON (" + configured
                    + ") is not a usable interpreter";
            LOG.warning(operatorDetail + "; looking for another one");
        }

        String rejected = "";
        for (Path candidate : candidates()) {
            Interpreter probed = probe(Origin.SYSTEM, candidate);
            if (probed != null) {
                return probed;
            }
        }
        rejected = "no usable python3/python on PATH";

        PythonRuntimeManager.Installed managed = PythonRuntimeManager.currentShared();
        if (managed != null) {
            Interpreter probed = probe(Origin.MANAGED, managed.interpreter());
            if (probed != null) {
                return probed;
            }
            rejected = "the panel-managed CPython " + managed.version() + " does not run";
        }

        return new Interpreter(Origin.NONE, null, "", false, false,
                operatorDetail.isEmpty() ? rejected : operatorDetail);
    }

    /// Every `python3`/`python` on `PATH`, in `PATH` order, stubs excluded.
    static List<Path> candidates() {
        return candidates(System.getenv("PATH"), OperatingSystem.CURRENT_OS,
                System.getenv("WINDIR") != null ? System.getenv("WINDIR")
                        : System.getenv("SystemRoot"), PythonResolver::isExecutable);
    }

    /// The pure form: a `PATH` value, an OS and a file test.
    ///
    /// @param path      a `PATH` value, or `null`
    /// @param os        the platform whose naming and stub rules apply
    /// @param windir    `%WINDIR%`, or `null`
    /// @param executable how to ask whether a path is a runnable file
    /// @return the candidates, first match first, duplicates removed
    static List<Path> candidates(@Nullable String path, OperatingSystem os,
                                 @Nullable String windir, java.util.function.Predicate<Path> executable) {
        List<Path> result = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        if (path == null || path.isBlank()) {
            return result;
        }
        List<String> names = os == OperatingSystem.WINDOWS
                ? List.of("python.exe", "python3.exe")
                : List.of("python3", "python");
        for (String directory : path.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (directory.isBlank()) {
                continue;
            }
            for (String name : names) {
                Path candidate;
                try {
                    candidate = Path.of(directory, name);
                } catch (RuntimeException e) {
                    continue;
                }
                if (!executable.test(candidate) || isStub(candidate, os, windir)) {
                    continue;
                }
                Path key = realPath(candidate);
                if (seen.add(key)) {
                    result.add(candidate);
                }
            }
        }
        return result;
    }

    /// Whether `path` is a place an interpreter can actually be.
    ///
    /// Windows' `WindowsApps` holds zero-byte reparse points that launch the
    /// Store page; `C:\Windows` holds nothing that answers `-c`. Both exist,
    /// both satisfy `Files.exists`, and neither is a Python.
    static boolean isStub(Path path) {
        return isStub(path, OperatingSystem.CURRENT_OS,
                System.getenv("WINDIR") != null ? System.getenv("WINDIR")
                        : System.getenv("SystemRoot"));
    }

    /// The pure form of the stub test.
    ///
    /// @param path   the candidate
    /// @param os     the platform the rules belong to
    /// @param windir `%WINDIR%`, or `null`
    /// @return whether the path exists but cannot be an interpreter
    static boolean isStub(Path path, OperatingSystem os, @Nullable String windir) {
        if (os != OperatingSystem.WINDOWS) {
            return false;
        }
        String value = path.toString().toLowerCase(Locale.ROOT).replace('\\', '/');
        if (value.contains("/microsoft/windowsapps/")) {
            return true;
        }
        if (windir == null || windir.isBlank()) {
            return false;
        }
        String root = windir.toLowerCase(Locale.ROOT).replace('\\', '/');
        while (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        return value.startsWith(root + "/");
    }

    private static boolean isExecutable(Path path) {
        if (!Files.isRegularFile(path)) {
            return false;
        }
        return OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS || Files.isExecutable(path);
    }

    private static Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }

    /// Resolves an operator-set value: an absolute path, or a bare name looked
    /// up on `PATH`.
    static @Nullable Path locate(String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        Path direct;
        try {
            direct = Path.of(trimmed);
        } catch (RuntimeException e) {
            return null;
        }
        if (trimmed.contains(File.separator) || direct.isAbsolute()) {
            return isExecutable(direct) && !isStub(direct) ? direct : null;
        }
        for (String directory : String.valueOf(System.getenv("PATH")).split(
                java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (directory.isBlank()) {
                continue;
            }
            Path candidate = Path.of(directory, trimmed);
            if (isExecutable(candidate) && !isStub(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /// Runs the probe. `null` means "this one is not an interpreter we can use".
    static @Nullable Interpreter probe(Origin origin, Path executable) {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(executable.toString(), "-c", PROBE);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            process = builder.start();
            if (!process.waitFor(PROBE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                LOG.info("python: " + executable + " did not answer the probe within "
                        + PROBE_TIMEOUT_MILLIS + " ms");
                return null;
            }
            if (process.exitValue() != 0) {
                return null;
            }
            String output = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            JsonObject object = lastJsonLine(output);
            if (object == null || !object.has("v")) {
                return null;
            }
            var version = object.getAsJsonArray("v");
            int major = version.get(0).getAsInt();
            int minor = version.get(1).getAsInt();
            int patch = version.get(2).getAsInt();
            if (major < MIN_MAJOR || (major == MIN_MAJOR && minor < MIN_MINOR)) {
                LOG.info("python: " + executable + " is " + major + "." + minor
                        + ", older than " + MIN_MAJOR + "." + MIN_MINOR);
                return null;
            }
            Path reported = executable;
            if (object.has("exe")) {
                try {
                    Path real = Path.of(object.get("exe").getAsString());
                    if (Files.isRegularFile(real)) {
                        reported = real;
                    }
                } catch (RuntimeException ignored) {
                    // The probed path stays authoritative.
                }
            }
            return new Interpreter(origin, reported, major + "." + minor + "." + patch,
                    object.has("venv") && object.get("venv").getAsBoolean(),
                    object.has("em") && object.get("em").getAsBoolean(), "");
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /// The last line that is a JSON object. Windows tools occasionally prepend
    /// warnings a Python one-liner would not.
    private static @Nullable JsonObject lastJsonLine(String output) {
        String[] lines = output.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (!line.startsWith("{")) {
                continue;
            }
            try {
                return JsonParser.parseString(line).getAsJsonObject();
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    /// Names the interpreter for uv and node-gyp.
    ///
    /// An operator's choice is left alone: they set it, they own it. A managed
    /// interpreter also goes on `PATH`, because reaching that level means no
    /// usable system interpreter exists — a bare image where `python3` must
    /// resolve to something, or configure scripts fail for the wrong reason.
    public static void applyTo(Map<String, String> environment) {
        Interpreter python = resolve();
        if (!python.usable() || python.origin() == Origin.OPERATOR) {
            return;
        }
        Path executable = python.executable();
        if (python.origin() == Origin.MANAGED) {
            environment.compute("PATH", (key, existing) -> existing == null || existing.isBlank()
                    ? executable.getParent().toString()
                    : BootstrapEnvironment.prepend(executable.getParent(), existing));
        }
        putIfBlank(environment, "PYTHON", executable.toString());
        putIfBlank(environment, "npm_config_python", executable.toString());
        putIfBlank(environment, "UV_PYTHON", executable.toString());
    }

    private static void putIfBlank(Map<String, String> environment, String name, String value) {
        String existing = environment.get(name);
        if (existing == null || existing.isBlank()) {
            environment.put(name, value);
        }
    }

    private static @Nullable String firstNonBlank(@Nullable String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}