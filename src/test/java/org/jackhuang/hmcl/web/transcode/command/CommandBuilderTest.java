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
package org.jackhuang.hmcl.web.transcode.command;

import org.jackhuang.hmcl.web.transcode.preset.TranscodePreset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandBuilderTest {

    private static TranscodePreset preset() {
        TranscodePreset preset = new TranscodePreset();
        preset.name = "test";
        preset.container = "mkv";
        preset.videoEncoder = "libx264";
        preset.qualityMode = "crf";
        preset.qualityValue = "20";
        preset.preset = "medium";
        preset.audioMode = "copy";
        preset.subtitleMode = "copy";
        return preset;
    }

    private static String joined(List<String> args) {
        return String.join(" ", args);
    }

    @Test
    void buildsAnH264JobWithStreamMapping(@TempDir Path dir) {
        var args = CommandBuilder.build(preset(), dir.resolve("in.mkv"), dir.resolve("out.mkv"));
        String command = joined(args);
        assertTrue(command.contains("-c:v libx264"));
        assertTrue(command.contains("-crf 20"));
        assertTrue(command.contains("-preset medium"));
        assertTrue(command.contains("-map 0:v:0?"));
        assertTrue(command.contains("-map 0:a?"));
        assertTrue(command.contains("-c:a copy"));
        assertTrue(command.contains("-c:s copy"));
        assertTrue(command.contains("-map_metadata 0"));
        assertTrue(args.get(args.size() - 1).endsWith("out.mkv"));
    }

    @Test
    void copyIsARemuxNotAnEncoder(@TempDir Path dir) {
        TranscodePreset preset = preset();
        preset.videoEncoder = "copy";
        var args = CommandBuilder.build(preset, dir.resolve("in.mkv"), dir.resolve("out.mkv"));
        String command = joined(args);
        assertTrue(command.contains("-c:v copy"));
        assertTrue(command.contains("-c:a copy"));
        assertTrue(command.contains("-c:s copy"));
        assertTrue(!command.contains("-crf"));
    }

    @Test
    void audioOnlyDropsVideo(@TempDir Path dir) {
        TranscodePreset preset = preset();
        preset.container = "m4a";
        preset.videoEncoder = "none";
        preset.audioMode = "encode";
        preset.audioEncoder = "aac";
        preset.audioBitrate = "192k";
        preset.subtitleMode = "none";
        var args = CommandBuilder.build(preset, dir.resolve("in.mkv"), dir.resolve("out.m4a"));
        String command = joined(args);
        assertTrue(command.contains("-vn"));
        assertTrue(command.contains("-c:a aac"));
        assertTrue(command.contains("-b:a 192k"));
        assertTrue(command.contains("-sn"));
    }

    @Test
    void aPathWithSpacesStaysOneArgument(@TempDir Path dir) {
        Path input = dir.resolve("my movie.mkv");
        var args = CommandBuilder.build(preset(), input, dir.resolve("out.mkv"));
        assertTrue(args.contains(input.toString()));
        assertTrue(CommandBuilder.display(args).contains("my movie.mkv"));
        assertTrue(CommandBuilder.display(args).contains("\""));
    }

    @Test
    void outputNamesAvoidCollisions(@TempDir Path dir) throws Exception {
        TranscodePreset preset = preset();
        Path input = dir.resolve("clip.mp4");
        Files.writeString(input, "x");
        Path first = CommandBuilder.outputPath(preset, input, dir);
        assertEquals("clip_transcoded.mkv", first.getFileName().toString());
        Files.writeString(first, "x");
        Path second = CommandBuilder.outputPath(preset, input, dir);
        assertEquals("clip_transcoded_1.mkv", second.getFileName().toString());
    }

    @Test
    void burningSubtitlesRefusesACopyVideo(@TempDir Path dir) {
        TranscodePreset preset = preset();
        preset.videoEncoder = "copy";
        preset.subtitleMode = "burn";
        preset.subtitleFile = "subs.srt";
        assertThrows(IllegalArgumentException.class,
                () -> CommandBuilder.build(preset, dir.resolve("in.mkv"), dir.resolve("out.mkv")));
    }
    @Test
    void writesMetadataAttachmentsAndChapters(@TempDir Path dir) {
        TranscodePreset preset = preset();
        preset.metadataTitle = "My film";
        preset.metadataArtist = "Someone";
        preset.copyAttachments = true;
        Path chapters = dir.resolve("chapters.txt");
        var args = CommandBuilder.build(preset, dir.resolve("in.mkv"), dir.resolve("out.mkv"), chapters);
        String command = joined(args);
        assertTrue(command.contains("-metadata title=My film"));
        assertTrue(command.contains("-metadata artist=Someone"));
        assertTrue(command.contains("-map 0:t?"));
        assertTrue(command.contains("-c:t copy"));
        assertTrue(command.contains(chapters.toString()));
        assertTrue(command.contains("-map_chapters 1"));
    }

}