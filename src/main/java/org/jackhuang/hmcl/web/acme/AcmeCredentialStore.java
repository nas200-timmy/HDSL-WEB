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
import com.google.gson.JsonArray;
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
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// Owner-only storage for ACME DNS/EAB credential **profiles**.
///
/// Path: `<data>/auth/acme.json`, 0600, atomic writes. Each profile has an id,
/// a display name, a provider, and provider-specific DNS fields; an ACME config
/// selects one by id. Multiple profiles let an operator keep, for example, a
/// production Cloudflare token and a staging one, or two Aliyun accounts.
///
/// The old single-credential file shape is loaded as one `default` profile, so
/// upgrading does not lose or re-prompt for a token.
///
/// These values must be recoverable (the service sends them to the DNS API), so
/// they cannot be PBKDF2 hashes. "Same treatment" means: owner-only file,
/// atomic write, never echoed by the API (only masked previews), never logged,
/// changes require the panel password + developer elevation, and every change
/// is audited.
@NotNullByDefault
public final class AcmeCredentialStore {

    public static final String FILE_NAME = "acme.json";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /// One credential profile. The maps are copied on the way in and out.
    public record Credential(String id, String name, String provider, String updatedAt,
                             Map<String, String> dns, Map<String, String> eab) {
        public boolean eabConfigured() {
            return eab != null && !eab.isEmpty();
        }
    }

    private static final class Stored {
        int version = 2;
        List<Entry> credentials = new ArrayList<>();
    }

    private static final class Entry {
        String id;
        String name;
        String provider;
        String updatedAt;
        Map<String, String> dns;
        Map<String, String> eab;
    }

    private final Path file;
    private final Stored stored;

    private AcmeCredentialStore(Path file, Stored stored) {
        this.file = file;
        this.stored = stored;
    }

    public static AcmeCredentialStore open(Path dataDir) throws IOException {
        Path authDir = dataDir.resolve("auth");
        Files.createDirectories(authDir);
        setOwnerOnly(authDir);
        Path file = authDir.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            return new AcmeCredentialStore(file, new Stored());
        }
        return new AcmeCredentialStore(file, load(file));
    }

    public synchronized List<Credential> all() {
        List<Credential> result = new ArrayList<>();
        for (Entry entry : stored.credentials) {
            result.add(toCredential(entry));
        }
        return List.copyOf(result);
    }

    public synchronized @Nullable Credential find(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        for (Entry entry : stored.credentials) {
            if (id.equals(entry.id)) {
                return toCredential(entry);
            }
        }
        return null;
    }

    /// The first profile matching a provider, for old callers/configs that did
    /// not have a credential id.
    public synchronized @Nullable Credential findProvider(String provider) {
        for (Entry entry : stored.credentials) {
            if (provider != null && provider.equals(entry.provider)) {
                return toCredential(entry);
            }
        }
        return null;
    }

    public synchronized boolean configured() {
        return !stored.credentials.isEmpty();
    }

    public synchronized @Nullable String provider() {
        return stored.credentials.isEmpty() ? null : stored.credentials.get(0).provider;
    }

    public synchronized @Nullable String updatedAt() {
        return stored.credentials.isEmpty() ? null : stored.credentials.get(0).updatedAt;
    }

    public synchronized boolean eabConfigured() {
        return stored.credentials.stream().anyMatch(e -> e.eab != null && !e.eab.isEmpty());
    }

    /// First profile's DNS fields; kept for callers that predate profiles.
    public synchronized Map<String, String> dnsCredentials() {
        return stored.credentials.isEmpty() || stored.credentials.get(0).dns == null
                ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(stored.credentials.get(0).dns));
    }

    public synchronized Map<String, String> eabCredentials() {
        return stored.credentials.isEmpty() || stored.credentials.get(0).eab == null
                ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(stored.credentials.get(0).eab));
    }

    /// Creates or updates a profile. A null/blank id creates a new one.
    public synchronized Credential upsert(@Nullable String id, String name, String provider,
                                          Map<String, String> dns, Map<String, String> eab)
            throws IOException {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider is required");
        }
        if (dns == null || dns.isEmpty()) {
            throw new IllegalArgumentException("DNS credentials are required");
        }
        Entry target = null;
        if (id != null && !id.isBlank()) {
            for (Entry entry : stored.credentials) {
                if (id.equals(entry.id)) {
                    target = entry;
                    break;
                }
            }
        }
        boolean created = target == null;
        if (target == null) {
            target = new Entry();
            target.id = newId();
            stored.credentials.add(target);
        }
        target.name = name == null || name.isBlank() ? provider : name.trim();
        target.provider = provider.trim();
        target.updatedAt = Instant.now().toString();
        target.dns = new LinkedHashMap<>(dns);
        target.eab = eab == null || eab.isEmpty() ? null : new LinkedHashMap<>(eab);
        save(file, stored);
        return toCredential(target);
    }

    /// Legacy single-profile API: update the first profile or create one.
    public synchronized Credential save(String provider, Map<String, String> dns,
                                        Map<String, String> eab) throws IOException {
        String id = stored.credentials.isEmpty() ? null : stored.credentials.get(0).id;
        return upsert(id, null, provider, dns, eab);
    }

    public synchronized boolean delete(String id) throws IOException {
        boolean removed = stored.credentials.removeIf(entry -> entry.id.equals(id));
        if (removed) {
            save(file, stored);
        }
        return removed;
    }

    /// Removes every profile. The issued certificate is untouched.
    public synchronized void clear() throws IOException {
        stored.credentials.clear();
        Files.deleteIfExists(file);
    }

    private static Credential toCredential(Entry entry) {
        return new Credential(entry.id, entry.name, entry.provider, entry.updatedAt,
                Collections.unmodifiableMap(new LinkedHashMap<>(entry.dns == null ? Map.of() : entry.dns)),
                Collections.unmodifiableMap(new LinkedHashMap<>(entry.eab == null ? Map.of() : entry.eab)));
    }

    private static Stored load(Path file) throws IOException {
        String json;
        try {
            json = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IOException("Failed to read " + file + ": " + e.getMessage(), e);
        }
        if (json.isBlank()) {
            return new Stored();
        }
        try {
            JsonElement tree = JsonParser.parseString(json);
            if (tree.isJsonNull()) {
                return new Stored();
            }
            if (!tree.isJsonObject()) {
                throw new IOException(file + " is malformed: expected a JSON object");
            }
            JsonObject object = tree.getAsJsonObject();
            Stored stored = new Stored();
            if (object.has("credentials") && object.get("credentials").isJsonArray()) {
                for (JsonElement element : object.getAsJsonArray("credentials")) {
                    if (!element.isJsonObject()) {
                        continue;
                    }
                    JsonObject row = element.getAsJsonObject();
                    Entry entry = new Entry();
                    entry.id = string(row, "id");
                    entry.name = string(row, "name");
                    entry.provider = string(row, "provider");
                    entry.updatedAt = string(row, "updatedAt");
                    entry.dns = map(row, "dns");
                    entry.eab = map(row, "eab");
                    if (entry.id == null || entry.provider == null || entry.dns == null) {
                        continue;
                    }
                    stored.credentials.add(entry);
                }
                return stored;
            }
            // Old single-object shape: migrate it to one `default` profile.
            Entry entry = new Entry();
            entry.id = "default";
            entry.provider = string(object, "provider");
            entry.name = entry.provider == null ? "default" : entry.provider;
            entry.updatedAt = string(object, "updatedAt");
            entry.dns = map(object, "dns");
            entry.eab = map(object, "eab");
            if (entry.provider == null || entry.dns == null || entry.dns.isEmpty()) {
                throw new IOException(file + " is malformed: provider/dns are missing");
            }
            stored.credentials.add(entry);
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

    private static String newId() {
        byte[] bytes = new byte[9];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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
