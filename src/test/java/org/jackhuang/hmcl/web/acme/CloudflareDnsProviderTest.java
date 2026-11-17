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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudflareDnsProviderTest {

    @Test
    void createsAndDeletesTheTxtRecord() throws Exception {
        List<String> calls = new ArrayList<>();
        AtomicReference<String> createBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/client/v4/zones", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getQuery();
            calls.add(exchange.getRequestMethod() + " " + path + (query == null ? "" : "?" + query));
            String response;
            if (exchange.getRequestMethod().equals("GET") && path.endsWith("/zones")) {
                response = "{\"success\":true,\"result\":[{\"id\":\"zone-1\"}]}";
            } else if (exchange.getRequestMethod().equals("POST") && path.endsWith("/dns_records")) {
                createBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                response = "{\"success\":true,\"result\":{\"id\":\"record-1\"}}";
            } else if (exchange.getRequestMethod().equals("DELETE")) {
                response = "{\"success\":true,\"result\":{\"id\":\"record-1\"}}";
            } else {
                response = "{\"success\":false,\"errors\":[{\"message\":\"unexpected\"}]}";
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/client/v4";
            CloudflareDnsProvider provider = new CloudflareDnsProvider(
                    "token", "example.com", HttpClient.newHttpClient(), base);
            provider.present("_acme-challenge.example.com", "txt-value");
            assertTrue(createBody.get().contains("\"name\":\"_acme-challenge.example.com\""));
            assertTrue(createBody.get().contains("\"content\":\"txt-value\""));
            provider.cleanup("_acme-challenge.example.com", "txt-value");
            assertTrue(calls.stream().anyMatch(c -> c.startsWith("DELETE ")),
                    "cleanup should delete the created record: " + calls);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void discoversTheZoneWhenNotConfigured() throws Exception {
        List<String> zoneQueries = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/client/v4/zones", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getQuery();
            zoneQueries.add(query == null ? "" : query);
            String response;
            if (exchange.getRequestMethod().equals("POST") && path.endsWith("/dns_records")) {
                response = "{\"success\":true,\"result\":{\"id\":\"record-1\"}}";
            } else if (exchange.getRequestMethod().equals("GET") && query != null && query.contains("sub.example.com")) {
                response = "{\"success\":true,\"result\":[]}";
            } else if (exchange.getRequestMethod().equals("GET") && query != null && query.contains("example.com")) {
                response = "{\"success\":true,\"result\":[{\"id\":\"zone-1\"}]}";
            } else {
                response = "{\"success\":true,\"result\":[{\"id\":\"zone-1\"}]}";
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/client/v4";
            CloudflareDnsProvider provider = new CloudflareDnsProvider(
                    "token", null, HttpClient.newHttpClient(), base);
            provider.present("_acme-challenge.sub.example.com", "v");
            assertTrue(zoneQueries.stream().anyMatch(q -> q.contains("sub.example.com")));
            assertTrue(zoneQueries.stream().anyMatch(q -> q.contains("example.com")));
        } finally {
            server.stop(0);
        }
    }
}
