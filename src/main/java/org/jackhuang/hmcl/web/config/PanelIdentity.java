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
package org.jackhuang.hmcl.web.config;

import org.jackhuang.hmcl.util.logging.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

/// A stable identity for one panel installation, stored in the data directory.
///
/// The browser cannot distinguish two panels by port (cookies ignore ports), so
/// the session cookie name carries this id (and the port) instead. The id is
/// **not a secret**: it only names a cookie, and it is exposed by `/api/health`
/// so testers can tell two panels apart. Two independent data directories get
/// different ids; a cloned data directory keeps the original id, so the port is
/// appended to the cookie name as well.
public final class PanelIdentity {

    private static final Logger LOG = Logger.LOG;
    private static final String FILE = "instance-id";
    private static final java.util.regex.Pattern FORMAT =
            java.util.regex.Pattern.compile("[0-9a-f]{32}");

    private static volatile @NotNull String current = "";

    private PanelIdentity() {
    }

    /// The id loaded (or generated) by [loadOrCreate], for `/api/health`.
    public static @NotNull String current() {
        return current;
    }

    /// Reads `<data>/instance-id`, creating it on first run. A data directory
    /// that cannot be written already cannot persist accounts or sessions; in
    /// that case a deterministic digest of the directory path keeps the cookie
    /// name stable across restarts instead of failing startup.
    ///
    /// @param dataDir the panel data directory
    /// @return a 32-character lowercase hex id
    public static @NotNull String loadOrCreate(Path dataDir) {
        Path file = dataDir.resolve(FILE);
        try {
            if (Files.isRegularFile(file)) {
                String existing = Files.readString(file, StandardCharsets.UTF_8)
                        .trim().toLowerCase(Locale.ROOT);
                if (FORMAT.matcher(existing).matches()) {
                    current = existing;
                    return existing;
                }
                LOG.warning("panel instance id is malformed; regenerating " + file);
            }
            Files.createDirectories(dataDir);
            String id = UUID.randomUUID().toString().replace("-", "");
            Path tmp = Files.createTempFile(dataDir, ".instance-id-", ".tmp");
            try {
                Files.writeString(tmp, id + "\n", StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
                try {
                    Files.setPosixFilePermissions(file,
                            PosixFilePermissions.fromString("rw-------"));
                } catch (UnsupportedOperationException ignored) {
                    // Windows/NTFS: the owner-only rule is not expressible this way.
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
            current = id;
            return id;
        } catch (IOException e) {
            String fallback = fallback(dataDir);
            LOG.warning("could not persist panel instance id (" + e.getMessage()
                    + "); using " + fallback);
            current = fallback;
            return fallback;
        }
    }

    private static @NotNull String fallback(Path dataDir) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(dataDir.toAbsolutePath().normalize().toString()
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 32);
        } catch (Exception e) {
            return UUID.randomUUID().toString().replace("-", "");
        }
    }
}