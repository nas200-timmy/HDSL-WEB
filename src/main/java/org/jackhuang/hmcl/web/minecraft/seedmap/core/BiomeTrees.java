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
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/// Loads the generated biome-tree tables from classpath resources.
final class BiomeTrees {
    private static final Map<Version, BiomeTree> CACHE = new HashMap<>();

    private BiomeTrees() {
    }

    static synchronized BiomeTree forVersion(Version version) {
        return CACHE.computeIfAbsent(version, BiomeTrees::load);
    }

    private static BiomeTree load(Version version) {
        String name = switch (version) {
            case V1_21_3, V1_21_1 -> "btree21wd";
            case V1_20_6 -> "btree20";
            case V1_18_2 -> "btree18";
            default -> "btree18";
        };
        String resource = "/seedmap/" + name + ".txt";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                BiomeTrees.class.getResourceAsStream(resource), StandardCharsets.UTF_8))) {
            int order = 0;
            int[] steps = null;
            int[][] params = null;
            long[] nodes = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("order=")) {
                    order = Integer.parseInt(line.substring(6));
                } else if (line.startsWith("steps=")) {
                    String[] parts = line.substring(6).split(",");
                    steps = new int[parts.length];
                    for (int i = 0; i < parts.length; i++) {
                        steps[i] = Integer.parseInt(parts[i]);
                    }
                } else if (line.startsWith("params=")) {
                    String[] pairs = line.substring(7).split(";");
                    params = new int[pairs.length][2];
                    for (int i = 0; i < pairs.length; i++) {
                        String[] ab = pairs[i].split(",");
                        params[i][0] = Integer.parseInt(ab[0]);
                        params[i][1] = Integer.parseInt(ab[1]);
                    }
                } else if (line.startsWith("nodes=")) {
                    String[] parts = line.substring(6).split(",");
                    nodes = new long[parts.length];
                    for (int i = 0; i < parts.length; i++) {
                        nodes[i] = Long.parseUnsignedLong(parts[i], 16);
                    }
                }
            }
            if (steps == null || params == null || nodes == null) {
                throw new IllegalStateException("incomplete biome tree resource " + resource);
            }
            return new BiomeTree(steps, params, nodes, order);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
