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

import static org.junit.jupiter.api.Assertions.assertEquals;

/// 第三方把工具调用写成 `<tool_call>` XML 时，不能把标记当答案展示。
class ChatTurnManagerToolMarkupTest {

    @Test
    void aCompleteToolCallBlockIsStrippedButTheAnswerStays() {
        String text = "前言<tool_call><function=search_web><parameter=query>x</parameter>"
                + "</function></tool_call>后记";
        assertEquals("前言后记", ChatTurnManager.stripGenericToolCalls(text));
    }

    @Test
    void anUnclosedBlockIsCutAtTheFirstMarker() {
        assertEquals("半截", ChatTurnManager.stripGenericToolCalls("半截<tool_call><function=search_web>"));
        assertEquals("保留", ChatTurnManager.stripGenericToolCalls("保留<function=search_web"));
    }

    @Test
    void plainTextIsLeftAlone() {
        assertEquals("没有任何标记", ChatTurnManager.stripGenericToolCalls("没有任何标记"));
        assertEquals("说说 <tool_call> 这个标签长什么样", ChatTurnManager.stripGenericToolCalls("说说 <tool_call> 这个标签长什么样"));
    }
}
