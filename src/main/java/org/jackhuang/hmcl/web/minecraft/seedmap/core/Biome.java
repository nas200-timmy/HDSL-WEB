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
package org.jackhuang.hmcl.web.minecraft.seedmap.core;

/// Overworld biome identifiers used by the seed-map core.
///
/// The numeric ids match the legacy registry and cubiomes where possible so
/// that both the legacy layer stack and the modern multi-noise path can share
/// one enum. Newer biomes that only exist in 1.18+ are assigned the ids used by
/// cubiomes.
public enum Biome {
    NONE(-1, "none"),
    OCEAN(0, "ocean"),
    PLAINS(1, "plains"),
    DESERT(2, "desert"),
    WINDSWEPT_HILLS(3, "windswept_hills"),
    FOREST(4, "forest"),
    TAIGA(5, "taiga"),
    SWAMP(6, "swamp"),
    RIVER(7, "river"),
    NETHER_WASTES(8, "nether_wastes"),
    THE_END(9, "the_end"),
    FROZEN_OCEAN(10, "frozen_ocean"),
    FROZEN_RIVER(11, "frozen_river"),
    SNOWY_PLAINS(12, "snowy_plains"),
    SNOWY_MOUNTAINS(13, "snowy_mountains"),
    MUSHROOM_FIELDS(14, "mushroom_fields"),
    MUSHROOM_FIELD_SHORE(15, "mushroom_field_shore"),
    BEACH(16, "beach"),
    DESERT_HILLS(17, "desert_hills"),
    WOODED_HILLS(18, "wooded_hills"),
    TAIGA_HILLS(19, "taiga_hills"),
    MOUNTAIN_EDGE(20, "mountain_edge"),
    JUNGLE(21, "jungle"),
    JUNGLE_HILLS(22, "jungle_hills"),
    SPARSE_JUNGLE(23, "sparse_jungle"),
    DEEP_OCEAN(24, "deep_ocean"),
    STONY_SHORE(25, "stony_shore"),
    SNOWY_BEACH(26, "snowy_beach"),
    BIRCH_FOREST(27, "birch_forest"),
    BIRCH_FOREST_HILLS(28, "birch_forest_hills"),
    DARK_FOREST(29, "dark_forest"),
    SNOWY_TAIGA(30, "snowy_taiga"),
    SNOWY_TAIGA_HILLS(31, "snowy_taiga_hills"),
    OLD_GROWTH_PINE_TAIGA(32, "old_growth_pine_taiga"),
    GIANT_TREE_TAIGA_HILLS(33, "giant_tree_taiga_hills"),
    WINDSWEPT_FOREST(34, "windswept_forest"),
    SAVANNA(35, "savanna"),
    SAVANNA_PLATEAU(36, "savanna_plateau"),
    BADLANDS(37, "badlands"),
    WOODED_BADLANDS(38, "wooded_badlands"),
    BADLANDS_PLATEAU(39, "badlands_plateau"),
    SMALL_END_ISLANDS(40, "small_end_islands"),
    END_MIDLANDS(41, "end_midlands"),
    END_HIGHLANDS(42, "end_highlands"),
    END_BARRENS(43, "end_barrens"),
    WARM_OCEAN(44, "warm_ocean"),
    LUKEWARM_OCEAN(45, "lukewarm_ocean"),
    COLD_OCEAN(46, "cold_ocean"),
    DEEP_WARM_OCEAN(47, "deep_warm_ocean"),
    DEEP_LUKEWARM_OCEAN(48, "deep_lukewarm_ocean"),
    DEEP_COLD_OCEAN(49, "deep_cold_ocean"),
    DEEP_FROZEN_OCEAN(50, "deep_frozen_ocean"),
    THE_VOID(127, "the_void"),
    SUNFLOWER_PLAINS(129, "sunflower_plains"),
    DESERT_LAKES(130, "desert_lakes"),
    WINDSWEPT_GRAVELLY_HILLS(131, "windswept_gravelly_hills"),
    FLOWER_FOREST(132, "flower_forest"),
    TAIGA_MOUNTAINS(133, "taiga_mountains"),
    SWAMP_HILLS(134, "swamp_hills"),
    ICE_SPIKES(140, "ice_spikes"),
    MODIFIED_JUNGLE(149, "modified_jungle"),
    MODIFIED_JUNGLE_EDGE(151, "modified_jungle_edge"),
    OLD_GROWTH_BIRCH_FOREST(155, "old_growth_birch_forest"),
    TALL_BIRCH_HILLS(156, "tall_birch_hills"),
    DARK_FOREST_HILLS(157, "dark_forest_hills"),
    SNOWY_TAIGA_MOUNTAINS(158, "snowy_taiga_mountains"),
    OLD_GROWTH_SPRUCE_TAIGA(160, "old_growth_spruce_taiga"),
    GIANT_SPRUCE_TAIGA_HILLS(161, "giant_spruce_taiga_hills"),
    MODIFIED_GRAVELLY_MOUNTAINS(162, "modified_gravelly_mountains"),
    WINDSWEPT_SAVANNA(163, "windswept_savanna"),
    SHATTERED_SAVANNA_PLATEAU(164, "shattered_savanna_plateau"),
    ERODED_BADLANDS(165, "eroded_badlands"),
    MODIFIED_WOODED_BADLANDS_PLATEAU(166, "modified_wooded_badlands_plateau"),
    MODIFIED_BADLANDS_PLATEAU(167, "modified_badlands_plateau"),
    BAMBOO_JUNGLE(168, "bamboo_jungle"),
    BAMBOO_JUNGLE_HILLS(169, "bamboo_jungle_hills"),
    SOUL_SAND_VALLEY(170, "soul_sand_valley"),
    CRIMSON_FOREST(171, "crimson_forest"),
    WARPED_FOREST(172, "warped_forest"),
    BASALT_DELTAS(173, "basalt_deltas"),
    DRIPSTONE_CAVES(174, "dripstone_caves"),
    LUSH_CAVES(175, "lush_caves"),
    MEADOW(177, "meadow"),
    GROVE(178, "grove"),
    SNOWY_SLOPES(179, "snowy_slopes"),
    JAGGED_PEAKS(180, "jagged_peaks"),
    FROZEN_PEAKS(181, "frozen_peaks"),
    STONY_PEAKS(182, "stony_peaks"),
    DEEP_DARK(183, "deep_dark"),
    MANGROVE_SWAMP(184, "mangrove_swamp"),
    CHERRY_GROVE(185, "cherry_grove"),
    PALE_GARDEN(186, "pale_garden");

    private final int id;
    private final String key;

    Biome(int id, String key) {
        this.id = id;
        this.key = key;
    }

    public int id() {
        return id;
    }

    /// The resource-location path, e.g. `plains` or `windswept_hills`.
    public String key() {
        return key;
    }

    private static final Biome[] BY_ID;

    static {
        int max = 0;
        for (Biome biome : values()) {
            if (biome.id > max) {
                max = biome.id;
            }
        }
        BY_ID = new Biome[max + 1];
        for (Biome biome : values()) {
            if (biome.id >= 0) {
                BY_ID[biome.id] = biome;
            }
        }
    }

    public static Biome byId(int id) {
        if (id < 0 || id >= BY_ID.length) {
            return NONE;
        }
        Biome biome = BY_ID[id];
        return biome != null ? biome : NONE;
    }
}
