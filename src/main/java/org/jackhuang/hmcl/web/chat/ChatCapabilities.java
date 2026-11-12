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
import java.util.Locale;

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

    /// 内置的常见模型表。只放确定度高的判断：图片、原生文件、思考、上下文/输出上限。
    /// 判断不出来的模型留给 `unknown`，由用户在聊天页覆盖，而不是猜一个错的能力。
    static Capabilities knownModel(String model) {
        String id = model.toLowerCase(Locale.ROOT);
        if (id.isEmpty()) {
            return null;
        }
        if (id.contains("claude")) {
            boolean reasoning = id.contains("3-7") || id.contains("4-") || id.contains("opus-4")
                    || id.contains("sonnet-4") || id.contains("thinking");
            return new Capabilities(true, true, reasoning, true, 200_000,
                    reasoning ? 32_000 : 8_192, "builtin");
        }
        if (id.startsWith("gpt-4o") || id.startsWith("gpt-4.1") || id.startsWith("gpt-5")
                || id.startsWith("chatgpt-4o")) {
            return new Capabilities(true, false, id.startsWith("gpt-5"), true, 128_000, 16_384, "builtin");
        }
        if (id.startsWith("o1") || id.startsWith("o3") || id.startsWith("o4")) {
            return new Capabilities(true, false, true, true, 200_000, 100_000, "builtin");
        }
        if (id.contains("deepseek-reasoner") || id.contains("deepseek-r1")) {
            return new Capabilities(false, false, true, false, 128_000, 32_768, "builtin");
        }
        if (id.startsWith("deepseek")) {
            return new Capabilities(false, false, false, true, 128_000, 8_192, "builtin");
        }
        if (id.startsWith("gemini")) {
            return new Capabilities(true, false, id.contains("2.5") || id.contains("thinking"),
                    true, 1_000_000, 65_536, "builtin");
        }
        if (id.contains("qwen") && (id.contains("vl") || id.contains("omni"))) {
            return new Capabilities(true, false, true, true, 128_000, 16_384, "builtin");
        }
        if (id.contains("qwen3") || id.contains("qwq")) {
            return new Capabilities(false, false, true, true, 128_000, 32_768, "builtin");
        }
        if (id.startsWith("qwen")) {
            return new Capabilities(false, false, false, true, 128_000, 8_192, "builtin");
        }
        if (id.contains("glm-4v") || id.contains("glm-4.5v") || id.contains("glm-4.6v")) {
            return new Capabilities(true, false, true, true, 128_000, 16_384, "builtin");
        }
        if (id.contains("glm")) {
            return new Capabilities(false, false, id.contains("glm-4.5") || id.contains("glm-4.6"),
                    true, 128_000, 16_384, "builtin");
        }
        if (id.contains("kimi") || id.contains("moonshot")) {
            boolean vision = id.contains("vision") || id.contains("latest");
            boolean reasoning = id.contains("thinking") || id.contains("k2");
            return new Capabilities(vision, false, reasoning, true, 256_000, 16_384, "builtin");
        }
        if (id.contains("llama") || id.contains("mistral") || id.contains("mixtral")) {
            boolean vision = id.contains("vision") || id.contains("pixtral");
            return new Capabilities(vision, false, false, true, 128_000, 8_192, "builtin");
        }
        if (id.contains("grok")) {
            boolean vision = id.contains("vision") || id.contains("2");
            return new Capabilities(vision, false, id.contains("3"), true, 131_072, 16_384, "builtin");
        }
        return null;
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