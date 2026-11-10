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
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/// Turns a [TranscodePreset] into an ffmpeg argument vector.
///
/// The output is **arguments**, never a shell string: a path with a space or a quote is
/// one argv element, and no preset field can inject a second command. The caller
/// prepends the resolved ffmpeg executable and decides where the output lives; this
/// class only decides what ffmpeg is asked to do.
@NotNullByDefault
public final class CommandBuilder {

    private CommandBuilder() {
    }

    /// Builds the arguments for one job. `input` and `output` are absolute paths.
    public static List<String> build(TranscodePreset preset, Path input, Path output) {
        return build(preset, input, output, null);
    }

    /// The same, with an optional ffmetadata chapters file (already resolved under the
    /// media root by the caller).
    public static List<String> build(TranscodePreset preset, Path input, Path output, Path chapters) {
        preset.validate();
        List<String> args = new ArrayList<>();
        args.add("-hide_banner");
        args.add("-nostdin");
        args.add("-y");
        args.add("-nostats");
        args.add("-progress");
        args.add("pipe:1");

        boolean vaapi = preset.videoEncoder != null && preset.videoEncoder.endsWith("_vaapi");
        if (vaapi) {
            args.add("-vaapi_device");
            args.add("/dev/dri/renderD128");
        }
        args.addAll(preset.inputArgs);
        args.add("-i");
        args.add(input.toString());
        if (chapters != null) {
            args.add("-i");
            args.add(chapters.toString());
        }

        String video = preset.videoEncoder == null ? "" : preset.videoEncoder;
        boolean copyVideo = "copy".equals(video);
        boolean noVideo = "none".equals(video);

        if (noVideo) {
            args.add("-vn");
        } else {
            args.add("-map");
            args.add(preset.videoMap == null || preset.videoMap.isBlank() ? "0:v:0?" : preset.videoMap);
        }
        if (!"none".equals(preset.audioMode)) {
            args.add("-map");
            args.add(preset.audioMap == null || preset.audioMap.isBlank() ? "0:a?" : preset.audioMap);
        }
        if ("copy".equals(preset.subtitleMode)) {
            args.add("-map");
            args.add(preset.subtitleMap == null || preset.subtitleMap.isBlank() ? "0:s?" : preset.subtitleMap);
        }
        if (preset.copyAttachments) {
            args.add("-map");
            args.add("0:t?");
        }

        List<String> filters = filters(preset, vaapi);
        if ("burn".equals(preset.subtitleMode) && copyVideo) {
            throw new IllegalArgumentException("burning subtitles requires video re-encoding; choose an encoder instead of copy");
        }
        if (!noVideo) {
            if (copyVideo) {
                args.add("-c:v");
                args.add("copy");
            } else {
                args.add("-c:v");
                args.add(video);
                addQuality(args, preset, video);
                if (softwareEncoder(video) && !blank(preset.preset)) {
                    args.add("-preset");
                    args.add(preset.preset);
                }
                if (!blank(preset.profile)) {
                    args.add("-profile:v");
                    args.add(preset.profile);
                }
                if (!blank(preset.level)) {
                    args.add("-level");
                    args.add(preset.level);
                }
                if (!blank(preset.tune)) {
                    args.add("-tune");
                    args.add(preset.tune);
                }
                if (!blank(preset.pixelFormat)) {
                    args.add("-pix_fmt");
                    args.add(preset.pixelFormat);
                } else if (preset.tenBit) {
                    args.add("-pix_fmt");
                    args.add("yuv420p10le");
                }
                if (!blank(preset.gop)) {
                    args.add("-g");
                    args.add(preset.gop);
                }
                if (!blank(preset.threads)) {
                    args.add("-threads");
                    args.add(preset.threads);
                }
            }
            if (!filters.isEmpty()) {
                args.add("-vf");
                args.add(String.join(",", filters));
            }
            if (!blank(preset.colorPrimaries)) {
                args.add("-color_primaries");
                args.add(preset.colorPrimaries);
            }
            if (!blank(preset.colorTransfer)) {
                args.add("-color_trc");
                args.add(preset.colorTransfer);
            }
            if (!blank(preset.colorMatrix)) {
                args.add("-colorspace");
                args.add(preset.colorMatrix);
            }
            if (!blank(preset.colorRange)) {
                args.add("-color_range");
                args.add(preset.colorRange);
            }
            args.addAll(preset.videoArgs);
        }

        switch (preset.audioMode) {
            case "none" -> args.add("-an");
            case "copy" -> {
                args.add("-c:a");
                args.add("copy");
            }
            default -> {
                args.add("-c:a");
                args.add(preset.audioEncoder);
                if (!blank(preset.audioBitrate)) {
                    args.add("-b:a");
                    args.add(preset.audioBitrate);
                }
                if (!blank(preset.audioChannels)) {
                    args.add("-ac");
                    args.add(preset.audioChannels);
                }
                if (!blank(preset.audioSampleRate)) {
                    args.add("-ar");
                    args.add(preset.audioSampleRate);
                }
                args.addAll(preset.audioArgs);
            }
        }

        switch (preset.subtitleMode) {
            case "none" -> args.add("-sn");
            case "copy" -> {
                args.add("-c:s");
                args.add("copy");
            }
            default -> {
                // `burn` is handled in filters(); no subtitle stream is copied.
            }
        }
        args.addAll(preset.subtitleArgs);
        if (preset.copyAttachments) {
            args.add("-c:t");
            args.add("copy");
        }

        if (preset.copyMetadata) {
            args.add("-map_metadata");
            args.add("0");
        } else {
            args.add("-map_metadata");
            args.add("-1");
        }
        metadata(args, preset);
        args.add("-map_chapters");
        if (chapters != null) {
            args.add("1");
        } else {
            args.add(preset.copyChapters ? "0" : "-1");
        }
        if ("mp4".equalsIgnoreCase(preset.container)) {
            args.add("-movflags");
            args.add("+faststart");
        }
        args.addAll(preset.outputArgs);
        args.add(output.toString());
        return List.copyOf(args);
    }

    /// A shell-quoted display form for the preview card. It is **not** executed.
    public static String display(List<String> args) {
        StringBuilder result = new StringBuilder();
        for (String arg : args) {
            if (result.length() > 0) {
                result.append(' ');
            }
            if (arg.isEmpty() || arg.chars().anyMatch(Character::isWhitespace)) {
                result.append('"').append(arg.replace("\"", "\\\"")).append('"');
            } else {
                result.append(arg);
            }
        }
        return result.toString();
    }

    private static void metadata(List<String> args, TranscodePreset preset) {
        putMetadata(args, "title", preset.metadataTitle);
        putMetadata(args, "artist", preset.metadataArtist);
        putMetadata(args, "album", preset.metadataAlbum);
        putMetadata(args, "comment", preset.metadataComment);
        putMetadata(args, "date", preset.metadataDate);
        putMetadata(args, "genre", preset.metadataGenre);
    }

    private static void putMetadata(List<String> args, String key, String value) {
        if (value != null && !value.isBlank()) {
            args.add("-metadata");
            args.add(key + "=" + value);
        }
    }

    private static void addQuality(List<String> args, TranscodePreset preset, String encoder) {
        String mode = preset.qualityMode == null ? "crf" : preset.qualityMode;
        if ("bitrate".equals(mode)) {
            args.add("-b:v");
            args.add(preset.qualityValue);
        } else if ("cqp".equals(mode)) {
            args.add("-qp");
            args.add(preset.qualityValue);
        } else if (encoder.contains("_nvenc")) {
            args.add("-cq");
            args.add(preset.qualityValue);
        } else if (encoder.contains("_vaapi")) {
            args.add("-qp");
            args.add(preset.qualityValue);
        } else if (encoder.contains("_qsv")) {
            args.add("-global_quality");
            args.add(preset.qualityValue);
        } else {
            args.add("-crf");
            args.add(preset.qualityValue);
        }
    }

    private static List<String> filters(TranscodePreset preset, boolean vaapi) {
        List<String> filters = new ArrayList<>();
        if (!blank(preset.width) || !blank(preset.height)) {
            filters.add("scale=" + (blank(preset.width) ? "-2" : preset.width)
                    + ":" + (blank(preset.height) ? "-2" : preset.height));
        }
        if (!blank(preset.fps)) {
            filters.add("fps=" + preset.fps);
        }
        if (!blank(preset.crop)) {
            filters.add("crop=" + preset.crop);
        }
        switch (preset.rotate == null ? "" : preset.rotate) {
            case "90" -> filters.add("transpose=1");
            case "180" -> {
                filters.add("hflip");
                filters.add("vflip");
            }
            case "270" -> filters.add("transpose=2");
            default -> {
            }
        }
        if ("yadif".equals(preset.deinterlace)) {
            filters.add("yadif");
        }
        if (!blank(preset.filterGraph)) {
            filters.add(preset.filterGraph);
        }
        if ("burn".equals(preset.subtitleMode) && !blank(preset.subtitleFile)) {
            filters.add("subtitles=" + preset.subtitleFile.replace("\\", "/").replace(":", "\\:"));
        }
        if (vaapi) {
            filters.add(preset.tenBit ? "format=p010le,hwupload" : "format=nv12,hwupload");
        }
        return filters;
    }

    private static boolean softwareEncoder(String encoder) {
        return encoder.startsWith("lib") || encoder.startsWith("aom") || encoder.startsWith("svt")
                || encoder.equals("mpeg4") || encoder.equals("libvpx") || encoder.equals("libvpx-vp9");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /// Resolves the output path. The caller has already made `outputDir` absolute and
    /// inside the media root.
    public static Path outputPath(TranscodePreset preset, Path input, Path outputDir) {
        String container = preset.container == null || preset.container.isBlank()
                ? extension(input)
                : preset.container;
        String stem = stem(input);
        String suffix = switch (preset.outputNaming == null ? "suffix" : preset.outputNaming) {
            case "none" -> "";
            case "timestamp" -> "_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            default -> preset.outputSuffix == null ? "_transcoded" : preset.outputSuffix;
        };
        Path directory = outputDir == null ? input.getParent() : outputDir;
        Path candidate = directory.resolve(stem + suffix + "." + container);
        int counter = 1;
        while (java.nio.file.Files.exists(candidate) && counter < 10_000) {
            candidate = directory.resolve(stem + suffix + "_" + counter + "." + container);
            counter++;
        }
        return candidate;
    }

    private static String stem(Path input) {
        String name = input.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String extension(Path input) {
        String name = input.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1) : "mkv";
    }
}