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
import org.jackhuang.hmcl.dsh.DshAccount;
import org.jackhuang.hmcl.web.event.EventBus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/// 每个会话同时只有一个 active turn（docs/chat.md §7）。
///
/// HTTP 只负责开始/停止；token 流从这里经 `/ws` 的 `chat:<id>` topic 推给浏览器。
/// 落库是 1 秒一次的快照，最终文本在 done/stopped/error 时写一次。
@NotNullByDefault
public final class ChatTurnManager {

    public record Outcome(
            String turnId,
            ChatStore.Node userNode,
            ChatStore.Message userMessage,
            ChatStore.Node assistantNode,
            ChatStore.Message assistantMessage) {
    }

    private record RecentTurn(long at, Outcome outcome) {
    }

    private record Resolved(
            String model, String account, ChatEndpoints.Profile profile,
            ChatProtocol protocol, ChatCapabilities.Capabilities capability) {
    }

    private final ChatStore store;
    private final ChatAttachments attachments;
    private final EventBus bus;
    private final Map<String, ChatProtocol> protocols = Map.of(
            ChatEndpoints.OPENAI_COMPLETIONS, new OpenAiProtocol(),
            ChatEndpoints.ANTHROPIC_MESSAGES, new AnthropicProtocol());
    private final ExecutorService workers = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "chat-turn");
        thread.setDaemon(true);
        return thread;
    });
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final Map<String, ActiveTurn> bySession = new ConcurrentHashMap<>();
    private final Map<String, ActiveTurn> byId = new ConcurrentHashMap<>();
    private final Map<String, RecentTurn> recent = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong dailyTokens =
            new java.util.concurrent.atomic.AtomicLong();
    private volatile LocalDate dailyDate = LocalDate.now();
    private volatile ChatSettings.Settings settings;

    public ChatTurnManager(ChatStore store, ChatAttachments attachments, EventBus bus,
                           ChatSettings.Settings settings) {
        this.store = store;
        this.attachments = attachments;
        this.bus = bus;
        this.settings = settings;
    }

    public void setSettings(ChatSettings.Settings settings) {
        this.settings = settings;
    }

    public boolean busy(String sessionId) {
        return bySession.containsKey(sessionId);
    }

    public @Nullable String activeTurn(String sessionId) {
        ActiveTurn turn = bySession.get(sessionId);
        return turn == null ? null : turn.id;
    }

    // --------------------------------------------------------------- send --

    public Outcome send(String sessionId, String text, List<String> attachmentIds,
                        @Nullable String model, @Nullable String account,
                        @Nullable String requestId)
            throws ChatException, SQLException, IOException {
        ChatStore.Session session = requireSession(sessionId);
        String key = requestId == null || requestId.isBlank() ? null : sessionId + "|" + requestId;
        if (key != null) {
            RecentTurn previous = recent.get(key);
            if (previous != null && System.currentTimeMillis() - previous.at() < 10 * 60 * 1000) {
                return previous.outcome();
            }
        }
        synchronized (this) {
            if (bySession.containsKey(sessionId)) {
                throw new ChatException(409, "chat_busy", "this session is already streaming; stop it first");
            }
            checkDailyLimit();
            Resolved resolved = resolve(session, model, account);
            List<String> ids = new ArrayList<>(attachmentIds);
            String content = text == null ? "" : text;
            String titleSource = content;
            if (content.isBlank() && ids.isEmpty()) {
                throw new ChatException(400, "empty_message", "a message needs text or an attachment");
            }
            if (ids.size() > ChatAttachments.MAX_PER_MESSAGE) {
                throw new ChatException(400, "too_many_attachments",
                        "at most " + ChatAttachments.MAX_PER_MESSAGE + " attachments per message");
            }
            int originalCount = ids.size();
            ids = maybeAttachLongText(sessionId, content, ids, resolved);
            if (ids.size() > originalCount) {
                content = "（消息过长，已作为 .txt 附件发送）";
            }
            if (ids.size() > ChatAttachments.MAX_PER_MESSAGE) {
                throw new ChatException(400, "too_many_attachments",
                        "at most " + ChatAttachments.MAX_PER_MESSAGE + " attachments per message");
            }
            ChatStore.Node userNode = store.appendUserNode(sessionId, content, resolved.model(), ids);
            ChatStore.Message userMessage = userNode.active();
            if (userMessage == null) {
                throw new ChatException(500, "store_error", "could not store the message");
            }
            if (isUntitled(session.title())) {
                store.updateSession(sessionId, titleOf(titleSource, ids), null, null);
            }
            ChatStore.Node assistantNode = store.beginAssistantNode(sessionId, resolved.model());
            ChatStore.Message assistantMessage = assistantNode.active();
            if (assistantMessage == null) {
                throw new ChatException(500, "store_error", "could not start the reply");
            }
            ActiveTurn turn = new ActiveTurn(sessionId, assistantNode.id(), assistantMessage.id(),
                    resolved, session.id(), 1);
            register(turn, systemPrompt(session));
            start(turn, contextBefore(sessionId, assistantNode.id()));
            Outcome outcome = new Outcome(turn.id, userNode, userMessage, assistantNode, assistantMessage);
            if (key != null) {
                pruneRecent();
                recent.put(key, new RecentTurn(System.currentTimeMillis(), outcome));
            }
            return outcome;
        }
    }

    // ---------------------------------------------------------- regenerate --

    public Outcome regenerate(String sessionId, String nodeId, String mode,
                              @Nullable String requestId)
            throws ChatException, SQLException, IOException {
        ChatStore.Session session = requireSession(sessionId);
        String key = requestId == null || requestId.isBlank() ? null : sessionId + "|" + requestId;
        if (key != null) {
            RecentTurn previous = recent.get(key);
            if (previous != null && System.currentTimeMillis() - previous.at() < 10 * 60 * 1000) {
                return previous.outcome();
            }
        }
        synchronized (this) {
            if (bySession.containsKey(sessionId)) {
                throw new ChatException(409, "chat_busy", "this session is already streaming; stop it first");
            }
            checkDailyLimit();
            ChatStore.Node target = findNode(store.context(sessionId), nodeId);
            if (target == null) {
                throw new ChatException(404, "node_not_found", "node not found");
            }
            Resolved resolved = resolve(session, null, null);
            ChatStore.Node assistantNode;
            List<ChatStore.Node> context;
            if ("in_place".equals(mode)) {
                if (!"assistant".equals(target.role())) {
                    throw new ChatException(400, "not_assistant",
                            "in_place regeneration is only available on an assistant message");
                }
                ChatStore.Message variant = store.addVariant(target.id(), "", "", "streaming",
                        resolved.model(), null);
                assistantNode = new ChatStore.Node(target.id(), sessionId, target.ordinal(),
                        "assistant", variant.id(), target.createdAt(), List.of(variant));
                context = contextBefore(sessionId, target.id());
            } else if ("truncate_after".equals(mode)) {
                store.truncateAfter(sessionId, target.id());
                attachments.pruneOrphans();
                if ("assistant".equals(target.role())) {
                    ChatStore.Message variant = store.addVariant(target.id(), "", "", "streaming",
                            resolved.model(), null);
                    assistantNode = new ChatStore.Node(target.id(), sessionId, target.ordinal(),
                            "assistant", variant.id(), target.createdAt(), List.of(variant));
                    context = contextBefore(sessionId, target.id());
                } else {
                    assistantNode = store.beginAssistantNode(sessionId, resolved.model());
                    context = store.context(sessionId);
                }
            } else {
                throw new ChatException(400, "bad_mode", "mode must be in_place or truncate_after");
            }
            ChatStore.Message assistantMessage = assistantNode.active();
            if (assistantMessage == null) {
                throw new ChatException(500, "store_error", "could not start the reply");
            }
            ActiveTurn turn = new ActiveTurn(sessionId, assistantNode.id(), assistantMessage.id(),
                    resolved, session.id(), 2);
            register(turn, systemPrompt(session));
            start(turn, context);
            Outcome outcome = new Outcome(turn.id, null, null, assistantNode, assistantMessage);
            if (key != null) {
                pruneRecent();
                recent.put(key, new RecentTurn(System.currentTimeMillis(), outcome));
            }
            return outcome;
        }
    }

    // ---------------------------------------------------------------- edit --

    public Outcome edit(String sessionId, String nodeId, String text, @Nullable String requestId)
            throws ChatException, SQLException, IOException {
        ChatStore.Session session = requireSession(sessionId);
        if (text == null || text.isBlank()) {
            throw new ChatException(400, "empty_message", "the edited message is empty");
        }
        String key = requestId == null || requestId.isBlank() ? null : sessionId + "|" + requestId;
        if (key != null) {
            RecentTurn previous = recent.get(key);
            if (previous != null && System.currentTimeMillis() - previous.at() < 10 * 60 * 1000) {
                return previous.outcome();
            }
        }
        synchronized (this) {
            if (bySession.containsKey(sessionId)) {
                throw new ChatException(409, "chat_busy", "this session is already streaming; stop it first");
            }
            checkDailyLimit();
            ChatStore.Node target = findNode(store.context(sessionId), nodeId);
            if (target == null) {
                throw new ChatException(404, "node_not_found", "node not found");
            }
            if (!"user".equals(target.role())) {
                throw new ChatException(400, "not_user", "only user messages can be edited");
            }
            ChatStore.Message message = target.active();
            if (message == null) {
                throw new ChatException(404, "message_not_found", "message not found");
            }
            Resolved resolved = resolve(session, null, null);
            store.updateMessage(message.id(), text, "", "done", message.usageJson(), true);
            store.truncateAfter(sessionId, nodeId);
            attachments.pruneOrphans();
            ChatStore.Node assistantNode = store.beginAssistantNode(sessionId, resolved.model());
            ChatStore.Message assistantMessage = assistantNode.active();
            if (assistantMessage == null) {
                throw new ChatException(500, "store_error", "could not start the reply");
            }
            if (isUntitled(session.title())) {
                store.updateSession(sessionId, titleOf(text, List.of()), null, null);
            }
            ActiveTurn turn = new ActiveTurn(sessionId, assistantNode.id(), assistantMessage.id(),
                    resolved, session.id(), 3);
            register(turn, systemPrompt(session));
            start(turn, store.context(sessionId));
            Outcome outcome = new Outcome(turn.id, null, message, assistantNode, assistantMessage);
            if (key != null) {
                pruneRecent();
                recent.put(key, new RecentTurn(System.currentTimeMillis(), outcome));
            }
            return outcome;
        }
    }

    // -------------------------------------------------------------- cancel --

    public boolean cancel(String turnId) {
        ActiveTurn turn = byId.get(turnId);
        if (turn == null) {
            return false;
        }
        turn.cancelled = true;
        InputStream upstream = turn.upstream;
        if (upstream != null) {
            try {
                upstream.close();
            } catch (IOException ignored) {
                // The reader will notice.
            }
        }
        Thread worker = turn.worker;
        if (worker != null) {
            worker.interrupt();
        }
        return true;
    }

    public void close() {
        for (ActiveTurn turn : byId.values()) {
            turn.cancelled = true;
            InputStream upstream = turn.upstream;
            if (upstream != null) {
                try {
                    upstream.close();
                } catch (IOException ignored) {
                    // Shutting down.
                }
            }
            Thread worker = turn.worker;
            if (worker != null) {
                worker.interrupt();
            }
        }
        workers.shutdownNow();
        org.jackhuang.hmcl.util.io.HttpClients.closeQuietly(client);
    }

    // ------------------------------------------------------------- context --

    private List<ChatStore.Node> contextBefore(String sessionId, String nodeId) throws SQLException {
        List<ChatStore.Node> context = new ArrayList<>();
        for (ChatStore.Node node : store.context(sessionId)) {
            if (node.id().equals(nodeId)) {
                break;
            }
            context.add(node);
        }
        return context;
    }

    private Resolved resolve(ChatStore.Session session, @Nullable String bodyModel,
                             @Nullable String bodyAccount) throws ChatException, SQLException {
        ChatStore.Assistant assistant = store.assistant(session.assistantId());
        if (assistant == null) {
            throw new ChatException(409, "assistant_missing", "this session's assistant is gone");
        }
        String account = firstNonBlank(bodyAccount, session.accountName(), assistant.accountName(),
                settings.defaultAccount());
        if (account == null) {
            // 单用户面板的常见起点：没有给助手指定账户时，用第一个带 key 的账户。
            for (DshAccount candidate : ChatEndpoints.accounts()) {
                if (candidate.carriesAKey()) {
                    account = candidate.key();
                    break;
                }
            }
        }
        String model = firstNonBlank(bodyModel, session.model(), assistant.model(), settings.defaultModel());
        if (account == null) {
            throw new ChatException(400, "no_account",
                    "no account is configured for chat; add one on the accounts page first");
        }
        DshAccount dshAccount = ChatEndpoints.account(account);
        if (dshAccount == null) {
            throw new ChatException(404, "account_not_found", "account " + account + " does not exist");
        }
        if (!dshAccount.carriesAKey()) {
            throw new ChatException(400, "account_has_no_key", "account " + account + " has no API key");
        }
        if (model == null || model.isBlank()) {
            model = dshAccount.modelOrDefault();
        }
        if (model == null || model.isBlank()) {
            throw new ChatException(400, "no_model",
                    "no model is selected; pick one in the chat composer");
        }
        ChatEndpoints.Profile profile;
        try {
            profile = ChatEndpoints.profile(store, dshAccount.key());
            if (profile == null) {
                throw new ChatException(500, "endpoint_error", "could not resolve the endpoint");
            }
            ChatEndpoints.checkPrivateEndpoint(profile, settings);
        } catch (IllegalArgumentException | IOException e) {
            throw new ChatException(400, "endpoint_refused", e.getMessage());
        }
        ChatProtocol protocol = protocols.get(profile.protocol());
        if (protocol == null) {
            throw new ChatException(400, "unsupported_protocol",
                    "chat v1 does not speak " + profile.protocol());
        }
        ChatCapabilities.Capabilities capability =
                ChatCapabilities.resolve(store, dshAccount.key(), model, profile.protocol());
        if (!model.equals(session.model()) || !dshAccount.key().equals(session.accountName())) {
            store.updateSession(session.id(), null, model, dshAccount.key());
        }
        return new Resolved(model, dshAccount.key(), profile, protocol, capability);
    }

    private String systemPrompt(ChatStore.Session session) throws SQLException {
        ChatStore.Assistant assistant = store.assistant(session.assistantId());
        return assistant == null ? "" : assistant.systemPrompt();
    }

    // --------------------------------------------------------------- start --

    private void register(ActiveTurn turn, String systemPrompt) throws SQLException {
        turn.systemPrompt = systemPrompt;
        ChatStore.Assistant assistant = assistantOf(turn);
        turn.temperature = assistant == null ? null : assistant.temperature();
        turn.maxTokens = assistant == null ? null : assistant.maxTokens();
        turn.reasoningLevel = assistant == null ? null : assistant.reasoningLevel();
        bySession.put(turn.sessionId, turn);
        byId.put(turn.id, turn);
        publishStarted(turn);
    }

    private @Nullable ChatStore.Assistant assistantOf(ActiveTurn turn) throws SQLException {
        ChatStore.Session session = store.session(turn.sessionId);
        if (session == null) {
            return null;
        }
        return store.assistant(session.assistantId());
    }

    private void start(ActiveTurn turn, List<ChatStore.Node> context) {
        workers.submit(() -> {
            turn.worker = Thread.currentThread();
            run(turn, context);
        });
    }

    private void run(ActiveTurn turn, List<ChatStore.Node> context) {
        try {
            List<ChatProtocol.ChatMessage> messages = messages(context, turn.resolved);
            ChatProtocol.ChatRequest request = new ChatProtocol.ChatRequest(
                    turn.resolved.model(), turn.systemPrompt, messages, turn.temperature,
                    turn.maxTokens == null ? Math.min(turn.resolved.capability.maxOutput(), 8192)
                            : turn.maxTokens,
                    turn.reasoningLevel);
            HttpRequest httpRequest = turn.resolved.protocol.prepare(turn.resolved.profile, request);
            HttpResponse<InputStream> response = client.send(httpRequest,
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                ChatProtocol.emitHttpError(response.statusCode(), response.body(), turn);
                return;
            }
            turn.upstream = response.body();
            if (turn.cancelled) {
                finish(turn, "stopped", "cancelled");
                return;
            }
            turn.resolved.protocol.read(turn.upstream, turn);
            if (!turn.finished) {
                finish(turn, turn.cancelled ? "stopped" : "done",
                        turn.finishReason == null ? "stop" : turn.finishReason);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finish(turn, "stopped", "cancelled");
        } catch (IOException e) {
            if (turn.cancelled) {
                finish(turn, "stopped", "cancelled");
            } else {
                error(turn, "stream_failed", message(e), true);
            }
        } catch (ChatException e) {
            error(turn, e.code(), e.getMessage(), false);
        } catch (SQLException e) {
            error(turn, "store_error", message(e), false);
        } catch (RuntimeException e) {
            error(turn, "chat_error", message(e), false);
        } finally {
            InputStream upstream = turn.upstream;
            if (upstream != null) {
                try {
                    upstream.close();
                } catch (IOException ignored) {
                    // Done.
                }
            }
        }
    }

    // ------------------------------------------------------------ messages --

    private List<ChatProtocol.ChatMessage> messages(List<ChatStore.Node> nodes, Resolved resolved)
            throws SQLException, ChatException, IOException {
        List<ChatProtocol.ChatMessage> messages = new ArrayList<>();
        for (ChatStore.Node node : nodes) {
            ChatStore.Message message = node.active();
            if (message == null) {
                continue;
            }
            if ("assistant".equals(node.role())
                    && (message.content() == null || message.content().isBlank())) {
                continue;
            }
            List<ChatProtocol.Attachment> attachments = new ArrayList<>();
            for (ChatStore.Attachment attachment : message.attachments()) {
                attachments.add(toProtocolAttachment(attachment, resolved));
            }
            String text = message.content() == null ? "" : message.content();
            if (text.isBlank() && attachments.isEmpty()) {
                continue;
            }
            messages.add(new ChatProtocol.ChatMessage(node.role(), text, attachments));
        }
        if (messages.isEmpty()) {
            messages.add(new ChatProtocol.ChatMessage("user", "", List.of()));
        }
        return truncate(messages, resolved.capability);
    }

    private ChatProtocol.Attachment toProtocolAttachment(ChatStore.Attachment attachment,
                                                         Resolved resolved)
            throws ChatException {
        String kind = attachment.kind() == null ? "text" : attachment.kind();
        if ("image".equals(kind)) {
            if (!resolved.capability.vision()) {
                throw new ChatException(422, "model_no_vision",
                        "model " + resolved.model() + " does not accept images; disable the image"
                                + " attachment or switch models");
            }
            return new ChatProtocol.Attachment(attachment.name(), attachment.mime(), kind,
                    attachment.size(), Path.of(attachment.path()), null);
        }
        if ("audio".equals(kind) || "video".equals(kind)) {
            throw new ChatException(422, "unsupported_attachment",
                    "audio and video attachments are stored but not sent to the model in v1");
        }
        String text = attachments.loadText(attachment);
        boolean nativeDocument = resolved.capability.files()
                && "application/pdf".equals(attachment.mime())
                && (resolved.protocol.supportsDocuments()
                    || ChatEndpoints.OPENAI_COMPLETIONS.equals(resolved.protocol.id()));
        if (nativeDocument) {
            return new ChatProtocol.Attachment(attachment.name(), attachment.mime(), "document",
                    attachment.size(), Path.of(attachment.path()), text);
        }
        if (!resolved.capability.files() && text != null && text.length() > 30_000) {
            text = text.substring(0, 30_000) + "\n\n[附件内容已截断，完整内容见附件]";
        }
        return new ChatProtocol.Attachment(attachment.name(), attachment.mime(), "text",
                attachment.size(), Path.of(attachment.path()), text);
    }

    /// 按字符估算（约 3 字符/token）截断最旧的消息；最后一条永远保留，
    /// 它自己超长时只截它。
    private List<ChatProtocol.ChatMessage> truncate(List<ChatProtocol.ChatMessage> messages,
                                                    ChatCapabilities.Capabilities capability) {
        int budget = Math.max(4096, capability.maxContext() - Math.min(capability.maxOutput(), 8192) - 1024);
        int budgetChars = budget * 3;
        long total = 0;
        for (ChatProtocol.ChatMessage message : messages) {
            total += length(message);
        }
        while (total > budgetChars && messages.size() > 1) {
            ChatProtocol.ChatMessage removed = messages.remove(0);
            total -= length(removed);
        }
        if (!messages.isEmpty() && total > budgetChars) {
            ChatProtocol.ChatMessage last = messages.get(messages.size() - 1);
            String text = last.text() == null ? "" : last.text();
            int allowed = Math.max(1000, budgetChars - (int) (total - text.length()));
            if (text.length() > allowed) {
                messages.set(messages.size() - 1, new ChatProtocol.ChatMessage(last.role(),
                        text.substring(0, allowed) + "\n\n[内容过长，已截断]",
                        last.attachments()));
            }
        }
        return messages;
    }

    private static long length(ChatProtocol.ChatMessage message) {
        long total = message.text() == null ? 0 : message.text().length();
        for (ChatProtocol.Attachment attachment : message.attachments()) {
            if (attachment.text() != null) {
                total += attachment.text().length();
            }
        }
        return total;
    }

    // --------------------------------------------------------------- sinks --

    private void text(ActiveTurn turn, String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        synchronized (turn.lock) {
            if (turn.finished || turn.cancelled) {
                return;
            }
            turn.content.append(delta);
            turn.pendingText.append(delta);
            flush(turn, false);
            snapshot(turn);
        }
    }

    private void reasoning(ActiveTurn turn, String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        synchronized (turn.lock) {
            if (turn.finished || turn.cancelled) {
                return;
            }
            turn.reasoning.append(delta);
            turn.pendingReasoning.append(delta);
            flush(turn, false);
            snapshot(turn);
        }
    }

    private void usage(ActiveTurn turn, int input, int output) {
        int added;
        synchronized (turn.lock) {
            if (turn.finished) {
                return;
            }
            added = Math.max(0, input - turn.inputTokens) + Math.max(0, output - turn.outputTokens);
            if (input > 0) {
                turn.inputTokens = input;
            }
            if (output > 0) {
                turn.outputTokens = output;
            }
        }
        if (added > 0) {
            rollDailyBudget();
            dailyTokens.addAndGet(added);
        }
        JsonObject event = event("chat-usage", turn);
        event.addProperty("input", turn.inputTokens);
        event.addProperty("output", turn.outputTokens);
        publish(turn.sessionId, event);
    }

    private void done(ActiveTurn turn, String reason) {
        turn.finishReason = reason;
        finish(turn, turn.cancelled ? "stopped" : "done", reason);
    }

    private void error(ActiveTurn turn, String code, String message, boolean retryable) {
        synchronized (turn.lock) {
            if (turn.finished) {
                return;
            }
        }
        JsonObject event = event("chat-error", turn);
        event.addProperty("code", code);
        event.addProperty("message", message == null ? "" : message);
        event.addProperty("retryable", retryable);
        publish(turn.sessionId, event);
        finish(turn, "error", code);
    }

    private void finish(ActiveTurn turn, String status, String reason) {
        synchronized (turn.lock) {
            if (turn.finished) {
                return;
            }
            turn.finished = true;
            try {
                flush(turn, true);
            } catch (RuntimeException ignored) {
                // Publishing a last frame is best-effort.
            }
            try {
                store.updateMessage(turn.messageId, turn.content.toString(), turn.reasoning.toString(),
                        status, usageJson(turn), false);
                store.touchSession(turn.sessionId);
            } catch (SQLException e) {
                org.jackhuang.hmcl.util.logging.Logger.LOG.warning(
                        "Could not persist chat message " + turn.messageId, e);
            }
            JsonObject event = event("chat-turn-finished", turn);
            event.addProperty("status", status);
            event.addProperty("messageId", turn.messageId);
            event.addProperty("finishReason", reason == null ? "" : reason);
            event.addProperty("input", turn.inputTokens);
            event.addProperty("output", turn.outputTokens);
            publish(turn.sessionId, event);
        }
        bySession.remove(turn.sessionId, turn);
        byId.remove(turn.id, turn);
    }

    private void flush(ActiveTurn turn, boolean force) {
        long now = System.currentTimeMillis();
        long flushMs = Math.max(10, settings.streamFlushMs());
        if (!force && now - turn.lastFlush < flushMs
                && turn.pendingText.length() + turn.pendingReasoning.length() < 2048) {
            return;
        }
        if (turn.pendingReasoning.length() > 0) {
            JsonObject event = event("chat-reasoning", turn);
            event.addProperty("seq", ++turn.seq);
            event.addProperty("text", turn.pendingReasoning.toString());
            turn.pendingReasoning.setLength(0);
            publish(turn.sessionId, event);
        }
        if (turn.pendingText.length() > 0) {
            JsonObject event = event("chat-delta", turn);
            event.addProperty("seq", ++turn.seq);
            event.addProperty("text", turn.pendingText.toString());
            turn.pendingText.setLength(0);
            publish(turn.sessionId, event);
        }
        turn.lastFlush = now;
    }

    private void snapshot(ActiveTurn turn) {
        long now = System.currentTimeMillis();
        if (now - turn.lastSnapshot < 1000) {
            return;
        }
        turn.lastSnapshot = now;
        try {
            store.updateMessage(turn.messageId, turn.content.toString(), turn.reasoning.toString(),
                    "streaming", null, false);
        } catch (SQLException e) {
            org.jackhuang.hmcl.util.logging.Logger.LOG.warning(
                    "Could not snapshot chat message " + turn.messageId, e);
        }
    }

    private void publishStarted(ActiveTurn turn) {
        JsonObject event = event("chat-turn-started", turn);
        event.addProperty("messageId", turn.messageId);
        event.addProperty("nodeId", turn.nodeId);
        event.addProperty("attemptNo", turn.attemptNo);
        publish(turn.sessionId, event);
    }

    private JsonObject event(String type, ActiveTurn turn) {
        JsonObject event = new JsonObject();
        event.addProperty("type", type);
        event.addProperty("sessionId", turn.sessionId);
        event.addProperty("turnId", turn.id);
        return event;
    }

    private void publish(String sessionId, JsonObject event) {
        bus.publish("chat:" + sessionId, event);
    }

    private static @Nullable String usageJson(ActiveTurn turn) {
        if (turn.inputTokens == 0 && turn.outputTokens == 0) {
            return null;
        }
        JsonObject usage = new JsonObject();
        usage.addProperty("input", turn.inputTokens);
        usage.addProperty("output", turn.outputTokens);
        return usage.toString();
    }

    // -------------------------------------------------------------- helpers --

    private ChatStore.Session requireSession(String id) throws ChatException, SQLException {
        ChatStore.Session session = store.session(id);
        if (session == null) {
            throw new ChatException(404, "session_not_found", "session not found");
        }
        return session;
    }

    private @Nullable ChatStore.Node findNode(List<ChatStore.Node> nodes, String id) {
        for (ChatStore.Node node : nodes) {
            if (node.id().equals(id)) {
                return node;
            }
        }
        return null;
    }

    private List<String> maybeAttachLongText(String sessionId, String text, List<String> ids,
                                             Resolved resolved)
            throws SQLException, IOException, ChatException {
        int threshold = settings.longTextTokens() * 4;
        if (ids.size() > 0 || text == null || text.length() <= threshold) {
            return ids;
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        String sha = ChatStore.sha256(bytes);
        Path file = store.filesDir().resolve(sha + ".txt");
        if (!Files.exists(file)) {
            Files.write(file, bytes);
        }
        ChatStore.Attachment attachment = store.addAttachment(sessionId, null,
                "长文本.txt", "text/plain", "text", bytes.length, sha, file.toString(),
                file.toString(), "done", null);
        List<String> result = new ArrayList<>(ids);
        result.add(attachment.id());
        return result;
    }

    private static boolean isUntitled(String title) {
        return title == null || title.isBlank() || "新会话".equals(title);
    }

    private static String titleOf(String text, List<String> attachmentIds) {
        String candidate = text == null ? "" : text.strip();
        if (candidate.isEmpty()) {
            return attachmentIds.isEmpty() ? "新会话" : "附件会话";
        }
        int newline = candidate.indexOf('\n');
        if (newline > 0) {
            candidate = candidate.substring(0, newline);
        }
        candidate = candidate.replaceAll("\\s+", " ").trim();
        return candidate.length() > 40 ? candidate.substring(0, 40) : candidate;
    }

    private void pruneRecent() {
        long cutoff = System.currentTimeMillis() - 10 * 60 * 1000;
        recent.entrySet().removeIf(entry -> entry.getValue().at() < cutoff);
    }

    private void checkDailyLimit() throws ChatException {
        rollDailyBudget();
        int limit = settings.dailyTokenLimit();
        if (limit > 0 && dailyTokens.get() >= limit) {
            throw new ChatException(429, "daily_limit",
                    "the chat token budget for today is used up; raise it in chat settings");
        }
    }

    private void rollDailyBudget() {
        LocalDate today = LocalDate.now();
        if (!today.equals(dailyDate)) {
            synchronized (this) {
                if (!today.equals(dailyDate)) {
                    dailyDate = today;
                    dailyTokens.set(0);
                }
            }
        }
    }

    private static @Nullable String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String message(Throwable error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName() : error.getMessage();
    }

    // ----------------------------------------------------------- active turn --

    private final class ActiveTurn implements ChatProtocol.ChatEventSink {
        final String id = java.util.UUID.randomUUID().toString();
        final String sessionId;
        final String nodeId;
        final String messageId;
        final Resolved resolved;
        final String sessionKey;
        final int attemptNo;
        final Object lock = new Object();
        final StringBuilder content = new StringBuilder();
        final StringBuilder reasoning = new StringBuilder();
        final StringBuilder pendingText = new StringBuilder();
        final StringBuilder pendingReasoning = new StringBuilder();
        volatile boolean finished;
        volatile boolean cancelled;
        volatile InputStream upstream;
        volatile Thread worker;
        volatile String finishReason;
        volatile long lastFlush;
        volatile long lastSnapshot;
        volatile int seq;
        volatile int inputTokens;
        volatile int outputTokens;
        volatile String systemPrompt = "";
        volatile Double temperature;
        volatile Integer maxTokens;
        volatile String reasoningLevel;

        ActiveTurn(String sessionId, String nodeId, String messageId, Resolved resolved,
                   String sessionKey, int attemptNo) {
            this.sessionId = sessionId;
            this.nodeId = nodeId;
            this.messageId = messageId;
            this.resolved = resolved;
            this.sessionKey = sessionKey;
            this.attemptNo = attemptNo;
            this.lastFlush = System.currentTimeMillis();
            this.lastSnapshot = System.currentTimeMillis();
        }

        @Override
        public void text(String delta) {
            ChatTurnManager.this.text(this, delta);
        }

        @Override
        public void reasoning(String delta) {
            ChatTurnManager.this.reasoning(this, delta);
        }

        @Override
        public void usage(int input, int output) {
            ChatTurnManager.this.usage(this, input, output);
        }

        @Override
        public void done(String reason) {
            ChatTurnManager.this.done(this, reason);
        }

        @Override
        public void error(String code, String message, boolean retryable) {
            ChatTurnManager.this.error(this, code, message, retryable);
        }
    }
}