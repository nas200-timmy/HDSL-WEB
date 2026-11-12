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
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.servlet.http.Part;
import org.jackhuang.hmcl.dsh.DshAccount;
import org.jackhuang.hmcl.web.event.EventBus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/// 聊天模块的业务门面：会话/助手、设置、模型发现、附件上传与备份调度。
@NotNullByDefault
public final class ChatService {

    private final ChatStore store;
    private final ChatAttachments attachments;
    private final ChatTurnManager turns;
    private final ChatBackupService backups;
    private final EventBus bus;
    private final Path dataDir;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "chat-maintenance");
                thread.setDaemon(true);
                return thread;
            });
    private volatile ChatSettings.Settings settings;
    private volatile ScheduledFuture<?> backupTask;
    private volatile ScheduledFuture<?> retentionTask;

    public ChatService(ChatStore store, EventBus bus, Path dataDir)
            throws SQLException {
        this.store = store;
        this.bus = bus;
        this.dataDir = dataDir;
        this.attachments = new ChatAttachments(store);
        this.settings = ChatSettings.load(store, dataDir);
        this.turns = new ChatTurnManager(store, attachments, bus, settings);
        this.backups = new ChatBackupService(store, dataDir);
        schedule();
    }

    public ChatStore store() {
        return store;
    }

    public ChatSettings.Settings settings() {
        return settings;
    }

    public ChatTurnManager turns() {
        return turns;
    }

    public ChatAttachments attachments() {
        return attachments;
    }

    public ChatStore.Session requireSession(String id) throws ChatException, SQLException {
        ChatStore.Session session = store.session(id);
        if (session == null) {
            throw new ChatException(404, "session_not_found", "session not found");
        }
        return session;
    }

    // ---------------------------------------------------------- assistants --

    public JsonArray assistants() throws SQLException {
        JsonArray result = new JsonArray();
        for (ChatStore.Assistant assistant : store.assistants()) {
            result.add(assistantJson(assistant));
        }
        return result;
    }

    public JsonObject assistantJson(ChatStore.Assistant assistant) {
        JsonObject json = new JsonObject();
        json.addProperty("id", assistant.id());
        json.addProperty("name", assistant.name());
        json.addProperty("avatar", assistant.avatar());
        json.addProperty("description", assistant.description());
        json.addProperty("systemPrompt", assistant.systemPrompt());
        json.addProperty("openingMessage", assistant.openingMessage());
        addNullable(json, "account", assistant.accountName());
        addNullable(json, "model", assistant.model());
        json.add("temperature", assistant.temperature() == null
                ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(assistant.temperature()));
        json.add("maxTokens", assistant.maxTokens() == null
                ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(assistant.maxTokens()));
        addNullable(json, "reasoningLevel", assistant.reasoningLevel());
        json.add("quickPhrases", parseArray(assistant.quickPhrasesJson()));
        json.addProperty("builtin", assistant.builtin());
        json.addProperty("createdAt", assistant.createdAt());
        json.addProperty("updatedAt", assistant.updatedAt());
        return json;
    }

    public ChatStore.Assistant createAssistant(JsonObject body) throws ChatException, SQLException {
        String name = required(body, "name", "name is required");
        ChatStore.Assistant draft = new ChatStore.Assistant(null, name,
                string(body, "avatar", "💬"), string(body, "description", ""),
                string(body, "systemPrompt", ""), string(body, "openingMessage", ""),
                nullable(body, "account"), nullable(body, "model"),
                doubleValue(body, "temperature"), intValue(body, "maxTokens"),
                nullable(body, "reasoningLevel"), arrayJson(body, "quickPhrases"),
                false, 0, 0);
        return store.createAssistant(draft);
    }

    public ChatStore.Assistant updateAssistant(String id, JsonObject body)
            throws ChatException, SQLException {
        ChatStore.Assistant current = store.assistant(id);
        if (current == null) {
            throw new ChatException(404, "assistant_not_found", "assistant not found");
        }
        ChatStore.Assistant updated = new ChatStore.Assistant(id,
                body.has("name") ? string(body, "name", current.name()) : current.name(),
                body.has("avatar") ? string(body, "avatar", current.avatar()) : current.avatar(),
                body.has("description") ? string(body, "description", current.description()) : current.description(),
                body.has("systemPrompt") ? string(body, "systemPrompt", current.systemPrompt()) : current.systemPrompt(),
                body.has("openingMessage") ? string(body, "openingMessage", current.openingMessage()) : current.openingMessage(),
                body.has("account") ? nullable(body, "account") : current.accountName(),
                body.has("model") ? nullable(body, "model") : current.model(),
                body.has("temperature") ? doubleValue(body, "temperature") : current.temperature(),
                body.has("maxTokens") ? intValue(body, "maxTokens") : current.maxTokens(),
                body.has("reasoningLevel") ? nullable(body, "reasoningLevel") : current.reasoningLevel(),
                body.has("quickPhrases") ? arrayJson(body, "quickPhrases") : current.quickPhrasesJson(),
                current.builtin(), current.createdAt(), current.updatedAt());
        return store.updateAssistant(updated);
    }

    public void deleteAssistant(String id) throws ChatException, SQLException {
        ChatStore.Assistant assistant = store.assistant(id);
        if (assistant == null) {
            throw new ChatException(404, "assistant_not_found", "assistant not found");
        }
        if (assistant.builtin()) {
            throw new ChatException(409, "builtin_assistant", "the built-in assistant cannot be deleted");
        }
        store.deleteAssistant(id);
        broadcast("chat-assistants-changed", null);
    }

    public ChatStore.Assistant duplicateAssistant(String id) throws ChatException, SQLException {
        if (store.assistant(id) == null) {
            throw new ChatException(404, "assistant_not_found", "assistant not found");
        }
        ChatStore.Assistant copy = store.duplicateAssistant(id);
        broadcast("chat-assistants-changed", null);
        return copy;
    }

    // ------------------------------------------------------------ sessions --

    public JsonArray sessions(@Nullable String assistantId) throws SQLException {
        JsonArray result = new JsonArray();
        for (ChatStore.SessionSummary summary : store.sessions(assistantId)) {
            JsonObject json = new JsonObject();
            json.addProperty("id", summary.id());
            json.addProperty("assistantId", summary.assistantId());
            json.addProperty("title", summary.title());
            addNullable(json, "model", summary.model());
            addNullable(json, "account", summary.accountName());
            json.addProperty("createdAt", summary.createdAt());
            json.addProperty("updatedAt", summary.updatedAt());
            json.addProperty("messageCount", summary.messageCount());
            addNullable(json, "lastMessage", summary.lastMessage());
            result.add(json);
        }
        return result;
    }

    public JsonObject sessionJson(String id) throws ChatException, SQLException {
        ChatStore.Session session = requireSession(id);
        JsonObject json = new JsonObject();
        json.addProperty("id", session.id());
        json.addProperty("assistantId", session.assistantId());
        json.addProperty("title", session.title());
        addNullable(json, "model", session.model());
        addNullable(json, "account", session.accountName());
        json.addProperty("createdAt", session.createdAt());
        json.addProperty("updatedAt", session.updatedAt());
        json.addProperty("streaming", turns.busy(id));
        JsonArray nodes = new JsonArray();
        for (ChatStore.Node node : session.nodes()) {
            nodes.add(nodeJson(node));
        }
        json.add("nodes", nodes);
        return json;
    }

    public JsonObject createSession(JsonObject body) throws ChatException, SQLException {
        String assistantId = string(body, "assistantId", "default");
        ChatStore.Assistant assistant = store.assistant(assistantId);
        if (assistant == null) {
            throw new ChatException(404, "assistant_not_found", "assistant not found");
        }
        String title = string(body, "title", "新会话");
        String model = firstNonBlank(nullable(body, "model"), assistant.model(), settings.defaultModel());
        String account = firstNonBlank(nullable(body, "account"), assistant.accountName(),
                settings.defaultAccount());
        ChatStore.Session session = store.createSession(assistantId, title, model, account);
        if (assistant.openingMessage() != null && !assistant.openingMessage().isBlank()) {
            ChatStore.Node node = store.beginAssistantNode(session.id(), model);
            ChatStore.Message message = node.active();
            if (message != null) {
                store.updateMessage(message.id(), assistant.openingMessage(), "", "done", null, false);
            }
        }
        broadcast("chat-sessions-changed", session.id());
        return sessionJson(session.id());
    }

    public JsonObject updateSession(String id, JsonObject body) throws ChatException, SQLException {
        requireSession(id);
        String title = body.has("title") ? string(body, "title", "") : null;
        String model = body.has("model") ? string(body, "model", "") : null;
        String account = body.has("account") ? string(body, "account", "") : null;
        store.updateSession(id, title, model, account);
        broadcast("chat-sessions-changed", id);
        return sessionJson(id);
    }

    public void deleteSession(String id) throws ChatException, SQLException, IOException {
        ChatStore.Session session = store.session(id);
        if (session == null) {
            throw new ChatException(404, "session_not_found", "session not found");
        }
        if (turns.busy(id)) {
            throw new ChatException(409, "chat_busy", "stop the running reply before deleting the session");
        }
        store.deleteSession(id);
        attachments.pruneOrphans();
        broadcast("chat-sessions-changed", id);
    }

    public JsonObject switchVariant(String sessionId, String nodeId, String messageId)
            throws ChatException, SQLException {
        ChatStore.Session session = requireSession(sessionId);
        ChatStore.Node node = null;
        for (ChatStore.Node candidate : session.nodes()) {
            if (candidate.id().equals(nodeId)) {
                node = candidate;
                break;
            }
        }
        if (node == null) {
            throw new ChatException(404, "node_not_found", "node not found");
        }
        boolean found = false;
        for (ChatStore.Message variant : node.variants()) {
            if (variant.id().equals(messageId)) {
                found = true;
                break;
            }
        }
        if (!found) {
            throw new ChatException(404, "message_not_found", "message not found");
        }
        store.setActiveVariant(nodeId, messageId);
        store.touchSession(sessionId);
        broadcast("chat-sessions-changed", sessionId);
        return sessionJson(sessionId);
    }

    public JsonObject deleteNode(String sessionId, String nodeId) throws ChatException, SQLException, IOException {
        requireSession(sessionId);
        if (turns.busy(sessionId)) {
            throw new ChatException(409, "chat_busy", "stop the running reply first");
        }
        store.deleteNodeAndAfter(sessionId, nodeId);
        attachments.pruneOrphans();
        broadcast("chat-sessions-changed", sessionId);
        return sessionJson(sessionId);
    }

    // --------------------------------------------------------- attachments --

    public JsonObject uploadAttachment(String sessionId, Part part)
            throws ChatException, IOException, SQLException {
        requireSession(sessionId);
        List<ChatStore.Attachment> existing = store.attachmentsForSession(sessionId);
        if (existing.size() >= 200) {
            throw new ChatException(413, "attachment_quota",
                    "this session already holds too many attachments");
        }
        ChatAttachments.Stored stored;
        try {
            stored = attachments.ingest(sessionId, part);
        } catch (ChatAttachments.Refused e) {
            throw new ChatException(415, "attachment_refused", e.getMessage());
        }
        ChatStore.Attachment attachment = store.addAttachment(sessionId, null, stored.name(),
                stored.mime(), stored.kind(), stored.size(), stored.sha256(), stored.path(),
                stored.textPath(), stored.status(), stored.error());
        return attachmentJson(attachment, true);
    }

    public ChatStore.Attachment attachment(String id) throws SQLException {
        return store.attachment(id);
    }

    public JsonObject attachmentJson(ChatStore.Attachment attachment, boolean includeText) {
        JsonObject json = new JsonObject();
        json.addProperty("id", attachment.id());
        json.addProperty("name", attachment.name());
        json.addProperty("mime", attachment.mime());
        json.addProperty("kind", attachment.kind());
        json.addProperty("size", attachment.size());
        json.addProperty("extractStatus", attachment.extractStatus());
        addNullable(json, "extractError", attachment.extractError());
        json.addProperty("url", "/api/chat/attachments/" + attachment.id());
        if (includeText) {
            String text = attachments.loadText(attachment);
            if (text != null && text.length() <= 2000) {
                json.addProperty("preview", text);
            }
        }
        return json;
    }

    // -------------------------------------------------------- model/endpoint --

    public JsonArray accounts() throws SQLException {
        JsonArray result = new JsonArray();
        for (DshAccount account : ChatEndpoints.accounts()) {
            if (!account.carriesAKey()) {
                continue;
            }
            JsonObject json = ChatEndpoints.profileJson(store, account, settings);
            ChatEndpoints.Profile profile = ChatEndpoints.profile(store, account.key());
            if (profile != null) {
                JsonArray models = new JsonArray();
                for (String model : profile.models()) {
                    models.add(modelJson(account.key(), model, profile.protocol()));
                }
                json.add("modelList", models);
            }
            result.add(json);
        }
        return result;
    }

    public JsonObject models(String accountName) throws ChatException, SQLException {
        DshAccount account = ChatEndpoints.account(accountName);
        if (account == null) {
            throw new ChatException(404, "account_not_found", "account not found");
        }
        ChatEndpoints.Profile profile = ChatEndpoints.profile(store, account.key());
        JsonObject json = new JsonObject();
        json.addProperty("account", account.key());
        json.addProperty("protocol", profile == null ? ChatEndpoints.defaultProtocol(account)
                : profile.protocol());
        JsonArray models = new JsonArray();
        if (profile != null) {
            for (String model : profile.models()) {
                models.add(modelJson(account.key(), model, profile.protocol()));
            }
        }
        if (models.isEmpty() && account.model() != null && !account.model().isBlank()) {
            models.add(modelJson(account.key(), account.model(), profile == null
                    ? ChatEndpoints.defaultProtocol(account) : profile.protocol()));
        }
        json.add("models", models);
        return json;
    }

    private JsonObject modelJson(String accountName, String model, String protocol) throws SQLException {
        ChatCapabilities.Capabilities capability =
                ChatCapabilities.resolve(store, accountName, model, protocol);
        JsonObject json = new JsonObject();
        json.addProperty("id", model);
        JsonObject caps = new JsonObject();
        caps.addProperty("vision", capability.vision());
        caps.addProperty("files", capability.files());
        caps.addProperty("reasoning", capability.reasoning());
        caps.addProperty("tools", capability.tools());
        caps.addProperty("maxContext", capability.maxContext());
        caps.addProperty("maxOutput", capability.maxOutput());
        caps.addProperty("source", capability.source());
        json.add("capabilities", caps);
        return json;
    }

    public JsonObject saveAccountProfile(String accountName, JsonObject body)
            throws ChatException, SQLException {
        DshAccount account = ChatEndpoints.account(accountName);
        if (account == null) {
            throw new ChatException(404, "account_not_found", "account not found");
        }
        Map<String, String> headers = null;
        if (body.has("extraHeaders")) {
            headers = new java.util.LinkedHashMap<>();
            JsonElement element = body.get("extraHeaders");
            if (element.isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                    if (entry.getValue().isJsonPrimitive()) {
                        headers.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }
            }
        }
        try {
            ChatEndpoints.saveProfile(store, account,
                    body.has("protocol") ? nullable(body, "protocol") : null,
                    body.has("base") ? nullable(body, "base") : null,
                    body.has("modelsPath") ? nullable(body, "modelsPath") : null,
                    body.has("chatPath") ? nullable(body, "chatPath") : null,
                    body.has("authStyle") ? nullable(body, "authStyle") : null,
                    headers);
        } catch (IllegalArgumentException e) {
            throw new ChatException(400, "invalid_endpoint", e.getMessage());
        }
        return ChatEndpoints.profileJson(store, account, settings);
    }

    public JsonObject saveCapability(String accountName, JsonObject body)
            throws ChatException, SQLException {
        DshAccount account = ChatEndpoints.account(accountName);
        if (account == null) {
            throw new ChatException(404, "account_not_found", "account not found");
        }
        String model = required(body, "model", "model is required");
        if (body.has("reset") && body.get("reset").getAsBoolean()) {
            store.deleteCapability(account.key(), model);
            ChatEndpoints.Profile profile = ChatEndpoints.profile(store, account.key());
            return modelJson(account.key(), model, profile == null
                    ? ChatEndpoints.defaultProtocol(account) : profile.protocol());
        }
        ChatEndpoints.Profile profile = ChatEndpoints.profile(store, account.key());
        ChatCapabilities.Capabilities current = ChatCapabilities.resolve(store, account.key(), model,
                profile == null ? ChatEndpoints.defaultProtocol(account) : profile.protocol());
        ChatStore.Capability capability = new ChatStore.Capability(account.key(), model,
                body.has("vision") ? body.get("vision").getAsBoolean() : current.vision(),
                body.has("files") ? body.get("files").getAsBoolean() : current.files(),
                body.has("reasoning") ? body.get("reasoning").getAsBoolean() : current.reasoning(),
                body.has("tools") ? body.get("tools").getAsBoolean() : current.tools(),
                body.has("maxContext") ? body.get("maxContext").getAsInt() : current.maxContext(),
                body.has("maxOutput") ? body.get("maxOutput").getAsInt() : current.maxOutput(),
                "manual", System.currentTimeMillis());
        store.saveCapability(capability);
        return modelJson(account.key(), model, profile == null
                ? ChatEndpoints.defaultProtocol(account) : profile.protocol());
    }

    public ChatEndpoints.Probe probe(String accountName) throws ChatException, SQLException, IOException {
        try {
            return ChatEndpoints.probe(store, accountName, settings);
        } catch (IllegalArgumentException e) {
            throw new ChatException(404, "account_not_found", e.getMessage());
        }
    }

    public List<String> refreshModels(String accountName) throws ChatException, SQLException, IOException {
        try {
            return ChatEndpoints.refreshModels(store, accountName, settings);
        } catch (IllegalArgumentException e) {
            throw new ChatException(404, "account_not_found", e.getMessage());
        }
    }

    // ------------------------------------------------------------- settings --

    public JsonObject settingsJson() throws IOException {
        ChatSettings.Settings current = settings;
        JsonObject json = new JsonObject();
        json.addProperty("retention", current.retention());
        json.addProperty("retentionDays", current.retentionDays());
        json.addProperty("backupEnabled", current.backupEnabled());
        json.addProperty("backupTime", current.backupTime());
        json.addProperty("backupKeep", current.backupKeep());
        json.addProperty("backupDir", current.backupDir());
        json.addProperty("privateEndpoints", current.privateEndpoints());
        JsonArray allow = new JsonArray();
        current.privateAllowlist().forEach(allow::add);
        json.add("privateAllowlist", allow);
        json.addProperty("longTextTokens", current.longTextTokens());
        json.addProperty("dailyTokenLimit", current.dailyTokenLimit());
        json.addProperty("streamFlushMs", current.streamFlushMs());
        addNullable(json, "defaultAccount", current.defaultAccount());
        addNullable(json, "defaultModel", current.defaultModel());
        JsonArray retentionOptions = new JsonArray();
        for (String option : List.of("forever", "30d", "90d", "180d", "365d", "custom")) {
            retentionOptions.add(option);
        }
        json.add("retentionOptions", retentionOptions);
        json.add("backups", backupsJson());
        return json;
    }

    public JsonObject updateSettings(JsonObject body) throws ChatException, SQLException, IOException {
        ChatSettings.Settings merged;
        try {
            merged = ChatSettings.merge(settings,
                    body, ChatSettings.defaults(dataDir));
        } catch (IllegalArgumentException e) {
            throw new ChatException(400, "invalid_settings", e.getMessage());
        }
        ChatSettings.save(store, merged);
        settings = merged;
        turns.setSettings(merged);
        schedule();
        broadcast("chat-settings-changed", null);
        return settingsJson();
    }

    // -------------------------------------------------------------- backups --

    private JsonArray backupsJson() throws IOException {
        JsonArray result = new JsonArray();
        for (ChatBackupService.BackupEntry entry : backups.list(settings)) {
            JsonObject json = new JsonObject();
            json.addProperty("name", entry.name());
            json.addProperty("path", entry.path());
            json.addProperty("createdAt", entry.createdAt());
            json.addProperty("bytes", entry.bytes());
            json.addProperty("sessions", entry.sessions());
            json.addProperty("messages", entry.messages());
            json.addProperty("attachments", entry.attachments());
            json.addProperty("complete", entry.complete());
            addNullable(json, "error", entry.error());
            result.add(json);
        }
        return result;
    }

    public JsonObject backupNow() throws ChatException, IOException, SQLException {
        try {
            ChatBackupService.BackupEntry entry = backups.backupNow(settings);
            JsonObject json = new JsonObject();
            json.addProperty("name", entry.name());
            json.addProperty("path", entry.path());
            json.addProperty("createdAt", entry.createdAt());
            json.addProperty("bytes", entry.bytes());
            json.addProperty("sessions", entry.sessions());
            json.addProperty("messages", entry.messages());
            json.addProperty("attachments", entry.attachments());
            json.addProperty("complete", entry.complete());
            broadcast("chat-backups-changed", null);
            return json;
        } catch (IOException | SQLException e) {
            throw new ChatException(500, "backup_failed", e.getMessage());
        }
    }

    public JsonArray backups() throws IOException {
        return backupsJson();
    }

    // ----------------------------------------------------------- maintenance --

    private void schedule() {
        cancel(backupTask);
        cancel(retentionTask);
        ChatSettings.Settings current = settings;
        if (current.backupEnabled()) {
            LocalTime time = current.backupLocalTime() == null ? LocalTime.of(3, 17)
                    : current.backupLocalTime();
            backupTask = scheduler.schedule(() -> {
                try {
                    backups.backupNow(settings);
                    broadcast("chat-backups-changed", null);
                } catch (Exception e) {
                    org.jackhuang.hmcl.util.logging.Logger.LOG.warning("Scheduled chat backup failed", e);
                } finally {
                    schedule();
                }
            }, delayUntil(time), TimeUnit.MILLISECONDS);
        }
        retentionTask = scheduler.schedule(() -> {
            try {
                retention();
            } catch (Exception e) {
                org.jackhuang.hmcl.util.logging.Logger.LOG.warning("Chat retention sweep failed", e);
            } finally {
                schedule();
            }
        }, delayUntil(LocalTime.of(3, 47)), TimeUnit.MILLISECONDS);
    }

    private static long delayUntil(LocalTime time) {
        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime next = now.with(time);
        if (!next.isAfter(now)) {
            next = next.plusDays(1);
        }
        long millis = Duration.between(now, next).toMillis();
        return Math.max(60_000, Math.min(millis, Duration.ofDays(1).toMillis()));
    }

    private static void cancel(@Nullable ScheduledFuture<?> task) {
        if (task != null) {
            task.cancel(false);
        }
    }

    private void retention() throws SQLException, IOException {
        ChatSettings.Settings current = settings;
        if ("forever".equals(current.retention())) {
            return;
        }
        long cutoff = current.retentionCutoffMillis();
        for (String id : store.sessionIdsBefore(cutoff)) {
            store.deleteSession(id);
        }
        attachments.pruneOrphans();
    }

    private void broadcast(String type, @Nullable String sessionId) {
        JsonObject event = new JsonObject();
        event.addProperty("type", type);
        if (sessionId != null) {
            event.addProperty("sessionId", sessionId);
        }
        bus.publish(sessionId == null ? "chat" : "chat:" + sessionId, event);
        if (sessionId != null) {
            bus.publish("chat", event);
        }
    }

    // --------------------------------------------------------------- close --

    public void close() {
        cancel(backupTask);
        cancel(retentionTask);
        scheduler.shutdownNow();
        turns.close();
        attachments.close();
        store.close();
    }

    // ------------------------------------------------------------- helpers --

    private JsonObject nodeJson(ChatStore.Node node) {
        JsonObject json = new JsonObject();
        json.addProperty("id", node.id());
        json.addProperty("ordinal", node.ordinal());
        json.addProperty("role", node.role());
        addNullable(json, "activeVariantId", node.activeVariantId());
        json.addProperty("createdAt", node.createdAt());
        JsonArray variants = new JsonArray();
        for (ChatStore.Message message : node.variants()) {
            variants.add(messageJson(message));
        }
        json.add("variants", variants);
        return json;
    }

    private JsonObject messageJson(ChatStore.Message message) {
        JsonObject json = new JsonObject();
        json.addProperty("id", message.id());
        json.addProperty("nodeId", message.nodeId());
        json.addProperty("variantIndex", message.variantIndex());
        json.addProperty("content", message.content() == null ? "" : message.content());
        json.addProperty("reasoning", message.reasoning() == null ? "" : message.reasoning());
        json.addProperty("status", message.status());
        addNullable(json, "model", message.model());
        json.addProperty("createdAt", message.createdAt());
        json.add("editedAt", message.editedAt() == null
                ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(message.editedAt()));
        if (message.usageJson() != null) {
            try {
                json.add("usage", JsonParser.parseString(message.usageJson()));
            } catch (RuntimeException ignored) {
                // Ignore a malformed usage blob.
            }
        }
        JsonArray attachments = new JsonArray();
        for (ChatStore.Attachment attachment : message.attachments()) {
            attachments.add(attachmentJson(attachment, false));
        }
        json.add("attachments", attachments);
        return json;
    }

    private static JsonArray parseArray(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return new JsonArray();
        }
        try {
            JsonElement parsed = JsonParser.parseString(json);
            return parsed.isJsonArray() ? parsed.getAsJsonArray() : new JsonArray();
        } catch (RuntimeException e) {
            return new JsonArray();
        }
    }

    private static String arrayJson(JsonObject body, String name) {
        JsonElement element = body.get(name);
        return element != null && element.isJsonArray() ? element.toString() : "[]";
    }

    private static String required(JsonObject body, String name, String message) throws ChatException {
        String value = nullable(body, name);
        if (value == null || value.isBlank()) {
            throw new ChatException(400, "invalid_request", message);
        }
        return value.trim();
    }

    private static String string(JsonObject body, String name, String fallback) {
        String value = nullable(body, name);
        return value == null ? fallback : value;
    }

    private static @Nullable String nullable(JsonObject body, String name) {
        JsonElement element = body.get(name);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        String value = element.getAsString();
        return value.isBlank() ? null : value;
    }

    private static @Nullable Double doubleValue(JsonObject body, String name) {
        JsonElement element = body.get(name);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()
                ? element.getAsDouble() : null;
    }

    private static @Nullable Integer intValue(JsonObject body, String name) {
        JsonElement element = body.get(name);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()
                ? element.getAsInt() : null;
    }

    private static void addNullable(JsonObject json, String name, @Nullable String value) {
        json.add(name, value == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(value));
    }

    private static @Nullable String firstNonBlank(@Nullable String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}