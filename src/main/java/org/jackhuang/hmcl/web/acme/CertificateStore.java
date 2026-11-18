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
import com.google.gson.JsonParseException;
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
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/// The certificates this panel manages, grouped by their **apex domain**
/// (`example.com`), with the subdomains they cover as members.
///
/// One apex may hold several certificates (default one): the current ACME
/// certificate, an uploaded one kept as a fallback, or a split certificate.
/// Exactly one entry is `active` — the identity Jetty actually serves — which
/// is what replaces the old "PKCS#12 silently wins the load order" rule.
///
/// Storage: `<data>/certs/registry.json`, 0600. Key material lives next to it
/// in `<data>/certs/<id>/`.
@NotNullByDefault
public final class CertificateStore {

    public static final String FILE_NAME = "registry.json";

    public enum Source {
        /// Issued and renewed by this panel through ACME.
        ACME,
        /// Imported from a PEM/PKCS#12 file; never renewed automatically.
        UPLOAD
    }

    /// One managed certificate. Mutable on purpose: the service updates the
    /// issuance result in place and persists it with [#save].
    public static final class Entry {
        public String id;
        public String apex;
        /// Names the operator wants on the certificate (desired state).
        public List<String> domains = new ArrayList<>();
        /// Names on the certificate that is currently stored on disk.
        public List<String> issuedDomains = new ArrayList<>();
        public Source source = Source.ACME;
        public boolean active;
        public boolean enabled = true;

        // ACME settings (ignored for uploads).
        public String ca = "letsencrypt";
        public String email = "";
        public String provider = "cloudflare";
        public String credentialId = "";
        public int renewBeforeDays = 30;
        public boolean staging;
        public boolean acceptTerms;

        // Last known state, persisted so the list is useful after a restart.
        public String notAfter;
        public String lastError;
        public String lastAttempt;
        public String updatedAt;

        public Entry copy() {
            Entry copy = new Entry();
            copy.id = id;
            copy.apex = apex;
            copy.domains = new ArrayList<>(domains);
            copy.issuedDomains = new ArrayList<>(issuedDomains);
            copy.source = source;
            copy.active = active;
            copy.enabled = enabled;
            copy.ca = ca;
            copy.email = email;
            copy.provider = provider;
            copy.credentialId = credentialId;
            copy.renewBeforeDays = renewBeforeDays;
            copy.staging = staging;
            copy.acceptTerms = acceptTerms;
            copy.notAfter = notAfter;
            copy.lastError = lastError;
            copy.lastAttempt = lastAttempt;
            copy.updatedAt = updatedAt;
            return copy;
        }
    }

    private static final class Stored {
        int version = 1;
        /// Set once the legacy `https.acme` / uploaded-certificate config has
        /// been imported. An empty registry afterwards is intentional (the
        /// operator deleted everything) and must not be repopulated on boot.
        boolean migrated;
        List<Entry> certificates = new ArrayList<>();
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path dataDir;
    private final Path file;
    private final Stored stored;

    private CertificateStore(Path dataDir, Path file, Stored stored) {
        this.dataDir = dataDir;
        this.file = file;
        this.stored = stored;
    }

    public static CertificateStore open(Path dataDir) throws IOException {
        Path certs = dataDir.resolve("certs");
        Files.createDirectories(certs);
        tighten(certs);
        Path file = certs.resolve(FILE_NAME);
        Stored stored = new Stored();
        if (Files.exists(file)) {
            try {
                String json = Files.readString(file, StandardCharsets.UTF_8);
                if (!json.isBlank()) {
                    Stored parsed = GSON.fromJson(json, Stored.class);
                    if (parsed != null && parsed.certificates != null) {
                        stored = parsed;
                    }
                }
            } catch (JsonParseException e) {
                throw new IOException("certs/" + FILE_NAME + " is malformed: " + e.getMessage(), e);
            }
        }
        CertificateStore store = new CertificateStore(dataDir, file, stored);
        store.normalize();
        return store;
    }

    /// Repairs the invariants the UI relies on: one active entry at most, and
    /// no entry without an id/apex.
    private void normalize() {
        stored.certificates.removeIf(entry -> entry == null || entry.id == null || entry.apex == null);
        Entry active = null;
        for (Entry entry : stored.certificates) {
            if (entry.active) {
                if (active == null) {
                    active = entry;
                } else {
                    entry.active = false;
                }
            }
        }
    }

    public synchronized boolean isEmpty() {
        return stored.certificates.isEmpty();
    }

    public synchronized boolean isMigrated() {
        return stored.migrated;
    }

    public synchronized void markMigrated() throws IOException {
        stored.migrated = true;
        save();
    }

    public synchronized List<Entry> all() {
        List<Entry> result = new ArrayList<>();
        for (Entry entry : stored.certificates) {
            result.add(entry.copy());
        }
        result.sort(Comparator
                .comparing((Entry entry) -> entry.apex == null ? "" : entry.apex)
                .thenComparing(entry -> entry.source == Source.ACME ? 0 : 1)
                .thenComparing(entry -> entry.updatedAt == null ? "" : entry.updatedAt));
        return result;
    }

    public synchronized @Nullable Entry find(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        for (Entry entry : stored.certificates) {
            if (id.equals(entry.id)) {
                return entry.copy();
            }
        }
        return null;
    }

    public synchronized @Nullable Entry active() {
        for (Entry entry : stored.certificates) {
            if (entry.active) {
                return entry.copy();
            }
        }
        return null;
    }

    public synchronized Entry create(String apex, List<String> domains) throws IOException {
        Entry entry = new Entry();
        entry.id = newId();
        entry.apex = apex;
        entry.domains = new ArrayList<>(domains);
        entry.updatedAt = Instant.now().toString();
        stored.certificates.add(entry);
        save();
        return entry.copy();
    }

    /// Replaces the mutable fields of one entry. `id` and `apex` are fixed
    /// after creation — regroup by deleting and adding instead.
    public synchronized Entry update(String id, java.util.function.Consumer<Entry> change) throws IOException {
        Entry target = raw(id);
        if (target == null) {
            throw new IOException("unknown certificate: " + id);
        }
        change.accept(target);
        target.updatedAt = Instant.now().toString();
        save();
        return target.copy();
    }

    public synchronized void delete(String id) throws IOException {
        stored.certificates.removeIf(entry -> id.equals(entry.id));
        save();
    }

    /// Marks one entry as the identity to serve and clears the flag on the
    /// rest. Callers still have to install the material and reload Jetty.
    public synchronized void setActive(String id) throws IOException {
        for (Entry entry : stored.certificates) {
            entry.active = id.equals(entry.id);
        }
        save();
    }

    /// Persists the result of one issuance without touching the operator's
    /// desired state.
    public synchronized void recordIssued(String id, List<String> issuedDomains,
                                          @Nullable Instant notAfter, @Nullable String error)
            throws IOException {
        Entry target = raw(id);
        if (target == null) {
            return;
        }
        target.issuedDomains = new ArrayList<>(issuedDomains);
        target.notAfter = notAfter == null ? null : notAfter.toString();
        target.lastError = error;
        target.lastAttempt = Instant.now().toString();
        target.updatedAt = target.lastAttempt;
        save();
    }

    public synchronized void recordError(String id, String error) throws IOException {
        Entry target = raw(id);
        if (target == null) {
            return;
        }
        target.lastError = error;
        target.lastAttempt = Instant.now().toString();
        target.updatedAt = target.lastAttempt;
        save();
    }

    /// Directory holding this certificate's PEM files. It is not created here:
    /// listing certificates must not leave empty directories behind, and every
    /// writer (`writeSecret`, the PEM export) creates its parent itself.
    public Path dir(String id) {
        return dataDir.resolve("certs").resolve(id);
    }

    public Path certPath(String id) {
        return dir(id).resolve("fullchain.pem");
    }

    public Path keyPath(String id) {
        return dir(id).resolve("privkey.pem");
    }

    private @Nullable Entry raw(@Nullable String id) {
        if (id == null) {
            return null;
        }
        for (Entry entry : stored.certificates) {
            if (id.equals(entry.id)) {
                return entry;
            }
        }
        return null;
    }

    private void save() throws IOException {
        normalize();
        Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
        Files.writeString(tmp, GSON.toJson(stored), StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(tmp, Set.copyOf(EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)));
        } catch (IOException | UnsupportedOperationException ignored) {
            // Non-POSIX filesystems cannot express this.
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void tighten(Path path) {
        try {
            Files.setPosixFilePermissions(path, Set.copyOf(EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE)));
        } catch (IOException | UnsupportedOperationException ignored) {
            // Best effort.
        }
    }

    private static String newId() {
        byte[] bytes = new byte[5];
        RANDOM.nextBytes(bytes);
        StringBuilder builder = new StringBuilder("c_");
        for (byte b : bytes) {
            builder.append(String.format(Locale.ROOT, "%02x", b));
        }
        return builder.toString();
    }
}
