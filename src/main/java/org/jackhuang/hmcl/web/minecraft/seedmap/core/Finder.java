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
 * Ported from cubiomes finders.c / finders.h (MIT license, Copyright (C) Cubitect).
 */
package org.jackhuang.hmcl.web.minecraft.seedmap.core;

import java.util.ArrayList;
import java.util.List;

/// Structure placement and stronghold ring maths.
final class Finder {
    private Finder() {
    }

    static StructureConfig configFor(StructureType type, Version version) {
        return switch (type) {
            case VILLAGE -> version == Version.V1_12_2 || version == Version.V1_14_4 || version == Version.V1_16_5
                    ? new StructureConfig(10387312, 32, 24)
                    : new StructureConfig(10387312, 34, 26);
            case DESERT_PYRAMID -> new StructureConfig(14357617, 32, 24);
            case JUNGLE_TEMPLE -> new StructureConfig(14357619, 32, 24);
            case IGLOO -> new StructureConfig(14357618, 32, 24);
            case SWAMP_HUT -> new StructureConfig(14357620, 32, 24);
            case WOODLAND_MANSION -> new StructureConfig(10387319, 80, 60);
            case OCEAN_MONUMENT -> new StructureConfig(10387313, 32, 27);
            case OCEAN_RUIN -> version == Version.V1_14_4 || version == Version.V1_12_2
                    ? new StructureConfig(14357621, 16, 8)
                    : new StructureConfig(14357621, 20, 12);
            case SHIPWRECK -> version == Version.V1_14_4 || version == Version.V1_12_2
                    ? new StructureConfig(165745295, 16, 8)
                    : new StructureConfig(165745295, 24, 20);
            case RUINED_PORTAL -> new StructureConfig(34222645, 40, 25);
            case PILLAGER_OUTPOST -> new StructureConfig(165745296, 32, 24);
            case ANCIENT_CITY -> new StructureConfig(20083232, 24, 16);
            case TRIAL_CHAMBERS -> new StructureConfig(94251327, 34, 22);
            case BURIED_TREASURE -> new StructureConfig(10387320, 1, 1);
            case MINESHAFT -> new StructureConfig(0, 1, 1);
            case STRONGHOLD -> throw new IllegalArgumentException("strongholds use the ring iterator");
        };
    }

    static BlockPos structurePos(StructureType type, Version version, long seed, int regX, int regZ) {
        StructureConfig config = configFor(type, version);
        return switch (type) {
            case WOODLAND_MANSION, OCEAN_MONUMENT -> largeStructurePos(config, seed, regX, regZ);
            case BURIED_TREASURE -> {
                long[] s = {seed};
                long value = regX * 341873128712L + regZ * 132897987541L + seed + config.salt();
                s[0] = Rng.setSeed(value);
                if (Rng.nextFloat(s) < 0.01f) {
                    yield new BlockPos(regX * 16 + 9, 0, regZ * 16 + 9);
                }
                yield null;
            }
            case PILLAGER_OUTPOST -> {
                BlockPos pos = featurePos(config, seed, regX, regZ);
                long[] s = {seed};
                setAttemptSeed(s, pos.x() >> 4, pos.z() >> 4);
                yield Rng.nextInt(s, 5) == 0 ? pos : null;
            }
            default -> featurePos(config, seed, regX, regZ);
        };
    }

    static BlockPos featurePos(StructureConfig config, long seed, int regX, int regZ) {
        long[] chunk = featureChunkInRegion(config, seed, regX, regZ);
        int x = (int) (((long) regX * config.regionSize() + chunk[0]) << 4);
        int z = (int) (((long) regZ * config.regionSize() + chunk[1]) << 4);
        return new BlockPos(x, 0, z);
    }

    private static long[] featureChunkInRegion(StructureConfig config, long seed, int regX, int regZ) {
        final long k = 0x5deece66dL;
        final long m = (1L << 48) - 1;
        final long b = 0xbL;

        seed = seed + regX * 341873128712L + regZ * 132897987541L + config.salt();
        seed = (seed ^ k);
        seed = (seed * k + b) & m;

        long r = config.chunkRange();
        long x;
        long z;
        if ((r & (r - 1)) != 0) {
            x = (seed >> 17) % r;
            seed = (seed * k + b) & m;
            z = (seed >> 17) % r;
        } else {
            x = (r * (seed >> 17)) >> 31;
            seed = (seed * k + b) & m;
            z = (r * (seed >> 17)) >> 31;
        }
        return new long[]{x, z};
    }

    static BlockPos largeStructurePos(StructureConfig config, long seed, int regX, int regZ) {
        long[] chunk = largeStructureChunkInRegion(config, seed, regX, regZ);
        int x = (int) (((long) regX * config.regionSize() + chunk[0]) << 4);
        int z = (int) (((long) regZ * config.regionSize() + chunk[1]) << 4);
        return new BlockPos(x, 0, z);
    }

    private static long[] largeStructureChunkInRegion(StructureConfig config, long seed, int regX, int regZ) {
        final long k = 0x5deece66dL;
        final long m = (1L << 48) - 1;
        final long b = 0xbL;

        seed = seed + regX * 341873128712L + regZ * 132897987541L + config.salt();
        seed = (seed ^ k);

        seed = (seed * k + b) & m;
        long x = (seed >> 17) % config.chunkRange();
        seed = (seed * k + b) & m;
        x += (seed >> 17) % config.chunkRange();

        seed = (seed * k + b) & m;
        long z = (seed >> 17) % config.chunkRange();
        seed = (seed * k + b) & m;
        z += (seed >> 17) % config.chunkRange();

        x >>= 1;
        z >>= 1;
        return new long[]{x, z};
    }

    static void setAttemptSeed(long[] s, int cx, int cz) {
        s[0] ^= (cx >> 4) ^ ((long) (cz >> 4) << 4);
        s[0] = Rng.setSeed(s[0]);
        Rng.next(s, 31);
    }

    static List<BlockPos> strongholds(Version version, long seed, int count) {
        List<BlockPos> out = new ArrayList<>();
        StrongholdIter iter = new StrongholdIter();
        initFirstStronghold(iter, version, seed);
        for (int i = 0; i < count; i++) {
            BlockPos pos = nextStronghold(iter, version);
            if (pos == null) {
                break;
            }
            out.add(pos);
        }
        return out;
    }

    private static void initFirstStronghold(StrongholdIter sh, Version version, long s48) {
        long[] rnds = {Rng.setSeed(s48)};
        double angle = 2.0 * Math.PI * Rng.nextDouble(rnds);
        double dist = (4.0 * 32.0) + (Rng.nextDouble(rnds) - 0.5) * 32 * 2.5;
        sh.nextApprox = new BlockPos(
                ((int) Math.round(Math.cos(angle) * dist) * 16) + 8,
                0,
                ((int) Math.round(Math.sin(angle) * dist) * 16) + 8);
        sh.index = 0;
        sh.ringNum = 0;
        sh.ringMax = 3;
        sh.ringIdx = 0;
        sh.angle = angle;
        sh.dist = dist;
        sh.rnds = rnds[0];
    }

    private static BlockPos nextStronghold(StrongholdIter sh, Version version) {
        // Without a full biome generator we cannot run locateBiome; the
        // approximate position is still deterministic and matches cubiomes
        // when biome snapping is disabled.
        BlockPos pos = sh.nextApprox;
        pos = new BlockPos((pos.x() & ~15) + 4, 0, (pos.z() & ~15) + 4);

        sh.ringIdx++;
        sh.angle += 2 * Math.PI / sh.ringMax;
        if (sh.ringIdx == sh.ringMax) {
            sh.ringNum++;
            sh.ringIdx = 0;
            sh.ringMax = switch (sh.ringNum) {
                case 1 -> 6;
                case 2 -> 8;
                case 3 -> 10;
                case 4 -> 12;
                case 5 -> 14;
                case 6 -> 16;
                case 7 -> 18;
                default -> 0;
            };
            if (sh.ringMax == 0) {
                return null;
            }
            sh.dist += (2.0 * sh.ringNum + 1.0) * 32.0 / (2.0 * sh.ringNum);
        }
        long[] rnds = {sh.rnds};
        sh.nextApprox = new BlockPos(
                ((int) Math.round(Math.cos(sh.angle) * sh.dist) * 16) + 8,
                0,
                ((int) Math.round(Math.sin(sh.angle) * sh.dist) * 16) + 8);
        sh.rnds = rnds[0];
        return pos;
    }

    static boolean isSlimeChunk(long seed, int chunkX, int chunkZ) {
        long rnd = seed;
        rnd += (int) (chunkX * 0x5ac0db);
        rnd += (int) (chunkX * chunkX * 0x4c1906);
        rnd += (int) (chunkZ * 0x5f24f);
        rnd += (int) (chunkZ * chunkZ * 0x4307a7L);
        rnd ^= 0x3ad8025fL;
        long[] s = {Rng.setSeed(rnd)};
        return Rng.nextInt(s, 10) == 0;
    }

    private static final class StrongholdIter {
        BlockPos nextApprox;
        int index;
        int ringNum;
        int ringMax;
        int ringIdx;
        double angle;
        double dist;
        long rnds;
    }
}
