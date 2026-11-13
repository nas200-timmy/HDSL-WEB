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
package org.jackhuang.hmcl.web.chat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// 思考挡位要落到各家自己的参数上：OpenAI 是 reasoning_effort，百炼是
/// enable_thinking/thinking_budget，智谱是 thinking.type，Anthropic 是
/// thinking.budget_tokens（还必须 max_tokens > budget 且不能带 temperature）。
class ChatReasoningTest {

    @Test
    void genericOpenAiEndpointGetsReasoningEffort() throws Exception {
        JsonObject body = capture("v1", ChatEndpoints.OPENAI_COMPLETIONS, "gpt-5.4-mini", "high");
        assertEquals("high", body.get("reasoning_effort").getAsString());
    }

    @Test
    void dashScopeGetsEnableThinkingAndBudget() throws Exception {
        JsonObject body = capture("dashscope/compatible-mode/v1",
                ChatEndpoints.OPENAI_COMPLETIONS, "qwen3.8-flash", "medium");
        assertTrue(body.get("enable_thinking").getAsBoolean());
        assertEquals(4096, body.get("thinking_budget").getAsInt());
        assertFalse(body.has("reasoning_effort"));
    }

    @Test
    void zhipuGetsThinkingObject() throws Exception {
        JsonObject on = capture("bigmodel/api/paas/v4",
                ChatEndpoints.OPENAI_COMPLETIONS, "glm-5.2", "low");
        assertEquals("enabled", on.getAsJsonObject("thinking").get("type").getAsString());

        JsonObject off = capture("bigmodel/api/paas/v4",
                ChatEndpoints.OPENAI_COMPLETIONS, "glm-5.2", "off");
        assertEquals("disabled", off.getAsJsonObject("thinking").get("type").getAsString());
    }

    @Test
    void offDoesNotPushUnknownFieldsToGenericEndpoints() throws Exception {
        JsonObject body = capture("v1", ChatEndpoints.OPENAI_COMPLETIONS, "qwen3.8-flash", "off");
        assertFalse(body.has("reasoning_effort"));
        assertFalse(body.has("enable_thinking"));
        assertFalse(body.has("thinking"));
    }

    @Test
    void anthropicThinkingCarriesBudgetAndDropsTemperature() throws Exception {
        JsonObject body = capture("v1", ChatEndpoints.ANTHROPIC_MESSAGES,
                "claude-sonnet-4-5", "high", 42.0, 2_000);
        JsonObject thinking = body.getAsJsonObject("thinking");
        assertEquals("enabled", thinking.get("type").getAsString());
        int budget = thinking.get("budget_tokens").getAsInt();
        assertEquals(16_384, budget);
        assertTrue(body.get("max_tokens").getAsInt() > budget);
        assertFalse(body.has("temperature"));

        JsonObject off = capture("v1", ChatEndpoints.ANTHROPIC_MESSAGES,
                "claude-sonnet-4-5", "off", 42.0, 2_000);
        assertFalse(off.has("thinking"));
        assertEquals(42.0, off.get("temperature").getAsDouble());
    }

    @Test
    void sessionLevelPersistsAndCanBeCleared() throws Exception {
        java.nio.file.Path dir = Files.createTempDirectory("hdsl-session-level");
        ChatStore store = new ChatStore(dir);
        try {
            String assistantId = store.assistants().get(0).id();
            ChatStore.Session session = store.createSession(assistantId, "t", null, null);
            assertNull(session.reasoningLevel());
            store.updateSession(session.id(), null, null, null, "high");
            assertEquals("high", store.session(session.id()).reasoningLevel());
            store.updateSession(session.id(), null, null, null, "");
            assertNull(store.session(session.id()).reasoningLevel());
        } finally {
            store.close();
        }
    }

    private static JsonObject capture(String pathPrefix, String protocolId, String model,
                                      String level) throws Exception {
        return capture(pathPrefix, protocolId, model, level, null, 512);
    }

    private static JsonObject capture(String pathPrefix, String protocolId, String model,
                                      String level, Double temperature, int maxTokens) throws Exception {
        AtomicReference<String> captured = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            // 本地假端点 + 在路径里带上厂商标记：prepare() 只按 base 里的关键字选参数
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/" + pathPrefix;
            ChatEndpoints.Profile profile = new ChatEndpoints.Profile(
                    "test", protocolId, base,
                    "/models", ChatEndpoints.ANTHROPIC_MESSAGES.equals(protocolId)
                            ? "/messages" : "/chat/completions",
                    "bearer", Map.of(), List.of(model), null, null);
            ChatProtocol protocol = ChatEndpoints.ANTHROPIC_MESSAGES.equals(protocolId)
                    ? new AnthropicProtocol() : new OpenAiProtocol();
            ChatProtocol.ChatRequest request = new ChatProtocol.ChatRequest(
                    model, null,
                    List.of(new ChatProtocol.ChatMessage("user", "hi", List.of())),
                    temperature, maxTokens, level);
            HttpClient.newHttpClient().send(protocol.prepare(profile, request),
                    HttpResponse.BodyHandlers.discarding());
            return JsonParser.parseString(captured.get()).getAsJsonObject();
        } finally {
            server.stop(0);
        }
    }
}