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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The reader is the only part of the preview that can be wrong without the
/// server noticing, so it is tested against hand-built region files for all
/// three chunk layouts the panel claims to support.
class SeedPreviewTest {

    @TempDir
    Path temporary;

    // ------------------------------------------------------------------ spec --

    @Test
    void specTurnsRadiusIntoAWholeChunkSquare() {
        PreviewSpec spec = new PreviewSpec("1.21.4", "42", 512, 63);
        assertEquals(64, spec.chunksPerSide());
        assertEquals(1024, spec.pixelsPerSide());
        assertEquals(4096, spec.chunkCount());
        assertEquals(-32, spec.firstChunk());
        assertEquals(31, spec.lastChunk());
    }

    @Test
    void slimeChunksAreDeterministicAndAboutOneInTen() {
        int slime = 0;
        for (int x = -50; x < 50; x++) {
            for (int z = -50; z < 50; z++) {
                boolean first = SlimeChunks.isSlimeChunk(1234L, x, z);
                assertEquals(first, SlimeChunks.isSlimeChunk(1234L, x, z));
                if (first) {
                    slime++;
                }
            }
        }
        assertTrue(slime > 700 && slime < 1300, "expected roughly 10%, saw " + slime);
    }

    @Test
    void reportIsLineOrientedAndCarriesCoordinates() {
        com.google.gson.JsonObject meta = com.google.gson.JsonParser.parseString("""
                {"version":"1.21","seed":"42","radius":256,"biomeY":63,
                 "generatedAt":"2026-11-19T00:00:00Z","chunks":1024,"partial":false,
                 "structureCounts":{"village_plains":2,"mineshaft":1},
                 "structures":[
                   {"id":"village_plains","x":16,"z":-32,"chunkX":1,"chunkZ":-2},
                   {"id":"village_plains","x":48,"z":0,"chunkX":3,"chunkZ":0},
                   {"id":"mineshaft","x":-16,"z":16,"chunkX":-1,"chunkZ":1}],
                 "biomeCounts":{"plains":3,"ocean":1},
                 "slimeChunks":[{"x":0,"z":0},{"x":1,"z":1}],
                 "ascii":["..TT","..TT"]}
                """).getAsJsonObject();

        String text = PreviewReport.render(meta);
        assertTrue(text.contains("seed: 42"));
        assertTrue(text.contains("state: complete"));
        assertTrue(text.contains("village_plains"));
        assertTrue(text.contains("- 16, -32 (chunk 1, -2)"));
        assertTrue(text.contains("- 48, 0 (chunk 3, 0)"));
        // Percentages are computed against the biome cells, not the block area.
        assertTrue(text.contains("plains 75.0%"));
        assertTrue(text.contains("ocean 25.0%"));
        assertTrue(text.contains("2 chunks"));
        assertTrue(text.contains("..TT"));
    }

    // --------------------------------------------------------------- fixtures --

    @Test
    void readsA112Chunk() throws IOException {
        Map<String, Object> level = new LinkedHashMap<>();
        byte[] biomes = new byte[256];
        biomes[0] = 2; // desert
        level.put("Biomes", biomes);
        level.put("Structures", Map.of("Starts", Map.of(
                "Village", Map.of("ChunkX", 0, "ChunkZ", 0))));
        Path region = writeRegion(Map.of("Level", level), 2);

        List<RegionReader.ChunkSummary> chunks = RegionReader.read(region, 0, 0, 63);
        assertEquals(1, chunks.size());
        RegionReader.ChunkSummary chunk = chunks.get(0);
        assertEquals(0, chunk.chunkX());
        assertEquals(0, chunk.chunkZ());
        assertEquals("desert", chunk.biomes()[0]);
        assertEquals(1, chunk.structures().size());
        assertEquals("village", chunk.structures().get(0).id());
    }

    @Test
    void readsA116ChunkAtTheRequestedHeight() throws IOException {
        int[] biomes = new int[1024];
        for (int i = 0; i < biomes.length; i++) {
            biomes[i] = 1; // plains everywhere
        }
        // y=63 is the fifteenth 4-block slice (15 * 16 + z4 * 4 + x4).
        biomes[15 * 16] = 2; // desert in the first column of that slice
        Map<String, Object> level = new LinkedHashMap<>();
        level.put("Biomes", biomes);
        Path region = writeRegion(Map.of("Level", level), 1);

        RegionReader.ChunkSummary chunk = RegionReader.read(region, 0, 0, 63).get(0);
        assertEquals("desert", chunk.biomes()[0]);
        assertEquals("plains", chunk.biomes()[1]);

        // The same chunk read at y=0 must not see the y=63 slice.
        RegionReader.ChunkSummary surface = RegionReader.read(region, 0, 0, 0).get(0);
        assertEquals("plains", surface.biomes()[0]);
    }

    @Test
    void readsA121ChunkFromSections() throws IOException {
        // A two-entry biome palette packs at one bit per cell: 64 cells in a
        // single long. Every bit set means every cell is palette index 1.
        long[] data = new long[]{-1L};
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("Y", 3);
        section.put("biomes", Map.of(
                "palette", List.of("minecraft:plains", "minecraft:desert"),
                "data", data));
        Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("sections", List.of(section));
        chunk.put("structures", Map.of("starts", Map.of(
                "minecraft:village", Map.of(
                        "id", "minecraft:village_plains",
                        "ChunkX", 5,
                        "ChunkZ", 6))));

        Path region = writeRegion(chunk, 2);
        RegionReader.ChunkSummary summary = RegionReader.read(region, 0, 0, 63).get(0);
        assertEquals("desert", summary.biomes()[0]);
        assertEquals("desert", summary.biomes()[15]);
        assertEquals(1, summary.structures().size());
        assertEquals("village_plains", summary.structures().get(0).id());
        assertEquals(5, summary.structures().get(0).chunkX());
        assertEquals(6, summary.structures().get(0).chunkZ());
    }

    @Test
    void countsOnlyTheRequestedSquare() throws IOException {
        Map<String, Object> level = new LinkedHashMap<>();
        level.put("Biomes", new byte[256]);
        writeRegion(Map.of("Level", level), 2);

        Path directory = temporary.resolve("region");
        assertEquals(1, RegionReader.countPresentChunks(directory));
        assertEquals(1, RegionReader.countPresentChunks(directory, 0, 0));
        assertEquals(0, RegionReader.countPresentChunks(directory, 10, 20));
    }

    // --------------------------------------------------------------- the file --

    /// Writes a one-chunk `r.0.0.mca` whose only chunk sits at 0,0.
    private Path writeRegion(Map<String, Object> root, int compression) throws IOException {
        Path directory = temporary.resolve("region");
        Files.createDirectories(directory);

        byte[] nbt = nbt(root);
        byte[] payload;
        if (compression == 1) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
                gzip.write(nbt);
            }
            payload = bytes.toByteArray();
        } else {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DeflaterOutputStream deflate = new DeflaterOutputStream(bytes)) {
                deflate.write(nbt);
            }
            payload = bytes.toByteArray();
        }

        int total = 5 + payload.length;
        int sectors = (total + 4095) / 4096;
        ByteBuffer file = ByteBuffer.allocate(8192 + sectors * 4096);
        // Location entry 0: offset 2, sector count.
        file.put(0, (byte) 0);
        file.put(1, (byte) 0);
        file.put(2, (byte) 2);
        file.put(3, (byte) sectors);
        file.position(8192);
        file.putInt(total);
        file.put((byte) compression);
        file.put(payload);

        Path path = directory.resolve("r.0.0.mca");
        Files.write(path, file.array());
        return path;
    }

    private static byte[] nbt(Map<String, Object> root) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(10);
            writeString(out, "");
            writeBody(out, root);
        }
        return bytes.toByteArray();
    }

    private static void writeBody(DataOutputStream out, Map<String, Object> body) throws IOException {
        for (Map.Entry<String, Object> entry : body.entrySet()) {
            writeTag(out, entry.getKey(), entry.getValue());
        }
        out.writeByte(0);
    }

    private static void writeTag(DataOutputStream out, String name, Object value) throws IOException {
        if (value instanceof Integer number) {
            out.writeByte(3);
            writeString(out, name);
            out.writeInt(number);
        } else if (value instanceof Long number) {
            out.writeByte(4);
            writeString(out, name);
            out.writeLong(number);
        } else if (value instanceof String text) {
            out.writeByte(8);
            writeString(out, name);
            writeString(out, text);
        } else if (value instanceof byte[] array) {
            out.writeByte(7);
            writeString(out, name);
            out.writeInt(array.length);
            out.write(array);
        } else if (value instanceof int[] array) {
            out.writeByte(11);
            writeString(out, name);
            out.writeInt(array.length);
            for (int element : array) {
                out.writeInt(element);
            }
        } else if (value instanceof long[] array) {
            out.writeByte(12);
            writeString(out, name);
            out.writeInt(array.length);
            for (long element : array) {
                out.writeLong(element);
            }
        } else if (value instanceof List<?> list) {
            out.writeByte(9);
            writeString(out, name);
            writeList(out, list);
        } else if (value instanceof Map<?, ?> map) {
            out.writeByte(10);
            writeString(out, name);
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            writeBody(out, typed);
        } else {
            throw new IllegalArgumentException("unsupported fixture type " + value.getClass());
        }
    }

    private static void writeList(DataOutputStream out, List<?> list) throws IOException {
        if (list.isEmpty()) {
            out.writeByte(0);
            out.writeInt(0);
            return;
        }
        Object first = list.get(0);
        int type;
        if (first instanceof Integer) {
            type = 3;
        } else if (first instanceof Long) {
            type = 4;
        } else if (first instanceof String) {
            type = 8;
        } else if (first instanceof byte[]) {
            type = 7;
        } else if (first instanceof int[]) {
            type = 11;
        } else if (first instanceof long[]) {
            type = 12;
        } else if (first instanceof Map<?, ?>) {
            type = 10;
        } else if (first instanceof List<?>) {
            type = 9;
        } else {
            throw new IllegalArgumentException("unsupported list element " + first.getClass());
        }
        out.writeByte(type);
        out.writeInt(list.size());
        for (Object element : list) {
            writePayload(out, element);
        }
    }

    private static void writePayload(DataOutputStream out, Object value) throws IOException {
        if (value instanceof Integer number) {
            out.writeInt(number);
        } else if (value instanceof Long number) {
            out.writeLong(number);
        } else if (value instanceof String text) {
            writeString(out, text);
        } else if (value instanceof byte[] array) {
            out.writeInt(array.length);
            out.write(array);
        } else if (value instanceof int[] array) {
            out.writeInt(array.length);
            for (int element : array) {
                out.writeInt(element);
            }
        } else if (value instanceof long[] array) {
            out.writeInt(array.length);
            for (long element : array) {
                out.writeLong(element);
            }
        } else if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            writeBody(out, typed);
        } else if (value instanceof List<?> list) {
            writeList(out, new ArrayList<>(list));
        } else {
            throw new IllegalArgumentException("unsupported payload " + value.getClass());
        }
    }

    @Test
    void oldCacheDirectoriesRecoverFieldsAndKeepDistinctIds() {
        // Legacy meta.json files recorded no engine/quality; the directory name
        // still carries them, and it is what keeps sibling entries apart.
        SeedPreviewService.VariantHints hd = SeedPreviewService.variantHints("8192-63-biome-java-hd-v10");
        assertEquals("java", hd.engine());
        assertEquals("high", hd.quality());
        assertEquals("biome", hd.mode());

        SeedPreviewService.VariantHints detail = SeedPreviewService.variantHints("512-63-v9");
        assertEquals("server", detail.engine());
        assertEquals("standard", detail.quality());
        assertEquals("detail", detail.mode());

        // These siblings in the live cache recorded identical inputs and used to
        // share one id (and one React key in the history table).
        assertNotEquals(
                SeedPreviewService.historyId("1.14.4", "237286", "512-63-v10"),
                SeedPreviewService.historyId("1.14.4", "237286", "512-63-biome-v10"));
        assertNotEquals(
                SeedPreviewService.historyId("1.14.4", "237286", "8192-63-biome-java-v10"),
                SeedPreviewService.historyId("1.14.4", "237286", "8192-63-biome-java-hd-v10"));
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.writeShort(bytes.length);
        out.write(bytes);
    }
}
