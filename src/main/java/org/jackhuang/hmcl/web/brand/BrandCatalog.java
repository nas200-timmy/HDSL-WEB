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
package org.jackhuang.hmcl.web.brand;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jackhuang.hmcl.dsh.NpmRegistry;
import org.jackhuang.hmcl.util.platform.OutboundTls;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// The published versions of each [Brand]'s npm package — the version picker
/// the panel shows for these categories.
///
/// The list comes from the same registry the panel's download-source setting
/// points at ([NpmRegistry.effective]), so a mirror-first deployment sees the
/// same versions the installer will fetch. Results are cached briefly: version
/// lists change often (these upstreams ship weekly or faster) but a picker
/// rendered seconds apart should not refetch.
///
/// Upstreams publish CI noise under their packages — OpenCode alone has over
/// twelve thousand `0.0.0-snapshot-*` style tags. Only strict `x.y.z` versions
/// reach the picker; everything else is filtered out.
@NotNullByDefault
public final class BrandCatalog {

    /// A strict semantic version: the only shape the picker admits.
    private static final Pattern CLEAN_VERSION = Pattern.compile("^\\d+\\.\\d+\\.\\d+$");

    /// How long a fetched list stays fresh.
    private static final long TTL_MILLIS = Duration.ofMinutes(30).toMillis();

    /// The HTTP timeout for one registry round trip.
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    /// One fetched state of a brand's version list.
    ///
    /// Package-private so [parse] is directly testable.
    record Snapshot(List<String> versions, String latest, long fetchedAt) {
    }

    private static final Map<Brand, Snapshot> CACHE = new ConcurrentHashMap<>();

    /// A client per fetch, not a cached one: `HttpClient` captures the JVM's default
    /// `SSLContext` when it is built, and an acknowledgement given later (see
    /// [org.jackhuang.hmcl.util.platform.OutboundTls]) has to reach the very next
    /// request or the picker stays empty until a restart. Building one costs
    /// milliseconds against a network round trip, and the list itself is cached for
    /// half an hour.
    private static HttpClient client() {
        return HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /// The last certificate failure per brand, so the picker can say why it is empty
    /// instead of looking like "this project has no releases".
    private static final Map<Brand, OutboundTls.Trouble> TROUBLE = new ConcurrentHashMap<>();

    /// The certificate failure that emptied this brand's list, if that is what
    /// happened and it is recent enough to still be the reason.
    ///
    /// @param brand the brand
    /// @return the failure, or `null`
    public static @Nullable OutboundTls.Trouble trouble(Brand brand) {
        OutboundTls.Trouble trouble = TROUBLE.get(brand);
        if (trouble == null) {
            return null;
        }
        return java.time.Instant.now().isBefore(trouble.at().plus(Duration.ofMinutes(TROUBLE_MEMORY_MINUTES)))
                ? trouble : null;
    }

    /// How long a recorded failure keeps being offered as the explanation. Long
    /// enough to survive a person opening the page after the failed prefetch,
    /// short enough that a fixed network stops producing a stale excuse.
    private static final long TROUBLE_MEMORY_MINUTES = 30;

    private BrandCatalog() {
    }

    /// The brand's clean versions, newest first. Empty when the registry cannot
    /// be reached and nothing is cached yet.
    ///
    /// @param brand the brand
    /// @return the versions, newest first
    public static List<String> versions(Brand brand) {
        return installable(brand).versions();
    }

    /// The version the registry's `latest` dist-tag points at, when it is one
    /// of the clean versions; otherwise the newest clean version.
    ///
    /// @param brand the brand
    /// @return the version to preselect, never empty when versions exist
    public static String latest(Brand brand) {
        return installable(brand).latest();
    }

    /// Octop needs a patched SPA per release; only versions this panel build
    /// actually bundles are offered. Other brands pass their picker through.
    private static Snapshot installable(Brand brand) {
        Snapshot snapshot = snapshot(brand);
        if (brand != Brand.OCTOP) {
            return snapshot;
        }
        List<String> versions = snapshot.versions().stream()
                .filter(BrandInstaller::hasBundledOctopDashboard)
                .toList();
        String latest = versions.isEmpty() ? "" : versions.get(0);
        return new Snapshot(versions, latest, snapshot.fetchedAt());
    }

    /// Prefetches every brand's list — called once at server startup so the
    /// first picker render is instant. Failures are logged and left to the
    /// on-demand path.
    public static void warm() {
        for (Brand brand : Brand.values()) {
            try {
                snapshot(brand);
            } catch (RuntimeException e) {
                LOG.warning("Could not warm the " + brand.id() + " version catalog", e);
            }
        }
    }

    private static Snapshot snapshot(Brand brand) {
        Snapshot cached = CACHE.get(brand);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.fetchedAt() < TTL_MILLIS) {
            return cached;
        }
        Snapshot fetched = fetch(brand);
        if (fetched.versions().isEmpty()) {
            // Never cache an empty list: the upstream was unreachable (a
            // transient network blip on this deployment's route to GitHub is
            // routine), and caching it would leave the picker blank for the
            // whole TTL after the network came back. The next call retries.
            return cached != null ? cached : fetched;
        }
        CACHE.put(brand, fetched);
        return fetched;
    }

    /// Parses a registry package document into a snapshot: the clean versions
    /// (newest first) and the `latest` dist-tag when it names one of them.
    ///
    /// Package-private so the filtering and ordering are testable without a
    /// registry round trip.
    ///
    /// @param body the registry's package document
    /// @return the snapshot; empty on any parse failure
    static Snapshot parse(String body) {
        try {
            JsonObject document = JsonParser.parseString(body).getAsJsonObject();
            List<String> clean = new ArrayList<>();
            for (String version : document.getAsJsonObject("versions").keySet()) {
                if (CLEAN_VERSION.matcher(version).matches()) {
                    clean.add(version);
                }
            }
            clean.sort(NEWEST_FIRST);
            String latest = "";
            JsonObject tags = document.has("dist-tags") ? document.getAsJsonObject("dist-tags") : null;
            if (tags != null && tags.has("latest") && tags.get("latest").isJsonPrimitive()) {
                String tagged = tags.get("latest").getAsString();
                if (clean.contains(tagged)) {
                    latest = tagged;
                }
            }
            if (latest.isEmpty() && !clean.isEmpty()) {
                latest = clean.get(0);
            }
            return new Snapshot(List.copyOf(clean), latest, System.currentTimeMillis());
        } catch (RuntimeException e) {
            return new Snapshot(List.of(), "", System.currentTimeMillis());
        }
    }

    /// One registry round trip: the package document's `versions` keys, filtered
    /// to clean semver and sorted newest first, plus the `latest` dist-tag.
    private static Snapshot fetch(Brand brand) {
        if (brand.fromGitHub()) {
            return fetchGitHub(brand);
        }
        String registry = NpmRegistry.effective().registry();
        String encoded = brand.npmPackage().replace("/", "%2f");
        HttpRequest request = HttpRequest.newBuilder(URI.create(registry + "/" + encoded))
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response = client().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                LOG.warning("Registry answered " + response.statusCode() + " for " + brand.npmPackage());
                return new Snapshot(List.of(), "", System.currentTimeMillis());
            }
            return parse(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Snapshot(List.of(), "", System.currentTimeMillis());
        } catch (Exception e) {
            recordIfTrustFailure(brand, URI.create(registry).getHost(), e);
            LOG.warning("Could not fetch versions of " + brand.npmPackage() + " from " + registry, e);
            return new Snapshot(List.of(), "", System.currentTimeMillis());
        }
    }

    /// One GitHub REST call for a GitHub brand: `/repos/<slug>/tags`, whose
    /// array of `{name, …}` entries is parsed by [parseGitHubTags].
    ///
    /// Deliberately the REST API, not `git ls-remote`: this deployment's route
    /// to `github.com:443` cuts the git transport mid-handshake routinely,
    /// while `api.github.com` answers in well under a second. Unauthenticated
    /// requests are rate limited (60/hour per IP), which the 30-minute cache
    /// keeps comfortably clear of.
    ///
    /// Retried a few times: on this network a single fetch failing is routine,
    /// and the alternative is a blank picker until the next refresh.
    private static Snapshot fetchGitHub(Brand brand) {
        String url = "https://api.github.com/repos/" + brand.githubSlug() + "/tags?per_page=100";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .GET()
                .build();
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                HttpResponse<String> response = client().send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 == 2) {
                    return parseGitHubTags(response.body(), brand.tagPrefix(), brand::isReleaseVersion);
                }
                LOG.warning("GitHub answered " + response.statusCode() + " for " + url);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Snapshot(List.of(), "", System.currentTimeMillis());
            } catch (Exception e) {
                recordIfTrustFailure(brand, "api.github.com", e);
                LOG.warning("Could not fetch tags of " + brand.repoUrl()
                        + " (attempt " + attempt + " of 3)", e);
            }
        }
        return new Snapshot(List.of(), "", System.currentTimeMillis());
    }

    /// Keeps the certificate failures: they are the ones a person can act on, and
    /// the ones whose symptom (an empty picker) says nothing on its own.
    private static void recordIfTrustFailure(Brand brand, String host, Throwable error) {
        if (OutboundTls.isTrustFailure(error)) {
            TROUBLE.put(brand, new OutboundTls.Trouble(host,
                    String.valueOf(error.getMessage()), java.time.Instant.now()));
        }
    }

    /// Parses the `/repos/<slug>/tags` document into a snapshot: each entry's
    /// `name`, the brand's tag prefix stripped, only clean `x.y.z` kept,
    /// deduplicated and sorted newest first; `latest` is the newest.
    ///
    /// Package-private so the filtering and ordering are testable without a
    /// network round trip.
    ///
    /// @param body the API's JSON array
    /// @return the snapshot; empty on any parse failure or when no clean tag exists
    static Snapshot parseGitHubTags(String body) {
        return parseGitHubTags(body, "v");
    }

    /// [parseGitHubTags(String)] with an explicit tag prefix.
    static Snapshot parseGitHubTags(String body, String tagPrefix) {
        return parseGitHubTags(body, tagPrefix, version -> CLEAN_VERSION.matcher(version).matches());
    }

    /// The same, with a brand-specific release predicate: Octop publishes beta
    /// tags (`1.0.3b1`) that the strict semantic-version filter would hide.
    static Snapshot parseGitHubTags(String body, String tagPrefix,
                                    java.util.function.Predicate<String> accepted) {
        try {
            com.google.gson.JsonArray array = JsonParser.parseString(body).getAsJsonArray();
            java.util.Set<String> seen = new java.util.HashSet<>();
            List<String> clean = new ArrayList<>();
            for (com.google.gson.JsonElement element : array) {
                if (!element.isJsonObject() || !element.getAsJsonObject().has("name")) {
                    continue;
                }
                String name = element.getAsJsonObject().get("name").getAsString();
                String version = tagPrefix.isEmpty() || !name.startsWith(tagPrefix)
                        ? name
                        : name.substring(tagPrefix.length());
                if (accepted.test(version) && seen.add(version)) {
                    clean.add(version);
                }
            }
            clean.sort(NEWEST_FIRST);
            String latest = clean.isEmpty() ? "" : clean.get(0);
            return new Snapshot(List.copyOf(clean), latest, System.currentTimeMillis());
        } catch (RuntimeException e) {
            return new Snapshot(List.of(), "", System.currentTimeMillis());
        }
    }

    /// Numeric component-wise ordering: `2.10.0` sorts above `2.9.0`, which
    /// plain string ordering gets wrong.
    private static final Comparator<String> NEWEST_FIRST = (left, right) -> {
        String[] a = left.split("\\.");
        String[] b = right.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? leadingNumber(a[i]) : 0;
            int y = i < b.length ? leadingNumber(b[i]) : 0;
            if (x != y) {
                return Integer.compare(y, x);
            }
        }
        return right.compareTo(left);
    };

    /// The numeric prefix of a version component: `3` for `3`, `3` for
    /// `3b1` (Octop's beta tags), `0` when the component starts with a letter.
    private static int leadingNumber(String component) {
        int end = 0;
        while (end < component.length() && Character.isDigit(component.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return 0;
        }
        try {
            return Integer.parseInt(component.substring(0, end));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }
}
