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
 * Ported from cubiomes layers.c (MIT license, Copyright (C) Cubitect).
 */
package org.jackhuang.hmcl.web.minecraft.seedmap.core;

/// Remaining layer mapping functions: hills, rivers, shores and voronoi.
final class Layers2 {
    private Layers2() {
    }

    private static final int ocean = Biome.OCEAN.id();
    private static final int plains = Biome.PLAINS.id();
    private static final int desert = Biome.DESERT.id();
    private static final int mountains = Biome.WINDSWEPT_HILLS.id();
    private static final int forest = Biome.FOREST.id();
    private static final int taiga = Biome.TAIGA.id();
    private static final int swamp = Biome.SWAMP.id();
    private static final int river = Biome.RIVER.id();
    private static final int frozen_river = Biome.FROZEN_RIVER.id();
    private static final int snowy_tundra = Biome.SNOWY_PLAINS.id();
    private static final int snowy_mountains = Biome.SNOWY_MOUNTAINS.id();
    private static final int mushroom_fields = Biome.MUSHROOM_FIELDS.id();
    private static final int mushroom_field_shore = Biome.MUSHROOM_FIELD_SHORE.id();
    private static final int beach = Biome.BEACH.id();
    private static final int desert_hills = Biome.DESERT_HILLS.id();
    private static final int wooded_hills = Biome.WOODED_HILLS.id();
    private static final int taiga_hills = Biome.TAIGA_HILLS.id();
    private static final int mountain_edge = Biome.MOUNTAIN_EDGE.id();
    private static final int jungle = Biome.JUNGLE.id();
    private static final int jungle_hills = Biome.JUNGLE_HILLS.id();
    private static final int jungle_edge = Biome.SPARSE_JUNGLE.id();
    private static final int deep_ocean = Biome.DEEP_OCEAN.id();
    private static final int stone_shore = Biome.STONY_SHORE.id();
    private static final int snowy_beach = Biome.SNOWY_BEACH.id();
    private static final int birch_forest = Biome.BIRCH_FOREST.id();
    private static final int birch_forest_hills = Biome.BIRCH_FOREST_HILLS.id();
    private static final int dark_forest = Biome.DARK_FOREST.id();
    private static final int snowy_taiga = Biome.SNOWY_TAIGA.id();
    private static final int snowy_taiga_hills = Biome.SNOWY_TAIGA_HILLS.id();
    private static final int giant_tree_taiga = Biome.OLD_GROWTH_PINE_TAIGA.id();
    private static final int giant_tree_taiga_hills = Biome.GIANT_TREE_TAIGA_HILLS.id();
    private static final int wooded_mountains = Biome.WINDSWEPT_FOREST.id();
    private static final int savanna = Biome.SAVANNA.id();
    private static final int savanna_plateau = Biome.SAVANNA_PLATEAU.id();
    private static final int badlands = Biome.BADLANDS.id();
    private static final int wooded_badlands_plateau = Biome.WOODED_BADLANDS.id();
    private static final int badlands_plateau = Biome.BADLANDS_PLATEAU.id();
    private static final int warm_ocean = Biome.WARM_OCEAN.id();
    private static final int lukewarm_ocean = Biome.LUKEWARM_OCEAN.id();
    private static final int cold_ocean = Biome.COLD_OCEAN.id();
    private static final int deep_warm_ocean = Biome.DEEP_WARM_OCEAN.id();
    private static final int deep_lukewarm_ocean = Biome.DEEP_LUKEWARM_OCEAN.id();
    private static final int deep_cold_ocean = Biome.DEEP_COLD_OCEAN.id();
    private static final int deep_frozen_ocean = Biome.DEEP_FROZEN_OCEAN.id();
    private static final int sunflower_plains = Biome.SUNFLOWER_PLAINS.id();
    private static final int bamboo_jungle = Biome.BAMBOO_JUNGLE.id();
    private static final int bamboo_jungle_hills = Biome.BAMBOO_JUNGLE_HILLS.id();

    static final Layer.MapFunc mapHills = (l, out, x, z, w, h) -> {
        int pX = x - 1;
        int pZ = z - 1;
        int pW = w + 2;
        int pH = h + 2;

        if (l.p2 == null) {
            throw new IllegalStateException("mapHills requires two parents");
        }

        int err = l.p.getMap.apply(l.p, out, pX, pZ, pW, pH);
        if (err != 0) return err;

        int[] riv = new int[out.length];
        err = l.p2.getMap.apply(l.p2, riv, pX, pZ, pW, pH);
        if (err != 0) return err;

        int mc = l.mc;
        long st = l.startSalt;
        long ss = l.startSeed;

        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int a11 = out[i + 1 + (j + 1) * pW];
                int b11 = riv[i + 1 + (j + 1) * pW];
                int idx = i + j * w;
                int bn = -1;

                if (mc >= 107) {
                    bn = (b11 - 2) % 29;
                }

                if (bn == 1 && b11 >= 2 && !Layers.isShallowOcean(a11)) {
                    int m = Layers.getMutated(mc, a11);
                    if (m > 0) {
                        out[idx] = m;
                    } else {
                        out[idx] = a11;
                    }
                } else {
                    long cs = Rng.getChunkSeed(ss, i + x, j + z);
                    if (bn == 0 || Rng.mcFirstIsZero(cs, 3)) {
                        int hillID = a11;

                        switch (a11) {
                            case 2 -> hillID = desert_hills;
                            case 4 -> hillID = wooded_hills;
                            case 27 -> hillID = birch_forest_hills;
                            case 29 -> hillID = plains;
                            case 5 -> hillID = taiga_hills;
                            case 32 -> hillID = giant_tree_taiga_hills;
                            case 30 -> hillID = snowy_taiga_hills;
                            case 1 -> {
                                if (mc <= 106) {
                                    hillID = forest;
                                } else {
                                    cs = Rng.mcStepSeed(cs, st);
                                    hillID = Rng.mcFirstIsZero(cs, 3) ? wooded_hills : forest;
                                }
                            }
                            case 12 -> hillID = snowy_mountains;
                            case 21 -> hillID = jungle_hills;
                            case 168 -> hillID = bamboo_jungle_hills;
                            case 0 -> {
                                if (mc >= 107) hillID = deep_ocean;
                            }
                            case 3 -> {
                                if (mc >= 107) hillID = wooded_mountains;
                            }
                            case 35 -> hillID = savanna_plateau;
                            default -> {
                                if (Layers.areSimilar(mc, a11, wooded_badlands_plateau)) {
                                    hillID = badlands;
                                } else if (Layers.isDeepOcean(a11)) {
                                    cs = Rng.mcStepSeed(cs, st);
                                    if (Rng.mcFirstIsZero(cs, 3)) {
                                        cs = Rng.mcStepSeed(cs, st);
                                        hillID = Rng.mcFirstIsZero(cs, 2) ? plains : forest;
                                    }
                                }
                            }
                        }

                        if (bn == 0 && hillID != a11) {
                            hillID = Layers.getMutated(mc, hillID);
                            if (hillID < 0) hillID = a11;
                        }

                        if (hillID != a11) {
                            int a10 = out[i + 1 + (j + 0) * pW];
                            int a21 = out[i + 2 + (j + 1) * pW];
                            int a01 = out[i + 0 + (j + 1) * pW];
                            int a12 = out[i + 1 + (j + 2) * pW];
                            int equals = 0;

                            if (Layers.areSimilar(mc, a10, a11)) equals++;
                            if (Layers.areSimilar(mc, a21, a11)) equals++;
                            if (Layers.areSimilar(mc, a01, a11)) equals++;
                            if (Layers.areSimilar(mc, a12, a11)) equals++;

                            if (equals >= 3 + (mc <= 106 ? 1 : 0)) {
                                out[idx] = hillID;
                            } else {
                                out[idx] = a11;
                            }
                        } else {
                            out[idx] = a11;
                        }
                    } else {
                        out[idx] = a11;
                    }
                }
            }
        }
        return 0;
    };

    private static int reduceID(int id) {
        return id >= 2 ? 2 + (id & 1) : id;
    }

    static final Layer.MapFunc mapRiver = (l, out, x, z, w, h) -> {
        int pX = x - 1;
        int pZ = z - 1;
        int pW = w + 2;
        int pH = h + 2;

        int err = l.p.getMap.apply(l.p, out, pX, pZ, pW, pH);
        if (err != 0) return err;

        int mc = l.mc;
        for (int j = 0; j < h; j++) {
            int vz0 = (j + 0) * pW;
            int vz1 = (j + 1) * pW;
            int vz2 = (j + 2) * pW;

            for (int i = 0; i < w; i++) {
                int v01 = out[vz1 + i + 0];
                int v11 = out[vz1 + i + 1];
                int v21 = out[vz1 + i + 2];
                int v10 = out[vz0 + i + 1];
                int v12 = out[vz2 + i + 1];

                if (mc >= 107) {
                    v01 = reduceID(v01);
                    v11 = reduceID(v11);
                    v21 = reduceID(v21);
                    v10 = reduceID(v10);
                    v12 = reduceID(v12);
                } else if (v11 == 0) {
                    out[i + j * w] = river;
                    continue;
                }

                if (v11 == v01 && v11 == v10 && v11 == v12 && v11 == v21) {
                    out[i + j * w] = -1;
                } else {
                    out[i + j * w] = river;
                }
            }
        }
        return 0;
    };

    static final Layer.MapFunc mapSmooth = (l, out, x, z, w, h) -> {
        int pX = x - 1;
        int pZ = z - 1;
        int pW = w + 2;
        int pH = h + 2;

        int err = l.p.getMap.apply(l.p, out, pX, pZ, pW, pH);
        if (err != 0) return err;

        long ss = l.startSeed;
        for (int j = 0; j < h; j++) {
            int vz0 = (j + 0) * pW;
            int vz1 = (j + 1) * pW;
            int vz2 = (j + 2) * pW;

            for (int i = 0; i < w; i++) {
                int v11 = out[vz1 + i + 1];
                int v01 = out[vz1 + i + 0];
                int v10 = out[vz0 + i + 1];

                if (v11 != v01 || v11 != v10) {
                    int v21 = out[vz1 + i + 2];
                    int v12 = out[vz2 + i + 1];
                    if (v01 == v21 && v10 == v12) {
                        long cs = Rng.getChunkSeed(ss, i + x, j + z);
                        if ((cs & (1L << 24)) != 0) {
                            v11 = v10;
                        } else {
                            v11 = v01;
                        }
                    } else {
                        if (v01 == v21) v11 = v01;
                        if (v10 == v12) v11 = v10;
                    }
                }
                out[i + j * w] = v11;
            }
        }
        return 0;
    };

    static final Layer.MapFunc mapSunflower = (l, out, x, z, w, h) -> {
        int err = l.p.getMap.apply(l.p, out, x, z, w, h);
        if (err != 0) return err;

        long ss = l.startSeed;
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int v = out[i + j * w];
                if (v == plains) {
                    long cs = Rng.getChunkSeed(ss, i + x, j + z);
                    if (Rng.mcFirstIsZero(cs, 57)) {
                        out[i + j * w] = sunflower_plains;
                    }
                }
            }
        }
        return 0;
    };

    private static boolean isAll4JFTO(int mc, int a, int b, int c, int d) {
        return (Layers.getCategory(mc, a) == jungle || a == forest || a == taiga || Layers.isOceanic(a))
                && (Layers.getCategory(mc, b) == jungle || b == forest || b == taiga || Layers.isOceanic(b))
                && (Layers.getCategory(mc, c) == jungle || c == forest || c == taiga || Layers.isOceanic(c))
                && (Layers.getCategory(mc, d) == jungle || d == forest || d == taiga || Layers.isOceanic(d));
    }

    private static boolean isAny4Oceanic(int a, int b, int c, int d) {
        return Layers.isOceanic(a) || Layers.isOceanic(b) || Layers.isOceanic(c) || Layers.isOceanic(d);
    }

    private static void replaceOcean(int[] out, int idx, int v10, int v21, int v01, int v12, int id, int replaceID) {
        if (Layers.isOceanic(id)) return;
        if (Layers.isOceanic(v10) || Layers.isOceanic(v21) || Layers.isOceanic(v01) || Layers.isOceanic(v12)) {
            out[idx] = replaceID;
        } else {
            out[idx] = id;
        }
    }

    static final Layer.MapFunc mapShore = (l, out, x, z, w, h) -> {
        int pX = x - 1;
        int pZ = z - 1;
        int pW = w + 2;
        int pH = h + 2;

        int err = l.p.getMap.apply(l.p, out, pX, pZ, pW, pH);
        if (err != 0) return err;

        int mc = l.mc;
        for (int j = 0; j < h; j++) {
            int vz0 = (j + 0) * pW;
            int vz1 = (j + 1) * pW;
            int vz2 = (j + 2) * pW;

            for (int i = 0; i < w; i++) {
                int v11 = out[vz1 + i + 1];
                int v10 = out[vz0 + i + 1];
                int v21 = out[vz1 + i + 2];
                int v01 = out[vz1 + i + 0];
                int v12 = out[vz2 + i + 1];

                if (v11 == mushroom_fields) {
                    if (Layer.isAny4(ocean, v10, v21, v01, v12)) {
                        out[i + j * w] = mushroom_field_shore;
                    } else {
                        out[i + j * w] = v11;
                    }
                    continue;
                }
                if (mc <= 100) {
                    out[i + j * w] = v11;
                    continue;
                }

                if (mc <= 106) {
                    if (v11 == mountains) {
                        if (v10 != mountains || v21 != mountains || v01 != mountains || v12 != mountains) {
                            v11 = mountain_edge;
                        }
                    } else if (v11 != ocean && v11 != river && v11 != swamp) {
                        if (Layer.isAny4(ocean, v10, v21, v01, v12)) {
                            v11 = beach;
                        }
                    }
                    out[i + j * w] = v11;
                } else if (Layers.getCategory(mc, v11) == jungle) {
                    if (isAll4JFTO(mc, v10, v21, v01, v12)) {
                        if (isAny4Oceanic(v10, v21, v01, v12)) {
                            out[i + j * w] = beach;
                        } else {
                            out[i + j * w] = v11;
                        }
                    } else {
                        out[i + j * w] = jungle_edge;
                    }
                } else if (v11 == mountains || v11 == wooded_mountains) {
                    replaceOcean(out, i + j * w, v10, v21, v01, v12, v11, stone_shore);
                } else if (Layers.isSnowy(v11)) {
                    replaceOcean(out, i + j * w, v10, v21, v01, v12, v11, snowy_beach);
                } else if (v11 == badlands || v11 == wooded_badlands_plateau) {
                    if (!isAny4Oceanic(v10, v21, v01, v12)) {
                        if (Layers.isMesa(v10) && Layers.isMesa(v21) && Layers.isMesa(v01) && Layers.isMesa(v12)) {
                            out[i + j * w] = v11;
                        } else {
                            out[i + j * w] = desert;
                        }
                    } else {
                        out[i + j * w] = v11;
                    }
                } else {
                    if (v11 != ocean && v11 != deep_ocean && v11 != river && v11 != swamp) {
                        if (isAny4Oceanic(v10, v21, v01, v12)) {
                            out[i + j * w] = beach;
                        } else {
                            out[i + j * w] = v11;
                        }
                    } else {
                        out[i + j * w] = v11;
                    }
                }
            }
        }
        return 0;
    };

    static final Layer.MapFunc mapSwampRiver = (l, out, x, z, w, h) -> {
        int err = l.p.getMap.apply(l.p, out, x, z, w, h);
        if (err != 0) return err;

        long ss = l.startSeed;
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int v = out[i + j * w];
                if (v != swamp && v != jungle && v != jungle_hills) {
                    continue;
                }
                long cs = Rng.getChunkSeed(ss, i + x, j + z);
                if (Rng.mcFirstIsZero(cs, (v == swamp) ? 6 : 8)) {
                    v = river;
                }
                out[i + j * w] = v;
            }
        }
        return 0;
    };

    static final Layer.MapFunc mapRiverMix = (l, out, x, z, w, h) -> {
        if (l.p2 == null) {
            throw new IllegalStateException("mapRiverMix requires two parents");
        }

        int err = l.p.getMap.apply(l.p, out, x, z, w, h);
        if (err != 0) return err;

        int len = w * h;
        int mc = l.mc;
        int[] buf = new int[out.length];
        err = l.p2.getMap.apply(l.p2, buf, x, z, w, h);
        if (err != 0) return err;

        for (int idx = 0; idx < len; idx++) {
            int v = out[idx];
            if (buf[idx] == river && v != ocean && (mc <= 106 || !Layers.isOceanic(v))) {
                if (v == snowy_tundra) {
                    v = frozen_river;
                } else if (v == mushroom_fields || v == mushroom_field_shore) {
                    v = mushroom_field_shore;
                } else {
                    v = river;
                }
            }
            out[idx] = v;
        }
        return 0;
    };

    static final Layer.MapFunc mapOceanTemp = (l, out, x, z, w, h) -> {
        PerlinNoise rnd = l.noise;
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                double tmp = rnd.sample((i + x) / 8.0, (j + z) / 8.0, 0, 0, 0);
                if (tmp > 0.4) {
                    out[i + j * w] = warm_ocean;
                } else if (tmp > 0.2) {
                    out[i + j * w] = lukewarm_ocean;
                } else if (tmp < -0.4) {
                    out[i + j * w] = Biome.FROZEN_OCEAN.id();
                } else if (tmp < -0.2) {
                    out[i + j * w] = cold_ocean;
                } else {
                    out[i + j * w] = ocean;
                }
            }
        }
        return 0;
    };

    static final Layer.MapFunc mapOceanMix = (l, out, x, z, w, h) -> {
        if (l.p2 == null) {
            throw new IllegalStateException("mapOceanMix requires two parents");
        }

        int err = l.p2.getMap.apply(l.p2, out, x, z, w, h);
        if (err != 0) return err;

        int lx0 = 0;
        int lx1 = w;
        int lz0 = 0;
        int lz1 = h;

        for (int j = 0; j < h; j++) {
            boolean jcentre = (j - 8 > 0 && j + 9 < h);
            for (int i = 0; i < w; i++) {
                if (jcentre && i - 8 > 0 && i + 9 < w) {
                    continue;
                }
                int oceanID = out[i + j * w];
                if (oceanID == warm_ocean || oceanID == Biome.FROZEN_OCEAN.id()) {
                    if (i - 8 < lx0) lx0 = i - 8;
                    if (i + 9 > lx1) lx1 = i + 9;
                    if (j - 8 < lz0) lz0 = j - 8;
                    if (j + 9 > lz1) lz1 = j + 9;
                }
            }
        }

        int lw = lx1 - lx0;
        int lh = lz1 - lz0;
        int[] land = new int[out.length];
        err = l.p.getMap.apply(l.p, land, x + lx0, z + lz0, lw, lh);
        if (err != 0) return err;

        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int landID = land[(i - lx0) + (j - lz0) * lw];
                int oceanID = out[i + j * w];
                int replaceID = 0;

                if (!Layers.isOceanic(landID)) {
                    out[i + j * w] = landID;
                    continue;
                }

                if (oceanID == warm_ocean) replaceID = lukewarm_ocean;
                if (oceanID == Biome.FROZEN_OCEAN.id()) replaceID = cold_ocean;
                if (replaceID != 0) {
                    boolean found = false;
                    for (int ii = -8; ii <= 8; ii += 4) {
                        for (int jj = -8; jj <= 8; jj += 4) {
                            int px = i + ii - lx0;
                            int pz = j + jj - lz0;
                            if (px < 0 || pz < 0 || px >= lw || pz >= lh) continue;
                            int id = land[px + pz * lw];
                            if (!Layers.isOceanic(id)) {
                                out[i + j * w] = replaceID;
                                found = true;
                                break;
                            }
                        }
                        if (found) break;
                    }
                    if (found) continue;
                }

                if (landID == deep_ocean) {
                    switch (oceanID) {
                        case 45 -> oceanID = deep_lukewarm_ocean;
                        case 0 -> oceanID = deep_ocean;
                        case 46 -> oceanID = deep_cold_ocean;
                        case 10 -> oceanID = deep_frozen_ocean;
                    }
                }
                out[i + j * w] = oceanID;
            }
        }
        return 0;
    };

    private static void getVoronoiCell(long sha, int a, int b, int c, int[] out) {
        long s = sha;
        s = Rng.mcStepSeed(s, a);
        s = Rng.mcStepSeed(s, b);
        s = Rng.mcStepSeed(s, c);
        s = Rng.mcStepSeed(s, a);
        s = Rng.mcStepSeed(s, b);
        s = Rng.mcStepSeed(s, c);

        out[0] = (int) (((s >> 24) & 1023) - 512) * 36;
        s = Rng.mcStepSeed(s, sha);
        out[1] = (int) (((s >> 24) & 1023) - 512) * 36;
        s = Rng.mcStepSeed(s, sha);
        out[2] = (int) (((s >> 24) & 1023) - 512) * 36;
    }

    static final Layer.MapFunc mapVoronoi = (l, out, x, z, w, h) -> {
        x -= 2;
        z -= 2;
        int px = x >> 2;
        int pz = z >> 2;
        int pw = ((x + w) >> 2) - px + 2;
        int ph = ((z + h) >> 2) - pz + 2;

        if (l.p != null) {
            int err = l.p.getMap.apply(l.p, out, px, pz, pw, ph);
            if (err != 0) return err;
        }

        int[] src = new int[pw * ph];
        System.arraycopy(out, 0, src, 0, pw * ph);
        mapVoronoiPlane(l.startSalt, out, src, x, z, w, h, 0, px, pz, pw, ph);
        return 0;
    };

    private static void mapVoronoiPlane(long sha, int[] out, int[] src,
                                        int x, int z, int w, int h, int y, int px, int pz, int pw, int ph) {
        x -= 2;
        y -= 2;
        z -= 2;
        int[] x000 = new int[3];
        int[] x001 = new int[3];
        int[] x010 = new int[3];
        int[] x011 = new int[3];
        int[] x100 = new int[3];
        int[] x101 = new int[3];
        int[] x110 = new int[3];
        int[] x111 = new int[3];
        boolean prevSkip = true;

        for (int pj = 0; pj < ph - 1; pj++) {
            int v00 = src[(pj + 0) * pw];
            int v10 = src[(pj + 1) * pw];
            int pjz = pz + pj;
            int j4 = pjz * 4 - z;

            for (int pi = 0; pi < pw - 1; pi++) {
                int v01 = src[(pj + 0) * pw + (pi + 1)];
                int v11 = src[(pj + 1) * pw + (pi + 1)];
                int pix = px + pi;
                int i4 = pix * 4 - x;

                if (v00 == v01 && v00 == v10 && v00 == v11) {
                    for (int jj = 0; jj < 4; jj++) {
                        int j = j4 + jj;
                        if (j < 0 || j >= h) continue;
                        for (int ii = 0; ii < 4; ii++) {
                            int i = i4 + ii;
                            if (i < 0 || i >= w) continue;
                            out[j * w + i] = v00;
                        }
                    }
                    prevSkip = true;
                    continue;
                }
                if (prevSkip) {
                    getVoronoiCell(sha, pix, y - 1, pjz + 0, x000);
                    getVoronoiCell(sha, pix, y + 0, pjz + 0, x001);
                    getVoronoiCell(sha, pix, y - 1, pjz + 1, x100);
                    getVoronoiCell(sha, pix, y + 0, pjz + 1, x101);
                    prevSkip = false;
                }
                getVoronoiCell(sha, pix + 1, y - 1, pjz + 0, x010);
                getVoronoiCell(sha, pix + 1, y + 0, pjz + 0, x011);
                getVoronoiCell(sha, pix + 1, y - 1, pjz + 1, x110);
                getVoronoiCell(sha, pix + 1, y + 0, pjz + 1, x111);

                for (int jj = 0; jj < 4; jj++) {
                    int j = j4 + jj;
                    if (j < 0 || j >= h) continue;
                    for (int ii = 0; ii < 4; ii++) {
                        int i = i4 + ii;
                        if (i < 0 || i >= w) continue;

                        final int A = 40 * 1024;
                        final int B = 20 * 1024;
                        int dx = ii * 10 * 1024;
                        int dz = jj * 10 * 1024;
                        long dmin = Long.MAX_VALUE;
                        int v = v00;

                        long d = dist(x000, 0, B, 0, dx, dz);
                        if (d < dmin) dmin = d;
                        d = dist(x001, 0, -B, 0, dx, dz);
                        if (d < dmin) dmin = d;

                        d = dist(x010, A, B, 0, dx, dz);
                        if (d < dmin) { dmin = d; v = v01; }
                        d = dist(x011, A, -B, 0, dx, dz);
                        if (d < dmin) { dmin = d; v = v01; }

                        d = dist(x100, 0, B, A, dx, dz);
                        if (d < dmin) { dmin = d; v = v10; }
                        d = dist(x101, 0, -B, A, dx, dz);
                        if (d < dmin) { dmin = d; v = v10; }

                        d = dist(x110, A, B, A, dx, dz);
                        if (d < dmin) { dmin = d; v = v11; }
                        d = dist(x111, A, -B, A, dx, dz);
                        if (d < dmin) { dmin = d; v = v11; }

                        out[j * w + i] = v;
                    }
                }

                x000 = x010;
                x100 = x110;
                x001 = x011;
                x101 = x111;
                v00 = v01;
                v10 = v11;
            }
        }
    }

    private static long dist(int[] cell, int ax, int ay, int az, int dx, int dz) {
        long rx = cell[0] - ax + dx;
        long ry = cell[1] + ay;
        long rz = cell[2] - az + dz;
        return rx * rx + ry * ry + rz * rz;
    }

    static final Layer.MapFunc mapVoronoi114 = (l, out, x, z, w, h) -> {
        x -= 2;
        z -= 2;
        int pX = x >> 2;
        int pZ = z >> 2;
        int pW = ((x + w) >> 2) - pX + 2;
        int pH = ((z + h) >> 2) - pZ + 2;

        if (l.p != null) {
            int err = l.p.getMap.apply(l.p, out, pX, pZ, pW, pH);
            if (err != 0) return err;
        }

        int[] buf = new int[pW * pH * 4];
        long st = l.startSalt;
        long ss = l.startSeed;

        for (int pj = 0; pj < pH - 1; pj++) {
            int v00 = out[(pj + 0) * pW];
            int v01 = out[(pj + 1) * pW];
            int pjz = pZ + pj;
            int j4 = pjz * 4 - z;

            for (int pi = 0; pi < pW - 1; pi++) {
                int pix = pX + pi;
                int i4 = pix * 4 - x;
                int v10 = out[pi + 1 + (pj + 0) * pW];
                int v11 = out[pi + 1 + (pj + 1) * pW];

                if (v00 == v01 && v00 == v10 && v00 == v11) {
                    for (int jj = 0; jj < 4; jj++) {
                        int j = j4 + jj;
                        if (j < 0 || j >= h) continue;
                        for (int ii = 0; ii < 4; ii++) {
                            int i = i4 + ii;
                            if (i < 0 || i >= w) continue;
                            buf[j * w + i] = v00;
                        }
                    }
                    continue;
                }

                long cs = Rng.getChunkSeed(ss, (pi + pX) * 4, (pj + pZ) * 4);
                long da1 = (Rng.mcFirstInt(cs, 1024) - 512) * 36;
                cs = Rng.mcStepSeed(cs, st);
                long da2 = (Rng.mcFirstInt(cs, 1024) - 512) * 36;

                cs = Rng.getChunkSeed(ss, (pi + pX + 1) * 4, (pj + pZ) * 4);
                long db1 = (Rng.mcFirstInt(cs, 1024) - 512) * 36 + 40 * 1024;
                cs = Rng.mcStepSeed(cs, st);
                long db2 = (Rng.mcFirstInt(cs, 1024) - 512) * 36;

                cs = Rng.getChunkSeed(ss, (pi + pX) * 4, (pj + pZ + 1) * 4);
                long dc1 = (Rng.mcFirstInt(cs, 1024) - 512) * 36;
                cs = Rng.mcStepSeed(cs, st);
                long dc2 = (Rng.mcFirstInt(cs, 1024) - 512) * 36 + 40 * 1024;

                cs = Rng.getChunkSeed(ss, (pi + pX + 1) * 4, (pj + pZ + 1) * 4);
                long dd1 = (Rng.mcFirstInt(cs, 1024) - 512) * 36 + 40 * 1024;
                cs = Rng.mcStepSeed(cs, st);
                long dd2 = (Rng.mcFirstInt(cs, 1024) - 512) * 36 + 40 * 1024;

                for (int jj = 0; jj < 4; jj++) {
                    int j = j4 + jj;
                    if (j < 0 || j >= h) continue;

                    long mj = 10240L * jj;
                    long sja = (mj - da2) * (mj - da2);
                    long sjb = (mj - db2) * (mj - db2);
                    long sjc = (mj - dc2) * (mj - dc2);
                    long sjd = (mj - dd2) * (mj - dd2);

                    for (int ii = 0; ii < 4; ii++) {
                        int i = i4 + ii;
                        if (i < 0 || i >= w) continue;

                        long mi = 10240L * ii;
                        long da = (mi - da1) * (mi - da1) + sja;
                        long db = (mi - db1) * (mi - db1) + sjb;
                        long dc = (mi - dc1) * (mi - dc1) + sjc;
                        long dd = (mi - dd1) * (mi - dd1) + sjd;

                        int v;
                        if (da < db && da < dc && da < dd) {
                            v = v00;
                        } else if (db < da && db < dc && db < dd) {
                            v = v10;
                        } else if (dc < da && dc < db && dc < dd) {
                            v = v01;
                        } else {
                            v = v11;
                        }
                        buf[j * w + i] = v;
                    }
                }
                v00 = v10;
                v01 = v11;
            }
        }
        System.arraycopy(buf, 0, out, 0, w * h);
        return 0;
    };
}
