import { useEffect, useReducer } from "react";
import { useAppState } from "./store";

/**
 * 运行时长平滑计时：以 store 里最近一次拿到的 uptimeSec 为基准，
 * 本地每秒自增，无需轮询后端。
 */
export function useTickingUptime(instanceId: string | null, uptimeSec?: number): number | null {
  const s = useAppState();
  const last = instanceId ? s.lastUptime[instanceId] : undefined;
  const base =
    last ?? (typeof uptimeSec === "number" ? { sec: uptimeSec, at: Date.now() } : null);
  const [, tick] = useReducer((x: number) => x + 1, 0);

  useEffect(() => {
    if (!base) return;
    const timer = window.setInterval(() => tick(), 1000);
    return () => window.clearInterval(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [base?.at, base?.sec, Boolean(base)]);

  return base ? base.sec + Math.floor((Date.now() - base.at) / 1000) : null;
}
