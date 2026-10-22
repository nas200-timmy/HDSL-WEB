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

import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/// Windows-only: opens the agent TUI in the desktop's own console window.
///
/// A bare Windows deployment has a desktop and a shell; a web-rendered PTY
/// would be a worse copy of what the machine already provides (and Windows
/// services do not share the interactive desktop anyway). So on Windows the
/// button asks the panel process — which runs in that desktop session — to
/// start a console window in the instance workspace instead of opening an
/// xterm in the browser.
///
/// The console is `cmd.exe`; that is the point here: this path *is* the
/// desktop terminal, and the agent is started inside it. The web terminal's
/// "no shell in the chain" invariant applies to the POSIX PTY path, not to
/// handing the user their own console.
@NotNullByDefault
public final class DesktopTerminal {

    private DesktopTerminal() {
    }

    /// Writes a launcher script into the instance workspace and asks Windows to
    /// open it in a new console window.
    ///
    /// @throws IOException when the platform is not Windows or the console
    ///                     cannot be started
    public static void open(TerminalTarget target) throws IOException {
        if (!org.jackhuang.hmcl.web.desktop.DesktopIntegration.supported()) {
            throw new IOException("no interactive Windows desktop session (a service has nowhere to show a console)");
        }
        Files.createDirectories(target.cwd());
        Path script = Files.createTempFile(target.cwd(), ".hdsl-terminal-", ".cmd");
        Files.writeString(script, script(target), StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING);
        try {
            new ProcessBuilder("cmd.exe", "/c", "start", "hdsl terminal", "cmd.exe", "/k",
                    script.toString())
                    .directory(target.cwd().toFile())
                    .start();
        } catch (IOException e) {
            throw new IOException("could not open a console window: " + e.getMessage(), e);
        }
    }

    /// The launcher script body. Visible for tests: it is the only part of
    /// this class that can be checked without a Windows desktop.
    static String script(TerminalTarget target) {
        StringBuilder script = new StringBuilder();
        script.append("@echo off\r\n");
        script.append("cd /d ").append(quote(target.cwd().toString())).append("\r\n");
        target.environment().entrySet().stream()
                .filter(entry -> entry.getKey().equals("HOME") || entry.getKey().equals("USERPROFILE")
                        || entry.getKey().equals("TERM") || entry.getKey().equals("COLORTERM"))
                .forEach(entry -> script.append("set \"").append(entry.getKey()).append('=')
                        .append(entry.getValue()).append("\"\r\n"));
        for (int i = 0; i < target.command().size(); i++) {
            if (i > 0) {
                script.append(' ');
            }
            script.append(quote(target.command().get(i)));
        }
        script.append("\r\n");
        return script.toString();
    }

    /// cmd.exe quoting: wrap in quotes and double any quote. The command and
    /// the paths come from the agent's own installation, never from user input.
    private static String quote(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
