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
package org.jackhuang.hmcl.web.server;

import jakarta.servlet.DispatcherType;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.ee10.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.util.logging.Logger;
import org.jackhuang.hmcl.web.acp.AcpSessionManager;
import org.jackhuang.hmcl.web.auth.AuthFilter;
import org.jackhuang.hmcl.web.auth.AuthService;
import org.jackhuang.hmcl.web.config.ServerConfig;
import org.jackhuang.hmcl.web.event.EventBus;
import org.jackhuang.hmcl.web.http.AccountsApiServlet;
import org.jackhuang.hmcl.web.http.ApiServlet;
import org.jackhuang.hmcl.web.http.DoctorApiServlet;
import org.jackhuang.hmcl.web.http.ExportsApiServlet;
import org.jackhuang.hmcl.web.http.InstancesApiServlet;
import org.jackhuang.hmcl.web.http.PacksApiServlet;
import org.jackhuang.hmcl.web.http.PluginsApiServlet;
import org.jackhuang.hmcl.web.http.StaticServlet;
import org.jackhuang.hmcl.web.http.TasksApiServlet;
import org.jackhuang.hmcl.web.http.TlsApiServlet;
import org.jackhuang.hmcl.web.http.VendorsApiServlet;
import org.jackhuang.hmcl.web.http.VersionsApiServlet;
import org.jackhuang.hmcl.web.instance.InstanceRuntime;
import org.jackhuang.hmcl.web.proxy.InstanceProxyServlet;
import org.jackhuang.hmcl.web.proxy.InstanceProxyWebSocket;
import org.jackhuang.hmcl.web.task.TaskService;
import org.jackhuang.hmcl.web.tls.CertificateManager;
import org.jackhuang.hmcl.web.ws.WsGateway;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/// The Jetty assembly behind the Phase 1 server:
///
/// - the main connector on `bind_host:port`, speaking TLS when
///   `https.enabled` (identity from [CertificateManager]);
/// - when `https.redirect_http` is on, a second plain-HTTP connector that
///   answers every request with 308 to the same host/path over HTTPS;
/// - one servlet context holding the session gate ([AuthFilter] on `/api/*`,
///   `/i/*` and `/ws/*`), the REST surface (auth/health/instances/versions/
///   tasks), the reverse proxy into dsh instances under `/i/*` (HTTP in
///   [org.jackhuang.hmcl.web.proxy.InstanceProxyServlet], WebSocket upgrades
///   relayed through [org.jackhuang.hmcl.web.proxy.InstanceProxyWebSocket]),
///   the `/ws` event gateway ([org.jackhuang.hmcl.web.ws.WsGateway]) and the
///   SPA shell ([StaticServlet]);
/// - the Phase 2 services behind it: [EventBus], [TaskService] and
///   [InstanceRuntime].
///
/// [start] registers a JVM shutdown hook so SIGTERM stops Jetty, whose stop
/// sequence drains in-flight connections (default 30s stop timeout) — a
/// shutdown does not cut requests in half.
@NotNullByDefault
public final class HdslServer {

    private final Server server;
    private final HttpConfiguration baseHttpConfig;
    /// The connector serving the panel — replaced wholesale when the
    /// certificate upload switches the port between plain HTTP and TLS at
    /// runtime. volatile: request threads read it while a switch is in flight.
    private volatile ServerConnector mainConnector;
    private final @Nullable ServerConnector redirectConnector;
    private volatile @Nullable SslContextFactory.Server sslContextFactory;
    private final WebSocketClient proxyWebSocketClient;
    /// The ACP console bridge; its processes outlive `/ws` connections and are
    /// all stopped when the server stops.
    private final AcpSessionManager acpSessions;
    private final Logger logger;
    private final AtomicLong startedAtMillis;
    private final AtomicBoolean hookInstalled = new AtomicBoolean();

    private HdslServer(Server server, HttpConfiguration baseHttpConfig, ServerConnector mainConnector,
                       @Nullable ServerConnector redirectConnector,
                       @Nullable SslContextFactory.Server sslContextFactory, WebSocketClient proxyWebSocketClient,
                       AcpSessionManager acpSessions, Logger logger, AtomicLong startedAtMillis) {
        this.server = server;
        this.baseHttpConfig = baseHttpConfig;
        this.mainConnector = mainConnector;
        this.redirectConnector = redirectConnector;
        this.sslContextFactory = sslContextFactory;
        this.proxyWebSocketClient = proxyWebSocketClient;
        this.acpSessions = acpSessions;
        this.logger = logger;
        this.startedAtMillis = startedAtMillis;
    }

    /// Assembles the server for `config`; nothing binds until [start].
    ///
    /// @param certificates the TLS identity source; may be null when HTTPS is
    ///                     off (then the config must say so too)
    public static HdslServer build(ServerConfig config, AuthService authService,
                                   @Nullable CertificateManager certificates, Logger logger) {
        Server server = new Server();
        // The stop sequence is owned by our own shutdown hook (below); Jetty's
        // built-in one would stop the server twice without adding anything.
        server.setStopAtShutdown(false);

        // Uptime is reported from the moment start() returned; the servlet is
        // built first, so it reads this holder.
        AtomicLong startedAtMillis = new AtomicLong();

        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.setSendServerVersion(false);

        SslContextFactory.Server ssl = null;
        ServerConnector mainConnector;
        if (config.https.enabled) {
            if (certificates == null) {
                throw new IllegalArgumentException("HTTPS is enabled but no CertificateManager was provided");
            }
            ssl = new SslContextFactory.Server();
            certificates.applyTo(ssl);
            HttpConfiguration httpsConfig = new HttpConfiguration(httpConfig);
            httpsConfig.setSecureScheme("https");
            httpsConfig.setSecurePort(config.port);
            httpsConfig.addCustomizer(secureRequestCustomizer());
            mainConnector = new ServerConnector(server, ssl, new HttpConnectionFactory(httpsConfig));
        } else {
            mainConnector = new ServerConnector(server, new HttpConnectionFactory(httpConfig));
        }
        mainConnector.setHost(config.bindHost);
        mainConnector.setPort(config.port);
        server.addConnector(mainConnector);

        ServerConnector redirectConnector = null;
        if (config.https.enabled && config.https.redirectHttp) {
            redirectConnector = new ServerConnector(server, new HttpConnectionFactory(httpConfig));
            redirectConnector.setHost(config.bindHost);
            redirectConnector.setPort(config.https.redirectPort);
            server.addConnector(redirectConnector);
        }

        int ttlSeconds = Math.toIntExact(java.time.Duration.ofHours(config.auth.sessionTtlHours).toSeconds());
        AuthFilter authFilter = new AuthFilter(authService, config.auth.disabled,
                () -> config.https.enabled, ttlSeconds);

        // The Phase 2 services: the event bus every WebSocket payload flows
        // through, the task pool behind installs and launches, and the
        // runtime that turns domain events into the panel's event stream.
        EventBus eventBus = new EventBus();
        TaskService taskService = new TaskService(eventBus);
        InstanceRuntime runtime = new InstanceRuntime(eventBus, taskService);
        runtime.wire();
        AcpSessionManager acpSessions = new AcpSessionManager(eventBus);
        WsGateway.install(eventBus, authService, config.auth.disabled, acpSessions);

        // The client behind the `/i/*` WebSocket relay; its life is the
        // server's own. Its message limits are raised for the same reason the
        // server-side ones are (see InstanceProxyWebSocket#MAX_MESSAGE_BYTES):
        // Jetty's 64 KiB default fails the connection on one session history.
        WebSocketClient proxyWebSocketClient = new WebSocketClient();
        proxyWebSocketClient.setMaxFrameSize(InstanceProxyWebSocket.MAX_MESSAGE_BYTES);
        proxyWebSocketClient.setMaxTextMessageSize(InstanceProxyWebSocket.MAX_MESSAGE_BYTES);
        proxyWebSocketClient.setMaxBinaryMessageSize(InstanceProxyWebSocket.MAX_MESSAGE_BYTES);

        // Multipart uploads (a plugin .tgz, a TLS certificate) need a staging
        // directory; 50 MiB caps the upload, anything larger is a 400. A pack
        // upload gets its own, larger bound: a `.dspack` carries a profile's
        // files, and 200 MiB is the documented cap.
        java.nio.file.Path uploadStaging;
        try {
            uploadStaging = config.dataDir.resolve("tmp");
            java.nio.file.Files.createDirectories(uploadStaging);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot create the upload staging directory in " + config.dataDir, e);
        }
        jakarta.servlet.MultipartConfigElement multipart = new jakarta.servlet.MultipartConfigElement(
                uploadStaging.toString(), 50L * 1024 * 1024, 55L * 1024 * 1024, 1024 * 1024);
        jakarta.servlet.MultipartConfigElement packMultipart = new jakarta.servlet.MultipartConfigElement(
                uploadStaging.toString(), 200L * 1024 * 1024, 210L * 1024 * 1024, 1024 * 1024);

        // The TLS settings endpoint switches this very server to TLS at
        // runtime; the reference is filled once the instance exists.
        java.util.concurrent.atomic.AtomicReference<HdslServer> self = new java.util.concurrent.atomic.AtomicReference<>();

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.addFilter(authFilter, "/api/*", EnumSet.of(DispatcherType.REQUEST));
        context.addFilter(authFilter, "/i/*", EnumSet.of(DispatcherType.REQUEST));
        context.addFilter(authFilter, "/ws/*", EnumSet.of(DispatcherType.REQUEST));
        context.addServlet(new ServletHolder(new ApiServlet(
                authService, authFilter, config.auth.disabled, Metadata.VERSION, startedAtMillis::get)), "/api/*");
        ServletHolder instancesHolder = new ServletHolder(new InstancesApiServlet(runtime, taskService, config));
        instancesHolder.getRegistration().setMultipartConfig(multipart);
        context.addServlet(instancesHolder, "/api/instances/*");
        context.addServlet(new ServletHolder(new VersionsApiServlet(taskService)), "/api/versions");
        context.addServlet(new ServletHolder(new TasksApiServlet(taskService)), "/api/tasks/*");
        context.addServlet(new ServletHolder(new PluginsApiServlet()), "/api/plugins/*");
        ServletHolder packsHolder = new ServletHolder(new PacksApiServlet(runtime, taskService, config));
        packsHolder.getRegistration().setMultipartConfig(packMultipart);
        context.addServlet(packsHolder, "/api/packs/*");
        context.addServlet(new ServletHolder(new ExportsApiServlet(config)), "/api/exports/*");
        context.addServlet(new ServletHolder(new DoctorApiServlet()), "/api/doctor");
        context.addServlet(new ServletHolder(new AccountsApiServlet(eventBus)), "/api/accounts/*");
        context.addServlet(new ServletHolder(new VendorsApiServlet()), "/api/vendors");
        ServletHolder tlsHolder = new ServletHolder(new TlsApiServlet(config, certificates, self::get));
        tlsHolder.getRegistration().setMultipartConfig(multipart);
        context.addServlet(tlsHolder, "/api/settings/*");
        context.addServlet(new ServletHolder(new InstanceProxyServlet(taskService)), "/i/*");
        context.addServlet(new ServletHolder(new StaticServlet()), "/");

        // `/ws` rides the jakarta WebSocket container of the context, which
        // funnels upgrades through the filter chain — that is what makes the
        // AuthFilter above the 401 gate for unauthenticated handshakes.
        JakartaWebSocketServletContainerInitializer.configure(context,
                (servletContext, container) -> container.addEndpoint(WsGateway.class));

        // Upgrades under `/i/*` are relayed to the instance's own WebSocket
        // endpoint. Jakarta endpoint paths are single-segment URI templates
        // and cannot express the multi-segment wildcard `/i/<id>/<rest…>`, so
        // the relay uses the core mapping API, which can.
        WebSocketUpgradeHandler instanceUpgrades = WebSocketUpgradeHandler.from(server, container -> {
            // Jetty's default frame and message limit is 64 KiB; a dsh payload
            // (a session history, a tool result) is routinely larger, and the
            // relay would fail the whole stream with 1009. Raised here as well
            // as on the client side.
            container.setMaxFrameSize(InstanceProxyWebSocket.MAX_MESSAGE_BYTES);
            container.setMaxTextMessageSize(InstanceProxyWebSocket.MAX_MESSAGE_BYTES);
            container.setMaxBinaryMessageSize(InstanceProxyWebSocket.MAX_MESSAGE_BYTES);
            container.addMapping("/i/*",
                    (upgradeRequest, upgradeResponse, callback) -> createRelay(
                            authService, config.auth.disabled, proxyWebSocketClient,
                            upgradeRequest, upgradeResponse, callback));
        });

        // The jakarta container's own upgrade filter prepends itself ahead of
        // every servlet filter, so the AuthFilter never sees a `/ws` upgrade
        // and an unauthenticated handshake would surface as a 500 from the
        // endpoint configurator. This handler is the gate instead: upgrade
        // requests to `/ws` without a session are answered 401 right here,
        // before the context ever negotiates.
        WsUpgradeAuthGate wsAuthGate = new WsUpgradeAuthGate(authService, config.auth.disabled);

        Handler handler = new Handler.Sequence(instanceUpgrades, wsAuthGate, context);
        if (redirectConnector != null) {
            handler = new Handler.Sequence(
                    new HttpsRedirectHandler(redirectConnector, mainConnector),
                    instanceUpgrades,
                    wsAuthGate,
                    context);
        }
        server.setHandler(handler);

        HdslServer built = new HdslServer(server, httpConfig, mainConnector, redirectConnector, ssl,
                proxyWebSocketClient, acpSessions, logger, startedAtMillis);
        self.set(built);
        return built;
    }

    /// The 401 gate in front of the `/ws` WebSocket endpoint: answers
    /// unauthenticated handshakes directly, passes everything else through.
    private static final class WsUpgradeAuthGate extends Handler.Abstract {
        private final AuthService authService;
        private final boolean authDisabled;

        private WsUpgradeAuthGate(AuthService authService, boolean authDisabled) {
            this.authService = authService;
            this.authDisabled = authDisabled;
        }

        @Override
        public boolean handle(Request request, Response response, Callback callback) {
            if (authDisabled || !request.getHeaders().contains(HttpHeader.UPGRADE)) {
                return false;
            }
            String path = request.getHttpURI().getPath();
            if (!path.equals("/ws") && !path.startsWith("/ws/")) {
                return false;
            }
            if (hasSession(authService, request.getHeaders())) {
                return false;
            }
            response.setStatus(401);
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, "application/json; charset=utf-8");
            byte[] body = "{\"error\":\"unauthorized\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            response.write(true, java.nio.ByteBuffer.wrap(body), callback);
            return true;
        }
    }

    /// The upgrade creator behind `/i/*`: an unauthenticated handshake is
    /// answered 401 in place; anything else becomes a relay whose upstream
    /// connection is opened when the endpoint comes up.
    private static @Nullable InstanceProxyWebSocket createRelay(
            AuthService authService, boolean authDisabled, WebSocketClient proxyWebSocketClient,
            org.eclipse.jetty.websocket.server.ServerUpgradeRequest upgradeRequest,
            org.eclipse.jetty.websocket.server.ServerUpgradeResponse upgradeResponse, Callback callback) {
        if (!authDisabled && !hasSession(authService, upgradeRequest.getHeaders())) {
            upgradeResponse.setStatus(401);
            callback.succeeded();
            return null;
        }
        String path = upgradeRequest.getHttpURI().getPath();
        String rest = path.startsWith("/i/") ? path.substring("/i/".length()) : "";
        int slash = rest.indexOf('/');
        String instanceId = slash < 0 ? rest : rest.substring(0, slash);
        String targetPath = slash < 0 ? "/" : rest.substring(slash);
        if (upgradeRequest.getHttpURI().getQuery() != null) {
            targetPath += "?" + upgradeRequest.getHttpURI().getQuery();
        }
        if (instanceId.isEmpty()) {
            upgradeResponse.setStatus(404);
            callback.succeeded();
            return null;
        }
        List<HttpField> headers = new ArrayList<>();
        upgradeRequest.getHeaders().forEach(headers::add);
        return new InstanceProxyWebSocket(proxyWebSocketClient, instanceId, targetPath, headers);
    }

    /// Whether the upgrade request carries a valid `hdsl_session` cookie.
    private static boolean hasSession(AuthService authService, org.eclipse.jetty.http.HttpFields headers) {
        String cookie = headers.get(HttpHeader.COOKIE);
        if (cookie == null) {
            return false;
        }
        for (String pair : cookie.split(";")) {
            String trimmed = pair.trim();
            if (trimmed.startsWith(AuthFilter.SESSION_COOKIE + "=")) {
                String token = trimmed.substring(AuthFilter.SESSION_COOKIE.length() + 1);
                return authService.verify(token).isPresent();
            }
        }
        return false;
    }

    /// Binds the connectors and registers the shutdown hook.
    public void start() throws Exception {
        proxyWebSocketClient.start();
        server.start();
        startedAtMillis.set(System.currentTimeMillis());
        if (hookInstalled.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    server.stop();
                } catch (Exception e) {
                    logger.warning("Failed to stop the server during shutdown", e);
                }
            }, "HDSL-web shutdown"));
        }
    }

    /// The port the main connector actually bound. With `port: 0` this is the
    /// ephemeral port the OS picked; meaningful after [start].
    public int getPort() {
        return mainConnector.getLocalPort();
    }

    /// The port of the HTTP→HTTPS redirect connector, or -1 without one.
    public int getRedirectPort() {
        return redirectConnector == null ? -1 : redirectConnector.getLocalPort();
    }

    /// The live [SslContextFactory] behind the main connector, for certificate
    /// hot-reload via `SslContextFactory.reload(...)`; empty when HTTP only.
    public Optional<SslContextFactory.Server> getSslContextFactory() {
        return Optional.ofNullable(sslContextFactory);
    }

    /// Whether the main connector currently speaks TLS.
    public boolean isTls() {
        return sslContextFactory != null;
    }

    /// The outcome of a TLS switch attempt: on success `restartRequired` is
    /// false; on failure the plain-HTTP connector was put back and a restart
    /// would apply the uploaded identity instead.
    public record TlsSwitch(boolean ok, boolean restartRequired, @Nullable String error) {
    }

    /// Swaps the live identity on a TLS connector without dropping the port.
    ///
    /// @param certificates the manager holding the new identity
    /// @return what happened
    public synchronized TlsSwitch reloadTls(CertificateManager certificates) {
        SslContextFactory.Server ssl = sslContextFactory;
        if (ssl == null) {
            return new TlsSwitch(false, true, "the server is not speaking TLS");
        }
        try {
            ssl.reload(certificates::applyTo);
            logger.info("TLS identity hot-reloaded from the uploaded certificate");
            return new TlsSwitch(true, false, null);
        } catch (Exception e) {
            logger.warning("Failed to reload the TLS identity", e);
            return new TlsSwitch(false, true, String.valueOf(e.getMessage()));
        }
    }

    /// The customizer both TLS connectors install.
    ///
    /// Its SNI host check is deliberately off. The panel serves exactly one
    /// identity, so the check has nothing to protect — but it does turn a
    /// probe that reaches the port by address (`curl https://127.0.0.1:…`,
    /// which sends no SNI and therefore matches no certificate in the
    /// keystore) into a 400 `Invalid SNI`, which is how the container's own
    /// health check kept failing while the server was perfectly healthy.
    /// `sniRequired` stays at its default `false`.
    private static SecureRequestCustomizer secureRequestCustomizer() {
        SecureRequestCustomizer customizer = new SecureRequestCustomizer();
        customizer.setSniHostCheck(false);
        return customizer;
    }

    /// Switches a plain-HTTP server to TLS at runtime: the plain connector is
    /// gracefully shut down — its accept socket closes immediately (an
    /// interrupted `accept()` closes the channel), freeing the port, while the
    /// connections already on it drain — and a TLS connector takes its place on
    /// the same host and port.
    ///
    /// The graceful shutdown is what makes this callable from a request being
    /// served by the very connector being replaced (the certificate upload is):
    /// a hard `stop()` would cut that connection before its answer is written.
    /// The retired connector is stopped for real once its last connection ends.
    ///
    /// Jetty supports runtime connector swaps, so this is a connector exchange,
    /// not a restart. On any failure a fresh plain connector is put back and
    /// the answer says a restart would apply the certificate instead — the
    /// upload has already been written to `server.yaml`, so a restart does
    /// exactly that.
    ///
    /// @param certificates the manager holding the uploaded identity
    /// @return what happened
    public synchronized TlsSwitch enableTls(CertificateManager certificates) {
        if (sslContextFactory != null) {
            // Already TLS; the caller should have reloaded instead.
            return reloadTls(certificates);
        }
        ServerConnector previous = mainConnector;
        String host = previous.getHost();
        int port = previous.getLocalPort();

        SslContextFactory.Server ssl = new SslContextFactory.Server();
        certificates.applyTo(ssl);
        HttpConfiguration httpsConfig = new HttpConfiguration(baseHttpConfig);
        httpsConfig.setSecureScheme("https");
        httpsConfig.setSecurePort(port);
        httpsConfig.addCustomizer(secureRequestCustomizer());
        ServerConnector tlsConnector = new ServerConnector(server, ssl, new HttpConnectionFactory(httpsConfig));
        tlsConnector.setHost(host);
        tlsConnector.setPort(port);

        java.util.concurrent.CompletableFuture<Void> drained;
        try {
            // Graceful shutdown only — do NOT removeConnector here: removing a
            // started connector stops it (Jetty's bean lifecycle), which would
            // cut the very connection the upload request rides on. The retired
            // connector is removed and stopped once drained.
            drained = previous.shutdown();
        } catch (Exception e) {
            logger.warning("TLS switch failed: the HTTP connector would not stop accepting", e);
            return new TlsSwitch(false, true, String.valueOf(e.getMessage()));
        }

        try {
            server.addConnector(tlsConnector);
            startAwaitingPort(tlsConnector);
        } catch (Exception e) {
            logger.warning("TLS switch failed; restoring the plain-HTTP connector", e);
            try {
                tlsConnector.stop();
            } catch (Exception ignored) {
                // Never started or already gone.
            }
            try {
                server.removeConnector(tlsConnector);
            } catch (Exception ignored) {
                // Not registered.
            }
            ServerConnector plain = new ServerConnector(server, new HttpConnectionFactory(baseHttpConfig));
            plain.setHost(host);
            plain.setPort(port);
            try {
                server.addConnector(plain);
                startAwaitingPort(plain);
                mainConnector = plain;
            } catch (Exception rollback) {
                logger.error("Could not restore the plain-HTTP connector on port " + port, rollback);
                retireWhenDrained(previous, drained);
                return new TlsSwitch(false, true, String.valueOf(e.getMessage())
                        + " (and restoring HTTP failed: " + rollback.getMessage() + ")");
            }
            retireWhenDrained(previous, drained);
            return new TlsSwitch(false, true, String.valueOf(e.getMessage()));
        }

        this.sslContextFactory = ssl;
        this.mainConnector = tlsConnector;
        retireWhenDrained(previous, drained);
        logger.info("TLS enabled at runtime: port " + port + " now speaks HTTPS");
        return new TlsSwitch(true, false, null);
    }

    /// Starts a connector on a port the retired connector is in the middle of
    /// releasing: the accept thread needs a moment after the interrupt for the
    /// channel to close, so a handful of retries bridges the gap before the
    /// switch is declared failed.
    private static void startAwaitingPort(ServerConnector connector) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 40; attempt++) {
            try {
                connector.start();
                return;
            } catch (Exception e) {
                last = e;
                if (!(e instanceof java.io.IOException) && !(e.getCause() instanceof java.io.IOException)) {
                    throw e;
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last == null ? new java.io.IOException("the port never came free") : last;
    }

    /// Removes and stops a gracefully-retired connector once its remaining
    /// connections have ended (with a bound, so a stuck connection cannot keep
    /// it forever). Removal is what stops it — Jetty stops a started bean on
    /// removal — so it must not happen before the drain.
    private void retireWhenDrained(ServerConnector retired,
                                   java.util.concurrent.CompletableFuture<Void> drained) {
        Thread closer = new Thread(() -> {
            try {
                drained.get(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // The bound elapsed; stop it anyway.
            }
            try {
                server.removeConnector(retired);
            } catch (Exception e) {
                logger.info("Could not remove the retired connector: " + e.getMessage());
            }
            try {
                retired.stop();
            } catch (Exception e) {
                logger.info("Could not stop the retired connector: " + e.getMessage());
            }
        }, "HDSL-web connector-retire");
        closer.setDaemon(true);
        closer.start();
    }

    /// Stops Jetty, waiting for in-flight connections to drain, and ends every
    /// ACP console the bridge is holding.
    public void stop() throws Exception {
        acpSessions.closeAll();
        server.stop();
        proxyWebSocketClient.stop();
    }

    /// Blocks the calling thread until the server has stopped.
    public void join() throws InterruptedException {
        server.join();
    }

    /// Answers every request arriving on the redirect connector with
    /// `308 Location: https://<host>:<mainPort><path>`; requests on any other
    /// connector pass through untouched.
    private static final class HttpsRedirectHandler extends Handler.Abstract {
        private final ServerConnector redirectConnector;
        private final ServerConnector mainConnector;

        private HttpsRedirectHandler(ServerConnector redirectConnector, ServerConnector mainConnector) {
            this.redirectConnector = redirectConnector;
            this.mainConnector = mainConnector;
        }

        @Override
        public boolean handle(Request request, Response response, Callback callback) {
            if (request.getConnectionMetaData().getConnector() != redirectConnector) {
                return false;
            }
            String host = request.getHeaders().get(HttpHeader.HOST);
            String bareHost = stripPort(host == null || host.isBlank() ? "localhost" : host);
            int mainPort = mainConnector.getLocalPort();

            HttpURI uri = request.getHttpURI();
            StringBuilder location = new StringBuilder("https://").append(bareHost);
            if (mainPort != 443) {
                location.append(':').append(mainPort);
            }
            location.append(uri.getPath());
            if (uri.getQuery() != null) {
                location.append('?').append(uri.getQuery());
            }

            response.setStatus(308);
            response.getHeaders().put(HttpHeader.LOCATION, location.toString());
            callback.succeeded();
            return true;
        }

        /// Drops the `:port` suffix of a Host header, keeping IPv6 brackets
        /// intact: `[::1]:8080` → `[::1]`, `example.com:80` → `example.com`.
        private static String stripPort(String host) {
            String trimmed = host.trim();
            if (trimmed.startsWith("[")) {
                int close = trimmed.indexOf(']');
                return close >= 0 ? trimmed.substring(0, close + 1) : trimmed;
            }
            int colon = trimmed.lastIndexOf(':');
            if (colon > 0 && colon == trimmed.indexOf(':')) {
                String suffix = trimmed.substring(colon + 1);
                if (!suffix.isEmpty() && suffix.chars().allMatch(Character::isDigit)) {
                    return trimmed.substring(0, colon);
                }
            }
            return trimmed;
        }
    }
}
