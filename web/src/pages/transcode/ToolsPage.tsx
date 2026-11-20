import { useEffect, useMemo, useRef, useState } from "react";
import { api, type TranscodeMediaEntry, type TranscodeQualityJob } from "../../api";
import { Dialog } from "../../components/Dialog";
import { toast } from "../../store";
import { errMsg } from "../../utils";
import { HelpNote, ensureQueueRunning } from "./common";
import { DirectoryPicker } from "./DirectoryPicker";
import { useTranscodeWorkspace } from "./TranscodeWorkspace";

type ToolTab = "merge" | "mux" | "extract" | "quality";

type MuxRow = { path: string; video: string; audio: string; subtitle: string };

type ProbeStream = {
  index: number;
  codec_type?: string;
  codec_name?: string;
  width?: number;
  height?: number;
  sample_rate?: string;
  channels?: number;
  tags?: Record<string, string>;
};

type DisplayStream = { index: number; type: "v" | "a" | "s" | "t"; typeIndex: number; codec: string; detail: string; title: string };

type QualityEntry = { path: string; job?: TranscodeQualityJob; error?: string };

function baseName(path: string): string {
  return path.split("/").pop() ?? path;
}

function dirOf(path: string): string {
  const index = path.lastIndexOf("/");
  return index > 0 ? path.slice(0, index) : "";
}

function stamp(): string {
  return new Date().toISOString().replace(/[-:T]/g, "").slice(0, 14);
}

function suggestOutput(name: string, extension: string): string {
  return `${name}_${stamp()}.${extension}`;
}

function streamExtension(codec: string, type: string): string {
  const value = codec.toLowerCase();
  if (type === "subtitle") {
    if (value === "subrip") return "srt";
    if (value === "ass" || value === "ssa") return "ass";
    if (value === "webvtt") return "vtt";
    return "srt";
  }
  if (type === "audio") {
    if (value === "aac") return "aac";
    if (value === "opus") return "opus";
    if (value === "mp3") return "mp3";
    if (value === "flac") return "flac";
    if (value === "vorbis") return "ogg";
    return "mka";
  }
  if (value === "h264" || value === "avc") return "h264";
  if (value === "hevc" || value === "h265") return "h265";
  if (value === "vp9") return "ivf";
  return "mkv";
}

function describeStream(stream: ProbeStream, typeIndex: number): DisplayStream {
  const type = stream.codec_type === "video" ? "v"
    : stream.codec_type === "audio" ? "a"
      : stream.codec_type === "subtitle" ? "s" : "t";
  const parts = [stream.codec_name ?? "未知编码"];
  if (type === "v" && stream.width && stream.height) parts.push(`${stream.width}x${stream.height}`);
  if (type === "a" && stream.sample_rate) parts.push(`${stream.sample_rate}Hz`);
  if (stream.tags?.language) parts.push(stream.tags.language);
  return {
    index: stream.index,
    type,
    typeIndex,
    codec: stream.codec_name ?? "",
    detail: parts.join(" · "),
    title: stream.tags?.title ?? stream.tags?.filename ?? "",
  };
}

/* ── 文件选择弹窗：浏览媒体根，选一个文件 ─────────────────────── */
function FilePickerDialog({
  open,
  title,
  initial,
  onClose,
  onPick,
}: {
  open: boolean;
  title: string;
  initial: string;
  onClose: () => void;
  onPick: (path: string) => void;
}) {
  const [path, setPath] = useState(initial);
  const [entries, setEntries] = useState<TranscodeMediaEntry[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const load = async (next: string) => {
    setLoading(true);
    setError(null);
    try {
      const answer = await api.transcode.mediaList(next, 0, 300);
      setEntries(answer.entries);
      setPath(answer.path);
    } catch (e) {
      setError(errMsg(e));
      setEntries([]);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (open) void load(initial);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, initial]);

  const parent = () => {
    const parts = path.split("/").filter(Boolean);
    parts.pop();
    void load(parts.join("/"));
  };

  return (
    <Dialog open={open} title={title} onClose={onClose} wide>
      <div style={{ display: "grid", gap: 8 }}>
        <div style={{ display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap" }}>
          <button className="btn btn-text" disabled={!path || loading} onClick={parent}>上级</button>
          <button className="btn btn-text" disabled={loading} onClick={() => void load("")}>媒体根</button>
          <span className="mono" style={{ flex: 1, minWidth: 0, fontSize: 12, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
            {path || "媒体根"}
          </span>
        </div>
        {error && <div style={{ fontSize: 12, color: "var(--monet-error)" }}>{error}</div>}
        <div className="list-body scroll-hover" style={{ maxHeight: 380, border: "1px solid var(--monet-outline)", borderRadius: 4 }}>
          {loading && <div className="empty-state"><span>读取中…</span></div>}
          {!loading && entries.length === 0 && !error && <div className="empty-state"><span>这个目录是空的</span></div>}
          {!loading && entries.map((entry) => (
            <div
              key={entry.path}
              className="tlli ripple-host"
              style={{ padding: "7px 10px", cursor: entry.directory ? "pointer" : "default" }}
              onClick={() => entry.directory && void load(entry.path)}
            >
              <span className="tlli-text" style={{ minWidth: 0 }}>
                <span className="tlli-title" style={{ overflow: "hidden", textOverflow: "ellipsis" }}>{entry.name}</span>
                <span className="tlli-subtitle">{entry.directory ? "目录" : entry.kind}</span>
              </span>
              {!entry.directory && (
                <button
                  className="btn btn-text"
                  style={{ padding: "2px 6px", fontSize: 11 }}
                  onClick={(event) => {
                    event.stopPropagation();
                    onPick(entry.path);
                  }}
                >
                  选择
                </button>
              )}
            </div>
          ))}
        </div>
      </div>
    </Dialog>
  );
}

/* ── 混流：可视化选流弹窗 ────────────────────────────────────── */
function StreamPickerDialog({
  file,
  initialVideo,
  initialAudio,
  initialSubtitle,
  onClose,
  onApply,
}: {
  file: string;
  initialVideo: string;
  initialAudio: string;
  initialSubtitle: string;
  onClose: () => void;
  onApply: (video: string, audio: string, subtitle: string) => void;
}) {
  const [streams, setStreams] = useState<DisplayStream[]>([]);
  const [video, setVideo] = useState(initialVideo);
  const [audio, setAudio] = useState(initialAudio);
  const [subtitle, setSubtitle] = useState(initialSubtitle);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    void api.transcode.mediaProbe(file).then((probe) => {
      if (cancelled) return;
      const raw = (probe.streams as ProbeStream[] | undefined) ?? [];
      const counters: Record<string, number> = { v: 0, a: 0, s: 0, t: 0 };
      setStreams(raw
        .filter((stream) => ["video", "audio", "subtitle", "attachment"].includes(String(stream.codec_type)))
        .map((stream) => describeStream(stream, counters[(stream.codec_type === "video" ? "v" : stream.codec_type === "audio" ? "a" : stream.codec_type === "subtitle" ? "s" : "t") as "v"]++)));
    }).catch((e) => {
      if (!cancelled) setError(errMsg(e));
    });
    return () => {
      cancelled = true;
    };
  }, [file]);

  const toggle = (type: string, typeIndex: number) => {
    const token = `${typeIndex}`;
    const current = type === "v" ? video : type === "a" ? audio : subtitle;
    const set = new Set(current.split(",").map((item) => item.trim()).filter(Boolean));
    if (set.has(token)) set.delete(token);
    else set.add(token);
    const next = [...set].sort((a, b) => Number(a) - Number(b)).join(",");
    if (type === "v") setVideo(next);
    else if (type === "a") setAudio(next);
    else setSubtitle(next);
  };

  const groups: Array<{ type: "v" | "a" | "s" | "t"; label: string }> = [
    { type: "v", label: "视频流" },
    { type: "a", label: "音频流" },
    { type: "s", label: "字幕流" },
  ];

  return (
    <Dialog
      open
      title={`选择流 · ${baseName(file)}`}
      onClose={onClose}
      wide
      footer={
        <>
          <button className="btn btn-text" onClick={onClose}>取消</button>
          <button className="btn btn-raised" onClick={() => onApply(video, audio, subtitle)}>应用</button>
        </>
      }
    >
      {error && <div style={{ fontSize: 12, color: "var(--monet-error)" }}>{error}</div>}
      <div style={{ display: "grid", gap: 10 }}>
        {groups.map((group) => {
          const groupStreams = streams.filter((stream) => stream.type === group.type);
          if (groupStreams.length === 0) return null;
          return (
            <div key={group.type}>
              <div style={{ fontSize: 12, fontWeight: 600, padding: "2px 0 4px" }}>{group.label}</div>
              <div className="list-body scroll-hover" style={{ border: "1px solid var(--monet-outline)", borderRadius: 4, maxHeight: 180 }}>
                {groupStreams.map((stream) => {
                  const selected = (group.type === "v" ? video : group.type === "a" ? audio : subtitle)
                    .split(",").map((item) => item.trim()).includes(String(stream.typeIndex));
                  return (
                    <label key={stream.index} className="tlli" style={{ padding: "6px 10px", alignItems: "center" }}>
                      <input
                        type="checkbox"
                        checked={selected}
                        onChange={() => toggle(group.type, stream.typeIndex)}
                      />
                      <span className="tlli-text" style={{ minWidth: 0 }}>
                        <span className="tlli-title mono">{group.type}:{stream.typeIndex} · {stream.detail}</span>
                        {stream.title && <span className="tlli-subtitle">{stream.title}</span>}
                      </span>
                    </label>
                  );
                })}
              </div>
            </div>
          );
        })}
        <div style={{ fontSize: 11, color: "var(--monet-on-surface-variant)" }}>
          序号是同类型流的第几条（如 0:v:0 的最后一段）；应用到该文件的映射字段，多个用逗号分隔。
        </div>
      </div>
    </Dialog>
  );
}

export function ToolsPage({ onOpenTab }: { onOpenTab: (tab: string) => void }) {
  const { staged } = useTranscodeWorkspace();
  const [tab, setTab] = useState<ToolTab>("merge");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [mediaRoot, setMediaRoot] = useState("");

  const [picker, setPicker] = useState<{ title: string; initial: string; onPick: (path: string) => void } | null>(null);

  const stagedMedia = useMemo(
    () => staged.filter((item) => ["video", "audio", "subtitle", "image"].includes(item.entry.kind)).map((item) => item.entry.path),
    [staged],
  );

  const openPicker = (title: string, initial: string, onPick: (path: string) => void) =>
    setPicker({ title, initial, onPick });

  useEffect(() => {
    void api.transcode.capabilities()
      .then((capabilities) => setMediaRoot(capabilities.mediaRoot))
      .catch(() => undefined);
  }, []);

  /* ── 合并 ───────────────────────────────────────────────── */
  const [mergeFiles, setMergeFiles] = useState<string[]>([]);
  const [mergeSelected, setMergeSelected] = useState(-1);
  const [mergeOutput, setMergeOutput] = useState("");

  const moveMerge = (offset: number) => {
    const index = mergeSelected;
    const target = index + offset;
    if (index < 0 || target < 0 || target >= mergeFiles.length) return;
    const next = [...mergeFiles];
    [next[index], next[target]] = [next[target], next[index]];
    setMergeFiles(next);
    setMergeSelected(target);
  };

  const startMerge = async () => {
    if (mergeFiles.length < 2) {
      toast("error", "合并至少需要两个文件");
      return;
    }
    if (!mergeOutput.trim()) {
      toast("error", "请设置输出文件");
      return;
    }
    setBusy(true);
    try {
      await api.transcode.toolsMerge(mergeFiles, mergeOutput.trim());
      await ensureQueueRunning();
      toast("success", "合并任务已加入编码队列");
      onOpenTab("queue");
    } catch (e) {
      setError(errMsg(e));
      toast("error", errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  /* ── 混流 ───────────────────────────────────────────────── */
  const [muxRows, setMuxRows] = useState<MuxRow[]>([]);
  const [muxSelected, setMuxSelected] = useState(-1);
  const [muxOutput, setMuxOutput] = useState("");
  const [streamTarget, setStreamTarget] = useState<number | null>(null);

  const moveMux = (offset: number) => {
    const index = muxSelected;
    const target = index + offset;
    if (index < 0 || target < 0 || target >= muxRows.length) return;
    const next = [...muxRows];
    [next[index], next[target]] = [next[target], next[index]];
    setMuxRows(next);
    setMuxSelected(target);
  };

  const normalizeMaps = (row: MuxRow, fileIndex: number): string[] => {
    const maps: string[] = [];
    const entries: Array<[string, string]> = [
      [row.video, "v"],
      [row.audio, "a"],
      [row.subtitle, "s"],
    ];
    for (const [text, type] of entries) {
      for (const token of text.split(",").map((item) => item.trim()).filter(Boolean)) {
        const match = /^(?:(\d+):)?[vas]:(\d+)$/.exec(token);
        maps.push(match ? `${match[1] ?? fileIndex}:${type}:${match[2]}` : `${fileIndex}:${type}:${token}`);
      }
    }
    return maps;
  };

  const startMux = async () => {
    if (muxRows.length === 0) {
      toast("error", "请先添加要混流的文件");
      return;
    }
    const maps = muxRows.flatMap((row, index) => normalizeMaps(row, index));
    if (maps.length === 0) {
      toast("error", "还没有选择任何流（在每个文件上点「选流」）");
      return;
    }
    if (!muxOutput.trim()) {
      toast("error", "请设置输出文件");
      return;
    }
    setBusy(true);
    try {
      await api.transcode.toolsMux(muxRows.map((row) => row.path), maps, muxOutput.trim());
      await ensureQueueRunning();
      toast("success", "混流任务已加入编码队列");
      onOpenTab("queue");
    } catch (e) {
      setError(errMsg(e));
      toast("error", errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  /* ── 抽流 ───────────────────────────────────────────────── */
  const [extractFile, setExtractFile] = useState("");
  const [extractStreams, setExtractStreams] = useState<DisplayStream[]>([]);
  const [extractSelected, setExtractSelected] = useState<number[]>([]);
  const [extractOutputDir, setExtractOutputDir] = useState("");
  const [extractPickerOpen, setExtractPickerOpen] = useState(false);

  const loadExtractFile = async (path: string) => {
    setExtractFile(path);
    setExtractStreams([]);
    setExtractSelected([]);
    try {
      const probe = await api.transcode.mediaProbe(path);
      const raw = (probe.streams as ProbeStream[] | undefined) ?? [];
      const counters: Record<string, number> = { v: 0, a: 0, s: 0, t: 0 };
      setExtractStreams(raw
        .filter((stream) => ["video", "audio", "subtitle"].includes(String(stream.codec_type)))
        .map((stream) => describeStream(stream, counters[(stream.codec_type === "video" ? "v" : stream.codec_type === "audio" ? "a" : "s") as "v"]++)));
    } catch (e) {
      toast("error", errMsg(e));
    }
  };

  const startExtract = async () => {
    if (!extractFile) {
      toast("error", "先打开媒体文件");
      return;
    }
    if (extractSelected.length === 0) {
      toast("error", "先勾选要提取的流");
      return;
    }
    setBusy(true);
    const added: string[] = [];
    const failures: string[] = [];
    const stem = baseName(extractFile).replace(/\.[^.]+$/, "");
    for (const index of extractSelected) {
      const stream = extractStreams.find((item) => item.index === index);
      if (!stream) continue;
      const output = `${extractOutputDir ? `${extractOutputDir.replace(/\/+$/, "")}/` : ""}`
        + `${stem}.${stream.type}${stream.typeIndex}.${streamExtension(stream.codec, stream.type === "v" ? "video" : stream.type === "a" ? "audio" : "subtitle")}`;
      try {
        const job = await api.transcode.toolsExtract(
          extractFile,
          stream.type === "v" ? "video" : stream.type === "a" ? "audio" : "subtitle",
          stream.typeIndex,
          output,
        );
        added.push(job.output);
      } catch (e) {
        failures.push(`${output}: ${errMsg(e)}`);
      }
    }
    setBusy(false);
    if (added.length > 0) {
      try {
        await ensureQueueRunning();
      } catch (e) {
        toast("error", `任务已入队，但启动队列失败：${errMsg(e)}`);
      }
      toast("success", `已加入 ${added.length} 个抽流任务`);
      onOpenTab("queue");
    }
    if (failures.length > 0) {
      setError(failures.slice(0, 3).join("\n"));
      toast("error", `${failures.length} 个流未加入`);
    }
  };

  /* ── 质量评测（多个待测文件，逐个跑） ───────────────────── */
  const [qualityReference, setQualityReference] = useState("");
  const [qualityEntries, setQualityEntries] = useState<QualityEntry[]>([]);
  const [qualityMetrics, setQualityMetrics] = useState<string[]>(["psnr", "ssim", "xpsnr"]);
  const [qualityStart, setQualityStart] = useState("");
  const [qualityDuration, setQualityDuration] = useState("");
  const [qualityBusy, setQualityBusy] = useState(false);
  const [activeQualityId, setActiveQualityId] = useState<string | null>(null);
  const [qualityView, setQualityView] = useState<{ metric: string; path: string } | null>(null);
  const abortRef = useRef(false);

  const toggleMetric = (metric: string) => {
    setQualityMetrics((current) =>
      current.includes(metric) ? current.filter((item) => item !== metric) : [...current, metric]);
  };

  const cancelQuality = async () => {
    abortRef.current = true;
    if (activeQualityId) {
      try {
        await api.transcode.qualityCancel(activeQualityId);
        toast("info", "已请求取消，剩余文件将跳过");
      } catch (e) {
        toast("error", errMsg(e));
      }
    }
  };

  const runQuality = async () => {
    if (!qualityReference) {
      toast("error", "先选择参考文件");
      return;
    }
    if (qualityEntries.length === 0) {
      toast("error", "至少添加一个待测文件");
      return;
    }
    if (qualityMetrics.length === 0) {
      toast("error", "至少选择一项指标");
      return;
    }
    abortRef.current = false;
    setQualityBusy(true);
    setQualityEntries((current) => current.map((entry) => ({ ...entry, job: undefined, error: undefined })));
    let finished = 0;
    for (let index = 0; index < qualityEntries.length; index++) {
      if (abortRef.current) break;
      const entry = qualityEntries[index];
      try {
        let job = await api.transcode.qualityStart({
          reference: qualityReference,
          encoded: entry.path,
          metrics: qualityMetrics,
          start: qualityStart.trim() || undefined,
          duration: qualityDuration.trim() || undefined,
        });
        setActiveQualityId(job.id);
        setQualityEntries((current) => current.map((item, position) => position === index ? { ...item, job } : item));
        while (job.state === "running") {
          if (abortRef.current) {
            await api.transcode.qualityCancel(job.id).catch(() => undefined);
            break;
          }
          await new Promise((resolve) => window.setTimeout(resolve, 1000));
          job = await api.transcode.qualityJob(job.id);
          setQualityEntries((current) => current.map((item, position) => position === index ? { ...item, job } : item));
        }
        if (job.state === "failed" && job.error) {
          setQualityEntries((current) => current.map((item, position) =>
            position === index ? { ...item, job, error: job.error ?? undefined } : item));
        }
        if (job.state === "done") finished++;
      } catch (e) {
        setQualityEntries((current) => current.map((item, position) =>
          position === index ? { ...item, error: errMsg(e) } : item));
      }
    }
    setActiveQualityId(null);
    setQualityBusy(false);
    if (finished > 0) toast("success", `${finished} 个文件评测完成`);
  };

  const qualityChart = qualityView
    ? qualityEntries.find((entry) => entry.path === qualityView.path)?.job?.results[qualityView.metric]
    : undefined;

  return (
    <div className="tc-tools">
      <div className="card tc-tool-tabs" role="tablist">
        {([
          ["merge", "合并（头尾相接）"],
          ["mux", "混流（布置轨道）"],
          ["extract", "抽流（取出纯流）"],
          ["quality", "质量评测"],
        ] as Array<[ToolTab, string]>).map(([key, label]) => (
          <button
            key={key}
            className={`tc-tool-tab${tab === key ? " selected" : ""}`}
            role="tab"
            aria-selected={tab === key}
            onClick={() => setTab(key)}
          >
            {label}
          </button>
        ))}
        <span style={{ flex: 1 }} />
        {stagedMedia.length > 0 && (
          <button
            className="btn btn-text"
            onClick={() => {
              if (tab === "merge") setMergeFiles((current) => {
                const set = new Set(current);
                return [...current, ...stagedMedia.filter((path) => !set.has(path))];
              });
              else if (tab === "mux") setMuxRows((current) => {
                const set = new Set(current.map((row) => row.path));
                return [...current, ...stagedMedia.filter((path) => !set.has(path)).map((path) => ({ path, video: "", audio: "", subtitle: "" }))];
              });
              else if (tab === "quality") setQualityEntries((current) => {
                const set = new Set(current.map((entry) => entry.path));
                return [...current, ...stagedMedia.filter((path) => !set.has(path)).map((path) => ({ path }))];
              });
              else if (!extractFile && stagedMedia[0]) void loadExtractFile(stagedMedia[0]);
              toast("success", tab === "extract"
                ? "已从准备列表打开第一个文件"
                : `已从准备列表带入 ${stagedMedia.length} 个文件`);
            }}
          >
            带入准备列表（{stagedMedia.length}）
          </button>
        )}
      </div>

      {error && <div className="card tc-error">{error}</div>}

      {tab === "merge" && (
        <section className="card tc-tool-panel">
          <div className="tc-card-title">合并</div>
          <p className="tc-tool-desc">按列表顺序首尾拼接，流复制不重编码；输入编码参数需一致，否则会在队列日志里看到 ffmpeg 的原因。</p>
          <div className="tc-tool-actions">
            <button className="btn btn-text" onClick={() => openPicker("添加要合并的文件", mergeFiles.length > 0 ? dirOf(mergeFiles[mergeFiles.length - 1]) : "", (path) => {
              setPicker(null);
              setMergeFiles((current) => current.includes(path) ? current : [...current, path]);
              setMergeSelected(mergeFiles.length);
            })}>添加文件</button>
            <button className="btn btn-text" disabled={mergeSelected < 0} onClick={() => moveMerge(-1)}>上移</button>
            <button className="btn btn-text" disabled={mergeSelected < 0} onClick={() => moveMerge(1)}>下移</button>
            <button className="btn btn-text" disabled={mergeSelected < 0} onClick={() => {
              setMergeFiles((current) => current.filter((_, index) => index !== mergeSelected));
              setMergeSelected(-1);
            }}>移除选中</button>
            <button className="btn btn-text" disabled={mergeFiles.length === 0} onClick={() => {
              setMergeFiles([]);
              setMergeSelected(-1);
            }}>清空</button>
          </div>
          <div className="tc-tool-list">
            {mergeFiles.map((path, index) => (
              <div key={path} className={`tc-tool-row${mergeSelected === index ? " selected" : ""}`} onClick={() => setMergeSelected(index)}>
                <span className="mono tc-tool-order">{index + 1}</span>
                <span className="tc-tool-name">{baseName(path)}</span>
                <span className="mono tc-tool-path">{path}</span>
              </div>
            ))}
            {mergeFiles.length === 0 && <div className="tc-tool-empty">尚未添加文件</div>}
          </div>
          <label className="tc-tool-field">
            <span>输出文件（相对媒体根）</span>
            <span className="tc-tool-field-row">
              <input className="input" value={mergeOutput} placeholder="merged_20260101.mkv" onChange={(e) => setMergeOutput(e.target.value)} />
              <button className="btn btn-text" onClick={() => setPicker({ title: "选择输出目录", initial: dirOf(mergeOutput), onPick: (path) => {
                setPicker(null);
                setMergeOutput(`${path ? `${path}/` : ""}${suggestOutput("merged", "mkv")}`);
              }})}>选择目录</button>
            </span>
          </label>
          <div className="tc-tool-submit">
            <button className="btn btn-raised" disabled={busy || mergeFiles.length < 2 || !mergeOutput.trim()} onClick={() => void startMerge()}>
              {busy ? "提交中…" : "开始合并"}
            </button>
          </div>
        </section>
      )}

      {tab === "mux" && (
        <section className="card tc-tool-panel">
          <div className="tc-card-title">混流</div>
          <p className="tc-tool-desc">从多个文件挑轨道重新封装；每个文件点「选流」可视化勾选视频/音频/字幕流，流复制不重编码。</p>
          <div className="tc-tool-actions">
            <button className="btn btn-text" onClick={() => openPicker("添加要混流的文件", "", (path) => {
              setPicker(null);
              setMuxRows((current) => current.some((row) => row.path === path) ? current : [...current, { path, video: "", audio: "", subtitle: "" }]);
              setMuxSelected(muxRows.length);
            })}>添加文件</button>
            <button className="btn btn-text" disabled={muxSelected < 0} onClick={() => moveMux(-1)}>上移</button>
            <button className="btn btn-text" disabled={muxSelected < 0} onClick={() => moveMux(1)}>下移</button>
            <button className="btn btn-text" disabled={muxSelected < 0} onClick={() => {
              setMuxRows((current) => current.filter((_, index) => index !== muxSelected));
              setMuxSelected(-1);
            }}>移除选中</button>
          </div>
          <div className="tc-tool-list">
            {muxRows.map((row, index) => (
              <div key={row.path} className={`tc-mux-row${muxSelected === index ? " selected" : ""}`} onClick={() => setMuxSelected(index)}>
                <div className="tc-mux-head">
                  <span className="mono tc-tool-order">{index}</span>
                  <span className="tc-tool-name">{baseName(row.path)}</span>
                  <button className="btn btn-text" onClick={(event) => {
                    event.stopPropagation();
                    setStreamTarget(index);
                  }}>选流</button>
                </div>
                <div className="tc-mux-fields">
                  <label>视频<input value={row.video} placeholder="0" onChange={(e) => setMuxRows((current) => current.map((item, position) => position === index ? { ...item, video: e.target.value } : item))} /></label>
                  <label>音频<input value={row.audio} placeholder="0,1" onChange={(e) => setMuxRows((current) => current.map((item, position) => position === index ? { ...item, audio: e.target.value } : item))} /></label>
                  <label>字幕<input value={row.subtitle} placeholder="" onChange={(e) => setMuxRows((current) => current.map((item, position) => position === index ? { ...item, subtitle: e.target.value } : item))} /></label>
                </div>
              </div>
            ))}
            {muxRows.length === 0 && <div className="tc-tool-empty">尚未添加文件</div>}
          </div>
          <label className="tc-tool-field">
            <span>输出文件（相对媒体根）</span>
            <span className="tc-tool-field-row">
              <input className="input" value={muxOutput} placeholder="muxed_20260101.mkv" onChange={(e) => setMuxOutput(e.target.value)} />
              <button className="btn btn-text" onClick={() => setPicker({ title: "选择输出目录", initial: dirOf(muxOutput), onPick: (path) => {
                setPicker(null);
                setMuxOutput(`${path ? `${path}/` : ""}${suggestOutput("muxed", "mkv")}`);
              }})}>选择目录</button>
            </span>
          </label>
          <div className="tc-tool-submit">
            <button className="btn btn-raised" disabled={busy || muxRows.length === 0 || !muxOutput.trim()} onClick={() => void startMux()}>
              {busy ? "提交中…" : "开始混流"}
            </button>
          </div>
        </section>
      )}

      {tab === "extract" && (
        <section className="card tc-tool-panel">
          <div className="tc-card-title">抽流</div>
          <p className="tc-tool-desc">从单个文件勾选多条流，输出名按流类型与编码自动生成（如 name.v0.h264）；流复制不重编码。</p>
          <div className="tc-tool-actions">
            <button className="btn btn-text" onClick={() => openPicker("打开媒体文件", dirOf(extractFile), (path) => {
              setPicker(null);
              void loadExtractFile(path);
            })}>打开媒体文件</button>
            <button className="btn btn-text" onClick={() => setExtractPickerOpen(true)}>选择输出目录</button>
          </div>
          <div className="tc-tool-inline">
            <span>媒体文件：</span><span className="mono">{extractFile || "尚未选择"}</span>
          </div>
          <div className="tc-tool-inline">
            <span>输出目录：</span><span className="mono">{extractOutputDir || "源文件所在目录"}</span>
          </div>
          <div className="tc-tool-list tc-tool-list-tall">
            {(["v", "a", "s"] as const).map((type) => {
              const group = extractStreams.filter((stream) => stream.type === type);
              if (group.length === 0) return null;
              return (
                <div key={type} className="tc-tool-group">
                  <div className="tc-tool-group-head">
                    <span>{type === "v" ? "视频流" : type === "a" ? "音频流" : "字幕流"}</span>
                    <button className="btn btn-text" onClick={() => setExtractSelected((current) => {
                      const set = new Set(current);
                      for (const stream of group) set.add(stream.index);
                      return [...set];
                    })}>全选</button>
                  </div>
                  {group.map((stream) => (
                    <label key={stream.index} className="tc-tool-stream">
                      <input
                        type="checkbox"
                        checked={extractSelected.includes(stream.index)}
                        onChange={(event) => setExtractSelected((current) => event.target.checked
                          ? [...current, stream.index]
                          : current.filter((index) => index !== stream.index))}
                      />
                      <span className="mono">{stream.type}:{stream.typeIndex}</span>
                      <span>{stream.detail}</span>
                      {stream.title && <span className="tc-tool-dim">{stream.title}</span>}
                    </label>
                  ))}
                </div>
              );
            })}
            {extractStreams.length === 0 && <div className="tc-tool-empty">打开媒体文件后勾选要提取的流</div>}
          </div>
          <div className="tc-tool-submit">
            <button className="btn btn-raised" disabled={busy || !extractFile || extractSelected.length === 0} onClick={() => void startExtract()}>
              {busy ? "提交中…" : `提取所选（${extractSelected.length}）`}
            </button>
          </div>
        </section>
      )}

      {tab === "quality" && (
        <section className="card tc-tool-panel">
          <div className="tc-card-title">质量评测</div>
          <p className="tc-tool-desc">参考文件逐个对比待测文件，PSNR/SSIM/XPSNR 需要 ffmpeg 对应滤镜，VMAF 需 libvmaf；待测文件逐个执行，可随时取消。</p>
          <label className="tc-tool-field">
            <span>参考文件</span>
            <span className="tc-tool-field-row">
              <input className="input" value={qualityReference} placeholder="input.mp4" onChange={(e) => setQualityReference(e.target.value)} />
              <button className="btn btn-text" onClick={() => openPicker("选择参考文件", dirOf(qualityReference), (path) => {
                setPicker(null);
                setQualityReference(path);
              })}>选择</button>
            </span>
          </label>
          <div className="tc-tool-actions">
            <button className="btn btn-text" onClick={() => openPicker("添加待测文件", "", (path) => {
              setPicker(null);
              setQualityEntries((current) => current.some((entry) => entry.path === path) ? current : [...current, { path }]);
            })}>添加待测文件</button>
          </div>
          <div className="tc-tool-list">
            {qualityEntries.map((entry, index) => (
              <div key={entry.path} className="tc-tool-row">
                <span className="mono tc-tool-order">{index + 1}</span>
                <span className="tc-tool-name">{baseName(entry.path)}</span>
                <span className="mono tc-tool-path">{entry.path}</span>
                {entry.job && (
                  <span className={`tag ${entry.job.state === "done" ? "running" : entry.job.state === "failed" ? "danger" : "plain"}`}>
                    {entry.job.state === "done" ? "完成" : entry.job.state === "failed" ? "失败" : entry.job.state === "cancelled" ? "取消" : "评测中"}
                  </span>
                )}
                {entry.error && <span className="tc-tool-dim" style={{ color: "var(--monet-error)" }}>{entry.error}</span>}
                <button className="btn btn-text" onClick={() => setQualityEntries((current) => current.filter((item) => item.path !== entry.path))}>移除</button>
              </div>
            ))}
            {qualityEntries.length === 0 && <div className="tc-tool-empty">尚未添加待测文件</div>}
          </div>
          <div className="tc-tool-grid">
            <label className="tc-tool-field"><span>起始（秒或 HH:MM:SS）</span><input className="input" value={qualityStart} placeholder="默认 0" onChange={(e) => setQualityStart(e.target.value)} /></label>
            <label className="tc-tool-field"><span>时长</span><input className="input" value={qualityDuration} placeholder="默认全部" onChange={(e) => setQualityDuration(e.target.value)} /></label>
          </div>
          <div className="tc-tool-metrics">
            {["psnr", "ssim", "xpsnr", "vmaf"].map((metric) => (
              <label key={metric}>
                <input type="checkbox" checked={qualityMetrics.includes(metric)} onChange={() => toggleMetric(metric)} />
                {metric.toUpperCase()}
              </label>
            ))}
          </div>
          <div className="tc-tool-submit">
            <button className="btn btn-raised" disabled={qualityBusy || !qualityReference || qualityEntries.length === 0 || qualityMetrics.length === 0} onClick={() => void runQuality()}>
              {qualityBusy ? "评测中…" : "开始评测"}
            </button>
            {qualityBusy && <button className="btn btn-text danger" onClick={() => void cancelQuality()}>取消</button>}
          </div>

          {qualityEntries.some((entry) => entry.job) && (
            <div className="tc-quality-results">
              <table className="tc-quality-table">
                <thead>
                  <tr>
                    <th>文件</th>
                    {qualityMetrics.map((metric) => <th key={metric}>{metric.toUpperCase()}</th>)}
                  </tr>
                </thead>
                <tbody>
                  {qualityEntries.map((entry) => (
                    <tr key={entry.path}>
                      <td className="mono">{baseName(entry.path)}</td>
                      {qualityMetrics.map((metric) => {
                        const result = entry.job?.results[metric];
                        return (
                          <td key={metric} className={result?.average == null ? "tc-tool-dim" : undefined}>
                            <button
                              className="tc-quality-cell"
                              disabled={!result || result.frames.length < 2}
                              onClick={() => setQualityView({ metric, path: entry.path })}
                            >
                              {result?.average == null ? "—" : metric === "ssim" ? result.average.toFixed(6) : result.average.toFixed(3)}
                            </button>
                          </td>
                        );
                      })}
                    </tr>
                  ))}
                </tbody>
              </table>
              {qualityChart && qualityChart.frames.length >= 2 && (
                <QualityChart metric={qualityView?.metric ?? ""} frames={qualityChart.frames} />
              )}
            </div>
          )}
        </section>
      )}

      <HelpNote title="工具说明">
        <ul>
          <li>合并 / 混流 / 抽流生成的任务进入<b>编码队列</b>，进度、日志与取消都在队列页统一处理。</li>
          <li>混流的「选流」按文件读取流信息；映射写法 <span className="mono">0:v:0</span>（输入下标:类型:第几条）。</li>
          <li>质量评测一次只跑一个文件，避免多个逐帧比较同时挤占 CPU；点结果数字查看该文件的逐帧曲线。</li>
          <li>所有路径都在媒体根内；「带入准备列表」可把当前准备的文件直接填充到当前工具。</li>
        </ul>
      </HelpNote>

      {picker && (
        <FilePickerDialog
          open
          title={picker.title}
          initial={picker.initial}
          onClose={() => setPicker(null)}
          onPick={(path) => picker.onPick(path)}
        />
      )}

      {streamTarget != null && muxRows[streamTarget] && (
        <StreamPickerDialog
          file={muxRows[streamTarget].path}
          initialVideo={muxRows[streamTarget].video}
          initialAudio={muxRows[streamTarget].audio}
          initialSubtitle={muxRows[streamTarget].subtitle}
          onClose={() => setStreamTarget(null)}
          onApply={(video, audio, subtitle) => {
            setMuxRows((current) => current.map((row, index) => index === streamTarget ? { ...row, video, audio, subtitle } : row));
            setStreamTarget(null);
          }}
        />
      )}

      <DirectoryPicker
        open={extractPickerOpen}
        initial={extractOutputDir}
        mediaRoot={mediaRoot}
        onClose={() => setExtractPickerOpen(false)}
        onSelect={(path) => {
          setExtractOutputDir(path);
          setExtractPickerOpen(false);
        }}
      />
    </div>
  );
}

function QualityChart({ metric, frames }: { metric: string; frames: Array<{ n: number; value: number }> }) {
  const width = 600;
  const height = 110;
  const values = frames.map((frame) => frame.value);
  const min = Math.min(...values);
  const max = Math.max(...values);
  const span = Math.max(0.0001, max - min);
  const points = frames.map((frame, index) => {
    const x = (index / (frames.length - 1)) * width;
    const y = height - ((frame.value - min) / span) * (height - 12) - 6;
    return `${x.toFixed(1)},${y.toFixed(1)}`;
  }).join(" ");
  return (
    <svg viewBox={`0 0 ${width} ${height}`} style={{ width: "100%", height: 110 }}>
      <polyline points={points} fill="none" stroke="var(--monet-primary)" strokeWidth="1.5" />
      <text x="2" y="10" fontSize="9" fill="var(--monet-on-surface-variant)">{max.toFixed(2)}</text>
      <text x="2" y={height - 2} fontSize="9" fill="var(--monet-on-surface-variant)">{min.toFixed(2)}</text>
      <text x={width - 40} y={height - 2} fontSize="9" fill="var(--monet-on-surface-variant)">{metric.toUpperCase()}</text>
    </svg>
  );
}
