// ---------------------------------------------------------------------------
// ZCode 的“实测过的上游版本”
//
// Windows 上最后一次完整人肉测试是 v3.17.2：构建、反代、工作区、项目、会话和
// agent 都真的跑通过。之后的版本上游改过构建产物（例如 server/entry-http.js
// 里项目 owner 的表达式），面板得跟着补补丁；没重测过的版本不能当成稳定版。
// ---------------------------------------------------------------------------

export const WINDOWS_TESTED_ZCODE = "3.17.2";

function numericParts(version: string): number[] {
  return version
    .replace(/^v/i, "")
    .split(/[.+-]/)
    .map((part) => Number.parseInt(part, 10))
    .filter((part) => Number.isFinite(part));
}

/// 比较两个版本号（忽略前缀 v 与预发布后缀）；返回负数/0/正数。
export function compareZcodeVersion(left: string, right: string): number {
  const a = numericParts(left);
  const b = numericParts(right);
  const length = Math.max(a.length, b.length);
  for (let index = 0; index < length; index += 1) {
    const diff = (a[index] ?? 0) - (b[index] ?? 0);
    if (diff !== 0) return diff;
  }
  return 0;
}

export function isWindowsTestedZcode(version: string): boolean {
  return compareZcodeVersion(version, WINDOWS_TESTED_ZCODE) === 0;
}

/// 比实测版本新：上游可能已经改过面板补丁依赖的锚点。
export function isNewerThanWindowsTested(version: string): boolean {
  return compareZcodeVersion(version, WINDOWS_TESTED_ZCODE) > 0;
}

/// 版本列表里跟在版本号后面的短标签；没有额外含义时为空串。
export function zcodeVersionBadge(version: string): string {
  if (isWindowsTestedZcode(version)) return "Windows 实测稳定";
  if (isNewerThanWindowsTested(version)) return "需面板补丁";
  return "";
}