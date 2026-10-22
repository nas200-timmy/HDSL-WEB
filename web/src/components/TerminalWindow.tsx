import { useCallback, useEffect, useRef, useState } from "react";
import type { Terminal as XTerm } from "@xterm/xterm";
import type { FitAddon as XFitAddon } from "@xterm/addon-fit";
import { api, ApiError } from "../api";
import type { TerminalOpen } from "../api";
import type { BrandId } from "../types";
import { I18N } from "../i18n";
import "@xterm/xterm/css/xterm.css";

/** 终端窗口的目标：一个品牌实例或一个 dsh 实例。 */
export interface TerminalTargetInfo {
  kind: "brand" | "dsh";
  brand?: BrandId;
  id: string;
  name: string;
}

type Phase = "opening" | "password" | "running" | "ended" | "detached" | "desktop" | "error";

/** 原地终端（docs/web-shell.md）：xterm 覆盖层 + 服务端 PTY。
 *
 * 关窗只 detach，agent 继续跑；底栏「停止」才杀进程。刷新后重新打开会接回
 * 同一个会话并回放最近的输出（后端对运行中的会话直接发票据）。
 * 打开需要开发者密码提权：403 时先在这里输入一次。 */
export function TerminalWindow({ target, onClose }: { target: TerminalTargetInfo | null; onClose: () => void }) {
  const hostRef = useRef<HTMLDivElement>(null);
  const termRef = useRef<XTerm | null>(null);
  const fitRef = useRef<XFitAddon | null>(null);
  const wsRef = useRef<WebSocket | null>(null);
  const disposedRef = useRef(false);
  const phaseRef = useRef<Phase>("opening");
  const closeTimerRef = useRef<number | null>(null);
  const observerRef = useRef<ResizeObserver | null>(null);

  const [phase, setPhaseState] = useState<Phase>("opening");
  const [message, setMessage] = useState("");
  const [exitCode, setExitCode] = useState<number | null>(null);
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);

  const setPhase = useCallback((next: Phase) => {
    phaseRef.current = next;
    setPhaseState(next);
  }, []);

  const teardown = useCallback(() => {
    if (closeTimerRef.current !== null) {
      window.clearTimeout(closeTimerRef.current);
      closeTimerRef.current = null;
    }
    observerRef.current?.disconnect();
    observerRef.current = null;
    wsRef.current?.close();
    wsRef.current = null;
    termRef.current?.dispose();
    termRef.current = null;
    fitRef.current = null;
  }, []);

  const connect = useCallback(async (data: TerminalOpen) => {
    const { Terminal } = await import("@xterm/xterm");
    const { FitAddon } = await import("@xterm/addon-fit");
    if (disposedRef.current || !hostRef.current || !data.ws) return;

    const term = new Terminal({
      cursorBlink: true,
      fontSize: 13,
      scrollback: 3000,
      theme: {
        background: "#1b1b1f",
        foreground: "#e6e1e5",
        cursor: "#d0bcff",
        selectionBackground: "#4a4458",
      },
    });
    const fit = new FitAddon();
    term.loadAddon(fit);
    term.open(hostRef.current);
    fit.fit();
    termRef.current = term;
    fitRef.current = fit;

    const protocol = window.location.protocol === "https:" ? "wss" : "ws";
    const ws = new WebSocket(`${protocol}://${window.location.host}${data.ws}`);
    ws.binaryType = "arraybuffer";
    wsRef.current = ws;

    term.onData((chunk) => {
      if (ws.readyState === WebSocket.OPEN) {
        ws.send(new TextEncoder().encode(chunk));
      }
    });
    term.onResize(({ cols, rows }) => {
      if (ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ type: "resize", cols, rows }));
      }
    });

    ws.onmessage = (event) => {
      if (typeof event.data === "string") {
        try {
          const control = JSON.parse(event.data) as { type?: string; code?: number };
          if (control.type === "ready") {
            setPhase("running");
            term.focus();
          } else if (control.type === "exit") {
            setExitCode(control.code ?? 0);
            term.write(`\r\n\x1b[90m${I18N["terminal.ended"]} (${control.code ?? 0})\x1b[0m\r\n`);
            setPhase("ended");
            ws.close();
            closeTimerRef.current = window.setTimeout(onClose, 3000);
          }
        } catch {
          term.write(event.data);
        }
      } else {
        term.write(new Uint8Array(event.data as ArrayBuffer));
      }
    };
    ws.onclose = () => {
      if (phaseRef.current === "running") {
        setPhase("detached");
      }
    };

    const observer = new ResizeObserver(() => fit.fit());
    observer.observe(hostRef.current);
    observerRef.current = observer;
  }, [onClose, setPhase]);

  const openSession = useCallback(async () => {
    if (!target) return;
    setPhase("opening");
    setMessage("");
    try {
      const data =
        target.kind === "brand"
          ? await api.openBrandTerminal(target.brand as BrandId, target.id)
          : await api.openDshTerminal(target.id);
      if (disposedRef.current) return;
      if (data.mode === "desktop") {
        setPhase("desktop");
        return;
      }
      await connect(data);
    } catch (e) {
      if (e instanceof ApiError && e.status === 403) {
        setPhase("password");
        return;
      }
      setPhase("error");
      setMessage(e instanceof Error ? e.message : String(e));
    }
  }, [target, connect, setPhase]);

  useEffect(() => {
    if (!target) return;
    disposedRef.current = false;
    void openSession();
    return () => {
      disposedRef.current = true;
      teardown();
    };
  }, [target, openSession, teardown]);

  const submitPassword = async () => {
    if (!password) return;
    setBusy(true);
    setMessage("");
    try {
      await api.elevateDeveloper(password);
      setPassword("");
      await openSession();
    } catch (e) {
      setMessage(e instanceof ApiError && e.status === 429 ? I18N["terminal.password.locked"] : e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  const stopSession = async () => {
    if (!target) return;
    setBusy(true);
    try {
      if (target.kind === "brand") {
        await api.closeBrandTerminal(target.brand as BrandId, target.id);
      } else {
        await api.closeDshTerminal(target.id);
      }
    } catch {
      // 进程可能已经退出；关闭窗口即可。
    } finally {
      setBusy(false);
      teardown();
      onClose();
    }
  };

  if (!target) return null;

  return (
    <div className="terminal-overlay" role="dialog" aria-label={`${target.name} ${I18N["terminal.title"]}`}>
      <div className="terminal-window">
        <div className="terminal-head">
          <span className="terminal-title">
            {target.name} · {I18N["terminal.title"]}
          </span>
          <span className={`terminal-state terminal-state-${phase}`}>{phaseText(phase)}</span>
        </div>

        <div className="terminal-body" ref={hostRef} />

        {phase === "password" && (
          <div className="terminal-password">
            <div className="terminal-password-title">{I18N["terminal.password.prompt"]}</div>
            <input
              className="input"
              type="password"
              autoFocus
              value={password}
              placeholder={I18N["terminal.password.placeholder"]}
              onChange={(e) => setPassword(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter") void submitPassword();
              }}
            />
            <button className="btn btn-raised ripple-host" disabled={busy || !password} onClick={() => void submitPassword()}>
              {I18N["terminal.password.submit"]}
            </button>
            {message && <div className="hint error">{message}</div>}
          </div>
        )}

        {phase === "desktop" && <div className="terminal-note">{I18N["terminal.desktop"]}</div>}
        {phase === "error" && <div className="terminal-note error">{message || I18N["terminal.error"]}</div>}
        {phase === "detached" && <div className="terminal-note">{I18N["terminal.detached"]}</div>}
        {phase === "ended" && <div className="terminal-note">{I18N["terminal.ended"]} ({exitCode ?? 0})</div>}

        <div className="terminal-footer">
          {(phase === "running" || phase === "detached") && (
            <button className="btn btn-text ripple-host" disabled={busy} onClick={() => void stopSession()}>
              {I18N["terminal.stop"]}
            </button>
          )}
          {(phase === "detached" || phase === "error") && (
            <button className="btn btn-text ripple-host" onClick={() => void openSession()}>
              {I18N["terminal.reconnect"]}
            </button>
          )}
          <span className="spacer" />
          <button className="btn btn-text ripple-host" onClick={onClose}>
            {I18N["terminal.close"]}
          </button>
        </div>
      </div>
    </div>
  );
}

function phaseText(phase: Phase): string {
  switch (phase) {
    case "opening":
      return I18N["terminal.state.opening"];
    case "password":
      return I18N["terminal.state.locked"];
    case "running":
      return I18N["terminal.state.running"];
    case "ended":
      return I18N["terminal.state.ended"];
    case "detached":
      return I18N["terminal.state.detached"];
    case "desktop":
      return I18N["terminal.state.desktop"];
    default:
      return I18N["terminal.state.error"];
  }
}
