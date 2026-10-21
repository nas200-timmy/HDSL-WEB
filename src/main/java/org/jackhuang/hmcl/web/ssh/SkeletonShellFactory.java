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
package org.jackhuang.hmcl.web.ssh;

import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.shell.ShellFactory;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/// The placeholder shell of the M1 skeleton: a session that has passed SSH
/// authentication lands here, is told the interactive menu is coming, and is
/// sent on its way with exit 0.
///
/// M2 replaces this with the real `PanelShellFactory` (TUI gate + instance
/// menu). Until then this proves the whole pipeline — bind, host key,
/// key/password authentication, channel wiring — without pretending to be a
/// shell. Crucially the server still sets **no** `CommandFactory` and no
/// SFTP subsystem, so `ssh host cmd` requests keep being refused outright.
@NotNullByDefault
public final class SkeletonShellFactory implements ShellFactory {

    /// The one line every M1 session gets before it exits.
    public static final String MESSAGE = "HDSL-web SSH 通道已就绪；交互菜单将在后续版本提供。";

    @Override
    public Command createShell(ChannelSession channel) {
        return new SkeletonCommand();
    }

    private static final class SkeletonCommand implements Command {
        private @Nullable InputStream stdin;
        private @Nullable OutputStream stdout;
        private @Nullable OutputStream stderr;
        private @Nullable ExitCallback callback;

        @Override
        public void setInputStream(InputStream in) {
            this.stdin = in;
        }

        @Override
        public void setOutputStream(OutputStream out) {
            this.stdout = out;
        }

        @Override
        public void setErrorStream(OutputStream err) {
            this.stderr = err;
        }

        @Override
        public void setExitCallback(ExitCallback callback) {
            this.callback = callback;
        }

        @Override
        public void start(ChannelSession channel, Environment env) throws IOException {
            OutputStream out = stdout;
            if (out != null) {
                out.write((MESSAGE + "\r\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            ExitCallback exit = callback;
            if (exit != null) {
                exit.onExit(0);
            }
        }

        @Override
        public void destroy(ChannelSession channel) {
            // Nothing to reap: the skeleton holds no process and no threads.
        }
    }
}
