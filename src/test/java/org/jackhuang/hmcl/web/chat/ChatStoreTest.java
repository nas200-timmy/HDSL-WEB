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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// 会话的线性节点模型：variant、两种重生成前提与删除截断。
class ChatStoreTest {

    @Test
    void seedsTheBuiltInAssistantAndRoundTripsASession(@TempDir Path data) throws Exception {
        ChatStore store = new ChatStore(data);
        try {
            ChatStore.Assistant assistant = store.assistant("default");
            assertNotNull(assistant);
            assertTrue(assistant.builtin());
            assertEquals("默认助手", assistant.name());

            ChatStore.Session session = store.createSession("default", "新会话", "m", "acc");
            ChatStore.Node user = store.appendUserNode(session.id(), "hello", "m", List.of());
            ChatStore.Message userMessage = user.active();
            assertNotNull(userMessage);
            assertEquals("hello", userMessage.content());

            ChatStore.Node reply = store.beginAssistantNode(session.id(), "m");
            ChatStore.Message first = reply.active();
            assertNotNull(first);
            assertEquals("streaming", first.status());
            store.updateMessage(first.id(), "hi", "", "done", "{\"input\":1,\"output\":2}", false);

            ChatStore.Message second = store.addVariant(reply.id(), "hey", "", "done", "m", null);
            store.setActiveVariant(reply.id(), second.id());

            ChatStore.Session reloaded = store.session(session.id());
            assertNotNull(reloaded);
            assertEquals(2, reloaded.nodes().size());
            ChatStore.Node assistantNode = reloaded.nodes().get(1);
            assertEquals(2, assistantNode.variants().size());
            assertEquals("hey", assistantNode.active().content());
        } finally {
            store.close();
        }
    }

    @Test
    void truncateAndDeleteKeepTheListLinear(@TempDir Path data) throws Exception {
        ChatStore store = new ChatStore(data);
        try {
            ChatStore.Session session = store.createSession("default", "t", "m", "a");
            ChatStore.Node user = store.appendUserNode(session.id(), "q", "m", List.of());
            ChatStore.Node reply = store.beginAssistantNode(session.id(), "m");
            store.updateMessage(reply.active().id(), "a1", "", "done", null, false);
            ChatStore.Node second = store.beginAssistantNode(session.id(), "m");
            store.updateMessage(second.active().id(), "a2", "", "done", null, false);

            store.truncateAfter(session.id(), user.id());
            assertEquals(1, store.session(session.id()).nodes().size());

            store.deleteNodeAndAfter(session.id(), user.id());
            assertEquals(0, store.session(session.id()).nodes().size());
        } finally {
            store.close();
        }
    }

    @Test
    void builtInAssistantCannotBeDeletedAndOthersMigrateSessions(@TempDir Path data) throws Exception {
        ChatStore store = new ChatStore(data);
        try {
            assertThrows(IllegalArgumentException.class, () -> store.deleteAssistant("default"));
            ChatStore.Assistant custom = store.createAssistant(new ChatStore.Assistant(null,
                    "角色", "🎭", "", "you are someone", "", null, null, null, null, null, "[]",
                    false, 0, 0));
            ChatStore.Session session = store.createSession(custom.id(), "t", "m", "a");
            store.deleteAssistant(custom.id());
            assertEquals("default", store.session(session.id()).assistantId());
            assertNull(store.assistant(custom.id()));
        } finally {
            store.close();
        }
    }

    @Test
    void reopeningKeepsTheDataAndInterruptsStreaming(@TempDir Path data) throws Exception {
        String sessionId;
        ChatStore store = new ChatStore(data);
        try {
            sessionId = store.createSession("default", "t", "m", "a").id();
            store.beginAssistantNode(sessionId, "m");
        } finally {
            store.close();
        }
        ChatStore reopened = new ChatStore(data);
        try {
            ChatStore.Session session = reopened.session(sessionId);
            assertNotNull(session);
            assertEquals("interrupted", session.nodes().get(0).active().status());
            assertFalse(reopened.assistants().isEmpty());
        } finally {
            reopened.close();
        }
    }
}