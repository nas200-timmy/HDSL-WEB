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
package org.jackhuang.hmcl.web.terminal;

import com.google.gson.JsonObject;
import jakarta.servlet.http.HttpServletRequest;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jackhuang.hmcl.web.audit.AuditLog;
import org.jackhuang.hmcl.web.auth.AuthFilter;
import org.jackhuang.hmcl.web.auth.AuthService;
import org.jackhuang.hmcl.web.config.ServerConfig;
import org.jackhuang.hmcl.web.developer.DeveloperAuth;
import org.jackhuang.hmcl.web.developer.DeveloperElevation;
import org.jackhuang.hmcl.web.http.Json;
import org.jackhuang.hmcl.web.http.RequestSecurity;
import org.jackhuang.hmcl.web.terminal.pty.PtySize;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;

/// The terminal routes' shared half: the gates, the takeover of the web
/// runtime, the spawn and the one-use ticket.
///
/// dsh, the brand categories and ZCode each own their instance records and
/// their adapter, but the security decisions and the wire contract must not
/// drift apart between them. A servlet supplies the instance-specific part —
/// what command to start and what to stop first — and this class answers the
/// same `{mode, protocol, instance, brand, ticket, ws}` body for all of them.
@NotNullByDefault
public final class InstanceTerminal {

    /// Builds the argv for one instance. `resume` is the query parameter, or
    /// `null`.
    @FunctionalInterface
    public interface TargetFactory {
        TerminalTarget create(@Nullable String resume) throws IOException;
    }

    /// A refusal, so servlets can turn it into `{"error": code}` without
    /// repeating the checks. The message is the wire text and carries the
    /// machine-readable prefix the clients branch on.
    public static final class Refused extends RuntimeException {
        private final int status;

        public Refused(int status, String code) {
            super(code);
            this.status = status;
        }

        /// The HTTP status to answer with.
        public int status() {
            return status;
        }

        /// The `code: human text` message to answer with.
        public String code() {
            return getMessage();
        }
    }

    private final ServerConfig config;
    private final TerminalManager terminals;
    private final TerminalTickets tickets;
    private final AuthService auth;
    private final DeveloperAuth developerAuth;
    private final DeveloperElevation developerElevation;
    private final BooleanSupplier tlsActive;

    public InstanceTerminal(ServerConfig config, TerminalManager terminals, TerminalTickets tickets,
                            AuthService auth, DeveloperAuth developerAuth,
                            DeveloperElevation developerElevation, BooleanSupplier tlsActive) {
        this.config = config;
        this.terminals = terminals;
        this.tickets = tickets;
        this.auth = auth;
        this.developerAuth = developerAuth;
        this.developerElevation = developerElevation;
        this.tlsActive = tlsActive;
    }

    /// The gates every terminal request passes before anything is started:
    /// the web shell switch, HTTPS, a developer password and its elevation.
    ///
    /// @throws Refused with the status and code the caller should answer
    public void authorize(HttpServletRequest request) {
        if (!config.webShell.enabled) {
            throw new Refused(403, "web_shell_disabled: the web shell is disabled in developer settings");
        }
        if (!RequestSecurity.isSecure(request, tlsActive.getAsBoolean(), config)) {
            throw new Refused(403,
                    "insecure_transport: the web shell requires HTTPS; enable it in the HTTPS settings");
        }
        if (!developerAuth.isSet()) {
            throw new Refused(409, "developer_password_not_set: set it before opening a terminal");
        }
        String token = sessionToken(request);
        boolean sessionAlive = token == null || token.isBlank() || auth.isAlive(token);
        String key = DeveloperElevation.key(token, request.getRemoteAddr());
        if (!developerElevation.require(key, sessionAlive)) {
            throw new Refused(403, "developer_elevation_required: elevate with the developer password first");
        }
    }

    /// Stops the instance's terminal, if one is running.
    ///
    /// @return whether there was one
    public boolean close(String brand, String instanceId) {
        return terminals.terminate(brand, instanceId);
    }

    /// Takes the instance over and answers the terminal body.
    ///
    /// `stopWeb` brings the instance's web mode down before the TUI starts:
    /// both share the agent's state, and on POSIX both would write the same
    /// session files. It must return once the process is really gone — an
    /// asynchronous stop followed by a spawn races two processes over one home.
    ///
    /// @throws Refused when the PTY or the agent cannot be started, or when a
    ///                 Windows panel is asked without its literal confirmation
    public JsonObject open(HttpServletRequest request, String instanceId,
                           @Nullable Runnable stopWeb, TargetFactory factory) throws IOException {
        if (stopWeb != null) {
            stopWeb.run();
        }
        TerminalTarget target;
        try {
            target = factory.create(request.getParameter("resume"));
        } catch (IOException | RuntimeException e) {
            AuditLog.log(AuditLog.Channel.TERMINAL, "terminal.open", instanceId, "failure",
                    actorOf(request), request.getRemoteAddr());
            throw new Refused(409, "terminal_failed: " + e.getMessage());
        }
        if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
            return openDesktop(request, instanceId, target);
        }
        // A refresh comes back to the same agent: an existing running session
        // gets a fresh ticket instead of a 409. The process outlives the tab.
        try {
            TerminalSession running = terminals.find(target.brand(), instanceId)
                    .filter(candidate -> candidate.state() == TerminalSession.State.RUNNING)
                    .orElse(null);
            if (running == null) {
                terminals.open(target, PtySize.DEFAULT);
            }
        } catch (TerminalBusyException e) {
            throw new Refused(409, "terminal_already_open: " + e.getMessage());
        } catch (IOException | RuntimeException e) {
            AuditLog.log(AuditLog.Channel.TERMINAL, "terminal.open", instanceId, "failure",
                    actorOf(request), request.getRemoteAddr());
            throw new Refused(409, "terminal_failed: " + e.getMessage());
        }
        String ticket = tickets.issue(instanceId, target.brand(), sessionToken(request));
        JsonObject body = new JsonObject();
        body.addProperty("mode", "pty");
        body.addProperty("protocol", TerminalWebSocket.PROTOCOL);
        body.addProperty("instance", instanceId);
        body.addProperty("brand", target.brand());
        body.addProperty("ticket", ticket);
        body.addProperty("ws", "/ws/terminal/" + ticket);
        return body;
    }

    /// The Windows branch: the console belongs to the desktop, so there is no
    /// PTY and no ticket, only a window in the instance's workspace.
    private JsonObject openDesktop(HttpServletRequest request, String instanceId, TerminalTarget target)
            throws IOException {
        // A remote caller who only stole a cookie should not pop a console
        // with a single POST, so the request has to spell the intent out. The
        // real gates are the session and the developer elevation above.
        if (!"open-desktop-terminal".equals(readBodyField(request, "confirm"))) {
            throw new Refused(400, "confirmation_required: send {\"confirm\":\"open-desktop-terminal\"}");
        }
        try {
            DesktopTerminal.open(target);
        } catch (IOException e) {
            throw new Refused(409, "terminal_failed: " + e.getMessage());
        }
        AuditLog.log(AuditLog.Channel.TERMINAL, "terminal.desktop", instanceId, "success",
                actorOf(request), request.getRemoteAddr());
        JsonObject body = new JsonObject();
        body.addProperty("mode", "desktop");
        body.addProperty("instance", instanceId);
        body.addProperty("brand", target.brand());
        body.addProperty("hint", DesktopTerminal.REMOTE_HINT);
        return body;
    }

    /// Answers a refusal in the shape the clients parse.
    public static void write(HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response,
                             Refused refused) throws IOException {
        Json.error(response, refused.status(), refused.code());
    }

    private static @Nullable String sessionToken(HttpServletRequest request) {
        return (String) request.getAttribute(AuthFilter.SESSION_TOKEN_ATTRIBUTE);
    }

    /// Who asked, for the audit trail. `null` when the panel runs with
    /// authentication disabled (loopback-only).
    private static @Nullable String actorOf(HttpServletRequest request) {
        Object session = request.getAttribute(AuthFilter.SESSION_ATTRIBUTE);
        return session instanceof AuthService.Session value ? value.username() : null;
    }

    /// Reads one string field out of the request body, or `null`.
    static @Nullable String readBodyField(HttpServletRequest request, String field) throws IOException {
        try (InputStreamReader reader =
                     new InputStreamReader(request.getInputStream(), StandardCharsets.UTF_8)) {
            JsonObject body = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
            return body.has(field) && !body.get(field).isJsonNull()
                    ? body.get(field).getAsString()
                    : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}