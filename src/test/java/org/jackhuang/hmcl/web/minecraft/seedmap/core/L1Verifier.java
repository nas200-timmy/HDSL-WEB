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
package org.jackhuang.hmcl.web.minecraft.seedmap.core;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/// Differential test against the C reference implementation of cubiomes.
///
/// The verifier starts the native query tool once per version/seed pair, feeds
/// it biome-scale coordinates and compares the answers with the Java core.
/// Sampling is deliberately dense around biome boundaries: pick a random
/// centre, then walk outward until the biome changes and probe ±8 cells around
/// that edge.
public final class L1Verifier {
    private final Path nativeTool;
    private int compared;
    private int mismatches;

    public L1Verifier(Path nativeTool) {
        this.nativeTool = nativeTool;
    }

    public static void main(String[] args) throws Exception {
        Path tool = args.length > 0 ? Path.of(args[0]) : Path.of("build/l1-cubiomes-ref");
        L1Verifier verifier = new L1Verifier(tool);
        verifier.run();
        if (verifier.mismatches > 0) {
            System.exit(1);
        }
    }

    public void run() throws IOException {
        Version[] versions = {Version.V1_18_2, Version.V1_20_6, Version.V1_21_3};
        Random random = new Random(20261119L);
        for (Version version : versions) {
            for (int i = 0; i < 3; i++) {
                long seed = random.nextLong();
                verifySeed(version, seed, random);
            }
        }
        System.out.println("L1 compared=" + compared + " mismatches=" + mismatches);
    }

    private void verifySeed(Version version, long seed, Random random) throws IOException {
        SeedMap map = SeedMap.of(version, seed);
        Process process = new ProcessBuilder(nativeTool.toString(), version.id(), Long.toUnsignedString(seed))
                .redirectErrorStream(true).start();
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
             BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            List<int[]> points = samplePoints(random, 50);
            for (int[] point : points) {
                writer.write(point[0] + " " + point[1] + " " + point[2] + "\n");
            }
            writer.flush();
            for (int[] point : points) {
                String line = reader.readLine();
                if (line == null) {
                    throw new IOException("native tool ended early");
                }
                int expected = Integer.parseInt(line.trim());
                int actual = map.biomeAt(point[0] * 4, point[1] * 4, point[2] * 4).id();
                compared++;
                if (expected != actual) {
                    mismatches++;
                    System.err.println("MISMATCH " + version.id() + " seed=" + seed
                            + " at " + point[0] + "," + point[1] + "," + point[2]
                            + " expected=" + expected + " actual=" + actual);
                }
            }
        } finally {
            process.destroy();
        }
    }

    /// Random centres plus dense probes around the first biome boundary found
    /// on each axis.
    private List<int[]> samplePoints(Random random, int count) {
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < count / 2; i++) {
            out.add(new int[]{random.nextInt(2000) - 1000, random.nextInt(64), random.nextInt(2000) - 1000});
        }
        for (int i = out.size(); i < count; i++) {
            int cx = random.nextInt(2000) - 1000;
            int cy = random.nextInt(64);
            int cz = random.nextInt(2000) - 1000;
            for (int dx = -8; dx <= 8 && out.size() < count; dx++) {
                out.add(new int[]{cx + dx, cy, cz});
            }
        }
        return out;
    }
}
