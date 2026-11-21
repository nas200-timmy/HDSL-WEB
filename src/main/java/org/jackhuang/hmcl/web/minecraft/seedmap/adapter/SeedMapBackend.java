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

    /// Called once per finished row, so a large render still shows a live
    /// progress bar instead of jumping from 0 to 100.
    public interface Progress {
        void update(long done, long total);
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
        return render(spec, null);
    }

    public static Result render(PreviewSpec spec, Progress progress) {
        Version version = Version.parse(spec.version());
        long seed = parseSeed(spec.seed());
        SeedMap map = SeedMap.of(version, seed);

        int cells = spec.javaSamples();
        String[][] samples = new String[cells][cells];
        int radius = spec.radiusBlocks();
        int side = radius * 2;
        // Sample the biome grid directly, in blocks. Sampling one point per
        // chunk and stretching it made the whole map 16 blocks per pixel no
        // matter how many samples were asked for, which is why it never lined up
        // with the one-pixel-per-block server render.
        int step = Math.max(1, (int) Math.round(side / (double) cells));
        long total = (long) cells * cells;

        for (int j = 0; j < cells; j++) {
            for (int i = 0; i < cells; i++) {
                int blockX = -radius + i * step + step / 2;
                int blockZ = -radius + j * step + step / 2;
                Biome biome = map.biomeAt(blockX, spec.biomeY(), blockZ);
                samples[j][i] = biome.key();
            }
            if (progress != null) {
                progress.update((j + 1) * (long) cells, total);
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