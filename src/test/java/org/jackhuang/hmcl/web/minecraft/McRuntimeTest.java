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

import org.jackhuang.hmcl.web.terminal.TerminalManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McRuntimeTest {

    @TempDir
    Path dir;

    private McInstanceManager manager;
    private McFeatureState feature;
    private TerminalManager terminals;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"),
                "the PTY implementation is POSIX-only");
        manager = new McInstanceManager(dir.resolve("minecraft").resolve("instances"));
        feature = new McFeatureState(dir.resolve("feature.json"));
        feature.enable(consent());
        terminals = new TerminalManager();
    }

    @AfterEach
    void tearDown() {
        if (terminals != null) {
            terminals.close();
        }
    }

    private static McConsent consent() {
        return new McConsent(3, "sha256:terms", "sha256:eula",
                "2026-10-29T00:58:00Z", "2026-10-29T01:00:00Z", "2026-10-29T01:00:00Z",
                "tester", "127.0.0.1", "scroll+checkbox", true, "1.4.0", "UA", "abc1234", null);
    }

    private static String javaBin() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", name).toString();
    }

    private McInstance installedInstance(String name, String version) throws Exception {
        McInstance instance = manager.create(name, version, 25565, 25575, 1024, "world", null);
        Files.writeString(manager.serverJar(instance), "");
        manager.acceptEula(instance);
        return instance;
    }

    private McRuntime runtime(String... mode) {
        List<String> command = new java.util.ArrayList<>(List.of(
                javaBin(), "-cp", System.getProperty("java.class.path"), FakeMcServer.class.getName()));
        command.addAll(List.of(mode));
        return new McRuntime(manager, terminals, feature, instance -> command);
    }

    private static McRuntime.Status await(McRuntime runtime, String id, McRuntime.State expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        McRuntime.Status status = runtime.status(id);
        while (System.nanoTime() < deadline) {
            status = runtime.status(id);
            if (status.state() == expected) {
                return status;
            }
            Thread.sleep(50);
        }
        return status;
    }

    @Test
    void launchWaitsForTheReadinessLineAndStopEndsTheProcess() throws Exception {
        McInstance instance = installedInstance("survival", "1.21.4");
        McRuntime runtime = runtime();

        runtime.launch(instance.id());
        assertEquals(McRuntime.State.RUNNING, await(runtime, instance.id(), McRuntime.State.RUNNING).state());
        assertNotNull(runtime.console(instance.id()).orElse(null));

        runtime.stop(instance.id());
        assertEquals(McRuntime.State.STOPPED, await(runtime, instance.id(), McRuntime.State.STOPPED).state());
        assertTrue(runtime.console(instance.id()).isEmpty());
    }

    @Test
    void launchingWhileLockedIsRefused() throws Exception {
        McInstance instance = installedInstance("locked", "1.21.4");
        feature.stopFeature();

        McException refused = assertThrows(McException.class, () -> runtime().launch(instance.id()));
        assertTrue(refused.getMessage().contains("locked"), refused.getMessage());
    }

    @Test
    void aServerThatCrashesBeforeReadyIsMarkedFailed() throws Exception {
        McInstance instance = installedInstance("crashy", "1.21.4");
        McRuntime runtime = runtime("crash");

        runtime.launch(instance.id());
        McRuntime.Status status = await(runtime, instance.id(), McRuntime.State.FAILED);
        assertEquals(McRuntime.State.FAILED, status.state());
        assertNotNull(status.error());
    }

    @Test
    void aMissingJarOrEulaIsRefusedBeforeAnythingIsSpawned() throws Exception {
        McInstance instance = manager.create("bare", "1.21.4", 25566, 25576, 1024, "world", null);
        McRuntime runtime = runtime();

        McException noJar = assertThrows(McException.class, () -> runtime.launch(instance.id()));
        assertTrue(noJar.getMessage().contains("server.jar"), noJar.getMessage());

        Files.writeString(manager.serverJar(instance), "");
        McException noEula = assertThrows(McException.class, () -> runtime.launch(instance.id()));
        assertTrue(noEula.getMessage().contains("EULA"), noEula.getMessage());
        assertTrue(runtime.console(instance.id()).isEmpty());
    }

    @Test
    void theConsoleIsAttachOnly() throws Exception {
        McInstance instance = installedInstance("console", "1.21.4");
        McRuntime runtime = runtime();
        assertTrue(runtime.console(instance.id()).isEmpty(), "opening the console must not start a server");

        runtime.launch(instance.id());
        await(runtime, instance.id(), McRuntime.State.RUNNING);
        // A second launch is refused instead of attaching a second process.
        assertThrows(McException.class, () -> runtime.launch(instance.id()));

        runtime.kill(instance.id());
        assertEquals(McRuntime.State.STOPPED, await(runtime, instance.id(), McRuntime.State.STOPPED).state());
    }
}