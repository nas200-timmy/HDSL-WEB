import { useEffect, useState } from "react";
import { api, type TranscodeQualityJob } from "../../api";
import { ProgressBar } from "../../components/ProgressBar";
import { toast } from "../../store";
import { errMsg } from "../../utils";

export function ToolsPage({ onOpenTab }: { onOpenTab: (tab: string) => void }) {
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
  const [qualityReference, setQualityReference] = useState("");
  const [qualityEncoded, setQualityEncoded] = useState("");
  const [qualityMetrics, setQualityMetrics] = useState<string[]>(["psnr", "ssim"]);
  const [qualityJob, setQualityJob] = useState<TranscodeQualityJob | null>(null);
  const [qualityError, setQualityError] = useState<string | null>(null);

  const lines = (text: string) => text.split(/\r?\n/).map((line) => line.trim()).filter(Boolean);

  useEffect(() => {
    if (!qualityJob || qualityJob.state !== "running") return;
    const timer = window.setInterval(() => {
      void api.transcode.qualityJob(qualityJob.id).then(setQualityJob).catch(() => undefined);
    }, 1000);
    return () => window.clearInterval(timer);
  }, [qualityJob]);

  const startQuality = async () => {
    if (!qualityReference || !qualityEncoded || qualityMetrics.length === 0) return;
    setQualityError(null);
    try {
      setQualityJob(await api.transcode.qualityStart({
        reference: qualityReference,
        encoded: qualityEncoded,
        metrics: qualityMetrics,
      }));
    } catch (e) {
      setQualityError(errMsg(e));
      toast("error", errMsg(e));
    }
  };

  const toggleMetric = (metric: string) => {
    setQualityMetrics((current) =>
      current.includes(metric) ? current.filter((item) => item !== metric) : [...current, metric]);
  };

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
      {error && (
        <div className="card">
          <div className="comp-row">
            <span className="comp-label">错误</span>
            <span className="comp-value" style={{ color: "var(--monet-error)", fontSize: 12 }}>{error}</span>
          </div>
        </div>
      )}

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
          <span className="comp-value" style={{ fontSize: 12 }}>
            PSNR / SSIM / XPSNR / VMAF（需要 ffmpeg 带对应滤镜）
          </span>
        </div>
        <div style={{ display: "grid", gap: 6, padding: "4px 8px 8px" }}>
          <label style={{ fontSize: 12 }}>参考文件（相对媒体根）</label>
          <input className="input" value={qualityReference}
                 onChange={(e) => setQualityReference(e.target.value)} placeholder="input.mp4" />
          <label style={{ fontSize: 12 }}>待测文件</label>
          <input className="input" value={qualityEncoded}
                 onChange={(e) => setQualityEncoded(e.target.value)} placeholder="output.mkv" />
          <div style={{ display: "flex", gap: 12, flexWrap: "wrap", fontSize: 13 }}>
            {["psnr", "ssim", "xpsnr", "vmaf"].map((metric) => (
              <label key={metric} style={{ display: "flex", gap: 4, alignItems: "center" }}>
                <input type="checkbox" checked={qualityMetrics.includes(metric)}
                       onChange={() => toggleMetric(metric)} />
                {metric.toUpperCase()}
              </label>
            ))}
          </div>
          <div style={{ display: "flex", gap: 8 }}>
            <button className="btn btn-raised" disabled={!qualityReference || !qualityEncoded
              || qualityMetrics.length === 0} onClick={() => void startQuality()}>
              开始评测
            </button>
            {qualityJob?.state === "running" && (
              <button className="btn btn-text danger"
                      onClick={() => void api.transcode.qualityCancel(qualityJob.id).then(setQualityJob)}>
                取消
              </button>
            )}
          </div>
          {qualityError && <span style={{ fontSize: 12, color: "var(--monet-error)" }}>{qualityError}</span>}
          {qualityJob && (
            <>
              <div className="comp-row">
                <span className="comp-label">状态</span>
                <span className="comp-value">
                  <span className={`tag ${qualityJob.state === "done" ? "running" : "plain"}`}>
                    {qualityJob.state === "done" ? "完成" : qualityJob.state === "failed" ? "失败"
                      : qualityJob.state === "cancelled" ? "已取消" : qualityJob.message || "运行中"}
                  </span>
                </span>
              </div>
              <ProgressBar fraction={qualityJob.progress} />
              {qualityJob.error && (
                <span style={{ fontSize: 12, color: "var(--monet-error)" }}>{qualityJob.error}</span>
              )}
              {Object.entries(qualityJob.results).map(([metric, result]) => (
                <div key={metric} style={{ paddingTop: 6 }}>
                  <div className="comp-row">
                    <span className="comp-label">{metric.toUpperCase()}</span>
                    <span className="comp-value mono">
                      平均 {result.average >= 0 ? result.average.toFixed(3) : "—"}
                    </span>
                  </div>
                  <QualityChart metric={metric} frames={result.frames} />
                </div>
              ))}
            </>
          )}
        </div>
      </div>
    </div>
  );
}

function QualityChart({ metric, frames }: { metric: string; frames: Array<{ n: number; value: number }> }) {
  if (frames.length < 2) return null;
  const width = 600;
  const height = 110;
  const values = frames.map((frame) => frame.value);
  const min = Math.min(...values);
  const max = Math.max(...values);
  const span = Math.max(0.0001, max - min);
  const points = frames.map((frame, index) => {
    const x = (index / (frames.length - 1)) * width;
    const y = height - ((frame.value - min) / span) * (height - 12) - 6;
    return `${x.toFixed(1)},${y.toFixed(1)}`;
  }).join(" ");
  return (
    <svg viewBox={`0 0 ${width} ${height}`} style={{ width: "100%", height: 110 }}>
      <polyline points={points} fill="none" stroke="var(--monet-primary)" strokeWidth="1.5" />
      <text x="2" y="10" fontSize="9" fill="var(--monet-on-surface-variant)">{max.toFixed(2)}</text>
      <text x="2" y={height - 2} fontSize="9" fill="var(--monet-on-surface-variant)">{min.toFixed(2)}</text>
      <text x={width - 40} y={height - 2} fontSize="9" fill="var(--monet-on-surface-variant)">{metric}</text>
    </svg>
  );
}