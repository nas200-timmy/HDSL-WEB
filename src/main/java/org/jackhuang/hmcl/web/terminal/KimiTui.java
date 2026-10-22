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

import org.jackhuang.hmcl.util.platform.Executables;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// Kimi Code's interactive TUI: `node <dist/main.mjs> [--resume <id>]`.
///
/// The installed `node_modules/.bin/kimi` is a symlink to the package's
/// `dist/main.mjs`; resolving it to the real file and starting Node directly
/// is what keeps the process tree free of the shim, whose shebang would add a
/// `/usr/bin/env` step and a second interpreter.
///
/// Sessions live under `<HOME>/.kimi-code/sessions`, which the instance home
/// supplies — so the TUI lists exactly the sessions `kimi web` wrote.
@NotNullByDefault
public final class KimiTui implements BrandTui {

    @Override
    public TerminalTarget target(Path packageDir, Path home, Path workspace,
                                 String instanceId, @Nullable String resumeSession) throws IOException {
        Path entry = Executables.resolve(packageDir.resolve("node_modules/.bin/kimi").toString())
                .orElseThrow(() -> new IOException(
                        "kimi entry point missing under " + packageDir + " (node_modules/.bin/kimi)"));
        // Defensive: a package whose shim resolves to something other than the
        // JS entry is not the install this adapter knows how to start.
        if (!entry.getFileName().toString().endsWith(".mjs")
                && !entry.getFileName().toString().endsWith(".js")) {
            throw new IOException("kimi entry point is not a JS file: " + entry);
        }
        Path node = Executables.resolve("node")
                .orElseThrow(() -> new IOException("node is not on PATH; the Kimi Code instance needs it"));

        List<String> command = new ArrayList<>(List.of(node.toString(), entry.toString()));
        if (resumeSession != null && !resumeSession.isBlank()) {
            command.add("--resume");
            command.add(resumeSession);
        }
        return new TerminalTarget("kimi", instanceId, command, workspace, BrandTui.environment(home));
    }
}
