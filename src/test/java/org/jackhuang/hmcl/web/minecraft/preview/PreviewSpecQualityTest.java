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
package org.jackhuang.hmcl.web.minecraft.preview;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// The EXTREME quality tier: twice ULTRA's ceiling, and the reason it never
/// renders in-process (the panel's own 1 GB heap cannot hold the grid — that
/// half is a wiring fact in SeedPreviewService, asserted by code review and
/// the child-JVM path itself).
class PreviewSpecQualityTest {

    @Test
    void extremeCapsAt8192Samples() {
        PreviewSpec spec = new PreviewSpec("1.20.4", "42", 8192, 63,
                PreviewSpec.Mode.BIOME, PreviewSpec.Engine.JAVA, PreviewSpec.Quality.EXTREME);
        org.junit.jupiter.api.Assertions.assertAll(
            () -> assertEquals(8192, spec.radiusBlocks(), "radius"),
            () -> assertEquals(PreviewSpec.Quality.EXTREME, spec.quality(), "quality"),
            () -> assertEquals(1024, spec.chunksPerSide(), "chunksPerSide"),
            () -> assertEquals(8192, spec.javaSamples(), "samples"));
        // 采样密度按 8/区块（=1 采样/2 格）随半径走，EXTREME 的 8192 上限只在
        // 半径 8192 处被真正顶到；半径 4096 时 ULTRA 也一样是 4096。
        PreviewSpec mid = new PreviewSpec("1.20.4", "42", 4096, 63,
                PreviewSpec.Mode.BIOME, PreviewSpec.Engine.JAVA, PreviewSpec.Quality.EXTREME);
        assertEquals(4096, mid.javaSamples());
    }

    @Test
    void smallerRadiiKeepTheirFloorAndUltraIsUnchanged() {
        // 密度下限只咬最小的半径：64 格 → 8 区块/边 × 8 = 64，抬到 MIN=128；
        // 再往上一直是 1 采样/2 格，直到撞质量档上限。
        PreviewSpec tiny = new PreviewSpec("1.20.4", "42", 64, 63,
                PreviewSpec.Mode.BIOME, PreviewSpec.Engine.JAVA, PreviewSpec.Quality.EXTREME);
        assertEquals(PreviewSpec.JAVA_BIOME_SAMPLES_MIN, tiny.javaSamples());
        PreviewSpec ultra = new PreviewSpec("1.20.4", "42", 8192, 63,
                PreviewSpec.Mode.BIOME, PreviewSpec.Engine.JAVA, PreviewSpec.Quality.ULTRA);
        assertEquals(4096, ultra.javaSamples());
    }
}
