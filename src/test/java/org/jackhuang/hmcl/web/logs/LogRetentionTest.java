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
package org.jackhuang.hmcl.web.logs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogRetentionTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 30);

    @Test
    void auditFilesAgeByTheDateInTheirName(@TempDir Path dir) throws IOException {
        Path expired = write(dir, "audit-2026-01-01.jsonl");
        Path insideWindow = write(dir, "audit-2026-10-29.jsonl");
        Path today = write(dir, "audit-2026-10-30.jsonl");

        assertEquals(1, LogRetention.at(dir, 180, 180, noon()).sweep());

        assertFalse(Files.exists(expired), "180 天以外的审计日志应当删除");
        assertTrue(Files.exists(insideWindow));
        assertTrue(Files.exists(today));
    }

    @Test
    void auditFilesWithoutADateAreKeptEvenWhenTheirMtimeIsAncient(@TempDir Path dir) throws IOException {
        Path undated = write(dir, "audit-latest.jsonl");
        setMtime(undated, 0);

        assertEquals(0, LogRetention.at(dir, 180, 180, noon()).sweep());

        assertTrue(Files.exists(undated), "名字里没有日期就不按 mtime 猜，宁可留着");
    }

    @Test
    void panelLogsAgeByMtimeAndTheNewestOneSurvives(@TempDir Path dir) throws IOException {
        Path oldest = write(dir, "2026-01-01T00-00-00.log.xz");
        Path newest = write(dir, "2026-10-30T00-00-00.log");
        setMtime(oldest, daysAgo(200));
        // 名字最新、mtime 也在窗口外：它仍是「最新那份」，必须留下。
        setMtime(newest, daysAgo(120));

        assertEquals(1, LogRetention.at(dir, 180, 180, noon()).sweep());

        assertFalse(Files.exists(oldest));
        assertTrue(Files.exists(newest), "最新的一份永远保留，时钟错了也不能删正在写的文件");
    }

    @Test
    void freshPanelLogsAreNeverTouched(@TempDir Path dir) throws IOException {
        Path fresh = write(dir, "2026-10-30T12-00-00.log");
        setMtime(fresh, daysAgo(1));

        assertEquals(0, LogRetention.at(dir, 180, 180, noon()).sweep());
        assertTrue(Files.exists(fresh));
    }

    @Test
    void missingDirectoryIsNotAnError(@TempDir Path dir) {
        assertEquals(0, LogRetention.at(dir.resolve("nope"), 180, 180, noon()).sweep());
    }

    @Test
    void windowsAreBoundedToTheFloorAndFourYears(@TempDir Path dir) {
        assertThrows(IllegalArgumentException.class,
                () -> new LogRetention(dir, LogRetention.HARD_CAP_DAYS + 1, 180));
        assertThrows(IllegalArgumentException.class,
                () -> new LogRetention(dir, 179, 180));
        // 两端本身都允许：180 天到 1460 天。
        new LogRetention(dir, LogRetention.FLOOR_DAYS, LogRetention.HARD_CAP_DAYS);
    }

    @Test
    void auditDayOnlyAcceptsTheFileNameShape() {
        assertEquals(TODAY, LogRetention.auditDay(Path.of("audit-2026-10-30.jsonl")));
        assertNull(LogRetention.auditDay(Path.of("audit-unknown.jsonl")));
        assertNull(LogRetention.auditDay(Path.of("2026-10-30.log")));
    }

    private static Path write(Path dir, String name) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, "{}\n");
        return file;
    }

    private static void setMtime(Path file, long millis) throws IOException {
        Files.setLastModifiedTime(file, FileTime.fromMillis(millis));
    }

    private static long daysAgo(long days) {
        return Instant.now().minusSeconds(TimeUnit.DAYS.toSeconds(days)).toEpochMilli();
    }

    private static Instant noon() {
        return TODAY.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant();
    }
}
