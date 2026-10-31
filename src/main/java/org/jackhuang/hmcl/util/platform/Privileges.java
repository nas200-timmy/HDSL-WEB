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
package org.jackhuang.hmcl.util.platform;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/// Who a child process runs as, and when the panel is allowed to answer that
/// question differently from "whoever I am".
///
/// **Only inside a container.** There the image owns uid 1000 as `hdsl` and the
/// panel is its PID 1, so dropping agent work to 1000 is a name the image
/// defines. On a bare host the same number belongs to whoever the machine's
/// administrator made it — handing an agent another person's uid would be a
/// privilege bug in either direction, so the drop is not attempted there at all.
///
/// The reason the panel runs as root in the container is narrow: the files that
/// must stay out of an agent's reach (`auth/`, `certs/`, `server.yaml`, the audit
/// log) can only be protected by ownership, and a single uid cannot own what it
/// must not touch. Everything else the panel does — writing instance files,
/// installing runtimes — keeps working because it writes into directories that
/// belong to uid 1000 with a group both sides share. The one place that is not
/// enough is a file the panel writes with a mode of its own choosing, which is
/// what [#handToAgent] is for: a shared group does not make a `0600` file
/// readable by anyone.
///
/// On a bare deployment this class is inert: [#inContainer()] is false, so
/// [#asAgent] returns its argument unchanged and a panel started by systemd as
/// `hdsl` behaves exactly as it always did. A panel started as root on a host is
/// the operator's own decision (the panel logs it and the security notes say
/// what it means); it is not refused, because refusing would only start an
/// argument with whoever chose it.
@NotNullByDefault
public final class Privileges {

    /// The uid and gid the image gives to `hdsl`, and therefore the identity
    /// everything that acts for an instance runs as.
    ///
    /// It is a build argument (`HDSL_AGENT_UID`) rather than a constant because
    /// some storage refuses to be protected by mode bits for one particular
    /// number: a QNAP ACL-managed share treats its owner (usually 1000) as an
    /// administrator and lets it through 0700. There the only lever inside the
    /// image is "be a different uid", so the image can be built as 1001 instead.
    /// The entrypoint reads the same variable, so the two cannot drift.
    public static final int AGENT_UID = agentUid(System.getenv("HDSL_AGENT_UID"));

    /// Parses the configured uid, falling back to 1000.
    ///
    /// @param configured the raw value, or `null`
    /// @return a usable uid
    static int agentUid(@Nullable String configured) {
        if (configured == null || configured.isBlank()) {
            return 1000;
        }
        try {
            int parsed = Integer.parseInt(configured.trim());
            if (parsed > 0 && parsed < 65536) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // fall through to the warning
        }
        org.jackhuang.hmcl.util.logging.Logger.LOG.warning(
                "HDSL_AGENT_UID='" + configured + "' is not a uid; using 1000");
        return 1000;
    }

    /// Set once at startup: whether this JVM is the root inside a container.
    private static final boolean DROP_CHILDREN = inContainer() && euid() == 0;

    private Privileges() {
    }

    /// Whether this process runs inside a container.
    ///
    /// Three facts, any of which is enough: Docker's marker file, podman's, or a
    /// cgroup path that names a container runtime. They are all present without
    /// privileges, which is what makes this usable from a process that may not
    /// be root.
    public static boolean inContainer() {
        return detect(Path.of("/.dockerenv"), Path.of("/run/.containerenv"), Path.of("/proc/self/cgroup"));
    }

    /// The container marker test, with its inputs named so a test can drive it.
    ///
    /// @param dockerEnv    `/ .dockerenv`
    /// @param containerEnv `/run/.containerenv` (podman)
    /// @param cgroup       `/proc/self/cgroup`
    /// @return whether any of them says "container"
    static boolean detect(Path dockerEnv, Path containerEnv, Path cgroup) {
        if (Files.exists(dockerEnv) || Files.exists(containerEnv)) {
            return true;
        }
        try {
            String text = Files.readString(cgroup);
            return text.contains("docker") || text.contains("kubepods")
                    || text.contains("libpod") || text.contains("lxc");
        } catch (Exception e) {
            return false;
        }
    }

    /// The effective uid, or `-1` where the platform has no such notion.
    static int euid() {
        if (OperatingSystem.CURRENT_OS != OperatingSystem.LINUX
                && OperatingSystem.CURRENT_OS != OperatingSystem.MACOS) {
            return -1;
        }
        try {
            return (int) Files.getAttribute(Path.of("/proc/self"), "unix:uid");
        } catch (Exception e) {
            // macOS has no /proc; ask the account database instead.
            try {
                return (int) new com.sun.security.auth.module.UnixSystem().getUid();
            } catch (Exception | LinkageError ignored) {
                return -1;
            }
        }
    }

    /// Whether a file or directory is one the agent reads *and* the panel writes with a
    /// mode of its own: pnpm's `config.yaml`, a generated settings file.
    ///
    /// 0664 with the agent as owner. This is what the panel writes when it owns a file
    /// that is not a secret but is consumed by a process running as uid 1000 — the
    /// pair `PnpmConfigFile` used to keep privately, promoted here because a second
    /// caller (the Hermes runtime) needs the same answer.
    public static final Set<PosixFilePermission> AGENT_SHARED_FILE =
            PosixFilePermissions.fromString("rw-rw-r--");

    /// A directory with the same rule: the agent writes inside it, the panel may too.
    public static final Set<PosixFilePermission> AGENT_SHARED_DIRECTORY =
            PosixFilePermissions.fromString("rwxrwxr-x");

    /// A secret the panel writes and only the agent may read: an `.env` carrying an API
    /// key. 0600 with the agent as owner — the panel is root and gets in anyway.
    public static final Set<PosixFilePermission> AGENT_PRIVATE_FILE =
            PosixFilePermissions.fromString("rw-------");

    /// A directory only the agent may enter: the pid and lock files of a supervised
    /// process.
    public static final Set<PosixFilePermission> AGENT_PRIVATE_DIRECTORY =
            PosixFilePermissions.fromString("rwx------");

    /// An executable only the agent may run: a generated launcher script.
    public static final Set<PosixFilePermission> AGENT_PRIVATE_EXECUTABLE =
            PosixFilePermissions.fromString("rwx------");

    /// Whether child processes are dropped to [#AGENT_UID] on this machine.
    public static boolean dropsChildren() {
        return DROP_CHILDREN;
    }

    /// Hands a file or directory to the agent, so that the uid its children run
    /// as can read — and where it needs to, rewrite — what the panel put there.
    ///
    /// The split exists to keep some files out of an agent's reach; it also
    /// catches the files an agent has to read, and those are what this is for.
    /// `pnpm` is a child process, so a `config.yaml` the panel wrote as root is
    /// one the agent's pnpm cannot open, and a `0600` one is how the first
    /// image with the split failed every instance install with `EACCES:
    /// /home/hdsl/.config/pnpm/config.yaml`. Handing over the mode alone is not
    /// enough either: pnpm's own `config set` rewrites the file, which needs the
    /// directory writable by the same uid.
    ///
    /// A no-op outside a container: there the panel already runs as whoever the
    /// agent runs as, and changing an owner would be changing somebody's file.
    ///
    /// @param path        the file or directory
    /// @param permissions the mode to give it, e.g. `rw-rw-r--`
    /// @return whether the file was handed over
    public static boolean handToAgent(Path path, Set<PosixFilePermission> permissions) {
        return DROP_CHILDREN && handTo(path, permissions, AGENT_UID, AGENT_UID);
    }

    /// The two calls handing over consists of, with the numbers named so a test
    /// can drive them: the container branch itself is out of a test's reach, but
    /// `chown` and `chmod` are not, and they are what the fix depends on.
    ///
    /// The order is not free. `chown` clears the setuid and setgid bits, so the
    /// mode has to come after it or the file ends up readable by nobody but
    /// root — the exact failure this exists to repair.
    ///
    /// @param path        the file or directory
    /// @param permissions the mode
    /// @param uid         the owner to set
    /// @param gid         the group to set
    /// @return whether both calls succeeded
    static boolean handTo(Path path, Set<PosixFilePermission> permissions, int uid, int gid) {
        try {
            Files.setAttribute(path, "unix:uid", uid);
            Files.setAttribute(path, "unix:gid", gid);
            Files.setPosixFilePermissions(path, permissions);
            return true;
        } catch (UnsupportedOperationException | IOException | IllegalArgumentException e) {
            org.jackhuang.hmcl.util.logging.Logger.LOG.warning(
                    "Could not hand " + path + " to uid " + uid + ": " + e);
            return false;
        }
    }

    /// Whether this is a root process outside a container: the panel and every
    /// agent it starts share that identity, which the startup banner says out
    /// loud because nothing in the process can fix it.
    public static boolean rootOutsideContainer() {
        return euid() == 0 && !inContainer();
    }

    /// The argv to run `command` with, as an agent.
    ///
    /// Inside a container running as root this is
    /// `setpriv --reuid=1000 --regid=1000 --init-groups -- <command>`; anywhere
    /// else it is the command itself. `setpriv` execs in place, so the child
    /// still owns its process group and the PTY's session semantics are
    /// unchanged.
    ///
    /// @param command the program and its arguments, program first
    /// @return the argv to hand to the process builder
    public static List<String> asAgent(List<String> command) {
        return wrap(command, DROP_CHILDREN);
    }

    /// The wrapping itself, with the decision named so a test can drive both
    /// branches on any machine.
    ///
    /// @param command the program and its arguments
    /// @param drop    whether to prefix `setpriv`
    /// @return the argv to hand to the process builder
    static List<String> wrap(List<String> command, boolean drop) {
        if (!drop || command.isEmpty()) {
            return command;
        }
        List<String> wrapped = new ArrayList<>(command.size() + 5);
        wrapped.add(setpriv());
        wrapped.add("--reuid=" + AGENT_UID);
        wrapped.add("--regid=" + AGENT_UID);
        wrapped.add("--init-groups");
        wrapped.add("--");
        wrapped.addAll(command);
        return wrapped;
    }

    /// The `setpriv` to exec, resolved when the panel can see it. A bare name is
    /// left alone: the child inherits the panel's `PATH`, and failing here would
    /// turn a missing tool into a crash instead of the exec error the spawner
    /// already reports.
    private static String setpriv() {
        return Executables.resolve("setpriv").map(Path::toString).orElse("setpriv");
    }

    /// One line for the startup banner, or `null` when there is nothing to say.
    ///
    /// @return the warning, or `null`
    public static String bannerNote() {
        if (rootOutsideContainer()) {
            return "running as root outside a container: every instance and every agent this panel "
                    + "starts runs as root too, and the panel cannot separate them from its own "
                    + "credential files (see docs/design-notes.md §7)";
        }
        return null;
    }
}
