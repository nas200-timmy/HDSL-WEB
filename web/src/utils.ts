/** 状态归一化：后端枚举大小写未知，统一转大写比较 */
export function normState(s: string | null | undefined): string {
  return (s ?? "").toUpperCase();
}

export function isInstanceRunning(state: string | null | undefined): boolean {
  return normState(state) === "RUNNING";
}

const TERMINAL_TASK_STATES = new Set([
  "DONE",
  "SUCCESS",
  "SUCCEEDED",
  "COMPLETED",
  "FAILED",
  "ERROR",
  "CANCELLED",
  "CANCELED",
]);

export function isTerminalTaskState(state: string): boolean {
  return TERMINAL_TASK_STATES.has((state ?? "").toUpperCase());
}

const SUCCESS_TASK_STATES = new Set(["DONE", "SUCCESS", "SUCCEEDED", "COMPLETED"]);

/** 任务终态中的"成功"类（用于读取 result / 触发完成动作） */
export function isSuccessTaskState(state: string): boolean {
  return SUCCESS_TASK_STATES.has((state ?? "").toUpperCase());
}

/** 任务等待审批（build-scripts），属非终态 */
export function isWaitingApproval(state: string | null | undefined): boolean {
  return normState(state) === "WAITING_APPROVAL";
}

export function clamp01(n: number): number {
  return Math.min(1, Math.max(0, n));
}

function pad2(n: number): string {
  return String(n).padStart(2, "0");
}

/** "YYYY-MM-DD HH:mm:ss"；无法解析时原样返回 */
export function formatDateTime(t: string | number | null | undefined): string {
  if (t == null) return "—";
  const d = typeof t === "number" ? new Date(t) : new Date(t);
  if (Number.isNaN(d.getTime())) return String(t);
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())} ${pad2(d.getHours())}:${pad2(d.getMinutes())}:${pad2(d.getSeconds())}`;
}

/** "HH:mm:ss"；用于日志行时间 */
export function formatClock(t: string | null | undefined): string {
  if (!t) return "";
  const d = typeof t === "number" ? new Date(t) : new Date(t);
  if (Number.isNaN(d.getTime())) return t;
  return `${pad2(d.getHours())}:${pad2(d.getMinutes())}:${pad2(d.getSeconds())}`;
}

/** 秒数 → "1天2小时3分" / "2小时3分" / "3分4秒" / "5秒" */
export function formatUptime(sec: number): string {
  const s = Math.max(0, Math.floor(sec));
  const d = Math.floor(s / 86400);
  const h = Math.floor((s % 86400) / 3600);
  const m = Math.floor((s % 3600) / 60);
  const r = s % 60;
  if (d > 0) return `${d}天${h}小时${m}分`;
  if (h > 0) return `${h}小时${m}分`;
  if (m > 0) return `${m}分${r}秒`;
  return `${r}秒`;
}

const CHANNEL_LABELS: Record<string, string> = {
  stable: "稳定版",
  release: "正式版",
  latest: "最新版",
  beta: "测试版",
  preview: "预览版",
  prerelease: "预发布",
  snapshot: "快照",
};

export function channelLabel(ch: string): string {
  return CHANNEL_LABELS[(ch ?? "").toLowerCase()] ?? ch;
}

/** 从实例 url（如 http://127.0.0.1:3100/?token=…）解析实际端口 */
export function parsePortFromUrl(url: string | null | undefined): number | null {
  if (!url) return null;
  const m = /(?:^|\/\/)[^/:]+:(\d+)/.exec(url);
  return m ? Number(m[1]) : null;
}

export function errMsg(e: unknown): string {
  if (e instanceof Error) return e.message;
  return "操作失败";
}

/** "YYYY-MM-DD"；无法解析时原样返回 */
export function formatDate(t: string | number | null | undefined): string {
  if (t == null) return "—";
  const d = new Date(t);
  if (Number.isNaN(d.getTime())) return String(t);
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`;
}

/** 大数字缩写：1234 → "1.2k"，12 → "12" */
export function formatCount(n: number | null | undefined): string {
  if (n == null || !Number.isFinite(n)) return "—";
  if (n >= 1000) {
    const v = n / 1000;
    return `${v >= 100 ? Math.round(v) : v.toFixed(1).replace(/\.0$/, "")}k`;
  }
  return String(n);
}

/** 文件大小：1024 → "1.0 KB" */
export function formatBytes(n: number): string {
  if (!Number.isFinite(n) || n < 0) return "—";
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / (1024 * 1024)).toFixed(2)} MB`;
}
