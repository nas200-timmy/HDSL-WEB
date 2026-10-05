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
package org.jackhuang.hmcl.web.config;

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;

/// The declared configuration of the HDSL-web server, as described in
/// docs/plan.md ("声明式 HTTPS 设计" and "认证设计").
///
/// Every field is optional: [ServerConfigLoader] fills the documented
/// defaults from `server.yaml` and the environment, and validates the result.
@NotNullByDefault
public final class ServerConfig {

    /// The directory all server-owned data lives in (`HDSL_DATA`, default `./data`).
    public Path dataDir = Path.of("data").toAbsolutePath().normalize();

    /// The address the main connector binds (`HDSL_BIND_HOST`, default `0.0.0.0`).
    public String bindHost = "0.0.0.0";

    /// The main connector port (`HDSL_PORT`, default 3080). 0 means "pick a
    /// free port", which tests use; the real port is then read from the
    /// running connector.
    public int port = 3080;

    /// HTTPS settings; see [HttpsConfig].
    public final HttpsConfig https = new HttpsConfig();

    /// The public base URL of this instance behind a reverse proxy, e.g.
    /// `https://dsh.example.com`. Phase 1 only stores it; Phase 2 uses it for
    /// `--trusted-host` and the certificate manager already borrows its host
    /// for the self-signed SAN list.
    public String publicBaseUrl = "";

    /// Authentication settings; see [AuthConfig].
    public final AuthConfig auth = new AuthConfig();

    /// The experimental ZCode category; see [ZcodeConfig].
    public final ZcodeConfig zcode = new ZcodeConfig();

    /// ZCode category knobs. Both come from the environment only — the shape
    /// of a ZCode distribution is not something `server.yaml` promises.
    public static final class ZcodeConfig {
        /// Directory holding a self-built ZCode distribution
        /// (`HDSL_ZCODE_PACKAGE`); it must contain `bin/zcode.mjs`. Empty means
        /// `<dataDir>/zcode/current/`.
        public String packageDir = "";

        /// The node executable used to run ZCode (`HDSL_ZCODE_NODE`).
        /// Empty means `node` resolved from `PATH`.
        public String nodePath = "";
    }

    public static final class HttpsConfig {
        /// Whether the main connector speaks TLS (`HDSL_HTTPS`, default false).
        public boolean enabled = false;

        /// PEM certificate chain, relative to `HDSL_DATA` or absolute.
        public String certFile = "";

        /// PEM private key, relative to `HDSL_DATA` or absolute.
        public String keyFile = "";

        /// PKCS#12 keystore, relative to `HDSL_DATA` or absolute. When both
        /// PEM and PKCS#12 are configured, the PKCS#12 file wins.
        public String pkcs12File = "";

        public String pkcs12Password = "";

        /// Whether to run a second, plain-HTTP connector that answers every
        /// request with 308 to the HTTPS URL of the same host. Only meaningful
        /// when HTTPS is enabled.
        public boolean redirectHttp = false;

        /// The port of that redirect connector (default 80).
        public int redirectPort = 80;

        /// Whether a certificate file was explicitly configured (PKCS#12 or PEM).
        public boolean hasExplicitCertificate() {
            return !pkcs12File.isBlank() || (!certFile.isBlank() && !keyFile.isBlank());
        }
    }

    public static final class AuthConfig {
        /// Whether authentication is turned off entirely. Only allowed when
        /// the server binds a loopback address (see docs/plan.md).
        public boolean disabled = false;

        /// Session time to live in hours; sessions slide forward on every
        /// verified request (default 168 = one week).
        public int sessionTtlHours = 168;
    }
}
