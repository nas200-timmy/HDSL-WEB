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

import org.jetbrains.annotations.NotNullByDefault;

import java.security.KeyPair;
import java.time.Instant;
import java.util.List;

/// The ACME operation the renewal service needs, isolated from acme4j so the
/// state machine (issue / renew / back off / keep the old certificate) can be
/// tested without a real CA.
@NotNullByDefault
public interface AcmeClient {

    /// One issuance request. The account key is loaded and persisted by the
    /// service, not by the client.
    record Request(String directoryUrl, String email, List<String> domains,
                   boolean acceptTerms, @org.jetbrains.annotations.Nullable String eabKid,
                   @org.jetbrains.annotations.Nullable String eabHmacKey,
                   KeyPair accountKey) {
    }

    /// PEM chain + unencrypted PEM key, plus what the leaf says.
    record IssuedCertificate(String certificatePem, String privateKeyPem,
                             Instant notAfter, List<String> domains) {
    }

    /// The DNS hand-off the client uses for each authorization.
    interface ChallengeHandler {
        void present(String fqdn, String value) throws Exception;

        /// Called after [present] succeeded, before the challenge is triggered.
        /// Returning false fails the authorization — and, because presentation
        /// already happened, [cleanup] still runs. Do the propagation wait here
        /// rather than inside [present]: a timeout inside `present` is invisible
        /// to the caller and would leak the TXT record.
        default boolean awaitPropagation(String fqdn, String value) {
            return true;
        }

        void cleanup(String fqdn, String value) throws Exception;
    }

    /// Issues a certificate. The handler is called once per authorization;
    /// implementations must clean up in a finally block.
    IssuedCertificate issue(Request request, ChallengeHandler handler) throws AcmeClientException;
}
