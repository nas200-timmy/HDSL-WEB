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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TencentDnsProviderTest {

    @Test
    void presentCleanupAndTestUseTheV3Actions() throws Exception {
        List<String> actions = new ArrayList<>();
        List<String> authorizations = new ArrayList<>();
        List<String> bodies = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String action = exchange.getRequestHeaders().getFirst("X-TC-Action");
            actions.add(action);
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String response = switch (action == null ? "" : action) {
                case "CreateRecord" -> "{\"Response\":{\"RecordId\":123,\"RequestId\":\"r\"}}";
                case "DeleteRecord" -> "{\"Response\":{\"RequestId\":\"r\"}}";
                default -> "{\"Response\":{\"DomainList\":[],\"RequestId\":\"r\"}}";
            };
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            TencentDnsProvider provider = new TencentDnsProvider(
                    "secret-id", "secret-key", "example.com",
                    HttpClient.newHttpClient(), base);
            provider.present("_acme-challenge.example.com", "txt-value");
            provider.cleanup("_acme-challenge.example.com", "txt-value");
            provider.test();

            assertEquals(List.of("CreateRecord", "DeleteRecord", "DescribeDomainList"), actions);
            String authorization = authorizations.get(0);
            assertTrue(authorization.startsWith("TC3-HMAC-SHA256 Credential=secret-id/"), authorization);
            assertTrue(authorization.contains("/dnspod/tc3_request"), authorization);
            assertTrue(authorization.contains("SignedHeaders=content-type;host"), authorization);
            assertTrue(authorization.contains("Signature="), authorization);

            assertTrue(bodies.get(0).contains("\"Domain\":\"example.com\""), bodies.get(0));
            assertTrue(bodies.get(0).contains("\"SubDomain\":\"_acme-challenge\""), bodies.get(0));
            assertTrue(bodies.get(0).contains("\"RecordType\":\"TXT\""), bodies.get(0));
            assertTrue(bodies.get(0).contains("\"Value\":\"txt-value\""), bodies.get(0));
            assertTrue(bodies.get(1).contains("\"RecordId\":123"), bodies.get(1));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void apiErrorsSurfaceTheCodeAndMessage() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String response = "{\"Response\":{\"Error\":{\"Code\":\"AuthFailure.SignatureFailure\","
                    + "\"Message\":\"The provided credentials could not be validated.\"},"
                    + "\"RequestId\":\"r\"}}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            TencentDnsProvider provider = new TencentDnsProvider(
                    "secret-id", "wrong", "example.com",
                    HttpClient.newHttpClient(), base);
            AcmeDnsException error = assertThrows(AcmeDnsException.class, provider::test);
            assertTrue(error.getMessage().contains("AuthFailure.SignatureFailure"), error.getMessage());
            assertTrue(error.getMessage().contains("could not be validated"), error.getMessage());
        } finally {
            server.stop(0);
        }
    }
}
