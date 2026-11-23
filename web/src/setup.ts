// ---------------------------------------------------------------------------
// 部署检查
//
// 首页卡片、侧栏徽标、缺 Node 的硬拦都读这一份状态。数据全部来自现成接口，
// 不新增聚合端点：Node 用 `/api/node/resolve`（和下拉框、启动器同一个解析器），
// 下载源、证书、开发者密码各有自己的接口。任何一项读不到都只降级那一项，
// 不让整张卡片变成错误——首次部署时最缺的恰恰是"到底哪一项没配好"。
//
// 收起/隐藏是这台设备的偏好（和主题、壁纸同一套做法，存 localStorage）；
// 硬拦不读这两个开关，它只认服务端。
// ---------------------------------------------------------------------------

import { useSyncExternalStore } from "react";
import { api } from "./api";
import type {
  DeveloperStatus, NodeResolution, PanelInfo, RegistrySettings, TlsSettings,
} from "./types";

export type SetupTone = "ok" | "warn" | "bad";
export type SetupItemId = "node" | "registry" | "tls" | "developer";

export interface SetupItem {
  id: SetupItemId;
  tone: SetupTone;
  title: string;
  detail: string;
  /** 状态读不到（接口失败）：允许动作继续，交给服务端兜底。 */
  unknown?: boolean;
}

export interface SetupSecurity {
  level: "warn" | "bad";
  text: string;
}

export interface SetupStatus {
  /** 没有阻塞项（bad）。warn 只算建议，不让徽标常亮。 */
  ready: boolean;
  items: SetupItem[];
  security: SetupSecurity | null;
  /** 拉取时间（毫秒），用于缓存与调试。 */
  at: number;
}

const HIDDEN_KEY = "hdsl.setup.hidden";
const COLLAPSED_KEY = "hdsl.setup.collapsed";
const SEEN_KEY = "hdsl.setup.seen";

// ---------------------------------------------------------------- 设备偏好 --

const prefListeners = new Set<() => void>();

function readFlag(key: string): boolean {
  try {
    return localStorage.getItem(key) === "1";
  } catch {
    return false;
  }
}

function writeFlag(key: string, value: boolean): void {
  try {
    if (value) localStorage.setItem(key, "1");
    else localStorage.removeItem(key);
  } catch {
    // 隐私模式下存不了：只影响这台设备的记住状态，不该抛给调用方。
  }
  prefListeners.forEach((listener) => listener());
}

function subscribePrefs(listener: () => void): () => void {
  prefListeners.add(listener);
  return () => {
    prefListeners.delete(listener);
  };
}

/** 设置 → 通用 里关掉以后，卡片和徽标都不出现（硬拦仍然生效）。 */
export function setupHidden(): boolean {
  return readFlag(HIDDEN_KEY);
}

export function setSetupHidden(hidden: boolean): void {
  writeFlag(HIDDEN_KEY, hidden);
}

/** 卡片是否收成一行（徽标入口仍然保留）。 */
export function setupCollapsed(): boolean {
  return readFlag(COLLAPSED_KEY);
}

export function setSetupCollapsed(collapsed: boolean): void {
  writeFlag(COLLAPSED_KEY, collapsed);
}

export function useSetupHidden(): boolean {
  return useSyncExternalStore(subscribePrefs, setupHidden, () => false);
}

export function useSetupCollapsed(): boolean {
  return useSyncExternalStore(subscribePrefs, setupCollapsed, () => true);
}

/** 这台设备是否已经见过首次部署卡片；没见过时无论状态如何都展开。 */
export function setupSeen(): boolean {
  return readFlag(SEEN_KEY);
}

export function markSetupSeen(): void {
  writeFlag(SEEN_KEY, true);
}

// ------------------------------------------------------------------ 状态 --

let cached: SetupStatus | null = null;
let inflight: Promise<SetupStatus> | null = null;
const statusListeners = new Set<() => void>();

function emitStatus(): void {
  statusListeners.forEach((listener) => listener());
}

export function getSetupStatus(): SetupStatus | null {
  return cached;
}

export function subscribeSetupStatus(listener: () => void): () => void {
  statusListeners.add(listener);
  return () => {
    statusListeners.delete(listener);
  };
}

/** SSR 没有浏览器环境：卡片本来也只在登录后的 SPA 里出现。 */
export function useSetupStatus(): SetupStatus | null {
  return useSyncExternalStore(subscribeSetupStatus, getSetupStatus, () => null);
}

const CACHE_MS = 20_000;

/** 重新探测；默认 20 秒内复用上一次结果。 */
export function refreshSetupStatus(force = false): Promise<SetupStatus> {
  if (!force && cached && Date.now() - cached.at < CACHE_MS) {
    return Promise.resolve(cached);
  }
  if (inflight) return inflight;
  inflight = probe().then((status) => {
    cached = status;
    inflight = null;
    emitStatus();
    return status;
  }).catch((error: unknown) => {
    inflight = null;
    // 全部失败时也要让 UI 有东西可画：四个 unknown 项比空白卡片诚实。
    const fallback: SetupStatus = {
      ready: false,
      items: [
        { id: "node", tone: "warn", unknown: true, title: "Node.js", detail: reasonOf(error) },
        { id: "registry", tone: "warn", unknown: true, title: "下载源", detail: "状态读取失败" },
        { id: "tls", tone: "warn", unknown: true, title: "HTTPS 证书", detail: "状态读取失败" },
        { id: "developer", tone: "warn", unknown: true, title: "开发者密码", detail: "状态读取失败" },
      ],
      security: null,
      at: Date.now(),
    };
    cached = fallback;
    emitStatus();
    return fallback;
  });
  return inflight;
}

function reasonOf(error: unknown): string {
  if (error && typeof error === "object" && "message" in error) {
    return String((error as { message?: unknown }).message ?? "状态读取失败");
  }
  return "状态读取失败";
}

function isLoopbackHost(host: string | undefined): boolean {
  const value = (host ?? "").trim().toLowerCase().replace(/^\[|\]$/g, "");
  return value === "localhost" || value === "::1" || value === "127.0.0.1" || value.startsWith("127.");
}

const REGISTRY_LABEL: Record<string, string> = {
  npmjs: "npm 官方源",
  npmmirror: "npmmirror",
  ustc: "中科大镜像",
  tencent: "腾讯云镜像",
  huawei: "华为云镜像",
  custom: "自定义源",
};

async function probe(): Promise<SetupStatus> {
  const [node, registry, tls, developer, panel] = await Promise.allSettled([
    api.nodeResolve("auto", undefined, true),
    api.getRegistry(),
    api.getTls(),
    api.developerStatus(),
    api.panelInfo(),
  ] as const);

  const pageSecure = typeof window !== "undefined" && window.location.protocol === "https:";
  const panelInfo: PanelInfo | null = panel.status === "fulfilled" ? panel.value : null;
  const loopback = panelInfo ? isLoopbackHost(panelInfo.bindHost) : false;

  const nodeItem = nodeItemOf(node.status === "fulfilled" ? node.value : null,
    node.status === "rejected" ? reasonOf(node.reason) : null);
  const registryItem = registryItemOf(
    registry.status === "fulfilled" ? registry.value : null,
    registry.status === "rejected" ? reasonOf(registry.reason) : null,
  );
  const tlsItem = tlsItemOf(
    tls.status === "fulfilled" ? tls.value : null,
    tls.status === "rejected" ? reasonOf(tls.reason) : null,
    { pageSecure, panelHttps: panelInfo?.https ?? false, loopback },
  );
  const devItem = developerItemOf(
    developer.status === "fulfilled" ? developer.value : null,
    developer.status === "rejected" ? reasonOf(developer.reason) : null,
    { loopback },
  );

  const items = [nodeItem, registryItem, tlsItem, devItem];

  let security: SetupSecurity | null = null;
  if (panelInfo && !loopback && !pageSecure) {
    security = {
      level: "bad",
      text: "面板正以 HTTP 明文对外提供服务：同一网络的人可以读到会话和输入。"
        + "建议在「设置 → 证书管理」启用 HTTPS，或把它放到 HTTPS 反代后面。",
    };
  } else if (panelInfo && !loopback && developer.status === "fulfilled"
      && !developer.value.devPasswordSet) {
    security = {
      level: "warn",
      text: "面板对外开放，但还没有设置开发者密码：实例网页和终端没有第二道验证。",
    };
  }

  return {
    ready: !items.some((item) => item.tone === "bad"),
    items,
    security,
    at: Date.now(),
  };
}

function nodeItemOf(resolved: NodeResolution | null, error: string | null): SetupItem {
  if (!resolved) {
    return { id: "node", tone: "warn", unknown: true, title: "Node.js", detail: error ?? "状态读取失败" };
  }
  if (resolved.compatible && resolved.version && resolved.pnpm !== false) {
    const where = resolved.source === "managed" || resolved.source === "channel" || resolved.source === "exact"
      ? "面板托管" : "系统 PATH";
    return { id: "node", tone: "ok", title: "Node.js", detail: `${resolved.version} · ${where}` };
  }
  if (resolved.version && resolved.pnpm === false) {
    return {
      id: "node",
      tone: "bad",
      title: "Node.js",
      detail: `${resolved.version} 缺少 pnpm；安装 dsh 需要它，点「一键安装」会装一个自带 pnpm 的托管运行时`,
    };
  }
  return {
    id: "node",
    tone: "bad",
    title: "Node.js",
    detail: resolved.reason || (resolved.version
      ? `Node.js ${resolved.version} 不可用`
      : "没有可用的 Node.js"),
  };
}

function registryItemOf(settings: RegistrySettings | null, error: string | null): SetupItem {
  if (!settings) {
    return { id: "registry", tone: "warn", unknown: true, title: "下载源", detail: error ?? "状态读取失败" };
  }
  const label = REGISTRY_LABEL[settings.preset] ?? settings.effective;
  const official = settings.effective.includes("registry.npmjs.org");
  // 明确选过官方源是用户的决定，不再劝；跟随默认/环境才提示。
  if (settings.source === "setting" && !official) {
    return { id: "registry", tone: "ok", title: "下载源", detail: `${label} · ${settings.effective}` };
  }
  if (!official && settings.source !== "setting") {
    return { id: "registry", tone: "ok", title: "下载源", detail: `跟随环境 · ${settings.effective}` };
  }
  return {
    id: "registry",
    tone: "warn",
    title: "下载源",
    detail: `${label}（${settings.effective}）：国内首次安装常常很慢，建议切到 npmmirror`,
  };
}

function tlsItemOf(
  settings: TlsSettings | null,
  error: string | null,
  state: { pageSecure: boolean; panelHttps: boolean; loopback: boolean },
): SetupItem {
  if (!settings) {
    return { id: "tls", tone: "warn", unknown: true, title: "HTTPS 证书", detail: error ?? "状态读取失败" };
  }
  if (settings.https) {
    return {
      id: "tls",
      tone: "ok",
      title: "HTTPS 证书",
      detail: settings.source === "self-signed" ? "面板终结 TLS · 自签证书" : "面板终结 TLS",
    };
  }
  if (state.pageSecure) {
    return { id: "tls", tone: "ok", title: "HTTPS 证书", detail: "由前置代理终结" };
  }
  return {
    id: "tls",
    tone: state.loopback ? "warn" : "bad",
    title: "HTTPS 证书",
    detail: state.loopback
      ? "仅监听本机，当前是 HTTP 明文；要从别的设备访问先配证书"
      : "HTTP 明文：同网络可读到会话和输入，建议生成自签证书或挂 HTTPS 反代",
  };
}

function developerItemOf(
  status: DeveloperStatus | null,
  error: string | null,
  state: { loopback: boolean },
): SetupItem {
  if (!status) {
    return { id: "developer", tone: "warn", unknown: true, title: "开发者密码", detail: error ?? "状态读取失败" };
  }
  if (status.devPasswordSet) {
    return { id: "developer", tone: "ok", title: "开发者密码", detail: "已设置" };
  }
  return {
    id: "developer",
    tone: state.loopback ? "warn" : "bad",
    title: "开发者密码",
    detail: state.loopback
      ? "未设置；只有本机能访问时可以先不设"
      : "未设置：实例网页和终端没有第二道验证，建议设置",
  };
}

/** 卡片标题：全部 ok / 只有建议 / 有阻塞项。 */
export function setupSummary(status: SetupStatus): string {
  const bad = status.items.filter((item) => item.tone === "bad").length;
  const warn = status.items.filter((item) => item.tone === "warn").length;
  if (bad === 0 && warn === 0) return "部署检查 · 全部就绪";
  if (bad === 0) return `部署检查 · 可用（${warn} 条建议）`;
  return `部署检查 · ${bad} 项待处理`;
}

// ------------------------------------------------------------ 一键装 Node --

/** 托管运行时安装：启动任务后轮询到完成。返回是否装好。 */
async function nodeReady(): Promise<boolean> {
  const status = await refreshSetupStatus(true);
  return status.items.find((item) => item.id === "node")?.tone === "ok";
}

export async function installManagedNode(
  onProgress?: (message: string) => void,
): Promise<boolean> {
  const { taskId } = await api.nodeInstall({ source: "mirror" });
  const deadline = Date.now() + 15 * 60 * 1000;
  let misses = 0;
  while (Date.now() < deadline) {
    await new Promise((resolve) => window.setTimeout(resolve, 1200));
    const tasks = await api.tasks().catch(() => []);
    const task = tasks.find((item) => item.id === taskId);
    if (!task) {
      // 任务列表偶尔会在完成瞬间把它收走：连续看不到就直接问运行时状态。
      if (++misses > 15 && await nodeReady()) return true;
      continue;
    }
    misses = 0;
    if (task.message) onProgress?.(task.message);
    if (task.state === "done") {
      await refreshSetupStatus(true);
      return true;
    }
    if (task.state === "failed" || task.state === "cancelled") {
      throw new Error(task.error || task.message || "安装失败");
    }
  }
  if (await nodeReady()) return true;
  throw new Error("安装超时；可以到「设置 → Node.js」查看进度");
}