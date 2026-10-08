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

import com.google.gson.annotations.SerializedName;
import org.jetbrains.annotations.NotNullByDefault;

/// One brand-category instance: a [Brand] tool pinned to one installed version,
/// plus the panel-side record of how to launch it.
///
/// The record is deliberately thin, mirroring
/// [org.jackhuang.hmcl.web.zcode.ZcodeInstance]: the tool's own state
/// (configuration, sessions, credentials) lives in the instance's `home/`
/// directory — the child process's `HOME` — never in this manifest. The brand
/// id is stored denormalized so the manifest on disk is self-describing.
@NotNullByDefault
public record BrandInstance(
        @SerializedName("id") String id,
        @SerializedName("brand") String brand,
        @SerializedName("name") String name,
        @SerializedName("version") String version,
        @SerializedName("lastPort") int lastPort,
        @SerializedName("createdAt") long createdAt) {

    public BrandInstance withName(String newName) {
        return new BrandInstance(id, brand, newName, version, lastPort, createdAt);
    }

    public BrandInstance withVersion(String newVersion) {
        return new BrandInstance(id, brand, name, newVersion, lastPort, createdAt);
    }

    public BrandInstance withLastPort(int newLastPort) {
        return new BrandInstance(id, brand, name, version, newLastPort, createdAt);
    }
}
