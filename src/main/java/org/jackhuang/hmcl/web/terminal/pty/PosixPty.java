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
package org.jackhuang.hmcl.web.terminal.pty;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jackhuang.hmcl.util.platform.VirtualThreads;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/// The POSIX pseudo terminal: `openpty` plus `posix_spawn`, glued with JNA.
///
/// Two decisions are load-bearing:
///
/// - **No `fork()`.** Forks in a JVM that has threads are unsafe; `posix_spawn`
///   is the portable, safe way to start a child. The child gets its own
///   process group (`POSIX_SPAWN_SETPGROUP` with pgroup 0), which is what
///   [#terminate] kills.
/// - **No JNA callbacks.** Everything is a blocking call made from a Java
///   thread (`poll` + `read`), so the native side never calls back into the
///   JVM and there is no callback-thread bookkeeping to get wrong.
///
/// When the child exits, the pump drains what is still buffered and then
/// reports EOF; the master is closed right after, so no output written after
/// the exit can be observed. That is the property the web terminal relies on:
/// the moment the agent is gone, the screen can no longer change.
@NotNullByDefault
final class PosixPty implements Pty {

    /// How long the pump may keep draining after `waitpid` returned. The data
    /// is already in the kernel buffer; this is not a window for interaction.
    private static final long DRAIN_MILLIS = 500;

    /// One read from the master. Large enough for a TUI repaint, small enough
    /// that the replay buffer's chunk list stays cheap.
    private static final int READ_BUFFER = 32 * 1024;

    /// How much output the pump may hold for a slow reader before it stops
    /// reading, which in turn blocks the child on its own writes.
    private static final int REPLAY_CAPACITY = 1024 * 1024;

    /// Background struct sizes. The C types are opaque; over-allocating is
    /// safe (the callee only touches its own size) and avoids hard-coding
    /// glibc's internal layout. macOS treats both as pointers.
    private static final int POSIX_STRUCT_BYTES = 512;

    private static final short POSIX_SPAWN_SETPGROUP = 0x02;

    private static final int SIGTERM = 15;
    private static final int SIGKILL = 9;

    private static final int POLLIN = 0x001;
    private static final int POLLERR = 0x008;
    private static final int POLLHUP = 0x010;
    private static final int POLLNVAL = 0x020;

    private static final int EINTR = 4;
    private static final int EIO = 5;

    private static final int F_SETFD = 2;
    private static final int FD_CLOEXEC = 1;

    /// `TIOCSWINSZ`; the value is an ioctl request, and ioctl numbers differ
    /// between the two POSIX platforms this build runs on.
    private static final long TIOCSWINSZ =
            OperatingSystem.CURRENT_OS == OperatingSystem.LINUX ? 0x5414L : 0x80087467L;

    /// `SIGWINCH` is 28 on both Linux and macOS.
    private static final int SIGWINCH = 28;

    private final int masterFd;
    private final int pid;
    private final Buffer output = new Buffer(REPLAY_CAPACITY);
    private final AtomicBoolean masterClosed = new AtomicBoolean();
    private final AtomicBoolean inputClosed = new AtomicBoolean();
    private final CountDownLatch finished = new CountDownLatch(1);
    private final Thread pump;
    private volatile int exitCode = -1;

    private PosixPty(int masterFd, int pid) {
        this.masterFd = masterFd;
        this.pid = pid;
        this.pump = VirtualThreads.start(this::pump);
        VirtualThreads.start(this::reap);
    }

    /// Starts `command` on a new pseudo terminal.
    ///
    /// @throws PtyException when `openpty` or `posix_spawn` fails; the errno
    ///                      is carried in the exception
    static PosixPty spawn(List<String> command, @Nullable Path cwd,
                          Map<String, String> environment, PtySize size) throws IOException {
        if (command.isEmpty()) {
            throw new PtyException("empty command");
        }
        String program = command.get(0);
        IntByReference master = new IntByReference();
        IntByReference slave = new IntByReference();
        Memory winsize = winsize(size);
        int rc;
        try {
            rc = LibUtil.INSTANCE.openpty(master, slave, null, null, winsize);
        } catch (UnsatisfiedLinkError e) {
            throw new PtyException("libutil does not provide openpty: " + e.getMessage());
        }
        if (rc != 0) {
            throw new PtyException("openpty", Native.getLastError());
        }
        int masterFd = master.getValue();
        int slaveFd = slave.getValue();
        Memory fileActions = new Memory(POSIX_STRUCT_BYTES);
        Memory attributes = new Memory(POSIX_STRUCT_BYTES);
        fileActions.clear();
        attributes.clear();
        boolean fileActionsReady = false;
        boolean attributesReady = false;
        try {
            // The child's stdio is the slave side; the master never leaks into
            // it, and neither leaks into any other child this JVM starts.
            check(LibC.INSTANCE.posix_spawn_file_actions_init(fileActions), "posix_spawn_file_actions_init");
            fileActionsReady = true;
            for (int fd = 0; fd <= 2; fd++) {
                check(LibC.INSTANCE.posix_spawn_file_actions_adddup2(fileActions, slaveFd, fd),
                        "posix_spawn_file_actions_adddup2");
            }
            check(LibC.INSTANCE.posix_spawn_file_actions_addclose(fileActions, masterFd),
                    "posix_spawn_file_actions_addclose");
            if (cwd != null) {
                try {
                    check(LibC.INSTANCE.posix_spawn_file_actions_addchdir_np(fileActions, cwd.toString()),
                            "posix_spawn_file_actions_addchdir_np");
                } catch (UnsatisfiedLinkError e) {
                    throw new PtyException("this libc has no posix_spawn_file_actions_addchdir_np");
                }
            }

            check(LibC.INSTANCE.posix_spawnattr_init(attributes), "posix_spawnattr_init");
            attributesReady = true;
            check(LibC.INSTANCE.posix_spawnattr_setflags(attributes, POSIX_SPAWN_SETPGROUP),
                    "posix_spawnattr_setflags");
            // pgroup 0 means "the child's own pid": one process group per
            // terminal, which is exactly the unit terminate() kills.
            check(LibC.INSTANCE.posix_spawnattr_setpgroup(attributes, 0), "posix_spawnattr_setpgroup");

            IntByReference childPid = new IntByReference();
            String[] argv = command.toArray(new String[0]);
            String[] envp = environment.entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .toArray(String[]::new);
            int spawned = LibC.INSTANCE.posix_spawn(childPid, program, fileActions, attributes, argv, envp);
            if (spawned != 0) {
                throw new PtyException("posix_spawn " + program, spawned);
            }
            // Keep the master out of every other child this JVM spawns.
            LibC.INSTANCE.fcntl(masterFd, F_SETFD, FD_CLOEXEC);
            return new PosixPty(masterFd, childPid.getValue());
        } catch (IOException | RuntimeException e) {
            LibC.INSTANCE.close(masterFd);
            throw e;
        } finally {
            if (attributesReady) {
                LibC.INSTANCE.posix_spawnattr_destroy(attributes);
            }
            if (fileActionsReady) {
                LibC.INSTANCE.posix_spawn_file_actions_destroy(fileActions);
            }
            // The child owns the slave side now; the parent must not.
            LibC.INSTANCE.close(slaveFd);
        }
    }

    @Override
    public OutputStream input() {
        return new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                write(new byte[]{(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] bytes, int offset, int length) throws IOException {
                if (inputClosed.get() || masterClosed.get()) {
                    throw new IOException("the terminal has ended");
                }
                if (length == 0) {
                    return;
                }
                byte[] chunk = offset == 0 && length == bytes.length
                        ? bytes
                        : Arrays.copyOfRange(bytes, offset, offset + length);
                if (LibC.INSTANCE.write(masterFd, chunk, new NativeLong(length)).longValue() < 0) {
                    throw new PtyException("write", Native.getLastError());
                }
            }
        };
    }

    @Override
    public InputStream output() {
        return new InputStream() {
            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                int n = read(one, 0, 1);
                return n < 0 ? -1 : one[0] & 0xff;
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                try {
                    return output.read(bytes, offset, length);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while reading the terminal", e);
                }
            }
        };
    }

    @Override
    public void resize(PtySize size) {
        if (masterClosed.get()) {
            return;
        }
        // A failed resize is not fatal: the session keeps running at the old
        // size and the browser sends another one on the next fit.
        LibC.INSTANCE.ioctl(masterFd, new NativeLong(TIOCSWINSZ), winsize(size));
        // The child is spawned without TIOCSCTTY, so the pty has no controlling
        // terminal and the kernel has no foreground process group to notify:
        // TIOCSWINSZ alone changes the size but never delivers SIGWINCH, and a
        // running TUI keeps painting the width it started with. On a reattach
        // from a different window that paints wider than the real terminal and
        // the terminal wraps every line. Deliver the signal ourselves.
        LibC.INSTANCE.kill(pid, SIGWINCH);
    }

    @Override
    public int waitFor() {
        try {
            finished.await();
            return exitCode;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    @Override
    public void terminate(Duration grace) {
        if (finished.getCount() == 0) {
            return;
        }
        LibC.INSTANCE.kill(-pid, SIGTERM);
        try {
            if (!finished.await(Math.max(1, grace.toMillis()), TimeUnit.MILLISECONDS)) {
                LibC.INSTANCE.kill(-pid, SIGKILL);
                finished.await(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        terminate(Duration.ofSeconds(2));
        closeMaster();
    }

    /// `waitpid` on the root process, then the end of the session.
    ///
    /// The order is the security contract: input is refused first, then the
    /// pump gets a bounded moment to drain the last bytes, then the master is
    /// closed (no further output can be observed), and only then the process
    /// group is swept.
    private void reap() {
        IntByReference status = new IntByReference();
        int rc;
        do {
            rc = LibC.INSTANCE.waitpid(pid, status, 0);
        } while (rc < 0 && Native.getLastError() == EINTR);
        exitCode = rc < 0 ? -1 : decodeStatus(status.getValue());
        inputClosed.set(true);
        try {
            pump.join(DRAIN_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        closeMaster();
        LibC.INSTANCE.kill(-pid, SIGKILL);
        output.finish();
        finished.countDown();
    }

    private void pump() {
        Memory pollfd = new Memory(8);
        Memory buffer = new Memory(READ_BUFFER);
        try {
            while (!masterClosed.get()) {
                pollfd.setInt(0, masterFd);
                pollfd.setShort(4, (short) POLLIN);
                pollfd.setShort(6, (short) 0);
                int ready = LibC.INSTANCE.poll(pollfd, new NativeLong(1), 100);
                if (ready < 0) {
                    if (Native.getLastError() == EINTR) {
                        continue;
                    }
                    break;
                }
                if (ready == 0) {
                    continue;
                }
                short events = pollfd.getShort(6);
                if ((events & POLLIN) != 0) {
                    long n = LibC.INSTANCE.read(masterFd, buffer, new NativeLong(READ_BUFFER)).longValue();
                    if (n > 0) {
                        output.put(buffer.getByteArray(0, (int) n));
                        continue;
                    }
                    if (n == 0) {
                        break;
                    }
                    if (Native.getLastError() == EINTR) {
                        continue;
                    }
                    // EIO: the child closed the slave side. Nothing more can
                    // ever arrive.
                    break;
                }
                if ((events & (POLLERR | POLLHUP | POLLNVAL)) != 0) {
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            output.finish();
        }
    }

    private void closeMaster() {
        if (masterClosed.compareAndSet(false, true)) {
            LibC.INSTANCE.close(masterFd);
        }
    }

    /// The usual shell mapping: exit code, or `128 + signal`.
    private static int decodeStatus(int status) {
        int signal = status & 0x7f;
        if (signal == 0) {
            return (status >> 8) & 0xff;
        }
        return signal == 0x7f ? -1 : 128 + signal;
    }

    private static Memory winsize(PtySize size) {
        Memory memory = new Memory(8);
        memory.clear();
        memory.setShort(0, (short) size.rows());
        memory.setShort(2, (short) size.cols());
        return memory;
    }

    /// POSIX spawn helpers return the error number, never `-1`.
    private static void check(int result, String call) throws PtyException {
        if (result != 0) {
            throw new PtyException(call, result);
        }
    }

    private static final class Buffer {
        private final ArrayDeque<byte[]> chunks = new ArrayDeque<>();
        private final long capacity;
        private byte[] head;
        private int headOffset;
        private long bytes;
        private boolean finished;

        Buffer(long capacity) {
            this.capacity = capacity;
        }

        synchronized void put(byte[] data) throws InterruptedException {
            while (bytes + data.length > capacity && !finished) {
                wait();
            }
            if (finished) {
                return;
            }
            chunks.addLast(data);
            bytes += data.length;
            notifyAll();
        }

        synchronized int read(byte[] target, int offset, int length) throws InterruptedException {
            while (bytes == 0 && !finished) {
                wait();
            }
            if (bytes == 0) {
                return -1;
            }
            int copied = 0;
            while (copied < length) {
                if (head == null) {
                    head = chunks.pollFirst();
                    headOffset = 0;
                    if (head == null) {
                        break;
                    }
                }
                int n = Math.min(length - copied, head.length - headOffset);
                System.arraycopy(head, headOffset, target, offset + copied, n);
                headOffset += n;
                copied += n;
                bytes -= n;
                if (headOffset == head.length) {
                    head = null;
                }
            }
            notifyAll();
            return copied;
        }

        synchronized void finish() {
            // Mark the end, but keep what is already buffered: the reader
            // drains it and only then gets EOF. Clearing here was a race the
            // first test run caught — a child that exits immediately had its
            // last line dropped before the collector could read it.
            finished = true;
            notifyAll();
        }
    }

    /// `openpty` lives in libutil on both glibc and macOS.
    private interface LibUtil extends Library {
        LibUtil INSTANCE = Native.load("util", LibUtil.class);

        int openpty(IntByReference amaster, IntByReference aslave, Pointer name, Pointer termp, Pointer winp);
    }

    private interface LibC extends Library {
        LibC INSTANCE = Native.load("c", LibC.class);

        int posix_spawn_file_actions_init(Pointer fileActions);

        int posix_spawn_file_actions_destroy(Pointer fileActions);

        int posix_spawn_file_actions_adddup2(Pointer fileActions, int fd, int newFd);

        int posix_spawn_file_actions_addclose(Pointer fileActions, int fd);

        int posix_spawn_file_actions_addchdir_np(Pointer fileActions, String path);

        int posix_spawnattr_init(Pointer attributes);

        int posix_spawnattr_destroy(Pointer attributes);

        int posix_spawnattr_setflags(Pointer attributes, short flags);

        int posix_spawnattr_setpgroup(Pointer attributes, int pgroup);

        int posix_spawn(IntByReference pid, String path, Pointer fileActions, Pointer attributes,
                        String[] argv, String[] envp);

        int ioctl(int fd, NativeLong request, Pointer argument);

        int close(int fd);

        NativeLong read(int fd, Pointer buffer, NativeLong count);

        NativeLong write(int fd, byte[] buffer, NativeLong count);

        int poll(Pointer fds, NativeLong count, int timeoutMillis);

        int kill(int pid, int signal);

        int waitpid(int pid, IntByReference status, int options);

        int fcntl(int fd, int command, int argument);
    }
}
