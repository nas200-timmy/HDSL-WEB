import { useEffect, useState } from "react";
import { api, type TranscodeQueueSnapshot } from "../../api";
import { ProgressBar } from "../../components/ProgressBar";
import { toast } from "../../store";
import { errMsg } from "../../utils";
import { fmtBytes, fmtDuration, pct, stateLabel, stateTone } from "./common";

export function QueuePage() {
  const [snapshot, setSnapshot] = useState<TranscodeQueueSnapshot | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [log, setLog] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = async () => {
    try {
      const next = await api.transcode.queue();
      setSnapshot(next);
      setError(null);
      if (!selected && next.jobs.length > 0) {
        setSelected(next.jobs.find((job) => job.state === "running")?.id ?? next.jobs[0].id);
      }
    } catch (e) {
      setError(errMsg(e));
    }
  };

  useEffect(() => {
    void load();
    const timer = window.setInterval(() => void load(), 1000);
    return () => window.clearInterval(timer);
  }, []);

  useEffect(() => {
    if (!selected) {
      setLog("");
      return;
    }
    let cancelled = false;
    const loadLog = async () => {
      try {
        const answer = await api.transcode.jobLog(selected, 400);
        if (!cancelled) setLog(answer.log);
      } catch {
        // A job whose log has been rotated away still deserves its row.
      }
    };
    void loadLog();
    const timer = window.setInterval(() => void loadLog(), 1500);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [selected]);

  const action = async (id: string, name: "cancel" | "retry" | "pause" | "resume") => {
    setBusy(true);
    try {
      setSnapshot(await api.transcode.jobAction(id, name));
    } catch (e) {
      toast("error", errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  const decide = async (id: string, decision: "stop" | "fallback" | "fallback-queue" | "auto") => {
    setBusy(true);
    try {
      setSnapshot(await api.transcode.jobDecision(id, decision));
    } catch (e) {
      toast("error", errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  const queueAction = async (name: "start" | "pause" | "stop") => {
    setBusy(true);
    try {
      const next = name === "start"
        ? await api.transcode.startQueue()
        : name === "pause"
          ? await api.transcode.pauseQueue()
          : await api.transcode.stopQueue();
      setSnapshot(next);
    } catch (e) {
      toast("error", errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  const running = snapshot?.jobs.find((job) => job.state === "running" || job.state === "paused") ?? null;
  const waiting = snapshot?.jobs.find((job) => job.state === "waiting_decision") ?? null;
  const selectedJob = snapshot?.jobs.find((job) => job.id === selected) ?? null;

  return (
    <>
      {error && (
        <div className="card">
          <div className="comp-row">
            <span className="comp-label">读取失败</span>
            <span className="comp-value" style={{ color: "var(--monet-error)" }}>{error}</span>
          </div>
        </div>
      )}

      <div className="card">
        <div className="comp-row">
          <span className="comp-label">队列状态</span>
          <span className="comp-value">
            <span className={`tag ${snapshot?.runState === "running" ? "running" : "plain"}`}>
              {snapshot?.runState === "running" ? "运行中" : snapshot?.runState === "paused" ? "已暂停" : "空闲"}
            </span>
          </span>
        </div>
        <div style={{ display: "flex", gap: 8, flexWrap: "wrap", padding: "8px" }}>
          <button className="btn btn-raised" disabled={busy} onClick={() => void queueAction("start")}>
            开始 / 继续
          </button>
          <button className="btn btn-text" disabled={busy} onClick={() => void queueAction("pause")}>
            暂停派发
          </button>
          <button
            className="btn btn-text danger"
            disabled={busy}
            onClick={() => {
              if (window.confirm("停止全部任务？正在运行的 ffmpeg 会被终止，已完成的输出保留。")) {
                void queueAction("stop");
              }
            }}
          >
            停止全部
          </button>
          <span style={{ fontSize: 12, color: "var(--monet-on-surface-variant)", alignSelf: "center" }}>
            并发 {snapshot?.maxConcurrent ?? "—"} · 队列上限 {snapshot?.maxQueue ?? "—"} · 关闭网页不会停止任务
          </span>
        </div>
      </div>

      {waiting && (
        <div className="card" style={{ borderColor: "var(--monet-error)" }}>
          <div className="comp-row">
            <span className="comp-label" style={{ color: "var(--monet-error)" }}>编码失败，等待决定</span>
            <span className="comp-value mono" style={{ fontSize: 11, overflowWrap: "anywhere" }}>
              {waiting.input} · {waiting.encoder}
            </span>
          </div>
          <p style={{ fontSize: 12, padding: "4px 8px", lineHeight: 1.6 }}>{waiting.error}</p>
          <div style={{ display: "flex", gap: 8, flexWrap: "wrap", padding: "0 8px 8px" }}>
            <button className="btn btn-text" disabled={busy} onClick={() => void decide(waiting.id, "stop")}>
              停止此任务
            </button>
            <button className="btn btn-text" disabled={busy} onClick={() => void decide(waiting.id, "fallback")}>
              仅此任务降级
            </button>
            <button className="btn btn-raised" disabled={busy} onClick={() => void decide(waiting.id, "fallback-queue")}>
              降级并应用队列
            </button>
            <button className="btn btn-text" disabled={busy} onClick={() => void decide(waiting.id, "auto")}>
              完全自动化
            </button>
          </div>
        </div>
      )}

      {running && (
        <div className="card">
          <div className="comp-row">
            <span className="comp-label">当前任务</span>
            <span className="comp-value mono" style={{ fontSize: 11, overflowWrap: "anywhere" }}>
              {running.input}
            </span>
          </div>
          <div style={{ padding: "0 8px 4px", display: "flex", justifyContent: "space-between", fontSize: 12 }}>
            <span>{pct(running.fraction)} · {stateLabel(running.state)}</span>
            <span>
              {running.speed > 0 ? `${running.speed.toFixed(1)}x` : "—"}
              {" · "}
              {running.fps > 0 ? `${running.fps.toFixed(0)} fps` : "—"}
              {" · "}
              {fmtDuration(running.outTimeMs)} / {fmtDuration(running.durationMs)}
            </span>
          </div>
          <div style={{ padding: "0 8px 8px" }}>
            <ProgressBar fraction={running.fraction >= 0 ? running.fraction : null} />
          </div>
        </div>
      )}

      <div className="card" style={{ padding: 0 }}>
        <div className="list-body scroll-hover" style={{ maxHeight: 380 }}>
          {snapshot?.jobs.length === 0 && (
            <div className="empty-state">
              <span>队列是空的</span>
              <span className="empty-sub">在「准备文件」里选择文件并加入队列</span>
            </div>
          )}
          {snapshot?.jobs.map((job) => (
            <div
              key={job.id}
              className={`tlli ripple-host${selected === job.id ? " selected" : ""}`}
              style={{
                padding: "7px 10px",
                cursor: "pointer",
                background: selected === job.id ? "var(--monet-secondary-container)" : undefined,
              }}
              onClick={() => setSelected(job.id)}
            >
              <span className="tlli-text" style={{ minWidth: 0, flex: 1 }}>
                <span className="tlli-title" style={{ display: "flex", gap: 8, alignItems: "center" }}>
                  <span style={{ overflow: "hidden", textOverflow: "ellipsis" }}>{job.input}</span>
                  <span className={`tag ${stateTone(job.state)}`} style={{ fontSize: 10 }}>{stateLabel(job.state)}</span>
                </span>
                <span className="tlli-subtitle" style={{ display: "block", marginTop: 2 }}>
                  {job.presetName || job.presetId || "预设"} · {job.encoder || "—"}
                  {" · "}
                  {pct(job.fraction)} · {fmtDuration(job.outTimeMs)} / {fmtDuration(job.durationMs)}
                  {job.speed > 0 ? ` · ${job.speed.toFixed(1)}x` : ""}
                  {job.sizeBytes > 0 ? ` · ${fmtBytes(job.sizeBytes)}` : ""}
                  {job.error ? ` · ${job.error}` : ""}
                </span>
                <div style={{ marginTop: 3 }}>
                  <ProgressBar fraction={job.fraction >= 0 ? job.fraction : null} />
                </div>
              </span>
              <span className="row-actions" onClick={(event) => event.stopPropagation()}>
                {job.state === "waiting_decision" ? (
                  <button className="btn btn-text danger" disabled={busy} onClick={() => void decide(job.id, "fallback")}>
                    降级
                  </button>
                ) : job.state === "failed" || job.state === "cancelled" ? (
                  <button className="btn btn-text" disabled={busy} onClick={() => void action(job.id, "retry")}>
                    重试
                  </button>
                ) : job.state === "running" || job.state === "paused" ? (
                  <>
                    <button
                      className="btn btn-text"
                      disabled={busy}
                      onClick={() => void action(job.id, job.state === "paused" ? "resume" : "pause")}
                    >
                      {job.state === "paused" ? "继续" : "暂停"}
                    </button>
                    <button className="btn btn-text danger" disabled={busy} onClick={() => void action(job.id, "cancel")}>
                      取消
                    </button>
                  </>
                ) : job.state === "queued" ? (
                  <button className="btn btn-text danger" disabled={busy} onClick={() => void action(job.id, "cancel")}>
                    移除
                  </button>
                ) : null}
              </span>
            </div>
          ))}
        </div>
      </div>

      <div className="card">
        <div className="comp-row">
          <span className="comp-label">ffmpeg 日志</span>
          <span className="comp-value mono" style={{ fontSize: 11 }}>
            {selectedJob ? selectedJob.id : "选择一个任务"}
          </span>
        </div>
        {selectedJob?.command && selectedJob.command.length > 0 && (
          <pre style={{ margin: "0 8px 4px", padding: 6, maxHeight: 70, overflow: "auto", fontSize: 10,
                        background: "var(--monet-surface-container)", whiteSpace: "pre-wrap", wordBreak: "break-all" }}>
            {selectedJob.command.join(" ")}
          </pre>
        )}
        <pre
          style={{
            margin: "4px 8px 8px",
            padding: 8,
            height: 220,
            overflow: "auto",
            fontSize: 11,
            lineHeight: 1.45,
            background: "var(--monet-surface-container-low)",
            whiteSpace: "pre-wrap",
            wordBreak: "break-all",
          }}
        >
          {log || "（暂无日志）"}
        </pre>
      </div>
    </>
  );
}