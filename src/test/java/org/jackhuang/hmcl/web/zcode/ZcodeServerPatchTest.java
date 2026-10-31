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
package org.jackhuang.hmcl.web.zcode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The one edit the panel makes to ZCode's own server entry — and the promise
/// that it is made only when the expression it edits is really there.
class ZcodeServerPatchTest {

    /// What upstream 3.17.1 ships, verbatim from
    /// `<release>/server/entry-http.js` (the bundle is unminified). The
    /// surrounding statements are kept because they are what must still hold
    /// afterwards.
    private static final String UPSTREAM = """
            async function createServerHostServices(options2) {
              const projectStorageHostId = remoteHostIdFromWorkspaceIdentity(
                buildServerRemoteWorkspaceIdentity({ serverId: options2.sourceServerId, workspacePath: "/" })
              );
              if (!projectStorageHostId) throw new Error("Invalid Source Server storage identity");
              await ensureTelemetryDeviceMid();
              return {};
            }
            """;

    @TempDir
    Path release;

    private Path server() throws IOException {
        Path entry = release.resolve(ZcodeServerPatch.SERVER_ENTRY);
        Files.createDirectories(entry.getParent());
        Files.writeString(entry, UPSTREAM, StandardCharsets.UTF_8);
        return entry;
    }

    /// The owner becomes `local`, the statement's tail stays satisfied, and the
    /// rest of the file is untouched.
    @Test
    void theOwnerBecomesLocalAndNothingElseMoves() throws IOException {
        Path entry = server();

        assertEquals(ZcodeServerPatch.Outcome.PATCHED, ZcodeServerPatch.ensure(release));

        String patched = Files.readString(entry, StandardCharsets.UTF_8);
        assertTrue(patched.contains("const projectStorageHostId = \"local\";"), patched);
        assertFalse(patched.contains("remoteHostIdFromWorkspaceIdentity("), patched);
        // The guard upstream keeps right after the assignment must still make
        // sense: a literal is never falsy.
        assertTrue(patched.contains("throw new Error(\"Invalid Source Server storage identity\")"), patched);
        assertTrue(patched.contains("await ensureTelemetryDeviceMid();"), patched);
    }

    /// Starting a release is something a panel does many times; the file is
    /// written once and left alone afterwards.
    @Test
    void aSecondStartDoesNotTouchTheFileAgain() throws IOException {
        Path entry = server();
        assertEquals(ZcodeServerPatch.Outcome.PATCHED, ZcodeServerPatch.ensure(release));
        byte[] once = Files.readAllBytes(entry);

        assertEquals(ZcodeServerPatch.Outcome.ALREADY_PATCHED, ZcodeServerPatch.ensure(release));

        assertArrayEquals("the file was rewritten on a start that had nothing to do", once,
                Files.readAllBytes(entry));
    }

    /// Upstream will move this anchor eventually. The release is then left
    /// exactly as it is — the panel reports it once and the page still opens —
    /// rather than being written half-recognised.
    @Test
    void anAnchorThatMovedLeavesTheReleaseByteForByte() throws IOException {
        Path entry = release.resolve(ZcodeServerPatch.SERVER_ENTRY);
        Files.createDirectories(entry.getParent());
        String withoutAnchor = UPSTREAM.replace(
                "const projectStorageHostId = remoteHostIdFromWorkspaceIdentity(", "const owner = (");
        Files.writeString(entry, withoutAnchor, StandardCharsets.UTF_8);

        assertEquals(ZcodeServerPatch.Outcome.ANCHOR_MISSING, ZcodeServerPatch.ensure(release));

        assertEquals(withoutAnchor, Files.readString(entry, StandardCharsets.UTF_8));
    }

    /// A TUI-only build has no server entry at all, and that is not a failure.
    @Test
    void aReleaseWithoutAServerEntryIsNotAServer() throws IOException {
        Files.createDirectories(release.resolve("bin"));

        assertEquals(ZcodeServerPatch.Outcome.NOT_A_SERVER, ZcodeServerPatch.ensure(release));
    }

    private static void assertArrayEquals(String message, byte[] expected, byte[] actual) {
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual, message);
    }
}
