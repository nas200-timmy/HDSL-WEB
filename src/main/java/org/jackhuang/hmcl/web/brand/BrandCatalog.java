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
import org.jetbrains.annotations.NotNullByDefault;

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

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private BrandCatalog() {
    }

    /// The brand's clean versions, newest first. Empty when the registry cannot
    /// be reached and nothing is cached yet.
    ///
    /// @param brand the brand
    /// @return the versions, newest first
    public static List<String> versions(Brand brand) {
        return snapshot(brand).versions();
    }

    /// The version the registry's `latest` dist-tag points at, when it is one
    /// of the clean versions; otherwise the newest clean version.
    ///
    /// @param brand the brand
    /// @return the version to preselect, never empty when versions exist
    public static String latest(Brand brand) {
        return snapshot(brand).latest();
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
        if (fetched.versions().isEmpty() && cached != null) {
            // The registry is down; a stale list beats an empty picker.
            return cached;
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
        String registry = NpmRegistry.effective().registry();
        String encoded = brand.npmPackage().replace("/", "%2f");
        HttpRequest request = HttpRequest.newBuilder(URI.create(registry + "/" + encoded))
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                LOG.warning("Registry answered " + response.statusCode() + " for " + brand.npmPackage());
                return new Snapshot(List.of(), "", System.currentTimeMillis());
            }
            return parse(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Snapshot(List.of(), "", System.currentTimeMillis());
        } catch (Exception e) {
            LOG.warning("Could not fetch versions of " + brand.npmPackage() + " from " + registry, e);
            return new Snapshot(List.of(), "", System.currentTimeMillis());
        }
    }

    /// Numeric component-wise ordering: `2.10.0` sorts above `2.9.0`, which
    /// plain string ordering gets wrong.
    private static final Comparator<String> NEWEST_FIRST = (left, right) -> {
        String[] a = left.split("\\.");
        String[] b = right.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? Integer.parseInt(a[i]) : 0;
            int y = i < b.length ? Integer.parseInt(b[i]) : 0;
            if (x != y) {
                return Integer.compare(y, x);
            }
        }
        return right.compareTo(left);
    };
}
