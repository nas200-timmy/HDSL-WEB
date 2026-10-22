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

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/// The Windows desktop launcher is a generated script; its shape is testable
/// without a Windows desktop.
class DesktopTerminalTest {

    @Test
    void theScriptChangesDirectorySetsHomeAndQuotesTheCommand() {
        TerminalTarget target = new TerminalTarget("kimi", "i1",
                List.of("C:\\Program Files\\node.exe", "C:\\work dir\\main.mjs", "--resume", "s 1"),
                Path.of("C:\\work dir"),
                Map.of("HOME", "C:\\home", "TERM", "xterm-256color"));

        String script = DesktopTerminal.script(target);

        assertTrue(script.startsWith("@echo off"), script);
        assertTrue(script.contains("cd /d \"C:\\work dir\""), script);
        assertTrue(script.contains("set \"HOME=C:\\home\""), script);
        assertTrue(script.contains("\"C:\\Program Files\\node.exe\""), script);
        assertTrue(script.contains("\"C:\\work dir\\main.mjs\""), script);
        assertTrue(script.contains("\"s 1\""), script);
    }
}
