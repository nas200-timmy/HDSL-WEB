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

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class DshDoctorBootstrapTest {

    @Test
    public void aSystemInterpreterShowsItsOriginAndCapabilities() {
        String line = DshDoctor.formatPython(new PythonResolver.Interpreter(
                PythonResolver.Origin.SYSTEM, Path.of("/usr/bin/python3"), "3.11.2",
                true, false, ""));
        assertTrue(line.contains("3.11.2"), line);
        assertTrue(line.contains("[system]"), line);
        assertTrue(line.contains("venv=yes"), line);
        assertTrue(line.contains("pep668=no"), line);
    }

    @Test
    public void noInterpreterSaysSo() {
        String line = DshDoctor.formatPython(new PythonResolver.Interpreter(
                PythonResolver.Origin.NONE, null, "", false, false, "no usable python3"));
        assertTrue(line.startsWith("none ("), line);
        assertTrue(line.contains("no usable python3"), line);
    }

    @Test
    public void aHostThatProvidesTheClosureSaysNothingWasDownloaded() {
        String line = DshDoctor.formatClosure(null, List.of(), true);
        assertTrue(line.contains("nothing downloaded"), line);
    }

    @Test
    public void aMissingLibraryIsShouted() {
        String line = DshDoctor.formatClosure(null,
                List.of("libatomic.so.1", "libstdc++.so.6"), true);
        assertTrue(line.startsWith("MISSING libatomic.so.1"), line);
        assertTrue(line.contains("would not start"), line);
    }

    @Test
    public void windowsSaysTheClosureQuestionDoesNotApply() {
        String line = DshDoctor.formatClosure(null, List.of(), false);
        assertTrue(line.startsWith("not applicable"), line);
        assertTrue(line.contains("libstdc++/libatomic"), line);
    }

    @Test
    public void noToolchainIsNamed() {
        assertTrue(DshDoctor.formatToolchain(null).startsWith("none"), "");
    }

    @Test
    public void aHostToolchainNamesItsCc() {
        BuildToolchainManager.Toolchain host = new BuildToolchainManager.Toolchain(
                "linux", "x64", null, Path.of("/usr/bin"), true, false,
                List.of(new BuildToolchainManager.Tool("cc", "/usr/bin/cc", "12.2.0")),
                List.of(), Map.of(), "");
        String line = DshDoctor.formatToolchain(host);
        assertTrue(line.startsWith("host linux-x64"), line);
        assertTrue(line.contains("cc=/usr/bin/cc"), line);
    }

    @Test
    public void anIncompletePortableToolchainSaysWhatIsMissing() {
        BuildToolchainManager.Toolchain portable = new BuildToolchainManager.Toolchain(
                "linux", "x64", Path.of("/t/toolchains/zig"), Path.of("/t/toolchains/zig/bin"),
                false, true, List.of(), List.of("cc", "make"), Map.of(), "");
        String line = DshDoctor.formatToolchain(portable);
        assertTrue(line.startsWith("portable linux-x64"), line);
        assertTrue(line.contains("INCOMPLETE"), line);
        assertTrue(line.contains("cc, make"), line);
    }
}
