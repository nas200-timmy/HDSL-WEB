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

import jakarta.servlet.http.HttpServletRequest;
import org.jackhuang.hmcl.web.auth.AuthFilter;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/// The address a security decision should be made on.
///
/// `request.getRemoteAddr()` is the **connection's** peer, which behind a
/// same-host nginx/Caddy is `127.0.0.1` for every visitor. Decisions that used
/// it directly — the agent loopback rule, the developer guard's cleartext
/// upgrade, the login throttle's bucket — therefore silently turned into "every
/// external request is a local one" the moment the panel was proxied.
///
/// The answer here is deliberately conservative:
///
/// - **Nobody is trusted by default.** With no `network.trusted_proxies`
///   configured the connection peer is the answer, exactly as before. A client
///   can write `X-Forwarded-For` itself, so honouring it unconditionally would
///   trade "the proxy hides the client" for "the client picks its own address".
/// - **Only a configured proxy may vouch for a client.** When the peer is in
///   `network.trusted_proxies`, the header is walked from the right, skipping
///   hops that are themselves trusted proxies; the first untrusted hop is the
///   client. Anything the client forged to the left of that hop is ignored.
/// - **No header, no upgrade.** A trusted proxy that did not send
///   `X-Forwarded-For` leaves the connection peer as the answer.
@NotNullByDefault
public final class ClientAddresses {

    private ClientAddresses() {
    }

    /// The client address for this request under the panel's proxy trust.
    public static String remote(HttpServletRequest request, Set<String> trustedProxies) {
        return resolve(request.getRemoteAddr(), request.getHeader("X-Forwarded-For"), trustedProxies);
    }

    /// Whether the client — not the connection — is on this machine.
    public static boolean isLoopback(HttpServletRequest request, Set<String> trustedProxies) {
        return AuthFilter.isLoopback(remote(request, trustedProxies));
    }

    /// The resolution as a pure function, so the proxy matrix can be exercised
    /// without a socket.
    ///
    /// @param remoteAddr    the connection's peer
    /// @param forwardedFor  the raw `X-Forwarded-For` header, or `null`
    /// @param trustedProxies the addresses allowed to vouch for a client
    /// @return the address to decide on, never `null`
    public static String resolve(@Nullable String remoteAddr, @Nullable String forwardedFor,
                                 Set<String> trustedProxies) {
        String peer = remoteAddr == null || remoteAddr.isBlank() ? "unknown" : remoteAddr.trim();
        if (trustedProxies.isEmpty() || !trustedProxies.contains(peer)
                || forwardedFor == null || forwardedFor.isBlank()) {
            return peer;
        }
        List<String> hops = new ArrayList<>();
        for (String hop : forwardedFor.split(",")) {
            String trimmed = hop.trim();
            if (!trimmed.isEmpty()) {
                hops.add(trimmed);
            }
        }
        for (int i = hops.size() - 1; i >= 0; i--) {
            if (!trustedProxies.contains(hops.get(i))) {
                String client = hops.get(i);
                // 只接受 IP 字面量：`AuthFilter.isLoopback` 走 `InetAddress.getByName`，
                // 一个像主机名的转发项会在 Windows/裸部署上触发 DNS 查询（慢、且把
                // 客户端可控的字符串发给 DNS）。转发链说得不清不楚时，退回连接对端，
                // 不做域名解析，也不替它猜。
                return ipLiteral(client) ? client : peer;
            }
        }
        // Every hop is a trusted proxy: the leftmost is the furthest away from
        // this panel that the chain still names, which is the best answer left.
        return hops.isEmpty() ? peer : hops.get(0);
    }

    /// IPv4 四段或 IPv6 字面量，**不做 DNS**——这是这个类唯一的格式判据。
    private static boolean ipLiteral(String value) {
        if (value.isEmpty() || value.length() > 64) {
            return false;
        }
        if (value.indexOf(':') >= 0) {
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
                if (!hex && c != ':' && c != '.') {
                    return false;
                }
            }
            return true;
        }
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) {
                    return false;
                }
            }
            if (Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }
}
