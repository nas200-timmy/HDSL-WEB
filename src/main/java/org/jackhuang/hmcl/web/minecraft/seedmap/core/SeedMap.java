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

import java.util.ArrayList;
import java.util.List;

/// Public facade of the seed-map core.
///
/// A `SeedMap` is cheap to create and safe to share across threads as long as
/// each thread uses its own instance. All methods are pure functions of the
/// version and seed they were created with.
public final class SeedMap {
    private final Version version;
    private final long seed;
    private final Generator generator;

    private SeedMap(Version version, long seed) {
        this.version = version;
        this.seed = seed;
        this.generator = new Generator(version);
        this.generator.applySeed(seed);
    }

    public static SeedMap of(Version version, long seed) {
        return new SeedMap(version, seed);
    }

    public Version version() {
        return version;
    }

    public long seed() {
        return seed;
    }

    /// The biome at an exact block position.
    public Biome biomeAt(int blockX, int blockY, int blockZ) {
        return generator.biomeAt(blockX, blockY, blockZ);
    }

    /// The biome at sea level, the slice a flat seed map usually shows.
    public Biome biomeAt(int blockX, int blockZ) {
        return biomeAt(blockX, 63, blockZ);
    }

    /// A rectangular biome map sampled at the given scale.
    ///
    /// @param scale  horizontal scale in blocks per sample (1, 4, 16, ...)
    /// @param x      north-west corner in sample coordinates
    /// @param z      north-west corner in sample coordinates
    /// @param width  number of samples along x
    /// @param height number of samples along z
    /// @param y      vertical sample position at the same scale
    public Biome[] mapBiomes(int scale, int x, int z, int width, int height, int y) {
        return generator.mapBiomes(scale, x, z, width, height, y);
    }

    /// All structure candidates of one type whose region intersects the given
    /// block rectangle.
    public List<Structure> structures(StructureType type, int x0, int z0, int x1, int z1) {
        StructureConfig config = Finder.configFor(type, version);
        int regionSize = config.regionSize() * 16;
        int rx0 = Math.floorDiv(x0, regionSize);
        int rz0 = Math.floorDiv(z0, regionSize);
        int rx1 = Math.floorDiv(x1, regionSize);
        int rz1 = Math.floorDiv(z1, regionSize);
        List<Structure> out = new ArrayList<>();
        for (int rz = rz0; rz <= rz1; rz++) {
            for (int rx = rx0; rx <= rx1; rx++) {
                BlockPos pos = Finder.structurePos(type, version, seed, rx, rz);
                if (pos != null && pos.x() >= x0 && pos.x() <= x1 && pos.z() >= z0 && pos.z() <= z1) {
                    out.add(new Structure(type, pos));
                }
            }
        }
        return out;
    }

    /// All structure types supported by this version in the given area.
    public List<Structure> structures(int x0, int z0, int x1, int z1) {
        List<Structure> out = new ArrayList<>();
        for (StructureType type : StructureType.values()) {
            if (type == StructureType.STRONGHOLD || type == StructureType.MINESHAFT) {
                continue;
            }
            out.addAll(structures(type, x0, z0, x1, z1));
        }
        return out;
    }

    /// Approximate world spawn. The exact spawn search requires biome lookups
    /// over a candidate area and is not implemented yet.
    public BlockPos spawn() {
        return new BlockPos(0, 63, 0);
    }

    /// Stronghold positions on the vanilla concentric rings.
    public List<BlockPos> strongholds(int count) {
        return Finder.strongholds(version, seed, count);
    }

    public boolean isSlimeChunk(int chunkX, int chunkZ) {
        return Finder.isSlimeChunk(seed, chunkX, chunkZ);
    }
}
