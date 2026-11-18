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
package org.jackhuang.hmcl.web.acme;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CertificateServiceTest {

    @Test
    void parsesTheRetryAfterFromARateLimitMessage() {
        String message = "too many certificates (5) already issued for this exact set of identifiers "
                + "in the last 168h0m0s, retry after 2026-11-19 09:33:30 UTC: see https://...";
        assertEquals(Instant.parse("2026-11-19T09:33:30Z"), CertificateService.retryAfter(message));
    }

    @Test
    void ignoresMessagesWithoutARetryAfter() {
        assertNull(CertificateService.retryAfter("Network error"));
        assertNull(CertificateService.retryAfter(null));
    }
}
