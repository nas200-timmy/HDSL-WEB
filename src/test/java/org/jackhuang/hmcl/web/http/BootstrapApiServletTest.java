/*
 * HDSL
 * Copyright (C) 2026  HDSL contributors
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
package org.jackhuang.hmcl.web.http;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BootstrapApiServletTest {

    @Test
    public void theReportNamesEveryBundleOnce() {
        JsonObject report = BootstrapApiServlet.report();
        JsonArray bundles = report.getAsJsonArray("bundles");
        assertEquals(List.of("closure", "ca", "python", "toolchain", "toolchain-portable", "uv"),
                bundles.asList().stream().map(e -> e.getAsJsonObject().get("id").getAsString())
                        .toList());
        assertFalse(report.get("catalog").getAsString().isBlank());
        assertFalse(report.get("generatedAt").getAsString().isBlank());
    }

    @Test
    public void everyBundleSaysWhatWhyAndWhere() {
        for (JsonElement element : BootstrapApiServlet.report().getAsJsonArray("bundles")) {
            JsonObject bundle = element.getAsJsonObject();
            String id = bundle.get("id").getAsString();
            assertFalse(bundle.get("label").getAsString().isBlank(), id);
            assertFalse(bundle.get("detail").getAsString().isBlank(), id);
            assertFalse(bundle.get("why").getAsString().isBlank(), id);
            assertTrue(List.of("ready", "missing", "not-applicable")
                    .contains(bundle.get("state").getAsString()), id);
            assertTrue(bundle.has("canInstall"), id);
        }
    }

    @Test
    public void onlyAMissingBundleOffersInstall() {
        for (JsonElement element : BootstrapApiServlet.report().getAsJsonArray("bundles")) {
            JsonObject bundle = element.getAsJsonObject();
            if (!"missing".equals(bundle.get("state").getAsString())) {
                assertFalse(bundle.get("canInstall").getAsBoolean(),
                        bundle.get("id").getAsString() + " is not missing but offers install");
            }
        }
    }

    @Test
    public void aMissingBundleWithAPinCarriesUrlAndDigest() {
        for (JsonElement element : BootstrapApiServlet.report().getAsJsonArray("bundles")) {
            JsonObject bundle = element.getAsJsonObject();
            if (!bundle.get("canInstall").getAsBoolean()) {
                continue;
            }
            assertTrue(bundle.get("state").getAsString().equals("missing"));
            if (bundle.has("sha256")) {
                assertTrue(bundle.get("sha256").getAsString().matches("[0-9a-f]{64}"),
                        bundle.get("id").getAsString());
                assertTrue(bundle.has("source"), bundle.get("id").getAsString());
            }
        }
    }

    @Test
    public void thePlatformTagMatchesTheCatalogueKeys() {
        String tag = BootstrapApiServlet.archTag();
        assertTrue(tag.equals("x64") || tag.equals("arm64") || tag.matches("[a-z0-9]+"), tag);
        // Portable bundles exist for x64 and Linux arm64; an offer to install
        // one proves which architecture the page thinks it is on.
        if (BootstrapApiServlet.canInstallToolchain()) {
            assertTrue(tag.equals("x64") || tag.equals("arm64"), tag);
        }
    }
}
