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
/// A token minted through `POST /api/developer/agent-auth` — the agent signs
/// for it with the agent password — is a bearer credential an AI agent carries
/// on its own; the panel never sees a browser session behind it. The design
/// keeps the blast radius small in the only place the agent cannot rewrite —
/// **here, in the root process**:
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
/// answers a URL carrying the instance's access token, `/api/exports/*` serves
/// build artifacts that may embed session content, and a brand instance's
/// `admin-password` is a generated credential — the first-run window hands it
/// out without any elevation, so only this layer can close it. None of them
/// belongs in a model's context.
///
/// The scope model also stops at the API boundary: `/i/*` (the reverse proxy)
/// and `/ws/*` (the event streams) are browser channels and are never reachable
/// with a grant, whatever class it names. An agent that needs an instance's
/// state asks the API for it; the panel then records who asked.
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
    /// operator's password; the reads below hand out another credential.
    ///
    /// The path must already be the **decoded** one the servlet routes on (see
    /// `AuthFilter#pathOf`) — otherwise `%61dmin-password` passes this check and
    /// the servlet decodes it back into the closed route.
    ///
    /// @param method the request method
    /// @param path   the decoded request path
    /// @return whether the route is closed to agents
    public static boolean forbidden(@Nullable String method, @Nullable String path) {
        if (path == null) {
            return true;
        }
        String route = stripTrailingSlash(path);
        if (route.startsWith("/api/developer/") || route.equals("/api/developer")) {
            return true;
        }
        if (route.startsWith("/api/auth/")) {
            return true;
        }
        if (route.startsWith("/api/exports")) {
            return true;
        }
        // A generated admin password is a credential, not a setting: the panel
        // hands it to the operator's browser, never to a model's context. It is
        // closed here rather than in the servlet because the first-run window
        // (`GET .../admin-password` on a fresh instance) answers **without any
        // elevation at all** — a scope check in the servlet would never run.
        if (route.endsWith("/admin-password")) {
            return true;
        }
        return ("GET".equals(method) || "HEAD".equals(method)) && route.endsWith("/open");
    }

    /// Drops one trailing slash so a route matches whether or not the caller
    /// wrote it, while leaving "/" alone.
    private static String stripTrailingSlash(String path) {
        return path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
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
        // Only `/api/*` is an API. The reverse proxy (`/i/*`), the event
        // streams (`/ws/*`) and the static shell are browser channels: an agent
        // reaches the panel through the API or not at all. The `GET -> READ`
        // shortcut below must not swallow them — before this guard a
        // `GET /i/<id>/...` counted as a read, so a `read` grant could have
        // pulled a proxied instance page into a model's context.
        if (!path.startsWith("/api/") && !path.equals("/api")) {
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
        String route = stripTrailingSlash(path);
        String self = "/api/instances/" + instanceId;
        if (route.equals(self)) {
            return "DELETE".equals(method);
        }
        if (route.equals(self + "/stop")) {
            return "POST".equals(method);
        }
        return route.equals(self + "/terminal");
    }
}
