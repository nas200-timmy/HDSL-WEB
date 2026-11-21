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
 * Ported from cubiomes rng.h (MIT license, Copyright (C) Cubitect).
 */
package org.jackhuang.hmcl.web.minecraft.seedmap.core;

/// Random-number helpers used by world generation.
///
/// Two algorithms are needed: the 48-bit linear congruential generator used by
/// Java Edition before 1.18, and Xoroshiro128++ used from 1.18 onwards.
final class Rng {
    private Rng() {
    }

    // ------------------------------------------------------------------ Java --

    static long setSeed(long value) {
        return (value ^ 0x5deece66dL) & ((1L << 48) - 1);
    }

    static int next(long[] seed, int bits) {
        seed[0] = (seed[0] * 0x5deece66dL + 0xbL) & ((1L << 48) - 1);
        return (int) (seed[0] >> (48 - bits));
    }

    static int nextInt(long[] seed, int n) {
        int bits;
        int val;
        int m = n - 1;
        if ((m & n) == 0) {
            long x = n * (long) next(seed, 31);
            return (int) (x >> 31);
        }
        do {
            bits = next(seed, 31);
            val = bits % n;
        } while (bits - val + m < 0);
        return val;
    }

    static long nextLong(long[] seed) {
        return ((long) next(seed, 32) << 32) + next(seed, 32);
    }

    static float nextFloat(long[] seed) {
        return next(seed, 24) / (float) (1 << 24);
    }

    static double nextDouble(long[] seed) {
        long x = (long) next(seed, 26);
        x <<= 27;
        x += next(seed, 27);
        return x / (double) (1L << 53);
    }

    static void skipNextN(long[] seed, long n) {
        long m = 1;
        long a = 0;
        long im = 0x5deece66dL;
        long ia = 0xbL;
        for (long k = n; k != 0; k >>= 1) {
            if ((k & 1) != 0) {
                m *= im;
                a = im * a + ia;
            }
            ia = (im + 1) * ia;
            im *= im;
        }
        seed[0] = (seed[0] * m + a) & ((1L << 48) - 1);
    }

    // ------------------------------------------------------------- Xoroshiro --

    static void xSetSeed(long[] xr, long value) {
        final long xl = 0x9e3779b97f4a7c15L;
        final long xh = 0x6a09e667f3bcc909L;
        final long a = 0xbf58476d1ce4e5b9L;
        final long b = 0x94d049bb133111ebL;
        long l = value ^ xh;
        long h = l + xl;
        l = (l ^ (l >>> 30)) * a;
        h = (h ^ (h >>> 30)) * a;
        l = (l ^ (l >>> 27)) * b;
        h = (h ^ (h >>> 27)) * b;
        l = l ^ (l >>> 31);
        h = h ^ (h >>> 31);
        xr[0] = l;
        xr[1] = h;
    }

    static long xNextLong(long[] xr) {
        long l = xr[0];
        long h = xr[1];
        long n = Long.rotateLeft(l + h, 17) + l;
        h ^= l;
        xr[0] = Long.rotateLeft(l, 49) ^ h ^ (h << 21);
        xr[1] = Long.rotateLeft(h, 28);
        return n;
    }

    static int xNextInt(long[] xr, int n) {
        long r = (xNextLong(xr) & 0xFFFFFFFFL) * n;
        if ((r & 0xFFFFFFFFL) < n) {
            long threshold = (~n + 1) % n;
            while ((r & 0xFFFFFFFFL) < threshold) {
                r = (xNextLong(xr) & 0xFFFFFFFFL) * n;
            }
        }
        return (int) (r >> 32);
    }

    static double xNextDouble(long[] xr) {
        return (xNextLong(xr) >>> 11) * 1.1102230246251565E-16;
    }

    static float xNextFloat(long[] xr) {
        return (xNextLong(xr) >>> 40) * 5.9604645E-8f;
    }

    static void xSkipN(long[] xr, int count) {
        while (count-- > 0) {
            xNextLong(xr);
        }
    }

    static long xNextLongJ(long[] xr) {
        int a = (int) (xNextLong(xr) >> 32);
        int b = (int) (xNextLong(xr) >> 32);
        return ((long) a << 32) + b;
    }

    static int xNextIntJ(long[] xr, int n) {
        int bits;
        int val;
        int m = n - 1;
        if ((m & n) == 0) {
            long x = n * (xNextLong(xr) >> 33);
            return (int) (x >> 31);
        }
        do {
            bits = (int) (xNextLong(xr) >> 33);
            val = bits % n;
        } while (bits - val + m < 0);
        return val;
    }

    // ----------------------------------------------------------- MC seed glue --

    static long mcStepSeed(long s, long salt) {
        return s * (s * 6364136223846793005L + 1442695040888963407L) + salt;
    }

    static int mcFirstInt(long s, int mod) {
        int ret = (int) ((s >> 24) % mod);
        if (ret < 0) {
            ret += mod;
        }
        return ret;
    }

    static boolean mcFirstIsZero(long s, int mod) {
        return (int) ((s >> 24) % mod) == 0;
    }

    static long getChunkSeed(long ss, int x, int z) {
        long cs = ss + x;
        cs = mcStepSeed(cs, z);
        cs = mcStepSeed(cs, x);
        cs = mcStepSeed(cs, z);
        return cs;
    }

    static long getLayerSalt(long salt) {
        long ls = mcStepSeed(salt, salt);
        ls = mcStepSeed(ls, salt);
        ls = mcStepSeed(ls, salt);
        return ls;
    }

    static long getStartSalt(long ws, long ls) {
        long st = ws;
        st = mcStepSeed(st, ls);
        st = mcStepSeed(st, ls);
        st = mcStepSeed(st, ls);
        return st;
    }

    static long getStartSeed(long ws, long ls) {
        long ss = ws;
        ss = getStartSalt(ss, ls);
        ss = mcStepSeed(ss, 0);
        return ss;
    }

    // -------------------------------------------------------------- Arithmetic --

    static double lerp(double part, double from, double to) {
        return from + part * (to - from);
    }

    static double lerp2(double dx, double dy, double v00, double v10, double v01, double v11) {
        return lerp(dy, lerp(dx, v00, v10), lerp(dx, v01, v11));
    }

    static double lerp3(double dx, double dy, double dz,
                        double v000, double v100, double v010, double v110,
                        double v001, double v101, double v011, double v111) {
        v000 = lerp2(dx, dy, v000, v100, v010, v110);
        v001 = lerp2(dx, dy, v001, v101, v011, v111);
        return lerp(dz, v000, v001);
    }

    static double clampedLerp(double part, double from, double to) {
        if (part <= 0) {
            return from;
        }
        if (part >= 1) {
            return to;
        }
        return lerp(part, from, to);
    }

    static int floorDiv(int a, int b) {
        int q = a / b;
        int r = a % b;
        return q - ((a ^ b) < 0 && r != 0 ? 1 : 0);
    }
}
