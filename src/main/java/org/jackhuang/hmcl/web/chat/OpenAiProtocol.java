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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Base64;

/// `openai-completions` 适配器（docs/chat.md §5/§8.3）。
@NotNullByDefault
public final class OpenAiProtocol implements ChatProtocol {

    @Override
    public String id() {
        return ChatEndpoints.OPENAI_COMPLETIONS;
    }

    @Override
    public boolean supportsDocuments() {
        return false;
    }

    @Override
    public HttpRequest prepare(ChatEndpoints.Profile profile, ChatRequest request) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("model", request.model());
        body.addProperty("stream", true);
        JsonObject streamOptions = new JsonObject();
        streamOptions.addProperty("include_usage", true);
        body.add("stream_options", streamOptions);
        if (request.temperature() != null) {
            body.addProperty("temperature", request.temperature());
        }
        if (request.maxTokens() != null && request.maxTokens() > 0) {
            if (isReasoningModel(request.model())) {
                body.addProperty("max_completion_tokens", request.maxTokens());
            } else {
                body.addProperty("max_tokens", request.maxTokens());
            }
        }
        if (request.reasoningLevel() != null && !request.reasoningLevel().isBlank()
                && !"off".equalsIgnoreCase(request.reasoningLevel())
                && !"none".equalsIgnoreCase(request.reasoningLevel())) {
            body.addProperty("reasoning_effort", request.reasoningLevel());
        }

        JsonArray messages = new JsonArray();
        if (request.system() != null && !request.system().isBlank()) {
            JsonObject system = new JsonObject();
            system.addProperty("role", "system");
            system.addProperty("content", request.system());
            messages.add(system);
        }
        for (ChatMessage message : request.messages()) {
            JsonObject row = new JsonObject();
            row.addProperty("role", message.role());
            row.add("content", content(message));
            messages.add(row);
        }
        body.add("messages", messages);

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(profile.chatUri().toString()))
                .timeout(Duration.ofMinutes(10))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("User-Agent", org.jackhuang.hmcl.Metadata.NAME + "/" + org.jackhuang.hmcl.Metadata.VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        ChatEndpoints.applyAuth(builder, profile);
        return builder.build();
    }

    private static boolean isReasoningModel(String model) {
        String id = model == null ? "" : model.toLowerCase(java.util.Locale.ROOT);
        return id.startsWith("o1") || id.startsWith("o3") || id.startsWith("o4")
                || id.startsWith("gpt-5");
    }

    private static JsonElement content(ChatMessage message) throws IOException {
        StringBuilder text = new StringBuilder(message.text() == null ? "" : message.text());
        JsonArray images = new JsonArray();
        for (Attachment attachment : message.attachments()) {
            if ("image".equals(attachment.kind())) {
                byte[] bytes = Files.readAllBytes(attachment.path());
                String data = Base64.getEncoder().encodeToString(bytes);
                JsonObject image = new JsonObject();
                image.addProperty("type", "image_url");
                JsonObject url = new JsonObject();
                url.addProperty("url", "data:" + attachment.mime() + ";base64," + data);
                image.add("image_url", url);
                images.add(image);
            } else {
                appendText(text, attachment);
            }
        }
        if (images.isEmpty()) {
            return new com.google.gson.JsonPrimitive(text.toString());
        }
        JsonArray parts = new JsonArray();
        if (!text.isEmpty()) {
            JsonObject part = new JsonObject();
            part.addProperty("type", "text");
            part.addProperty("text", text.toString());
            parts.add(part);
        }
        parts.addAll(images);
        return parts;
    }

    static void appendText(StringBuilder text, Attachment attachment) {
        if (!text.isEmpty()) {
            text.append("\n\n");
        }
        text.append("[附件: ").append(attachment.name()).append("]");
        String extracted = attachment.text();
        if (extracted == null || extracted.isBlank()) {
            text.append("\n（无法从该附件中抽取文本）");
        } else {
            text.append('\n').append(extracted);
        }
    }

    @Override
    public void read(InputStream body, ChatEventSink sink) throws IOException {
        String[] finish = {null};
        boolean[] done = {false};
        SseParser.parse(body, (event, data) -> {
            if ("[DONE]".equals(data.trim())) {
                sink.done(finish[0] == null ? "stop" : finish[0]);
                done[0] = true;
                return false;
            }
            JsonObject json;
            try {
                JsonElement parsed = JsonParser.parseString(data);
                if (!parsed.isJsonObject()) {
                    return true;
                }
                json = parsed.getAsJsonObject();
            } catch (RuntimeException e) {
                return true;
            }
            if (json.has("error") && !json.get("error").isJsonNull()) {
                sink.error("provider_error", ChatProtocol.errorMessage(data), true);
                return false;
            }
            JsonElement usage = json.get("usage");
            if (usage != null && usage.isJsonObject()) {
                JsonObject u = usage.getAsJsonObject();
                int input = intOf(u, "prompt_tokens", intOf(u, "input_tokens", 0));
                int output = intOf(u, "completion_tokens", intOf(u, "output_tokens", 0));
                if (input > 0 || output > 0) {
                    sink.usage(input, output);
                }
            }
            JsonElement choices = json.get("choices");
            if (choices == null || !choices.isJsonArray() || ((JsonArray) choices).isEmpty()) {
                return true;
            }
            JsonElement first = choices.getAsJsonArray().get(0);
            if (!first.isJsonObject()) {
                return true;
            }
            JsonObject choice = first.getAsJsonObject();
            JsonElement finishReason = choice.get("finish_reason");
            if (finishReason != null && finishReason.isJsonPrimitive()
                    && !finishReason.getAsString().isBlank()
                    && !"null".equals(finishReason.getAsString())) {
                finish[0] = finishReason.getAsString();
            }
            JsonElement delta = choice.get("delta");
            if (delta != null && delta.isJsonObject()) {
                JsonObject d = delta.getAsJsonObject();
                String reasoning = textOf(d.get("reasoning_content"));
                if (reasoning.isEmpty()) {
                    reasoning = textOf(d.get("reasoning"));
                }
                if (!reasoning.isEmpty()) {
                    sink.reasoning(reasoning);
                }
                String content = textOf(d.get("content"));
                if (!content.isEmpty()) {
                    sink.text(content);
                }
            }
            return true;
        });
        if (!done[0]) {
            sink.done(finish[0] == null ? "stop" : finish[0]);
        }
    }

    private static int intOf(JsonObject object, String name, int fallback) {
        JsonElement element = object.get(name);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()
                ? element.getAsInt() : fallback;
    }

    /// OpenAI 的 delta.content 通常是字符串，但兼容网关有时给 parts 数组。
    private static String textOf(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return "";
        }
        if (element.isJsonPrimitive()) {
            return element.getAsString();
        }
        if (element.isJsonArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonElement part : element.getAsJsonArray()) {
                if (part.isJsonObject()) {
                    String value = textOf(part.getAsJsonObject().get("text"));
                    text.append(value);
                }
            }
            return text.toString();
        }
        return "";
    }
}