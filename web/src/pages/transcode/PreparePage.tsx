import { useEffect, useMemo, useState } from "react";
import { api, type TranscodeMediaEntry, type TranscodePreset, type TranscodePresetSummary, type TranscodePreview, type TranscodePlan } from "../../api";
import { toast } from "../../store";
import { errMsg } from "../../utils";
import { MediaBrowserPanel, ensureQueueRunning, fmtBytes, probeSummary, HelpNote } from "./common";
import { DirectoryPicker } from "./DirectoryPicker";
import { valueLabel } from "./labels";
import { useTranscodeWorkspace } from "./TranscodeWorkspace";

type SortBy = "name" | "size" | "ext";

function extension(path: string): string {
  const name = path.split("/").pop() ?? path;
  const dot = name.lastIndexOf(".");
  return dot < 0 ? "" : name.slice(dot + 1).toUpperCase();
}

export function PreparePage({ onOpenTab }: { onOpenTab: (tab: string) => void }) {
  const {
    staged,
    setStaged,
    clearStaged,
    presetId,
    setPresetId,
    outputDir,
    setOutputDir,
    addEntries,
    removeStaged,
  } = useTranscodeWorkspace();
  const [presets, setPresets] = useState<TranscodePresetSummary[]>([]);
  const [selected, setSelected] = useState<TranscodeMediaEntry | null>(null);
  const [probe, setProbe] = useState<Record<string, unknown> | null>(null);
  const [preview, setPreview] = useState<TranscodePreview | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [presetDetail, setPresetDetail] = useState<TranscodePreset | null>(null);
  const [plan, setPlan] = useState<TranscodePlan | null>(null);
  const [mediaRoot, setMediaRoot] = useState("");
  const [scanning, setScanning] = useState(false);
  const [pickerOpen, setPickerOpen] = useState(false);
  const [browserOpen, setBrowserOpen] = useState(false);
  const [checked, setChecked] = useState<Set<string>>(new Set());
  const [sortBy, setSortBy] = useState<SortBy>("name");

  useEffect(() => {
    if (!presetId) {
      setPresetDetail(null);
      return;
    }
    let cancelled = false;
    void api.transcode.preset(presetId).then((answer) => {
      if (!cancelled) setPresetDetail(answer.preset);
    }).catch(() => {
      if (!cancelled) setPresetDetail(null);
    });
    return () => {
      cancelled = true;
    };
  }, [presetId]);

  useEffect(() => {
    void api.transcode.presets()
      .then((answer) => setPresets(answer.presets))
      .catch((e) => setError(errMsg(e)));
    void api.transcode.capabilities()
      .then((capabilities) => setMediaRoot(capabilities.mediaRoot))
      .catch(() => undefined);
  }, []);

  useEffect(() => {
    setChecked((current) => {
      const paths = new Set(staged.map((item) => item.entry.path));
      const next = new Set([...current].filter((path) => paths.has(path)));
      return next.size === current.size ? current : next;
    });
  }, [staged]);

  const select = async (entry: TranscodeMediaEntry) => {
    setSelected(entry);
    setPreview(null);
    setProbe(null);
    setError(null);
    try {
      setProbe(await api.transcode.mediaProbe(entry.path));
    } catch {
      // Probing is a convenience; preview and submission still validate the file.
    }
  };

  const checkFoolproof = (): boolean => {
    if (!presetDetail) return true;
    const encoder = String(presetDetail.videoEncoder ?? "");
    const container = String(presetDetail.container ?? "").toLowerCase();
    if (!encoder || !container) {
      const message = !encoder ? "预设没有视频编码器" : "预设没有输出容器";
      setError(message);
      toast("error", message);
      return false;
    }
    const restrictive = ["mp4", "mov", "avi", "flv", "ts", "m2ts"];
    const keepsTracks = presetDetail.subtitleMode === "copy"
      || presetDetail.copyAttachments === true
      || presetDetail.copyChapters === true;
    if (restrictive.includes(container) && keepsTracks) {
      return window.confirm(
        `当前容器 ${container.toUpperCase()} 可能装不下全部字幕/附件/章节。\n` +
        "建议把预设容器改成 MKV。仍然继续入队吗？",
      );
    }
    return true;
  };

  const addFolderPath = async (folder: string) => {
    setScanning(true);
    setError(null);
    try {
      const answer = await api.transcode.mediaScan(folder, true, 2000);
      addEntries(answer.entries);
      if (answer.truncated) {
        toast("info", `文件太多，只加入前 ${answer.entries.length} 个（上限 2000）`);
      } else if (answer.entries.length === 0) {
        toast("info", "这个文件夹里没有视频或音频文件");
      } else {
        toast("success", `已扫描 ${answer.entries.length} 个文件，重复项已跳过`);
      }
    } catch (e) {
      const message = errMsg(e);
      setError(message);
      toast("error", message);
    } finally {
      setScanning(false);
    }
  };

  const stagedKey = useMemo(() => staged.map((item) => item.entry.path).join("\n"), [staged]);
  const sortedStaged = useMemo(() => {
    const list = [...staged];
    if (sortBy === "name") {
      list.sort((a, b) => a.entry.name.localeCompare(b.entry.name, "zh-CN"));
    } else if (sortBy === "size") {
      list.sort((a, b) => b.entry.size - a.entry.size);
    } else {
      list.sort((a, b) => extension(a.entry.path).localeCompare(extension(b.entry.path)));
    }
    return list;
  }, [staged, sortBy]);

  // Plan with the same server-side naming/collision logic used at submission time.
  useEffect(() => {
    if (!presetDetail || staged.length === 0) {
      setPlan(null);
      return;
    }
    let cancelled = false;
    const timer = window.setTimeout(() => {
      void api.transcode.plan({
        preset: presetDetail,
        inputs: staged.map((item) => item.entry.path),
        outputDir: outputDir.trim() || undefined,
      }).then((answer) => {
        if (cancelled) return;
        setPlan(answer);
        setStaged(staged.map((item) => {
          const hit = answer.items.find((candidate) => candidate.input === item.entry.path);
          return hit
            ? { ...item, output: hit.output, outputAbsolute: hit.outputAbsolute, error: hit.error }
            : item;
        }));
      }).catch((e) => {
        if (!cancelled) setError(errMsg(e));
      });
    }, 400);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [stagedKey, presetDetail, outputDir]);

  useEffect(() => {
    if (!selected || !presetDetail) {
      setPreview(null);
      return;
    }
    let cancelled = false;
    const timer = window.setTimeout(() => {
      void api.transcode.preview({
        preset: presetDetail,
        input: selected.path,
        outputDir: outputDir.trim() || undefined,
      }).then((answer) => {
        if (!cancelled) setPreview(answer);
      }).catch(() => {
        if (!cancelled) setPreview(null);
      });
    }, 600);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [selected, presetDetail, outputDir]);

  const enqueue = async (items = staged) => {
    if (items.length === 0 || !presetId || !checkFoolproof()) return;
    setBusy(true);
    setError(null);
    const added: string[] = [];
    const failures: string[] = [];
    for (const item of items) {
      try {
        await api.transcode.submitJob({
          input: item.entry.path,
          presetId,
          outputDir: outputDir.trim() || undefined,
        });
        added.push(item.entry.path);
      } catch (e) {
        failures.push(`${item.entry.name}: ${errMsg(e)}`);
      }
    }
    setBusy(false);
    if (added.length > 0) {
      try {
        await ensureQueueRunning();
      } catch (e) {
        toast("error", `任务已入队，但启动队列失败：${errMsg(e)}`);
      }
      toast("success", `已加入 ${added.length} 个任务`);
      removeStaged(added);
      setPlan(null);
      onOpenTab("queue");
    }
    if (failures.length > 0) {
      setError(failures.slice(0, 3).join("\n") + (failures.length > 3 ? `\n…还有 ${failures.length - 3} 个` : ""));
      toast("error", `${failures.length} 个文件未加入，仍保留在准备列表`);
    }
  };

  const toggleChecked = (path: string) => {
    setChecked((current) => {
      const next = new Set(current);
      if (next.has(path)) next.delete(path);
      else next.add(path);
      return next;
    });
  };

  const outputSelection = outputDir.trim();
  const outputLocation = outputSelection === "."
    ? (plan?.outputDir || mediaRoot)
    : outputSelection
      ? (plan?.outputDir || `${mediaRoot}/${outputSelection}`.replace(/\/+/g, "/"))
      : "每个源文件旁边";
  const allChecked = staged.length > 0 && checked.size === staged.length;

  return (
    <div className="tc-prepare">
      <div className="card tc-prepare-toolbar">
        <button
          className="btn btn-raised"
          disabled={busy || staged.length === 0 || !presetId}
          onClick={() => void enqueue()}
        >
          {busy ? "提交中…" : `加入编码队列（${staged.length}）`}
        </button>
        <button className={`btn ${browserOpen ? "btn-raised" : "btn-text"}`} onClick={() => setBrowserOpen(!browserOpen)}>
          {browserOpen ? "收起文件浏览" : "添加文件"}
        </button>
        <button className="btn btn-text" disabled={scanning} onClick={() => void addFolderPath("")}>
          {scanning ? "扫描中…" : "添加媒体根及子目录"}
        </button>
        <button
          className="btn btn-text"
          disabled={checked.size === 0}
          onClick={() => {
            removeStaged([...checked]);
            setChecked(new Set());
          }}
        >
          移除选中{checked.size > 0 ? `（${checked.size}）` : ""}
        </button>
        <button className="btn btn-text" disabled={staged.length === 0} onClick={clearStaged}>移除全部</button>
        <span style={{ flex: 1 }} />
        <select className="input tc-prepare-sort" value={sortBy} onChange={(e) => setSortBy(e.target.value as SortBy)}>
          <option value="name">排序：文件名</option>
          <option value="size">排序：大小</option>
          <option value="ext">排序：后缀</option>
        </select>
      </div>

      {browserOpen && (
        <MediaBrowserPanel
          selected={selected?.path}
          onSelect={(entry) => void select(entry)}
          onAddFile={(entry) => {
            addEntries([entry]);
            void select(entry);
          }}
          onAddFolder={(entry) => void addFolderPath(entry.path)}
          onAddCurrentFolder={(path) => void addFolderPath(path)}
        />
      )}

      <div className="card tc-prepare-config">
        <label>
          <span>当前预设</span>
          <select className="input" value={presetId} disabled={busy || presets.length === 0} onChange={(e) => setPresetId(e.target.value)}>
            <option value="">选择预设…</option>
            {presets.map((preset) => (
              <option key={preset.id} value={preset.id}>{preset.name}{preset.builtin ? "" : "（自定义）"}</option>
            ))}
          </select>
        </label>
        <label>
          <span>输出目录（相对媒体根）</span>
          <span className="tc-prepare-output">
            <input className="input" value={outputDir} disabled={busy} placeholder="留空 = 源文件旁边" onChange={(e) => setOutputDir(e.target.value)} />
            <button className="btn btn-text" disabled={busy} onClick={() => setPickerOpen(true)}>选择</button>
          </span>
        </label>
        <div className="tc-prepare-config-side">
          <button className="btn btn-text" onClick={() => onOpenTab("presets")}>参数面板</button>
          <button className="btn btn-text" disabled={busy || outputDir === ""} onClick={() => setOutputDir("")}>源目录</button>
        </div>
        {presetDetail && (
          <div className="tc-prepare-summary">
            <span className="tag plain">{valueLabel("videoEncoder", presetDetail.videoEncoder)}</span>
            <span className="tag plain">{valueLabel("container", presetDetail.container)}</span>
            <span className="tag plain">{valueLabel("qualityMode", presetDetail.qualityMode)} {String(presetDetail.qualityValue ?? "")}</span>
            <span className="tag plain">音频 {valueLabel("audioMode", presetDetail.audioMode)}</span>
            <span className="tag plain">字幕 {valueLabel("subtitleMode", presetDetail.subtitleMode)}</span>
          </div>
        )}
        <div className="tc-prepare-location">输出到：<span className="mono">{outputLocation}</span></div>
      </div>

      {error && <div className="card tc-error">{error}</div>}

      <div className="card tc-stage-card">
        <div className="tc-stage-head">
          <b>待转码文件</b>
          <span>{staged.length} 个 · 切换页面不会清空</span>
        </div>
        {staged.length === 0 ? (
          <div className="empty-state tc-stage-empty">
            <span>还没有准备文件</span>
            <span className="empty-sub">点上方「添加文件」逐个选择，或递归添加整个媒体目录</span>
          </div>
        ) : (
          <div className="tc-stage-scroll">
            <table className="tc-stage-table">
              <thead>
                <tr>
                  <th><input type="checkbox" checked={allChecked} onChange={() => setChecked(allChecked ? new Set() : new Set(staged.map((item) => item.entry.path)))} /></th>
                  <th>文件名</th>
                  <th>路径</th>
                  <th>后缀</th>
                  <th>大小</th>
                  <th>输出文件</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {sortedStaged.map((item) => (
                  <tr key={item.entry.path} className={selected?.path === item.entry.path ? "selected" : ""}>
                    <td><input type="checkbox" checked={checked.has(item.entry.path)} onChange={() => toggleChecked(item.entry.path)} /></td>
                    <td className="tc-stage-name" onClick={() => void select(item.entry)}>{item.entry.name}</td>
                    <td className="mono tc-stage-path" onClick={() => void select(item.entry)}>{item.entry.path}</td>
                    <td className="mono">{extension(item.entry.path)}</td>
                    <td className="mono">{fmtBytes(item.entry.size)}</td>
                    <td className={`mono tc-stage-output${item.error ? " error" : ""}`}>{item.error || item.outputAbsolute || "计算中…"}</td>
                    <td><button className="btn btn-text" onClick={() => removeStaged([item.entry.path])}>移除</button></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {selected && (
        <div className="tc-prepare-inspect">
          <div className="card">
            <div className="tc-card-title">{selected.name}</div>
            <div className="tc-prepare-selected-path mono">{selected.path}</div>
            <div className="tc-prepare-probe">{probe ? probeSummary(probe) : "正在读取媒体信息…"} · {fmtBytes(selected.size)}</div>
            {selected && staged.some((item) => item.entry.path === selected.path) && (
              <button className="btn btn-text" disabled={busy || !presetId} onClick={() => void enqueue(staged.filter((item) => item.entry.path === selected.path))}>
                仅加入这个文件
              </button>
            )}
          </div>
          <div className="card tc-prepare-command-card">
            <div className="tc-card-title">实际命令行预览</div>
            <pre className="tc-prepare-command">{preview?.command || "选择预设后自动生成；这里只预览，不执行"}</pre>
          </div>
        </div>
      )}

      <HelpNote title="准备文件说明">
        <ul>
          <li>准备列表、当前预设和输出目录在整个转码工作区共享；去参数页调整后可以直接在那里入队。</li>
          <li>输出目录留空时写到每个源文件旁边；同名文件由服务端自动加序号，不覆盖已有文件。</li>
          <li>列表中的输出路径由服务端预先计算；入队失败的文件会留在列表里，不会被误清空。</li>
        </ul>
      </HelpNote>

      <DirectoryPicker
        open={pickerOpen}
        initial={outputDir}
        mediaRoot={mediaRoot}
        onClose={() => setPickerOpen(false)}
        onSelect={(path) => {
          setOutputDir(path);
          setPickerOpen(false);
        }}
      />
    </div>
  );
}
