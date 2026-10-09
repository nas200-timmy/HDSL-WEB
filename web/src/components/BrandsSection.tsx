import { useEffect, useMemo, useState } from "react";
import { api } from "../api";
import { ConfirmDialog, Dialog } from "./Dialog";
import { ZcodeSection } from "./ZcodeSection";
import {
  CloseIcon,
  DeleteIcon,
  InfoIcon,
  PublicIcon,
  RefreshIcon,
  RocketLaunchIcon,
} from "./icons";
import { I18N } from "../i18n";
import { toast } from "../store";
import type { BrandId, BrandInstance, BrandInstallStatus, BrandVersions } from "../types";
import { errMsg } from "../utils";

/// 状态徽章：与 ZcodeSection 的 STATE_TEXT/STATE_TAG 同款（文案进 I18N 字典）
const STATE_TEXT: Record<string, string> = {
  created: I18N["dsh.brand.state.created"],
  starting: I18N["dsh.brand.state.starting"],
  running: I18N["dsh.brand.state.running"],
  stopped: I18N["dsh.brand.state.stopped"],
  error: I18N["dsh.brand.state.error"],
};

const STATE_TAG: Record<string, string> = {
  starting: "running",
  running: "running",
  error: "failed",
};

function stateText(state: string): string {
  return STATE_TEXT[state] ?? state;
}

const BRAND_NAME: Record<BrandId, string> = {
  kimi: I18N["dsh.brand.kimi"],
  opencode: I18N["dsh.brand.opencode"],
};

/// 品牌区（实例列表页底部）：ZCode 页签原样渲染现有 ZcodeSection，
/// Kimi Code / OpenCode 页签为同构的品牌面板。
export function BrandsSection() {
  const [tab, setTab] = useState<"zcode" | BrandId>("zcode");
  const tabs: { key: "zcode" | BrandId; label: string }[] = [
    { key: "zcode", label: I18N["dsh.brand.tab.zcode"] },
    { key: "kimi", label: I18N["dsh.brand.kimi"] },
    { key: "opencode", label: I18N["dsh.brand.opencode"] },
  ];
  return (
    <>
      <div className="card toolbar-row" style={{ flexShrink: 0, padding: 4, gap: 2 }}>
        {tabs.map((t) => (
          <button
            key={t.key}
            className="tool-btn ripple-host"
            style={tab === t.key ? { background: "var(--monet-secondary-container)", color: "var(--monet-on-secondary-container)" } : undefined}
            onClick={() => setTab(t.key)}
          >
            {t.label}
          </button>
        ))}
      </div>
      {tab === "zcode" ? <ZcodeSection /> : <BrandPanel key={tab} brand={tab} />}
    </>
  );
}

/// 单个第三方品牌面板：警示条 + 版本安装 + 实例列表（kimi / opencode 同构）
function BrandPanel({ brand }: { brand: BrandId }) {
  const brandName = BRAND_NAME[brand];
  const [versions, setVersions] = useState<BrandVersions | null>(null);
  const [versionsError, setVersionsError] = useState<string | null>(null);
  const [instances, setInstances] = useState<BrandInstance[] | null>(null);
  const [query, setQuery] = useState("");
  const [picked, setPicked] = useState<string | null>(null);
  const [installing, setInstalling] = useState(false);
  const [installStatus, setInstallStatus] = useState<BrandInstallStatus | null>(null);
  const [installLogOpen, setInstallLogOpen] = useState(false);
  const [installLog, setInstallLog] = useState<string[]>([]);
  const [form, setForm] = useState({ name: "", version: "" });
  const [busy, setBusy] = useState(false);
  const [logTarget, setLogTarget] = useState<BrandInstance | null>(null);
  const [logLines, setLogLines] = useState<string[]>([]);
  const [deleteTarget, setDeleteTarget] = useState<BrandInstance | null>(null);

  const reloadVersions = async () => {
    try {
      setVersionsError(null);
      setVersions(await api.brandVersions(brand));
    } catch (e) {
      setVersionsError(errMsg(e));
    }
  };
  const reloadInstances = async () => {
    try {
      setInstances(await api.brandInstances(brand).then((r) => r.instances));
    } catch {
      // 列表暂不可用：保持现状，下次轮询/刷新再来
    }
  };

  useEffect(() => {
    setPicked(null);
    setForm({ name: "", version: "" });
    void reloadVersions();
    void reloadInstances();
  }, [brand]);

  // 启动中的实例每 1.5 秒拉一次状态（同 ZcodeSection 的轮询模式）
  useEffect(() => {
    if (!instances?.some((i) => i.state === "starting")) return;
    const timer = setInterval(() => void reloadInstances(), 1500);
    return () => clearInterval(timer);
  }, [instances]);

  // 安装中：每秒轮询安装状态；终态后刷新版本/实例
  useEffect(() => {
    if (!installing) return;
    let alive = true;
    const timer = setInterval(() => {
      void (async () => {
        try {
          const st = await api.brandInstallStatus(brand);
          if (!alive) return;
          setInstallStatus(st);
          if (st.state === "done") {
            setInstalling(false);
            toast("success", `${brandName} ${I18N["dsh.brand.installed"]}`);
            void reloadVersions();
            void reloadInstances();
          } else if (st.state === "failed" || st.state === "none") {
            setInstalling(false);
            if (st.state === "failed") toast("error", `安装失败：${st.error ?? st.message}`);
            void reloadVersions();
          }
        } catch {
          if (alive) setInstalling(false);
        }
      })();
    }, 1000);
    return () => {
      alive = false;
      clearInterval(timer);
    };
  }, [installing, brand]);

  const q = query.trim().toLowerCase();
  const filteredVersions = useMemo(() => {
    const all = versions?.versions ?? [];
    if (!q) return all;
    return all.filter((v) => v.toLowerCase().includes(q));
  }, [versions, q]);

  /** 安装行版本下拉当前值：默认选 /versions 的 latest */
  const selectedVersion = picked ?? versions?.latest ?? filteredVersions[0] ?? "";

  const installedVersions = versions?.releases ?? [];
  const isInstalled = installedVersions.some((r) => r.version === selectedVersion);

  /** 新建实例下拉的推荐默认值：/versions 的 default（没装则为 latest），未安装时退回空（=最新已装） */
  const recommendedVersion = (() => {
    const rec = versions?.default ?? versions?.latest ?? "";
    return rec && installedVersions.some((r) => r.version === rec) ? rec : "";
  })();

  const doInstall = async () => {
    if (!selectedVersion) return;
    setBusy(true);
    try {
      await api.brandInstall(brand, selectedVersion);
      setInstalling(true);
      setInstallStatus({ state: "running", message: "", fraction: 0 });
    } catch (e) {
      toast("error", `安装失败：${errMsg(e)}`);
    } finally {
      setBusy(false);
    }
  };

  const toggleInstallLog = async () => {
    if (installLogOpen) {
      setInstallLogOpen(false);
      return;
    }
    try {
      const r = await api.brandInstallLog(brand, 300);
      setInstallLog(r.lines);
      setInstallLogOpen(true);
    } catch (e) {
      toast("error", `读取安装日志失败：${errMsg(e)}`);
    }
  };

  const doCreate = async () => {
    const name = form.name.trim();
    if (!name) return;
    setBusy(true);
    try {
      await api.createBrandInstance(brand, { name, version: form.version || undefined });
      toast("success", "实例已创建");
      setForm({ name: "", version: form.version });
      await reloadInstances();
    } catch (e) {
      toast("error", `创建失败：${errMsg(e)}`);
    } finally {
      setBusy(false);
    }
  };

  const doLaunch = async (inst: BrandInstance) => {
    setBusy(true);
    try {
      const r = await api.launchBrand(brand, inst.id);
      if (r.state === "error") toast("error", `启动失败：${r.error ?? "未知原因"}`);
      else toast("success", "已启动");
      await reloadInstances();
    } catch (e) {
      toast("error", `启动失败：${errMsg(e)}`);
    } finally {
      setBusy(false);
    }
  };

  const doStop = async (inst: BrandInstance) => {
    try {
      await api.stopBrand(brand, inst.id);
      toast("info", "已停止");
      await reloadInstances();
    } catch (e) {
      toast("error", `停止失败：${errMsg(e)}`);
    }
  };

  const doOpen = (inst: BrandInstance) => {
    const url = inst.url ?? `/i/${inst.id}/`;
    window.open(url, "_blank", "noopener");
  };

  const doShowLogs = async (inst: BrandInstance) => {
    try {
      const r = await api.brandLogs(brand, inst.id, 300);
      setLogLines(r.lines);
      setLogTarget(inst);
    } catch (e) {
      toast("error", `读取日志失败：${errMsg(e)}`);
    }
  };

  const doPatchVersion = async (inst: BrandInstance, version: string) => {
    if (!version || version === inst.version) return;
    try {
      await api.patchBrandInstance(brand, inst.id, { version });
      toast("success", "已切换版本");
      await reloadInstances();
    } catch (e) {
      toast("error", `切换版本失败：${errMsg(e)}`);
    }
  };

  const doDelete = async () => {
    if (!deleteTarget) return;
    setBusy(true);
    try {
      await api.deleteBrandInstance(brand, deleteTarget.id);
      toast("success", "已删除");
      setDeleteTarget(null);
      await reloadInstances();
    } catch (e) {
      toast("error", `删除失败：${errMsg(e)}`);
    } finally {
      setBusy(false);
    }
  };

  const list = instances ?? [];

  return (
    <>
      <div className="hint warning" style={{ flexShrink: 0, display: "grid", gap: 4 }}>
        <span>{I18N["dsh.brand.thirdparty.warning"].replace("%s", brandName)}</span>
        {/* OpenCode 是实验性接入：面板已经到极限，剩下的是上游的事——见 i18n 里的说明。 */}
        {brand === "opencode" && <span>{I18N["dsh.brand.opencode.limit"]}</span>}
      </div>

      {/* 版本行：可搜索下拉 + 安装 */}
      <div className="card filter-bar" style={{ flexShrink: 0 }}>
        <span className="filter-label">{I18N["dsh.brand.versions"]}</span>
        <input
          className="input"
          style={{ maxWidth: 200 }}
          placeholder={I18N["search"]}
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
        {/* 显式高度：不设时个别引擎对 auto 高度的 select 测量有偏差，会在行内上浮 */}
        <select
          className="input"
          style={{ height: 36 }}
          value={selectedVersion}
          onChange={(e) => setPicked(e.target.value)}
        >
          {filteredVersions.map((v) => (
            <option key={v} value={v}>
              {v}
              {v === versions?.latest ? `（${I18N["dsh.brand.latest"]}）` : ""}
            </option>
          ))}
          {filteredVersions.length === 0 && <option value="">{I18N["search.no_results_found"]}</option>}
        </select>
        <button
          className="btn btn-raised ripple-host"
          disabled={busy || installing || !selectedVersion || isInstalled}
          onClick={() => void doInstall()}
        >
          {installing
            ? I18N["dsh.launch.launching"]
            : isInstalled
              ? I18N["dsh.brand.installed"]
              : I18N["button.install"]}
        </button>
        <span className="spacer" />
        <button className="tool-btn ripple-host" title={I18N["button.refresh"]} onClick={() => void reloadVersions()}>
          <RefreshIcon size={18} />
          {I18N["button.refresh"]}
        </button>
      </div>

      {versionsError && <div className="hint error">{versionsError}</div>}

      {/* 安装进度 / 已装版本列表 */}
      {installing && installStatus && (
        <div className="hint info" style={{ flexShrink: 0 }}>
          <span style={{ flex: 1 }}>
            {I18N["dsh.brand.installing.progress"].replace("%s", installStatus.message || "…")}
            {installStatus.state === "failed" && installStatus.error
              ? ` · ${installStatus.error}`
              : ""}
          </span>
          <button className="btn btn-text ripple-host" style={{ height: 24 }} onClick={() => void toggleInstallLog()}>
            {I18N["dsh.brand.view_log"]}
          </button>
        </div>
      )}
      {installLogOpen && (
        <pre className="card" style={{ flexShrink: 0, maxHeight: 180, overflow: "auto", margin: 0, padding: 10, fontSize: 12, lineHeight: 1.5 }}>
          {installLog.length === 0 ? I18N["dsh.brand.log.empty"] : installLog.join("\n")}
        </pre>
      )}
      {versions && (
        <div className="card toolbar-row" style={{ flexShrink: 0 }}>
          <span style={{ fontSize: 12, color: "var(--monet-on-surface-variant)" }}>
            {I18N["dsh.brand.installed"]}：
            {installedVersions.length === 0
              ? I18N["dsh.brand.none_installed"]
              : installedVersions.map((r) => (
                  <span key={r.version} className={`tag${r.current ? " running" : " plain"}`} style={{ marginLeft: 6 }}>
                    {r.version}
                    {r.current ? `（${I18N["dsh.brand.current"]}）` : ""}
                  </span>
                ))}
          </span>
        </div>
      )}

      {/* 实例列表 */}
      <div className="card" style={{ flexShrink: 0, display: "flex", flexDirection: "column", maxHeight: 330 }}>
        <div className="toolbar-row" style={{ padding: "6px 12px", flexWrap: "wrap" }}>
          <span style={{ fontSize: 13, fontWeight: 600 }}>{brandName} {I18N["dsh.instance.list"]}</span>
          <input
            className="input"
            style={{ flex: "1 1 140px" }}
            placeholder={I18N["dsh.instance.name"]}
            value={form.name}
            onChange={(e) => setForm((f) => ({ ...f, name: e.target.value }))}
          />
          <select
            className="input"
            style={{ flex: "none", maxWidth: 150, height: 36 }}
            value={form.version || recommendedVersion}
            onChange={(e) => setForm((f) => ({ ...f, version: e.target.value }))}
          >
            <option value="">{I18N["dsh.brand.default_version"]}</option>
            {installedVersions.map((r) => (
              <option key={r.version} value={r.version}>
                {r.version}
              </option>
            ))}
          </select>
          <button
            className="btn btn-outline ripple-host"
            disabled={busy || !form.name.trim()}
            onClick={() => void doCreate()}
          >
            {I18N["dsh.brand.create"]}
          </button>
        </div>

        <div className="list-body scroll-hover">
          {list.length === 0 ? (
            <div className="empty-state" style={{ padding: "6px 12px" }}>
              <span>还没有 {brandName} 实例</span>
            </div>
          ) : (
            list.map((inst, idx) => (
              <div key={inst.id}>
                <div className="tlli" style={{ padding: "6px 12px" }}>
                  <span className="tlli-text">
                    <span className="tlli-title">
                      {inst.name}
                      <span className={`tag ${STATE_TAG[inst.state] ?? "plain"}`}>
                        {stateText(inst.state)}
                      </span>
                    </span>
                    <span className="tlli-subtitle">
                      {inst.id} · {inst.version}
                      {inst.state === "running" ? " · 经面板反代打开" : ""}
                      {inst.state === "error" && inst.error ? ` · ${inst.error}` : ""}
                    </span>
                  </span>
                  {/* 切换版本：运行中禁改（后端 409） */}
                  {/* 显式高度：height:28 时内容盒只有 28−8×2=12px，装不下 13px 的行盒，数字会被上下裁掉 */}
                  <select
                    className="input"
                    style={{ flex: "none", maxWidth: 110, height: 36 }}
                    value={inst.version}
                    disabled={inst.state === "running" || inst.state === "starting"}
                    onChange={(e) => void doPatchVersion(inst, e.target.value)}
                  >
                    {installedVersions.map((r) => (
                      <option key={r.version} value={r.version}>
                        {r.version}
                      </option>
                    ))}
                  </select>
                  <span className="row-actions">
                    <button
                      className="icon-btn on-variant ripple-host"
                      title={inst.state === "running" ? I18N["dsh.stop"] : I18N["dsh.launch"]}
                      disabled={busy || inst.state === "starting"}
                      onClick={() => (inst.state === "running" ? void doStop(inst) : void doLaunch(inst))}
                    >
                      {inst.state === "running" ? <CloseIcon size={18} /> : <RocketLaunchIcon size={18} />}
                    </button>
                    <button
                      className="icon-btn on-variant ripple-host"
                      title={I18N["dsh.brand.open"]}
                      disabled={inst.state !== "running"}
                      onClick={() => doOpen(inst)}
                    >
                      <PublicIcon size={18} />
                    </button>
                    <button className="icon-btn on-variant ripple-host" title="日志" onClick={() => void doShowLogs(inst)}>
                      <InfoIcon size={18} />
                    </button>
                    <button
                      className="icon-btn on-variant ripple-host"
                      title={I18N["button.delete"]}
                      disabled={busy || inst.state === "running" || inst.state === "starting"}
                      onClick={() => setDeleteTarget(inst)}
                    >
                      <DeleteIcon size={18} />
                    </button>
                  </span>
                </div>
                {idx < list.length - 1 && <div className="divider" style={{ marginLeft: 12 }} />}
              </div>
            ))
          )}
        </div>
      </div>

      {/* 日志 */}
      <Dialog open={logTarget !== null} title={`日志 · ${logTarget?.name ?? ""}`} wide onClose={() => setLogTarget(null)}>
        <pre style={{ maxHeight: 360, overflow: "auto", fontSize: 12, lineHeight: 1.5, margin: 0 }}>
          {logLines.length === 0 ? I18N["dsh.brand.log.empty"] : logLines.join("\n")}
        </pre>
        <div className="dialog-actions">
          <button className="btn btn-text dim" onClick={() => setLogTarget(null)}>
            {I18N["button.cancel"]}
          </button>
          <button className="btn btn-text" onClick={() => logTarget && void doShowLogs(logTarget)}>
            {I18N["button.refresh"]}
          </button>
        </div>
      </Dialog>

      <ConfirmDialog
        open={deleteTarget !== null}
        title={`删除 ${brandName} 实例`}
        message={I18N["dsh.brand.delete.confirm"].replace("%s", deleteTarget?.name ?? "")}
        confirmLabel={I18N["button.delete"]}
        danger
        busy={busy}
        onConfirm={() => void doDelete()}
        onCancel={() => setDeleteTarget(null)}
      />
    </>
  );
}
