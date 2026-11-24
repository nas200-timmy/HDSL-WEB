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
package org.jackhuang.hmcl.web.transcode.runtime;

import org.jackhuang.hmcl.util.versioning.VersionNumber;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/// Picks the best catalogue entry for a host and a required feature set.
///
/// The selector is intentionally explainable: every answer carries a `reason`
/// the settings page can show. It never reaches the network and never installs;
/// it only ranks what the catalogue already says is verified.
@NotNullByDefault
public final class FfmpegSelector {

    private FfmpegSelector() {
    }

    public record Recommendation(FfmpegCatalog.Entry entry, String reason) {
    }

    /// Returns the best entry, or throws when the catalogue has nothing that can
    /// run on this host.
    ///
    /// `requiredFeatures` may be empty; in that case a build with the broadest
    /// declared feature set wins. A missing feature lowers the score but does not
    /// exclude the entry — the install step and the UI are where "this build
    /// cannot do X" must be said plainly, not silently papered over.
    public static Recommendation select(FfmpegCatalog.Catalog catalog,
                                        FfmpegCapabilities.Host host,
                                        Set<String> requiredFeatures) throws IOException {
        Set<String> required = lower(requiredFeatures);
        List<Scored> candidates = new ArrayList<>();
        for (FfmpegCatalog.Entry entry : catalog.entries()) {
            if (!entry.matches(host.os(), host.arch(), host.libc())) {
                continue;
            }
            Set<String> have = lower(entry.features());
            int covered = 0;
            for (String feature : required) {
                if (have.contains(feature)) {
                    covered++;
                }
            }
            double score = required.isEmpty()
                    ? 0.01 * have.size()
                    : 3.0 * covered / required.size() + 0.01 * have.size();
            candidates.add(new Scored(entry, score, covered));
        }
        if (candidates.isEmpty()) {
            throw new IOException("ffmpeg_no_candidate: no catalog entry for "
                    + host.describe() + describeRequired(required));
        }
        candidates.sort(Comparator
                .comparingDouble(Scored::score).reversed()
                .thenComparing((left, right) ->
                        VersionNumber.compare(right.entry().ffmpegVersion(), left.entry().ffmpegVersion())));
        Scored best = candidates.get(0);
        String reason = "host " + host.describe()
                + "; features " + best.covered() + "/" + required.size()
                + (required.isEmpty() ? " (none required)" : " required")
                + "; license " + best.entry().license();
        return new Recommendation(best.entry(), reason);
    }

    private static String describeRequired(Set<String> required) {
        return required.isEmpty() ? "" : " with features " + String.join(", ", required);
    }

    private static Set<String> lower(Iterable<String> values) {
        Set<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                result.add(value.trim().toLowerCase(Locale.ROOT));
            }
        }
        return result;
    }

    private record Scored(FfmpegCatalog.Entry entry, double score, int covered) {
    }
}