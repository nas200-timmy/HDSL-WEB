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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/// `anthropic-messages` 适配器（docs/chat.md §5/§8.3）。
@NotNullByDefault
public final class AnthropicProtocol implements ChatProtocol {

    @Override
    public String id() {
        return ChatEndpoints.ANTHROPIC_MESSAGES;
    }

    @Override
    public boolean supportsDocuments() {
        return true;
    }

    @Override
    public HttpRequest prepare(ChatEndpoints.Profile profile, ChatRequest request) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("model", request.model());
        body.addProperty("stream", true);
        body.addProperty("max_tokens", request.maxTokens() == null || request.maxTokens() <= 0
                ? 4096 : request.maxTokens());
        if (request.system() != null && !request.system().isBlank()) {
            body.addProperty("system", request.system());
        }
        if (request.temperature() != null) {
            body.addProperty("temperature", request.temperature());
        }

        // Anthropic 要求 user/assistant 交替，且第一条是 user。历史里空回复、
        // 连续同角色都可能出现（停止、错误重试），这里合并/丢弃而不是把 400 丢给用户。
        List<ChatMessage> merged = new ArrayList<>();
        for (ChatMessage message : request.messages()) {
            if ("assistant".equals(message.role())
                    && (message.text() == null || message.text().isBlank())) {
                continue;
            }
            if (merged.isEmpty() && "assistant".equals(message.role())) {
                continue;
            }
            if (!merged.isEmpty() && merged.get(merged.size() - 1).role().equals(message.role())) {
                ChatMessage previous = merged.remove(merged.size() - 1);
                List<Attachment> attachments = new ArrayList<>(previous.attachments());
                attachments.addAll(message.attachments());
                merged.add(new ChatMessage(message.role(),
                        (previous.text() == null ? "" : previous.text())
                                + (message.text() == null ? "" : "\n\n" + message.text()),
                        attachments));
            } else {
                merged.add(message);
            }
        }
        if (merged.isEmpty()) {
            merged.add(new ChatMessage("user", "", List.of()));
        }
        JsonArray messages = new JsonArray();
        for (ChatMessage message : merged) {
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
                .header("anthropic-version", "2023-06-01")
                .header("User-Agent", org.jackhuang.hmcl.Metadata.NAME + "/" + org.jackhuang.hmcl.Metadata.VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        ChatEndpoints.applyAuth(builder, profile);
        return builder.build();
    }

    private static JsonArray content(ChatMessage message) throws IOException {
        JsonArray parts = new JsonArray();
        StringBuilder text = new StringBuilder(message.text() == null ? "" : message.text());
        for (Attachment attachment : message.attachments()) {
            if ("image".equals(attachment.kind())) {
                byte[] bytes = Files.readAllBytes(attachment.path());
                JsonObject block = new JsonObject();
                block.addProperty("type", "image");
                JsonObject source = new JsonObject();
                source.addProperty("type", "base64");
                source.addProperty("media_type", attachment.mime());
                source.addProperty("data", Base64.getEncoder().encodeToString(bytes));
                block.add("source", source);
                parts.add(block);
            } else if ("document".equals(attachment.kind())
                    && "application/pdf".equals(attachment.mime())) {
                byte[] bytes = Files.readAllBytes(attachment.path());
                JsonObject block = new JsonObject();
                block.addProperty("type", "document");
                JsonObject source = new JsonObject();
                source.addProperty("type", "base64");
                source.addProperty("media_type", "application/pdf");
                source.addProperty("data", Base64.getEncoder().encodeToString(bytes));
                block.add("source", source);
                parts.add(block);
            } else {
                OpenAiProtocol.appendText(text, attachment);
            }
        }
        if (!text.isEmpty()) {
            JsonObject block = new JsonObject();
            block.addProperty("type", "text");
            block.addProperty("text", text.toString());
            parts.add(block);
        }
        if (parts.isEmpty()) {
            JsonObject block = new JsonObject();
            block.addProperty("type", "text");
            block.addProperty("text", " ");
            parts.add(block);
        }
        return parts;
    }

    @Override
    public void read(InputStream body, ChatEventSink sink) throws IOException {
        String[] stop = {null};
        boolean[] done = {false};
        SseParser.parse(body, (event, data) -> {
            if (data.isBlank()) {
                return true;
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
            String type = event;
            if (type == null || "message".equals(type)) {
                JsonElement declared = json.get("type");
                type = declared != null && declared.isJsonPrimitive() ? declared.getAsString() : "message";
            }
            switch (type) {
                case "message_start" -> {
                    JsonObject message = objectOf(json.get("message"));
                    JsonObject usage = message == null ? null : objectOf(message.get("usage"));
                    int input = usage == null ? 0 : intOf(usage, "input_tokens", 0);
                    int output = usage == null ? 0 : intOf(usage, "output_tokens", 0);
                    if (input > 0 || output > 0) {
                        sink.usage(input, output);
                    }
                }
                case "content_block_delta" -> {
                    JsonObject delta = objectOf(json.get("delta"));
                    if (delta == null) {
                        return true;
                    }
                    String deltaType = stringOf(delta.get("type"));
                    if ("text_delta".equals(deltaType)) {
                        String text = stringOf(delta.get("text"));
                        if (!text.isEmpty()) {
                            sink.text(text);
                        }
                    } else if ("thinking_delta".equals(deltaType)) {
                        String thinking = stringOf(delta.get("thinking"));
                        if (!thinking.isEmpty()) {
                            sink.reasoning(thinking);
                        }
                    }
                }
                case "message_delta" -> {
                    JsonObject delta = objectOf(json.get("delta"));
                    if (delta != null) {
                        String reason = stringOf(delta.get("stop_reason"));
                        if (!reason.isEmpty()) {
                            stop[0] = reason;
                        }
                    }
                    JsonObject usage = objectOf(json.get("usage"));
                    if (usage != null) {
                        int output = intOf(usage, "output_tokens", 0);
                        if (output > 0) {
                            sink.usage(0, output);
                        }
                    }
                }
                case "message_stop" -> {
                    sink.done(stop[0] == null ? "stop" : stop[0]);
                    done[0] = true;
                    return false;
                }
                case "error" -> {
                    JsonObject error = objectOf(json.get("error"));
                    String message = error == null ? ChatProtocol.errorMessage(data)
                            : stringOf(error.get("message"));
                    String errorType = error == null ? "" : stringOf(error.get("type"));
                    boolean retryable = "overloaded_error".equals(errorType)
                            || "api_error".equals(errorType);
                    sink.error("provider_error", message, retryable);
                    return false;
                }
                case "ping", "content_block_start", "content_block_stop" -> {
                    // Nothing to forward.
                }
                default -> {
                    // Unknown event types are ignored.
                }
            }
            return true;
        });
        if (!done[0]) {
            sink.done(stop[0] == null ? "stop" : stop[0]);
        }
    }

    private static JsonObject objectOf(JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static String stringOf(JsonElement element) {
        return element != null && element.isJsonPrimitive() ? element.getAsString() : "";
    }

    private static int intOf(JsonObject object, String name, int fallback) {
        JsonElement element = object.get(name);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()
                ? element.getAsInt() : fallback;
    }
}