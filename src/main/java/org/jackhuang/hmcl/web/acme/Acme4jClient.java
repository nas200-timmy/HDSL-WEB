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
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.shredzone.acme4j.Account;
import org.shredzone.acme4j.AccountBuilder;
import org.shredzone.acme4j.Authorization;
import org.shredzone.acme4j.Certificate;
import org.shredzone.acme4j.Order;
import org.shredzone.acme4j.Session;
import org.shredzone.acme4j.Status;
import org.shredzone.acme4j.challenge.Dns01Challenge;
import org.shredzone.acme4j.exception.AcmeException;
import org.shredzone.acme4j.exception.AcmeNetworkException;
import org.shredzone.acme4j.util.KeyPairUtils;

import java.io.StringWriter;
import java.net.URI;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/// acme4j-backed implementation. All cryptography and ACME wire handling stays
/// in acme4j; this class only maps our request/record shape and guarantees
/// per-authorization DNS cleanup.
@NotNullByDefault
public final class Acme4jClient implements AcmeClient {

    private static final Duration CHALLENGE_TIMEOUT = Duration.ofMinutes(3);
    private static final Duration ORDER_TIMEOUT = Duration.ofMinutes(3);

    private final Logger logger;

    public Acme4jClient(Logger logger) {
        this.logger = logger;
    }

    @Override
    public IssuedCertificate issue(Request request, ChallengeHandler handler) throws AcmeClientException {
        if (!request.acceptTerms()) {
            throw new AcmeClientException("the CA terms have not been accepted");
        }
        try {
            Session session = new Session(URI.create(request.directoryUrl()));
            session.setLocale(java.util.Locale.US);

            AccountBuilder builder = new AccountBuilder()
                    .agreeToTermsOfService()
                    .useKeyPair(request.accountKey());
            if (!request.email().isBlank()) {
                builder.addEmail(request.email());
            }
            if (request.eabKid() != null && !request.eabKid().isBlank()
                    && request.eabHmacKey() != null && !request.eabHmacKey().isBlank()) {
                builder.withKeyIdentifier(request.eabKid(), request.eabHmacKey());
            } else if ((request.eabKid() == null || request.eabKid().isBlank())
                    != (request.eabHmacKey() == null || request.eabHmacKey().isBlank())) {
                throw new AcmeClientException("EAB requires both the key id and the HMAC key");
            }
            Account account = builder.create(session);

            Order order = account.newOrder().domains(request.domains()).create();
            for (Authorization authorization : order.getAuthorizations()) {
                if (authorization.getStatus() == Status.VALID) {
                    continue;
                }
                Dns01Challenge challenge = authorization.findChallenge(Dns01Challenge.class)
                        .orElseThrow(() -> new AcmeClientException(
                                "the CA offered no DNS-01 challenge for "
                                        + authorization.getIdentifier().getDomain()));
                // acme4j returns an absolute name with a trailing dot
                // (`_acme-challenge.example.com.`). Providers hand that name to
                // DNS-control APIs, and several of them reject it: AliDNS answers
                // `SubDomainInvalid.RR` and the authorization dies after the
                // record was already written.
                String rrName = withoutTrailingDot(Dns01Challenge.toRRName(authorization.getIdentifier()));
                String digest = challenge.getDigest();
                boolean presented = false;
                try {
                    handler.present(rrName, digest);
                    presented = true;
                    if (!handler.awaitPropagation(rrName, digest)) {
                        throw new AcmeClientException("DNS-01 TXT record " + rrName
                                + " did not become visible in time; check the DNS provider and retry");
                    }
                    triggerWithRetry(challenge);
                    Status status = awaitChallenge(challenge, CHALLENGE_TIMEOUT);
                    if (status != Status.VALID) {
                        throw new AcmeClientException("DNS-01 validation for " + rrName
                                + " ended in " + status);
                    }
                } finally {
                    if (presented) {
                        try {
                            handler.cleanup(rrName, digest);
                        } catch (Exception e) {
                            logger.warning("Could not remove the ACME TXT record " + rrName
                                    + "; remove it manually if the DNS provider keeps it", e);
                        }
                    }
                }
            }

            Status ready = withNetworkRetry(() -> order.waitUntilReady(ORDER_TIMEOUT));
            if (ready != Status.READY) {
                throw new AcmeClientException("the ACME order ended in " + ready + " before finalization");
            }
            KeyPair domainKey = KeyPairUtils.createKeyPair(2048);
            withNetworkRetry(() -> {
                order.execute(domainKey);
                return null;
            });
            Status completed = withNetworkRetry(() -> order.waitForCompletion(ORDER_TIMEOUT));
            if (completed != Status.VALID) {
                throw new AcmeClientException("the ACME order ended in " + completed + " after finalization");
            }

            Certificate certificate = order.getCertificate();
            if (certificate == null) {
                throw new AcmeClientException("the ACME order has no certificate");
            }
            certificate.download();
            X509Certificate leaf = certificate.getCertificate();
            StringWriter chain = new StringWriter();
            certificate.writeCertificate(chain);
            StringWriter key = new StringWriter();
            KeyPairUtils.writeKeyPair(domainKey, key);

            List<String> names = new ArrayList<>();
            for (var identifier : order.getIdentifiers()) {
                names.add(identifier.getDomain());
            }
            return new IssuedCertificate(chain.toString(), key.toString(),
                    leaf.getNotAfter().toInstant(), List.copyOf(names));
        } catch (AcmeClientException e) {
            throw e;
        } catch (AcmeException e) {
            throw new AcmeClientException("ACME request failed: " + sanitize(describe(e)), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AcmeClientException("ACME request was interrupted", e);
        } catch (Exception e) {
            throw new AcmeClientException("ACME request failed: " + sanitize(describe(e)), e);
        }
    }

    /// Triggers the challenge, retrying transport failures. A dropped
    /// keep-alive connection used to fail the whole issuance even though the
    /// CA was reachable a second earlier.
    private void triggerWithRetry(org.shredzone.acme4j.challenge.Challenge challenge)
            throws AcmeException, InterruptedException {
        int failures = 0;
        while (true) {
            try {
                challenge.trigger();
                return;
            } catch (AcmeNetworkException e) {
                if (++failures >= 3) {
                    throw e;
                }
                Thread.sleep(2_000L * failures);
            }
        }
    }

    /// Polls the challenge ourselves instead of `waitForCompletion`: that call
    /// aborts on the first dropped connection, which is exactly what a
    /// long DNS-01 wait over a home/ISP link runs into. Transport failures are
    /// retried; the overall deadline is unchanged.
    private Status awaitChallenge(org.shredzone.acme4j.challenge.Challenge challenge, Duration timeout)
            throws AcmeException, AcmeClientException, InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        int failures = 0;
        while (System.nanoTime() < deadline) {
            Status status = challenge.getStatus();
            if (status != Status.PENDING && status != Status.PROCESSING) {
                return status;
            }
            try {
                Optional<Instant> retryAfter = challenge.fetch();
                failures = 0;
                long wait = retryAfter
                        .map(at -> Math.max(1_000L, Duration.between(Instant.now(), at).toMillis()))
                        .orElse(2_000L);
                Thread.sleep(Math.min(wait, 10_000L));
            } catch (AcmeNetworkException e) {
                if (++failures > 5) {
                    throw e;
                }
                logger.warning("Temporary ACME network error while polling; retrying: " + describe(e));
                Thread.sleep(Math.min(2_000L * failures, 10_000L));
            }
        }
        throw new AcmeClientException("DNS-01 challenge did not finish within "
                + timeout.toMinutes() + " minutes (last status " + challenge.getStatus() + ")");
    }

    /// Retries idempotent order operations on transport failures.
    private <T> T withNetworkRetry(Callable<T> action) throws AcmeException, AcmeClientException, InterruptedException {
        int failures = 0;
        while (true) {
            try {
                return action.call();
            } catch (AcmeNetworkException e) {
                if (++failures >= 3) {
                    throw e;
                }
                Thread.sleep(2_000L * failures);
            } catch (AcmeException | RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new AcmeClientException("ACME request failed: " + sanitize(describe(e)), e);
            }
        }
    }

    /// Message plus a short cause chain. acme4j only says "Network error";
    /// the cause (refused, reset, timeout) is what the operator needs.
    private static String describe(Throwable error) {
        StringBuilder builder = new StringBuilder();
        String message = error.getMessage();
        builder.append(message == null || message.isBlank()
                ? error.getClass().getSimpleName() : message);
        Throwable cause = error.getCause();
        for (int depth = 0; cause != null && depth < 3; depth++, cause = cause.getCause()) {
            String detail = cause.getMessage();
            builder.append(" [").append(cause.getClass().getSimpleName());
            if (detail != null && !detail.isBlank()) {
                builder.append(": ").append(detail);
            }
            builder.append(']');
        }
        return builder.toString();
    }

    /// Drops the root label acme4j appends, so the name matches what DNS APIs
    /// expect. Package-private for the regression test.
    static String withoutTrailingDot(String name) {
        return name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
    }

    private static String sanitize(@Nullable String message) {
        if (message == null || message.isBlank()) {
            return "unknown error";
        }
        // acme4j may include the response body; it never contains our DNS/EAB
        // credentials, but keep the operator-facing line short.
        String oneLine = message.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() > 500 ? oneLine.substring(0, 500) + "…" : oneLine;
    }
}
