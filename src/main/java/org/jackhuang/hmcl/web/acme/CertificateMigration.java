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

import org.jackhuang.hmcl.util.logging.Logger;
import org.jackhuang.hmcl.web.config.ServerConfig;
import org.jackhuang.hmcl.web.tls.CertificateManager;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/// Turns a pre-registry deployment into certificate entries, once.
///
/// Before this feature the panel had three independent certificate sources
/// (`https.pkcs12_file`, `https.cert_file`/`key_file`, and `https.acme`), and
/// the first one won silently. The migration records what was actually
/// configured so the new certificate list starts with the same identity the
/// panel was serving.
@NotNullByDefault
public final class CertificateMigration {

    private CertificateMigration() {
    }

    public static void runIfNeeded(ServerConfig config, CertificateStore store,
                                   AcmeCredentialStore credentials,
                                   CertificateManager certificates, Logger logger) {
        try {
            repairNames(store);
        } catch (Exception e) {
            logger.warning("Could not repair certificate names in the registry", e);
        }
        if (!store.isEmpty() || store.isMigrated()) {
            return;
        }
        try {
            CertificateStore.Entry upload = syncUploaded(store, certificates);
            CertificateStore.Entry acme = migrateAcme(config, store, credentials, logger);
            if (upload != null) {
                store.setActive(upload.id);
                logger.info("Certificate registry initialized; serving the imported upload `"
                        + upload.apex + "` (" + upload.id + ")");
            } else if (acme != null) {
                store.setActive(acme.id);
                logger.info("Certificate registry initialized; serving the ACME entry `"
                        + acme.apex + "` (" + acme.id + ")");
            }
            store.markMigrated();
        } catch (Exception e) {
            logger.warning("Certificate migration failed; the legacy certificate path stays in use", e);
        }
    }

    /// Mirrors the identity currently installed from an uploaded PEM/PKCS#12
    /// into the registry (creating it on first import, refreshing it when the
    /// operator uploads a replacement) and marks it active. Also used by the
    /// TLS settings endpoint after an upload, so both surfaces agree.
    public static @Nullable CertificateStore.Entry syncUploaded(
            CertificateStore store, CertificateManager certificates) throws IOException {
        CertificateManager.Source source = certificates.getSource();
        if (source != CertificateManager.Source.PKCS12 && source != CertificateManager.Source.PEM) {
            return null;
        }
        CertificateManager.Info info = certificates.info();
        if (info == null || info.sans().isEmpty()) {
            return null;
        }
        List<String> domains = normalize(info.sans());
        CertificateStore.Entry existing = null;
        for (CertificateStore.Entry entry : store.all()) {
            if (entry.source == CertificateStore.Source.UPLOAD
                    && new java.util.TreeSet<>(entry.issuedDomains).equals(new java.util.TreeSet<>(domains))) {
                existing = entry;
                break;
            }
        }
        String apex = existing != null ? existing.apex : apexOf(domains, null);
        CertificateStore.Entry entry = existing != null
                ? existing
                : store.create(apex, domains);
        certificates.exportIdentityPem(store.certPath(entry.id), store.keyPath(entry.id));
        CertificateStore.Entry updated = store.update(entry.id, target -> {
            target.source = CertificateStore.Source.UPLOAD;
            target.domains = new ArrayList<>(domains);
            target.issuedDomains = new ArrayList<>(domains);
            target.notAfter = info.expires().toString();
            target.enabled = false;
            target.lastError = null;
        });
        store.setActive(entry.id);
        return updated;
    }

    private static @Nullable CertificateStore.Entry migrateAcme(
            ServerConfig config, CertificateStore store,
            AcmeCredentialStore credentials, Logger logger) throws IOException {
        ServerConfig.AcmeConfig acme = config.https.acme;
        if (!acme.enabled || acme.domains.isEmpty()) {
            return null;
        }
        List<String> domains = normalize(acme.domains);
        if (domains.isEmpty()) {
            return null;
        }
        String zone = credentialZone(credentials, acme);
        CertificateStore.Entry entry = store.create(apexOf(domains, zone), domains);
        // Keep the certificate the panel was already serving, instead of
        // forcing a fresh issuance on the first boot after the upgrade.
        copyLegacy(config, store, entry.id);
        CertificateStore.Entry updated = store.update(entry.id, target -> {
            target.source = CertificateStore.Source.ACME;
            target.enabled = true;
            target.ca = acme.ca;
            target.email = acme.email;
            target.provider = acme.provider;
            target.credentialId = acme.credentialId;
            target.renewBeforeDays = acme.renewBeforeDays;
            target.staging = acme.useStaging;
            target.acceptTerms = acme.acceptTerms;
            target.notAfter = readNotAfter(store, entry.id);
        });
        logger.info("Imported the existing ACME configuration as `" + updated.apex
                + "` (" + updated.id + ")");
        return updated;
    }

    private static void copyLegacy(ServerConfig config, CertificateStore store, String id) {
        java.nio.file.Path legacyCert = config.dataDir.resolve("certs").resolve("acme").resolve("fullchain.pem");
        java.nio.file.Path legacyKey = config.dataDir.resolve("certs").resolve("acme").resolve("privkey.pem");
        try {
            if (java.nio.file.Files.isRegularFile(legacyCert)
                    && java.nio.file.Files.isRegularFile(legacyKey)) {
                java.nio.file.Files.createDirectories(store.dir(id));
                java.nio.file.Files.copy(legacyCert, store.certPath(id),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                java.nio.file.Files.copy(legacyKey, store.keyPath(id),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
            // A missing copy only means one extra issuance.
        }
    }

    private static @Nullable String readNotAfter(CertificateStore store, String id) {
        java.nio.file.Path cert = store.certPath(id);
        if (!java.nio.file.Files.isRegularFile(cert)) {
            return null;
        }
        try (var in = java.nio.file.Files.newInputStream(cert)) {
            return ((java.security.cert.X509Certificate)
                    java.security.cert.CertificateFactory.getInstance("X.509")
                            .generateCertificate(in)).getNotAfter().toInstant().toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static @Nullable String credentialZone(AcmeCredentialStore credentials,
                                                   ServerConfig.AcmeConfig acme) {
        AcmeCredentialStore.Credential credential = acme.credentialId == null || acme.credentialId.isBlank()
                ? credentials.findProvider(acme.provider)
                : credentials.find(acme.credentialId);
        if (credential == null) {
            return null;
        }
        String zone = credential.dns().get("zone");
        return zone == null || zone.isBlank() ? null : zone.trim().toLowerCase(Locale.ROOT);
    }

    /// Longest suffix shared by every name, preferring the credential's zone
    /// when it matches. Wildcards contribute their parent (`*.example.com` →
    /// `example.com`).
    static String apexOf(List<String> domains, @Nullable String zone) {
        List<String> bases = new ArrayList<>();
        for (String domain : domains) {
            bases.add(domain.startsWith("*.") ? domain.substring(2) : domain);
        }
        if (zone != null && !zone.isBlank()) {
            boolean matches = bases.stream().allMatch(base -> base.equals(zone) || base.endsWith("." + zone));
            if (matches) {
                return zone;
            }
        }
        if (bases.isEmpty()) {
            return "";
        }
        String[] parts = bases.get(0).split("\\.");
        int shared = bases.size() == 1 ? Math.min(parts.length, 2) : parts.length;
        for (String base : bases) {
            String[] other = base.split("\\.");
            int count = 0;
            while (count < shared && count < other.length
                    && parts[parts.length - 1 - count].equals(other[other.length - 1 - count])) {
                count++;
            }
            shared = count;
        }
        if (shared < 2) {
            return bases.get(0);
        }
        StringBuilder builder = new StringBuilder();
        for (int i = parts.length - shared; i < parts.length; i++) {
            if (builder.length() > 0) {
                builder.append('.');
            }
            builder.append(parts[i]);
        }
        return builder.toString();
    }

    /// CertificateManager reports SANs as `DNS:example.com` / `IP:…`. The
    /// registry stores plain DNS names, so the prefix is stripped and IP SANs
    /// are dropped (they are not certificate names for ACME).
    private static List<String> normalize(List<String> domains) {
        Set<String> unique = new LinkedHashSet<>();
        for (String raw : domains) {
            if (raw == null) {
                continue;
            }
            String domain = raw.trim().toLowerCase(Locale.ROOT);
            if (domain.startsWith("dns:")) {
                domain = domain.substring(4);
            } else if (domain.startsWith("ip:")) {
                continue;
            }
            if (domain.endsWith(".")) {
                domain = domain.substring(0, domain.length() - 1);
            }
            if (!domain.isEmpty() && domain.contains(".")) {
                unique.add(domain);
            }
        }
        return new ArrayList<>(unique);
    }

    /// Repairs entries created before SANs were normalized (`dns:example.com`
    /// used to be written verbatim) and re-derives a broken apex. Runs on every
    /// boot; a healthy registry is left untouched.
    static void repairNames(CertificateStore store) throws IOException {
        for (CertificateStore.Entry entry : store.all()) {
            List<String> domains = normalize(entry.domains);
            List<String> issued = normalize(entry.issuedDomains);
            String apex = entry.apex;
            if (apex == null || apex.isBlank() || apex.contains(":")) {
                List<String> source = domains.isEmpty() ? issued : domains;
                if (!source.isEmpty()) {
                    apex = apexOf(source, null);
                }
            }
            if (!domains.equals(entry.domains)
                    || !issued.equals(entry.issuedDomains)
                    || (apex != null && !apex.equals(entry.apex))) {
                String target = apex;
                store.update(entry.id, value -> {
                    value.domains = new ArrayList<>(domains);
                    value.issuedDomains = new ArrayList<>(issued);
                    if (target != null) {
                        value.apex = target;
                    }
                });
            }
        }
    }

}
