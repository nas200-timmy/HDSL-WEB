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
import org.shredzone.acme4j.util.KeyPairUtils;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/// Issues and renews the panel's certificate through ACME.
///
/// The renewal rule is deliberately simple and observable:
/// - on start, check once;
/// - afterwards check every 12h;
/// - renew when fewer than `https.acme.renew_before_days` remain;
/// - on failure keep the old certificate, record a redacted error, and retry
///   with 1h/6h/24h backoff.
///
/// Everything that talks to the CA is behind [AcmeClient]; everything that
/// installs the result is behind [CertificateSink]. Both are replaceable in
/// tests.
@NotNullByDefault
public final class AcmeService implements AutoCloseable {

    public enum State {
        DISABLED,
        IDLE,
        ISSUING,
        ACTIVE,
        ERROR
    }

    public record Status(boolean enabled, State state,
                         @Nullable String lastError,
                         @Nullable Instant lastAttempt,
                         @Nullable Instant nextAttempt,
                         @Nullable Instant expires,
                         @Nullable String source,
                         List<String> domains,
                         String ca,
                         String provider) {
    }

    /// What the service may do to the live identity. The production
    /// implementation wraps [CertificateManager] and `HdslServer`.
    public interface CertificateSink {
        CertificateManager.Source source();

        @Nullable CertificateManager.Info info();

        /// Validates and installs the PEM pair in the in-memory manager.
        void install(Path certPem, Path keyPem) throws Exception;

        /// Hot-reloads a TLS connector, or switches a plain connector to TLS.
        void reload() throws Exception;

        /// Whether the live connector currently speaks TLS. A valid ACME
        /// certificate on a plain-HTTP connector still needs one switch after
        /// a restart.
        boolean tlsActive();
    }

    private static final Duration CHECK_INTERVAL = Duration.ofHours(12);
    private static final Duration INITIAL_DELAY = Duration.ofSeconds(10);
    private static final Duration CHALLENGE_PROPAGATION = Duration.ofMinutes(3);

    private final ServerConfig config;
    private final AcmeCredentialStore credentials;
    private final AcmeClient client;
    private final DnsTxtResolver resolver;
    private final CertificateSink sink;
    private final Logger logger;
    private final ScheduledExecutorService scheduler;
    private final AtomicInteger failures = new AtomicInteger();
    private final java.util.concurrent.atomic.AtomicBoolean started =
            new java.util.concurrent.atomic.AtomicBoolean();

    private final Object lock = new Object();
    private State state = State.IDLE;
    private @Nullable String lastError;
    private @Nullable Instant lastAttempt;
    private @Nullable Instant nextAttempt;

    public AcmeService(ServerConfig config, AcmeCredentialStore credentials,
                       AcmeClient client, DnsTxtResolver resolver,
                       CertificateSink sink, Logger logger) {
        this.config = config;
        this.credentials = credentials;
        this.client = client;
        this.resolver = resolver;
        this.sink = sink;
        this.logger = logger;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "hdsl-acme");
            thread.setDaemon(true);
            return thread;
        });
        if (!config.https.acme.enabled) {
            this.state = State.DISABLED;
        }
    }

    /// Starts the background renewal loop. Calling it twice is harmless.
    public void start() {
        if (!config.https.acme.enabled || !started.compareAndSet(false, true)) {
            return;
        }
        synchronized (lock) {
            if (state == State.DISABLED) {
                state = State.IDLE;
            }
        }
        scheduler.schedule(this::safeCheck, INITIAL_DELAY.toMillis(), TimeUnit.MILLISECONDS);
    }

    /// Stops future scheduling and reports the disabled state immediately, so
    /// the settings page does not keep showing the last ACTIVE snapshot.
    public void disable() {
        stop();
        synchronized (lock) {
            state = State.DISABLED;
            lastError = null;
            nextAttempt = null;
        }
    }

    /// Stops future scheduling. A pending one-shot may still run; it sees the
    /// disabled switch and does not schedule another.
    public void stop() {
        started.set(false);
    }

    /// The current state, including the live certificate's expiry.
    public Status status() {
        synchronized (lock) {
            CertificateManager.Info info = sink.info();
            return new Status(config.https.acme.enabled, state, lastError, lastAttempt, nextAttempt,
                    info == null ? null : info.expires(),
                    sink.source().name().toLowerCase(java.util.Locale.ROOT),
                    List.copyOf(config.https.acme.domains),
                    config.https.acme.ca,
                    config.https.acme.provider);
        }
    }

    /// The credential store, for the settings endpoint. Never expose its
    /// contents through the API.
    public AcmeCredentialStore credentials() {
        return credentials;
    }

    /// Starts an issuance in the background and returns the current status.
    /// The settings page uses this for "立即签发": the HTTP request must not
    /// sit on a DNS-01 challenge for minutes.
    public Status requestIssue() {
        synchronized (lock) {
            if (!config.https.acme.enabled) {
                state = State.DISABLED;
                return status();
            }
            if (state == State.ISSUING) {
                return status();
            }
            state = State.ISSUING;
            lastAttempt = Instant.now();
            lastError = null;
            scheduler.execute(() -> {
                try {
                    synchronized (lock) {
                        issueLocked(Instant.now());
                    }
                } finally {
                    scheduleNext();
                }
            });
            return status();
        }
    }

    /// Runs one renewal check. Public for the settings page's "issue now"
    /// action and for tests.
    public Status renewIfNeeded() {
        synchronized (lock) {
            if (!config.https.acme.enabled) {
                state = State.DISABLED;
                return status();
            }
            Instant now = Instant.now();
            if (nextAttempt != null && now.isBefore(nextAttempt)) {
                return status();
            }
            CertificateManager.Info info = sink.info();
            if (sink.source() == CertificateManager.Source.ACME && info != null) {
                Instant renewAt = info.expires().minus(Duration.ofDays(config.https.acme.renewBeforeDays));
                if (now.isBefore(renewAt)) {
                    state = State.ACTIVE;
                    lastError = null;
                    if (!sink.tlsActive()) {
                        try {
                            sink.reload();
                            logger.info("Activated the existing ACME certificate on this connector");
                        } catch (Exception e) {
                            state = State.ERROR;
                            lastError = redact(e.getMessage());
                            logger.warning("Could not activate the existing ACME certificate", e);
                        }
                    }
                    return status();
                }
            }
            // No ACME certificate yet — including a self-signed fallback — or
            // an ACME certificate inside the renewal window: issue.
            return issueLocked(now);
        }
    }

    /// Issues regardless of the current expiry, once the configured data is
    /// valid. Used by the settings page's explicit "立即签发".
    public Status issueNow() {
        synchronized (lock) {
            if (!config.https.acme.enabled) {
                state = State.DISABLED;
                return status();
            }
            return issueLocked(Instant.now());
        }
    }

    private Status issueLocked(Instant now) {
        state = State.ISSUING;
        lastAttempt = now;
        lastError = null;
        try {
            doIssue();
            state = State.ACTIVE;
            failures.set(0);
            nextAttempt = now.plus(CHECK_INTERVAL);
            logger.info("ACME certificate is active; next check at " + nextAttempt);
        } catch (Exception e) {
            state = State.ERROR;
            lastError = redact(e.getMessage());
            int count = failures.incrementAndGet();
            Duration delay = switch (Math.min(count, 3)) {
                case 1 -> Duration.ofHours(1);
                case 2 -> Duration.ofHours(6);
                default -> Duration.ofHours(24);
            };
            nextAttempt = now.plus(delay);
            logger.warning("ACME issuance failed: " + lastError + "; retry at " + nextAttempt);
        }
        return status();
    }

    private void doIssue() throws Exception {
        ServerConfig.AcmeConfig acme = config.https.acme;
        if (acme.domains.isEmpty()) {
            throw new AcmeClientException("no certificate names are configured");
        }
        if (acme.email.isBlank()) {
            throw new AcmeClientException("https.acme.email is required");
        }
        if (!acme.acceptTerms) {
            throw new AcmeClientException("the CA terms have not been accepted");
        }
        AcmeCa.Info ca = AcmeCa.find(acme.ca);
        if (ca == null) {
            throw new AcmeClientException("unknown ACME CA: " + acme.ca);
        }
        if (ca.requiresEab() && !credentials.eabConfigured()) {
            throw new AcmeClientException("CA `" + ca.displayName() + "` requires EAB credentials");
        }
        if (credentials.provider() == null || !credentials.provider().equals(acme.provider)) {
            throw new AcmeClientException("DNS credentials are for provider `"
                    + credentials.provider() + "`, but the configuration uses `" + acme.provider + "`");
        }
        String directory = AcmeCa.directory(acme.ca, acme.directoryUrl, acme.useStaging);
        AcmeDnsProvider provider = AcmeDnsProviderRegistry.create(acme.provider, credentials.dnsCredentials());
        KeyPair accountKey = loadOrCreateAccountKey();
        AcmeClient.Request request = new AcmeClient.Request(
                directory, acme.email, List.copyOf(acme.domains), acme.acceptTerms,
                credentials.eabCredentials().get("kid"),
                credentials.eabCredentials().get("hmacKey"),
                accountKey);

        AcmeClient.IssuedCertificate issued = client.issue(request, new AcmeClient.ChallengeHandler() {
            @Override
            public void present(String fqdn, String value) throws Exception {
                provider.present(fqdn, value);
                if (!resolver.await(fqdn, value, CHALLENGE_PROPAGATION)) {
                    throw new AcmeDnsException("TXT record " + fqdn
                            + " is not visible yet; check the DNS provider and retry");
                }
            }

            @Override
            public void cleanup(String fqdn, String value) throws Exception {
                provider.cleanup(fqdn, value);
            }
        });

        Path certPath = config.dataDir.resolve("certs").resolve("acme").resolve("fullchain.pem");
        Path keyPath = config.dataDir.resolve("certs").resolve("acme").resolve("privkey.pem");
        writeSecret(certPath, issued.certificatePem());
        writeSecret(keyPath, issued.privateKeyPem());
        sink.install(certPath, keyPath);
        sink.reload();
        logger.info("ACME issued a certificate for " + issued.domains()
                + " valid until " + issued.notAfter());
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
        } catch (IOException | UnsupportedOperationException e) {
            // Non-POSIX filesystems cannot express this.
        }
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /// Replace any credential value that somehow reached an exception message.
    private String redact(@Nullable String message) {
        if (message == null || message.isBlank()) {
            return "unknown error";
        }
        String redacted = message;
        for (Map.Entry<String, String> entry : credentials.dnsCredentials().entrySet()) {
            if (entry.getValue() != null && entry.getValue().length() >= 4) {
                redacted = redacted.replace(entry.getValue(), "***");
            }
        }
        for (Map.Entry<String, String> entry : credentials.eabCredentials().entrySet()) {
            if (entry.getValue() != null && entry.getValue().length() >= 4) {
                redacted = redacted.replace(entry.getValue(), "***");
            }
        }
        return redacted.replace('\n', ' ').replace('\r', ' ');
    }

    private void safeCheck() {
        try {
            renewIfNeeded();
        } catch (RuntimeException e) {
            logger.warning("ACME renewal check failed unexpectedly", e);
        } finally {
            scheduleNext();
        }
    }

    /// One-shot scheduling is what makes the failure backoff real: a fixed
    /// 12h tick would ignore the 1h/6h/24h retry times. The next wake-up is
    /// `nextAttempt` when there is one, otherwise the regular check interval.
    private void scheduleNext() {
        if (scheduler.isShutdown() || !config.https.acme.enabled) {
            return;
        }
        Instant now = Instant.now();
        Duration delay = nextAttempt == null
                ? CHECK_INTERVAL : Duration.between(now, nextAttempt);
        if (delay.isNegative() || delay.isZero()) {
            delay = Duration.ofMinutes(1);
        }
        scheduler.schedule(this::safeCheck, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
