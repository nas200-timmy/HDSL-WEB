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

/// Per-seed biome generator.
///
/// Modern versions (1.18+) use multi-noise directly. Legacy versions use the
/// layer stack ported from cubiomes.
final class Generator {
    private final Version version;
    private final BiomeNoise biomeNoise;
    private final LayerStack layerStack;
    private long seed;
    private long sha;

    Generator(Version version) {
        this.version = version;
        if (version.family() == Version.Family.MODERN) {
            this.biomeNoise = new BiomeNoise();
            this.biomeNoise.init(version);
            this.layerStack = null;
        } else {
            this.biomeNoise = null;
            this.layerStack = new LayerStack();
            this.layerStack.setup(version, false);
        }
    }

    void applySeed(long seed) {
        this.seed = seed;
        this.sha = sha(seed);
        if (biomeNoise != null) {
            biomeNoise.setSeed(seed, false);
        }
        if (layerStack != null) {
            layerStack.setSeed(seed);
        }
    }

    long seed() {
        return seed;
    }

    long sha() {
        return sha;
    }

    Biome biomeAt(int blockX, int blockY, int blockZ) {
        if (biomeNoise != null) {
            // Biome noise is sampled at scale 1:4; block coordinates are divided
            // by four. The y coordinate is also divided by four, matching the
            // chunk-section biome storage in 1.18+.
            int id = biomeNoise.sample(null, blockX >> 2, blockY >> 2, blockZ >> 2, null, 0);
            return Biome.byId(id);
        }
        if (layerStack != null) {
            Biome[] area = mapBiomes(4, blockX >> 2, blockZ >> 2, 1, 1, blockY >> 2);
            return area[0];
        }
        throw new IllegalStateException("no generator for " + version.id());
    }

    Biome[] mapBiomes(int scale, int x, int z, int width, int height, int y) {
        Biome[] out = new Biome[width * height];
        if (biomeNoise != null) {
            long[] dat = new long[1];
            for (int j = 0; j < height; j++) {
                for (int i = 0; i < width; i++) {
                    int xi = (x + i) * scale + scale / 2;
                    int zj = (z + j) * scale + scale / 2;
                    int id = biomeNoise.sample(null, xi, y, zj, dat, 0);
                    out[j * width + i] = Biome.byId(id);
                }
            }
            return out;
        }
        if (layerStack != null) {
            Layer entry = switch (scale) {
                case 1 -> layerStack.entry1;
                case 4 -> layerStack.entry4;
                case 16 -> layerStack.entry16;
                case 64 -> layerStack.entry64;
                case 256 -> layerStack.entry256;
                default -> layerStack.entry4;
            };
            int[] ids = new int[width * height];
            entry.genArea(ids, x, z, width, height);
            for (int i = 0; i < ids.length; i++) {
                out[i] = Biome.byId(ids[i]);
            }
            return out;
        }
        throw new IllegalStateException("no generator for " + version.id());
    }

    private static long sha(long seed) {
        // SHA-256 hash of the seed, used for voronoi zoom at block scale.
        // For now only the lower 64 bits are needed by the algorithms.
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(java.nio.ByteBuffer.allocate(8).putLong(seed).array());
            return java.nio.ByteBuffer.wrap(bytes).getLong();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
