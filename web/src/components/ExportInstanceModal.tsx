import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { api, isNotImplemented } from "../api";
import { Dialog } from "./Dialog";
import { ProgressBar } from "./ProgressBar";
import { seedTasks, toast, useAppState } from "../store";
import type { Instance } from "../types";
import { errMsg, isSuccessTaskState, isTerminalTaskState } from "../utils";
import { I18N } from "../i18n";

type ExportPhase = "form" | "running" | "done" | "failed";

/** 「导出整合包」弹窗：名称 + 是否包含会话 → 任务进度 → 完成提示去 下载→整合包。 */
export function ExportInstanceModal({
  instance,
  open,
  onClose,
}: {
  instance: Instance;
  open: boolean;
  onClose: () => void;
}) {
  const nav = useNavigate();
  const tasks = useAppState().tasks;
  const [name, setName] = useState(instance.name);
  const [includeSessions, setIncludeSessions] = useState(false);
  const [busy, setBusy] = useState(false);
  const [taskId, setTaskId] = useState<string | null>(null);
  const [phase, setPhase] = useState<ExportPhase>("form");
  const [filename, setFilename] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const handledRef = useRef<string | null>(null);

  useEffect(() => {
    if (!open) return;
    setName(instance.name);
    setIncludeSessions(false);
    setBusy(false);
    setTaskId(null);
    setPhase("form");
    setFilename(null);
    setError(null);
    handledRef.current = null;
  }, [open, instance.name]);

  const task = taskId ? tasks[taskId] : undefined;

  useEffect(() => {
    if (phase !== "running" || !task || !taskId || handledRef.current === taskId) return;
    if (!isTerminalTaskState(task.state)) return;
    handledRef.current = taskId;
    void (async () => {
      if (isSuccessTaskState(task.state)) {
        let fn = task.result?.filename ?? null;
        if (!fn) {
          try {
            const list = await api.tasks();
            fn = list.find((t) => t.id === taskId)?.result?.filename ?? null;
          } catch {
            // 文件名仅用于展示
          }
        }
        setFilename(fn);
        setPhase("done");
        toast("success", "整合包导出完成");
      } else {
        setError(task.error ?? "导出任务未完成");
        setPhase("failed");
      }
    })();
  }, [phase, task, taskId]);

  const submit = async () => {
    setBusy(true);
    try {
      const r = await api.exportInstance(instance.id, {
        name: name.trim() || undefined,
        includeSessions,
      });
      setTaskId(r.taskId);
      setPhase("running");
      void seedTasks();
    } catch (e) {
      setError(isNotImplemented(e) ? "后端尚未支持整合包导出" : errMsg(e));
      setPhase("failed");
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open={open} title={`${I18N["modpack.export"]} - ${instance.name}`} onClose={onClose}>
      {phase === "form" && (
        <>
          <div className="form-row">
            <span className="form-label">{I18N["dsh.instance.name"]}</span>
            <input
              className="input"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder={instance.name}
            />
          </div>
          <label className="input-check" style={{ marginBottom: 14 }}>
            <input
              type="checkbox"
              checked={includeSessions}
              onChange={(e) => setIncludeSessions(e.target.checked)}
            />
            <span>包含会话数据（聊天记录、会话上下文）</span>
          </label>
          <div className="dialog-actions">
            <button className="btn btn-text dim" onClick={onClose} disabled={busy}>
              {I18N["button.cancel"]}
            </button>
            <button className="btn btn-text" disabled={busy} onClick={() => void submit()}>
              {busy ? "提交中…" : "开始导出"}
            </button>
          </div>
        </>
      )}

      {phase === "running" && (
        <div style={{ display: "flex", flexDirection: "column", gap: 10, minWidth: 340 }}>
          <ProgressBar fraction={task?.fraction} />
          <div style={{ fontSize: 13 }}>{task?.message ?? "正在打包实例数据…"}</div>
        </div>
      )}

      {phase === "done" && (
        <>
          <p style={{ lineHeight: 1.7 }}>
            导出完成
            {filename ? (
              <>
                ：<code>{filename}</code>
              </>
            ) : null}
            ，可前往「下载 → 整合包」的导出记录中下载。
          </p>
          <div className="dialog-actions">
            <button className="btn btn-text dim" onClick={onClose}>
              关闭
            </button>
            <button
              className="btn btn-text"
              onClick={() => {
                onClose();
                nav("/download?tab=modpack");
              }}
            >
              前往下载
            </button>
          </div>
        </>
      )}

      {phase === "failed" && (
        <>
          <p style={{ lineHeight: 1.7 }}>导出失败:{error ?? "未知错误"}</p>
          <div className="dialog-actions">
            <button className="btn btn-text dim" onClick={onClose}>
              关闭
            </button>
            <button className="btn btn-text" onClick={() => setPhase("form")}>
              重新填写
            </button>
          </div>
        </>
      )}
    </Dialog>
  );
}
