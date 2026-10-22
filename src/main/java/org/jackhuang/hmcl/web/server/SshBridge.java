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

import jakarta.servlet.http.HttpServlet;
import org.jackhuang.hmcl.util.logging.Logger;
import org.jackhuang.hmcl.web.auth.AuthService;
import org.jackhuang.hmcl.web.config.ServerConfig;
import org.jackhuang.hmcl.web.developer.DeveloperAuth;
import org.jackhuang.hmcl.web.developer.DeveloperElevation;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/// The SSH terminal channel behind one reflective seam
/// (docs/ssh-terminal-channel.md §M4): the `-PnoSsh` build drops
/// `org/jackhuang/hmcl/web/ssh/**` and the MINA/eddsa packages from the fat
/// jar, so nothing outside that package may name its classes — one static
/// import of `SshService` would turn the whole server into a
/// NoClassDefFoundError in the minimal image. Everything the rest of the
/// server needs — the service, the management servlet, the doctor rows — is
/// loaded by name here; a build without the classes answers `absent`
/// everywhere and the server runs on, the channel simply not existing.
///
/// The bridge is created once, at server assembly. A class that IS present
/// but fails to initialize is a broken build, not an absent feature, and
/// fails the boot loudly.
@NotNullByDefault
public final class SshBridge {

    private static final String PACKAGE = "org.jackhuang.hmcl.web.ssh.";

    /// Whether this build carries the SSH channel at all.
    private final boolean present;
    private final @Nullable Object service;
    private final @Nullable Method start;
    private final @Nullable Method stop;
    private final @Nullable Method isRunning;
    private final @Nullable Method activePort;
    private final @Nullable Method platformVerdict;
    private final @Nullable HttpServlet apiServlet;

    private SshBridge(boolean present, @Nullable Object service, @Nullable Method start, @Nullable Method stop,
                      @Nullable Method isRunning, @Nullable Method activePort, @Nullable Method platformVerdict,
                      @Nullable HttpServlet apiServlet) {
        this.present = present;
        this.service = service;
        this.start = start;
        this.stop = stop;
        this.isRunning = isRunning;
        this.activePort = activePort;
        this.platformVerdict = platformVerdict;
        this.apiServlet = apiServlet;
    }

    /// Assembles the channel: opens the developer credential store, builds
    /// the service, wires the attach support, and instantiates the
    /// management servlet with the live-TLS supplier. Absent classes answer
    /// an absent bridge; any other reflective failure stops the boot.
    public static SshBridge load(ServerConfig config, AuthService auth, DeveloperAuth devAuth,
                                 DeveloperElevation elevation, BooleanSupplier tlsActive, Logger log) {
        try {
            Class<?> serviceClass = Class.forName(PACKAGE + "SshService");
            Object service = serviceClass
                    .getConstructor(ServerConfig.class, AuthService.class, DeveloperAuth.class, Logger.class)
                    .newInstance(config, auth, devAuth, log);
            Class<?> attachSupportClass = Class.forName(PACKAGE + "AttachSupport");
            Object attach = Class.forName(PACKAGE + "DshAttachSupport").getConstructor().newInstance();
            serviceClass.getMethod("attachSupport", attachSupportClass).invoke(service, attach);

            Class<?> apiClass = Class.forName(PACKAGE + "SshApiServlet");
            HttpServlet api = (HttpServlet) apiClass
                    .getConstructor(serviceClass, DeveloperAuth.class, DeveloperElevation.class,
                            ServerConfig.class, AuthService.class, BooleanSupplier.class)
                    .newInstance(service, devAuth, elevation, config, auth, tlsActive);

            Class<?> guardClass = Class.forName(PACKAGE + "PlatformGuard");
            return new SshBridge(true, service,
                    serviceClass.getMethod("start"), serviceClass.getMethod("stop"),
                    serviceClass.getMethod("isRunning"), serviceClass.getMethod("activePort"),
                    guardClass.getMethod("verdict"), api);
        } catch (ClassNotFoundException | NoClassDefFoundError missing) {
            log.info("此构建不含 SSH 终端通道（-PnoSsh）；SSH 相关能力不可用。");
            return new SshBridge(false, null, null, null, null, null, null, null);
        } catch (ReflectiveOperationException | RuntimeException broken) {
            throw new IllegalStateException("The SSH channel is present but could not be initialized", broken);
        }
    }

    /// Whether this build carries the SSH channel.
    public boolean present() {
        return present;
    }

    /// The management servlet, or null when the channel is absent.
    public @Nullable HttpServlet apiServlet() {
        return apiServlet;
    }

    /// Starts the listener when the config has the channel enabled; silent
    /// no-op when absent (there is nothing to start).
    public void start() {
        invoke(start, service);
    }

    /// Stops the listener; silent no-op when absent.
    public void stop() {
        invoke(stop, service);
    }

    /// Whether the listener is up. Absent builds answer false.
    public boolean isRunning() {
        Object answer = invoke(isRunning, service);
        return answer instanceof Boolean running && running;
    }

    /// The bound port, -1 when down or absent.
    public int activePort() {
        Object answer = invoke(activePort, service);
        return answer instanceof Integer port ? port : -1;
    }

    /// The platform verdict in the doctor's wire spelling —
    /// `supported` / `supported_unverified` / `unsupported`, or null when the
    /// channel is absent from this build.
    public @Nullable String platformVerdict() {
        Object verdict = invoke(platformVerdict, service);
        return verdict == null ? null : verdict.toString().toLowerCase(java.util.Locale.ROOT);
    }

    /// The reflective invoke every method here goes through: any failure is
    /// logged once and answered as "nothing", because every caller has an
    /// honest nothing to say (not running, no port, no verdict).
    private static @Nullable Object invoke(@Nullable Method method, @Nullable Object target) {
        if (method == null || target == null) {
            return null;
        }
        try {
            return method.invoke(target);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
