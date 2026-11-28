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
package org.jackhuang.hmcl.web.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionCookiesTest {

    @Test
    void theNameCarriesTheInstanceAndThePort() {
        String a3080 = SessionCookies.forInstance("a".repeat(32), 3080);
        String a3081 = SessionCookies.forInstance("a".repeat(32), 3081);
        String b3080 = SessionCookies.forInstance("b".repeat(32), 3080);

        assertTrue(a3080.startsWith("hdsl_session_"), a3080);
        assertTrue(a3080.endsWith("_3080"), a3080);
        assertTrue(a3081.endsWith("_3081"), a3081);
        assertNotEquals(a3080, b3080, "different panels must not share a cookie");
        assertNotEquals(a3080, a3081, "the same panel on two ports must not share a cookie");
    }

    @Test
    void noInstanceFallsBackToTheLegacyName() {
        assertEquals(SessionCookies.LEGACY, SessionCookies.forInstance(null, 0));
        assertEquals(SessionCookies.LEGACY, SessionCookies.forInstance("", 0));
    }

    @Test
    void bindingUsesTheLegacyNameForBlankValues() {
        try {
            SessionCookies.bind("hdsl_session_test_3080");
            assertEquals("hdsl_session_test_3080", SessionCookies.name());
            SessionCookies.bind("");
            assertEquals(SessionCookies.LEGACY, SessionCookies.name());
        } finally {
            SessionCookies.bind(SessionCookies.LEGACY);
        }
    }
}