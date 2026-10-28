import { useEffect, useState } from "react";
import { api } from "../api";
import type { BrandPolicyEntry, BrandPolicyStatus } from "../types";
import { toast } from "../store";
import { errMsg } from "../utils";

/// 品牌实例的“最后手段”开关。默认全关；打开单实例后必须点名保留哪个实例，
/// 其余实例灰掉并拒绝启动/打开/修改/删除。固定 /i/octop/ 与源码编译是更深的兜底。
export function BrandPolicyPanel() {
  const [status, setStatus] = useState<BrandPolicyStatus | null>(null);
  const [octop, setOctop] = useState<BrandPolicyEntry | null>(null);
  const [singleInstance, setSingleInstance] = useState(false);
  const [survivor, setSurvivor] = useState<string | null>(null);
  const [lockedRoute, setLockedRoute] = useState(false);
  const [sourceBuild, setSourceBuild] = useState(false);
  const [busy, setBusy] = useState(false);

  const apply = (entry: BrandPolicyEntry) => {
    setOctop(entry);
    setSingleInstance(entry.singleInstance);
    setSurvivor(entry.survivor);
    setLockedRoute(entry.lockedRoute);
    setSourceBuild(entry.sourceBuild);
  };

  const load = async () => {
    try {
      const next = await api.brandPolicy();
      setStatus(next);
      const entry = next.brands.find((b) => b.brand === "octop") ?? null;
      if (entry) apply(entry);
    } catch (e) {
      // 页面本身就在开发者模式里；失败通常是提权过期，保持安静并给出重试。
      toast("error", `品牌实例策略加载失败：${errMsg(e)}`);
    }
  };

  useEffect(() => {
    void load();
  }, []);

  const save = async () => {
    if (singleInstance && !survivor) {
      toast("error", "单实例模式必须先选择保留实例");
      return;
    }
    setBusy(true);
    try {
      await api.setBrandPolicy("octop", {
        singleInstance,
        survivor: singleInstance ? survivor : null,
        lockedRoute: singleInstance && lockedRoute,
        sourceBuild,
      });
      toast("success", "品牌实例策略已保存");
      await load();
    } catch (e) {
      toast("error", errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  const instances = octop?.instances ?? [];

  return (
    <div className="card">
      <div className="comp-row">
        <span className="comp-label">品牌实例管理</span>
        <span className="comp-value">
          <span className="tag plain">最后手段</span>
        </span>
      </div>
      <p style={{ fontSize: 12, color: "var(--monet-on-surface-variant)", padding: "4px 8px 0", lineHeight: 1.6 }}>
        日常行为和其他品牌实例一样：随机 token、<span className="mono">/i/&lt;token&gt;/</span>、多实例并存。
        这里的开关只在出问题时使用，默认全关；数据不会因关闭策略而删除。
      </p>

      {!status && <p style={{ padding: 8, fontSize: 12 }}>加载中…</p>}

      {octop && (
        <div style={{ display: "grid", gap: 10, padding: "10px 8px 4px" }}>
          <label className="input-check">
            <input
              type="checkbox"
              checked={singleInstance}
              onChange={(e) => {
                const on = e.target.checked;
                setSingleInstance(on);
                if (on && !survivor && instances.length > 0) setSurvivor(instances[0].id);
                if (!on) setLockedRoute(false);
              }}
            />
            单实例模式：只保留一个 Octop 实例，其余灰掉并禁用
          </label>

          <label style={{ display: "flex", alignItems: "center", gap: 8, fontSize: 13 }}>
            保留实例
            <select
              className="input"
              style={{ flex: "none", maxWidth: 260 }}
              value={survivor ?? ""}
              disabled={!singleInstance || instances.length === 0}
              onChange={(e) => setSurvivor(e.target.value || null)}
            >
              <option value="">请选择</option>
              {instances.map((i) => (
                <option key={i.id} value={i.id}>
                  {i.name} · {i.id}
                </option>
              ))}
            </select>
          </label>

          <label className="input-check" style={{ opacity: singleInstance ? 1 : 0.55 }}>
            <input
              type="checkbox"
              checked={lockedRoute}
              disabled={!singleInstance || !survivor}
              onChange={(e) => setLockedRoute(e.target.checked)}
            />
            固定反代路径 <span className="mono">/i/octop/</span>（要求单实例）
          </label>

          <label className="input-check">
            <input type="checkbox" checked={sourceBuild} onChange={(e) => setSourceBuild(e.target.checked)} />
            本地源码编译（预留：当前版本会拒绝安装，仍使用预编译包）
          </label>

          <div style={{ display: "flex", gap: 8, alignItems: "center" }}>
            <button className="btn btn-raised ripple-host" disabled={busy} onClick={() => void save()}>
              保存策略
            </button>
            <span style={{ fontSize: 12, color: "var(--monet-on-surface-variant)" }}>
              固定路径只对保留实例生效；关闭后回到 token 路径。
            </span>
          </div>

          <div style={{ borderTop: "1px solid var(--monet-outline-variant)", paddingTop: 8 }}>
            {instances.length === 0 ? (
              <span style={{ fontSize: 12, color: "var(--monet-on-surface-variant)" }}>
                还没有 Octop 实例。
              </span>
            ) : (
              instances.map((i) => (
                <div
                  key={i.id}
                  style={{
                    display: "flex",
                    gap: 8,
                    alignItems: "center",
                    padding: "3px 0",
                    opacity: i.disabled ? 0.5 : 1,
                    fontSize: 12,
                  }}
                >
                  <span className="mono">{i.id}</span>
                  <span>{i.name}</span>
                  <span className="tag plain">{i.state}</span>
                  {i.id === survivor && <span className="tag running">保留</span>}
                  {i.disabled && <span className="tag plain">禁用</span>}
                </div>
              ))
            )}
          </div>
        </div>
      )}
    </div>
  );
}
