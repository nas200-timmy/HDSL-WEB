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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// 上下文超限的识别：不同供应商的措辞归到同一个 `context_exceeded`，普通 400 不受影响。
class ChatProtocolContextTest {

    @Test
    void openAiContextLengthBecomesContextExceeded() {
        String[] parts = ChatProtocol.httpError(400, body(
                "This model's maximum context length is 128000 tokens. "
                        + "However, your messages resulted in 130000 tokens.")).split("\u0000", 3);
        assertEquals("context_exceeded", parts[0]);
        assertEquals("false", parts[1]);
        assertTrue(parts[2].startsWith("上下文已满"));
    }

    @Test
    void anthropicPromptTooLongBecomesContextExceeded() {
        String[] parts = ChatProtocol.httpError(400, body(
                "prompt is too long: 210000 tokens > 200000 maximum")).split("\u0000", 3);
        assertEquals("context_exceeded", parts[0]);
        assertEquals("false", parts[1]);
    }

    @Test
    void plainBadRequestStaysBadRequest() {
        String[] parts = ChatProtocol.httpError(400, body("invalid model")).split("\u0000", 3);
        assertEquals("bad_request", parts[0]);
        assertFalse(parts[2].startsWith("上下文已满"));
    }

    @Test
    void rateLimitIsStillRetryable() {
        String[] parts = ChatProtocol.httpError(429, body("slow down")).split("\u0000", 3);
        assertEquals("rate_limited", parts[0]);
        assertEquals("true", parts[1]);
    }

    @Test
    void recognisesCommonOverflowPhrases() {
        assertTrue(ChatProtocol.isContextOverflow("context_length_exceeded"));
        assertTrue(ChatProtocol.isContextOverflow(
                "the input token count exceeds the maximum number of tokens allowed"));
        assertTrue(ChatProtocol.isContextOverflow("request too large"));
        assertFalse(ChatProtocol.isContextOverflow("invalid api key"));
        assertFalse(ChatProtocol.isContextOverflow(null));
    }

    private static ByteArrayInputStream body(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
