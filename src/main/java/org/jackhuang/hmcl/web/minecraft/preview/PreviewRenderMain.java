/*
 * HDSL-web
 * Copyright (C) 2026  HDSL-web contributors
 *
 * This program is free software; you can redistribute it and/or modify
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
package org.jackhuang.hmcl.web.minecraft.preview;

import org.jackhuang.hmcl.web.minecraft.seedmap.adapter.SeedMapBackend;
import org.jackhuang.hmcl.web.minecraft.seedmap.core.Version;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/// The child-JVM entry point behind an EXTREME-quality Java overview.
///
/// A 8192×8192 biome grid is ~512 MB of references before the picture itself,
/// which does not fit the panel's default 1 GB heap — so for EXTREME the panel
/// re-executes its own jar with this main class and a 3 GB heap
/// ([SeedPreviewService#runBiomeCoreExtreme]). The child renders, writes the
/// PNG and the meta (same schema as the in-process path, sharing the writer),
/// and reports progress on stdout as `PROGRESS_PREFIX + done`, one line per
/// finished row. Any failure is an exception and a non-zero exit; the parent
/// surfaces the tail.
public final class PreviewRenderMain {

    /// The parent parses lines starting with this prefix as progress updates.
    public static final String PROGRESS_PREFIX = "render-progress ";
    /// `RATE_PREFIX + <samples/sec>` arrives once at the end; the parent folds it
    /// into the job log, which is the seed of the CPU-benchmark idea and the
    /// first thing to read when a render is suspiciously slow.
    public static final String RATE_PREFIX = "render-rate ";

    private PreviewRenderMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 6) {
            System.err.println("usage: PreviewRenderMain <version> <seed> <radius> <biomeY> <png> <meta>");
            System.exit(2);
        }
        PreviewSpec spec = new PreviewSpec(args[0], args[1], Integer.parseInt(args[2]),
                Integer.parseInt(args[3]), PreviewSpec.Mode.BIOME, PreviewSpec.Engine.JAVA,
                PreviewSpec.Quality.EXTREME);
        Path png = Path.of(args[4]);
        Path meta = Path.of(args[5]);
        Files.createDirectories(png.getParent());
        Files.createDirectories(meta.getParent());

        // 诊断先行：一次失败/变慢的运行，第一眼看这四项就够定位大半问题
        // （是不是只分到单核、走了哪条引擎路径、目标规模对不对）。
        Version version = Version.parse(spec.version());
        System.out.println("render-start cells=" + spec.javaSamples()
                + " radius=" + spec.radiusBlocks()
                + " version=" + spec.version()
                + " cores=" + Runtime.getRuntime().availableProcessors()
                + " generator=" + (version.family() == Version.Family.MODERN ? "multinoise" : "layerstack"));

        long startedAt = System.nanoTime();
        SeedMapBackend.Result result = SeedMapBackend.render(spec, (done, total) ->
                System.out.println(PROGRESS_PREFIX + done));
        double seconds = (System.nanoTime() - startedAt) / 1_000_000_000.0;
        long total = (long) spec.javaSamples() * spec.javaSamples();
        System.out.println(RATE_PREFIX + Math.round(total / seconds) + " 采样/秒"
                + "（" + spec.javaSamples() + "²，" + Math.round(seconds) + " 秒，"
                + Runtime.getRuntime().availableProcessors() + " 核）");
        ImageIO.write(result.image(), "png", png.toFile());
        SeedPreviewService.writeBiomeMeta(meta, spec, result.samples(), result.map(), false);
        System.out.println("render-done "
                + spec.quality().name().toLowerCase(Locale.ROOT) + " " + spec.javaSamples());
    }
}
