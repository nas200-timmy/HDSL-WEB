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

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/// The thirty-minute developer-mode elevation, shared by every door that
/// needs it: the developer routes and the web terminal.
///
/// The key is the **session token**, never the username: two browser sessions
/// of the same account must not share an elevation, and logging one out must
/// end its elevation in the same instant. A key whose session is gone is
/// treated as not elevated (the caller passes that in, because only
/// [org.jackhuang.hmcl.web.auth.AuthService] knows).
///
/// Failures are counted per key with the same shape the sign-in gate uses:
/// [#MAX_FAILURES] wrong passwords inside [#FAILURE_WINDOW] lock the key for
/// [#LOCK]. A correct password clears both.
@NotNullByDefault
public final class DeveloperElevation {

    /// How long one elevation lasts, sliding on every use. Thirty minutes is
    /// deliberately longer than a terminal session's usual idle gaps: a user
    /// watching an agent should not be asked for the password mid-task, and
    /// the elevation only gates new opens/attach, not the live WebSocket.
    public static final Duration TTL = Duration.ofMinutes(30);
    /// The ceiling a session may give itself on demand (the developer page's
    /// 「延长提权」): one long stretch — a hands-on maintenance window — beats
    /// being re-asked hourly, and it is still a fraction of a day.
    public static final Duration EXTENDED_TTL = Duration.ofHours(3);
    /// How many wrong developer passwords are allowed inside the window.
    public static final int MAX_FAILURES = 3;
    /// How long a locked key waits before it may guess again.
    public static final Duration LOCK = Duration.ofMinutes(10);
    /// How far back failures are counted.
    public static final Duration FAILURE_WINDOW = Duration.ofMinutes(10);

    private static final String SESSION_PREFIX = "token:";

    private final ConcurrentHashMap<String, Instant> elevations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Failures> failures = new ConcurrentHashMap<>();

    /// The elevation key for one request: the session token when there is
    /// one, otherwise the remote address (authentication disabled is
    /// loopback-only by config validation).
    public static String key(@Nullable String sessionToken, @Nullable String remoteAddress) {
        if (sessionToken != null && !sessionToken.isBlank()) {
            return SESSION_PREFIX + sessionToken;
        }
        return "anonymous:" + (remoteAddress == null || remoteAddress.isBlank() ? "unknown" : remoteAddress);
    }

    /// Whether `key` is elevated right now, given whether its session is
    /// still alive.
    public boolean isElevated(String key, boolean sessionAlive) {
        if (!sessionAlive) {
            elevations.remove(key);
            return false;
        }
        Instant expiry = elevations.get(key);
        return expiry != null && expiry.isAfter(Instant.now());
    }

    /// Passes and slides an elevation, or answers `false`.
    public boolean require(String key, boolean sessionAlive) {
        Instant now = Instant.now();
        prune(now);
        if (!sessionAlive) {
            elevations.remove(key);
            return false;
        }
        Instant expiry = elevations.get(key);
        if (expiry != null && expiry.isAfter(now)) {
            elevations.put(key, now.plus(TTL));
            return true;
        }
        elevations.remove(key);
        return false;
    }

    /// Marks `key` elevated for a fresh [TTL].
    public void elevate(String key) {
        elevations.put(key, Instant.now().plus(TTL));
    }

    /// Stretches `key`'s elevation to `duration` from now, answering the new
    /// expiry. The caller must have established elevation first (see
    /// [#require]); extending a key that has none would mint authority out of
    /// nothing, so this method refuses by answering `null`.
    public @Nullable Instant extend(String key, Duration duration) {
        Instant now = Instant.now();
        Instant expiry = elevations.computeIfPresent(key, (k, old) -> old.isAfter(now) ? now.plus(duration) : null);
        return expiry;
    }

    /// When `key`'s elevation expires, if it is elevated and its session
    /// alive — what [#status] answers as `expiresAt`.
    public @Nullable Instant expiryOf(String key, boolean sessionAlive) {
        if (!sessionAlive) {
            elevations.remove(key);
            return null;
        }
        Instant expiry = elevations.get(key);
        return expiry != null && expiry.isAfter(Instant.now()) ? expiry : null;
    }

    /// Ends one key's elevation.
    public void clear(String key) {
        elevations.remove(key);
        failures.remove(key);
    }

    /// Ends every elevation: a changed or cleared developer password makes
    /// every proof of the old one worthless.
    public void clearAll() {
        elevations.clear();
        failures.clear();
    }

    /// Whether wrong passwords locked this key.
    public boolean isLocked(String key, Instant now) {
        Failures record = failures.computeIfAbsent(key, k -> new Failures());
        synchronized (record) {
            return record.lockedUntil != null && now.isBefore(record.lockedUntil);
        }
    }

    /// Records a wrong developer password.
    ///
    /// @return whether this failure tripped the lock
    public boolean recordFailure(String key, Instant now) {
        Failures record = failures.computeIfAbsent(key, k -> new Failures());
        synchronized (record) {
            if (record.lockedUntil != null) {
                if (now.isBefore(record.lockedUntil)) {
                    return true;
                }
                record.lockedUntil = null;
                record.count = 0;
            }
            if (record.windowStart == null || now.isAfter(record.windowStart.plus(FAILURE_WINDOW))) {
                record.count = 0;
                record.windowStart = now;
            }
            record.count++;
            if (record.count >= MAX_FAILURES) {
                record.lockedUntil = now.plus(LOCK);
                record.count = 0;
                return true;
            }
            return false;
        }
    }

    /// Forgets a key's failures, which a correct password does.
    public void clearFailures(String key) {
        failures.remove(key);
    }

    /// Drops elevations whose fifteen minutes are up. Sessions come and go,
    /// so the map is only bounded if the expired entries actually leave.
    public void prune(Instant now) {
        if (elevations.size() < 64) {
            return;
        }
        elevations.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
    }

    private static final class Failures {
        private int count;
        private @Nullable Instant windowStart;
        private @Nullable Instant lockedUntil;
    }
}
