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

/// Layer mapping functions for the pre-1.18 biome generator.
final class Layers {
    private Layers() {
    }

    private static final int ocean = Biome.OCEAN.id();
    private static final int plains = Biome.PLAINS.id();
    private static final int desert = Biome.DESERT.id();
    private static final int mountains = Biome.WINDSWEPT_HILLS.id();
    private static final int forest = Biome.FOREST.id();
    private static final int taiga = Biome.TAIGA.id();
    private static final int swamp = Biome.SWAMP.id();
    private static final int river = Biome.RIVER.id();
    private static final int nether_wastes = Biome.NETHER_WASTES.id();
    private static final int the_end = Biome.THE_END.id();
    private static final int frozen_ocean = Biome.FROZEN_OCEAN.id();
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

    private static final int Oceanic = 0;
    private static final int Warm = 1;
    private static final int Lush = 2;
    private static final int Cold = 3;
    private static final int Freezing = 4;
    private static final int Special = 5;

    static final Layer.MapFunc mapContinent = (l, out, x, z, w, h) -> {
        if (w * h > out.length) {
            throw new IllegalStateException("continent buffer too small: w=" + w + " h=" + h + " out=" + out.length);
        }
        long ss = l.startSeed;
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                long cs = Rng.getChunkSeed(ss, i + x, j + z);
                out[j * w + i] = Rng.mcFirstIsZero(cs, 10) ? 1 : 0;
            }
        }
        if (x > -w && x <= 0 && z > -h && z <= 0) {
            out[-z * w - x] = 1;
        }
        return 0;
    };

    static final Layer.MapFunc mapZoomFuzzy = (l, out, x, z, w, h) -> {
        int pX = x >> 1;
        int pZ = z >> 1;
        int pW = ((x + w) >> 1) - pX + 1;
        int pH = ((z + h) >> 1) - pZ + 1;

        int err = l.p.getMap.apply(l.p, out, pX, pZ, pW, pH);
        if (err != 0) return err;

        int newW = pW * 2;
        int[] buf = new int[newW * pH * 2];
        int st = (int) l.startSalt;
        int ss = (int) l.startSeed;

        for (int j = 0; j < pH; j++) {
            int idx = (j * 2) * newW;
            int v00 = out[(j + 0) * pW];
            int v01 = out[(j + 1) * pW];

            for (int i = 0; i < pW; i++) {
                int v10 = out[i + 1 + (j + 0) * pW];
                int v11 = out[i + 1 + (j + 1) * pW];

                if (v00 == v01 && v00 == v10 && v00 == v11) {
                    buf[idx] = v00;
                    buf[idx + 1] = v00;
                    buf[idx + newW] = v00;
                    buf[idx + newW + 1] = v00;
                    idx += 2;
                    v00 = v10;
                    v01 = v11;
                    continue;
                }

                int chunkX = (i + pX) * 2;
                int chunkZ = (j + pZ) * 2;

                int cs = ss;
                cs += chunkX;
                cs *= cs * 1284865837 + (int) 4150755663L;
                cs += chunkZ;
                cs *= cs * 1284865837 + (int) 4150755663L;
                cs += chunkX;
                cs *= cs * 1284865837 + (int) 4150755663L;
                cs += chunkZ;

                buf[idx] = v00;
                buf[idx + newW] = ((cs >>> 24) & 1) != 0 ? v01 : v00;
                idx++;

                cs *= cs * 1284865837 + (int) 4150755663L;
                cs += st;
                buf[idx] = ((cs >>> 24) & 1) != 0 ? v10 : v00;

                cs *= cs * 1284865837 + (int) 4150755663L;
                cs += st;
                int r = (cs >>> 24) & 3;
                buf[idx + newW] = r == 0 ? v00 : r == 1 ? v10 : r == 2 ? v01 : v11;
                idx++;
                v00 = v10;
                v01 = v11;
            }
        }

        for (int j = 0; j < h; j++) {
            System.arraycopy(buf, (j + (z & 1)) * newW + (x & 1), out, j * w, w);
        }
        return 0;
    };

    static final Layer.MapFunc mapZoom = (l, out, x, z, w, h) -> {
        int pX = x >> 1;
        int pZ = z >> 1;
        int pW = ((x + w) >> 1) - pX + 1;
        int pH = ((z + h) >> 1) - pZ + 1;

        int err = l.p.getMap.apply(l.p, out, pX, pZ, pW, pH);
        if (err != 0) return err;

        int newW = pW * 2;
        int[] buf = new int[newW * pH * 2];
        int st = (int) l.startSalt;
        int ss = (int) l.startSeed;

        for (int j = 0; j < pH; j++) {
            int idx = (j * 2) * newW;
            int v00 = out[(j + 0) * pW];
            int v01 = out[(j + 1) * pW];

            for (int i = 0; i < pW; i++) {
                int v10 = out[i + 1 + (j + 0) * pW];
                int v11 = out[i + 1 + (j + 1) * pW];

                if (v00 == v01 && v00 == v10 && v00 == v11) {
                    buf[idx] = v00;
                    buf[idx + 1] = v00;
                    buf[idx + newW] = v00;
                    buf[idx + newW + 1] = v00;
                    idx += 2;
                    v00 = v10;
                    v01 = v11;
                    continue;
                }

                int chunkX = (i + pX) * 2;
                int chunkZ = (j + pZ) * 2;

                int cs = ss;
                cs += chunkX;
                cs *= cs * 1284865837 + (int) 4150755663L;
                cs += chunkZ;
                cs *= cs * 1284865837 + (int) 4150755663L;
                cs += chunkX;
                cs *= cs * 1284865837 + (int) 4150755663L;
                cs += chunkZ;

                buf[idx] = v00;
                buf[idx + newW] = ((cs >>> 24) & 1) != 0 ? v01 : v00;
                idx++;

                cs *= cs * 1284865837 + (int) 4150755663L;
                cs += st;
                buf[idx] = ((cs >>> 24) & 1) != 0 ? v10 : v00;

                buf[idx + newW] = select4(cs, st, v00, v01, v10, v11);
                idx++;
                v00 = v10;
                v01 = v11;
            }
        }

        for (int j = 0; j < h; j++) {
            System.arraycopy(buf, (j + (z & 1)) * newW + (x & 1), out, j * w, w);
        }
        return 0;
    };

    private static int select4(int cs, int st, int v00, int v01, int v10, int v11) {
        int v;
        int cv00 = (v00 == v10 ? 1 : 0) + (v00 == v01 ? 1 : 0) + (v00 == v11 ? 1 : 0);
        int cv10 = (v10 == v01 ? 1 : 0) + (v10 == v11 ? 1 : 0);
        int cv01 = (v01 == v11 ? 1 : 0);
        if (cv00 > cv10 && cv00 > cv01) {
            v = v00;
        } else if (cv10 > cv00) {
            v = v10;
        } else if (cv01 > cv00) {
            v = v01;
        } else {
            cs *= cs * 1284865837 + (int) 4150755663L;
            cs += st;
            int r = (cs >>> 24) & 3;
            v = r == 0 ? v00 : r == 1 ? v10 : r == 2 ? v01 : v11;
        }
        return v;
    }

    static final Layer.MapFunc mapLand = (l, out, x, z, w, h) -> {
        int pX = x - 1;
        int pZ = z - 1;
        int pW = w + 2;
        int pH = h + 2;

        int err = l.p.getMap.apply(l.p, out, pX, pZ, pW, pH);
        if (err != 0) return err;

        long st = l.startSalt;
        long ss = l.startSeed;

        for (int j = 0; j < h; j++) {
            int vz0 = (j + 0) * pW;
            int vz1 = (j + 1) * pW;
            int vz2 = (j + 2) * pW;

            int v00 = out[vz0];
            int vt0 = out[vz0 + 1];
            int v02 = out[vz2];
            int vt2 = out[vz2 + 1];

            for (int i = 0; i < w; i++) {
                int v11 = out[vz1 + i + 1];
                int v20 = out[vz0 + i + 2];
                int v22 = out[vz2 + i + 2];
                int v = v11;

                switch (v11) {
                    case 0 -> {
                        if (v00 != 0 || v20 != 0 || v02 != 0 || v22 != 0) {
                            long cs = Rng.getChunkSeed(ss, i + x, j + z);
                            int inc = 0;
                            v = 1;

                            if (v00 != 0) {
                                ++inc;
                                v = v00;
                                cs = Rng.mcStepSeed(cs, st);
                            }
                            if (v20 != 0) {
                                if (++inc == 1 || Rng.mcFirstIsZero(cs, 2)) v = v20;
                                cs = Rng.mcStepSeed(cs, st);
                            }
                            if (v02 != 0) {
                                switch (++inc) {
                                    case 1 -> v = v02;
                                    case 2 -> { if (Rng.mcFirstIsZero(cs, 2)) v = v02; }
                                    default -> { if (Rng.mcFirstIsZero(cs, 3)) v = v02; }
                                }
                                cs = Rng.mcStepSeed(cs, st);
                            }
                            if (v22 != 0) {
                                switch (++inc) {
                                    case 1 -> v = v22;
                                    case 2 -> { if (Rng.mcFirstIsZero(cs, 2)) v = v22; }
                                    case 3 -> { if (Rng.mcFirstIsZero(cs, 3)) v = v22; }
                                    default -> { if (Rng.mcFirstIsZero(cs, 4)) v = v22; }
                                }
                                cs = Rng.mcStepSeed(cs, st);
                            }

                            if (v != forest) {
                                if (!Rng.mcFirstIsZero(cs, 3)) v = 0;
                            }
                        }
                    }
                    case 4 -> {
                    }
                    default -> {
                        if (v00 == 0 || v20 == 0 || v02 == 0 || v22 == 0) {
                            long cs = Rng.getChunkSeed(ss, i + x, j + z);
                            if (Rng.mcFirstIsZero(cs, 5)) v = 0;
                        }
                    }
                }

                out[i + j * w] = v;
                v00 = vt0;
                vt0 = v20;
                v02 = vt2;
                vt2 = v22;
            }
        }
        return 0;
    };

    static final Layer.MapFunc mapLand16 = (l, out, x, z, w, h) -> {
        int pX = x - 1;
        int pZ = z - 1;
        int pW = w + 2;
        int pH = h + 2;

        int err = l.p.getMap.apply(l.p, out, pX, pZ, pW, pH);
        if (err != 0) return err;

        long st = l.startSalt;
        long ss = l.startSeed;

        for (int j = 0; j < h; j++) {
            int vz0 = (j + 0) * pW;
            int vz1 = (j + 1) * pW;
            int vz2 = (j + 2) * pW;

            int v00 = out[vz0];
            int vt0 = out[vz0 + 1];
            int v02 = out[vz2];
            int vt2 = out[vz2 + 1];

            for (int i = 0; i < w; i++) {
                int v11 = out[vz1 + i + 1];
                int v20 = out[vz0 + i + 2];
                int v22 = out[vz2 + i + 2];
                int v = v11;

                if (v11 != 0 || (v00 == 0 && v20 == 0 && v02 == 0 && v22 == 0)) {
                    if (v11 != 0 && (v00 == 0 || v20 == 0 || v02 == 0 || v22 == 0)) {
                        long cs = Rng.getChunkSeed(ss, i + x, j + z);
                        if (Rng.mcFirstIsZero(cs, 5)) {
                            v = (v == snowy_tundra) ? frozen_ocean : ocean;
                        }
                    }
                } else {
                    long cs = Rng.getChunkSeed(ss, i + x, j + z);
                    int inc = 0;
                    v = 1;

                    if (v00 != 0) {
                        ++inc;
                        v = v00;
                        cs = Rng.mcStepSeed(cs, st);
                    }
                    if (v20 != 0) {
                        if (++inc == 1 || Rng.mcFirstIsZero(cs, 2)) v = v20;
                        cs = Rng.mcStepSeed(cs, st);
                    }
                    if (v02 != 0) {
                        switch (++inc) {
                            case 1 -> v = v02;
                            case 2 -> { if (Rng.mcFirstIsZero(cs, 2)) v = v02; }
                            default -> { if (Rng.mcFirstIsZero(cs, 3)) v = v02; }
                        }
                        cs = Rng.mcStepSeed(cs, st);
                    }
                    if (v22 != 0) {
                        switch (++inc) {
                            case 1 -> v = v22;
                            case 2 -> { if (Rng.mcFirstIsZero(cs, 2)) v = v22; }
                            case 3 -> { if (Rng.mcFirstIsZero(cs, 3)) v = v22; }
                            default -> { if (Rng.mcFirstIsZero(cs, 4)) v = v22; }
                        }
                        cs = Rng.mcStepSeed(cs, st);
                    }

                    if (!Rng.mcFirstIsZero(cs, 3)) {
                        v = (v == snowy_tundra) ? frozen_ocean : ocean;
                    }
                }

                out[i + j * w] = v;
                v00 = vt0;
                vt0 = v20;
                v02 = vt2;
                vt2 = v22;
            }
        }
        return 0;
    };

    static final Layer.MapFunc mapIsland = (l, out, x, z, w, h) -> {
        int pX = x - 1;
        int pZ = z - 1;
        int pW = w + 2;
        int pH = h + 2;

        int err = l.p.getMap.apply(l.p, out, pX, pZ, pW, pH);
        if (err != 0) return err;

        long ss = l.startSeed;
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int v11 = out[i + 1 + (j + 1) * pW];
                out[i + j * w] = v11;

                if (v11 == Oceanic) {
                    if (out[i + 1 + (j + 0) * pW] != Oceanic) continue;
                    if (out[i + 2 + (j + 1) * pW] != Oceanic) continue;
                    if (out[i + 0 + (j + 1) * pW] != Oceanic) continue;
                    if (out[i + 1 + (j + 2) * pW] != Oceanic) continue;

                    long cs = Rng.getChunkSeed(ss, i + x, j + z);
                    if (Rng.mcFirstIsZero(cs, 2)) {
                        out[i + j * w] = 1;
                    }
                }
            }
        }
        return 0;
    };

    static final Layer.MapFunc mapSnow16 = (l, out, x, z, w, h) -> {
        int pX = x - 1;
        int pZ = z - 1;
        int pW = w + 2;
        int pH = h + 2;

        int err = l.p.getMap.apply(l.p, out, pX, pZ, pW, pH);
        if (err != 0) return err;

        long ss = l.startSeed;
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int v11 = out[i + 1 + (j + 1) * pW];
                if (v11 != ocean) {
                    long cs = Rng.getChunkSeed(ss, i + x, j + z);
                    v11 = Rng.mcFirstIsZero(cs, 5) ? snowy_tundra : plains;
                }
                out[i + j * w] = v11;
            }
        }
        return 0;
    };

    static final Layer.MapFunc mapSnow = (l, out, x, z, w, h) -> {
        int pX = x - 1;
        int pZ = z - 1;
        int pW = w + 2;
        int pH = h + 2;

        int err = l.p.getMap.apply(l.p, out, pX, pZ, pW, pH);
        if (err != 0) return err;

        long ss = l.startSeed;
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int v11 = out[i + 1 + (j + 1) * pW];
                if (!isShallowOcean(v11)) {
                    long cs = Rng.getChunkSeed(ss, i + x, j + z);
                    int r = Rng.mcFirstInt(cs, 6);
                    if (r == 0) v11 = Freezing;
                    else if (r <= 1) v11 = Cold;
                    else v11 = Warm;
                }
                out[i + j * w] = v11;
            }
        }
        return 0;
    };

    static final Layer.MapFunc mapCool = (l, out, x, z, w, h) -> {
        int pX = x - 1;
        int pZ = z - 1;
        int pW = w + 2;
        int pH = h + 2;

        int err = l.p.getMap.apply(l.p, out, pX, pZ, pW, pH);
        if (err != 0) return err;

        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int v11 = out[i + 1 + (j + 1) * pW];

                if (v11 == Warm) {
                    int v10 = out[i + 1 + (j + 0) * pW];
                    int v21 = out[i + 2 + (j + 1) * pW];
                    int v01 = out[i + 0 + (j + 1) * pW];
                    int v12 = out[i + 1 + (j + 2) * pW];

                    if (Layer.isAny4(Cold, v10, v21, v01, v12) || Layer.isAny4(Freezing, v10, v21, v01, v12)) {
                        v11 = Lush;
                    }
                }
                out[i + j * w] = v11;
            }
        }
        return 0;
    };

    static final Layer.MapFunc mapHeat = (l, out, x, z, w, h) -> {
        int pX = x - 1;
        int pZ = z - 1;
        int pW = w + 2;
        int pH = h + 2;

        int err = l.p.getMap.apply(l.p, out, pX, pZ, pW, pH);
        if (err != 0) return err;

        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int v11 = out[i + 1 + (j + 1) * pW];

                if (v11 == Freezing) {
                    int v10 = out[i + 1 + (j + 0) * pW];
                    int v21 = out[i + 2 + (j + 1) * pW];
                    int v01 = out[i + 0 + (j + 1) * pW];
                    int v12 = out[i + 1 + (j + 2) * pW];

                    if (Layer.isAny4(Warm, v10, v21, v01, v12) || Layer.isAny4(Lush, v10, v21, v01, v12)) {
                        v11 = Cold;
                    }
                }
                out[i + j * w] = v11;
            }
        }
        return 0;
    };

    static final Layer.MapFunc mapSpecial = (l, out, x, z, w, h) -> {
        int err = l.p.getMap.apply(l.p, out, x, z, w, h);
        if (err != 0) return err;

        long st = l.startSalt;
        long ss = l.startSeed;
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int v = out[i + j * w];
                if (v == Oceanic) continue;

                long cs = Rng.getChunkSeed(ss, i + x, j + z);
                if (Rng.mcFirstIsZero(cs, 13)) {
                    cs = Rng.mcStepSeed(cs, st);
                    v |= (1 + Rng.mcFirstInt(cs, 15)) << 8 & 0xf00;
                    out[i + j * w] = v;
                }
            }
        }
        return 0;
    };

    static final Layer.MapFunc mapMushroom = (l, out, x, z, w, h) -> {
        int pX = x - 1;
        int pZ = z - 1;
        int pW = w + 2;
        int pH = h + 2;

        int err = l.p.getMap.apply(l.p, out, pX, pZ, pW, pH);
        if (err != 0) return err;

        long ss = l.startSeed;
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int v11 = out[i + 1 + (j + 1) * pW];

                if (v11 == 0
                        && out[i + 0 + (j + 0) * pW] == 0 && out[i + 2 + (j + 0) * pW] == 0
                        && out[i + 0 + (j + 2) * pW] == 0 && out[i + 2 + (j + 2) * pW] == 0) {
                    long cs = Rng.getChunkSeed(ss, i + x, j + z);
                    if (Rng.mcFirstIsZero(cs, 100)) {
                        v11 = mushroom_fields;
                    }
                }
                out[i + j * w] = v11;
            }
        }
        return 0;
    };

    static final Layer.MapFunc mapDeepOcean = (l, out, x, z, w, h) -> {
        int pX = x - 1;
        int pZ = z - 1;
        int pW = w + 2;
        int pH = h + 2;

        int err = l.p.getMap.apply(l.p, out, pX, pZ, pW, pH);
        if (err != 0) return err;

        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int v11 = out[(i + 1) + (j + 1) * pW];

                if (isShallowOcean(v11)) {
                    int oceans = 0;
                    if (isShallowOcean(out[(i + 1) + (j + 0) * pW])) oceans++;
                    if (isShallowOcean(out[(i + 2) + (j + 1) * pW])) oceans++;
                    if (isShallowOcean(out[(i + 0) + (j + 1) * pW])) oceans++;
                    if (isShallowOcean(out[(i + 1) + (j + 2) * pW])) oceans++;

                    if (oceans >= 4) {
                        switch (v11) {
                            case 44 -> v11 = deep_warm_ocean;
                            case 45 -> v11 = deep_lukewarm_ocean;
                            case 0 -> v11 = deep_ocean;
                            case 46 -> v11 = deep_cold_ocean;
                            case 10 -> v11 = deep_frozen_ocean;
                            default -> v11 = deep_ocean;
                        }
                    }
                }
                out[i + j * w] = v11;
            }
        }
        return 0;
    };

    private static final int[] warmBiomes = {desert, desert, desert, savanna, savanna, plains};
    private static final int[] lushBiomes = {forest, dark_forest, mountains, plains, birch_forest, swamp};
    private static final int[] coldBiomes = {forest, mountains, taiga, plains};
    private static final int[] snowBiomes = {snowy_tundra, snowy_tundra, snowy_tundra, snowy_taiga};
    private static final int[] oldBiomes = {desert, forest, mountains, swamp, plains, taiga, jungle};
    private static final int[] oldBiomes11 = {desert, forest, mountains, swamp, plains, taiga};

    static final Layer.MapFunc mapBiome = (l, out, x, z, w, h) -> {
        int err = l.p.getMap.apply(l.p, out, x, z, w, h);
        if (err != 0) return err;

        int mc = l.mc;
        long ss = l.startSeed;
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int idx = i + j * w;
                int id = out[idx];
                int v;
                int hasHighBit = id & 0xf00;
                id &= ~0xf00;

                if (mc <= 106) {
                    if (id == ocean || id == mushroom_fields) {
                        out[idx] = id;
                        continue;
                    }
                    long cs = Rng.getChunkSeed(ss, i + x, j + z);
                    if (mc <= 101) {
                        v = oldBiomes11[Rng.mcFirstInt(cs, 6)];
                    } else {
                        v = oldBiomes[Rng.mcFirstInt(cs, 7)];
                    }
                    if (id != plains && (v != taiga || mc <= 102)) {
                        v = snowy_tundra;
                    }
                } else {
                    if (isOceanic(id) || id == mushroom_fields) {
                        out[idx] = id;
                        continue;
                    }
                    long cs = Rng.getChunkSeed(ss, i + x, j + z);
                    switch (id) {
                        case 1 -> {
                            if (hasHighBit != 0) {
                                v = Rng.mcFirstIsZero(cs, 3) ? badlands_plateau : wooded_badlands_plateau;
                            } else {
                                v = warmBiomes[Rng.mcFirstInt(cs, 6)];
                            }
                        }
                        case 2 -> {
                            if (hasHighBit != 0) {
                                v = jungle;
                            } else {
                                v = lushBiomes[Rng.mcFirstInt(cs, 6)];
                            }
                        }
                        case 3 -> {
                            if (hasHighBit != 0) {
                                v = giant_tree_taiga;
                            } else {
                                v = coldBiomes[Rng.mcFirstInt(cs, 4)];
                            }
                        }
                        case 4 -> v = snowBiomes[Rng.mcFirstInt(cs, 4)];
                        default -> v = mushroom_fields;
                    }
                }
                out[idx] = v;
            }
        }
        return 0;
    };

    static final Layer.MapFunc mapNoise = (l, out, x, z, w, h) -> {
        int err = l.p.getMap.apply(l.p, out, x, z, w, h);
        if (err != 0) return err;

        long ss = l.startSeed;
        int mod = (l.mc <= 106) ? 2 : 299999;
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                if (out[i + j * w] > 0) {
                    long cs = Rng.getChunkSeed(ss, i + x, j + z);
                    out[i + j * w] = Rng.mcFirstInt(cs, mod) + 2;
                } else {
                    out[i + j * w] = 0;
                }
            }
        }
        return 0;
    };

    static final Layer.MapFunc mapBamboo = (l, out, x, z, w, h) -> {
        int err = l.p.getMap.apply(l.p, out, x, z, w, h);
        if (err != 0) return err;

        long ss = l.startSeed;
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int idx = i + j * w;
                if (out[idx] != jungle) continue;

                long cs = Rng.getChunkSeed(ss, i + x, j + z);
                if (Rng.mcFirstIsZero(cs, 10)) {
                    out[idx] = bamboo_jungle;
                }
            }
        }
        return 0;
    };

    private static boolean replaceEdge(int[] out, int idx, int mc, int v10, int v21, int v01, int v12,
                                       int id, int baseID, int edgeID) {
        if (id != baseID) return false;
        if (areSimilar(mc, v10, baseID) && areSimilar(mc, v21, baseID)
                && areSimilar(mc, v01, baseID) && areSimilar(mc, v12, baseID)) {
            out[idx] = id;
        } else {
            out[idx] = edgeID;
        }
        return true;
    }

    static final Layer.MapFunc mapBiomeEdge = (l, out, x, z, w, h) -> {
        int pX = x - 1;
        int pZ = z - 1;
        int pW = w + 2;
        int pH = h + 2;
        int mc = l.mc;

        int err = l.p.getMap.apply(l.p, out, pX, pZ, pW, pH);
        if (err != 0) return err;

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

                if (!replaceEdge(out, i + j * w, mc, v10, v21, v01, v12, v11, wooded_badlands_plateau, badlands)
                        && !replaceEdge(out, i + j * w, mc, v10, v21, v01, v12, v11, badlands_plateau, badlands)
                        && !replaceEdge(out, i + j * w, mc, v10, v21, v01, v12, v11, giant_tree_taiga, taiga)) {
                    if (v11 == desert) {
                        if (!Layer.isAny4(snowy_tundra, v10, v21, v01, v12)) {
                            out[i + j * w] = v11;
                        } else {
                            out[i + j * w] = wooded_mountains;
                        }
                    } else if (v11 == swamp) {
                        if (!Layer.isAny4(desert, v10, v21, v01, v12)
                                && !Layer.isAny4(snowy_taiga, v10, v21, v01, v12)
                                && !Layer.isAny4(snowy_tundra, v10, v21, v01, v12)) {
                            if (!Layer.isAny4(jungle, v10, v21, v01, v12)
                                    && !Layer.isAny4(bamboo_jungle, v10, v21, v01, v12)) {
                                out[i + j * w] = v11;
                            } else {
                                out[i + j * w] = jungle_edge;
                            }
                        } else {
                            out[i + j * w] = plains;
                        }
                    } else {
                        out[i + j * w] = v11;
                    }
                }
            }
        }
        return 0;
    };

    static boolean isShallowOcean(int id) {
        return id == ocean || id == frozen_ocean || id == warm_ocean
                || id == lukewarm_ocean || id == cold_ocean;
    }

    static boolean isDeepOcean(int id) {
        return id == deep_ocean || id == deep_frozen_ocean || id == deep_warm_ocean
                || id == deep_lukewarm_ocean || id == deep_cold_ocean;
    }

    static boolean isOceanic(int id) {
        return isShallowOcean(id) || isDeepOcean(id);
    }

    static boolean isSnowy(int id) {
        return id == snowy_tundra || id == snowy_mountains || id == snowy_taiga
                || id == snowy_taiga_hills || id == snowy_beach || id == frozen_ocean
                || id == frozen_river || id == ice_spikes();
    }

    private static int ice_spikes() {
        return Biome.ICE_SPIKES.id();
    }

    static boolean isMesa(int id) {
        return id == badlands || id == wooded_badlands_plateau || id == badlands_plateau
                || id == Biome.ERODED_BADLANDS.id() || id == Biome.MODIFIED_WOODED_BADLANDS_PLATEAU.id()
                || id == Biome.MODIFIED_BADLANDS_PLATEAU.id();
    }

    static int getMutated(int mc, int id) {
        return switch (id) {
            case 1 -> Biome.SUNFLOWER_PLAINS.id();
            case 2 -> Biome.DESERT_LAKES.id();
            case 3 -> Biome.WINDSWEPT_GRAVELLY_HILLS.id();
            case 4 -> Biome.FLOWER_FOREST.id();
            case 5 -> Biome.TAIGA_MOUNTAINS.id();
            case 6 -> Biome.SWAMP_HILLS.id();
            case 12 -> Biome.ICE_SPIKES.id();
            case 21 -> Biome.MODIFIED_JUNGLE.id();
            case 23 -> Biome.MODIFIED_JUNGLE_EDGE.id();
            case 27 -> Biome.OLD_GROWTH_BIRCH_FOREST.id();
            case 28 -> Biome.TALL_BIRCH_HILLS.id();
            case 29 -> Biome.DARK_FOREST_HILLS.id();
            case 30 -> Biome.SNOWY_TAIGA_MOUNTAINS.id();
            case 32 -> Biome.OLD_GROWTH_SPRUCE_TAIGA.id();
            case 33 -> Biome.GIANT_SPRUCE_TAIGA_HILLS.id();
            case 34 -> Biome.MODIFIED_GRAVELLY_MOUNTAINS.id();
            case 35 -> Biome.WINDSWEPT_SAVANNA.id();
            case 36 -> Biome.SHATTERED_SAVANNA_PLATEAU.id();
            case 37 -> Biome.ERODED_BADLANDS.id();
            case 38 -> Biome.MODIFIED_WOODED_BADLANDS_PLATEAU.id();
            case 39 -> Biome.MODIFIED_BADLANDS_PLATEAU.id();
            default -> -1;
        };
    }

    static int getCategory(int mc, int id) {
        if (isOceanic(id)) return ocean;
        return switch (id) {
            case 16, 26 -> beach;
            case 2, 17, 130 -> desert;
            case 3, 20, 34, 131, 162 -> mountains;
            case 4, 18, 27, 28, 29, 132, 155, 156, 157 -> forest;
            case 12, 13, 140 -> snowy_tundra;
            case 21, 22, 23, 149, 151 -> jungle;
            case 5, 19, 133, 30, 31, 158, 32, 33, 160, 161 -> taiga;
            case 6, 134 -> swamp;
            case 1, 129 -> plains;
            case 35, 36, 163, 164 -> savanna;
            case 37, 165, 166, 167 -> badlands;
            case 38, 39 -> mc <= 115 ? badlands : badlands_plateau;
            case 7, 11 -> river;
            case 14, 15 -> mushroom_fields;
            case 25 -> stone_shore;
            default -> -1;
        };
    }

    static boolean areSimilar(int mc, int id1, int id2) {
        if (id1 == id2) return true;
        if (mc <= 115) {
            if (id1 == wooded_badlands_plateau || id1 == badlands_plateau) {
                return id2 == wooded_badlands_plateau || id2 == badlands_plateau;
            }
        }
        return getCategory(mc, id1) == getCategory(mc, id2);
    }
}
