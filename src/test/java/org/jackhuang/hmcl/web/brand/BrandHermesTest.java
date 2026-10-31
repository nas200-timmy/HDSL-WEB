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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// What Hermes is, as the rest of the panel asks about it: prefix-aware on its
/// own, installed from Python source, two processes behind one wrapper, and a
/// stop window long enough for its gateway to drain.
class BrandHermesTest {

    @Test
    void hermesDeclaresItsFourCapabilities() {
        assertTrue(Brand.HERMES.serverSidePrefix(), "its dashboard reads X-Forwarded-Prefix");
        assertTrue(Brand.HERMES.pythonSource());
        assertTrue(Brand.HERMES.companionProcess(), "gateway + dashboard behind one wrapper");
        assertEquals(40, Brand.HERMES.stopWaitSeconds(), "the gateway drains for up to 30 s");

        for (Brand other : Brand.values()) {
            if (other == Brand.HERMES) {
                continue;
            }
            assertFalse(other.serverSidePrefix(), other + " is proxied by the panel's rewriting");
            assertFalse(other.pythonSource(), other.name());
            assertFalse(other.companionProcess(), other.name());
            assertEquals(10, other.stopWaitSeconds(), other.name());
        }
    }

    @Test
    void theInstallMarkerIsTheBuiltDashboard() {
        assertEquals("hermes_cli/web_dist/index.html", Brand.HERMES.installMarker());
        assertEquals("https://github.com/NousResearch/hermes-agent", Brand.HERMES.repoUrl());
        assertEquals("NousResearch/hermes-agent", Brand.HERMES.githubSlug());
        assertEquals("v", Brand.HERMES.tagPrefix());
        assertEquals("v0.22.5", Brand.HERMES.releaseTag("0.22.5"));
    }

    @Test
    void dateTagsAndCanaryBuildsAreNotReleases() {
        assertTrue(Brand.HERMES.isReleaseVersion("0.22.5"));
        assertTrue(Brand.HERMES.isReleaseVersion("1.0.0"));
        // Upstream also publishes a date snapshot (`v2026.9.24`) that a plain
        // three-part rule would accept; a two-digit major is what keeps it out.
        assertFalse(Brand.HERMES.isReleaseVersion("2026.9.24"));
        assertFalse(Brand.HERMES.isReleaseVersion("0.22.5+canary.20261031T064409Z"));
        assertFalse(Brand.HERMES.isReleaseVersion("0.22.5.1"));
    }

    @Test
    void windowsIsRefusedWithAReasonThePageCanPrint() {
        for (org.jackhuang.hmcl.util.platform.OperatingSystem os
                : org.jackhuang.hmcl.util.platform.OperatingSystem.values()) {
            if (os == org.jackhuang.hmcl.util.platform.OperatingSystem.WINDOWS) {
                assertFalse(Brand.HERMES.installableOn(os), "no shell, no POSIX venv layout");
                String reason = Brand.HERMES.notInstallableReason(os);
                assertNotNull(reason);
                assertTrue(reason.contains("WSL2"), "the way out has to be in the sentence: " + reason);
            } else {
                assertTrue(Brand.HERMES.installableOn(os), os + " is supported");
                assertNull(Brand.HERMES.notInstallableReason(os));
            }
            // Every other brand installs everywhere: their installers are npm/portable archives.
            for (Brand other : Brand.values()) {
                if (other != Brand.HERMES) {
                    assertTrue(other.installableOn(os), other + " on " + os);
                    assertNull(other.notInstallableReason(os));
                }
            }
        }
    }

    @Test
    void everyBrandHasAStableId() {
        assertNull(Brand.fromId("nope"));
        assertEquals(Brand.HERMES, Brand.fromId("hermes"));
    }
}
