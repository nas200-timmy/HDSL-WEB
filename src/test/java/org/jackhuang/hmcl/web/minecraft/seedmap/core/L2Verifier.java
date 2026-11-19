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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/// Compares the Java core with the game's own MultiNoiseBiomeSource.
///
/// The probe is a tiny main class inside the MCP-Reborn checkout. It loads the
/// vanilla datapack registries once per seed and prints biome resource keys.
/// This verifier feeds the same coordinates to both sides and reports any
/// divergence. Deviations that cannot be fixed must be recorded in
/// docs/seedmap-deviations.md.
public final class L2Verifier {
    private final Path mcpDir;
    private final String classpath;
    private int compared;
    private int mismatches;

    public L2Verifier(Path mcpDir, String classpath) {
        this.mcpDir = mcpDir;
        this.classpath = classpath;
    }

    public static void main(String[] args) throws Exception {
        Path dir = args.length > 0 ? Path.of(args[0]) : Path.of("/data/MCP-Reborn-1.21");
        Path cpFile = args.length > 1 ? Path.of(args[1]) : Path.of("/tmp/l2-classpath.txt");
        String classpath = Files.readString(cpFile).trim();
        L2Verifier verifier = new L2Verifier(dir, classpath);
        verifier.run();
        if (verifier.mismatches > 0) {
            System.exit(1);
        }
    }

    public void run() throws IOException {
        Version version = Version.V1_21_3;
        Random random = new Random(20261119L);
        for (int i = 0; i < 4; i++) {
            long seed = random.nextLong();
            verifySeed(version, seed, random);
        }
        System.out.println("L2 compared=" + compared + " mismatches=" + mismatches);
    }

    private void verifySeed(Version version, long seed, Random random) throws IOException {
        SeedMap map = SeedMap.of(version, seed);
        Process process = new ProcessBuilder("java", "-cp", classpath, "mcp.L2BiomeProbe", Long.toString(seed))
                .directory(mcpDir.toFile()).redirectErrorStream(false).start();
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
             BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            List<int[]> points = samplePoints(random, 32);
            for (int[] point : points) {
                writer.write(point[0] + " " + point[1] + " " + point[2] + "\n");
            }
            writer.flush();
            for (int[] point : points) {
                String expected = readStdoutLine(reader);
                if (expected == null) {
                    throw new IOException("L2 probe ended early");
                }
                String actual = "minecraft:" + map.biomeAt(point[0] * 4, point[1] * 4, point[2] * 4).key();
                compared++;
                if (!expected.equals(actual)) {
                    mismatches++;
                    System.err.println("MISMATCH seed=" + seed + " at " + point[0] + "," + point[1] + "," + point[2]
                            + " expected=" + expected + " actual=" + actual);
                }
            }
        } finally {
            process.destroy();
        }
    }

    private static String readStdoutLine(BufferedReader reader) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            int idx = line.indexOf("minecraft:");
            if (idx >= 0) {
                return line.substring(idx).trim();
            }
        }
        return null;
    }

    private List<int[]> samplePoints(Random random, int count) {
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < count / 2; i++) {
            out.add(new int[]{random.nextInt(2000) - 1000, random.nextInt(64), random.nextInt(2000) - 1000});
        }
        for (int i = out.size(); i < count; i++) {
            int cx = random.nextInt(2000) - 1000;
            int cy = random.nextInt(64);
            int cz = random.nextInt(2000) - 1000;
            for (int dx = -4; dx <= 4 && out.size() < count; dx++) {
                out.add(new int[]{cx + dx, cy, cz});
            }
        }
        return out;
    }
}
