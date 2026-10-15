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
import org.jackhuang.hmcl.web.config.WorkspaceDirectory;
import org.jackhuang.hmcl.web.server.HdslServer;
import org.jackhuang.hmcl.web.tls.CertificateManager;

import java.nio.file.Path;
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

            // pnpm reads its registry from its own configuration file rather than from the
            // environment, so the source chosen in the panel is written there now as well as when
            // it changes: a container whose home directory was recreated still installs from the
            // mirror it was configured with.
            org.jackhuang.hmcl.dsh.PnpmConfigFile.apply();

            // The workspace is where a session's files live and where the
            // harness process runs: `<home>/workspace` (what the compose file
            // mounts), or HDSL_WORKSPACE when the operator named another
            // folder. Not the directory the launcher keeps its instances in,
            // which is what the domain defaults to.
            WorkspaceDirectory.install(env, Path.of(System.getProperty("user.home")), Logger.LOG);

            UserStore users = UserStore.open(config.dataDir, env, Logger.LOG);
            AuthService auth = new AuthService(users, Duration.ofHours(config.auth.sessionTtlHours));
            CertificateManager certificates = CertificateManager.create(config, Logger.LOG);
            HdslServer server = HdslServer.build(config, auth, certificates, Logger.LOG);

            server.start();
            printBanner(config, server, certificates, env);
            server.join();
        } catch (ServerConfigLoader.ConfigException e) {
            System.err.println("Invalid configuration: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            Logger.LOG.error("Failed to start HDSL-web: " + e.getMessage(), e);
            System.exit(1);
        }
    }

    private static void printBanner(ServerConfig config, HdslServer server, CertificateManager certificates,
                                    Map<String, String> env) {
        String scheme = config.https.enabled ? "https" : "http";
        System.out.println();
        System.out.println("HDSL-web " + Metadata.VERSION);
        System.out.println("  listening: " + scheme + "://" + config.bindHost + ":" + server.getPort());
        System.out.println("  open     : " + scheme + "://localhost:" + server.getPort()
                + (config.basePath.isEmpty() ? "/" : config.basePath + "/")
                + "  (first run: this page creates the admin account)");
        if (config.https.enabled && config.https.redirectHttp) {
            System.out.println("  redirect : http://" + config.bindHost + ":" + config.https.redirectPort + " -> https (308)");
        }
        if (config.https.enabled && config.https.redirectSamePort) {
            System.out.println("  redirect : http://<any address>:" + server.getPort()
                    + " -> https (308)  (cleartext on the TLS port)");
        }
        System.out.println("  data dir : " + config.dataDir);
        System.out.println("             (server.yaml / accounts / instances / pnpm store / certificates -");
        System.out.println("              back up or migrate by copying this folder; keep it off the public net)");
        System.out.println("  workspace: " + WorkspaceDirectory.resolve(env, Path.of(System.getProperty("user.home"))));
        System.out.println("  auth     : " + (config.auth.disabled
                ? "disabled (loopback bind only; do not expose this instance)"
                : "enabled (session cookie)"));
        System.out.println("  tls      : " + switch (certificates.getSource()) {
            case NONE -> "not enabled";
            case PKCS12 -> "PKCS#12 keystore";
            case PEM -> "PEM certificate";
            case SELF_SIGNED -> "self-signed (browsers will warn; configure https.cert_file/https.pkcs12_file to replace)";
        });
        if (!config.basePath.isEmpty()) {
            System.out.println("  base path: " + config.basePath + " (behind a path-stripping reverse proxy)");
        }

        // ---- 体检段：这两项缺了，面板能启动但"装不了东西 / 打不开页面"，
        // 裸部署（尤其 Windows）控制台是唯一窗口，必须在启动时说清楚 ----
        if (frontendPackaged()) {
            System.out.println("  frontend : packaged");
        } else {
            System.out.println("  !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
            System.out.println("  !! web frontend MISSING from this jar - every page    !!");
            System.out.println("  !! answers \"not found\". This build never packaged    !!");
            System.out.println("  !! the SPA (usually: built without Node.js on PATH).  !!");
            System.out.println("  !! Fix - either:                                      !!");
            System.out.println("  !!   1. download the release jar:                     !!");
            System.out.println("  !!      github.com/nas200-timmy/HDSL-WEB/releases     !!");
            System.out.println("  !!   2. or install Node.js LTS, then rebuild:         !!");
            System.out.println("  !!      gradlew shadowJar -PreleaseVersion=...        !!");
            System.out.println("  !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
        }
        String node = toolVersion("node", "--version");
        String pnpm = toolVersion("pnpm", "--version");
        if (node != null && pnpm != null) {
            System.out.println("  runtime  : node " + node + ", pnpm " + pnpm);
        } else {
            System.out.println("  runtime  : " + (node == null ? "node MISSING" : "node " + node)
                    + ", " + (pnpm == null ? "pnpm MISSING" : "pnpm " + pnpm));
            System.out.println("  !! installing dsh versions / plugins / brand tools needs");
            System.out.println("  !! node + pnpm. Either:");
            System.out.println("  !!   1. one-click a panel-managed runtime (Settings -> Node.js);");
            System.out.println("  !!   2. or install Node.js LTS yourself: https://nodejs.org");
        }
        if (org.jackhuang.hmcl.util.platform.OperatingSystem.CURRENT_OS
                == org.jackhuang.hmcl.util.platform.OperatingSystem.WINDOWS) {
            // POSIX 0600 has no NTFS equivalent: Java's setReadable maps onto
            // ACLs unpredictably, and the credential file can be readable by
            // other accounts on a multi-user machine. Worth saying out loud.
            System.out.println("  note     : Windows has no POSIX 0600 semantics - the credential file");
            System.out.println("             may be readable by other accounts on this machine. Single-");
            System.out.println("             user machines are fine; keep the data dir off the public net.");
        }
        System.out.println();
    }

    /// Whether the SPA shell is on the classpath. A classes/dev build without
    /// the packaged frontend boots fine and then answers every page with a bare
    /// "not found" — this check is what turns that into a printed diagnosis.
    private static boolean frontendPackaged() {
        return Main.class.getClassLoader().getResource("web/index.html") != null;
    }

    /// Runs `tool --version`, returning the trimmed first line or `null`.
    /// Best effort: any failure means "not available", and the banner says so.
    private static @org.jetbrains.annotations.Nullable String toolVersion(String tool, String flag) {
        try {
            Process process = new ProcessBuilder(tool, flag).redirectErrorStream(true).start();
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) {
                return null;
            }
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                return line == null ? null : line.trim();
            }
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }
}
