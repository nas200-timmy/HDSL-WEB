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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DownloadSourcePickerTest {

    private static final String OFFICIAL = "https://official.example/zig.tar.xz";
    private static final String MIRROR_A = "https://a.example/zig.tar.xz";
    private static final String MIRROR_B = "https://b.example/zig.tar.xz";
    private static final byte[] XZ = {(byte) 0xFD, '7', 'z', 'X', 'Z', 0};
    private static final long SIZE = 49_086_504;

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-25T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void theFastestMirrorWins() {
        Map<String, DownloadSourcePicker.Sample> samples = new HashMap<>();
        samples.put(OFFICIAL, sample(XZ, 100, SIZE));
        samples.put(MIRROR_A, sample(XZ, 512, SIZE));
        samples.put(MIRROR_B, sample(XZ, 200, SIZE));
        Map<String, Integer> calls = new HashMap<>();

        DownloadSourcePicker.Choice choice = picker(samples, calls)
                .choose(OFFICIAL, List.of(MIRROR_A, MIRROR_B), SIZE, XZ);

        assertEquals(MIRROR_A, choice.url());
        assertEquals(1, calls.get(MIRROR_A));
    }

    @Test
    void aMirrorWithinThePreferenceWinsEvenWhenTheOfficialIsFaster() {
        Map<String, DownloadSourcePicker.Sample> samples = new HashMap<>();
        samples.put(OFFICIAL, sample(XZ, 1000, SIZE));
        samples.put(MIRROR_A, sample(XZ, 900, SIZE));
        Map<String, Integer> calls = new HashMap<>();

        DownloadSourcePicker.Choice choice = picker(samples, calls)
                .choose(OFFICIAL, List.of(MIRROR_A), SIZE, XZ);

        assertEquals(MIRROR_A, choice.url(), "spare the upstream when the loss is small");
    }

    @Test
    void theOfficialWinsWhenEveryMirrorIsFarTooSlow() {
        Map<String, DownloadSourcePicker.Sample> samples = new HashMap<>();
        samples.put(OFFICIAL, sample(XZ, 1000, SIZE));
        samples.put(MIRROR_A, sample(XZ, 100, SIZE));
        Map<String, Integer> calls = new HashMap<>();

        DownloadSourcePicker.Choice choice = picker(samples, calls)
                .choose(OFFICIAL, List.of(MIRROR_A), SIZE, XZ);

        assertEquals(OFFICIAL, choice.url());
    }

    @Test
    void aPortalPageIsNotAnArchive() {
        Map<String, DownloadSourcePicker.Sample> samples = new HashMap<>();
        samples.put(OFFICIAL, sample(XZ, 100, SIZE));
        samples.put(MIRROR_A, sample("<html>".getBytes(), 1000, -1));
        Map<String, Integer> calls = new HashMap<>();

        DownloadSourcePicker.Choice choice = picker(samples, calls)
                .choose(OFFICIAL, List.of(MIRROR_A), SIZE, XZ);

        assertEquals(OFFICIAL, choice.url());
        DownloadSourcePicker.Probe probe = choice.probes().stream()
                .filter(item -> item.url().equals(MIRROR_A)).findFirst().orElseThrow();
        assertFalse(probe.ok());
        assertTrue(probe.error().contains("archive"), probe.error());
    }

    @Test
    void aWrongTotalSizeIsRejectedBeforeTheDownload() {
        Map<String, DownloadSourcePicker.Sample> samples = new HashMap<>();
        samples.put(OFFICIAL, sample(XZ, 100, SIZE));
        samples.put(MIRROR_A, sample(XZ, 1000, SIZE + 1));
        Map<String, Integer> calls = new HashMap<>();

        DownloadSourcePicker.Choice choice = picker(samples, calls)
                .choose(OFFICIAL, List.of(MIRROR_A), SIZE, XZ);

        assertEquals(OFFICIAL, choice.url());
        DownloadSourcePicker.Probe probe = choice.probes().stream()
                .filter(item -> item.url().equals(MIRROR_A)).findFirst().orElseThrow();
        assertFalse(probe.ok());
        assertTrue(probe.error().contains("pinned"), probe.error());
    }

    @Test
    void aSingleSourceIsNeverProbed() {
        Map<String, Integer> calls = new HashMap<>();
        DownloadSourcePicker picker = new DownloadSourcePicker(
                url -> {
                    calls.merge(url, 1, Integer::sum);
                    return sample(XZ, 100, SIZE);
                }, CLOCK);

        DownloadSourcePicker.Choice choice = picker.choose(OFFICIAL, List.of(), SIZE, XZ);

        assertEquals(OFFICIAL, choice.url());
        assertFalse(choice.probed());
        assertTrue(calls.isEmpty());
    }

    @Test
    void aFreshProbeIsReused() {
        Map<String, DownloadSourcePicker.Sample> samples = new HashMap<>();
        samples.put(OFFICIAL, sample(XZ, 100, SIZE));
        samples.put(MIRROR_A, sample(XZ, 200, SIZE));
        Map<String, Integer> calls = new HashMap<>();
        DownloadSourcePicker picker = picker(samples, calls);

        picker.choose(OFFICIAL, List.of(MIRROR_A), SIZE, XZ);
        picker.choose(OFFICIAL, List.of(MIRROR_A), SIZE, XZ);

        assertEquals(1, calls.get(MIRROR_A), "the second choice must use the cached probe");
        assertEquals(1, calls.get(OFFICIAL));
    }

    @Test
    void aFailedDownloadIsSkippedOnTheNextChoice() {
        Map<String, DownloadSourcePicker.Sample> samples = new HashMap<>();
        samples.put(OFFICIAL, sample(XZ, 100, SIZE));
        samples.put(MIRROR_A, sample(XZ, 200, SIZE));
        Map<String, Integer> calls = new HashMap<>();
        DownloadSourcePicker picker = picker(samples, calls);

        picker.recordOutcome(MIRROR_A, false, "truncated");
        DownloadSourcePicker.Choice choice = picker.choose(OFFICIAL, List.of(MIRROR_A), SIZE, XZ);

        assertEquals(OFFICIAL, choice.url());
        assertFalse(calls.containsKey(MIRROR_A), "a skipped source is not sampled again");
    }

    @Test
    void candidatesKeepMirrorOrderAndDropDuplicates() {
        assertEquals(List.of(MIRROR_A, MIRROR_B, OFFICIAL),
                DownloadSourcePicker.candidates(OFFICIAL, List.of(MIRROR_A, MIRROR_B)));
        assertEquals(List.of(MIRROR_A, OFFICIAL),
                DownloadSourcePicker.candidates(OFFICIAL, List.of(MIRROR_A, OFFICIAL, MIRROR_A)));
    }

    private static DownloadSourcePicker picker(
            Map<String, DownloadSourcePicker.Sample> samples, Map<String, Integer> calls) {
        return new DownloadSourcePicker(url -> {
            calls.merge(url, 1, Integer::sum);
            DownloadSourcePicker.Sample sample = samples.get(url);
            if (sample == null) {
                throw new IOException("no route to " + url);
            }
            return sample;
        }, CLOCK);
    }

    private static DownloadSourcePicker.Sample sample(byte[] head, long speedKBps, long total) {
        long bytes = speedKBps * 1024;
        long durationNanos = 1_000_000_000L;
        if (speedKBps == 0) {
            bytes = 0;
        }
        return new DownloadSourcePicker.Sample(206, head, bytes, durationNanos, total);
    }
}