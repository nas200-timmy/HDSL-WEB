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

/// Turns parsed chunks into the picture the panel serves.
///
/// Four pixels per chunk is exactly the resolution of the biome data, so the
/// image has no interpolated detail to lie about. The browser scales it up with
/// `image-rendering: pixelated`.
///
/// Layers are composited in a fixed order — biome, slime, structures — so a
/// structure marker is never hidden behind the slime tint.
@NotNullByDefault
public final class PreviewRenderer {

    /// The tint painted over a slime chunk.
    private static final int SLIME_TINT = 0x59C46A;
    /// How much of the tint is mixed in, out of 255.
    private static final int SLIME_ALPHA = 110;

    private PreviewRenderer() {
    }

    /// Draws the map.
    ///
    /// @param chunks    what {@link RegionReader} produced
    /// @param spec      the request (it fixes size and centre)
    /// @param slime     whether to overlay slime chunks
    /// @param structures whether to draw structure markers
    /// @return the image, never `null`
    public static BufferedImage render(List<RegionReader.ChunkSummary> chunks, PreviewSpec spec,
                                       boolean slime, boolean structures) {
        int side = spec.pixelsPerSide();
        BufferedImage image = new BufferedImage(side, side, BufferedImage.TYPE_INT_RGB);
        int first = spec.firstChunk();

        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                image.setRGB(x, y, 0x1B1B1F);
            }
        }

        Map<Long, RegionReader.ChunkSummary> byPosition = new HashMap<>();
        for (RegionReader.ChunkSummary chunk : chunks) {
            byPosition.put(key(chunk.chunkX(), chunk.chunkZ()), chunk);
        }

        // A text seed hashes to the same number the game derives; the server
        // generates the world, this number only drives the slime overlay.
        long seed = parseSeed(spec.seed());

        for (int cz = first; cz <= spec.lastChunk(); cz++) {
            for (int cx = first; cx <= spec.lastChunk(); cx++) {
                RegionReader.ChunkSummary chunk = byPosition.get(key(cx, cz));
                int px = (cx - first) * 4;
                int pz = (cz - first) * 4;
                if (chunk == null) {
                    continue;
                }
                for (int z4 = 0; z4 < 4; z4++) {
                    for (int x4 = 0; x4 < 4; x4++) {
                        int rgb = BiomePalette.colorOf(chunk.biomes()[z4 * 4 + x4]);
                        image.setRGB(px + x4, pz + z4, rgb);
                    }
                }
                if (slime && SlimeChunks.isSlimeChunk(seed, cx, cz)) {
                    for (int z4 = 0; z4 < 4; z4++) {
                        for (int x4 = 0; x4 < 4; x4++) {
                            image.setRGB(px + x4, pz + z4,
                                    blend(image.getRGB(px + x4, pz + z4), SLIME_TINT, SLIME_ALPHA));
                        }
                    }
                }
            }
        }

        if (structures) {
            for (RegionReader.ChunkSummary chunk : chunks) {
                for (RegionReader.StructureRef structure : chunk.structures()) {
                    int px = (structure.chunkX() - first) * 4;
                    int pz = (structure.chunkZ() - first) * 4;
                    if (px < 0 || pz < 0 || px + 2 > side || pz + 2 > side) {
                        continue;
                    }
                    int color = structureColor(structure.id());
                    for (int dz = 0; dz < 2; dz++) {
                        for (int dx = 0; dx < 2; dx++) {
                            image.setRGB(px + 1 + dx, pz + 1 + dz, color);
                        }
                    }
                }
            }
        }

        // The origin chunk is where a default world puts the player; a small
        // white cross makes the centre of the picture identifiable.
        int middle = (0 - first) * 4 + 1;
        for (int i = -2; i <= 2; i++) {
            setSafe(image, middle + i, middle, 0xFFFFFF);
            setSafe(image, middle, middle + i, 0xFFFFFF);
        }
        return image;
    }

    private static void setSafe(BufferedImage image, int x, int y, int rgb) {
        if (x >= 0 && y >= 0 && x < image.getWidth() && y < image.getHeight()) {
            image.setRGB(x, y, rgb);
        }
    }

    private static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    private static int blend(int base, int tint, int alpha) {
        int r = (((base >> 16) & 0xFF) * (255 - alpha) + ((tint >> 16) & 0xFF) * alpha) / 255;
        int g = (((base >> 8) & 0xFF) * (255 - alpha) + ((tint >> 8) & 0xFF) * alpha) / 255;
        int b = ((base & 0xFF) * (255 - alpha) + (tint & 0xFF) * alpha) / 255;
        return (r << 16) | (g << 8) | b;
    }

    /// Marker colour per structure family. The exact palette does not matter —
    /// the legend in the UI maps the same ids — but it must be stable.
    static int structureColor(String id) {
        String normalised = id.toLowerCase(Locale.ROOT);
        return switch (normalised) {
            case "village", "village_plains", "village_desert", "village_savanna",
                 "village_snowy", "village_taiga" -> 0xFFD54F;
            case "monument", "ocean_monument" -> 0x4FC3F7;
            case "mansion", "woodland_mansion" -> 0x8D6E63;
            case "stronghold" -> 0xE0E0E0;
            case "mineshaft", "mineshaft_mesa" -> 0xA1887F;
            case "temple", "desert_pyramid", "jungle_pyramid", "igloo", "witch_hut",
                 "swamp_hut", "ocean_ruin", "ocean_ruin_cold", "ocean_ruin_warm",
                 "ruined_portal", "shipwreck", "shipwreck_beached", "buried_treasure",
                 "pillager_outpost", "trail_ruins", "trial_chambers", "ancient_city" -> 0xE57373;
            case "fortress", "nether_fortress", "bastion_remnant" -> 0xB71C1C;
            case "end_city" -> 0xBA68C8;
            default -> 0xF06292;
        };
    }

    /// The numeric seed the game derives from the text seed.
    ///
    /// Minecraft accepts any text: a number is used as-is, anything else is
    /// hashed with `String.hashCode()` (the same value the game passes to the
    /// generator). The panel never needs the hash to *generate* — the server
    /// does that — only to draw the slime overlay.
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
