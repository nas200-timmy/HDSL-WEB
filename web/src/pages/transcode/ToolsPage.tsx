import { useState } from "react";
import { api, type TranscodeMediaEntry } from "../../api";
import { errMsg } from "../../utils";
import { MediaBrowserPanel, probeSummary } from "./common";

export function ToolsPage() {
  const [selected, setSelected] = useState<TranscodeMediaEntry | null>(null);
  const [probe, setProbe] = useState<Record<string, unknown> | null>(null);
  const [error, setError] = useState<string | null>(null);

  const select = async (entry: TranscodeMediaEntry) => {
    setSelected(entry);
    setProbe(null);
    setError(null);
    try {
      setProbe(await api.transcode.mediaProbe(entry.path));
    } catch (e) {
      setError(errMsg(e));
    }
  };

  return (
    <div style={{ display: "grid", gap: 8 }}>
      <div style={{ display: "flex", gap: 8, alignItems: "flex-start", flexWrap: "wrap" }}>
        <div style={{ flex: "1 1 380px", minWidth: 0 }}>
          <MediaBrowserPanel selected={selected?.path} onSelect={(entry) => void select(entry)} />
        </div>
        <div className="card" style={{ flex: "1 1 380px", minWidth: 0 }}>
          <div className="comp-row">
            <span className="comp-label">媒体信息</span>
            <span className="comp-value mono" style={{ fontSize: 11, overflowWrap: "anywhere" }}>
              {selected?.path || "从左侧选择"}
            </span>
          </div>
          {probe && (
            <div className="comp-row">
              <span className="comp-label">摘要</span>
              <span className="comp-value" style={{ fontSize: 12 }}>{probeSummary(probe)}</span>
            </div>
          )}
          {error && (
            <div className="comp-row">
              <span className="comp-label" style={{ color: "var(--monet-error)" }}>错误</span>
              <span className="comp-value" style={{ fontSize: 12 }}>{error}</span>
            </div>
          )}
          <pre
            style={{
              margin: "4px 8px 8px",
              padding: 8,
              maxHeight: 360,
              overflow: "auto",
              fontSize: 11,
              lineHeight: 1.45,
              background: "var(--monet-surface-container-low)",
              whiteSpace: "pre-wrap",
              wordBreak: "break-all",
            }}
          >
            {probe ? JSON.stringify(probe, null, 2) : "选择文件后显示 ffprobe JSON"}
          </pre>
        </div>
      </div>

      <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
        {[
          ["合并", "把多个片段按顺序拼接，输出一个新文件。"],
          ["混流", "多输入轨道布置：视频、音频、字幕、附件。"],
          ["抽流", "从文件里提取视频 / 音频 / 字幕。"],
          ["质量评测", "PSNR / SSIM / XPSNR / VMAF 与逐帧曲线。"],
        ].map(([name, description]) => (
          <div className="card" key={name} style={{ flex: "1 1 220px", minWidth: 200 }}>
            <div className="comp-row">
              <span className="comp-label">{name}</span>
              <span className="comp-value"><span className="tag plain">建设中</span></span>
            </div>
            <p style={{ fontSize: 12, padding: "4px 8px 8px", lineHeight: 1.6, color: "var(--monet-on-surface-variant)" }}>
              {description}
            </p>
          </div>
        ))}
      </div>
    </div>
  );
}