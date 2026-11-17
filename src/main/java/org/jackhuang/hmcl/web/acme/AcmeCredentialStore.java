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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.EnumSet;
import java.util.Set;

/// Owner-only storage for the ACME DNS token/AccessKey and EAB credentials.
///
/// Path: `<data>/auth/acme.json`. The file is written atomically with 0600
/// permissions. It is deliberately **not** in `server.yaml`: YAML is copied
/// around, read by operators and included in support bundles; these values
/// are bearer credentials.
///
/// Unlike the panel/developer passwords these values must be recoverable (the
/// ACME service sends them to the DNS API), so they cannot be PBKDF2 hashes.
/// "Same treatment" here means: owner-only file, atomic write, never echoed by
/// the API, never logged, changes require the panel password + developer
/// elevation, and every change is audited.
@NotNullByDefault
public final class AcmeCredentialStore {

    public static final String FILE_NAME = "acme.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /// The on-disk shape. Never expose this object or its maps to a logger.
    private static final class Stored {
        String provider;
        String updatedAt;
        Map<String, String> dns;
        Map<String, String> eab;
    }

    private final Path file;
    private @Nullable Stored stored;

    private AcmeCredentialStore(Path file, @Nullable Stored stored) {
        this.file = file;
        this.stored = stored;
    }

    public static AcmeCredentialStore open(Path dataDir) throws IOException {
        Path authDir = dataDir.resolve("auth");
        Files.createDirectories(authDir);
        setOwnerOnly(authDir);
        Path file = authDir.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            return new AcmeCredentialStore(file, null);
        }
        return new AcmeCredentialStore(file, load(file));
    }

    public synchronized boolean configured() {
        return stored != null && stored.dns != null && !stored.dns.isEmpty();
    }

    public synchronized boolean eabConfigured() {
        return stored != null && stored.eab != null && !stored.eab.isEmpty();
    }

    public synchronized @Nullable String provider() {
        return stored == null ? null : stored.provider;
    }

    public synchronized @Nullable String updatedAt() {
        return stored == null ? null : stored.updatedAt;
    }

    /// A defensive copy; callers must not log it. Empty when nothing is stored.
    public synchronized Map<String, String> dnsCredentials() {
        return stored == null || stored.dns == null
                ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(stored.dns));
    }

    /// A defensive copy; callers must not log it. Empty when no EAB is stored.
    public synchronized Map<String, String> eabCredentials() {
        return stored == null || stored.eab == null
                ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(stored.eab));
    }

    /// Writes DNS credentials and (optional) EAB credentials.
    ///
    /// @param provider DNS provider id
    /// @param dns      provider-specific fields, e.g. `{apiToken: "..."}`
    /// @param eab      `{kid: "...", hmacKey: "..."}` or empty
    public synchronized void save(String provider, Map<String, String> dns,
                                  Map<String, String> eab) throws IOException {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider is required");
        }
        if (dns == null || dns.isEmpty()) {
            throw new IllegalArgumentException("DNS credentials are required");
        }
        Stored next = new Stored();
        next.provider = provider.trim();
        next.updatedAt = Instant.now().toString();
        next.dns = new LinkedHashMap<>(dns);
        next.eab = eab == null || eab.isEmpty() ? null : new LinkedHashMap<>(eab);
        save(file, next);
        stored = next;
    }

    /// Removes DNS and EAB credentials. The issued certificate is untouched.
    public synchronized void clear() throws IOException {
        Files.deleteIfExists(file);
        stored = null;
    }

    private static @Nullable Stored load(Path file) throws IOException {
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
            if (tree.isJsonNull()) {
                return null;
            }
            if (!tree.isJsonObject()) {
                throw new IOException(file + " is malformed: expected a JSON object");
            }
            JsonObject object = tree.getAsJsonObject();
            Stored stored = new Stored();
            stored.provider = string(object, "provider");
            stored.updatedAt = string(object, "updatedAt");
            stored.dns = map(object, "dns");
            stored.eab = map(object, "eab");
            if (stored.provider == null || stored.provider.isBlank()) {
                throw new IOException(file + " is malformed: provider is missing");
            }
            if (stored.dns == null || stored.dns.isEmpty()) {
                throw new IOException(file + " is malformed: dns credentials are missing");
            }
            return stored;
        } catch (JsonParseException e) {
            throw new IOException(file + " is malformed JSON: " + e.getMessage(), e);
        }
    }

    private static @Nullable String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static @Nullable Map<String, String> map(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonObject()) {
            return null;
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
            if (entry.getValue().isJsonPrimitive()) {
                result.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
        return result;
    }

    private static void save(Path file, Stored stored) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(stored), StandardCharsets.UTF_8);
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
            // Non-POSIX filesystems (Windows) cannot express this.
        }
    }
}
