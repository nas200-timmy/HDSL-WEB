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
package org.jackhuang.hmcl.web.minecraft.preview;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/// The text a model can read.
///
/// A PNG is useless to an agent: it cannot ask "is there a village near 0,0"
/// from a picture, and it should not have to fetch a 4 MB bitmap to find out.
/// This class turns the same metadata the map is drawn from into a stable,
/// line-oriented report: blocks of `key: value`, a section per structure type
/// with block coordinates, biome shares, and a coarse character map so the
/// shape of the world survives the trip through a chat context.
///
/// The format is deliberately boring. Every line either is `key: value` or
/// starts with `- `, so both a human and a parser can read it, and a future
/// change can add a section without breaking the ones before it.
@NotNullByDefault
public final class PreviewReport {

    private PreviewReport() {
    }

    /// Renders one cached preview.
    ///
    /// @param meta the `meta.json` body
    /// @return a plain-text report
    public static String render(JsonObject meta) {
        StringBuilder out = new StringBuilder(4096);
        out.append("# HDSL seed preview\n\n");

        line(out, "version", string(meta, "version"));
        line(out, "seed", string(meta, "seed"));
        int radius = integer(meta, "radius");
        line(out, "radius", radius + " blocks (x/z from " + (-radius) + " to " + (radius - 1) + ")");
        line(out, "biomeY", String.valueOf(integer(meta, "biomeY")));
        line(out, "generatedAt", string(meta, "generatedAt"));
        line(out, "chunks", String.valueOf(integer(meta, "chunks")));
        line(out, "state", bool(meta, "partial") ? "partial (generation timed out)" : "complete");

        JsonObject counts = object(meta, "structureCounts");
        out.append("\n## structures\n");
        if (counts == null || counts.size() == 0) {
            out.append("none\n");
        } else {
            out.append("counts:");
            List<String> types = new ArrayList<>(counts.keySet());
            types.sort(Comparator.naturalOrder());
            for (String type : types) {
                out.append(' ').append(type).append('=').append(counts.get(type).getAsInt());
            }
            out.append("\n\n");
            for (String type : types) {
                out.append(type).append('\n');
                for (String entry : coordinates(meta, type)) {
                    out.append("- ").append(entry).append('\n');
                }
            }
        }

        JsonObject biomes = object(meta, "biomeCounts");
        out.append("\n## biomes\n");
        if (biomes == null || biomes.size() == 0) {
            out.append("unknown\n");
        } else {
            int total = 0;
            for (Map.Entry<String, JsonElement> entry : biomes.entrySet()) {
                total += entry.getValue().getAsInt();
            }
            List<Map.Entry<String, JsonElement>> ordered = new ArrayList<>(biomes.entrySet());
            ordered.sort((left, right) -> right.getValue().getAsInt() - left.getValue().getAsInt());
            for (Map.Entry<String, JsonElement> entry : ordered) {
                out.append(entry.getKey()).append(' ')
                        .append(String.format(Locale.ROOT, "%.1f%%",
                                100.0 * entry.getValue().getAsInt() / Math.max(1, total)))
                        .append('\n');
            }
        }

        JsonArray slime = array(meta, "slimeChunks");
        out.append("\n## slime chunks\n").append(slime == null ? 0 : slime.size()).append(" chunks\n");

        JsonArray ascii = array(meta, "ascii");
        if (ascii != null && !ascii.isEmpty()) {
            out.append("\n## map\n");
            out.append("one character per 16 blocks, north is up\n");
            for (JsonElement line : ascii) {
                out.append(line.getAsString()).append('\n');
            }
            out.append("legend: . ocean  ~ river  , beach  \" plains  T forest  ^ mountain  ")
                    .append("# desert  * snow  + swamp  % cave  & mushroom  ? other\n");
        }
        return out.toString();
    }

    /// `- x, z (chunk cx, cz)` for every recorded start of one type.
    private static List<String> coordinates(JsonObject meta, String type) {
        List<String> rows = new ArrayList<>();
        JsonArray structures = array(meta, "structures");
        if (structures == null) {
            return rows;
        }
        for (JsonElement element : structures) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject structure = element.getAsJsonObject();
            if (!type.equals(string(structure, "id"))) {
                continue;
            }
            rows.add(string(structure, "x") + ", " + string(structure, "z")
                    + " (chunk " + string(structure, "chunkX") + ", " + string(structure, "chunkZ") + ")");
        }
        return rows;
    }

    private static void line(StringBuilder out, String key, String value) {
        out.append(key).append(": ").append(value == null ? "" : value).append('\n');
    }

    private static String string(JsonObject object, String name) {
        return object != null && object.has(name) && object.get(name).isJsonPrimitive()
                ? object.get(name).getAsString() : "";
    }

    private static int integer(JsonObject object, String name) {
        return object != null && object.has(name) && object.get(name).isJsonPrimitive()
                ? object.get(name).getAsInt() : 0;
    }

    private static boolean bool(JsonObject object, String name) {
        return object != null && object.has(name) && object.get(name).isJsonPrimitive()
                && object.get(name).getAsBoolean();
    }

    private static JsonObject object(JsonObject object, String name) {
        return object != null && object.has(name) && object.get(name).isJsonObject()
                ? object.getAsJsonObject(name) : null;
    }

    private static JsonArray array(JsonObject object, String name) {
        return object != null && object.has(name) && object.get(name).isJsonArray()
                ? object.getAsJsonArray(name) : null;
    }
}
