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

type Phase = "opening" | "password" | "setup" | "running" | "ended" | "detached" | "desktop" | "error";

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
  /** 父组件每次渲染都会传入新的 onClose；用 ref 兜住，挂载 effect 才不会跟着重连。 */
  const onCloseRef = useRef(onClose);
  /** 每次 openSession 自增；慢 POST 回来时若已过期，丢弃结果。 */
  const generationRef = useRef(0);

  useEffect(() => {
    onCloseRef.current = onClose;
  });

  const [phase, setPhaseState] = useState<Phase>("opening");
  const [message, setMessage] = useState("");
  const [exitCode, setExitCode] = useState<number | null>(null);
  const [password, setPassword] = useState("");
  const [setupAdmin, setSetupAdmin] = useState("");
  const [setupPassword, setSetupPassword] = useState("");
  const [setupConfirm, setSetupConfirm] = useState("");
  const [busy, setBusy] = useState(false);
  /** 已从服务端收到的终端字节数（KB），用于区分“没收到”和“收到但没渲染”。 */
  const [rxKb, setRxKb] = useState(0);
  const rxRef = useRef(0);

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
    // A reconnect replaces the view: drop the old terminal/socket first so a
    // second canvas does not pile up in the same host.
    teardown();

    rxRef.current = 0;
    setRxKb(0);

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

    // fit() ran before the socket existed, so its resize was dropped. Send
    // the real size once the handshake completes; the PTY starts at 80x24.
    ws.onopen = () => {
      if (ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ type: "resize", cols: term.cols, rows: term.rows }));
      }
    };

    /** 收到二进制帧：计数并渲染。「已接收 N KB」用来区分「没收到字节」和
     *  「收到了但 xterm 没画出来」——这次空白屏就是后者。 */
    const acceptBytes = (bytes: Uint8Array) => {
      rxRef.current += bytes.byteLength;
      setRxKb(Math.floor(rxRef.current / 1024));
      term.write(bytes);
    };

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
            closeTimerRef.current = window.setTimeout(() => onCloseRef.current(), 3000);
          }
        } catch {
          term.write(event.data);
        }
      } else if (event.data instanceof ArrayBuffer) {
        acceptBytes(new Uint8Array(event.data));
      } else if (event.data instanceof Blob) {
        // binaryType is "arraybuffer", but a browser that ignored it must not
        // leave a silent black screen.
        void event.data.arrayBuffer().then((buffer) => acceptBytes(new Uint8Array(buffer)));
      }
    };
    ws.onerror = () => {
      if (disposedRef.current || phaseRef.current !== "opening") {
        return;
      }
      setPhase("error");
      setMessage(I18N["terminal.error"]);
    };
    ws.onclose = (event) => {
      if (disposedRef.current) {
        return;
      }
      if (phaseRef.current === "running") {
        setPhase("detached");
      } else if (phaseRef.current === "opening") {
        // The server rejects a stale ticket / missing session by closing
        // before "ready"; without this the window sat on "opening" forever.
        setPhase("error");
        setMessage(`${I18N["terminal.error"]}（WS ${event.code}${event.reason ? `：${event.reason}` : ""}）`);
      }
    };

    const observer = new ResizeObserver(() => fit.fit());
    observer.observe(hostRef.current);
    observerRef.current = observer;
  }, [teardown, setPhase]);

  const openSession = useCallback(async () => {
    if (!target) return;
    const generation = ++generationRef.current;
    setPhase("opening");
    setMessage("");
    try {
      const data =
        target.kind === "brand"
          ? await api.openBrandTerminal(target.brand as BrandId, target.id)
          : await api.openDshTerminal(target.id);
      if (disposedRef.current || generation !== generationRef.current) return;
      if (data.mode === "desktop") {
        setPhase("desktop");
        return;
      }
      await connect(data);
    } catch (e) {
      if (disposedRef.current || generation !== generationRef.current) return;
      if (e instanceof ApiError && e.status === 403 && e.message.includes("web_shell_disabled")) {
        setPhase("error");
        setMessage("Web Shell 已在「设置 → 开发者模式」里关闭");
        return;
      }
      if (e instanceof ApiError && e.status === 403 && e.message.includes("insecure_transport")) {
        setPhase("error");
        setMessage("Web Shell 需要 HTTPS，请先在「设置 → HTTPS 证书」里启用");
        return;
      }
      if (e instanceof ApiError && e.status === 403) {
        setPhase("password");
        return;
      }
      if (e instanceof ApiError && e.status === 409 && e.message.includes("developer_password_not_set")) {
        setPhase("setup");
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

  const submitSetup = async () => {
    if (!setupAdmin || !setupPassword || setupPassword !== setupConfirm) {
      setMessage(I18N["terminal.setup.mismatch"]);
      return;
    }
    setBusy(true);
    setMessage("");
    try {
      await api.setDeveloperPassword(setupAdmin, setupPassword, setupConfirm);
      await api.elevateDeveloper(setupPassword);
      setSetupAdmin("");
      setSetupPassword("");
      setSetupConfirm("");
      await openSession();
    } catch (e) {
      setMessage(e instanceof Error ? e.message : String(e));
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
      onCloseRef.current();
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
          <span className={`terminal-state terminal-state-${phase}`}>
            {phaseText(phase)}
            {rxKb > 0 ? ` · 已接收 ${rxKb} KB` : ""}
          </span>
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

        {phase === "setup" && (
          <div className="terminal-password">
            <div className="terminal-password-title">{I18N["terminal.setup.prompt"]}</div>
            <input
              className="input"
              type="password"
              placeholder={I18N["terminal.setup.panel"]}
              value={setupAdmin}
              onChange={(e) => setSetupAdmin(e.target.value)}
            />
            <input
              className="input"
              type="password"
              placeholder={I18N["terminal.setup.password"]}
              value={setupPassword}
              onChange={(e) => setSetupPassword(e.target.value)}
            />
            <input
              className="input"
              type="password"
              placeholder={I18N["terminal.setup.confirm"]}
              value={setupConfirm}
              onChange={(e) => setSetupConfirm(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter") void submitSetup();
              }}
            />
            <button
              className="btn btn-raised ripple-host"
              disabled={busy || !setupAdmin || !setupPassword || !setupConfirm}
              onClick={() => void submitSetup()}
            >
              {I18N["terminal.setup.submit"]}
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
    case "setup":
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
