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
package org.jackhuang.hmcl.util.io;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/// Picks which origin should serve one pinned artifact, by sampling real bytes
/// from every candidate before the download starts.
///
/// The catalogue's digest remains the only trust anchor: this class chooses
/// speed, never bytes. A mirror that answers HTML for everything (a portal page
/// is the usual shape) is rejected on its first bytes, and one whose
/// `Content-Range` total disagrees with the pinned size is rejected before the
/// download begins. A mirror that is within [MIRROR_PREFERENCE] of the fastest
/// candidate still wins, so the upstream host is spared when the difference is
/// small; when no mirror answers, the official URL is tried anyway.
///
/// Probes are cached for [TTL]; a failed source is skipped for a growing
/// window so a dead mirror is not sampled again on every install. At most one
/// probe runs at a time.
@NotNullByDefault
public final class DownloadSourcePicker {

    /// What the sample fetcher answered. `head` holds the first bytes for the
    /// magic check, `bytes` is what was actually read, and `totalBytes` is the
    /// full artifact size when the response said it (`Content-Range` or
    /// `Content-Length`), or `-1` when it did not.
    public record Sample(int status, byte[] head, long bytes, long durationNanos,
                         long totalBytes) {
    }

    /// Fetches the first bytes of a URL. Implemented with HTTP here, with
    /// fakes in tests.
    public interface Sampler {
        Sample sample(String url) throws IOException, InterruptedException;
    }

    public record Probe(String url, boolean ok, long speedKBps, long sampleBytes,
                        long durationMs, String error, Instant probedAt, int failures,
                        @Nullable Instant skipUntil) {
    }

    public record Choice(String url, boolean probed, List<Probe> probes, String reason) {
    }

    static final long SAMPLE_BYTES = 512 * 1024;
    static final Duration PER_SOURCE_TIMEOUT = Duration.ofSeconds(6);
    static final Duration TTL = Duration.ofHours(6);
    static final Duration BASE_SKIP = Duration.ofMinutes(30);
    static final Duration MIN_REPROBE = Duration.ofMinutes(2);
    static final int MAX_FAILURES = 4;
    /// A mirror within this fraction of the best speed wins: being 15% slower
    /// than the upstream but avoiding it is the right trade.
    static final double MIRROR_PREFERENCE = 0.85;

    private final Sampler sampler;
    private final Clock clock;
    private final Object lock = new Object();
    private final Map<String, Probe> probes = new LinkedHashMap<>();

    public DownloadSourcePicker() {
        this(httpSampler(), Clock.systemUTC());
    }

    DownloadSourcePicker(Sampler sampler, Clock clock) {
        this.sampler = sampler;
        this.clock = clock;
    }

    /// Chooses an origin for one artifact.
    ///
    /// A list with a single candidate is answered without touching the
    /// network: probing it would spend a sample to learn nothing.
    ///
    /// @param officialUrl the pinned URL, always a candidate
    /// @param mirrors     the catalogue's mirrors, in preference order
    /// @param expectedSize the pinned size, or `0` when it is not stated
    /// @param magic       the first bytes every candidate must serve, or `null`
    /// @return the chosen URL and what every candidate answered
    public Choice choose(String officialUrl, @Nullable List<String> mirrors,
                         long expectedSize, byte @Nullable [] magic) {
        synchronized (lock) {
            List<String> candidates = candidates(officialUrl, mirrors);
            if (candidates.size() <= 1) {
                return new Choice(officialUrl, false, List.of(), "single source");
            }
            List<Probe> results = new ArrayList<>();
            for (String url : candidates) {
                Probe previous = probes.get(url);
                Instant now = clock.instant();
                boolean fresh = previous != null && previous.ok()
                        && previous.probedAt() != null
                        && Duration.between(previous.probedAt(), now).compareTo(TTL) < 0
                        && (previous.skipUntil() == null || !previous.skipUntil().isAfter(now));
                boolean coolingDown = previous != null && previous.skipUntil() != null
                        && previous.skipUntil().isAfter(now);
                if (fresh || coolingDown) {
                    results.add(previous);
                    continue;
                }
                Probe probe = sampleOne(url, expectedSize, magic);
                probes.put(url, probe);
                results.add(probe);
            }
            String chosen = chooseFastest(candidates, results);
            long best = results.stream().filter(Probe::ok)
                    .mapToLong(Probe::speedKBps).max().orElse(0);
            return new Choice(chosen, true, List.copyOf(results),
                    best == 0 ? "no candidate answered; trying the official URL"
                            : "fastest usable candidate (" + best + " KB/s)");
        }
    }

    /// A finished download tells the picker how the chosen source did, so a
    /// mirror that serves truncated bytes is skipped before the next install.
    ///
    /// @param url     the source that was tried
    /// @param success whether the full file passed its size/digest checks
    /// @param error   why it failed, or `null`
    public void recordOutcome(String url, boolean success, @Nullable String error) {
        synchronized (lock) {
            Probe previous = probes.get(url);
            Instant now = clock.instant();
            if (success) {
                probes.put(url, new Probe(url, true,
                        previous == null ? 0 : previous.speedKBps(),
                        previous == null ? 0 : previous.sampleBytes(),
                        previous == null ? 0 : previous.durationMs(),
                        "", previous == null ? now : previous.probedAt(),
                        previous == null ? 0 : previous.failures(), null));
            } else {
                probes.put(url, failed(url, now,
                        error == null ? "download failed" : error));
            }
        }
    }

    /// The candidate list: mirrors in catalogue order, then the official URL,
    /// without duplicates.
    ///
    /// @param officialUrl the pinned URL
    /// @param mirrors     the catalogue's mirrors, in preference order
    /// @return the ordered candidates
    public static List<String> candidates(String officialUrl, @Nullable List<String> mirrors) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        if (mirrors != null) {
            for (String mirror : mirrors) {
                if (mirror != null && !mirror.isBlank()) {
                    ordered.add(mirror.trim());
                }
            }
        }
        ordered.add(officialUrl);
        return List.copyOf(ordered);
    }

    private Probe sampleOne(String url, long expectedSize, byte @Nullable [] magic) {
        Instant probedAt = clock.instant();
        long start = System.nanoTime();
        try {
            Sample sample = sampler.sample(url);
            long durationMs = sample.durationNanos() > 0
                    ? Math.max(1, sample.durationNanos() / 1_000_000)
                    : Math.max(1, (System.nanoTime() - start) / 1_000_000);
            if (sample.status() != 200 && sample.status() != 206) {
                return failed(url, probedAt, "HTTP " + sample.status());
            }
            if (magic != null && !startsWith(sample.head(), magic)) {
                return failed(url, probedAt, "not the expected archive (portal page?)");
            }
            if (expectedSize > 0 && sample.totalBytes() > 0
                    && sample.totalBytes() != expectedSize) {
                return failed(url, probedAt, "size " + sample.totalBytes()
                        + " != pinned " + expectedSize);
            }
            if (sample.bytes() <= 0) {
                return failed(url, probedAt, "empty sample");
            }
            long speed = sample.bytes() * 1000L / durationMs / 1024L;
            Probe previous = probes.get(url);
            return new Probe(url, true, speed, sample.bytes(), durationMs, "", probedAt,
                    previous == null ? 0 : previous.failures(), null);
        } catch (Exception e) {
            return failed(url, probedAt, e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private Probe failed(String url, Instant probedAt, String error) {
        Probe previous = probes.get(url);
        int failures = (previous == null ? 0 : previous.failures()) + 1;
        Duration skip = BASE_SKIP.multipliedBy(1L << Math.min(failures - 1, MAX_FAILURES));
        return new Probe(url, false, 0, 0, 0, error, probedAt, failures,
                probedAt.plus(skip));
    }

    private static String chooseFastest(List<String> candidates, List<Probe> results) {
        long best = results.stream().filter(Probe::ok)
                .mapToLong(Probe::speedKBps).max().orElse(0);
        if (best == 0) {
            // Nothing answered. The official URL is still attempted — the
            // probe is a shortcut, not a gate.
            return candidates.get(candidates.size() - 1);
        }
        for (String url : candidates) {
            Probe probe = results.stream()
                    .filter(item -> item.url().equals(url)).findFirst().orElse(null);
            if (probe != null && probe.ok()
                    && probe.speedKBps() >= best * MIRROR_PREFERENCE) {
                return url;
            }
        }
        return candidates.get(candidates.size() - 1);
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (value[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    // ----------------------------------------------------------------- probe --

    private static Sampler httpSampler() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        return url -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(PER_SOURCE_TIMEOUT)
                    .header("User-Agent", "HDSL-web")
                    .header("Range", "bytes=0-" + (SAMPLE_BYTES - 1))
                    .GET()
                    .build();
            HttpResponse<InputStream> response =
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            long total = totalBytes(response);
            InputStream body = response.body();
            AtomicReference<InputStream> stream = new AtomicReference<>(body);
            AtomicBoolean timedOut = new AtomicBoolean();
            // A read that never returns would hold the whole install; closing
            // the body from the watchdog unblocks it, and the bytes read so
            // far are still a speed signal.
            Thread watchdog = new Thread(() -> {
                try {
                    Thread.sleep(PER_SOURCE_TIMEOUT.toMillis());
                } catch (InterruptedException e) {
                    return;
                }
                timedOut.set(true);
                try {
                    stream.get().close();
                } catch (IOException ignored) {
                    // The read loop will see the closed stream.
                }
            }, "download-source-probe");
            watchdog.setDaemon(true);
            watchdog.start();
            byte[] buffer = new byte[8192];
            byte[] head = new byte[0];
            long bytes = 0;
            long deadline = System.nanoTime() + PER_SOURCE_TIMEOUT.toNanos();
            try (InputStream in = body) {
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    if (bytes == 0 && read > 0) {
                        head = Arrays.copyOf(buffer, Math.min(read, 8));
                    }
                    bytes += read;
                    if (bytes >= SAMPLE_BYTES || System.nanoTime() > deadline) {
                        break;
                    }
                }
            } catch (IOException e) {
                if (!timedOut.get()) {
                    throw e;
                }
            } finally {
                watchdog.interrupt();
            }
            return new Sample(response.statusCode(), head, bytes, 0, total);
        };
    }

    /// The full artifact size from a response: `Content-Range`'s total for a
    /// 206, `Content-Length` for a 200, or `-1` when neither says.
    private static long totalBytes(HttpResponse<?> response) {
        String range = response.headers().firstValue("content-range").orElse(null);
        if (range != null) {
            int slash = range.lastIndexOf('/');
            if (slash >= 0) {
                try {
                    String total = range.substring(slash + 1).trim();
                    if (!"*".equals(total)) {
                        return Long.parseLong(total);
                    }
                } catch (NumberFormatException ignored) {
                    // A malformed header is "unknown", not a size mismatch.
                }
            }
        }
        return response.headers().firstValueAsLong("content-length").orElse(-1);
    }
}