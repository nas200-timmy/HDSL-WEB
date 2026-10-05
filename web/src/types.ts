// REST 契约类型（与后端冻结契约一一对应）

export interface Health {
  status: string;
  version: string;
  uptimeSec: number;
}

export interface VersionInfo {
  version: string;
  time: string | number;
  channel: string;
  installable: boolean;
}

export interface Instance {
  id: string;
  name: string;
  version: string;
  homeMode: string;
  port: number | null;
  portMode: string;
  state: string;
  url?: string;
  uptimeSec?: number;
  icon?: string;
  /** 实例绑定的账户名；null/缺省 = 不使用 */
  account?: string | null;
  /** 详情接口附带：最近一次安装的进度/结果（失败原因在这里） */
  installProgress?: InstallProgress;
}

/** 安装进度（GET /api/instances/{id} 的 installProgress 字段）。 */
export interface InstallProgress {
  /** running | pending | done | failed | cancelled | none */
  state: string;
  message: string;
  fraction: number;
}

export interface LogLine {
  level: string;
  time: string;
  text: string;
}

export interface Task {
  id: string;
  kind: string;
  state: string;
  message?: string;
  fraction?: number;
  error?: string;
  /** 任务归属的实例；null/缺省 = 全局任务（导出、插件目录刷新等） */
  instance?: string | null;
  /** state 为 waiting_approval 时携带的审批信息（build-scripts） */
  approval?: TaskApproval;
  /** 任务完成时携带的结果（安装整合包 → instanceId；导出 → filename） */
  result?: TaskResult;
}

/** 任务终态结果体（字段按任务类型可选） */
export interface TaskResult {
  instanceId?: string;
  filename?: string;
}

/** Task 附带本地更新时间，用于"最近活跃任务"启发式匹配 */
export interface TaskRec extends Task {
  seenAt: number;
}

export interface Toast {
  id: number;
  kind: "success" | "error" | "info";
  text: string;
}

export interface CrashInfo {
  exitCode?: number;
  crashTail?: string;
}

// ---------- Phase 3：插件 ----------

export interface PluginCatalogItem {
  name: string;
  owner: string;
  url: string;
  category: string;
  description: string;
  descriptionZh?: string;
  npm: string;
  version: string;
  stars: number;
  downloads: number;
  tarball: string;
  added: string;
}

export interface InstalledPlugin {
  name: string;
  version: string;
  bundled?: boolean;
  pending?: boolean;
}

export interface TaskApproval {
  kind: string;
  keys: string[];
}

// ---------- Phase 3：账户 ----------

export interface Vendor {
  id: string;
  name: string;
  endpoint: string | null;
  envVar: string;
  kinds: string[];
  preferred: boolean;
}

/** 模型目录里的供应商（GET /api/models/providers 的 providers[] 一条） */
export interface ModelProvider {
  id: string;
  name: string;
  /** dsh 认识的供应商才有固定端点；null = 由账户/区域决定，需用户自填 */
  endpoint: string | null;
  /** 密钥环境变量名，仅用于界面提示 */
  envVar: string;
  /** null = dsh 的三种协议都路由不了这家，卡片禁用 */
  protocol: string | null;
  protocolSource: "dsh" | "directory" | null;
  modelCount: number;
  /** false = 只在 models.dev 目录里，dsh 自家目录没有它 */
  known: boolean;
  preferred: boolean;
  /** 用户以前自己加过的厂商 */
  custom: boolean;
  doc: string | null;
  catalogId: string | null;
  npm: string | null;
}

/** 模型目录里的单个模型（GET /api/models/providers/{id} 的 models[] 一条） */
export interface ModelInfo {
  id: string;
  name: string;
  context: number | null;
  output: number | null;
  reasoning: boolean;
  reasoningEfforts: string[];
  toolCall: boolean;
  attachment: boolean;
  costInput: number | null;
  costOutput: number | null;
  status: string | null;
}

export interface Account {
  name: string;
  vendor: string;
  kind: string | null;
  label: string | null;
  model: string | null;
  endpoint: string | null;
  maskedKey: string;
  skinSet?: boolean;
}

export interface CreateAccountBody {
  vendor?: string;
  endpoint?: string;
  apiKey: string;
  label?: string;
  model?: string;
  kind?: string;
  /** 只在供应商是「目录里新发现的」时才生效（dsh 自家目录里的厂商以 dsh 的协议为准） */
  protocol?: string;
}

export interface PatchAccountBody {
  label?: string;
  model?: string;
  apiKey?: string;
}

// ---------- Phase 3：TLS ----------

export interface TlsSettings {
  https: boolean;
  /** "none" | "self-signed" | "uploaded" | "pkcs12" */
  source: string;
  subject?: string;
  issuer?: string;
  sans?: string[];
  expires?: string | number;
  fingerprintSha256?: string;
}

export interface TlsUploadResult {
  ok: boolean;
  restartRequired?: boolean;
  https?: boolean;
  error?: string;
}

// ---------- Phase 3：下载源（npm registry） ----------

/** 服务端下发的下载源预设；界面不硬编码这张表，直接渲染 */
export interface RegistryPreset {
  id: string;
  label: string;
  url: string;
  note?: string;
}

/** GET /api/settings/registry（POST 成功时也回同样的形状，用它回读省一次请求） */
export interface RegistrySettings {
  /** environment | npmjs | npmmirror | ustc | tencent | huawei | custom */
  preset: string;
  /** 手填的地址（规范化后）；只在 preset = custom 时生效，服务端切到预设后仍保留上次填的值 */
  registry: string;
  /** 最终会用的那个源：面板设置 → 环境变量 → 官方默认 */
  effective: string;
  /** effective 的来源："setting" | "environment" | "default" */
  source: string;
  presets: RegistryPreset[];
}

/** POST /api/settings/registry/test：由面板所在的那台机器实测一次源能不能用、多快 */
export interface RegistryTestResult {
  /** 被测的地址（服务端规范化后的） */
  registry: string;
  ok: boolean;
  /** HTTP 状态码；连不上时为 null */
  status: number | null;
  millis: number;
  /** ok = false 时的原因 */
  error?: string;
}

// ---------- Phase 4：整合包 / 导出 ----------

export interface MarketPack {
  id: string;
  name: string;
  author: string;
  description: string;
  descriptionZh?: string;
  version?: string;
  downloads?: number;
  avatarUrl?: string;
  updatedAt?: string | number;
}

/** 市场详情：契约只冻结 {pack:{...}}，版本列表/readme 字段按存在与否兜底渲染 */
export interface MarketPackDetail extends MarketPack {
  versions?: unknown[];
  readme?: string;
  readmeUrl?: string;
}

export interface ExportRecord {
  filename: string;
  sizeBytes: number;
  createdAt: string | number;
}

// ---------- Phase 4：会话 / 工作区 / 技能 ----------

export interface SessionInfo {
  id: string;
  title?: string;
  messageCount?: number;
  updatedAt?: string | number;
  sizeBytes?: number;
}

export interface WorkspaceInfo {
  id: string;
  name?: string;
  path?: string;
  updatedAt?: string | number;
}

export interface SkillInfo {
  id: string;
  name: string;
  description?: string;
  enabled?: boolean;
}

// ---------- Phase 4：体检 ----------

export interface DoctorCheck {
  id: string;
  name: string;
  status: "ok" | "warn" | "fail" | string;
  detail?: string;
}

export interface DoctorReport {
  checks: DoctorCheck[];
  generatedAt: string | number;
}

// ---------- Phase 4：ACP 控制台 ----------

/** acp-update 事件的 update 体：kind 之外字段按 ACP 会话更新类型各异，宽松读取 */
export interface AcpSessionUpdate {
  kind?: string;
  [key: string]: unknown;
}

// ---------- WebSocket 事件（服务端 → 客户端） ----------

export type WsEvent =
  | { type: "instance-state"; instance: string; state: string; url?: string; exitCode?: number; crashTail?: string }
  | { type: "log"; instance: string; level: string; line: string }
  | { type: "task"; taskId: string; instance?: string | null; state: string; message?: string; fraction?: number; error?: string; approval?: TaskApproval; result?: TaskResult }
  | { type: "instance-created" | "instance-deleted" | "instance-updated"; instance: Instance }
  | { type: "accounts-changed" }
  | { type: "acp-ready"; instance: string; session: string; protocolVersion?: number }
  | { type: "acp-error"; instance: string; error: string }
  | { type: "acp-update"; instance: string; session: string; update: AcpSessionUpdate }
  | { type: "acp-finished"; instance: string; session: string; stopReason?: string };

// ---------- 全局应用状态 ----------

export interface AppState {
  username: string | null;
  authChecked: boolean;
  authError: string | null;
  /** 移动版式下主导航抽屉是否展开；桌面版式下无意义 */
  sidebarOpen: boolean;
  /** 服务端是否存在任何用户；false = 已有用户（或尚未询问），true = 需先走初始化引导页 */
  setupRequired: boolean;
  instances: Instance[];
  instancesLoaded: boolean;
  instancesLoading: boolean;
  versions: VersionInfo[] | null;
  versionsLoading: boolean;
  versionsError: string | null;
  tasks: Record<string, TaskRec>;
  logs: Record<string, LogLine[]>;
  crash: Record<string, CrashInfo>;
  /** 运行中实例最近一次拿到的 uptime 采样，用于本地平滑计时 */
  lastUptime: Record<string, { sec: number; at: number }>;
  /** null = 尚未加载或后端不支持（见 accountsError） */
  accounts: Account[] | null;
  accountsLoading: boolean;
  accountsError: string | null;
  /** 合并后的供应商名单（dsh 自家目录 + models.dev）；null = 尚未加载或不可用（见 modelProvidersError） */
  modelProviders: ModelProvider[] | null;
  modelProvidersLoading: boolean;
  modelProvidersError: string | null;
  /** 目录数据的抓取时间（毫秒），用于"x 分钟前更新" */
  modelProvidersAt: number | null;
  /** 实验性 ZCode 品类：发行包检测结果（null = 尚未加载） */
  zcodeDist: ZcodeDistInfo | null;
  zcodeInstances: ZcodeInstance[];
  zcodeLoading: boolean;
  toasts: Toast[];
}

// ---------- REST 请求体 ----------

export interface CreateInstanceBody {
  name: string;
  version: string;
  homeMode: string;
  portMode: string;
  port?: number;
}

export interface PatchInstanceBody {
  name?: string;
  homeMode?: string;
  portMode?: string;
  port?: number;
  autoPort?: boolean;
  /** 绑定账户名；null 表示解绑（不使用） */
  account?: string | null;
}

// ---------- ZCode（实验性） ----------

/// ZCode 实例（GET /api/zcode/instances 的一项）。实验性功能，
/// 定位与限制见 docs/zcode-experimental.md。
export interface ZcodeInstance {
  id: string;
  name: string;
  workspacePath: string;
  baseUrl: string | null;
  /** 是否已保存 API Key；明文存在实例清单里，接口不返回原文 */
  hasApiKey: boolean;
  /** 访问令牌，打开实例 URL 时作为查询串带上 */
  token: string;
  /** 最近一次启动时 ZCode 自选的端口；0 = 未知/未启动 */
  lastPort: number;
  createdAt: number;
  /** created | starting | running | stopped | error */
  state: string;
  /** state = error 时的原因（进程日志尾部） */
  error?: string;
}

/// ZCode 发行包检测结果（GET /api/zcode/dist）。
export interface ZcodeDistInfo {
  present: boolean;
  path: string;
  version: string | null;
}

/// 已安装的 ZCode 发行包（GET /api/zcode/releases 的一项）。
export interface ZcodeRelease {
  version: string;
  path: string;
  builtAt: number;
  /** 是否 `current` 指向它（启动用的就是它） */
  current: boolean;
}

/// 面板内构建的状态（GET/POST /api/zcode/build）。
export interface ZcodeBuildStatus {
  /** idle | running | done | error */
  state: string;
  version: string;
  message: string;
  fraction: number;
  error?: string;
}
