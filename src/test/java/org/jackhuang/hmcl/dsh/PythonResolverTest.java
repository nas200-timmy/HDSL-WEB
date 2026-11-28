/*
 * HDSL
 * Copyright (C) 2026  HDSL contributors
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
package org.jackhuang.hmcl.dsh;

import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PythonResolverTest {

    private static Path script(Path directory, String name, String body) throws Exception {
        Files.createDirectories(directory);
        Path file = directory.resolve(name);
        Files.writeString(file, "#!/bin/sh\n" + body + "\n");
        file.toFile().setExecutable(true);
        return file;
    }

    @Test
    public void windowsStoreStubIsRejected() {
        assertTrue(PythonResolver.isStub(
                Path.of("C:\\Users\\timmy\\AppData\\Local\\Microsoft\\WindowsApps\\python.exe"),
                OperatingSystem.WINDOWS, "C:\\Windows"));
        assertTrue(PythonResolver.isStub(Path.of("C:\\Windows\\py.exe"),
                OperatingSystem.WINDOWS, "C:\\Windows"));
        assertTrue(PythonResolver.isStub(Path.of("C:\\Windows\\System32\\python3.exe"),
                OperatingSystem.WINDOWS, "C:\\Windows"));
        assertTrue(!PythonResolver.isStub(Path.of("C:\\Python312\\python.exe"),
                OperatingSystem.WINDOWS, "C:\\Windows"));
        // The same string on Linux is just a directory name.
        assertTrue(!PythonResolver.isStub(
                Path.of("C:\\Windows\\py.exe"), OperatingSystem.LINUX, "C:\\Windows"));
    }

    @Test
    public void candidatesKeepPathOrderAndSkipStubs() throws Exception {
        Path first = Path.of("/opt/py1");
        Path second = Path.of("/opt/py2");
        Path stub = Path.of("/mnt/c/Users/x/AppData/Local/Microsoft/WindowsApps");
        String path = String.join(File.pathSeparator,
                first.toString(), stub.toString(), second.toString());

        List<Path> found = PythonResolver.candidates(path, OperatingSystem.WINDOWS, "C:\\Windows",
                candidate -> candidate.getFileName().toString().equals("python.exe"));

        assertEquals(List.of(first.resolve("python.exe"), second.resolve("python.exe")), found);
    }

    @Test
    public void aBrokenInterpreterYieldsNoChoice() throws Exception {
        Path fake = script(scratch(), "python-broken", "echo not json");
        assertNull(PythonResolver.probe(PythonResolver.Origin.SYSTEM, fake));
    }

    @Test
    public void anOldInterpreterIsRejected() throws Exception {
        Path fake = script(scratch(), "python-old",
                "echo '{\"v\":[3,7,9],\"exe\":\"/usr/bin/python3\",\"venv\":true,\"em\":false}'");
        assertNull(PythonResolver.probe(PythonResolver.Origin.SYSTEM, fake));
    }

    @Test
    public void aProbeResultCarriesVersionAndCapabilities() throws Exception {
        Path fake = script(scratch(), "python-good",
                "echo '{\"v\":[3,12,3],\"exe\":\"/nonexistent/python3\",\"venv\":true,\"em\":false}'");
        PythonResolver.Interpreter interpreter =
                PythonResolver.probe(PythonResolver.Origin.SYSTEM, fake);
        assertNotNull(interpreter);
        assertEquals(PythonResolver.Origin.SYSTEM, interpreter.origin());
        assertEquals("3.12.3", interpreter.version());
        assertTrue(interpreter.venv());
        assertTrue(!interpreter.externallyManaged());
        assertEquals(fake, interpreter.executable());
    }

    @Test
    public void theReportedExecutableWinsWhenItExists(@TempDir Path dir) throws Exception {
        Path real = script(dir.resolve("real"), "python3", "exit 0");
        Path wrapper = script(dir, "python3-wrapper",
                "echo '{\"v\":[3,11,4],\"exe\":\"" + real + "\",\"venv\":false,\"em\":true}'");
        PythonResolver.Interpreter interpreter =
                PythonResolver.probe(PythonResolver.Origin.SYSTEM, wrapper);
        assertNotNull(interpreter);
        assertEquals(real, interpreter.executable());
        assertTrue(interpreter.externallyManaged());
    }

    @Test
    public void locateFindsAnAbsolutePathAndAMissingOneIsNull(@TempDir Path dir) throws Exception {
        Path fake = script(dir, "python3", "exit 0");
        assertEquals(fake, PythonResolver.locate(fake.toString()));
        assertNull(PythonResolver.locate(dir.resolve("absent").toString()));
        assertNull(PythonResolver.locate("   "));
    }

    @Test
    public void anUnusableOriginIsNotUsable() {
        PythonResolver.Interpreter none = new PythonResolver.Interpreter(
                PythonResolver.Origin.NONE, null, "", false, false, "nothing");
        assertTrue(!none.usable());
        assertTrue(none.describe().startsWith("none ("));
    }

    /// A scratch directory for the fake interpreters, removed with the JVM.
    private static Path scratchDirectory;

    private static Path scratch() throws Exception {
        if (scratchDirectory == null) {
            scratchDirectory = Files.createTempDirectory("python-resolver-test");
            scratchDirectory.toFile().deleteOnExit();
        }
        return scratchDirectory;
    }

    @Test
    public void policyValuesAreParsedAndUnknownMeansAuto() {
        assertEquals(PythonResolver.Policy.SYSTEM_ONLY,
                PythonResolver.parsePolicy("system-only"));
        assertEquals(PythonResolver.Policy.SYSTEM_ONLY, PythonResolver.parsePolicy("host"));
        assertEquals(PythonResolver.Policy.MANAGED, PythonResolver.parsePolicy("managed"));
        assertEquals(PythonResolver.Policy.AUTO, PythonResolver.parsePolicy("auto"));
        assertEquals(PythonResolver.Policy.AUTO, PythonResolver.parsePolicy(null));
        assertEquals(PythonResolver.Policy.AUTO, PythonResolver.parsePolicy("whatever"));
    }

    @Test
    public void aVersionIsMatchedByMajorAndMinorOnly() {
        assertTrue(PythonResolver.matchesVersion("3.12.16", "3.12"));
        assertTrue(PythonResolver.matchesVersion("3.12", "3.12"));
        assertTrue(!PythonResolver.matchesVersion("3.13.5", "3.12"));
        assertTrue(!PythonResolver.matchesVersion("", "3.12"));
    }

    @Test
    public void aPinnedVersionKeepsItsMajorMinor() {
        assertEquals("3.12", PythonResolver.majorMinor("3.12.16"));
        assertEquals("3.12", PythonResolver.majorMinor("3.12"));
        assertEquals("3.14", PythonResolver.majorMinor("3.14.0"));
    }

    @Test
    public void applyToLeavesAChildWithItsOwnInterpreterAlone(@TempDir Path dir) throws Exception {
        Path own = dir.resolve("python3");
        Files.writeString(own, "#!/bin/sh\nexit 0\n");
        own.toFile().setExecutable(true, false);
        Map<String, String> environment = new java.util.HashMap<>();
        environment.put("PATH", dir.toString());

        PythonResolver.applyTo(environment);

        assertTrue(!environment.containsKey("PYTHON"), environment.toString());
        assertEquals(dir.toString(), environment.get("PATH"));
    }

    @Test
    public void applyToFillsAGapWhenNoInterpreterIsVisible() {
        org.junit.jupiter.api.Assumptions.assumeTrue(PythonResolver.resolve().usable());
        Map<String, String> environment = new java.util.HashMap<>();
        environment.put("PATH", "/nonexistent");

        PythonResolver.applyTo(environment);

        assertTrue(environment.containsKey("PYTHON"), environment.toString());
    }
}
