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
 *
 * Ported from cubiomes generator.c (MIT license, Copyright (C) Cubitect).
 */
package org.jackhuang.hmcl.web.minecraft.seedmap.core;

/// The pre-1.18 biome layer stack.
final class LayerStack {
    static final long LAYER_INIT_SHA = ~0L;

    // Layer indices, matching cubiomes' LayerId enum.
    static final int L_CONTINENT_4096 = 0;
    static final int L_ISLAND_4096 = L_CONTINENT_4096;
    static final int L_ZOOM_4096 = 1;
    static final int L_LAND_4096 = 2;
    static final int L_ZOOM_2048 = 3;
    static final int L_LAND_2048 = 4;
    static final int L_ZOOM_1024 = 5;
    static final int L_LAND_1024_A = 6;
    static final int L_LAND_1024_B = 7;
    static final int L_LAND_1024_C = 8;
    static final int L_ISLAND_1024 = 9;
    static final int L_SNOW_1024 = 10;
    static final int L_LAND_1024_D = 11;
    static final int L_COOL_1024 = 12;
    static final int L_HEAT_1024 = 13;
    static final int L_SPECIAL_1024 = 14;
    static final int L_ZOOM_512 = 15;
    static final int L_LAND_512 = 16;
    static final int L_ZOOM_256 = 17;
    static final int L_LAND_256 = 18;
    static final int L_MUSHROOM_256 = 19;
    static final int L_DEEP_OCEAN_256 = 20;
    static final int L_BIOME_256 = 21;
    static final int L_BAMBOO_256 = 22;
    static final int L_ZOOM_128 = 23;
    static final int L_ZOOM_64 = 24;
    static final int L_BIOME_EDGE_64 = 25;
    static final int L_NOISE_256 = 26;
    static final int L_RIVER_INIT_256 = L_NOISE_256;
    static final int L_ZOOM_128_HILLS = 27;
    static final int L_ZOOM_64_HILLS = 28;
    static final int L_HILLS_64 = 29;
    static final int L_SUNFLOWER_64 = 30;
    static final int L_ZOOM_32 = 31;
    static final int L_LAND_32 = 32;
    static final int L_ZOOM_16 = 33;
    static final int L_SHORE_16 = 34;
    static final int L_SWAMP_RIVER_16 = 35;
    static final int L_ZOOM_8 = 36;
    static final int L_ZOOM_4 = 37;
    static final int L_SMOOTH_4 = 38;
    static final int L_ZOOM_128_RIVER = 39;
    static final int L_ZOOM_64_RIVER = 40;
    static final int L_ZOOM_32_RIVER = 41;
    static final int L_ZOOM_16_RIVER = 42;
    static final int L_ZOOM_8_RIVER = 43;
    static final int L_ZOOM_4_RIVER = 44;
    static final int L_RIVER_4 = 45;
    static final int L_SMOOTH_4_RIVER = 46;
    static final int L_RIVER_MIX_4 = 47;
    static final int L_OCEAN_TEMP_256 = 48;
    static final int L_ZOOM_128_OCEAN = 49;
    static final int L_ZOOM_64_OCEAN = 50;
    static final int L_ZOOM_32_OCEAN = 51;
    static final int L_ZOOM_16_OCEAN = 52;
    static final int L_ZOOM_8_OCEAN = 53;
    static final int L_ZOOM_4_OCEAN = 54;
    static final int L_OCEAN_MIX_4 = 55;
    static final int L_VORONOI_1 = 56;
    static final int L_ZOOM_LARGE_A = 57;
    static final int L_ZOOM_LARGE_B = 58;
    static final int L_ZOOM_L_RIVER_A = 59;
    static final int L_ZOOM_L_RIVER_B = 60;
    static final int L_NUM = 61;

    final Layer[] layers = new Layer[L_NUM];
    Layer entry1;
    Layer entry4;
    Layer entry16;
    Layer entry64;
    Layer entry256;
    final PerlinNoise oceanRnd = new PerlinNoise();

    LayerStack() {
        for (int i = 0; i < L_NUM; i++) {
            layers[i] = new Layer();
        }
    }

    void setup(Version version, boolean largeBiomes) {
        int mc = versionToMc(version);
        if (mc < 13) {
            largeBiomes = false;
        }

        Layer p;
        Layer.MapFunc mapLand = Layers.mapLand;

        if (mc <= 106) { // <= 1.6
            mapLand = Layers.mapLand16;
            p = layers[L_CONTINENT_4096].setup(Layers.mapContinent, mc, 1, 0, 1, null, null);
            p = layers[L_ZOOM_2048].setup(Layers.mapZoomFuzzy, mc, 2, 3, 2000, p, null);
            p = layers[L_LAND_2048].setup(mapLand, mc, 1, 2, 1, p, null);
            p = layers[L_ZOOM_1024].setup(Layers.mapZoom, mc, 2, 3, 2001, p, null);
            p = layers[L_LAND_1024_A].setup(mapLand, mc, 1, 2, 2, p, null);
            p = layers[L_SNOW_1024].setup(Layers.mapSnow16, mc, 1, 2, 2, p, null);
            p = layers[L_ZOOM_512].setup(Layers.mapZoom, mc, 2, 3, 2002, p, null);
            p = layers[L_LAND_512].setup(mapLand, mc, 1, 2, 3, p, null);
            p = layers[L_ZOOM_256].setup(Layers.mapZoom, mc, 2, 3, 2003, p, null);
            p = layers[L_LAND_256].setup(mapLand, mc, 1, 2, 4, p, null);
            p = layers[L_MUSHROOM_256].setup(Layers.mapMushroom, mc, 1, 2, 5, p, null);
            p = layers[L_BIOME_256].setup(Layers.mapBiome, mc, 1, 0, 200, p, null);
            p = layers[L_ZOOM_128].setup(Layers.mapZoom, mc, 2, 3, 1000, p, null);
            p = layers[L_ZOOM_64].setup(Layers.mapZoom, mc, 2, 3, 1001, p, null);
            p = layers[L_NOISE_256].setup(Layers.mapNoise, mc, 1, 0, 100, layers[L_MUSHROOM_256], null);
        } else { // 1.7+
            p = layers[L_CONTINENT_4096].setup(Layers.mapContinent, mc, 1, 0, 1, null, null);
            p = layers[L_ZOOM_2048].setup(Layers.mapZoomFuzzy, mc, 2, 3, 2000, p, null);
            p = layers[L_LAND_2048].setup(mapLand, mc, 1, 2, 1, p, null);
            p = layers[L_ZOOM_1024].setup(Layers.mapZoom, mc, 2, 3, 2001, p, null);
            p = layers[L_LAND_1024_A].setup(mapLand, mc, 1, 2, 2, p, null);
            p = layers[L_LAND_1024_B].setup(mapLand, mc, 1, 2, 50, p, null);
            p = layers[L_LAND_1024_C].setup(mapLand, mc, 1, 2, 70, p, null);
            p = layers[L_ISLAND_1024].setup(Layers.mapIsland, mc, 1, 2, 2, p, null);
            p = layers[L_SNOW_1024].setup(Layers.mapSnow, mc, 1, 2, 2, p, null);
            p = layers[L_LAND_1024_D].setup(mapLand, mc, 1, 2, 3, p, null);
            p = layers[L_COOL_1024].setup(Layers.mapCool, mc, 1, 2, 2, p, null);
            p = layers[L_HEAT_1024].setup(Layers.mapHeat, mc, 1, 2, 2, p, null);
            p = layers[L_SPECIAL_1024].setup(Layers.mapSpecial, mc, 1, 2, 3, p, null);
            p = layers[L_ZOOM_512].setup(Layers.mapZoom, mc, 2, 3, 2002, p, null);
            p = layers[L_ZOOM_256].setup(Layers.mapZoom, mc, 2, 3, 2003, p, null);
            p = layers[L_LAND_256].setup(mapLand, mc, 1, 2, 4, p, null);
            p = layers[L_MUSHROOM_256].setup(Layers.mapMushroom, mc, 1, 2, 5, p, null);
            p = layers[L_DEEP_OCEAN_256].setup(Layers.mapDeepOcean, mc, 1, 2, 4, p, null);
            p = layers[L_BIOME_256].setup(Layers.mapBiome, mc, 1, 0, 200, p, null);
            if (mc >= 114) {
                p = layers[L_BAMBOO_256].setup(Layers.mapBamboo, mc, 1, 0, 1001, p, null);
            }
            p = layers[L_ZOOM_128].setup(Layers.mapZoom, mc, 2, 3, 1000, p, null);
            p = layers[L_ZOOM_64].setup(Layers.mapZoom, mc, 2, 3, 1001, p, null);
            p = layers[L_BIOME_EDGE_64].setup(Layers.mapBiomeEdge, mc, 1, 2, 1000, p, null);
            p = layers[L_RIVER_INIT_256].setup(Layers.mapNoise, mc, 1, 0, 100, layers[L_DEEP_OCEAN_256], null);
        }

        if (mc <= 100) {
            // no hills zoom layers
        } else if (mc <= 112) {
            p = layers[L_ZOOM_128_HILLS].setup(Layers.mapZoom, mc, 2, 3, 0, p, null);
            p = layers[L_ZOOM_64_HILLS].setup(Layers.mapZoom, mc, 2, 3, 0, p, null);
        } else {
            p = layers[L_ZOOM_128_HILLS].setup(Layers.mapZoom, mc, 2, 3, 1000, p, null);
            p = layers[L_ZOOM_64_HILLS].setup(Layers.mapZoom, mc, 2, 3, 1001, p, null);
        }

        if (mc <= 106) {
            p = layers[L_HILLS_64].setup(Layers2.mapHills, mc, 1, 2, 1000, layers[L_ZOOM_64], layers[L_ZOOM_64_HILLS]);
            p = layers[L_ZOOM_32].setup(Layers.mapZoom, mc, 2, 3, 1000, p, null);
            p = layers[L_LAND_32].setup(mapLand, mc, 1, 2, 3, p, null);
            p = layers[L_ZOOM_16].setup(Layers.mapZoom, mc, 2, 3, 1001, p, null);
            p = layers[L_SHORE_16].setup(Layers2.mapShore, mc, 1, 2, 1000, p, null);
            p = layers[L_SWAMP_RIVER_16].setup(Layers2.mapSwampRiver, mc, 1, 0, 1000, p, null);
            p = layers[L_ZOOM_8].setup(Layers.mapZoom, mc, 2, 3, 1002, p, null);
            p = layers[L_ZOOM_4].setup(Layers.mapZoom, mc, 2, 3, 1003, p, null);
            if (largeBiomes) {
                p = layers[L_ZOOM_LARGE_A].setup(Layers.mapZoom, mc, 2, 3, 1004, p, null);
                p = layers[L_ZOOM_LARGE_B].setup(Layers.mapZoom, mc, 2, 3, 1005, p, null);
            }
            p = layers[L_SMOOTH_4].setup(Layers2.mapSmooth, mc, 1, 2, 1000, p, null);

            p = layers[L_ZOOM_128_RIVER].setup(Layers.mapZoom, mc, 2, 3, 1000, layers[L_NOISE_256], null);
            p = layers[L_ZOOM_64_RIVER].setup(Layers.mapZoom, mc, 2, 3, 1001, p, null);
            p = layers[L_ZOOM_32_RIVER].setup(Layers.mapZoom, mc, 2, 3, 1002, p, null);
            p = layers[L_ZOOM_16_RIVER].setup(Layers.mapZoom, mc, 2, 3, 1003, p, null);
            p = layers[L_ZOOM_8_RIVER].setup(Layers.mapZoom, mc, 2, 3, 1004, p, null);
            p = layers[L_ZOOM_4_RIVER].setup(Layers.mapZoom, mc, 2, 3, 1005, p, null);
            if (largeBiomes) {
                p = layers[L_ZOOM_L_RIVER_A].setup(Layers.mapZoom, mc, 2, 3, 1006, p, null);
                p = layers[L_ZOOM_L_RIVER_B].setup(Layers.mapZoom, mc, 2, 3, 1007, p, null);
            }
            p = layers[L_RIVER_4].setup(Layers2.mapRiver, mc, 1, 2, 1, p, null);
            p = layers[L_SMOOTH_4_RIVER].setup(Layers2.mapSmooth, mc, 1, 2, 1000, p, null);
        } else {
            p = layers[L_HILLS_64].setup(Layers2.mapHills, mc, 1, 2, 1000, layers[L_BIOME_EDGE_64], layers[L_ZOOM_64_HILLS]);
            p = layers[L_SUNFLOWER_64].setup(Layers2.mapSunflower, mc, 1, 0, 1001, p, null);
            p = layers[L_ZOOM_32].setup(Layers.mapZoom, mc, 2, 3, 1000, p, null);
            p = layers[L_LAND_32].setup(mapLand, mc, 1, 2, 3, p, null);
            p = layers[L_ZOOM_16].setup(Layers.mapZoom, mc, 2, 3, 1001, p, null);
            p = layers[L_SHORE_16].setup(Layers2.mapShore, mc, 1, 2, 1000, p, null);
            p = layers[L_ZOOM_8].setup(Layers.mapZoom, mc, 2, 3, 1002, p, null);
            p = layers[L_ZOOM_4].setup(Layers.mapZoom, mc, 2, 3, 1003, p, null);
            if (largeBiomes) {
                p = layers[L_ZOOM_LARGE_A].setup(Layers.mapZoom, mc, 2, 3, 1004, p, null);
                p = layers[L_ZOOM_LARGE_B].setup(Layers.mapZoom, mc, 2, 3, 1005, p, null);
            }
            p = layers[L_SMOOTH_4].setup(Layers2.mapSmooth, mc, 1, 2, 1000, p, null);

            p = layers[L_ZOOM_128_RIVER].setup(Layers.mapZoom, mc, 2, 3, 1000, layers[L_RIVER_INIT_256], null);
            p = layers[L_ZOOM_64_RIVER].setup(Layers.mapZoom, mc, 2, 3, 1001, p, null);
            p = layers[L_ZOOM_32_RIVER].setup(Layers.mapZoom, mc, 2, 3, 1000, p, null);
            p = layers[L_ZOOM_16_RIVER].setup(Layers.mapZoom, mc, 2, 3, 1001, p, null);
            p = layers[L_ZOOM_8_RIVER].setup(Layers.mapZoom, mc, 2, 3, 1002, p, null);
            p = layers[L_ZOOM_4_RIVER].setup(Layers.mapZoom, mc, 2, 3, 1003, p, null);
            if (largeBiomes && mc == 107) {
                p = layers[L_ZOOM_L_RIVER_A].setup(Layers.mapZoom, mc, 2, 3, 1004, p, null);
                p = layers[L_ZOOM_L_RIVER_B].setup(Layers.mapZoom, mc, 2, 3, 1005, p, null);
            }
            p = layers[L_RIVER_4].setup(Layers2.mapRiver, mc, 1, 2, 1, p, null);
            p = layers[L_SMOOTH_4_RIVER].setup(Layers2.mapSmooth, mc, 1, 2, 1000, p, null);
        }

        p = layers[L_RIVER_MIX_4].setup(Layers2.mapRiverMix, mc, 1, 0, 100, layers[L_SMOOTH_4], layers[L_SMOOTH_4_RIVER]);

        if (mc <= 112) {
            p = layers[L_VORONOI_1].setup(Layers2.mapVoronoi114, mc, 4, 3, 10, p, null);
        } else {
            p = layers[L_OCEAN_TEMP_256].setup(Layers2.mapOceanTemp, mc, 1, 0, 2, null, null);
            p.noise = oceanRnd;
            p = layers[L_ZOOM_128_OCEAN].setup(Layers.mapZoom, mc, 2, 3, 2001, p, null);
            p = layers[L_ZOOM_64_OCEAN].setup(Layers.mapZoom, mc, 2, 3, 2002, p, null);
            p = layers[L_ZOOM_32_OCEAN].setup(Layers.mapZoom, mc, 2, 3, 2003, p, null);
            p = layers[L_ZOOM_16_OCEAN].setup(Layers.mapZoom, mc, 2, 3, 2004, p, null);
            p = layers[L_ZOOM_8_OCEAN].setup(Layers.mapZoom, mc, 2, 3, 2005, p, null);
            p = layers[L_ZOOM_4_OCEAN].setup(Layers.mapZoom, mc, 2, 3, 2006, p, null);
            p = layers[L_OCEAN_MIX_4].setup(Layers2.mapOceanMix, mc, 1, 17, 100, layers[L_RIVER_MIX_4], layers[L_ZOOM_4_OCEAN]);
            if (mc <= 114) {
                p = layers[L_VORONOI_1].setup(Layers2.mapVoronoi114, mc, 4, 3, 10, p, null);
            } else {
                p = layers[L_VORONOI_1].setup(Layers2.mapVoronoi, mc, 4, 3, LAYER_INIT_SHA, p, null);
            }
        }

        entry1 = p;
        entry4 = layers[mc <= 112 ? L_RIVER_MIX_4 : L_OCEAN_MIX_4];
        if (largeBiomes) {
            entry16 = layers[L_ZOOM_4];
            entry64 = layers[mc <= 106 ? L_SWAMP_RIVER_16 : L_SHORE_16];
            entry256 = layers[mc <= 106 ? L_HILLS_64 : L_SUNFLOWER_64];
        } else if (mc >= 101) {
            entry16 = layers[mc <= 106 ? L_SWAMP_RIVER_16 : L_SHORE_16];
            entry64 = layers[mc <= 106 ? L_HILLS_64 : L_SUNFLOWER_64];
            entry256 = layers[mc <= 114 ? L_BIOME_256 : L_BAMBOO_256];
        } else {
            entry16 = layers[L_ZOOM_16];
            entry64 = layers[L_ZOOM_64];
            entry256 = layers[L_BIOME_256];
        }
        setupScale(entry1, 1);
    }

    private static void setupScale(Layer l, int scale) {
        l.scale = scale;
        if (l.p != null) {
            setupScale(l.p, scale * l.zoom);
        }
        if (l.p2 != null) {
            setupScale(l.p2, scale * l.zoom);
        }
    }

    void setSeed(long worldSeed) {
        entry1.setSeed(worldSeed);
    }

    static long getVoronoiSHA(long worldSeed) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(java.nio.ByteBuffer.allocate(8).putLong(worldSeed).array());
            return java.nio.ByteBuffer.wrap(bytes).getLong();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int versionToMc(Version version) {
        return switch (version) {
            case V1_12_2 -> 112;
            case V1_14_4 -> 114;
            case V1_16_5 -> 116;
            default -> 116;
        };
    }
}
