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
package org.jackhuang.hmcl.web.auth;

import org.jackhuang.hmcl.dsh.DshProcessManager;
import org.jackhuang.hmcl.web.brand.BrandRuntime;
import org.jackhuang.hmcl.web.terminal.TerminalManager;
import org.jackhuang.hmcl.web.terminal.TerminalSession;
import org.jackhuang.hmcl.web.zcode.ZcodeRuntime;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/// Which instance a caller runs in, derived from the kernel rather than asked.
///
/// An agent grant used to carry a self-reported `instanceId`, and a caller that
/// got it wrong — or omitted it — could stop the very instance it was running
/// in. This class answers the same question from facts the caller cannot
/// rewrite: every instance's root process is a direct child of this panel, so
/// the process trees are disjoint, and the socket that carried a request names
/// exactly one of them.
///
/// The walk, all of it in `/proc`:
///
/// 1. `request.getRemotePort()` is the caller-side socket's local port. The
///    entry in `/proc/net/tcp` with that local port and the panel's port as its
///    remote port carries the caller socket's inode.
/// 2. The process whose `/proc/<pid>/fd` holds `socket:[inode]` is the caller —
///    the kernel sets that mapping, not the caller.
/// 3. Walking `/proc/<pid>/stat` parents upward, the first process that is a
///    known instance root names the caller's instance. Reaching this panel's
///    own pid, or losing the chain, means the caller is not inside any
///    instance: a host script, a detached relay, or a caller that deliberately
///    left its tree — and the answer is `null`, which the fatal-operation gate
///    reads as "unidentified", never as "privileged".
///
/// Only loopback callers from this network namespace can be identified at all;
/// everything else fails step 1 and reads the same `null`.
@NotNullByDefault
public final class PeerIdentity {

    /// One instance, as the fatal-operation gate compares it.
    ///
    /// `family` is the id-space owner — `dsh`, a brand id, or `zcode` — and
    /// matches the brand part of the terminal registry key.
    ///
    /// @param family      the instance's family
    /// @param instanceId  the instance's id
    public record Ref(String family, String instanceId) {

        /// The binding string a grant carries: `family/id`.
        ///
        /// @return the wire form
        public String wire() {
            return family + "/" + instanceId;
        }
    }

    /// The `/proc` surface this class reads, so the walk itself stays testable.
    public interface Proc {

        /// The inode of the caller-side socket, or `null` when there is none.
        ///
        /// @param callerPort the caller's local port (the request's remote port)
        /// @param panelPort  the port the panel served the request on
        /// @return the socket's inode, or `null`
        @Nullable Long callerInode(int callerPort, int panelPort);

        /// The pid holding `inode`, or `null` when no other process does.
        ///
        /// @param inode the socket's inode
        /// @return the owning pid, or `null`
        @Nullable Long socketOwner(long inode);

        /// The parent of `pid`, or `null` when it cannot be read.
        ///
        /// @param pid the process
        /// @return its parent's pid, or `null`
        @Nullable Long parentPid(long pid);
    }

    /// How long a derived identity is reused for one remote port. Short on
    /// purpose: a reused ephemeral port must not answer for its previous
    /// holder for long, and the only callers that hit the same port twice are
    /// probe loops — which the fatal gate answers, cheaply, every time.
    private static final long CACHE_MILLIS = 3_000;

    private static final int CACHE_LIMIT = 128;

    private final TerminalManager terminals;
    private final Proc proc;
    private final long selfPid = ProcessHandle.current().pid();

    private record Cached(@Nullable Ref ref, long expiresAt) {
    }

    private final ConcurrentHashMap<Integer, Cached> cache = new ConcurrentHashMap<>();

    public PeerIdentity(TerminalManager terminals) {
        this(terminals, new LinuxProc());
    }

    PeerIdentity(TerminalManager terminals, Proc proc) {
        this.terminals = terminals;
        this.proc = proc;
    }

    /// Which instance the caller of this connection runs in.
    ///
    /// @param remotePort the request's remote port — the caller socket's own
    ///                   local port
    /// @param localPort  the request's local port — the panel's
    /// @return the instance, or `null` when the caller is identifiable as
    ///         nobody (outside every instance's tree, or outside this network
    ///         namespace)
    public @Nullable Ref derive(int remotePort, int localPort) {
        if (remotePort <= 0 || localPort <= 0) {
            return null;
        }
        long now = System.currentTimeMillis();
        Cached cached = cache.get(remotePort);
        if (cached != null && cached.expiresAt() > now) {
            return cached.ref();
        }
        Ref ref = deriveUncached(remotePort, localPort);
        if (cache.size() > CACHE_LIMIT) {
            cache.clear();
        }
        cache.put(remotePort, new Cached(ref, now + CACHE_MILLIS));
        return ref;
    }

    private @Nullable Ref deriveUncached(int remotePort, int localPort) {
        Long inode = proc.callerInode(remotePort, localPort);
        if (inode == null) {
            return null;
        }
        Long pid = proc.socketOwner(inode);
        if (pid == null) {
            return null;
        }
        return walkToRoot(pid);
    }

    /// Walks `pid`'s parent chain to the instance root it lives under.
    ///
    /// The panel's own pid ends the walk without an answer: a process that
    /// reached this panel without passing through an instance root is nobody's
    /// child but the panel's own.
    private @Nullable Ref walkToRoot(long pid) {
        Map<Long, Ref> roots = roots();
        if (roots.isEmpty()) {
            return null;
        }
        long current = pid;
        for (int depth = 0; depth < 64 && current > 1; depth++) {
            Ref ref = roots.get(current);
            if (ref != null) {
                return ref;
            }
            if (current == selfPid) {
                return null;
            }
            Long parent = proc.parentPid(current);
            if (parent == null) {
                return null;
            }
            current = parent;
        }
        return null;
    }

    /// Every live instance root this panel knows, as `pid → instance`.
    ///
    /// The terminal roots and the web-mode roots are one registry each; both
    /// are direct children of the panel, so a pid can appear in at most one of
    /// them and the map cannot disagree with itself.
    private Map<Long, Ref> roots() {
        Map<Long, Ref> roots = new HashMap<>();
        for (TerminalManager.LiveTerminal terminal : terminals.liveTerminals()) {
            if (terminal.pid() > 0) {
                roots.put(terminal.pid(), new Ref(terminal.brand(), terminal.instanceId()));
            }
        }
        for (BrandRuntime.LiveRoot root : BrandRuntime.liveRoots()) {
            roots.put(root.pid(), new Ref(root.brand(), root.instanceId()));
        }
        for (Map.Entry<Long, String> root : DshProcessManager.liveRootPids().entrySet()) {
            roots.put(root.getKey(), new Ref("dsh", root.getValue()));
        }
        for (Map.Entry<Long, String> root : ZcodeRuntime.liveRootPids().entrySet()) {
            roots.put(root.getKey(), new Ref("zcode", root.getValue()));
        }
        return roots;
    }

    /// Whether the family's instance currently holds a running terminal — the
    /// condition that turns a `launch` into a takeover.
    ///
    /// @param family      the terminal registry's brand (`dsh`, a brand id, `zcode`)
    /// @param instanceId  the instance
    /// @return whether a terminal session is running for it
    public boolean terminalOpen(String family, String instanceId) {
        return terminals.find(family, instanceId)
                .map(session -> session.state() == TerminalSession.State.RUNNING)
                .orElse(false);
    }

    /// The `/proc` reader, as deployed on Linux. Every method answers `null`
    /// rather than throwing: an unreadable `/proc` means "unidentified", which
    /// the callers already know how to treat safely.
    static final class LinuxProc implements Proc {

        private static final List<String> FILES = List.of("/proc/net/tcp", "/proc/net/tcp6");

        @Override
        public @Nullable Long callerInode(int callerPort, int panelPort) {
            for (String file : FILES) {
                Long inode = scan(linesOf(file), callerPort, panelPort);
                if (inode != null) {
                    return inode;
                }
            }
            return null;
        }

        /// Finds the caller-side socket among `/proc/net/tcp` lines: the entry
        /// whose local port is the caller's and whose remote port is the
        /// panel's, established. The panel's own side of the same connection is
        /// the mirror entry and never matches.
        ///
        /// @param lines      the file's lines
        /// @param callerPort the caller's local port
        /// @param panelPort  the panel's port
        /// @return the socket's inode, or `null`
        static @Nullable Long scan(List<String> lines, int callerPort, int panelPort) {
            for (String line : lines) {
                String[] fields = line.trim().split("\\s+");
                if (fields.length < 10) {
                    continue;
                }
                if (portOf(fields[1]) != callerPort || portOf(fields[2]) != panelPort) {
                    continue;
                }
                if (!"01".equals(fields[3])) {
                    continue;
                }
                try {
                    return Long.parseLong(fields[9]);
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
            return null;
        }

        private static List<String> linesOf(String file) {
            try {
                return Files.readAllLines(Path.of(file));
            } catch (IOException | RuntimeException e) {
                return List.of();
            }
        }

        /// The port part of a `/proc/net/tcp` address, little-endian hex.
        static int portOf(String address) {
            int colon = address.lastIndexOf(':');
            if (colon < 0 || colon + 1 >= address.length()) {
                return -1;
            }
            try {
                return Integer.parseInt(address.substring(colon + 1), 16);
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        @Override
        public @Nullable Long socketOwner(long inode) {
            String target = "socket:[" + inode + "]";
            File[] pids = new File("/proc").listFiles();
            if (pids == null) {
                return null;
            }
            for (File pidDir : pids) {
                long pid = pidOf(pidDir);
                if (pid <= 0 || pid == ProcessHandle.current().pid()) {
                    continue;
                }
                File[] fds = pidDir.toPath().resolve("fd").toFile().listFiles();
                if (fds == null) {
                    continue;
                }
                for (File fd : fds) {
                    try {
                        if (target.equals(Files.readSymbolicLink(fd.toPath()).toString())) {
                            return pid;
                        }
                    } catch (IOException | RuntimeException ignored) {
                        // A descriptor that vanished mid-scan, or a process we
                        // may not read: not the answer, and not an error.
                    }
                }
            }
            return null;
        }

        private static long pidOf(File pidDir) {
            try {
                return Long.parseLong(pidDir.getName());
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        @Override
        public @Nullable Long parentPid(long pid) {
            try {
                String stat = Files.readString(Path.of("/proc/" + pid + "/stat"));
                // comm may contain spaces and parentheses; everything after the
                // final ')' is field 3 onward, and ppid is the second of those.
                int close = stat.lastIndexOf(')');
                if (close < 0) {
                    return null;
                }
                String[] fields = stat.substring(close + 2).split("\\s+");
                if (fields.length < 2) {
                    return null;
                }
                return Long.parseLong(fields[1]);
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }
    }
}
