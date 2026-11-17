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
import org.shredzone.acme4j.util.KeyPairUtils;

import java.io.StringWriter;
import java.net.URI;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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
                String rrName = Dns01Challenge.toRRName(authorization.getIdentifier());
                String digest = challenge.getDigest();
                boolean presented = false;
                try {
                    handler.present(rrName, digest);
                    presented = true;
                    challenge.trigger();
                    Status status = challenge.waitForCompletion(CHALLENGE_TIMEOUT);
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

            Status ready = order.waitUntilReady(ORDER_TIMEOUT);
            if (ready != Status.READY) {
                throw new AcmeClientException("the ACME order ended in " + ready + " before finalization");
            }
            KeyPair domainKey = KeyPairUtils.createKeyPair(2048);
            order.execute(domainKey);
            Status completed = order.waitForCompletion(ORDER_TIMEOUT);
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
            throw new AcmeClientException("ACME request failed: " + sanitize(e.getMessage()), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AcmeClientException("ACME request was interrupted", e);
        } catch (Exception e) {
            throw new AcmeClientException("ACME request failed: " + sanitize(e.getMessage()), e);
        }
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
