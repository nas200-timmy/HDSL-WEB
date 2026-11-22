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
        String peer = remoteAddr == null || remoteAddr.isBlank() ? "unknown" : normalizeIp(remoteAddr.trim());
        if (trustedProxies.isEmpty() || !isTrusted(peer, trustedProxies)
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
            String hop = normalizeIp(hops.get(i));
            if (!isTrusted(hop, trustedProxies)) {
                // 只接受 IP 字面量：`AuthFilter.isLoopback` 走 `InetAddress.getByName`，
                // 一个像主机名的转发项会在 Windows/裸部署上触发 DNS 查询（慢、且把
                // 客户端可控的字符串发给 DNS）。
                //
                // 转发项畸形时**不能退回连接对端**：同机反代的对端就是 127.0.0.1，
                // 退回等于把外部请求当本机放行（agent 票、开发者守卫、节流全放宽）。
                // 说不清来源就按外部处理（fail-closed），不是按本机。
                return ipLiteral(hop) ? hop : "unknown";
            }
        }
        // Every hop is a trusted proxy: the leftmost is the furthest away from
        // this panel that the chain still names, which is the best answer left.
        return hops.isEmpty() ? peer : normalizeIp(hops.get(0));
    }

    /// 把 IP 字面量规范化成统一写法（`0:0:0:0:0:0:0:1` → `::1`），让
    /// `trusted_proxies` 的精确匹配不被 IPv6 的多种写法绕过。只对字面量调用
    /// `InetAddress`——字面量不会触发 DNS。
    public static String normalizeIp(String value) {
        if (!ipLiteral(value)) {
            return value;
        }
        try {
            return java.net.InetAddress.getByName(value).getHostAddress();
        } catch (java.net.UnknownHostException e) {
            return value;
        }
    }

    /// 某个地址是否属于可信反代：两边都先规范化，IPv6 的全角/压缩写法都算数。
    public static boolean isTrusted(@Nullable String address, Set<String> trustedProxies) {
        if (address == null || address.isBlank()) {
            return false;
        }
        String normalized = normalizeIp(address.trim());
        for (String trusted : trustedProxies) {
            if (normalizeIp(trusted).equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    /// IPv4 四段或 IPv6 字面量，**不做 DNS**——这是这个类唯一的格式判据。
    public static boolean ipLiteral(String value) {
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
