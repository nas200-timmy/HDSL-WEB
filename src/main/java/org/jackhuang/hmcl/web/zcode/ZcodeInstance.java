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
package org.jackhuang.hmcl.web.zcode;

import com.google.gson.annotations.SerializedName;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// One **experimental** ZCode instance: a self-built ZCode distribution
/// (zai-org/ZCode, Apache-2.0) launched by this panel in its `--web` mode,
/// plus the panel-side record of how to launch it.
///
/// The record is deliberately thin, mirroring [org.jackhuang.hmcl.dsh.DshInstance]:
/// it names the workspace the instance works on, the provider credentials the
/// panel injects (when given), the access token ZCode itself requires, and the
/// last port the process announced. Everything else — ZCode's own config,
/// sessions and credentials — lives under the per-instance `data/` directory
/// (`ZCODE_DATA_BASE_DIR`), which is what keeps two ZCode instances naturally
/// isolated from each other and from every dsh instance.
///
/// Instances are immutable; [ZcodeInstanceManager] replaces them on edit.
///
/// **Experimental.** The whole category is an implementation-level proof of
/// concept: nothing here is covered by the compatibility promises the dsh
/// category has, and the API key is stored in clear text in `instance.json`
/// on purpose (there is no vault in this panel) — the interface says so next
/// to the field.
@NotNullByDefault
public record ZcodeInstance(
        @SerializedName("id") String id,
        @SerializedName("name") String name,
        @SerializedName("workspacePath") String workspacePath,
        @SerializedName("baseUrl") @Nullable String baseUrl,
        /// Plain text, stored in `instance.json` beside the instance. This is a
        /// deliberate experiment-grade trade-off, advertised as such in the UI.
        @SerializedName("apiKey") @Nullable String apiKey,
        @SerializedName("token") String token,
        /// The port the process last announced on stdout; `0` before the first
        /// successful launch chose one.
        @SerializedName("lastPort") int lastPort,
        @SerializedName("createdAt") long createdAt) {

    /// The default provider endpoint the creation dialog pre-fills.
    public static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";

    /// Returns the provider base URL, falling back to the documented default.
    ///
    /// @return the base URL, never `null`
    public String baseUrlOrDefault() {
        return baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl;
    }

    /// Returns a copy with a different display name.
    ///
    /// @param newName the name
    /// @return the copy
    public ZcodeInstance withName(String newName) {
        return new ZcodeInstance(id, newName, workspacePath, baseUrl, apiKey, token, lastPort, createdAt);
    }

    /// Returns a copy with different provider credentials.
    ///
    /// @param newBaseUrl the base URL, `null` to clear
    /// @param newApiKey  the API key, `null` to clear
    /// @return the copy
    public ZcodeInstance withCredentials(@Nullable String newBaseUrl, @Nullable String newApiKey) {
        return new ZcodeInstance(id, name, workspacePath, newBaseUrl, newApiKey, token, lastPort, createdAt);
    }

    /// Returns a copy remembering the port the process announced.
    ///
    /// @param newLastPort the port
    /// @return the copy
    public ZcodeInstance withLastPort(int newLastPort) {
        return new ZcodeInstance(id, name, workspacePath, baseUrl, apiKey, token, newLastPort, createdAt);
    }
}
