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
package org.jackhuang.hmcl.dsh;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BootstrapCatalogTest {

    @AfterEach
    public void restore() {
        System.clearProperty(BootstrapCatalog.PROPERTY_CATALOG);
        BootstrapCatalog.reset();
    }

    @Test
    public void theEmbeddedCataloguePinsEveryBundle() {
        BootstrapCatalog.Catalog catalog = BootstrapCatalog.current();

        assertEquals(2, catalog.schema());
        assertNotNull(catalog.ca());
        assertNotNull(catalog.uv());
        assertEquals("0.7.13", catalog.uv().version());
        assertEquals(6, catalog.uv().binaries().size());
        assertNotNull(catalog.uv().sha256("linux-x64"));
        assertEquals(64, catalog.uv().sha256("linux-x64").length());

        assertNotNull(catalog.python());
        assertNotNull(catalog.python().artifact("linux-x64"));
        assertTrue(catalog.python().url(catalog.python().artifact("linux-x64"))
                .startsWith(catalog.python().releaseBase()));

        assertNotNull(catalog.toolchain());
        assertNotNull(catalog.toolchain().windows());
        assertNotNull(catalog.toolchain().linux());
        assertEquals(5, catalog.toolchain().linux().artifacts("x64").size());
        assertEquals(5, catalog.toolchain().linux().artifacts("arm64").size());
        assertEquals("zig", catalog.toolchain().linux().artifacts("x64").get(0).name());

        assertEquals("embedded", BootstrapCatalog.origin().kind());
    }

    @Test
    public void anOperatorCatalogueIsUsedAndNamed(@TempDir Path dir) throws Exception {
        String sha = "b".repeat(64);
        Path file = dir.resolve("bootstrap.json");
        Files.writeString(file, completeCatalog("9.9.9", sha));
        System.setProperty(BootstrapCatalog.PROPERTY_CATALOG, file.toString());
        BootstrapCatalog.reset();

        assertEquals("9.9.9", BootstrapCatalog.current().uv().version());
        assertEquals(sha, UvRuntime.binarySha256("linux-x64"));
        assertEquals("operator", BootstrapCatalog.origin().kind());
        assertTrue(BootstrapCatalog.origin().detail().contains("bootstrap.json"));
    }

    @Test
    public void aHalfFilledOverrideIsRejectedRatherThanHalfUsed(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("partial.json");
        Files.writeString(file, "{\"schema\":2,\"uv\":{\"version\":\"9.9.9\","
                + "\"binaries\":[{\"platform\":\"linux-x64\",\"sha256\":\"" + "d".repeat(64)
                + "\"}]}}");
        System.setProperty(BootstrapCatalog.PROPERTY_CATALOG, file.toString());
        BootstrapCatalog.reset();

        assertEquals("embedded", BootstrapCatalog.origin().kind());
        assertTrue(BootstrapCatalog.origin().detail().contains("python, toolchain"),
                BootstrapCatalog.origin().detail());
    }

    @Test
    public void aRejectedOverrideSaysSoAndFallsBack(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("broken.json");
        Files.writeString(file, "{ not json");
        System.setProperty(BootstrapCatalog.PROPERTY_CATALOG, file.toString());
        BootstrapCatalog.reset();

        assertEquals("embedded", BootstrapCatalog.origin().kind());
        assertTrue(BootstrapCatalog.origin().detail().contains("rejected"),
                BootstrapCatalog.origin().detail());
        assertEquals("0.7.13", BootstrapCatalog.current().uv().version());
    }

    @Test
    public void aSchemaOneCatalogueStillParsesWithoutTheNewerSections() {
        BootstrapCatalog.Catalog catalog = BootstrapCatalog.Catalog.parse(
                "{\"schema\":1,\"updatedAt\":\"2025-01-01\"}", "test");
        assertEquals(1, catalog.schema());
        assertNull(catalog.uv());
        assertNull(catalog.python());
        assertNull(catalog.toolchain());
    }

    @Test
    public void aPinThatIsNotLowercaseSha256IsRejected() {
        assertTrue(throwsOn("{\"schema\":2,\"uv\":{\"version\":\"1\","
                + "\"binaries\":[{\"platform\":\"linux-x64\",\"sha256\":\"ABC\"}]}}"));
        assertTrue(throwsOn("{\"schema\":2,\"uv\":{\"version\":\"1\","
                + "\"binaries\":[{\"platform\":\"linux-x64\",\"sha256\":\""
                + "c".repeat(64) + "\"}]},\"python\":{\"version\":\"3.12.16\","
                + "\"build\":\"20260101\",\"releaseBase\":\"http://insecure.example\","
                + "\"artifacts\":[]}}"));
    }

    /// A catalogue that pins every section, so only the values under test vary.
    /// A complete override: the embedded catalogue with its `uv` section
    /// replaced. Built this way rather than spelled out by hand so that a
    /// section added to the real catalogue is required here too — the sibling
    /// test is named "half-filled override rejected", and a fixture that quietly
    /// omits a section was the thing that let the check miss `ca` and `runtimes`.
    private static String completeCatalog(String uvVersion, String uvSha) throws Exception {
        String json;
        try (java.io.InputStream in =
                     BootstrapCatalog.class.getResourceAsStream("/bootstrap/bootstrap.json")) {
            json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        com.google.gson.JsonObject root =
                com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        com.google.gson.JsonObject uv = new com.google.gson.JsonObject();
        uv.addProperty("version", uvVersion);
        com.google.gson.JsonArray binaries = new com.google.gson.JsonArray();
        com.google.gson.JsonObject binary = new com.google.gson.JsonObject();
        binary.addProperty("platform", "linux-x64");
        binary.addProperty("sha256", uvSha);
        binaries.add(binary);
        uv.add("binaries", binaries);
        root.add("uv", uv);
        return root.toString();
    }

    private static boolean throwsOn(String json) {
        try {
            BootstrapCatalog.Catalog.parse(json, "test");
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }
    /// An override that drops a whole section used to pass: only uv, python and
    /// toolchain were required, so a catalogue with no CA pin or no runtime
    /// closure was accepted and failed later at install time, after the operator
    /// believed the override had worked.
    @Test
    void anOverrideMissingACaOrRuntimePinIsRejected() throws Exception {
        String json;
        try (java.io.InputStream in =
                     BootstrapCatalog.class.getResourceAsStream("/bootstrap/bootstrap.json")) {
            json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        com.google.gson.JsonObject root =
                com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        root.remove("ca");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> BootstrapCatalog.requireComplete(
                        BootstrapCatalog.Catalog.parse(root.toString(), "test"), "test"));
        assertTrue(thrown.getMessage().contains("ca"), thrown.getMessage());
    }
}
