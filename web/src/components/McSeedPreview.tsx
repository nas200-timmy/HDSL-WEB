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
 * 种子预览：给一个种子，出一张"这世界长什么样"的图。
 *
 * 实现方式不是重写地形生成，而是让**面板自己缓存的那个 `server.jar`** 起一个
 * 一次性服务端，在临时目录里生成一块区域，然后把 region 文件读出来画图。所以
 * 图上的一草一木都来自游戏本体，不会和实际开出来的世界有偏差。
 *
 * 地形烧成 PNG（那是重活，要生成世界）；网格、史莱姆区块、结构点都放在浏览器
 * 的 SVG 里——它们是元数据，切成开关不该再跑一次世界生成。
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
          <MapView job={job} meta={meta} />
          <p className="dim" style={{ margin: 0, fontSize: 12 }}>
            {job.partial ? "⚠ 生成超时，这张图不完整。" : "生成完成。"}
            {" "}
            1 像素 = 1 格。滚轮缩放、拖动平移、双击复位；鼠标停在地图上读坐标。
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

/// 结构标记的颜色，和后端 `PreviewRenderer.structureColor` 同一套。
function markerColor(id: string): string {
  if (id.startsWith("village")) return "#FFD54F";
  if (id.includes("monument")) return "#4FC3F7";
  if (id.includes("mansion")) return "#8D6E63";
  if (id === "stronghold") return "#E0E0E0";
  if (id.startsWith("mineshaft")) return "#A1887F";
  if (id.includes("fortress") || id.includes("bastion")) return "#B71C1C";
  if (id.includes("end_city")) return "#BA68C8";
  return "#E57373";
}

/**
 * 地图本体：PNG 地形 + SVG 覆盖层，缩放和平移都在一个 transform 里完成。
 *
 * 覆盖层画在方块坐标系里（viewBox 就是 ±radius），所以缩放时不用重新算任何
 * 东西；`vector-effect: non-scaling-stroke` 让线宽不跟着放大。
 */
function MapView({ job, meta }: { job: McSeedPreviewJob; meta: McSeedPreviewMeta | null }) {
  const radius = job.radius;
  const size = radius * 2;
  const containerRef = useRef<HTMLDivElement>(null);
  const imageRef = useRef<HTMLImageElement>(null);
  const [scale, setScale] = useState(1);
  const [offset, setOffset] = useState({ x: 0, y: 0 });
  const [hover, setHover] = useState<{ x: number; z: number } | null>(null);
  const dragRef = useRef<{ x: number; y: number; ox: number; oy: number } | null>(null);
  const [layers, setLayers] = useState({ grid: true, slime: true, structures: true });

  const reset = () => {
    setScale(1);
    setOffset({ x: 0, y: 0 });
  };

  const onWheel = (event: React.WheelEvent) => {
    event.preventDefault();
    const box = containerRef.current;
    if (!box) return;
    const rect = box.getBoundingClientRect();
    const mx = event.clientX - rect.left;
    const my = event.clientY - rect.top;
    const next = Math.min(12, Math.max(1, scale * (event.deltaY < 0 ? 1.15 : 1 / 1.15)));
    if (next === scale) return;
    setOffset({
      x: mx - ((mx - offset.x) * next) / scale,
      y: my - ((my - offset.y) * next) / scale,
    });
    setScale(next);
  };

  const onPointerDown = (event: React.PointerEvent) => {
    if (event.button !== 0) return;
    dragRef.current = { x: event.clientX, y: event.clientY, ox: offset.x, oy: offset.y };
    (event.target as HTMLElement).setPointerCapture?.(event.pointerId);
  };

  const onPointerMove = (event: React.PointerEvent) => {
    const drag = dragRef.current;
    if (drag) {
      setOffset({ x: drag.ox + event.clientX - drag.x, y: drag.oy + event.clientY - drag.y });
    }
    const image = imageRef.current;
    if (!image) return;
    // The image is transformed, so its bounding box already reflects zoom/pan.
    const rect = image.getBoundingClientRect();
    const fx = (event.clientX - rect.left) / rect.width;
    const fz = (event.clientY - rect.top) / rect.height;
    if (fx < 0 || fz < 0 || fx > 1 || fz > 1) {
      setHover(null);
      return;
    }
    setHover({ x: Math.round(fx * size - radius), z: Math.round(fz * size - radius) });
  };

  const endDrag = () => {
    dragRef.current = null;
  };

  const lines = [];
  if (layers.grid) {
    for (let block = -radius; block <= radius; block += 64) {
      const major = block % 256 === 0;
      lines.push(
        <line
          key={`v${block}`}
          x1={block}
          y1={-radius}
          x2={block}
          y2={radius}
          stroke={major ? "#ffffff" : "#bfd4e8"}
          strokeOpacity={major ? 0.38 : 0.18}
          strokeWidth={1}
          vectorEffect="non-scaling-stroke"
        />,
        <line
          key={`h${block}`}
          x1={-radius}
          y1={block}
          x2={radius}
          y2={block}
          stroke={major ? "#ffffff" : "#bfd4e8"}
          strokeOpacity={major ? 0.38 : 0.18}
          strokeWidth={1}
          vectorEffect="non-scaling-stroke"
        />,
      );
    }
  }

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
      <div style={{ display: "flex", flexWrap: "wrap", gap: 12, alignItems: "center", fontSize: 12 }}>
        <label style={{ display: "flex", gap: 4, alignItems: "center" }}>
          <input
            type="checkbox"
            checked={layers.grid}
            onChange={(e) => setLayers({ ...layers, grid: e.target.checked })}
          />
          网格
        </label>
        <label style={{ display: "flex", gap: 4, alignItems: "center" }}>
          <input
            type="checkbox"
            checked={layers.slime}
            onChange={(e) => setLayers({ ...layers, slime: e.target.checked })}
          />
          <span style={{ display: "inline-block", width: 10, height: 10, background: "#59c46a", opacity: 0.55 }} />
          史莱姆区块
        </label>
        <label style={{ display: "flex", gap: 4, alignItems: "center" }}>
          <input
            type="checkbox"
            checked={layers.structures}
            onChange={(e) => setLayers({ ...layers, structures: e.target.checked })}
          />
          <span style={{ display: "inline-block", width: 10, height: 10, background: "#FFD54F", borderRadius: 5 }} />
          结构
        </label>
        <span style={{ flex: 1 }} />
        <span className="dim mono">{Math.round(scale * 100)}%</span>
        <button className="btn btn-text" onClick={reset}>
          复位
        </button>
      </div>

      <div
        ref={containerRef}
        onWheel={onWheel}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={endDrag}
        onPointerLeave={() => {
          endDrag();
          setHover(null);
        }}
        onDoubleClick={reset}
        style={{
          position: "relative",
          width: "100%",
          maxWidth: 720,
          aspectRatio: "1 / 1",
          overflow: "hidden",
          borderRadius: 6,
          border: "1px solid rgba(127,127,127,.25)",
          cursor: dragRef.current ? "grabbing" : "crosshair",
          touchAction: "none",
          background: "#1b1b1f",
        }}
      >
        <div
          style={{
            position: "absolute",
            inset: 0,
            transformOrigin: "0 0",
            transform: `translate(${offset.x}px, ${offset.y}px) scale(${scale})`,
          }}
        >
          <img
            ref={imageRef}
            src={`${withBase(`/api/mc/preview/${job.id}.png`)}?v=${job.id}`}
            alt={`种子 ${job.seed} 的地图`}
            draggable={false}
            style={{ width: "100%", height: "100%", display: "block", imageRendering: "pixelated" }}
          />
          <svg
            viewBox={`${-radius} ${-radius} ${size} ${size}`}
            style={{ position: "absolute", inset: 0, width: "100%", height: "100%", pointerEvents: "none" }}
          >
            {lines}
            {layers.slime &&
              meta?.slimeChunks?.map((chunk) => (
                <rect
                  key={`s${chunk.x}:${chunk.z}`}
                  x={chunk.x * 16}
                  y={chunk.z * 16}
                  width={16}
                  height={16}
                  fill="#59c46a"
                  fillOpacity={0.3}
                />
              ))}
            {layers.structures &&
              meta?.structures?.map((structure, index) => (
                <rect
                  key={`m${index}`}
                  x={structure.x - 2}
                  y={structure.z - 2}
                  width={4}
                  height={4}
                  fill={markerColor(structure.id)}
                  stroke="#1a1a1a"
                  strokeWidth={1}
                  vectorEffect="non-scaling-stroke"
                  transform={`rotate(45 ${structure.x} ${structure.z})`}
                />
              ))}
            <line
              x1={-6}
              y1={0}
              x2={6}
              y2={0}
              stroke="#e53935"
              strokeWidth={2}
              vectorEffect="non-scaling-stroke"
            />
            <line
              x1={0}
              y1={-6}
              x2={0}
              y2={6}
              stroke="#e53935"
              strokeWidth={2}
              vectorEffect="non-scaling-stroke"
            />
          </svg>
        </div>

        <div
          className="mono"
          style={{
            position: "absolute",
            left: 8,
            top: 8,
            padding: "2px 8px",
            borderRadius: 4,
            fontSize: 12,
            lineHeight: 1.6,
            color: "#fff",
            background: "rgba(0,0,0,.55)",
            pointerEvents: "none",
          }}
        >
          {hover ? `X ${hover.x}  Z ${hover.z}` : `种子 ${job.seed}`}
        </div>
      </div>
    </div>
  );
}
