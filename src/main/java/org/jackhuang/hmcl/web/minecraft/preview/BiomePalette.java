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

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/// The biome → colour table behind the map.
///
/// Two eras have to be served: 1.18 and later name biomes in the chunk NBT
/// (`minecraft:plains`), while 1.12–1.17 store the numeric registry id. The
/// numeric table is the old stable one — it did not change between 1.13 and
/// 1.17, and 1.12 uses the same ids.
///
/// Colours are hand-picked to be *distinguishable*, not to match the game's
/// textures: a seed map is read for shape (where is the ocean, where is the
/// desert), not for beauty. Unknown biomes get a stable hash colour so a new
/// version cannot make half the map grey.
@NotNullByDefault
public final class BiomePalette {

    private static final Map<String, Integer> COLORS = new HashMap<>();
    private static final Map<Integer, String> LEGACY_IDS = new HashMap<>();

    private BiomePalette() {
    }

    /// The colour of a biome name (`minecraft:plains`, `plains`, or a legacy
    /// capitalised key such as `Village`).
    ///
    /// @param name the raw name from the chunk NBT
    /// @return an `0xRRGGBB` colour
    public static int colorOf(@Nullable String name) {
        if (name == null || name.isBlank()) {
            return 0x808080;
        }
        String normalized = normalize(name);
        Integer known = COLORS.get(normalized);
        if (known != null) {
            return known;
        }
        // A stable colour for biomes this table has not met yet (a new version,
        // a datapack). Hash rather than grey so the map still has structure.
        int hash = normalized.hashCode();
        int r = 96 + (hash & 0x5F);
        int g = 96 + ((hash >> 8) & 0x5F);
        int b = 96 + ((hash >> 16) & 0x5F);
        return (r << 16) | (g << 8) | b;
    }

    /// The name for a legacy numeric biome id, or `null` when the id is not in
    /// the table.
    ///
    /// @param id the registry id from `Level.Biomes`
    /// @return the name, or `null`
    public static @Nullable String legacyName(int id) {
        return LEGACY_IDS.get(id);
    }

    /// Envelope-name normalisation: `minecraft:plains` and `Plains` both become
    /// `plains`.
    private static String normalize(@NotNull String raw) {
        String value = raw.trim();
        int colon = value.indexOf(':');
        if (colon >= 0) {
            value = value.substring(colon + 1);
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static void color(String name, int rgb) {
        COLORS.put(name, rgb);
    }

    private static void legacy(int id, String name) {
        LEGACY_IDS.put(id, name);
    }

    static {
        color("ocean", 0x2D50A0);
        color("deep_ocean", 0x1B3A7A);
        color("warm_ocean", 0x2F6FD0);
        color("lukewarm_ocean", 0x2A5FBF);
        color("cold_ocean", 0x27509E);
        color("deep_cold_ocean", 0x1C3D7C);
        color("frozen_ocean", 0x2B4F8E);
        color("deep_frozen_ocean", 0x1A3A6E);
        color("deep_lukewarm_ocean", 0x22489A);
        color("deep_warm_ocean", 0x2656B4);
        color("river", 0x3B7BD0);
        color("frozen_river", 0x5C86C0);
        color("beach", 0xF5E7A8);
        color("snowy_beach", 0xEEF2F5);
        color("stony_shore", 0x8A8886);
        color("plains", 0x8DB360);
        color("sunflower_plains", 0x9BCB52);
        color("forest", 0x3E7A2E);
        color("flower_forest", 0x5FA83C);
        color("birch_forest", 0x6E9E4E);
        color("old_growth_birch_forest", 0x7BA85A);
        color("dark_forest", 0x2A5423);
        color("swamp", 0x4C763B);
        color("mangrove_swamp", 0x3E6B4A);
        color("jungle", 0x2E8B2E);
        color("sparse_jungle", 0x3E8F3E);
        color("bamboo_jungle", 0x5CB33C);
        color("taiga", 0x3D6B4A);
        color("snowy_taiga", 0x4A7A6A);
        color("old_growth_pine_taiga", 0x33553A);
        color("old_growth_spruce_taiga", 0x2E4F36);
        color("windswept_hills", 0x8A8A8A);
        color("windswept_gravelly_hills", 0x9A9A9A);
        color("windswept_forest", 0x6E7A5E);
        color("windswept_savanna", 0xC0A860);
        color("savanna", 0xC5B85C);
        color("savanna_plateau", 0xD0C070);
        color("desert", 0xE8D6A0);
        color("badlands", 0xC46A32);
        color("eroded_badlands", 0xB85A28);
        color("wooded_badlands", 0xA85A30);
        color("snowy_plains", 0xEEF2F5);
        color("snowy_slopes", 0xF0F4F8);
        color("ice_spikes", 0xDDEBF5);
        color("meadow", 0x7DA85E);
        color("grove", 0x5C7F6A);
        color("cherry_grove", 0xE8A0C0);
        color("jagged_peaks", 0xC8C8C8);
        color("frozen_peaks", 0xE0E8F0);
        color("stony_peaks", 0x9A8F86);
        color("mushroom_fields", 0x9A6E8E);
        color("lush_caves", 0x3E8A4A);
        color("dripstone_caves", 0x8A7A6A);
        color("deep_dark", 0x1A2A2E);
        color("the_void", 0x101010);
        color("the_end", 0xD8D8B0);
        color("end_barrens", 0xC8C8A0);
        color("end_highlands", 0xE0E0B8);
        color("end_midlands", 0xD0D0A8);
        color("small_end_islands", 0xB8B890);
        color("nether_wastes", 0x7A2E2E);
        color("soul_sand_valley", 0x5A3E2E);
        color("crimson_forest", 0x8A2A2A);
        color("warped_forest", 0x2A6A5A);
        color("basalt_deltas", 0x4A4A4A);
        color("pale_garden", 0x6A7078);

        // The legacy registry ids, 1.12–1.17. Only the overworld matters for a
        // flat map, but the nether and end ids are here so a mis-set world type
        // still renders instead of turning into a grey field.
        legacy(0, "ocean");
        legacy(1, "plains");
        legacy(2, "desert");
        legacy(3, "windswept_hills");
        legacy(4, "forest");
        legacy(5, "taiga");
        legacy(6, "swamp");
        legacy(7, "river");
        legacy(8, "nether_wastes");
        legacy(9, "the_end");
        legacy(10, "frozen_ocean");
        legacy(11, "frozen_river");
        legacy(12, "snowy_plains");
        legacy(13, "snowy_slopes");
        legacy(14, "mushroom_fields");
        legacy(15, "mushroom_fields");
        legacy(16, "beach");
        legacy(17, "desert");
        legacy(18, "forest");
        legacy(19, "taiga");
        legacy(20, "windswept_hills");
        legacy(21, "jungle");
        legacy(22, "jungle");
        legacy(23, "sparse_jungle");
        legacy(24, "deep_ocean");
        legacy(25, "stony_shore");
        legacy(26, "snowy_beach");
        legacy(27, "birch_forest");
        legacy(28, "birch_forest");
        legacy(29, "dark_forest");
        legacy(30, "snowy_taiga");
        legacy(31, "snowy_taiga");
        legacy(32, "old_growth_pine_taiga");
        legacy(33, "old_growth_pine_taiga");
        legacy(34, "windswept_forest");
        legacy(35, "savanna");
        legacy(36, "savanna_plateau");
        legacy(37, "badlands");
        legacy(38, "wooded_badlands");
        legacy(39, "badlands");
        legacy(40, "small_end_islands");
        legacy(41, "end_midlands");
        legacy(42, "end_highlands");
        legacy(43, "end_barrens");
        legacy(44, "warm_ocean");
        legacy(45, "lukewarm_ocean");
        legacy(46, "cold_ocean");
        legacy(47, "deep_warm_ocean");
        legacy(48, "deep_lukewarm_ocean");
        legacy(49, "deep_cold_ocean");
        legacy(50, "deep_frozen_ocean");
        legacy(127, "the_void");
        legacy(129, "sunflower_plains");
        legacy(130, "desert");
        legacy(131, "windswept_gravelly_hills");
        legacy(132, "flower_forest");
        legacy(133, "taiga");
        legacy(134, "swamp");
        legacy(140, "ice_spikes");
        legacy(149, "jungle");
        legacy(151, "sparse_jungle");
        legacy(155, "old_growth_spruce_taiga");
        legacy(156, "old_growth_spruce_taiga");
        legacy(157, "windswept_forest");
        legacy(158, "savanna_plateau");
        legacy(160, "wooded_badlands");
        legacy(161, "eroded_badlands");
        legacy(162, "bamboo_jungle");
        legacy(163, "bamboo_jungle");
        legacy(164, "soul_sand_valley");
        legacy(165, "crimson_forest");
        legacy(166, "warped_forest");
        legacy(167, "basalt_deltas");
    }
}
