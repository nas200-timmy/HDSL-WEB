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
package org.jackhuang.hmcl.web.brand;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// A never-opened Octop has an empty `users` table; `user passwd` answers "user not
/// found", and the panel must switch to creating the admin instead of surfacing that
/// error as the reset result.
class BrandAdminPasswordTest {

    @Test
    void theCliUnknownUserMessageIsRecognised() {
        assertTrue(BrandApiServlet.isUnknownUser(
                "octop user passwd failed (exit 1): error: user not found: admin"));
        assertTrue(BrandApiServlet.isUnknownUser("ERROR: User not found"));
    }

    @Test
    void theWizardPasswordIsReadEvenWithoutTheOtherCredentialFiles(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path home) throws Exception {
        java.nio.file.Files.writeString(home.resolve("octop-login.txt"), "wizard-pass-123\n");
        assertEquals("wizard-pass-123", BrandApiServlet.wizardPassword(home));

        java.nio.file.Files.writeString(home.resolve("octop-login.txt"), "  \n");
        assertNull(BrandApiServlet.wizardPassword(home), "空文件不算有启动密码");

        assertNull(BrandApiServlet.wizardPassword(home.resolve("missing")), "没有文件时返回 null");
    }

    @Test
    void otherFailuresAreNotMistakenForAMissingUser() {
        assertFalse(BrandApiServlet.isUnknownUser("database is locked"));
        assertFalse(BrandApiServlet.isUnknownUser("no output"));
        assertFalse(BrandApiServlet.isUnknownUser(null));
    }
}