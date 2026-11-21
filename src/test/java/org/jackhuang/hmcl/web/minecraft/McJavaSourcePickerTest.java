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
package org.jackhuang.hmcl.web.minecraft;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McJavaSourcePickerTest {

    @TempDir
    Path dataDir;

    /// A clock the tests can move forward.
    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-19T00:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final byte[] GZIP_HEAD = {(byte) 0x1f, (byte) 0x8b, 8, 0, 0, 0, 0, 0};
    private static final byte[] ZIP_HEAD = {'P', 'K', 3, 4, 0, 0, 0, 0};
    private static final byte[] HTML_HEAD = {'<', '!', 'D', 'O', 'C', 'T', 'Y', 'P'};

    private static McJavaRuntimes.Release temurinRelease() {
        return new McJavaRuntimes.Release(21, "21.0.13+9",
                "https://github.com/adoptium/temurin21-binaries/releases/download/"
                        + "jdk-21.0.13%2B9/OpenJDK21U-jdk_x64_linux_hotspot_21.0.13_9.tar.gz",
                "aa".repeat(32), 200_000_000L, McJavaRuntimes.Vendor.TEMURIN);
    }

    /// url -> (head bytes, durationNanos). Counts calls per URL.
    private static final class FakeSampler implements McJavaSourcePicker.Sampler {
        final Map<String, McJavaSourcePicker.Sample> answers = new ConcurrentHashMap<>();
        final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

        @Override
        public McJavaSourcePicker.Sample sample(String url) {
            calls.computeIfAbsent(url, key -> new AtomicInteger()).incrementAndGet();
            McJavaSourcePicker.Sample sample = answers.get(url);
            if (sample == null) {
                throw new IllegalStateException("unexpected probe URL " + url);
            }
            return sample;
        }

        int calls(String url) {
            return calls.getOrDefault(url, new AtomicInteger()).get();
        }
    }

    private static McJavaSourcePicker.Sample sample(byte[] head, long bytes, long durationNanos) {
        return new McJavaSourcePicker.Sample(206, head, bytes, durationNanos);
    }

    private String tunaUrl() {
        return "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/21/jdk/x64/linux/"
                + "OpenJDK21U-jdk_x64_linux_hotspot_21.0.13_9.tar.gz";
    }

    @Test
    void picksTheFastestSourceAndCachesTheChoice() {
        MutableClock clock = new MutableClock();
        FakeSampler sampler = new FakeSampler();
        sampler.answers.put(tunaUrl(), sample(GZIP_HEAD, 524_288, 50_000_000)); // ~10 MB/s
        sampler.answers.put(temurinRelease().url(), sample(GZIP_HEAD, 524_288, 400_000_000)); // ~1.3 MB/s
        McJavaSourcePicker picker = new McJavaSourcePicker(dataDir, sampler, clock);

        McJavaSourcePicker.Choice choice = picker.choose(McJavaRuntimes.Vendor.TEMURIN, temurinRelease());
        assertEquals(McJavaSources.TUNA_ADOPTIUM, choice.source());
        assertTrue(choice.probed());

        // A fresh picker over the same file must reuse the cached choice
        // without sampling again.
        McJavaSourcePicker reopened = new McJavaSourcePicker(dataDir, sampler, clock);
        McJavaSourcePicker.Choice cached = reopened.choose(McJavaRuntimes.Vendor.TEMURIN, temurinRelease());
        assertEquals(McJavaSources.TUNA_ADOPTIUM, cached.source());
        assertFalse(cached.probed());
        assertEquals(1, sampler.calls(tunaUrl()));
    }

    @Test
    void aSlightlySlowerMirrorStillBeatsOfficial() {
        MutableClock clock = new MutableClock();
        FakeSampler sampler = new FakeSampler();
        sampler.answers.put(tunaUrl(), sample(GZIP_HEAD, 524_288, 55_000_000)); // ~9% slower
        sampler.answers.put(temurinRelease().url(), sample(GZIP_HEAD, 524_288, 50_000_000));
        McJavaSourcePicker picker = new McJavaSourcePicker(dataDir, sampler, clock);

        assertEquals(McJavaSources.TUNA_ADOPTIUM,
                picker.choose(McJavaRuntimes.Vendor.TEMURIN, temurinRelease()).source());
    }

    @Test
    void htmlPortalPagesFailTheMagicCheckAndAreSkipped() {
        MutableClock clock = new MutableClock();
        FakeSampler sampler = new FakeSampler();
        // The mirror answers its SPA page: 200, but HTML.
        sampler.answers.put(tunaUrl(), sample(HTML_HEAD, 524_288, 10_000_000));
        sampler.answers.put(temurinRelease().url(), sample(GZIP_HEAD, 524_288, 200_000_000));
        McJavaSourcePicker picker = new McJavaSourcePicker(dataDir, sampler, clock);

        assertEquals(McJavaSources.OFFICIAL,
                picker.choose(McJavaRuntimes.Vendor.TEMURIN, temurinRelease()).source());
        List<McJavaSourcePicker.ProbeResult> results =
                picker.probe(McJavaRuntimes.Vendor.TEMURIN, temurinRelease());
        McJavaSourcePicker.ProbeResult tuna = results.stream()
                .filter(item -> item.sourceId().equals(McJavaSources.TUNA_ADOPTIUM.id()))
                .findFirst().orElseThrow();
        assertFalse(tuna.ok());
        assertTrue(tuna.skipUntil() != null && tuna.skipUntil().isAfter(clock.instant()));
        // Inside the skip window the mirror is not even sampled again.
        int calls = sampler.calls(tunaUrl());
        picker.probe(McJavaRuntimes.Vendor.TEMURIN, temurinRelease());
        assertEquals(calls, sampler.calls(tunaUrl()));
    }

    @Test
    void downloadFailuresPushTheSourceIntoBackoff() {
        MutableClock clock = new MutableClock();
        FakeSampler sampler = new FakeSampler();
        sampler.answers.put(tunaUrl(), sample(GZIP_HEAD, 524_288, 50_000_000));
        sampler.answers.put(temurinRelease().url(), sample(GZIP_HEAD, 524_288, 400_000_000));
        McJavaSourcePicker picker = new McJavaSourcePicker(dataDir, sampler, clock);
        assertEquals(McJavaSources.TUNA_ADOPTIUM,
                picker.choose(McJavaRuntimes.Vendor.TEMURIN, temurinRelease()).source());

        picker.recordDownloadOutcome(McJavaSources.TUNA_ADOPTIUM.id(), false, "truncated archive");

        // The cached choice is poisoned: the next choose() re-probes and,
        // because tuna is inside its skip window, falls back to official
        // without sampling the mirror.
        int calls = sampler.calls(tunaUrl());
        assertEquals(McJavaSources.OFFICIAL,
                picker.choose(McJavaRuntimes.Vendor.TEMURIN, temurinRelease()).source());
        assertEquals(calls, sampler.calls(tunaUrl()));
    }

    @Test
    void policyOverridesAndUnknownIdsFallBack() {
        MutableClock clock = new MutableClock();
        FakeSampler sampler = new FakeSampler();
        McJavaSourcePicker picker = new McJavaSourcePicker(dataDir, sampler, clock);

        picker.setPolicy("official");
        assertEquals(McJavaSources.OFFICIAL,
                picker.choose(McJavaRuntimes.Vendor.TEMURIN, temurinRelease()).source());
        assertTrue(sampler.calls.isEmpty(), "a pinned policy must not probe");

        picker.setPolicy("nope");
        assertEquals("auto", picker.policy());
    }

    @Test
    void probesAreSingleFlight() throws Exception {
        MutableClock clock = new MutableClock();
        FakeSampler sampler = new FakeSampler();
        sampler.answers.put(tunaUrl(), sample(GZIP_HEAD, 524_288, 50_000_000));
        sampler.answers.put(temurinRelease().url(), sample(GZIP_HEAD, 524_288, 400_000_000));
        McJavaSourcePicker picker = new McJavaSourcePicker(dataDir, sampler, clock);

        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        for (int i = 0; i < 2; i++) {
            new Thread(() -> {
                try {
                    ready.await(5, TimeUnit.SECONDS);
                    picker.probe(McJavaRuntimes.Vendor.TEMURIN, temurinRelease());
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        ready.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertEquals(1, sampler.calls(tunaUrl()));
        assertEquals(1, sampler.calls(temurinRelease().url()));
    }

    @Test
    void describeIsPurelyLocal() {
        MutableClock clock = new MutableClock();
        FakeSampler sampler = new FakeSampler();
        sampler.answers.put(tunaUrl(), sample(GZIP_HEAD, 524_288, 50_000_000));
        sampler.answers.put(temurinRelease().url(), sample(GZIP_HEAD, 524_288, 400_000_000));
        McJavaSourcePicker picker = new McJavaSourcePicker(dataDir, sampler, clock);
        picker.choose(McJavaRuntimes.Vendor.TEMURIN, temurinRelease());

        int calls = sampler.calls(tunaUrl()) + sampler.calls(temurinRelease().url());
        com.google.gson.JsonObject snapshot = picker.describe();
        assertEquals(calls, sampler.calls(tunaUrl()) + sampler.calls(temurinRelease().url()));
        assertEquals("auto", snapshot.get("policy").getAsString());
        assertEquals(3, snapshot.getAsJsonArray("sources").size());
        assertEquals(McJavaSources.TUNA_ADOPTIUM.id(),
                snapshot.getAsJsonObject("choices").get("temurin").getAsString());
    }
}
