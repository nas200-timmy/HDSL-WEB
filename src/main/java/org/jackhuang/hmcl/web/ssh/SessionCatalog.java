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

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// The "恢复 dsh 会话" menu section: a read-only scan of the session
/// directories every dsh instance keeps in its home
/// (docs/ssh-terminal-channel.md, milestone M2).
///
/// The on-disk layout, verified against a live deployment:
///
/// ```
/// <hdsl.home>/instances/<instance>/home/
///     sessions/<cwd-slug>/<uuid>/{session.lock, session.v4.jsonl.zstd}
///     sessions/<cwd-slug>/session-<uuid>/{session.lock, session.v4.jsonl.zstd}   (older layout)
///     storages/session_projcache/sessions/<uuid>.json                            (sidecar)
/// ```
///
/// Both session directory spellings — the bare `<uuid>` and the older
/// `session-<uuid>` — appear on disk and are listed. The sidecar JSON is the
/// only thing parsed (its `rows.title.val` is the human title; every missing
/// or malformed piece degrades to showing the id). The main log
/// `session.v4.jsonl.zstd` is never opened — v1 reads its mtime as the
/// "last active" time and nothing else. A session whose `session.lock` was
/// touched within the last [LOCK_FRESHNESS] is in use by the web UI and is
/// flagged `[web 使用中]`; M3 forwards that to `dsh tui --resume` rather
/// than arbitrating the lock.
@NotNullByDefault
public final class SessionCatalog {

    /// The main session log; only its mtime is ever read.
    public static final String LOG_FILE = "session.v4.jsonl.zstd";

    /// The lock file dsh web keeps while a session is attached to the UI.
    public static final String LOCK_FILE = "session.lock";

    /// A `session.lock` younger than this means the web UI is sitting on the
    /// session right now.
    public static final Duration LOCK_FRESHNESS = Duration.ofMinutes(2);

    /// Older session directories carry this prefix before the uuid.
    public static final String LEGACY_PREFIX = "session-";

    /// One resumable session.
    ///
    /// @param instanceId the owning dsh instance
    /// @param uuid       the session id `dsh tui --resume` takes
    /// @param title      the sidecar title, or the uuid when none was found
    /// @param activity   last write of the session log
    /// @param inUse      whether the web UI holds the session lock right now
    public record Entry(String instanceId, String uuid, String title, Instant activity, boolean inUse) {

        /// The attach id: instance and session in one string.
        public String id() {
            return instanceId + ":" + uuid;
        }
    }

    /// Loose uuid shape — 8-4-4-4-12 hex. Directories that do not look like a
    /// session are skipped without a fuss.
    private static final Pattern UUID = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
            + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final Path instancesRoot;

    /// Binds the catalog to `<hdsl.home>/instances`.
    ///
    /// @param instancesRoot the instances root, usually [org.jackhuang.hmcl.dsh.DshPaths.INSTANCES]
    public SessionCatalog(Path instancesRoot) {
        this.instancesRoot = instancesRoot;
    }

    /// Every resumable session across every instance home, newest activity
    /// first. Unreadable directories are skipped one by one — a corrupt
    /// instance must not blank the whole section.
    public List<Entry> list() {
        Instant now = Instant.now();
        if (!Files.isDirectory(instancesRoot)) {
            return List.of();
        }
        List<Entry> entries = new ArrayList<>();
        try (Stream<Path> instances = Files.list(instancesRoot)) {
            for (Path instanceDirectory : instances.filter(Files::isDirectory).toList()) {
                collect(instanceDirectory, now, entries);
            }
        } catch (IOException | RuntimeException e) {
            LOG.warning("Failed to scan sessions under " + instancesRoot, e);
        }
        entries.sort(Comparator.comparing(Entry::activity).reversed());
        return entries;
    }

    private void collect(Path instanceDirectory, Instant now, List<Entry> entries) {
        String instanceId = instanceDirectory.getFileName().toString();
        Path sessionsRoot = instanceDirectory.resolve("home").resolve("sessions");
        if (!Files.isDirectory(sessionsRoot)) {
            return;
        }
        try (Stream<Path> slugs = Files.list(sessionsRoot)) {
            for (Path slug : slugs.filter(Files::isDirectory).toList()) {
                try (Stream<Path> children = Files.list(slug)) {
                    for (Path directory : children.filter(Files::isDirectory).toList()) {
                        Entry entry = scan(instanceId, instanceDirectory, directory, now);
                        if (entry != null) {
                            entries.add(entry);
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            LOG.warning("Failed to scan sessions in " + sessionsRoot, e);
        }
    }

    /// Reads one `session-*` directory. Anything missing degrades: no log
    /// file means the directory's own mtime, no lock means idle, no sidecar
    /// (or an unparsable one) means "show the uuid".
    private static @Nullable Entry scan(String instanceId, Path instanceDirectory, Path directory, Instant now) {
        String name = directory.getFileName().toString();
        String uuid = name.startsWith(LEGACY_PREFIX) ? name.substring(LEGACY_PREFIX.length()) : name;
        if (!UUID.matcher(uuid).matches()) {
            return null;
        }

        Instant activity = lastModified(directory.resolve(LOG_FILE));
        if (activity.equals(Instant.EPOCH)) {
            activity = lastModified(directory);
        }
        boolean inUse = false;
        Instant lockedAt = lastModified(directory.resolve(LOCK_FILE));
        if (!lockedAt.equals(Instant.EPOCH)
                && Duration.between(lockedAt, now).compareTo(LOCK_FRESHNESS) <= 0) {
            inUse = true;
        }

        Path sidecar = instanceDirectory.resolve("home")
                .resolve("storages").resolve("session_projcache").resolve("sessions")
                .resolve(uuid + ".json");
        String title = sidecarTitle(sidecar);
        if (title == null) {
            title = uuid;
        }
        return new Entry(instanceId, uuid, title, activity, inUse);
    }

    /// Extracts `record.rows.title.val` from the sidecar JSON. Null on every
    /// failure — a malformed sidecar is a row that shows its id, never a
    /// reason to fail the menu.
    private static @Nullable String sidecarTitle(Path sidecar) {
        if (!Files.isRegularFile(sidecar)) {
            return null;
        }
        try {
            JsonElement root = JsonParser.parseString(Files.readString(sidecar, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) {
                return null;
            }
            JsonElement rows = root.getAsJsonObject().get("record");
            if (rows == null || !rows.isJsonObject()) {
                return null;
            }
            JsonElement title = rows.getAsJsonObject().get("rows");
            if (title == null || !title.isJsonObject()) {
                return null;
            }
            JsonElement value = title.getAsJsonObject().get("title");
            if (value == null || !value.isJsonObject()) {
                return null;
            }
            JsonElement val = value.getAsJsonObject().get("val");
            return val != null && val.isJsonPrimitive() && val.getAsJsonPrimitive().isString()
                    ? val.getAsString()
                    : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static Instant lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException | RuntimeException e) {
            return Instant.EPOCH;
        }
    }
}
