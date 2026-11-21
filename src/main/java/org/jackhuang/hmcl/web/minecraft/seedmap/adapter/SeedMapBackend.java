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
package org.jackhuang.hmcl.web.minecraft.seedmap.adapter;

import org.jackhuang.hmcl.web.minecraft.preview.PreviewRenderer;
import org.jackhuang.hmcl.web.minecraft.preview.PreviewSpec;
import org.jackhuang.hmcl.web.minecraft.seedmap.core.SeedMap;
import org.jackhuang.hmcl.web.minecraft.seedmap.core.Version;
import org.jetbrains.annotations.Nullable;

import java.awt.image.BufferedImage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/// Renders a biome overview directly from the seed-map core, without starting
/// a Minecraft server.
public final class SeedMapBackend {
    private SeedMapBackend() {
    }

    /// What a render produces: the image, the sampled grid and the map itself,
    /// so the caller can write metadata and structures without rebuilding the
    /// generator.
    public record Result(BufferedImage image, String[][] samples, SeedMap map) {
    }

    /// Called once per finished row, so a large render still shows a live
    /// progress bar instead of jumping from 0 to 100.
    public interface Progress {
        void update(long done, long total);
    }

    public static boolean supports(String versionId) {
        try {
            Version.parse(versionId);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static Result render(PreviewSpec spec) {
        return render(spec, null);
    }

    public static Result render(PreviewSpec spec, Progress progress) {
        Version version = Version.parse(spec.version());
        long seed = parseSeed(spec.seed());

        int cells = spec.javaSamples();
        String[][] samples = new String[cells][cells];
        int radius = spec.radiusBlocks();
        int side = radius * 2;
        // Sample the biome grid directly, in blocks. Sampling one point per
        // chunk and stretching it made the whole map 16 blocks per pixel no
        // matter how many samples were asked for, which is why it never lined up
        // with the one-pixel-per-block server render.
        int step = Math.max(1, (int) Math.round(side / (double) cells));
        long total = (long) cells * cells;

        int workers = parallelWorkers(cells);
        if (workers <= 1) {
            // 小网格开池子不划算：一个线程从头刷到尾。
            SeedMap map = SeedMap.of(version, seed);
            for (int j = 0; j < cells; j++) {
                for (int i = 0; i < cells; i++) {
                    int blockX = -radius + i * step + step / 2;
                    int blockZ = -radius + j * step + step / 2;
                    samples[j][i] = map.biomeAt(blockX, spec.biomeY(), blockZ).key();
                }
                if (progress != null) {
                    progress.update((j + 1) * (long) cells, total);
                }
            }
        } else {
            sampleParallel(version, seed, spec.biomeY(), samples, cells, radius, step, total,
                    progress, workers);
        }
        // The map handed back is for structure queries; any instance answers
        // identically, so a fresh one keeps the parallel workers state-free.
        return new Result(PreviewRenderer.renderBiome(samples, spec), samples,
                SeedMap.of(version, seed));
    }

    /// Striped multi-threaded sampling: each worker builds its own SeedMap
    /// (table construction is cheap against the sampling itself) and fills a
    /// disjoint band of rows, which sidesteps every question about sharing the
    /// legacy layer stack or the modern noise tables. The embarrassingly
    /// parallel part is exactly the part that costs minutes: an EXTREME render
    /// divides by the core count almost linearly, which also makes it a decent
    /// unrigorous CPU benchmark.
    private static void sampleParallel(Version version, long seed, int biomeY,
                                       String[][] samples, int cells, int radius, int step,
                                       long total, @Nullable Progress progress, int workers) {
        AtomicLong done = new AtomicLong();
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        // More strips than workers so a fast thread can take over the tail of a
        // slow one; rows are the natural stripe (contiguous, disjoint, simple).
        int strips = workers * 4;
        ExecutorService pool = Executors.newFixedThreadPool(workers, runnable -> {
            Thread thread = new Thread(runnable, "seedmap-render");
            thread.setDaemon(true);
            return thread;
        });
        try {
            for (int s = 0; s < strips; s++) {
                int j0 = (int) ((long) cells * s / strips);
                int j1 = (int) ((long) cells * (s + 1) / strips);
                if (j0 >= j1) {
                    continue;
                }
                pool.submit(() -> {
                    try {
                        SeedMap map = SeedMap.of(version, seed);
                        for (int j = j0; j < j1; j++) {
                            for (int i = 0; i < cells; i++) {
                                int blockX = -radius + i * step + step / 2;
                                int blockZ = -radius + j * step + step / 2;
                                samples[j][i] = map.biomeAt(blockX, biomeY, blockZ).key();
                            }
                            if (progress != null) {
                                progress.update(done.addAndGet(cells), total);
                            }
                        }
                    } catch (RuntimeException e) {
                        failure.compareAndSet(null, e);
                    }
                });
            }
            pool.shutdown();
            while (true) {
                try {
                    if (pool.awaitTermination(1, TimeUnit.MINUTES)) {
                        break;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    pool.shutdownNow();
                    throw new RuntimeException("sampling was interrupted", e);
                }
                if (failure.get() != null) {
                    pool.shutdownNow();
                    throw failure.get();
                }
            }
        } finally {
            pool.shutdownNow();
        }
        if (failure.get() != null) {
            throw failure.get();
        }
    }

    /// Small grids run serially; large ones fan out to every core the box has,
    /// capped so a shared host is not fully monopolised by one preview.
    static int parallelWorkers(int cells) {
        if (cells < 512) {
            return 1;
        }
        return Math.min(16, Math.max(1, Runtime.getRuntime().availableProcessors()));
    }

    public static BufferedImage renderBiome(PreviewSpec spec) {
        return render(spec).image();
    }

    private static long parseSeed(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return text.trim().hashCode();
        }
    }
}