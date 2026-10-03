import { useEffect, useReducer, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { api, ApiError, isNotImplemented } from "../api";
import { FileDropInput } from "../components/FileDropInput";
import {
  CheckCircleIcon,
  CheckIcon,
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
import { logout, toast, useAppState } from "../store";
import { bindSystemThemeListener, setThemeMode, useThemeMode, type ThemeMode } from "../theme";
import type { DoctorCheck, DoctorReport, Health, TlsSettings, TlsUploadResult } from "../types";
import { errMsg, formatDateTime, formatUptime } from "../utils";

type SettingsTab =
  | "general"
  | "node"
  | "launcher-general"
  | "appearance"
  | "download-source"
  | "tls"
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

/* ---------------- Node.js（网页版由服务端托管，降级展示） ---------------- */

function NodeTab() {
  return (
    <>
      <div className="section-caption">
        <LocalCafeIcon size={16} />
        {I18N["dsh.node.title"]}
      </div>
      <div className="card">
        <div className="hint info" style={{ margin: 2 }}>
          HDSL-web 由服务端统一托管 Node.js 运行时，网页版无需（也无法）单独安装或删除运行时。
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

const NPM_REGISTRY_KEY = "hdsl.npm.registry";

function DownloadSourceTab() {
  const [registry, setRegistry] = useState(() => localStorage.getItem(NPM_REGISTRY_KEY) ?? "");
  const [saved, setSaved] = useState<string | null>(null);

  const save = () => {
    const v = registry.trim();
    if (v) localStorage.setItem(NPM_REGISTRY_KEY, v);
    else localStorage.removeItem(NPM_REGISTRY_KEY);
    setSaved(v || null);
    toast("success", "下载源已保存");
  };

  return (
    <>
      <div className="section-caption">
        <DownloadIcon size={16} />
        {I18N["settings.launcher.download_source"]}
      </div>
      <div className="card">
        <div className="comp-row">
          <span className="comp-label">npm registry</span>
          <span className="comp-value">
            <input
              className="input"
              style={{ flex: 1, minWidth: 220, fontWeight: 400 }}
              placeholder="https://registry.npmjs.org"
              value={registry}
              onChange={(e) => setRegistry(e.target.value)}
            />
          </span>
        </div>
        <div className="comp-row">
          <span className="comp-label">当前</span>
          <span className="comp-value" style={{ fontSize: 12, color: "var(--monet-on-surface-variant)" }}>
            {saved ?? "官方默认源（https://registry.npmjs.org）"}
          </span>
        </div>
        <div style={{ display: "flex", justifyContent: "flex-end", paddingTop: 6 }}>
          <button className="btn btn-raised ripple-host" onClick={save}>
            {I18N["button.save"]}
          </button>
        </div>
        <p style={{ fontSize: 12, color: "var(--monet-on-surface-variant)", padding: "4px 8px 0" }}>
          网页版暂未接入服务端下载源设置，此处配置保存在本地浏览器。
        </p>
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

function AboutTab() {
  const [health, setHealth] = useState<Health | null>(null);

  useEffect(() => {
    api
      .health()
      .then(setHealth)
      .catch(() => undefined);
  }, []);

  return (
    <>
      <div className="section-caption">
        <InfoIcon size={16} />
        {I18N["about"]}
      </div>
      <div className="card">
        <div className="comp-row">
          <span className="comp-label">启动器</span>
          <span className="comp-value">
            {I18N.appFullName}
            {health ? ` v${health.version}` : ""}
          </span>
        </div>
        <div className="comp-row">
          <span className="comp-label">{I18N["about.copyright"]}</span>
          <span className="comp-value">{I18N["about.copyright.statement"]}</span>
        </div>
        <div className="comp-row">
          <span className="comp-label">{I18N["about.author.statement"].split(" ")[0]}</span>
          <span className="comp-value">{I18N["about.author.statement"]}</span>
        </div>
        <div className="comp-row">
          <span className="comp-label">{I18N["about.open_source"]}</span>
          <span className="comp-value">{I18N["about.open_source.statement"]}</span>
        </div>
        <div className="comp-row">
          <span className="comp-label">第三方</span>
          <span className="comp-value" style={{ fontSize: 12, color: "var(--monet-on-surface-variant)" }}>
            React · Vite · @material/material-color-utilities · 图标来自 Material Symbols
          </span>
        </div>
      </div>
    </>
  );
}
