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
package org.jackhuang.hmcl.web.ssh;

import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.loader.openssh.OpenSSHKeyPairResourceParser;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.session.SessionContext;
import org.apache.sshd.common.session.SessionListener;
import org.apache.sshd.common.util.buffer.ByteArrayBuffer;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.forward.RejectAllForwardingFilter;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.session.ServerSession;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshPaths;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.util.logging.Logger;
import org.jackhuang.hmcl.web.developer.DeveloperAuth;
import org.jackhuang.hmcl.web.audit.AuditLog;
import org.jackhuang.hmcl.web.auth.AuthService;
import org.jackhuang.hmcl.web.auth.LoginThrottle;
import org.jackhuang.hmcl.web.brand.Brand;
import org.jackhuang.hmcl.web.brand.BrandInstance;
import org.jackhuang.hmcl.web.brand.BrandInstanceManager;
import org.jackhuang.hmcl.web.config.ServerConfig;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/// The embedded SSH endpoint of the panel (docs/ssh-terminal-channel.md,
/// milestone M1: switch, platform gate, developer password, skeleton).
///
/// The endpoint is a **capability-true-subset** door, not a shell box:
/// no `CommandFactory` (an `exec` request has nothing to run and is refused
/// by the channel layer), no SFTP subsystem, [RejectAllForwardingFilter] for
/// every forwarding kind, and the only door in is [MenuShellFactory]: the
/// TUI gate + numbered instance menu of milestone M2.
///
/// Authentication: publickey against the panel-managed
/// [AuthorizedKeys] (always on), password against the panel user store
/// (off unless `ssh.password_auth` is on; while it is on, every guess spends
/// this channel's own per-IP [LoginThrottle] budget, with the same policy as
/// the panel sign-in). The host key is an
/// Ed25519 pair generated on first start and persisted under
/// `<data>/ssh/hostkey_ed25519` so restarts keep the fingerprint.
///
/// Lifecycle: [start] refuses unless `config.ssh.enabled` is true (the hard
/// guarantee behind "off = no attack surface") and unless the platform gate
/// passes; [HdslServer] starts it after Jetty is up and stops it
/// symmetrically. A start/stop pair audits `ssh.enable` / `ssh.disable` on
/// the ssh channel; connections, disconnects and failed authentications
/// audit as they happen.
@NotNullByDefault
public final class SshService {

    /// Where the listener actually is; null port means "not running".
    public record Address(String host, int port) {
    }

    /// Name of the persisted host key file inside the channel directory.
    public static final String HOST_KEY_FILE = "hostkey_ed25519";

    /// How long the gate refuses guesses — and how long the peer that spent
    /// the budget is refused SSH logins — once 15 answers were wrong.
    static final Duration GATE_BLOCK = Duration.ofMinutes(30);

    private final ServerConfig config;
    private final AuthService auth;
    /// The credential the TUI gate of [MenuShellFactory] asks for.
    private final DeveloperAuth devAuth;
    private final Logger log;
    private final Path sshDir;
    private final AuthorizedKeys authorizedKeys;
    /// One budget per source IP, shared semantics with the panel sign-in.
    private final LoginThrottle passwordThrottle = new LoginThrottle();
    /// The TUI gate's cross-connection budget. One key for every peer
    /// ([MenuShellFactory#GATE_BUDGET_KEY]): 15 wrong answers inside the
    /// window buy a 30-minute refusal of the gate for everybody — moving to
    /// another address does not shorten it.
    private final LoginThrottle gateThrottle =
            new LoginThrottle(LoginThrottle.MAX_FAILURES, LoginThrottle.WINDOW, GATE_BLOCK);
    /// Peers whose SSH *logins* are refused because they spent the gate's
    /// budget: address → when the refusal ends. Bounded like the throttles.
    private final ConcurrentHashMap<String, Instant> blockedPeers = new ConcurrentHashMap<>();
    private final AtomicInteger connections = new AtomicInteger();
    private final SessionListener auditListener = new AuditSessionListener();

    /// What a menu choice attaches to, wired by the composition root
    /// ([org.jackhuang.hmcl.web.server.HdslServer] hands in a
    /// [DshAttachSupport]); null leaves the menu answering "未启用".
    private volatile @Nullable AttachSupport attachSupport;

    private volatile @Nullable SshServer server;
    private volatile boolean running;

    public SshService(ServerConfig config, AuthService auth, DeveloperAuth devAuth, Logger log) {
        this.config = config;
        this.auth = auth;
        this.devAuth = devAuth;
        this.log = log;
        this.sshDir = config.dataDir.resolve(AuthorizedKeys.DIR_NAME);
        this.authorizedKeys = AuthorizedKeys.open(config.dataDir);
    }

    /// Whether the listener is up. The answer is the service's own word,
    /// not a probe, so the toggle API can rely on it mid-flight.
    public boolean isRunning() {
        return running;
    }

    /// The port the listener actually bound (0 in the config means the OS
    /// picked one). -1 when not running.
    public int activePort() {
        SshServer sshd = server;
        return running && sshd != null ? sshd.getPort() : -1;
    }

    /// How many SSH sessions are open right now.
    public int connections() {
        return Math.max(0, connections.get());
    }

    /// The keys the publickey authenticator consults.
    public AuthorizedKeys authorizedKeys() {
        return authorizedKeys;
    }

    /// What a menu choice attaches to. The composition root hands in the
    /// real spawn/byte-pump ([DshAttachSupport]); until then (and in tests)
    /// the menu answers "未启用".
    public void attachSupport(@Nullable AttachSupport support) {
        attachSupport = support;
    }

    /// The OpenSSH-format SHA256 fingerprint of the host key, or null when
    /// the listener is not running (or the key cannot be read).
    public @Nullable String hostKeyFingerprint() {
        SshServer sshd = server;
        if (sshd == null) {
            return null;
        }
        try {
            Iterable<KeyPair> keys = sshd.getKeyPairProvider().loadKeys(null);
            for (KeyPair key : keys) {
                ByteArrayBuffer wire = new ByteArrayBuffer();
                wire.putPublicKey(key.getPublic());
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(wire.getCompactData());
                return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
            }
            return null;
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            log.warning("Could not compute the SSH host key fingerprint: " + e);
            return null;
        }
    }

    /// Binds the listener when `config.ssh.enabled` is true. A disabled
    /// channel is a silent no-op; an unsupported platform is refused loudly
    /// (warning + audited `blocked`) — the third layer of the platform gate.
    public synchronized void start() {
        if (!config.ssh.enabled) {
            return;
        }
        if (!PlatformGuard.supported()) {
            log.warning("The SSH channel is not supported on this platform (" + verdictText()
                    + "); refusing to start. See docs/ssh-terminal-channel.md.");
            AuditLog.log(AuditLog.Channel.SSH, "ssh.enable", null, "blocked", null, null);
            return;
        }
        if (running) {
            return;
        }
        try {
            SshServer sshd = SshServer.setUpDefaultServer();
            sshd.setHost(config.ssh.bindHost);
            sshd.setPort(config.ssh.port);

            Files.createDirectories(sshDir);
            setOwnerOnly(sshDir);
            SimpleGeneratorHostKeyProvider hostKeys = new SimpleGeneratorHostKeyProvider(sshDir.resolve(HOST_KEY_FILE)) {
                /// Read through sshd's own OpenSSH parser, never the
                /// auto-registered BouncyCastle one: BC parses the Ed25519
                /// file into an unrelated EC key, which would change the
                /// fingerprint on every restart. The Ed25519 entry decoder
                /// is i2p-based, so a reloaded key is byte-identical to the
                /// generated one.
                @Override
                protected Iterable<KeyPair> doReadKeyPairs(SessionContext session, NamedResource resourceKey,
                        InputStream inputStream) throws IOException, GeneralSecurityException {
                    Collection<KeyPair> keys = OpenSSHKeyPairResourceParser.INSTANCE
                            .loadKeyPairs(session, resourceKey, null, inputStream);
                    return keys == null ? List.of() : keys;
                }
            };
            // "EdDSA" resolves through sshd's provider machinery to the i2p
            // EdDSA provider, whose KeyPairGenerator rejects the JDK
            // NamedParameterSpec ("parameter object not a
            // EdDSAParameterSpec") — the curve is therefore pinned in the
            // provider's own spec language. Generated once, kept forever: a
            // restarted panel answers with the same fingerprint clients
            // pinned.
            hostKeys.setAlgorithm("EdDSA");
            hostKeys.setKeySpec(EdDSANamedCurveTable.getByName("Ed25519"));
            hostKeys.setOverwriteAllowed(false);
            sshd.setKeyPairProvider(hostKeys);

            sshd.setPublickeyAuthenticator(this::authenticateKey);
            if (config.ssh.passwordAuth) {
                sshd.setPasswordAuthenticator(this::authenticatePassword);
            }
            // No interactive fallback: either password auth is on (then the
            // factory list from checkConfig already covers it) or the method
            // must not be offered at all.
            sshd.setKeyboardInteractiveAuthenticator(null);

            sshd.setShellFactory(new MenuShellFactory(
                    devAuth, this::dshInstanceRows, this::brandInstanceRows,
                    () -> new SessionCatalog(DshPaths.INSTANCES).list(), attachSupport, gateThrottle,
                    this::blockPeer));
            sshd.setForwardingFilter(RejectAllForwardingFilter.INSTANCE);
            sshd.addSessionListener(auditListener);

            sshd.start();
            setOwnerOnly(sshDir.resolve(HOST_KEY_FILE));

            server = sshd;
            running = true;
            log.info("SSH channel listening on " + config.ssh.bindHost + ":" + sshd.getPort()
                    + " (host key SHA256 fingerprint " + hostKeyFingerprint() + ")");
            AuditLog.log(AuditLog.Channel.SSH, "ssh.enable", null, "success", null, null);
        } catch (IOException | RuntimeException e) {
            log.warning("Failed to start the SSH channel", e);
            AuditLog.log(AuditLog.Channel.SSH, "ssh.enable", null, "failure", null, null);
        }
    }

    /// Closes the listener, abruptly — the toggle promises the port is free
    /// when the answer returns. Audits `ssh.disable` when it was up.
    public synchronized void stop() {
        SshServer sshd = server;
        server = null;
        running = false;
        if (sshd != null) {
            try {
                sshd.close(true).await(Duration.ofSeconds(10));
            } catch (IOException | RuntimeException e) {
                log.warning("Failed to stop the SSH channel cleanly", e);
            }
            AuditLog.log(AuditLog.Channel.SSH, "ssh.disable", null, "success", null, null);
        }
    }

    /// Stops and starts again when running — how the password-auth switch
    /// hot-applies: the offered user-auth methods are fixed at start, so a
    /// running listener has to be re-resolved to add or drop "password".
    public synchronized void restartIfRunning() {
        if (!running) {
            return;
        }
        stop();
        start();
    }

    private boolean authenticatePassword(String username, String password, ServerSession session) {
        String address = peerAddress(session);
        Instant now = Instant.now();
        // The refusal happens before the PBKDF2 work, exactly like the panel
        // sign-in: the check is the cost an attacker is trying to spend.
        if (isPeerBlocked(address)) {
            AuditLog.log(AuditLog.Channel.SSH, "ssh.auth-blocked", "password", "blocked", username, address);
            return false;
        }
        if (passwordThrottle.isBlocked(address, now)) {
            AuditLog.log(AuditLog.Channel.SSH, "ssh.auth-failure", "password", "blocked", username, address);
            return false;
        }
        if (!auth.userStore().verify(username, password)) {
            Duration refusedFor = passwordThrottle.recordFailure(address, now);
            AuditLog.log(AuditLog.Channel.SSH, "ssh.auth-failure", "password",
                    refusedFor.isZero() ? "failure" : "blocked", username, address);
            return false;
        }
        passwordThrottle.clear(address);
        return true;
    }

    private boolean authenticateKey(String username, PublicKey key, ServerSession session) {
        String address = peerAddress(session);
        if (isPeerBlocked(address)) {
            AuditLog.log(AuditLog.Channel.SSH, "ssh.auth-blocked", "publickey", "blocked", username, address);
            return false;
        }
        try {
            if (authorizedKeys.contains(key)) {
                return true;
            }
            AuditLog.log(AuditLog.Channel.SSH, "ssh.auth-failure", "publickey", "failure", username,
                    address);
            return false;
        } catch (IOException e) {
            log.warning("Could not read authorized_keys during an SSH login", e);
            return false;
        }
    }

    /// Refuses SSH logins from `address` for [GATE_BLOCK], because that peer
    /// spent the TUI gate's budget. Wired into [MenuShellFactory] as its
    /// `banPeer` callback: the gate is global, this refusal is per address,
    /// so the peer that did the guessing cannot come back to try again.
    void blockPeer(String address) {
        blockedPeers.put(address, Instant.now().plus(GATE_BLOCK));
        pruneBlockedPeers();
        log.info("SSH logins from " + address + " are refused for " + GATE_BLOCK.toMinutes()
                + " minutes: the developer-password gate spent its budget");
    }

    /// Reports whether an address's SSH logins are being refused.
    ///
    /// @param address the peer address
    /// @return whether the refusal is still in force
    boolean isPeerBlocked(String address) {
        Instant until = blockedPeers.get(address);
        if (until == null) {
            return false;
        }
        if (Instant.now().isBefore(until)) {
            return true;
        }
        blockedPeers.remove(address, until);
        return false;
    }

    /// Keeps [blockedPeers] from growing without bound. Entries are refusals
    /// only, so evicting the expired ones is enough.
    private void pruneBlockedPeers() {
        if (blockedPeers.size() <= 1024) {
            return;
        }
        Instant now = Instant.now();
        blockedPeers.entrySet().removeIf(entry -> now.isAfter(entry.getValue()));
    }

    /// The menu's "dsh 实例" section: the selected game directory's
    /// instances, resolved through [GameDirectoryManager] →
    /// [org.jackhuang.hmcl.setting.DshInstanceRepository] the same selection
    /// the web home page renders. A dsh instance's id is its name, so the
    /// label is the id.
    private List<MenuShellFactory.Entry> dshInstanceRows() {
        List<MenuShellFactory.Entry> rows = new ArrayList<>();
        for (DshInstance instance : GameDirectoryManager.getSelectedRepository().getInstances()) {
            rows.add(new MenuShellFactory.Entry("dsh", instance.id(), instance.id()));
        }
        return rows;
    }

    /// The menu's "品牌实例" section: every brand's instances under
    /// `<data>/brands/<brand>/instances` — the same manifest tree
    /// [org.jackhuang.hmcl.web.brand.BrandApiServlet] serves.
    private List<MenuShellFactory.Entry> brandInstanceRows() {
        List<MenuShellFactory.Entry> rows = new ArrayList<>();
        Path base = config.dataDir.resolve("brands");
        for (Brand brand : Brand.values()) {
            BrandInstanceManager manager = new BrandInstanceManager(brand, base.resolve(brand.id())
                    .resolve("instances"));
            for (BrandInstance instance : manager.list()) {
                rows.add(new MenuShellFactory.Entry("brand", brand.id() + ":" + instance.id(),
                        brand.id() + ": " + instance.name()));
            }
        }
        return rows;
    }

    /// Connect and disconnect are meta events: they land in the audit trail,
    /// never the traffic that flows through the channel.
    private final class AuditSessionListener implements SessionListener {
        @Override
        public void sessionEstablished(Session session) {
            connections.incrementAndGet();
            AuditLog.log(AuditLog.Channel.SSH, "ssh.connect", null, "success", null, peerAddress(session));
        }

        @Override
        public void sessionClosed(Session session) {
            connections.updateAndGet(current -> Math.max(0, current - 1));
            AuditLog.log(AuditLog.Channel.SSH, "ssh.disconnect", null, "success", null, peerAddress(session));
        }
    }

    private static String peerAddress(Session session) {
        SocketAddress remote = session.getRemoteAddress();
        if (remote instanceof InetSocketAddress inet) {
            InetAddress address = inet.getAddress();
            return address == null ? inet.getHostString() : address.getHostAddress();
        }
        return String.valueOf(remote);
    }

    private static String verdictText() {
        return switch (PlatformGuard.verdict()) {
            case SUPPORTED -> "supported";
            case SUPPORTED_UNVERIFIED -> "supported-unverified";
            case UNSUPPORTED -> "unsupported";
        };
    }

    private static final Set<PosixFilePermission> OWNER_FILE = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private static final Set<PosixFilePermission> OWNER_DIR = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);

    private static void setOwnerOnly(Path path) {
        try {
            Files.setPosixFilePermissions(path, Files.isDirectory(path) ? OWNER_DIR : OWNER_FILE);
        } catch (IOException | UnsupportedOperationException e) {
            // Non-POSIX filesystems (Windows) cannot express this; the best
            // effort above is still applied by every other filesystem.
        }
    }
}
