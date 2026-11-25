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

    /// What a build may do when no host interpreter satisfies it.
    ///
    /// - `auto` — use a host interpreter that matches, download the shared line
    ///   only when none does (the default);
    /// - `system-only` — never download; a build without a matching host
    ///   interpreter fails with the command that would fix it;
    /// - `managed` — use the panel's shared line even when a host interpreter
    ///   exists, for deployments that pin one interpreter for everything.
    ///
    /// The value comes from `HDSL_PYTHON_POLICY`.
    public enum Policy {
        AUTO, SYSTEM_ONLY, MANAGED
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

    /// The policy named by `HDSL_PYTHON_POLICY`, `auto` when absent or unknown.
    public static Policy policy() {
        return parsePolicy(System.getenv("HDSL_PYTHON_POLICY"));
    }

    /// Parses a policy value, `auto` when absent or unknown.
    static Policy parsePolicy(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return Policy.AUTO;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return switch (normalized) {
            case "system-only", "system", "host" -> Policy.SYSTEM_ONLY;
            case "managed", "panel" -> Policy.MANAGED;
            case "auto", "" -> Policy.AUTO;
            default -> {
                LOG.warning("HDSL_PYTHON_POLICY='" + value + "' is not auto/system-only/managed;"
                        + " using auto");
                yield Policy.AUTO;
            }
        };
    }

    /// Whether a build may download the shared CPython line.
    public static boolean managedAllowed() {
        return policy() != Policy.SYSTEM_ONLY;
    }

    /// A host interpreter whose `major.minor` matches, or `null`.
    ///
    /// A build that pins `3.12` must not be answered by a host `3.13`: the
    /// interpreter decides the stdlib and the wheel ABI. `managed` policy returns
    /// `null` here, because that policy says the shared line is the answer.
    ///
    /// @param majorMinor e.g. `3.12`; `null` accepts any usable host interpreter
    /// @return the interpreter, or `null` when the host has none that matches
    public static @Nullable Interpreter forVersion(@Nullable String majorMinor) {
        if (policy() == Policy.MANAGED) {
            return null;
        }
        Interpreter resolved = resolve();
        if (!resolved.usable() || resolved.origin() == Origin.MANAGED) {
            return null;
        }
        if (majorMinor == null || majorMinor.isBlank()) {
            return resolved;
        }
        return matchesVersion(resolved.version(), majorMinor) ? resolved : null;
    }

    /// `3.12.16` matches `3.12`; `3.13.5` does not.
    static boolean matchesVersion(@Nullable String version, String majorMinor) {
        if (version == null || version.isBlank()) {
            return false;
        }
        return version.equals(majorMinor) || version.startsWith(majorMinor + ".");
    }

    /// `3.12.16` → `3.12`, `3.12` → `3.12`.
    public static String majorMinor(@Nullable String version) {
        if (version == null) {
            return "";
        }
        String[] parts = version.trim().split("\\.");
        return parts.length >= 2 ? parts[0] + "." + parts[1] : version.trim();
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

        // `managed` says the shared line wins even over a host interpreter;
        // every other policy prefers what the host already has.
        if (policy() == Policy.MANAGED) {
            Interpreter managedFirst = fromManaged();
            if (managedFirst != null) {
                return managedFirst;
            }
        }

        String rejected = "";
        for (Path candidate : candidates()) {
            Interpreter probed = probe(Origin.SYSTEM, candidate);
            if (probed != null) {
                return probed;
            }
        }
        rejected = "no usable python3/python on PATH";

        if (policy() != Policy.MANAGED) {
            Interpreter managed = fromManaged();
            if (managed != null) {
                return managed;
            }
            rejected = managedDetail();
        }

        return new Interpreter(Origin.NONE, null, "", false, false,
                operatorDetail.isEmpty() ? rejected : operatorDetail);
    }

    private static @Nullable Interpreter fromManaged() {
        PythonRuntimeManager.Installed managed = PythonRuntimeManager.currentShared();
        if (managed == null) {
            return null;
        }
        return probe(Origin.MANAGED, managed.interpreter());
    }

    private static String managedDetail() {
        PythonRuntimeManager.Installed managed = PythonRuntimeManager.currentShared();
        return managed == null ? "no panel-managed CPython is installed"
                : "the panel-managed CPython " + managed.version() + " does not run";
    }

    /// Whether an environment's own `PATH` already names an interpreter.
    ///
    /// A child that brings its own Python — a bundled runtime, a venv, an
    /// instance that sets its own PATH — must not be answered by the panel's
    /// interpreter: the panel only fills a gap. This is the check both the
    /// environment additions and [applyTo] make before injecting anything.
    ///
    /// @param environment the child's environment
    /// @param executable  how to ask whether a path is a runnable file
    /// @return whether a `python3`/`python` is already visible
    static boolean hasInterpreter(Map<String, String> environment,
                                  java.util.function.Predicate<Path> executable) {
        return !candidates(BootstrapEnvironment.lookup(environment, "PATH"), OperatingSystem.CURRENT_OS,
                System.getenv("WINDIR") != null ? System.getenv("WINDIR")
                        : System.getenv("SystemRoot"), executable).isEmpty();
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
        // The child's own interpreter wins: an instance that ships one, a venv,
        // or a PATH the caller built must not be shadowed by the panel's choice.
        if (hasInterpreter(environment, PythonResolver::isExecutable)) {
            return;
        }
        Path executable = python.executable();
        if (python.origin() == Origin.MANAGED) {
            // Under the spelling the map already uses: on Windows that is
            // `Path`, and writing a second key beside it leaves the child with
            // two search paths and no rule for which one wins.
            String key = BootstrapEnvironment.keyOf(environment, "PATH");
            String existing = BootstrapEnvironment.lookup(environment, "PATH");
            environment.put(key, existing == null || existing.isBlank()
                    ? executable.getParent().toString()
                    : BootstrapEnvironment.prepend(executable.getParent(), existing));
        }
        putIfBlank(environment, "PYTHON", executable.toString());
        putIfBlank(environment, "npm_config_python", executable.toString());
        putIfBlank(environment, "UV_PYTHON", executable.toString());
    }

    private static void putIfBlank(Map<String, String> environment, String name, String value) {
        String existing = BootstrapEnvironment.lookup(environment, name);
        if (existing == null || existing.isBlank()) {
            environment.put(BootstrapEnvironment.keyOf(environment, name), value);
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