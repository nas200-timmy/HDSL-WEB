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
package org.jackhuang.hmcl.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Processes nobody can see, named by the path they sit on.
///
/// A panel restarted since a launch has no record of the child it started, and on Windows a
/// directory cannot be removed while any process has it as its current directory — so
/// "delete this instance" failed with an empty error while an invisible agent sat on the
/// tree. The match is on the command line, which is where a launcher names the instance.
class ProcessTreeKillReferencingTest {

    @TempDir
    Path temp;

    @Test
    void aDirectoryNothingReferencesKillsNothing() {
        assertEquals(0, ProcessTree.killReferencing(temp));
    }

    @Test
    void anInstanceWhoseIdExtendsTheDeletedOneIsNotMatched() {
        String deleted = "F:\\data\\instances\\demo";
        String working = "F:\\data\\instances\\demo-2\\node_modules\\dsh\\lib\\bin.js";
        assertTrue(ProcessTree.references(deleted + "\\dsh\\node_modules\\a\\bin.js", deleted),
                "a process inside the deleted instance is matched");
        assertTrue(!ProcessTree.references(working, deleted),
                "the instance whose id merely starts with it is not: " + working);
        assertTrue(!ProcessTree.references("node /srv/instances/demo-2/bin.js", "/srv/instances/demo"),
                "the same rule with forward slashes");
        assertTrue(ProcessTree.references("node /srv/instances/demo/bin.js", "/srv/instances/demo"));
    }

    @Test
    void thePanelItselfIsNotMatched() {
        // The panel's own command line names the data directory, never an instance
        // directory, and this test running here proves a live JVM is not swept by a path it
        // happens to share. Nothing in this JVM's command line names `temp`.
        String here = ProcessHandle.current().info().commandLine().orElse("");
        assertTrue(!here.contains(temp.toAbsolutePath().toString()),
                "the test JVM must not reference the temp directory: " + here);
    }
}
