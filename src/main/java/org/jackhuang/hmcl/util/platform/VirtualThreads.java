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
package org.jackhuang.hmcl.util.platform;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Per-task executors that use virtual threads where the runtime has them.
///
/// The launcher's minimum runtime is Java 17; virtual threads arrived in 21. The
/// call sites all want "one thread per task" — an unbounded executor whose tasks
/// mostly block on I/O — so on 17 the closest equivalent is a cached pool of
/// daemon platform threads. Reflection keeps a single jar running on both: the
/// JVM in use decides, not the compiler.
@NotNullByDefault
public final class VirtualThreads {
    /// `Executors.newVirtualThreadPerTaskExecutor`, or `null` before Java 21.
    private static final @Nullable Method NEW_VIRTUAL = find();

    /// `Thread.startVirtualThread`, or `null` before Java 21.
    private static final @Nullable Method START_VIRTUAL = findStart();

    private VirtualThreads() {
    }

    /// An executor that gives each task its own thread.
    ///
    /// @return virtual threads on Java 21+, a cached daemon pool on 17
    public static ExecutorService newPerTaskExecutor() {
        if (NEW_VIRTUAL != null) {
            try {
                return (ExecutorService) NEW_VIRTUAL.invoke(null);
            } catch (IllegalAccessException | InvocationTargetException | RuntimeException e) {
                LOG.warning("Could not create virtual threads; falling back to platform threads", e);
            }
        }
        return Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "hdsl-task");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static @Nullable Method find() {
        try {
            return Executors.class.getMethod("newVirtualThreadPerTaskExecutor");
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /// Starts `task` on its own thread, joining is the caller's business.
    ///
    /// The PTY pump needs a [Thread] handle (it joins the read loop before
    /// returning), which the executor above cannot hand out.
    ///
    /// @return the started thread: virtual on Java 21+, a daemon platform
    ///         thread on 17
    public static Thread start(Runnable task) {
        if (START_VIRTUAL != null) {
            try {
                return (Thread) START_VIRTUAL.invoke(null, task);
            } catch (IllegalAccessException | InvocationTargetException | RuntimeException e) {
                LOG.warning("Could not start a virtual thread; falling back to a platform thread", e);
            }
        }
        Thread thread = new Thread(task, "hdsl-task");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static @Nullable Method findStart() {
        try {
            return Thread.class.getMethod("startVirtualThread", Runnable.class);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}
