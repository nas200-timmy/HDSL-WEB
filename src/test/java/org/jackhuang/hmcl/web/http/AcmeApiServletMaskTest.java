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
package org.jackhuang.hmcl.web.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AcmeApiServletMaskTest {

    @Test
    void accessKeyIdsAreMaskedLikeSecrets() {
        assertEquals("••••gtGS",
                AcmeApiServlet.maskValue("accessKeyId", "LTAI5tK1V7Cq3byUQCQgtGS", false));
        assertEquals("••••abcd",
                AcmeApiServlet.maskValue("accessKeySecret", "secret-value-abcd", true));
        assertEquals("example.com", AcmeApiServlet.maskValue("zone", "example.com", false));
        assertEquals("••••", AcmeApiServlet.maskValue("accessKeyId", "abc", false));
        assertEquals("••••west",
                AcmeApiServlet.maskValue("secretId", "AKIDtencent-cloud-northwest", false));
    }
}
