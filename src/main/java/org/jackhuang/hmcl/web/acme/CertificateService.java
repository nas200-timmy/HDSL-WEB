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
import org.jackhuang.hmcl.web.server.HdslServer;
import org.jackhuang.hmcl.web.tls.CertificateManager;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.shredzone.acme4j.util.KeyPairUtils;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPair;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/// Issues and renews the certificates recorded in [CertificateStore].
///
/// This is the per-certificate successor of [AcmeService]: every entry carries
/// its own apex, domain members, CA and DNS credential, so one panel can host
/// several groups. Exactly one entry is active; issuing a non-active entry only
/// writes files, while issuing the active one also hot-reloads the connector.
@NotNullByDefault
public final class CertificateService implements AutoCloseable {

    /// UI-facing state of one entry. `phase` mirrors [AcmeService.Phase]'s
    /// names so the settings page can reuse its stage list.
    public record Status(String id, String state, String phase, @Nullable String lastError,
                         @Nullable Instant lastAttempt, @Nullable Instant nextAttempt,
                         @Nullable Instant notAfter, List<String> domains) {
    }

    private static final Duration CHECK_INTERVAL = Duration.ofHours(12);
    private static final Duration INITIAL_DELAY = Duration.ofSeconds(15);
    private static final Duration CHALLENGE_PROPAGATION = Duration.ofMinutes(3);

    private final ServerConfig config;
    private final CertificateStore store;
    private final AcmeCredentialStore credentials;
    private final AcmeClient client;
    private final DnsTxtResolver resolver;
    private final CertificateManager certificates;
    private final Supplier<@Nullable HdslServer> server;
    private final Logger logger;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "acme-certificates");
                thread.setDaemon(true);
                return thread;
            });

    private final Map<String, String> states = new ConcurrentHashMap<>();
    private final Map<String, String> phases = new ConcurrentHashMap<>();
    private final Map<String, Instant> nextAttempts = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> failures = new ConcurrentHashMap<>();
    private final Map<String, Boolean> running = new ConcurrentHashMap<>();

    public CertificateService(ServerConfig config, CertificateStore store,
                              AcmeCredentialStore credentials, AcmeClient client,
                              DnsTxtResolver resolver, CertificateManager certificates,
                              Supplier<@Nullable HdslServer> server, Logger logger) {
        this.config = config;
        this.store = store;
        this.credentials = credentials;
        this.client = client;
        this.resolver = resolver;
        this.certificates = certificates;
        this.server = server;
        this.logger = logger;
        for (CertificateStore.Entry entry : store.all()) {
            states.put(entry.id, entry.enabled ? "idle" : "disabled");
        }
    }

    /// Starts the renewal loop. The first check runs a few seconds after boot
    /// so a restart does not sit on an expiring certificate for half a day.
    public void start() {
        scheduler.schedule(this::safeCheck, INITIAL_DELAY.toSeconds(), TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(this::safeCheck,
                CHECK_INTERVAL.toSeconds(), CHECK_INTERVAL.toSeconds(), TimeUnit.SECONDS);
    }

    private void safeCheck() {
        try {
            checkDue();
        } catch (Exception e) {
            logger.warning("Certificate renewal check failed unexpectedly", e);
        }
    }

    /// Renews every enabled ACME entry that is missing a certificate or inside
    /// its renewal window. Public so the settings page can trigger it too.
    public void checkDue() {
        Instant now = Instant.now();
        for (CertificateStore.Entry entry : store.all()) {
            if (entry.source != CertificateStore.Source.ACME || !entry.enabled) {
                continue;
            }
            Instant next = nextAttempts.get(entry.id);
            if (next != null && now.isBefore(next)) {
                continue;
            }
            Instant expires = expiryOf(entry);
            boolean due = expires == null
                    || !now.isBefore(expires.minus(Duration.ofDays(entry.renewBeforeDays)));
            if (due) {
                issueNow(entry.id);
            } else if (!"error".equals(states.get(entry.id))) {
                states.put(entry.id, "active");
                phases.put(entry.id, "done");
            }
        }
    }

    /// Fire-and-forget issuance for the settings page; the UI polls status.
    public Status requestIssue(String id) throws IOException {
        CertificateStore.Entry entry = require(id);
        if (entry.source != CertificateStore.Source.ACME) {
            throw new IOException("uploaded certificates are not renewed automatically");
        }
        if (running.putIfAbsent(id, Boolean.TRUE) != null) {
            return status(id);
        }
        states.put(id, "issuing");
        phases.put(id, "preparing");
        scheduler.execute(() -> {
            try {
                issueNow(id);
            } finally {
                running.remove(id);
            }
        });
        return status(id);
    }

    /// One synchronous issuance attempt. Returns the resulting status; a
    /// failure is recorded on the entry instead of thrown, because callers are
    /// both the scheduler and the HTTP handler.
    public synchronized Status issueNow(String id) {
        CertificateStore.Entry entry = store.find(id);
        if (entry == null) {
            return status(id);
        }
        states.put(id, "issuing");
        phases.put(id, "preparing");
        try {
            doIssue(entry);
            states.put(id, entry.active ? "active" : "idle");
            phases.put(id, "done");
            nextAttempts.remove(id);
            failures.remove(id);
            logger.info("Certificate " + entry.apex + " (" + id + ") issued for " + entry.domains);
        } catch (Exception e) {
            String message = redact(e.getMessage());
            states.put(id, "error");
            phases.put(id, "error");
            int count = failures.computeIfAbsent(id, key -> new AtomicInteger()).incrementAndGet();
            // A dropped connection is not a configuration mistake: retry soon
            // instead of sitting out the hour-long backoff.
            boolean network = message.contains("Network error");
            Duration delay = network ? Duration.ofMinutes(5) : switch (Math.min(count, 3)) {
                case 1 -> Duration.ofHours(1);
                case 2 -> Duration.ofHours(6);
                default -> Duration.ofHours(24);
            };
            nextAttempts.put(id, Instant.now().plus(delay));
            try {
                store.recordError(id, message);
            } catch (IOException ignored) {
                // The message is still logged below.
            }
            logger.warning("Certificate issuance for " + entry.apex + " (" + id + ") failed: "
                    + message + "; retry at " + nextAttempts.get(id));
        }
        return status(id);
    }

    /// Makes an entry the identity Jetty serves. Validates the stored PEM pair
    /// first, so a broken file cannot take the panel offline.
    public CertificateManager.Info activate(String id) throws IOException {
        CertificateStore.Entry entry = require(id);
        Path cert = store.certPath(id);
        Path key = store.keyPath(id);
        if (!Files.isRegularFile(cert) || !Files.isRegularFile(key)) {
            throw new IOException("this certificate has no files on disk yet; issue or import it first");
        }
        // `active` is per apex: each main domain serves its own certificate.
        store.setActive(id);
        installActiveSet();
        states.put(id, "active");
        CertificateManager.Info info = certificates.info();
        if (info == null) {
            throw new IOException("the certificate set could not be loaded");
        }
        return info;
    }

    public Status status(@Nullable String id) {
        CertificateStore.Entry entry = store.find(id);
        if (entry == null) {
            return new Status(id == null ? "" : id, "unknown", "idle", null, null, null, null, List.of());
        }
        return new Status(entry.id,
                states.getOrDefault(entry.id, entry.enabled ? "idle" : "disabled"),
                phases.getOrDefault(entry.id, "idle"),
                entry.lastError,
                parse(entry.lastAttempt),
                nextAttempts.get(entry.id),
                expiryOf(entry),
                List.copyOf(entry.issuedDomains.isEmpty() ? entry.domains : entry.issuedDomains));
    }

    /// Whether the desired domains differ from the SANs currently on disk.
    public static boolean pending(CertificateStore.Entry entry) {
        if (entry.source != CertificateStore.Source.ACME) {
            return false;
        }
        if (entry.issuedDomains.isEmpty()) {
            return !entry.domains.isEmpty();
        }
        return !new java.util.TreeSet<>(entry.domains)
                .equals(new java.util.TreeSet<>(entry.issuedDomains));
    }

    // --------------------------------------------------------------- issuance --

    private void doIssue(CertificateStore.Entry entry) throws Exception {
        if (entry.domains.isEmpty()) {
            throw new AcmeClientException("no certificate names are configured");
        }
        if (entry.email.isBlank() || !entry.email.contains("@")) {
            throw new AcmeClientException("an email address is required");
        }
        if (!entry.acceptTerms) {
            throw new AcmeClientException("the CA terms have not been accepted");
        }
        String domainError = validateDomains(entry.apex, entry.domains);
        if (domainError != null) {
            throw new AcmeClientException(domainError);
        }
        AcmeCa.Info ca = AcmeCa.find(entry.ca);
        if (ca == null) {
            throw new AcmeClientException("unknown ACME CA: " + entry.ca);
        }
        AcmeCredentialStore.Credential credential = entry.credentialId == null || entry.credentialId.isBlank()
                ? credentials.findProvider(entry.provider)
                : credentials.find(entry.credentialId);
        if (credential == null) {
            throw new AcmeClientException("DNS credential profile not found");
        }
        if (!entry.provider.equals(credential.provider())) {
            throw new AcmeClientException("DNS credential `" + credential.name()
                    + "` is for provider `" + credential.provider()
                    + "`, but this certificate uses `" + entry.provider + "`");
        }
        if (ca.requiresEab() && !credential.eabConfigured()) {
            throw new AcmeClientException("CA `" + ca.displayName() + "` requires EAB credentials");
        }
        AcmeDnsProvider provider = AcmeDnsProviderRegistry.create(entry.provider, credential.dns());
        String directory = AcmeCa.directory(entry.ca, "", entry.staging);

        phases.put(entry.id, "order");
        AcmeClient.IssuedCertificate issued = client.issue(
                new AcmeClient.Request(directory, entry.email, List.copyOf(entry.domains),
                        entry.acceptTerms, credential.eab().get("kid"),
                        credential.eab().get("hmacKey"), loadOrCreateAccountKey()),
                new AcmeClient.ChallengeHandler() {
                    @Override
                    public void present(String fqdn, String value) throws Exception {
                        phases.put(entry.id, "dns");
                        provider.present(fqdn, value);
                    }

                    @Override
                    public boolean awaitPropagation(String fqdn, String value) {
                        return resolver.await(fqdn, value, CHALLENGE_PROPAGATION);
                    }

                    @Override
                    public void cleanup(String fqdn, String value) throws Exception {
                        provider.cleanup(fqdn, value);
                    }
                });

        phases.put(entry.id, "installing");
        Path cert = store.certPath(entry.id);
        Path key = store.keyPath(entry.id);
        writeSecret(cert, issued.certificatePem());
        writeSecret(key, issued.privateKeyPem());
        store.recordIssued(entry.id, issued.domains(), issued.notAfter(), null);

        // First certificate of an apex becomes its default, so a freshly
        // signed main domain is served without an extra click.
        boolean hasDefault = store.all().stream()
                .anyMatch(item -> item.apex.equals(entry.apex) && item.active);
        if (!hasDefault) {
            store.setActive(entry.id);
        }
        CertificateStore.Entry refreshed = store.find(entry.id);
        if (refreshed != null && refreshed.active) {
            installActiveSet();
        }
    }

    /// One active entry per apex. Rebuilds the connector's keystore from all of
    /// them and hot-reloads, so each main domain answers with its own
    /// certificate (SNI selection happens in JSSE).
    private int installActiveSet() throws IOException {
        int installed = installActiveSet(store, certificates, logger);
        if (installed == 0) {
            return 0;
        }
        HdslServer live = server.get();
        if (live != null) {
            HdslServer.TlsSwitch result = live.tlsActive()
                    ? live.reloadTls(certificates)
                    : live.enableTls(certificates);
            if (!result.ok()) {
                throw new IOException(result.error() == null ? "TLS reload failed" : result.error());
            }
        }
        return installed;
    }

    /// Boot-time variant: installs every active entry that has files on disk.
    public static int installActiveSet(CertificateStore store, CertificateManager certificates,
                                       Logger logger) throws IOException {
        List<CertificateManager.ManagedCertificate> active = new ArrayList<>();
        for (CertificateStore.Entry entry : store.all()) {
            if (!entry.active) {
                continue;
            }
            Path cert = store.certPath(entry.id);
            Path key = store.keyPath(entry.id);
            if (Files.isRegularFile(cert) && Files.isRegularFile(key)) {
                active.add(new CertificateManager.ManagedCertificate("cert-" + entry.id, cert, key));
            }
        }
        if (active.isEmpty()) {
            return 0;
        }
        int count = certificates.installManagedSet(active);
        logger.info("Serving " + count + " certificate(s) from the registry (one per main domain)");
        return count;
    }

    /// Domains must belong to the entry's apex: `apex`, `sub.apex` or
    /// `*.apex`. A wildcard for another zone would make the DNS credential
    /// point at a zone it does not control.
    public static @Nullable String validateDomains(String apex, List<String> domains) {
        if (apex == null || apex.isBlank() || !apex.contains(".")) {
            return "apex domain is required (e.g. example.com)";
        }
        String normalizedApex = apex.trim().toLowerCase(java.util.Locale.ROOT);
        for (String raw : domains) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String domain = raw.trim().toLowerCase(java.util.Locale.ROOT);
            String base = domain.startsWith("*.") ? domain.substring(2) : domain;
            if (domain.startsWith("*.") && base.contains("*")) {
                return "wildcards are only allowed as *.`apex`: " + domain;
            }
            boolean belongs = base.equals(normalizedApex) || base.endsWith("." + normalizedApex);
            if (!belongs) {
                return "`" + domain + "` is not under `" + normalizedApex + "`";
            }
            if (base.isEmpty() || base.contains("/") || base.contains(":")
                    || !base.contains(".")) {
                return "invalid DNS name: " + domain;
            }
        }
        return null;
    }

    private CertificateStore.Entry require(String id) throws IOException {
        CertificateStore.Entry entry = store.find(id);
        if (entry == null) {
            throw new IOException("unknown certificate: " + id);
        }
        return entry;
    }

    private @Nullable Instant expiryOf(CertificateStore.Entry entry) {
        Path cert = store.certPath(entry.id);
        if (Files.isRegularFile(cert)) {
            try (var in = Files.newInputStream(cert)) {
                X509Certificate leaf = (X509Certificate) CertificateFactory.getInstance("X.509")
                        .generateCertificate(in);
                return leaf.getNotAfter().toInstant();
            } catch (Exception ignored) {
                // Fall back to the recorded value below.
            }
        }
        return parse(entry.notAfter);
    }

    private static @Nullable Instant parse(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private KeyPair loadOrCreateAccountKey() throws Exception {
        Path file = config.dataDir.resolve("auth").resolve("acme-account.key");
        if (Files.isRegularFile(file)) {
            try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                return KeyPairUtils.readKeyPair(reader);
            }
        }
        KeyPair keyPair = KeyPairUtils.createKeyPair(2048);
        StringWriter writer = new StringWriter();
        KeyPairUtils.writeKeyPair(keyPair, writer);
        writeSecret(file, writer.toString());
        return keyPair;
    }

    private static void writeSecret(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(tmp, Set.copyOf(EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)));
        } catch (IOException | UnsupportedOperationException ignored) {
            // Non-POSIX filesystems cannot express this.
        }
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /// Keeps credential material out of operator-facing error messages.
    private String redact(@Nullable String message) {
        if (message == null || message.isBlank()) {
            return "unknown error";
        }
        String oneLine = message.replace('\n', ' ').replace('\r', ' ');
        for (CertificateStore.Entry entry : store.all()) {
            if (entry.credentialId != null && !entry.credentialId.isBlank()) {
                oneLine = oneLine.replace(entry.credentialId, "«credential»");
            }
        }
        return oneLine.length() > 500 ? oneLine.substring(0, 500) + "…" : oneLine;
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
