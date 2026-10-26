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
import com.google.gson.JsonParser;
import jakarta.websocket.CloseReason;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpoint;
import jakarta.websocket.server.ServerEndpointConfig;
import org.jackhuang.hmcl.web.auth.AuthFilter;
import org.jackhuang.hmcl.web.auth.AuthService;
import org.jackhuang.hmcl.web.terminal.pty.PtySize;
import org.jackhuang.hmcl.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/// The terminal's WebSocket at `/ws/terminal/{ticket}`.
///
/// Binary frames carry terminal bytes both ways. Text frames carry control:
/// `{"type":"resize","cols":120,"rows":40}` and, from the server,
/// `{"type":"ready","instance":"…"}` /
/// `{"type":"exit","code":0}` — plus `"failedWithoutOutput":true` when the child
/// failed without writing anything, which is the only trace that failure
/// leaves (see [TerminalSession#silentFailure]).
///
/// The socket is a **viewer**, not the session: closing it detaches; the agent
/// keeps running and a new ticket reattaches with the replay buffer. When the
/// agent exits, the server sends `exit`, closes the socket, and the frontend
/// clears its screen.
@NotNullByDefault
@ServerEndpoint(value = "/ws/terminal/{ticket}", configurator = TerminalWebSocket.SessionConfigurator.class)
public final class TerminalWebSocket implements TerminalSink, TerminalSockets.Socket {

    /// Version of the control-frame contract (docs/terminal-protocol.md).
    /// Sent in the `ready` frame; a client that does not know the number warns
    /// and tries anyway, because the byte channel itself is unchanged.
    public static final int PROTOCOL = 1;

    private static volatile TerminalManager manager;
    private static volatile TerminalTickets tickets;
    private static volatile AuthService authService;
    private static volatile boolean authDisabled = true;
    /// Live sockets by the session that opened them: logout severs exactly
    /// the connections the logged-out session was using.
    private static final TerminalSockets SOCKETS = new TerminalSockets();

    /// Installed once by the composition root, before the endpoint accepts
    /// handshakes.
    public static void configure(TerminalManager manager, TerminalTickets tickets,
                                 AuthService authService, boolean authDisabled) {
        TerminalWebSocket.manager = manager;
        TerminalWebSocket.tickets = tickets;
        TerminalWebSocket.authService = authService;
        TerminalWebSocket.authDisabled = authDisabled;
    }

    /// Closes every terminal socket `sessionToken` opened. Called from the
    /// logout route; the close is a detach, so the agents keep running.
    public static int closeForSession(String sessionToken) {
        return SOCKETS.closeForSession(sessionToken);
    }

    private volatile @Nullable Session ws;
    private volatile @Nullable TerminalSession terminal;
    private volatile @Nullable String sessionToken;

    @OnOpen
    public void onOpen(Session ws) {
        this.ws = ws;
        // No idle timeout. A terminal is silent whenever the agent is thinking
        // or waiting for input, and docs/web-shell.md §3 is explicit that a
        // quiet terminal must never be killed for it. Jetty's 30-second default
        // closed exactly those sessions (`1001 Connection Idle Timeout`), which
        // reads to the user as "the terminal disconnected by itself".
        ws.setMaxIdleTimeout(0);
        TerminalManager manager = TerminalWebSocket.manager;
        TerminalTickets tickets = TerminalWebSocket.tickets;
        if (manager == null || tickets == null) {
            close(ws, 1011, "terminal service is not available");
            return;
        }
        String ticket = ws.getPathParameters().get("ticket");
        TerminalTickets.Ticket granted = tickets.consume(ticket);
        if (granted == null) {
            Logger.LOG.warning("Terminal WebSocket rejected: unknown or expired ticket");
            close(ws, 1008, "unknown or expired ticket");
            return;
        }
        if (!authDisabled) {
            Object token = ws.getUserProperties().get("token");
            if (granted.sessionToken() == null || !granted.sessionToken().equals(token)) {
                Logger.LOG.warning("Terminal WebSocket rejected: ticket does not belong to this session");
                close(ws, 1008, "ticket does not belong to this session");
                return;
            }
        }
        Optional<TerminalSession> found = manager.find(granted.brand(), granted.instanceId());
        if (found.isEmpty() || found.get().state() != TerminalSession.State.RUNNING) {
            Logger.LOG.warning("Terminal WebSocket rejected: no running terminal for instance "
                    + granted.instanceId());
            close(ws, 1011, "no running terminal for this instance");
            return;
        }
        TerminalSession session = found.get();
        this.terminal = session;
        this.sessionToken = granted.sessionToken();
        sendText(ws, readyJson(granted.instanceId(), granted.brand()));
        session.attach(this);
        if (granted.sessionToken() != null) {
            SOCKETS.register(granted.sessionToken(), this);
        }
        Logger.LOG.info("Terminal WebSocket attached: " + granted.instanceId());
    }

    /// The `ready` control frame. Static and separate from the handshake so the
    /// shape can be asserted in a unit test — docs/terminal-protocol.md is the
    /// contract both sides are written against.
    ///
    /// @param instanceId the instance this socket is attached to
    /// @param brand      the brand id the terminal serves
    /// @return the JSON text frame
    public static String readyJson(String instanceId, String brand) {
        JsonObject ready = new JsonObject();
        ready.addProperty("type", "ready");
        ready.addProperty("protocol", PROTOCOL);
        ready.addProperty("instance", instanceId);
        ready.addProperty("brand", brand);
        return ready.toString();
    }

    @OnMessage
    public void onBinary(ByteBuffer data, Session ws) {
        TerminalSession session = terminal;
        if (session == null) {
            return;
        }
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        try {
            session.input().write(bytes);
            session.input().flush();
        } catch (IOException e) {
            close(ws, 1008, "the terminal has ended");
        }
    }

    @OnMessage
    public void onText(String text, Session ws) {
        TerminalSession session = terminal;
        if (session == null) {
            return;
        }
        try {
            JsonObject message = JsonParser.parseString(text).getAsJsonObject();
            if ("resize".equals(message.has("type") ? message.get("type").getAsString() : "")) {
                session.resize(new PtySize(message.get("cols").getAsInt(), message.get("rows").getAsInt()));
            }
        } catch (RuntimeException ignored) {
            // A malformed control frame is not worth dropping the terminal.
        }
    }

    @OnClose
    public void onClose(Session ws, CloseReason reason) {
        Logger.LOG.info("Terminal WebSocket closed: " + reason.getCloseCode() + " " + reason.getReasonPhrase());
        detach();
    }

    @OnError
    public void onError(Throwable error) {
        Logger.LOG.warning("Terminal WebSocket error", error);
        detach();
    }

    private void detach() {
        String token = sessionToken;
        sessionToken = null;
        if (token != null) {
            SOCKETS.unregister(token, this);
        }
        TerminalSession session = terminal;
        if (session != null) {
            session.detach(this);
        }
    }

    /// The owning session logged out: sever this viewer. Deliberately a
    /// detach, not a kill — the agent keeps running and may be reattached
    /// after the next elevation.
    @Override
    public void closeForLogout() {
        Session ws = this.ws;
        if (ws != null && ws.isOpen()) {
            close(ws, 1008, "logged out");
        }
    }

    /// Bytes from the agent. Called on the session's pump thread.
    @Override
    public void data(byte[] bytes, int offset, int length) {
        Session ws = this.ws;
        if (ws == null || !ws.isOpen()) {
            return;
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes, offset, length);
        try {
            synchronized (ws) {
                ws.getBasicRemote().sendBinary(buffer);
            }
        } catch (IOException | RuntimeException e) {
            Logger.LOG.warning("Terminal WebSocket send failed (" + length + " bytes)", e);
            // The viewer is gone; the session stays alive for the next one.
            detach();
        }
    }

    /// The agent is gone: tell the viewer and close the socket.
    @Override
    public void exited(int code) {
        Session ws = this.ws;
        if (ws == null) {
            return;
        }
        TerminalSession terminal = this.terminal;
        sendText(ws, exitJson(code, terminal != null && terminal.silentFailure()));
        try {
            ws.close(new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, "terminal exited"));
        } catch (IOException ignored) {
            // Closing an already-closed socket is not an error here.
        }
    }

    /// The `exit` control frame, kept separate from the socket for the same
    /// reason as [readyJson]: docs/terminal-protocol.md is the contract and a
    /// unit test pins the shape.
    ///
    /// `failedWithoutOutput` is only present when true — a viewer that predates
    /// the field keeps parsing the frame unchanged, which is why adding it is not
    /// a protocol break (docs/terminal-protocol.md §4). It is the one thing the
    /// browser can act on when a terminal dies instantly: the child never spoke,
    /// so the screen is empty because there was nothing to paint, not because the
    /// viewer missed it.
    ///
    /// @param code                the child's exit code
    /// @param failedWithoutOutput whether this is a failure that left no output
    ///                            ([TerminalSession#silentFailure])
    /// @return the JSON text frame
    public static String exitJson(int code, boolean failedWithoutOutput) {
        JsonObject exit = new JsonObject();
        exit.addProperty("type", "exit");
        exit.addProperty("code", code);
        if (failedWithoutOutput) {
            exit.addProperty("failedWithoutOutput", true);
        }
        return exit.toString();
    }

    private static void sendText(Session ws, String text) {
        try {
            synchronized (ws) {
                ws.getBasicRemote().sendText(text);
            }
        } catch (IOException ignored) {
            // Same as above: the viewer is gone.
        }
    }

    private static void close(Session ws, int code, String reason) {
        try {
            ws.close(new CloseReason(
                    CloseReason.CloseCodes.getCloseCode(code), reason));
        } catch (IOException ignored) {
            // Already closing.
        }
    }

    /// Authentication for the terminal socket: the same session cookie as the
    /// panel, plus the token that the ticket was issued to.
    public static final class SessionConfigurator extends ServerEndpointConfig.Configurator {
        @Override
        public void modifyHandshake(ServerEndpointConfig config, HandshakeRequest request,
                                    HandshakeResponse response) {
            String token = sessionCookie(request);
            if (!authDisabled) {
                AuthService auth = authService;
                if (auth == null) {
                    throw new IllegalStateException("no AuthService installed");
                }
                if (token == null || auth.verify(token).isEmpty()) {
                    Logger.LOG.warning("Terminal WebSocket handshake rejected: missing or invalid session cookie");
                    throw new SecurityException("unauthorized");
                }
            }
            config.getUserProperties().put("token", token);
        }

        private static @Nullable String sessionCookie(HandshakeRequest request) {
            for (Map.Entry<String, List<String>> header : request.getHeaders().entrySet()) {
                if (!"cookie".equalsIgnoreCase(header.getKey())) {
                    continue;
                }
                for (String value : header.getValue()) {
                    for (String pair : value.split(";")) {
                        String trimmed = pair.trim();
                        if (trimmed.startsWith(AuthFilter.SESSION_COOKIE + "=")) {
                            return trimmed.substring(AuthFilter.SESSION_COOKIE.length() + 1);
                        }
                    }
                }
            }
            return null;
        }
    }
}
