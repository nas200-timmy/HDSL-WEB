import type {
  Account,
  CreateAccountBody,
  CreateInstanceBody,
  DoctorReport,
  ExportRecord,
  Health,
  Instance,
  InstalledPlugin,
  LogLine,
  MarketPack,
  MarketPackDetail,
  ModelInfo,
  ModelProvider,
  PatchAccountBody,
  PatchInstanceBody,
  PluginCatalogItem,
  SessionInfo,
  SkillInfo,
  Task,
  TlsSettings,
  TlsUploadResult,
  Vendor,
  VersionInfo,
  WorkspaceInfo,
  ZcodeDistInfo,
  ZcodeInstance,
} from "./types";

export class ApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
  }
}

/** 契约中已冻结但后端尚未实现的端点会返回 404/405，UI 据此优雅降级 */
export function isNotImplemented(e: unknown): boolean {
  return e instanceof ApiError && (e.status === 404 || e.status === 405);
}

function jsonInit(method: string, body: unknown): RequestInit {
  return { method, headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) };
}

/** 任何 API 返回 401（会话过期）时的全局处理，由 store 注册 */
let unauthorizedHandler: (() => void) | null = null;
export function setUnauthorizedHandler(fn: () => void): void {
  unauthorizedHandler = fn;
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let res: Response;
  try {
    res = await fetch(path, {
      credentials: "same-origin",
      cache: "no-store",
      signal: AbortSignal.timeout(30000),
      ...init,
    });
  } catch (e) {
    if (e instanceof DOMException && e.name === "TimeoutError") {
      throw new ApiError(0, "请求超时，请检查服务器状态");
    }
    throw new ApiError(0, "无法连接服务器");
  }

  let body: unknown = null;
  if (res.status !== 204) {
    try {
      body = await res.json();
    } catch {
      body = null;
    }
  }

  if (!res.ok) {
    if (res.status === 401 && unauthorizedHandler) {
      // 会话失效：强制回到登录页（登录接口自身的 401 由调用方处理，这里不重复触发）
      unauthorizedHandler();
    }
    const msg =
      body && typeof body === "object" && typeof (body as { error?: unknown }).error === "string"
        ? ((body as { error: string }).error)
        : `请求失败（HTTP ${res.status}）`;
    throw new ApiError(res.status, msg);
  }
  return body as T;
}

export const api = {
  health: () => request<Health>("/api/health"),

  login: (username: string, password: string) =>
    request<{ ok: boolean; username: string }>("/api/auth/login", jsonInit("POST", { username, password })),

  logout: () => request<unknown>("/api/auth/logout", { method: "POST" }),

  whoami: () => request<{ username: string }>("/api/auth/whoami"),

  /** 首次启动引导：服务端是否还没有任何用户 */
  authStatus: () => request<{ setupRequired: boolean }>("/api/auth/status"),

  /** 创建第一个用户并自动登录（服务端在无用户时才接受；201 = 已下发会话） */
  setup: (username: string, password: string) =>
    request<{ ok: boolean; username: string }>("/api/auth/setup", jsonInit("POST", { username, password })),

  /** 修改当前用户密码；成功后服务端会吊销本用户的其它会话 */
  changePassword: (currentPassword: string, newPassword: string) =>
    request<{ ok: boolean }>("/api/auth/password", jsonInit("POST", { currentPassword, newPassword })),

  /** 修改当前用户名；本设备的会话保留并切换到新名，其它会话被吊销 */
  changeUsername: (newUsername: string) =>
    request<{ ok: boolean; username: string }>("/api/auth/username", jsonInit("POST", { newUsername })),

  versions: () => request<{ versions: VersionInfo[] }>("/api/versions"),

  instances: () => request<{ instances: Instance[] }>("/api/instances"),

  getInstance: (id: string) => request<Instance>(`/api/instances/${id}`),

  createInstance: (body: CreateInstanceBody) =>
    request<{ instance: Instance; installTaskId?: string }>("/api/instances", jsonInit("POST", body)),

  patchInstance: (id: string, body: PatchInstanceBody) =>
    request<{ instance?: Instance }>(`/api/instances/${id}`, jsonInit("PATCH", body)),

  deleteInstance: (id: string) => request<unknown>(`/api/instances/${id}`, { method: "DELETE" }),

  install: (id: string, version: string) =>
    request<{ taskId: string }>(`/api/instances/${id}/install`, jsonInit("POST", { version })),

  launch: (id: string, publicHost?: string) =>
    request<{ state: string }>(
      `/api/instances/${id}/launch`,
      jsonInit("POST", publicHost ? { publicHost } : {}),
    ),

  stop: (id: string) => request<unknown>(`/api/instances/${id}/stop`, { method: "POST" }),

  logs: (id: string, tail = 200) =>
    request<{ lines: LogLine[] }>(`/api/instances/${id}/logs?tail=${tail}`),

  open: (id: string) => request<{ url: string }>(`/api/instances/${id}/open`),

  tasks: async (): Promise<Task[]> => {
    const raw: unknown = await request("/api/tasks");
    if (Array.isArray(raw)) return raw as Task[];
    if (raw && typeof raw === "object" && Array.isArray((raw as { tasks?: unknown }).tasks)) {
      return (raw as { tasks: Task[] }).tasks;
    }
    return [];
  },

  // 注：冻结契约未列出取消任务端点，但 Phase 2 UX 规范要求进度视图提供 [取消]。
  // 按 TaskService 设计（cancel → DshCommand.stopRunning）推测为 POST /api/tasks/{id}/cancel。
  cancelTask: (taskId: string) => request<unknown>(`/api/tasks/${taskId}/cancel`, { method: "POST" }),

  // ---------- Phase 3：插件 ----------

  pluginCatalog: (refresh = false) =>
    request<{ plugins: PluginCatalogItem[] }>(`/api/plugins/catalog${refresh ? "?refresh=1" : ""}`),

  instancePlugins: (id: string) =>
    request<{ plugins: InstalledPlugin[]; bundles: InstalledPlugin[] }>(`/api/instances/${id}/plugins`),

  installPlugins: (id: string, specs: string[]) =>
    request<{ taskId: string }>(`/api/instances/${id}/plugins/install`, jsonInit("POST", { specs })),

  removePlugins: (id: string, specs: string[]) =>
    request<{ taskId: string }>(`/api/instances/${id}/plugins/remove`, jsonInit("POST", { specs })),

  // multipart 上传：不要手动设 Content-Type，浏览器会自动带 boundary
  uploadLocalPlugin: (id: string, file: File) => {
    const fd = new FormData();
    fd.append("file", file);
    return request<{ taskId: string }>(`/api/instances/${id}/plugins/local`, { method: "POST", body: fd });
  },

  approveTask: (taskId: string, allow: boolean) =>
    request<unknown>(`/api/tasks/${taskId}/approve`, jsonInit("POST", { allow })),

  // ---------- Phase 3：账户 ----------

  vendors: () => request<{ vendors: Vendor[] }>("/api/vendors"),

  accounts: () => request<{ accounts: Account[] }>("/api/accounts"),

  createAccount: (body: CreateAccountBody) =>
    request<{ account: Account; verified?: boolean; verifyError?: string }>(
      "/api/accounts",
      jsonInit("POST", body),
    ),

  verifyAccount: (name: string) =>
    request<{ ok: boolean; models?: string[]; error?: string }>(
      `/api/accounts/${encodeURIComponent(name)}/verify`,
    ),

  patchAccount: (name: string, body: PatchAccountBody) =>
    request<unknown>(`/api/accounts/${encodeURIComponent(name)}`, jsonInit("PATCH", body)),

  deleteAccount: (name: string) =>
    request<unknown>(`/api/accounts/${encodeURIComponent(name)}`, { method: "DELETE" }),

  // ---------- Phase 3：模型目录（models.dev） ----------

  /** 供应商名单；refresh = 走后端 ?refresh=1 强制重抓（默认落 12 小时 TTL 缓存） */
  modelProviders: (refresh = false) =>
    request<{ fetchedAt: number | null; source: string; providers: ModelProvider[] }>(
      `/api/models/providers${refresh ? "?refresh=1" : ""}`,
    ),

  /** 单家供应商的模型列表；dsh 认识但目录里没有 → 200 + 空数组 */
  modelProvider: (id: string) =>
    request<{ vendor: ModelProvider; models: ModelInfo[] }>(
      `/api/models/providers/${encodeURIComponent(id)}`,
    ),

  // ---------- Phase 3：TLS ----------

  getTls: () => request<TlsSettings>("/api/settings/tls"),

  uploadTls: (fd: FormData) =>
    request<TlsUploadResult>("/api/settings/tls", { method: "POST", body: fd }),

  // ---------- Phase 4：整合包市场 / 安装 / 上传 ----------

  packsMarket: (refresh = false) =>
    request<{ packs: MarketPack[] }>(`/api/packs/market${refresh ? "?refresh=1" : ""}`),

  packMarketDetail: (packId: string) =>
    request<{ pack: MarketPackDetail }>(`/api/packs/market/${encodeURIComponent(packId)}`),

  installPack: (body: { packId: string; version?: string; name?: string }) =>
    request<{ taskId: string }>("/api/packs/install", jsonInit("POST", body)),

  // multipart：file(.dspack) + 可选 name；不要手动设 Content-Type
  uploadPack: (file: File, name?: string) => {
    const fd = new FormData();
    fd.append("file", file);
    if (name) fd.append("name", name);
    return request<{ taskId: string }>("/api/packs/upload", { method: "POST", body: fd });
  },

  // ---------- Phase 4：导出 ----------

  exportInstance: (id: string, body: { name?: string; includeSessions?: boolean }) =>
    request<{ taskId: string }>(`/api/instances/${id}/export`, jsonInit("POST", body)),

  exports: () => request<{ exports: ExportRecord[] }>("/api/exports"),

  /** 导出文件下载地址：浏览器直接打开（同源带 cookie） */
  exportDownloadUrl: (filename: string) => `/api/exports/${encodeURIComponent(filename)}`,

  // ---------- Phase 4：会话 / 工作区 ----------

  instanceSessions: (id: string) =>
    request<{ sessions: SessionInfo[] }>(`/api/instances/${id}/sessions`),

  exportSessions: (id: string, sessionIds: string[]) =>
    request<{ taskId: string }>(`/api/instances/${id}/sessions/export`, jsonInit("POST", { sessionIds })),

  instanceWorkspaces: (id: string) =>
    request<{ workspaces: WorkspaceInfo[] }>(`/api/instances/${id}/workspaces`),

  // ---------- Phase 4：技能 ----------

  instanceSkills: (id: string) => request<{ skills: SkillInfo[] }>(`/api/instances/${id}/skills`),

  installSkill: (id: string, source: string) =>
    request<{ taskId: string }>(`/api/instances/${id}/skills/install`, jsonInit("POST", { source })),

  deleteSkill: (id: string, skillId: string) =>
    request<unknown>(`/api/instances/${id}/skills/${encodeURIComponent(skillId)}`, { method: "DELETE" }),

  // ---------- Phase 4：体检 ----------

  doctor: () => request<DoctorReport>("/api/doctor"),

  // ---------- 实验性：ZCode 品类（见 docs/zcode-experimental.md） ----------

  /** ZCode 发行包检测：HDSL_ZCODE_PACKAGE 或 <dataDir>/zcode/current/ */
  zcodeDist: () => request<ZcodeDistInfo>("/api/zcode/dist"),

  zcodeInstances: () => request<{ instances: ZcodeInstance[] }>("/api/zcode/instances"),

  createZcodeInstance: (body: { name: string; baseUrl?: string; apiKey?: string }) =>
    request<{ instance: ZcodeInstance }>("/api/zcode/instances", jsonInit("POST", body)),

  patchZcodeInstance: (id: string, body: { name?: string; baseUrl?: string; apiKey?: string }) =>
    request<{ instance: ZcodeInstance }>(
      `/api/zcode/instances/${encodeURIComponent(id)}`,
      jsonInit("PATCH", body),
    ),

  deleteZcodeInstance: (id: string) =>
    request<unknown>(`/api/zcode/instances/${encodeURIComponent(id)}`, { method: "DELETE" }),

  /** 启动并等 ZCode 的就绪行；error 状态会带日志尾部作为原因 */
  launchZcode: (id: string) =>
    request<{ state: string; error?: string }>(
      `/api/zcode/instances/${encodeURIComponent(id)}/launch`,
      jsonInit("POST", {}),
    ),

  stopZcode: (id: string) =>
    request<{ state: string; error?: string }>(
      `/api/zcode/instances/${encodeURIComponent(id)}/stop`,
      jsonInit("POST", {}),
    ),

  /** 运行中实例的打开地址（HTTP 明文 + 令牌，用请求 Host 拼出来） */
  zcodeOpen: (id: string) => request<{ url: string }>(`/api/zcode/instances/${encodeURIComponent(id)}/open`),

  zcodeLogs: (id: string, tail = 200) =>
    request<{ lines: string[] }>(`/api/zcode/instances/${encodeURIComponent(id)}/logs?tail=${tail}`),
};
