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
package org.jackhuang.hmcl.web.minecraft.preview;

import org.jetbrains.annotations.NotNullByDefault;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/// Turns parsed chunks into a map that reads like a map.
///
/// One pixel per block. The biome layer is only four blocks per cell, but the
/// **heightmap is per block**, and height is what makes terrain legible: a
/// hillshade from the north-west turns a flat field of colours into ridges and
/// valleys, and the sea gets depth instead of one flat blue.
///
/// Everything here is composited in one pass over a height grid that spans the
/// whole picture, so shading is continuous across chunk borders — the seams a
/// per-chunk hillshade would leave are the most obvious "this is generated, not
/// rendered" tell.
@NotNullByDefault
public final class PreviewRenderer {

    /// Sea level; water is drawn by how far the floor sits below it.
    private static final int SEA_LEVEL = 63;
    /// Where the snow line starts and how quickly it takes over.
    private static final int SNOW_LINE = 150;
    /// Light direction: the north-west corner is brighter, as on every map.
    private static final float SHADE_PER_BLOCK = 0.038f;

    private PreviewRenderer() {
    }

    /// Draws the map.
    ///
    /// @param chunks     what {@link RegionReader} produced
    /// @param spec       the request (it fixes size and centre)
    /// @return the image, never `null`
    public static BufferedImage render(List<RegionReader.ChunkSummary> chunks, PreviewSpec spec) {
        int side = spec.pixelsPerSide();
        int radius = spec.radiusBlocks();
        BufferedImage image = new BufferedImage(side, side, BufferedImage.TYPE_INT_RGB);

        int[] heights = new int[side * side];
        int[] base = new int[side * side];
        boolean[] water = new boolean[side * side];
        String[] surface = new String[side * side];
        java.util.Arrays.fill(heights, SEA_LEVEL - 8);

        for (RegionReader.ChunkSummary chunk : chunks) {
            int baseX = chunk.chunkX() * 16 + radius;
            int baseZ = chunk.chunkZ() * 16 + radius;
            for (int z = 0; z < 16; z++) {
                int pz = baseZ + z;
                if (pz < 0 || pz >= side) {
                    continue;
                }
                for (int x = 0; x < 16; x++) {
                    int px = baseX + x;
                    if (px < 0 || px >= side) {
                        continue;
                    }
                    int index = pz * side + px;
                    String biome = chunk.biomes()[(z >> 2) * 4 + (x >> 2)];
                    base[index] = BiomePalette.colorOf(biome);
                    water[index] = isWater(biome);
                    int[] map = chunk.heights();
                    heights[index] = map.length >= 256 ? map[z * 16 + x] : SEA_LEVEL;
                    String[] blocks = chunk.blocks();
                    surface[index] = blocks.length >= 256 ? blocks[z * 16 + x] : null;
                }
            }
        }

        // The raw heightmap includes leaves, snow layers and every one-block
        // bump, which turns a hillshade into noise. A small blur keeps the shape
        // of the land and drops the texture; water is still decided per block
        // from the unblurred data.
        int[] relief = boxBlur(boxBlur(heights, side, 6), side, 6);

        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                int index = z * side + x;
                int color;
                if (water[index]) {
                    int depth = Math.max(0, Math.min(48, SEA_LEVEL - heights[index]));
                    color = waterColor(depth);
                } else {
                    String block = surface[index];
                    color = block != null && !"unknown".equals(block)
                            ? blockColor(block, base[index])
                            : base[index];
                    // With a real surface block the shading is only a hint: the
                    // material already carries the colour, so a strong gradient
                    // would put the noise back.
                    float shade = shade(relief, side, x, z);
                    color = scale(color, 1f + (shade - 1f) * (block != null ? 0.6f : 1f));
                    int height = heights[index];
                    if (height > SNOW_LINE && block == null) {
                        float snow = Math.min(0.55f, (height - SNOW_LINE) / 50f);
                        color = blend(color, 0xFFFFFF, (int) (snow * 255));
                    }
                }
                image.setRGB(x, z, color);
            }
        }

        // A coast line is the single strongest "this is a map" cue: without it
        // the sea simply stops, with it the continent has a shape.
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                int index = z * side + x;
                if (water[index] || !touchesWater(water, side, x, z)) {
                    continue;
                }
                image.setRGB(x, z, blend(image.getRGB(x, z), 0x18324F, 90));
            }
        }

        // Slime, structures and the grid are drawn by the browser: they are
        // metadata, not terrain, and an HTML overlay makes a toggle instant
        // instead of another six-minute world generation.
        drawOrigin(image, radius);
        return image;
    }

    // ------------------------------------------------------------------ colour --

    private static boolean isWater(String biome) {
        String name = biome.toLowerCase(Locale.ROOT);
        return name.contains("ocean") || name.contains("river");
    }

    /// Deep water is darker and a little more saturated; shallow water is the
    /// pale blue a beach expects.
    private static int waterColor(int depth) {
        float t = Math.min(1f, depth / 28f);
        int r = (int) (74 - 46 * t);
        int g = (int) (124 - 78 * t);
        int b = (int) (184 - 64 * t);
        return (r << 16) | (g << 8) | b;
    }

    /// Hillshade from a fixed north-west light. Flat ground is 1.0; a slope
    /// facing the light brightens, one facing away darkens.
    private static float shade(int[] heights, int side, int x, int z) {
        int center = heights[z * side + x];
        int left = at(heights, side, x - 2, z, center);
        int right = at(heights, side, x + 2, z, center);
        int up = at(heights, side, x, z - 2, center);
        int down = at(heights, side, x, z + 2, center);
        float dx = right - left;
        float dz = down - up;
        float value = 1f - (dx + dz) * SHADE_PER_BLOCK;
        return Math.max(0.68f, Math.min(1.26f, value));
    }

    /// A separable box blur. Two passes at radius six remove the tree canopy
    /// and leave landform: the hillshade should show where the hills are, not
    /// where the oaks are.
    private static int[] boxBlur(int[] source, int side, int radius) {
        int[] horizontal = new int[source.length];
        for (int z = 0; z < side; z++) {
            int row = z * side;
            for (int x = 0; x < side; x++) {
                int sum = 0;
                int count = 0;
                for (int dx = -radius; dx <= radius; dx++) {
                    int nx = x + dx;
                    if (nx < 0 || nx >= side) {
                        continue;
                    }
                    sum += source[row + nx];
                    count++;
                }
                horizontal[row + x] = sum / Math.max(1, count);
            }
        }
        int[] vertical = new int[source.length];
        for (int x = 0; x < side; x++) {
            for (int z = 0; z < side; z++) {
                int sum = 0;
                int count = 0;
                for (int dz = -radius; dz <= radius; dz++) {
                    int nz = z + dz;
                    if (nz < 0 || nz >= side) {
                        continue;
                    }
                    sum += horizontal[nz * side + x];
                    count++;
                }
                vertical[z * side + x] = sum / Math.max(1, count);
            }
        }
        return vertical;
    }

    private static boolean touchesWater(boolean[] water, int side, int x, int z) {
        return (x > 0 && water[z * side + x - 1])
                || (x + 1 < side && water[z * side + x + 1])
                || (z > 0 && water[(z - 1) * side + x])
                || (z + 1 < side && water[(z + 1) * side + x]);
    }

    private static int at(int[] heights, int side, int x, int z, int fallback) {
        if (x < 0 || z < 0 || x >= side || z >= side) {
            return fallback;
        }
        return heights[z * side + x];
    }

    private static int scale(int color, float factor) {
        int r = Math.min(255, Math.max(0, (int) (((color >> 16) & 0xFF) * factor)));
        int g = Math.min(255, Math.max(0, (int) (((color >> 8) & 0xFF) * factor)));
        int b = Math.min(255, Math.max(0, (int) ((color & 0xFF) * factor)));
        return (r << 16) | (g << 8) | b;
    }

    private static int blend(int base, int tint, int alpha) {
        int r = (((base >> 16) & 0xFF) * (255 - alpha) + ((tint >> 16) & 0xFF) * alpha) / 255;
        int g = (((base >> 8) & 0xFF) * (255 - alpha) + ((tint >> 8) & 0xFF) * alpha) / 255;
        int b = ((base & 0xFF) * (255 - alpha) + (tint & 0xFF) * alpha) / 255;
        return (r << 16) | (g << 8) | b;
    }

    // --------------------------------------------------------------- overlays --

    private static void drawOrigin(BufferedImage image, int radius) {
        int center = radius;
        for (int i = -6; i <= 6; i++) {
            setSafe(image, center + i, center, 0xFFFFFF);
            setSafe(image, center, center + i, 0xFFFFFF);
        }
        for (int i = -2; i <= 2; i++) {
            setSafe(image, center + i, center, 0xE53935);
            setSafe(image, center, center + i, 0xE53935);
        }
    }

    private static void setSafe(BufferedImage image, int x, int y, int rgb) {
        if (x >= 0 && y >= 0 && x < image.getWidth() && y < image.getHeight()) {
            image.setRGB(x, y, rgb);
        }
    }

    /// The map colour of a surface block.
    ///
    /// A map reads the *material*, not the height: grass is one green whether
    /// it sits on a hill or in a dip. Falling back to the biome colour keeps
    /// unknown blocks (datapacks, mods) from turning the map grey.
    private static int blockColor(String block, int biomeColor) {
        if (block.endsWith("_leaves") || block.equals("azalea_leaves") || block.equals("flowering_azalea_leaves")) {
            return scale(biomeColor, 0.8f);
        }
        if (block.equals("grass_block") || block.equals("moss_block") || block.equals("short_grass")
                || block.equals("tall_grass") || block.equals("fern") || block.equals("large_fern")
                || block.equals("bush") || block.equals("sugar_cane") || block.equals("lily_pad")) {
            return biomeColor;
        }
        if (block.equals("sand") || block.equals("sandstone") || block.equals("suspicious_sand")
                || block.equals("smooth_sandstone") || block.equals("cut_sandstone")) {
            return 0xE4D6A2;
        }
        if (block.equals("red_sand") || block.equals("red_sandstone") || block.contains("terracotta")
                || block.equals("orange_terracotta")) {
            return 0xBE6A3C;
        }
        if (block.equals("gravel") || block.equals("cobblestone") || block.equals("mossy_cobblestone")
                || block.equals("andesite") || block.equals("tuff")) {
            return 0x968F8B;
        }
        if (block.equals("clay")) {
            return 0xA3ABB8;
        }
        if (block.equals("snow") || block.equals("snow_block") || block.equals("powder_snow")
                || block.equals("ice") || block.equals("packed_ice") || block.equals("blue_ice")
                || block.equals("frosted_ice")) {
            return 0xF1F6FA;
        }
        if (block.equals("deepslate") || block.equals("cobbled_deepslate") || block.equals("basalt")
                || block.equals("smooth_basalt") || block.equals("blackstone") || block.equals("sculk")
                || block.equals("calcite") || block.equals("dripstone_block")) {
            return 0x6E6E72;
        }
        if (block.equals("stone") || block.equals("granite") || block.equals("diorite")
                || block.endsWith("_ore")) {
            return 0x8C8C8C;
        }
        if (block.equals("mycelium") || block.equals("mushroom_stem") || block.equals("brown_mushroom_block")
                || block.equals("red_mushroom_block")) {
            return 0x8A6E8A;
        }
        if (block.equals("dirt") || block.equals("coarse_dirt") || block.equals("rooted_dirt")
                || block.equals("podzol")) {
            return 0x7A5A34;
        }
        if (block.equals("mud") || block.equals("muddy_mangrove_roots")) {
            return 0x4A3E38;
        }
        if (block.equals("lava") || block.equals("magma_block")) {
            return 0xD8581E;
        }
        return biomeColor;
    }

    /// The numeric seed the game derives from the text seed.
    ///
    /// Minecraft accepts any text: a number is used as-is, anything else is
    /// hashed with `String.hashCode()`. The panel never needs the hash to
    /// *generate* — the server does that — only to draw the slime overlay.
    static long parseSeed(String seed) {
        String trimmed = seed == null ? "" : seed.trim();
        if (trimmed.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException ignored) {
            return trimmed.hashCode();
        }
    }
}
