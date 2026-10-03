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
package org.jackhuang.hmcl.web;

import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.util.logging.Logger;
import org.jackhuang.hmcl.web.auth.AuthService;
import org.jackhuang.hmcl.web.auth.UserStore;
import org.jackhuang.hmcl.web.config.ServerConfig;
import org.jackhuang.hmcl.web.config.ServerConfigLoader;
import org.jackhuang.hmcl.web.server.HdslServer;
import org.jackhuang.hmcl.web.tls.CertificateManager;

import java.time.Duration;
import java.util.Map;

/// The HDSL-web server entry point.
///
/// `hdsl-web` starts the server from `$HDSL_DATA/server.yaml` (defaults to
/// `./data`), prints a banner naming where it listens, under which protocol,
/// whether authentication is on, and where the TLS identity comes from, then
/// blocks until a shutdown signal stops Jetty gracefully.
///
/// `hdsl-web -version` prints the banner and exits.
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        for (String arg : args) {
            if ("-version".equals(arg) || "--version".equals(arg)) {
                System.out.println("HDSL-web " + Metadata.VERSION);
                return;
            }
        }
        if (args.length > 0) {
            System.err.println("Unknown argument(s): " + String.join(" ", args));
            System.err.println("Usage: hdsl-web [-version]");
            System.exit(2);
        }

        try {
            Map<String, String> env = System.getenv();
            ServerConfig config = ServerConfigLoader.load(env, Logger.LOG);

            // The launcher's per-user home lives inside the data directory, so
            // a deployment mounts exactly one volume (`/data`, see the plan).
            // An explicit -Dhdsl.home wins, and the property must be set before
            // the first touch of Metadata, whose static initializer reads it.
            if (System.getProperty("hdsl.home") == null) {
                System.setProperty("hdsl.home", config.dataDir.resolve("hdsl").toString());
            }

            Logger.LOG.start(config.dataDir.resolve("logs"));

            UserStore users = UserStore.open(config.dataDir, env, Logger.LOG);
            AuthService auth = new AuthService(users, Duration.ofHours(config.auth.sessionTtlHours));
            CertificateManager certificates = CertificateManager.create(config, Logger.LOG);
            HdslServer server = HdslServer.build(config, auth, certificates, Logger.LOG);

            server.start();
            printBanner(config, server, certificates);
            server.join();
        } catch (ServerConfigLoader.ConfigException e) {
            System.err.println("Invalid configuration: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            Logger.LOG.error("Failed to start HDSL-web: " + e.getMessage(), e);
            System.exit(1);
        }
    }

    private static void printBanner(ServerConfig config, HdslServer server, CertificateManager certificates) {
        String scheme = config.https.enabled ? "https" : "http";
        System.out.println();
        System.out.println("HDSL-web " + Metadata.VERSION);
        System.out.println("  data dir : " + config.dataDir);
        System.out.println("  listening: " + scheme + "://" + config.bindHost + ":" + server.getPort());
        if (config.https.enabled && config.https.redirectHttp) {
            System.out.println("  redirect : http://" + config.bindHost + ":" + config.https.redirectPort + " -> https (308)");
        }
        System.out.println("  auth     : " + (config.auth.disabled
                ? "disabled (loopback bind only; do not expose this instance)"
                : "enabled (session cookie)"));
        System.out.println("  tls      : " + switch (certificates.getSource()) {
            case NONE -> "not enabled";
            case PKCS12 -> "PKCS#12 keystore";
            case PEM -> "PEM certificate";
            case SELF_SIGNED -> "self-signed (browsers will warn; configure https.cert_file/https.pkcs12_file to replace)";
        });
        System.out.println();
    }
}
