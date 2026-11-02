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
package org.jackhuang.hmcl.web.developer;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/// The capability classes an **agent** token may hold, and the two rules that
/// keep it from doing something no agent should be able to do.
///
/// A token minted through `POST /api/developer/agent-token` is a bearer
/// credential an AI agent carries on its own; the panel never sees a browser
/// session behind it. The design keeps the blast radius small in the only place
/// the agent cannot rewrite — **here, in the root process**:
///
/// 1. **Deny by default.** A request that matches no class below is refused,
///    so a route added later is closed to agents until someone says otherwise.
/// 2. **Self-fatal operations are refused.** Stopping the instance whose
///    process is running the call, or opening its terminal (which stops the web
///    runtime first), would kill the caller mid-request: the tool call could
///    never return and the transcript would end in silence. The panel answers
///    `409 self_operation_refused` instead, and the agent is expected to pick
///    another route.
///
/// Credential-leaking reads are refused outright: `GET /api/instances/{id}/open`
/// answers a URL carrying the instance's access token, and `/api/exports/*`
/// serves build artifacts that may embed session content. Neither belongs in a
/// model's context.
@NotNullByDefault
public final class AgentScopes {

    /// Every `GET`/`HEAD` the panel serves, minus the leaks below.
    public static final String READ = "read";
    /// `/api/mc/*`: the only surface where a stray process would corrupt state
    /// the panel owns (port, world, console).
    public static final String MC = "mc";
    /// Creating, deleting, launching and stopping **other** instances.
    public static final String INSTANCES = "instances";
    /// Accounts, models, TLS, network trust, node runtimes, wallpapers.
    public static final String CONFIG = "config";
    /// Installing or removing plugins, packs and skills.
    public static final String PLUGINS = "plugins";

    /// Every class a grant may name, in the order the panel presents them.
    public static final Set<String> KNOWN = Set.of(READ, MC, INSTANCES, CONFIG, PLUGINS);

    /// What a grant holds when the operator ticks nothing: reading the panel,
    /// and nothing that changes it.
    public static final Set<String> DEFAULT = Set.of(READ);

    private AgentScopes() {
    }

    /// Whether `scope` names a class this build knows.
    ///
    /// @param scope the claimed class
    /// @return whether it is known
    public static boolean known(@Nullable String scope) {
        return scope != null && KNOWN.contains(scope);
    }

    /// Routes an agent token may never reach, whatever it was granted.
    ///
    /// The developer routes mint and revoke credentials, so an agent holding
    /// one must not reach them; the auth routes would let it change the
    /// operator's password; the two reads below hand out another credential.
    ///
    /// @param method the request method
    /// @param path   the request path
    /// @return whether the route is closed to agents
    public static boolean forbidden(@Nullable String method, @Nullable String path) {
        if (path == null) {
            return true;
        }
        if (path.startsWith("/api/developer/") || path.equals("/api/developer")) {
            return true;
        }
        if (path.startsWith("/api/auth/")) {
            return true;
        }
        if (path.startsWith("/api/exports")) {
            return true;
        }
        return ("GET".equals(method) || "HEAD".equals(method)) && path.endsWith("/open");
    }

    /// The class a request needs, or `null` when no class covers it — which the
    /// caller must read as a refusal, not as "no class needed".
    ///
    /// @param method the request method
    /// @param path   the request path
    /// @return the required class, or `null` for "not covered"
    public static @Nullable String required(@Nullable String method, @Nullable String path) {
        if (path == null || method == null) {
            return null;
        }
        if ("GET".equals(method) || "HEAD".equals(method)) {
            return READ;
        }
        if (path.startsWith("/api/mc/") || path.equals("/api/mc")) {
            return MC;
        }
        if (path.startsWith("/api/instances")) {
            return path.contains("/plugins") ? PLUGINS : INSTANCES;
        }
        if (path.startsWith("/api/zcode/") || path.startsWith("/api/brands/")) {
            return INSTANCES;
        }
        if (path.startsWith("/api/plugins/") || path.startsWith("/api/packs/")) {
            return PLUGINS;
        }
        if (path.startsWith("/api/accounts") || path.startsWith("/api/models")
                || path.startsWith("/api/settings") || path.startsWith("/api/network")
                || path.startsWith("/api/node") || path.startsWith("/api/vendors")
                || path.startsWith("/api/wallpapers")) {
            return CONFIG;
        }
        if (path.startsWith("/api/tasks")) {
            return INSTANCES;
        }
        return null;
    }

    /// Whether this request would end the process making it.
    ///
    /// Only the instance that owns the token counts: stopping *another*
    /// instance is ordinary work, and the agent may do it when its grant holds
    /// [#INSTANCES]. Opening a terminal is included because the launcher stops
    /// the web runtime first — the same death by a quieter route.
    ///
    /// @param method     the request method
    /// @param path       the request path
    /// @param instanceId the instance the grant is bound to, or `null`
    /// @return whether the panel must refuse the request
    public static boolean selfFatal(@Nullable String method, @Nullable String path, @Nullable String instanceId) {
        if (path == null || instanceId == null || instanceId.isBlank()) {
            return false;
        }
        String self = "/api/instances/" + instanceId;
        if (path.equals(self)) {
            return "DELETE".equals(method);
        }
        if (path.equals(self + "/stop")) {
            return "POST".equals(method);
        }
        return path.equals(self + "/terminal");
    }
}
