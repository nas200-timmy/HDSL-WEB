import { useEffect, useMemo, useRef, useState } from "react";
import { api, type TranscodeQueueSnapshot, type TranscodeJob } from "../../api";
import { ProgressBar } from "../../components/ProgressBar";
import { Dialog } from "../../components/Dialog";
import { toast } from "../../store";
import { errMsg } from "../../utils";
import { ContentCopyIcon, RefreshIcon } from "../../components/icons";
import { fmtBytes, fmtDuration, pct, stateLabel, stateTone } from "./common";

/**
 * 编码队列。
 *
 * 3FUI 队列页的关键不是“表格”，而是三件事：
 *   1. 一眼看到当前在跑谁、下一个是谁（当前/下一个徽标 + #N 排队序号）
 *   2. 选中一个任务就能看它的实时输出与命令行，不必来回切页
 *   3. 失败任务给的是可操作的决策（降级/停止），不是一句红字
 */
export function QueuePage() {
  const [snapshot, setSnapshot] = useState<TranscodeQueueSnapshot | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [log, setLog] = useState("");
  const [dialogLog, setDialogLog] = useState("");
  const [logDialog, setLogDialog] = useState<string | null>(null);
  const [logFilter, setLogFilter] = useState<"all" | "error">("all");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const dialogLogRef = useRef<HTMLPreElement | null>(null);

  const load = async () => {
    try {
      const next = await api.transcode.queue();
      setSnapshot(next);
      setError(null);
      setSelected((current) => {
        if (current && next.jobs.some((job) => job.id === current)) return current;
        return next.jobs.find((job) => job.state === "running")?.id
          ?? next.jobs.find((job) => job.state === "waiting_decision")?.id
          ?? next.jobs[0]?.id
          ?? null;
      });
    } catch (e) {
      setError(errMsg(e));
    }
  };

  useEffect(() => {
    void load();
    const timer = window.setInterval(() => void load(), 1000);
    return () => window.clearInterval(timer);
  }, []);

  // 详情区的实时输出：只拉选中任务的日志尾部，避免整队列日志刷屏。
  useEffect(() => {
    if (!selected) {
      setLog("");
      return;
    }
    let cancelled = false;
    const pull = async () => {
      try {
        const answer = await api.transcode.jobLog(selected, 80);
        if (!cancelled) setLog(answer.log);
      } catch {
        // 日志被清理时保留最后一份，不要清空。
      }
    };
    void pull();
    const timer = window.setInterval(() => void pull(), 1500);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [selected]);

  const pullDialogLog = async (id: string) => {
    try {
      const answer = await api.transcode.jobLog(id, 600);
      const lines = answer.log.split(/\r?\n/);
      const filtered = logFilter === "error"
        ? lines.filter((line) => /error|failed|invalid|cannot|not supported|退出码|错误/i.test(line))
        : lines;
      setDialogLog(lines.length === 0 ? "" : filtered.join("\n"));
      window.setTimeout(() => {
        if (dialogLogRef.current) dialogLogRef.current.scrollTop = dialogLogRef.current.scrollHeight;
      }, 30);
    } catch (e) {
      toast("error", errMsg(e));
    }
  };

  useEffect(() => {
    if (logDialog) void pullDialogLog(logDialog);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [logDialog, logFilter]);

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

  const stats = useMemo(() => {
    const jobs = snapshot?.jobs ?? [];
    return {
      running: jobs.filter((job) => job.state === "running").length,
      queued: jobs.filter((job) => job.state === "queued").length,
      done: jobs.filter((job) => job.state === "done").length,
      failed: jobs.filter((job) => job.state === "failed" || job.state === "waiting_decision").length,
    };
  }, [snapshot]);

  const waiting = snapshot?.jobs.find((job) => job.state === "waiting_decision") ?? null;
  const selectedJob = snapshot?.jobs.find((job) => job.id === selected) ?? null;
  const nextQueued = snapshot?.jobs.find((job) => job.state === "queued") ?? null;

  const seqBadge = (job: TranscodeJob): string | null => {
    if (job.state === "running" || job.state === "paused" || job.state === "stopping") return "当前";
    if (job.state === "queued") return job.id === nextQueued?.id ? "下一个" : null;
    return null;
  };

  const queuedIndex = (job: TranscodeJob): number | null => {
    if (job.state !== "queued") return null;
    const list = (snapshot?.jobs ?? []).filter((item) => item.state === "queued");
    const index = list.findIndex((item) => item.id === job.id);
    return index >= 0 ? index + 1 : null;
  };

  const copy = (text: string, label: string) => {
    if (!text) return toast("error", `${label}为空`);
    void navigator.clipboard?.writeText(text).then(
      () => toast("success", `${label}已复制`),
      () => toast("error", "复制失败（需要 HTTPS 或 localhost）"),
    );
  };

  return (
    <div className="tc-queue">
      {error && <div className="card tc-error">{error}</div>}

      <div className="card tc-queue-head">
        <div className="tc-stat-row">
          <span className={`tc-stat${stats.running > 0 ? " active" : ""}`}>
            <b>{stats.running}</b>运行
          </span>
          <span className="tc-stat"><b>{stats.queued}</b>排队</span>
          <span className="tc-stat"><b>{stats.done}</b>完成</span>
          <span className={`tc-stat${stats.failed > 0 ? " danger" : ""}`}><b>{stats.failed}</b>失败/待决定</span>
          <span className="tc-stat-meta">
            并发 {snapshot?.maxConcurrent ?? "—"} · 上限 {snapshot?.maxQueue ?? "—"} · 关网页不停任务
          </span>
        </div>
        <div className="tc-queue-actions">
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
          <button className="btn btn-text" onClick={() => void load()}>
            <RefreshIcon size={16} />
            刷新
          </button>
        </div>
      </div>

      {waiting && (
        <div className="card tc-decision">
          <div className="tc-decision-title">编码失败，等待决定</div>
          <div className="tc-decision-body mono">
            {waiting.input} · {waiting.encoder ?? "—"}
          </div>
          <pre className="tc-decision-error">{waiting.error}</pre>
          <div className="tc-decision-actions">
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

      <div className="tc-queue-body">
        <div className="card tc-queue-list">
          {snapshot?.jobs.length === 0 && (
            <div className="empty-state">
              <span>队列是空的</span>
              <span className="empty-sub">在「准备文件」里选择文件并加入队列</span>
            </div>
          )}
          {snapshot?.jobs.map((job) => {
            const badge = seqBadge(job);
            const index = queuedIndex(job);
            return (
              <div
                key={job.id}
                className={`tc-queue-row${selected === job.id ? " selected" : ""}${job.state === "waiting_decision" ? " danger" : ""}`}
                onClick={() => setSelected(job.id)}
              >
                <span className={`tc-seq${badge === "当前" ? " now" : badge === "下一个" ? " next" : ""}`}>
                  {badge ?? (index != null ? `#${index}` : "")}
                </span>
                <span className="tc-queue-main">
                  <span className="tc-queue-name" title={job.input}>{job.input}</span>
                  <span className="tc-queue-sub">
                    <span className={`tag ${stateTone(job.state)}`}>{stateLabel(job.state)}</span>
                    <span className="mono">{job.encoder ?? job.presetName ?? "—"}</span>
                    {job.fallbackAttempted && <span className="tag plain">已降级</span>}
                    {job.output && <span className="tc-queue-out mono" title={job.output}>{job.output}</span>}
                  </span>
                  <span className="tc-queue-progress">
                    <ProgressBar fraction={job.fraction >= 0 ? job.fraction : null} />
                  </span>
                </span>
                <span className="tc-queue-metrics mono">
                  <span>{pct(job.fraction)}</span>
                  <span>{job.speed > 0 ? `${job.speed.toFixed(1)}x` : "—"}</span>
                  <span>{job.fps > 0 ? `${job.fps.toFixed(0)} fps` : "—"}</span>
                  <span>{fmtDuration(job.outTimeMs)} / {fmtDuration(job.durationMs)}</span>
                  <span>{fmtBytes(job.sizeBytes)}</span>
                </span>
                <span className="tc-queue-row-actions" onClick={(e) => e.stopPropagation()}>
                  <button className="btn btn-text" onClick={() => setLogDialog(job.id)}>日志</button>
                  {(job.state === "running" || job.state === "paused") && (
                    <button className="btn btn-text" disabled={busy} onClick={() => void action(job.id, "cancel")}>
                      取消
                    </button>
                  )}
                  {(job.state === "failed" || job.state === "cancelled") && (
                    <button className="btn btn-text" disabled={busy} onClick={() => void action(job.id, "retry")}>
                      重试
                    </button>
                  )}
                </span>
              </div>
            );
          })}
        </div>

        <aside className="card tc-queue-detail">
          {selectedJob ? (
            <>
              <div className="tc-card-title">
                任务详情
                <span className={`tag ${stateTone(selectedJob.state)}`}>{stateLabel(selectedJob.state)}</span>
              </div>
              <div className="tc-detail-grid">
                <span>输入</span><span className="mono">{selectedJob.input}</span>
                <span>输出</span><span className="mono">{selectedJob.output || "—"}</span>
                <span>预设</span><span className="mono">{selectedJob.presetName || selectedJob.presetId || "—"}</span>
                <span>编码器</span><span className="mono">{selectedJob.encoder || "—"}</span>
                <span>进度</span><span className="mono">{pct(selectedJob.fraction)} · {selectedJob.speed > 0 ? `${selectedJob.speed.toFixed(1)}x` : "—"}</span>
                <span>帧</span><span className="mono">{selectedJob.frame >= 0 ? selectedJob.frame : "—"}</span>
                <span>码率</span><span className="mono">{selectedJob.bitrate || "—"}</span>
                <span>大小</span><span className="mono">{fmtBytes(selectedJob.sizeBytes)}</span>
                <span>耗时</span><span className="mono">{fmtDuration(selectedJob.outTimeMs)} / {fmtDuration(selectedJob.durationMs)}</span>
              </div>
              {selectedJob.error && <pre className="tc-detail-error">{selectedJob.error}</pre>}
              <div className="tc-detail-actions">
                <button className="btn btn-text" onClick={() => copy(selectedJob.output, "输出路径")}>
                  <ContentCopyIcon size={16} />
                  复制路径
                </button>
                <button className="btn btn-text" onClick={() => copy((selectedJob.command ?? []).join(" "), "命令行")}>
                  复制命令行
                </button>
                <button className="btn btn-text" onClick={() => setLogDialog(selectedJob.id)}>完整日志</button>
                {(selectedJob.state === "running" || selectedJob.state === "paused") && (
                  <button className="btn btn-text" disabled={busy} onClick={() => void action(selectedJob.id, "cancel")}>
                    取消
                  </button>
                )}
              </div>
              <div className="tc-live-head">实时输出</div>
              <pre className="tc-live">{log || "（还没有输出）"}</pre>
            </>
          ) : (
            <div className="tc-empty">选择一个任务查看详情</div>
          )}
        </aside>
      </div>

      <Dialog
        open={logDialog !== null}
        title="任务日志"
        onClose={() => setLogDialog(null)}
        wide
        footer={
          <>
            <button
              className={`btn ${logFilter === "all" ? "btn-raised" : "btn-text"}`}
              onClick={() => setLogFilter("all")}
            >
              全部
            </button>
            <button
              className={`btn ${logFilter === "error" ? "btn-raised" : "btn-text"}`}
              onClick={() => setLogFilter("error")}
            >
              只看错误
            </button>
            <span style={{ flex: 1 }} />
            <button className="btn btn-text" onClick={() => logDialog && void pullDialogLog(logDialog)}>
              <RefreshIcon size={16} />
              刷新
            </button>
            <button className="btn btn-text" onClick={() => copy(dialogLog, "日志")}>复制</button>
            <button className="btn btn-text" onClick={() => setLogDialog(null)}>关闭</button>
          </>
        }
      >
        <pre className="tc-log-dialog" ref={dialogLogRef}>{dialogLog || "（没有日志）"}</pre>
      </Dialog>
    </div>
  );
}
