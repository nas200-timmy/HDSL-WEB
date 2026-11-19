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
package org.jackhuang.hmcl.web.minecraft.preview;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/// One preview run: the state the UI polls, plus the process it owns.
///
/// A job is created instantly and answers "queued"; the work happens on the
/// service's single worker. That is deliberate — generating a world is the most
/// expensive thing the panel does, and letting two run at once would make the
/// panel feel broken for everyone else.
@NotNullByDefault
public final class PreviewJob {

    /// The stages the UI shows. They are coarse on purpose: a percentage and a
    /// verb are all a waiting user needs.
    public enum State {
        QUEUED("排队中"),
        RESOLVING("准备服务端"),
        STARTING("启动服务端"),
        GENERATING("生成区块"),
        SAVING("写入存档"),
        PARSING("解析区块"),
        RENDERING("绘制地图"),
        DONE("完成"),
        FAILED("失败"),
        CANCELLED("已取消");

        private final String label;

        State(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private static final int MAX_LOG_LINES = 40;

    private final String id;
    private final PreviewSpec spec;
    private final Instant createdAt = Instant.now();

    private volatile State state = State.QUEUED;
    private volatile @Nullable String error;
    private volatile int percent;
    private volatile long chunksDone;
    private volatile long chunksTotal;
    private volatile boolean cancelled;
    private volatile @Nullable Process process;
    private volatile @Nullable Path image;
    private volatile @Nullable Path meta;
    private volatile boolean partial;
    private volatile Instant finishedAt;

    private final Deque<String> log = new ArrayDeque<>();

    public PreviewJob(String id, PreviewSpec spec) {
        this.id = id;
        this.spec = spec;
        this.chunksTotal = spec.chunkCount();
    }

    public String id() {
        return id;
    }

    public PreviewSpec spec() {
        return spec;
    }

    public State state() {
        return state;
    }

    public @Nullable String error() {
        return error;
    }

    public int percent() {
        return percent;
    }

    public boolean cancelled() {
        return cancelled;
    }

    public long chunksDone() {
        return chunksDone;
    }

    public long chunksTotal() {
        return chunksTotal;
    }

    public @Nullable Path image() {
        return image;
    }

    public @Nullable Path meta() {
        return meta;
    }

    public boolean partial() {
        return partial;
    }

    public boolean finished() {
        return state == State.DONE || state == State.FAILED || state == State.CANCELLED;
    }

    public void state(State next) {
        if (finished()) {
            return;
        }
        this.state = next;
        if (next == State.GENERATING) {
            this.percent = 5;
        } else if (next == State.SAVING) {
            this.percent = 75;
        } else if (next == State.PARSING) {
            this.percent = 85;
        } else if (next == State.RENDERING) {
            this.percent = 95;
        }
    }

    public void progress(long done, long total) {
        this.chunksDone = done;
        this.chunksTotal = Math.max(1, total);
        this.percent = (int) Math.min(74, 5 + done * 70 / Math.max(1, total));
    }

    public void attach(@Nullable Process process) {
        this.process = process;
    }

    public @Nullable Process process() {
        return process;
    }

    public void log(String line) {
        synchronized (log) {
            log.addLast(line);
            while (log.size() > MAX_LOG_LINES) {
                log.removeFirst();
            }
        }
    }

    public java.util.List<String> logLines() {
        synchronized (log) {
            return java.util.List.copyOf(log);
        }
    }

    public void cancel() {
        cancelled = true;
        Process running = process;
        if (running != null) {
            running.destroy();
        }
    }

    public void fail(String message) {
        if (finished()) {
            return;
        }
        this.error = message;
        this.state = State.FAILED;
        this.finishedAt = Instant.now();
    }

    public void markCancelled() {
        if (finished()) {
            return;
        }
        this.state = State.CANCELLED;
        this.finishedAt = Instant.now();
    }

    public void done(Path image, @Nullable Path meta, boolean partial) {
        this.image = image;
        this.meta = meta;
        this.partial = partial;
        this.percent = 100;
        this.state = State.DONE;
        this.finishedAt = Instant.now();
    }

    /// The JSON the status endpoint returns.
    public JsonObject toJson() {
        JsonObject body = new JsonObject();
        body.addProperty("id", id);
        body.addProperty("state", state.name());
        body.addProperty("label", state.label());
        body.addProperty("percent", percent);
        body.addProperty("chunksDone", chunksDone);
        body.addProperty("chunksTotal", chunksTotal);
        body.addProperty("partial", partial);
        body.addProperty("version", spec.version());
        body.addProperty("seed", spec.seed());
        body.addProperty("radius", spec.radiusBlocks());
        body.addProperty("biomeY", spec.biomeY());
        body.addProperty("mode", spec.mode().name().toLowerCase(java.util.Locale.ROOT));
        body.addProperty("engine", spec.engine().name().toLowerCase(java.util.Locale.ROOT));
        body.addProperty("createdAt", createdAt.toString());
        if (finishedAt != null) {
            body.addProperty("elapsedMs", Duration.between(createdAt, finishedAt).toMillis());
        }
        if (error != null) {
            body.addProperty("error", error);
        }
        if (!finished() && percent > 3) {
            long elapsed = Duration.between(createdAt, Instant.now()).toMillis();
            body.addProperty("etaSeconds", Math.max(0, elapsed * (100 - percent) / percent / 1000));
        }
        body.addProperty("imageReady", image != null);
        JsonArray lines = new JsonArray();
        for (String line : logLines()) {
            lines.add(line);
        }
        body.add("log", lines);
        return body;
    }
}
