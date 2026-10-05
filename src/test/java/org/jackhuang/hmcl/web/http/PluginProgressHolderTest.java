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
package org.jackhuang.hmcl.web.http;

import org.jackhuang.hmcl.web.event.EventBus;
import org.jackhuang.hmcl.web.task.TaskService;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/// The plugin install/remove paths report progress through a one-slot holder
/// that the submitter fills in *after* `submit` returns — and `submit` starts
/// the work before it returns. A line that arrives inside that window has no
/// task to tell yet, and must be dropped rather than dereferenced: an NPE there
/// fails an install that was going fine.
///
/// Driven directly rather than through an install, because the window is a race
/// and a test cannot be made to lose it on demand.
class PluginProgressHolderTest {

    @Test
    void aLineThatArrivesBeforeTheHandleIsDroppedRatherThanThrown() {
        TaskService.Task[] holder = new TaskService.Task[1];

        assertDoesNotThrow(() -> InstancesApiServlet.report(holder, "pnpm install …"),
                "a line with no task to tell yet is not a failure");
    }

    @Test
    void onceTheHandleIsThereTheLineReachesTheTask() throws Exception {
        TaskService tasks = new TaskService(new EventBus());
        CountDownLatch release = new CountDownLatch(1);
        TaskService.Task[] holder = new TaskService.Task[1];
        holder[0] = tasks.submit("probe", null, () -> {
            release.await();
            return "done";
        });

        InstancesApiServlet.report(holder, "installing");

        assertEquals("installing", tasks.get(holder[0].id()).orElseThrow().message(),
                "a line that arrives after the handle is not dropped");

        release.countDown();
    }
}
