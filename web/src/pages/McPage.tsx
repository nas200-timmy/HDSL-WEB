import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { api, type McInstance, type McVersion } from "../api";
import { refreshMcFeature, useMcFeature } from "../mcFeature";
import { AddIcon, RefreshIcon, RocketLaunchIcon } from "../components/icons";
import { requestDeveloperPassword, isElevationRequired } from "../elevation";
import { toast } from "../store";
import { errMsg, mcStateLabel } from "../utils";

/**
 * MC 实例列表（docs/minecraft.md）。
 *
 * 这一页只是「有哪些服、开没开」，点进去是实例自己的二级页面（`/mc/:id`）。
 * 功能开关不在这里：它在「设置 → 品牌实例」，和品牌策略同一层（`McFeatureCard`）。
 * 关闭时整页只有一句「去那里开启」——入口本身在实例列表里根本不会出现。
 */
export function McPage() {
  const nav = useNavigate();
  const feature = useMcFeature();
  const [instances, setInstances] = useState<McInstance[]>([]);
  const [versions, setVersions] = useState<McVersion[]>([]);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);

  const load = async () => {
    setLoading(true);
    try {
      // 版本清单要联网：拉不到只让「创建/装版本」退化，不该把整个页面变成错误页。
      const list = await api.mc.instances();
      setInstances(list.instances);
      api.mc.versions()
        .then((catalog) => setVersions(catalog.versions))
        .catch(() => setVersions([]));
    } catch (e) {
      toast("error", errMsg(e));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void refreshMcFeature();
  }, []);

  useEffect(() => {
    if (feature?.enabled) void load();
  }, [feature?.enabled]);

  // 起停是异步状态（STARTING/STOPPING 由服务端推进），有过渡态时轻量轮询。
  const transitioning = instances.some((instance) => instance.state === "STARTING" || instance.state === "STOPPING");
  useEffect(() => {
    if (!transitioning) return;
    const timer = window.setInterval(() => {
      void api.mc.instances()
        .then((list) => setInstances(list.instances))
        .catch(() => undefined);
    }, 2000);
    return () => window.clearInterval(timer);
  }, [transitioning]);

  const withElevation = async <T,>(call: () => Promise<T>): Promise<T> => {
    try {
      return await call();
    } catch (e) {
      if (!isElevationRequired(e)) throw e;
      if (!(await requestDeveloperPassword())) throw e;
      return await call();
    }
  };

  const start = async (instance: McInstance) => {
    setBusy(true);
    try {
      await api.mc.start(instance.id);
      toast("info", `正在启动「${instance.name}」`);
      await load();
    } catch (e) {
      toast("error", errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  if (!feature?.enabled) {
    return (
      <div className="card" style={{ padding: 16, display: "flex", flexDirection: "column", gap: 12 }}>
        <h2 style={{ margin: 0 }}>MC 服务端（第三方功能）</h2>
        <p className="dim" style={{ margin: 0, lineHeight: 1.7 }}>
          还没开启。开关在「设置 → 品牌实例」里的「MC 服务端」卡片：需要开发者密码，
          读完 Mojang 的 EULA 与服务条款后开启。开启之后，入口才会出现在
          「实例列表 → 品牌实例」下面。
        </p>
        <div>
          <button className="btn btn-raised" onClick={() => nav("/settings?tab=brands")}>
            去品牌实例管理
          </button>
        </div>
      </div>
    );
  }

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 8, minHeight: 0 }}>
      <div className="card" style={{ padding: 16, display: "flex", alignItems: "center", gap: 10, flexWrap: "wrap" }}>
        <h2 style={{ margin: 0 }}>MC 服务端</h2>
        <span className="tag plain">第三方工具 · 非官方</span>
        <span style={{ flex: 1 }} />
        <button className="btn btn-text" onClick={() => nav("/instances?view=brands")}>
          返回实例列表
        </button>
        <button className="btn btn-text" disabled={loading} onClick={() => void load()}>
          <RefreshIcon size={16} /> 刷新
        </button>
      </div>

      <p className="dim" style={{ margin: 0, padding: "0 4px", fontSize: 12, lineHeight: 1.7 }}>
        客户端连的是游戏端口，不是面板；默认只建议局域网或加防火墙。
        <b> 服务端程序不随本项目分发</b>，首次安装时从 Mojang 官方下载。
        功能开关在「设置 → 品牌实例」。
      </p>

      <CreateInstanceForm
        versions={versions}
        busy={busy || feature.locked}
        onCreate={async (body) => {
          setBusy(true);
          try {
            await withElevation(() => api.mc.create(body));
            toast("success", `已创建「${body.name}」`);
            await load();
          } catch (e) {
            toast("error", errMsg(e));
          } finally {
            setBusy(false);
          }
        }}
      />

      {feature.locked && (
        <div className="card" style={{ padding: 12, fontSize: 13 }}>
          功能正在冷却（按下停止后一小时内不能启服、不能新建）。倒计时与撤销在「设置 → 品牌实例」。
        </div>
      )}

      {instances.length === 0 ? (
        <div className="card" style={{ padding: 16 }}>还没有 MC 实例。选一个版本，建一个。</div>
      ) : (
        instances.map((instance) => (
          <div
            key={instance.id}
            className="card tlli ripple-host"
            style={{ padding: 12, display: "flex", gap: 10, alignItems: "center", flexWrap: "wrap", cursor: "pointer" }}
            onClick={() => nav(`/mc/${encodeURIComponent(instance.id)}`)}
          >
            <div style={{ minWidth: 180, flex: 1 }}>
              <div style={{ fontWeight: 600 }}>{instance.name}</div>
              <div className="dim" style={{ fontSize: 12 }}>
                {instance.version} · 端口 {instance.port} · RCON {instance.rconPort} · {instance.memoryMb} MB
              </div>
              {instance.error && <div style={{ color: "var(--monet-error)", fontSize: 12 }}>{instance.error}</div>}
            </div>
            <span className={`tag plain${instance.state === "RUNNING" ? "" : " dim"}`}>{mcStateLabel(instance.state)}</span>
            <button
              className="btn btn-text"
              disabled={busy || feature.locked || instance.state === "RUNNING" || instance.state === "STARTING"}
              onClick={(e) => {
                e.stopPropagation();
                void start(instance);
              }}
              title={feature.locked ? "锁定期间不能启服" : undefined}
            >
              <RocketLaunchIcon size={16} /> 启动
            </button>
          </div>
        ))
      )}
    </div>
  );
}

function CreateInstanceForm({
  versions,
  busy,
  onCreate,
}: {
  versions: McVersion[];
  busy: boolean;
  onCreate: (body: { name: string; version: string; memoryMb: number; levelName: string; seed?: string }) => void;
}) {
  const [name, setName] = useState("");
  const [version, setVersion] = useState("");
  const [memoryMb, setMemoryMb] = useState(2048);
  const [levelName, setLevelName] = useState("world");
  const [seed, setSeed] = useState("");
  const releases = versions.filter((entry) => entry.type === "release").slice(0, 80);

  return (
    <div className="card" style={{ padding: 12, display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap" }}>
      <input className="input" placeholder="实例名" value={name} onChange={(e) => setName(e.target.value)} style={{ flex: "1 1 140px" }} />
      <select className="input" value={version} onChange={(e) => setVersion(e.target.value)} style={{ flex: "1 1 140px" }}>
        <option value="">版本</option>
        {releases.map((entry) => (
          <option key={entry.id} value={entry.id}>{entry.id}</option>
        ))}
      </select>
      <input
        className="input"
        type="number"
        min={256}
        step={256}
        value={memoryMb}
        onChange={(e) => setMemoryMb(Number(e.target.value) || 1024)}
        style={{ width: 120 }}
        title="内存（MB）"
      />
      <input className="input" value={levelName} onChange={(e) => setLevelName(e.target.value)} style={{ width: 120 }} title="世界目录名" />
      <input className="input" placeholder="种子（可空）" value={seed} onChange={(e) => setSeed(e.target.value)} style={{ width: 120 }} />
      <button
        className="btn btn-raised"
        disabled={busy || !name.trim() || !version}
        onClick={() => onCreate({ name: name.trim(), version, memoryMb, levelName, seed: seed.trim() || undefined })}
      >
        <AddIcon size={16} /> 创建
      </button>
    </div>
  );
}
