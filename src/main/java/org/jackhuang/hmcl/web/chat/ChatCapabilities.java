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

import org.jackhuang.hmcl.dsh.DshAccount;
import org.jetbrains.annotations.NotNullByDefault;

import java.sql.SQLException;

/// 模型能力（docs/chat.md §9）。
///
/// 优先级：用户在聊天页手动覆盖 > 供应商预设 > 内置常见模型表 > unknown。
/// 这里只回答“能不能”，不改变模型实际行为；发送前的附件映射与 reasoning 折叠都读它。
@NotNullByDefault
public final class ChatCapabilities {

    public record Capabilities(
            boolean vision, boolean files, boolean reasoning, boolean tools,
            int maxContext, int maxOutput, String source) {
    }

    private ChatCapabilities() {
    }

    public static Capabilities resolve(ChatStore store, String accountName, String model,
                                       String protocol) throws SQLException {
        String cleaned = model == null ? "" : model.trim();
        ChatStore.Capability manual = store.capability(accountName, cleaned);
        if (manual != null && "manual".equals(manual.source())) {
            return new Capabilities(manual.vision(), manual.files(), manual.reasoning(),
                    manual.tools(), manual.maxContext(), manual.maxOutput(), "manual");
        }
        Capabilities preset = preset(protocol, cleaned);
        if (preset != null) {
            return preset;
        }
        Capabilities known = knownModel(cleaned);
        if (known != null) {
            return known;
        }
        if (manual != null) {
            return new Capabilities(manual.vision(), manual.files(), manual.reasoning(),
                    manual.tools(), manual.maxContext(), manual.maxOutput(), manual.source());
        }
        return new Capabilities(false, false, false, false, 32_768, 4_096, "unknown");
    }

    private static Capabilities preset(String protocol, String model) {
        if ("anthropic-messages".equals(protocol)) {
            boolean reasoning = model.contains("claude-3-7") || model.contains("claude-4")
                    || model.contains("claude-sonnet-4") || model.contains("thinking");
            return new Capabilities(true, true, reasoning, true, 200_000,
                    reasoning ? 32_000 : 8_192, "vendor");
        }
        return null;
    }

    /// 内置的常见模型表。判据在 [ChatModelRules]：按模型 id 的词元匹配，
    /// 具体型号压过家族通配，认不出来的留给 `unknown` 由用户覆盖。
    static Capabilities knownModel(String model) {
        ChatModelRules.Result rule = ChatModelRules.resolve(model);
        if (rule == null) {
            return null;
        }
        return new Capabilities(rule.vision(), rule.files(), rule.reasoning(), rule.tools(),
                rule.maxContext(), rule.maxOutput(), "builtin");
    }

    /// 供应商协议本身对附件的限制：OpenAI completions 没有原生文档块，Anthropic 有。
    public static boolean nativeDocuments(String protocol) {
        return "anthropic-messages".equals(protocol);
    }

    public static String protocolOf(ChatStore store, DshAccount account) throws SQLException {
        ChatStore.AccountProfile profile = store.profile(account.key());
        if (profile != null && profile.protocol() != null && !profile.protocol().isBlank()) {
            return profile.protocol();
        }
        return ChatEndpoints.defaultProtocol(account);
    }
}