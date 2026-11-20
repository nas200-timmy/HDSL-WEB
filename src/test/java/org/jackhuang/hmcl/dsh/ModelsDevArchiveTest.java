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

import kala.compress.archivers.tar.TarArchiveEntry;
import kala.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelsDevArchiveTest {

    @Test
    void providerAndModelTomlFlattenIntoTheSameCatalogShapeAsThePublishedJson() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            entry(tar, "models.dev-dev/providers/alibaba-token-plan/provider.toml", """
                    name = "Alibaba Token Plan"
                    env = ["ALIBABA_TOKEN_PLAN_API_KEY"]
                    npm = "@ai-sdk/openai-compatible"
                    doc = "https://www.alibabacloud.com/help/zh/model-studio/token-plan-overview"
                    api = "https://token-plan.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1"
                    """);
            entry(tar, "models.dev-dev/models/alibaba/qwen3.8-max.toml", """
                    schema = 2
                    name = "Qwen3.8 Max"
                    [modalities]
                    input = ["text", "image/png"]
                    output = ["text"]
                    [capabilities]
                    tools = { supported = true }
                    reasoning = { supported = true, effort = ["low", "medium", "xhigh"] }
                    [limit]
                    context = 1_000_000
                    output = 131_072
                    """);
            entry(tar, "models.dev-dev/providers/alibaba-token-plan/models/qwen3.8-max.toml", """
                    schema = 2
                    base_model = "alibaba/qwen3.8-max"
                    [cost.text.input]
                    price = 2
                    [cost.text.output]
                    price = 6
                    """);
            tar.finish();
        }
        byte[] archive = bytes.toByteArray();

        DshModelCatalog.Catalog catalog = ModelsDevArchive.parse(archive);
        assertEquals(1, catalog.providers().size());
        DshModelCatalog.Provider provider = catalog.byId("alibaba-token-plan");
        assertNotNull(provider);
        assertEquals("Alibaba Token Plan", provider.name());
        assertEquals("https://token-plan.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1",
                provider.endpoint());
        assertEquals(List.of("ALIBABA_TOKEN_PLAN_API_KEY"), provider.env());
        assertEquals("@ai-sdk/openai-compatible", provider.npm());

        assertEquals(1, provider.models().size());
        DshModelCatalog.Model model = provider.models().get(0);
        assertEquals("qwen3.8-max", model.id());
        assertEquals("Qwen3.8 Max", model.name());
        assertEquals(1_000_000, model.context().intValue());
        assertEquals(131_072, model.output().intValue());
        assertTrue(model.reasoning());
        assertTrue(model.toolCall());
        assertTrue(model.attachment());
        assertEquals(List.of("low", "medium", "xhigh"), model.reasoningEfforts());
        assertEquals(2.0, model.costInput().doubleValue(), 0.0);
        assertEquals(6.0, model.costOutput().doubleValue(), 0.0);
    }

    private static void entry(TarArchiveOutputStream tar, String name, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(bytes.length);
        tar.putArchiveEntry(entry);
        tar.write(bytes);
        tar.closeArchiveEntry();
    }
}