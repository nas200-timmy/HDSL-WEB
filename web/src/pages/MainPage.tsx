import { useEffect, useMemo } from "react";
import { useNavigate } from "react-router-dom";
import { api } from "../api";
import { launchInstance, openDsh, stopInstance } from "../actions";
import { LaunchPane } from "../components/LaunchPane";
import { LogView } from "../components/LogView";
import { MainSideBar, InstanceGraphic } from "../components/SideBar";
import { ProgressBar } from "../components/ProgressBar";
import { StateBadge } from "../components/InstanceIcon";
import { useMobileLayout, useTickingUptime } from "../hooks";
import { I18N } from "../i18n";
import { findTaskForInstance, getState, refreshInstances, useAppState } from "../store";
import type { Instance } from "../types";
import { formatUptime, normState, parsePortFromUrl } from "../utils";

function wallpaper(): string {
  return `${import.meta.env.BASE_URL}assets-img/wallpapers/2021-08-26.jpg`;
}

/** 启动/安装中的进度浮层（HMCL 任务对话框风格：进度条 + 消息 + 实时日志）。 */
function LaunchProgress({ inst }: { inst: Instance }) {
  const s = useAppState();
  const st = normState(inst.state);
  const task = useMemo(() => {
    if (st !== "INSTALLING") return null;
    return findTaskForInstance(inst.id);
  }, [s.tasks, st, inst.id]);

  const title =
    st === "INSTALLING"
      ? I18N["dsh.versions.installing"].replace("%s", inst.version)
      : st === "STARTING"
        ? I18N["dsh.launch.launching"]
        : I18N["dsh.stopping"];

  const cancel = async () => {
    if (st === "INSTALLING" && task) {
      try {
        await api.cancelTask(task.id);
      } catch (e) {
        // 取消失败不阻塞
      }
    } else {
      try {
        await api.stop(inst.id);
      } catch (e) {
        // 取消失败不阻塞
      }
    }
  };

  return (
    <div className="launch-progress">
      <div className="card">
        <div className="progress-title">{title}</div>
        {st === "INSTALLING" && <ProgressBar fraction={task?.fraction} />}
        <div className="progress-msg">{task?.message ?? "…"}</div>
        <div style={{ maxHeight: 150, overflow: "hidden" }}>
          <LogView instanceId={inst.id} maxHeight={150} emptyHint="等待日志输出…" />
        </div>
        <div className="progress-actions">
          <button className="btn btn-text dim ripple-host" onClick={() => void cancel()}>
            {I18N["button.cancel"]}
          </button>
        </div>
      </div>
    </div>
  );
}

/** 运行中浮层：状态 + [打开 dsh]。 */
function RunningPane({ inst }: { inst: Instance }) {
  const port = inst.port ?? parsePortFromUrl(inst.url);
  return (
    <div className="launch-progress">
      <div className="card">
        <div className="progress-title">
          {inst.name}
          <span className="tag running" style={{ marginLeft: 8 }}>
            {I18N["dsh.instance.running"]}
          </span>
        </div>
        <div className="progress-msg">
          {inst.id} · {port != null ? `${I18N["dsh.instance.port"]} ${port}` : ""}
          {inst.url ? ` · ${inst.url}` : ""}
        </div>
        <div className="progress-actions">
          <button className="btn btn-text ripple-host" onClick={() => openDsh(inst.id)}>
            {I18N["dsh.launch.running"]}
          </button>
        </div>
      </div>
    </div>
  );
}

/** 主页面：200px 侧边栏 + 壁纸内容区 + 右下启动面板（MainPage.java 结构）。
 *
 *  移动版式（≤760px 宽或 ≤520px 高，见 styles/mobile.css）下侧栏收成抽屉，
 *  壁纸上就空了 —— 所以这里补一张「当前实例」卡片，
 *  让远程启动/停止 dsh 在手机上一屏能完成（底部操作栏仍然贴底）。 */
export function MainPage() {
  const s = useAppState();
  const mobile = useMobileLayout();

  useEffect(() => {
    void refreshInstances(!getState().instancesLoaded);
  }, []);

  const current = useMemo(() => {
    const running = s.instances.find((i) => normState(i.state) === "RUNNING");
    return running ?? s.instances[0] ?? null;
  }, [s.instances]);

  const st = current ? normState(current.state) : "";
  const showProgress =
    current && (st === "INSTALLING" || st === "STARTING" || st === "STOPPING");
  const showRunning = current && st === "RUNNING";

  return (
    <div className="main-page">
      <MainSideBar />
      <div className="wallpaper-bg" style={{ backgroundImage: `url(${wallpaper()})` }} />
      {mobile && (current ? <MobileHome inst={current} /> : <MobileHomeEmpty />)}
      {current ? <LaunchPane current={current} /> : <LaunchPanePlaceholderInternal />}
      {showProgress && current && <LaunchProgress inst={current} />}
      {showRunning && current && <RunningPane inst={current} />}
    </div>
  );
}

/** 手机端主页内容：当前实例的状态与三个主要动作（启动/停止、打开 dsh、管理）。 */
function MobileHome({ inst }: { inst: Instance }) {
  const nav = useNavigate();
  const st = normState(inst.state);
  const running = st === "RUNNING";
  const busy = st === "INSTALLING" || st === "STARTING" || st === "STOPPING";
  const uptime = useTickingUptime(inst.id, inst.uptimeSec);
  const port = inst.port ?? parsePortFromUrl(inst.url);

  return (
    <div className="mobile-home">
      <div className="card">
        <div className="comp-row">
          <span className="comp-label">{I18N["instance"]}</span>
          <span className="comp-value">
            <InstanceGraphic inst={inst} size={24} />
            {inst.name}
            <StateBadge state={inst.state} />
          </span>
        </div>
        <div className="comp-row">
          <span className="comp-label">版本</span>
          <span className="comp-value">{inst.version}</span>
        </div>
        {port != null && (
          <div className="comp-row">
            <span className="comp-label">{I18N["dsh.instance.port"]}</span>
            <span className="comp-value">{port}</span>
          </div>
        )}
        {running && uptime != null && (
          <div className="comp-row">
            <span className="comp-label">运行时长</span>
            <span className="comp-value">{formatUptime(uptime)}</span>
          </div>
        )}
      </div>
      <div className="card" style={{ display: "flex", gap: 6, padding: 6 }}>
        <button
          className="tool-btn ripple-host"
          style={{ flex: 1, justifyContent: "center" }}
          disabled={busy}
          onClick={() => (running ? stopInstance(inst) : launchInstance(inst))}
        >
          {running ? I18N["dsh.stop"] : I18N["dsh.launch"]}
        </button>
        <button
          className="tool-btn ripple-host"
          style={{ flex: 1, justifyContent: "center" }}
          disabled={!running}
          onClick={() => openDsh(inst.id)}
        >
          {I18N["dsh.launch.running"]}
        </button>
        <button
          className="tool-btn ripple-host"
          style={{ flex: 1, justifyContent: "center" }}
          onClick={() => nav(`/instances/${inst.id}`)}
        >
          {I18N["settings.game.management"]}
        </button>
      </div>
    </div>
  );
}

/** 手机端主页：一个实例都没有时的入口（桌面版式下这些内容在侧栏里）。 */
function MobileHomeEmpty() {
  const nav = useNavigate();
  return (
    <div className="mobile-home">
      <div className="card">
        <div className="empty-state">
          <span style={{ fontSize: 16 }}>{I18N["dsh.instance.none"]}</span>
          <span className="empty-sub">{I18N["dsh.launch.no_instance.hint"]}</span>
          <button className="btn btn-raised ripple-host" onClick={() => nav("/instances/new")}>
            {I18N["dsh.instance.install"]}
          </button>
        </div>
      </div>
    </div>
  );
}

function LaunchPanePlaceholderInternal() {
  return <LaunchPane current={null} />;
}
