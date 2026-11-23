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
import org.jackhuang.hmcl.web.config.ServerConfig;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// `X-Forwarded-Proto` 和 `X-Forwarded-For` 一样，都只能由可信反代提供：面板若同时
/// 能被直连，客户端自己写这个头就能声称 HTTPS，解锁开发者/终端闸门。
class RequestSecurityTest {

    private static HttpServletRequest request(String remoteAddr, String forwardedProto) {
        return (HttpServletRequest) Proxy.newProxyInstance(RequestSecurityTest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getRemoteAddr" -> remoteAddr;
                    case "getHeader" -> "X-Forwarded-Proto".equals(args[0]) ? forwardedProto : null;
                    case "isSecure" -> false;
                    default -> null;
                });
    }

    private static ServerConfig config(boolean trustForwardedProto, Set<String> trustedProxies) {
        ServerConfig config = new ServerConfig();
        config.https.trustForwardedProto = trustForwardedProto;
        config.network.trustedProxies = trustedProxies;
        return config;
    }

    @Test
    void aTrustedProxyMayVouchForHttps() {
        ServerConfig config = config(true, Set.of("127.0.0.1"));
        assertTrue(RequestSecurity.forwardedHttps(request("127.0.0.1", "https"), config));
        assertFalse(RequestSecurity.forwardedHttps(request("127.0.0.1", "http"), config));
    }

    @Test
    void aDirectClientCannotVouchForHttpsItself() {
        ServerConfig config = config(true, Set.of("127.0.0.1"));
        assertFalse(RequestSecurity.forwardedHttps(request("203.0.113.7", "https"), config),
                "不在 trusted_proxies 里的来源，写 X-Forwarded-Proto: https 也不算数");
        // IPv6 全角与压缩写法都算同一个可信反代。
        assertTrue(RequestSecurity.forwardedHttps(request("0:0:0:0:0:0:0:1", "https"),
                config(true, Set.of("::1"))));
    }

    @Test
    void withoutTrustedProxiesTheLegacyFlagStillApplies() {
        ServerConfig config = config(true, Set.of());
        assertTrue(RequestSecurity.forwardedHttps(request("203.0.113.7", "https"), config),
                "没配置可信反代时保持旧行为，不悄悄改变现有部署");
    }

    @Test
    void theOptInSwitchStillComesFirst() {
        assertFalse(RequestSecurity.forwardedHttps(request("127.0.0.1", "https"),
                config(false, Set.of("127.0.0.1"))));
    }
}
