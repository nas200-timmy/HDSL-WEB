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
import org.jackhuang.hmcl.dsh.DshVendor;
import org.jackhuang.hmcl.web.developer.AgentScopes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Chat is a human surface: no agent ticket may read or write it, and the
/// endpoint path table is data rather than a guessed `base + "/v1/chat"`.
class ChatContractTest {

    @Test
    void agentTicketsCannotReachAnyChatRoute() {
        for (String method : new String[]{"GET", "HEAD", "POST", "PATCH", "DELETE"}) {
            for (String path : new String[]{
                    "/api/chat", "/api/chat/", "/api/chat/sessions",
                    "/api/chat/sessions/a/messages", "/api/chat/settings",
                    "/api/chat/attachments/a", "/api/chat/assistants"}) {
                assertTrue(AgentScopes.forbidden(method, path), method + " " + path);
            }
        }
        // And the generic class table must not call a chat read "read".
        assertNull(AgentScopes.required("GET", "/api/chat/sessions"));
        assertNull(AgentScopes.required("POST", "/api/chat/sessions"));
        // Other routes still behave as before.
        assertEquals(AgentScopes.READ, AgentScopes.required("GET", "/api/instances"));
    }

    @Test
    void defaultPathsFollowTheProtocolAndVendor() {
        DshAccount openai = new DshAccount(DshAccount.AccountKind.THIRD_PARTY, "openai",
                "k", "https://api.openai.com/v1", null, null, null);
        assertEquals("/models", ChatEndpoints.defaultPaths(openai,
                ChatEndpoints.OPENAI_COMPLETIONS, "https://api.openai.com/v1")[0]);
        assertEquals("/chat/completions", ChatEndpoints.defaultPaths(openai,
                ChatEndpoints.OPENAI_COMPLETIONS, "https://api.openai.com/v1")[1]);

        DshAccount anthropic = new DshAccount(DshAccount.AccountKind.THIRD_PARTY, "anthropic",
                "k", "https://api.anthropic.com", null, null, null);
        assertEquals("/v1/models?limit=1000", ChatEndpoints.defaultPaths(anthropic,
                ChatEndpoints.ANTHROPIC_MESSAGES, "https://api.anthropic.com")[0]);
        assertEquals("/v1/messages", ChatEndpoints.defaultPaths(anthropic,
                ChatEndpoints.ANTHROPIC_MESSAGES, "https://api.anthropic.com")[1]);

        DshAccount google = new DshAccount(DshAccount.AccountKind.THIRD_PARTY, "google",
                "k", "https://generativelanguage.googleapis.com/v1beta", null, null, null);
        assertEquals("/openai/chat/completions", ChatEndpoints.defaultPaths(google,
                ChatEndpoints.OPENAI_COMPLETIONS,
                "https://generativelanguage.googleapis.com/v1beta")[1]);
    }

    @Test
    void baseAndPathValidationRefusesAmbiguousAddresses() {
        assertEquals("https://api.example.com/v1",
                ChatEndpoints.normalizeBase("https://api.example.com/v1/"));
        assertThrows(IllegalArgumentException.class,
                () -> ChatEndpoints.normalizeBase("ftp://api.example.com"));
        assertThrows(IllegalArgumentException.class,
                () -> ChatEndpoints.normalizeBase("https://user:pass@api.example.com"));
        assertThrows(IllegalArgumentException.class,
                () -> ChatEndpoints.normalizeBase("https://api.example.com?x=1"));
        assertThrows(IllegalArgumentException.class,
                () -> ChatEndpoints.normalizePath("chat/completions"));
        assertThrows(IllegalArgumentException.class,
                () -> ChatEndpoints.normalizePath("/../admin"));
    }

    @Test
    void privateAddressDetectionCoversLoopbackAndCgnat() throws Exception {
        assertTrue(ChatEndpoints.isPrivate(java.net.InetAddress.getByName("127.0.0.1")));
        assertTrue(ChatEndpoints.isPrivate(java.net.InetAddress.getByName("192.168.1.5")));
        assertTrue(ChatEndpoints.isPrivate(java.net.InetAddress.getByName("100.64.0.1")));
        assertTrue(ChatEndpoints.isPrivate(java.net.InetAddress.getByName("::1")));
    }

    @Test
    void capabilityTableIsConservativeAndOverridable() {
        assertEquals(true, ChatCapabilities.knownModel("claude-sonnet-4-20250514").vision());
        assertEquals(true, ChatCapabilities.knownModel("deepseek-reasoner").reasoning());
        assertEquals(false, ChatCapabilities.knownModel("deepseek-chat").vision());
        assertEquals(true, ChatCapabilities.knownModel("gpt-4o").vision());
        assertEquals(true, ChatCapabilities.knownModel("qwen2.5-vl-72b").vision());
    }
}