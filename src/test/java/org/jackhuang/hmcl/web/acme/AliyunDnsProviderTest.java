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
package org.jackhuang.hmcl.web.acme;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AliyunDnsProviderTest {

    @Test
    void canonicalQueryIsSortedAndRfc3986Encoded() {
        assertEquals("a=1&b=2", AliyunDnsProvider.canonicalQuery(Map.of("b", "2", "a", "1")));
        assertEquals("a=a%20b", AliyunDnsProvider.canonicalQuery(Map.of("a", "a b")));
        assertEquals("a=%2A", AliyunDnsProvider.canonicalQuery(Map.of("a", "*")));
        assertEquals("a=~", AliyunDnsProvider.canonicalQuery(Map.of("a", "~")));
    }

    @Test
    void presentAndCleanupUseTheRpcActions() throws Exception {
        List<String> queries = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            queries.add(exchange.getRequestURI().getRawQuery());
            String response = "{\"RecordId\":\"r1\"}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            AliyunDnsProvider provider = new AliyunDnsProvider(
                    "ak", "sk", "example.com", HttpClient.newHttpClient(), base);
            provider.present("_acme-challenge.example.com", "txt-value");
            provider.cleanup("_acme-challenge.example.com", "txt-value");

            assertTrue(queries.get(0).contains("Action=AddDomainRecord"));
            assertTrue(queries.get(0).contains("RR=_acme-challenge"));
            assertTrue(queries.get(0).contains("Value=txt-value"));
            assertTrue(queries.get(0).contains("Signature="));
            assertTrue(queries.get(1).contains("Action=DeleteDomainRecord"));
            assertTrue(queries.get(1).contains("RecordId=r1"));
        } finally {
            server.stop(0);
        }
    }
}
