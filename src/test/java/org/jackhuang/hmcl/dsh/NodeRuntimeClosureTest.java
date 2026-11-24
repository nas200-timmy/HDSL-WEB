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
package org.jackhuang.hmcl.dsh;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/// The refusal a Node install gets when the image has no C++ runtime.
///
/// The message is the deliverable: "unpacked but does not run" is the symptom,
/// and the two ways out — install the panel's closure, or install the
/// distribution packages — are what the operator needs before retrying. It is a
/// pure function of the missing names and the attempted fix, so the wording can
/// be tested without a machine that lacks the libraries.
class NodeRuntimeClosureTest {

    @Test
    void theRefusalNamesTheMissingLibrariesAndBothFixes() {
        DshException error = NodeRuntimeManager.missingClosureError(
                List.of("libstdc++.so.6", "libatomic.so.1"), "network is unreachable");

        String message = error.getMessage();
        assertTrue(message.startsWith("node_runtime_closure_missing:"), message);
        assertTrue(message.contains("libstdc++.so.6"), message);
        assertTrue(message.contains("libatomic.so.1"), message);
        assertTrue(message.contains("bootstrap runtime closure"), message);
        assertTrue(message.contains("network is unreachable"), message);
        assertTrue(message.contains("apt-get install libstdc++6 libgcc-s1 libatomic1"), message);
    }

    @Test
    void aRefusalWithoutAnAttemptStillSaysTheClosureCouldNotBeInstalled() {
        String message = NodeRuntimeManager.missingClosureError(List.of("libgcc_s.so.1"), null)
                .getMessage();
        assertTrue(message.contains("could not be installed"), message);
        assertTrue(message.contains("libgcc_s.so.1"), message);
    }
}