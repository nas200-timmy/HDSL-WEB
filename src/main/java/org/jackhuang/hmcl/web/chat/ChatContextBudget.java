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

import java.util.List;

/// 一次请求的上下文构成估算（docs/chat.md §12.3.0）。
///
/// 只用来在占用浮窗里拆出「系统提示 / 历史与提问 / 附件」三块，帮助用户判断
/// 该删附件还是该新建会话；真正的用量仍以服务商返回的 `usage.input` 为准。
@NotNullByDefault
final class ChatContextBudget {

    /// 图片按张估算。服务商按像素分块计费，这里只给一个量级，不参与任何拒绝判断。
    static final int IMAGE_TOKENS = 1_000;

    record Estimate(int system, int history, int attachments, int images) {

        int total() {
            return system + history + attachments;
        }
    }

    private ChatContextBudget() {
    }

    /// `system` 是已经拼好的系统提示（含搜索声明）；`tools` 是这一轮下发的工具声明，
    /// 它们会和系统提示一起发送，所以归到「系统提示」一栏。
    static Estimate estimate(@Nullable String system,
                             List<ChatProtocol.ChatMessage> messages,
                             List<ChatProtocol.ToolSpec> tools) {
        int systemChars = system == null ? 0 : system.length();
        for (ChatProtocol.ToolSpec tool : tools) {
            systemChars += tool.name().length() + tool.description().length()
                    + tool.parametersJson().length();
        }
        int historyChars = 0;
        int attachmentChars = 0;
        int images = 0;
        for (ChatProtocol.ChatMessage message : messages) {
            if (message.text() != null) {
                historyChars += message.text().length();
            }
            for (ChatProtocol.Attachment attachment : message.attachments()) {
                if (attachment.text() != null) {
                    attachmentChars += attachment.text().length();
                }
                if ("image".equals(attachment.kind())) {
                    images++;
                }
            }
        }
        return new Estimate(tokens(systemChars), tokens(historyChars),
                tokens(attachmentChars) + images * IMAGE_TOKENS, images);
    }

    /// 与 `ChatTurnManager#estimateInputTokens` 同一口径：约 3 字符 / token。
    static int tokens(int chars) {
        return chars <= 0 ? 0 : (chars + 2) / 3;
    }
}
