import { useState } from "react";
import { api, type TranscodeMediaEntry } from "../../api";
import { toast } from "../../store";
import { errMsg } from "../../utils";
import { MediaBrowserPanel, probeSummary } from "./common";

export function ToolsPage({ onOpenTab }: { onOpenTab: (tab: string) => void }) {
  const [selected, setSelected] = useState<TranscodeMediaEntry | null>(null);
  const [probe, setProbe] = useState<Record<string, unknown> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const [mergeInputs, setMergeInputs] = useState("");
  const [mergeOutput, setMergeOutput] = useState("merged.mkv");
  const [muxInputs, setMuxInputs] = useState("");
  const [muxMaps, setMuxMaps] = useState("0:v:0\n1:a:0");
  const [muxOutput, setMuxOutput] = useState("muxed.mkv");
  const [extractInput, setExtractInput] = useState("");
  const [extractType, setExtractType] = useState<"video" | "audio" | "subtitle">("video");
  const [extractIndex, setExtractIndex] = useState(0);
  const [extractOutput, setExtractOutput] = useState("stream.mkv");

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

  const lines = (text: string) => text.split(/\r?\n/).map((line) => line.trim()).filter(Boolean);

  const run = async (call: () => Promise<unknown>, label: string) => {
    setBusy(true);
    setError(null);
    try {
      await call();
      toast("success", `${label}已加入队列`);
      onOpenTab("queue");
    } catch (e) {
      setError(errMsg(e));
      toast("error", errMsg(e));
    } finally {
      setBusy(false);
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
              maxHeight: 300,
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

      <div style={{ display: "flex", gap: 8, flexWrap: "wrap", alignItems: "flex-start" }}>
        <div className="card" style={{ flex: "1 1 300px", minWidth: 280 }}>
          <div className="comp-row"><span className="comp-label">合并</span><span className="comp-value">concat · copy</span></div>
          <div style={{ display: "grid", gap: 5, padding: "4px 8px 8px" }}>
            <label style={{ fontSize: 12 }}>输入（每行一个，相对媒体根）</label>
            <textarea className="input" rows={4} value={mergeInputs} onChange={(e) => setMergeInputs(e.target.value)} />
            <label style={{ fontSize: 12 }}>输出</label>
            <input className="input" value={mergeOutput} onChange={(e) => setMergeOutput(e.target.value)} />
            <button className="btn btn-raised" disabled={busy}
                    onClick={() => void run(() => api.transcode.toolsMerge(lines(mergeInputs), mergeOutput), "合并")}>
              开始合并
            </button>
          </div>
        </div>

        <div className="card" style={{ flex: "1 1 300px", minWidth: 280 }}>
          <div className="comp-row"><span className="comp-label">混流</span><span className="comp-value">多输入 · copy</span></div>
          <div style={{ display: "grid", gap: 5, padding: "4px 8px 8px" }}>
            <label style={{ fontSize: 12 }}>输入（每行一个）</label>
            <textarea className="input" rows={3} value={muxInputs} onChange={(e) => setMuxInputs(e.target.value)} />
            <label style={{ fontSize: 12 }}>流映射（每行一个，如 0:v:0）</label>
            <textarea className="input" rows={3} value={muxMaps} onChange={(e) => setMuxMaps(e.target.value)} />
            <label style={{ fontSize: 12 }}>输出</label>
            <input className="input" value={muxOutput} onChange={(e) => setMuxOutput(e.target.value)} />
            <button className="btn btn-raised" disabled={busy}
                    onClick={() => void run(() => api.transcode.toolsMux(lines(muxInputs), lines(muxMaps), muxOutput), "混流")}>
              开始混流
            </button>
          </div>
        </div>

        <div className="card" style={{ flex: "1 1 300px", minWidth: 280 }}>
          <div className="comp-row"><span className="comp-label">抽流</span><span className="comp-value">单流 · copy</span></div>
          <div style={{ display: "grid", gap: 5, padding: "4px 8px 8px" }}>
            <label style={{ fontSize: 12 }}>输入</label>
            <input className="input" value={extractInput} onChange={(e) => setExtractInput(e.target.value)} />
            <div style={{ display: "flex", gap: 6 }}>
              <select className="input" value={extractType} onChange={(e) => setExtractType(e.target.value as typeof extractType)}>
                <option value="video">视频</option>
                <option value="audio">音频</option>
                <option value="subtitle">字幕</option>
              </select>
              <input className="input" type="number" min={0} value={extractIndex}
                     onChange={(e) => setExtractIndex(Number(e.target.value) || 0)} style={{ width: 80 }} />
            </div>
            <label style={{ fontSize: 12 }}>输出</label>
            <input className="input" value={extractOutput} onChange={(e) => setExtractOutput(e.target.value)} />
            <button className="btn btn-raised" disabled={busy}
                    onClick={() => void run(
                      () => api.transcode.toolsExtract(extractInput, extractType, extractIndex, extractOutput),
                      "抽流",
                    )}>
              开始抽流
            </button>
          </div>
        </div>
      </div>

      <div className="card">
        <div className="comp-row">
          <span className="comp-label">质量评测</span>
          <span className="comp-value"><span className="tag plain">建设中</span></span>
        </div>
        <p style={{ fontSize: 12, padding: "4px 8px 8px", lineHeight: 1.6, color: "var(--monet-on-surface-variant)" }}>
          PSNR / SSIM / XPSNR / VMAF 与逐帧曲线在后续提交接入。
        </p>
      </div>
    </div>
  );
}