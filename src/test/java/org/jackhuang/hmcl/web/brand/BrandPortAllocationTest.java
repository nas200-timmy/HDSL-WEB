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
package org.jackhuang.hmcl.web.brand;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BrandPortAllocationTest {

    /// 并发跑真实分配：返回的号必须两两不同。旧的"查一下就用"在这条上是靠号段
    /// 大、碰撞少来蒙混的，这条用例把不变量钉住，防止退回没有预留的实现。
    @Test
    void concurrentAllocationNeverHandsOutTheSamePort() throws Exception {
        int threads = 8;
        int perThread = 50;
        Set<Integer> ports = ConcurrentHashMap.newKeySet();
        AtomicInteger duplicates = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> pool = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int j = 0; j < perThread; j++) {
                    try {
                        if (!ports.add(BrandRuntime.allocatePort())) {
                            duplicates.incrementAndGet();
                        }
                    } catch (Exception e) {
                        throw new AssertionError("分配端口失败", e);
                    }
                }
            });
            thread.start();
            pool.add(thread);
        }
        start.countDown();
        for (Thread thread : pool) {
            thread.join();
        }
        assertEquals(0, duplicates.get(), "并发分配不能发出重复的端口");
        assertEquals(threads * perThread, ports.size());
    }
}
