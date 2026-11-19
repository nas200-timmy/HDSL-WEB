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

/// Slime chunks, which need no world generation at all.
///
/// The formula has not changed since slimes were added: the chunk coordinates
/// are mixed into the world seed and the low bits of the result decide it. The
/// panel draws the overlay itself instead of asking the server, because the
/// answer is exact and free.
///
/// The constants are the ones in `SlimeCheck` (Mojang's `WorldgenRandom`
/// lineage); they are written in decimal on purpose so a reviewer can grep for
/// them in a decompiled tree.
@NotNullByDefault
public final class SlimeChunks {

    private SlimeChunks() {
    }

    /// Whether the chunk at `chunkX, chunkZ` spawns slimes.
    ///
    /// @param worldSeed the level seed
    /// @param chunkX    the chunk's x coordinate
    /// @param chunkZ    the chunk's z coordinate
    /// @return `true` for the roughly one chunk in ten that is a slime chunk
    public static boolean isSlimeChunk(long worldSeed, int chunkX, int chunkZ) {
        long hash = worldSeed
                + (long) chunkX * chunkX * 4987142L
                + (long) chunkX * 5947611L
                + (long) chunkZ * chunkZ * 4392871L
                + (long) chunkZ * 389711L
                ^ 987234911L;
        return new java.util.Random(hash).nextInt(10) == 0;
    }
}
