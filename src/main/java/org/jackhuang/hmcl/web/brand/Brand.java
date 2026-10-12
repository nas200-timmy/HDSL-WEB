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
package org.jackhuang.hmcl.web.brand;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// The third-party tool categories the panel can install and serve, beyond the
/// dsh and ZCode ones.
///
/// Each value is a static description of one upstream tool — what the panel
/// installs (an npm package or a git repository), what it launches, and how
/// that process announces its port. Behavioural differences (launch flags,
/// port strategy, readiness line) live in [BrandRuntime] and switch on this
/// enum; everything here is data.
///
/// These tools are **third-party**: the panel only installs, launches and
/// reverse-proxies them. Their functionality, security and billing belong to
/// their respective vendors.
@NotNullByDefault
public enum Brand {
    /// Kimi Code (Moonshot AI): `kimi web` serves its own web UI on a
    /// loopback port; `--port 0` yields an ephemeral port announced on stdout.
    /// Its client is mount-aware, so instances are served under `/i/<id>/`
    /// like dsh (verified end to end with a headless browser).
    KIMI("kimi", Source.NPM, "@moonshot-ai/kimi-code", "kimi", false),

    /// OpenCode: `opencode web` serves its own web UI; `--port 0` is NOT
    /// honoured (it falls back to 4096), so the panel pre-allocates a port.
    /// Its client **routes by `location.pathname`** (verified in its bundle
    /// and by serving it under any subpath: the shell renders, the content
    /// stays blank). Mounted like everything else, such a client needs the
    /// router shim that reads the mount away from the paths it routes by and
    /// puts it back on the addresses it writes — see [needsRouterShim].
    OPENCODE("opencode", Source.NPM, "opencode-ai", "opencode", true),

    /// Claude Web (ypyik0669/claude-web, MIT): a community workbench around
    /// the Claude Code CLI — and, through its bundled engine and provider
    /// system, around relay APIs. **Not** Anthropic's hosted "Claude Code on
    /// the web", which is a SaaS and cannot be self-hosted.
    ///
    /// Distributed as git tags, not an npm package. The panel reads them over
    /// the **GitHub REST API** and installs by downloading the tag's source
    /// zip — deliberately not the git protocol: on this deployment's network
    /// `github.com:443`'s git transport is cut mid-handshake (measured:
    /// `expected flush after ref listing`, GnuTLS resets, 130 s connect
    /// timeouts), while `api.github.com` and `codeload.github.com` answer in
    /// well under two seconds. Over HTTPS the whole source is a plain,
    /// retryable download — and the panel needs no `git` at all.
    ///
    /// The server is plain Node (`tsx server/src/index.ts`), honours a `PORT`
    /// environment variable, defaults to `127.0.0.1`, and prints `listening on
    /// http://127.0.0.1:<port>` — all verified in its source. Its web client
    /// builds with relative asset URLs (`base: './'`, chosen upstream so a
    /// phone shell can run the same build below a folder), talks to the server
    /// over a single WebSocket at `/ws`, and routes nothing by pathname, so a
    /// subpath mount under `/i/<id>/` works with the standard brand shim — no
    /// router shim.
    CLAUDE_WEB("claude-web", Source.GITHUB, "https://github.com/ypyik0669/claude-web", "v", false);

    /// Where a brand's releases come from: an npm registry package, or a
    /// GitHub repository's release tags (source zip over HTTPS, no git).
    public enum Source {
        NPM,
        GITHUB
    }

    private final String id;
    private final Source source;
    private final String locator;
    private final String aux;
    private final boolean needsRouterShim;

    /// @param locator the npm package (NPM) or the repository URL (GITHUB)
    /// @param aux the executable inside `node_modules/.bin/` (NPM) or the
    ///            tag prefix (GITHUB, e.g. `"v"` for `v0.2.1`)
    Brand(String id, Source source, String locator, String aux, boolean needsRouterShim) {
        this.id = id;
        this.source = source;
        this.locator = locator;
        this.aux = aux;
        this.needsRouterShim = needsRouterShim;
    }

    /// The stable category id used in API paths and directory names.
    public String id() {
        return id;
    }

    /// Where this brand's releases come from.
    public Source source() {
        return source;
    }

    /// The upstream npm package the installer fetches; `null` for GitHub brands.
    public @Nullable String npmPackage() {
        return source == Source.NPM ? locator : null;
    }

    /// The executable inside the installed prefix's `node_modules/.bin/`;
    /// `null` for GitHub brands, whose launch is not a single binary.
    public @Nullable String binName() {
        return source == Source.NPM ? aux : null;
    }

    /// The upstream repository URL the installer fetches sources from; `null`
    /// for npm brands.
    public @Nullable String repoUrl() {
        return source == Source.GITHUB ? locator : null;
    }

    /// The `owner/repo` slug of the repository — the form the GitHub REST API
    /// (`/repos/<slug>/tags`) and codeload (`/<slug>/zip/…`) address it by.
    /// Empty for npm brands.
    public String githubSlug() {
        if (source != Source.GITHUB) {
            return "";
        }
        String slug = locator;
        if (slug.startsWith("https://github.com/")) {
            slug = slug.substring("https://github.com/".length());
        }
        while (slug.endsWith("/")) {
            slug = slug.substring(0, slug.length() - 1);
        }
        if (slug.endsWith(".git")) {
            slug = slug.substring(0, slug.length() - ".git".length());
        }
        return slug;
    }

    /// The tag prefix a version is released under (`v` for `v0.2.1`). Empty
    /// for npm brands.
    public String tagPrefix() {
        return source == Source.GITHUB ? aux : "";
    }

    /// The tag naming one version: the prefix plus the version (`v` + `0.2.1`
    /// → `v0.2.1`). The version alone for npm brands.
    public String releaseTag(String version) {
        return source == Source.GITHUB ? aux + version : version;
    }

    /// The release-relative path whose existence proves an install: the
    /// package's executable (NPM) or the built web app (GIT).
    public String installMarker() {
        return source == Source.NPM ? "node_modules/.bin/" + aux : "web/dist/index.html";
    }

    /// What the panel calls the upstream in user-facing messages: the npm
    /// package or the repository URL.
    public String sourceLabel() {
        return locator;
    }

    /// Whether the tool's web client routes by URL path, making a subpath
    /// mount (like `/i/<id>/`) render a broken shell unless the proxy hands
    /// the origin root back to it. Such pages are served through the mount
    /// with an extra script that keeps `location.pathname` at `/` while the
    /// runtime shim keeps every request inside the mount.
    public boolean needsRouterShim() {
        return needsRouterShim;
    }

    /// Resolves a category id back to its brand.
    ///
    /// @param id the id from a URL path or manifest
    /// @return the brand, or `null` when the id names none
    public static @Nullable Brand fromId(@Nullable String id) {
        if (id == null) {
            return null;
        }
        for (Brand brand : values()) {
            if (brand.id.equals(id)) {
                return brand;
            }
        }
        return null;
    }
}
