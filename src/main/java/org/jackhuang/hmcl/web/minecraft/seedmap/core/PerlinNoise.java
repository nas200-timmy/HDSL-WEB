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
 * Ported from cubiomes noise.c (MIT license, Copyright (C) Cubitect).
 */
package org.jackhuang.hmcl.web.minecraft.seedmap.core;

/// Classic 3D Perlin noise as used by Minecraft world generation.
final class PerlinNoise {
    private final int[] d = new int[257];
    private int h2;
    private double a;
    private double b;
    private double c;
    private double amplitude = 1.0;
    private double lacunarity = 1.0;
    private double d2;
    private double t2;

    void init(long[] seed) {
        a = Rng.nextDouble(seed) * 256.0;
        b = Rng.nextDouble(seed) * 256.0;
        c = Rng.nextDouble(seed) * 256.0;
        amplitude = 1.0;
        lacunarity = 1.0;
        for (int i = 0; i < 256; i++) {
            d[i] = i;
        }
        for (int i = 0; i < 256; i++) {
            int j = Rng.nextInt(seed, 256 - i) + i;
            int n = d[i];
            d[i] = d[j];
            d[j] = n;
        }
        d[256] = d[0];
        double i2 = Math.floor(b);
        d2 = b - i2;
        h2 = (int) i2;
        t2 = d2 * d2 * d2 * (d2 * (d2 * 6.0 - 15.0) + 10.0);
    }

    void xInit(long[] xr) {
        a = Rng.xNextDouble(xr) * 256.0;
        b = Rng.xNextDouble(xr) * 256.0;
        c = Rng.xNextDouble(xr) * 256.0;
        amplitude = 1.0;
        lacunarity = 1.0;
        for (int i = 0; i < 256; i++) {
            d[i] = i;
        }
        for (int i = 0; i < 256; i++) {
            int j = Rng.xNextInt(xr, 256 - i) + i;
            int n = d[i];
            d[i] = d[j];
            d[j] = n;
        }
        d[256] = d[0];
        double i2 = Math.floor(b);
        d2 = b - i2;
        h2 = (int) i2;
        t2 = d2 * d2 * d2 * (d2 * (d2 * 6.0 - 15.0) + 10.0);
    }

    double amplitude() {
        return amplitude;
    }

    void setAmplitude(double amplitude) {
        this.amplitude = amplitude;
    }

    double lacunarity() {
        return lacunarity;
    }

    double b() {
        return b;
    }

    void setLacunarity(double lacunarity) {
        this.lacunarity = lacunarity;
    }

    double sample(double d1, double d2, double d3, double yamp, double ymin) {
        int h1;
        int h2;
        int h3;
        double t1;
        double t2;
        double t3;

        if (d2 == 0.0) {
            d2 = this.d2;
            h2 = this.h2;
            t2 = this.t2;
        } else {
            d2 += b;
            double i2 = Math.floor(d2);
            d2 -= i2;
            h2 = (int) i2;
            t2 = d2 * d2 * d2 * (d2 * (d2 * 6.0 - 15.0) + 10.0);
        }

        d1 += a;
        d3 += c;

        double i1 = Math.floor(d1);
        double i3 = Math.floor(d3);
        d1 -= i1;
        d3 -= i3;

        h1 = (int) i1;
        h3 = (int) i3;

        t1 = d1 * d1 * d1 * (d1 * (d1 * 6.0 - 15.0) + 10.0);
        t3 = d3 * d3 * d3 * (d3 * (d3 * 6.0 - 15.0) + 10.0);

        if (yamp != 0.0) {
            double yclamp = Math.min(ymin, d2);
            d2 -= Math.floor(yclamp / yamp) * yamp;
        }

        h1 &= 0xff;
        h2 &= 0xff;
        h3 &= 0xff;

        int a1 = (d[h1] + h2) & 0xff;
        int b1 = (d[h1 + 1] + h2) & 0xff;

        int a2 = (d[a1] + h3) & 0xff;
        int b2 = (d[b1] + h3) & 0xff;
        int a3 = (d[a1 + 1] + h3) & 0xff;
        int b3 = (d[b1 + 1] + h3) & 0xff;

        double l1 = indexedLerp(d[a2], d1, d2, d3);
        double l2 = indexedLerp(d[b2], d1 - 1, d2, d3);
        double l3 = indexedLerp(d[a3], d1, d2 - 1, d3);
        double l4 = indexedLerp(d[b3], d1 - 1, d2 - 1, d3);
        double l5 = indexedLerp(d[a2 + 1], d1, d2, d3 - 1);
        double l6 = indexedLerp(d[b2 + 1], d1 - 1, d2, d3 - 1);
        double l7 = indexedLerp(d[a3 + 1], d1, d2 - 1, d3 - 1);
        double l8 = indexedLerp(d[b3 + 1], d1 - 1, d2 - 1, d3 - 1);

        l1 = Rng.lerp(t1, l1, l2);
        l3 = Rng.lerp(t1, l3, l4);
        l5 = Rng.lerp(t1, l5, l6);
        l7 = Rng.lerp(t1, l7, l8);

        l1 = Rng.lerp(t2, l1, l3);
        l5 = Rng.lerp(t2, l5, l7);

        return Rng.lerp(t3, l1, l5);
    }

    double sample(double x, double y, double z) {
        return sample(x, y, z, 0.0, 0.0);
    }

    private static double indexedLerp(int idx, double a, double b, double c) {
        return switch (idx & 0xf) {
            case 0 -> a + b;
            case 1 -> -a + b;
            case 2 -> a - b;
            case 3 -> -a - b;
            case 4 -> a + c;
            case 5 -> -a + c;
            case 6 -> a - c;
            case 7 -> -a - c;
            case 8 -> b + c;
            case 9 -> -b + c;
            case 10 -> b - c;
            case 11 -> -b - c;
            case 12 -> a + b;
            case 13 -> -b + c;
            case 14 -> -a + b;
            case 15 -> -b - c;
            default -> throw new IllegalStateException();
        };
    }
}
