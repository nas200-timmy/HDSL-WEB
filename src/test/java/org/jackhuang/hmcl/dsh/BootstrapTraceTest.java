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

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class BootstrapTraceTest {

    @Test
    public void anInstallNamesSourceDigestAndDestination() {
        assertEquals("bootstrap: status=installed kind=uv need=brand-build"
                        + " source=https://mirror/uv.whl digest=pinned-sha256:abc"
                        + " dest=/data/toolchains/uv/0.7.13/uv",
                BootstrapTrace.line("installed", "uv", "brand-build", "https://mirror/uv.whl",
                        "pinned-sha256:abc", Path.of("/data/toolchains/uv/0.7.13/uv"), null));
    }

    @Test
    public void aRefusalCarriesTheDetail() {
        assertEquals("bootstrap: status=refused kind=closure need=node"
                        + " detail=missing libatomic.so.1",
                BootstrapTrace.line("refused", "closure", "node", null, null, null,
                        "missing libatomic.so.1"));
    }

    @Test
    public void aNoteSaysWhatWasNotDownloaded() {
        assertEquals("bootstrap: status=note kind=closure need=-"
                        + " detail=the host's loader finds the Node libraries",
                BootstrapTrace.line("note", "closure", "-", null, null, null,
                        "the host's loader finds the Node libraries"));
    }
}
