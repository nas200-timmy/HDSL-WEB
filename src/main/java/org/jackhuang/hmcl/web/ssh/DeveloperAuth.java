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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.jackhuang.hmcl.web.auth.UserStore;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;

/// The developer password: a credential separate from the panel password that
/// gates every sensitive SSH operation (docs/ssh-terminal-channel.md).
///
/// It plays two roles with the same secret: the web developer mode (elevate
/// before touching keys, the toggle, or the password-auth switch — the thing
/// that stops a stolen web session from planting a key and turning a
/// one-off login into a permanent SSH backdoor) and, from M2, the TUI gate in
/// the SSH channel itself.
///
/// The hash lives apart from the user store at `<data>/auth/developer.json`:
/// PBKDF2WithHmacSHA256 with the same parameters as [UserStore] (16-byte
/// salt, 210000 iterations, 256-bit output), constant-time comparison, every
/// write atomic with owner-only permissions. It can be changed and cleared;
/// a reset or clear invalidates every elevated session, which the API layer
/// enforces after calling [set]/[clear].
@NotNullByDefault
public final class DeveloperAuth {

    /// Shortest accepted developer password.
    public static final int PASSWORD_MIN_LENGTH = 8;

    /// Default PBKDF2 iteration count, the same value [UserStore] uses.
    public static final int DEFAULT_ITERATIONS = 210_000;

    /// Name of the file inside `<data>/auth/`.
    public static final String FILE_NAME = "developer.json";

    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;
    private static final String HASH_ALGORITHM = "PBKDF2WithHmacSHA256";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final SecureRandom RANDOM = new SecureRandom();

    /// One stored hash. `saltB64`/`hashB64` are standard base64.
    public record StoredHash(String saltB64, int iterations, String hashB64, String createdAt) {
    }

    private final Path file;
    private @Nullable StoredHash stored;

    private DeveloperAuth(Path file, @Nullable StoredHash stored) {
        this.file = file;
        this.stored = stored;
    }

    /// Opens the store rooted at `dataDir`, reading the existing hash when
    /// there is one. A malformed file fails the open — like a malformed
    /// `users.json` does — so a broken credential never passes silently.
    ///
    /// @param dataDir the HDSL_DATA directory
    public static DeveloperAuth open(Path dataDir) throws IOException {
        Path authDir = dataDir.resolve("auth");
        Files.createDirectories(authDir);
        setOwnerOnly(authDir);

        Path file = authDir.resolve(FILE_NAME);
        if (Files.exists(file)) {
            return new DeveloperAuth(file, load(file));
        }
        return new DeveloperAuth(file, null);
    }

    /// Whether a developer password has been set.
    public synchronized boolean isSet() {
        return stored != null;
    }

    /// Stores a new developer password, replacing any previous one.
    ///
    /// @param password the new secret; at least [PASSWORD_MIN_LENGTH] characters
    /// @param users    the panel user store, to enforce the rule that the
    ///                 developer password must differ from the panel password
    /// @throws IllegalArgumentException when the password is too short or
    ///                                  equals the panel password
    public synchronized void set(String password, UserStore users) throws IOException {
        if (password == null || password.length() < PASSWORD_MIN_LENGTH) {
            throw new IllegalArgumentException("the developer password must be at least "
                    + PASSWORD_MIN_LENGTH + " characters");
        }
        UserStore.User admin = users.admin();
        if (admin != null && users.verify(admin.username(), password)) {
            throw new IllegalArgumentException("the developer password must differ from the panel password");
        }
        StoredHash created = create(password, DEFAULT_ITERATIONS);
        save(file, created);
        stored = created;
    }

    /// Verifies a candidate in constant time. False when no password is set.
    public boolean verify(String password) {
        StoredHash hash = stored;
        if (hash == null) {
            return false;
        }
        byte[] salt;
        byte[] expected;
        try {
            salt = Base64.getDecoder().decode(hash.saltB64());
            expected = Base64.getDecoder().decode(hash.hashB64());
        } catch (IllegalArgumentException e) {
            return false;
        }
        byte[] actual = pbkdf2(password, salt, hash.iterations());
        return MessageDigest.isEqual(expected, actual);
    }

    /// Removes the developer password: the file is deleted and every
    /// elevation built on it is dead (the API layer clears those maps).
    public synchronized void clear() throws IOException {
        Files.deleteIfExists(file);
        stored = null;
    }

    private static StoredHash create(String password, int iterations) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] hash = pbkdf2(password, salt, iterations);
        return new StoredHash(
                Base64.getEncoder().encodeToString(salt),
                iterations,
                Base64.getEncoder().encodeToString(hash),
                Instant.now().toString());
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance(HASH_ALGORITHM).generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 is missing from this JRE", e);
        } catch (InvalidKeySpecException e) {
            throw new IllegalStateException("PBKDF2 key derivation failed", e);
        } finally {
            spec.clearPassword();
        }
    }

    private static @Nullable StoredHash load(Path file) throws IOException {
        String json;
        try {
            json = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IOException("Failed to read " + file + ": " + e.getMessage(), e);
        }
        if (json.isBlank()) {
            return null;
        }
        try {
            JsonElement tree = JsonParser.parseString(json);
            if (tree.isJsonNull() || (tree.isJsonObject() && !tree.getAsJsonObject().has("hashB64"))) {
                // The file exists but holds no record: a previous boot died
                // between creating it and writing the hash. Treat as fresh.
                return null;
            }
            StoredHash hash = GSON.fromJson(tree, StoredHash.class);
            if (hash == null || hash.saltB64() == null || hash.hashB64() == null || hash.iterations() < 1) {
                throw new IOException(file + " is malformed: expected {saltB64, iterations, hashB64, createdAt}");
            }
            return hash;
        } catch (JsonParseException e) {
            throw new IOException(file + " is malformed JSON: " + e.getMessage(), e);
        }
    }

    private static void save(Path file, StoredHash hash) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(hash), StandardCharsets.UTF_8);
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
            // A directory without the execute bit cannot be entered, which
            // would lock the store out of its own files.
            Files.setPosixFilePermissions(path, Files.isDirectory(path) ? OWNER_DIR : OWNER_FILE);
        } catch (IOException | UnsupportedOperationException e) {
            // Non-POSIX filesystems (Windows) cannot express this; the best
            // effort above is still applied by every other filesystem.
        }
    }
}
