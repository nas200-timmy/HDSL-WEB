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
package org.jackhuang.hmcl.web.terminal;

import org.jackhuang.hmcl.dsh.DshException;
import org.jackhuang.hmcl.dsh.DshHomeMode;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshNodeRuntime;
import org.jackhuang.hmcl.dsh.DshTuiPlugins;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// The terminal is a plugin on **its own profile**, and the instance's web profile is
/// never it: a terminal interface and `dsh-web-app` are both app plugins, and sharing a
/// profile with a second app plugin is what stops the instance from starting. What is
/// started here is the plugin's own executable with Node, or its profile booted the
/// upstream way (`dsh --profile <name>`).
class DshTuiTest {

    /// The instance runs on a home of its own, so nothing here reads or writes the
    /// launcher's real one.
    private static DshInstance instanceWith(Path home, String profile) {
        return new DshInstance("tui-test", "0.1.7-rc.1", profile, home.resolve("workspace").toString(),
                null, DshHomeMode.CUSTOM, home.toString(), List.of(), Map.of(), null, null, null, 0, 0L);
    }

    /// Writes what the installer's own `dsh plugin add` leaves behind: the profile manifest,
    /// **and the files that came with it**.
    ///
    /// A manifest on its own is not an installed plugin — it is the state an install leaves
    /// when the fetch fails after the dependency was written, which is exactly the state the
    /// launcher refuses instead of starting. So every package also gets the directory pnpm
    /// would fill under `node_modules/<name>`, and the file its manifest names as a bin:
    /// a package that ships an executable is started through that file.
    private static void install(DshInstance instance, String profile, String... packages) throws Exception {
        declareOnly(instance, profile, packages);
        Path nodeModules = instance.homeDirectory().resolve("profiles").resolve(profile).resolve("node_modules");
        for (String name : packages) {
            layPackage(nodeModules.resolve(name), name);
        }
    }

    /// The files one installed package has: a manifest that declares a bin named after the
    /// package, and the script it points at.
    private static void layPackage(Path packageDirectory, String name) throws Exception {
        String bin = name.substring(name.lastIndexOf('/') + 1);
        Files.createDirectories(packageDirectory.resolve("lib"));
        Files.writeString(packageDirectory.resolve("lib/main.js"), "// " + name + "\n");
        Files.writeString(packageDirectory.resolve("package.json"),
                "{\"name\":\"" + name + "\",\"version\":\"1.0.0\",\"bin\":{\"" + bin + "\":\"lib/main.js\"}}");
    }

    /// A manifest with a dependency and nothing on disk: what an install that could not
    /// fetch leaves, and what the launcher must not treat as a terminal.
    private static void declareOnly(DshInstance instance, String profile, String... packages) throws Exception {
        StringBuilder dependencies = new StringBuilder();
        for (String name : packages) {
            if (!dependencies.isEmpty()) {
                dependencies.append(',');
            }
            dependencies.append('"').append(name).append("\":\"1.0.0\"");
        }
        Path profileDirectory = instance.homeDirectory().resolve("profiles").resolve(profile);
        Files.createDirectories(profileDirectory);
        Files.writeString(profileDirectory.resolve("package.json"),
                "{\"dependencies\":{" + dependencies + "}}");
    }

    @Test
    void anInstanceWithNoTerminalPluginIsRefusedWithWhereToGetOne(@TempDir Path home) throws Exception {
        DshInstance instance = instanceWith(home, "web");

        DshException refused = assertThrows(DshException.class, () -> DshTui.terminalPlugin(instance));

        assertTrue(refused.getMessage().contains("未安装终端界面插件"), refused.getMessage());
        assertTrue(refused.getMessage().contains("「安装」"), refused.getMessage());
        assertTrue(refused.getMessage().contains(DshTuiPlugins.ENTRIES.get(0).packageName()),
                "the refusal names what to install: " + refused.getMessage());
    }

    /// The hard constraint, pinned from the side that can be tested here: the same
    /// package written into the *web* profile is not a terminal plugin this launcher will
    /// start, because that profile is where the two app plugins collide.
    @Test
    void aTerminalPluginInTheWebProfileIsNotInstalled(@TempDir Path home) throws Exception {
        DshInstance instance = instanceWith(home, "web");
        install(instance, "web", "@deepseek-harness-tui/dsh-tui");

        assertThrows(DshException.class, () -> DshTui.terminalPlugin(instance));
    }

    @Test
    void aPluginThatShipsItsOwnExecutableIsStartedWithNode(@TempDir Path home) throws Exception {
        DshInstance instance = instanceWith(home, "web");
        DshTuiPlugins.Entry plugin = DshTuiPlugins.ENTRIES.get(0);
        assertEquals("@deepseek-harness-tui/dsh-tui", plugin.packageName());
        assertEquals("dsh-tui", plugin.profile(), "the profile is the plugin's own, not the instance's");
        assertTrue(plugin.hasExecutable());
        install(instance, plugin.profile(), plugin.packageName());

        assertEquals(plugin.packageName(), DshTui.terminalPlugin(instance).packageName());

        // The package's own manifest names its entry; the file is what is started, and it
        // is looked for in the plugin's profile.
        Path packageDirectory = DshTui.profileNodeModules(instance, plugin.profile())
                .resolve(plugin.packageName());
        Files.createDirectories(packageDirectory.resolve("bin"));
        Files.writeString(packageDirectory.resolve("bin/dsh-tui.js"), "console.log('tui');\n");
        Files.writeString(packageDirectory.resolve("package.json"),
                "{\"name\":\"@deepseek-harness-tui/dsh-tui\",\"bin\":{\"dst\":\"bin/dsh-tui.js\",\"dsh-tui\":\"bin/dsh-tui.js\"}}");

        Path entry = DshTui.entryPoint(instance, plugin);
        assertEquals(packageDirectory.resolve("bin/dsh-tui.js").toAbsolutePath().normalize(), entry);

        DshNodeRuntime runtime = new DshNodeRuntime(Path.of("/opt/node/bin/node"), "22.19.0",
                null, null, null, null);
        assertEquals(List.of("/opt/node/bin/node", entry.toString(), "--resume", "sess-1"),
                DshTui.executableCommand(runtime, entry, "sess-1"));
        assertEquals(List.of("/opt/node/bin/node", entry.toString()),
                DshTui.executableCommand(runtime, entry, null),
                "no session to resume means no flag");
    }

    /// A profile that *declares* a bin-shipping plugin and does not hold its file is not a
    /// terminal, and booting it is exactly what leaves an empty screen: the harness starts,
    /// finds nothing to load, and dies before it can print. The declaration alone is
    /// therefore refused, and the refusal says which package and what to do about it —
    /// which is the difference between a user seeing "reinstall this" and watching a
    /// terminal flash.
    @Test
    void aDeclaredPluginWithNoFilesIsRefusedRatherThanBooted(@TempDir Path home) throws Exception {
        DshInstance instance = instanceWith(home, "web");
        DshTuiPlugins.Entry plugin = DshTuiPlugins.ENTRIES.get(0);
        assertTrue(plugin.hasExecutable());
        declareOnly(instance, plugin.profile(), plugin.packageName());

        assertNull(DshTui.entryPoint(instance, plugin), "there is no file to start");

        DshException refused = assertThrows(DshException.class, () -> DshTui.terminalPlugin(instance));
        assertTrue(refused.getMessage().contains(plugin.packageName()),
                "the refusal names the package: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("重新安装"),
                "the refusal says what to do about it: " + refused.getMessage());
    }

    /// A declared-but-missing entry does not shadow a working one: the list's order is a
    /// preference, not a promise that everything ahead of an entry is usable.
    @Test
    void aDeclaredButMissingEntryDoesNotShadowAWorkingOne(@TempDir Path home) throws Exception {
        DshInstance instance = instanceWith(home, "web");
        DshTuiPlugins.Entry preferred = DshTuiPlugins.ENTRIES.get(0);
        DshTuiPlugins.Entry bundle = DshTuiPlugins.find("@tomowang/dsh-tui").orElseThrow();
        declareOnly(instance, preferred.profile(), preferred.packageName());
        install(instance, bundle.profile(), bundle.packageName());

        assertEquals(bundle.packageName(), DshTui.terminalPlugin(instance).packageName(),
                "the plugin whose files are really there is what gets started");
    }

    /// pnpm writes `.bin/<tool>` as a shell shim; starting through it would make
    /// `/bin/sh` the root process of the terminal, so only a shim that resolves to
    /// the package's own script is taken.
    @Test
    void theShimIsTakenOnlyWhenItIsThePackagesOwnScript(@TempDir Path nodeModules) throws Exception {
        assumeTrue(OperatingSystem.CURRENT_OS != OperatingSystem.WINDOWS, "the refusal is about POSIX shims");

        Path bin = nodeModules.resolve(".bin");
        Files.createDirectories(bin);
        Path shim = bin.resolve("dsh-tui");
        Files.writeString(shim, "#!/bin/sh\nexec node \"$0/../lib/bin.js\" \"$@\"\n");
        Files.setPosixFilePermissions(shim, PosixFilePermissions.fromString("rwx------"));
        assertNull(DshTui.entryPoint(nodeModules, "@huiliyi37/dsh-tianshu-tui", "dsh-tui"),
                "a shell shim is not the entry to start");

        // The same name, linked to the script the package ships, is.
        Path script = nodeModules.resolve("tui-package/lib/bin.js");
        Files.createDirectories(script.getParent());
        Files.writeString(script, "console.log('tui');\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        Files.delete(shim);
        Files.createSymbolicLink(shim, script);

        assertEquals(script.toRealPath(), DshTui.entryPoint(nodeModules, "tui-package", "dsh-tui"));
    }

    @Test
    void aPureBundleIsStartedByBootingItsProfile(@TempDir Path home) throws Exception {
        DshInstance instance = instanceWith(home, "web");
        DshTuiPlugins.Entry plugin = DshTuiPlugins.find("@tomowang/dsh-tui").orElseThrow();
        assertEquals("tui", plugin.profile());
        assertFalse(plugin.hasExecutable(), "a pure bundle has no entry of its own");
        install(instance, plugin.profile(), plugin.packageName());

        assertEquals(plugin.packageName(), DshTui.terminalPlugin(instance).packageName());

        // The plan is the harness's own argv — interpreter, entry, `--profile <name>` —
        // and that is what is started; the profile in it is the plugin's, never `web`.
        List<String> plan = List.of("/opt/node/bin/node", "/data/dsh/lib/bin.js", "--profile", "tui");
        assertEquals("tui", DshTui.plannedProfile(plan));
        assertEquals(plan, DshTui.cliCommand(plan, null));
        assertEquals(List.of("/opt/node/bin/node", "/data/dsh/bin.js", "--profile", "tui", "--resume", "abc"),
                DshTui.cliCommand(List.of("/opt/node/bin/node", "/data/dsh/bin.js", "--profile", "tui"), "abc"));
        assertNull(DshTui.plannedProfile(List.of("/opt/node/bin/node", "/data/dsh/bin.js")));
    }

    /// The rule applies to the instance's own arguments too: `--profile web` in them
    /// would boot the web app over the terminal's profile, which is the combination that
    /// does not start.
    @Test
    void aPlanThatBootsTheWebProfileIsRefused() throws Exception {
        DshException refused = assertThrows(DshException.class, () -> DshTui.refuseWebProfile("web"));
        assertTrue(refused.getMessage().contains("--profile web"), refused.getMessage());
        assertTrue(refused.getMessage().contains("app 插件"), refused.getMessage());

        DshTui.refuseWebProfile("tui");
        DshTui.refuseWebProfile("pi-tui");
        DshTui.refuseWebProfile(null);
        DshTui.refuseWebProfile("");
    }

    @Test
    void theRecommendedPluginWinsWhenSeveralAreInstalled(@TempDir Path home) throws Exception {
        DshInstance instance = instanceWith(home, "web");
        DshTuiPlugins.Entry recommended = DshTuiPlugins.ENTRIES.get(0);
        // Each in its own profile — the profiles differ, and that is the point.
        install(instance, "tui", "@tomowang/dsh-tui");
        install(instance, recommended.profile(), recommended.packageName());

        assertEquals(recommended.packageName(), DshTui.terminalPlugin(instance).packageName(),
                "the list's order is the preference");
    }

    @Test
    void aPlanWithoutAnEntryIsRefused() {
        assertThrows(DshException.class, () -> DshTui.cliCommand(List.of("node"), null));
    }
}