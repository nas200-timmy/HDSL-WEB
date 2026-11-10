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
///
/// `hint` is the one-line explanation under a control (the web port of 3FUI's per-field
/// description). `important` marks the fields the overview and the beginner flow read.
/// `optionHints` explains individual select values without changing the wire format.
@NotNullByDefault
public final class PresetSchema {

    private PresetSchema() {
    }

    public static JsonObject json() {
        JsonObject root = new JsonObject();
        root.addProperty("version", 2);
        JsonArray sections = new JsonArray();

        sections.add(section("输出", new JsonArray[]{
                fields(
                        hint(select("container", "输出容器", "mkv", "mkv", "mp4", "mov", "webm", "avi", "ts", "m4a"),
                                "mkv 能装下全部音轨/字幕/附件；mp4 兼容性最好但字幕与附件受限",
                                "mkv", "能装下全部轨道（推荐）",
                                "mp4", "兼容性最好；字幕只能 mov_text，附件装不下",
                                "mov", "与 mp4 类似，剪辑生态更常见",
                                "webm", "只支持 VP9/AV1 + Opus/Vorbis；别装 AAC",
                                "avi", "老设备兼容；字幕/章节支持很差",
                                "ts", "电视/直播流；容错好但体积大",
                                "m4a", "纯音频容器"),
                        important(),
                        hint(select("outputNaming", "自动命名", "suffix", "suffix", "timestamp", "none"),
                                "suffix 追加后缀；timestamp 带日期时间；none 直接同名（会撞已有文件）",
                                "suffix", "追加固定后缀（推荐）",
                                "timestamp", "追加 yyyyMMdd-HHmmss",
                                "none", "不加后缀，可能覆盖同名输出"),
                        hint(text("outputSuffix", "文件名后缀", "_transcoded"),
                                "自动命名选 suffix 时使用"),
                        hint(text("outputDir", "输出目录（相对媒体根；空 = 源目录）", ""),
                                "留空输出到输入文件旁边；填相对路径则必须有写权限")
                )
        }));

        sections.add(section("编码器与质量", new JsonArray[]{
                fields(
                        hint(select("videoEncoder", "视频编码器", "libx264",
                                "copy", "none", "libx264", "libx265", "libvpx-vp9", "libaom-av1",
                                "h264_nvenc", "hevc_nvenc", "h264_vaapi", "hevc_vaapi",
                                "h264_qsv", "hevc_qsv"),
                                "copy 不重编码；x264 兼容性最好；x265 同画质更小；VAAPI/QSV 走核显",
                                "copy", "直接复制视频流，不重编码",
                                "none", "丢弃视频（纯音频输出）",
                                "libx264", "H.264 软件编码，兼容性最好",
                                "libx265", "H.265 软件编码，同画质约小 30–50%",
                                "libvpx-vp9", "VP9 软件编码，WebM 生态",
                                "libaom-av1", "AV1 软件编码，压缩强、速度慢",
                                "h264_nvenc", "NVIDIA 硬件 H.264",
                                "hevc_nvenc", "NVIDIA 硬件 H.265",
                                "h264_vaapi", "Intel/AMD 核显 H.264",
                                "hevc_vaapi", "Intel/AMD 核显 H.265（推荐硬编）",
                                "h264_qsv", "Intel QSV H.264",
                                "hevc_qsv", "Intel QSV H.265（部分平台不可用）"),
                        important(),
                        hint(select("qualityMode", "质量模式", "crf", "crf", "cqp", "bitrate"),
                                "crf 恒定质量（软件编码推荐）；cqp 固定 QP（硬件）；bitrate 固定码率",
                                "crf", "恒定质量，越小越清晰（推荐）",
                                "cqp", "固定 QP，硬件编码常用",
                                "bitrate", "固定码率，按目标体积/带宽"),
                        hint(text("qualityValue", "质量值 / 码率", "20"),
                                "x264/x265 CRF 常用 18–28；硬件 CQP 常用 20–28；bitrate 写 2500k 或 5M"),
                        important(),
                        hint(select("preset", "编码 preset", "medium",
                                "", "ultrafast", "superfast", "veryfast", "faster", "fast",
                                "medium", "slow", "slower", "veryslow"),
                                "软件编码的速度/压缩档；越慢同画质越小。硬件编码不支持时会被自动清空",
                                "", "自动",
                                "ultrafast", "最快，体积最大",
                                "medium", "默认平衡",
                                "slow", "更小体积，更慢",
                                "veryslow", "最慢，压缩最好"),
                        hint(text("profile", "profile", ""), "留空自动；H.264 常用 high，H.265 常用 main"),
                        hint(text("level", "level", ""), "留空自动；设备兼容问题时可指定 4.1/5.1"),
                        hint(text("tune", "tune", ""), "留空自动；x264 可用 film/animation/grain"),
                        hint(select("pixelFormat", "像素格式", "",
                                "", "yuv420p", "yuv422p", "yuv444p", "yuv420p10le", "yuv422p10le", "yuv444p10le", "p010le"),
                                "留空跟随源；8-bit 用 yuv420p；10-bit HDR 用 yuv420p10le / p010le"),
                        hint(booleanField("tenBit", "10-bit（未指定像素格式时）", false),
                                "只在像素格式留空时生效；会转 yuv420p10le"),
                        hint(text("gop", "GOP", ""), "关键帧间隔；留空由编码器决定"),
                        hint(text("threads", "线程数", ""), "留空自动；限制 CPU 占用时可填 4/6"),
                        hint(text("videoMap", "视频流 map", "0:v:0?"),
                                "0:v:0? 第一条视频；0:v? 保留全部视频流（copy 时常用）")
                )
        }));

        sections.add(section("画面帧", new JsonArray[]{
                fields(
                        hint(text("width", "宽", ""), "留空跟随源；只填一边另一边按比例"),
                        hint(text("height", "高", ""), "留空跟随源；常用 1080 / 720"),
                        hint(text("fps", "帧率", ""), "留空跟随源；可写 30 或 30000/1001"),
                        hint(text("crop", "裁剪（w:h:x:y）", ""), "例 1920:800:0:140（宽:高:左上角 x:y）"),
                        hint(select("rotate", "旋转", "", "", "90", "180", "270"), "顺时针旋转画面"),
                        hint(select("deinterlace", "去隔行", "", "", "yadif"), "DVD/电视源用 yadif")
                )
        }));

        sections.add(section("色彩", new JsonArray[]{
                fields(
                        hint(select("colorPrimaries", "primaries", "",
                                "", "bt709", "bt2020", "smpte170m", "smpte240m"), "SDR 用 bt709；HDR 用 bt2020"),
                        hint(select("colorTransfer", "transfer", "",
                                "", "bt709", "smpte2084", "arib-std-b67", "bt2020-10", "bt2020-12"),
                                "HDR10 用 smpte2084；HLG 用 arib-std-b67"),
                        hint(select("colorMatrix", "matrix", "",
                                "", "bt709", "bt2020nc", "smpte170m", "smpte240m"), "HDR 用 bt2020nc"),
                        hint(select("colorRange", "range", "", "", "tv", "pc"), "视频通常 tv（有限范围）")
                )
        }));

        sections.add(section("音频", new JsonArray[]{
                fields(
                        hint(select("audioMode", "音频模式", "copy", "copy", "encode", "none"),
                                "copy 原样保留全部音轨（不重编码）；encode 重编码；none 丢弃音频",
                                "copy", "保留全部音轨，不重编码（推荐）",
                                "encode", "按下面的编码器/码率重编码",
                                "none", "丢弃音频"),
                        important(),
                        hint(select("audioEncoder", "音频编码器", "aac", "aac", "libopus", "libmp3lame", "flac", "ac3", "eac3"),
                                "encode 时生效；mp4 常用 aac，mkv 可用 flac/opus"),
                        hint(text("audioBitrate", "音频码率", "192k"), "encode 时生效，例 192k / 320k"),
                        hint(text("audioChannels", "声道数", ""), "留空跟随源；2=立体声，6=5.1"),
                        hint(text("audioSampleRate", "采样率", ""), "留空跟随源；常用 48000"),
                        hint(text("audioMap", "音频流 map", "0:a?"),
                                "0:a? 全部音轨；只要第一条写 0:a:0?")
                )
        }));

        sections.add(section("字幕", new JsonArray[]{
                fields(
                        hint(select("subtitleMode", "字幕模式", "copy", "copy", "burn", "none"),
                                "copy 原样保留字幕轨；burn 烧进画面；none 丢弃",
                                "copy", "保留字幕轨，不烧录（推荐）",
                                "burn", "烧录到画面，兼容一切播放器",
                                "none", "丢弃字幕"),
                        important(),
                        hint(text("subtitleFile", "烧录字幕文件（相对媒体根）", ""),
                                "只在 burn 时使用；ASS/SRT 都行，中文字体缺失会显示方块"),
                        hint(text("subtitleFont", "字体", ""), "burn 时生效，例 Noto Sans CJK SC"),
                        hint(text("subtitleFontSize", "字号", ""), "burn 时生效，例 24"),
                        hint(text("subtitleMap", "字幕流 map", "0:s?"), "0:s? 全部字幕；0:s:0? 第一条")
                )
        }));

        sections.add(section("元数据 / 章节 / 附件", new JsonArray[]{
                fields(
                        hint(text("metadataTitle", "标题", ""), "留空不写入"),
                        hint(text("metadataArtist", "艺术家", ""), "留空不写入"),
                        hint(text("metadataAlbum", "专辑", ""), "留空不写入"),
                        hint(text("metadataComment", "备注", ""), "留空不写入"),
                        hint(text("metadataDate", "日期", ""), "留空不写入"),
                        hint(text("metadataGenre", "流派", ""), "留空不写入"),
                        hint(text("chaptersFile", "章节文件（ffmetadata，相对媒体根）", ""),
                                "留空沿用源章节；填写则用这个 ffmetadata 文件"),
                        hint(booleanField("copyMetadata", "复制源元数据", true), "保留标题/演员等刮削信息"),
                        hint(booleanField("copyChapters", "复制源章节", true), "保留章节标记"),
                        hint(booleanField("copyAttachments", "复制附件（字体等）", true),
                                "MKV 里的字体/封面；mp4 装不下，入队前会被拦下")
                )
        }));

        sections.add(section("滤镜 / 高级", new JsonArray[]{
                fields(
                        hint(textarea("filterGraph", "附加滤镜链", ""),
                                "逗号分隔的 ffmpeg 滤镜，例 scale=-2:1080,yadif；留空不加滤镜")
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
            for (int i = 0; i < group.size(); i++) {
                JsonObject field = group.get(i).getAsJsonObject();
                // `important()` is a marker: it raises the flag on the field declared
                // immediately before it, so the schema literals stay readable.
                if (field.has("__important")) {
                    if (fields.size() > 0) {
                        fields.get(fields.size() - 1).getAsJsonObject().addProperty("important", true);
                    }
                    continue;
                }
                fields.add(field);
            }
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

    private static JsonObject hint(JsonObject field, String hint, String... optionHints) {
        field.addProperty("hint", hint);
        if (optionHints.length >= 2) {
            JsonObject map = new JsonObject();
            for (int i = 0; i + 1 < optionHints.length; i += 2) {
                map.addProperty(optionHints[i], optionHints[i + 1]);
            }
            field.add("optionHints", map);
        }
        return field;
    }

    private static JsonObject important() {
        JsonObject marker = new JsonObject();
        marker.addProperty("__important", true);
        return marker;
    }

    private static JsonObject base(String id, String label, String type) {
        JsonObject field = new JsonObject();
        field.addProperty("id", id);
        field.addProperty("label", label);
        field.addProperty("type", type);
        return field;
    }
}
