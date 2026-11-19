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
/// Modern versions (1.18+) use multi-noise directly. Legacy versions are not
/// yet implemented and throw [UnsupportedOperationException] on use.
final class Generator {
    private final Version version;
    private final BiomeNoise biomeNoise;
    private long seed;
    private long sha;

    Generator(Version version) {
        this.version = version;
        if (version.family() == Version.Family.MODERN) {
            this.biomeNoise = new BiomeNoise();
            this.biomeNoise.init(version);
        } else {
            this.biomeNoise = null;
        }
    }

    void applySeed(long seed) {
        this.seed = seed;
        this.sha = sha(seed);
        if (biomeNoise != null) {
            biomeNoise.setSeed(seed, false);
        }
    }

    long seed() {
        return seed;
    }

    long sha() {
        return sha;
    }

    Biome biomeAt(int blockX, int blockY, int blockZ) {
        if (biomeNoise == null) {
            throw new UnsupportedOperationException("legacy layer stack not implemented yet: " + version.id());
        }
        // Biome noise is sampled at scale 1:4; block coordinates are divided
        // by four. The y coordinate is also divided by four, matching the
        // chunk-section biome storage in 1.18+.
        int id = biomeNoise.sample(null, blockX >> 2, blockY >> 2, blockZ >> 2, null, 0);
        return Biome.byId(id);
    }

    Biome[] mapBiomes(int scale, int x, int z, int width, int height, int y) {
        if (biomeNoise == null) {
            throw new UnsupportedOperationException("legacy layer stack not implemented yet: " + version.id());
        }
        Biome[] out = new Biome[width * height];
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
