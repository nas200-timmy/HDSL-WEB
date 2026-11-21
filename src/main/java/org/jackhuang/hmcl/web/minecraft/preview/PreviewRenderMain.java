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

        SeedMapBackend.Result result = SeedMapBackend.render(spec, (done, total) ->
                System.out.println(PROGRESS_PREFIX + done));
        ImageIO.write(result.image(), "png", png.toFile());
        SeedPreviewService.writeBiomeMeta(meta, spec, result.samples(), result.map(), false);
        System.out.println("render-done "
                + spec.quality().name().toLowerCase(Locale.ROOT) + " " + spec.javaSamples());
    }
}
