import type {
  Account,
  BrandAdminCredentials,
  BrandSettings,
  BrandSourceProbe,
  BrandSourcesBody,
  CreateAccountBody,
  CreateInstanceBody,
  DeveloperGuardScope,
  DeveloperStatus,
  DoctorReport,
  EmergencyStopPreview,
  ExportChoices,
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
  RegistrySettings,
  RegistryTestResult,
  SessionInfo,
  SkillInfo,
  Task,
  TlsSettings,
  TlsUploadResult,
  TuiPluginItem,
  TuiPluginState,
  VersionInfo,
  Wallpaper,
  WallpaperList,
  WorkspaceInfo,
  ZcodeBuildStatus,
  ZcodeDistInfo,
  ZcodeInstance,
  ZcodeRelease,
  BrandId,
  BrandInstallStatus,
  BrandInstance,
  BrandVersions,
  InstancePlugins,
  TerminalKernel,
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

async function request<T>(path: string, init?: RequestInit, opts?: { skipUnauthorizedRedirect?: boolean; timeoutMs?: number }): Promise<T> {
  let res: Response;
  try {
    // 相对路径：由后端注入的 <base href> 解析——根挂载解析到 /api/…，
    // 子路径挂载解析到 <base>/api/…。两种挂载共用一个构建产物。
    res = await fetch(path.replace(/^\//, ""), {
      credentials: "same-origin",
      cache: "no-store",
      signal: AbortSignal.timeout(opts?.timeoutMs ?? 30000),
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
    if (res.status === 401 && unauthorizedHandler && !opts?.skipUnauthorizedRedirect) {
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

  // --- 终端（docs/web-shell.md） -------------------------------------------
  openDshTerminal: (id: string, resume?: string) =>
    request<TerminalOpen>(
      `/api/instances/${encodeURIComponent(id)}/terminal${resume ? `?resume=${encodeURIComponent(resume)}` : ""}`,
      jsonInit("POST", {}),
    ),
  closeDshTerminal: (id: string) =>
    request<{ ok: boolean; killed: boolean }>(`/api/instances/${encodeURIComponent(id)}/terminal`, {
      method: "DELETE",
    }),
  revealDsh: (id: string, target: "home" | "workspace") =>
    request<{ opened: string }>(`/api/instances/${encodeURIComponent(id)}/reveal`, jsonInit("POST", { target })),
  openBrandTerminal: (brand: BrandId, id: string, resume?: string, kernel?: string) => {
    const query = new URLSearchParams();
    if (resume) query.set("resume", resume);
    if (kernel) query.set("kernel", kernel);
    const suffix = query.size > 0 ? `?${query.toString()}` : "";
    return request<TerminalOpen>(
      `/api/brands/${brand}/instances/${encodeURIComponent(id)}/terminal${suffix}`,
      jsonInit("POST", {}),
    );
  },
  /// Claude Web 的两个内核（社区引擎 / 官方）；别的品类有且只有一个 TUI，会回 404。
  brandTerminalKernels: (brand: BrandId, id: string) =>
    request<{ kernels: TerminalKernel[]; default: string | null }>(
      `/api/brands/${brand}/instances/${encodeURIComponent(id)}/terminals`,
    ),
  closeBrandTerminal: (brand: BrandId, id: string) =>
    request<{ ok: boolean; killed: boolean }>(
      `/api/brands/${brand}/instances/${encodeURIComponent(id)}/terminal`,
      { method: "DELETE" },
    ),
  revealBrand: (brand: BrandId, id: string, target: "home" | "workspace" | "log") =>
    request<{ opened: string }>(
      `/api/brands/${brand}/instances/${encodeURIComponent(id)}/reveal`,
      jsonInit("POST", { target }),
    ),
  developerStatus: () => request<DeveloperStatus>("/api/developer/status"),
  elevateDeveloper: (password: string) =>
    request<{ elevated: boolean; expiresAt: string; token?: string }>(
      "/api/developer/elevate",
      jsonInit("POST", { password }),
    ),
  /** 已提权的前提下把提权延长到 3 小时后；未提权时后端拒绝 */
  extendDeveloper: () =>
    request<{ elevated: boolean; expiresAt: string }>("/api/developer/extend", { method: "POST" }),
  /** 立即停止当前会话的提权（不需要当前处于提权状态，幂等） */
  revokeDeveloper: () => request<{ elevated: boolean }>("/api/developer/revoke", { method: "POST" }),
  /** 开发者模式下翻转 Web Shell 开关；需要先提权 */
  setWebShell: (enabled: boolean) =>
    request<{ ok: boolean; enabled: boolean }>("/api/developer/web-shell", jsonInit("POST", { enabled })),
  /** 设置开发者守卫的作用域；需要先提权，且必须已启用 HTTPS */
  setDeveloperGuard: (scope: DeveloperGuardScope) =>
    request<{ ok: boolean; scope: string }>("/api/developer/guard", jsonInit("POST", { scope })),
  /** 品牌实例页（设置 → 品牌实例）：下载源 + 每个品牌的策略与实例。
   *  读不需要开发者密码；写操作（策略、下载源、测速）需要提权。 */
  brandSettings: () => request<BrandSettings>("/api/settings/brands"),
  setBrandPolicy: (
    brand: BrandId,
    body: { singleInstance: boolean; survivor: string | null; lockedRoute: boolean; sourceBuild: boolean },
  ) => request<{ ok: boolean }>(`/api/settings/brands/policy/${brand}`, jsonInit("PUT", body)),
  setBrandSources: (body: BrandSourcesBody) =>
    request<BrandSettings>("/api/settings/brands/sources", jsonInit("POST", body)),
  /** 从面板这台机器测一个候选源；`url` 省略时测当前生效的那个 */
  probeBrandSource: (kind: "pypi" | "python" | "archive" | "release", brand?: BrandId, url?: string) =>
    request<BrandSourceProbe>("/api/settings/brands/test", jsonInit("POST", { kind, brand, url })),
  /** 紧急停机：按下之前先看清单（会杀掉哪些实例与终端） */
  estopPreview: () => request<EmergencyStopPreview>("/api/developer/estop/preview"),
  /** 紧急停机：强杀所有实例进程树并关掉所有终端，1 小时内不能解除 */
  estopEngage: (reason: string) =>
    request<{
      engaged: boolean;
      /** 已处于停机状态时后端不再杀一遍，也不会重算冷却；此时没有 terminals/processes。 */
      alreadyEngaged?: boolean;
      terminals?: number;
      processes?: number;
      releasesAt?: string;
    }>("/api/developer/estop/engage", jsonInit("POST", { confirm: "ENGAGE-EMERGENCY-STOP", reason })),
  /** 紧急停机：冷却到点后才能解除（否则后端 409 带 remainingSeconds） */
  estopRelease: () => request<{ engaged: boolean }>("/api/developer/estop/release", { method: "POST" }),
  setDeveloperPassword: (
    adminPassword: string,
    password: string,
    confirm: string,
    currentDevPassword?: string,
  ) =>
    request<{ ok: boolean; devPasswordSet: boolean }>(
      "/api/developer/password",
      jsonInit("POST", { adminPassword, password, confirm, currentDevPassword }),
    ),
  clearDeveloperPassword: (adminPassword: string) =>
    request<{ ok: boolean; devPasswordSet: boolean }>(
      "/api/developer/password/clear",
      jsonInit("POST", { adminPassword }),
    ),


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

  // 取消任务：POST /api/tasks/{id}/cancel（TasksApiServlet）。它先杀这次任务起的进程树，
  // 再中断工作线程——下载与解包循环只认这个中断标志。
  cancelTask: (taskId: string) => request<unknown>(`/api/tasks/${taskId}/cancel`, { method: "POST" }),

  task: (taskId: string) => request<Task>(`/api/tasks/${encodeURIComponent(taskId)}`),

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

  // 面板精选的 TUI 终端插件清单（全局常量），以及每个实例上它们的安装状态。
  // 安装/卸载走 `/api/instances/{id}/tui-plugins/*`：TUI 插件是 app 插件，必须装进它
  // 自己的 profile —— 装进实例的 web profile 会和 dsh-web-app 撞在同一份 argv 上，实例就起不来了。
  // profile 由服务端按清单决定，请求里只说包名。
  tuiPlugins: () => request<{ plugins: TuiPluginItem[] }>("/api/dsh/tui-plugins"),

  instanceTuiPlugins: (id: string) =>
    request<{ plugins: TuiPluginState[] }>(`/api/instances/${id}/tui-plugins`),

  installTuiPlugin: (id: string, packageName: string) =>
    request<{ taskId: string }>(
      `/api/instances/${id}/tui-plugins/install`,
      jsonInit("POST", { package: packageName }),
    ),

  removeTuiPlugin: (id: string, packageName: string) =>
    request<{ taskId: string }>(
      `/api/instances/${id}/tui-plugins/remove`,
      jsonInit("POST", { package: packageName }),
    ),

  approveTask: (taskId: string, allow: boolean) =>
    request<unknown>(`/api/tasks/${taskId}/approve`, jsonInit("POST", { allow })),

  // ---------- Phase 3：账户 ----------


  accounts: () => request<{ accounts: Account[] }>("/api/accounts"),

  createAccount: (body: CreateAccountBody) =>
    request<{ account: Account; verified?: boolean; verifyError?: string }>(
      "/api/accounts",
      jsonInit("POST", body),
    ),

  verifyAccount: (name: string) =>
    request<{ ok: boolean; models?: string[]; error?: string; note?: string }>(
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

  /** 生成（或复用）自签名证书并在同一端口切到 HTTPS；浏览器会提示证书不受信任 */
  enableSelfSignedTls: () =>
    request<{ ok: boolean; https?: boolean; restartRequired?: boolean; error?: string; fingerprintSha256?: string }>(
      "/api/settings/tls/self-signed",
      { method: "POST" },
    ),

  // ---------- 下载源（npm registry） ----------

  getRegistry: () => request<RegistrySettings>("/api/settings/registry"),

  /** 选预设传 { preset }；手填传 { preset: "custom", registry }。成功时回读同 GET 的形状 */
  setRegistry: (body: { preset: string; registry?: string }) =>
    request<RegistrySettings>("/api/settings/registry", jsonInit("POST", body)),

  /** 测速：不传地址就测当前生效的那个源 */
  testRegistry: (registry?: string) =>
    request<RegistryTestResult>("/api/settings/registry/test", jsonInit("POST", registry ? { registry } : {})),

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

  // ---------- 壁纸 ----------

  /** 可选壁纸清单：内置的在前，用户上传的按时间排在后面 */
  wallpapers: () => request<WallpaperList>("/api/wallpapers"),

  // multipart：只收静态位图，判据在服务端（魔数 + 动图特征 + 真读一遍图片头）
  uploadWallpaper: (file: File, name?: string) => {
    const fd = new FormData();
    fd.append("file", file);
    if (name) fd.append("name", name);
    return request<Wallpaper>("/api/wallpapers", { method: "POST", body: fd });
  },

  deleteWallpaper: (id: string) =>
    request<void>(`/api/wallpapers/${encodeURIComponent(id)}`, { method: "DELETE" }),

  // ---------- Phase 4：导出 ----------

  /** 导出表单的可选项：能随包的 home 内容 + 本机已装的 dsh 版本（从不猜，由后端列） */
  exportChoices: (id: string) => request<ExportChoices>(`/api/instances/${id}/export`),

  exportInstance: (
    id: string,
    body: {
      name?: string;
      includeSessions?: boolean;
      /** 白名单：随包分发的 home 相对路径，缺省/空即不分发任何 home 内容 */
      home?: string[];
      /** 写进 manifest 的兼容集，实例自身版本由后端固定排在首位 */
      dshVersions?: string[];
    },
  ) => request<{ taskId: string }>(`/api/instances/${id}/export`, jsonInit("POST", body)),

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

  createZcodeInstance: (body: { name: string; baseUrl?: string; apiKey?: string; workspacePath?: string }) =>
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

  /// ZCode 的原地终端：`node <发行包>/bin/zcode.mjs`（不带 --web，即官方 TUI）。
  /// 协议与品牌实例相同，见 docs/terminal-protocol.md。
  openZcodeTerminal: (id: string) =>
    request<TerminalOpen>(`/api/zcode/instances/${encodeURIComponent(id)}/terminal`, jsonInit("POST", {})),
  closeZcodeTerminal: (id: string) =>
    request<{ ok: boolean; killed: boolean }>(`/api/zcode/instances/${encodeURIComponent(id)}/terminal`, {
      method: "DELETE",
    }),

  zcodeLogs: (id: string, tail = 200) =>
    request<{ lines: string[] }>(`/api/zcode/instances/${encodeURIComponent(id)}/logs?tail=${tail}`),

  /** 插件通道（预留）：ZCode 品类现在不支持插件，形状与品牌实例一致 */
  zcodePlugins: (id: string) =>
    request<InstancePlugins>(`/api/zcode/instances/${encodeURIComponent(id)}/plugins`),

  /** 已安装的发行包（构造器装出来的那些） */
  zcodeReleases: () => request<{ releases: ZcodeRelease[] }>("/api/zcode/releases"),

  /** 面板内构建：当前状态 */
  zcodeBuild: () => request<ZcodeBuildStatus>("/api/zcode/build"),

  /** 面板内构建：开始（下载源码 → 打补丁 → pnpm 构建 → 安装） */
  startZcodeBuild: (version: string) =>
    request<ZcodeBuildStatus>("/api/zcode/build", jsonInit("POST", { version })),

  zcodeBuildLog: (tail = 400) => request<{ lines: string[] }>(`/api/zcode/build/log?tail=${tail}`),

  // ---------- 第三方品牌实例（kimi / opencode，REST 挂 /api/brands/<brand>/*） ----------

  brandVersions: (brand: BrandId) => request<BrandVersions>(`/api/brands/${brand}/versions`),

  brandReleases: (brand: BrandId) =>
    request<{ releases: { version: string; path: string; current: boolean }[] }>(
      `/api/brands/${brand}/releases`,
    ),

  /** 开始安装指定版本；品牌内同时只允许一个安装（409） */
  brandInstall: (brand: BrandId, version: string) =>
    request<{ taskId: string }>(`/api/brands/${brand}/install`, jsonInit("POST", { version })),

  brandInstallStatus: (brand: BrandId) =>
    request<BrandInstallStatus>(`/api/brands/${brand}/install`),

  brandInstallLog: (brand: BrandId, tail = 300) =>
    request<{ lines: string[] }>(`/api/brands/${brand}/install/log?tail=${tail}`),

  brandInstances: (brand: BrandId) =>
    request<{ instances: BrandInstance[] }>(`/api/brands/${brand}/instances`),

  /** version 缺省用已装最新；一个版本没装 → 400 */
  createBrandInstance: (brand: BrandId, body: { name: string; version?: string }) =>
    request<{ instance: BrandInstance }>(`/api/brands/${brand}/instances`, jsonInit("POST", body)),

  /** 运行中改 version → 409；未安装的 version → 400 */
  /** baseUrl 目前只有 claude-web 接受（ANTHROPIC_BASE_URL）；传空串表示清除 */
  patchBrandInstance: (brand: BrandId, id: string, body: { name?: string; version?: string; baseUrl?: string }) =>
    request<{ instance: BrandInstance }>(
      `/api/brands/${brand}/instances/${encodeURIComponent(id)}`,
      jsonInit("PATCH", body),
    ),

  /** 运行中删除 → 409 */
  deleteBrandInstance: (brand: BrandId, id: string) =>
    request<unknown>(`/api/brands/${brand}/instances/${encodeURIComponent(id)}`, { method: "DELETE" }),

  /** 实例自己的管理员凭据（目前只有 octop）：读，或带 password 重置（空串=随机生成） */
  brandAdminPassword: (
    brand: BrandId,
    id: string,
    body: { password?: string; adminPassword?: string },
  ) =>
    request<BrandAdminCredentials>(
      `/api/brands/${brand}/instances/${encodeURIComponent(id)}/admin-password`,
      jsonInit("POST", body),
    ),

  /** 同步阻塞至就绪（最多 60s）；error 状态带原因 */
  launchBrand: (brand: BrandId, id: string) =>
    request<{ state: string; error?: string }>(
      `/api/brands/${brand}/instances/${encodeURIComponent(id)}/launch`,
      jsonInit("POST", {}),
    ),

  stopBrand: (brand: BrandId, id: string) =>
    request<{ state: string }>(
      `/api/brands/${brand}/instances/${encodeURIComponent(id)}/stop`,
      jsonInit("POST", {}),
    ),

  /** 未运行 → 409 */
  openBrand: (brand: BrandId, id: string) =>
    request<{ url: string }>(`/api/brands/${brand}/instances/${encodeURIComponent(id)}/open`),

  brandLogs: (brand: BrandId, id: string, tail = 300) =>
    request<{ lines: string[] }>(
      `/api/brands/${brand}/instances/${encodeURIComponent(id)}/logs?tail=${tail}`,
    ),

  /** 插件通道（预留）：品牌实例现在不支持插件，supported=false + 空列表 */
  brandPlugins: (brand: BrandId, id: string) =>
    request<InstancePlugins>(`/api/brands/${brand}/instances/${encodeURIComponent(id)}/plugins`),

  // ---- Node.js 工具链（裸部署一键供给，设置 → Node.js） ----
  nodeStatus: () =>
    request<NodeStatus>("/api/node"),

  /** 出站 TLS 的授权状态与一次性确认（见 NetworkTrust）。 */
  networkTrust: {
    state: () => request<NetworkTrust>("/api/network/trust"),
    accept: (minutes: number, acknowledge: string) =>
      request<NetworkTrust>("/api/network/trust", jsonInit("POST", { minutes, acknowledge })),
    revoke: () => request<NetworkTrust>("/api/network/trust", { method: "DELETE" }),
  },
  nodeInstall: (body: { version?: string; source?: string }) =>
    request<{ taskId: string }>("/api/node/install", jsonInit("POST", body)),
  nodeUninstall: (version: string) =>
    request<{ ok: boolean }>("/api/node/uninstall", jsonInit("POST", { version })),
  /** 日志保留窗口（关于 → 日志）。PUT 要开发者提权，且只能往上调。 */
  panelLogs: {
    retention: () => request<LogRetention>("/api/settings/logs"),
    setRetention: (body: { auditDays?: number; panelDays?: number }) =>
      request<LogRetention>("/api/settings/logs", jsonInit("PUT", body)),
    /** 界面能看的日志：服务端写死最近几天，不随保留窗口变。 */
    recent: () => request<{ viewDays: number; files: LogFileEntry[] }>("/api/settings/logs/recent"),
    tail: (name: string, tail = 200) =>
      request<{ name: string; lines: string[] }>(
        `/api/settings/logs/recent/${encodeURIComponent(name)}?tail=${tail}`,
      ),
  },
  mc: {
    feature: () => request<McFeature>("/api/mc/feature"),
    eula: () => request<McEula>("/api/mc/eula"),
    terms: () => request<McTerms>("/api/mc/terms"),
    enable: (body: {
      eulaAcknowledged: boolean;
      termsAcknowledged: boolean;
      eulaHash: string;
      termsVersion: number;
      termsTextHash: string;
      sawDeclineOption: boolean;
      uiVersion: string;
    }) => request<McFeature>("/api/mc/feature/enable", jsonInit("POST", body)),
    stopFeature: () => request<McFeature>("/api/mc/feature/stop", jsonInit("POST", {})),
    confirmClose: () => request<McFeature>("/api/mc/feature/confirm-close", jsonInit("POST", {})),
    cancelClose: () => request<McFeature>("/api/mc/feature/cancel-close", jsonInit("POST", {})),
    decline: () => request<{ ok: boolean }>("/api/mc/feature/decline", jsonInit("POST", {})),
    versions: () => request<{ versions: McVersion[] }>("/api/mc/versions"),
    storage: () => request<McStorage>("/api/mc/storage"),
    suggestPorts: () => request<{ port: number; rconPort: number }>("/api/mc/ports"),
    instances: () => request<{ instances: McInstance[] }>("/api/mc/instances"),
    instance: (id: string) =>
      request<McInstance>(`/api/mc/instances/${encodeURIComponent(id)}`),
    create: (body: {
      name: string;
      version: string;
      memoryMb?: number;
      levelName?: string;
      seed?: string;
      port?: number;
      rconPort?: number;
      storage?: "external" | "data";
    }) => request<McInstance>("/api/mc/instances", jsonInit("POST", body)),
    rename: (id: string, name: string) =>
      request<McInstance>(`/api/mc/instances/${encodeURIComponent(id)}`, jsonInit("PATCH", { name })),
    update: (
      id: string,
      body: { name?: string; port?: number; rconPort?: number; memoryMb?: number },
    ) => request<McInstance>(`/api/mc/instances/${encodeURIComponent(id)}`, jsonInit("PATCH", body)),
    remove: (id: string) =>
      request<{ ok: boolean; backup: string }>(`/api/mc/instances/${encodeURIComponent(id)}`, { method: "DELETE" }),
    start: (id: string) =>
      request<McInstance>(`/api/mc/instances/${encodeURIComponent(id)}/start`, jsonInit("POST", {})),
    stop: (id: string) =>
      request<McInstance>(`/api/mc/instances/${encodeURIComponent(id)}/stop`, jsonInit("POST", {})),
    kill: (id: string) =>
      request<McInstance>(`/api/mc/instances/${encodeURIComponent(id)}/kill`, jsonInit("POST", {})),
    install: (id: string, version: string) =>
      request<{ taskId: string }>(
        `/api/mc/instances/${encodeURIComponent(id)}/install`,
        jsonInit("POST", { version }),
      ),
    backups: (id: string) =>
      request<{ backups: McBackup[] }>(`/api/mc/instances/${encodeURIComponent(id)}/backups`),
    createBackup: (id: string) =>
      request<McBackup>(`/api/mc/instances/${encodeURIComponent(id)}/backups`, jsonInit("POST", {}), {
        timeoutMs: 5 * 60 * 1000,
      }),
    deleteBackup: (id: string, name: string) =>
      request<{ ok: boolean }>(
        `/api/mc/instances/${encodeURIComponent(id)}/backups/${encodeURIComponent(name)}`,
        { method: "DELETE" },
      ),
    restoreBackup: (id: string, name: string) =>
      request<{ ok: boolean; safety: string }>(
        `/api/mc/instances/${encodeURIComponent(id)}/backups/${encodeURIComponent(name)}/restore`,
        jsonInit("POST", {}),
        { timeoutMs: 5 * 60 * 1000 },
      ),
    properties: (id: string) =>
      request<{ properties: Record<string, string> }>(
        `/api/mc/instances/${encodeURIComponent(id)}/properties`,
      ),
    saveProperties: (id: string, values: Record<string, string>) =>
      request<{ properties: Record<string, string> }>(
        `/api/mc/instances/${encodeURIComponent(id)}/properties`,
        jsonInit("PUT", values),
      ),
    logs: (id: string) =>
      request<{ lines: string[] }>(`/api/mc/instances/${encodeURIComponent(id)}/logs?tail=200`),
    consoleTicket: (id: string) =>
      request<TerminalOpen>(`/api/mc/instances/${encodeURIComponent(id)}/console/ticket`, jsonInit("POST", {})),
    consoleInput: (id: string, line: string) =>
      request<{ ok: boolean }>(`/api/mc/instances/${encodeURIComponent(id)}/console/input`, jsonInit("POST", { line })),
    players: (id: string) =>
      request<{ online: number; max: number; names: string[] }>(
        `/api/mc/instances/${encodeURIComponent(id)}/players`,
      ),
    playerAction: (id: string, name: string, action: "kick" | "op" | "deop" | "ban") =>
      request<{ ok: boolean; answer: string }>(
        `/api/mc/instances/${encodeURIComponent(id)}/players/${encodeURIComponent(name)}/${action}`,
        jsonInit("POST", {}),
      ),
    exportWorld: async (id: string): Promise<Blob> => {
      const res = await fetch(`api/mc/instances/${encodeURIComponent(id)}/world/export`, {
        credentials: "same-origin",
        cache: "no-store",
      });
      if (!res.ok) throw new ApiError(res.status, await res.text());
      return res.blob();
    },
    importWorld: async (id: string, file: File): Promise<{ ok: boolean; safety: string }> => {
      const res = await fetch(`api/mc/instances/${encodeURIComponent(id)}/world/import`, {
        method: "POST",
        credentials: "same-origin",
        cache: "no-store",
        body: file,
        headers: { "Content-Type": "application/zip" },
      });
      if (!res.ok) throw new ApiError(res.status, await res.text());
      return (await res.json()) as { ok: boolean; safety: string };
    },
    importPlayerLists: async (
      id: string,
      file: File,
    ): Promise<{ ok: boolean; files: Record<string, number> }> => {
      const res = await fetch(`api/mc/instances/${encodeURIComponent(id)}/player-lists`, {
        method: "POST",
        credentials: "same-origin",
        cache: "no-store",
        body: file,
        headers: { "Content-Type": "application/zip" },
      });
      if (!res.ok) throw new ApiError(res.status, await res.text());
      return (await res.json()) as { ok: boolean; files: Record<string, number> };
    },
  },
};

/// GET /api/mc/feature：功能开关与内存锁。
export interface McFeature {
  state: "DISABLED" | "ENABLED";
  enabled: boolean;
  locked: boolean;
  lockRemainingSeconds: number;
  keepFeature: boolean | null;
  /** 同意记录（有则回看用；功能关闭后仍保留）。 */
  termsVersion?: number;
  termsAcceptedAt?: string;
  acceptedBy?: string;
  termsTextHash?: string;
  eulaHash?: string;
  eulaRetrievedAt?: string;
  ackMethod?: string;
}

export interface McEula {
  text: string;
  hash: string;
  fetchedAt: string;
  source: string;
}

export interface McTerms {
  version: number;
  text: string;
  hash: string;
}

/// GET /api/settings/logs：磁盘上留多久 + 界面能看多久 + 时钟可不可信。
export interface LogRetention {
  auditDays: number;
  panelDays: number;
  floorDays: number;
  ceilingDays: number;
  viewDays: number;
  /// 系统时钟可不可信。不可信时清理一个文件都不删（要重启面板才恢复）。
  clock?: {
    trusted: boolean;
    reason?: string | null;
    skewMillis: number;
    checkedAt?: string | null;
  };
  /// 最近一轮清理的结果；`skippedReason` 非空表示那轮什么都没删。
  lastSweep?: {
    deleted: number;
    at?: string | null;
    skippedReason?: string | null;
  };
}

/// GET /api/settings/logs/recent 里的一份日志。
export interface LogFileEntry {
  name: string;
  kind: "audit" | "panel";
  bytes: number;
  modified: string;
  compressed: boolean;
}

export interface McVersion {
  id: string;
  type: string;
}

export interface McInstance {
  id: string;
  name: string;
  version: string;
  port: number;
  rconPort: number;
  memoryMb: number;
  levelName: string;
  seed?: string;
  createdAt: number;
  state: "STOPPED" | "STARTING" | "RUNNING" | "STOPPING" | "FAILED";
  error?: string;
  consoleAvailable: boolean;
  /** 服务器目录（存档、server.jar、日志都在这儿） */
  dir: string;
  /** 在专用目录（容器里是 /home/hdsl/mc）还是旧的 data 目录 */
  storage: "external" | "data";
}

/** GET /api/mc/storage：两个可选存储位置与默认值 */
export interface McStorage {
  external: { path: string; writable: boolean };
  data: { path: string; writable: boolean } | null;
  default: "external" | "data";
}

export interface McBackup {
  name: string;
  createdAt: string;
  bytes: number;
}

/// GET /api/node 的快照：系统 PATH 探测 + 面板托管运行时列表
export interface NodeStatus {
  system: {
    node: string;
    nodeVersion: string;
    npm?: string;
    npmVersion?: string;
    pnpm?: string;
    pnpmVersion?: string;
  } | null;
  managed: { version: string; suitable: boolean; node: string; pnpm: boolean }[];
  requirement: string;
}

/** 出站 TLS 的策略：strict 是默认（只认 Java 的 cacerts）；
 *  accept_unverified 是操作者确认过的窗口（到 acceptedUntil 为止），期间无法校验的
 *  证书会被接受并在日志里记下。acknowledge 是必须逐字回传的那句话。 */
export interface NetworkTrust {
  mode: "strict" | "accept_unverified";
  acceptedUntil: string | null;
  acknowledge: string;
  maxMinutes: number;
  lastTrouble?: { host: string; reason: string; at: string } | null;
  explanation?: string;
}

/** 打开终端的结果：POSIX 返回 ticket + ws，Windows 返回 desktop。 */
export interface TerminalOpen {
  mode: "pty" | "desktop" | "pipe";
  instance: string;
  brand?: string;
  ticket?: string;
  ws?: string;
}
