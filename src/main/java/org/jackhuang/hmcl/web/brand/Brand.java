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
/// Each value is a static description of one upstream npm package — what the
/// panel installs, what binary it launches, and how that binary announces its
/// port. Behavioural differences (launch flags, port strategy, readiness line)
/// live in [BrandRuntime] and switch on this enum; everything here is data.
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
    KIMI("kimi", "@moonshot-ai/kimi-code", "kimi", false),

    /// OpenCode: `opencode web` serves its own web UI; `--port 0` is NOT
    /// honoured (it falls back to 4096), so the panel pre-allocates a port.
    /// Its client **routes by `location.pathname`** (verified in its bundle
    /// and by serving it under any subpath: the shell renders, the content
    /// stays blank) — no subpath mount can ever work, so instances get an
    /// origin of their own: a dedicated published port where the app owns `/`.
    OPENCODE("opencode", "opencode-ai", "opencode", true);

    private final String id;
    private final String npmPackage;
    private final String binName;
    private final boolean needsOwnOrigin;

    Brand(String id, String npmPackage, String binName, boolean needsOwnOrigin) {
        this.id = id;
        this.npmPackage = npmPackage;
        this.binName = binName;
        this.needsOwnOrigin = needsOwnOrigin;
    }

    /// The stable category id used in API paths and directory names.
    public String id() {
        return id;
    }

    /// The upstream npm package the installer fetches.
    public String npmPackage() {
        return npmPackage;
    }

    /// The executable inside the installed prefix's `node_modules/.bin/`.
    public String binName() {
        return binName;
    }

    /// Whether the tool's web client routes by URL path, making any subpath
    /// mount (like `/i/<id>/`) render a broken shell. Such brands are served
    /// on an origin of their own — a dedicated published port where the app
    /// owns `/` — instead of through the mount.
    public boolean needsOwnOrigin() {
        return needsOwnOrigin;
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
