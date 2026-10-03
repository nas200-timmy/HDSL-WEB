import { useEffect, useMemo } from "react";
import { api } from "../api";
import { openDsh } from "../actions";
import { LaunchPane } from "../components/LaunchPane";
import { LogView } from "../components/LogView";
import { MainSideBar } from "../components/SideBar";
import { ProgressBar } from "../components/ProgressBar";
import { I18N } from "../i18n";
import { findTaskForInstance, getState, refreshInstances, useAppState } from "../store";
import type { Instance } from "../types";
import { normState, parsePortFromUrl } from "../utils";

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

/** 主页面：200px 侧边栏 + 壁纸内容区 + 右下启动面板（MainPage.java 结构）。 */
export function MainPage() {
  const s = useAppState();

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
      {current ? <LaunchPane current={current} /> : <LaunchPanePlaceholderInternal />}
      {showProgress && current && <LaunchProgress inst={current} />}
      {showRunning && current && <RunningPane inst={current} />}
    </div>
  );
}

function LaunchPanePlaceholderInternal() {
  return <LaunchPane current={null} />;
}
