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

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/// Everything the terminal needs to start one instance's agent: the exact
/// argv, the working directory and the complete environment.
///
/// The command is a list, never a command line: the adapter decides the
/// program and its arguments, and nothing between the browser and the agent
/// can add one. `command[0]` is expected to be the interpreter or the agent
/// binary itself — never a shell or a package-manager shim (see
/// [BrandTui]).
///
/// @param brand       the brand id (`kimi`, `opencode`, …)
/// @param instanceId  the instance the terminal belongs to
/// @param command     program and arguments
/// @param cwd         the instance workspace
/// @param environment the child's complete environment
@NotNullByDefault
public record TerminalTarget(
        String brand,
        String instanceId,
        List<String> command,
        Path cwd,
        Map<String, String> environment) {

    public TerminalTarget {
        if (command.isEmpty()) {
            throw new IllegalArgumentException("a terminal target needs a command");
        }
        command = List.copyOf(command);
        environment = Map.copyOf(environment);
    }
}
