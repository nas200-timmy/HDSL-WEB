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

/// One seed preview request.
///
/// `radiusBlocks` is the **half width** of the square: a radius of 512 renders
/// the 1024×1024 block area around the origin, i.e. 64×64 chunks. The map is
/// drawn at four pixels per chunk, which is the native 4×4×4 resolution of the
/// biome data — asking for a finer image would only invent detail.
///
/// `biomeY` is the height the two-dimensional biome layer is sampled at. A
/// chunk stores biomes in three dimensions (1.15 and later), so a flat map has
/// to pick one slice; the sea-level default is what players expect from a
/// "where is this seed" picture.
///
/// @param version     the Minecraft version id (`1.21.4`)
/// @param seed        the world seed exactly as the player typed it
/// @param radiusBlocks the half width in blocks
/// @param biomeY      the height the biome layer is sampled at
@NotNullByDefault
public record PreviewSpec(String version, String seed, int radiusBlocks, int biomeY) {

    /// The smallest radius that still shows something useful.
    public static final int MIN_RADIUS = 64;
    /// The largest radius the panel will generate; 1024 blocks is 128×128 chunks.
    public static final int MAX_RADIUS = 1024;
    /// The slice a flat map is drawn from when the caller does not say.
    public static final int DEFAULT_BIOME_Y = 63;
    /// The longest seed the panel accepts, to keep the properties file bounded.
    public static final int MAX_SEED_LENGTH = 128;

    /// Chunks along one side of the rendered square.
    public int chunksPerSide() {
        return Math.max(1, radiusBlocks * 2 / 16);
    }

    /// Pixels along one side: four per chunk.
    public int pixelsPerSide() {
        return chunksPerSide() * 4;
    }

    /// Chunks the preview has to generate.
    public int chunkCount() {
        return chunksPerSide() * chunksPerSide();
    }

    /// The first chunk covered on each axis (the square is centred on 0,0).
    public int firstChunk() {
        return -chunksPerSide() / 2;
    }

    /// The last chunk covered, inclusive.
    public int lastChunk() {
        return firstChunk() + chunksPerSide() - 1;
    }

    /// A stable key for reuse: two requests with the same key produce the same
    /// picture, so the second one must not start another server.
    public String key() {
        return version + "|" + seed + "|" + radiusBlocks + "|" + biomeY;
    }
}
