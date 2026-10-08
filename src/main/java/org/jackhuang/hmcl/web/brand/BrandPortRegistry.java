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

import org.jackhuang.hmcl.dsh.DshPorts;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// The published ports that brand instances with a path-sensitive web client
/// ([Brand.needsOwnOrigin]) are served on.
///
/// Such an instance cannot live under an `/i/<id>/` mount — its client routes
/// by `location.pathname` and renders a blank shell on any subpath (measured
/// on OpenCode, whose bundle reads `window.location.pathname` directly) — so
/// the panel publishes one extra edge port per instance, where the app owns
/// `/` and every request is proxied to the instance's loopback port verbatim,
/// no rewriting at all.
///
/// The port is chosen from a small fixed range **outside** the dsh instance
/// pool (3081–4081, container-internal) and persisted in the instance
/// manifest: an origin is part of what an instance is — the browser keys its
/// storage by origin, so a brand instance that moved ports would lose nothing
/// visible but gain nothing either. Persisted ports are re-registered at
/// panel startup so a restart finds the same mapping.
@NotNullByDefault
public final class BrandPortRegistry {

    /// The first port handed to brand instances that need their own origin.
    public static final int RANGE_START = 3091;

    /// The last port handed to brand instances that need their own origin.
    /// The whole range must be published by the compose file for the host to
    /// reach it; inside the container the panel binds it either way.
    public static final int RANGE_END = 3100;

    /// Which instance owns which published port.
    private static final Map<Integer, String> OWNERS = new ConcurrentHashMap<>();

    private BrandPortRegistry() {
    }

    /// Finds the instance published on the given port.
    ///
    /// @param port the port the request arrived on
    /// @return the instance id, or `null` when the port publishes nothing
    public static @Nullable String ownerOf(int port) {
        return OWNERS.get(port);
    }

    /// Whether the port belongs to this registry's range at all — the edge
    /// filter declines every port outside it without touching the registry.
    ///
    /// @param port the local port
    /// @return whether the port is in the published brand range
    public static boolean inRange(int port) {
        return port >= RANGE_START && port <= RANGE_END;
    }

    /// Registers ownership. Panel startup replays every persisted port through
    /// here; a launch claims its port the same way.
    ///
    /// @param port the published port
    /// @param id   the owning instance
    public static void register(int port, String id) {
        OWNERS.put(port, id);
    }

    /// Releases a port (instance deleted).
    ///
    /// @param port the published port
    public static void release(int port) {
        OWNERS.remove(port);
    }

    /// Empties the registry. Tests only: the panel never unmaps ports it has
    /// bound, but a static map shared across test classes would otherwise make
    /// every allocation test order-dependent.
    static void clear() {
        OWNERS.clear();
    }

    /// Allocates a free port in the range, avoiding the ones other instances
    /// hold and the ones the OS reports bound.
    ///
    /// Handed out **from the start of the range upward**, not at random: the
    /// port is a published one the operator forwards on their router, and
    /// "the first own-origin instance is always 3091" is what makes forwarding
    /// a single port possible at all.
    ///
    /// @return the port
    /// @throws BrandException when the range is exhausted
    public static int allocate() throws BrandException {
        for (int port = RANGE_START; port <= RANGE_END; port++) {
            if (!OWNERS.containsKey(port) && DshPorts.isFree(port)) {
                return port;
            }
        }
        throw new BrandException("No free brand port between " + RANGE_START + " and " + RANGE_END
                + "; publish a wider range or stop some own-origin instances");
    }

    /// The ports currently registered, for wholesale rebinding at startup.
    ///
    /// @return the registered ports
    public static java.util.Set<Integer> ports() {
        return java.util.Set.copyOf(OWNERS.keySet());
    }

    /// Replays the persisted public ports of every brand instance under the
    /// data directory into the registry — called once at panel startup.
    ///
    /// @param brandsRoot `<dataDir>/brands`
    public static void replay(Path brandsRoot) {
        for (Brand brand : Brand.values()) {
            Path instances = brandsRoot.resolve(brand.id()).resolve("instances");
            if (!Files.isDirectory(instances)) {
                continue;
            }
            try (var entries = Files.list(instances)) {
                for (Path directory : entries.filter(Files::isDirectory).toList()) {
                    BrandInstance instance = read(directory);
                    if (instance != null && instance.publicPort() > 0) {
                        register(instance.publicPort(), instance.id());
                    }
                }
            } catch (IOException e) {
                LOG.warning("Failed to replay brand ports from " + instances, e);
            }
        }
    }

    private static @Nullable BrandInstance read(Path directory) {
        Path manifest = directory.resolve(BrandInstanceManager.MANIFEST_NAME);
        if (!Files.isRegularFile(manifest)) {
            return null;
        }
        try {
            return org.jackhuang.hmcl.util.gson.JsonUtils.fromJsonFile(manifest, BrandInstance.class);
        } catch (Exception e) {
            return null;
        }
    }
}
