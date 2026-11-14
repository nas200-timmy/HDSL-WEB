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

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatTurnManagerDsmlTest {

    private static final ChatProtocol.ToolSpec SEARCH =
            new ChatProtocol.ToolSpec("search_web", "", "{}");

    @Test
    void parsesFullwidthDsmlToolCall() {
        String text = """
                <｜｜DSML｜｜ calls>
                <｜｜DSML｜｜ invoke name="search_web">
                <｜｜DSML｜｜ parameter name="query" string="true">HDSL-WEB README</｜｜DSML｜｜ parameter>
                </｜｜DSML｜｜ invoke>
                </｜｜DSML｜｜ calls>""";

        List<ChatProtocol.ToolCall> calls =
                ChatTurnManager.parseDsmlToolCalls(ChatTurnManager.normalizeDsml(text), List.of(SEARCH));

        assertEquals(1, calls.size());
        assertEquals("search_web", calls.get(0).name());
        assertEquals("HDSL-WEB README", JsonParser.parseString(calls.get(0).arguments())
                .getAsJsonObject().get("query").getAsString());
        assertTrue(calls.get(0).id().startsWith("call_dsml_"));
    }

    @Test
    void parsesAsciiDsmlToolCall() {
        String text = """
                <|DSML| calls>
                <|DSML| invoke name="search_web">
                <|DSML| parameter name="query" string="true">panel chat</|DSML| parameter>
                </|DSML| invoke>
                </|DSML| calls>""";

        List<ChatProtocol.ToolCall> calls =
                ChatTurnManager.parseDsmlToolCalls(ChatTurnManager.normalizeDsml(text), List.of(SEARCH));

        assertEquals(1, calls.size());
        assertEquals("panel chat", JsonParser.parseString(calls.get(0).arguments())
                .getAsJsonObject().get("query").getAsString());
    }

    @Test
    void ignoresUnknownTools() {
        String text = """
                <｜｜DSML｜｜ calls>
                <｜｜DSML｜｜ invoke name="search_web">
                <｜｜DSML｜｜ parameter name="query" string="true">x</｜｜DSML｜｜ parameter>
                </｜｜DSML｜｜ invoke>
                </｜｜DSML｜｜ calls>""";

        assertTrue(ChatTurnManager.parseDsmlToolCalls(
                ChatTurnManager.normalizeDsml(text),
                List.of(new ChatProtocol.ToolSpec("scrape_web", "", "{}"))).isEmpty());
    }

    @Test
    void extractsCallsFromWrappedText() {
        String text = """
                before
                <|DSML| calls>
                <|DSML| invoke name="search_web">
                <|DSML| parameter name="query" string="true">wrapped</|DSML| parameter>
                </|DSML| invoke>
                </|DSML| calls>
                after""";

        List<ChatProtocol.ToolCall> calls =
                ChatTurnManager.dsmlCallsFromText(text, List.of(SEARCH));
        assertEquals(1, calls.size());
        assertEquals("wrapped", JsonParser.parseString(calls.get(0).arguments())
                .getAsJsonObject().get("query").getAsString());
    }
}
