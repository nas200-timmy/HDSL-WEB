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

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The account may legitimately have no key (a local endpoint that does not
/// authenticate). Sending `Authorization: Bearer ` in that case hides the real
/// cause behind an upstream 401, so a blank key must send no auth header at all.
class ChatAuthHeaderTest {

    private static ChatEndpoints.Profile profile(String protocol, String authStyle) {
        return new ChatEndpoints.Profile("acct", protocol, "https://example.test", "/models",
                "/chat/completions", authStyle, Map.of(), List.of(), null, null);
    }

    private static HttpRequest.Builder request() {
        return HttpRequest.newBuilder(URI.create("https://example.test/models")).GET();
    }

    @Test
    void aBlankKeySendsNoAuthHeader() {
        HttpRequest.Builder builder = request();
        ChatEndpoints.applyAuth(builder, profile("openai", null), "");
        assertTrue(builder.build().headers().allValues("Authorization").isEmpty());
    }

    @Test
    void aPresentKeyUsesTheBearerScheme() {
        HttpRequest.Builder builder = request();
        ChatEndpoints.applyAuth(builder, profile("openai", null), "sk-test");
        assertEquals(List.of("Bearer sk-test"),
                builder.build().headers().allValues("Authorization"));
    }

    @Test
    void anthropicStyleUsesTheXApiKeyHeader() {
        HttpRequest.Builder builder = request();
        ChatEndpoints.applyAuth(builder, profile("anthropic", "x-api-key"), "sk-ant");
        assertEquals(List.of("sk-ant"), builder.build().headers().allValues("x-api-key"));
        assertEquals(List.of("2023-06-01"),
                builder.build().headers().allValues("anthropic-version"));
        assertTrue(builder.build().headers().allValues("Authorization").isEmpty());
    }
}