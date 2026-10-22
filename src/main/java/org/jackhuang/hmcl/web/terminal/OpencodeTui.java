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
package org.jackhuang.hmcl.web.terminal;

import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/// OpenCode's interactive TUI: `<native binary> [--session <id>]`.
///
/// The npm package ships `bin/opencode.cjs` as a launcher that spawns a
/// platform-specific native binary (`@opencode/cli-<os>-<arch>/bin/opencode`).
/// Starting through that launcher would make the Node launcher the root
/// process, so the adapter resolves the native binary itself, with the same
/// variants the launcher would try (baseline / musl on x64).
///
/// Sessions live under the XDG data directory (`~/.local/share/opencode`),
/// which follows the instance `HOME` the target carries.
@NotNullByDefault
public final class OpencodeTui implements BrandTui {

    @Override
    public TerminalTarget target(Path packageDir, Path home, Path workspace,
                                 String instanceId, @Nullable String resumeSession) throws IOException {
        Path binary = resolveBinary(packageDir);
        List<String> command = new ArrayList<>(List.of(binary.toString()));
        if (resumeSession != null && !resumeSession.isBlank()) {
            command.add("--session");
            command.add(resumeSession);
        }
        return new TerminalTarget("opencode", instanceId, command, workspace, BrandTui.environment(home));
    }

    /// The native CLI for this platform, tried in the order the shipped
    /// launcher would try it.
    static Path resolveBinary(Path packageDir) throws IOException {
        String os = switch (OperatingSystem.CURRENT_OS) {
            case LINUX -> "linux";
            case MACOS -> "darwin";
            case WINDOWS -> "windows";
            default -> null;
        };
        if (os == null) {
            throw new IOException("opencode has no native build for " + OperatingSystem.CURRENT_OS);
        }
        String arch = architecture();
        String base = "@opencode/cli-" + os + "-" + arch;
        String executable = "windows".equals(os) ? "opencode.exe" : "opencode";
        List<String> names = new ArrayList<>(List.of(base));
        if ("x64".equals(arch)) {
            names.add(base + "-baseline");
            names.add(base + "-musl");
            names.add(base + "-baseline-musl");
        } else {
            names.add(base + "-musl");
        }
        for (String name : names) {
            Path candidate = packageDir.resolve("node_modules").resolve(name).resolve("bin").resolve(executable);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IOException("opencode native binary missing under " + packageDir
                + " (looked for " + String.join(", ", names) + ")");
    }

    private static String architecture() throws IOException {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return switch (arch) {
            case "x86_64", "amd64" -> "x64";
            case "aarch64", "arm64" -> "arm64";
            case "arm" -> "arm";
            default -> throw new IOException("unsupported CPU architecture for opencode: " + arch);
        };
    }
}
