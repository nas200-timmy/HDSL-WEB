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
/// ahead on `PATH`, and `DSH_HOME`. The TUI therefore opens the same sessions
/// the web page lists.
@NotNullByDefault
public final class DshTui {

    private DshTui() {
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
        List<String> command = new ArrayList<>(plan.command());
        command.add("tui");
        if (resumeSession != null && !resumeSession.isBlank()) {
            command.add("--resume");
            command.add(resumeSession);
        }
        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.putAll(plan.environment());
        environment.put("TERM", "xterm-256color");
        environment.put("COLORTERM", "truecolor");
        return new TerminalTarget("dsh", instance.id(), command, plan.workingDirectory(),
                Map.copyOf(environment));
    }
}
