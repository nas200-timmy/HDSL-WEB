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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/// 聊天模块的 SQLite 存储（docs/chat.md §6）。
///
/// 一个会话就是按 `ordinal` 排列的节点列表；用户节点一条消息，助手节点可以有多个
/// variant，`active_variant_id` 决定当前显示与下一轮上下文。流式内容不逐 token 落库，
/// turn 管理器每隔一秒写一次快照，完成时再写最终文本。
///
/// 单用户面板，一个连接、方法级同步就够了：SQLite 的写锁本来就只有一把，连接池只会
/// 把并发换成 `SQLITE_BUSY`。
@NotNullByDefault
public final class ChatStore implements AutoCloseable {

    private static final int SCHEMA_VERSION = 1;
    private static final String DEFAULT_ASSISTANT_ID = "default";

    private final Path root;
    private final Path filesDir;
    private final Connection connection;

    public ChatStore(Path dataDir) throws SQLException, IOException {
        this.root = dataDir.resolve("chat");
        this.filesDir = root.resolve("files");
        Files.createDirectories(filesDir);
        chmodPrivate(root);
        chmodPrivate(filesDir);
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("chat.db"));
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA foreign_keys=ON");
        }
        migrate();
        seedDefaultAssistant();
        recoverStreaming();
    }

    public Path root() {
        return root;
    }

    public Path filesDir() {
        return filesDir;
    }

    private static void chmodPrivate(Path path) {
        try {
            if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
            }
        } catch (IOException | UnsupportedOperationException ignored) {
            // Windows and exotic filesystems have no POSIX mode; the directory
            // is still under the panel's data dir.
        }
    }

    // ------------------------------------------------------------- schema --

    private void migrate() throws SQLException {
        int version = userVersion();
        if (version >= SCHEMA_VERSION) {
            return;
        }
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            if (version < 1) {
                statement.execute("""
                        CREATE TABLE chat_assistant (
                          id TEXT PRIMARY KEY,
                          name TEXT NOT NULL,
                          avatar TEXT NOT NULL DEFAULT '',
                          description TEXT NOT NULL DEFAULT '',
                          system_prompt TEXT NOT NULL DEFAULT '',
                          opening_message TEXT NOT NULL DEFAULT '',
                          account_name TEXT,
                          model TEXT,
                          temperature REAL,
                          max_tokens INTEGER,
                          reasoning_level TEXT,
                          quick_phrases_json TEXT NOT NULL DEFAULT '[]',
                          is_builtin INTEGER NOT NULL DEFAULT 0,
                          created_at INTEGER NOT NULL,
                          updated_at INTEGER NOT NULL
                        )""");
                statement.execute("""
                        CREATE TABLE chat_session (
                          id TEXT PRIMARY KEY,
                          assistant_id TEXT NOT NULL REFERENCES chat_assistant(id),
                          title TEXT NOT NULL,
                          model TEXT,
                          account_name TEXT,
                          created_at INTEGER NOT NULL,
                          updated_at INTEGER NOT NULL
                        )""");
                statement.execute("CREATE INDEX chat_session_assistant ON chat_session(assistant_id, updated_at DESC)");
                statement.execute("""
                        CREATE TABLE chat_node (
                          id TEXT PRIMARY KEY,
                          session_id TEXT NOT NULL REFERENCES chat_session(id) ON DELETE CASCADE,
                          ordinal INTEGER NOT NULL,
                          role TEXT NOT NULL,
                          active_variant_id TEXT,
                          created_at INTEGER NOT NULL
                        )""");
                statement.execute("CREATE INDEX chat_node_session ON chat_node(session_id, ordinal)");
                statement.execute("""
                        CREATE TABLE chat_message (
                          id TEXT PRIMARY KEY,
                          node_id TEXT NOT NULL REFERENCES chat_node(id) ON DELETE CASCADE,
                          variant_index INTEGER NOT NULL DEFAULT 0,
                          content TEXT NOT NULL DEFAULT '',
                          reasoning TEXT NOT NULL DEFAULT '',
                          status TEXT NOT NULL DEFAULT 'done',
                          model TEXT,
                          usage_json TEXT,
                          created_at INTEGER NOT NULL,
                          edited_at INTEGER
                        )""");
                statement.execute("CREATE INDEX chat_message_node ON chat_message(node_id, variant_index)");
                statement.execute("""
                        CREATE TABLE chat_attachment (
                          id TEXT PRIMARY KEY,
                          session_id TEXT NOT NULL REFERENCES chat_session(id) ON DELETE CASCADE,
                          message_id TEXT,
                          name TEXT NOT NULL,
                          mime TEXT NOT NULL,
                          kind TEXT NOT NULL,
                          size INTEGER NOT NULL,
                          sha256 TEXT NOT NULL,
                          path TEXT NOT NULL,
                          extracted_text_path TEXT,
                          extract_status TEXT NOT NULL DEFAULT 'pending',
                          extract_error TEXT,
                          created_at INTEGER NOT NULL
                        )""");
                statement.execute("CREATE INDEX chat_attachment_session ON chat_attachment(session_id)");
                statement.execute("CREATE INDEX chat_attachment_message ON chat_attachment(message_id)");
                statement.execute("""
                        CREATE TABLE chat_account_profile (
                          account_name TEXT PRIMARY KEY,
                          protocol TEXT,
                          base_override TEXT,
                          models_path TEXT,
                          chat_path TEXT,
                          auth_style TEXT,
                          extra_headers_json TEXT NOT NULL DEFAULT '{}',
                          models_json TEXT NOT NULL DEFAULT '[]',
                          models_fetched_at INTEGER,
                          last_probe_json TEXT,
                          updated_at INTEGER NOT NULL
                        )""");
                statement.execute("""
                        CREATE TABLE chat_model_capability (
                          account_name TEXT NOT NULL,
                          model TEXT NOT NULL,
                          vision INTEGER NOT NULL,
                          files INTEGER NOT NULL,
                          reasoning INTEGER NOT NULL,
                          tools INTEGER NOT NULL,
                          max_context INTEGER NOT NULL,
                          max_output INTEGER NOT NULL,
                          source TEXT NOT NULL,
                          updated_at INTEGER NOT NULL,
                          PRIMARY KEY (account_name, model)
                        )""");
                statement.execute("""
                        CREATE TABLE chat_setting (
                          key TEXT PRIMARY KEY,
                          value TEXT NOT NULL
                        )""");
            }
            statement.execute("PRAGMA user_version=" + SCHEMA_VERSION);
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private int userVersion() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private void seedDefaultAssistant() throws SQLException {
        if (assistant(DEFAULT_ASSISTANT_ID) != null) {
            return;
        }
        long now = System.currentTimeMillis();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat_assistant
                  (id, name, avatar, description, system_prompt, opening_message, account_name, model,
                   temperature, max_tokens, reasoning_level, quick_phrases_json, is_builtin, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, NULL, NULL, NULL, '[]', 1, ?, ?)""")) {
            statement.setString(1, DEFAULT_ASSISTANT_ID);
            statement.setString(2, "默认助手");
            statement.setString(3, "💬");
            statement.setString(4, "不设系统提示词的通用助手");
            statement.setString(5, "");
            statement.setString(6, "");
            statement.setLong(7, now);
            statement.setLong(8, now);
            statement.executeUpdate();
        }
    }

    /// 面板上次没有正常结束时留下的 `streaming` 消息，启动后一律改成 `interrupted`。
    /// 它们可以重新生成，但不能再被当成一个还在进行的 turn。
    private void recoverStreaming() throws SQLException {
        synchronized (this) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE chat_message SET status='interrupted' WHERE status='streaming'")) {
                statement.executeUpdate();
            }
        }
    }

    // ---------------------------------------------------------- assistants --

    public record Assistant(
            String id, String name, String avatar, String description,
            String systemPrompt, String openingMessage,
            @Nullable String accountName, @Nullable String model,
            @Nullable Double temperature, @Nullable Integer maxTokens,
            @Nullable String reasoningLevel, String quickPhrasesJson,
            boolean builtin, long createdAt, long updatedAt) {

        public Assistant withId(String newId) {
            return new Assistant(newId, name, avatar, description, systemPrompt, openingMessage,
                    accountName, model, temperature, maxTokens, reasoningLevel, quickPhrasesJson,
                    false, createdAt, updatedAt);
        }
    }

    public synchronized List<Assistant> assistants() throws SQLException {
        List<Assistant> result = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT * FROM chat_assistant ORDER BY is_builtin DESC, created_at")) {
            while (rs.next()) {
                result.add(readAssistant(rs));
            }
        }
        return result;
    }

    public synchronized @Nullable Assistant assistant(String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM chat_assistant WHERE id=?")) {
            statement.setString(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? readAssistant(rs) : null;
            }
        }
    }

    private static Assistant readAssistant(ResultSet rs) throws SQLException {
        return new Assistant(
                rs.getString("id"), rs.getString("name"), rs.getString("avatar"),
                rs.getString("description"), rs.getString("system_prompt"),
                rs.getString("opening_message"),
                rs.getString("account_name"), rs.getString("model"),
                nullableDouble(rs, "temperature"), nullableInt(rs, "max_tokens"),
                rs.getString("reasoning_level"), rs.getString("quick_phrases_json"),
                rs.getInt("is_builtin") != 0, rs.getLong("created_at"), rs.getLong("updated_at"));
    }

    public synchronized Assistant createAssistant(Assistant draft) throws SQLException {
        long now = System.currentTimeMillis();
        Assistant assistant = new Assistant(
                draft.id() == null || draft.id().isBlank() ? UUID.randomUUID().toString() : draft.id(),
                draft.name(), draft.avatar(), draft.description(), draft.systemPrompt(),
                draft.openingMessage(), draft.accountName(), draft.model(), draft.temperature(),
                draft.maxTokens(), draft.reasoningLevel(), draft.quickPhrasesJson(),
                draft.builtin(), now, now);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat_assistant
                  (id, name, avatar, description, system_prompt, opening_message, account_name, model,
                   temperature, max_tokens, reasoning_level, quick_phrases_json, is_builtin, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
            bindAssistant(statement, assistant);
            statement.executeUpdate();
        }
        return assistant;
    }

    private static void bindAssistant(PreparedStatement statement, Assistant a) throws SQLException {
        statement.setString(1, a.id());
        statement.setString(2, a.name());
        statement.setString(3, a.avatar());
        statement.setString(4, a.description());
        statement.setString(5, a.systemPrompt());
        statement.setString(6, a.openingMessage());
        statement.setString(7, a.accountName());
        statement.setString(8, a.model());
        if (a.temperature() == null) statement.setNull(9, java.sql.Types.REAL);
        else statement.setDouble(9, a.temperature());
        if (a.maxTokens() == null) statement.setNull(10, java.sql.Types.INTEGER);
        else statement.setInt(10, a.maxTokens());
        statement.setString(11, a.reasoningLevel());
        statement.setString(12, a.quickPhrasesJson());
        statement.setInt(13, a.builtin() ? 1 : 0);
        statement.setLong(14, a.createdAt());
        statement.setLong(15, a.updatedAt());
    }

    public synchronized Assistant updateAssistant(Assistant assistant) throws SQLException {
        Assistant updated = new Assistant(assistant.id(), assistant.name(), assistant.avatar(),
                assistant.description(), assistant.systemPrompt(), assistant.openingMessage(),
                assistant.accountName(), assistant.model(), assistant.temperature(), assistant.maxTokens(),
                assistant.reasoningLevel(), assistant.quickPhrasesJson(), assistant.builtin(),
                assistant.createdAt(), System.currentTimeMillis());
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE chat_assistant SET name=?, avatar=?, description=?, system_prompt=?,
                  opening_message=?, account_name=?, model=?, temperature=?, max_tokens=?,
                  reasoning_level=?, quick_phrases_json=?, updated_at=? WHERE id=?""")) {
            statement.setString(1, updated.name());
            statement.setString(2, updated.avatar());
            statement.setString(3, updated.description());
            statement.setString(4, updated.systemPrompt());
            statement.setString(5, updated.openingMessage());
            statement.setString(6, updated.accountName());
            statement.setString(7, updated.model());
            if (updated.temperature() == null) statement.setNull(8, java.sql.Types.REAL);
            else statement.setDouble(8, updated.temperature());
            if (updated.maxTokens() == null) statement.setNull(9, java.sql.Types.INTEGER);
            else statement.setInt(9, updated.maxTokens());
            statement.setString(10, updated.reasoningLevel());
            statement.setString(11, updated.quickPhrasesJson());
            statement.setLong(12, updated.updatedAt());
            statement.setString(13, updated.id());
            statement.executeUpdate();
        }
        return updated;
    }

    /// 删除助手：内置助手拒绝；其余助手的会话迁移到默认助手，绝不留下无主会话。
    public synchronized void deleteAssistant(String id) throws SQLException {
        Assistant assistant = assistant(id);
        if (assistant == null) {
            throw new SQLException("assistant not found");
        }
        if (assistant.builtin()) {
            throw new IllegalArgumentException("the built-in assistant cannot be deleted");
        }
        try (PreparedStatement move = connection.prepareStatement(
                "UPDATE chat_session SET assistant_id=? WHERE assistant_id=?")) {
            move.setString(1, DEFAULT_ASSISTANT_ID);
            move.setString(2, id);
            move.executeUpdate();
        }
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM chat_assistant WHERE id=?")) {
            delete.setString(1, id);
            delete.executeUpdate();
        }
    }

    public synchronized Assistant duplicateAssistant(String id) throws SQLException {
        Assistant source = assistant(id);
        if (source == null) {
            throw new SQLException("assistant not found");
        }
        return createAssistant(new Assistant(UUID.randomUUID().toString(), source.name() + " 副本",
                source.avatar(), source.description(), source.systemPrompt(), source.openingMessage(),
                source.accountName(), source.model(), source.temperature(), source.maxTokens(),
                source.reasoningLevel(), source.quickPhrasesJson(), false, 0, 0));
    }

    // ------------------------------------------------------------ sessions --

    public record SessionSummary(
            String id, String assistantId, String title, @Nullable String model,
            @Nullable String accountName, long createdAt, long updatedAt,
            int messageCount, @Nullable String lastMessage) {
    }

    public record Session(
            String id, String assistantId, String title, @Nullable String model,
            @Nullable String accountName, long createdAt, long updatedAt, List<Node> nodes) {
    }

    public record Node(
            String id, String sessionId, int ordinal, String role,
            @Nullable String activeVariantId, long createdAt, List<Message> variants) {

        public @Nullable Message active() {
            if (variants.isEmpty()) {
                return null;
            }
            for (Message message : variants) {
                if (message.id().equals(activeVariantId)) {
                    return message;
                }
            }
            return variants.get(variants.size() - 1);
        }
    }

    public record Message(
            String id, String nodeId, int variantIndex, String content, String reasoning,
            String status, @Nullable String model, @Nullable String usageJson,
            long createdAt, @Nullable Long editedAt, List<Attachment> attachments) {
    }

    public record Attachment(
            String id, String sessionId, @Nullable String messageId, String name, String mime,
            String kind, long size, String sha256, String path,
            @Nullable String extractedTextPath, String extractStatus, @Nullable String extractError,
            long createdAt) {
    }

    public synchronized List<SessionSummary> sessions(@Nullable String assistantId) throws SQLException {
        String sql = """
                SELECT s.*,
                       (SELECT COUNT(*) FROM chat_message m JOIN chat_node n ON n.id=m.node_id
                         WHERE n.session_id=s.id) AS message_count,
                       (SELECT m.content FROM chat_message m JOIN chat_node n ON n.id=m.node_id
                         WHERE n.session_id=s.id AND m.status='done'
                         ORDER BY n.ordinal DESC, m.variant_index DESC LIMIT 1) AS last_message
                FROM chat_session s
                """ + (assistantId == null ? "" : " WHERE s.assistant_id=? ") + " ORDER BY s.updated_at DESC";
        List<SessionSummary> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (assistantId != null) {
                statement.setString(1, assistantId);
            }
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(new SessionSummary(
                            rs.getString("id"), rs.getString("assistant_id"), rs.getString("title"),
                            rs.getString("model"), rs.getString("account_name"),
                            rs.getLong("created_at"), rs.getLong("updated_at"),
                            rs.getInt("message_count"), rs.getString("last_message")));
                }
            }
        }
        return result;
    }

    public synchronized @Nullable Session session(String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM chat_session WHERE id=?")) {
            statement.setString(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new Session(rs.getString("id"), rs.getString("assistant_id"),
                        rs.getString("title"), rs.getString("model"), rs.getString("account_name"),
                        rs.getLong("created_at"), rs.getLong("updated_at"), nodes(id));
            }
        }
    }

    public synchronized void requireSession(String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM chat_session WHERE id=?")) {
            statement.setString(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("session not found");
                }
            }
        }
    }

    public synchronized String sessionAssistant(String sessionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT assistant_id FROM chat_session WHERE id=?")) {
            statement.setString(1, sessionId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("session not found");
                }
                return rs.getString(1);
            }
        }
    }

    private List<Node> nodes(String sessionId) throws SQLException {
        List<Node> nodes = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM chat_node WHERE session_id=? ORDER BY ordinal")) {
            statement.setString(1, sessionId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String nodeId = rs.getString("id");
                    nodes.add(new Node(nodeId, sessionId, rs.getInt("ordinal"), rs.getString("role"),
                            rs.getString("active_variant_id"), rs.getLong("created_at"),
                            messages(nodeId)));
                }
            }
        }
        return nodes;
    }

    private List<Message> messages(String nodeId) throws SQLException {
        List<Message> messages = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM chat_message WHERE node_id=? ORDER BY variant_index")) {
            statement.setString(1, nodeId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString("id");
                    messages.add(new Message(id, nodeId, rs.getInt("variant_index"),
                            rs.getString("content"), rs.getString("reasoning"), rs.getString("status"),
                            rs.getString("model"), rs.getString("usage_json"), rs.getLong("created_at"),
                            nullableLong(rs, "edited_at"), attachmentsForMessage(id)));
                }
            }
        }
        return messages;
    }

    public synchronized Session createSession(String assistantId, String title,
                                              @Nullable String model,
                                              @Nullable String accountName) throws SQLException {
        long now = System.currentTimeMillis();
        String id = UUID.randomUUID().toString();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat_session (id, assistant_id, title, model, account_name, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)""")) {
            statement.setString(1, id);
            statement.setString(2, assistantId);
            statement.setString(3, title);
            statement.setString(4, model);
            statement.setString(5, accountName);
            statement.setLong(6, now);
            statement.setLong(7, now);
            statement.executeUpdate();
        }
        return new Session(id, assistantId, title, model, accountName, now, now, List.of());
    }

    public synchronized void updateSession(String id, @Nullable String title,
                                           @Nullable String model,
                                           @Nullable String accountName) throws SQLException {
        StringBuilder sql = new StringBuilder("UPDATE chat_session SET updated_at=?");
        List<Object> values = new ArrayList<>();
        values.add(System.currentTimeMillis());
        if (title != null) {
            sql.append(", title=?");
            values.add(title);
        }
        if (model != null) {
            sql.append(", model=?");
            values.add(model.isEmpty() ? null : model);
        }
        if (accountName != null) {
            sql.append(", account_name=?");
            values.add(accountName.isEmpty() ? null : accountName);
        }
        sql.append(" WHERE id=?");
        values.add(id);
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < values.size(); i++) {
                statement.setObject(i + 1, values.get(i));
            }
            statement.executeUpdate();
        }
    }

    public synchronized void touchSession(String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE chat_session SET updated_at=? WHERE id=?")) {
            statement.setLong(1, System.currentTimeMillis());
            statement.setString(2, id);
            statement.executeUpdate();
        }
    }

    /// 删除会话，返回它的附件（调用方负责删除磁盘文件）。
    public synchronized List<Attachment> deleteSession(String id) throws SQLException {
        List<Attachment> attachments = attachmentsForSession(id);
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM chat_session WHERE id=?")) {
            statement.setString(1, id);
            statement.executeUpdate();
        }
        return attachments;
    }

    // --------------------------------------------------------------- nodes --

    private int nextOrdinal(String sessionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(MAX(ordinal), -1) + 1 FROM chat_node WHERE session_id=?")) {
            statement.setString(1, sessionId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private String insertNode(String sessionId, String role, @Nullable String activeVariantId,
                              long createdAt) throws SQLException {
        String id = UUID.randomUUID().toString();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat_node (id, session_id, ordinal, role, active_variant_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?)""")) {
            statement.setString(1, id);
            statement.setString(2, sessionId);
            statement.setInt(3, nextOrdinal(sessionId));
            statement.setString(4, role);
            statement.setString(5, activeVariantId);
            statement.setLong(6, createdAt);
            statement.executeUpdate();
        }
        return id;
    }

    private Message insertMessage(String nodeId, int variantIndex, String content, String reasoning,
                                  String status, @Nullable String model, @Nullable String usageJson,
                                  long createdAt) throws SQLException {
        String id = UUID.randomUUID().toString();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat_message
                  (id, node_id, variant_index, content, reasoning, status, model, usage_json, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
            statement.setString(1, id);
            statement.setString(2, nodeId);
            statement.setInt(3, variantIndex);
            statement.setString(4, content);
            statement.setString(5, reasoning);
            statement.setString(6, status);
            statement.setString(7, model);
            statement.setString(8, usageJson);
            statement.setLong(9, createdAt);
            statement.executeUpdate();
        }
        return new Message(id, nodeId, variantIndex, content, reasoning, status, model, usageJson,
                createdAt, null, List.of());
    }

    private int nextVariant(String nodeId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(MAX(variant_index), -1) + 1 FROM chat_message WHERE node_id=?")) {
            statement.setString(1, nodeId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /// 追加一条用户节点，并把附件挂到这条消息上。
    public synchronized Node appendUserNode(String sessionId, String content,
                                            @Nullable String model,
                                            List<String> attachmentIds) throws SQLException {
        long now = System.currentTimeMillis();
        String nodeId = insertNode(sessionId, "user", null, now);
        Message message = insertMessage(nodeId, 0, content, "", "done", model, null, now);
        setActiveVariant(nodeId, message.id());
        for (String attachmentId : attachmentIds) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE chat_attachment SET message_id=? WHERE id=? AND session_id=?")) {
                statement.setString(1, message.id());
                statement.setString(2, attachmentId);
                statement.setString(3, sessionId);
                statement.executeUpdate();
            }
        }
        touchSession(sessionId);
        return new Node(nodeId, sessionId, 0, "user", message.id(), now,
                List.of(new Message(message.id(), nodeId, 0, content, "", "done", model, null,
                        now, null, attachmentsForMessage(message.id()))));
    }

    /// 新建一个空的助手节点（状态 streaming），用于本轮生成。
    public synchronized Node beginAssistantNode(String sessionId, @Nullable String model) throws SQLException {
        long now = System.currentTimeMillis();
        String nodeId = insertNode(sessionId, "assistant", null, now);
        Message message = insertMessage(nodeId, 0, "", "", "streaming", model, null, now);
        setActiveVariant(nodeId, message.id());
        touchSession(sessionId);
        return new Node(nodeId, sessionId, 0, "assistant", message.id(), now, List.of(message));
    }

    public synchronized Message addVariant(String nodeId, String content, String reasoning,
                                           String status, @Nullable String model,
                                           @Nullable String usageJson) throws SQLException {
        long now = System.currentTimeMillis();
        Message message = insertMessage(nodeId, nextVariant(nodeId), content, reasoning, status,
                model, usageJson, now);
        setActiveVariant(nodeId, message.id());
        return message;
    }

    public synchronized void setActiveVariant(String nodeId, String messageId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE chat_node SET active_variant_id=? WHERE id=?")) {
            statement.setString(1, messageId);
            statement.setString(2, nodeId);
            statement.executeUpdate();
        }
    }

    public synchronized @Nullable Message message(String messageId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM chat_message WHERE id=?")) {
            statement.setString(1, messageId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new Message(messageId, rs.getString("node_id"), rs.getInt("variant_index"),
                        rs.getString("content"), rs.getString("reasoning"), rs.getString("status"),
                        rs.getString("model"), rs.getString("usage_json"), rs.getLong("created_at"),
                        nullableLong(rs, "edited_at"), attachmentsForMessage(messageId));
            }
        }
    }

    public synchronized void updateMessage(String messageId, String content, String reasoning,
                                           String status, @Nullable String usageJson,
                                           boolean edited) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE chat_message SET content=?, reasoning=?, status=?, usage_json=?,
                  edited_at=CASE WHEN ? THEN ? ELSE edited_at END WHERE id=?""")) {
            statement.setString(1, content);
            statement.setString(2, reasoning);
            statement.setString(3, status);
            statement.setString(4, usageJson);
            statement.setInt(5, edited ? 1 : 0);
            statement.setLong(6, System.currentTimeMillis());
            statement.setString(7, messageId);
            statement.executeUpdate();
        }
    }

    public synchronized void truncateAfter(String sessionId, String nodeId) throws SQLException {
        try (PreparedStatement ordinal = connection.prepareStatement(
                "SELECT ordinal FROM chat_node WHERE id=? AND session_id=?")) {
            ordinal.setString(1, nodeId);
            ordinal.setString(2, sessionId);
            try (ResultSet rs = ordinal.executeQuery()) {
                if (!rs.next()) {
                    return;
                }
                int value = rs.getInt(1);
                deleteAttachmentsFrom(sessionId, value, false);
                try (PreparedStatement delete = connection.prepareStatement("""
                        DELETE FROM chat_node WHERE session_id=? AND ordinal>?
                        """)) {
                    delete.setString(1, sessionId);
                    delete.setInt(2, value);
                    delete.executeUpdate();
                }
            }
        }
        touchSession(sessionId);
    }

    public synchronized void deleteNodeAndAfter(String sessionId, String nodeId) throws SQLException {
        try (PreparedStatement ordinal = connection.prepareStatement(
                "SELECT ordinal FROM chat_node WHERE id=? AND session_id=?")) {
            ordinal.setString(1, nodeId);
            ordinal.setString(2, sessionId);
            try (ResultSet rs = ordinal.executeQuery()) {
                if (!rs.next()) {
                    return;
                }
                int value = rs.getInt(1);
                deleteAttachmentsFrom(sessionId, value, true);
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM chat_node WHERE session_id=? AND ordinal>=?")) {
                    delete.setString(1, sessionId);
                    delete.setInt(2, value);
                    delete.executeUpdate();
                }
            }
        }
        touchSession(sessionId);
    }

    /// 删除节点前先清掉它们的附件记录；磁盘文件由调用方 [ChatAttachments#pruneOrphans] 清。
    private void deleteAttachmentsFrom(String sessionId, int ordinal, boolean inclusive)
            throws SQLException {
        String operator = inclusive ? ">=" : ">";
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM chat_attachment WHERE message_id IN ("
                        + "SELECT m.id FROM chat_message m JOIN chat_node n ON n.id=m.node_id "
                        + "WHERE n.session_id=? AND n.ordinal " + operator + " ?)")) {
            statement.setString(1, sessionId);
            statement.setInt(2, ordinal);
            statement.executeUpdate();
        }
    }

    /// 上下文：每个节点取 active variant，按 ordinal 排列。
    public synchronized List<Node> context(String sessionId) throws SQLException {
        return nodes(sessionId);
    }

    // --------------------------------------------------------- attachments --

    public synchronized Attachment addAttachment(String sessionId, @Nullable String messageId,
                                                 String name, String mime, String kind, long size,
                                                 String sha256, String path,
                                                 @Nullable String extractedTextPath,
                                                 String extractStatus,
                                                 @Nullable String extractError) throws SQLException {
        long now = System.currentTimeMillis();
        String id = UUID.randomUUID().toString();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat_attachment
                  (id, session_id, message_id, name, mime, kind, size, sha256, path,
                   extracted_text_path, extract_status, extract_error, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
            statement.setString(1, id);
            statement.setString(2, sessionId);
            statement.setString(3, messageId);
            statement.setString(4, name);
            statement.setString(5, mime);
            statement.setString(6, kind);
            statement.setLong(7, size);
            statement.setString(8, sha256);
            statement.setString(9, path);
            statement.setString(10, extractedTextPath);
            statement.setString(11, extractStatus);
            statement.setString(12, extractError);
            statement.setLong(13, now);
            statement.executeUpdate();
        }
        touchSession(sessionId);
        return new Attachment(id, sessionId, messageId, name, mime, kind, size, sha256, path,
                extractedTextPath, extractStatus, extractError, now);
    }

    public synchronized @Nullable Attachment attachment(String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM chat_attachment WHERE id=?")) {
            statement.setString(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? readAttachment(rs) : null;
            }
        }
    }

    public synchronized List<Attachment> attachmentsForMessage(String messageId) throws SQLException {
        List<Attachment> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM chat_attachment WHERE message_id=? ORDER BY created_at")) {
            statement.setString(1, messageId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(readAttachment(rs));
                }
            }
        }
        return result;
    }

    public synchronized List<Attachment> attachmentsForSession(String sessionId) throws SQLException {
        List<Attachment> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM chat_attachment WHERE session_id=? ORDER BY created_at")) {
            statement.setString(1, sessionId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(readAttachment(rs));
                }
            }
        }
        return result;
    }

    private static Attachment readAttachment(ResultSet rs) throws SQLException {
        return new Attachment(rs.getString("id"), rs.getString("session_id"),
                rs.getString("message_id"), rs.getString("name"), rs.getString("mime"),
                rs.getString("kind"), rs.getLong("size"), rs.getString("sha256"),
                rs.getString("path"), rs.getString("extracted_text_path"),
                rs.getString("extract_status"), rs.getString("extract_error"),
                rs.getLong("created_at"));
    }

    public synchronized void setAttachmentExtraction(String id, @Nullable String textPath,
                                                     String status,
                                                     @Nullable String error) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE chat_attachment SET extracted_text_path=?, extract_status=?, extract_error=? WHERE id=?")) {
            statement.setString(1, textPath);
            statement.setString(2, status);
            statement.setString(3, error);
            statement.setString(4, id);
            statement.executeUpdate();
        }
    }

    public synchronized void deleteAttachment(String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM chat_attachment WHERE id=?")) {
            statement.setString(1, id);
            statement.executeUpdate();
        }
    }

    // ------------------------------------------------------------ profiles --

    public record AccountProfile(
            String accountName, @Nullable String protocol, @Nullable String baseOverride,
            @Nullable String modelsPath, @Nullable String chatPath, @Nullable String authStyle,
            String extraHeadersJson, String modelsJson, @Nullable Long modelsFetchedAt,
            @Nullable String lastProbeJson, long updatedAt) {
    }

    public synchronized @Nullable AccountProfile profile(String accountName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM chat_account_profile WHERE account_name=?")) {
            statement.setString(1, accountName);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new AccountProfile(rs.getString("account_name"), rs.getString("protocol"),
                        rs.getString("base_override"), rs.getString("models_path"),
                        rs.getString("chat_path"), rs.getString("auth_style"),
                        rs.getString("extra_headers_json"), rs.getString("models_json"),
                        nullableLong(rs, "models_fetched_at"), rs.getString("last_probe_json"),
                        rs.getLong("updated_at"));
            }
        }
    }

    public synchronized void saveProfile(AccountProfile profile) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat_account_profile
                  (account_name, protocol, base_override, models_path, chat_path, auth_style,
                   extra_headers_json, models_json, models_fetched_at, last_probe_json, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(account_name) DO UPDATE SET
                  protocol=excluded.protocol, base_override=excluded.base_override,
                  models_path=excluded.models_path, chat_path=excluded.chat_path,
                  auth_style=excluded.auth_style, extra_headers_json=excluded.extra_headers_json,
                  models_json=excluded.models_json, models_fetched_at=excluded.models_fetched_at,
                  last_probe_json=excluded.last_probe_json, updated_at=excluded.updated_at""")) {
            statement.setString(1, profile.accountName());
            statement.setString(2, profile.protocol());
            statement.setString(3, profile.baseOverride());
            statement.setString(4, profile.modelsPath());
            statement.setString(5, profile.chatPath());
            statement.setString(6, profile.authStyle());
            statement.setString(7, profile.extraHeadersJson());
            statement.setString(8, profile.modelsJson());
            if (profile.modelsFetchedAt() == null) statement.setNull(9, java.sql.Types.INTEGER);
            else statement.setLong(9, profile.modelsFetchedAt());
            statement.setString(10, profile.lastProbeJson());
            statement.setLong(11, profile.updatedAt());
            statement.executeUpdate();
        }
    }

    public record Capability(
            String accountName, String model, boolean vision, boolean files, boolean reasoning,
            boolean tools, int maxContext, int maxOutput, String source, long updatedAt) {
    }

    public synchronized @Nullable Capability capability(String accountName, String model)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM chat_model_capability WHERE account_name=? AND model=?")) {
            statement.setString(1, accountName);
            statement.setString(2, model);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new Capability(accountName, model, rs.getInt("vision") != 0,
                        rs.getInt("files") != 0, rs.getInt("reasoning") != 0,
                        rs.getInt("tools") != 0, rs.getInt("max_context"),
                        rs.getInt("max_output"), rs.getString("source"), rs.getLong("updated_at"));
            }
        }
    }

    public synchronized void saveCapability(Capability capability) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat_model_capability
                  (account_name, model, vision, files, reasoning, tools, max_context, max_output, source, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(account_name, model) DO UPDATE SET
                  vision=excluded.vision, files=excluded.files, reasoning=excluded.reasoning,
                  tools=excluded.tools, max_context=excluded.max_context,
                  max_output=excluded.max_output, source=excluded.source, updated_at=excluded.updated_at""")) {
            statement.setString(1, capability.accountName());
            statement.setString(2, capability.model());
            statement.setInt(3, capability.vision() ? 1 : 0);
            statement.setInt(4, capability.files() ? 1 : 0);
            statement.setInt(5, capability.reasoning() ? 1 : 0);
            statement.setInt(6, capability.tools() ? 1 : 0);
            statement.setInt(7, capability.maxContext());
            statement.setInt(8, capability.maxOutput());
            statement.setString(9, capability.source());
            statement.setLong(10, capability.updatedAt());
            statement.executeUpdate();
        }
    }

    public synchronized void deleteCapability(String accountName, String model) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM chat_model_capability WHERE account_name=? AND model=?")) {
            statement.setString(1, accountName);
            statement.setString(2, model);
            statement.executeUpdate();
        }
    }

    public synchronized List<Capability> capabilities(String accountName) throws SQLException {
        List<Capability> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM chat_model_capability WHERE account_name=? ORDER BY model")) {
            statement.setString(1, accountName);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(new Capability(accountName, rs.getString("model"),
                            rs.getInt("vision") != 0, rs.getInt("files") != 0,
                            rs.getInt("reasoning") != 0, rs.getInt("tools") != 0,
                            rs.getInt("max_context"), rs.getInt("max_output"),
                            rs.getString("source"), rs.getLong("updated_at")));
                }
            }
        }
        return result;
    }

    // ------------------------------------------------------------ settings --

    public synchronized @Nullable String setting(String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT value FROM chat_setting WHERE key=?")) {
            statement.setString(1, key);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    public synchronized void putSetting(String key, String value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat_setting (key, value) VALUES (?, ?)
                ON CONFLICT(key) DO UPDATE SET value=excluded.value""")) {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.executeUpdate();
        }
    }

    // ------------------------------------------------------------ stats -----

    public synchronized int countSessions() throws SQLException {
        return scalar("SELECT COUNT(*) FROM chat_session");
    }

    public synchronized int countMessages() throws SQLException {
        return scalar("SELECT COUNT(*) FROM chat_message");
    }

    public synchronized int countAttachments() throws SQLException {
        return scalar("SELECT COUNT(*) FROM chat_attachment");
    }

    private int scalar(String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    public synchronized List<String> sessionIdsBefore(long cutoff) throws SQLException {
        List<String> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM chat_session WHERE updated_at < ?")) {
            statement.setLong(1, cutoff);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getString(1));
                }
            }
        }
        return result;
    }

    /// 所有仍被附件记录引用的磁盘路径（原文件与抽取文本）。
    public synchronized List<String> attachmentFilePaths() throws SQLException {
        List<String> result = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT path, extracted_text_path FROM chat_attachment")) {
            while (rs.next()) {
                result.add(rs.getString(1));
                String text = rs.getString(2);
                if (text != null) {
                    result.add(text);
                }
            }
        }
        return result;
    }

    public synchronized Connection connection() {
        return connection;
    }

    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Closing at shutdown; nothing useful to do with a failure.
        }
    }

    // ------------------------------------------------------------ helpers --

    static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            StringBuilder hex = new StringBuilder();
            for (byte value : digest.digest()) {
                hex.append(String.format("%02x", value & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder hex = new StringBuilder();
            for (byte value : digest.digest(bytes)) {
                hex.append(String.format("%02x", value & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    private static @Nullable Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static @Nullable Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static @Nullable Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}