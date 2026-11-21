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

/// Overworld biome generator for 1.18 and later.
final class BiomeNoise {
    static final int NP_TEMPERATURE = 0;
    static final int NP_HUMIDITY = 1;
    static final int NP_CONTINENTALNESS = 2;
    static final int NP_EROSION = 3;
    static final int NP_SHIFT = 4;
    static final int NP_DEPTH = NP_SHIFT;
    static final int NP_WEIRDNESS = 5;
    static final int NP_MAX = 6;

    static final int SAMPLE_NO_SHIFT = 0x1;
    static final int SAMPLE_NO_DEPTH = 0x2;
    static final int SAMPLE_NO_BIOME = 0x4;

    private final DoublePerlinNoise[] climate = new DoublePerlinNoise[NP_MAX];
    private final PerlinNoise[] oct = new PerlinNoise[2 * 23];
    private Spline sp;
    private final Spline.Stack ss = new Spline.Stack();
    private int nptype = -1;
    private Version version;

    BiomeNoise() {
        for (int i = 0; i < NP_MAX; i++) {
            climate[i] = new DoublePerlinNoise();
        }
        for (int i = 0; i < oct.length; i++) {
            oct[i] = new PerlinNoise();
        }
    }

    void init(Version version) {
        this.version = version;
        ss.len = 0;
        ss.flen = 0;
        Spline root = ss.next(Spline.SP_CONTINENTALNESS);

        Spline sp1 = createLandSpline(ss, -0.15f, 0.00f, 0.0f, 0.1f, 0.00f, -0.03f, false);
        Spline sp2 = createLandSpline(ss, -0.10f, 0.03f, 0.1f, 0.1f, 0.01f, -0.03f, false);
        Spline sp3 = createLandSpline(ss, -0.10f, 0.03f, 0.1f, 0.7f, 0.01f, -0.03f, true);
        Spline sp4 = createLandSpline(ss, -0.05f, 0.03f, 0.1f, 1.0f, 0.01f, 0.01f, true);

        Spline.add(root, -1.10f, ss.fix(0.044f), 0.0f);
        Spline.add(root, -1.02f, ss.fix(-0.2222f), 0.0f);
        Spline.add(root, -0.51f, ss.fix(-0.2222f), 0.0f);
        Spline.add(root, -0.44f, ss.fix(-0.12f), 0.0f);
        Spline.add(root, -0.18f, ss.fix(-0.12f), 0.0f);
        Spline.add(root, -0.16f, sp1, 0.0f);
        Spline.add(root, -0.15f, sp1, 0.0f);
        Spline.add(root, -0.10f, sp2, 0.0f);
        Spline.add(root, 0.25f, sp3, 0.0f);
        Spline.add(root, 1.00f, sp4, 0.0f);
        this.sp = root;
    }

    void setSeed(long seed, boolean large) {
        long[] pxr = new long[2];
        Rng.xSetSeed(pxr, seed);
        long xlo = Rng.xNextLong(pxr);
        long xhi = Rng.xNextLong(pxr);

        int n = 0;
        for (int i = 0; i < NP_MAX; i++) {
            n += initClimateSeed(climate[i], n, xlo, xhi, large, i, -1);
        }
        nptype = -1;
    }

    private int initClimateSeed(DoublePerlinNoise dpn, int offset, long xlo, long xhi,
                                boolean large, int type, int nmax) {
        long[] pxr = new long[2];
        return switch (type) {
            case NP_SHIFT -> {
                double[] amp = {1, 1, 1, 0};
                pxr[0] = xlo ^ 0x080518cf6af25384L;
                pxr[1] = xhi ^ 0x3f3dfb40a54febd5L;
                yield dpn.xInit(pxr, sliceOct(offset, 6), amp, -3, 4, nmax);
            }
            case NP_TEMPERATURE -> {
                double[] amp = {1.5, 0, 1, 0, 0, 0};
                pxr[0] = xlo ^ (large ? 0x944b0073edf549dbL : 0x5c7e6b29735f0d7fL);
                pxr[1] = xhi ^ (large ? 0x4ff44347e9d22b96L : 0xf7d86f1bbc734988L);
                yield dpn.xInit(pxr, sliceOct(offset, 4), amp, large ? -12 : -10, 6, nmax);
            }
            case NP_HUMIDITY -> {
                double[] amp = {1, 1, 0, 0, 0, 0};
                pxr[0] = xlo ^ (large ? 0x71b8ab943dbd5301L : 0x81bb4d22e8dc168eL);
                pxr[1] = xhi ^ (large ? 0xbb63ddcf39ff7a2bL : 0xf1c8b4bea16303cdL);
                yield dpn.xInit(pxr, sliceOct(offset, 4), amp, large ? -10 : -8, 6, nmax);
            }
            case NP_CONTINENTALNESS -> {
                double[] amp = {1, 1, 2, 2, 2, 1, 1, 1, 1};
                pxr[0] = xlo ^ (large ? 0x9a3f51a113fce8dcL : 0x83886c9d0ae3a662L);
                pxr[1] = xhi ^ (large ? 0xee2dbd157e5dcdadL : 0xafa638a61b42e8adL);
                yield dpn.xInit(pxr, sliceOct(offset, 18), amp, large ? -11 : -9, 9, nmax);
            }
            case NP_EROSION -> {
                double[] amp = {1, 1, 0, 1, 1};
                pxr[0] = xlo ^ (large ? 0x8c984b1f8702a951L : 0xd02491e6058f6fd8L);
                pxr[1] = xhi ^ (large ? 0xead7b1f92bae535fL : 0x4792512c94c17a80L);
                yield dpn.xInit(pxr, sliceOct(offset, 8), amp, large ? -11 : -9, 5, nmax);
            }
            case NP_WEIRDNESS -> {
                double[] amp = {1, 2, 1, 0, 0, 0};
                pxr[0] = xlo ^ 0xefc8ef4d36102b34L;
                pxr[1] = xhi ^ 0x1beeeb324a0f24eaL;
                yield dpn.xInit(pxr, sliceOct(offset, 6), amp, -7, 6, nmax);
            }
            default -> throw new IllegalArgumentException("unsupported climate parameter " + type);
        };
    }

    private PerlinNoise[] sliceOct(int from, int count) {
        PerlinNoise[] out = new PerlinNoise[count];
        System.arraycopy(oct, from, out, 0, count);
        return out;
    }

    int sample(long[] np, int x, int y, int z, long[] dat, int sampleFlags) {
        if (nptype >= 0) {
            if (np != null) {
                java.util.Arrays.fill(np, 0);
            }
            return (int) (10000.0 * sampleClimatePara(np, x, z));
        }

        float t;
        float h;
        float c;
        float e;
        float d = 0;
        float w;
        double px = x;
        double pz = z;
        if ((sampleFlags & SAMPLE_NO_SHIFT) == 0) {
            px += climate[NP_SHIFT].sample(x, 0, z) * 4.0;
            pz += climate[NP_SHIFT].sample(z, x, 0) * 4.0;
        }

        c = (float) climate[NP_CONTINENTALNESS].sample(px, 0, pz);
        e = (float) climate[NP_EROSION].sample(px, 0, pz);
        w = (float) climate[NP_WEIRDNESS].sample(px, 0, pz);

        if ((sampleFlags & SAMPLE_NO_DEPTH) == 0) {
            float[] npParam = {
                    c, e, -3.0f * (Math.abs(Math.abs(w) - 0.6666667f) - 0.33333334f), w,
            };
            double off = Spline.get(sp, npParam) + 0.015f;
            d = (float) (1.0 - (y * 4) / 128.0 - 83.0 / 160.0 + off);
        }

        t = (float) climate[NP_TEMPERATURE].sample(px, 0, pz);
        h = (float) climate[NP_HUMIDITY].sample(px, 0, pz);

        long[] lNp = np != null ? np : new long[6];
        lNp[0] = (long) (10000.0f * t);
        lNp[1] = (long) (10000.0f * h);
        lNp[2] = (long) (10000.0f * c);
        lNp[3] = (long) (10000.0f * e);
        lNp[4] = (long) (10000.0f * d);
        lNp[5] = (long) (10000.0f * w);

        int id = Biome.NONE.id();
        if ((sampleFlags & SAMPLE_NO_BIOME) == 0) {
            id = climateToBiome(lNp, dat);
        }
        return id;
    }

    private double sampleClimatePara(long[] np, double x, double z) {
        if (nptype == NP_DEPTH) {
            float c = (float) climate[NP_CONTINENTALNESS].sample(x, 0, z);
            float e = (float) climate[NP_EROSION].sample(x, 0, z);
            float w = (float) climate[NP_WEIRDNESS].sample(x, 0, z);
            float[] npParam = {
                    c, e, -3.0f * (Math.abs(Math.abs(w) - 0.6666667f) - 0.33333334f), w,
            };
            double off = Spline.get(sp, npParam) + 0.015f;
            int y = 0;
            float d = (float) (1.0 - (y * 4) / 128.0 - 83.0 / 160.0 + off);
            if (np != null) {
                np[2] = (long) (10000.0f * c);
                np[3] = (long) (10000.0f * e);
                np[4] = (long) (10000.0f * d);
                np[5] = (long) (10000.0f * w);
            }
            return d;
        }
        double p = climate[nptype].sample(x, 0, z);
        if (np != null) {
            np[nptype] = (long) (10000.0f * p);
        }
        return p;
    }

    private int climateToBiome(long[] np, long[] dat) {
        return BiomeTrees.forVersion(version).climateToBiome(np, dat);
    }

    private static float getOffsetValue(float weirdness, float continentalness) {
        float f0 = 1.0f - (1.0f - continentalness) * 0.5f;
        float f1 = 0.5f * (1.0f - continentalness);
        float f2 = (weirdness + 1.17f) * 0.46082947f;
        float off = f2 * f0 - f1;
        if (weirdness < -0.7f) {
            return Math.max(off, -0.2222f);
        } else {
            return Math.max(off, 0);
        }
    }

    private static Spline createSpline38219(Spline.Stack ss, float f, boolean bl) {
        Spline sp = ss.next(Spline.SP_RIDGES);
        float i = getOffsetValue(-1.0f, f);
        float k = getOffsetValue(1.0f, f);
        float l = 1.0f - (1.0f - f) * 0.5f;
        float u = 0.5f * (1.0f - f);
        l = u / (0.46082947f * l) - 1.17f;

        if (-0.65f < l && l < 1.0f) {
            float p;
            float q;
            float r;
            float s;
            u = getOffsetValue(-0.65f, f);
            p = getOffsetValue(-0.75f, f);
            q = (p - i) * 4.0f;
            r = getOffsetValue(l, f);
            s = (k - r) / (1.0f - l);

            Spline.add(sp, -1.0f, ss.fix(i), q);
            Spline.add(sp, -0.75f, ss.fix(p), 0);
            Spline.add(sp, -0.65f, ss.fix(u), 0);
            Spline.add(sp, l - 0.01f, ss.fix(r), 0);
            Spline.add(sp, l, ss.fix(r), s);
            Spline.add(sp, 1.0f, ss.fix(k), s);
        } else {
            u = (k - i) * 0.5f;
            if (bl) {
                Spline.add(sp, -1.0f, ss.fix(Math.max(i, 0.2f)), 0);
                Spline.add(sp, 0.0f, ss.fix((float) Rng.lerp(0.5f, i, k)), u);
            } else {
                Spline.add(sp, -1.0f, ss.fix(i), u);
            }
            Spline.add(sp, 1.0f, ss.fix(k), u);
        }
        return sp;
    }

    private static Spline createFlatOffsetSpline(Spline.Stack ss, float f, float g, float h,
                                                 float i, float j, float k) {
        Spline sp = ss.next(Spline.SP_RIDGES);
        float l = Math.max(0.5f * (g - f), k);
        float m = 5.0f * (h - g);

        Spline.add(sp, -1.0f, ss.fix(f), l);
        Spline.add(sp, -0.4f, ss.fix(g), Math.min(l, m));
        Spline.add(sp, 0.0f, ss.fix(h), m);
        Spline.add(sp, 0.4f, ss.fix(i), 2.0f * (i - h));
        Spline.add(sp, 1.0f, ss.fix(j), 0.7f * (j - i));
        return sp;
    }

    private static Spline createLandSpline(Spline.Stack ss, float f, float g, float h,
                                           float i, float j, float k, boolean bl) {
        Spline sp1 = createSpline38219(ss, (float) Rng.lerp(i, 0.6f, 1.5f), bl);
        Spline sp2 = createSpline38219(ss, (float) Rng.lerp(i, 0.6f, 1.0f), bl);
        Spline sp3 = createSpline38219(ss, i, bl);
        float ih = 0.5f * i;
        Spline sp4 = createFlatOffsetSpline(ss, f - 0.15f, ih, ih, ih, i * 0.6f, 0.5f);
        Spline sp5 = createFlatOffsetSpline(ss, f, j * i, g * i, ih, i * 0.6f, 0.5f);
        Spline sp6 = createFlatOffsetSpline(ss, f, j, j, g, h, 0.5f);
        Spline sp7 = createFlatOffsetSpline(ss, f, j, j, g, h, 0.5f);

        Spline sp8 = ss.next(Spline.SP_RIDGES);
        Spline.add(sp8, -1.0f, ss.fix(f), 0.0f);
        Spline.add(sp8, -0.4f, sp6, 0.0f);
        Spline.add(sp8, 0.0f, ss.fix(h + 0.07f), 0.0f);

        Spline sp9 = createFlatOffsetSpline(ss, -0.02f, k, k, g, h, 0.0f);
        Spline sp = ss.next(Spline.SP_EROSION);
        Spline.add(sp, -0.85f, sp1, 0.0f);
        Spline.add(sp, -0.7f, sp2, 0.0f);
        Spline.add(sp, -0.4f, sp3, 0.0f);
        Spline.add(sp, -0.35f, sp4, 0.0f);
        Spline.add(sp, -0.1f, sp5, 0.0f);
        Spline.add(sp, 0.2f, sp6, 0.0f);
        if (bl) {
            Spline.add(sp, 0.4f, sp7, 0.0f);
            Spline.add(sp, 0.45f, sp8, 0.0f);
            Spline.add(sp, 0.55f, sp8, 0.0f);
            Spline.add(sp, 0.58f, sp7, 0.0f);
        }
        Spline.add(sp, 0.7f, sp9, 0.0f);
        return sp;
    }
}
