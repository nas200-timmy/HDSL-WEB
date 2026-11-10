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
package org.jackhuang.hmcl.web.transcode.queue;

import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jackhuang.hmcl.web.config.ServerConfig;
import org.jackhuang.hmcl.web.event.EventBus;
import org.jackhuang.hmcl.web.transcode.TranscodeConsent;
import org.jackhuang.hmcl.web.transcode.TranscodeModule;
import org.jackhuang.hmcl.web.transcode.TranscodeTerms;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranscodeQueueTest {

    private static void script(Path file, String body) throws Exception {
        Files.writeString(file, body);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private static TranscodeConsent consent() {
        return new TranscodeConsent(TranscodeTerms.VERSION, TranscodeTerms.hash(),
                "2026-11-10T00:00:00Z", "tester", "127.0.0.1",
                "checkbox", "0.6.82", "UA", "abc1234", true);
    }

    @Test
    void runsAFakeEncodeToDone(@TempDir Path dir) throws Exception {
        Assumptions.assumeFalse(OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS,
                "the fake ffmpeg uses a POSIX shell script");

        Path media = Files.createDirectory(dir.resolve("media"));
        Path input = media.resolve("input.mp4");
        Files.writeString(input, "source");
        Path bin = Files.createDirectory(dir.resolve("bin"));
        Path ffmpeg = bin.resolve("ffmpeg");
        Path ffprobe = bin.resolve("ffprobe");
        script(ffmpeg, """
                #!/bin/sh
                case "$*" in
                  *-version*) echo "ffmpeg version 7.1.5"; exit 0 ;;
                  *-encoders*) echo " V....D libx264 H.264"; exit 0 ;;
                  *-hwaccels*) echo "Hardware acceleration methods:"; echo "vaapi"; exit 0 ;;
                  *-filters*) echo " TSC scale V->V Scale"; exit 0 ;;
                esac
                for last; do :; done
                echo "out_time_us=1000000"
                echo "speed=2.0x"
                echo "progress=end"
                printf x > "$last"
                exit 0
                """);
        script(ffprobe, """
                #!/bin/sh
                echo '{"streams":[{"codec_type":"video"},{"codec_type":"audio"}],"format":{"duration":"2.0"}}'
                """);

        ServerConfig config = new ServerConfig();
        config.dataDir = dir.resolve("data");
        config.transcode.ffmpegPath = ffmpeg.toString();
        config.transcode.mediaRoot = media.toString();
        config.transcode.maxConcurrent = 1;

        TranscodeModule module = new TranscodeModule(config, new EventBus());
        module.feature().enable(consent());
        module.queue().start();
        try {
            var preset = module.presets().get("h264-quality").orElseThrow().preset();
            var job = module.queue().submit("input.mp4", "", "h264-quality", preset);
            module.queue().startQueue();

            String state = "";
            for (int i = 0; i < 100; i++) {
                Thread.sleep(50);
                var current = module.queue().jobJson(job.get("id").getAsString()).orElseThrow();
                state = current.get("state").getAsString();
                if ("done".equals(state) || "failed".equals(state)) {
                    break;
                }
            }
            assertEquals("done", state, module.queue().snapshot().toString());
            var jobJson = module.queue().jobJson(job.get("id").getAsString()).orElseThrow();
            Path output = media.resolve(jobJson.get("output").getAsString());
            assertTrue(Files.isRegularFile(output));
            assertTrue(Files.size(output) > 0);
            String log = module.queue().log(job.get("id").getAsString(), 100);
            assertTrue(log.contains("ffmpeg"), log);
            assertEquals(1.0, jobJson.get("fraction").getAsDouble(), 0.0001);
        } finally {
            module.queue().stop();
        }
    }
    @Test
    void fullAutoFallsBackFromVaapiToSoftware(@TempDir Path dir) throws Exception {
        Assumptions.assumeFalse(OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS,
                "the fake ffmpeg uses a POSIX shell script");

        Path media = Files.createDirectory(dir.resolve("media"));
        Files.writeString(media.resolve("input.mp4"), "source");
        Path bin = Files.createDirectory(dir.resolve("bin"));
        Path ffmpeg = bin.resolve("ffmpeg");
        Path ffprobe = bin.resolve("ffprobe");
        script(ffmpeg, """
                #!/bin/sh
                case "$*" in
                  *-version*) echo "ffmpeg version 7.1.5"; exit 0 ;;
                  *-encoders*) echo " V....D libx264 H.264"; exit 0 ;;
                  *-hwaccels*) echo "Hardware acceleration methods:"; echo "vaapi"; exit 0 ;;
                  *-filters*) echo " TSC scale V->V Scale"; exit 0 ;;
                  *hevc_vaapi*) echo "Invalid FrameType:0"; exit 1 ;;
                esac
                for last; do :; done
                echo "out_time_us=1000000"
                echo "speed=2.0x"
                echo "progress=end"
                printf x > "$last"
                exit 0
                """);
        script(ffprobe, """
                #!/bin/sh
                echo '{"streams":[{"codec_type":"video"},{"codec_type":"audio"}],"format":{"duration":"2.0"}}'
                """);

        ServerConfig config = new ServerConfig();
        config.dataDir = dir.resolve("data");
        config.transcode.ffmpegPath = ffmpeg.toString();
        config.transcode.mediaRoot = media.toString();
        config.transcode.failurePolicy = "full-auto";

        TranscodeModule module = new TranscodeModule(config, new EventBus());
        module.feature().enable(consent());
        module.queue().start();
        try {
            var preset = module.presets().get("hevc-vaapi").orElseThrow().preset();
            var job = module.queue().submit("input.mp4", "", "hevc-vaapi", preset);
            module.queue().startQueue();
            String state = "";
            for (int i = 0; i < 100; i++) {
                Thread.sleep(50);
                var current = module.queue().jobJson(job.get("id").getAsString()).orElseThrow();
                state = current.get("state").getAsString();
                if ("done".equals(state) || "failed".equals(state)) {
                    break;
                }
            }
            var finalJob = module.queue().jobJson(job.get("id").getAsString()).orElseThrow();
            assertEquals("done", state, module.queue().snapshot().toString());
            assertEquals("hevc_vaapi", finalJob.get("fallbackFrom").getAsString());
            assertEquals("libx265", finalJob.get("fallbackTo").getAsString());
            assertTrue(Files.isRegularFile(media.resolve(finalJob.get("output").getAsString())));
        } finally {
            module.queue().stop();
        }
    }

    @Test
    void timeoutAutoFallsBackAfterTheDecisionWindow(@TempDir Path dir) throws Exception {
        Assumptions.assumeFalse(OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS,
                "the fake ffmpeg uses a POSIX shell script");

        Path media = Files.createDirectory(dir.resolve("media"));
        Files.writeString(media.resolve("input.mp4"), "source");
        Path bin = Files.createDirectory(dir.resolve("bin"));
        Path ffmpeg = bin.resolve("ffmpeg");
        Path ffprobe = bin.resolve("ffprobe");
        script(ffmpeg, """
                #!/bin/sh
                case "$*" in
                  *-version*) echo "ffmpeg version 7.1.5"; exit 0 ;;
                  *-encoders*) echo " V....D libx264 H.264"; exit 0 ;;
                  *-hwaccels*) echo "Hardware acceleration methods:"; echo "vaapi"; exit 0 ;;
                  *-filters*) echo " TSC scale V->V Scale"; exit 0 ;;
                  *hevc_vaapi*) echo "Invalid FrameType:0"; exit 1 ;;
                esac
                for last; do :; done
                echo "out_time_us=1000000"
                echo "speed=2.0x"
                echo "progress=end"
                printf x > "$last"
                exit 0
                """);
        script(ffprobe, """
                #!/bin/sh
                echo '{"streams":[{"codec_type":"video"},{"codec_type":"audio"}],"format":{"duration":"2.0"}}'
                """);

        ServerConfig config = new ServerConfig();
        config.dataDir = dir.resolve("data");
        config.transcode.ffmpegPath = ffmpeg.toString();
        config.transcode.mediaRoot = media.toString();
        config.transcode.failurePolicy = "timeout-auto";
        config.transcode.failureTimeoutMinutes = 1;

        TranscodeModule module = new TranscodeModule(config, new EventBus());
        module.feature().enable(consent());
        module.queue().start();
        try {
            var preset = module.presets().get("hevc-vaapi").orElseThrow().preset();
            var job = module.queue().submit("input.mp4", "", "hevc-vaapi", preset);
            String id = job.get("id").getAsString();
            module.queue().startQueue();

            String state = "";
            for (int i = 0; i < 100; i++) {
                Thread.sleep(50);
                state = module.queue().jobJson(id).orElseThrow().get("state").getAsString();
                if ("waiting_decision".equals(state) || "failed".equals(state)) {
                    break;
                }
            }
            assertEquals("waiting_decision", state, module.queue().snapshot().toString());

            module.queue().onDecisionTimeout(id);
            for (int i = 0; i < 100; i++) {
                Thread.sleep(50);
                state = module.queue().jobJson(id).orElseThrow().get("state").getAsString();
                if ("done".equals(state) || "failed".equals(state)) {
                    break;
                }
            }
            var finalJob = module.queue().jobJson(id).orElseThrow();
            assertEquals("done", state, module.queue().snapshot().toString());
            assertEquals("libx265", finalJob.get("fallbackTo").getAsString());
        } finally {
            module.queue().stop();
        }
    }

}