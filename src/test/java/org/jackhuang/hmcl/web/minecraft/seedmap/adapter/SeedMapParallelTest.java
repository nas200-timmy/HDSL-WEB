/*
 * HDSL-web
 * Copyright (C) 2026  HDSL-web contributors
 *
 * This program is free software; you can redistribute it and/or modify
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
package org.jackhuang.hmcl.web.minecraft.seedmap.adapter;

import org.jackhuang.hmcl.web.minecraft.preview.PreviewSpec;
import org.jackhuang.hmcl.web.minecraft.seedmap.core.SeedMap;
import org.jackhuang.hmcl.web.minecraft.seedmap.core.Version;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The striped parallel sampler must agree with direct single-point queries —
/// it is the same noise, so any divergence is a racing bug, not a tolerance.
class SeedMapParallelTest {

    @Test
    void parallelGridMatchesDirectBiomeAt() {
        // 1024 cells crosses the 512-cell threshold and exercises the pool;
        // ~1M samples keeps the test around a second.
        PreviewSpec spec = new PreviewSpec("1.21.3", "-424242", 1024, 63,
                PreviewSpec.Mode.BIOME, PreviewSpec.Engine.JAVA, PreviewSpec.Quality.STANDARD);
        SeedMapBackend.Result result = SeedMapBackend.render(spec, null);
        String[][] samples = result.samples();
        assertEquals(spec.javaSamples(), samples.length);

        SeedMap reference = SeedMap.of(Version.parse("1.21.3"), -424242L);
        int radius = spec.radiusBlocks();
        int step = Math.max(1, (int) Math.round((radius * 2) / (double) samples.length));
        // Scattered probes: corners, edges and interior, so a stripe boundary
        // or an off-by-one in any direction shows up.
        int[][] probes = {
                {0, 0}, {samples.length - 1, samples.length - 1},
                {0, samples.length - 1}, {samples.length - 1, 0},
                {511, 511}, {512, 512}, {200, 800}, {801, 199},
        };
        for (int[] probe : probes) {
            int i = probe[0];
            int j = probe[1];
            int blockX = -radius + i * step + step / 2;
            int blockZ = -radius + j * step + step / 2;
            assertEquals(reference.biomeAt(blockX, 63, blockZ).key(), samples[j][i],
                    "cell (" + i + ", " + j + ")");
        }
    }

    @Test
    void workerCountFollowsTheThreshold() {
        assertEquals(1, SeedMapBackend.parallelWorkers(128));
        assertEquals(1, SeedMapBackend.parallelWorkers(511));
        // Above the threshold it is at least 2 (any real CI box) and capped at 16.
        int workers = SeedMapBackend.parallelWorkers(1024);
        assertTrue(workers >= 1 && workers <= 16);
    }
}
