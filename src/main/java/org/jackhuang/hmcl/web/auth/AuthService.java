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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/// Issues and verifies session tokens.
///
/// A login that passes [UserStore] yields a 32-byte random token
/// (base64url). The token is the key of an in-memory session map; sessions
/// live at most [sessionTtl] and slide forward on every verified request.
/// They are deliberately not persisted — a restart logs everyone out, which
/// is accepted for the single-admin panel.
@NotNullByDefault
public final class AuthService {

    /// A live session. `expiresAt` slides on every [verify].
    public record Session(String username, Instant expiresAt) {
    }

    private static final int TOKEN_BYTES = 32;

    private final UserStore userStore;
    private final Duration sessionTtl;
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    public AuthService(UserStore userStore, Duration sessionTtl) {
        this.userStore = userStore;
        this.sessionTtl = sessionTtl;
    }

    /// Verifies credentials and, on success, opens a session.
    ///
    /// @return the session token to hand out, or empty when the credentials
    /// are wrong.
    public Optional<String> login(String username, String password) {
        if (username == null || password == null || !userStore.verify(username, password)) {
            return Optional.empty();
        }
        return Optional.of(mintSession(username));
    }

    /// Opens a session for `username` without checking a password — the
    /// first-run setup hands the freshly created user their session this way.
    /// Refuses names the store does not hold.
    public Optional<String> createSession(String username) {
        UserStore.User user = userStore.admin();
        if (user == null || !user.username().equals(username)) {
            return Optional.empty();
        }
        return Optional.of(mintSession(username));
    }

    /// Ends every session of `username` except `keepToken` — the password
    /// change revokes the user's other devices while the session that made
    /// the change keeps working. A null `keepToken` revokes them all.
    ///
    /// @return how many sessions were revoked
    public int revokeOtherSessions(String username, @Nullable String keepToken) {
        int revoked = 0;
        for (Map.Entry<String, Session> entry : sessions.entrySet()) {
            if (entry.getKey().equals(keepToken)) {
                continue;
            }
            if (entry.getValue().username().equals(username) && sessions.remove(entry.getKey(), entry.getValue())) {
                revoked++;
            }
        }
        return revoked;
    }

    /// The store behind this service; the auth endpoints read initialization
    /// and password policy state from it.
    public UserStore userStore() {
        return userStore;
    }

    private String mintSession(String username) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessions.put(token, new Session(username, Instant.now().plus(sessionTtl)));
        return token;
    }

    /// Checks a token and slides its expiry forward.
    public Optional<Session> verify(@Nullable String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Session session = sessions.get(token);
        if (session == null) {
            return Optional.empty();
        }
        if (session.expiresAt().isBefore(Instant.now())) {
            sessions.remove(token);
            return Optional.empty();
        }
        Session renewed = new Session(session.username(), Instant.now().plus(sessionTtl));
        sessions.put(token, renewed);
        return Optional.of(renewed);
    }

    /// Ends the session behind `token`, if any.
    public void logout(@Nullable String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    /// How many sessions are currently alive (expired ones are purged first).
    public int sessions() {
        Instant now = Instant.now();
        sessions.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
        return sessions.size();
    }
}
