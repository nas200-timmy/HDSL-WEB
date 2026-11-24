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
        assertEquals(5, catalog.toolchain().linux().artifacts().size());
        assertEquals("zig", catalog.toolchain().linux().artifacts().get(0).name());

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
    private static String completeCatalog(String uvVersion, String uvSha) {
        return "{\"schema\":2,\"updatedAt\":\"2026-01-01\","
                + "\"uv\":{\"version\":\"" + uvVersion + "\",\"binaries\":["
                + "{\"platform\":\"linux-x64\",\"sha256\":\"" + uvSha + "\"}]},"
                + "\"python\":{\"version\":\"3.12.16\",\"build\":\"20260101\","
                + "\"releaseBase\":\"https://example.invalid/py\",\"artifacts\":[]},"
                + "\"toolchain\":{\"linux\":{\"version\":\"0.14.1\",\"artifacts\":["
                + "{\"name\":\"zig\",\"archive\":\"tar.xz\","
                + "\"url\":\"https://example.invalid/z.tar.xz\",\"sha256\":\""
                + "e".repeat(64) + "\",\"size\":0,\"includes\":[]}]}}}";
    }

    private static boolean throwsOn(String json) {
        try {
            BootstrapCatalog.Catalog.parse(json, "test");
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }
}
