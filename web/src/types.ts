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
  endpoint: string;
  envVar: string;
  kinds: string[];
}

export interface Account {
  name: string;
  vendor: string;
  kind: string;
  label: string;
  model: string;
  endpoint: string;
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
  | { type: "task"; taskId: string; state: string; message?: string; fraction?: number; approval?: TaskApproval; result?: TaskResult }
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
  vendors: Vendor[] | null;
  vendorsLoading: boolean;
  vendorsError: string | null;
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
