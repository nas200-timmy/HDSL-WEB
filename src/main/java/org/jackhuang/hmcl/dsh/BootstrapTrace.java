/*
 * HDSL
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

import org.jackhuang.hmcl.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/// One line per bootstrap artifact, in a form a user can paste into a report.
///
/// Debugging a minimal image is hard enough without the panel deciding on its
/// own which download failed: every artifact says what was needed, where it
/// came from, what the digest was, and where it landed. A silent fallback is
/// the one thing this class exists to prevent — if a bootstrap step is skipped,
/// it says so.
@NotNullByDefault
public final class BootstrapTrace {

    private static final Logger LOG = Logger.LOG;

    private BootstrapTrace() {
    }

    /// A download that was verified and activated.
    ///
    /// @param kind        `closure`, `ca`, `python`, `toolchain`, `uv`
    /// @param need        why it was fetched — an artifact name, a version
    /// @param source      the URL
    /// @param digest      how the bytes were checked, e.g. `snapshot-sha256:…`
    /// @param destination where it now lives
    public static void installed(String kind, String need, @Nullable String source,
                                 @Nullable String digest, Path destination) {
        LOG.info(line("installed", kind, need, source, digest, destination, null));
    }

    /// An already-installed artifact that was used without a download.
    public static void reused(String kind, String need, @Nullable Path destination) {
        LOG.info(line("reused", kind, need, null, null, destination, null));
    }

    /// Something that was needed and is not there: the actionable half.
    public static void refused(String kind, String need, String detail) {
        LOG.warning(line("refused", kind, need, null, null, null, detail));
    }

    /// A decision worth naming: "nothing was downloaded, because…".
    public static void note(String kind, String detail) {
        LOG.info(line("note", kind, "-", null, null, null, detail));
    }

    /// The pure form, so a test can pin the shape without a log handler.
    ///
    /// @return one line, never `null`
    static String line(String status, String kind, String need, @Nullable String source,
                       @Nullable String digest, @Nullable Path destination,
                       @Nullable String detail) {
        StringBuilder builder = new StringBuilder("bootstrap: status=").append(status)
                .append(" kind=").append(kind)
                .append(" need=").append(need);
        if (source != null && !source.isBlank()) {
            builder.append(" source=").append(source);
        }
        if (digest != null && !digest.isBlank()) {
            builder.append(" digest=").append(digest);
        }
        if (destination != null) {
            builder.append(" dest=").append(destination);
        }
        if (detail != null && !detail.isBlank()) {
            builder.append(" detail=").append(detail);
        }
        return builder.toString();
    }
}