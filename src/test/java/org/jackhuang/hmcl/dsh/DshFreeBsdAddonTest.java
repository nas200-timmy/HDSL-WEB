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
package org.jackhuang.hmcl.dsh;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The FreeBSD escape hatch's only testable half on a non-FreeBSD machine: the
/// source patch. It is string surgery on somebody else's tree, so a renamed
/// anchor has to fail loudly rather than silently building an unpatched addon.
class DshFreeBsdAddonTest {

    private static Path fixture(Path root) throws IOException {
        Path nativeDir = root.resolve("packages/native/src");
        Files.createDirectories(nativeDir.resolve("runtime_context"));
        Files.createDirectories(nativeDir.resolve("runtime_probe"));
        Files.writeString(nativeDir.resolve("runtime_context/platform.cc"), """
                #elif defined(__linux__) && defined(__x86_64__)
                  return ReadLinuxX64CurrentV8Context(isolate, symbols);
                #elif defined(__linux__)
                  return UnsupportedContextReader("unsupported linux architecture");
                #else
                  return UnsupportedContextReader("unsupported platform");
                #endif
                """);
        Files.writeString(nativeDir.resolve("runtime_probe/platform.cc"), """
                #elif defined(__linux__) && defined(__x86_64__)
                  return ParseLinuxX64BuiltinModuleRequireGetterOffset(getter);
                #else
                  return Result<GetterPattern>::Failure(Status::Failure(
                      ProbeStatus::kUnsupportedNoGetter, "unsupported platform"));
                #endif
                """);
        return root;
    }

    private static int count(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    @Test
    void patchesBothSwitchesAndIsIdempotent(@TempDir Path dir) throws Exception {
        Path root = fixture(dir);
        List<String> log = new ArrayList<>();

        DshFreeBsdAddon.patch(root, log::add);
        String context = Files.readString(root.resolve("packages/native/src/runtime_context/platform.cc"));
        String probe = Files.readString(root.resolve("packages/native/src/runtime_probe/platform.cc"));
        assertTrue(context.contains("defined(__FreeBSD__) && defined(__x86_64__)"));
        assertTrue(context.contains("ReadDirectCurrentV8Context(\"freebsd-x64\""));
        assertTrue(probe.contains("defined(__FreeBSD__) && defined(__x86_64__)"));
        assertEquals(2, log.size(), "one line per patched file: " + log);

        // A second run must not add the branch twice (the panel can be asked twice).
        DshFreeBsdAddon.patch(root, log::add);
        assertEquals(1, count(context, "defined(__FreeBSD__)"));
        assertEquals(1, count(Files.readString(root.resolve("packages/native/src/runtime_probe/platform.cc")),
                "defined(__FreeBSD__)"));
    }

    @Test
    void aRenamedAnchorFailsLoudly(@TempDir Path dir) throws Exception {
        Path root = fixture(dir);
        Path context = root.resolve("packages/native/src/runtime_context/platform.cc");
        Files.writeString(context, "#elif defined(__linux__)\n  return nothing;\n");
        assertThrows(IOException.class, () -> DshFreeBsdAddon.patch(root, line -> {
        }));
    }
}
