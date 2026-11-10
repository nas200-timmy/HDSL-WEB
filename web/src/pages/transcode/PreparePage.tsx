import { useEffect, useState } from "react";
import { api, type TranscodeMediaEntry, type TranscodePreset, type TranscodePresetSummary, type TranscodePreview } from "../../api";
import { toast } from "../../store";
import { errMsg } from "../../utils";
import { MediaBrowserPanel, fmtBytes, probeSummary } from "./common";

export function PreparePage({ onOpenTab }: { onOpenTab: (tab: string) => void }) {
  const [presets, setPresets] = useState<TranscodePresetSummary[]>([]);
  const [presetId, setPresetId] = useState("");
  const [selected, setSelected] = useState<TranscodeMediaEntry | null>(null);
  const [probe, setProbe] = useState<Record<string, unknown> | null>(null);
  const [outputDir, setOutputDir] = useState("");
  const [preview, setPreview] = useState<TranscodePreview | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [presetDetail, setPresetDetail] = useState<TranscodePreset | null>(null);

  useEffect(() => {
    if (!presetId) {
      setPresetDetail(null);
      return;
    }
    let cancelled = false;
    void api.transcode.preset(presetId).then((entry) => {
      if (!cancelled) setPresetDetail(entry.preset);
    }).catch(() => {
      if (!cancelled) setPresetDetail(null);
    });
    return () => {
      cancelled = true;
    };
  }, [presetId]);

  useEffect(() => {
    void api.transcode
      .presets()
      .then((answer) => {
        setPresets(answer.presets);
        // No auto-selection: opening the page must not silently enqueue with the
        // first built-in preset. The operator picks a starting point explicitly.
      })
      .catch((e) => setError(errMsg(e)));
  }, []);

  const select = async (entry: TranscodeMediaEntry) => {
    setSelected(entry);
    setPreview(null);
    setProbe(null);
    setError(null);
    try {
      setProbe(await api.transcode.mediaProbe(entry.path));
    } catch {
      // The probe is a convenience; submission has its own validation.
    }
  };

  const checkFoolproof = (): boolean => {
    if (!presetDetail) return true;
    const encoder = String(presetDetail.videoEncoder ?? "");
    const container = String(presetDetail.container ?? "").toLowerCase();
    if (!encoder || !container) {
      const message = !encoder ? "预设没有视频编码器" : "预设没有输出容器";
      setError(message);
      toast("error", message);
      return false;
    }
    const restrictive = ["mp4", "mov", "avi", "flv", "ts", "m2ts"];
    const keepsTracks = presetDetail.subtitleMode === "copy"
      || presetDetail.copyAttachments === true
      || presetDetail.copyChapters === true;
    if (restrictive.includes(container) && keepsTracks) {
      const proceed = window.confirm(
        `当前容器 ${container.toUpperCase()} 可能装不下全部字幕/附件/章节。\n` +
        `建议把预设容器改成 MKV。仍然继续入队吗？`,
      );
      if (!proceed) return false;
    }
    return true;
  };

  const previewCommand = async () => {
    if (!selected || !presetId || !checkFoolproof()) return;
    setBusy(true);
    setError(null);
    try {
      setPreview(await api.transcode.preview({
        presetId,
        input: selected.path,
        outputDir: outputDir.trim() || undefined,
      }));
    } catch (e) {
      setError(errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  const enqueue = async () => {
    if (!selected || !presetId || !checkFoolproof()) return;
    setBusy(true);
    setError(null);
    try {
      await api.transcode.submitJob({
        input: selected.path,
        presetId,
        outputDir: outputDir.trim() || undefined,
      });
      toast("success", "已加入编码队列");
      onOpenTab("queue");
    } catch (e) {
      setError(errMsg(e));
      toast("error", errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div style={{ display: "flex", gap: 8, alignItems: "flex-start", flexWrap: "wrap" }}>
      <div style={{ flex: "2 1 420px", minWidth: 0, width: "100%" }}>
        <MediaBrowserPanel selected={selected?.path} onSelect={(entry) => void select(entry)} />
      </div>

      <div style={{ flex: "1 1 300px", minWidth: 0, display: "grid", gap: 8 }}>
        <div className="card">
          <div className="comp-row">
            <span className="comp-label">已选文件</span>
            <span className="comp-value mono" style={{ fontSize: 11, overflowWrap: "anywhere" }}>
              {selected ? selected.path : "从左侧选择"}
            </span>
          </div>
          {selected && (
            <div className="comp-row">
              <span className="comp-label">信息</span>
              <span className="comp-value" style={{ fontSize: 12 }}>
                {probe ? probeSummary(probe) : "—"} · {fmtBytes(selected.size)}
              </span>
            </div>
          )}
          <div style={{ padding: "4px 8px 8px", display: "grid", gap: 6 }}>
            <label style={{ fontSize: 12 }}>预设</label>
            <select
              className="input"
              value={presetId}
              disabled={busy || presets.length === 0}
              onChange={(e) => setPresetId(e.target.value)}
            >
              <option value="">选择参数面板 / 预设…</option>
              {presets.map((preset) => (
                <option key={preset.id} value={preset.id}>
                  {preset.name}{preset.builtin ? "" : "（自定义）"}
                </option>
              ))}
            </select>
            <label style={{ fontSize: 12 }}>输出目录（相对媒体根；空 = 源目录）</label>
            <input
              className="input"
              value={outputDir}
              disabled={busy}
              placeholder="例如 输出"
              onChange={(e) => setOutputDir(e.target.value)}
            />
            <div style={{ display: "flex", gap: 8, flexWrap: "wrap", paddingTop: 4 }}>
              <button className="btn btn-text" disabled={busy} onClick={() => onOpenTab("presets")}>
                打开参数面板
              </button>
              <button className="btn btn-text" disabled={busy || !selected || !presetId} onClick={() => void previewCommand()}>
                预览命令
              </button>
              <button className="btn btn-raised" disabled={busy || !selected || !presetId} onClick={() => void enqueue()}>
                {busy ? "处理中…" : "加入队列"}
              </button>
            </div>
          </div>
          {error && (
            <div style={{ padding: "0 8px 8px", fontSize: 12, color: "var(--monet-error)" }}>{error}</div>
          )}
        </div>

        {preview && (
          <div className="card">
            <div className="comp-row">
              <span className="comp-label">输出</span>
              <span className="comp-value mono" style={{ fontSize: 11, overflowWrap: "anywhere" }}>
                {preview.output}
              </span>
            </div>
            <pre
              style={{
                margin: "4px 8px 8px",
                padding: 8,
                maxHeight: 220,
                overflow: "auto",
                fontSize: 11,
                lineHeight: 1.5,
                background: "var(--monet-surface-container-low)",
                whiteSpace: "pre-wrap",
                wordBreak: "break-all",
              }}
            >
              {preview.command}
            </pre>
          </div>
        )}
      </div>
    </div>
  );
}