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

import static org.junit.jupiter.api.Assertions.assertTrue;

/// The Tier 2 adapters are small but their wire shapes differ (GoDaddy
/// replaces a set, DigitalOcean and Vultr create individual records). Pin each
/// one against a local HTTP server.
class Tier2DnsProvidersTest {

    @Test
    void godaddyReadsAppendsAndDeletesOnlyItsOwnTxtValue() throws Exception {
        List<String> calls = new ArrayList<>();
        java.util.concurrent.atomic.AtomicReference<String> records =
                new java.util.concurrent.atomic.AtomicReference<>("[{\"data\":\"other-txt\",\"ttl\":600}]");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/godaddy/domains", exchange -> {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            calls.add(method + " " + path + " " + body);
            String response;
            if (method.equals("GET")) {
                response = records.get();
            } else {
                if (method.equals("PUT")) {
                    records.set(body);
                } else if (method.equals("DELETE")) {
                    records.set("[]");
                }
                response = "";
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/godaddy";
            GoDaddyDnsProvider provider = new GoDaddyDnsProvider(
                    "key", "secret", "example.com", HttpClient.newHttpClient(), base);
            provider.present("_acme-challenge.example.com", "acme-value");
            provider.cleanup("_acme-challenge.example.com", "acme-value");
            assertTrue(calls.stream().anyMatch(c -> c.startsWith("GET ")
                    && c.contains("_acme-challenge")));
            assertTrue(calls.stream().anyMatch(c -> c.startsWith("PUT ")
                    && c.contains("other-txt") && c.contains("acme-value")),
                    "present must preserve unrelated TXT records: " + calls);
            assertTrue(calls.stream().anyMatch(c -> c.startsWith("PUT ")
                    && c.contains("other-txt") && !c.contains("acme-value")),
                    "cleanup must remove only its own value: " + calls);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void digitalOceanCreatesAndDeletesARecord() throws Exception {
        List<String> calls = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/do/domains", exchange -> {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            calls.add(method + " " + path + " " + body);
            String response = method.equals("POST") ? "{\"domain_record\":{\"id\":101}}" : "";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/do";
            DigitalOceanDnsProvider provider = new DigitalOceanDnsProvider(
                    "token", "example.com", HttpClient.newHttpClient(), base);
            provider.present("_acme-challenge.example.com", "value");
            provider.cleanup("_acme-challenge.example.com", "value");
            assertTrue(calls.stream().anyMatch(c -> c.startsWith("POST ")
                    && c.contains("\"name\":\"_acme-challenge\"")));
            assertTrue(calls.stream().anyMatch(c -> c.startsWith("DELETE ")
                    && c.endsWith("/records/101 ")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void vultrCreatesAndDeletesARecord() throws Exception {
        List<String> calls = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/vultr/domains", exchange -> {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            calls.add(method + " " + path + " " + body);
            String response = method.equals("POST") ? "{\"record\":{\"id\":\"r1\"}}" : "";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/vultr";
            VultrDnsProvider provider = new VultrDnsProvider(
                    "key", "example.com", HttpClient.newHttpClient(), base);
            provider.present("_acme-challenge.example.com", "value");
            provider.cleanup("_acme-challenge.example.com", "value");
            assertTrue(calls.stream().anyMatch(c -> c.startsWith("POST ")
                    && c.contains("\"name\":\"_acme-challenge\"")));
            assertTrue(calls.stream().anyMatch(c -> c.startsWith("DELETE ")
                    && c.endsWith("/records/r1 ")));
        } finally {
            server.stop(0);
        }
    }
}
