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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The renewal rules are the selling point: a valid certificate must not be
/// churned, an expiring one must be renewed, a failure must keep serving the
/// old certificate and back off.
class AcmeServiceTest {

    @TempDir
    Path dataDir;

    private ServerConfig config(int renewBeforeDays) {
        ServerConfig config = new ServerConfig();
        config.dataDir = dataDir;
        config.https.acme.enabled = true;
        config.https.acme.ca = "letsencrypt";
        config.https.acme.email = "ops@example.com";
        config.https.acme.domains.add("example.com");
        config.https.acme.provider = "cloudflare";
        config.https.acme.renewBeforeDays = renewBeforeDays;
        config.https.acme.acceptTerms = true;
        return config;
    }

    private AcmeCredentialStore credentials() throws Exception {
        AcmeCredentialStore store = AcmeCredentialStore.open(dataDir);
        store.save("cloudflare", Map.of("apiToken", "token"), Map.of());
        return store;
    }

    private static CertificateManager.Info info(Instant expires) {
        return new CertificateManager.Info("CN=example.com", "CN=test CA",
                List.of("DNS:example.com"), expires, "00");
    }

    private static final class FakeClient implements AcmeClient {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<Request> last = new AtomicReference<>();
        volatile boolean fail;

        @Override
        public IssuedCertificate issue(Request request, ChallengeHandler handler) throws AcmeClientException {
            calls.incrementAndGet();
            last.set(request);
            if (fail) {
                throw new AcmeClientException("simulated CA failure");
            }
            return new IssuedCertificate("-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n",
                    "-----BEGIN PRIVATE KEY-----\nMIIB\n-----END PRIVATE KEY-----\n",
                    Instant.now().plus(90, ChronoUnit.DAYS), request.domains());
        }
    }

    private static final class FakeSink implements AcmeService.CertificateSink {
        volatile CertificateManager.Source source = CertificateManager.Source.ACME;
        volatile CertificateManager.Info info;
        volatile Path installedCert;
        volatile Path installedKey;
        final AtomicInteger reloads = new AtomicInteger();
        volatile boolean tlsActive = true;

        @Override
        public CertificateManager.Source source() {
            return source;
        }

        @Override
        public CertificateManager.Info info() {
            return info;
        }

        @Override
        public void install(Path certPem, Path keyPem) {
            installedCert = certPem;
            installedKey = keyPem;
        }

        @Override
        public void reload() {
            reloads.incrementAndGet();
            tlsActive = true;
        }

        @Override
        public boolean tlsActive() {
            return tlsActive;
        }
    }

    @Test
    void validAcmeCertificateOnPlainConnectorIsActivated() throws Exception {
        FakeClient client = new FakeClient();
        FakeSink sink = new FakeSink();
        sink.info = info(Instant.now().plus(60, ChronoUnit.DAYS));
        sink.tlsActive = false;
        AcmeService service = new AcmeService(config(30), credentials(), client,
                new DnsTxtResolver(), sink, Logger.LOG);

        AcmeService.Status status = service.renewIfNeeded();

        assertEquals(AcmeService.State.ACTIVE, status.state());
        assertEquals(0, client.calls.get(), "a valid certificate is reused, not reissued");
        assertEquals(1, sink.reloads.get(), "the plain connector must be switched to TLS");
        assertTrue(sink.tlsActive);
        service.close();
    }

    @Test
    void renewsInsideTheWindow() throws Exception {
        FakeClient client = new FakeClient();
        FakeSink sink = new FakeSink();
        sink.info = info(Instant.now().plus(5, ChronoUnit.DAYS));
        AcmeService service = new AcmeService(config(30), credentials(), client,
                new DnsTxtResolver(), sink, Logger.LOG);

        AcmeService.Status status = service.renewIfNeeded();

        assertEquals(AcmeService.State.ACTIVE, status.state());
        assertEquals(1, client.calls.get());
        assertNotNull(sink.installedCert);
        assertNotNull(sink.installedKey);
        assertEquals(1, sink.reloads.get());
        assertTrue(Files.isRegularFile(dataDir.resolve("certs/acme/fullchain.pem")));
        assertTrue(Files.isRegularFile(dataDir.resolve("certs/acme/privkey.pem")));
        assertEquals(List.of("example.com"), client.last.get().domains());
        service.close();
    }

    @Test
    void doesNotChurnAFreshCertificate() throws Exception {
        FakeClient client = new FakeClient();
        FakeSink sink = new FakeSink();
        sink.info = info(Instant.now().plus(60, ChronoUnit.DAYS));
        AcmeService service = new AcmeService(config(30), credentials(), client,
                new DnsTxtResolver(), sink, Logger.LOG);

        AcmeService.Status status = service.renewIfNeeded();

        assertEquals(AcmeService.State.ACTIVE, status.state());
        assertEquals(0, client.calls.get());
        assertEquals(0, sink.reloads.get());
        service.close();
    }

    @Test
    void failureKeepsTheOldCertificateAndBacksOff() throws Exception {
        FakeClient client = new FakeClient();
        client.fail = true;
        FakeSink sink = new FakeSink();
        sink.info = info(Instant.now().plus(5, ChronoUnit.DAYS));
        AcmeService service = new AcmeService(config(30), credentials(), client,
                new DnsTxtResolver(), sink, Logger.LOG);

        AcmeService.Status status = service.renewIfNeeded();

        assertEquals(AcmeService.State.ERROR, status.state());
        assertNotNull(status.lastError());
        assertTrue(status.lastError().contains("simulated CA failure"));
        assertNotNull(status.nextAttempt());
        assertEquals(0, sink.reloads.get());
        assertNull(sink.installedCert, "a failed renewal must not replace the live identity");
        service.close();
    }

    @Test
    void selfSignedFallbackIsReplacedEvenWhenItsExpiryIsFarAway() throws Exception {
        FakeClient client = new FakeClient();
        FakeSink sink = new FakeSink();
        sink.source = CertificateManager.Source.SELF_SIGNED;
        sink.info = info(Instant.now().plus(800, ChronoUnit.DAYS));
        AcmeService service = new AcmeService(config(30), credentials(), client,
                new DnsTxtResolver(), sink, Logger.LOG);

        AcmeService.Status status = service.renewIfNeeded();

        assertEquals(AcmeService.State.ACTIVE, status.state());
        assertEquals(1, client.calls.get(), "a self-signed fallback must be replaced by ACME");
        service.close();
    }

    @Test
    void aFailedIssuanceKeepsTheStageThatFailed() throws Exception {
        FakeClient client = new FakeClient();
        client.fail = true;
        AcmeService service = new AcmeService(config(30), credentials(), client,
                new DnsTxtResolver(), new FakeSink(), Logger.LOG);

        AcmeService.Status status = service.issueNow();

        assertEquals(AcmeService.State.ERROR, status.state());
        // The settings page renders this as the red step; collapsing it to
        // Phase.ERROR would leave every step grey and the operator guessing.
        assertEquals(AcmeService.Phase.ORDER, status.phase());
        assertNotNull(status.lastError());
        service.close();
    }

    @Test
    void disabledServiceDoesNothing() throws Exception {
        ServerConfig config = config(30);
        config.https.acme.enabled = false;
        FakeClient client = new FakeClient();
        FakeSink sink = new FakeSink();
        sink.info = info(Instant.now().plus(1, ChronoUnit.DAYS));
        AcmeService service = new AcmeService(config, credentials(), client,
                new DnsTxtResolver(), sink, Logger.LOG);

        AcmeService.Status status = service.renewIfNeeded();

        assertEquals(AcmeService.State.DISABLED, status.state());
        assertEquals(0, client.calls.get());
        service.close();
    }
}
