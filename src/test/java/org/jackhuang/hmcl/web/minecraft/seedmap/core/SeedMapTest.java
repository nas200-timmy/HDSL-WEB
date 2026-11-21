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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SeedMapTest {

    @Test
    void biomeAtIsDeterministic() {
        SeedMap map = SeedMap.of(Version.V1_21_3, 12345L);
        Biome first = map.biomeAt(100, 200);
        Biome second = map.biomeAt(100, 200);
        assertEquals(first, second);
        assertNotEquals(Biome.NONE, first);
    }

    @Test
    void mapBiomesProducesRequestedArea() {
        SeedMap map = SeedMap.of(Version.V1_21_3, -987654321L);
        Biome[] area = map.mapBiomes(4, -8, -8, 16, 16, 15);
        assertEquals(256, area.length);
        for (Biome biome : area) {
            assertNotNull(biome);
            assertNotEquals(Biome.NONE, biome);
        }
    }

    @Test
    void villagesAreFoundInExpectedRegion() {
        SeedMap map = SeedMap.of(Version.V1_21_3, 42L);
        List<Structure> villages = map.structures(StructureType.VILLAGE, -512, -512, 512, 512);
        assertFalse(villages.isEmpty());
        for (Structure structure : villages) {
            assertEquals(StructureType.VILLAGE, structure.type());
            assertTrue(structure.pos().x() >= -512 && structure.pos().x() <= 512);
            assertTrue(structure.pos().z() >= -512 && structure.pos().z() <= 512);
        }
    }

    @Test
    void strongholdsFollowConcentricRings() {
        SeedMap map = SeedMap.of(Version.V1_21_3, 42L);
        List<BlockPos> strongholds = map.strongholds(3);
        assertEquals(3, strongholds.size());
        for (BlockPos pos : strongholds) {
            double distance = Math.sqrt((double) pos.x() * pos.x() + (double) pos.z() * pos.z());
            assertTrue(distance > 1000 && distance < 3000, "unexpected stronghold distance " + distance);
        }
    }

    @Test
    void slimeChunksAreDeterministic() {
        SeedMap map = SeedMap.of(Version.V1_21_3, 42L);
        boolean first = map.isSlimeChunk(3, -1);
        assertEquals(first, map.isSlimeChunk(3, -1));
        assertEquals(first, SeedMap.of(Version.V1_21_3, 42L).isSlimeChunk(3, -1));
    }
}
