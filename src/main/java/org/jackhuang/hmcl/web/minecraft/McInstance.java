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
package org.jackhuang.hmcl.web.minecraft;

import com.google.gson.annotations.SerializedName;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// One Minecraft server the panel manages: its identity, its two ports and the
/// world it owns.
///
/// Deliberately thin — the same shape as [org.jackhuang.hmcl.web.brand.BrandInstance].
/// Everything else (server.jar, `server.properties`, the world) lives beside the
/// manifest and is discovered from disk, so a hand-edited instance is not
/// rejected just because the panel did not write it.
@NotNullByDefault
public record McInstance(
        @SerializedName("id") String id,
        @SerializedName("name") String name,
        @SerializedName("version") String version,
        @SerializedName("port") int port,
        @SerializedName("rconPort") int rconPort,
        @SerializedName("memoryMb") int memoryMb,
        @SerializedName("levelName") String levelName,
        @SerializedName("seed") @Nullable String seed,
        @SerializedName("createdAt") long createdAt) {

    /// The smallest heap worth starting a server with.
    public static final int MIN_MEMORY_MB = 256;
    /// A cap the panel refuses to exceed even when asked: one server is not the host.
    public static final int MAX_MEMORY_MB = 16384;

    public McInstance {
        if (id == null || id.isBlank() || id.contains("/") || id.contains("\\")
                || id.equals(".") || id.equals("..")) {
            throw new IllegalArgumentException("invalid instance id");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("an instance needs a name");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("an instance needs a version");
        }
        McPorts.validate(port, rconPort);
        if (memoryMb < MIN_MEMORY_MB || memoryMb > MAX_MEMORY_MB) {
            throw new IllegalArgumentException("memory must be between " + MIN_MEMORY_MB
                    + " and " + MAX_MEMORY_MB + " MB");
        }
        if (levelName == null || levelName.isBlank() || levelName.contains("/") || levelName.contains("\\")
                || levelName.equals(".") || levelName.equals("..")) {
            throw new IllegalArgumentException("invalid level name");
        }
        if (seed != null && seed.isBlank()) {
            seed = null;
        }
    }

    /// The same instance with a new display name.
    ///
    /// @param newName the name
    /// @return the updated instance
    public McInstance withName(String newName) {
        return new McInstance(id, newName, version, port, rconPort, memoryMb, levelName, seed, createdAt);
    }

    /// The same instance with a new version.
    ///
    /// @param newVersion the version
    /// @return the updated instance
    public McInstance withVersion(String newVersion) {
        return new McInstance(id, name, newVersion, port, rconPort, memoryMb, levelName, seed, createdAt);
    }

    /// The same instance with new ports.
    ///
    /// @param newPort     the game port
    /// @param newRconPort the RCON port
    /// @return the updated instance
    public McInstance withPorts(int newPort, int newRconPort) {
        return new McInstance(id, name, version, newPort, newRconPort, memoryMb, levelName, seed, createdAt);
    }

    /// The same instance with a new heap size.
    ///
    /// @param newMemoryMb the heap size in MB
    /// @return the updated instance
    public McInstance withMemory(int newMemoryMb) {
        return new McInstance(id, name, version, port, rconPort, newMemoryMb, levelName, seed, createdAt);
    }
}