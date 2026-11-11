import type { TranscodePreset, TranscodeSchema } from "../../api";

/**
 * 预设里保存的是 ffmpeg 的原始取值（libx264 / crf / copy …）。
 * 面板里给它们配中文短名：下拉框收起时、参数总览、预设预览都显示中文，
 * 原始取值放在下拉项右侧或悬停提示里，方便对照命令行。
 */
const VALUE_LABELS: Record<string, Record<string, string>> = {
  container: {
    mkv: "MKV",
    mp4: "MP4",
    mov: "MOV",
    webm: "WebM",
    avi: "AVI",
    ts: "MPEG-TS",
    m4a: "M4A（纯音频）",
  },
  outputNaming: {
    suffix: "追加后缀",
    timestamp: "时间戳",
    none: "不追加",
  },
  videoEncoder: {
    copy: "直接复制",
    none: "丢弃视频",
    libx264: "H.264（x264）",
    libx265: "H.265（x265）",
    "libvpx-vp9": "VP9（libvpx）",
    "libaom-av1": "AV1（aom）",
    h264_nvenc: "H.264（NVENC）",
    hevc_nvenc: "H.265（NVENC）",
    h264_vaapi: "H.264（VAAPI）",
    hevc_vaapi: "H.265（VAAPI）",
    h264_qsv: "H.264（QSV）",
    hevc_qsv: "H.265（QSV）",
  },
  qualityMode: {
    crf: "CRF 恒定质量",
    cqp: "CQP 固定 QP",
    bitrate: "固定码率",
  },
  preset: {
    "": "自动",
    ultrafast: "极快",
    superfast: "超快",
    veryfast: "很快",
    faster: "较快",
    fast: "快",
    medium: "平衡",
    slow: "慢",
    slower: "更慢",
    veryslow: "最慢",
    p1: "P1（最快）",
    p2: "P2（更快）",
    p3: "P3（快）",
    p4: "P4（平衡）",
    p5: "P5（慢）",
    p6: "P6（更慢）",
    p7: "P7（最慢）",
    "0": "0（最慢，最好）",
    "1": "1（更慢）",
    "2": "2（慢）",
    "3": "3（略慢）",
    "4": "4（平衡）",
    "5": "5（略快）",
    "6": "6（快）",
    "7": "7（更快）",
    "8": "8（最快）",
  },
  pixelFormat: {
    "": "跟随源",
    yuv420p: "8-bit 4:2:0",
    yuv422p: "8-bit 4:2:2",
    yuv444p: "8-bit 4:4:4",
    yuv420p10le: "10-bit 4:2:0",
    yuv422p10le: "10-bit 4:2:2",
    yuv444p10le: "10-bit 4:4:4",
    p010le: "10-bit P010（HDR）",
  },
  rotate: {
    "": "不旋转",
    "90": "顺时针 90°",
    "180": "旋转 180°",
    "270": "逆时针 90°",
  },
  deinterlace: {
    "": "不去隔行",
    yadif: "yadif 去隔行",
  },
  colorPrimaries: {
    "": "跟随源",
    bt709: "BT.709（SDR）",
    bt2020: "BT.2020（HDR 广色域）",
    smpte170m: "SMPTE 170M（NTSC/SD）",
    smpte240m: "SMPTE 240M（老 HD）",
  },
  colorTransfer: {
    "": "跟随源",
    bt709: "BT.709（SDR）",
    smpte2084: "PQ（HDR10）",
    "arib-std-b67": "HLG（广播 HDR）",
    "bt2020-10": "BT.2020 10-bit",
    "bt2020-12": "BT.2020 12-bit",
  },
  colorMatrix: {
    "": "跟随源",
    bt709: "BT.709（SDR）",
    bt2020nc: "BT.2020 非恒定（HDR）",
    smpte170m: "SMPTE 170M（NTSC/SD）",
    smpte240m: "SMPTE 240M（老 HD）",
  },
  colorRange: {
    "": "跟随源",
    tv: "有限范围 TV（16–235）",
    pc: "全范围 PC（0–255）",
  },
  audioMode: {
    copy: "保留原音轨",
    encode: "重新编码",
    none: "丢弃音频",
  },
  audioEncoder: {
    aac: "AAC",
    libopus: "Opus",
    libmp3lame: "MP3",
    flac: "FLAC（无损）",
    ac3: "AC-3",
    eac3: "E-AC-3",
  },
  subtitleMode: {
    copy: "保留字幕轨",
    burn: "烧录进画面",
    none: "丢弃字幕",
  },
};

/// 中文短名；没有映射时退回原始取值（空值显示“（默认）”）。
export function valueLabel(fieldId: string, value: unknown): string {
  const raw = String(value ?? "");
  const labellings = VALUE_LABELS[fieldId];
  const label = labellings?.[raw];
  if (label) return label;
  return raw === "" ? "（默认）" : raw;
}

/// 同一个字段里“原始取值 + 中文说明”，用于悬停提示。
export function valueTip(
  schema: TranscodeSchema | null,
  fieldId: string,
  value: unknown,
  extraHint?: string,
): string {
  const raw = String(value ?? "");
  const field = schema?.sections.flatMap((section) => section.fields).find((item) => item.id === fieldId);
  const description = field?.optionHints?.[raw] ?? extraHint ?? field?.hint ?? "";
  const head = raw === "" ? "（未设置，使用默认值）" : `原始取值 ${raw}`;
  return description ? `${head}\n${description}` : head;
}

// 依赖别的选项才有意义的字段：视频不重新编码时，画面/质量/编码档位全部不生效。
const VIDEO_ENCODER_FIELDS = new Set([
  "qualityMode", "qualityValue", "preset", "profile", "level", "tune",
  "pixelFormat", "tenBit", "gop", "threads",
  "width", "height", "fps", "crop", "rotate", "deinterlace", "filterGraph",
  "colorPrimaries", "colorTransfer", "colorMatrix", "colorRange",
]);
const HARDWARE_IGNORED_FIELDS = new Set(["profile", "tune"]);
const AUDIO_ENCODE_FIELDS = new Set(["audioEncoder", "audioBitrate", "audioChannels", "audioSampleRate"]);
const SUBTITLE_BURN_FIELDS = new Set(["subtitleFile", "subtitleFont", "subtitleFontSize"]);

/// 各编码器家族的速度档：可选值与切换时的默认档。
/// 软件 x264/x265 与 QSV 用命名档位，NVENC 用 p1–p7，H.264 VAAPI 用数值 quality（越大越快），
/// HEVC VAAPI 在当前 ffmpeg 里没有速度控制。
const SPEED_PRESETS: Record<string, { values: string[]; fallback: string }> = {
  software: {
    values: ["", "ultrafast", "superfast", "veryfast", "faster", "fast", "medium", "slow", "slower", "veryslow"],
    fallback: "medium",
  },
  nvenc: { values: ["", "p1", "p2", "p3", "p4", "p5", "p6", "p7"], fallback: "p4" },
  qsv: { values: ["", "veryfast", "faster", "fast", "medium", "slow", "slower", "veryslow"], fallback: "medium" },
  vaapi: { values: ["", "0", "1", "2", "3", "4", "5", "6", "7", "8"], fallback: "" },
  vaapiHevc: { values: [], fallback: "" },
};

export function speedGroup(encoder: string): string {
  if (encoder.endsWith("_nvenc")) return "nvenc";
  if (encoder.endsWith("_qsv")) return "qsv";
  if (encoder === "h264_vaapi") return "vaapi";
  if (encoder === "hevc_vaapi") return "vaapiHevc";
  return "software";
}

/// 当前编码器可用的速度档；schema 的 optionGroups 是权威列表，拿不到时退回默认列表。
export function speedPresetOptions(fieldOptions: string[] | undefined, encoder: string): string[] {
  return SPEED_PRESETS[speedGroup(encoder)]?.values ?? fieldOptions ?? [];
}

/// 切换编码器后把旧速度档换成新编码器能接受的值；不认识就落到该家族的默认档。
export function remapSpeedPreset(encoder: string, current: unknown): string {
  const group = SPEED_PRESETS[speedGroup(encoder)];
  if (!group || group.values.length === 0) return "";
  const value = String(current ?? "");
  return group.values.includes(value) ? value : group.fallback;
}

/// 返回空串表示可编辑；否则是「为什么变灰」的说明，同时显示在字段下方。
export function inactiveReason(fieldId: string, preset: TranscodePreset): string {
  const encoder = String(preset.videoEncoder ?? "");
  const hardware = /_vaapi|_qsv|_nvenc/.test(encoder);
  if ((encoder === "copy" || encoder === "none") && VIDEO_ENCODER_FIELDS.has(fieldId)) {
    return encoder === "none" ? "视频已丢弃，此项不生效" : "不重新编码，此项不生效";
  }
  if (fieldId === "preset" && encoder === "hevc_vaapi") {
    return "此 VAAPI 编码器不提供速度档";
  }
  if (hardware && HARDWARE_IGNORED_FIELDS.has(fieldId)) {
    return "硬件编码忽略此项";
  }
  if (String(preset.audioMode ?? "copy") !== "encode" && AUDIO_ENCODE_FIELDS.has(fieldId)) {
    return "音频未重新编码，此项不生效";
  }
  if (String(preset.subtitleMode ?? "copy") !== "burn" && SUBTITLE_BURN_FIELDS.has(fieldId)) {
    return "字幕未烧录，此项不生效";
  }
  return "";
}
