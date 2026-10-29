import { useEffect, useMemo, useRef, useState } from "react";
import { launchBrandInstance, stopBrandInstance } from "../actions";
import { api } from "../api";
import { withBase } from "../base";
import { AdminCredentialsDialog } from "../components/AdminCredentialsDialog";
import { CollapsibleNote } from "../components/CollapsibleNote";
import { ConfirmDialog, Dialog } from "../components/Dialog";
import { BrandIcon } from "../components/BrandIcon";
import { PopupItem, PopupMenu, PopupSep } from "../components/PopupMenu";
import { ProgressBar } from "../components/ProgressBar";
import { ZcodeBuildButton } from "../components/ZcodeBuildDialog";
import { ZcodeSection } from "../components/ZcodeSection";
import { TerminalWindow, type TerminalTargetInfo } from "../components/TerminalWindow";
import {
  ArrowDropUpIcon,
  CloseIcon,
  ContentCopyIcon,
  DeleteIcon,
  EditIcon,
  ExtensionIcon,
  FormatListBulletedIcon,
  InfoIcon,
  KeyboardArrowDownIcon,
  MoreVertIcon,
  PublicIcon,
  RefreshIcon,
  RocketLaunchIcon,
  TerminalIcon,
} from "../components/icons";
import { useMobileLayout } from "../hooks";
import { I18N } from "../i18n";
import { getSelectedInstanceId, setSelectedInstanceId, useSelectedInstanceId } from "../selection";
import { getState, refreshExternal, refreshZcode, toast, useAppState } from "../store";
import type {
  BrandId,
  BrandInstallStatus,
  BrandInstance,
  BrandVersions,
  ExternalInstance,
  InstancePlugins,
} from "../types";
import { errMsg, formatDateTime } from "../utils";

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

/// 版本选项的文案：`2.1.1 最新 未装` / `2.1.0 已装`。
/// 用空格而不是全角括号——行内那个选择器在手机上只有 120 来像素宽，
/// 带括号会被裁成「2.1.0（已装」，反而不如短一档的写法好认。
function versionOption(version: string, latest: boolean, installed: boolean): string {
  return [
    version,
    latest ? I18N["dsh.brand.latest"] : "",
    installed ? I18N["dsh.brand.version.installed"] : I18N["dsh.brand.version.uninstalled"],
  ]
    .filter(Boolean)
    .join(" ");
}

/// 四个品牌键：zcode 是实验品类（自己的 REST 族），
/// 其余三个共用 /api/brands/<brand>/*。顺序 = 页签顺序，也是深链缺省时的挑选顺序。
export type BrandKey = "zcode" | BrandId;

export const BRAND_KEYS: BrandKey[] = ["zcode", "kimi", "opencode", "claude-web", "octop"];

const BRAND_TITLE: Record<BrandKey, string> = {
  zcode: I18N["dsh.brand.zcode"],
  kimi: I18N["dsh.brand.kimi"],
  opencode: I18N["dsh.brand.opencode"],
  "claude-web": I18N["dsh.brand.claude-web"],
  octop: I18N["dsh.brand.octop"],
};

/// 短名（不带「（实验）」后缀）：写进用户的实例名与「还没有 X 实例」这类句子里，
/// 实验标记留在页签标题与警示条上说一次就够了。
const BRAND_SHORT: Record<BrandKey, string> = {
  zcode: I18N["dsh.brand.zcode.short"],
  kimi: I18N["dsh.brand.kimi"],
  opencode: I18N["dsh.brand.opencode.short"],
  "claude-web": I18N["dsh.brand.claude-web.short"],
  octop: I18N["dsh.brand.octop.short"],
};

export function isBrandKey(value: string | null | undefined): value is BrandKey {
  return BRAND_KEYS.includes(value as BrandKey);
}

/// 缺省/非法深链的落地品牌：第一个有实例的（顺序同页签），都没有才是 zcode
export function fallbackBrand(external: ExternalInstance[]): BrandKey {
  return BRAND_KEYS.find((k) => external.some((e) => e.brand === k)) ?? "zcode";
}

/// 等一拍：停止态与安装进度都是「隔一秒问一次」，用 await 写比嵌套 setTimeout 直白
function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => window.setTimeout(resolve, ms));
}

/**
 * 品牌实例视图：实例列表页内**原地切换**出来的第二层内容。
 *
 * 这几个品牌最早是实例列表页底部挤成一堆的页签区（dsh 列表只剩 529px、品牌实例列表体
 * 只剩 48px），中间短暂独立成 /brands 一页；现在回到实例列表页里原地换视图——
 * URL 仍是 /instances（只换查询串），页面不跳转、不重挂载：
 * 左侧子侧栏「品牌实例」选中，右侧内容区从 dsh 实例列表换成这里。
 *
 * 从上到下：品牌页签行（带实例数）→ 第三方警示条 → 版本卡（品牌共享的构建，
 * 默认折叠成一行摘要）→ 实例卡（吃掉剩余高度、卡内滚动）。
 * 实例状态一律只读 store.external（唯一状态源），本视图只负责触发 refreshExternal()。
 */
export function BrandsView({
  brand,
  onSelect,
}: {
  brand: BrandKey;
  /** 换品牌：由调用方写进查询串（视图自己不碰路由） */
  onSelect: (brand: BrandKey) => void;
}) {
  const s = useAppState();
  // 窄屏页签用短名（去掉「（实验）」）：五个品牌全带后缀时一行放不下，
  // 而实验标记在下面的警示条里还会说一次，不必每个页签都占那六个字。
  const mobile = useMobileLayout();

  useEffect(() => {
    void refreshExternal();
  }, []);

  const warning = I18N["dsh.brand.thirdparty.warning"].replace("%s", BRAND_TITLE[brand]);

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: mobile ? 6 : 8, flex: 1, minHeight: 0 }}>
      {/* 品牌页签行：带实例数（0 个就不显示标签），换页签只换下面这块内容 */}
      <div className="card toolbar-row brand-tabs" style={{ flexShrink: 0 }}>
        {BRAND_KEYS.map((k) => {
          const count = s.external.filter((e) => e.brand === k).length;
          const active = k === brand;
          return (
            <button
              key={k}
              className="tool-btn ripple-host"
              style={
                active
                  ? { background: "var(--monet-secondary-container)", color: "var(--monet-on-secondary-container)" }
                  : undefined
              }
              onClick={() => onSelect(k)}
            >
              <BrandIcon brand={k} size={mobile ? 14 : 16} />
              {mobile ? BRAND_SHORT[k] : BRAND_TITLE[k]}
              {count > 0 && (
                <span className="tag plain" style={{ marginLeft: mobile ? 2 : 4 }}>
                  {count}
                </span>
              )}
            </button>
          );
        })}
      </div>

      {/* key=品牌：换品牌即重挂载（表单、折叠态、进场动画都跟着重置） */}
      <div
        key={brand}
        style={{
          display: "flex",
          flexDirection: "column",
          gap: 8,
          flex: 1,
          minHeight: 0,
          animation: "slide-up-fade-in 400ms var(--ease)",
        }}
      >
        {/* 移动端长警示收成一行 + 详情展开（CollapsibleNote 内部分支），桌面端原样 */}
        <div className="hint warning" style={{ flexShrink: 0, display: "grid", gap: 4 }}>
          <CollapsibleNote summary={<span>{warning}</span>}>
            <span>{warning}</span>
            {/* OpenCode 是实验性接入：面板已经到极限，剩下的是上游的事——见 i18n 里的说明。 */}
            {brand === "opencode" && <span>{I18N["dsh.brand.opencode.limit"]}</span>}
            {/* Claude Web 同为实验性接入：社区引擎/中转/凭据都归上游与服务商。 */}
            {brand === "claude-web" && <span>{I18N["dsh.brand.claude-web.limit"]}</span>}
          </CollapsibleNote>
        </div>

        {brand === "zcode" ? (
          <>
            <ZcodeVersionsCard />
            <ZcodeSection />
          </>
        ) : (
          <BrandPane brand={brand} />
        )}
      </div>
    </div>
  );
}

/* ---------------------------------------------------------------------------
 * 品牌（kimi / opencode / claude-web）：版本卡 + 实例卡
 * ------------------------------------------------------------------------- */

/// 单品牌的内容：版本卡与实例卡共用**同一份**版本目录（api.brandVersions 只拉一次）。
function BrandPane({ brand }: { brand: BrandId }) {
  const [versions, setVersions] = useState<BrandVersions | null>(null);
  const [versionsError, setVersionsError] = useState<string | null>(null);
  // 默认折叠：实例是主对象，版本是偶尔才动的共享构建
  const [versionsOpen, setVersionsOpen] = useState(false);

  const reload = async () => {
    try {
      setVersionsError(null);
      setVersions(await api.brandVersions(brand));
    } catch (e) {
      setVersionsError(errMsg(e));
    }
  };

  useEffect(() => {
    void reload();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [brand]);

  return (
    <>
      <VersionCard
        brand={brand}
        versions={versions}
        versionsError={versionsError}
        open={versionsOpen}
        onToggle={() => setVersionsOpen((v) => !v)}
        reload={reload}
      />
      <InstancesCard
        brand={brand}
        versions={versions}
        reload={reload}
        onOpenVersions={() => setVersionsOpen(true)}
      />
    </>
  );
}

/// 版本卡（品牌共享）：折叠态一行摘要「已安装的版本构建 + 安装新版本 + 展开」，
/// 展开后才是整个版本目录的搜索/安装/日志（这套逻辑是原样搬过来的，只重排了位置）。
function VersionCard({
  brand,
  versions,
  versionsError,
  open,
  onToggle,
  reload,
}: {
  brand: BrandId;
  versions: BrandVersions | null;
  versionsError: string | null;
  open: boolean;
  onToggle: () => void;
  reload: () => Promise<void>;
}) {
  const [query, setQuery] = useState("");
  const [picked, setPicked] = useState<string | null>(null);
  const [installing, setInstalling] = useState(false);
  const [installStatus, setInstallStatus] = useState<BrandInstallStatus | null>(null);
  const [installLogOpen, setInstallLogOpen] = useState(false);
  const [installLog, setInstallLog] = useState<string[]>([]);
  const [installTaskId, setInstallTaskId] = useState<string | null>(null);
  const [installStartedAt, setInstallStartedAt] = useState<number | null>(null);
  const [stopping, setStopping] = useState(false);
  const [, forceTick] = useState(0);
  const [busy, setBusy] = useState(false);
  const installLogRef = useRef<HTMLPreElement | null>(null);

  // 预计剩余：用已完成比例与已用时间反推。比例太小或刚启动时先不给数字，免得跳。
  useEffect(() => {
    if (!installing || installStartedAt === null) return;
    const timer = setInterval(() => forceTick((n) => n + 1), 1000);
    return () => clearInterval(timer);
  }, [installing, installStartedAt]);

  // 日志永远滚到最新一行：编译慢的时候人盯着的就是这一行。
  useEffect(() => {
    const node = installLogRef.current;
    if (node) node.scrollTop = node.scrollHeight;
  }, [installLog, installLogOpen]);

  // 日志面板开着时每 3 秒拉一次尾巴：`>>> 已安装 (199 of 345) …` 这类 portage 风格的行是
  // 写进 install.log 的，只靠状态消息会漏掉它们。
  useEffect(() => {
    if (!installLogOpen || !installing) return;
    let alive = true;
    const timer = setInterval(() => {
      void (async () => {
        try {
          const r = await api.brandInstallLog(brand, 400);
          if (alive) setInstallLog(r.lines);
        } catch {
          // 读不到就保持上一份，不必打扰用户
        }
      })();
    }, 3000);
    return () => {
      alive = false;
      clearInterval(timer);
    };
  }, [installLogOpen, installing, brand]);

  // 下载一旦彻底结束（阶段键离开 download）就明确说一声：后面那段是编译/写盘，
  // 在这台慢盘上可以很久，用户不该还以为在下载。
  const previousStageKey = useRef<string | null>(null);
  useEffect(() => {
    const key = installStatus?.stageKey ?? null;
    const previous = previousStageKey.current;
    previousStageKey.current = key;
    if (previous === "download" && key !== null && key !== "download" && key !== "done") {
      toast("success", "下载阶段已结束：接下来是编译/写盘，这台机器上可能很久——可以离开去喝杯咖啡。");
    }
  }, [installStatus?.stageKey]);

  // 安装中：每秒轮询安装状态；终态后刷新版本目录与实例（新版本可能马上被实例切过去）
  // 挂载时先认领一次：刷新页面或从别的入口进来时，正在跑的安装也要显示进度与「停止」，
  // 而不是等下一次点安装才冒出一句「已有安装在跑」。
  useEffect(() => {
    let alive = true;
    void (async () => {
      try {
        const st = await api.brandInstallStatus(brand);
        if (!alive || st.state !== "running") return;
        setInstalling(true);
        setInstallTaskId(st.taskId ?? null);
        setInstallStatus(st);
      } catch {
        // 读不到状态就什么也不改：页面本来就还有别的重试入口。
      }
    })();
    return () => {
      alive = false;
    };
  }, [brand]);

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
            setInstallTaskId(null);
            setInstallStartedAt(null);
            toast("success", `${BRAND_TITLE[brand]} ${I18N["dsh.brand.installed"]}`);
            void reload();
            void refreshExternal();
          } else if (st.state === "failed" || st.state === "none") {
            setInstalling(false);
            setInstallTaskId(null);
            setInstallStartedAt(null);
            if (st.state === "failed") toast("error", `安装失败：${st.error ?? st.message}`);
            void reload();
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
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [installing, brand]);

  const q = query.trim().toLowerCase();
  const catalog = useMemo(() => {
    const all = versions?.versions ?? [];
    if (!q) return all;
    return all.filter((v) => v.toLowerCase().includes(q));
  }, [versions, q]);

  const installed = versions?.releases ?? [];
  /// 下拉当前值：默认选 /versions 的 latest
  const selected = picked ?? versions?.latest ?? catalog[0] ?? "";
  const selectedInstalled = installed.some((r) => r.version === selected);

  const installFraction = installStatus && installStatus.fraction >= 0 ? installStatus.fraction : null;
  const installPercent = installFraction === null ? null : Math.round(installFraction * 100);
  const installElapsed = installStartedAt === null ? 0 : Math.round((Date.now() - installStartedAt) / 1000);
  // 预计剩余不再由前端瞎算：后端在知道总量时（下载字节、npm 包数）把「剩约 …」写进消息，
  // 不知道时（uv 的编译/安装）就只说速度和已完成数量——比一个反推出来的数字诚实。
  const formatElapsed = (totalSeconds: number) => {
    const minutes = Math.floor(totalSeconds / 60);
    const seconds = totalSeconds % 60;
    return minutes > 0 ? `${minutes} 分 ${String(seconds).padStart(2, "0")} 秒` : `${seconds} 秒`;
  };

  const doInstall = async () => {
    if (!selected) return;
    setBusy(true);
    try {
      const started = Date.now();
      const accepted = await api.brandInstall(brand, selected);
      setInstalling(true);
      setInstallTaskId(accepted.taskId);
      setInstallStartedAt(started);
      setInstallStatus({ state: "running", message: "已提交，正在准备…", fraction: 0 });
    } catch (e) {
      // 409（同品牌已有安装在进行）也走这里：把后端给的原因原样带出来
      toast("error", `安装失败：${errMsg(e)}`);
    } finally {
      setBusy(false);
    }
  };

  /// 停止：取消任务会杀掉它起的进程树，并让下载/解包循环看到中断标志。
  const stopInstall = async () => {
    if (!installTaskId || stopping) return;
    setStopping(true);
    try {
      await api.cancelTask(installTaskId);
      toast("success", "已请求停止：进程与下载会随之中断，半成品会留在安装目录（启动时会自动对账）");
    } catch (e) {
      toast("error", `停止失败：${errMsg(e)}`);
    } finally {
      setStopping(false);
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

  return (
    <div className="card" style={{ flexShrink: 0, padding: 8 }}>
      {/* 摘要行：不换行——折叠态的整卡高度必须压在 90px 内，
          否则 390×844 的首屏就被版本卡吃掉，实例行与行内启动按钮要滚动才看得见 */}
      <div className="toolbar-row" style={{ padding: 0, gap: 8, flexWrap: "nowrap" }}>
        <span style={{ fontSize: 13, fontWeight: 600, flex: "none" }}>
          {I18N["dsh.brand.section.versions"]}
        </span>
        <span
          style={{
            flex: 1,
            minWidth: 0,
            display: "flex",
            alignItems: "center",
            gap: 6,
            overflow: "hidden",
          }}
        >
          <span
            style={{
              fontSize: 12,
              color: "var(--monet-on-surface-variant)",
              whiteSpace: "nowrap",
              flex: "none",
            }}
          >
            {I18N["dsh.brand.installed_builds"]}：
          </span>
          {installed.length === 0 ? (
            <span style={{ fontSize: 12, whiteSpace: "nowrap", flex: "none" }}>
              {versions === null ? "加载中…" : I18N["dsh.brand.none_installed"]}
            </span>
          ) : (
            installed
              .slice(0, 3)
              .map((r) => (
                <span key={r.version} className={`tag${r.current ? " running" : " plain"}`}>
                  {r.version}
                </span>
              ))
          )}
          {/* 装得多了就折成 +N，别让摘要行撑高（展开后能看到全部） */}
          {installed.length > 3 && (
            <span
              style={{
                fontSize: 12,
                color: "var(--monet-on-surface-variant)",
                whiteSpace: "nowrap",
                flex: "none",
              }}
            >
              +{installed.length - 3}
            </span>
          )}
        </span>
        {/* 摘要行的按钮不许被挤：它们是折叠态唯一的操作入口 */}
        <button className="btn btn-raised ripple-host" style={{ flex: "none" }} onClick={onToggle}>
          {I18N["dsh.brand.install_new"]}
          {open ? <ArrowDropUpIcon size={18} /> : <KeyboardArrowDownIcon size={18} />}
        </button>
      </div>

      {!open && (
        <div style={{ fontSize: 11, color: "var(--monet-on-surface-variant)", paddingTop: 2 }}>
          {I18N["dsh.brand.version.shared"]}
        </div>
      )}

      {open && (
        // 展开态自带限高与滚动：内容再长也不会把下面的实例卡挤成零高
        <div
          style={{
            maxHeight: "45vh",
            overflowY: "auto",
            marginTop: 6,
            display: "flex",
            flexDirection: "column",
            gap: 6,
          }}
        >
          <div className="filter-bar" style={{ padding: 0 }}>
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
              value={selected}
              onChange={(e) => setPicked(e.target.value)}
            >
              {catalog.map((v) => (
                <option key={v} value={v}>
                  {versionOption(
                    v,
                    v === versions?.latest,
                    installed.some((r) => r.version === v),
                  )}
                </option>
              ))}
              {catalog.length === 0 && <option value="">{I18N["search.no_results_found"]}</option>}
            </select>
            <button
              className="btn btn-raised ripple-host"
              disabled={busy || installing || !selected || selectedInstalled}
              onClick={() => void doInstall()}
            >
              {installing
                ? I18N["dsh.launch.launching"]
                : selectedInstalled
                  ? I18N["dsh.brand.installed"]
                  : I18N["button.install"]}
            </button>
            <span className="spacer" />
            <button
              className="tool-btn ripple-host"
              title={I18N["button.refresh"]}
              onClick={() => void reload()}
            >
              <RefreshIcon size={18} />
              {I18N["button.refresh"]}
            </button>
          </div>

          {versionsError && <div className="hint error">{versionsError}</div>}

          {installing && installStatus && (
            <div className="card" style={{ padding: "10px 12px", display: "grid", gap: 8 }}>
              <div style={{ display: "flex", alignItems: "baseline", gap: 8 }}>
                {installStatus.stage && (
                  <span
                    className={`tag ${installStatus.stageKey === "download" ? "running" : "plain"}`}
                    style={{ flexShrink: 0 }}
                  >
                    {installStatus.stage}
                  </span>
                )}
                <span style={{ flex: 1, fontSize: 13.5, fontWeight: 500, wordBreak: "break-all" }}>
                  {installStatus.message || "已提交，正在准备…"}
                </span>
                {installPercent !== null && (
                  <span className="mono" style={{ fontSize: 13 }}>
                    {installPercent}%
                  </span>
                )}
              </div>
              <ProgressBar fraction={installFraction} />
              {installStatus.stageKey &&
                installStatus.stageKey !== "download" &&
                installStatus.stageKey !== "done" && (
                  <div
                    style={{
                      fontSize: 12,
                      lineHeight: 1.6,
                      padding: "6px 8px",
                      borderRadius: 8,
                      background: "var(--monet-secondary-container)",
                      color: "var(--monet-on-secondary-container)",
                    }}
                  >
                    ✅ 下载阶段已结束 · 正在{installStatus.stage ?? "处理"}（
                    {installStatus.stageKey === "build"
                      ? "编译"
                      : installStatus.stageKey === "install"
                        ? "安装依赖"
                        : "写盘"}
                    ）· 这一步在慢盘或慢网上可能很久，去喝杯咖啡再回来看也行
                  </div>
                )}
              {installStatus.stageFraction !== undefined && installStatus.stageFraction >= 0 && (
                <div style={{ display: "grid", gap: 3 }}>
                  <div
                    style={{
                      display: "flex",
                      alignItems: "baseline",
                      gap: 8,
                      fontSize: 11.5,
                      color: "var(--monet-on-surface-variant)",
                    }}
                  >
                    <span style={{ flex: 1 }}>{installStatus.stage ?? "当前步骤"}</span>
                    <span className="mono">
                      {Math.round(installStatus.stageFraction * 100)}%
                    </span>
                  </div>
                  <div
                    className="progress"
                    style={{ height: 4 }}
                    role="progressbar"
                    aria-label={installStatus.stage ?? "当前步骤"}
                  >
                    <div
                      className="bar"
                      style={{
                        width: `${Math.round(Math.min(1, Math.max(0, installStatus.stageFraction)) * 1000) / 10}%`,
                      }}
                    />
                  </div>
                </div>
              )}
              <div style={{ display: "flex", alignItems: "center", gap: 10, flexWrap: "wrap", fontSize: 12 }}>
                {installStartedAt !== null && (
                  <span style={{ color: "var(--monet-on-surface-variant)" }}>
                    已用 {formatElapsed(installElapsed)}
                  </span>
                )}
                <span className="spacer" style={{ flex: 1 }} />
                <button
                  className="btn btn-text ripple-host"
                  style={{ height: 24 }}
                  onClick={() => void toggleInstallLog()}
                >
                  {I18N["dsh.brand.view_log"]}
                </button>
                <button
                  className="btn btn-text danger ripple-host"
                  style={{ height: 24 }}
                  disabled={!installTaskId || stopping}
                  title="杀掉这次安装起的进程并中断下载；已写入的半成品由启动时的对账清理"
                  onClick={() => void stopInstall()}
                >
                  {stopping ? "正在停止…" : "停止"}
                </button>
              </div>
            </div>
          )}
          {installLogOpen && (
            <pre
              ref={installLogRef}
              className="card"
              style={{
                maxHeight: 260,
                overflow: "auto",
                margin: 0,
                padding: "10px 12px",
                fontSize: 11.5,
                lineHeight: 1.55,
                fontFamily: "var(--mono-font, ui-monospace, SFMono-Regular, Menlo, monospace)",
                background: "var(--monet-surface-container)",
                whiteSpace: "pre-wrap",
                wordBreak: "break-all",
              }}
            >
              {installLog.length === 0
                ? I18N["dsh.brand.log.empty"]
                : installLog.map((line, index) => (
                    <div
                      key={index}
                      style={
                        line.startsWith("[")
                          ? { color: "var(--monet-primary)", fontWeight: 600 }
                          : undefined
                      }
                    >
                      {line}
                    </div>
                  ))}
            </pre>
          )}

          {/* 全部已装构建（摘要行只标前三个） */}
          <div className="toolbar-row" style={{ padding: 0 }}>
            <span style={{ fontSize: 12, color: "var(--monet-on-surface-variant)" }}>
              {I18N["dsh.brand.installed"]}：
              {installed.length === 0
                ? I18N["dsh.brand.none_installed"]
                : installed.map((r) => (
                    <span
                      key={r.version}
                      className={`tag${r.current ? " running" : " plain"}`}
                      style={{ marginLeft: 6 }}
                    >
                      {r.version}
                      {r.current ? `（${I18N["dsh.brand.current"]}）` : ""}
                    </span>
                  ))}
            </span>
          </div>
        </div>
      )}
    </div>
  );
}

/// zcode 版卡：这一卡装的是 ZCode 发行包（面板里构建出来的那些），
/// 实例卡那部分归 ZcodeSection。折叠态与品牌版卡同构，展开后给发行包详情与构建入口。
function ZcodeVersionsCard() {
  const s = useAppState();
  const [open, setOpen] = useState(false);
  const dist = s.zcodeDist;

  return (
    <div className="card" style={{ flexShrink: 0, padding: 8 }}>
      <div className="toolbar-row" style={{ padding: 0, gap: 8, flexWrap: "nowrap" }}>
        <span style={{ fontSize: 13, fontWeight: 600, flex: "none" }}>
          {I18N["dsh.brand.section.versions"]}
        </span>
        <span
          style={{
            flex: 1,
            minWidth: 0,
            display: "flex",
            alignItems: "center",
            gap: 6,
            overflow: "hidden",
          }}
        >
          <span
            style={{
              fontSize: 12,
              color: "var(--monet-on-surface-variant)",
              whiteSpace: "nowrap",
              flex: "none",
            }}
          >
            {I18N["dsh.brand.installed_builds"]}：
          </span>
          <span style={{ fontSize: 12, whiteSpace: "nowrap", flex: "none" }}>
            {dist === null
              ? "检测中…"
              : dist.present
                ? (dist.version ?? "未知版本")
                : I18N["dsh.brand.none_installed"]}
          </span>
        </span>
        {/* 摘要行的按钮不许被挤：它们是折叠态唯一的操作入口（刷新沿用原面板的动作） */}
        <span style={{ flex: "none", display: "inline-flex", alignItems: "center" }}>
          <ZcodeBuildButton />
        </span>
        <button
          className="icon-btn on-variant ripple-host"
          style={{ flex: "none" }}
          title={I18N["button.refresh"]}
          disabled={s.zcodeLoading}
          onClick={() => void refreshZcode(true)}
        >
          <RefreshIcon size={18} />
        </button>
        <button
          className="icon-btn on-variant ripple-host"
          style={{ flex: "none" }}
          title={open ? "收起" : "展开"}
          onClick={() => setOpen((v) => !v)}
        >
          {open ? <ArrowDropUpIcon size={18} /> : <KeyboardArrowDownIcon size={18} />}
        </button>
      </div>

      {!open && (
        <div style={{ fontSize: 11, color: "var(--monet-on-surface-variant)", paddingTop: 2 }}>
          {I18N["dsh.brand.version.shared"]}
        </div>
      )}

      {open && (
        <div style={{ marginTop: 6, display: "flex", flexDirection: "column", gap: 6 }}>
          <div className="toolbar-row" style={{ padding: 0 }}>
            <span style={{ fontSize: 12, color: "var(--monet-on-surface-variant)" }}>
              {dist === null
                ? "正在检测 ZCode 发行包…"
                : dist.present
                  ? `发行包：${dist.version ?? "未知版本"} · ${dist.path}`
                  : "未检测到 ZCode 发行包"}
            </span>
          </div>
          {dist !== null && !dist.present && (
            <div className="empty-state" style={{ padding: "6px 0" }}>
              <span>
                把自建的 ZCode 发行包放到 <code>{dist.path}</code>（需含 bin/zcode.mjs）
              </span>
              <span className="empty-sub">
                或用 HDSL_ZCODE_PACKAGE 指向别处；构建方法见 docs/zcode-experimental.md
              </span>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

/* ---------------------------------------------------------------------------
 * 实例卡（kimi / opencode / claude-web）
 * ------------------------------------------------------------------------- */

/// 实例卡：主对象。卡片头是「标题 + 新建行」，卡内 .list-body 真的滚动（flex:1 吃剩余高度）。
function InstancesCard({
  brand,
  versions,
  reload,
  onOpenVersions,
}: {
  brand: BrandId;
  versions: BrandVersions | null;
  reload: () => Promise<void>;
  onOpenVersions: () => void;
}) {
  const s = useAppState();
  const selectedId = useSelectedInstanceId();
  const mobile = useMobileLayout();
  const [form, setForm] = useState({ name: "", version: "" });
  const [busy, setBusy] = useState(false);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [menuAnchor, setMenuAnchor] = useState<{ el: HTMLElement; inst: ExternalInstance } | null>(null);
  const [infoTarget, setInfoTarget] = useState<ExternalInstance | null>(null);
  const [renameTarget, setRenameTarget] = useState<ExternalInstance | null>(null);
  const [renameValue, setRenameValue] = useState("");
  /// 仅 claude-web：实例的 ANTHROPIC_BASE_URL（空 = 用工具默认端点）
  const [baseUrlTarget, setBaseUrlTarget] = useState<ExternalInstance | null>(null);
  const [baseUrlValue, setBaseUrlValue] = useState("");
  const [pluginsTarget, setPluginsTarget] = useState<ExternalInstance | null>(null);
  const [adminTarget, setAdminTarget] = useState<ExternalInstance | null>(null);
  const [logTarget, setLogTarget] = useState<ExternalInstance | null>(null);
  const [logLines, setLogLines] = useState<string[]>([]);
  const [deleteTarget, setDeleteTarget] = useState<ExternalInstance | null>(null);
  /// 待确认的版本切换（在跑 / 未装都需要先问一句）
  const [pending, setPending] = useState<{ inst: ExternalInstance; version: string } | null>(null);
  /// 行内安装进度：哪一行正在为哪个版本安装、装到哪了
  const [rowInstall, setRowInstall] = useState<{
    id: string;
    version: string;
    status: BrandInstallStatus | null;
  } | null>(null);
  const [terminalTarget, setTerminalTarget] = useState<TerminalTargetInfo | null>(null);
  /// Web Shell 总开关（开发者模式里控制）；关着就不显示终端按钮
  const [webShellEnabled, setWebShellEnabled] = useState(true);

  /// glm-5.2: 标记组件是否仍挂载；stopAndWait / installThenPatch 这类无界 async 循环
  /// 卸载后仍会继续发请求、更新状态（甚至静默 patchVersion），在循环里用它提前 return。
  const mountedRef = useRef(true);
  useEffect(() => {
    // Set here, not only in useRef: StrictMode mounts, clears and mounts again, and the
    // initial value would stay `false` after that simulated unmount.
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  useEffect(() => {
    let alive = true;
    api
      .developerStatus()
      .then((status) => {
        if (alive) setWebShellEnabled(status.webShellEnabled && status.https);
      })
      .catch(() => {
        // 读不到就维持显示：后端仍会拒绝打开，按钮不是那道门
      });
    return () => {
      alive = false;
    };
  }, []);

  const shortName = BRAND_SHORT[brand];
  const instances = s.external.filter((e) => e.brand === brand);
  const installed = versions?.releases ?? [];
  const catalog = versions?.versions ?? [];
  const empty = instances.length === 0;
  /// 新建实例的版本：/versions 的 default（= 最新已装）优先，否则退回列表第一个
  const defaultVersion = (() => {
    const rec = versions?.default ?? versions?.latest ?? "";
    return rec && installed.some((r) => r.version === rec) ? rec : (installed[0]?.version ?? "");
  })();
  const createVersion = form.version || defaultVersion;

  // 名称预填品牌名：该品牌一个实例都没有时，点一下「创建」就能有一个能跑的实例
  useEffect(() => {
    setForm((f) => ({ ...f, name: empty ? shortName : "" }));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [brand, empty]);

  // 启动中的实例每 1.5 秒拉一次状态（同 ZcodeSection 的轮询模式）
  useEffect(() => {
    if (!instances.some((i) => i.state === "starting")) return;
    const timer = setInterval(() => void refreshExternal(), 1500);
    return () => clearInterval(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [s.external]);

  const doCreate = async () => {
    const name = form.name.trim();
    if (!name || !createVersion) return;
    setBusy(true);
    try {
      await api.createBrandInstance(brand, { name, version: createVersion });
      toast("success", "实例已创建");
      setForm({ name: "", version: "" });
      await refreshExternal();
    } catch (e) {
      const raw = errMsg(e);
      if (raw.includes("is being installed")) {
        toast("error", "这个版本正在装，装完再创建实例");
      } else if (raw.includes("is not installed")) {
        // The list this form was built from did not have the version any more (a rebuild
        // replaced the directory, or another page refreshed the releases). Re-read instead of
        // leaving a select pointing at something the server cannot see.
        setForm((f) => ({ ...f, version: "" }));
        await reload();
        toast("error", "这个版本已经不在已装列表里了（已重新读取），请再选一次");
      } else {
        toast("error", `创建失败：${raw}`);
      }
    } finally {
      setBusy(false);
    }
  };

  const doLaunch = async (inst: ExternalInstance) => {
    setBusyId(inst.id);
    try {
      // 走 actions：点击瞬间就开好 about:blank，实例就绪后自动导航过去
      await launchBrandInstance(brand, { id: inst.id, name: inst.name });
    } finally {
      setBusyId(null);
    }
  };

  const doStop = async (inst: ExternalInstance) => {
    setBusyId(inst.id);
    try {
      await stopBrandInstance(brand, inst.id);
    } finally {
      setBusyId(null);
    }
  };

  const doOpen = (inst: ExternalInstance) => {
    // inst.url 已由后端带挂载点前缀（open/list 端点统一在 REST 边界加 base），
    // 直接打开；只有兜底构造的本地路径才需要 withBase。
    window.open(inst.url ?? withBase(`/i/${inst.id}/`), "_blank", "noopener");
  };

  const doShowLogs = async (inst: ExternalInstance) => {
    try {
      const r = await api.brandLogs(brand, inst.id, 300);
      setLogLines(r.lines);
      setLogTarget(inst);
    } catch (e) {
      toast("error", `读取日志失败：${errMsg(e)}`);
    }
  };

  const doRename = async () => {
    if (!renameTarget) return;
    const name = renameValue.trim();
    if (!name || name === renameTarget.name) {
      setRenameTarget(null);
      return;
    }
    setBusy(true);
    try {
      await api.patchBrandInstance(brand, renameTarget.id, { name });
      toast("success", "已重命名");
      setRenameTarget(null);
      await refreshExternal();
    } catch (e) {
      toast("error", `重命名失败：${errMsg(e)}`);
    } finally {
      setBusy(false);
    }
  };

  const doSaveBaseUrl = async () => {
    if (!baseUrlTarget) return;
    const value = baseUrlValue.trim();
    if (value === (baseUrlTarget.baseUrl ?? "")) {
      setBaseUrlTarget(null);
      return;
    }
    if (value !== "" && !/^https?:\/\//i.test(value)) {
      toast("error", "地址要以 http:// 或 https:// 开头");
      return;
    }
    setBusy(true);
    try {
      await api.patchBrandInstance(brand, baseUrlTarget.id, { baseUrl: value });
      toast("success", value ? "已设置 ANTHROPIC_BASE_URL" : "已恢复默认端点");
      setBaseUrlTarget(null);
      await refreshExternal();
    } catch (e) {
      toast("error", `保存失败：${errMsg(e)}`);
    } finally {
      setBusy(false);
    }
  };

  const doDelete = async () => {
    if (!deleteTarget) return;
    setBusy(true);
    try {
      await api.deleteBrandInstance(brand, deleteTarget.id);
      toast("success", "已删除");
      // 删掉的正好是「当前实例」时把选择清掉，免得启动面板指向一个不存在的实例
      if (getSelectedInstanceId() === deleteTarget.id) setSelectedInstanceId(null);
      setDeleteTarget(null);
      await refreshExternal();
    } catch (e) {
      toast("error", `删除失败：${errMsg(e)}`);
    } finally {
      setBusy(false);
    }
  };

  const patchVersion = async (id: string, version: string) => {
    try {
      await api.patchBrandInstance(brand, id, { version });
      toast("success", "已切换版本");
      await refreshExternal();
    } catch (e) {
      toast("error", `切换版本失败：${errMsg(e)}`);
    }
  };

  /// 停到它真的不在 running/starting：后端在实例运行中拒绝改版本（409），
  /// 而停止是异步的，所以这里跟着 store.external（唯一状态源）等，超时就报失败。
  const stopAndWait = async (id: string): Promise<boolean> => {
    try {
      await api.stopBrand(brand, id);
    } catch (e) {
      toast("error", `停止失败：${errMsg(e)}`);
      return false;
    }
    for (let i = 0; i < 30; i += 1) {
      await sleep(1000);
      // glm-5.2: 卸载后停止轮询，避免继续发请求 / 更新已卸载组件状态
      if (!mountedRef.current) return false;
      await refreshExternal();
      const state = getState().external.find((e) => e.id === id)?.state;
      if (state !== "running" && state !== "starting") return true;
    }
    toast("error", "停止超时：实例仍在运行，版本未切换");
    return false;
  };

  /// 装好指定版本再切过去：安装期间该行副标题位置显示进度，装完才 PATCH
  const installThenPatch = async (id: string, version: string) => {
    setRowInstall({ id, version, status: null });
    try {
      await api.brandInstall(brand, version);
    } catch (e) {
      // 409（同品牌已有安装在进行）等：把后端给的原因原样带出来，实例版本不动。
      // 同一个版本的重复请求后端会直接接上那个任务（202），走不到这里；能到这里说明
      // 正在装的是**另一个**版本，等它结束再切比排两个编译更靠谱。
      const raw = errMsg(e);
      toast(
        "error",
        raw.includes("already running")
          ? `这个品牌正在安装另一个版本，等它结束再切换（${raw}）`
          : `安装失败：${raw}`,
      );
      setRowInstall(null);
      return;
    }
    for (;;) {
      await sleep(1000);
      // glm-5.2: 卸载后停止轮询，避免继续拉安装状态 / 更新已卸载组件状态
      if (!mountedRef.current) return;
      let st: BrandInstallStatus;
      try {
        st = await api.brandInstallStatus(brand);
      } catch (e) {
        toast("error", `读取安装状态失败：${errMsg(e)}`);
        setRowInstall(null);
        return;
      }
      setRowInstall({ id, version, status: st });
      if (st.state === "done") break;
      if (st.state === "failed" || st.state === "none") {
        if (st.state === "failed") toast("error", `安装失败：${st.error ?? st.message}`);
        else toast("info", st.message || "安装未在进行");
        setRowInstall(null);
        return;
      }
    }
    // glm-5.2: 装完到 PATCH 之间组件可能已卸载——此时不再 reload，更不能静默 patchVersion
    if (!mountedRef.current) return;
    setRowInstall(null);
    await reload();
    await patchVersion(id, version);
  };

  /// 行内版本选择器的三条路径：已装且已停 → 直接切；已装在跑 → 先停下再切；
  /// 未装 → 先装上再切（版本构建按品牌共享）。需要用户点头的两条先弹确认。
  const pickVersion = async (inst: ExternalInstance, version: string) => {
    if (!version || version === inst.version) return;
    const isInstalled = installed.some((r) => r.version === version);
    const running = inst.state === "running" || inst.state === "starting";
    if (!isInstalled || running) {
      setPending({ inst, version });
      return;
    }
    await patchVersion(inst.id, version);
  };

  const confirmPending = async () => {
    if (!pending) return;
    const { inst, version } = pending;
    setPending(null);
    // 确认前读一次最新状态：弹窗期间它可能已经被启动/停掉了
    const cur = getState().external.find((e) => e.id === inst.id) ?? inst;
    if ((cur.state === "running" || cur.state === "starting") && !(await stopAndWait(inst.id))) return;
    if (installed.some((r) => r.version === version)) await patchVersion(inst.id, version);
    else await installThenPatch(inst.id, version);
  };

  return (
    <div
      className="card"
      style={{ flex: 1, minHeight: 0, display: "flex", flexDirection: "column", padding: 0 }}
    >
      <div className="toolbar-row brand-instance-toolbar" style={{ padding: "6px 12px", flexShrink: 0 }}>
        <span style={{ fontSize: 13, fontWeight: 600, flex: "none" }}>
          {I18N["dsh.brand.section.instances"]}
        </span>
        <span className="spacer" />
        {/* 版本目录没到之前不判定「没装过版本」，免得先闪一句「尚未安装任何版本」 */}
        {versions === null ? (
          <span style={{ fontSize: 12, color: "var(--monet-on-surface-variant)" }}>加载中…</span>
        ) : (
          <>
            <input
              className="input"
              style={{ flex: mobile ? "1 1 80px" : "1 1 140px", maxWidth: mobile ? 150 : 220 }}
              placeholder={I18N["dsh.instance.name"]}
              value={form.name}
              onChange={(e) => setForm((f) => ({ ...f, name: e.target.value }))}
            />
            {installed.length === 0 ? (
              <>
                <span
                  style={{
                    fontSize: 12,
                    color: "var(--monet-on-surface-variant)",
                    whiteSpace: "nowrap",
                  }}
                >
                  {I18N["dsh.brand.none_installed"]}
                </span>
                {/* 没有版本可选的出路就在上面那张卡：点它直接把版本卡展开 */}
                <button className="btn btn-text ripple-host" onClick={onOpenVersions}>
                  {I18N["dsh.brand.install_new"]}
                </button>
              </>
            ) : (
              <select
                className="input"
                style={{ flex: "none", maxWidth: mobile ? 96 : 170, height: 36 }}
                value={createVersion}
                onChange={(e) => setForm((f) => ({ ...f, version: e.target.value }))}
              >
                {installed.map((r) => (
                  <option key={r.version} value={r.version}>
                    {mobile
                      ? `${r.version}${r.current ? " ✓" : ""}`
                      : `${r.version}${r.current ? `（${I18N["dsh.brand.current"]}）` : ""}`}
                  </option>
                ))}
              </select>
            )}
            <button
              className="btn btn-outline ripple-host"
              disabled={busy || !form.name.trim() || !createVersion}
              onClick={() => void doCreate()}
            >
              {I18N["dsh.brand.create"]}
            </button>
          </>
        )}
      </div>

      <div className="list-body scroll-hover brand-instance-list">
        {instances.length === 0 ? (
          <div className="empty-state" style={{ padding: "6px 12px" }}>
            <span>还没有 {shortName} 实例</span>
            {installed.length === 0 && (
              <span className="empty-sub">{I18N["dsh.brand.none_installed"]}</span>
            )}
          </div>
        ) : (
          instances.map((inst, idx) => {
            const running = inst.state === "running";
            const installing = rowInstall?.id === inst.id;
            const version = inst.version ?? "";
            const disabled = !!inst.disabled;
            return (
              <div key={inst.id}>
                <div className="tlli" style={{ padding: "6px 12px", opacity: disabled ? 0.55 : undefined }}>
                  {/* 「当前实例」与 dsh 实例列表同款：启动面板与主侧栏都认这个选择 */}
                  <label className="input-check">
                    <input
                      type="radio"
                      name={`brand-instance-${brand}`}
                      checked={inst.id === selectedId}
                      onChange={() => setSelectedInstanceId(inst.id)}
                    />
                  </label>
                  <span className="tlli-text">
                    <span className="tlli-title">
                      {inst.name}
                      <span className={`tag ${STATE_TAG[inst.state] ?? "plain"}`}>
                        {stateText(inst.state)}
                      </span>
                      {disabled && <span className="tag plain">策略禁用</span>}
                    </span>
                    <span className="tlli-subtitle">
                      {inst.id} · {version}
                      {running
                        ? ` · ${I18N["dsh.instance.port"]} ${
                            inst.port ? inst.port : I18N["dsh.instance.port.auto.none"]
                          } · ${inst.url ?? withBase(`/i/${inst.id}/`)}`
                        : ""}
                      {inst.state === "error" && inst.error ? ` · ${inst.error}` : ""}
                    </span>
                    {installing && (
                      <span
                        className="tlli-subtitle"
                        style={{ display: "flex", alignItems: "center", gap: 6 }}
                      >
                        <span style={{ flex: 1, minWidth: 48 }}>
                          <ProgressBar fraction={rowInstall?.status?.fraction} />
                        </span>
                        <span style={{ whiteSpace: "nowrap" }}>
                          {I18N["dsh.brand.installing.progress"].replace(
                            "%s",
                            `${rowInstall?.version ?? ""} ${rowInstall?.status?.message ?? ""}`.trim(),
                          )}
                        </span>
                      </span>
                    )}
                  </span>
                  <span className="row-actions">
                    {/* 全量版本目录：已装/未装都列出来，切到未装的会先把它装上 */}
                    <select
                      className="input"
                      style={{ flex: "none", maxWidth: mobile ? 84 : 124, height: mobile ? 32 : 36 }}
                      value={version}
                      disabled={busy || installing || disabled}
                      onChange={(e) => void pickVersion(inst, e.target.value)}
                    >
                      {catalog.map((v) => (
                        <option key={v} value={v}>
                          {mobile
                            ? `${v}${installed.some((r) => r.version === v) ? " ✓" : ""}`
                            : versionOption(v, false, installed.some((r) => r.version === v))}
                        </option>
                      ))}
                      {/* 上游撤掉的版本会不在目录里：补一条占位，免得选择器回落到别的版本 */}
                      {!catalog.includes(version) && <option value={version}>{version}</option>}
                    </select>
                    <button
                      className="icon-btn on-variant ripple-host"
                      title={running ? I18N["dsh.stop"] : I18N["dsh.launch"]}
                      // "停止"对策略禁用的实例仍然要能点：后端明确允许把被禁用的实例关掉，
                      // 否则政策切换（或面板外改了 policy.json）之后只能看着它跑。
                      disabled={
                        busyId === inst.id
                        || inst.state === "starting"
                        || installing
                        || (disabled && !running)
                      }
                      onClick={() => (running ? void doStop(inst) : void doLaunch(inst))}
                    >
                      {running ? <CloseIcon size={18} /> : <RocketLaunchIcon size={18} />}
                    </button>
                    <button
                      className="icon-btn on-variant ripple-host"
                      title={webShellEnabled ? I18N["terminal.open"] : I18N["terminal.unavailable"]}
                      disabled={!webShellEnabled || installing || disabled}
                      onClick={() =>
                        setTerminalTarget({ kind: "brand", brand, id: inst.id, name: inst.name })
                      }
                    >
                      <TerminalIcon size={18} />
                    </button>
                    <button
                      className="icon-btn on-variant ripple-host"
                      title={I18N["dsh.brand.open"]}
                      disabled={!running || disabled}
                      onClick={() => doOpen(inst)}
                    >
                      <PublicIcon size={18} />
                    </button>
                    <button
                      className="icon-btn on-variant ripple-host"
                      title={I18N["dsh.instance.menu"]}
                      onClick={(e) => setMenuAnchor({ el: e.currentTarget, inst })}
                    >
                      <MoreVertIcon size={18} />
                    </button>
                  </span>
                </div>
                {idx < instances.length - 1 && <div className="divider" style={{ marginLeft: 12 }} />}
              </div>
            );
          })
        )}
      </div>

      {/* 更多菜单（HMCL IconedMenuItem 风格） */}
      <PopupMenu
        open={menuAnchor !== null}
        anchor={menuAnchor?.el ?? null}
        onClose={() => setMenuAnchor(null)}
      >
        <PopupItem
          icon={<InfoIcon size={16} />}
          onClick={() => {
            const inst = menuAnchor?.inst;
            setMenuAnchor(null);
            if (inst) setInfoTarget(inst);
          }}
        >
          {I18N["dsh.brand.instance.info"]}
        </PopupItem>
        <PopupItem
          icon={<EditIcon size={16} />}
          onClick={() => {
            const inst = menuAnchor?.inst;
            setMenuAnchor(null);
            if (inst) {
              setRenameValue(inst.name);
              setRenameTarget(inst);
            }
          }}
        >
          {I18N["dsh.brand.instance.rename"]}
        </PopupItem>
        <PopupItem
          icon={<ExtensionIcon size={16} />}
          onClick={() => {
            const inst = menuAnchor?.inst;
            setMenuAnchor(null);
            if (inst) setPluginsTarget(inst);
          }}
        >
          {I18N["dsh.brand.instance.plugins"]}
        </PopupItem>
        <PopupItem
          icon={<FormatListBulletedIcon size={16} />}
          onClick={() => {
            const inst = menuAnchor?.inst;
            setMenuAnchor(null);
            if (inst) void doShowLogs(inst);
          }}
        >
          {I18N["dsh.brand.view_log"]}
        </PopupItem>
        {brand === "claude-web" && (
          <PopupItem
            icon={<PublicIcon size={16} />}
            onClick={() => {
              const inst = menuAnchor?.inst;
              setMenuAnchor(null);
              if (inst) {
                setBaseUrlTarget(inst);
                setBaseUrlValue(inst.baseUrl ?? "");
              }
            }}
          >
            Anthropic 端点…
          </PopupItem>
        )}
        {brand === "octop" && (
          <PopupItem
            onClick={() => {
              const inst = menuAnchor?.inst;
              setMenuAnchor(null);
              if (inst) setAdminTarget(inst);
            }}
          >
            登录密码…
          </PopupItem>
        )}
        <PopupSep />
        <PopupItem
          danger
          icon={<DeleteIcon size={16} />}
          onClick={() => {
            const inst = menuAnchor?.inst;
            setMenuAnchor(null);
            if (inst) setDeleteTarget(inst);
          }}
        >
          {I18N["button.delete"]}
        </PopupItem>
      </PopupMenu>

      <InfoDialog brand={brand} inst={infoTarget} onClose={() => setInfoTarget(null)} />
      <PluginsDialog brand={brand} inst={pluginsTarget} onClose={() => setPluginsTarget(null)} />
      <AdminCredentialsDialog brand={brand} instance={adminTarget} onClose={() => setAdminTarget(null)} />

      {/* 重命名 */}
      <Dialog
        open={renameTarget !== null}
        title={I18N["dsh.brand.instance.rename"]}
        onClose={() => !busy && setRenameTarget(null)}
      >
        <div className="form-row">
          <span className="form-label">{I18N["dsh.instance.name"]}</span>
          <input
            className="input"
            autoFocus
            value={renameValue}
            onChange={(e) => setRenameValue(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && !busy) void doRename();
            }}
          />
        </div>
        <div className="dialog-actions">
          <button className="btn btn-text dim" disabled={busy} onClick={() => setRenameTarget(null)}>
            {I18N["button.cancel"]}
          </button>
          <button className="btn btn-text" disabled={busy} onClick={() => void doRename()}>
            {I18N["button.ok"]}
          </button>
        </div>
      </Dialog>

      {/* Claude Web 的 ANTHROPIC_BASE_URL */}
      <Dialog
        open={baseUrlTarget !== null}
        title="Anthropic 端点（ANTHROPIC_BASE_URL）"
        onClose={() => !busy && setBaseUrlTarget(null)}
      >
        <p style={{ fontSize: 12, color: "var(--monet-on-surface-variant)", lineHeight: 1.6, marginTop: 0 }}>
          官方内核在不受支持的地区会被 Anthropic 直接拒绝（HTTP 403）。这里填一个可达的网关地址，
          官方内核与这个实例的网页模式都会改用它；留空则用工具自己的默认端点。
        </p>
        <div className="form-row">
          <span className="form-label">ANTHROPIC_BASE_URL</span>
          <input
            className="input"
            autoFocus
            placeholder="https://gateway.example.com"
            value={baseUrlValue}
            onChange={(e) => setBaseUrlValue(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && !busy) void doSaveBaseUrl();
            }}
          />
        </div>
        <div className="dialog-actions">
          <button className="btn btn-text dim" disabled={busy} onClick={() => setBaseUrlTarget(null)}>
            {I18N["button.cancel"]}
          </button>
          <button className="btn btn-text" disabled={busy} onClick={() => void doSaveBaseUrl()}>
            {I18N["button.ok"]}
          </button>
        </div>
      </Dialog>

      {/* 日志 */}
      <Dialog
        open={logTarget !== null}
        title={`日志 · ${logTarget?.name ?? ""}`}
        wide
        onClose={() => setLogTarget(null)}
      >
        <pre style={{ maxHeight: 360, overflow: "auto", fontSize: 12, lineHeight: 1.5, margin: 0 }}>
          {logLines.length === 0 ? I18N["dsh.brand.log.empty"] : logLines.join("\n")}
        </pre>
        <div className="dialog-actions">
          <button className="btn btn-text dim" onClick={() => setLogTarget(null)}>
            {I18N["button.cancel"]}
          </button>
          <button
            className="btn btn-text"
            onClick={() => logTarget && void doShowLogs(logTarget)}
          >
            {I18N["button.refresh"]}
          </button>
        </div>
      </Dialog>

      <ConfirmDialog
        open={deleteTarget !== null}
        title={`删除 ${shortName} 实例`}
        message={I18N["dsh.brand.delete.confirm"].replace("%s", deleteTarget?.name ?? "")}
        confirmLabel={I18N["button.delete"]}
        danger
        busy={busy}
        onConfirm={() => void doDelete()}
        onCancel={() => setDeleteTarget(null)}
      />

      {/* 版本切换确认：未装的那条优先（它同时涵盖了「在跑要先停」） */}
      <ConfirmDialog
        open={pending !== null}
        title={I18N["dsh.brand.version.switch"]}
        message={
          pending &&
          (installed.some((r) => r.version === pending.version)
            ? I18N["dsh.brand.version.stop_and_switch"]
            : I18N["dsh.brand.version.install_and_switch"].replace("%s", pending.version))
        }
        confirmLabel={I18N["button.ok"]}
        busy={busy}
        onConfirm={() => void confirmPending()}
        onCancel={() => setPending(null)}
      />

      <TerminalWindow target={terminalTarget} onClose={() => setTerminalTarget(null)} />
    </div>
  );
}

/* ---------------------------------------------------------------------------
 * 弹窗：信息 / 插件通道
 * ------------------------------------------------------------------------- */

/// 信息弹窗：ExternalInstance 是状态侧视图（没有 createdAt），
/// 所以打开时按该品牌的实例清单回读一次详细记录，读不到就只用 external 里的字段。
function InfoDialog({
  brand,
  inst,
  onClose,
}: {
  brand: BrandId;
  inst: ExternalInstance | null;
  onClose: () => void;
}) {
  const [detail, setDetail] = useState<BrandInstance | null>(null);

  useEffect(() => {
    if (!inst) return;
    let alive = true;
    setDetail(null);
    void api
      .brandInstances(brand)
      .then((r) => {
        if (alive) setDetail(r.instances.find((i) => i.id === inst.id) ?? null);
      })
      .catch(() => undefined);
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [brand, inst?.id]);

  const url = inst ? (inst.url ?? withBase(`/i/${inst.id}/`)) : "";
  const port = detail?.lastPort ?? inst?.port ?? 0;
  const copy = () => {
    void navigator.clipboard.writeText(url).then(
      () => toast("success", "已复制"),
      () => toast("error", "复制失败"),
    );
  };

  return (
    <Dialog
      open={inst !== null}
      title={`${I18N["dsh.brand.instance.info"]} · ${inst?.name ?? ""}`}
      onClose={onClose}
    >
      <div className="comp-row">
        <span className="comp-label">ID</span>
        <span className="comp-value mono">{inst?.id ?? ""}</span>
      </div>
      <div className="comp-row">
        <span className="comp-label">{I18N["dsh.brand.versions"]}</span>
        <span className="comp-value">{inst?.version ?? "—"}</span>
      </div>
      <div className="comp-row">
        <span className="comp-label">状态</span>
        <span className="comp-value">
          <span className={`tag ${STATE_TAG[inst?.state ?? ""] ?? "plain"}`}>
            {stateText(inst?.state ?? "")}
          </span>
          {inst?.state === "error" && inst.error ? (
            <span style={{ fontSize: 12 }}>{inst.error}</span>
          ) : null}
        </span>
      </div>
      <div className="comp-row">
        <span className="comp-label">{I18N["dsh.instance.port"]}</span>
        <span className="comp-value">
          {port > 0 ? port : I18N["dsh.instance.port.auto.none"]}
        </span>
      </div>
      <div className="comp-row">
        <span className="comp-label">{I18N["dsh.brand.instance.address"]}</span>
        <span className="comp-value url-ellipsis" title={url}>
          {url}
        </span>
        <button
          className="icon-btn on-variant ripple-host"
          title={I18N["button.copy"]}
          onClick={copy}
        >
          <ContentCopyIcon size={16} />
        </button>
        <button
          className="icon-btn on-variant ripple-host"
          title={I18N["dsh.brand.open"]}
          disabled={inst?.state !== "running"}
          onClick={() => window.open(url, "_blank", "noopener")}
        >
          <PublicIcon size={16} />
        </button>
      </div>
      <div className="comp-row">
        <span className="comp-label">{I18N["dsh.brand.instance.created"]}</span>
        <span className="comp-value">{detail ? formatDateTime(detail.createdAt) : "…"}</span>
      </div>
      <div className="dialog-actions">
        <button className="btn btn-text" onClick={onClose}>
          {I18N["button.ok"]}
        </button>
      </div>
    </Dialog>
  );
}

/// 插件弹窗：品牌实例的插件通道现在恒为 supported=false（后端已经把形状与通道留好），
/// 所以这里只把 supported 与 plugins 如实画出来——没有品牌走 supported=true 的支路时
/// 也不造假动作按钮；后端哪天填上 plugins，这里直接列出来。
function PluginsDialog({
  brand,
  inst,
  onClose,
}: {
  brand: BrandId;
  inst: ExternalInstance | null;
  onClose: () => void;
}) {
  const [data, setData] = useState<InstancePlugins | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!inst) return;
    let alive = true;
    setData(null);
    setError(null);
    setLoading(true);
    api
      .brandPlugins(brand, inst.id)
      .then((d) => {
        if (alive) setData(d);
      })
      .catch((e) => {
        if (alive) setError(errMsg(e));
      })
      .finally(() => {
        if (alive) setLoading(false);
      });
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [brand, inst?.id]);

  return (
    <Dialog
      open={inst !== null}
      title={`${I18N["dsh.brand.instance.plugins"]} · ${inst?.name ?? ""}`}
      onClose={onClose}
    >
      {loading ? (
        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <span className="spinner" />
          <span>正在读取插件通道…</span>
        </div>
      ) : error ? (
        <div className="hint error">{error}</div>
      ) : data && data.plugins.length > 0 ? (
        <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
          {data.plugins.map((p) => (
            <div key={p.name} className="toolbar-row" style={{ padding: 0 }}>
              <span style={{ fontSize: 13 }}>{p.name}</span>
              {p.version && <span className="tag plain">{p.version}</span>}
              <span className={`tag ${p.enabled ? "running" : "plain"}`}>
                {p.enabled ? I18N["button.enable"] : I18N["button.disable"]}
              </span>
            </div>
          ))}
        </div>
      ) : data?.supported ? (
        <p style={{ lineHeight: 1.7 }}>{I18N["dsh.brand.plugins.empty"]}</p>
      ) : (
        <p style={{ lineHeight: 1.7 }}>{I18N["dsh.brand.plugins.unsupported"]}</p>
      )}
      <div className="dialog-actions">
        <button className="btn btn-text" onClick={onClose}>
          {I18N["button.ok"]}
        </button>
      </div>
    </Dialog>
  );
}