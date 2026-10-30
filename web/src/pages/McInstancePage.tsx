import { useEffect, useMemo, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { api, type McInstance, type McVersion } from "../api";
import { SubSideBar } from "../components/SideBar";
import { TerminalWindow } from "../components/TerminalWindow";
import {
  McBackupsPanel,
  McConsolePanel,
  McLogsPanel,
  McPlayersPanel,
  McPropertiesPanel,
  McWorldPanel,
} from "../components/McPanels";
import {
  ArrowBackIcon,
  CancelIcon,
  DeleteIcon,
  InfoIcon,
  Package2Icon,
  PersonIcon,
  PublicIcon,
  RefreshIcon,
  RocketLaunchIcon,
  SettingsFillIcon,
  TerminalIcon,
} from "../components/icons";
import { requestDeveloperPassword, isElevationRequired } from "../elevation";
import { toast } from "../store";
import { errMsg, mcStateLabel } from "../utils";

type McTab = "overview" | "console" | "settings" | "backups" | "players" | "world" | "logs";

const TABS: { key: McTab; title: string; icon: React.ReactNode; iconActive?: React.ReactNode }[] = [
  { key: "overview", title: "概览", icon: <SettingsFillIcon size={20} /> },
  { key: "console", title: "控制台", icon: <TerminalIcon size={20} /> },
  { key: "settings", title: "服务端设置", icon: <Package2Icon size={20} /> },
  { key: "backups", title: "世界备份", icon: <InfoIcon size={20} /> },
  { key: "players", title: "玩家", icon: <PersonIcon size={20} /> },
  { key: "world", title: "世界", icon: <PublicIcon size={20} /> },
  { key: "logs", title: "日志", icon: <InfoIcon size={20} /> },
];

/**
 * MC 实例的二级页面（`/mc/:id`）。
 *
 * 和 dsh 实例一个形状：左边一列是这台服自己的事，右边是内容。列表页只负责
 * 「建 + 启停 + 进去」，凡是会影响这台服的（设置、备份、玩家、世界、日志）都在这里。
 *
 * 没有单独的详情接口以外的状态：实例本身每 2 秒只在过渡态（STARTING/STOPPING）
 * 下拉一次，其余时候靠动作之后的刷新。
 */
export function McInstancePage() {
  const { id = "" } = useParams();
  const nav = useNavigate();
  const [instance, setInstance] = useState<McInstance | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [tab, setTab] = useState<McTab>("overview");
  const [versions, setVersions] = useState<McVersion[]>([]);
  const [busy, setBusy] = useState(false);
  const [terminalOpen, setTerminalOpen] = useState(false);
  const [nameDraft, setNameDraft] = useState("");
  const [installNote, setInstallNote] = useState<string | null>(null);

  const load = async () => {
    try {
      const next = await api.mc.instance(id);
      setInstance(next);
      setLoadError(null);
      return next;
    } catch (e) {
      setLoadError(errMsg(e));
      return null;
    }
  };

  useEffect(() => {
    void load();
    api.mc.versions().then((catalog) => setVersions(catalog.versions)).catch(() => setVersions([]));
  }, [id]);

  useEffect(() => {
    if (instance) setNameDraft((current) => (current === "" ? instance.name : current));
  }, [instance?.name]);

  const transitioning = instance?.state === "STARTING" || instance?.state === "STOPPING";
  useEffect(() => {
    if (!transitioning) return;
    const timer = window.setInterval(() => {
      void api.mc.instance(id)
        .then(setInstance)
        .catch(() => undefined);
    }, 2000);
    return () => window.clearInterval(timer);
  }, [transitioning, id]);

  const withElevation = async <T,>(call: () => Promise<T>): Promise<T> => {
    try {
      return await call();
    } catch (e) {
      if (!isElevationRequired(e)) throw e;
      if (!(await requestDeveloperPassword())) throw e;
      return await call();
    }
  };

  const start = async () => {
    setBusy(true);
    try {
      setInstance(await api.mc.start(id));
      toast("info", "正在启动…");
    } catch (e) {
      toast("error", errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  const stop = async () => {
    setBusy(true);
    try {
      setInstance(await withElevation(() => api.mc.stop(id)));
    } catch (e) {
      toast("error", errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  const kill = async () => {
    if (!window.confirm("强杀？可能丢存档。")) return;
    setBusy(true);
    try {
      setInstance(await withElevation(() => api.mc.kill(id)));
    } catch (e) {
      toast("error", errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  const rename = async () => {
    if (!instance) return;
    const name = nameDraft.trim();
    if (!name || name === instance.name) return;
    setBusy(true);
    try {
      setInstance(await withElevation(() => api.mc.rename(id, name)));
      toast("success", "已重命名");
    } catch (e) {
      toast("error", errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  const remove = async () => {
    if (!instance) return;
    if (!window.confirm(`删除「${instance.name}」？删除前会自动备份世界，备份不会随实例一起删。`)) return;
    setBusy(true);
    try {
      const answer = await withElevation(() => api.mc.remove(id));
      toast("success", `已删除；世界备份：${answer.backup}`);
      nav("/mc", { replace: true });
    } catch (e) {
      toast("error", errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  const install = async (version: string) => {
    setBusy(true);
    setInstallNote(`正在下载 ${version}…`);
    try {
      const { taskId } = await withElevation(() => api.mc.install(id, version));
      // 下载是任务：轮询到结束，顺便把任务的进度文案显示出来。
      for (let i = 0; i < 900; i += 1) {
        const task = await api.task(taskId);
        if (task.message) setInstallNote(task.message);
        if (task.state === "done") {
          toast("success", task.message || `已安装 ${version}`);
          break;
        }
        if (task.state === "failed" || task.state === "cancelled") {
          toast("error", task.error || "安装失败");
          break;
        }
        await new Promise((resolve) => setTimeout(resolve, 1000));
      }
      await load();
    } catch (e) {
      toast("error", errMsg(e));
    } finally {
      setInstallNote(null);
      setBusy(false);
    }
  };

  const releases = useMemo(() => versions.filter((entry) => entry.type === "release").slice(0, 80), [versions]);

  if (loadError) {
    return (
      <div className="card" style={{ padding: 16 }}>
        <h2 style={{ marginTop: 0 }}>MC 实例</h2>
        <p className="dim">{loadError}</p>
        <div style={{ display: "flex", gap: 8 }}>
          <button className="btn btn-raised" onClick={() => void load()}>重试</button>
          <button className="btn btn-text" onClick={() => nav("/mc")}>返回列表</button>
        </div>
      </div>
    );
  }

  if (!instance) {
    return (
      <div className="card" style={{ padding: 16 }}>
        <p className="dim" style={{ margin: 0 }}>正在读取实例…</p>
      </div>
    );
  }

  const running = instance.state === "RUNNING";

  return (
    <div className="page-with-sidebar">
      <nav className="sidebar-sub sidebar-stack" style={{ paddingTop: 0 }}>
        <div className="card instance-actions-box" style={{ display: "flex", padding: 4, gap: 2 }}>
          <button
            className="tool-btn ripple-host"
            style={{ flex: 1, justifyContent: "center" }}
            disabled={busy || instance.state === "STARTING" || instance.state === "STOPPING"}
            onClick={() => (running || instance.state === "STARTING" ? void stop() : void start())}
          >
            {running || instance.state === "STARTING" ? <CancelIcon size={18} /> : <RocketLaunchIcon size={18} />}
            {running || instance.state === "STARTING" ? "停止" : "启动"}
          </button>
          <button
            className="tool-btn ripple-host"
            style={{ flex: 1, justifyContent: "center" }}
            disabled={busy || !running}
            title="可能丢存档"
            onClick={() => void kill()}
          >
            强杀
          </button>
        </div>

        <SubSideBar
          activeKey={tab}
          onSelect={(key) => setTab(key as McTab)}
          sections={[{ items: TABS.map((entry) => ({ key: entry.key, title: entry.title, icon: entry.icon, iconActive: entry.iconActive })) }]}
          bottom={
            <>
              <button className="side-item ripple-host" onClick={() => nav("/mc")}>
                <span className="side-graphic">
                  <ArrowBackIcon size={20} />
                </span>
                <span className="side-text">
                  <span className="side-title">返回 MC 列表</span>
                </span>
              </button>
              <button className="side-item ripple-host" onClick={() => void load()}>
                <span className="side-graphic">
                  <RefreshIcon size={20} />
                </span>
                <span className="side-text">
                  <span className="side-title">刷新状态</span>
                </span>
              </button>
            </>
          }
        />
      </nav>

      <div className="page-content scroll" style={{ gap: 8 }}>
        {tab === "overview" && (
          <>
            <div className="card">
              <div className="comp-row">
                <span className="comp-label">{instance.name}</span>
                <span className="comp-value">
                  <span className={`tag ${running ? "running" : "plain"}`}>{mcStateLabel(instance.state)}</span>
                </span>
              </div>
              <div style={{ padding: "4px 8px 8px", fontSize: 12, color: "var(--monet-on-surface-variant)", lineHeight: 1.7 }}>
                {instance.version} · 游戏端口 {instance.port} · RCON {instance.rconPort} · 内存 {instance.memoryMb} MB ·
                世界 <span className="mono">{instance.levelName}</span>
                {instance.error && (
                  <div style={{ color: "var(--monet-error)" }}>{instance.error}</div>
                )}
              </div>
            </div>

            <div className="card">
              <div className="comp-row">
                <span className="comp-label">实例名</span>
                <span className="comp-value">
                  <input
                    className="input"
                    value={nameDraft}
                    onChange={(e) => setNameDraft(e.target.value)}
                    style={{ width: 220 }}
                  />
                  <button
                    className="btn btn-text"
                    disabled={busy || !nameDraft.trim() || nameDraft.trim() === instance.name}
                    onClick={() => void rename()}
                  >
                    保存
                  </button>
                </span>
              </div>
              <div className="comp-row">
                <span className="comp-label">服务端版本</span>
                <span className="comp-value">
                  <select
                    className="input"
                    defaultValue=""
                    disabled={busy || running}
                    onChange={(e) => {
                      const choice = e.target.value;
                      e.target.value = "";
                      if (choice) void install(choice);
                    }}
                    style={{ width: 150 }}
                  >
                    <option value="">装版本…</option>
                    {releases.map((entry) => (
                      <option key={entry.id} value={entry.id}>{entry.id}</option>
                    ))}
                  </select>
                  <span className="dim" style={{ fontSize: 12 }}>从 Mojang 官方下载并校验 SHA-1；停服才能换</span>
                </span>
              </div>
              {installNote && (
                <div style={{ padding: "0 8px 8px", fontSize: 12 }}>{installNote}</div>
              )}
              <div className="comp-row">
                <span className="comp-label">删除实例</span>
                <span className="comp-value">
                  <button className="btn btn-text danger" disabled={busy || running} onClick={() => void remove()}>
                    <DeleteIcon size={16} /> 删除
                  </button>
                </span>
              </div>
            </div>
          </>
        )}

        {tab === "console" && (
          <McConsolePanel instance={instance} onOpenTerminal={() => setTerminalOpen(true)} />
        )}
        {tab === "settings" && <McPropertiesPanel instance={instance} />}
        {tab === "backups" && <McBackupsPanel instance={instance} />}
        {tab === "players" && <McPlayersPanel instance={instance} />}
        {tab === "world" && <McWorldPanel instance={instance} />}
        {tab === "logs" && <McLogsPanel instance={instance} />}
      </div>

      <TerminalWindow
        target={terminalOpen ? { kind: "mc", id: instance.id, name: instance.name } : null}
        onClose={() => setTerminalOpen(false)}
      />
    </div>
  );
}
