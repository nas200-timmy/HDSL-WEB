import { useEffect, useState } from "react";
import { api, type McJavaRuntimesSnapshot } from "../api";
import { requestDeveloperPassword, isElevationRequired } from "../elevation";
import { refreshMcFeature, useMcFeature } from "../mcFeature";
import { toast } from "../store";
import { errMsg } from "../utils";
import { DeleteIcon, RefreshIcon, WarningIcon } from "./icons";

/** 设置页里的 Java 运行时（MC 专用），放在 Node.js 托管下面。
 *
 *  只做一件事：让面板自己拥有一份 JDK，不再依赖宿主机恰好装了哪个版本。
 *  **面板自身 JVM 与 PATH 上的 java 只读展示**——这里没有、也不该有能把它们
 *  换掉或删掉的按钮；实例的「高级设置」里选的 javaPath 也一样，只在那边改。
 *
 *  和 Node 托管同一个目录心法：`<data>/minecraft/java/<feature>/`，随数据卷
 *  持久，容器重建不丢；root 拥有、`0755`，agent 只能读和执行，改不了。 */

export function McJavaRuntimeCard() {
  const feature = useMcFeature();
  const enabled = feature?.enabled === true;
  const [snapshot, setSnapshot] = useState<McJavaRuntimesSnapshot | null>(null);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [target, setTarget] = useState(21);
  const [taskId, setTaskId] = useState<string | null>(null);
  const [taskMsg, setTaskMsg] = useState("");
  const [error, setError] = useState("");

  const load = () => {
    if (!enabled) return;
    setLoading(true);
    void api.mc
      .javaRuntimes()
      .then((answer) => {
        setSnapshot(answer);
        setError("");
        const first = answer.available[0]?.feature;
        if (answer.installed.length === 0 && first) setTarget(first);
      })
      .catch((e) => setError(errMsg(e)))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    if (!enabled) {
      setSnapshot(null);
      return;
    }
    load();
    void refreshMcFeature();
  }, [enabled]);

  useEffect(() => {
    if (!taskId) return;
    const timer = window.setInterval(() => {
      void api
        .tasks()
        .then((tasks) => {
          const task = tasks.find((t) => t.id === taskId);
          if (!task) return;
          setTaskMsg(task.message ?? "");
          if (task.state === "done") {
            setTaskId(null);
            setBusy(false);
            setTaskMsg("");
            toast("success", "JDK 已安装");
            load();
          } else if (task.state === "failed") {
            setTaskId(null);
            setBusy(false);
            setError(task.error ?? task.message ?? "安装失败");
          }
        })
        .catch(() => undefined);
    }, 800);
    return () => window.clearInterval(timer);
  }, [taskId]);

  const elevated = async <T,>(call: () => Promise<T>): Promise<T> => {
    try {
      return await call();
    } catch (e) {
      if (!isElevationRequired(e)) throw e;
      if (!(await requestDeveloperPassword())) throw e;
      return await call();
    }
  };

  const install = async () => {
    setBusy(true);
    setError("");
    try {
      const answer = await elevated(() => api.mc.installJava(target));
      setTaskId(answer.taskId);
      setTaskMsg(`正在下载 JDK ${target}…`);
    } catch (e) {
      setBusy(false);
      setError(errMsg(e));
    }
  };

  const remove = async (featureRelease: number) => {
    if (!window.confirm(`删除面板托管的 JDK ${featureRelease}？磁盘上的目录会被整个删掉。`)) {
      return;
    }
    setBusy(true);
    setError("");
    try {
      await elevated(() => api.mc.removeJava(featureRelease));
      toast("success", `已删除 JDK ${featureRelease}`);
      load();
    } catch (e) {
      setError(errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  if (!enabled) {
    return null;
  }

  const installed = snapshot?.installed ?? [];
  const available = snapshot?.available ?? [];
  const consumers = snapshot?.consumers ?? [];

  return (
    <>
      <div className="section-caption">
        <span>Java 运行时（Minecraft）</span>
      </div>

      <div className="card" style={{ display: "grid", gap: 8, padding: 12 }}>
        <div className="toolbar-row" style={{ padding: "4px 0", flexWrap: "wrap" }}>
          <span style={{ fontSize: 13, fontWeight: 600 }}>面板托管的 JDK</span>
          <span className="tag plain">{installed.length} 个</span>
          <span className="spacer" />
          <button className="btn btn-text ripple-host" disabled={loading || busy} onClick={load}>
            <RefreshIcon size={16} /> 刷新
          </button>
        </div>

        {installed.length === 0 && (
          <span style={{ fontSize: 13, color: "var(--monet-on-surface-variant)" }}>
            还没有托管 JDK。装一个之后，MC 实例在宿主机缺对应版本时会自动用它。
          </span>
        )}

        {installed.map((runtime) => {
          const pinned = consumers.filter((c) => c.feature === runtime.feature);
          const blocked = pinned.length > 0;
          return (
            <div key={runtime.feature} className="toolbar-row" style={{ padding: "4px 0", flexWrap: "wrap", gap: 6 }}>
              <span style={{ fontSize: 13, fontWeight: 600 }}>JDK {runtime.feature}</span>
              <span className="tag plain">{runtime.version ?? "版本未知"}</span>
              {runtime.bytes ? (
                <span className="tag plain">{Math.round(runtime.bytes / 1024 / 1024)} MB</span>
              ) : null}
              <code style={{ fontSize: 11, color: "var(--monet-on-surface-variant)", wordBreak: "break-all" }}>
                {runtime.path}
              </code>
              {blocked && (
                <span className="tag plain" title={pinned.map((c) => c.id).join("、")}>
                  被 {pinned.length} 个实例固定
                </span>
              )}
              <span className="spacer" />
              <button
                className="btn btn-text ripple-host"
                style={{ height: 28 }}
                disabled={busy || blocked}
                title={blocked ? "先到实例的高级设置里改掉 javaPath" : ""}
                onClick={() => void remove(runtime.feature)}
              >
                <DeleteIcon size={16} /> 删除
              </button>
            </div>
          );
        })}

        <div className="toolbar-row" style={{ padding: "4px 0", flexWrap: "wrap", gap: 6 }}>
          <select
            className="input"
            style={{ minHeight: 36, width: 220 }}
            value={target}
            disabled={busy || available.length === 0}
            onChange={(e) => setTarget(Number(e.target.value))}
          >
            {available.map((release) => (
              <option key={release.feature} value={release.feature}>
                JDK {release.feature} · {release.version} · {Math.round(release.size / 1024 / 1024)} MB
              </option>
            ))}
            {available.length === 0 && <option value={target}>Adoptium 列表不可用</option>}
          </select>
          <button
            className="btn btn-raised ripple-host"
            disabled={busy || available.length === 0 || installed.some((r) => r.feature === target)}
            onClick={() => void install()}
          >
            {busy ? "安装中…" : "安装 JDK"}
          </button>
          {installed.some((r) => r.feature === target) && (
            <span style={{ fontSize: 12, color: "var(--monet-on-surface-variant)" }}>
              这个主版本已经装好了
            </span>
          )}
        </div>
        {taskMsg && (
          <span style={{ fontSize: 12, color: "var(--monet-on-surface-variant)" }}>{taskMsg}</span>
        )}
        {snapshot?.source === "unavailable" && (
          <div className="hint warning" style={{ margin: 0 }}>
            <span style={{ flex: 1 }}>
              <WarningIcon size={14} style={{ verticalAlign: "-2px", marginRight: 4 }} />
              Adoptium 列表读取失败：{snapshot.error ?? "网络或镜像不可达"}。已装的 JDK 仍可正常使用。
            </span>
          </div>
        )}
      </div>

      {/* 只读区：这两个 Java 是面板/容器自己的，任何按钮都不该碰它们。 */}
      <div className="card" style={{ display: "grid", gap: 6, padding: 12 }}>
        <div className="toolbar-row" style={{ padding: "2px 0" }}>
          <span style={{ fontSize: 13, fontWeight: 600 }}>只读：面板与容器自己的 Java</span>
          <span className="tag plain">不会被安装、删除或切换</span>
        </div>
        <div className="comp-row" style={{ padding: "3px 0" }}>
          <span className="comp-label" style={{ width: "auto", flex: "none" }}>面板自身 JVM</span>
          <span className="comp-value" style={{ fontSize: 12 }}>
            {snapshot ? `${snapshot.panel.vendor} ${snapshot.panel.version}` : "读取中…"}
          </span>
        </div>
        <div className="comp-row" style={{ padding: "3px 0" }}>
          <span className="comp-label" style={{ width: "auto", flex: "none" }}>系统 PATH</span>
          <span className="comp-value" style={{ fontSize: 12 }}>
            {snapshot == null
              ? "读取中…"
              : snapshot.system.ok
                ? `java ${snapshot.system.version}`
                : snapshot.system.error ?? "未检测到 java"}
          </span>
        </div>
        <div className="comp-row" style={{ padding: "3px 0" }}>
          <span className="comp-label" style={{ width: "auto", flex: "none" }}>MC 当前生效</span>
          <span className="comp-value" style={{ fontSize: 12 }}>
            {feature?.java
              ? `${feature.java.path || "(空)"}${feature.java.version ? ` · ${feature.java.version}` : ""}`
              : "—"}
          </span>
        </div>
        <span style={{ fontSize: 12, color: "var(--monet-on-surface-variant)" }}>
          这三个值只用于对照。要换 MC 用的 Java，去实例的「高级设置」里选；
          面板自身的 JVM 由启动脚本决定，面板不会改。
        </span>
      </div>

      {error && (
        <div className="hint warning" style={{ margin: 0 }}>
          <span style={{ flex: 1 }}>{error}</span>
        </div>
      )}
    </>
  );
}
