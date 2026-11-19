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
 * Ported from cubiomes biomenoise.c (MIT license, Copyright (C) Cubitect).
 */
package org.jackhuang.hmcl.web.minecraft.seedmap.core;

/// Nearest-neighbour search over the biome climate parameter space.
final class BiomeTree {
    private final int[] steps;
    private final int[][] param;
    private final long[] nodes;
    private final int order;

    BiomeTree(int[] steps, int[][] param, long[] nodes, int order) {
        this.steps = steps;
        this.param = param;
        this.nodes = nodes;
        this.order = order;
    }

    int climateToBiome(long[] np, long[] dat) {
        int idx;
        if (dat != null) {
            int alt = (int) dat[0];
            long ds = getNpDist(np, alt);
            idx = getResultingNode(np, 0, alt, ds, 0);
            dat[0] = idx;
        } else {
            idx = getResultingNode(np, 0, 0, -1, 0);
        }
        return (int) ((nodes[idx] >> 48) & 0xFF);
    }

    private long getNpDist(long[] np, int idx) {
        long ds = 0;
        long node = nodes[idx];
        for (int i = 0; i < 6; i++) {
            idx = (int) ((node >> 8 * i) & 0xFF);
            long a = np[i] - param[idx][1];
            long b = param[idx][0] - np[i];
            long d = a > 0 ? a : b > 0 ? b : 0;
            d = d * d;
            ds += d;
        }
        return ds;
    }

    private int getResultingNode(long[] np, int idx, int alt, long ds, int depth) {
        if (steps[depth] == 0) {
            return idx;
        }
        int step;
        do {
            step = steps[depth];
            depth++;
        } while (idx + step >= nodes.length);

        long node = nodes[idx];
        int inner = (int) (node >> 48);
        int leaf = alt;

        for (int i = 0; i < order; i++) {
            long dsInner = getNpDist(np, inner);
            if (dsInner < ds) {
                int leaf2 = getResultingNode(np, inner, leaf, ds, depth);
                long dsLeaf2 = inner == leaf2 ? dsInner : getNpDist(np, leaf2);
                if (dsLeaf2 < ds) {
                    ds = dsLeaf2;
                    leaf = leaf2;
                }
            }
            inner += step;
            if (inner >= nodes.length) {
                break;
            }
        }
        return leaf;
    }
}
