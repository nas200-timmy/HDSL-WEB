import { useEffect, useMemo, useRef, useState } from "react";
import { api } from "../api";
import type { SystemSnapshot, SystemStats } from "../types";
import { errMsg } from "../utils";
import { TuneIcon } from "./icons";

/// 后端环形缓冲的容量；首次打开要一次拿满，之后只取最新一帧。
const HISTORY_MAX = 240;

/// 0..1 的占用写成百分比；-1 是"还在采第一个差值"。
function pct(value: number): string {
  return value < 0 ? "—" : `${(value * 100).toFixed(0)}%`;
}

function fmtBytes(bytes: number): string {
  if (bytes < 0) return "—";
  const units = ["B", "KiB", "MiB", "GiB", "TiB"];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  const digits = value >= 100 ? 0 : value >= 10 ? 1 : 2;
  return `${value.toFixed(digits)} ${units[unit]}`;
}

function fmtRate(bytesPerSecond: number): string {
  return bytesPerSecond < 0 ? "—" : `${fmtBytes(bytesPerSecond)}/s`;
}

/// 同一时刻只留一帧，按时间排好，只保留最近 HISTORY_MAX 条。
function mergeHistory(previous: SystemSnapshot[], incoming: SystemSnapshot[]): SystemSnapshot[] {
  const byTime = new Map<string, SystemSnapshot>();
  for (const snapshot of [...previous, ...incoming]) {
    byTime.set(snapshot.at, snapshot);
  }
  return [...byTime.values()]
    .sort((left, right) => Date.parse(left.at) - Date.parse(right.at))
    .slice(-HISTORY_MAX);
}

/// 一行总量：每项都能算时给速率，有一项是 -1 就整体"采集中"。
function totalRate(values: number[]): number {
  if (values.length === 0) return -1;
  let sum = 0;
  for (const value of values) {
    if (value < 0) return -1;
    sum += value;
  }
  return sum;
}

/// 每核热力块的颜色：越满越亮，未知是灰的。
function coreColor(value: number): string {
  if (value < 0) return "var(--monet-outline-variant)";
  const alpha = 0.12 + 0.88 * Math.min(1, Math.max(0, value));
  return `rgba(78, 111, 255, ${alpha.toFixed(2)})`;
}

/// 一分钟量级的迷你折线，不引图表库：一个 canvas 就够。
function Sparkline({ points, height = 34 }: { points: Array<{ t: number; v: number }>; height?: number }) {
  const ref = useRef<HTMLCanvasElement | null>(null);

  useEffect(() => {
    const canvas = ref.current;
    if (!canvas) return;
    const ratio = window.devicePixelRatio || 1;
    const width = Math.max(1, canvas.clientWidth || 300);
    canvas.width = Math.floor(width * ratio);
    canvas.height = Math.floor(height * ratio);
    const context = canvas.getContext("2d");
    if (!context) return;
    context.scale(ratio, ratio);
    context.clearRect(0, 0, width, height);

    context.globalAlpha = 0.25;
    context.strokeStyle = "#4e6fff";
    context.lineWidth = 1;
    context.beginPath();
    context.moveTo(0, height - 0.5);
    context.lineTo(width, height - 0.5);
    context.stroke();
    context.globalAlpha = 1;

    if (points.length < 2) return;
    const first = points[0].t;
    const span = Math.max(1, points[points.length - 1].t - first);
    context.beginPath();
    points.forEach((point, index) => {
      const x = ((point.t - first) / span) * width;
      const y = height - Math.max(0, Math.min(1, point.v)) * (height - 2) - 1;
      if (index === 0) context.moveTo(x, y);
      else context.lineTo(x, y);
    });
    context.strokeStyle = "#4e6fff";
    context.lineWidth = 1.5;
    context.stroke();
  }, [points, height]);

  return <canvas ref={ref} style={{ width: "100%", height, display: "block" }} />;
}

/// 通用 → 系统资源：总览 + 每核 + 内存 + 磁盘 + 网络。
///
/// 数据全部来自 `/api/system/stats`；这里**不做任何平台判断**——后端的能力位
/// 决定哪张子卡值得画，前端只负责画。采样频率也不由这里决定：请求再快，
/// 后端也只是把最新一帧给你。
export function SystemMonitorCard() {
  const [snapshot, setSnapshot] = useState<SystemSnapshot | null>(null);
  const [history, setHistory] = useState<SystemSnapshot[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [intervalMs, setIntervalMs] = useState(2000);
  const [paused, setPaused] = useState(false);
  const inFlight = useRef(false);

  const load = async (withHistory: boolean) => {
    if (inFlight.current) return;
    inFlight.current = true;
    try {
      const answer: SystemStats = await api.systemStats(withHistory ? HISTORY_MAX : 0);
      if (answer.snapshot) {
        setSnapshot(answer.snapshot);
        setHistory((previous) =>
          mergeHistory(previous, answer.history.length > 0 ? answer.history : [answer.snapshot!]),
        );
      } else if (answer.history.length > 0) {
        setHistory((previous) => mergeHistory(previous, answer.history));
        setSnapshot(answer.history[answer.history.length - 1]);
      }
      setError(null);
    } catch (e) {
      setError(errMsg(e));
    } finally {
      inFlight.current = false;
    }
  };

  useEffect(() => {
    void load(true);
  }, []);

  useEffect(() => {
    if (paused) return;
    const refresh = () => {
      if (document.visibilityState === "visible") void load(false);
    };
    const timer = window.setInterval(refresh, intervalMs);
    document.addEventListener("visibilitychange", refresh);
    return () => {
      window.clearInterval(timer);
      document.removeEventListener("visibilitychange", refresh);
    };
  }, [intervalMs, paused]);

  const cpuPoints = useMemo(
    () =>
      history
        .map((sample) => ({ t: Date.parse(sample.at), v: sample.cpu.load }))
        .filter((point) => Number.isFinite(point.t) && point.v >= 0),
    [history],
  );
  const memoryPoints = useMemo(
    () =>
      history
        .map((sample) => ({
          t: Date.parse(sample.at),
          v: sample.memory.totalBytes > 0 ? sample.memory.usedBytes / sample.memory.totalBytes : -1,
        }))
        .filter((point) => Number.isFinite(point.t) && point.v >= 0),
    [history],
  );

  const cpu = snapshot?.cpu;
  const memory = snapshot?.memory;
  const capabilities = snapshot?.capabilities;
  const diskRead = snapshot ? totalRate(snapshot.disks.map((disk) => disk.readBytesPerSec)) : -1;
  const diskWrite = snapshot ? totalRate(snapshot.disks.map((disk) => disk.writeBytesPerSec)) : -1;
  const netRx = snapshot ? totalRate(snapshot.networks.map((network) => network.rxBytesPerSec)) : -1;
  const netTx = snapshot ? totalRate(snapshot.networks.map((network) => network.txBytesPerSec)) : -1;
  const memoryRatio = memory && memory.totalBytes > 0 ? memory.usedBytes / memory.totalBytes : -1;

  return (
    <>
      <div className="section-caption">
        <TuneIcon size={16} />
        系统资源
      </div>
      <div className="card">
        {error && (
          <div style={{ display: "flex", alignItems: "center", gap: 8, padding: "10px 8px" }}>
            <span style={{ color: "var(--monet-error)" }}>读取失败：{error}</span>
            <button className="btn btn-text" style={{ height: 26, marginLeft: "auto" }} onClick={() => void load(true)}>
              重试
            </button>
          </div>
        )}
        {!snapshot ? (
          <div style={{ display: "flex", alignItems: "center", gap: 10, padding: 12 }}>
            <span className="spinner" />
            <span>正在采集系统信息…</span>
          </div>
        ) : (
          <>
            <div className="comp-row">
              <span className="comp-label">CPU</span>
              <span className="comp-value">
                <b>{pct(cpu!.load)}</b>
                <span style={{ color: "var(--monet-on-surface-variant)" }}>
                  {" "}
                  · {cpu!.count} 核
                  {cpu!.quotaCores > 0 && cpu!.quotaCores < cpu!.count
                    ? ` · 配额 ${cpu!.quotaCores.toFixed(2)} 核`
                    : ""}
                </span>
              </span>
            </div>
            <div style={{ padding: "0 8px 6px" }}>
              <Sparkline points={cpuPoints} />
            </div>

            <div className="comp-row">
              <span className="comp-label">内存</span>
              <span className="comp-value">
                {fmtBytes(memory!.usedBytes)} / {fmtBytes(memory!.totalBytes)}（{pct(memoryRatio)}）
              </span>
            </div>
            <div className="progress" style={{ margin: "0 8px 6px" }}>
              <div className="bar" style={{ width: `${Math.max(0, Math.min(100, memoryRatio * 100))}%` }} />
            </div>
            <div style={{ padding: "0 8px 6px" }}>
              <Sparkline points={memoryPoints} height={26} />
            </div>

            <div className="comp-row">
              <span className="comp-label">负载</span>
              <span className="comp-value mono">
                {cpu!.loadAverage.length > 0
                  ? cpu!.loadAverage.map((value) => value.toFixed(2)).join(" / ")
                  : "—"}
              </span>
            </div>
            <div className="comp-row">
              <span className="comp-label">磁盘吞吐</span>
              <span className="comp-value mono">
                {capabilities!.diskIo ? `读 ${fmtRate(diskRead)} · 写 ${fmtRate(diskWrite)}` : "不支持"}
              </span>
            </div>
            <div className="comp-row">
              <span className="comp-label">网络吞吐</span>
              <span className="comp-value mono">
                {capabilities!.network ? `↓ ${fmtRate(netRx)} · ↑ ${fmtRate(netTx)}` : "不支持"}
              </span>
            </div>
            <div className="comp-row">
              <span className="comp-label">面板自身</span>
              <span className="comp-value mono" style={{ fontSize: 12 }}>
                CPU {pct(snapshot.panel.cpuLoad)} · 内存 {fmtBytes(snapshot.panel.rssBytes)} ·{" "}
                {snapshot.panel.threads} 线程
              </span>
            </div>

            <div
              style={{
                display: "flex",
                alignItems: "center",
                gap: 8,
                flexWrap: "wrap",
                padding: "6px 8px 10px",
                fontSize: 12,
                color: "var(--monet-on-surface-variant)",
              }}
            >
              <span>
                采样 {Math.round(snapshot.intervalMs / 1000)} 秒一帧
                {snapshot.intervalMs >= 30_000 ? "（低频守护：暂时没人使用面板）" : ""}
              </span>
              <span>· 最后更新 {new Date(snapshot.at).toLocaleTimeString()}</span>
              <span style={{ marginLeft: "auto", display: "flex", alignItems: "center", gap: 6 }}>
                刷新
                <select
                  className="input"
                  style={{ width: 92, height: 26, padding: "0 6px" }}
                  value={intervalMs}
                  onChange={(e) => setIntervalMs(Number(e.target.value))}
                >
                  <option value={1000}>1 秒</option>
                  <option value={2000}>2 秒</option>
                  <option value={5000}>5 秒</option>
                </select>
                <button className="btn btn-text" style={{ height: 26 }} onClick={() => setPaused((value) => !value)}>
                  {paused ? "继续" : "暂停"}
                </button>
              </span>
            </div>
          </>
        )}
      </div>

      {snapshot && capabilities!.perCore && (
        <div className="card">
          <div className="comp-row">
            <span className="comp-label">每核占用</span>
            <span className="comp-value" style={{ fontSize: 12, color: "var(--monet-on-surface-variant)" }}>
              {cpu!.count} 个逻辑核（容器里显示的是宿主机核心）
            </span>
          </div>
          <div
            style={{
              display: "grid",
              gridTemplateColumns: "repeat(auto-fill, minmax(22px, 1fr))",
              gap: 4,
              padding: "4px 8px 10px",
            }}
          >
            {cpu!.cores.map((value, index) => (
              <div
                key={index}
                title={`核 ${index} · ${pct(value)}`}
                style={{ height: 22, borderRadius: 4, background: coreColor(value) }}
              />
            ))}
          </div>
        </div>
      )}

      {snapshot && (
        <div className="card">
          <div className="comp-row">
            <span className="comp-label">内存明细</span>
            <span className="comp-value mono" style={{ fontSize: 12 }}>
              {memory!.source === "cgroup" ? "容器限额" : "整机"} · 可用 {fmtBytes(memory!.availableBytes)}
            </span>
          </div>
          <div className="comp-row">
            <span className="comp-label">交换</span>
            <span className="comp-value mono" style={{ fontSize: 12 }}>
              {memory!.swapTotalBytes > 0
                ? `${fmtBytes(memory!.swapUsedBytes)} / ${fmtBytes(memory!.swapTotalBytes)}`
                : "未启用"}
            </span>
          </div>
        </div>
      )}

      {snapshot && capabilities!.diskIo && (
        <div className="card">
          <div className="comp-row">
            <span className="comp-label">磁盘</span>
            <span className="comp-value" style={{ fontSize: 12, color: "var(--monet-on-surface-variant)" }}>
              容器里这是宿主机级读数
            </span>
          </div>
          {snapshot.disks.map((disk) => (
            <div className="comp-row" key={disk.name}>
              <span className="comp-label mono">{disk.name}</span>
              <span className="comp-value mono" style={{ fontSize: 12 }}>
                读 {fmtRate(disk.readBytesPerSec)} · 写 {fmtRate(disk.writeBytesPerSec)}
              </span>
            </div>
          ))}
          {snapshot.mounts.map((mount) => {
            const ratio = mount.totalBytes > 0 ? mount.usedBytes / mount.totalBytes : 0;
            return (
              <div key={mount.path} style={{ padding: "2px 8px 8px" }}>
                <div className="comp-row" style={{ borderBottom: "none", padding: 0 }}>
                  <span className="comp-label mono">{mount.path}</span>
                  <span className="comp-value" style={{ fontSize: 12 }}>
                    {fmtBytes(mount.usedBytes)} / {fmtBytes(mount.totalBytes)}（{pct(ratio)}）
                  </span>
                </div>
                <div className="progress" style={{ marginTop: 4 }}>
                  <div className="bar" style={{ width: `${Math.max(0, Math.min(100, ratio * 100))}%` }} />
                </div>
              </div>
            );
          })}
        </div>
      )}

      {snapshot && capabilities!.network && (
        <div className="card">
          <div className="comp-row">
            <span className="comp-label">网络</span>
            <span className="comp-value" style={{ fontSize: 12, color: "var(--monet-on-surface-variant)" }}>
              容器自己的网络命名空间
            </span>
          </div>
          {snapshot.networks.map((network) => (
            <div className="comp-row" key={network.name}>
              <span className="comp-label mono">{network.name}</span>
              <span className="comp-value mono" style={{ fontSize: 12 }}>
                ↓ {fmtRate(network.rxBytesPerSec)} · ↑ {fmtRate(network.txBytesPerSec)}
              </span>
            </div>
          ))}
        </div>
      )}

      {snapshot && capabilities!.gpu && snapshot.gpus.length > 0 && (
        <div className="card">
          <div className="comp-row">
            <span className="comp-label">GPU</span>
            <span className="comp-value" />
          </div>
          {snapshot.gpus.map((gpu) => (
            <div className="comp-row" key={gpu.index}>
              <span className="comp-label mono">{gpu.name}</span>
              <span className="comp-value mono" style={{ fontSize: 12 }}>
                {pct(gpu.utilization)} · 显存 {fmtBytes(gpu.memoryUsedBytes)} / {fmtBytes(gpu.memoryTotalBytes)} ·{" "}
                {gpu.temperatureC.toFixed(0)}°C
              </span>
            </div>
          ))}
        </div>
      )}
    </>
  );
}
