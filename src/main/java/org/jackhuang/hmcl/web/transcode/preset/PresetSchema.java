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
package org.jackhuang.hmcl.web.transcode.preset;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNullByDefault;

/// The field metadata the preset form renders.
///
/// This is the contract between the generic React form and the Java preset model. Adding
/// a field means adding it to [TranscodePreset], the [CommandBuilder] and one entry here;
/// the frontend does not need a bespoke component for it.
@NotNullByDefault
public final class PresetSchema {

    private PresetSchema() {
    }

    public static JsonObject json() {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        JsonArray sections = new JsonArray();

        sections.add(section("输出", new JsonArray[]{
                fields(
                        select("container", "输出容器", "mkv", "mkv", "mp4", "mov", "webm", "avi", "ts", "m4a"),
                        select("outputNaming", "自动命名", "suffix", "suffix", "timestamp", "none"),
                        text("outputSuffix", "文件名后缀", "_transcoded"),
                        text("outputDir", "输出目录（相对媒体根；空 = 源目录）", "")
                )
        }));

        sections.add(section("视频", new JsonArray[]{
                fields(
                        select("videoEncoder", "视频编码器", "libx264",
                                "copy", "none", "libx264", "libx265", "libvpx-vp9", "libaom-av1",
                                "h264_nvenc", "hevc_nvenc", "h264_vaapi", "hevc_vaapi",
                                "h264_qsv", "hevc_qsv"),
                        select("qualityMode", "质量模式", "crf", "crf", "cqp", "bitrate"),
                        text("qualityValue", "质量值 / 码率", "20"),
                        select("preset", "编码 preset", "medium",
                                "", "ultrafast", "superfast", "veryfast", "faster", "fast",
                                "medium", "slow", "slower", "veryslow"),
                        text("profile", "profile", ""),
                        text("level", "level", ""),
                        text("tune", "tune", ""),
                        select("pixelFormat", "像素格式", "",
                                "", "yuv420p", "yuv422p", "yuv444p", "yuv420p10le", "yuv422p10le", "yuv444p10le", "p010le"),
                        booleanField("tenBit", "10-bit（未指定像素格式时）", false),
                        text("gop", "GOP", ""),
                        text("threads", "线程数", ""),
                        text("videoMap", "视频流 map", "0:v:0?")
                )
        }));

        sections.add(section("画面帧 / 色彩", new JsonArray[]{
                fields(
                        text("width", "宽", ""),
                        text("height", "高", ""),
                        text("fps", "帧率", ""),
                        text("crop", "裁剪（w:h:x:y）", ""),
                        select("rotate", "旋转", "", "", "90", "180", "270"),
                        select("deinterlace", "去隔行", "", "", "yadif"),
                        select("colorPrimaries", "primaries", "",
                                "", "bt709", "bt2020", "smpte170m", "smpte240m"),
                        select("colorTransfer", "transfer", "",
                                "", "bt709", "smpte2084", "arib-std-b67", "bt2020-10", "bt2020-12"),
                        select("colorMatrix", "matrix", "",
                                "", "bt709", "bt2020nc", "smpte170m", "smpte240m"),
                        select("colorRange", "range", "", "", "tv", "pc")
                )
        }));

        sections.add(section("音频", new JsonArray[]{
                fields(
                        select("audioMode", "音频模式", "copy", "copy", "encode", "none"),
                        select("audioEncoder", "音频编码器", "aac", "aac", "libopus", "libmp3lame", "flac", "ac3", "eac3"),
                        text("audioBitrate", "音频码率", "192k"),
                        text("audioChannels", "声道数", ""),
                        text("audioSampleRate", "采样率", ""),
                        text("audioMap", "音频流 map", "0:a?")
                )
        }));

        sections.add(section("字幕", new JsonArray[]{
                fields(
                        select("subtitleMode", "字幕模式", "copy", "copy", "burn", "none"),
                        text("subtitleFile", "烧录字幕文件（相对媒体根）", ""),
                        text("subtitleFont", "字体", ""),
                        text("subtitleFontSize", "字号", ""),
                        text("subtitleMap", "字幕流 map", "0:s?")
                )
        }));

        sections.add(section("元数据 / 章节 / 附件", new JsonArray[]{
                fields(
                        text("metadataTitle", "标题", ""),
                        text("metadataArtist", "艺术家", ""),
                        text("metadataAlbum", "专辑", ""),
                        text("metadataComment", "备注", ""),
                        text("metadataDate", "日期", ""),
                        text("metadataGenre", "流派", ""),
                        text("chaptersFile", "章节文件（ffmetadata，相对媒体根）", ""),
                        booleanField("copyMetadata", "复制源元数据", true),
                        booleanField("copyChapters", "复制源章节", true),
                        booleanField("copyAttachments", "复制附件（字体等）", true)
                )
        }));

        sections.add(section("滤镜 / 高级", new JsonArray[]{
                fields(
                        textarea("filterGraph", "附加滤镜链", "")
                )
        }));

        root.add("sections", sections);
        return root;
    }

    private static JsonObject section(String title, JsonArray[] groups) {
        JsonObject section = new JsonObject();
        section.addProperty("title", title);
        JsonArray fields = new JsonArray();
        for (JsonArray group : groups) {
            fields.addAll(group);
        }
        section.add("fields", fields);
        return section;
    }

    private static JsonArray fields(JsonObject... fields) {
        JsonArray array = new JsonArray();
        for (JsonObject field : fields) {
            array.add(field);
        }
        return array;
    }

    private static JsonObject text(String id, String label, String value) {
        JsonObject field = base(id, label, "text");
        field.addProperty("default", value);
        return field;
    }

    private static JsonObject select(String id, String label, String value, String... options) {
        JsonObject field = base(id, label, "select");
        field.addProperty("default", value);
        JsonArray array = new JsonArray();
        for (String option : options) {
            array.add(option);
        }
        field.add("options", array);
        return field;
    }

    private static JsonObject textarea(String id, String label, String value) {
        JsonObject field = base(id, label, "textarea");
        field.addProperty("default", value);
        return field;
    }

    private static JsonObject booleanField(String id, String label, boolean value) {
        JsonObject field = base(id, label, "switch");
        field.addProperty("default", value);
        return field;
    }

    private static JsonObject base(String id, String label, String type) {
        JsonObject field = new JsonObject();
        field.addProperty("id", id);
        field.addProperty("label", label);
        field.addProperty("type", type);
        return field;
    }
}