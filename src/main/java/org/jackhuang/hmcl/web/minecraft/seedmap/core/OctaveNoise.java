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

/// A stack of Perlin octaves sampled at successively doubled frequencies.
final class OctaveNoise {
    private static final long[][] MD5_OCTAVE_N = {
            {0xb198de63a8012672L, 0x7b84cad43ef7b5a8L}, // md5 "octave_-12"
            {0x0fd787bfbc403ec3L, 0x74a4a31ca21b48b8L}, // md5 "octave_-11"
            {0x36d326eed40efeb2L, 0x5be9ce18223c636aL}, // md5 "octave_-10"
            {0x082fe255f8be6631L, 0x4e96119e22dedc81L}, // md5 "octave_-9"
            {0x0ef68ec68504005eL, 0x48b6bf93a2789640L}, // md5 "octave_-8"
            {0xf11268128982754fL, 0x257a1d670430b0aaL}, // md5 "octave_-7"
            {0xe51c98ce7d1de664L, 0x5f9478a733040c45L}, // md5 "octave_-6"
            {0x6d7b49e7e429850aL, 0x2e3063c622a24777L}, // md5 "octave_-5"
            {0xbd90d5377ba1b762L, 0xc07317d419a7548dL}, // md5 "octave_-4"
            {0x53d39c6752dac858L, 0xbcd1c5a80ab65b3eL}, // md5 "octave_-3"
            {0xb4a24d7a84e7677bL, 0x023ff9668e89b5c4L}, // md5 "octave_-2"
            {0xdffa22b534c5f608L, 0xb9b67517d3665ca9L}, // md5 "octave_-1"
            {0xd50708086cef4d7cL, 0x6e1651ecc7f43309L}, // md5 "octave_0"
    };

    private static final double[] LACUNA_INI = {
            1, .5, .25, 1. / 8, 1. / 16, 1. / 32, 1. / 64, 1. / 128, 1. / 256, 1. / 512, 1. / 1024,
            1. / 2048, 1. / 4096,
    };

    private static final double[] PERSIST_INI = {
            0, 1, 2. / 3, 4. / 7, 8. / 15, 16. / 31, 32. / 63, 64. / 127, 128. / 255, 256. / 511,
    };

    private int octcnt;
    private PerlinNoise[] octaves;

    void init(long[] seed, PerlinNoise[] octaves, int omin, int len) {
        this.octcnt = len;
        this.octaves = octaves;
        int end = omin + len - 1;
        double persist = 1.0 / ((1L << len) - 1.0);
        double lacuna = Math.pow(2.0, end);

        if (len < 1 || end > 0) {
            throw new IllegalArgumentException("unsupported octave range");
        }

        int i = 0;
        if (end == 0) {
            octaves[0].init(seed);
            octaves[0].setAmplitude(persist);
            octaves[0].setLacunarity(lacuna);
            persist *= 2.0;
            lacuna *= 0.5;
            i = 1;
        } else {
            Rng.skipNextN(seed, (long) -end * 262);
        }

        for (; i < len; i++) {
            octaves[i].init(seed);
            octaves[i].setAmplitude(persist);
            octaves[i].setLacunarity(lacuna);
            persist *= 2.0;
            lacuna *= 0.5;
        }
    }

    int xInit(long[] xr, PerlinNoise[] octaves, double[] amplitudes, int omin, int len, int nmax) {
        this.octaves = octaves;
        double lacuna = LACUNA_INI[-omin];
        double persist = PERSIST_INI[len];
        long xlo = Rng.xNextLong(xr);
        long xhi = Rng.xNextLong(xr);
        int i = 0;
        int n = 0;

        for (; i < len && n != nmax; i++, lacuna *= 2.0, persist *= 0.5) {
            if (amplitudes[i] == 0) {
                continue;
            }
            long[] pxr = {xlo ^ MD5_OCTAVE_N[12 + omin + i][0], xhi ^ MD5_OCTAVE_N[12 + omin + i][1]};
            octaves[n].xInit(pxr);
            octaves[n].setAmplitude(amplitudes[i] * persist);
            octaves[n].setLacunarity(lacuna);
            n++;
        }
        this.octcnt = n;
        return n;
    }

    int count() {
        return octcnt;
    }

    PerlinNoise octave(int i) {
        return octaves[i];
    }

    double sample(double x, double y, double z) {
        double v = 0;
        for (int i = 0; i < octcnt; i++) {
            PerlinNoise p = octaves[i];
            double lf = p.lacunarity();
            v += p.amplitude() * p.sample(x * lf, y * lf, z * lf, 0, 0);
        }
        return v;
    }

    double sampleAmp(double x, double y, double z, double yamp, double ymin, int ydefault) {
        double v = 0;
        for (int i = 0; i < octcnt; i++) {
            PerlinNoise p = octaves[i];
            double lf = p.lacunarity();
            double ay = ydefault != 0 ? -p.b() : y * lf;
            v += p.amplitude() * p.sample(x * lf, ay, z * lf, yamp * lf, ymin * lf);
        }
        return v;
    }

    double sample2D(double x, double z) {
        double v = 0;
        for (int i = 0; i < octcnt; i++) {
            PerlinNoise p = octaves[i];
            double lf = p.lacunarity();
            v += p.amplitude() * p.sample(x * lf, 0.0, z * lf, 0, 0);
        }
        return v;
    }
}
