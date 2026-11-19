import { useCallback, useEffect, useRef, useState } from "react";
import {
  api,
  type McSeedPreviewCapabilities,
  type McSeedPreviewHistoryEntry,
  type McSeedPreviewJob,
  type McSeedPreviewMeta,
  type McVersion,
} from "../api";
import { withBase } from "../base";
import { errMsg } from "../utils";

/** 正在看的是哪一张图：刚跑完的作业，还是历史里的一条。 */
type View = {
  source: "job" | "history";
  id: string;
  seed: string;
  radius: number;
  partial: boolean;
};

/**
 * 种子预览：给一个种子，出一张"这世界长什么样"的图。
 *
 * 实现方式不是重写地形生成，而是让**面板自己缓存的那个 `server.jar`** 起一个
 * 一次性服务端，在临时目录里生成一块区域，然后把 region 文件读出来画图。所以
 * 图上的一草一木都来自游戏本体，不会和实际开出来的世界有偏差。
 *
 * 地形烧成 PNG（那是重活，要生成世界）；网格、史莱姆区块、结构点都放在浏览器
 * 的 SVG 里——它们是元数据，切成开挂不该再跑一次世界生成。
 *
 * 跑过的结果留在服务端缓存里（最近 30 个），关掉页面再回来还在：试种子这件事
 * 本来就是来回比较，结果不能是"一转头就没了"。
 */
export function McSeedPreview({
  initialVersion = "",
  initialSeed = "",
}: {
  /** 从实例带进来的默认值；独立页面上可以留空。 */
  initialVersion?: string;
  initialSeed?: string;
}) {
  const [version, setVersion] = useState(initialVersion);
  const [versions, setVersions] = useState<McVersion[]>([]);
  const [seed, setSeed] = useState(initialSeed);
  const [radius, setRadius] = useState(512);
  const [caps, setCaps] = useState<McSeedPreviewCapabilities | null>(null);
  const [job, setJob] = useState<McSeedPreviewJob | null>(null);
  const [view, setView] = useState<View | null>(null);
  const [meta, setMeta] = useState<McSeedPreviewMeta | null>(null);
  const [history, setHistory] = useState<McSeedPreviewHistoryEntry[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const jobRef = useRef<string | null>(null);

  useEffect(() => {
    api.mc
      .versions()
      .then((catalog) =>
        setVersions(catalog.versions.filter((entry) => entry.type === "release")),
      )
      .catch(() => setVersions([]));
  }, []);

  useEffect(() => {
    if (!version.trim()) {
      setCaps(null);
      return;
    }
    let alive = true;
    api.mc
      .previewCapabilities(version)
      .then((value) => alive && setCaps(value))
      .catch(() => alive && setCaps(null));
    return () => {
      alive = false;
    };
  }, [version]);

  const refreshHistory = useCallback(async () => {
    try {
      const page = await api.mc.previewHistory();
      setHistory(page.entries);
    } catch {
      // 历史读不到不影响正在看的图。
    }
  }, []);

  useEffect(() => {
    void refreshHistory();
  }, [refreshHistory]);

  const poll = useCallback(
    async (id: string) => {
      try {
        const next = await api.mc.previewJob(id);
        if (jobRef.current !== id) return;
        setJob(next);
        if (next.state === "DONE") {
          setView({ source: "job", id, seed: next.seed, radius: next.radius, partial: next.partial });
          try {
            setMeta(await api.mc.previewMeta(id));
          } catch {
            // 元数据只是加分项，图在就够了。
          }
          void refreshHistory();
        }
        if (next.state === "FAILED") {
          setError(next.error ?? "预览失败");
        }
      } catch (e) {
        if (jobRef.current === id) setError(errMsg(e));
      }
    },
    [refreshHistory],
  );

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
    setView(null);
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

  const openHistory = async (entry: McSeedPreviewHistoryEntry) => {
    setError(null);
    setMeta(null);
    setView({
      source: "history",
      id: entry.id,
      seed: entry.seed,
      radius: entry.radius,
      partial: entry.partial,
    });
    setSeed(entry.seed);
    setVersion(entry.version);
    setRadius(entry.radius);
    try {
      setMeta(await api.mc.previewHistoryMeta(entry.id));
    } catch {
      // 图还在，元数据读不到就少一层覆盖。
    }
  };

  const removeHistory = async (entry: McSeedPreviewHistoryEntry) => {
    try {
      await api.mc.previewHistoryDelete(entry.id);
      if (view?.source === "history" && view.id === entry.id) {
        setView(null);
        setMeta(null);
      }
      await refreshHistory();
    } catch (e) {
      setError(errMsg(e));
    }
  };

  const running = job !== null && !["DONE", "FAILED", "CANCELLED"].includes(job.state);

  const structureRows = meta ? Object.entries(meta.structureCounts).sort((a, b) => b[1] - a[1]) : [];

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
          <select
            value={version}
            onChange={(event) => setVersion(event.target.value)}
            style={{ width: 160, padding: "6px 8px" }}
          >
            {version && !versions.some((entry) => entry.id === version) && (
              <option value={version}>{version}</option>
            )}
            {versions.map((entry) => (
              <option key={entry.id} value={entry.id}>
                {entry.id}
              </option>
            ))}
          </select>
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
            <span>
              {job.label}
              {job.etaSeconds !== undefined && job.percent > 3 ? ` · 约剩 ${job.etaSeconds} 秒` : ""}
            </span>
            <span className="mono">
              {job.chunksDone} / {job.chunksTotal} 区块 · {job.percent}%
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
          {job.log.length > 0 && (
            <details>
              <summary className="dim" style={{ fontSize: 12, cursor: "pointer" }}>
                服务端日志（最近 {job.log.length} 行）
              </summary>
              <pre
                className="mono"
                style={{
                  maxHeight: 180,
                  overflow: "auto",
                  fontSize: 11,
                  lineHeight: 1.5,
                  background: "rgba(0,0,0,.25)",
                  padding: 8,
                  borderRadius: 4,
                  margin: "6px 0 0",
                }}
              >
                {job.log.join("\n")}
              </pre>
            </details>
          )}
        </div>
      )}

      {error && (
        <p style={{ margin: 0, color: "#b3261e", fontSize: 13, whiteSpace: "pre-wrap" }}>{error}</p>
      )}

      {view && (
        <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
          <MapView view={view} meta={meta} />
          <p className="dim" style={{ margin: 0, fontSize: 12 }}>
            {view.partial ? "⚠ 生成超时，这张图不完整。" : "生成完成。"}
            {" "}1 像素 = 1 格。滚轮 / 双指缩放，拖动平移，双击复位，单击选点。
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

      {history.length > 0 && (
        <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
          <div style={{ display: "flex", alignItems: "baseline", gap: 8 }}>
            <h4 style={{ margin: 0, fontSize: 13 }}>试过的种子</h4>
            <span className="dim" style={{ fontSize: 12 }}>
              最近 {history.length} / {30} 个
            </span>
          </div>
          <div style={{ display: "flex", flexDirection: "column", gap: 2 }}>
            {history.map((entry) => {
              const active = view?.source === "history" && view.id === entry.id;
              return (
                <div
                  key={entry.id}
                  style={{
                    display: "flex",
                    alignItems: "center",
                    gap: 8,
                    padding: "4px 6px",
                    borderRadius: 4,
                    fontSize: 12,
                    background: active ? "rgba(78,111,255,.14)" : "transparent",
                  }}
                >
                  <button
                    className="btn btn-text"
                    style={{ padding: "0 4px", fontFamily: "inherit" }}
                    title="查看这个种子"
                    onClick={() => void openHistory(entry)}
                  >
                    <span className="mono">{entry.seed}</span>
                  </button>
                  <span className="dim mono">
                    {entry.version} · {(entry.radius * 2).toLocaleString()} 格
                  </span>
                  <span className="dim" style={{ flex: 1, textAlign: "right" }}>
                    {new Date(entry.generatedAt).toLocaleString()}
                  </span>
                  <button
                    className="btn btn-text"
                    style={{ padding: "0 6px", color: "#b3261e" }}
                    title="删除这个结果"
                    onClick={() => void removeHistory(entry)}
                  >
                    删除
                  </button>
                </div>
              );
            })}
          </div>
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

/** 一组好看的比例尺长度（格）。 */
const SCALE_STEPS = [16, 32, 64, 128, 256, 512, 1024];

/**
 * 地图本体：PNG 地形 + SVG 覆盖层，缩放和平移都在一个 transform 里完成。
 *
 * 覆盖层画在方块坐标系里（viewBox 就是 ±radius），所以缩放时不用重新算任何
 * 东西；`vector-effect: non-scaling-stroke` 让线宽不跟着放大。
 *
 * 平移会夹在"图片始终铺满视口"的范围内——否则缩小后四边会露出底色，看起来
 * 像地图缺了一块（第一版就是这样，被当成生成失败）。
 */
function MapView({ view, meta }: { view: View; meta: McSeedPreviewMeta | null }) {
  const radius = view.radius;
  const size = radius * 2;
  const frameRef = useRef<HTMLDivElement>(null);
  const imageRef = useRef<HTMLImageElement>(null);
  const [box, setBox] = useState(0);
  const [scale, setScale] = useState(1);
  const [offset, setOffset] = useState({ x: 0, y: 0 });
  const [hover, setHover] = useState<{ x: number; z: number } | null>(null);
  const [pinned, setPinned] = useState<{ x: number; z: number } | null>(null);
  const [layers, setLayers] = useState({ grid: true, slime: true, structures: true });

  const pointers = useRef(new Map<number, { x: number; y: number }>());
  const drag = useRef<{ x: number; y: number; ox: number; oy: number; moved: boolean } | null>(null);
  const pinch = useRef<{ d0: number; s0: number; mx: number; my: number; ox: number; oy: number } | null>(null);

  useEffect(() => {
    const frame = frameRef.current;
    if (!frame) return;
    const update = () => setBox(frame.clientWidth);
    update();
    const observer = new ResizeObserver(update);
    observer.observe(frame);
    return () => observer.disconnect();
  }, []);

  // A different picture means a different coordinate system: reset the viewport
  // so a new seed does not open somewhere off-screen.
  useEffect(() => {
    setScale(1);
    setOffset({ x: 0, y: 0 });
    setPinned(null);
    setHover(null);
  }, [view.id]);

  const clampOffset = (value: { x: number; y: number }, next: number) => {
    const width = box || 1;
    const min = -(next - 1) * width;
    return {
      x: Math.min(0, Math.max(min, value.x)),
      y: Math.min(0, Math.max(min, value.y)),
    };
  };

  const reset = () => {
    setScale(1);
    setOffset({ x: 0, y: 0 });
  };

  const zoomAt = (factor: number, mx: number, my: number) => {
    const next = Math.min(16, Math.max(1, scale * factor));
    if (next === scale) return;
    const moved = {
      x: mx - ((mx - offset.x) * next) / scale,
      y: my - ((my - offset.y) * next) / scale,
    };
    setOffset(clampOffset(moved, next));
    setScale(next);
  };

  const onWheel = (event: React.WheelEvent) => {
    event.preventDefault();
    const rect = frameRef.current?.getBoundingClientRect();
    if (!rect) return;
    zoomAt(event.deltaY < 0 ? 1.15 : 1 / 1.15, event.clientX - rect.left, event.clientY - rect.top);
  };

  const coordsAt = (clientX: number, clientY: number) => {
    const image = imageRef.current;
    if (!image) return null;
    const rect = image.getBoundingClientRect();
    const fx = (clientX - rect.left) / rect.width;
    const fz = (clientY - rect.top) / rect.height;
    if (fx < 0 || fz < 0 || fx > 1 || fz > 1) return null;
    return { x: Math.round(fx * size - radius), z: Math.round(fz * size - radius) };
  };

  const onPointerDown = (event: React.PointerEvent) => {
    (event.currentTarget as HTMLElement).setPointerCapture?.(event.pointerId);
    pointers.current.set(event.pointerId, { x: event.clientX, y: event.clientY });
    if (pointers.current.size === 2) {
      drag.current = null;
      const [a, b] = [...pointers.current.values()];
      const rect = frameRef.current?.getBoundingClientRect();
      pinch.current = {
        d0: Math.max(1, Math.hypot(a.x - b.x, a.y - b.y)),
        s0: scale,
        mx: (a.x + b.x) / 2 - (rect?.left ?? 0),
        my: (a.y + b.y) / 2 - (rect?.top ?? 0),
        ox: offset.x,
        oy: offset.y,
      };
    } else if (pointers.current.size === 1) {
      drag.current = { x: event.clientX, y: event.clientY, ox: offset.x, oy: offset.y, moved: false };
    }
  };

  const onPointerMove = (event: React.PointerEvent) => {
    if (pointers.current.has(event.pointerId)) {
      pointers.current.set(event.pointerId, { x: event.clientX, y: event.clientY });
    }
    if (pinch.current && pointers.current.size >= 2) {
      const [a, b] = [...pointers.current.values()];
      const distance = Math.max(1, Math.hypot(a.x - b.x, a.y - b.y));
      const started = pinch.current;
      const next = Math.min(16, Math.max(1, (started.s0 * distance) / started.d0));
      setScale(next);
      setOffset(
        clampOffset(
          {
            x: started.mx - ((started.mx - started.ox) * next) / started.s0,
            y: started.my - ((started.my - started.oy) * next) / started.s0,
          },
          next,
        ),
      );
      return;
    }
    const current = drag.current;
    if (current) {
      const dx = event.clientX - current.x;
      const dy = event.clientY - current.y;
      if (Math.abs(dx) + Math.abs(dy) > 3) current.moved = true;
      setOffset(clampOffset({ x: current.ox + dx, y: current.oy + dy }, scale));
    }
    setHover(coordsAt(event.clientX, event.clientY));
  };

  const onPointerUp = (event: React.PointerEvent) => {
    const wasClick = drag.current !== null && !drag.current.moved;
    pointers.current.delete(event.pointerId);
    if (pointers.current.size < 2) pinch.current = null;
    drag.current = null;
    if (wasClick) {
      const point = coordsAt(event.clientX, event.clientY);
      if (point) setPinned(point);
    }
  };

  const lines = [];
  const labels = [];
  if (layers.grid) {
    for (let block = -radius; block <= radius; block += 64) {
      const major = block % 256 === 0;
      const stroke = major ? "#ffffff" : "#bfd4e8";
      const opacity = major ? 0.32 : 0.14;
      lines.push(
        <line key={`v${block}`} x1={block} y1={-radius} x2={block} y2={radius}
          stroke={stroke} strokeOpacity={opacity} strokeWidth={1} vectorEffect="non-scaling-stroke" />,
        <line key={`h${block}`} x1={-radius} y1={block} x2={radius} y2={block}
          stroke={stroke} strokeOpacity={opacity} strokeWidth={1} vectorEffect="non-scaling-stroke" />,
      );
      if (major) {
        // Lathe-style tick labels: the font is scaled down by the zoom so the
        // text keeps the same size on screen whatever the viewport does.
        labels.push(
          <text key={`tx${block}`} x={block} y={-radius + 14 / scale} fontSize={11 / scale}
            fill="#d6e4f0" fillOpacity={0.75} textAnchor="middle">{block}</text>,
          <text key={`tz${block}`} x={-radius + 4 / scale} y={block + 4 / scale} fontSize={11 / scale}
            fill="#d6e4f0" fillOpacity={0.75} textAnchor="start">{block}</text>,
        );
      }
    }
  }

  const imageUrl = withBase(
    view.source === "job"
      ? `/api/mc/preview/${view.id}.png`
      : `/api/mc/preview/history/${view.id}.png`,
  );

  const pxPerBlock = ((box || 1) * scale) / size;
  const barBlocks = SCALE_STEPS.find((value) => value * pxPerBlock >= 56) ?? SCALE_STEPS[SCALE_STEPS.length - 1];
  const barWidth = barBlocks * pxPerBlock;

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
      <div style={{ display: "flex", flexWrap: "wrap", gap: 12, alignItems: "center", fontSize: 12 }}>
        <label style={{ display: "flex", gap: 4, alignItems: "center" }}>
          <input type="checkbox" checked={layers.grid}
            onChange={(e) => setLayers({ ...layers, grid: e.target.checked })} />
          网格
        </label>
        <label style={{ display: "flex", gap: 4, alignItems: "center" }}>
          <input type="checkbox" checked={layers.slime}
            onChange={(e) => setLayers({ ...layers, slime: e.target.checked })} />
          <span style={{ display: "inline-block", width: 10, height: 10, background: "#59c46a", opacity: 0.55 }} />
          史莱姆区块
        </label>
        <label style={{ display: "flex", gap: 4, alignItems: "center" }}>
          <input type="checkbox" checked={layers.structures}
            onChange={(e) => setLayers({ ...layers, structures: e.target.checked })} />
          <span style={{ display: "inline-block", width: 10, height: 10, background: "#FFD54F", borderRadius: 5 }} />
          结构
        </label>
        <span style={{ flex: 1 }} />
        <span className="dim mono">{Math.round(scale * 100)}%</span>
        {pinned && (
          <button className="btn btn-text" onClick={() => setPinned(null)}>
            取消选点
          </button>
        )}
        <button className="btn btn-text" onClick={reset}>
          复位
        </button>
      </div>

      <div
        ref={frameRef}
        onWheel={onWheel}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={onPointerUp}
        onPointerLeave={() => {
          pointers.current.clear();
          drag.current = null;
          pinch.current = null;
          setHover(null);
        }}
        onDoubleClick={reset}
        style={{
          position: "relative",
          width: "100%",
          aspectRatio: "1 / 1",
          overflow: "hidden",
          borderRadius: 6,
          border: "1px solid rgba(127,127,127,.25)",
          cursor: "crosshair",
          touchAction: "none",
          background: "#141418",
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
            src={`${imageUrl}?v=${view.id}`}
            alt={`种子 ${view.seed} 的地图`}
            draggable={false}
            style={{ width: "100%", height: "100%", display: "block", imageRendering: "pixelated" }}
          />
          <svg
            viewBox={`${-radius} ${-radius} ${size} ${size}`}
            style={{ position: "absolute", inset: 0, width: "100%", height: "100%", pointerEvents: "none" }}
          >
            {lines}
            {labels}
            {layers.slime &&
              meta?.slimeChunks?.map((chunk) => (
                <rect key={`s${chunk.x}:${chunk.z}`} x={chunk.x * 16} y={chunk.z * 16}
                  width={16} height={16} fill="#59c46a" fillOpacity={0.3} />
              ))}
            {layers.structures &&
              meta?.structures?.map((structure, index) => (
                <rect key={`m${index}`} x={structure.x - 2} y={structure.z - 2} width={4} height={4}
                  fill={markerColor(structure.id)} stroke="#1a1a1a" strokeWidth={1}
                  vectorEffect="non-scaling-stroke"
                  transform={`rotate(45 ${structure.x} ${structure.z})`} />
              ))}
            <line x1={-8} y1={0} x2={8} y2={0} stroke="#e53935" strokeWidth={2} vectorEffect="non-scaling-stroke" />
            <line x1={0} y1={-8} x2={0} y2={8} stroke="#e53935" strokeWidth={2} vectorEffect="non-scaling-stroke" />
            {pinned && (
              <g>
                <line x1={pinned.x - 14 / scale} y1={pinned.z} x2={pinned.x + 14 / scale} y2={pinned.z}
                  stroke="#ffffff" strokeWidth={2} vectorEffect="non-scaling-stroke" />
                <line x1={pinned.x} y1={pinned.z - 14 / scale} x2={pinned.x} y2={pinned.z + 14 / scale}
                  stroke="#ffffff" strokeWidth={2} vectorEffect="non-scaling-stroke" />
              </g>
            )}
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
          {pinned ? `📍 X ${pinned.x}  Z ${pinned.z}` : hover ? `X ${hover.x}  Z ${hover.z}` : `种子 ${view.seed}`}
        </div>

        {/* 比例尺：随缩放变化，像地图软件左下角那根 */}
        <div style={{ position: "absolute", left: 10, bottom: 10, pointerEvents: "none", color: "#e8eef4", fontSize: 11 }}>
          <div style={{ width: barWidth, height: 4, borderLeft: "1px solid #e8eef4", borderRight: "1px solid #e8eef4", borderBottom: "1px solid #e8eef4" }} />
          <div className="mono" style={{ marginTop: 2, textShadow: "0 1px 2px rgba(0,0,0,.8)" }}>{barBlocks} 格</div>
        </div>
      </div>

      {/* 坐标读数：固定在地图下面，像 CAD 的状态栏 */}
      <div
        className="mono"
        style={{
          display: "flex",
          flexWrap: "wrap",
          gap: 16,
          alignItems: "center",
          fontSize: 12,
          padding: "6px 10px",
          borderRadius: 4,
          background: "rgba(127,127,127,.10)",
        }}
      >
        <span>X <b>{hover ? hover.x : pinned ? pinned.x : "—"}</b></span>
        <span>Z <b>{hover ? hover.z : pinned ? pinned.z : "—"}</b></span>
        <span className="dim">中心 (0, 0) · 范围 {size} 格</span>
        <span style={{ flex: 1 }} />
        {pinned && (
          <button
            className="btn btn-text"
            style={{ padding: "0 6px" }}
            onClick={() => void navigator.clipboard?.writeText(`${pinned.x} ${pinned.z}`)}
          >
            复制坐标
          </button>
        )}
        <span className="dim">{Math.round(scale * 100)}% · {Math.round(pxPerBlock * 100) / 100} px/格</span>
      </div>
    </div>
  );
}
