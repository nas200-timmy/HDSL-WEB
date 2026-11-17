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

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// 上下文构成估算：系统提示（含工具声明）、历史与提问、附件（文本 + 图片张数）。
class ChatContextBudgetTest {

    @Test
    void splitsSystemHistoryAndAttachments() {
        List<ChatProtocol.ToolSpec> tools = List.of(
                new ChatProtocol.ToolSpec("tool", "abc", "xyz"));

        List<ChatProtocol.ChatMessage> messages = List.of(
                new ChatProtocol.ChatMessage("user", "a".repeat(300), List.of(
                        new ChatProtocol.Attachment("a.txt", "text/plain", "text", 6,
                                Path.of("a.txt"), "abcdef"),
                        new ChatProtocol.Attachment("b.png", "image/png", "image", 100,
                                Path.of("b.png"), null))),
                new ChatProtocol.ChatMessage("assistant", "abc", List.of()));

        ChatContextBudget.Estimate estimate =
                ChatContextBudget.estimate("abc", messages, tools);

        // 系统：3 + (4 + 3 + 3) = 13 字符 → 5 tokens
        assertEquals(5, estimate.system());
        // 历史：300 + 3 = 303 字符 → 101 tokens
        assertEquals(101, estimate.history());
        // 附件：6 字符 → 2 tokens，再加一张图片的固定量
        assertEquals(2 + ChatContextBudget.IMAGE_TOKENS, estimate.attachments());
        assertEquals(1, estimate.images());
        assertEquals(estimate.system() + estimate.history() + estimate.attachments(),
                estimate.total());
    }

    @Test
    void emptyContextIsZero() {
        ChatContextBudget.Estimate estimate =
                ChatContextBudget.estimate(null, List.of(), List.of());
        assertEquals(0, estimate.system());
        assertEquals(0, estimate.history());
        assertEquals(0, estimate.attachments());
        assertEquals(0, estimate.images());
    }

    @Test
    void roundsCharsUpToTokens() {
        assertEquals(0, ChatContextBudget.tokens(0));
        assertEquals(1, ChatContextBudget.tokens(1));
        assertEquals(1, ChatContextBudget.tokens(3));
        assertEquals(2, ChatContextBudget.tokens(4));
    }
}
