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

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// 同机反代会让每个外部请求的 `remoteAddr` 都变成 `127.0.0.1`：agent 的
/// loopback 限制、开发者守卫的明文升级、登录节流曾经全部按这个地址判定。
/// 这组用例把"什么情况下才采纳 `X-Forwarded-For`"钉死，避免修成另一个方向
/// 的洞——默认信头等于让客户端自己填写来源地址。
class ClientAddressesTest {

    private static final Set<String> NONE = Set.of();
    private static final Set<String> LOCAL_PROXY = Set.of("127.0.0.1", "::1");

    @Test
    void withoutTrustedProxiesTheConnectionPeerIsTheAnswer() {
        // 反代场景的现状：外部访客看起来都是本机。没配置时保持这个行为，而不是
        // 悄悄开始信一个客户端可以自写的头。
        assertEquals("127.0.0.1", ClientAddresses.resolve("127.0.0.1", "1.2.3.4", NONE));
        assertEquals("203.0.113.7", ClientAddresses.resolve("203.0.113.7", "1.2.3.4", NONE));
    }

    @Test
    void anUntrustedPeerCannotVouchForAClient() {
        // 即便面板配置了可信反代，一个不在名单里的来源（比如绕过反代直连的
        // 客户端）写的头仍然不算数。
        assertEquals("203.0.113.7", ClientAddresses.resolve("203.0.113.7", "1.2.3.4", LOCAL_PROXY));
    }

    @Test
    void aTrustedProxyVouchesForTheFirstUntrustedHopFromTheRight() {
        assertEquals("1.2.3.4", ClientAddresses.resolve("127.0.0.1", "1.2.3.4", LOCAL_PROXY));
        // 客户端伪造的左半段被丢弃：可信反代之后的第一跳才是它真正看见的对端。
        assertEquals("198.51.100.9",
                ClientAddresses.resolve("127.0.0.1", "192.0.2.1, 198.51.100.9", LOCAL_PROXY));
        assertEquals("198.51.100.9",
                ClientAddresses.resolve("127.0.0.1", "192.0.2.1, 198.51.100.9, 127.0.0.1", LOCAL_PROXY));
    }

    @Test
    void aMissingOrEmptyHeaderLeavesThePeer() {
        assertEquals("127.0.0.1", ClientAddresses.resolve("127.0.0.1", null, LOCAL_PROXY));
        assertEquals("127.0.0.1", ClientAddresses.resolve("127.0.0.1", "  ", LOCAL_PROXY));
        assertEquals("127.0.0.1", ClientAddresses.resolve("127.0.0.1", " , ", LOCAL_PROXY));
    }

    @Test
    void aChainOfOnlyTrustedHopsFallsBackToTheLeftmost() {
        assertEquals("127.0.0.1", ClientAddresses.resolve("127.0.0.1", "127.0.0.1, ::1", LOCAL_PROXY));
    }

    @Test
    void aMissingPeerIsUnknownRatherThanNull() {
        assertEquals("unknown", ClientAddresses.resolve(null, "1.2.3.4", NONE));
    }

    /// 转发项必须是 IP 字面量：主机名会被退回去，绝不为它做 DNS 解析。
    /// Windows 裸部署没有反代可依赖时，这条保证客户端塞进来的垃圾不会变成
    /// 一次 DNS 查询，也不会被当成可信地址。
    @Test
    void hostnamesAndGarbageAreNotResolvedAsClients() {
        assertEquals("127.0.0.1", ClientAddresses.resolve("127.0.0.1", "host.example.invalid", LOCAL_PROXY));
        assertEquals("127.0.0.1", ClientAddresses.resolve("127.0.0.1", "1.2.3.4, not-an-ip", LOCAL_PROXY));
        assertEquals("127.0.0.1", ClientAddresses.resolve("127.0.0.1", "999.1.1.1", LOCAL_PROXY));
        assertEquals("127.0.0.1", ClientAddresses.resolve("127.0.0.1", "1.2.3.4.5", LOCAL_PROXY));
    }

    @Test
    void ipLiteralsIncludingIpv6AreAccepted() {
        assertEquals("2001:db8::1", ClientAddresses.resolve("127.0.0.1", "2001:db8::1", LOCAL_PROXY));
        assertEquals("::ffff:203.0.113.7",
                ClientAddresses.resolve("127.0.0.1", "::ffff:203.0.113.7", LOCAL_PROXY));
        assertEquals("203.0.113.7", ClientAddresses.resolve("127.0.0.1", " 203.0.113.7 ", LOCAL_PROXY));
    }
}
