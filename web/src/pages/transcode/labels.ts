import type { TranscodeSchema } from "../../api";

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
    copy: "直接复制（不重编码）",
    none: "丢弃视频",
    libx264: "H.264 软件编码（x264）",
    libx265: "H.265 软件编码（x265）",
    "libvpx-vp9": "VP9 软件编码",
    "libaom-av1": "AV1 软件编码（aom）",
    h264_nvenc: "H.264 硬件编码（NVIDIA）",
    hevc_nvenc: "H.265 硬件编码（NVIDIA）",
    h264_vaapi: "H.264 硬件编码（VAAPI）",
    hevc_vaapi: "H.265 硬件编码（VAAPI）",
    h264_qsv: "H.264 硬件编码（Intel QSV）",
    hevc_qsv: "H.265 硬件编码（Intel QSV）",
  },
  qualityMode: {
    crf: "恒定质量 CRF",
    cqp: "固定 QP CQP",
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
