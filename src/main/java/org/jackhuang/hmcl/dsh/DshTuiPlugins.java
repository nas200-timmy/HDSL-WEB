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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Optional;

/// The community's terminal interfaces for DeepSeek Harness, as a curated list.
///
/// [DshSurface] has no TUI member, and there is no `dsh-tui` subcommand either: a
/// terminal interface comes from a plugin, installed into a profile and started by
/// booting that profile (`dsh --profile <name>`; upstream also accepts the profile
/// name as a bare word, `dsh <name>`). What the list pins, therefore, is not only
/// *which* package — it is **which profile**, because that is the part that decides
/// whether the instance starts at all:
///
/// **A terminal plugin never goes into the web profile.** Both are *app* plugins: each
/// parses the whole argv with its own grammar, and upstream does not arbitrate between
/// two of them. Installed together they collide on the first flag the other one owns —
/// the community's own report is `error: unknown option '--no-open'` — so the instance
/// does not merely lose its terminal, it stops starting. That is why every entry names
/// the profile its own documentation installs into, why [refuseAppProfile] refuses the
/// profiles the harness ships apps for, and why a terminal is always started on the
/// plugin's own profile rather than the instance's.
///
/// Three things about an entry are decisions rather than descriptions:
///
/// - the **pinned version**. The panel installs `name@version` and not `name`, so a
///   profile keeps working when the author publishes something new;
/// - the **profile**: what the plugin's own README says to use (`tui`, `pi-tui`,
///   `dsh-tui`), never the instance's `web`;
/// - the **bin name**, when the package ships one. Everything else is a pure bundle and
///   is started by booting its profile, which is what its own documentation shows.
///
/// The list is a constant rather than a fetch: it is short, it changes when an author
/// publishes, and a page that had to reach the network to say what a terminal plugin is
/// would be unable to explain itself offline.
@NotNullByDefault
public final class DshTuiPlugins {

    /// The profile the panel's own instances boot, and the one a terminal plugin must
    /// not be installed into.
    public static final String WEB_PROFILE = "web";

    /// One terminal interface offered for installation.
    ///
    /// @param id          a stable identifier, used by the interface and by tests
    /// @param packageName the npm package name
    /// @param version     the version the panel pins
    /// @param displayName what a list row shows
    /// @param repository  the source repository, or `null` when the package names none
    /// @param license     the package's licence, as its manifest declares it
    /// @param description one sentence on what it is
    /// @param bin         the executable the package ships, or `null` for a pure bundle
    /// @param profile     the profile it is installed into and started from
    public record Entry(
            String id,
            String packageName,
            String version,
            String displayName,
            @Nullable String repository,
            String license,
            String description,
            @Nullable String bin,
            String profile) {

        /// Returns the specification the installer is given.
        ///
        /// The version is pinned, so an installation is reproducible and a later
        /// publication by the author cannot change what an instance runs.
        ///
        /// @return the `<package>@<version>` specification
        public String spec() {
            return packageName + "@" + version;
        }

        /// Reports whether this entry ships its own entry point.
        ///
        /// @return whether the package has a bin
        public boolean hasExecutable() {
            return bin != null && !bin.isBlank();
        }
    }

    /// The entries, in the order the interface lists them: the one developed against the
    /// harness's own releases first, then the rest by package name.
    ///
    /// Every field here was read from the package's own `latest` document on npm
    /// (version, licence, `bin`, `repository`) and from its README (the profile), not
    /// from a description of it.
    public static final @Unmodifiable List<Entry> ENTRIES = List.of(
            new Entry(
                    "deepseek-harness-tui",
                    "@deepseek-harness-tui/dsh-tui",
                    "0.17.0",
                    "dsh-TUI",
                    "https://github.com/ccch1mneyyy/dsh-TUI",
                    "MIT",
                    "社区主力终端界面：像素鲸鱼头部、流式思考、工具卡片、会话恢复与中英界面。"
                            + "自述 zero core changes —— 只消费 dsh 的公开导出，卸载不留补丁。",
                    "dsh-tui",
                    "dsh-tui"),
            new Entry(
                    "tianshu-tui",
                    "@huiliyi37/dsh-tianshu-tui",
                    "1.0.0-rc.2",
                    "dsh-tianshu-tui（天枢）",
                    "https://github.com/huiliyi37/dsh-tianshu-tui",
                    "Apache-2.0",
                    "天枢终端面板：自研极简 ANSI 渲染引擎、16+ 主题、slash 命令与选择器、"
                            + "LSP 诊断与图像/视觉桥接。",
                    null,
                    "tui"),
            new Entry(
                    "tomowang-tui",
                    "@tomowang/dsh-tui",
                    "0.10.2",
                    "@tomowang/dsh-tui",
                    "https://github.com/tomowang/dsh-tui",
                    "MIT",
                    "树外 mode bundle：与内置的 dsh-web-app / dsh-headless 同构地叠在 dsh-base 上，"
                            + "把 agent 从浏览器搬到终端。",
                    null,
                    "tui"),
            new Entry(
                    "pi-tui",
                    "@xmoon76/dsh-pi-tui",
                    "0.5.3",
                    "dsh-pi-tui",
                    "https://github.com/XMoon/dsh-pi-tui",
                    "MIT",
                    "基于 Pi TUI 的终端前端，纯 bundle：流式对话、工具调用、会话管理、Subagent 与审批。",
                    null,
                    "pi-tui"));

    private DshTuiPlugins() {
    }

    /// Looks an entry up by package name.
    ///
    /// @param packageName the npm package name, or `null`
    /// @return the entry, or empty when the name is not one of these
    public static Optional<Entry> find(@Nullable String packageName) {
        if (packageName == null || packageName.isBlank()) {
            return Optional.empty();
        }
        String wanted = packageName.trim();
        for (Entry entry : ENTRIES) {
            if (entry.packageName().equalsIgnoreCase(wanted)) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    /// Reports whether a profile's declared dependencies hold this entry.
    ///
    /// Read from the profile's own manifest, which is why it is asked per entry: the
    /// profiles differ (three of these four use three different ones), and a plugin
    /// installed into its own profile is invisible in the instance's web profile.
    ///
    /// @param entry        the entry
    /// @param dependencies the profile's dependency names
    /// @return whether it is installed there
    public static boolean isInstalled(Entry entry, Iterable<String> dependencies) {
        for (String dependency : dependencies) {
            if (dependency != null && entry.packageName().equalsIgnoreCase(dependency.trim())) {
                return true;
            }
        }
        return false;
    }

    /// Returns the package a specification names, without its version.
    ///
    /// @param spec the specification, as typed or as the catalogue pins it
    /// @return the package name, or null when the specification names no package
    public static @Nullable String packageNameOf(@Nullable String spec) {
        if (spec == null) {
            return null;
        }
        String raw = spec.trim();
        if (raw.isEmpty()) {
            return null;
        }
        // `@scope/name@1.2.3` keeps its leading `@`; anything from the second one on is
        // the version. A bare `name@1.2.3`, a repository (`github:o/n`), a file and an
        // archive URL all come out as themselves, and simply match no entry.
        int at = raw.indexOf('@', raw.startsWith("@") ? 1 : 0);
        return at <= 0 ? raw : raw.substring(0, at);
    }

    /// Refuses to install a terminal plugin into a profile the harness ships an app for.
    ///
    /// The refusal is the whole point of this class's `profile` field: a terminal plugin
    /// and the web app are both *app* plugins, each parsing the same argv with its own
    /// grammar while upstream arbitrates nothing between them, so the pair does not boot
    /// — the community's own report is `error: unknown option '--no-open'`. A refusal
    /// that names the profile to use instead is worth more than an install that bricks
    /// the instance.
    ///
    /// Asked of **installs**, not removals: a profile that somehow holds one of these is
    /// fixed by taking it out, and refusing the removal would leave it broken.
    ///
    /// @param spec    the specification being installed
    /// @param profile the profile it would be installed into
    /// @throws DshException when the specification is a known terminal plugin and the
    ///                       profile boots one of the harness's own apps
    public static void refuseAppProfile(@Nullable String spec, @Nullable String profile) throws DshException {
        if (profile == null || profile.isBlank()) {
            return;
        }
        // Named surfaces only: `DshSurface.ofProfile` answers OTHER for a profile
        // somebody made up, which is exactly the profile a terminal plugin belongs in.
        DshSurface surface = DshSurface.ofProfile(profile);
        if (surface == DshSurface.OTHER) {
            return;
        }
        String name = packageNameOf(spec);
        Optional<Entry> entry = find(name);
        if (entry.isEmpty()) {
            return;
        }
        throw new DshException("TUI 插件不能装进 " + surface.profileName() + " profile：它和 "
                + surface.appBundle() + " 都是 app 插件，各自按自己的语法解析同一份 argv，"
                + "上游不仲裁，装在一起启动就会失败（社区实测 `error: unknown option '--no-open'`）。"
                + "请装进 " + entry.get().packageName() + " 自己的 profile（" + entry.get().profile() + "）。");
    }
}