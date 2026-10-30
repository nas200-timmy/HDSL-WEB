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
package org.jackhuang.hmcl.util.platform;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// When the JVM may be pointed at the Windows root store.
///
/// The decision, not the effect: the effect is this JVM's TLS, which a unit test
/// cannot switch and still run. What is pinned here is that the panel only ever
/// helps (the machine's own roots) and never overrides an operator who named a
/// trust store.
class OsTrustStoreTest {

    @Test
    void theWindowsStoreIsUsedWhenNothingWasChosen() {
        assertTrue(OsTrustStore.shouldSwitch(true, null, null, true));
        assertTrue(OsTrustStore.shouldSwitch(true, "  ", null, true));
    }

    @Test
    void anOperatorsTrustStoreWins() {
        assertFalse(OsTrustStore.shouldSwitch(true, "C:\\custom\\truststore.p12", null, true));
        assertFalse(OsTrustStore.shouldSwitch(true, null, "PKCS12", true));
        assertFalse(OsTrustStore.shouldSwitch(true, "C:\\custom\\truststore.p12", "PKCS12", true));
    }

    @Test
    void otherPlatformsAreLeftAlone() {
        assertFalse(OsTrustStore.shouldSwitch(false, null, null, true));
    }

    @Test
    void anUnreadableStoreIsNotChosen() {
        // A JDK without SunMSCAPI cannot read it; switching would break every TLS
        // connection instead of fixing the intercepted ones.
        assertFalse(OsTrustStore.shouldSwitch(true, null, null, false));
    }
}
