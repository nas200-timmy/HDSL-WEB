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

/// Spline evaluator for 1.18+ terrain offset (depth) calculation.
class Spline {
    static final int SP_CONTINENTALNESS = 0;
    static final int SP_EROSION = 1;
    static final int SP_RIDGES = 2;
    static final int SP_WEIRDNESS = 3;

    int len;
    int typ;
    final float[] loc = new float[12];
    final float[] der = new float[12];
    final Spline[] val = new Spline[12];

    static final class Fix extends Spline {
        float fixed;

        Fix(float value) {
            this.len = 1;
            this.fixed = value;
        }
    }

    static final class Stack {
        final Spline[] stack = new Spline[42];
        final Fix[] fstack = new Fix[151];
        int len;
        int flen;

        Stack() {
            for (int i = 0; i < stack.length; i++) {
                stack[i] = new Spline();
            }
            for (int i = 0; i < fstack.length; i++) {
                fstack[i] = new Fix(0);
            }
        }

        Fix fix(float value) {
            Fix sp = fstack[flen++];
            sp.fixed = value;
            sp.len = 1;
            return sp;
        }

        Spline next(int type) {
            Spline sp = stack[len++];
            sp.typ = type;
            sp.len = 0;
            return sp;
        }
    }

    static void add(Spline rsp, float loc, Spline val, float der) {
        rsp.loc[rsp.len] = loc;
        rsp.val[rsp.len] = val;
        rsp.der[rsp.len] = der;
        rsp.len++;
    }

    static float get(Spline sp, float[] vals) {
        if (sp == null || sp.len <= 0 || sp.len >= 12) {
            throw new IllegalStateException("bad spline parameters");
        }
        if (sp.len == 1) {
            return ((Fix) sp).fixed;
        }
        float f = vals[sp.typ];
        int i;
        for (i = 0; i < sp.len; i++) {
            if (sp.loc[i] >= f) {
                break;
            }
        }
        if (i == 0 || i == sp.len) {
            if (i != 0) {
                i--;
            }
            float v = get(sp.val[i], vals);
            return v + sp.der[i] * (f - sp.loc[i]);
        }
        Spline sp1 = sp.val[i - 1];
        Spline sp2 = sp.val[i];
        float g = sp.loc[i - 1];
        float h = sp.loc[i];
        float k = (f - g) / (h - g);
        float l = sp.der[i - 1];
        float m = sp.der[i];
        float n = get(sp1, vals);
        float o = get(sp2, vals);
        float p = l * (h - g) - (o - n);
        float q = -m * (h - g) + (o - n);
        return (float) (Rng.lerp(k, n, o) + k * (1.0f - k) * Rng.lerp(k, p, q));
    }
}
