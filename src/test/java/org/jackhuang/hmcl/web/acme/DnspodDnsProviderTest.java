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

class DnspodDnsProviderTest {

    @Test
    void presentAndCleanupUseTheApiActions() throws Exception {
        List<String> bodies = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            bodies.add(path + " " + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String response;
            if (path.endsWith("/Domain.Info")) {
                response = "{\"status\":{\"code\":\"1\",\"message\":\"ok\"},\"domain\":{\"id\":\"d1\"}}";
            } else if (path.endsWith("/Record.Create")) {
                response = "{\"status\":{\"code\":\"1\",\"message\":\"ok\"},\"record\":{\"id\":\"r1\"}}";
            } else {
                response = "{\"status\":{\"code\":\"1\",\"message\":\"ok\"}}";
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            DnspodDnsProvider provider = new DnspodDnsProvider(
                    "id,token", "example.com", HttpClient.newHttpClient(), base);
            provider.present("_acme-challenge.example.com", "txt-value");
            provider.cleanup("_acme-challenge.example.com", "txt-value");

            assertTrue(bodies.stream().anyMatch(b -> b.contains("Domain.Info") && b.contains("login_token=id%2Ctoken")));
            assertTrue(bodies.stream().anyMatch(b -> b.contains("Record.Create") && b.contains("sub_domain=_acme-challenge")));
            assertTrue(bodies.stream().anyMatch(b -> b.contains("Record.Remove") && b.contains("record_id=r1")));
        } finally {
            server.stop(0);
        }
    }
}
