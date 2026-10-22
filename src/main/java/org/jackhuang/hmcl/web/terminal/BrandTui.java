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

import org.jackhuang.hmcl.web.brand.Brand;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/// Turns an installed brand package into the argv of its **interactive** TUI.
///
/// The web launch plans in the brand package run `kimi web` / `opencode web`;
/// a terminal is the same installation started without that subcommand. Two
/// rules keep the terminal from becoming a shell:
///
/// - the adapter resolves the interpreter or the agent's own binary and hands
///   `posix_spawn` an argv list — no shell, no `.bin` shim;
/// - the environment is the instance's, not the panel's: `HOME` points at the
///   instance home, so the TUI reads the same sessions the web mode does.
@NotNullByDefault
public interface BrandTui {

    /// Builds the terminal target for one instance.
    ///
    /// @param packageDir the installed release directory (contains `node_modules`)
    /// @param home       the instance home (the child's `HOME`)
    /// @param workspace  the instance workspace (the child's cwd)
    /// @param instanceId the instance id, for the target and the audit trail
    /// @param resumeSession a session id to resume, or `null` for a new one
    /// @return the argv, cwd and environment to spawn
    /// @throws IOException when the installed package lacks the entry point the
    ///                     adapter needs
    TerminalTarget target(Path packageDir, Path home, Path workspace,
                          String instanceId, @Nullable String resumeSession) throws IOException;

    /// The adapter for a brand, or `null` when the brand has no native TUI.
    static @Nullable BrandTui forBrand(String brandId) {
        Brand brand = Brand.fromId(brandId);
        if (brand == null) {
            return null;
        }
        return switch (brand) {
            case KIMI -> new KimiTui();
            case OPENCODE -> new OpencodeTui();
            // Claude Web is a web workbench around the Claude Code CLI and has
            // no interactive TUI of its own; dsh's TUI is a plugin and gets
            // its adapter in a later stage.
            default -> null;
        };
    }

    /// The environment every brand TUI starts with: the panel's, with the
    /// instance's home and a terminal type the TUIs can render into.
    ///
    /// `HOME` is what keeps the sessions and credentials inside the instance
    /// volume; `USERPROFILE` is the same thing for Node on Windows, which
    /// ignores `HOME` when resolving `~`.
    static Map<String, String> environment(Path home) {
        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.put("HOME", home.toString());
        environment.put("USERPROFILE", home.toString());
        environment.put("TERM", "xterm-256color");
        environment.put("COLORTERM", "truecolor");
        return Map.copyOf(environment);
    }
}
