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

/// Two octave stacks averaged together, used by 1.18+ climate noise.
final class DoublePerlinNoise {
    private static final double[] AMP_INI = {
            0, 5. / 6, 10. / 9, 15. / 12, 20. / 15, 25. / 18, 30. / 21, 35. / 24, 40. / 27, 45. / 30,
    };

    private double amplitude;
    private final OctaveNoise octA = new OctaveNoise();
    private final OctaveNoise octB = new OctaveNoise();

    void init(long[] seed, PerlinNoise[] octavesA, PerlinNoise[] octavesB, int omin, int len) {
        amplitude = (10.0 / 6.0) * len / (len + 1);
        octA.init(seed, octavesA, omin, len);
        octB.init(seed, octavesB, omin, len);
    }

    int xInit(long[] xr, PerlinNoise[] octaves, double[] amplitudes, int omin, int len, int nmax) {
        int n = 0;
        int na = -1;
        int nb = -1;
        if (nmax > 0) {
            na = (nmax + 1) >> 1;
            nb = nmax - na;
        }
        n += octA.xInit(xr, octaves, amplitudes, omin, len, na);
        n += octB.xInit(xr, slice(octaves, n), amplitudes, omin, len, nb);

        int trimmed = len;
        for (int i = len - 1; i >= 0 && amplitudes[i] == 0.0; i--) {
            trimmed--;
        }
        for (int i = 0; i < trimmed && amplitudes[i] == 0.0; i++) {
            trimmed--;
        }
        amplitude = AMP_INI[trimmed];
        return n;
    }

    double sample(double x, double y, double z) {
        final double f = 337.0 / 331.0;
        double v = octA.sample(x, y, z);
        v += octB.sample(x * f, y * f, z * f);
        return v * amplitude;
    }

    private static PerlinNoise[] slice(PerlinNoise[] array, int from) {
        PerlinNoise[] out = new PerlinNoise[array.length - from];
        System.arraycopy(array, from, out, 0, out.length);
        return out;
    }
}
