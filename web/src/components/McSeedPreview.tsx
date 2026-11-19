import { useCallback, useEffect, useRef, useState } from "react";
import {
  api,
  type McInstance,
  type McSeedPreviewCapabilities,
  type McSeedPreviewJob,
  type McSeedPreviewMeta,
} from "../api";
import { withBase } from "../base";
import { errMsg } from "../utils";

/**
 * 种子预览：给一个种子，出一张"这世界长什么样"的粗图。
 *
 * 实现方式不是重写地形生成，而是让**面板自己缓存的那个 `server.jar`** 起一个
 * 一次性服务端，在临时目录里生成一块区域，然后把 region 文件读出来画图。所以
 * 图上的一草一木都来自游戏本体，不会和实际开出来的世界有偏差。
 *
 * 代价是慢（要起服 + 生成），因此这里是显式的操作、有进度、可取消，而不是
 * 像地图一样随手拖动。
 */
export function McSeedPreview({ instance }: { instance: McInstance }) {
  const [version, setVersion] = useState(instance.version);
  const [seed, setSeed] = useState(instance.seed ?? "");
  const [radius, setRadius] = useState(512);
  const [caps, setCaps] = useState<McSeedPreviewCapabilities | null>(null);
  const [job, setJob] = useState<McSeedPreviewJob | null>(null);
  const [meta, setMeta] = useState<McSeedPreviewMeta | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const jobRef = useRef<string | null>(null);

  useEffect(() => {
    let alive = true;
    api.mc
      .previewCapabilities(version)
      .then((value) => alive && setCaps(value))
      .catch(() => alive && setCaps(null));
    return () => {
      alive = false;
    };
  }, [version]);

  const poll = useCallback(async (id: string) => {
    try {
      const next = await api.mc.previewJob(id);
      if (jobRef.current !== id) return;
      setJob(next);
      if (next.state === "DONE") {
        try {
          setMeta(await api.mc.previewMeta(id));
        } catch {
          // 元数据只是加分项，图在就够了。
        }
      }
      if (next.state === "FAILED") {
        setError(next.error ?? "预览失败");
      }
    } catch (e) {
      if (jobRef.current === id) setError(errMsg(e));
    }
  }, []);

  useEffect(() => {
    if (!job || job.state === "DONE" || job.state === "FAILED" || job.state === "CANCELLED") return;
    const timer = window.setTimeout(() => void poll(job.id), 700);
    return () => window.clearTimeout(timer);
  }, [job, poll]);

  const start = async () => {
    const trimmed = seed.trim();
    if (!trimmed) {
      setError("先填一个种子");
      return;
    }
    setBusy(true);
    setError(null);
    setMeta(null);
    setJob(null);
    try {
      const created = await api.mc.previewStart({ version: version.trim(), seed: trimmed, radius });
      jobRef.current = created.id;
      setJob(created);
    } catch (e) {
      setError(errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  const cancel = async () => {
    if (!job) return;
    try {
      await api.mc.previewCancel(job.id);
    } catch (e) {
      setError(errMsg(e));
    }
  };

  const running = job !== null && !["DONE", "FAILED", "CANCELLED"].includes(job.state);

  const structureRows = meta
    ? Object.entries(meta.structureCounts).sort((a, b) => b[1] - a[1])
    : [];

  return (
    <div className="card" style={{ padding: 16, display: "flex", flexDirection: "column", gap: 12 }}>
      <div>
        <h3 style={{ margin: 0 }}>种子预览</h3>
        <p className="dim" style={{ margin: "4px 0 0", fontSize: 13 }}>
          用这台服自己的服务端 jar，在临时目录里生成一次世界再出图。世界用完即删，只留图片和结构清单。
        </p>
      </div>

      <div style={{ display: "flex", flexWrap: "wrap", gap: 8, alignItems: "flex-end" }}>
        <label style={{ display: "flex", flexDirection: "column", gap: 4 }}>
          <span className="dim" style={{ fontSize: 12 }}>版本</span>
          <input
            className="mono"
            value={version}
            onChange={(event) => setVersion(event.target.value)}
            style={{ width: 120, padding: "6px 8px" }}
          />
        </label>
        <label style={{ display: "flex", flexDirection: "column", gap: 4, flex: "1 1 220px" }}>
          <span className="dim" style={{ fontSize: 12 }}>种子（数字或文本）</span>
          <input
            className="mono"
            value={seed}
            onChange={(event) => setSeed(event.target.value)}
            placeholder="例如 123456789 或 我的世界"
            style={{ padding: "6px 8px" }}
          />
        </label>
        <label style={{ display: "flex", flexDirection: "column", gap: 4 }}>
          <span className="dim" style={{ fontSize: 12 }}>范围</span>
          <select
            value={radius}
            onChange={(event) => setRadius(Number(event.target.value))}
            style={{ padding: "6px 8px" }}
          >
            <option value={256}>512 × 512 格</option>
            <option value={512}>1024 × 1024 格</option>
            <option value={1024}>2048 × 2048 格</option>
          </select>
        </label>
        <button className="btn btn-raised" disabled={busy || running} onClick={() => void start()}>
          {busy ? "提交中…" : "生成预览"}
        </button>
        {running && (
          <button className="btn btn-text" onClick={() => void cancel()}>
            取消
          </button>
        )}
      </div>

      {caps && (
        <p className="dim" style={{ margin: 0, fontSize: 12 }}>
          {caps.jarPresent ? "服务端 jar 已缓存" : "首次需要下载该版本的服务端 jar（约 50MB）"}
          {" · "}
          {caps.javaReady
            ? `Java ${caps.javaRequired} 已就绪`
            : `需要先安装 Java ${caps.javaRequired}（Minecraft → Java 运行时）`}
        </p>
      )}

      {running && job && (
        <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
          <div style={{ display: "flex", justifyContent: "space-between", fontSize: 12 }} className="dim">
            <span>{job.label}</span>
            <span className="mono">
              {job.chunksDone} / {job.chunksTotal} 区块
            </span>
          </div>
          <div style={{ height: 6, background: "rgba(127,127,127,.25)", borderRadius: 3, overflow: "hidden" }}>
            <div
              style={{
                width: `${job.percent}%`,
                height: "100%",
                background: "var(--accent, #4e6fff)",
                transition: "width .3s ease",
              }}
            />
          </div>
        </div>
      )}

      {error && (
        <p style={{ margin: 0, color: "#b3261e", fontSize: 13, whiteSpace: "pre-wrap" }}>{error}</p>
      )}

      {job?.state === "DONE" && job.imageReady && (
        <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
          <img
            src={`${withBase(`/api/mc/preview/${job.id}.png`)}?v=${job.id}`}
            alt={`种子 ${job.seed} 的地图`}
            style={{
              width: "100%",
              maxWidth: 640,
              imageRendering: "pixelated",
              borderRadius: 6,
              border: "1px solid rgba(127,127,127,.25)",
            }}
          />
          <p className="dim" style={{ margin: 0, fontSize: 12 }}>
            {job.partial ? "⚠ 生成超时，这张图不完整。" : "生成完成。"}
            {" "}
            白色十字是 (0,0)。绿色叠加是史莱姆区块，彩色小方块是结构起点。
          </p>
          {structureRows.length > 0 && (
            <div style={{ display: "flex", flexWrap: "wrap", gap: 6, fontSize: 12 }}>
              {structureRows.map(([id, count]) => (
                <span
                  key={id}
                  className="mono"
                  style={{ padding: "2px 6px", borderRadius: 4, background: "rgba(127,127,127,.15)" }}
                >
                  {id} × {count}
                </span>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
