import { useEffect, useReducer, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { api, ApiError, isNotImplemented } from "../api";
import { ConfirmDialog, Dialog } from "../components/Dialog";
import { FileDropInput } from "../components/FileDropInput";
import {
  CheckCircleIcon,
  CheckIcon,
  ContentCopyIcon,
  DeployedCodeFillIcon,
  DeployedCodeIcon,
  DownloadIcon,
  ErrorIcon,
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
  DoctorCheck,
  DoctorReport,
  Health,
  RegistrySettings,
  RegistryTestResult,
  SshGeneratedKeypair,
  SshKeyEntry,
  SshStatus,
  TlsSettings,
  TlsUploadResult,
} from "../types";
import { errMsg, formatDateTime, formatUptime, registryError } from "../utils";

type SettingsTab =
  | "general"
  | "node"
  | "launcher-general"
  | "appearance"
  | "download-source"
  | "tls"
  | "ssh"
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
      "tls",
      "ssh",
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
                key: "tls",
                title: "HTTPS 证书",
                icon: <KeepIcon size={20} />,
              },
              {
                key: "ssh",
                title: "SSH 终端",
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
        {tab === "tls" && <TlsTab />}
        {tab === "ssh" && <SshTab />}
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
    </>
  );
}

/* ---------------- SSH 终端通道 ---------------- */

/** 粘贴公钥的逐行校验：算法白名单与 AuthorizedKeys.ALLOWED_ALGORITHMS 对齐 */
const SSH_PUBKEY_LINE =
  /^(ssh-ed25519|ssh-rsa|ecdsa-sha2-nistp256|ecdsa-sha2-nistp384|ecdsa-sha2-nistp521)\s+[A-Za-z0-9+/]+={0,2}(\s+\S.*)?$/;

/** 纯 HTTP 下敏感操作的门：后端会 409，前端直接禁用并说清原因 */
const SSH_HTTPS_GATE = "需要 HTTPS（先在 TLS 设置页启用）";

/** 复制到剪贴板：HTTPS 下走 Clipboard API，纯 HTTP（非安全上下文）退到 execCommand */
async function copyText(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    try {
      const ta = document.createElement("textarea");
      ta.value = text;
      ta.style.position = "fixed";
      ta.style.opacity = "0";
      document.body.appendChild(ta);
      ta.select();
      const ok = document.execCommand("copy");
      ta.remove();
      return ok;
    } catch {
      return false;
    }
  }
}

function SshTab() {
  const [status, setStatus] = useState<SshStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [keys, setKeys] = useState<SshKeyEntry[] | null>(null);
  const [keysError, setKeysError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const [elevateOpen, setElevateOpen] = useState(false);
  const [devPwOpen, setDevPwOpen] = useState(false);
  const [pasteOpen, setPasteOpen] = useState(false);
  const [generated, setGenerated] = useState<SshGeneratedKeypair | null>(null);
  const [pwAuthWarning, setPwAuthWarning] = useState<string | null>(null);
  const [confirmRemove, setConfirmRemove] = useState<SshKeyEntry | null>(null);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      setStatus(await api.sshStatus());
    } catch (e) {
      setError(isNotImplemented(e) ? "后端尚未支持 SSH 终端通道" : errMsg(e));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const loadKeys = async () => {
    try {
      setKeys((await api.sshKeys()).keys);
      setKeysError(null);
    } catch (e) {
      if (e instanceof ApiError && e.status === 403) {
        // 提权已过期：主卡切回锁态，由用户重新进入
        setKeys(null);
        void load();
      } else {
        setKeysError(errMsg(e));
      }
    }
  };

  useEffect(() => {
    if (status?.elevated) void loadKeys();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [status?.elevated]);

  /** 敏感操作撞上 403（提权过期）：提示重新进入并刷新状态；返回 true 表示已处理 */
  const onForbidden = (e: unknown): boolean => {
    if (e instanceof ApiError && e.status === 403) {
      toast("info", "开发者模式已失效，请重新进入");
      void load();
      return true;
    }
    return false;
  };

  const doToggle = async (enabled: boolean) => {
    setBusy(true);
    try {
      const r = await api.sshToggle(enabled);
      setStatus((s) => (s ? { ...s, enabled: r.enabled, running: r.running, port: r.port } : s));
      toast("success", r.enabled ? "SSH 终端通道已启用" : "SSH 终端通道已关闭");
      if (r.enabled) {
        // 首次启用后回读一次 status：拿 host key fingerprint 供用户核对
        setStatus(await api.sshStatus());
      }
    } catch (e) {
      if (!onForbidden(e)) toast("error", `切换失败：${errMsg(e)}`);
    } finally {
      setBusy(false);
    }
  };

  const doPasswordAuth = async (enabled: boolean) => {
    setBusy(true);
    setPwAuthWarning(null);
    try {
      const r = await api.sshPasswordAuth(enabled);
      setStatus((s) => (s ? { ...s, passwordAuth: r.passwordAuth } : s));
      // 后端在开启时下发的爆破风险警告，原样亮出
      if (r.warning) setPwAuthWarning(r.warning);
      else toast("success", "已关闭口令登录，仅公钥可登录");
    } catch (e) {
      if (!onForbidden(e)) toast("error", `设置失败：${errMsg(e)}`);
    } finally {
      setBusy(false);
    }
  };

  const doGenerate = async () => {
    setBusy(true);
    try {
      const r = await api.sshGenerateKeypair();
      setGenerated(r);
      void loadKeys();
    } catch (e) {
      if (!onForbidden(e)) toast("error", `生成密钥对失败：${errMsg(e)}`);
    } finally {
      setBusy(false);
    }
  };

  const doRemoveKey = async () => {
    const k = confirmRemove;
    if (!k) return;
    setBusy(true);
    try {
      await api.sshRemoveKey(k.index);
      toast("success", `已删除公钥（${k.comment || k.fingerprintSHA256}）`);
      setConfirmRemove(null);
      void loadKeys();
    } catch (e) {
      if (!onForbidden(e)) toast("error", `删除失败：${errMsg(e)}`);
    } finally {
      setBusy(false);
    }
  };

  const doCopyPrivateKey = async () => {
    if (!generated) return;
    const ok = await copyText(generated.privateKey);
    toast(ok ? "success" : "error", ok ? "私钥已复制到剪贴板" : "复制失败，请手动全选复制");
  };

  const doDownloadPrivateKey = () => {
    if (!generated) return;
    const blob = new Blob([generated.privateKey], { type: "application/x-pem-file" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = "hdsl-ssh-key.pem";
    a.click();
    URL.revokeObjectURL(url);
  };

  const unverifiedBadge = status?.verdict === "supported_unverified" && (
    <span className="tag warn" style={{ marginLeft: 6 }}>未实测</span>
  );

  return (
    <>
      <div className="section-caption">
        <DeployedCodeIcon size={16} />
        SSH 终端
        <span style={{ marginLeft: "auto", display: "flex", alignItems: "center", gap: 8 }}>
          {status && (
            <span style={{ fontSize: 12, color: "var(--monet-on-surface-variant)" }}>
              {status.running
                ? `监听 ${status.bind}:${status.port} · ${status.connections} 个连接`
                : status.enabled
                  ? "已启用 · 未运行"
                  : "已关闭"}
            </span>
          )}
          <button className="btn btn-text ripple-host" disabled={loading} onClick={() => void load()}>
            <RefreshIcon size={15} />
            {I18N["button.refresh"]}
          </button>
        </span>
      </div>

      {loading && !status ? (
        <div className="card">
          <div style={{ display: "flex", alignItems: "center", gap: 10, padding: 12 }}>
            <span className="spinner" />
            <span>正在获取 SSH 终端状态…</span>
          </div>
        </div>
      ) : error && !status ? (
        <div className="card">
          <div style={{ display: "flex", alignItems: "center", gap: 8, padding: 12 }}>
            <ErrorIcon size={16} style={{ color: "var(--monet-error)" }} />
            <span>{error}</span>
            <button className="btn btn-outline ripple-host" style={{ marginLeft: "auto" }} onClick={() => void load()}>
              重试
            </button>
          </div>
        </div>
      ) : status ? (
        <>
          {!status.supported ? (
            /* 平台封禁：整卡禁用 + 原因（PlatformGuard：仅 Linux 完整支持，macOS 标未实测放行，Windows 等硬封） */
            <div className="card" style={{ opacity: 0.6 }}>
              <div className="comp-row">
                <span className="comp-label">状态</span>
                <span className="comp-value">
                  <span className="tag failed">不支持</span>
                </span>
              </div>
              <div className="hint warning" style={{ margin: 0 }}>
                <WarningIcon size={16} style={{ flex: "none", marginTop: 2 }} />
                <span>当前平台不支持 SSH 终端通道。仅 Linux 完整支持，macOS 放行但未实测；Windows 及其它平台已被硬性封禁（即使配置文件里打开了开关也不会监听）。</span>
              </div>
            </div>
          ) : !status.devPasswordSet ? (
            /* 未设置开发者密码：引导先设置（设置/修改只在 HTTPS 下可用） */
            <div className="card">
              <div className="comp-row">
                <span className="comp-label">开发者密码</span>
                <span className="comp-value">
                  <span className="tag plain">未设置</span>
                </span>
              </div>
              <p style={{ fontSize: 12, color: "var(--monet-on-surface-variant)", padding: "4px 8px 0", lineHeight: 1.6 }}>
                管理 SSH 终端通道（开关、公钥、口令登录）前，需要先设置开发者密码；设置时还会要求输入面板口令以确认是本人。开发者密码同样用于 SSH 登录后的 TUI 门禁。
              </p>
              {!status.https && <div className="form-error" style={{ margin: "4px 8px 0" }}>{SSH_HTTPS_GATE}</div>}
              <div style={{ display: "flex", justifyContent: "flex-end", paddingTop: 6 }}>
                <button
                  className="btn btn-raised ripple-host"
                  disabled={!status.https}
                  onClick={() => setDevPwOpen(true)}
                >
                  设置开发者密码
                </button>
              </div>
            </div>
          ) : !status.elevated ? (
            /* 已设置密码、未提权：锁态 */
            <div className="card">
              <div className="comp-row">
                <span className="comp-label">开发者模式</span>
                <span className="comp-value">
                  <span className="tag plain">未进入</span>
                  {unverifiedBadge}
                </span>
              </div>
              <p style={{ fontSize: 12, color: "var(--monet-on-surface-variant)", padding: "4px 8px 0", lineHeight: 1.6 }}>
                开关、公钥与口令登录等敏感操作需要进入开发者模式：输入开发者密码后提权 15 分钟，期间每次操作自动续期；15 分钟无操作自动失效，需重新进入。
              </p>
              {!status.https && <div className="form-error" style={{ margin: "4px 8px 0" }}>{SSH_HTTPS_GATE}</div>}
              <div style={{ display: "flex", justifyContent: "flex-end", paddingTop: 6 }}>
                <button
                  className="btn btn-raised ripple-host"
                  disabled={!status.https}
                  onClick={() => setElevateOpen(true)}
                >
                  进入开发者模式
                </button>
              </div>
            </div>
          ) : (
            /* 提权后的主界面 */
            <>
              <div className="card">
                <div className="comp-row">
                  <span className="comp-label">启用 SSH 终端通道</span>
                  <span className="comp-value">
                    <button
                      className={`switch${status.enabled ? " on" : ""}`}
                      role="switch"
                      aria-checked={status.enabled}
                      aria-label="启用 SSH 终端通道"
                      disabled={busy || !status.https}
                      onClick={() => void doToggle(!status.enabled)}
                    />
                    <span className={`tag ${status.running ? "running" : "plain"}`}>
                      {status.running ? "运行中" : status.enabled ? "已启用" : "已关闭"}
                    </span>
                    {unverifiedBadge}
                  </span>
                </div>
                {!status.https && <div className="form-error" style={{ margin: "0 8px" }}>{SSH_HTTPS_GATE}</div>}
                <div className="comp-row">
                  <span className="comp-label">绑定地址</span>
                  <span className="comp-value mono">{status.bind}</span>
                </div>
                <div className="comp-row">
                  <span className="comp-label">端口</span>
                  <span className="comp-value">{status.running ? status.port : "—"}</span>
                </div>
                <div className="comp-row">
                  <span className="comp-label">当前连接</span>
                  <span className="comp-value">{status.connections}</span>
                </div>
                {status.running && status.hostKeyFingerprint && (
                  <div className="comp-row">
                    <span className="comp-label">Host Key 指纹</span>
                    <span className="comp-value mono" style={{ fontSize: 11, wordBreak: "break-all" }}>
                      {status.hostKeyFingerprint}
                    </span>
                  </div>
                )}
                {status.running && status.hostKeyFingerprint && (
                  <p style={{ fontSize: 12, color: "var(--monet-on-surface-variant)", padding: "0 8px", lineHeight: 1.6 }}>
                    首次从客户端连接时请核对以上指纹；指纹不一致说明可能被中间人截获。
                  </p>
                )}
                <div className="comp-row">
                  <span className="comp-label">允许口令登录</span>
                  <span className="comp-value">
                    <button
                      className={`switch${status.passwordAuth ? " on" : ""}`}
                      role="switch"
                      aria-checked={status.passwordAuth}
                      aria-label="允许口令登录"
                      disabled={busy || !status.https}
                      onClick={() => void doPasswordAuth(!status.passwordAuth)}
                    />
                  </span>
                </div>
                <p style={{ fontSize: 12, color: "var(--monet-on-surface-variant)", padding: "4px 8px 0", lineHeight: 1.6 }}>
                  默认仅允许公钥登录。启用口令登录后，面板口令可直接用于 SSH 登录，爆破面增大；请确认网络层有额外保护（回环/内网/防火墙）。
                </p>
                {pwAuthWarning && (
                  <div className="hint warning" style={{ margin: "4px 8px 0" }}>
                    <WarningIcon size={16} style={{ flex: "none", marginTop: 2 }} />
                    <span>{pwAuthWarning}</span>
                  </div>
                )}
              </div>

              <div className="section-caption">
                <DeployedCodeFillIcon size={16} />
                公钥管理
              </div>
              <div className="card" style={{ padding: 0 }}>
                {keys === null && !keysError ? (
                  <div style={{ display: "flex", alignItems: "center", gap: 10, padding: 12 }}>
                    <span className="spinner" />
                    <span>正在读取公钥列表…</span>
                  </div>
                ) : keysError && keys === null ? (
                  <div style={{ display: "flex", alignItems: "center", gap: 8, padding: 12 }}>
                    <ErrorIcon size={16} style={{ color: "var(--monet-error)" }} />
                    <span>读取公钥列表失败：{keysError}</span>
                    <button className="btn btn-outline ripple-host" style={{ marginLeft: "auto" }} onClick={() => void loadKeys()}>
                      重试
                    </button>
                  </div>
                ) : keys && keys.length === 0 ? (
                  <div style={{ padding: 16, textAlign: "center", color: "var(--monet-on-surface-variant)", fontSize: 13 }}>
                    还没有授权的公钥。粘贴自己的 OpenSSH 公钥，或让面板生成一对。
                  </div>
                ) : (
                  <div className="list-body scroll-hover">
                    {keys!.map((k) => (
                      <div key={k.index} className="tlli" style={{ cursor: "default", padding: "8px 12px" }}>
                        <span className="tlli-graphic" style={{ width: 24 }}>
                          <DeployedCodeIcon size={18} style={{ color: "var(--monet-on-surface-variant)" }} />
                        </span>
                        <span className="tlli-text">
                          <span className="tlli-title">
                            <span className="tag plain">{k.algorithm}</span>
                            {k.comment && <span style={{ fontSize: 13 }}>{k.comment}</span>}
                          </span>
                          <span className="tlli-subtitle mono">{k.fingerprintSHA256}</span>
                        </span>
                        <button
                          className="btn btn-text danger ripple-host"
                          style={{ height: 28 }}
                          disabled={busy}
                          onClick={() => setConfirmRemove(k)}
                        >
                          {I18N["button.delete"]}
                        </button>
                      </div>
                    ))}
                  </div>
                )}
              </div>
              <div className="toolbar-row" style={{ padding: "4px 0", flexWrap: "wrap" }}>
                <button className="btn btn-outline ripple-host" disabled={busy || !status.https} onClick={() => setPasteOpen(true)}>
                  粘贴公钥
                </button>
                <button className="btn btn-outline ripple-host" disabled={busy || !status.https} onClick={() => void doGenerate()}>
                  {busy ? "处理中…" : "生成密钥对"}
                </button>
                {!status.https && (
                  <span className="form-error" style={{ margin: 0 }}>{SSH_HTTPS_GATE}</span>
                )}
              </div>
            </>
          )}
        </>
      ) : null}

      <SshElevateDialog
        open={elevateOpen}
        onClose={() => setElevateOpen(false)}
        onForbidden={onForbidden}
        onElevated={() => void load()}
      />
      <SshDevPasswordDialog
        open={devPwOpen}
        changing={status?.devPasswordSet ?? false}
        onClose={() => setDevPwOpen(false)}
        onDone={() => void load()}
      />
      <SshPasteKeyDialog
        open={pasteOpen}
        onClose={() => setPasteOpen(false)}
        onForbidden={onForbidden}
        onAdded={() => void loadKeys()}
      />

      {/* 生成的密钥对：私钥只显示这一次，关闭后无法找回 */}
      <Dialog open={!!generated} title="密钥对已生成" wide onClose={() => setGenerated(null)}>
        {generated && (
          <>
            <div className="hint error" style={{ marginBottom: 10 }}>
              <WarningIcon size={16} style={{ flex: "none", marginTop: 2 }} />
              <span>私钥只显示这一次，关闭后无法找回。请立即复制或下载保存；面板不保存私钥。</span>
            </div>
            <div className="form-row">
              <span className="form-label">公钥（已自动加入授权列表）</span>
              <textarea className="input mono" style={{ fontWeight: 400 }} readOnly rows={3} value={generated.publicKey} />
            </div>
            <div className="form-row">
              <span className="form-label">私钥</span>
              <textarea className="input mono" style={{ fontWeight: 400 }} readOnly rows={10} value={generated.privateKey} />
            </div>
            <p style={{ fontSize: 12, color: "var(--monet-on-surface-variant)" }}>
              指纹 {generated.fingerprint} · 格式 {generated.format === "pkcs8" ? "PKCS#8" : "OpenSSH PEM"}（ssh -i 可直接使用）
            </p>
            <div className="dialog-actions">
              <button className="btn btn-text ripple-host" onClick={() => void doCopyPrivateKey()}>
                <ContentCopyIcon size={15} />
                复制私钥
              </button>
              <button className="btn btn-text ripple-host" onClick={() => doDownloadPrivateKey()}>
                <DownloadIcon size={15} />
                下载 .pem
              </button>
              <button className="btn btn-text ripple-host" onClick={() => setGenerated(null)}>
                我已保存，关闭
              </button>
            </div>
          </>
        )}
      </Dialog>

      <ConfirmDialog
        open={!!confirmRemove}
        title="删除公钥"
        message={`确定删除这条公钥（${confirmRemove?.comment || confirmRemove?.fingerprintSHA256 || ""}）吗？对应客户端将无法再通过它登录。`}
        confirmLabel={I18N["button.delete"]}
        danger
        busy={busy}
        onConfirm={() => void doRemoveKey()}
        onCancel={() => setConfirmRemove(null)}
      />
    </>
  );
}

/** 进入开发者模式：输开发者密码提权。401 = 密码错误、429 = 试错锁定，都是业务失败，
 *  后端 error 消息直接显示在对话框里（401 已在 api 层跳过全局登出）。 */
function SshElevateDialog({
  open,
  onClose,
  onForbidden,
  onElevated,
}: {
  open: boolean;
  onClose: () => void;
  onForbidden: (e: unknown) => boolean;
  onElevated: () => void;
}) {
  const [password, setPassword] = useState("");
  const [formError, setFormError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (open) {
      setPassword("");
      setFormError(null);
    }
  }, [open]);

  const submit = async () => {
    if (!password) {
      setFormError("请输入开发者密码");
      return;
    }
    setFormError(null);
    setBusy(true);
    try {
      await api.sshElevate(password);
      toast("success", "已进入开发者模式");
      onClose();
      onElevated();
    } catch (e) {
      if (!onForbidden(e)) setFormError(errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open={open} title="进入开发者模式" onClose={busy ? undefined : onClose}>
      <div className="form-row">
        <span className="form-label">开发者密码</span>
        <input
          className="input"
          type="password"
          autoComplete="off"
          autoFocus
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && !busy) void submit();
          }}
        />
      </div>
      <p style={{ fontSize: 12, color: "var(--monet-on-surface-variant)", lineHeight: 1.6 }}>
        提权 15 分钟有效，期间每次敏感操作自动续期；连续猜错 3 次会锁定 10 分钟。
      </p>
      {formError && <div className="form-error">{formError}</div>}
      <div className="dialog-actions">
        <button className="btn btn-text dim" onClick={onClose} disabled={busy}>
          {I18N["button.cancel"]}
        </button>
        <button className="btn btn-text" onClick={() => void submit()} disabled={busy}>
          {busy ? "验证中…" : "进入"}
        </button>
      </div>
    </Dialog>
  );
}

/** 设置/修改开发者密码：面板口令证明是本人；≥8 位且不得与面板口令相同（后端强制的两条
 *  后端 error 会原样显示在对话框里）。设置成功后所有提权都被吊销，需重新进入。 */
function SshDevPasswordDialog({
  open,
  changing,
  onClose,
  onDone,
}: {
  open: boolean;
  changing: boolean;
  onClose: () => void;
  onDone: () => void;
}) {
  const [adminPassword, setAdminPassword] = useState("");
  const [current, setCurrent] = useState("");
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [formError, setFormError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (open) {
      setAdminPassword("");
      setCurrent("");
      setPassword("");
      setConfirm("");
      setFormError(null);
    }
  }, [open]);

  const submit = async () => {
    if (!adminPassword) {
      setFormError("请输入管理员密码");
      return;
    }
    if (changing && !current) {
      setFormError("请输入当前开发者密码");
      return;
    }
    if (password.length < 8) {
      setFormError("开发者密码至少 8 位");
      return;
    }
    if (password !== confirm) {
      setFormError("两次输入的新密码不一致");
      return;
    }
    setFormError(null);
    setBusy(true);
    try {
      await api.sshSetDevPassword({
        adminPassword,
        password,
        confirm,
        ...(changing ? { currentDevPassword: current } : {}),
      });
      toast("success", changing ? "开发者密码已更新，请重新进入开发者模式" : "开发者密码已设置");
      onClose();
      onDone();
    } catch (e) {
      setFormError(errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open={open} title={changing ? "修改开发者密码" : "设置开发者密码"} onClose={busy ? undefined : onClose}>
      <div className="form-row">
        <span className="form-label">管理员密码</span>
        <input
          className="input"
          type="password"
          autoComplete="current-password"
          value={adminPassword}
          onChange={(e) => setAdminPassword(e.target.value)}
        />
      </div>
      {changing && (
        <div className="form-row">
          <span className="form-label">当前开发者密码</span>
          <input
            className="input"
            type="password"
            autoComplete="off"
            value={current}
            onChange={(e) => setCurrent(e.target.value)}
          />
        </div>
      )}
      <div className="form-row">
        <span className="form-label">{changing ? "新开发者密码" : "开发者密码"}</span>
        <input
          className="input"
          type="password"
          autoComplete="new-password"
          autoFocus={!changing}
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
      </div>
      <div className="form-row">
        <span className="form-label">确认新密码</span>
        <input
          className="input"
          type="password"
          autoComplete="new-password"
          value={confirm}
          onChange={(e) => setConfirm(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && !busy) void submit();
          }}
        />
      </div>
      <p style={{ fontSize: 12, color: "var(--monet-on-surface-variant)", lineHeight: 1.6 }}>
        至少 8 位，且不能与面板口令相同。设置后还需要输一次面板口令确认是本人。
      </p>
      {formError && <div className="form-error">{formError}</div>}
      <div className="dialog-actions">
        <button className="btn btn-text dim" onClick={onClose} disabled={busy}>
          {I18N["button.cancel"]}
        </button>
        <button className="btn btn-text" onClick={() => void submit()} disabled={busy}>
          {busy ? "提交中…" : changing ? "更新密码" : "设置密码"}
        </button>
      </div>
    </Dialog>
  );
}

/** 粘贴公钥：一行一条，逐行按算法白名单 + base64 形状预检；空行与 # 注释行跳过 */
function SshPasteKeyDialog({
  open,
  onClose,
  onForbidden,
  onAdded,
}: {
  open: boolean;
  onClose: () => void;
  onForbidden: (e: unknown) => boolean;
  onAdded: () => void;
}) {
  const [text, setText] = useState("");
  const [formError, setFormError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (open) {
      setText("");
      setFormError(null);
    }
  }, [open]);

  const submit = async () => {
    const lines = text
      .split(/\r?\n/)
      .map((l) => l.trim())
      .filter((l) => l && !l.startsWith("#"));
    if (lines.length === 0) {
      setFormError("请粘贴至少一行 OpenSSH 公钥");
      return;
    }
    for (let i = 0; i < lines.length; i++) {
      if (!SSH_PUBKEY_LINE.test(lines[i])) {
        setFormError(
          `第 ${i + 1} 行不是有效的公钥：应为「算法 base64 [注释]」，算法支持 ssh-ed25519 / ssh-rsa / ecdsa-sha2-nistp256/384/521`,
        );
        return;
      }
    }
    setFormError(null);
    setBusy(true);
    try {
      for (const line of lines) await api.sshAddKey(line);
      toast("success", `已添加 ${lines.length} 条公钥`);
      onClose();
      onAdded();
    } catch (e) {
      if (!onForbidden(e)) setFormError(errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open={open} title="粘贴公钥" wide onClose={busy ? undefined : onClose}>
      <div className="form-row">
        <span className="form-label">OpenSSH 公钥（每行一条）</span>
        <textarea
          className="input mono"
          style={{ fontWeight: 400, minHeight: 120, resize: "vertical" }}
          placeholder={"ssh-ed25519 AAAA… 我的笔记本\nssh-rsa AAAA… 旧台式机"}
          value={text}
          onChange={(e) => setText(e.target.value)}
        />
      </div>
      <p style={{ fontSize: 12, color: "var(--monet-on-surface-variant)", lineHeight: 1.6 }}>
        支持 ssh-ed25519 / ssh-rsa / ecdsa-sha2-nistp256/384/521；对应私钥留在客户端，面板只存公钥。
      </p>
      {formError && <div className="form-error">{formError}</div>}
      <div className="dialog-actions">
        <button className="btn btn-text dim" onClick={onClose} disabled={busy}>
          {I18N["button.cancel"]}
        </button>
        <button className="btn btn-text" onClick={() => void submit()} disabled={busy}>
          {busy ? "添加中…" : "添加"}
        </button>
      </div>
    </Dialog>
  );
}

/* ---------------- 环境体检 ---------------- */

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
