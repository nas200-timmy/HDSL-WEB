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
package org.jackhuang.hmcl.web.developer;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The shared developer elevation: per session, sliding, and dead with its
/// session.
class DeveloperElevationTest {

    @Test
    void theKeyIsTheSessionTokenNeverTheUsername() {
        assertEquals("token:abc", DeveloperElevation.key("abc", "10.0.0.1"));
        assertEquals("anonymous:10.0.0.1", DeveloperElevation.key(null, "10.0.0.1"));
        assertEquals("anonymous:unknown", DeveloperElevation.key("", null));
    }

    @Test
    void elevationSlidesAndDiesWithItsSession() {
        DeveloperElevation elevation = new DeveloperElevation();
        String key = DeveloperElevation.key("abc", null);

        assertFalse(elevation.isElevated(key, true));
        elevation.elevate(key);
        assertTrue(elevation.isElevated(key, true));
        assertTrue(elevation.require(key, true), "a live session keeps the elevation");
        assertFalse(elevation.isElevated(key, false), "a dead session is not elevated");
        assertFalse(elevation.require(key, false));
    }

    @Test
    void clearingEndsEveryElevation() {
        DeveloperElevation elevation = new DeveloperElevation();
        String first = DeveloperElevation.key("one", null);
        String second = DeveloperElevation.key("two", null);
        elevation.elevate(first);
        elevation.elevate(second);

        elevation.clear(first);
        assertFalse(elevation.isElevated(first, true));
        assertTrue(elevation.isElevated(second, true));

        elevation.clearAll();
        assertFalse(elevation.isElevated(second, true));
    }

    @Test
    void threeWrongPasswordsLockTheKey() {
        DeveloperElevation elevation = new DeveloperElevation();
        String key = DeveloperElevation.key("abc", null);
        Instant now = Instant.now();

        assertFalse(elevation.recordFailure(key, now));
        assertFalse(elevation.recordFailure(key, now));
        assertTrue(elevation.recordFailure(key, now), "the third failure trips the lock");
        assertTrue(elevation.isLocked(key, now));

        elevation.clearFailures(key);
        assertFalse(elevation.isLocked(key, now));
    }
}
