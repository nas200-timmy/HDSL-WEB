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
/// @param mode        whether to paint terrain or flat biome colours
/// @param engine      which backend computes the picture
@NotNullByDefault
public record PreviewSpec(String version, String seed, int radiusBlocks, int biomeY, Mode mode, Engine engine) {

    /// How much truth the picture carries.
    ///
    /// [DETAIL] paints terrain: surface material, relief, coastline. [BIOME]
    /// paints the same chunks as flat biome colours, which is the view a seed
    /// map is usually read in. Both generate the same world — see
    /// [#sampleStep()] for why the "biome map" cannot be cheaper.
    public enum Mode {
        DETAIL,
        BIOME
    }

    /// Which implementation produces the preview.
    ///
    /// [JAVA] uses the cubiomes-based core and never starts a server. [SERVER]
    /// boots the official jar and reads what it writes. JAVA currently only
    /// supports [Mode#BIOME]; a DETAIL request with JAVA is rejected by the
    /// servlet.
    public enum Engine {
        JAVA,
        SERVER
    }

    /// The detail map's ceiling.
    public static final int MAX_RADIUS = 1024;

    public PreviewSpec(String version, String seed, int radiusBlocks, int biomeY, Mode mode) {
        this(version, seed, radiusBlocks, biomeY, mode, mode == Mode.BIOME ? Engine.JAVA : Engine.SERVER);
    }

    public PreviewSpec(String version, String seed, int radiusBlocks, int biomeY) {
        this(version, seed, radiusBlocks, biomeY, Mode.DETAIL, Engine.SERVER);
    }

    /// The smallest radius that still shows something useful.
    public static final int MIN_RADIUS = 64;

    /// The slice a flat map is drawn from when the caller does not say.
    public static final int DEFAULT_BIOME_Y = 63;
    /// The longest seed the panel accepts, to keep the properties file bounded.
    public static final int MAX_SEED_LENGTH = 128;

    /// Samples along one side, for the biome overview.
    ///
    /// The count is fixed, so the cost of an overview is fixed too: 64×64
    /// chunks, roughly forty seconds whatever the radius. The radius only
    /// decides how much ground each sample stands for.
    public static final int BIOME_SAMPLES = 32;

    /// Chunks along one side of the rendered square.
    public int chunksPerSide() {
        return Math.max(1, radiusBlocks * 2 / 16);
    }

    /// Pixels along one side: one per block on the detail map, one per sample
    /// on the overview (the browser scales it up smoothly).
    public int pixelsPerSide() {
        return mode == Mode.BIOME ? BIOME_SAMPLES : radiusBlocks * 2;
    }

    /// Chunks skipped between two samples.
    ///
    /// Always 1: measurements on a real server showed that generating scattered
    /// chunks costs about four times a contiguous chunk (each isolated chunk
    /// drags its neighbours through the early generation stages and then throws
    /// that work away). Sparse sampling therefore loses to dense generation on
    /// every axis, so the "biome overview" is a *rendering* of the same world,
    /// not a cheaper way to obtain it.
    public int sampleStep() {
        return 1;
    }

    /// How many chunks the overview has to generate.
    public int sampleCount() {
        if (mode != Mode.BIOME) {
            return chunkCount();
        }
        int step = sampleStep();
        int perSide = (chunksPerSide() + step - 1) / step;
        return perSide * perSide;
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
        return version + "|" + seed + "|" + radiusBlocks + "|" + biomeY + "|" + mode + "|" + engine;
    }
}
