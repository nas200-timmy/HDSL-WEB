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
/// 1. **Deny by default.** A request that matches no rule below is refused, so a
///    route added later is closed to agents until someone says otherwise.
/// 2. **Read-only.** A grant carries one class, [#READ], and it reaches only the
///    instance lists and their details — no create, no stop, no delete, no
///    terminal, no plugin or setting. Nothing an agent can call changes state,
///    which is why it can also never kill the instance it is running in: the
///    action that would do that is not on the menu.
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
/// with a grant.
@NotNullByDefault
public final class AgentScopes {

    /// Reading the instance lists (dsh, brands, ZCode) and one entry in them.
    /// It is the only class a grant may hold.
    public static final String READ = "read";

    /// Every class a grant may name. One entry on purpose: an agent gets a
    /// window into the instance lists, not a hand on the panel.
    public static final Set<String> KNOWN = Set.of(READ);

    /// What a grant holds when the caller names nothing.
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
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            return null;
        }
        return isInstanceListRead(stripTrailingSlash(path)) ? READ : null;
    }

    /// Whether the path is one of the instance lists, or one entry in one of
    /// them. Nothing deeper: a log, a plugin list or an export is not "the
    /// list", and none of them is reachable with a grant.
    ///
    /// dsh:   `/api/instances`, `/api/instances/{id}`
    /// brand: `/api/brands/{brand}/instances`, `.../instances/{id}`
    /// ZCode: `/api/zcode/instances`, `/api/zcode/instances/{id}`
    ///
    /// @param route the path without a trailing slash
    /// @return whether an agent may read it
    private static boolean isInstanceListRead(String route) {
        String[] parts = route.split("/", -1);
        if (parts.length < 3 || !"api".equals(parts[1])) {
            return false;
        }
        if ("instances".equals(parts[2])) {
            return parts.length == 3 || parts.length == 4;
        }
        if ("zcode".equals(parts[2])) {
            return parts.length >= 4 && "instances".equals(parts[3])
                    && (parts.length == 4 || parts.length == 5);
        }
        if ("brands".equals(parts[2])) {
            return parts.length >= 5 && "instances".equals(parts[4])
                    && (parts.length == 5 || parts.length == 6);
        }
        return false;
    }

}
