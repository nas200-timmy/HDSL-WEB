// ---------------------------------------------------------------------------
// 缺 Node 的硬拦
//
// 服务端在实例/品牌安装前会回 `409 node_missing`；这里负责在点下按钮之前
// 就把话说清楚：缺什么、能不能一键装好、以及装好之后原操作是否继续。
//
// 和 `elevation.ts` 同一个模式：动作里 `await requireNode()`，宿主组件在 App
// 顶层渲染，同一时刻只有一个提示框。
// ---------------------------------------------------------------------------

import { useEffect, useState } from "react";
import { refreshSetupStatus } from "./setup";

type Waiter = (ok: boolean) => void;

let waiters: Waiter[] = [];
let seq = 0;
let openSince: number | null = null;
const listeners = new Set<() => void>();

function emit(): void {
  for (const listener of [...listeners]) listener();
}

/** 弹出"缺少 Node.js"提示框；用户选择"装好了继续"时返回 true。 */
export function requestNodeInstall(): Promise<boolean> {
  return new Promise<boolean>((resolve) => {
    waiters.push(resolve);
    openSince = ++seq;
    emit();
  });
}

/** 提示框当前是否打开（供宿主组件渲染）。 */
export function useNodeGateOpen(): boolean {
  const [, force] = useState(0);
  useEffect(() => {
    const listener = () => force((value) => value + 1);
    listeners.add(listener);
    return () => {
      listeners.delete(listener);
    };
  }, []);
  return openSince !== null;
}

/** 关闭提示框：`ok` 决定原操作是否继续。 */
export function settleNodeGate(ok: boolean): void {
  const pending = waiters;
  waiters = [];
  openSince = null;
  emit();
  for (const waiter of pending) waiter(ok);
}

/** 这个错误是不是"缺 Node"那一个（服务端兜底路径）。 */
export function isNodeMissing(error: unknown): boolean {
  const message = error instanceof Error ? error.message : String(error);
  return message.includes("node_missing");
}

/**
 * 动作前的预检：Node 就绪直接放行；缺就弹框，装好返回 true，取消返回 false。
 *
 * 状态读不到时放行——服务端还会再拦一次，前端不该因为一次接口失败
 * 把按钮变成永远点不动。
 */
export async function requireNode(): Promise<boolean> {
  const status = await refreshSetupStatus(true);
  const node = status.items.find((item) => item.id === "node");
  if (!node || node.unknown || node.tone === "ok") return true;
  return requestNodeInstall();
}
