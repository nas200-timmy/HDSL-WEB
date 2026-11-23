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
package org.jackhuang.hmcl.web.node;

import org.jackhuang.hmcl.dsh.DshEnvironment;
import org.jackhuang.hmcl.dsh.DshNodeRuntime;
import org.jackhuang.hmcl.dsh.NodeSelection;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// The gate every install path shares: is there a Node.js that can run `pnpm`?
///
/// The failure this exists for is a first deployment that has never opened
/// Settings -> Node.js. Without the gate the instance is created, an install
/// task starts, and the reason surfaces minutes later in a log the new user
/// does not know to open. Answering `node_missing` before the task exists lets
/// the panel say "install a runtime first" while the click is still happening.
///
/// The resolution is the same one the Node page's dropdown and the launcher
/// use ([NodeSelection]), with `needsPnpm` on: an install runs `pnpm`, and a
/// bare `node` on `PATH` is the case that used to pass a naive check.
@NotNullByDefault
public final class NodePreflight {

    private NodePreflight() {
    }

    /// The reason an install cannot start, or `null` when it can.
    ///
    /// @param selection the instance's runtime selection; `null`/blank means the
    ///                  launcher-wide default, which is what a new instance gets
    /// @return the human-readable reason, or `null` when a usable runtime exists
    public static @Nullable String missingReason(@Nullable String selection) {
        String effective = selection == null || selection.isBlank()
                ? DshEnvironment.nodeRuntimeDefault()
                : selection;
        NodeSelection.Resolution resolved = NodeSelection.resolve(
                NodeSelection.parse(effective), null, true);
        if (resolved.runtime() == null || !resolved.compatible()) {
            return resolved.reason();
        }
        if (!resolved.runtime().canManagePlugins()) {
            return "Node.js " + resolved.runtime().nodeVersion()
                    + " has no pnpm beside it; installing needs " + DshNodeRuntime.requirement();
        }
        return null;
    }

    /// The same question for brand installs, which pick their own runtime
    /// instead of going through an instance selection. Which one they need
    /// depends on how the brand is installed: npm brands run `pnpm add`, the
    /// GitHub-source build runs `npm ci` + `npm run build`, and a prebuilt
    /// GitHub release (Octop) needs no Node at all.
    ///
    /// @param needsPnpm whether the brand installs through `pnpm add`
    /// @return the human-readable reason, or `null` when the needed tools exist
    public static @Nullable String missingBrandReason(boolean needsPnpm) {
        DshNodeRuntime runtime = needsPnpm
                ? DshNodeRuntime.detectWithPnpm().orElse(null)
                : DshNodeRuntime.detectForNpm().orElse(null);
        if (runtime == null) {
            return "Node.js was not found on PATH and no panel-managed runtime is installed ("
                    + DshNodeRuntime.requirement() + ")";
        }
        if (needsPnpm && !runtime.canManagePlugins()) {
            return runtime.pnpmMissing("installing this brand").getMessage();
        }
        if (!needsPnpm && !runtime.canInstall()) {
            return "Node.js " + runtime.nodeVersion()
                    + " has no npm beside it; building this brand needs one.";
        }
        return null;
    }

    /// The `409` body every entry point writes, so the frontend can key off one
    /// stable prefix instead of matching prose.
    ///
    /// @param reason what [NodeSelection] answered
    /// @return the message to hand to the client
    public static String message(String reason) {
        return "node_missing: " + reason;
    }
}