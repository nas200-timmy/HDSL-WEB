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

import org.jackhuang.hmcl.util.platform.Architecture;
import org.jackhuang.hmcl.util.platform.Executables;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// Claude Web's interactive terminal **is** the Claude Code CLI it drives.
///
/// The workbench is a front end: the agent runs as a CLI child process, either
/// its own engine (`claude-web-engine`, a Claude Code fork that also speaks
/// OpenAI/Gemini providers) or the official binary. The two are not
/// interchangeable in practice — a provider configured in the browser only
/// works on the engine — so the caller picks one and this adapter never
/// substitutes the other.
///
/// The entry points mirror upstream `server/src/claude-exe.ts`:
///
/// - engine: `<release>/node_modules/claude-web-engine/dist/cli-node.js`, run
///   with `node` (the package ships a JS entry, not a `bin`);
/// - official: the SDK's platform package
///   `@anthropic-ai/claude-agent-sdk-<platform>-<arch>/claude[.exe]`, else a
///   `claude` on PATH (a global npm install).
@NotNullByDefault
public final class ClaudeWebTui implements BrandTui {

    /// The brand id used by the protocol, tickets and the audit trail.
    public static final String BRAND = "claude-web";

    /// The community engine bundled with the workbench. Preferred default: the
    /// browser's provider configuration was written for it.
    public static final String COMMUNITY = "community";

    /// The official Claude Code binary (Anthropic account only).
    public static final String OFFICIAL = "official";

    /// The engine's entry inside its package.
    static final String ENGINE_ENTRY = "node_modules/claude-web-engine/dist/cli-node.js";

    /// One kernel the instance can be started on.
    ///
    /// @param id        `community` or `official`
    /// @param label     what the picker shows
    /// @param available whether an entry point was found
    /// @param detail    the resolved path, or why it is missing
    public record Kernel(String id, String label, boolean available, @Nullable String detail) {
    }

    private final @Nullable String kernel;

    public ClaudeWebTui(@Nullable String kernel) {
        this.kernel = kernel;
    }

    /// The kernels this installation can start, community first.
    public static List<Kernel> kernels(Path packageDir) {
        List<Kernel> kernels = new ArrayList<>(2);
        kernels.add(probe(COMMUNITY, "社区引擎（claude-web-engine）", packageDir));
        kernels.add(probe(OFFICIAL, "官方 Claude Code", packageDir));
        return List.copyOf(kernels);
    }

    /// The kernel to use when the request names none: the community engine when
    /// it is there (providers configured in the browser keep working), else the
    /// official binary.
    public static @Nullable String defaultKernel(Path packageDir) {
        for (Kernel kernel : kernels(packageDir)) {
            if (kernel.available()) {
                return kernel.id();
            }
        }
        return null;
    }

    @Override
    public TerminalTarget target(Path packageDir, Path home, Path workspace,
                                 String instanceId, @Nullable String resumeSession) throws IOException {
        String requested = kernel == null || kernel.isBlank() ? defaultKernel(packageDir) : kernel;
        if (requested == null) {
            throw new IOException("Claude Code is not installed in this release: neither claude-web-engine"
                    + " nor the official binary was found (release " + packageDir + ")");
        }
        Kernel chosen = null;
        for (Kernel candidate : kernels(packageDir)) {
            if (candidate.id().equals(requested)) {
                chosen = candidate;
                break;
            }
        }
        if (chosen == null) {
            throw new IOException("unknown Claude Code kernel \"" + requested + "\" (expected "
                    + COMMUNITY + " or " + OFFICIAL + ")");
        }
        if (!chosen.available() || chosen.detail() == null) {
            throw new IOException(chosen.label() + " is not available: " + chosen.detail());
        }
        Path entry = Path.of(chosen.detail());

        Map<String, String> environment = new HashMap<>(BrandTui.environment(home));
        List<String> command = new ArrayList<>();
        if (isJavaScript(entry)) {
            // The engine is a JS entry: without node there is nothing to run,
            // so a missing runtime fails here rather than inside the PTY.
            Path node = requireNode();
            command.add(node.toString());
            environment.put("PATH", node.getParent() + File.pathSeparator
                    + environment.getOrDefault("PATH", ""));
        } else {
            // A native binary (the official one) does not need node; put it on
            // the child's PATH when it happens to be there, but never fail.
            Executables.resolve("node").ifPresent(node ->
                    environment.put("PATH", node.getParent() + File.pathSeparator
                            + environment.getOrDefault("PATH", "")));
        }
        command.add(entry.toString());
        return new TerminalTarget(BRAND, instanceId, command, workspace, Map.copyOf(environment));
    }

    // ------------------------------------------------------------------ probe --

    private static Kernel probe(String id, String label, Path packageDir) {
        try {
            Path entry = id.equals(COMMUNITY) ? communityEntry(packageDir) : officialEntry(packageDir);
            return new Kernel(id, label, true, entry.toAbsolutePath().normalize().toString());
        } catch (IOException e) {
            return new Kernel(id, label, false, e.getMessage());
        }
    }

    /// `node_modules/claude-web-engine/dist/cli-node.js`, exactly as upstream
    /// resolves it — the package has no `bin` field to read.
    private static Path communityEntry(Path packageDir) throws IOException {
        Path entry = packageDir.resolve(ENGINE_ENTRY);
        if (!Files.isRegularFile(entry)) {
            throw new IOException("claude-web-engine is not installed (missing " + entry + ")");
        }
        return entry;
    }

    /// The official binary: the SDK's platform package inside the release, else
    /// a `claude` on PATH.
    private static Path officialEntry(Path packageDir) throws IOException {
        Path bundled = packageDir.resolve("node_modules")
                .resolve(officialPackageName())
                .resolve(OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS ? "claude.exe" : "claude");
        if (Files.isRegularFile(bundled)) {
            return bundled;
        }
        return Executables.resolve("claude")
                .orElseThrow(() -> new IOException("no " + officialPackageName() + " in the release and no"
                        + " `claude` on PATH (install it with: npm i -g @anthropic-ai/claude-code)"));
    }

    /// The npm package that carries the official binary for this platform:
    /// `@anthropic-ai/claude-agent-sdk-<platform>-<arch>`.
    static String officialPackageName() {
        return "@anthropic-ai/claude-agent-sdk-" + nodePlatform() + "-" + nodeArch();
    }

    static String nodePlatform() {
        return switch (OperatingSystem.CURRENT_OS) {
            case WINDOWS -> "win32";
            case MACOS -> "darwin";
            default -> "linux";
        };
    }

    static String nodeArch() {
        return switch (Architecture.CURRENT_ARCH) {
            case X86_64 -> "x64";
            case ARM64 -> "arm64";
            case X86 -> "ia32";
            default -> "arm";
        };
    }

    private static boolean isJavaScript(Path entry) {
        String name = entry.getFileName().toString();
        return name.endsWith(".js") || name.endsWith(".mjs") || name.endsWith(".cjs");
    }

    private static Path requireNode() throws IOException {
        return Executables.resolve("node")
                .orElseThrow(() -> new IOException("node is not on PATH; install Node before opening a terminal"));
    }
}