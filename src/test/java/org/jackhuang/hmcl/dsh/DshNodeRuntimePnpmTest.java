/*
 * HDSL-web
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

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Which toolchain a pnpm-needing path picks, when `PATH`'s is not the one that can do the work.
///
/// The failure this pins is a real one: a machine with a system Node.js and no pnpm reported
/// "pnpm was not found on PATH" on every DeepSeek Harness install, while the panel's own managed
/// runtime — the one that is installed *with* pnpm beside it — sat in the data directory and was
/// never consulted. "Some Node.js" and "a Node.js that can install" are not the same answer.
class DshNodeRuntimePnpmTest {

    @Test
    void aPathRuntimeWithoutPnpmYieldsToTheManagedOne() {
        DshNodeRuntime system = runtime("/usr/bin/node", null);
        DshNodeRuntime managed = runtime("/data/hdsl/runtimes/22.19.0/bin/node",
                "/data/hdsl/runtimes/22.19.0/bin/pnpm");

        assertEquals(Optional.of(managed),
                DshNodeRuntime.preferPnpm(Optional.of(system), Optional.of(managed)),
                "the managed runtime is the one that can run pnpm");
    }

    @Test
    void aPathRuntimeWithPnpmIsKept() {
        DshNodeRuntime system = runtime("/opt/node/bin/node", "/opt/node/bin/pnpm");
        DshNodeRuntime managed = runtime("/data/hdsl/runtimes/22.19.0/bin/node",
                "/data/hdsl/runtimes/22.19.0/bin/pnpm");

        assertEquals(Optional.of(system),
                DshNodeRuntime.preferPnpm(Optional.of(system), Optional.of(managed)),
                "what the operator put on PATH wins when it can do the job");
    }

    @Test
    void thePathRuntimeIsStillReturnedWhenNothingHasPnpm() {
        DshNodeRuntime system = runtime("/usr/bin/node", null);

        assertEquals(Optional.of(system),
                DshNodeRuntime.preferPnpm(Optional.of(system), Optional.empty()),
                "returning it lets the caller name the runtime it looked at, which is the whole diagnosis");
        assertEquals(Optional.empty(),
                DshNodeRuntime.preferPnpm(Optional.empty(), Optional.empty()),
                "no Node.js at all is a different answer, and a different message");
    }

    @Test
    void theRefusalNamesTheRuntimeTheFloorAndBothWaysOut() {
        DshNodeRuntime system = runtime("/usr/bin/node", null);

        String message = system.pnpmMissing("installing DeepSeek Harness").getMessage();

        assertTrue(message.contains("/usr/bin/node"),
                "the runtime that was found is the missing piece: " + message);
        assertTrue(message.contains(DshNodeRuntime.MINIMUM_PNPM), message);
        assertTrue(message.contains("npm install -g pnpm"), message);
        assertTrue(message.contains("Settings -> Node.js"),
                "and the managed runtime that brings pnpm: " + message);
    }

    /// A runtime descriptor, without spawning anything.
    ///
    /// @param node        the node path
    /// @param pnpm        the pnpm path, or `null` when the runtime has none
    /// @return the descriptor
    private static DshNodeRuntime runtime(String node, String pnpm) {
        return new DshNodeRuntime(Path.of(node), "22.19.0", Path.of("/usr/bin/npm"), "10.9.9",
                pnpm == null ? null : Path.of(pnpm), pnpm == null ? null : "11.7.0");
    }
}
