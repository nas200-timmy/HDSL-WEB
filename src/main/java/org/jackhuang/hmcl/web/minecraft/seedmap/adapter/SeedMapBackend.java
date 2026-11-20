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
package org.jackhuang.hmcl.web.minecraft.seedmap.adapter;

import org.jackhuang.hmcl.web.minecraft.preview.PreviewRenderer;
import org.jackhuang.hmcl.web.minecraft.preview.PreviewSpec;
import org.jackhuang.hmcl.web.minecraft.seedmap.core.Biome;
import org.jackhuang.hmcl.web.minecraft.seedmap.core.SeedMap;
import org.jackhuang.hmcl.web.minecraft.seedmap.core.Version;

import java.awt.image.BufferedImage;

/// Renders a biome overview directly from the seed-map core, without starting
/// a Minecraft server.
public final class SeedMapBackend {
    private SeedMapBackend() {
    }

    /// What a render produces: the image, the sampled grid and the map itself,
    /// so the caller can write metadata and structures without rebuilding the
    /// generator.
    public record Result(BufferedImage image, String[][] samples, SeedMap map) {
    }

    public static boolean supports(String versionId) {
        try {
            Version.parse(versionId);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static Result render(PreviewSpec spec) {
        Version version = Version.parse(spec.version());
        long seed = parseSeed(spec.seed());
        SeedMap map = SeedMap.of(version, seed);

        int cells = spec.javaSamples();
        String[][] samples = new String[cells][cells];
        int chunks = spec.chunksPerSide();
        int first = spec.firstChunk();
        double step = chunks / (double) cells;

        for (int j = 0; j < cells; j++) {
            for (int i = 0; i < cells; i++) {
                int chunkX = first + (int) (i * step);
                int chunkZ = first + (int) (j * step);
                int blockX = chunkX * 16 + 8;
                int blockZ = chunkZ * 16 + 8;
                Biome biome = map.biomeAt(blockX, spec.biomeY(), blockZ);
                samples[j][i] = biome.key();
            }
        }
        return new Result(PreviewRenderer.renderBiome(samples, spec), samples, map);
    }

    public static BufferedImage renderBiome(PreviewSpec spec) {
        return render(spec).image();
    }

    private static long parseSeed(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return text.trim().hashCode();
        }
    }
}