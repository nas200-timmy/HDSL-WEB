import { useEffect, useReducer, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { api, ApiError, isNotImplemented } from "../api";
import { ConfirmDialog, Dialog } from "../components/Dialog";
import { BrandManagementPanel } from "../components/BrandManagementPanel";
import { FileDropInput } from "../components/FileDropInput";
import {
  CheckCircleIcon,
  CheckIcon,
  DeployedCodeFillIcon,
  DeployedCodeIcon,
  DownloadIcon,
  ErrorIcon,
  ExtensionIcon,
  InfoFillIcon,
  InfoIcon,
  KeepIcon,
  LocalCafeFillIcon,
  LocalCafeIcon,
  RefreshIcon,
  StadiaControllerFillIcon,
  StadiaControllerIcon,
  StyleFillIcon,
  StyleIcon,
  TuneIcon,
  WarningIcon,
} from "../components/icons";
import { SubSideBar } from "../components/SideBar";
import { I18N } from "../i18n";
import { logout, setState, toast, useAppState } from "../store";
import { bindSystemThemeListener, setThemeMode, useThemeMode, type ThemeMode } from "../theme";
import type {
  DeveloperGuardScope,
  DeveloperStatus,
  DoctorCheck,
  DoctorReport,
  EmergencyStopPreview,
  Health,
  RegistrySettings,
  RegistryTestResult,
  TlsSettings,
  TlsUploadResult,
} from "../types";
import { errMsg, formatDateTime, formatUptime, registryError } from "../utils";

/// 开发者守卫四档的中文名，按钮与状态行共用。
const GUARD_SCOPE_LABEL: Record<DeveloperGuardScope, string> = {
  off: "关闭",
  proxy: "只反代",
  instance: "反代 + 实例操作",
  all: "全部 API",
};

/// 把剩余秒数写成 "47 分 12 秒" / "58 秒"。
function formatRemaining(seconds: number | undefined): string {
  const total = Math.max(0, Math.floor(seconds ?? 0));
  if (total <= 0) return "0 秒";
  const minutes = Math.floor(total / 60);
  const rest = total % 60;
  return minutes > 0 ? `${minutes} 分 ${rest} 秒` : `${rest} 秒`;
}

type SettingsTab =
  | "general"
  | "node"
  | "launcher-general"
  | "appearance"
  | "download-source"
  | "brands"
  | "tls"
  | "developer"
  | "doctor"
  | "about";

const TLS_SOURCE_LABEL: Record<string, string> = {
  none: "HTTP 明文",
  "self-signed": "自签证书",
  uploaded: "上传证书（PEM）",
  pkcs12: "上传证书（PKCS#12）",
};

export function SettingsPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const tabParam = searchParams.get("tab");
  const tab: SettingsTab = (
    [
      "general",
      "node",
      "launcher-general",
      "appearance",
      "download-source",
      "brands",
      "tls",
      "developer",
      "doctor",
      "about",
    ] as SettingsTab[]
  ).includes(tabParam as SettingsTab)
    ? (tabParam as SettingsTab)
    : "general";
  const setTab = (t: SettingsTab) => setSearchParams({ tab: t }, { replace: true });

  useEffect(() => bindSystemThemeListener(), []);

  return (
    <div className="page-with-sidebar">
      <SubSideBar
        activeKey={tab}
        onSelect={(k) => setTab(k as SettingsTab)}
        sections={[
          {
            items: [
              {
                key: "general",
                title: I18N["dsh.settings.general"],
                icon: <StadiaControllerIcon size={20} />,
                iconActive: <StadiaControllerFillIcon size={20} />,
              },
              {
                key: "node",
                title: I18N["dsh.node.title"],
                icon: <LocalCafeIcon size={20} />,
                iconActive: <LocalCafeFillIcon size={20} />,
              },
            ],
          },
          {
            title: I18N["launcher"],
            items: [
              {
                key: "launcher-general",
                title: I18N["settings.launcher.general"],
                icon: <TuneIcon size={20} />,
              },
              {
                key: "appearance",
                title: I18N["dsh.settings.appearance"],
                icon: <StyleIcon size={20} />,
                iconActive: <StyleFillIcon size={20} />,
              },
              {
                key: "download-source",
                title: I18N["settings.launcher.download_source"],
                icon: <DownloadIcon size={20} />,
              },
              {
                key: "brands",
                title: "品牌实例",
                icon: <ExtensionIcon size={20} />,
              },
              {
                key: "tls",
                title: "HTTPS 证书",
                icon: <KeepIcon size={20} />,
              },
              {
                key: "developer",
                title: "开发者模式",
                icon: <DeployedCodeIcon size={20} />,
                iconActive: <DeployedCodeFillIcon size={20} />,
              },
            ],
          },
          {
            title: I18N["help"],
            items: [
              {
                key: "doctor",
                title: "环境体检",
                icon: <CheckCircleIcon size={20} />,
              },
              {
                key: "about",
                title: I18N["about"],
                icon: <InfoIcon size={20} />,
                iconActive: <InfoFillIcon size={20} />,
              },
            ],
          },
        ]}
      />
      <div
        className="page-content scroll"
        key={tab}
        style={{ gap: 8, animation: "slide-up-fade-in 400ms var(--ease)" }}
      >
        {tab === "general" && <GeneralTab />}
        {tab === "node" && <NodeTab />}
        {tab === "launcher-general" && <LauncherGeneralTab />}
        {tab === "appearance" && <AppearanceTab />}
        {tab === "download-source" && <DownloadSourceTab />}
        {tab === "brands" && <BrandManagementPanel />}
        {tab === "tls" && <TlsTab />}
        {tab === "developer" && <DeveloperTab />}
        {tab === "doctor" && <DoctorTab />}
        {tab === "about" && <AboutTab />}
      </div>
    </div>
  );
}

/* ---------------- 通用：服务器信息 + 账户会话 ---------------- */

function GeneralTab() {
  const s = useAppState();
  const nav = useNavigate();
  const [health, setHealth] = useState<Health | null>(null);
  const [loadedAt, setLoadedAt] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [, tick] = useReducer((x: number) => x + 1, 0);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      const h = await api.health();
      setHealth(h);
      setLoadedAt(Date.now());
    } catch (e) {
      setError(errMsg(e));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load();
  }, []);

  useEffect(() => {
    if (!health) return;
    const timer = window.setInterval(() => tick(), 1000);
    return () => window.clearInterval(timer);
  }, [!!health]);

  const healthy = !!health && /^ok$/i.test(health.status);
  const uptime = health ? health.uptimeSec + Math.max(0, (Date.now() - loadedAt) / 1000) : null;

  const doLogout = async () => {
    await logout();
    nav("/login", { replace: true });
  };

  return (
    <>
      <div className="section-caption">
        <InfoIcon size={16} />
        服务器信息
      </div>
      <div className="card">
        {loading && !health ? (
          <div style={{ display: "flex", alignItems: "center", gap: 10, padding: 12 }}>
            <span className="spinner" />
            <span>正在获取服务器信息…</span>
          </div>
        ) : error && !health ? (
          <div style={{ display: "flex", alignItems: "center", gap: 8, padding: 12 }}>
            <ErrorIcon size={16} style={{ color: "var(--monet-error)" }} />
            <span>获取服务器信息失败:{error}</span>
            <button className="btn btn-outline ripple-host" style={{ marginLeft: "auto" }} onClick={() => void load()}>
              重试
            </button>
          </div>
        ) : health ? (
          <>
            <div className="comp-row">
              <span className="comp-label">状态</span>
              <span className="comp-value">
                <span className={`tag ${healthy ? "running" : "failed"}`}>{healthy ? "正常" : health.status}</span>
              </span>
            </div>
            <div className="comp-row">
              <span className="comp-label">服务端版本</span>
              <span className="comp-value">{health.version}</span>
            </div>
            <div className="comp-row">
              <span className="comp-label">运行时长</span>
              <span className="comp-value">{uptime != null ? formatUptime(uptime) : "—"}</span>
            </div>
            <div style={{ display: "flex", justifyContent: "flex-end", paddingTop: 6 }}>
              <button className="btn btn-text ripple-host" disabled={loading} onClick={() => void load()}>
                <RefreshIcon size={15} />
                {I18N["button.refresh"]}
              </button>
            </div>
          </>
        ) : null}
      </div>

      <div className="section-caption">
        <TuneIcon size={16} />
        修改密码
      </div>
      <PasswordCard />

      <div className="section-caption">
        <InfoIcon size={16} />
        修改用户名
      </div>
      <UsernameCard />

      <div className="section-caption">
        <InfoIcon size={16} />
        当前用户
      </div>
      <div className="card">
        <div className="comp-row">
          <span className="comp-label">用户名</span>
          <span className="comp-value">{s.username ?? "—"}</span>
        </div>
        <div className="comp-row">
          <span className="comp-label">账户</span>
          <span className="comp-value">
            <button className="btn btn-text" style={{ height: 28 }} onClick={() => nav("/accounts")}>
              {I18N["account"]}
            </button>
          </span>
        </div>
        <div style={{ display: "flex", justifyContent: "flex-end", paddingTop: 6 }}>
          <button className="btn btn-text danger ripple-host" onClick={() => void doLogout()}>
            退出登录
          </button>
        </div>
      </div>
    </>
  );
}

/* ---------------- 修改用户名 ---------------- */

function UsernameCard() {
  const s = useAppState();
  const [name, setName] = useState(s.username ?? "");
  const [formError, setFormError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // 用户名在别处变化（如重新登录）时同步输入框
  useEffect(() => setName(s.username ?? ""), [s.username]);

  const submit = async () => {
    const trimmed = name.trim();
    if (!trimmed || /\s/.test(trimmed) || trimmed.length > 32) {
      setFormError("用户名需为 1–32 位且不含空白字符");
      return;
    }
    setFormError(null);
    setBusy(true);
    try {
      const r = await api.changeUsername(trimmed);
      setState((st) => ({ ...st, username: r.username }));
      toast("success", trimmed === s.username ? "用户名未变化" : "用户名已更新");
    } catch (e) {
      toast("error", `修改用户名失败：${errMsg(e)}`);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="card">
      <div className="comp-row">
        <span className="comp-label">新用户名</span>
        <span className="comp-value">
          <input
            className="input"
            style={{ flex: 1, minWidth: 220, fontWeight: 400 }}
            autoComplete="username"
            value={name}
            onChange={(e) => setName(e.target.value)}
            disabled={busy}
          />
        </span>
      </div>
      {formError && <div className="form-error">{formError}</div>}
      <div style={{ display: "flex", justifyContent: "flex-end", paddingTop: 6 }}>
        <button
          className="btn btn-raised ripple-host"
          disabled={busy || !s.username || name.trim() === s.username}
          onClick={() => void submit()}
        >
          {busy ? "提交中…" : "更新用户名"}
        </button>
      </div>
      <p style={{ fontSize: 12, color: "var(--monet-on-surface-variant)", padding: "4px 8px 0" }}>
        修改成功后，该账号在其它浏览器中的会话会被退出。
      </p>
    </div>
  );
}

/* ---------------- 修改密码 ---------------- */

function PasswordCard() {
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [confirm, setConfirm] = useState("");
  const [formError, setFormError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async () => {
    if (!current || !next) {
      setFormError("请输入当前密码和新密码");
      return;
    }
    if (next.length < 6) {
      setFormError("新密码至少 6 位");
      return;
    }
    if (next !== confirm) {
      setFormError("两次输入的新密码不一致");
      return;
    }
    setFormError(null);
    setBusy(true);
    try {
      await api.changePassword(current, next);
      setCurrent("");
      setNext("");
      setConfirm("");
      toast("success", "密码已更新");
    } catch (e) {
      const msg = e instanceof ApiError && e.status === 403 ? "当前密码错误" : errMsg(e);
      toast("error", `修改密码失败：${msg}`);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="card">
      <div className="comp-row">
        <span className="comp-label">当前密码</span>
        <span className="comp-value">
          <input
            className="input"
            style={{ flex: 1, minWidth: 220, fontWeight: 400 }}
            type="password"
            autoComplete="current-password"
            value={current}
            onChange={(e) => setCurrent(e.target.value)}
            disabled={busy}
          />
        </span>
      </div>
      <div className="comp-row">
        <span className="comp-label">新密码</span>
        <span className="comp-value">
          <input
            className="input"
            style={{ flex: 1, minWidth: 220, fontWeight: 400 }}
            type="password"
            autoComplete="new-password"
            value={next}
            onChange={(e) => setNext(e.target.value)}
            disabled={busy}
          />
        </span>
      </div>
      <div className="comp-row">
        <span className="comp-label">确认新密码</span>
        <span className="comp-value">
          <input
            className="input"
            style={{ flex: 1, minWidth: 220, fontWeight: 400 }}
            type="password"
            autoComplete="new-password"
            value={confirm}
            onChange={(e) => setConfirm(e.target.value)}
            disabled={busy}
          />
        </span>
      </div>
      {formError && <div className="form-error">{formError}</div>}
      <div style={{ display: "flex", justifyContent: "flex-end", paddingTop: 6 }}>
        <button className="btn btn-raised ripple-host" disabled={busy} onClick={() => void submit()}>
          {busy ? "提交中…" : "更新密码"}
        </button>
      </div>
      <p style={{ fontSize: 12, color: "var(--monet-on-surface-variant)", padding: "4px 8px 0" }}>
        修改成功后，该账号在其它浏览器中的会话会被退出。
      </p>
    </div>
  );
}

/* ---------------- Node.js 工具链（裸部署一键供给） ---------------- */

function NodeTab() {
  const [status, setStatus] = useState<import("../api").NodeStatus | null>(null);
  const [installing, setInstalling] = useState<string | null>(null);
  const [taskMsg, setTaskMsg] = useState("");
  const [source, setSource] = useState<"mirror" | "official">("mirror");
  const [busy, setBusy] = useState(false);

  const reload = () => void api.nodeStatus().then(setStatus).catch((e) => toast("error", `读取状态失败：${errMsg(e)}`));
  useEffect(() => {
    reload();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 任务轮询：node-install 走 TaskService，进度经 WS 进 store，但这里
  // 简单起见直接轮询任务列表（此页打开时才轮询）
  useEffect(() => {
    if (!installing) return;
    const timer = setInterval(() => {
      void api.tasks().then((tasks) => {
        const t = tasks.find((x) => x.id === installing);
        if (!t) return;
        setTaskMsg(`${t.state}: ${t.message ?? ""}`);
        if (t.state === "done" || t.state === "failed") {
          setInstalling(null);
          setTaskMsg("");
          toast(t.state === "done" ? "success" : "error", t.message ?? t.state);
          reload();
        }
      }).catch(() => undefined);
    }, 1000);
    return () => clearInterval(timer);
  }, [installing]);  // eslint-disable-line react-hooks/exhaustive-deps

  const doInstall = async () => {
    setBusy(true);
    try {
      const r = await api.nodeInstall({ source });
      setInstalling(r.taskId);
      setTaskMsg("running: …");
    } catch (e) {
      toast("error", `启动安装失败：${errMsg(e)}`);
    } finally {
      setBusy(false);
    }
  };

  const doUninstall = async (version: string) => {
    setBusy(true);
    try {
      await api.nodeUninstall(version);
      toast("success", `已卸载 Node.js ${version}`);
      reload();
    } catch (e) {
      toast("error", `卸载失败：${errMsg(e)}`);
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <div className="section-caption">
        <LocalCafeIcon size={16} />
        {I18N["dsh.node.title"]}
      </div>
      <div className="card" style={{ display: "grid", gap: 8, padding: 12 }}>
        <span style={{ fontSize: 12, color: "var(--monet-on-surface-variant)" }}>
          安装 dsh 版本、插件与品牌工具需要 Node.js 工具链：{status?.requirement ?? "…"}
        </span>

        {/* 系统 node */}
        <div className="comp-row" style={{ padding: "6px 0" }}>
          <span className="comp-label" style={{ width: "auto", flex: "none" }}>系统 PATH</span>
          <span className="comp-value" style={{ fontSize: 13 }}>
            {status === null ? "检测中…" : status.system
              ? `node ${status.system.nodeVersion}${status.system.npmVersion ? ` · npm ${status.system.npmVersion}` : ""}${status.system.pnpmVersion ? ` · pnpm ${status.system.pnpmVersion}` : " · pnpm 缺失"}`
              : "未检测到 node"}
          </span>
        </div>

        {/* 托管运行时 */}
        <span style={{ fontSize: 13, fontWeight: 600 }}>面板托管的运行时（存于数据目录，PATH 没有 node 时自动使用）</span>
        {status && status.managed.length === 0 && (
          <span style={{ fontSize: 13, color: "var(--monet-on-surface-variant)" }}>还没有。裸部署（如 Windows 直接 java -jar）可一键安装：</span>
        )}
        {status?.managed.map((r) => (
          <div key={r.version} className="toolbar-row" style={{ padding: "2px 0" }}>
            <span style={{ fontSize: 13 }}>
              Node.js {r.version}
              {!r.suitable && <span className="tag plain" style={{ marginLeft: 6 }}>版本不适配</span>}
              <span className={`tag ${r.pnpm ? "running" : "plain"}`} style={{ marginLeft: 6 }}>{r.pnpm ? "pnpm 就绪" : "无 pnpm"}</span>
            </span>
            <span className="spacer" />
            <button className="btn btn-text ripple-host" style={{ height: 28 }} disabled={busy} onClick={() => void doUninstall(r.version)}>
              卸载
            </button>
          </div>
        ))}

        {/* 一键安装 */}
        <div className="toolbar-row" style={{ padding: "4px 0", flexWrap: "wrap" }}>
          <select className="input" style={{ height: 36, maxWidth: 160 }} value={source} onChange={(e) => setSource(e.target.value as "mirror" | "official")}>
            <option value="mirror">国内镜像（npmmirror）</option>
            <option value="official">官方 nodejs.org</option>
          </select>
          <button className="btn btn-raised ripple-host" disabled={busy || installing !== null} onClick={() => void doInstall()}>
            {installing ? "安装中…" : "一键安装最新适配版本"}
          </button>
        </div>
        {taskMsg && <span style={{ fontSize: 12, color: "var(--monet-on-surface-variant)" }}>{taskMsg}</span>}

        {/* 手动安装 */}
        <div className="hint info" style={{ margin: 0 }}>
          <span style={{ flex: 1 }}>也可以自己装：下载 Node.js LTS（nodejs.org），装完重启面板即可被探测到。</span>
          <button className="btn btn-text ripple-host" style={{ height: 28 }} onClick={() => window.open("https://nodejs.org", "_blank", "noopener")}>
            打开 nodejs.org
          </button>
        </div>
      </div>
    </>
  );
}

/* ---------------- 启动器 · 通用（网页版映射：语言/服务器信息入口） ---------------- */

function LauncherGeneralTab() {
  const [, setSearchParams] = useSearchParams();
  return (
    <>
      <div className="section-caption">
        <TuneIcon size={16} />
        {I18N["settings.launcher.general"]}
      </div>
      <div className="card">
        <div className="comp-row">
          <span className="comp-label">界面语言</span>
          <span className="comp-value">简体中文（网页版暂只提供中文界面）</span>
        </div>
        <div className="comp-row">
          <span className="comp-label">服务器信息</span>
          <span className="comp-value">
            <button
              className="btn btn-text"
              style={{ height: 28 }}
              onClick={() => setSearchParams({ tab: "general" }, { replace: true })}
            >
              前往「通用」查看
            </button>
          </span>
        </div>
      </div>
    </>
  );
}

/* ---------------- 外观：主题亮暗 + 主题色展示 ---------------- */

function AppearanceTab() {
  const mode = useThemeMode();

  const modes: { value: ThemeMode; label: string }[] = [
    { value: "auto", label: I18N["dsh.settings.theme.brightness.auto"] },
    { value: "light", label: I18N["dsh.settings.theme.brightness.light"] },
    { value: "dark", label: I18N["dsh.settings.theme.brightness.dark"] },
  ];

  const swatches = [
    { name: "primary", color: "var(--monet-primary)" },
    { name: "primary-container", color: "var(--monet-primary-container)" },
    { name: "secondary-container", color: "var(--monet-secondary-container)" },
    { name: "tertiary", color: "var(--monet-tertiary)" },
    { name: "error", color: "var(--monet-error)" },
    { name: "surface", color: "var(--monet-surface)" },
  ];

  return (
    <>
      <div className="section-caption">
        <StyleIcon size={16} />
        {I18N["dsh.settings.appearance"]}
      </div>
      <div className="card">
        <div className="comp-row">
          <span className="comp-label">{I18N["dsh.settings.theme.brightness"]}</span>
          <span className="comp-value">
            {modes.map((m) => (
              <label key={m.value} className="input-check" style={{ marginRight: 10 }}>
                <input
                  type="radio"
                  name="theme-mode"
                  checked={mode === m.value}
                  onChange={() => setThemeMode(m.value)}
                />
                <span>{m.label}</span>
              </label>
            ))}
          </span>
        </div>
      </div>

      <div className="section-caption">
        <StyleFillIcon size={16} />
        {I18N["dsh.settings.theme_color"]}
      </div>
      <div className="card">
        <div className="comp-row">
          <span className="comp-label">种子色</span>
          <span className="comp-value">
            <span
              style={{
                width: 20,
                height: 20,
                borderRadius: 4,
                background: "#5C6BC0",
                border: "1px solid var(--monet-outline-variant)",
                flex: "none",
              }}
            />
            <code>#5C6BC0</code>
            <span style={{ color: "var(--monet-on-surface-variant)", fontSize: 12 }}>
              （{I18N["dsh.settings.theme_color.default"]}，Material You · FIDELITY · Spec 2025）
            </span>
          </span>
        </div>
        <div className="comp-row">
          <span className="comp-label">当前调色板</span>
          <span className="comp-value">
            {swatches.map((sw) => (
              <span
                key={sw.name}
                title={sw.name}
                style={{
                  width: 22,
                  height: 22,
                  borderRadius: 4,
                  background: sw.color,
                  border: "1px solid var(--monet-outline-variant)",
                  display: "inline-block",
                }}
              />
            ))}
          </span>
        </div>
      </div>
    </>
  );
}

/* ---------------- 下载源：npm registry ---------------- */

/** effective 是从哪来的——用户最想知道的就是「我改的生效了没」 */
const REGISTRY_SOURCE_LABEL: Record<string, string> = {
  setting: "面板设置",
  environment: "部署环境变量",
  default: "默认",
};

function DownloadSourceTab() {
  const [settings, setSettings] = useState<RegistrySettings | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [preset, setPreset] = useState("");
  const [custom, setCustom] = useState("");
  /** 手填框有没有被碰过——空着又没碰过就先不报红 */
  const [customTouched, setCustomTouched] = useState(false);
  const [busy, setBusy] = useState(false);
  const [testing, setTesting] = useState(false);
  const [probe, setProbe] = useState<RegistryTestResult | null>(null);

  const apply = (r: RegistrySettings) => {
    setSettings(r);
    setPreset(r.preset);
    setCustom(r.registry);
  };

  const load = async () => {
    setLoading(true);
    setLoadError(null);
    try {
      apply(await api.getRegistry());
    } catch (e) {
      setLoadError(isNotImplemented(e) ? "后端尚未支持下载源设置" : errMsg(e));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load();
  }, []);

  const customError = preset === "custom" ? registryError(custom) : null;
  // 红字只在填过东西之后出现；保存按钮不看这个门，照旧按 customError 禁用
  const showCustomError = customError != null && (customTouched || custom.trim() !== "");
  const changed =
    settings != null &&
    (preset !== settings.preset ||
      (preset === "custom" && custom.trim().replace(/\/+$/, "") !== settings.registry));
  const selectedPreset = settings?.presets.find((p) => p.id === preset);

  const save = async () => {
    if (customError) return;
    setBusy(true);
    try {
      // 成功时用返回体回读，不再 GET 一次
      apply(await api.setRegistry(preset === "custom" ? { preset, registry: custom.trim() } : { preset }));
      toast("success", "下载源已保存");
    } catch (e) {
      if (isNotImplemented(e)) toast("info", "后端尚未支持下载源设置");
      else toast("error", errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  /** 测的是下拉框当前选中的那个源：预设用它自己的地址（不必先保存），「自定义…」用手填的地址；
   *  两者都没有地址（跟随部署环境、或手填框还空着）时留空，交给服务端测当前生效的那个。 */
  const runProbe = async () => {
    const target = preset === "custom" ? custom.trim() : (selectedPreset?.url ?? "");
    setTesting(true);
    setProbe(null);
    try {
      setProbe(await api.testRegistry(target));
    } catch (e) {
      setProbe({
        registry: target,
        ok: false,
        status: null,
        millis: 0,
        error: isNotImplemented(e) ? "后端尚未支持下载源测速" : errMsg(e),
      });
    } finally {
      setTesting(false);
    }
  };

  return (
    <>
      <div className="section-caption">
        <DownloadIcon size={16} />
        {I18N["settings.launcher.download_source"]}
      </div>
      <div className="card">
        {loading && !settings ? (
          <div style={{ display: "flex", alignItems: "center", gap: 10, padding: 12 }}>
            <span className="spinner" />
            <span>正在获取下载源设置…</span>
          </div>
        ) : loadError && !settings ? (
          <div style={{ display: "flex", alignItems: "center", gap: 8, padding: 12 }}>
            <ErrorIcon size={16} style={{ color: "var(--monet-error)" }} />
            <span>{loadError}</span>
            <button className="btn btn-outline ripple-host" style={{ marginLeft: "auto" }} onClick={() => void load()}>
              重试
            </button>
          </div>
        ) : settings ? (
          <>
            <div className="comp-row">
              <span className="comp-label">{I18N["settings.launcher.download_source.preset"]}</span>
              <span className="comp-value">
                <select
                  className="input"
                  style={{ width: 260, maxWidth: "100%", fontWeight: 400 }}
                  value={preset}
                  disabled={busy || testing}
                  onChange={(e) => {
                    setPreset(e.target.value);
                    setCustomTouched(false);
                    setProbe(null);
                  }}
                >
                  {settings.presets
                    .filter((p) => p.id !== "custom")
                    .map((p) => (
                      <option key={p.id} value={p.id}>
                        {p.label}
                      </option>
                    ))}
                  <option value="custom">{I18N["settings.launcher.download_source.custom"]}</option>
                </select>
                {selectedPreset?.note && (
                  <span style={{ fontSize: 12, color: "var(--monet-on-surface-variant)" }}>
                    {selectedPreset.note}
                  </span>
                )}
              </span>
            </div>
            {preset === "custom" && (
              <div className="comp-row">
                <span className="comp-label">{I18N["settings.launcher.download_source.custom_url"]}</span>
                <span className="comp-value">
                  <input
                    className="input"
                    style={{ flex: 1, minWidth: 0, fontWeight: 400 }}
                    placeholder="https://registry.example.com"
                    value={custom}
                    disabled={busy || testing}
                    onChange={(e) => {
                      setCustom(e.target.value);
                      setCustomTouched(true);
                      setProbe(null);
                    }}
                    onBlur={() => setCustomTouched(true)}
                  />
                </span>
              </div>
            )}
            {showCustomError && <div className="form-error">{customError}</div>}
            <div className="comp-row">
              <span className="comp-label">{I18N["settings.launcher.download_source.effective"]}</span>
              <span className="comp-value mono">
                <span className={`tag ${settings.source === "setting" ? "running" : "plain"}`}>
                  {REGISTRY_SOURCE_LABEL[settings.source] ?? settings.source}
                </span>
                {settings.effective}
              </span>
            </div>
            <div style={{ display: "flex", alignItems: "center", flexWrap: "wrap", gap: 8, paddingTop: 6 }}>
              <button
                className="btn btn-outline ripple-host"
                disabled={testing || busy}
                onClick={() => void runProbe()}
              >
                {testing ? "测速中…" : "测速"}
              </button>
              {probe &&
                (probe.ok ? (
                  <>
                    <span className="tag running">约 {probe.millis} ms</span>
                    <span className="mono" style={{ fontSize: 12, color: "var(--monet-on-surface-variant)" }}>
                      {probe.registry}
                    </span>
                  </>
                ) : (
                  <span className="form-error">失败（{probe.error ?? `HTTP ${probe.status ?? "无响应"}`}）</span>
                ))}
              <button
                className="btn btn-raised ripple-host"
                style={{ marginLeft: "auto" }}
                disabled={busy || !changed || !!customError}
                onClick={() => void save()}
              >
                {busy ? "保存中…" : I18N["button.save"]}
              </button>
            </div>
            <p
              style={{
                fontSize: 12,
                color: "var(--monet-on-surface-variant)",
                padding: "4px 8px 0",
                lineHeight: 1.6,
              }}
            >
              {I18N["settings.launcher.download_source.priority"]}
              保存后对新安装的实例与构建生效；选「跟随部署环境」即清空面板设置。
            </p>
          </>
        ) : null}
      </div>
    </>
  );
}

/* ---------------- HTTPS 证书 ---------------- */

function tlsSourceTag(source: string): { cls: string; label: string } {
  switch ((source ?? "").toLowerCase()) {
    case "uploaded":
    case "pkcs12":
      return { cls: "running", label: TLS_SOURCE_LABEL[(source ?? "").toLowerCase()] ?? source };
    case "self-signed":
      return { cls: "warn", label: TLS_SOURCE_LABEL["self-signed"] };
    default:
      return { cls: "plain", label: TLS_SOURCE_LABEL["none"] };
  }
}

function TlsTab() {
  const [tls, setTls] = useState<TlsSettings | null>(null);
  const [tlsLoading, setTlsLoading] = useState(true);
  const [tlsError, setTlsError] = useState<string | null>(null);
  const [tlsMode, setTlsMode] = useState<"pem" | "p12">("pem");
  const [certFile, setCertFile] = useState<File | null>(null);
  const [keyFile, setKeyFile] = useState<File | null>(null);
  const [p12File, setP12File] = useState<File | null>(null);
  const [p12Password, setP12Password] = useState("");
  const [tlsFormError, setTlsFormError] = useState<string | null>(null);
  const [tlsBusy, setTlsBusy] = useState(false);
  const [redirectIn, setRedirectIn] = useState<number | null>(null);
  const [selfSignedConfirmOpen, setSelfSignedConfirmOpen] = useState(false);
  const [selfSignedBusy, setSelfSignedBusy] = useState(false);

  const loadTls = async () => {
    setTlsLoading(true);
    setTlsError(null);
    try {
      setTls(await api.getTls());
    } catch (e) {
      setTlsError(isNotImplemented(e) ? "后端尚未支持 TLS 管理" : errMsg(e));
    } finally {
      setTlsLoading(false);
    }
  };

  useEffect(() => {
    void loadTls();
  }, []);

  // HTTPS 启用后的跳转倒计时
  useEffect(() => {
    if (redirectIn == null) return;
    if (redirectIn <= 0) {
      window.location.href = window.location.href.replace(/^http:/, "https:");
      return;
    }
    const t = window.setTimeout(() => setRedirectIn((n) => (n == null ? null : n - 1)), 1000);
    return () => window.clearTimeout(t);
  }, [redirectIn]);

  const pickCert = (f: File | null) => {
    setCertFile(f);
    setTlsFormError(f && !/\.(pem|crt|cer|cert)$/i.test(f.name) ? "证书文件应为 .pem / .crt / .cer" : null);
  };

  const pickKey = (f: File | null) => {
    setKeyFile(f);
    setTlsFormError(f && !/\.(pem|key)$/i.test(f.name) ? "私钥文件应为 .pem / .key" : null);
  };

  const pickP12 = (f: File | null) => {
    setP12File(f);
    setTlsFormError(f && !/\.(p12|pfx)$/i.test(f.name) ? "证书文件应为 .p12 / .pfx" : null);
  };

  const doUploadTls = async () => {
    const fd = new FormData();
    if (tlsMode === "pem") {
      if (!certFile || !keyFile) {
        setTlsFormError("请选择 PEM 证书和私钥两个文件");
        return;
      }
      fd.append("certFile", certFile);
      fd.append("keyFile", keyFile);
    } else {
      if (!p12File) {
        setTlsFormError("请选择 .p12 证书文件");
        return;
      }
      fd.append("p12File", p12File);
      fd.append("password", p12Password);
    }
    setTlsFormError(null);
    setTlsBusy(true);
    try {
      const r: TlsUploadResult = await api.uploadTls(fd);
      if (!r.ok) {
        toast("error", `证书上传失败:${r.error ?? "未知错误"}`);
        return;
      }
      if (r.https) {
        toast("success", "HTTPS 已启用，即将跳转到安全地址");
        setRedirectIn(3);
      } else if (r.restartRequired) {
        toast("info", "证书已保存，需重启容器后生效");
        void loadTls();
      } else {
        toast("success", "证书已更新");
        void loadTls();
      }
    } catch (e) {
      if (isNotImplemented(e)) toast("info", "后端尚未支持 TLS 管理");
      else toast("error", `证书上传失败:${errMsg(e)}`);
    } finally {
      setTlsBusy(false);
    }
  };

  const doSelfSigned = async () => {
    setTlsFormError(null);
    setSelfSignedBusy(true);
    try {
      const r = await api.enableSelfSignedTls();
      if (!r.ok) {
        setTlsFormError(r.error ?? "自签名证书没能启用，请稍后再试");
        return;
      }
      setSelfSignedConfirmOpen(false);
      toast("success", "已生成自签名证书，即将跳转到 HTTPS");
      setRedirectIn(3);
    } catch (e) {
      setTlsFormError(isNotImplemented(e) ? "后端尚未支持自签名证书" : errMsg(e));
    } finally {
      setSelfSignedBusy(false);
    }
  };

  const tag = tls ? tlsSourceTag(tls.source) : null;

  return (
    <>
      <div className="section-caption">
        <KeepIcon size={16} />
        HTTPS 证书
      </div>
      <div className="card">
        {tlsLoading && !tls ? (
          <div style={{ display: "flex", alignItems: "center", gap: 10, padding: 12 }}>
            <span className="spinner" />
            <span>正在获取证书状态…</span>
          </div>
        ) : tlsError && !tls ? (
          <div style={{ display: "flex", alignItems: "center", gap: 8, padding: 12 }}>
            <ErrorIcon size={16} style={{ color: "var(--monet-error)" }} />
            <span>{tlsError}</span>
            <button className="btn btn-outline ripple-host" style={{ marginLeft: "auto" }} onClick={() => void loadTls()}>
              重试
            </button>
          </div>
        ) : tls ? (
          <>
            <div
              style={{
                display: "flex",
                alignItems: "flex-start",
                gap: 8,
                margin: "4px 8px 8px",
                padding: "10px 12px",
                border: "1px solid var(--monet-error)",
                borderRadius: 4,
                color: "var(--monet-error)",
                fontSize: 12,
                lineHeight: 1.6,
              }}
            >
              <WarningIcon size={16} style={{ flex: "none", marginTop: 2 }} />
              <span>
                <strong>自签名证书只是权宜之计。</strong>
                浏览器会提示证书不受信任，它也无法证明服务器身份，只适合内网或临时使用。
                正式部署请上传受信任证书，或把面板放到带证书的反向代理后面。
              </span>
            </div>
            <div className="comp-row">
              <span className="comp-label">当前状态</span>
              <span className="comp-value">
                <span className={`tag ${tag?.cls}`}>{tag?.label}</span>
                {tls.https && <span style={{ fontSize: 12 }}>HTTPS 已启用</span>}
              </span>
            </div>
            {tls.subject && (
              <div className="comp-row">
                <span className="comp-label">Subject</span>
                <span className="comp-value mono" style={{ fontSize: 12 }}>{tls.subject}</span>
              </div>
            )}
            {tls.issuer && (
              <div className="comp-row">
                <span className="comp-label">Issuer</span>
                <span className="comp-value mono" style={{ fontSize: 12 }}>{tls.issuer}</span>
              </div>
            )}
            {tls.sans && tls.sans.length > 0 && (
              <div className="comp-row">
                <span className="comp-label">SAN</span>
                <span className="comp-value mono" style={{ fontSize: 12 }}>{tls.sans.join("、")}</span>
              </div>
            )}
            {tls.expires && (
              <div className="comp-row">
                <span className="comp-label">过期时间</span>
                <span className="comp-value">{formatDateTime(tls.expires)}</span>
              </div>
            )}
            {tls.fingerprintSha256 && (
              <div className="comp-row">
                <span className="comp-label">SHA-256 指纹</span>
                <span className="comp-value mono" style={{ fontSize: 11 }}>{tls.fingerprintSha256}</span>
              </div>
            )}

            {redirectIn != null ? (
              <div style={{ display: "flex", alignItems: "center", gap: 10, padding: 12 }}>
                <span className="spinner" />
                <span>正在跳转到 HTTPS 地址（{redirectIn}s）…</span>
              </div>
            ) : (
              <div style={{ padding: "8px 4px 0", display: "flex", flexDirection: "column", gap: 10 }}>
                {!tls.https && (
                  <div style={{ display: "flex", alignItems: "center", flexWrap: "wrap", gap: 8 }}>
                    <button
                      className="btn btn-outline ripple-host"
                      disabled={tlsBusy || selfSignedBusy}
                      onClick={() => setSelfSignedConfirmOpen(true)}
                    >
                      生成并使用自签名证书
                    </button>
                    <span style={{ fontSize: 12, color: "var(--monet-on-surface-variant)" }}>
                      没有证书时的临时方案；浏览器会提示证书不受信任。
                    </span>
                  </div>
                )}
                <div style={{ display: "flex", gap: 8 }}>
                  <label className="input-check">
                    <input type="radio" checked={tlsMode === "pem"} onChange={() => { setTlsMode("pem"); setTlsFormError(null); }} />
                    <span>PEM 证书 + 私钥</span>
                  </label>
                  <label className="input-check">
                    <input type="radio" checked={tlsMode === "p12"} onChange={() => { setTlsMode("p12"); setTlsFormError(null); }} />
                    <span>PKCS#12（.p12）</span>
                  </label>
                </div>
                {tlsMode === "pem" ? (
                  <div style={{ display: "flex", gap: 10, flexWrap: "wrap" }}>
                    <div className="form-row" style={{ flex: 1, minWidth: 200, marginBottom: 0 }}>
                      <span className="form-label">证书文件（.pem / .crt）</span>
                      <FileDropInput file={certFile} onFile={pickCert} accept=".pem,.crt,.cer,.cert" hint="选择或拖入证书文件" disabled={tlsBusy} />
                    </div>
                    <div className="form-row" style={{ flex: 1, minWidth: 200, marginBottom: 0 }}>
                      <span className="form-label">私钥文件（.pem / .key）</span>
                      <FileDropInput file={keyFile} onFile={pickKey} accept=".pem,.key" hint="选择或拖入私钥文件" disabled={tlsBusy} />
                    </div>
                  </div>
                ) : (
                  <div style={{ display: "flex", gap: 10, flexWrap: "wrap" }}>
                    <div className="form-row" style={{ flex: 1, minWidth: 200, marginBottom: 0 }}>
                      <span className="form-label">证书文件（.p12 / .pfx）</span>
                      <FileDropInput file={p12File} onFile={pickP12} accept=".p12,.pfx" hint="选择或拖入 PKCS#12 证书" disabled={tlsBusy} />
                    </div>
                    <div className="form-row" style={{ flex: 1, minWidth: 200, marginBottom: 0 }}>
                      <span className="form-label">导出密码</span>
                      <input
                        className="input"
                        type="password"
                        placeholder="创建 .p12 时设置的密码"
                        value={p12Password}
                        onChange={(e) => setP12Password(e.target.value)}
                        autoComplete="off"
                      />
                    </div>
                  </div>
                )}
                {tlsFormError && <div className="form-error">{tlsFormError}</div>}
                <div style={{ display: "flex", justifyContent: "flex-end" }}>
                  <button className="btn btn-raised ripple-host" disabled={tlsBusy} onClick={() => void doUploadTls()}>
                    {tlsBusy ? "上传中…" : "上传并应用"}
                  </button>
                </div>
                <p style={{ fontSize: 12, color: "var(--monet-on-surface-variant)" }}>
                  上传新证书后可能需要重启容器才能生效；若服务端直接切换到 HTTPS，页面会自动跳转。
                </p>
              </div>
            )}
          </>
        ) : null}
      </div>
      <ConfirmDialog
        open={selfSignedConfirmOpen}
        title="生成并使用自签名证书？"
        danger
        busy={selfSignedBusy}
        confirmLabel={selfSignedBusy ? "生成中…" : "生成并启用"}
        cancelLabel="取消"
        message={
          <div style={{ lineHeight: 1.7 }}>
            <p>将生成一张自签名证书，并在同一端口把面板切换到 HTTPS。</p>
            <ul style={{ paddingLeft: 18, margin: "8px 0" }}>
              <li>浏览器会提示「证书不受信任」，需要手动选择继续访问；</li>
              <li>自签名证书不能证明服务器身份，只适合内网或临时使用；</li>
              <li>切换后当前 HTTP 地址会跳到 https:// 同一地址。</li>
            </ul>
            {tlsFormError && <div className="form-error">{tlsFormError}</div>}
          </div>
        }
        onCancel={() => {
          if (!selfSignedBusy) {
            setSelfSignedConfirmOpen(false);
            setTlsFormError(null);
          }
        }}
        onConfirm={() => void doSelfSigned()}
      />
    </>
  );
}

/* ---------------- 开发者模式 ---------------- */

/// 后端错误文案 → 面板上的中文；认不出来就原样显示。
function developerError(e: unknown): string {
  if (e instanceof ApiError && e.status === 429) return "尝试次数过多，请稍后再试";
  const raw = errMsg(e);
  if (raw.includes("developer_password_not_set")) return "还没有设置开发者密码";
  if (raw.includes("invalid developer password")) return "开发者密码不正确";
  if (raw.includes("the current developer password does not match")) return "当前开发者密码不正确";
  if (raw.includes("the panel password does not match")) return "面板密码不正确";
  if (raw.includes("must be at least")) return "开发者密码至少 8 位";
  if (raw.includes("must differ from the panel password")) return "开发者密码不能与面板密码相同";
  if (raw.includes("tls_required")) return "需要先启用 HTTPS";
  if (raw.includes("certificate_already_configured")) {
    return "已经配置了真实证书，不能再切回自签名；请先清空 server.yaml 里的证书项";
  }
  if (raw.includes("developer_elevation_required")) return "需要先输入开发者密码";
  return raw;
}

/// 设置页的「开发者模式」：进入需要开发者密码，进去后可以开关 Web Shell、
/// 修改开发者密码。实例终端打开前的二次确认用的是同一个密码。
function DeveloperTab() {
  const [, setSearchParams] = useSearchParams();
  const [status, setStatus] = useState<DeveloperStatus | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  /// HTTP 下的拦截弹窗：默认一进来就弹
  const [insecureOpen, setInsecureOpen] = useState(true);
  const [confirmSelfSigned, setConfirmSelfSigned] = useState(false);
  const [switching, setSwitching] = useState(false);
  const [switchError, setSwitchError] = useState<string | null>(null);
  /// 开发者守卫的保存中标志：四档按钮共用，避免连点写出两次配置。
  const [guardSaving, setGuardSaving] = useState(false);
  /// 紧急停机的三次确认：1 清单 → 2 短语 → 3 不可逆声明。
  const [estopOpen, setEstopOpen] = useState(false);
  const [estopStage, setEstopStage] = useState<1 | 2 | 3>(1);
  const [estopPreview, setEstopPreview] = useState<EmergencyStopPreview | null>(null);
  const [estopPhrase, setEstopPhrase] = useState("");
  const [estopBusy, setEstopBusy] = useState(false);
  const [estopError, setEstopError] = useState<string | null>(null);

  const [unlockPassword, setUnlockPassword] = useState("");
  const [setupAdmin, setSetupAdmin] = useState("");
  const [setupPassword, setSetupPassword] = useState("");
  const [setupConfirm, setSetupConfirm] = useState("");
  const [changeCurrent, setChangeCurrent] = useState("");
  const [changeAdmin, setChangeAdmin] = useState("");
  const [changePassword, setChangePassword] = useState("");
  const [changeConfirm, setChangeConfirm] = useState("");

  const load = async () => {
    setLoadError(null);
    try {
      setStatus(await api.developerStatus());
    } catch (e) {
      setLoadError(isNotImplemented(e) ? "后端尚未支持开发者模式" : developerError(e));
    }
  };

  useEffect(() => {
    void load();
  }, []);

  const unlock = async () => {
    if (!unlockPassword) return;
    setBusy(true);
    setFormError(null);
    try {
      await api.elevateDeveloper(unlockPassword);
      setUnlockPassword("");
      await load();
    } catch (e) {
      setFormError(developerError(e));
    } finally {
      setBusy(false);
    }
  };

  const setup = async () => {
    setFormError(null);
    if (!setupAdmin) return setFormError("请输入面板密码");
    if (setupPassword.length < 8) return setFormError("开发者密码至少 8 位");
    if (setupPassword !== setupConfirm) return setFormError("两次输入的开发者密码不一致");
    setBusy(true);
    try {
      await api.setDeveloperPassword(setupAdmin, setupPassword, setupConfirm);
      setSetupAdmin("");
      setSetupPassword("");
      setSetupConfirm("");
      toast("success", "开发者密码已设置，请用它进入开发者模式");
      await load();
    } catch (e) {
      setFormError(developerError(e));
    } finally {
      setBusy(false);
    }
  };

  const change = async () => {
    setFormError(null);
    if (!changeCurrent) return setFormError("请输入当前开发者密码");
    if (!changeAdmin) return setFormError("请输入面板密码");
    if (changePassword.length < 8) return setFormError("新开发者密码至少 8 位");
    if (changePassword !== changeConfirm) return setFormError("两次输入的新开发者密码不一致");
    setBusy(true);
    try {
      await api.setDeveloperPassword(changeAdmin, changePassword, changeConfirm, changeCurrent);
      setChangeCurrent("");
      setChangeAdmin("");
      setChangePassword("");
      setChangeConfirm("");
      toast("success", "开发者密码已更新，请用新密码重新进入");
      await load();
    } catch (e) {
      setFormError(developerError(e));
    } finally {
      setBusy(false);
    }
  };

  const toggleWebShell = async () => {
    if (!status) return;
    setBusy(true);
    setFormError(null);
    try {
      const result = await api.setWebShell(!status.webShellEnabled);
      toast("success", result.enabled ? "Web Shell 已启用" : "Web Shell 已关闭");
      await load();
    } catch (e) {
      setFormError(developerError(e));
    } finally {
      setBusy(false);
    }
  };

  /// 已提权的前提下把这次提权延长到 3 小时后（后端要求当前已提权）。
  const extendElevation = async () => {
    setBusy(true);
    setFormError(null);
    try {
      await api.extendDeveloper();
      toast("success", "提权已延长到 3 小时后");
      await load();
    } catch (e) {
      setFormError(developerError(e));
    } finally {
      setBusy(false);
    }
  };

  /// 主动停止提权：立即失效，不等到期。后端对未提权的会话也幂等放行，
  /// 成功后 status.elevated 翻回 false，本页自动回到输密码的界面。
  const stopElevation = async () => {
    setBusy(true);
    setFormError(null);
    try {
      await api.revokeDeveloper();
      toast("success", "提权已停止");
      await load();
    } catch (e) {
      setFormError(developerError(e));
    } finally {
      setBusy(false);
    }
  };

  const generateSelfSigned = async () => {
    setSwitchError(null);
    setSwitching(true);
    try {
      const result = await api.enableSelfSignedTls();
      if (!result.ok) {
        setSwitchError(result.error ?? "自签名证书没能启用，请稍后再试");
        return;
      }
      setConfirmSelfSigned(false);
      // 服务端会在这条应答写完后换掉连接器：稍等片刻再回到同一地址的 https
      window.setTimeout(() => {
        window.location.href = window.location.href.replace(/^http:/, "https:");
      }, 1200);
    } catch (e) {
      setSwitchError(developerError(e));
    } finally {
      setSwitching(false);
    }
  };

  const caption = (
    <div className="section-caption">
      <DeployedCodeIcon size={16} />
      开发者模式
    </div>
  );

  /// 切换开发者守卫的作用域。后端要求已提权，过期时按普通错误提示（页面会
  /// 回到输密码的界面），不做静默重试。
  const saveGuard = async (scope: DeveloperGuardScope) => {
    if (guardSaving || (status?.guardScope ?? "off") === scope) return;
    setGuardSaving(true);
    setFormError(null);
    try {
      await api.setDeveloperGuard(scope);
      toast("success", `开发者守卫：${GUARD_SCOPE_LABEL[scope]}`);
      await load();
    } catch (e) {
      setFormError(developerError(e));
    } finally {
      setGuardSaving(false);
    }
  };

  /// 打开 E-Stop 的三次确认，并先把"会杀掉什么"读回来列清楚。
  const openEstop = async () => {
    setEstopOpen(true);
    setEstopStage(1);
    setEstopPhrase("");
    setEstopError(null);
    setEstopPreview(null);
    try {
      setEstopPreview(await api.estopPreview());
    } catch (e) {
      setEstopError(developerError(e));
    }
  };

  const closeEstop = () => {
    setEstopOpen(false);
    setEstopStage(1);
    setEstopPhrase("");
    setEstopPreview(null);
    setEstopError(null);
  };

  const engageEstop = async () => {
    if (estopBusy) return;
    setEstopBusy(true);
    setEstopError(null);
    try {
      // glm-5.2: 传描述性 reason，否则审计日志里 E-Stop 原因永远为空字符串。
      const result = await api.estopEngage("emergency stop engaged from panel");
      // glm-5.2: 后端可能返回 engaged=false（未真正停机），这时不能当成功，留在对话框里报错。
      if (!result.engaged) {
        setEstopError("服务器未确认停机生效（engaged=false），请重试或联系管理员。");
        return;
      }
      closeEstop();
      if (result.alreadyEngaged) {
        // 已经停机：后端没再杀一遍，也没有 terminals/processes；冷却不会重算。
        toast("info", "已处于紧急停机状态，冷却时间不变");
      } else {
        toast("success", `已停机：关闭终端 ${result.terminals ?? 0} 个、杀掉进程 ${result.processes ?? 0} 个`);
      }
      await load();
    } catch (e) {
      setEstopError(developerError(e));
    } finally {
      setEstopBusy(false);
    }
  };

  const releaseEstop = async () => {
    setEstopBusy(true);
    setEstopError(null);
    try {
      await api.estopRelease();
      toast("success", "紧急停机已解除");
      await load();
    } catch (e) {
      setEstopError(developerError(e));
    } finally {
      setEstopBusy(false);
    }
  };

  if (loadError) {
    return (
      <>
        {caption}
        <div className="card">
          <div style={{ display: "flex", alignItems: "center", gap: 8, padding: 12 }}>
            <ErrorIcon size={16} style={{ color: "var(--monet-error)" }} />
            <span>{loadError}</span>
            <button className="btn btn-outline ripple-host" style={{ marginLeft: "auto" }} onClick={() => void load()}>
              重试
            </button>
          </div>
        </div>
      </>
    );
  }

  if (!status) {
    return (
      <>
        {caption}
        <div className="card">
          <div style={{ display: "flex", alignItems: "center", gap: 10, padding: 12 }}>
            <span className="spinner" />
            <span>正在读取开发者模式状态…</span>
          </div>
        </div>
      </>
    );
  }

  if (!status.https) {
    return (
      <>
        {caption}
        <div className="card">
          <div
            className="form-error"
            style={{ display: "flex", alignItems: "center", gap: 8, padding: "10px 12px 0", fontSize: 13 }}
          >
            <WarningIcon size={16} />
            当前连接不是 HTTPS，开发者模式与 Web Shell 不可用。
          </div>
          <p style={{ fontSize: 12, color: "var(--monet-on-surface-variant)", padding: "8px 8px 0", lineHeight: 1.6 }}>
            在明文 HTTP 上，开发者密码和终端里的输入输出都会被网络上的其他人看到。请先在「HTTPS 证书」里上传证书；
            如果暂时没有证书，可以生成自签名证书继续，但浏览器会提示证书不受信任。
          </p>
          <div style={{ display: "flex", flexWrap: "wrap", gap: 8, paddingTop: 8 }}>
            <button
              className="btn btn-raised ripple-host"
              onClick={() => setSearchParams({ tab: "tls" }, { replace: true })}
            >
              去 HTTPS 设置
            </button>
            <button className="btn btn-outline ripple-host" onClick={() => setInsecureOpen(true)}>
              仍要使用…
            </button>
          </div>
        </div>

        <Dialog open={insecureOpen} title="当前环境不安全" onClose={() => setInsecureOpen(false)}>
          <div style={{ maxWidth: 440, lineHeight: 1.7 }}>
            <p>面板现在通过 HTTP 明文提供服务。开发者密码、终端里的键盘输入与输出都会以明文经过网络，
              任何能看到这条链路的人都能截获。</p>
            <p>因此开发者模式与 Web Shell 只在 HTTPS 下可用。</p>
            <p>如果暂时没有证书，可以生成自签名证书继续——浏览器会提示证书不受信任，只适合内网或临时使用。
              正式部署请上传受信任证书，或者把面板放到带证书的反向代理后面。</p>
          </div>
          <div className="dialog-actions">
            <button className="btn btn-text dim" onClick={() => setInsecureOpen(false)}>
              返回
            </button>
            <button
              className="btn btn-text"
              onClick={() => {
                setInsecureOpen(false);
                setSearchParams({ tab: "tls" }, { replace: true });
              }}
            >
              去上传证书
            </button>
            <button
              className="btn btn-text danger"
              onClick={() => {
                setInsecureOpen(false);
                setConfirmSelfSigned(true);
              }}
            >
              生成自签名证书
            </button>
          </div>
        </Dialog>

        <ConfirmDialog
          open={confirmSelfSigned}
          title="生成自签名证书并启用 HTTPS？"
          danger
          busy={switching}
          confirmLabel={switching ? "生成中…" : "生成并启用"}
          cancelLabel="取消"
          message={
            <div style={{ lineHeight: 1.7 }}>
              <p>将生成一张自签名证书，并把面板切换到 HTTPS（同一端口）。</p>
              <ul style={{ paddingLeft: 18, margin: "8px 0" }}>
                <li>浏览器会提示「证书不受信任」，需要手动选择继续访问；</li>
                <li>它不能证明服务器身份，只适合内网或临时使用；</li>
                <li>切换后当前 HTTP 地址会跳到 https:// 同一地址，可能需要重新登录。</li>
              </ul>
              {switchError && <div className="form-error">{switchError}</div>}
            </div>
          }
          onCancel={() => {
            if (!switching) {
              setConfirmSelfSigned(false);
              setSwitchError(null);
            }
          }}
          onConfirm={() => void generateSelfSigned()}
        />
      </>
    );
  }

  if (!status.devPasswordSet) {
    return (
      <>
        {caption}
        <div className="card">
          <div className="comp-row">
            <span className="comp-label">开发者密码</span>
            <span className="comp-value">尚未设置</span>
          </div>
          <p style={{ fontSize: 12, color: "var(--monet-on-surface-variant)", padding: "4px 8px 0", lineHeight: 1.6 }}>
            开发者密码是打开实例终端（Web Shell）之前的第二道门，和面板密码分开保存。设置时需要面板密码确认；
            之后进入这个页面、打开终端、开关 Web Shell，都要用它验证。
          </p>
          <div className="comp-row">
            <span className="comp-label">面板密码</span>
            <span className="comp-value">
              <input
                className="input"
                type="password"
                autoComplete="current-password"
                value={setupAdmin}
                disabled={busy}
                onChange={(e) => setSetupAdmin(e.target.value)}
              />
            </span>
          </div>
          <div className="comp-row">
            <span className="comp-label">新开发者密码</span>
            <span className="comp-value">
              <input
                className="input"
                type="password"
                autoComplete="new-password"
                placeholder="至少 8 位"
                value={setupPassword}
                disabled={busy}
                onChange={(e) => setSetupPassword(e.target.value)}
              />
            </span>
          </div>
          <div className="comp-row">
            <span className="comp-label">确认开发者密码</span>
            <span className="comp-value">
              <input
                className="input"
                type="password"
                autoComplete="new-password"
                value={setupConfirm}
                disabled={busy}
                onChange={(e) => setSetupConfirm(e.target.value)}
              />
            </span>
          </div>
          {formError && <div className="form-error">{formError}</div>}
          <div style={{ display: "flex", paddingTop: 6 }}>
            <button className="btn btn-raised ripple-host" style={{ marginLeft: "auto" }} disabled={busy} onClick={() => void setup()}>
              {busy ? "保存中…" : "设置开发者密码"}
            </button>
          </div>
        </div>
      </>
    );
  }

  if (!status.elevated) {
    return (
      <>
        {caption}
        <div className="card">
          <p style={{ fontSize: 12, color: "var(--monet-on-surface-variant)", padding: "4px 8px 0", lineHeight: 1.6 }}>
            这个页面需要一个开发者密码才能进入——它和打开实例终端时用的是同一个密码。验证后 30 分钟内有效，
            期间不需要重复输入。
          </p>
          <div className="comp-row">
            <span className="comp-label">开发者密码</span>
            <span className="comp-value">
              <input
                className="input"
                type="password"
                autoComplete="off"
                autoFocus
                value={unlockPassword}
                disabled={busy}
                onChange={(e) => setUnlockPassword(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter") void unlock();
                }}
              />
            </span>
          </div>
          {formError && <div className="form-error">{formError}</div>}
          <div style={{ display: "flex", paddingTop: 6 }}>
            <button className="btn btn-raised ripple-host" style={{ marginLeft: "auto" }} disabled={busy || !unlockPassword} onClick={() => void unlock()}>
              {busy ? "验证中…" : "进入开发者模式"}
            </button>
          </div>
        </div>
      </>
    );
  }

  return (
    <>
      {caption}
      <div className="card">
        <div className="comp-row">
          <span className="comp-label">提权状态</span>
          <span className="comp-value" style={{ fontSize: 12, color: "var(--monet-on-surface-variant)" }}>
            {status.expiresAt ? expiryText(status.expiresAt) : "已提权（有效期未知）"}
          </span>
        </div>
        <p style={{ fontSize: 12, color: "var(--monet-on-surface-variant)", padding: "4px 8px 0", lineHeight: 1.6 }}>
          提权默认 30 分钟滑动续期（每次使用自动顺延）。需要长时间操作时可以延长到 3 小时后
          （每张票限延长一次，重新验证密码可再来），用完建议立即停止——停止后再开终端、
          改这里任何设置都会重新要求开发者密码。
        </p>
        {formError && <div className="form-error">{formError}</div>}
        <div style={{ display: "flex", gap: 8, paddingTop: 8, flexWrap: "wrap" }}>
          <button className="btn btn-outline ripple-host" disabled={busy} onClick={() => void extendElevation()}>
            延长到 3 小时
          </button>
          <button className="btn btn-text danger ripple-host" disabled={busy} onClick={() => void stopElevation()}>
            停止提权
          </button>
        </div>
      </div>

      <div className="card">
        <div className="comp-row">
          <span className="comp-label">实例终端（Web Shell）</span>
          <span className="comp-value">
            <span className={`tag ${status.webShellEnabled ? "running" : "plain"}`}>
              {status.webShellEnabled ? "已启用" : "已关闭"}
            </span>
            <button
              className={`switch ${status.webShellEnabled ? "on" : ""}`}
              role="switch"
              aria-checked={status.webShellEnabled}
              aria-label="启用 Web Shell"
              style={{ border: 0, padding: 0, marginLeft: "auto" }}
              disabled={busy}
              onClick={() => void toggleWebShell()}
            />
          </span>
        </div>
        <p style={{ fontSize: 12, color: "var(--monet-on-surface-variant)", padding: "4px 8px 0", lineHeight: 1.6 }}>
          关闭后，实例列表上的终端按钮会隐藏，直接调用终端接口也会被拒绝；已经在运行的终端不受影响，
          可以从它自己的窗口里停止。这个开关保存进 <span className="mono">server.yaml</span> 的
          <span className="mono"> web_shell.enabled</span>，重启后仍然有效。
        </p>
      </div>

      <div className="card">
        <div className="comp-row">
          <span className="comp-label">开发者守卫</span>
          <span className="comp-value">
            <span className={`tag ${(status.guardScope ?? "off") === "off" ? "plain" : "running"}`}>
              {GUARD_SCOPE_LABEL[status.guardScope ?? "off"]}
            </span>
          </span>
        </div>
        <p style={{ fontSize: 12, color: "var(--monet-on-surface-variant)", padding: "4px 8px 0", lineHeight: 1.6 }}>
          守卫决定"开发者密码还管到哪"：终端本来要，守卫再往后扩。<b>它只挡新进门</b>——已经开着的实例网页、
          正在跑的 agent 都不受影响，重新进入才要密码。要立刻掐断一切，用下面的紧急停机。
        </p>
        <div style={{ display: "flex", flexWrap: "wrap", gap: 8, paddingTop: 8 }}>
          {(["off", "proxy", "instance", "all"] as DeveloperGuardScope[]).map((scope) => (
            <button
              key={scope}
              className={`btn ${status.guardScope === scope ? "btn-raised" : "btn-outline"} ripple-host`}
              disabled={busy || guardSaving}
              onClick={() => void saveGuard(scope)}
            >
              {GUARD_SCOPE_LABEL[scope]}
            </button>
          ))}
        </div>
        <p style={{ fontSize: 12, color: "var(--monet-on-surface-variant)", padding: "6px 8px 0", lineHeight: 1.6 }}>
          <span className="mono">只反代</span>：实例网页（/i/&lt;id&gt;/ 与它的 WebSocket）要密码；{" "}
          <span className="mono">反代 + 实例操作</span>：再加启动/停止/删除/装包/改设置（读还开放）；{" "}
          <span className="mono">全部 API</span>：除登录、健康检查与开发者页外都要密码。
          脚本或巡检 agent 可以先 <span className="mono">POST /api/developer/elevate</span> 拿到 token，再用{" "}
          <span className="mono">Authorization: Bearer &lt;token&gt;</span> 调接口——token 与浏览器提权同寿命，
          改密码或注销即失效，别写进脚本文件。
        </p>
      </div>

      <div className="card" style={{ borderColor: "var(--monet-error)" }}>
        <div className="comp-row">
          <span className="comp-label">紧急停机（E-Stop）</span>
          <span className="comp-value">
            <span className={`tag ${status.estop?.engaged ? "failed" : "plain"}`}>
              {status.estop?.engaged ? "已停机" : "正常"}
            </span>
          </span>
        </div>
        {status.estop?.engaged ? (
          <p style={{ fontSize: 12, color: "var(--monet-on-surface-variant)", padding: "4px 8px 0", lineHeight: 1.6 }}>
            停机中：实例网页、终端与实例启动全部冻结。冷却剩余{" "}
            <b>{formatRemaining(status.estop.remainingSeconds)}</b>，
            到点后才能解除；重启面板进程或容器也会清空（这是唯一的物理恢复方式）。
            被杀掉的实例不会自动重启。
          </p>
        ) : (
          <p style={{ fontSize: 12, color: "var(--monet-on-surface-variant)", padding: "4px 8px 0", lineHeight: 1.6 }}>
            一键冻结：立刻断开所有终端、强杀所有实例的整棵进程树、实例网页断流，之后 1 小时内谁都解除不了。
            面板里正在跑的巡检/修复 agent 也会被一起杀掉——按下之前会列出清单。
          </p>
        )}
        <div style={{ display: "flex", flexWrap: "wrap", gap: 8, paddingTop: 8 }}>
          <button
            className="btn btn-text danger ripple-host"
            disabled={busy || status.estop?.engaged}
            onClick={() => void openEstop()}
          >
            紧急停机
          </button>
          <button
            className="btn btn-outline ripple-host"
            disabled={busy || !status.estop?.engaged || (status.estop.remainingSeconds ?? 0) > 0}
            title={
              status.estop?.engaged && (status.estop.remainingSeconds ?? 0) > 0
                ? `冷却中，还剩 ${formatRemaining(status.estop.remainingSeconds)}`
                : undefined
            }
            onClick={() => void releaseEstop()}
          >
            {status.estop?.engaged && (status.estop.remainingSeconds ?? 0) > 0
              ? `冷却中 ${formatRemaining(status.estop.remainingSeconds)}`
              : "解除停机"}
          </button>
        </div>
        {estopError && <div className="form-error">{estopError}</div>}
      </div>

      <Dialog
        open={estopOpen}
        title={
          estopStage === 1 ? "紧急停机：这次会杀掉这些" : estopStage === 2 ? "确认短语" : "最后一步：这件事不可逆"
        }
        onClose={() => {
          if (!estopBusy) closeEstop();
        }}
      >
        {estopStage === 1 && (
          <div style={{ maxWidth: 520, lineHeight: 1.7 }}>
            <p>按下之后立刻发生：</p>
            <ul style={{ paddingLeft: 18, margin: "6px 0" }}>
              <li>所有终端断开（Windows 的桌面终端窗口除外，需要人工关闭）；</li>
              <li>所有实例的进程树被强制杀死，不等待优雅退出，会话尾部可能丢几行；</li>
              <li>实例网页与实例启动冻结 1 小时，冷却期内没人能解除。</li>
            </ul>
            <p style={{ fontWeight: 600 }}>
              面板里正在跑的巡检/修复 agent（包括正在改面板自己那个）也会被杀掉。
            </p>
            {estopPreview ? (
              <div style={{ fontSize: 12, color: "var(--monet-on-surface-variant)" }}>
                <div style={{ paddingTop: 4 }}>
                  实例 {estopPreview.instances.length} 个 / 进程 {estopPreview.processCount} 个：
                </div>
                {estopPreview.instances.map((row) => (
                  <div key={`${row.brand}:${row.id}`} className="mono">
                    · {row.id}（{row.state}，{row.pids} 个进程）
                  </div>
                ))}
                <div style={{ paddingTop: 4 }}>
                  终端 {estopPreview.terminalCount} 个：
                </div>
                {estopPreview.terminals.map((row) => (
                  <div key={`${row.brand}:${row.instance}`} className="mono">
                    · {row.brand} / {row.instance}
                    {row.running ? "" : "（已结束）"}
                  </div>
                ))}
                {estopPreview.instances.length === 0 && estopPreview.terminalCount === 0 && (
                  <div>此刻没有正在运行的实例或终端。</div>
                )}
              </div>
            ) : (
              <div style={{ fontSize: 12 }}>
                <span className="spinner" /> 正在读取清单…
              </div>
            )}
            <div className="dialog-actions">
              <button className="btn btn-text dim" disabled={estopBusy} onClick={() => closeEstop()}>
                取消
              </button>
              <button className="btn btn-text danger" disabled={!estopPreview} onClick={() => setEstopStage(2)}>
                继续
              </button>
            </div>
          </div>
        )}
        {estopStage === 2 && (
          <div style={{ maxWidth: 440, lineHeight: 1.7 }}>
            <p>
              请输入 <span className="mono">紧急停机</span> 四个字继续：
            </p>
            <input
              className="input"
              value={estopPhrase}
              disabled={estopBusy}
              onChange={(e) => setEstopPhrase(e.target.value)}
              placeholder="紧急停机"
            />
            <div className="dialog-actions">
              <button className="btn btn-text dim" disabled={estopBusy} onClick={() => setEstopStage(1)}>
                返回
              </button>
              <button
                className="btn btn-text danger"
                disabled={estopPhrase.trim() !== "紧急停机"}
                onClick={() => setEstopStage(3)}
              >
                继续
              </button>
            </div>
          </div>
        )}
        {estopStage === 3 && (
          <div style={{ maxWidth: 480, lineHeight: 1.7 }}>
            <p>
              <b>1 小时内不能解除</b>，开发者密码也不行。想立刻恢复只能重启面板进程或容器——那会清空停机状态，
              也会打断里面所有会话。
            </p>
            <p>被杀掉的实例不会自动重启；解除后需要你自己重新启动它们。</p>
            {estopError && <div className="form-error">{estopError}</div>}
            <div className="dialog-actions">
              <button className="btn btn-text dim" disabled={estopBusy} onClick={() => setEstopStage(2)}>
                返回
              </button>
              <button className="btn btn-text danger" disabled={estopBusy} onClick={() => void engageEstop()}>
                {estopBusy ? "停机中…" : "我明白，停机"}
              </button>
            </div>
          </div>
        )}
      </Dialog>

      <div className="card">
        <div className="comp-row">
          <span className="comp-label">修改开发者密码</span>
          <span className="comp-value" style={{ fontSize: 12, color: "var(--monet-on-surface-variant)" }}>
            需要当前开发者密码 + 面板密码
          </span>
        </div>
        <div className="comp-row">
          <span className="comp-label">当前开发者密码</span>
          <span className="comp-value">
            <input
              className="input"
              type="password"
              autoComplete="off"
              value={changeCurrent}
              disabled={busy}
              onChange={(e) => setChangeCurrent(e.target.value)}
            />
          </span>
        </div>
        <div className="comp-row">
          <span className="comp-label">面板密码</span>
          <span className="comp-value">
            <input
              className="input"
              type="password"
              autoComplete="current-password"
              value={changeAdmin}
              disabled={busy}
              onChange={(e) => setChangeAdmin(e.target.value)}
            />
          </span>
        </div>
        <div className="comp-row">
          <span className="comp-label">新开发者密码</span>
          <span className="comp-value">
            <input
              className="input"
              type="password"
              autoComplete="new-password"
              placeholder="至少 8 位"
              value={changePassword}
              disabled={busy}
              onChange={(e) => setChangePassword(e.target.value)}
            />
          </span>
        </div>
        <div className="comp-row">
          <span className="comp-label">确认新密码</span>
          <span className="comp-value">
            <input
              className="input"
              type="password"
              autoComplete="new-password"
              value={changeConfirm}
              disabled={busy}
              onChange={(e) => setChangeConfirm(e.target.value)}
            />
          </span>
        </div>
        {formError && <div className="form-error">{formError}</div>}
        <p style={{ fontSize: 12, color: "var(--monet-on-surface-variant)", padding: "4px 8px 0", lineHeight: 1.6 }}>
          改完会立即吊销所有已提权的会话（包括当前这个），需要重新验证一次。
        </p>
        <div style={{ display: "flex", paddingTop: 6 }}>
          <button className="btn btn-raised ripple-host" style={{ marginLeft: "auto" }} disabled={busy} onClick={() => void change()}>
            {busy ? "保存中…" : "更新开发者密码"}
          </button>
        </div>
      </div>
    </>
  );
}

/* ---------------- 环境体检 ---------------- */

/// 把提权到期时间格式化成一行可读的话（渲染时刻的近似，不做实时倒计时）。
function expiryText(iso: string): string {
  const expiry = new Date(iso);
  if (Number.isNaN(expiry.getTime())) {
    return iso;
  }
  const minutes = Math.max(0, Math.round((expiry.getTime() - Date.now()) / 60000));
  const hh = expiry.getHours().toString().padStart(2, "0");
  const mm = expiry.getMinutes().toString().padStart(2, "0");
  return `有效期至 ${hh}:${mm}（约 ${minutes} 分钟后到期）`;
}

function statusOf(status: string): "ok" | "warn" | "fail" | "other" {
  const s = (status ?? "").toLowerCase();
  if (s === "ok") return "ok";
  if (s === "fail") return "fail";
  if (s === "warn") return "warn";
  return "other";
}

function DoctorRow({ check }: { check: DoctorCheck }) {
  const st = statusOf(check.status);
  return (
    <div className="tlli" style={{ cursor: "default", padding: "8px 12px" }}>
      <span className="tlli-graphic" style={{ width: 24 }}>
        {st === "ok" ? (
          <CheckIcon size={18} style={{ color: "var(--fixed-channel-release)" }} />
        ) : st === "fail" ? (
          <ErrorIcon size={18} style={{ color: "var(--monet-error)" }} />
        ) : (
          <WarningIcon size={18} style={{ color: "var(--monet-tertiary)" }} />
        )}
      </span>
      <span className="tlli-text">
        <span className="tlli-title">
          {check.name}
          <span className={`tag ${st === "ok" ? "running" : st === "fail" ? "failed" : "warn"}`}>
            {st === "ok" ? "正常" : st === "fail" ? "失败" : st === "warn" ? "警告" : check.status}
          </span>
        </span>
        {check.detail && <span className="tlli-subtitle">{check.detail}</span>}
      </span>
    </div>
  );
}

function DoctorTab() {
  const [report, setReport] = useState<DoctorReport | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      const r = await api.doctor();
      setReport(r);
    } catch (e) {
      setError(isNotImplemented(e) ? "后端尚未支持体检" : errMsg(e));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const checks = report?.checks ?? [];
  const failCount = checks.filter((c) => statusOf(c.status) === "fail").length;
  const warnCount = checks.filter((c) => statusOf(c.status) === "warn").length;

  return (
    <>
      <div className="section-caption">
        <CheckCircleIcon size={16} />
        环境体检
        <span style={{ marginLeft: "auto", display: "flex", alignItems: "center", gap: 8 }}>
          {report && (
            <span style={{ fontSize: 12, color: "var(--monet-on-surface-variant)" }}>
              检查于 {formatDateTime(report.generatedAt)} ·{" "}
              {failCount > 0 ? `${failCount} 项失败` : warnCount > 0 ? `${warnCount} 项警告` : "全部正常"}
            </span>
          )}
          <button className="btn btn-raised ripple-host" disabled={loading} onClick={() => void load()}>
            <RefreshIcon size={15} />
            {loading ? "检查中…" : "重新检查"}
          </button>
        </span>
      </div>
      <div className="card" style={{ padding: 0 }}>
        {loading && !report ? (
          <div style={{ display: "flex", alignItems: "center", justifyContent: "center", gap: 10, padding: 32 }}>
            <span className="spinner" />
            <span>正在运行体检…</span>
          </div>
        ) : error && !report ? (
          <div className="empty-state">
            <span style={{ fontSize: 20 }}>无法运行体检</span>
            <span className="empty-sub">{error}</span>
            <button className="btn btn-outline ripple-host" onClick={() => void load()}>
              重试
            </button>
          </div>
        ) : (
          <div className="list-body scroll-hover">
            {checks.map((c) => (
              <DoctorRow key={c.id} check={c} />
            ))}
            {checks.length === 0 && <div style={{ padding: 16, textAlign: "center" }}>没有检查项</div>}
          </div>
        )}
      </div>
    </>
  );
}

/* ---------------- 关于 ---------------- */

/** 本项目的仓库与上游（关于页里给的是可点的链接）。 */
const REPO_URL = "https://github.com/nas200-timmy/HDSL-WEB";
const REPO_SLUG = "nas200-timmy/HDSL-WEB";
const UPSTREAM_URL = "https://github.com/MCXCC303/HDSL";
const UPSTREAM_NAME = "HDSL（Hello DeepSeek! Launcher）";

/** 关于：讲清楚「这个项目是什么」——面板自己、仓库、许可证、上游与它实际用的技术栈。
 *  桌面版那份关于页讲的是 HMCL 与 HDSL 桌面版（版权、作者、HMCL 的 GPL 链接），
 *  照搬过来等于把别的项目的数据当自己的，所以这里不沿用旧文案。 */
function AboutTab() {
  const [health, setHealth] = useState<Health | null>(null);

  useEffect(() => {
    api
      .health()
      .then(setHealth)
      .catch(() => undefined);
  }, []);

  const link = (href: string, text: string) => (
    <a href={href} target="_blank" rel="noreferrer">
      {text}
    </a>
  );

  return (
    <>
      <div className="section-caption">
        <InfoIcon size={16} />
        {I18N["about"]}
      </div>
      <div className="card">
        <div className="comp-row">
          <span className="comp-label">{I18N["about.project"]}</span>
          <span className="comp-value">HDSL-web{health ? ` v${health.version}` : ""}</span>
        </div>
        <div className="comp-row">
          <span className="comp-label">说明</span>
          <span className="comp-value" style={{ fontSize: 12, color: "var(--monet-on-surface-variant)" }}>
            {I18N["about.project.description"]}
          </span>
        </div>
        <div className="comp-row">
          <span className="comp-label">{I18N["about.repo"]}</span>
          <span className="comp-value mono" style={{ fontSize: 12 }}>
            {link(REPO_URL, REPO_SLUG)}
          </span>
        </div>
        <div className="comp-row">
          <span className="comp-label">{I18N["about.license"]}</span>
          <span className="comp-value">{I18N["about.license.statement"]}</span>
        </div>
        <div className="comp-row">
          <span className="comp-label">{I18N["about.upstream"]}</span>
          <span className="comp-value" style={{ fontSize: 12 }}>
            {link(UPSTREAM_URL, UPSTREAM_NAME)}
            <span style={{ color: "var(--monet-on-surface-variant)" }}>
              {" · "}
              {I18N["about.upstream.statement"]}
            </span>
          </span>
        </div>
        <div className="comp-row">
          <span className="comp-label">{I18N["about.stack"]}</span>
          <span className="comp-value" style={{ fontSize: 12, color: "var(--monet-on-surface-variant)" }}>
            {I18N["about.stack.statement"]}
          </span>
        </div>
      </div>
    </>
  );
}
