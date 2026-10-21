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
package org.jackhuang.hmcl.web.ssh;

import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// The platform gate's three-state contract on the machine the tests run on.
/// [OperatingSystem.CURRENT_OS] is a static fact of the host, so the three
/// branches cannot each be exercised here — what can be pinned is that the
/// verdict on this host is exactly the documented one, and that
/// [PlatformGuard.supported] always agrees with it.
class PlatformGuardTest {

    @Test
    void theCurrentHostGetsExactlyTheDocumentedVerdict() {
        switch (OperatingSystem.CURRENT_OS) {
            case LINUX -> assertEquals(PlatformGuard.Verdict.SUPPORTED, PlatformGuard.verdict(),
                    "Linux is the fully supported platform");
            case MACOS -> assertEquals(PlatformGuard.Verdict.SUPPORTED_UNVERIFIED, PlatformGuard.verdict(),
                    "macOS passes but stays labelled unverified");
            default -> assertEquals(PlatformGuard.Verdict.UNSUPPORTED, PlatformGuard.verdict(),
                    OperatingSystem.CURRENT_OS + " is hard-banned");
        }
    }

    @Test
    void supportedAgreesWithTheVerdict() {
        assertEquals(PlatformGuard.verdict() != PlatformGuard.Verdict.UNSUPPORTED, PlatformGuard.supported());
        assertEquals(PlatformGuard.supported(), PlatformGuard.verdict() == PlatformGuard.Verdict.SUPPORTED
                || PlatformGuard.verdict() == PlatformGuard.Verdict.SUPPORTED_UNVERIFIED);
    }
}
