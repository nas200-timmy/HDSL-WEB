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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The session scan behind the menu's "恢复 dsh 会话" section: real
/// directory layout on disk, both the bare `<uuid>` and the older
/// `session-<uuid>` spellings, sidecar titles with graceful fallback, lock
/// freshness, newest-first ordering. The zstd main log is never parsed — a
/// plain file with that name stands in for it.
class SessionCatalogTest {

    @TempDir
    Path instances;

    private static final String UUID_1 = "11111111-2222-3333-4444-555555555555";
    private static final String UUID_2 = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final String UUID_3 = "99999999-8888-7777-6666-555555555555";

    /// `instances/<id>/home/sessions/<slug>/<session-dir>` plus the sidecar
    /// at `instances/<id>/home/storages/session_projcache/sessions/<uuid>.json`.
    private Path sessionDir(String instance, String slug, String directoryName) throws Exception {
        Path directory = instances.resolve(instance).resolve("home").resolve("sessions")
                .resolve(slug).resolve(directoryName);
        Files.createDirectories(directory);
        return directory;
    }

    private void sidecar(String instance, String uuid, String json) throws Exception {
        Path file = instances.resolve(instance).resolve("home")
                .resolve("storages").resolve("session_projcache").resolve("sessions")
                .resolve(uuid + ".json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, json);
    }

    @Test
    void listsBothDirectorySpellingsNewestFirst() throws Exception {
        Instant recent = Instant.now().minusSeconds(60);
        Instant older = Instant.now().minusSeconds(3600);

        Path bare = sessionDir("dsh-1", "--data-x--", UUID_1);
        Files.write(bare.resolve(SessionCatalog.LOG_FILE), new byte[]{1});
        Files.setLastModifiedTime(bare.resolve(SessionCatalog.LOG_FILE), FileTime.from(recent));

        Path legacy = sessionDir("dsh-1", "--home-y--", SessionCatalog.LEGACY_PREFIX + UUID_2);
        Files.write(legacy.resolve(SessionCatalog.LOG_FILE), new byte[]{1});
        Files.setLastModifiedTime(legacy.resolve(SessionCatalog.LOG_FILE), FileTime.from(older));

        sidecar("dsh-1", UUID_1, """
                {"version":7,"record":{"rows":{"title":{"ver":1,"seq":2,"val":"克隆仓库并分析改进点"}}}}
                """);
        sidecar("dsh-1", UUID_2, """
                {"version":7,"record":{"rows":{"title":{"ver":1,"seq":2,"val":null}}}}
                """);

        List<SessionCatalog.Entry> entries = new SessionCatalog(instances).list();
        assertEquals(2, entries.size());
        assertEquals(UUID_1, entries.get(0).uuid(), "newest activity first");
        assertEquals("克隆仓库并分析改进点", entries.get(0).title());
        assertEquals(UUID_2, entries.get(1).uuid());
        assertEquals(UUID_2, entries.get(1).title(), "a null sidecar title falls back to the uuid");
        assertEquals("dsh-1", entries.get(0).instanceId());
        assertFalse(entries.get(0).inUse());
        assertEquals("dsh-1:" + UUID_1, entries.get(0).id());
    }

    @Test
    void freshLockMarksTheSessionAsInUse() throws Exception {
        Path directory = sessionDir("dsh-2", "--data-x--", UUID_3);
        Files.write(directory.resolve(SessionCatalog.LOG_FILE), new byte[]{1});
        Files.write(directory.resolve(SessionCatalog.LOCK_FILE), new byte[0]);

        List<SessionCatalog.Entry> entries = new SessionCatalog(instances).list();
        assertEquals(1, entries.size());
        assertTrue(entries.get(0).inUse(), "a fresh session.lock means the web UI holds it");
    }

    @Test
    void staleLockIsNotInUse() throws Exception {
        Path directory = sessionDir("dsh-2", "--data-x--", UUID_3);
        Files.write(directory.resolve(SessionCatalog.LOG_FILE), new byte[]{1});
        Files.write(directory.resolve(SessionCatalog.LOCK_FILE), new byte[0]);
        Files.setLastModifiedTime(directory.resolve(SessionCatalog.LOCK_FILE),
                FileTime.from(Instant.now().minusSeconds(10 * 60)));

        List<SessionCatalog.Entry> entries = new SessionCatalog(instances).list();
        assertEquals(1, entries.size());
        assertFalse(entries.get(0).inUse(), "a lock older than two minutes is a leftover");
    }

    @Test
    void missingSidecarAndJunkDirectoriesDegrade() throws Exception {
        Path directory = sessionDir("dsh-1", "--data-x--", UUID_1);
        Files.write(directory.resolve(SessionCatalog.LOG_FILE), new byte[]{1});
        // Not a session directory at all: no uuid shape, plus a stray file.
        Files.createDirectories(instances.resolve("dsh-1").resolve("home").resolve("sessions")
                .resolve("--data-x--").resolve("tmp-stuff"));
        Files.writeString(instances.resolve("dsh-1").resolve("home").resolve("sessions")
                .resolve("--data-x--").resolve("notes.txt"), "x");
        // A malformed sidecar next to a valid session.
        sidecar("dsh-1", UUID_1, "{not json");

        List<SessionCatalog.Entry> entries = new SessionCatalog(instances).list();
        assertEquals(1, entries.size(), "junk is skipped, the malformed sidecar degrades to the uuid");
        assertEquals(UUID_1, entries.get(0).title());
    }

    @Test
    void emptyRootYieldsNothing() {
        assertTrue(new SessionCatalog(instances.resolve("absent")).list().isEmpty());
        assertTrue(new SessionCatalog(instances).list().isEmpty());
    }
}
