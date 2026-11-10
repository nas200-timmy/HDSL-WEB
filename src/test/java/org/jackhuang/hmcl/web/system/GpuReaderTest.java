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
package org.jackhuang.hmcl.web.system;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GpuReaderTest {

    @Test
    void parsesNvidiaCsv() {
        var gpus = GpuReaders.NvidiaGpuReader.parse(List.of(
                "0, NVIDIA GeForce RTX 4090, 37, 82, 4, 1228, 24564, 54, 28.5"));
        assertEquals(1, gpus.size());
        var gpu = gpus.get(0);
        assertEquals(0, gpu.index());
        assertEquals("NVIDIA GeForce RTX 4090", gpu.name());
        assertEquals("NVIDIA", gpu.vendor());
        assertEquals(0.37, gpu.utilization(), 0.0001);
        assertEquals(0.82, gpu.encoderUtilization(), 0.0001);
        assertEquals(0.04, gpu.decoderUtilization(), 0.0001);
        assertEquals(1228L * 1024 * 1024, gpu.memoryUsedBytes());
        assertEquals(24564L * 1024 * 1024, gpu.memoryTotalBytes());
        assertEquals(54, gpu.temperatureC(), 0.001);
        assertEquals(28.5, gpu.powerW(), 0.001);
    }

    @Test
    void unavailableFieldsBecomeMinusOne() {
        var gpus = GpuReaders.NvidiaGpuReader.parseShort(List.of(
                "0, GPU, [N/A], [N/A], [N/A], [N/A], [N/A]"));
        assertEquals(1, gpus.size());
        assertEquals(-1, gpus.get(0).utilization(), 0.0001);
        assertEquals(-1, gpus.get(0).memoryUsedBytes());
    }
}
