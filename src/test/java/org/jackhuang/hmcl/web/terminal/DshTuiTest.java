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
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// `dsh tui` is the interpreter + entry, not the web command with `tui`
/// appended: the web flags must not survive into the TUI.
class DshTuiTest {

    @Test
    void theTuiCommandDropsTheWebFlags() throws Exception {
        List<String> plan = List.of("/opt/node/bin/node", "/data/dsh/bin.js",
                "--profile", "web", "--port", "3252", "--no-open");

        assertEquals(List.of("/opt/node/bin/node", "/data/dsh/bin.js", "tui"),
                DshTui.tuiCommand(plan, null));
        assertEquals(List.of("/opt/node/bin/node", "/data/dsh/bin.js", "tui", "--resume", "abc"),
                DshTui.tuiCommand(plan, "abc"));
    }

    @Test
    void aPlanWithoutAnEntryIsRefused() {
        assertThrows(DshException.class, () -> DshTui.tuiCommand(List.of("node"), null));
    }
}
