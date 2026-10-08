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
package org.jackhuang.hmcl.web.brand;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The published-port registry behind own-origin brand instances.
class BrandPortRegistryTest {

    @TempDir
    Path root;

    @org.junit.jupiter.api.BeforeEach
    void reset() {
        BrandPortRegistry.clear();
    }

    @Test
    void allocationsStayInTheRangeAndNeverCollide() throws Exception {
        Set<Integer> taken = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            int port = BrandPortRegistry.allocate();
            assertTrue(BrandPortRegistry.inRange(port), "allocated " + port);
            assertTrue(taken.add(port), "no duplicate allocation: " + port);
            // Handed out from the start of the range upward, so the first
            // own-origin instance always lands on 3091 — what makes forwarding
            // a single router port possible.
            assertEquals(BrandPortRegistry.RANGE_START + i, port);
            BrandPortRegistry.register(port, "instance-" + i);
        }
        // The range is exhausted now.
        try {
            BrandPortRegistry.allocate();
            org.junit.jupiter.api.Assertions.fail("an exhausted range must refuse");
        } catch (BrandException expected) {
            assertTrue(expected.getMessage().contains("3091"), expected.getMessage());
        }
    }

    @Test
    void ownershipResolvesAndReleases() {
        BrandPortRegistry.register(3091, "a");
        assertEquals("a", BrandPortRegistry.ownerOf(3091));
        assertNull(BrandPortRegistry.ownerOf(3092));
        BrandPortRegistry.release(3091);
        assertNull(BrandPortRegistry.ownerOf(3091));
    }

    @Test
    void replayPicksUpPersistedPortsFromManifests() throws Exception {
        Path instances = Files.createDirectories(
                root.resolve("opencode").resolve("instances").resolve("abc"));
        Files.writeString(instances.resolve("instance.json"), """
                {"id":"abc","brand":"opencode","name":"demo","version":"1.18.35",
                 "lastPort":0,"publicPort":3097,"createdAt":1}
                """);

        BrandPortRegistry.replay(root);

        assertEquals("abc", BrandPortRegistry.ownerOf(3097));
        BrandPortRegistry.release(3097);
    }
}
