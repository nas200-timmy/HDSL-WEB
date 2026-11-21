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

import org.jackhuang.hmcl.web.minecraft.McJavaRuntimes;
import org.jackhuang.hmcl.web.minecraft.McVersions;
import org.jackhuang.hmcl.web.minecraft.preview.PreviewSpec;
import org.jackhuang.hmcl.web.minecraft.preview.RegionReader;
import org.jackhuang.hmcl.web.config.ServerConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/// End-to-end regression against a real Minecraft server.
///
/// The verifier starts the official server jar for one version, asks it to
/// generate a small square of chunks, reads the biome data it wrote and
/// compares every sample with the Java seed-map core. It is slow by nature,
/// so it covers a handful of seeds rather than the millions L1 checks.
public final class L3Verifier {
    private final Path serverJar;
    private final Path javaBinary;
    private final Path workRoot;
    private int compared;
    private int mismatches;

    public L3Verifier(Path serverJar, Path javaBinary, Path workRoot) {
        this.serverJar = serverJar;
        this.javaBinary = javaBinary;
        this.workRoot = workRoot;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: L3Verifier <server.jar> <java> <workDir> [version]");
            System.exit(2);
        }
        String version = args.length > 3 ? args[3] : "1.21.4";
        L3Verifier verifier = new L3Verifier(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]));
        verifier.run(version);
        if (verifier.mismatches > 0) {
            System.exit(1);
        }
    }

    public void run(String versionId) throws IOException, InterruptedException {
        Version version = Version.parse(versionId);
        Random random = new Random(20261119L);
        for (int i = 0; i < 4; i++) {
            long seed = random.nextLong();
            verifySeed(version, seed);
        }
        System.out.println("L3 compared=" + compared + " mismatches=" + mismatches);
    }

    private void verifySeed(Version version, long seed) throws IOException, InterruptedException {
        Path work = Files.createDirectories(workRoot.resolve("l3-" + seed));
        try {
            Files.writeString(work.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
            Files.writeString(work.resolve("server.properties"), """
                    online-mode=false
                    level-seed=%d
                    spawn-protection=0
                    max-tick-time=-1
                    """.formatted(seed), StandardCharsets.UTF_8);

            Process process = new ProcessBuilder(javaBinary.toString(), "-Xmx1G", "-jar", serverJar.toString(), "--nogui")
                    .directory(work.toFile()).redirectErrorStream(true).start();
            CountDownLatch ready = new CountDownLatch(1);
            Thread pump = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.contains("Done (") || line.contains("Done in")) {
                            ready.countDown();
                        }
                    }
                } catch (IOException ignored) {
                } finally {
                    ready.countDown();
                }
            });
            pump.setDaemon(true);
            pump.start();

            if (!ready.await(3, TimeUnit.MINUTES)) {
                throw new IOException("server did not boot in time");
            }
            if (!process.isAlive()) {
                throw new IOException("server exited early");
            }

            int radiusChunks = 4;
            sendCommand(process, "forceload add " + (-radiusChunks * 16) + " " + (-radiusChunks * 16)
                    + " " + (radiusChunks * 16) + " " + (radiusChunks * 16));
            Path region = work.resolve("world").resolve("region");
            waitForChunks(region, radiusChunks);
            sendCommand(process, "save-all flush");
            sendCommand(process, "stop");
            process.waitFor(90, TimeUnit.SECONDS);
            if (process.isAlive()) {
                process.destroyForcibly();
            }

            compare(region, version, seed, radiusChunks);
        } finally {
            deleteTree(work);
        }
    }

    private void waitForChunks(Path region, int radiusChunks) throws InterruptedException {
        int expected = (radiusChunks * 2 + 1) * (radiusChunks * 2 + 1);
        long deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(6);
        while (System.currentTimeMillis() < deadline) {
            int present = RegionReader.countPresentChunks(region, -radiusChunks, radiusChunks);
            if (present >= expected) {
                return;
            }
            Thread.sleep(1000);
        }
    }

    private void compare(Path region, Version version, long seed, int radiusChunks) throws IOException {
        SeedMap map = SeedMap.of(version, seed);
        List<RegionReader.ChunkSummary> chunks = new ArrayList<>();
        try (Stream<Path> files = Files.list(region)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (!name.startsWith("r.") || !name.endsWith(".mca")) {
                    continue;
                }
                String[] parts = name.substring(2, name.length() - 4).split("\\.");
                int rx = Integer.parseInt(parts[0]);
                int rz = Integer.parseInt(parts[1]);
                chunks.addAll(RegionReader.read(file, rx, rz, 63));
            }
        }
        for (RegionReader.ChunkSummary chunk : chunks) {
            if (chunk.chunkX() < -radiusChunks || chunk.chunkX() > radiusChunks
                    || chunk.chunkZ() < -radiusChunks || chunk.chunkZ() > radiusChunks) {
                continue;
            }
            String[] biomes = chunk.biomes();
            int[] heights = chunk.heights();
            for (int z4 = 0; z4 < 4; z4++) {
                for (int x4 = 0; x4 < 4; x4++) {
                    String expectedName = biomes[z4 * 4 + x4];
                    if (expectedName == null || expectedName.isBlank() || "unknown".equals(expectedName)) {
                        continue;
                    }
                    int blockX = chunk.chunkX() * 16 + x4 * 4;
                    int blockZ = chunk.chunkZ() * 16 + z4 * 4;
                    int height = cellHeight(heights, z4, x4, 63);
                    Biome actual = map.biomeAt(blockX, height, blockZ);
                    String actualName = actual.key();
                    compared++;
                    if (!expectedName.equals(actualName)) {
                        mismatches++;
                        System.err.println("MISMATCH seed=" + seed + " at " + blockX + "," + height + "," + blockZ
                                + " expected=" + expectedName + " actual=" + actualName);
                    }
                }
            }
        }
    }

    private static int cellHeight(int[] heights, int z4, int x4, int fallback) {
        if (heights.length < 256) {
            return fallback;
        }
        int best = 0;
        for (int dz = 0; dz < 4; dz++) {
            for (int dx = 0; dx < 4; dx++) {
                best = Math.max(best, heights[(z4 * 4 + dz) * 16 + x4 * 4 + dx]);
            }
        }
        return best;
    }

    private static void sendCommand(Process process, String command) throws IOException {
        OutputStream out = process.getOutputStream();
        out.write((command + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
