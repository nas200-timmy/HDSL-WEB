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
package org.jackhuang.hmcl.web.minecraft;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McVersionsTest {

    private static String sha1(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, byte[] body) throws java.io.IOException {
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /// Serves a manifest → version metadata → jar chain, with `sha1` for the jar.
    private static HttpServer fakeMojang(byte[] jar, String sha1) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        String base = "http://127.0.0.1:" + port;
        server.createContext("/manifest.json", exchange -> respond(exchange, ("""
                {"versions":[{"id":"1.21.4","type":"release","url":"%s/meta.json"}]}
                """.formatted(base)).getBytes(StandardCharsets.UTF_8)));
        server.createContext("/meta.json", exchange -> respond(exchange, ("""
                {"downloads":{"server":{"url":"%s/server.jar","sha1":"%s","size":%d}}}
                """.formatted(base, sha1, jar.length)).getBytes(StandardCharsets.UTF_8)));
        server.createContext("/server.jar", exchange -> respond(exchange, jar));
        server.start();
        return server;
    }

    @Test
    void downloadsVerifiesAndLinksTheServerJar(@TempDir Path dir) throws Exception {
        byte[] jar = "not really a jar, but the hash is what matters".getBytes(StandardCharsets.UTF_8);
        HttpServer mojang = fakeMojang(jar, sha1(jar));
        try {
            String manifest = "http://127.0.0.1:" + mojang.getAddress().getPort() + "/manifest.json";
            McInstanceManager manager = new McInstanceManager(dir.resolve("instances"));
            McInstance instance = manager.create("survival", "1.21.4", 25565, 25575, 1024, "world", null);
            McVersions versions = new McVersions(dir.resolve("versions"), URI.create(manifest));

            assertEquals(1, versions.list().size());
            assertEquals("1.21.4", versions.list().get(0).id());

            McInstance installed = versions.install(instance, manager, "1.21.4");
            assertEquals("1.21.4", installed.version());
            assertArrayEquals(jar, Files.readAllBytes(manager.serverJar(installed)));
            assertTrue(Files.isRegularFile(dir.resolve("versions").resolve("1.21.4").resolve("server.jar")));
            assertTrue(Files.isRegularFile(dir.resolve("versions").resolve("1.21.4").resolve("version.json")));
        } finally {
            mojang.stop(0);
        }
    }

    @Test
    void aWrongHashFailsAndLeavesNothingBehind(@TempDir Path dir) throws Exception {
        byte[] jar = "payload".getBytes(StandardCharsets.UTF_8);
        HttpServer mojang = fakeMojang(jar, "0000000000000000000000000000000000000000");
        try {
            String manifest = "http://127.0.0.1:" + mojang.getAddress().getPort() + "/manifest.json";
            McInstanceManager manager = new McInstanceManager(dir.resolve("instances"));
            McInstance instance = manager.create("bad", "1.21.4", 25565, 25575, 1024, "world", null);
            McVersions versions = new McVersions(dir.resolve("versions"), URI.create(manifest));

            McException failed = assertThrows(McException.class,
                    () -> versions.install(instance, manager, "1.21.4"));
            assertTrue(failed.getMessage().contains("SHA-1"), failed.getMessage());
            assertFalse(Files.exists(manager.serverJar(instance)));
            assertFalse(Files.exists(dir.resolve("versions").resolve("1.21.4").resolve("server.jar")));
        } finally {
            mojang.stop(0);
        }
    }
}