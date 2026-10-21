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

import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;

/// The platform gate for the SSH terminal channel and developer mode
/// (docs/ssh-terminal-channel.md, threat model row "平台").
///
/// Linux is fully supported, macOS is let through but marked **unverified** —
/// it has never run on a real Mac and the doctor says so — and Windows plus
/// every other platform are hard-banned. The ban has three layers so that a
/// `server.yaml` copied across platforms can never smuggle the channel in:
///
/// 1. the settings-page section is disabled client-side (with the reason),
/// 2. the toggle API answers 409 `platform_unsupported`,
/// 3. [SshService.start] refuses to listen and audits the blocked attempt.
@NotNullByDefault
public final class PlatformGuard {

    /// What the current platform may do with the SSH channel.
    public enum Verdict {
        /// Linux: the channel is supported and tested.
        SUPPORTED,
        /// macOS: allowed but not yet verified on real hardware — the
        /// settings page and the doctor carry the "未实测" label.
        SUPPORTED_UNVERIFIED,
        /// Windows and everything else: the channel is off-limits.
        UNSUPPORTED
    }

    private PlatformGuard() {
    }

    /// Where [OperatingSystem.CURRENT_OS] stands. Only Linux counts as
    /// supported; macOS passes as unverified; everything else is banned.
    public static Verdict verdict() {
        return switch (OperatingSystem.CURRENT_OS) {
            case LINUX -> Verdict.SUPPORTED;
            case MACOS -> Verdict.SUPPORTED_UNVERIFIED;
            default -> Verdict.UNSUPPORTED;
        };
    }

    /// Whether the channel may run here at all (any verdict but [Verdict.UNSUPPORTED]).
    public static boolean supported() {
        return verdict() != Verdict.UNSUPPORTED;
    }
}
