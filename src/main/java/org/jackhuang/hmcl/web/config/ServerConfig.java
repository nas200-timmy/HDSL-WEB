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

    /// Base path the panel is mounted under when a reverse proxy serves it
    /// from a subpath (`HDSL_BASE_PATH`, default "" = root mount). Given in
    /// "/panel" form; the empty string means root. The proxy is expected to
    /// strip the prefix before forwarding — the same mounting style as the
    /// panel's own `/i/<id>/` proxy. The value is injected into the served
    /// index.html (`<base href>` + `window.__HDSL_BASE__`) so the SPA's
    /// relative asset/API/WS paths resolve inside the mount.
    public String basePath = "";

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

    /// ZCode category knobs. All of them come from the environment only — the
    /// shape of a ZCode distribution, and of the machine that builds it, is not
    /// something `server.yaml` promises.
    public static final class ZcodeConfig {
        /// Directory holding a self-built ZCode distribution
        /// (`HDSL_ZCODE_PACKAGE`); it must contain `bin/zcode.mjs`. Empty means
        /// the newest installed release below `<dataDir>/zcode/releases/`.
        public String packageDir = "";

        /// The node executable used to run ZCode (`HDSL_ZCODE_NODE`).
        /// Empty means `node` resolved from `PATH`.
        public String nodePath = "";

        /// The directory prepended to `PATH` while building
        /// (`HDSL_ZCODE_BUILD_BIN`). The image points it at a Node 24 install,
        /// because upstream's build refuses anything older. Empty keeps the
        /// panel's own `PATH`.
        public String buildBin = "";

        /// The pnpm executable used for the build (`HDSL_ZCODE_PNPM`). Tests
        /// point it at a stub; the default is whatever `PATH` resolves.
        public String pnpm = "pnpm";

        /// Source tarball template (`HDSL_ZCODE_SOURCE_URL`); `%s` is the tag or
        /// branch name. Defaults to the GitHub codeload endpoint for tags.
        public String sourceUrl = "https://codeload.github.com/zai-org/ZCode/tar.gz/refs/tags/%s";

        /// Keeps the build's source tree and dependency install afterwards
        /// (`HDSL_ZCODE_KEEP_SOURCES`) — for debugging a failing build, at the
        /// cost of gigabytes.
        public boolean keepSources = false;
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
