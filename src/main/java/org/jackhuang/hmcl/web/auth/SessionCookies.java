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
package org.jackhuang.hmcl.web.auth;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/// The session cookie's name for **this panel**, not a shared constant.
///
/// Cookies are scoped by host and path, never by port: two panels on
/// `host:3080` and `host:3081` overwrite the same `hdsl_session` cookie, so
/// logging into one signs the other out (or makes it impossible to log in).
/// The name therefore carries the panel's stable instance id and, when known,
/// the bound port. The [AuthFilter] uses its own name for set/read/clear;
/// the few places that parse a raw `Cookie` header use [name].
public final class SessionCookies {

    /// The historical name. Still used by unit tests that build a filter
    /// without an instance, and by callers that need to clear a cookie left by
    /// an older release.
    public static final String LEGACY = AuthFilter.SESSION_COOKIE;

    private static volatile String bound = LEGACY;

    private SessionCookies() {
    }

    /// The name this process bound at startup. One panel per JVM, so the
    /// process-wide value is the same as the filter's own name.
    public static String name() {
        return bound;
    }

    /// Binds the process-wide name; [org.jackhuang.hmcl.web.server.HdslServer]
    /// calls this once after constructing the filter.
    public static void bind(@Nullable String name) {
        bound = name == null || name.isBlank() ? LEGACY : name;
    }

    /// `hdsl_session_<id12>_<port>`; an empty id and a zero/unknown port fall
    /// back to the legacy name (unit tests, and installations that never ran
    /// through [org.jackhuang.hmcl.web.config.PanelIdentity]).
    ///
    /// @param instanceId the panel's stable id, or null
    /// @param port       the bound port, or `0` when it is unknown
    public static String forInstance(@Nullable String instanceId, int port) {
        String id = instanceId == null ? ""
                : instanceId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (id.length() > 12) {
            id = id.substring(0, 12);
        }
        StringBuilder name = new StringBuilder(LEGACY);
        if (!id.isEmpty()) {
            name.append('_').append(id);
        }
        if (port > 0) {
            name.append('_').append(port);
        }
        return name.toString();
    }
}