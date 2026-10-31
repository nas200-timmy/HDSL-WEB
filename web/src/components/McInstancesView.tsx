import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { api, type McInstance, type McVersion } from "../api";
import { refreshMcFeature, useMcFeature } from "../mcFeature";
import {
  AddIcon,
  ArrowForwardIcon,
  CancelIcon,
  CloseIcon,
  DeployedCodeIcon,
  InfoIcon,
  RefreshIcon,
  RocketLaunchIcon,
  SearchIcon,
} from "./icons";
import { McCreateWizard } from "./McCreateWizard";
import { toast } from "../store";
import { errMsg, mcStateLabel, normState } from "../utils";

/// MC 服务端实例视图，嵌在「实例列表」页里原地切换（`?view=mc`）。
///
/// 和品牌实例同一种东西：面板托管的另一套进程。所以它不该是一个独立页面——从实例列表
/// 点进来是换内容区，不是跳走再点「返回」。`/mc` 现在只是重定向到这里。
///
/// 版式与 dsh 实例列表一致：工具栏一张卡（刷新 / 计数 / 新建），列表一张卡、卡内
/// `.list-body` 真的滚动。手机上沿用同一套响应式规则，不需要另写一份。
export function McInstancesView() {
  const nav = useNavigate();
  const feature = useMcFeature();
  const [instances, setInstances] = useState<McInstance[]>([]);
  const [versions, setVersions] = useState<McVersion[]>([]);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [wizardOpen, setWizardOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [searchMode, setSearchMode] = useState(false);

  const load = async () => {
    setLoading(true);
    try {
      // 版本清单要联网：拉不到只让「创建/装版本」退化，不该把整个列表变成错误页。
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

  // 起停是服务端推进的异步状态，有过渡态时轻量轮询。
  const transitioning = instances.some(
    (instance) => instance.state === "STARTING" || instance.state === "STOPPING",
  );
  useEffect(() => {
    if (!transitioning) return;
    const timer = window.setInterval(() => {
      void api.mc.instances()
        .then((list) => setInstances(list.instances))
        .catch(() => undefined);
    }, 2000);
    return () => window.clearInterval(timer);
  }, [transitioning]);

  const toggle = async (instance: McInstance) => {
    const running = instance.state === "RUNNING" || instance.state === "STARTING";
    setBusy(true);
    try {
      if (running) {
        await api.mc.stop(instance.id);
        toast("info", `正在停止「${instance.name}」，等世界存盘`);
      } else {
        await api.mc.start(instance.id);
        toast("info", `正在启动「${instance.name}」`);
      }
      await load();
    } catch (e) {
      toast("error", errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  const running = instances.filter((instance) => normState(instance.state) === "RUNNING").length;
  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return instances;
    return instances.filter(
      (instance) =>
        instance.name.toLowerCase().includes(q) ||
        instance.id.toLowerCase().includes(q) ||
        instance.version.toLowerCase().includes(q),
    );
  }, [instances, query]);

  if (!feature?.enabled) {
    return (
      <div className="card" style={{ padding: 16, display: "flex", flexDirection: "column", gap: 12 }}>
        <h2 style={{ margin: 0 }}>MC 服务端（第三方功能）</h2>
        <p className="dim" style={{ margin: 0, lineHeight: 1.7 }}>
          还没开启。开关在「设置 → 品牌实例」里的「MC 服务端」卡片：需要开发者密码，
          读完 Mojang 的 EULA 与服务条款后开启。开启之后，入口才会出现在这一栏下面。
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
    <div style={{ display: "flex", flexDirection: "column", gap: 8, flex: 1, minHeight: 0 }}>
      {searchMode ? (
        <div className="card toolbar-row" style={{ flexWrap: "nowrap" }}>
          <SearchIcon size={18} style={{ color: "var(--monet-on-surface-variant)" }} />
          <input
            className="input"
            style={{ flex: 1 }}
            autoFocus
            placeholder="按名字、编号或版本找"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Escape") {
                setQuery("");
                setSearchMode(false);
              }
            }}
          />
          <button
            className="icon-btn on-variant ripple-host"
            onClick={() => {
              setQuery("");
              setSearchMode(false);
            }}
          >
            <CloseIcon size={18} />
          </button>
        </div>
      ) : (
        <div className="card toolbar-row">
          <button
            className="tool-btn ripple-host"
            disabled={loading}
            title="刷新"
            onClick={() => void load()}
          >
            <RefreshIcon size={20} />
            刷新
          </button>
          <span style={{ fontSize: 12, color: "var(--monet-on-surface-variant)" }}>
            {instances.length} 个服务器{running > 0 ? ` · ${running} 个运行中` : ""}
          </span>
          <span className="spacer" />
          {instances.length > 0 && (
            <button
              className="tool-btn ripple-host"
              title="搜索"
              onClick={() => setSearchMode(true)}
            >
              <SearchIcon size={20} />
              搜索
            </button>
          )}
          <button
            className="btn btn-raised"
            disabled={busy || feature.locked || versions.length === 0}
            onClick={() => setWizardOpen(true)}
            title={versions.length === 0 ? "版本清单拉不到（需要联网）" : undefined}
          >
            <AddIcon size={16} /> 新建服务器
          </button>
        </div>
      )}

      {feature.locked && (
        <div className="card" style={{ padding: "10px 12px", fontSize: 13, lineHeight: 1.6 }}>
          功能正在冷却：按下「停止 MC 功能」后一小时内不能启服、不能新建。倒计时与撤销在
          「设置 → 品牌实例」。
        </div>
      )}

      <div
        className="card"
        style={{ flex: 1, minHeight: 0, display: "flex", flexDirection: "column", padding: 0 }}
      >
        <div className="list-body scroll-hover">
          {filtered.length === 0 ? (
            <div className="empty-state">
              <span>{query ? "没有匹配的服务器" : "还没有 MC 服务器"}</span>
              <span className="empty-sub">
                {query
                  ? "换个关键词，或清掉搜索框。"
                  : "选一个版本建一个：参数 → 版本 → 存储位置 → 下载服务端，走完就能启动。"}
              </span>
            </div>
          ) : (
            filtered.map((instance) => {
              const state = normState(instance.state);
              const up = state === "RUNNING" || state === "STARTING";
              const busyState = state === "STARTING" || state === "STOPPING";
              return (
                <div
                  key={instance.id}
                  className="tlli ripple-host"
                  style={{ cursor: "pointer", padding: "8px 12px" }}
                  onClick={() => nav(`/mc/${encodeURIComponent(instance.id)}`)}
                >
                  <span className="tlli-graphic">
                    <DeployedCodeIcon size={32} />
                  </span>
                  <span className="tlli-text">
                    <span className="tlli-title">
                      {instance.name}
                      <span
                        className={`tag ${
                          state === "RUNNING" ? "running" : state === "FAILED" ? "failed" : "plain"
                        }`}
                      >
                        {mcStateLabel(instance.state)}
                      </span>
                    </span>
                    <span className="tlli-subtitle">
                      {instance.version} · 端口 {instance.port} · RCON {instance.rconPort} ·{" "}
                      {instance.memoryMb} MB
                      {instance.storage === "data" ? " · data 目录" : ""}
                    </span>
                    {/* 失败要说原因：只说「失败」等于没说，原因藏在详情页的事件流里。 */}
                    {state === "FAILED" && instance.error && (
                      <span className="tlli-subtitle" style={{ color: "var(--monet-error)" }}>
                        {instance.error}
                      </span>
                    )}
                  </span>
                  <span className="row-actions" onClick={(e) => e.stopPropagation()}>
                    <button
                      className="icon-btn on-variant ripple-host"
                      title={
                        feature.locked
                          ? "锁定期间不能启服"
                          : up
                            ? "停止（先存盘再关）"
                            : "启动"
                      }
                      disabled={busy || feature.locked || busyState}
                      onClick={() => void toggle(instance)}
                    >
                      {up ? <CancelIcon size={18} /> : <RocketLaunchIcon size={18} />}
                    </button>
                    <button
                      className="icon-btn on-variant ripple-host"
                      title="打开控制台与设置"
                      onClick={() => nav(`/mc/${encodeURIComponent(instance.id)}`)}
                    >
                      <ArrowForwardIcon size={18} />
                    </button>
                  </span>
                </div>
              );
            })
          )}
        </div>
      </div>

      <p
        style={{
          margin: 0,
          padding: "0 4px",
          fontSize: 12,
          color: "var(--monet-on-surface-variant)",
          lineHeight: 1.7,
        }}
      >
        <InfoIcon size={14} style={{ verticalAlign: "-2px", marginRight: 4 }} />
        客户端连的是游戏端口，不是面板；默认只建议局域网或加防火墙。
        <b> 服务端程序不随本项目分发</b>，首次安装时从 Mojang 官方下载。
      </p>

      <McCreateWizard
        open={wizardOpen}
        versions={versions}
        onClose={() => setWizardOpen(false)}
        onCreated={() => void load()}
      />
    </div>
  );
}
