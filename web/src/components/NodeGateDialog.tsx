import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { installManagedNode, refreshSetupStatus, useSetupStatus } from "../setup";
import { settleNodeGate, useNodeGateOpen } from "../setupGate";
import { toast } from "../store";
import { errMsg } from "../utils";
import { Dialog } from "./Dialog";

/**
 * 「缺少 Node.js」提示框的宿主：挂在 App 顶层，`requireNode()` 一被调用就出现。
 *
 * 一键安装走面板托管的运行时（带 pnpm），装完才放行原来的操作；不装也可以
 * 去设置页自己配，取消则原操作不执行。
 */
export function NodeGateHost() {
  const open = useNodeGateOpen();
  const status = useSetupStatus();
  const navigate = useNavigate();
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const [error, setError] = useState<string | null>(null);

  const node = status?.items.find((item) => item.id === "node") ?? null;
  const detail = node?.detail ?? "没有可用的 Node.js 运行时";

  const close = (ok: boolean) => {
    if (busy) return;
    setError(null);
    setMessage("");
    settleNodeGate(ok);
  };

  const install = async () => {
    if (busy) return;
    setBusy(true);
    setError(null);
    setMessage("正在准备…");
    try {
      await installManagedNode(setMessage);
      await refreshSetupStatus(true);
      toast("success", "Node.js 已装好，继续原来的操作");
      setBusy(false);
      setMessage("");
      settleNodeGate(true);
    } catch (e) {
      setError(errMsg(e));
      setBusy(false);
    }
  };

  return (
    <Dialog open={open} title="缺少 Node.js 工具链" closeOnBackdrop={!busy} onClose={() => close(false)}>
      <div style={{ maxWidth: 460, lineHeight: 1.7 }}>
        <p style={{ marginTop: 0 }}>
          创建或启动实例要先有 Node.js（含 pnpm）。现在这台机器上没有可用的运行时，
          所以这一步还没有开始。
        </p>
        <p className="mono" style={{ fontSize: 12, color: "var(--monet-on-surface-variant)" }}>
          {detail}
        </p>
        {busy && (
          <p style={{ fontSize: 12, color: "var(--monet-on-surface-variant)", wordBreak: "break-all" }}>
            {message || "正在安装…"}（下载与解包可能持续几分钟）
          </p>
        )}
        {error && <div className="form-error">{error}</div>}
        <div className="dialog-actions" style={{ flexWrap: "wrap" }}>
          <button className="btn btn-text dim" disabled={busy} onClick={() => close(false)}>
            取消
          </button>
          <button
            className="btn btn-text"
            disabled={busy}
            onClick={() => {
              close(false);
              navigate("/settings?tab=node");
            }}
          >
            去设置
          </button>
          <button className="btn btn-raised" disabled={busy} onClick={() => void install()}>
            {busy ? "安装中…" : "一键安装（面板托管）"}
          </button>
        </div>
      </div>
    </Dialog>
  );
}