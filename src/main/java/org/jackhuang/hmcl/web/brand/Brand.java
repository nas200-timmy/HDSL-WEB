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
    KIMI("kimi", "@moonshot-ai/kimi-code", "kimi"),

    /// OpenCode: `opencode web` serves its own web UI; `--port 0` is NOT
    /// honoured (it falls back to 4096), so the panel pre-allocates a port.
    OPENCODE("opencode", "opencode-ai", "opencode");

    private final String id;
    private final String npmPackage;
    private final String binName;

    Brand(String id, String npmPackage, String binName) {
        this.id = id;
        this.npmPackage = npmPackage;
        this.binName = binName;
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
