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

import org.apache.sshd.server.channel.ChannelSession;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/// The seam a menu choice crosses: `kind` says which section the row came from
/// (`dsh`, `brand` or `session`), `id` names the target within that section.
///
/// M2 ships [MenuShellFactory.PrintingAttachSupport], a stub that only
/// acknowledges the choice — the real spawn/byte-pump lands in M3
/// (docs/ssh-terminal-channel.md). The implementation runs on the shell's
/// worker thread and returns when the attachment ends; the menu then
/// re-renders. The streams are the channel's own: whatever the
/// implementation does not consume stays buffered for the next menu read, so
/// an attach that wants the keyboard must read `in` itself — and accept that
/// the menu may already have buffered a line of it.
@NotNullByDefault
public interface AttachSupport {

    /// Attaches the channel's terminal to the chosen target.
    ///
    /// @param kind    `dsh`, `brand` or `session`
    /// @param id      the instance id, `brand:instance` or `instance:uuid`
    /// @param in      the channel's stdin
    /// @param out     the channel's stdout
    /// @param channel the channel itself, for PTY/window metadata
    /// @throws IOException when the attachment fails; the menu survives it
    void attach(String kind, String id, InputStream in, OutputStream out, ChannelSession channel)
            throws IOException;
}
