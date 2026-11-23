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
package org.jackhuang.hmcl.web.instance;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/// 进程内的端口预留表：把"分配端口"和"子进程真正绑定"之间的窗口关上。
///
/// 之前的 `allocatePort()` 是 `isFree()` 一查就用：两个实例并发启动时，两边都
/// 看到同一个端口空闲，于是拿到同一个号，后绑定的那个失败——更糟的是反代按
/// `id → port` 转发，两个实例会指到同一个上游。
///
/// 预留是**先登记、后检查**：`tryReserve` 用 `putIfAbsent` 原子占位，谁登记成功
/// 谁才有资格去 `isFree()`，检查不过立刻释放。进程一旦绑定，`isFree()` 本身就
/// 挡住了后来的分配，所以预留只需要覆盖"分配 → 绑定"这一小段，不需要挂在进程
/// 生命周期上：超过 [DEFAULT_TTL_NANOS] 的预留自动过期，启动失败留下的残留不会
/// 永久占用号段。
public final class PortReservations {

    static final long DEFAULT_TTL_NANOS = Duration.ofMinutes(5).toNanos();

    private final Map<Integer, Long> reserved = new ConcurrentHashMap<>();
    private final long ttlNanos;
    private final LongSupplier clock;

    public PortReservations() {
        this(DEFAULT_TTL_NANOS, System::nanoTime);
    }

    public PortReservations(long ttlNanos, LongSupplier clock) {
        this.ttlNanos = ttlNanos;
        this.clock = clock;
    }

    /// 占位成功返回 `true`；这个号已被别人预留（且未过期）返回 `false`。
    public boolean tryReserve(int port) {
        evictExpired();
        return reserved.putIfAbsent(port, clock.getAsLong()) == null;
    }

    /// 释放一个预留（分配后检查发现不能用时调用）。
    public void release(int port) {
        reserved.remove(port);
    }

    /// 当前还在有效期内的预留数（测试与诊断用）。
    public int size() {
        evictExpired();
        return reserved.size();
    }

    private void evictExpired() {
        long now = clock.getAsLong();
        reserved.entrySet().removeIf(entry -> now - entry.getValue() >= ttlNanos);
    }
}
