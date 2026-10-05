import { useEffect, useState } from "react";
import { api } from "../api";
import { Dialog } from "./Dialog";
import { Package2Icon } from "./icons";
import { refreshZcode, toast } from "../store";
import type { ZcodeBuildStatus } from "../types";
import { errMsg } from "../utils";

/// 构建 ZCode 发行包：面板下载源码 → 打反代补丁 → pnpm install → pnpm build:zcode
/// → 校验 sha256 → 装成 `releases/<版本>`。慢是常态（几百 MB 源码 + GB 级依赖），
/// 所以这里只做「开始 + 看日志」：状态与日志每 1.5 秒拉一次。
export function ZcodeBuildButton() {
  const [open, setOpen] = useState(false);
  const [version, setVersion] = useState("main");
  const [status, setStatus] = useState<ZcodeBuildStatus | null>(null);
  const [lines, setLines] = useState<string[]>([]);
  const [starting, setStarting] = useState(false);

  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    const tick = async () => {
      try {
        const [state, log] = await Promise.all([api.zcodeBuild(), api.zcodeBuildLog(400)]);
        if (cancelled) return;
        setStatus((previous) => {
          if (previous?.state === "running" && state.state !== "running") {
            void refreshZcode();
          }
          return state;
        });
        setLines(log.lines);
      } catch {
        // 轮询失败不打扰：下一拍再来
      }
    };
    void tick();
    const timer = setInterval(() => void tick(), 1500);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [open]);

  const start = async () => {
    setStarting(true);
    try {
      setStatus(await api.startZcodeBuild(version.trim()));
      toast("info", "已开始构建：下载源码 + 装依赖，慢是正常的（看下面的日志）");
    } catch (e) {
      toast("error", `启动构建失败：${errMsg(e)}`);
    } finally {
      setStarting(false);
    }
  };

  const running = status?.state === "running";

  return (
    <>
      <button className="tool-btn ripple-host" onClick={() => setOpen(true)}>
        <Package2Icon size={18} />
        构建发行包
      </button>
      <Dialog open={open} title="构建 ZCode 发行包（实验性）" wide onClose={() => setOpen(false)}>
        <div className="form-row">
          <span className="form-label">上游版本</span>
          <input
            className="input"
            value={version}
            placeholder="v3.14.3 或 main"
            disabled={running}
            onChange={(e) => setVersion(e.target.value)}
          />
        </div>
        <p style={{ fontSize: 12, color: "var(--monet-on-surface-variant)", padding: "4px 8px 0", lineHeight: 1.7 }}>
          面板会下载 <code>zai-org/ZCode</code> 的源码、打上反代补丁（
          <code>base: "./"</code> + API/WS 前缀，共 5 处）、跑 <code>pnpm install</code> 与{" "}
          <code>pnpm build:zcode</code>，校验 sha256 后装成 <code>releases/&lt;版本&gt;</code>。
          要下几百 MB、装几 GB 依赖，没有 Node 24 构建环境会失败；失败不影响已装好的版本。
          能不能用不保证——这是实验性功能。
        </p>
        {status && status.state !== "idle" && (
          <div className="comp-row">
            <span className="comp-label">状态</span>
            <span className="comp-value">
              {status.message || status.state}
              {running ? `（${Math.round(status.fraction * 100)}%）` : ""}
            </span>
          </div>
        )}
        {status?.error && <div className="form-error">{status.error}</div>}
        <pre
          style={{
            maxHeight: 280,
            overflow: "auto",
            fontSize: 12,
            lineHeight: 1.5,
            margin: 0,
            padding: 8,
            background: "var(--monet-surface-transparent-50)",
            borderRadius: 4,
          }}
        >
          {lines.length === 0 ? "（还没有日志）" : lines.join("\n")}
        </pre>
        <div className="dialog-actions">
          <button className="btn btn-text dim" onClick={() => setOpen(false)}>
            关闭
          </button>
          <button
            className="btn btn-text"
            disabled={running || starting || !version.trim()}
            onClick={() => void start()}
          >
            {running ? "构建中…" : "开始构建"}
          </button>
        </div>
      </Dialog>
    </>
  );
}
