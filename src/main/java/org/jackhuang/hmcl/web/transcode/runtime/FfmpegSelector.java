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
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/// Picks the best catalogue entry for a host, a version line and a required
/// feature set.
///
/// The selector is intentionally explainable: every answer carries a `reason`
/// the settings page can show. It never reaches the network and never installs;
/// it only ranks what the catalogue already says is verified.
///
/// Filter order is fixed: platform/libc compatibility (including `minGlibc`) →
/// version line (`7.1`, `8.1`, or `auto`) → required feature coverage → version
/// order. A candidate that cannot cover every required feature is not eligible;
/// silently installing a build that lacks a preset's encoder is exactly what the
/// catalogue is meant to prevent.
@NotNullByDefault
public final class FfmpegSelector {

    private static final String AUTO = "auto";

    private FfmpegSelector() {
    }

    public record Recommendation(FfmpegCatalog.Entry entry, String reason) {
    }

    /// Backwards-compatible entry point: `auto` picks the highest version line
    /// that can satisfy the requirements.
    public static Recommendation select(FfmpegCatalog.Catalog catalog,
                                        FfmpegCapabilities.Host host,
                                        Set<String> requiredFeatures) throws IOException {
        return select(catalog, host, requiredFeatures, AUTO);
    }

    /// Returns the best entry, or throws when the catalogue has nothing that can
    /// run on this host and satisfy the request.
    ///
    /// `preferredMajor` is `auto`, `7.1`, `8.1`, or another `major` present in
    /// the catalogue. An explicit line never silently switches to another one:
    /// the caller gets `ffmpeg_no_candidate` instead.
    public static Recommendation select(FfmpegCatalog.Catalog catalog,
                                        FfmpegCapabilities.Host host,
                                        Set<String> requiredFeatures,
                                        @Nullable String preferredMajor) throws IOException {
        Set<String> required = lower(requiredFeatures);
        String requested = normalizeMajor(preferredMajor);
        if (!AUTO.equals(requested) && !requested.matches("\\d+\\.\\d+")) {
            throw new IOException("ffmpeg_no_candidate: unsupported major '" + preferredMajor
                    + "' (expected auto or one of " + catalog.majors() + ")");
        }

        List<Candidate> platformCandidates = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        Map<String, List<String>> rejectedByMajor = new LinkedHashMap<>();
        for (FfmpegCatalog.Entry entry : catalog.entries()) {
            // A source recipe is catalogue metadata for M2; there is no artifact
            // to install yet, so it can never be a recommendation.
            if (!entry.isBinary() || !entry.matches(host.os(), host.arch(), host.libc())) {
                continue;
            }
            String major = entry.major();
            String libcProblem = libcProblem(host, entry);
            boolean libcOk = libcProblem == null;
            if (!libcOk) {
                reject(rejected, rejectedByMajor, entry, major, libcProblem);
            }
            Set<String> have = lower(entry.features());
            boolean covers = have.containsAll(required);
            if (!covers) {
                reject(rejected, rejectedByMajor, entry, major, "missing " + missing(required, have));
            }
            platformCandidates.add(new Candidate(entry, major, libcOk, covers));
        }

        if (platformCandidates.isEmpty()) {
            throw new IOException("ffmpeg_no_candidate: no catalog entry for "
                    + host.describe() + libcSuffix(host) + describeRequired(required));
        }

        if (!AUTO.equals(requested)) {
            List<Candidate> eligible = new ArrayList<>();
            for (Candidate candidate : platformCandidates) {
                if (candidate.libcOk() && candidate.covers()
                        && requested.equalsIgnoreCase(candidate.major())) {
                    eligible.add(candidate);
                }
            }
            if (eligible.isEmpty()) {
                throw new IOException("ffmpeg_no_candidate: 请求的 major " + requested
                        + " 没有可用条目 for host " + host.describe() + libcSuffix(host)
                        + describeRequired(required) + summarize(rejected));
            }
            Candidate chosen = best(eligible);
            return new Recommendation(chosen.entry(),
                    reason(host, chosen, required, "requested", null));
        }

        List<Candidate> eligible = new ArrayList<>();
        for (Candidate candidate : platformCandidates) {
            if (candidate.libcOk() && candidate.covers()) {
                eligible.add(candidate);
            }
        }
        if (eligible.isEmpty()) {
            throw new IOException("ffmpeg_no_candidate: no catalog entry for "
                    + host.describe() + libcSuffix(host) + describeRequired(required)
                    + summarize(rejected));
        }

        String chosenMajor = highestMajor(eligible);
        List<Candidate> inMajor = new ArrayList<>();
        for (Candidate candidate : eligible) {
            if (candidate.major().equalsIgnoreCase(chosenMajor)) {
                inMajor.add(candidate);
            }
        }
        Candidate chosen = best(inMajor);

        String fallback = null;
        String highestPlatformMajor = highestMajor(platformCandidates);
        if (!chosenMajor.isEmpty() && !highestPlatformMajor.isEmpty()
                && VersionNumber.compare(chosenMajor, highestPlatformMajor) < 0) {
            List<String> skipped = new ArrayList<>();
            for (String major : distinctMajors(platformCandidates)) {
                if (!major.isEmpty() && VersionNumber.compare(major, chosenMajor) > 0) {
                    skipped.add(major);
                }
            }
            if (!skipped.isEmpty()) {
                List<String> skippedReasons = new ArrayList<>();
                for (String major : skipped) {
                    skippedReasons.addAll(rejectedByMajor.getOrDefault(major, List.of()));
                }
                fallback = "major(s) " + String.join(", ", skipped)
                        + " have no entry satisfying the required features and host libc; fell back to "
                        + chosenMajor + summarize(skippedReasons);
            }
        }
        return new Recommendation(chosen.entry(),
                reason(host, chosen, required, AUTO, fallback));
    }

    /// Picks a `kind=source` catalogue entry for this host and version line.
    ///
    /// Unlike a binary artifact, a source entry cannot promise a feature: whether
    /// `--enable-libx264` survives depends on the host's development packages.
    /// The entry's `features` therefore describe what the recipe *can* enable,
    /// and the selector only rejects a request those declarations cannot cover.
    /// The build records what was actually enabled in `featuresDeclared`.
    public static Recommendation selectSource(FfmpegCatalog.Catalog catalog,
                                              FfmpegCapabilities.Host host,
                                              Set<String> requiredFeatures,
                                              @Nullable String preferredMajor) throws IOException {
        Set<String> required = lower(requiredFeatures);
        String requested = normalizeMajor(preferredMajor);
        if (!AUTO.equals(requested) && !requested.matches("\\d+\\.\\d+")) {
            throw new IOException("ffmpeg_no_candidate: unsupported major '" + preferredMajor
                    + "' (expected auto or one of " + catalog.majors() + ")");
        }

        List<FfmpegCatalog.Entry> candidates = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        boolean sawSource = false;
        for (FfmpegCatalog.Entry entry : catalog.entries()) {
            if (!entry.isSource()) {
                continue;
            }
            sawSource = true;
            if (!entry.matches(host.os(), host.arch(), host.libc())) {
                rejected.add(entry.id() + ": platform " + entry.os() + "/" + entry.arch()
                        + "/" + entry.libc() + " does not match " + host.describe());
                continue;
            }
            if (!AUTO.equals(requested) && !requested.equalsIgnoreCase(entry.major())) {
                continue;
            }
            Set<String> have = lower(entry.features());
            if (!have.containsAll(required)) {
                rejected.add(label(entry, entry.major()) + ": missing " + missing(required, have));
                continue;
            }
            candidates.add(entry);
        }

        if (!sawSource) {
            throw new IOException("ffmpeg_no_candidate: the catalogue has no source entry"
                    + describeRequired(required));
        }
        if (candidates.isEmpty()) {
            throw new IOException("ffmpeg_no_candidate: no source entry for " + host.describe()
                    + (AUTO.equals(requested) ? "" : " on major " + requested)
                    + describeRequired(required) + summarize(rejected));
        }

        candidates.sort(Comparator
                .comparing((FfmpegCatalog.Entry entry) -> VersionNumber.asVersion(entry.ffmpegVersion()))
                .reversed()
                .thenComparing(FfmpegCatalog.Entry::major, Comparator.reverseOrder())
                .thenComparing(FfmpegCatalog.Entry::id));
        FfmpegCatalog.Entry chosen = candidates.get(0);
        String recipe = chosen.buildRecipe().isEmpty() ? "default" : chosen.buildRecipe();
        String reason = "source build " + chosen.ffmpegVersion()
                + " (major " + (chosen.major().isEmpty() ? "unknown" : chosen.major()) + ")"
                + " from " + (chosen.vendor().isEmpty() ? "the catalogue" : chosen.vendor())
                + "; recipe " + recipe
                + "; features are enabled only when the host toolchain provides their development packages"
                + (required.isEmpty() ? "" : "; requested " + String.join(", ", required));
        return new Recommendation(chosen, reason);
    }

    private static Candidate best(List<Candidate> candidates) {
        List<Candidate> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator
                .comparing((Candidate candidate) ->
                        VersionNumber.asVersion(candidate.entry().ffmpegVersion()))
                .reversed()
                .thenComparing((Candidate candidate) -> candidate.entry().features().size(),
                        Comparator.reverseOrder())
                .thenComparing(candidate -> candidate.entry().id()));
        return sorted.get(0);
    }

    private static String reason(FfmpegCapabilities.Host host,
                                 Candidate chosen,
                                 Set<String> required,
                                 String selection,
                                 @Nullable String fallback) {
        StringBuilder reason = new StringBuilder("host ").append(host.describe());
        if (!host.libcVersion().isEmpty()) {
            reason.append(" (libc ").append(host.libcVersion()).append(")");
        }
        reason.append("; major ")
                .append(chosen.major().isEmpty() ? "unknown" : chosen.major())
                .append(" (").append(selection).append(")");
        int covered = required.isEmpty() ? 0 : required.size();
        reason.append("; features ").append(covered).append("/").append(required.size());
        if (required.isEmpty()) {
            reason.append(" (none required)");
        }
        reason.append("; vendor ")
                .append(chosen.entry().vendor().isEmpty() ? "unknown" : chosen.entry().vendor());
        String minGlibc = chosen.entry().minGlibc();
        if (!minGlibc.isEmpty()) {
            reason.append("; minGlibc ").append(minGlibc)
                    .append(FfmpegCapabilities.supportsMinGlibc(host, minGlibc)
                            ? " satisfied" : " not satisfied");
        }
        if (fallback != null) {
            reason.append("; ").append(fallback);
        }
        return reason.toString();
    }

    private static @Nullable String libcProblem(FfmpegCapabilities.Host host,
                                                FfmpegCatalog.Entry entry) {
        String minGlibc = entry.minGlibc();
        if (minGlibc.isBlank()) {
            return null;
        }
        if (!"glibc".equalsIgnoreCase(host.libc())) {
            return "minGlibc " + minGlibc + " requires glibc, host is " + host.libc();
        }
        if (host.libcVersion().isEmpty()) {
            return "minGlibc " + minGlibc + " requires glibc >= " + minGlibc
                    + ", host libc version unknown";
        }
        if (!FfmpegCapabilities.supportsMinGlibc(host, minGlibc)) {
            return "minGlibc " + minGlibc + " not met (host glibc " + host.libcVersion() + ")";
        }
        return null;
    }

    private static String highestMajor(List<Candidate> candidates) {
        String highest = "";
        for (Candidate candidate : candidates) {
            String major = candidate.major();
            if (!major.isEmpty()
                    && (highest.isEmpty() || VersionNumber.compare(major, highest) > 0)) {
                highest = major;
            }
        }
        return highest;
    }

    private static List<String> distinctMajors(List<Candidate> candidates) {
        List<String> majors = new ArrayList<>();
        for (Candidate candidate : candidates) {
            String major = candidate.major();
            if (!major.isEmpty() && !majors.contains(major)) {
                majors.add(major);
            }
        }
        majors.sort(VersionNumber::compare);
        return majors;
    }

    private static String normalizeMajor(@Nullable String preferredMajor) {
        if (preferredMajor == null || preferredMajor.isBlank()) {
            return AUTO;
        }
        return preferredMajor.trim().toLowerCase(Locale.ROOT);
    }

    private static String label(FfmpegCatalog.Entry entry, String major) {
        return entry.id() + (major.isEmpty() ? "" : " (major " + major + ")");
    }

    private static void reject(List<String> rejected,
                               Map<String, List<String>> rejectedByMajor,
                               FfmpegCatalog.Entry entry,
                               String major,
                               String reason) {
        String message = label(entry, major) + ": " + reason;
        rejected.add(message);
        rejectedByMajor.computeIfAbsent(major, key -> new ArrayList<>()).add(message);
    }

    private static String missing(Set<String> required, Set<String> have) {
        List<String> missing = new ArrayList<>();
        for (String feature : required) {
            if (!have.contains(feature)) {
                missing.add(feature);
            }
        }
        return String.join(", ", missing);
    }

    private static String summarize(List<String> rejected) {
        if (rejected.isEmpty()) {
            return "";
        }
        int limit = Math.min(5, rejected.size());
        String joined = String.join("; ", rejected.subList(0, limit));
        return "; rejected: " + joined + (rejected.size() > limit ? "; …" : "");
    }

    private static String libcSuffix(FfmpegCapabilities.Host host) {
        return host.libcVersion().isEmpty() ? "" : " (libc " + host.libcVersion() + ")";
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

    private record Candidate(FfmpegCatalog.Entry entry, String major, boolean libcOk, boolean covers) {
    }
}