/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2025 huangyuhui <huanghongxun2008@126.com> and contributors
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
package org.jackhuang.hmcl.util;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/// Terminates a process together with the processes it spawned.
///
/// A launched web tool is rarely a single process: an npm shim, `cmd.exe`, or a
/// Node entry point starts the real server as a child. Killing only the handle
/// `ProcessBuilder` returned leaves that child running — and holding the port —
/// which is exactly what makes a "stopped" instance still answer (or refuse to
/// start again because its port is taken). Windows shows this most plainly,
/// because `destroy()` there is `TerminateProcess` for the one handle and has no
/// notion of a tree, but the same shape exists wherever the launcher is a shim.
///
/// The descendants are collected *before* the parent is destroyed: once it is
/// gone they are re-parented and [`ProcessHandle#descendants()`] no longer
/// reaches them. They are then asked to exit first, given the same grace the
/// parent had, and killed only if they are still alive afterwards.
@NotNullByDefault
public final class ProcessTree {
    private ProcessTree() {
    }

    /// Stops `process` and every process below it.
    ///
    /// @param process      the root process
    /// @param graceSeconds how long to wait for a polite exit before forcing it
    public static void terminate(Process process, long graceSeconds) {
        List<ProcessHandle> descendants = process.descendants().toList();

        process.destroy();
        try {
            if (!process.waitFor(graceSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(graceSeconds, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }

        for (ProcessHandle descendant : descendants) {
            if (descendant.isAlive()) {
                descendant.destroy();
            }
        }

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(graceSeconds);
        for (ProcessHandle descendant : descendants) {
            if (!descendant.isAlive()) {
                continue;
            }
            try {
                long remaining = deadline - System.nanoTime();
                if (remaining > 0) {
                    descendant.onExit().get(remaining, TimeUnit.NANOSECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (TimeoutException | ExecutionException e) {
                // Still running past the grace, or its exit state cannot be observed.
            }
            if (descendant.isAlive()) {
                descendant.destroyForcibly();
            }
        }
    }
}
