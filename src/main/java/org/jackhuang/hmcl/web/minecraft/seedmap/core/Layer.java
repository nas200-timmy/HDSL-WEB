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
 * Ported from cubiomes layers.c / generator.c (MIT license, Copyright (C) Cubitect).
 */
package org.jackhuang.hmcl.web.minecraft.seedmap.core;

/// One layer in the pre-1.18 biome layer stack.
final class Layer {
    interface MapFunc {
        int apply(Layer layer, int[] out, int x, int z, int w, int h);
    }

    MapFunc getMap;
    int mc;
    int zoom;
    int edge;
    int scale;
    long layerSalt;
    long startSalt;
    long startSeed;
    PerlinNoise noise;
    Object data;
    Layer p;
    Layer p2;

    Layer setup(MapFunc map, int mc, int zoom, int edge, long saltbase, Layer p, Layer p2) {
        this.getMap = map;
        this.mc = mc;
        this.zoom = zoom;
        this.edge = edge;
        this.scale = 0;
        if (saltbase == 0 || saltbase == LayerStack.LAYER_INIT_SHA) {
            this.layerSalt = saltbase;
        } else {
            this.layerSalt = Rng.getLayerSalt(saltbase);
        }
        this.startSalt = 0;
        this.startSeed = 0;
        this.noise = null;
        this.data = null;
        this.p = p;
        this.p2 = p2;
        return this;
    }

    void setSeed(long worldSeed) {
        if (p2 != null) {
            p2.setSeed(worldSeed);
        }
        if (p != null) {
            p.setSeed(worldSeed);
        }
        if (noise != null) {
            long[] s = {Rng.setSeed(worldSeed)};
            noise.init(s);
        }
        long ls = layerSalt;
        if (ls == 0) {
            startSalt = 0;
            startSeed = 0;
        } else if (ls == LayerStack.LAYER_INIT_SHA) {
            startSalt = LayerStack.getVoronoiSHA(worldSeed);
            startSeed = 0;
        } else {
            long st = worldSeed;
            st = Rng.mcStepSeed(st, ls);
            st = Rng.mcStepSeed(st, ls);
            st = Rng.mcStepSeed(st, ls);
            startSalt = st;
            startSeed = Rng.mcStepSeed(st, 0);
        }
    }

    int genArea(int[] out, int areaX, int areaZ, int areaWidth, int areaHeight) {
        // Layers address their parent's output inside the same buffer, so the
        // caller's array is only big enough for the final result. Allocate a
        // scratch pad with room for the largest parent area plus zoom buffers.
        int max = Math.max(areaWidth, areaHeight);
        int size = (max + 64) * (max + 64) * 4;
        int[] work = new int[size];
        int err = getMap.apply(this, work, areaX, areaZ, areaWidth, areaHeight);
        System.arraycopy(work, 0, out, 0, areaWidth * areaHeight);
        return err;
    }

    static boolean isAny4(int id, int a, int b, int c, int d) {
        return id == a || id == b || id == c || id == d;
    }
}
