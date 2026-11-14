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
package org.jackhuang.hmcl.web.http;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jackhuang.hmcl.dsh.DshFreeBsdAddon;
import org.jackhuang.hmcl.dsh.DshTuiPlugins;
import org.jackhuang.hmcl.web.auth.AuthFilter;
import org.jackhuang.hmcl.web.auth.AuthService;
import org.jackhuang.hmcl.web.config.ServerConfig;
import org.jackhuang.hmcl.web.developer.DeveloperElevation;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.util.function.BooleanSupplier;

/// The harness-level REST surface, mapped at `/api/dsh/*`:
///
/// - `GET /api/dsh/tui-plugins` — the curated terminal-interface list: what each
///   package is, which version the panel pins, its licence and repository, and
///   whether the panel can start it.
///
/// Nothing here installs. A terminal plugin is an ordinary profile plugin, so the
/// install and remove routes are the ones every other plugin uses —
/// `POST /api/instances/{id}/plugins/install|remove` in [InstancesApiServlet] —
/// and the per-instance installed state is read from
/// `GET /api/instances/{id}/plugins`. A second install path would be a second
/// answer to "what is installed", which is exactly the kind of divergence this
/// endpoint exists to avoid.
@NotNullByDefault
public final class DshApiServlet extends HttpServlet {

    private final ServerConfig config;
    private final AuthService auth;
    private final DeveloperElevation elevation;
    private final BooleanSupplier tlsActive;

    public DshApiServlet(ServerConfig config, AuthService auth,
                         DeveloperElevation elevation, BooleanSupplier tlsActive) {
        this.config = config;
        this.auth = auth;
        this.elevation = elevation;
        this.tlsActive = tlsActive;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getPathInfo();
        if ("/tui-plugins".equals(path)) {
            tuiPlugins(response);
            return;
        }
        if ("/freebsd-addon".equals(path)) {
            // Status only. Building is a privileged action (it fetches and compiles
            // code), so POST below is the one behind developer elevation.
            Json.write(response, DshFreeBsdAddon.status());
            return;
        }
        Json.error(response, HttpServletResponse.SC_NOT_FOUND, "not found");
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getPathInfo();
        if ("/freebsd-addon/build".equals(path)) {
            if (!requireElevation(request, response)) {
                return;
            }
            DshFreeBsdAddon.start();
            Json.write(response, DshFreeBsdAddon.status());
            return;
        }
        Json.error(response, HttpServletResponse.SC_NOT_FOUND, "not found");
    }

    /// Fetching and compiling native code is an operator decision: HTTPS plus the
    /// developer password, the same gate the ffmpeg installer uses.
    private boolean requireElevation(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        if (!RequestSecurity.isSecure(request, tlsActive.getAsBoolean(), config)) {
            Json.error(response, HttpServletResponse.SC_FORBIDDEN,
                    "tls_required: this action is only accepted over HTTPS");
            return false;
        }
        if (!AuthFilter.elevationSatisfied(request, elevation, keyOf(request), sessionAlive(request))) {
            Json.error(response, HttpServletResponse.SC_FORBIDDEN,
                    "developer_elevation_required: elevate with the developer password first");
            return false;
        }
        return true;
    }

    private static String keyOf(HttpServletRequest request) {
        Object token = request.getAttribute(AuthFilter.SESSION_TOKEN_ATTRIBUTE);
        return DeveloperElevation.key(token instanceof String value ? value : null,
                request.getRemoteAddr());
    }

    private boolean sessionAlive(HttpServletRequest request) {
        Object token = request.getAttribute(AuthFilter.SESSION_TOKEN_ATTRIBUTE);
        return !(token instanceof String value) || value.isBlank() || auth.isAlive(value);
    }

    /// The curated list, as the interface needs it.
    ///
    /// The licence and the repository travel with every entry because the page has
    /// to show them next to the install button: what is being installed is somebody
    /// else's work, and the disclaimer is only honest if the row also says which
    /// work and under what licence. The **profile** travels for the same reason and
    /// a stronger one: it is where the package goes, and it is the part of installing
    /// a terminal plugin that decides whether the instance still starts.
    private void tuiPlugins(HttpServletResponse response) throws IOException {
        JsonArray plugins = new JsonArray();
        for (DshTuiPlugins.Entry entry : DshTuiPlugins.ENTRIES) {
            JsonObject json = new JsonObject();
            json.addProperty("id", entry.id());
            json.addProperty("packageName", entry.packageName());
            json.addProperty("spec", entry.spec());
            json.addProperty("name", entry.displayName());
            json.addProperty("version", entry.version());
            json.addProperty("license", entry.license());
            json.add("repository", entry.repository() == null
                    ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(entry.repository()));
            json.addProperty("description", entry.description());
            json.add("bin", entry.bin() == null
                    ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(entry.bin()));
            json.addProperty("profile", entry.profile());
            // "terminal" vs "capability": the page groups by this, and a capability
            // is never offered as "the terminal to open".
            json.addProperty("kind", entry.terminal() ? "terminal" : "capability");
            // First-party: the panel ships the tarball, so the install needs no registry.
            json.addProperty("bundled", entry.bundled());
            if (entry.requiresDsh() != null) {
                // The harness line this one needs; absent means it starts on any published
                // line. Shown next to the licence because it decides whether installing it
                // is even meaningful (see DshTuiPlugins#fitsHost).
                json.addProperty("requiresDsh", entry.requiresDsh());
            }
            plugins.add(json);
        }
        JsonObject body = new JsonObject();
        body.add("plugins", plugins);
        Json.writePreservingNulls(response, body);
    }
}