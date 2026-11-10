import { useEffect, useState } from "react";
import { api, type TranscodeCapabilities } from "../../api";
import { refreshTranscodeFeature, useTranscodeFeature } from "../../transcodeFeature";
import { errMsg } from "../../utils";

export function TranscodeSettingsPage() {
  const feature = useTranscodeFeature();
  const [capabilities, setCapabilities] = useState<TranscodeCapabilities | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = async () => {
    setBusy(true);
    setError(null);
    try {
      setCapabilities(await api.transcode.capabilities());
      await refreshTranscodeFeature();
    } catch (e) {
      setError(errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  useEffect(() => {
    void load();
  }, []);

  const policy = feature?.failurePolicy === "ask"
    ? "询问（默认）"
    : feature?.failurePolicy === "timeout-auto"
      ? `超时自动降级（${feature.failureTimeoutMinutes} 分钟）`
      : "完全自动化";

  return (
    <>
      {error && (
        <div className="card">
          <div className="comp-row">
            <span className="comp-label">读取失败</span>
            <span className="comp-value" style={{ color: "var(--monet-error)" }}>{error}</span>
          </div>
        </div>
      )}

      <div className="card">
        <div className="comp-row">
          <span className="comp-label">ffmpeg</span>
          <span className="comp-value">
            {capabilities?.available ? <span className="tag running">可用</span> : <span className="tag plain">不可用</span>}
          </span>
        </div>
        <div className="comp-row">
          <span className="comp-label">版本</span>
          <span className="comp-value mono" style={{ fontSize: 12 }}>{capabilities?.version || "—"}</span>
        </div>
        <div className="comp-row">
          <span className="comp-label">ffmpeg 路径</span>
          <span className="comp-value mono" style={{ fontSize: 11, overflowWrap: "anywhere" }}>
            {capabilities?.ffmpeg || "—"}
          </span>
        </div>
        <div className="comp-row">
          <span className="comp-label">ffprobe 路径</span>
          <span className="comp-value mono" style={{ fontSize: 11, overflowWrap: "anywhere" }}>
            {capabilities?.ffprobe || "—"}
          </span>
        </div>
        <div style={{ display: "flex", gap: 8, padding: "8px" }}>
          <button className="btn btn-text" disabled={busy} onClick={() => void load()}>
            {busy ? "探测中…" : "重新探测"}
          </button>
          <span style={{ fontSize: 12, color: "var(--monet-on-surface-variant)", alignSelf: "center" }}>
            下载 / 指定路径等在线安装功能在后续提交接入；当前可用 `transcode.ffmpeg_path` / `HDSL_FFMPEG_PATH`。
          </span>
        </div>
      </div>

      <div className="card">
        <div className="comp-row">
          <span className="comp-label">媒体根</span>
          <span className="comp-value mono" style={{ fontSize: 11, overflowWrap: "anywhere" }}>
            {capabilities?.mediaRoot || feature?.mediaRoot || "—"}
          </span>
        </div>
        <div className="comp-row">
          <span className="comp-label">可写性</span>
          <span className="comp-value">
            {capabilities?.mediaRootExists
              ? capabilities.mediaRootWritable ? "可写" : "只读"
              : "不存在"}
          </span>
        </div>
        <p style={{ fontSize: 12, padding: "4px 8px 8px", lineHeight: 1.6, color: "var(--monet-on-surface-variant)" }}>
          容器里固定为 `/home/hdsl/video`（`HDSL_VIDEO_DIR` 决定宿主挂载）；裸部署默认
          &lt;数据目录&gt;/video，可用 `HDSL_MEDIA_ROOT` 或 `server.yaml` 的 `transcode.media_root` 修改。
        </p>
      </div>

      <div className="card">
        <div className="comp-row">
          <span className="comp-label">并发 / 队列上限</span>
          <span className="comp-value">
            {capabilities?.maxConcurrent ?? feature?.maxConcurrent ?? "—"} /{" "}
            {capabilities?.maxQueue ?? feature?.maxQueue ?? "—"}
          </span>
        </div>
        <div className="comp-row">
          <span className="comp-label">失败策略</span>
          <span className="comp-value">{policy}</span>
        </div>
        <div className="comp-row">
          <span className="comp-label">重启恢复队列</span>
          <span className="comp-value">{feature?.autoResume ? "是" : "否"}</span>
        </div>
        <p style={{ fontSize: 12, padding: "4px 8px 8px", lineHeight: 1.6, color: "var(--monet-on-surface-variant)" }}>
          这些值来自 `server.yaml` 的 `transcode:` 段与 `HDSL_TRANSCODE_*` 环境变量；改完重启面板生效。
        </p>
      </div>

      <div className="card">
        <div className="comp-row">
          <span className="comp-label">编码器</span>
          <span className="comp-value" style={{ fontSize: 12 }}>
            {capabilities ? `${capabilities.encoders.length} 个` : "—"}
          </span>
        </div>
        {capabilities && (
          <div style={{ display: "flex", gap: 6, flexWrap: "wrap", padding: "4px 8px 8px", maxHeight: 180, overflow: "auto" }}>
            {capabilities.encoders
              .filter((name) => /264|265|av1|vp9|aac|opus|vaapi|qsv|nvenc/.test(name))
              .map((name) => (
                <span key={name} className="tag plain" style={{ fontSize: 11 }}>{name}</span>
              ))}
          </div>
        )}
      </div>
    </>
  );
}