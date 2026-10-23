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

import org.jackhuang.hmcl.dsh.DshException;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshLauncher;
import org.jackhuang.hmcl.web.instance.InstanceRuntime;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// dsh's interactive TUI: the web launch plan with `tui` instead of a server
/// surface.
///
/// [DshLauncher#plan] is the same spec a browser launch uses — the account
/// route, the provider key in the environment, the instance's own Node runtime
/// ahead on `PATH`, and `DSH_HOME`. The plan's command is the **web** launch
/// (`node <bin.js> --profile web …`); appending `tui` to that would keep the
/// web profile and pass `tui` as an app argument. The TUI is
/// `dsh tui [--resume <id>]`, i.e. the interpreter and entry of the plan with
/// the `tui` subcommand in place of the web flags.
@NotNullByDefault
public final class DshTui {

    private DshTui() {
    }

    /// The TUI argv from a web launch plan: interpreter + entry, then `tui`
    /// (and `--resume`). The plan's web flags are deliberately dropped — they
    /// belong to the web profile, not to the TUI.
    ///
    /// Package-private for the test that pins the shape.
    static List<String> tuiCommand(List<String> planCommand, @Nullable String resumeSession)
            throws DshException {
        if (planCommand.size() < 2) {
            throw new DshException("unexpected dsh launch plan: " + planCommand);
        }
        List<String> command = new ArrayList<>(planCommand.subList(0, 2));
        command.add("tui");
        if (resumeSession != null && !resumeSession.isBlank()) {
            command.add("--resume");
            command.add(resumeSession);
        }
        return List.copyOf(command);
    }

    /// Builds the terminal target for one dsh instance.
    ///
    /// @param instance      the instance
    /// @param resumeSession a session id to resume, or `null`
    /// @throws DshException when the instance is not installed or its runtime
    ///                      is missing
    public static TerminalTarget target(DshInstance instance, @Nullable String resumeSession)
            throws DshException {
        DshLauncher.LaunchPlan plan = DshLauncher.plan(instance, InstanceRuntime.accountOf(instance));
        List<String> command = tuiCommand(plan.command(), resumeSession);
        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.putAll(plan.environment());
        environment.put("TERM", "xterm-256color");
        environment.put("COLORTERM", "truecolor");
        return new TerminalTarget("dsh", instance.id(), command, plan.workingDirectory(),
                Map.copyOf(environment));
    }
}
