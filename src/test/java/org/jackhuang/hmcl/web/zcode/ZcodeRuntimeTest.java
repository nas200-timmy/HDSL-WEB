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
package org.jackhuang.hmcl.web.zcode;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jackhuang.hmcl.web.NodeDshStub;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The experimental ZCode runtime against the `fake-zcode` node stub: the
/// readiness lines are parsed into a port, the pass-through arguments and the
/// injected `ZCODE_DATA_BASE_DIR` reach the process, a second launch is a
/// no-op, the log carries the process output, stop is clean, and a process
/// that exits early leaves `error` with the log's tail.
class ZcodeRuntimeTest {

    /// The stub distribution, the way `HDSL_ZCODE_PACKAGE` would point at a
    /// real one.
    private static final Path PACKAGE = Path.of("src/test/resources/fake-zcode")
            .toAbsolutePath().normalize();

    @TempDir
    Path dataDir;

    private ZcodeInstanceManager manager() {
        return new ZcodeInstanceManager(dataDir.resolve("zcode/instances"));
    }

    @Test
    void launchParsesThePortPassesArgumentsAndStopsCleanly() throws Exception {
        NodeDshStub.assumeNode();
        ZcodeInstanceManager manager = manager();
        ZcodeInstance instance = manager.create("lab", null, null);
        Path argsFile = dataDir.resolve("args.json");

        ZcodeRuntime.Status status = ZcodeRuntime.launch(manager, instance,
                Map.of("FAKE_ZCODE_ARGS_FILE", argsFile.toString()), "node", PACKAGE);
        assertEquals(ZcodeRuntime.State.RUNNING, status.state(), "status: " + status);

        // The announced port was parsed from the `Local:` line and persisted
        // into the manifest (the stub picks a random one above 20000 for `--port 0`).
        ZcodeInstance updated = manager.find(instance.id());
        assertNotNull(updated);
        assertTrue(updated.lastPort() >= 20000 && updated.lastPort() < 40000,
                "the port must come from the stub's Local: line: " + updated.lastPort());

        // The pass-through: --token, --workspace and --port 0 travel in argv,
        // and the data directory lands in ZCODE_DATA_BASE_DIR.
        JsonObject recorded = JsonParser.parseString(Files.readString(argsFile)).getAsJsonObject();
        JsonArray argv = recorded.getAsJsonArray("argv");
        List<String> arguments = new java.util.ArrayList<>();
        argv.forEach(element -> arguments.add(element.getAsString()));
        assertTrue(arguments.contains("--web"), "argv: " + arguments);
        assertTrue(arguments.contains("--no-open"), "argv: " + arguments);
        assertTrue(arguments.contains("--host"), "argv: " + arguments);
        assertEquals("0.0.0.0", valueAfter(arguments, "--host"));
        assertEquals("0", valueAfter(arguments, "--port"), "--port 0 asks the process to pick a free port");
        assertEquals(instance.token(), valueAfter(arguments, "--token"));
        assertEquals(manager.workspaceDirectory(instance).toString(), valueAfter(arguments, "--workspace"));
        assertEquals(manager.dataDirectory(instance).toString(), recorded.get("dataBaseDir").getAsString());

        // The log carries the readiness announcement for the tail endpoint.
        List<String> log = manager.tailLog(updated, 50);
        assertTrue(log.stream().anyMatch(line -> line.contains("ZCode Web is running")),
                "log tail: " + log);

        // A second launch while running changes nothing.
        ZcodeRuntime.Status again = ZcodeRuntime.launch(manager, updated,
                Map.of("FAKE_ZCODE_ARGS_FILE", argsFile.toString()), "node", PACKAGE);
        assertEquals(ZcodeRuntime.State.RUNNING, again.state());
        assertEquals(updated.lastPort(), manager.find(instance.id()).lastPort());

        ZcodeRuntime.stop(instance.id());
        assertEquals(ZcodeRuntime.State.STOPPED, ZcodeRuntime.status(instance.id()).state());
    }

    @Test
    void aProcessThatExitsEarlyLeavesErrorWithTheLogTail() throws Exception {
        NodeDshStub.assumeNode();
        Path broken = dataDir.resolve("broken-zcode");
        Files.createDirectories(broken.resolve("bin"));
        Files.writeString(broken.resolve("bin/zcode.mjs"),
                "console.log('boom: something is missing');\nprocess.exit(1);\n");

        ZcodeInstanceManager manager = manager();
        ZcodeInstance instance = manager.create("broken", null, null);
        ZcodeRuntime.Status status = ZcodeRuntime.launch(
                manager, instance, Map.of(), "node", broken);
        assertEquals(ZcodeRuntime.State.ERROR, status.state());
        assertNotNull(status.error());
        assertTrue(status.error().contains("boom: something is missing"),
                "the reason must carry the log tail: " + status.error());
    }

    private static @org.jetbrains.annotations.Nullable String valueAfter(List<String> arguments, String flag) {
        int index = arguments.indexOf(flag);
        return index >= 0 && index + 1 < arguments.size() ? arguments.get(index + 1) : null;
    }
}
