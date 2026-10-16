/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2025 huangyuhui <huanghongxun2008@126.com> and contributors
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
package org.jackhuang.hmcl.util.platform;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/// Resolves program names to files that can actually be started on this system.
///
/// On POSIX a program is a file with the execute bit, and a name without a slash is
/// looked up on `PATH` — so the name a person types is the name to start, and
/// `ProcessBuilder` agrees. Windows is different in two ways that a launcher which only
/// ever ran on Linux never had to know:
///
/// 1. what is executable is decided by **extension**, so `npm` is really `npm.cmd` and
///    `node` is really `node.exe` — the name without its extension is not a file at all;
/// 2. `ProcessBuilder` passes the program to `CreateProcess` with `lpApplicationName`
///    unset, which resolves `node` to `node.exe` on its own but has nothing to say about
///    `.cmd`, so a bare `npm` fails with "the system cannot find the file specified".
///
/// What it does **not** mean is that `.cmd` needs a shell. `CreateProcess` runs a batch
/// file through `cmd.exe` itself when the command line names one, so handing it
/// `npm.cmd` runs the shim with the arguments exactly as given — no re-quoting, which is
/// the whole reason this class resolves the extension instead of wrapping in `cmd /c`.
/// (Measured on Windows 10 with JDK 21: bare `npm` → error 2, `npm.cmd` → runs;
/// an extensionless `npm` → error 193, "not a valid Win32 application".)
///
/// The extensions themselves come from `PATHEXT`, the variable whose whole purpose is
/// this list; the table below is only the fallback for a process that has none.
@NotNullByDefault
public final class Executables {
    /// What Windows appends to a name when `PATHEXT` says nothing.
    ///
    /// Ordered by how likely a tool the launcher spawns is to be found under it: every
    /// Node.js shim is a `.cmd`, and `.exe` is a native binary like `node.exe`.
    private static final List<String> DEFAULT_WINDOWS_EXTENSIONS =
            List.of(".exe", ".cmd", ".bat", ".com");

    private Executables() {
    }

    /// Returns whether executable names on this system carry an extension.
    ///
    /// @return whether the current system is Windows
    public static boolean usesExtensions() {
        return OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS;
    }

    /// The extensions this system tries, in order, each including its dot.
    ///
    /// @return the `PATHEXT` entries, or the built-in table when it is unset
    static List<String> extensions() {
        if (!usesExtensions()) {
            return List.of();
        }
        String pathext = System.getenv("PATHEXT");
        if (pathext == null || pathext.isBlank()) {
            return DEFAULT_WINDOWS_EXTENSIONS;
        }
        Set<String> extensions = new LinkedHashSet<>();
        for (String entry : pathext.split(";")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            extensions.add(trimmed.startsWith(".") ? trimmed.toLowerCase(Locale.ROOT)
                    : "." + trimmed.toLowerCase(Locale.ROOT));
        }
        // PATHEXT is the user's list, but the two forms every Node.js installation ships
        // are not optional: a `.cmd` this system cannot reach is a tool that exists and
        // cannot be run.
        for (String required : DEFAULT_WINDOWS_EXTENSIONS) {
            extensions.add(required);
        }
        return List.copyOf(extensions);
    }

    /// Resolves a program name to a file that can be started, searching this process's `PATH`.
    ///
    /// @param name the program name, with or without an extension or directory
    /// @return the file to start, or empty when the name names nothing startable
    public static Optional<Path> resolve(@Nullable String name) {
        return resolve(name, System.getenv("PATH"));
    }

    /// Resolves a program name to a file that can be started, searching a given `PATH`.
    ///
    /// A name carrying a directory is treated as a path and never looked up on `PATH`;
    /// a bare name is looked up in each entry, which is what `CreateProcess` and `execvp`
    /// disagree about.
    ///
    /// @param name the program name
    /// @param path the `PATH` to search, or `null` for none
    /// @return the file to start, or empty when the name names nothing startable
    public static Optional<Path> resolve(@Nullable String name, @Nullable String path) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String trimmed = name.trim();

        Path asPath;
        try {
            asPath = Path.of(trimmed);
        } catch (InvalidPathException e) {
            return Optional.empty();
        }

        // A name that carries a directory is a path: `bin/node`, `C:\nodejs\npm.cmd`.
        // Deciding this before searching is what keeps a relative name from being looked
        // up in every `PATH` entry as if it were a bare one.
        if (asPath.isAbsolute() || hasSeparator(trimmed)) {
            return startableAmong(asPath);
        }

        if (path != null && !path.isBlank()) {
            for (String entry : path.split(File.pathSeparator)) {
                if (entry.isBlank()) {
                    continue;
                }
                try {
                    Optional<Path> candidate = startableAmong(Path.of(entry.trim()).resolve(trimmed));
                    if (candidate.isPresent()) {
                        return candidate;
                    }
                } catch (InvalidPathException e) {
                    // A malformed entry is one unusable directory, not an unusable PATH.
                }
            }
        }
        return Optional.empty();
    }

    /// Rewrites a command so its program can be started, leaving the arguments alone.
    ///
    /// The program is resolved like [resolve]; when it cannot be resolved the command is
    /// returned unchanged, because the failure to report is still the name the caller
    /// asked for — replacing it with an absolute path that does not exist would hide it.
    ///
    /// @param command the program and its arguments
    /// @return the command to hand to a `ProcessBuilder`
    public static List<String> startable(List<String> command) {
        if (command.isEmpty()) {
            return command;
        }
        Optional<Path> program = resolve(command.get(0));
        if (program.isEmpty()) {
            return command;
        }
        String resolved = program.get().toString();
        if (resolved.equals(command.get(0))) {
            return command;
        }
        List<String> rewritten = new ArrayList<>(command);
        rewritten.set(0, resolved);
        return List.copyOf(rewritten);
    }

    /// Rewrites a command's program without touching a command line that has none.
    ///
    /// @param command the command line
    /// @return the startable command line
    public static String startableProgram(String command) {
        return resolve(command).map(Path::toString).orElse(command);
    }

    /// Reports whether a name carries a directory component.
    ///
    /// @param name the name
    /// @return whether it is a path rather than a bare name
    private static boolean hasSeparator(String name) {
        return name.indexOf('/') >= 0 || name.indexOf('\\') >= 0;
    }

    /// Finds the startable form of one path: exactly as given, else with an extension.
    ///
    /// The order is what makes `node_modules/.bin/tsx` resolve to `tsx.cmd` on Windows.
    /// There every file without an extension is a POSIX shell script — the one shape
    /// `CreateProcess` refuses outright — so a name whose extension was left off has to
    /// be completed before the extensionless file is considered, and that file only as a
    /// last resort for a native binary that happens to have no extension.
    ///
    /// @param path the path to complete
    /// @return the file to start, or empty
    private static Optional<Path> startableAmong(Path path) {
        if (Files.isRegularFile(path) && !usesExtensions()) {
            return Optional.of(path);
        }
        for (String extension : extensions()) {
            Path completed = path.resolveSibling(path.getFileName() + extension);
            if (Files.isRegularFile(completed)) {
                return Optional.of(completed);
            }
        }
        // An extension the system already recognises, or a native binary with none.
        return Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
    }
}
