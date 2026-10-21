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
/// The implementation runs on the shell's worker thread and blocks for as
/// long as the attachment lasts — a real attach (M3's
/// [DshAttachSupport]) pumps bytes until the child process or the channel
/// ends. What comes back is the exit code the whole SSH session should end
/// with: `>= 0` ends the session there (the process's own code, or `0` when
/// the channel broke first), while `-1` means "nothing was attached, the
/// menu comes back" — the refusal answers (brand deferred, busy home, not
/// installed) all take that road, printing their reason to `out` first.
///
/// The streams are the channel's own: whatever the implementation does not
/// consume stays buffered for the next menu read, so an attach that wants
/// the keyboard must read `in` itself — and accept that the menu may
/// already have buffered a line of it.
@NotNullByDefault
public interface AttachSupport {

    /// Attaches the channel's terminal to the chosen target.
    ///
    /// @param kind    `dsh`, `brand` or `session`
    /// @param id      the instance id, `brand:instance` or `instance:uuid`
    /// @param in      the channel's stdin
    /// @param out     the channel's stdout
    /// @param channel the channel itself, for PTY/signal metadata
    /// @return the session exit code (`>= 0`), or `-1` to go back to the menu
    /// @throws IOException when the channel's streams fail; the menu survives it
    int attach(String kind, String id, InputStream in, OutputStream out, ChannelSession channel)
            throws IOException;
}
