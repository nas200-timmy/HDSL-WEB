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

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The three last-resort switches: defaults, survivor semantics and validation.
class BrandPolicyTest {

    @Test
    void defaultsAreOffAndEveryInstanceIsEnabled(@TempDir Path dataDir) {
        BrandPolicy policy = new BrandPolicy(dataDir);
        BrandPolicy.Entry entry = policy.get(Brand.OCTOP);
        assertFalse(entry.singleInstance());
        assertNull(entry.survivor());
        assertFalse(entry.lockedRoute());
        assertFalse(entry.sourceBuild());
        assertFalse(policy.disabled(Brand.OCTOP, "any-token"));
        assertNull(policy.lockedSurvivor());
    }

    @Test
    void singleInstanceRequiresASurvivorAndDisablesTheRest(@TempDir Path dataDir) throws Exception {
        BrandPolicy policy = new BrandPolicy(dataDir);
        assertThrows(IllegalArgumentException.class,
                () -> policy.set(Brand.OCTOP,
                        new BrandPolicy.Entry(true, null, false, false)));

        policy.set(Brand.OCTOP, new BrandPolicy.Entry(true, "keep-me", false, false));
        assertFalse(policy.disabled(Brand.OCTOP, "keep-me"));
        assertTrue(policy.disabled(Brand.OCTOP, "other"));
        // Other brands are independent.
        assertFalse(policy.disabled(Brand.KIMI, "other"));
    }

    @Test
    void lockedRouteRequiresSingleInstanceAndASurvivor(@TempDir Path dataDir) {
        BrandPolicy policy = new BrandPolicy(dataDir);
        assertThrows(IllegalArgumentException.class,
                () -> policy.set(Brand.OCTOP,
                        new BrandPolicy.Entry(false, null, true, false)));
        assertThrows(IllegalArgumentException.class,
                () -> policy.set(Brand.OCTOP,
                        new BrandPolicy.Entry(true, null, true, false)));
    }

    @Test
    void lockedRouteSurvivesAReadBack(@TempDir Path dataDir) throws Exception {
        BrandPolicy policy = new BrandPolicy(dataDir);
        policy.set(Brand.OCTOP, new BrandPolicy.Entry(true, "keep-me", true, false));
        assertEquals("keep-me", policy.lockedSurvivor());
        assertEquals("keep-me", new BrandPolicy(dataDir).lockedSurvivor());
    }

    @Test
    void writerKeepsOtherBrands(@TempDir Path dataDir) throws Exception {
        BrandPolicy policy = new BrandPolicy(dataDir);
        policy.set(Brand.KIMI, new BrandPolicy.Entry(true, "kimi-one", false, false));
        policy.set(Brand.OCTOP, new BrandPolicy.Entry(true, "octop-one", false, false));
        assertEquals("kimi-one", policy.get(Brand.KIMI).survivor());
        assertEquals("octop-one", policy.get(Brand.OCTOP).survivor());
    }
}
