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

import org.apache.sshd.common.util.buffer.ByteArrayBuffer;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/// The panel-managed `authorized_keys` of the SSH channel, at
/// `<data>/ssh/authorized_keys` (owner-only, created together with its
/// directory on first write).
///
/// The file is the same single-line OpenSSH format every `ssh-keygen` user
/// knows — `algorithm base64 [comment]` per line — so the settings page can
/// accept a pasted key verbatim and a hand-edited file still reads back.
/// Writes validate before they land: one line, an algorithm from the
/// whitelist, a base64 blob that decodes, and no duplicate blob. The file
/// holding the channel's trust roots never holds anything that did not pass.
@NotNullByDefault
public final class AuthorizedKeys {

    /// One key line of the file, as the API shows it. `index` is the
    /// 1-based line number in the file (blank and comment lines included),
    /// which is also what [remove] takes.
    public record Entry(int index, String algorithm, String comment, String fingerprintSHA256) {
    }

    /// Directory holding the channel's on-disk state, relative to the data directory.
    public static final String DIR_NAME = "ssh";

    /// Name of the key file inside [DIR_NAME].
    public static final String FILE_NAME = "authorized_keys";

    /// The only key algorithms the channel accepts. Everything else — DSA,
    /// certificates, sketchy vendor extensions — is refused at the door.
    private static final Set<String> ALLOWED_ALGORITHMS = Set.of(
            "ssh-ed25519",
            "ssh-rsa",
            "ecdsa-sha2-nistp256",
            "ecdsa-sha2-nistp384",
            "ecdsa-sha2-nistp521");

    private final Path file;

    private AuthorizedKeys(Path file) {
        this.file = file;
    }

    /// Resolves the key file under `dataDir`. Nothing is created yet: the
    /// first [add] makes the directory and the file.
    public static AuthorizedKeys open(Path dataDir) {
        return new AuthorizedKeys(dataDir.resolve(DIR_NAME).resolve(FILE_NAME));
    }

    /// Every accepted key line, in file order. A missing file is an empty
    /// list, not an error. Lines that do not parse as a whitelisted key
    /// (a hand-edited file can contain anything) are skipped rather than
    /// breaking the whole listing.
    public synchronized List<Entry> list() throws IOException {
        List<String> lines = readLines();
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Parsed parsed = parse(lines.get(i));
            if (parsed != null) {
                entries.add(new Entry(i + 1, parsed.algorithm, parsed.comment, fingerprint(parsed.blob)));
            }
        }
        return entries;
    }

    /// Appends one OpenSSH public key line. See the class comment for what
    /// "valid" means; anything invalid is an [IllegalArgumentException]
    /// before the file is touched, and a blob already present is rejected.
    public synchronized void add(String opensshPublicKey) throws IOException {
        Parsed parsed = validate(opensshPublicKey);
        List<String> lines = readLines();
        for (String line : lines) {
            Parsed existing = parse(line);
            if (existing != null && MessageDigest.isEqual(existing.blob, parsed.blob)) {
                throw new IllegalArgumentException("this key is already authorized");
            }
        }
        List<String> updated = new ArrayList<>(lines);
        updated.add(parsed.algorithm + " " + Base64.getEncoder().encodeToString(parsed.blob)
                + (parsed.comment.isEmpty() ? "" : " " + parsed.comment));
        writeLines(updated);
    }

    /// Removes the key line at `index` (the 1-based line number [Entry.index]
    /// reports). Out-of-range is an [IllegalArgumentException].
    public synchronized void remove(int index) throws IOException {
        List<String> lines = readLines();
        if (index < 1 || index > lines.size()) {
            throw new IllegalArgumentException("no authorized key at index " + index);
        }
        List<String> updated = new ArrayList<>(lines);
        updated.remove(index - 1);
        writeLines(updated);
    }

    /// Whether `key` is among the authorized keys: the key is encoded the
    /// way SSH wire-encodes it (what an OpenSSH blob is) and compared against
    /// every stored blob. This is the predicate behind the channel's
    /// publickey authenticator.
    public synchronized boolean contains(PublicKey key) throws IOException {
        ByteArrayBuffer wire = new ByteArrayBuffer();
        wire.putPublicKey(key);
        byte[] encoded = wire.getCompactData();
        for (String line : readLines()) {
            Parsed parsed = parse(line);
            if (parsed != null && MessageDigest.isEqual(parsed.blob, encoded)) {
                return true;
            }
        }
        return false;
    }

    /// The OpenSSH SHA256 fingerprint of one public key line —
    /// `SHA256:<base64-no-padding>` over the decoded blob, exactly what
    /// `ssh-keygen -lf` prints. Throws when the line is not a valid key.
    public static String fingerprint(String opensshPublicKey) {
        Parsed parsed = validate(opensshPublicKey);
        return fingerprint(parsed.blob);
    }

    private static String fingerprint(byte[] blob) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(blob);
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from this JRE", e);
        }
    }

    /// One parsed key line, or null when the line is blank, a comment, or
    /// not a whitelisted key.
    private record Parsed(String algorithm, byte[] blob, String comment) {
    }

    private static @Nullable Parsed parse(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return null;
        }
        String[] fields = trimmed.split("\\s+");
        if (fields.length < 2 || !ALLOWED_ALGORITHMS.contains(fields[0])) {
            return null;
        }
        byte[] blob;
        try {
            blob = Base64.getDecoder().decode(fields[1]);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (blob.length == 0) {
            return null;
        }
        StringBuilder comment = new StringBuilder();
        for (int i = 2; i < fields.length; i++) {
            if (i > 2) {
                comment.append(' ');
            }
            comment.append(fields[i]);
        }
        return new Parsed(fields[0], blob, comment.toString());
    }

    /// The full validation [add] applies, with reasons.
    private static Parsed validate(String opensshPublicKey) {
        if (opensshPublicKey == null) {
            throw new IllegalArgumentException("expected one OpenSSH public key line");
        }
        String line = opensshPublicKey.strip();
        if (line.isEmpty() || line.contains("\n") || line.contains("\r")) {
            throw new IllegalArgumentException("expected a single-line OpenSSH public key");
        }
        String[] fields = line.split("\\s+");
        if (fields.length < 2) {
            throw new IllegalArgumentException("expected `<algorithm> <base64> [comment]`");
        }
        if (!ALLOWED_ALGORITHMS.contains(fields[0])) {
            throw new IllegalArgumentException("unsupported key algorithm: " + fields[0]);
        }
        byte[] blob;
        try {
            blob = Base64.getDecoder().decode(fields[1]);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("the key blob is not valid base64", e);
        }
        if (blob.length == 0) {
            throw new IllegalArgumentException("the key blob is empty");
        }
        StringBuilder comment = new StringBuilder();
        for (int i = 2; i < fields.length; i++) {
            if (i > 2) {
                comment.append(' ');
            }
            comment.append(fields[i]);
        }
        return new Parsed(fields[0], blob, comment.toString());
    }

    private List<String> readLines() throws IOException {
        if (!Files.isRegularFile(file)) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
    }

    /// Rewrites the whole file atomically, owner-only: the file is the
    /// channel's trust root and its writes race with live authentications.
    private void writeLines(List<String> lines) throws IOException {
        Files.createDirectories(file.getParent());
        setOwnerOnly(file.getParent());
        Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
        Files.write(tmp, lines, StandardCharsets.UTF_8);
        setOwnerOnly(tmp);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        setOwnerOnly(file);
    }

    private static final Set<PosixFilePermission> OWNER_FILE = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private static final Set<PosixFilePermission> OWNER_DIR = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);

    private static void setOwnerOnly(Path path) {
        try {
            Files.setPosixFilePermissions(path, Files.isDirectory(path) ? OWNER_DIR : OWNER_FILE);
        } catch (IOException | UnsupportedOperationException e) {
            // Non-POSIX filesystems (Windows) cannot express this; the best
            // effort above is still applied by every other filesystem.
        }
    }
}
