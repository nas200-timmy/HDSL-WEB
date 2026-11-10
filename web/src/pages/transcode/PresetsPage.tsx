import { useEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  api,
  type TranscodePreset,
  type TranscodePresetEntry,
  type TranscodePresetSummary,
  type TranscodeSchema,
  type TranscodeSchemaField,
} from "../../api";
import { toast } from "../../store";
import { errMsg } from "../../utils";
import { Dialog } from "../../components/Dialog";
import { ContentCopyIcon, RefreshIcon, SearchIcon } from "../../components/icons";

/**
 * 参数面板工作台。
 *
 * 纵向导航是唯一的入口：参数总览 / 预设管理 / 各参数分区 / 自定义参数。
 * “预设管理”不是常驻侧栏，而是像 3FUI 那样的一页——单击预览，双击或“应用”才
 * 把它读进参数面板；右侧始终是参数总览 + 实时命令行。
 */

type FieldIssue = { field: string; text: string };

const RESTRICTIVE_CONTAINERS = ["mp4", "mov", "avi", "flv", "ts", "m2ts", "m4v"];

export function PresetsPage() {
  const nav = useNavigate();
  const [summaries, setSummaries] = useState<TranscodePresetSummary[]>([]);
  const [schema, setSchema] = useState<TranscodeSchema | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [entry, setEntry] = useState<TranscodePresetEntry | null>(null);
  const [draft, setDraft] = useState<TranscodePreset>({});
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [activeSection, setActiveSection] = useState<string | null>(null);
  const [presetSearch, setPresetSearch] = useState("");
  const [fieldSearch, setFieldSearch] = useState("");
  const [onlyChanged, setOnlyChanged] = useState(false);
  const [preview, setPreview] = useState("");
  const [previewError, setPreviewError] = useState<string | null>(null);
  const [previewBusy, setPreviewBusy] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [saveAsOpen, setSaveAsOpen] = useState(false);
  const [saveAsId, setSaveAsId] = useState("");
  const [saveAsName, setSaveAsName] = useState("");
  // 预设管理页：单击预览的那一份，不直接影响正在编辑的 draft
  const [managedId, setManagedId] = useState<string | null>(null);
  const [managedEntry, setManagedEntry] = useState<TranscodePresetEntry | null>(null);
  const [managedPreview, setManagedPreview] = useState("");
  const [managedError, setManagedError] = useState<string | null>(null);
  const fileInput = useRef<HTMLInputElement | null>(null);
  const previewSeq = useRef(0);

  const baseDraft = useMemo(() => {
    if (!schema) return {} as TranscodePreset;
    const base: TranscodePreset = { name: "", description: "" };
    for (const section of schema.sections) {
      for (const field of section.fields) base[field.id] = field.default;
    }
    base.inputArgs = [];
    base.videoArgs = [];
    base.audioArgs = [];
    base.subtitleArgs = [];
    base.outputArgs = [];
    return base;
  }, [schema]);

  const loadList = async (preferred?: string) => {
    try {
      const answer = await api.transcode.presets();
      setSummaries(answer.presets);
      if (preferred) await select(preferred);
    } catch (e) {
      setError(errMsg(e));
    }
  };

  const select = async (id: string) => {
    setSelectedId(id);
    setError(null);
    try {
      const answer = await api.transcode.preset(id);
      setEntry(answer);
      setDraft({ ...answer.preset });
      setDirty(false);
      sessionStorage.setItem("transcode.presetId", id);
    } catch (e) {
      setError(errMsg(e));
    }
  };

  useEffect(() => {
    void api.transcode.schema().then(setSchema).catch((e) => setError(errMsg(e)));
    const preferred = sessionStorage.getItem("transcode.presetId");
    void loadList(preferred && preferred.length > 0 ? preferred : undefined);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (!schema || entry || dirty) return;
    if (Object.keys(draft).length > 0) return;
    setDraft(baseDraft);
  }, [schema, entry, draft, baseDraft, dirty]);

  // 实时命令行：改参数后 600ms 自动刷新，失败只显示在预览区，不弹 toast。
  useEffect(() => {
    if (!schema || Object.keys(draft).length === 0) return;
    const seq = ++previewSeq.current;
    const timer = window.setTimeout(() => {
      if (!draft.container || !draft.videoEncoder || !draft.qualityMode || !draft.qualityValue) {
        setPreview("");
        setPreviewError("容器、视频编码器、质量模式与质量值是生成命令的必填项");
        return;
      }
      setPreviewBusy(true);
      void api.transcode
        .preview({ preset: draft })
        .then((answer) => {
          if (seq !== previewSeq.current) return;
          setPreview(answer.command);
          setPreviewError(null);
        })
        .catch((e) => {
          if (seq !== previewSeq.current) return;
          setPreview("");
          setPreviewError(errMsg(e));
        })
        .finally(() => {
          if (seq === previewSeq.current) setPreviewBusy(false);
        });
    }, 600);
    return () => window.clearTimeout(timer);
  }, [draft, schema]);

  const update = (id: string, value: unknown) => {
    setDirty(true);
    setDraft((current) => {
      const next = { ...current, [id]: value };
      // 与 3FUI 一致的编码器联动：图片编码器丢音/字幕，硬件编码器清掉软件档位。
      if (id === "videoEncoder") {
        const encoder = String(value);
        const image = /^(mjpeg|png|webp|libwebp|bmp|tiff|gif|avif|libaom-av1|jxl|libjxl|jpegxl)$/.test(encoder);
        const hardware = /_vaapi|_qsv|_nvenc/.test(encoder);
        if (image) {
          next.audioMode = "none";
          next.subtitleMode = "none";
          next.copyAttachments = false;
          next.copyChapters = false;
        } else if (hardware) {
          next.qualityMode = next.qualityMode === "bitrate" ? "bitrate" : "cqp";
          next.preset = "";
          next.profile = "";
          next.tune = "";
        } else if (next.qualityMode !== "bitrate") {
          next.qualityMode = "crf";
        }
      }
      return next;
    });
  };

  const save = async (id: string, next: TranscodePreset) => {
    setBusy(true);
    setError(null);
    try {
      const saved = await api.transcode.savePreset(id, next);
      setEntry(saved);
      setDraft({ ...saved.preset });
      setDirty(false);
      setSelectedId(saved.id);
      sessionStorage.setItem("transcode.presetId", saved.id);
      await loadList(saved.id);
      toast("success", "预设已保存");
    } catch (e) {
      setError(errMsg(e));
      toast("error", errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  const openSaveAs = () => {
    const id = selectedId ? `${selectedId}-copy` : "my-preset";
    setSaveAsId(id);
    setSaveAsName(String(draft.name ?? (entry?.builtin ? `${entry.id} 副本` : "我的参数")));
    setSaveAsOpen(true);
  };

  const saveAs = async () => {
    const id = saveAsId.trim();
    if (!/^[A-Za-z0-9._-]+$/.test(id)) {
      toast("error", "id 只能包含字母、数字、点、下划线和短横线");
      return;
    }
    if (!saveAsName.trim()) {
      toast("error", "名称不能为空");
      return;
    }
    setSaveAsOpen(false);
    await save(id, { ...draft, name: saveAsName.trim() });
  };

  const remove = async () => {
    if (!entry || entry.builtin) return;
    if (!window.confirm(`删除预设「${entry.preset.name ?? entry.id}」？`)) return;
    setBusy(true);
    try {
      await api.transcode.deletePreset(entry.id);
      toast("success", "预设已删除");
      setSelectedId(null);
      setEntry(null);
      setDraft(baseDraft);
      setDirty(false);
      sessionStorage.removeItem("transcode.presetId");
      await loadList();
    } catch (e) {
      toast("error", errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  const importFile = async (file: File) => {
    setBusy(true);
    setError(null);
    try {
      const data = JSON.parse(await file.text()) as Record<string, unknown>;
      const id = window.prompt("导入为预设 id（字母/数字/._-）", file.name.replace(/\.(3fui|json)$/i, ""));
      if (!id) return;
      const saved = await api.transcode.importPreset(id, data);
      toast("success", "预设已导入");
      await loadList(saved.id);
      setActiveSection("预设管理");
    } catch (e) {
      setError(errMsg(e));
      toast("error", errMsg(e));
    } finally {
      setBusy(false);
      if (fileInput.current) fileInput.current.value = "";
    }
  };

  // 预设管理：单击只预览（不动当前参数），双击/“应用”才读进参数面板。
  const inspectPreset = async (id: string) => {
    setManagedId(id);
    setManagedError(null);
    try {
      const answer = await api.transcode.preset(id);
      setManagedEntry(answer);
      try {
        const command = await api.transcode.preview({ preset: answer.preset });
        setManagedPreview(command.command);
      } catch {
        setManagedPreview("");
      }
    } catch (e) {
      setManagedError(errMsg(e));
      setManagedEntry(null);
    }
  };

  const applyManaged = async () => {
    if (!managedId) return;
    await select(managedId);
    setActiveSection(null);
    toast("success", "已应用预设到参数面板");
  };

  const changedFields = useMemo(() => {
    const changed = new Set<string>();
    if (!schema) return changed;
    const baseline = entry?.preset ?? baseDraft;
    for (const section of schema.sections) {
      for (const field of section.fields) {
        if (JSON.stringify(draft[field.id]) !== JSON.stringify(baseline[field.id])) changed.add(field.id);
        else if (!entry && JSON.stringify(field.default) !== JSON.stringify(baseline[field.id])) {
          changed.add(field.id);
        }
      }
    }
    return changed;
  }, [draft, entry, schema, baseDraft]);

  const issues = useMemo<FieldIssue[]>(() => {
    const list: FieldIssue[] = [];
    if (!draft.container) list.push({ field: "container", text: "没有选择输出容器" });
    if (!draft.videoEncoder) list.push({ field: "videoEncoder", text: "没有选择视频编码器" });
    if (!draft.qualityValue) list.push({ field: "qualityValue", text: "没有设置质量值 / 码率" });
    const container = String(draft.container ?? "").toLowerCase();
    if (String(draft.subtitleMode) === "burn" && !String(draft.subtitleFile ?? "").trim()) {
      list.push({ field: "subtitleFile", text: "字幕模式是「烧录」，但没有指定字幕文件" });
    }
    if (RESTRICTIVE_CONTAINERS.includes(container)) {
      if (String(draft.subtitleMode) === "copy") {
        list.push({ field: "container", text: `${container.toUpperCase()} 只能装 mov_text 字幕，copy 其它字幕会报错` });
      }
      if (draft.copyAttachments === true) {
        list.push({ field: "copyAttachments", text: `${container.toUpperCase()} 不支持附件流（字体/封面）` });
      }
      if (draft.copyChapters === true) {
        list.push({ field: "copyChapters", text: `${container.toUpperCase()} 对章节支持有限，可能被丢弃` });
      }
    }
    if (/_vaapi|_qsv|_nvenc/.test(String(draft.videoEncoder ?? "")) && String(draft.preset ?? "") !== "") {
      list.push({ field: "preset", text: "硬件编码器会忽略软件 preset，保存前会被自动清空" });
    }
    return list;
  }, [draft]);

  const overview = useMemo(() => {
    const video = String(draft.videoEncoder ?? "");
    const quality = `${String(draft.qualityMode ?? "")} ${String(draft.qualityValue ?? "")}`.trim();
    return [
      { label: "视频", value: video || "—", field: "videoEncoder" },
      { label: "质量", value: quality || "—", field: "qualityValue" },
      { label: "容器", value: String(draft.container ?? "—"), field: "container" },
      { label: "音频", value: String(draft.audioMode ?? "—"), field: "audioMode" },
      { label: "字幕", value: String(draft.subtitleMode ?? "—"), field: "subtitleMode" },
      { label: "命名", value: String(draft.outputNaming ?? "—"), field: "outputNaming" },
    ];
  }, [draft]);

  const filteredPresets = summaries.filter((preset) => {
    const q = presetSearch.trim().toLowerCase();
    if (!q) return true;
    return preset.name.toLowerCase().includes(q) || preset.id.toLowerCase().includes(q)
      || preset.description.toLowerCase().includes(q);
  });

  const search = fieldSearch.trim().toLowerCase();
  const sections = (schema?.sections ?? []).filter((section) => activeSection === null || activeSection === section.title);
  const visibleSections = (search
    ? sections.map((section) => ({
        ...section,
        fields: section.fields.filter((field) =>
          field.label.toLowerCase().includes(search) || field.id.toLowerCase().includes(search)
          || String(draft[field.id] ?? "").toLowerCase().includes(search)),
      })).filter((section) => section.fields.length > 0)
    : sections).map((section) => ({
      ...section,
      fields: onlyChanged ? section.fields.filter((field) => changedFields.has(field.id)) : section.fields,
    })).filter((section) => section.fields.length > 0);

  const managing = activeSection === "预设管理";

  return (
    <div className="tc-workbench">
      {/* ── 纵向导航（预设管理是其中一项，不是常驻侧栏）───────────────── */}
      <aside className="card tc-section-nav">
        <div
          className={`tc-nav-item${activeSection === null ? " selected" : ""}`}
          onClick={() => setActiveSection(null)}
        >
          参数总览
        </div>
        <div
          className={`tc-nav-item${managing ? " selected" : ""}`}
          onClick={() => {
            setActiveSection("预设管理");
            if (!managedId && selectedId) void inspectPreset(selectedId);
          }}
        >
          预设管理
        </div>
        <div className="tc-nav-divider" />
        {(schema?.sections ?? []).map((section) => (
          <div
            key={section.title}
            className={`tc-nav-item${activeSection === section.title ? " selected" : ""}`}
            onClick={() => setActiveSection(section.title)}
          >
            {section.title}
          </div>
        ))}
        <div className="tc-nav-divider" />
        <div
          className={`tc-nav-item${activeSection === "自定义参数" ? " selected" : ""}`}
          onClick={() => setActiveSection("自定义参数")}
        >
          自定义参数
        </div>
        <button className="tc-nav-item" onClick={() => nav("/transcode?tab=prepare")}>
          去准备文件 →
        </button>
      </aside>

      {/* ── 参数区 / 预设管理 ─────────────────────────────────────── */}
      <section className="tc-editor">
        <div className="card tc-editor-head">
          <div className="tc-editor-title">
            <input
              className="input tc-name"
              value={String(draft.name ?? "")}
              placeholder="预设名称"
              disabled={entry?.builtin === true || managing}
              onChange={(e) => update("name", e.target.value)}
            />
            <span className="mono tc-id">
              {managing ? "预设管理 · 单击预览，双击应用" : entry ? entry.id : "未保存的自定义参数"}
              {!managing && entry?.builtin ? "（只读）" : ""}
            </span>
            {dirty && !managing && <span className="tag plain">未保存</span>}
          </div>
          <input
            className="input"
            value={String(draft.description ?? "")}
            placeholder="描述（可选）"
            disabled={entry?.builtin === true || managing}
            onChange={(e) => update("description", e.target.value)}
          />
          <div className="tc-editor-actions">
            {managing ? (
              <>
                <button
                  className="btn btn-text"
                  disabled={busy || !schema}
                  onClick={() => {
                    setEntry(null);
                    setSelectedId(null);
                    setDraft(baseDraft);
                    setDirty(false);
                    setActiveSection(null);
                    sessionStorage.removeItem("transcode.presetId");
                  }}
                >
                  新建
                </button>
                <button className="btn btn-text" disabled={busy} onClick={() => void loadList()}>
                  <RefreshIcon size={16} />
                  刷新
                </button>
                <button className="btn btn-text" disabled={busy} onClick={() => fileInput.current?.click()}>
                  导入
                </button>
                <input
                  ref={fileInput}
                  type="file"
                  accept=".json,.3fui,application/json"
                  style={{ display: "none" }}
                  onChange={(e) => {
                    const file = e.target.files?.[0];
                    if (file) void importFile(file);
                  }}
                />
              </>
            ) : entry?.builtin ? (
              <button className="btn btn-raised" disabled={busy} onClick={openSaveAs}>
                另存为
              </button>
            ) : entry ? (
              <>
                <button className="btn btn-raised" disabled={busy || !dirty} onClick={() => void save(entry.id, draft)}>
                  保存
                </button>
                <button className="btn btn-text" disabled={busy} onClick={openSaveAs}>
                  另存为
                </button>
                <button className="btn btn-text danger" disabled={busy} onClick={() => void remove()}>
                  删除
                </button>
              </>
            ) : (
              <button className="btn btn-raised" disabled={busy || !schema} onClick={openSaveAs}>
                保存为预设
              </button>
            )}
            <span style={{ flex: 1 }} />
            {!managing && (
              <>
                <div className="tc-search-inline">
                  <SearchIcon size={16} />
                  <input value={fieldSearch} placeholder="搜索参数" onChange={(e) => setFieldSearch(e.target.value)} />
                </div>
                <label className="tc-check">
                  <input type="checkbox" checked={onlyChanged} onChange={(e) => setOnlyChanged(e.target.checked)} />
                  只看已改
                </label>
              </>
            )}
          </div>
        </div>

        {error && <div className="card tc-error">{error}</div>}

        {managing ? (
          <div className="card tc-manage">
            <div className="tc-manage-toolbar">
              <div className="tc-search">
                <SearchIcon size={16} />
                <input value={presetSearch} placeholder="搜索预设" onChange={(e) => setPresetSearch(e.target.value)} />
              </div>
              {managedEntry && !managedEntry.builtin && (
                <button
                  className="btn btn-text danger"
                  disabled={busy}
                  onClick={() => {
                    if (!window.confirm(`删除预设「${managedEntry.preset.name ?? managedEntry.id}」？`)) return;
                    void api.transcode.deletePreset(managedEntry.id).then(() => {
                      toast("success", "预设已删除");
                      setManagedId(null);
                      setManagedEntry(null);
                      setManagedPreview("");
                      if (selectedId === managedEntry.id) {
                        setSelectedId(null);
                        setEntry(null);
                        setDraft(baseDraft);
                      }
                      return loadList();
                    }).catch((e) => toast("error", errMsg(e)));
                  }}
                >
                  删除此预设
                </button>
              )}
            </div>
            <div className="tc-manage-body">
              <div className="tc-manage-list scroll-hover">
                {filteredPresets.length === 0 && <div className="tc-empty">没有匹配的预设</div>}
                {filteredPresets.map((preset) => (
                  <div
                    key={preset.id}
                    className={`tc-preset-item${managedId === preset.id ? " selected" : ""}`}
                    onClick={() => void inspectPreset(preset.id)}
                    onDoubleClick={() => void select(preset.id).then(() => {
                      setActiveSection(null);
                      toast("success", "已应用预设到参数面板");
                    })}
                    title="单击预览，双击应用"
                  >
                    <span className="tc-preset-name">
                      {preset.name}
                      <span className={`tag ${preset.builtin ? "plain" : "running"}`}>{preset.builtin ? "内置" : "自定义"}</span>
                      {selectedId === preset.id && <span className="tag running">当前</span>}
                    </span>
                    <span className="tc-preset-desc">{preset.description || preset.id}</span>
                  </div>
                ))}
              </div>
              <div className="tc-manage-preview">
                {managedError && <div className="tc-error">{managedError}</div>}
                {!managedEntry && !managedError && (
                  <div className="tc-empty">选择左侧预设查看参数与命令行</div>
                )}
                {managedEntry && (
                  <>
                    <div className="tc-card-title">
                      {String(managedEntry.preset.name ?? managedEntry.id)}
                      <span className="mono tc-id">{managedEntry.id}</span>
                    </div>
                    <div className="tc-manage-rows">
                      <span>视频</span><span className="mono">{String(managedEntry.preset.videoEncoder ?? "—")}</span>
                      <span>质量</span><span className="mono">{String(managedEntry.preset.qualityMode ?? "")} {String(managedEntry.preset.qualityValue ?? "")}</span>
                      <span>容器</span><span className="mono">{String(managedEntry.preset.container ?? "—")}</span>
                      <span>音频</span><span className="mono">{String(managedEntry.preset.audioMode ?? "—")}</span>
                      <span>字幕</span><span className="mono">{String(managedEntry.preset.subtitleMode ?? "—")}</span>
                      <span>描述</span><span>{String(managedEntry.preset.description ?? "—")}</span>
                    </div>
                    <pre className="tc-command">{managedPreview || "（无法生成命令行）"}</pre>
                    <div className="tc-manage-actions">
                      <button className="btn btn-raised" disabled={busy} onClick={() => void applyManaged()}>
                        应用此预设
                      </button>
                      <button
                        className="btn btn-text"
                        disabled={!managedPreview}
                        onClick={() => void navigator.clipboard?.writeText(managedPreview).then(
                          () => toast("success", "命令行已复制"),
                          () => toast("error", "复制失败（需要 HTTPS 或 localhost）"),
                        )}
                      >
                        <ContentCopyIcon size={16} />
                        复制命令行
                      </button>
                      {!managedEntry.builtin && (
                        <button
                          className="btn btn-text"
                          disabled={busy}
                          onClick={() => {
                            void select(managedEntry.id).then(() => {
                              setActiveSection(null);
                              openSaveAs();
                            });
                          }}
                        >
                          另存为副本
                        </button>
                      )}
                      <button
                        className="btn btn-text"
                        onClick={() => void api.transcode.exportPreset(managedEntry.id, "3fui").then((data) => {
                          const blob = new Blob([JSON.stringify(data, null, 2)], { type: "application/json" });
                          const url = URL.createObjectURL(blob);
                          const link = document.createElement("a");
                          link.href = url;
                          link.download = `${managedEntry.id}.3fui`;
                          link.click();
                          URL.revokeObjectURL(url);
                        }).catch((e) => toast("error", errMsg(e)))}
                      >
                        导出 .3fui
                      </button>
                    </div>
                  </>
                )}
              </div>
            </div>
          </div>
        ) : (
          <>
            {(activeSection === null || activeSection === "自定义参数") && (
              <div className="card tc-overview-card">
                <div className="tc-card-title">参数总览</div>
                <div className="tc-overview-grid">
                  {overview.map((item) => (
                    <div key={item.label} className="tc-overview-cell">
                      <span className="tc-overview-label">{item.label}</span>
                      <span className="tc-overview-value mono">{item.value}</span>
                    </div>
                  ))}
                </div>
                {issues.length > 0 && (
                  <div className="tc-issues">
                    {issues.map((issue) => (
                      <button
                        key={issue.text}
                        className="tc-issue"
                        onClick={() => {
                          const section = schema?.sections.find((s) => s.fields.some((f) => f.id === issue.field));
                          if (section) setActiveSection(section.title);
                        }}
                      >
                        {issue.text}
                      </button>
                    ))}
                  </div>
                )}
                {issues.length === 0 && <div className="tc-ok">参数没有明显问题</div>}
              </div>
            )}

            {visibleSections.map((section) => (
              <div className="card tc-fields-card" key={section.title}>
                <div className="tc-card-title">{section.title}</div>
                <div className="tc-fields-grid">
                  {section.fields.map((field) => (
                    <Field
                      key={field.id}
                      field={field}
                      value={draft[field.id] ?? field.default}
                      changed={changedFields.has(field.id)}
                      disabled={entry?.builtin === true}
                      onChange={(value) => update(field.id, value)}
                    />
                  ))}
                </div>
              </div>
            ))}

            {(activeSection === null || activeSection === "自定义参数") && (
              <CustomArgsEditor draft={draft} disabled={entry?.builtin === true} update={update} />
            )}
          </>
        )}
      </section>

      {/* ── 右侧：命令行 ─────────────────────────────────────────── */}
      <aside className="tc-inspector">
        <div className="card tc-command-card">
          <div className="tc-card-title">
            实时命令行
            <span className="tc-command-state">
              {previewBusy ? "生成中…" : previewError ? "生成失败" : "只生成，不执行"}
            </span>
          </div>
          <div className="tc-command-actions">
            <button
              className="btn btn-text"
              disabled={!preview}
              onClick={() => {
                void navigator.clipboard?.writeText(preview).then(
                  () => toast("success", "命令行已复制"),
                  () => toast("error", "复制失败（需要 HTTPS 或 localhost）"),
                );
              }}
            >
              <ContentCopyIcon size={16} />
              复制
            </button>
            <button
              className="btn btn-text"
              disabled={previewBusy}
              onClick={() => setDraft((current) => ({ ...current }))}
            >
              立即刷新
            </button>
          </div>
          {previewError ? (
            <div className="tc-command-error">{previewError}</div>
          ) : (
            <pre className="tc-command">{preview || "改一个参数就会在这里看到命令"}</pre>
          )}
          <p className="tc-command-note">
            输入/输出用 <span className="mono">&lt;输入文件&gt;</span> 占位；实际入队时会替换成媒体根里的真实路径。
          </p>
        </div>
      </aside>

      <Dialog
        open={saveAsOpen}
        title="保存为预设"
        onClose={() => setSaveAsOpen(false)}
        footer={
          <>
            <button className="btn btn-text" onClick={() => setSaveAsOpen(false)}>取消</button>
            <button className="btn btn-raised" disabled={busy} onClick={() => void saveAs()}>保存</button>
          </>
        }
      >
        <div style={{ display: "grid", gap: 8 }}>
          <label style={{ fontSize: 12 }}>预设 id（字母/数字/._-）</label>
          <input className="input" value={saveAsId} onChange={(e) => setSaveAsId(e.target.value)} />
          <label style={{ fontSize: 12 }}>名称</label>
          <input className="input" value={saveAsName} onChange={(e) => setSaveAsName(e.target.value)} />
          <span style={{ fontSize: 11, color: "var(--monet-on-surface-variant)" }}>
            同名 id 会覆盖已有用户预设；内置预设不会被修改。
          </span>
        </div>
      </Dialog>
    </div>
  );
}

function CustomArgsEditor({
  draft,
  disabled,
  update,
}: {
  draft: TranscodePreset;
  disabled: boolean;
  update: (id: string, value: unknown) => void;
}) {
  const listText = (value: unknown) => (Array.isArray(value) ? value.join("\n") : "");
  const setList = (id: string, text: string) =>
    update(id, text.split(/\r?\n/).map((line) => line.trim()).filter(Boolean));
  return (
    <div className="card tc-fields-card">
      <div className="tc-card-title">自定义参数</div>
      <div className="tc-custom-grid">
        {[
          ["inputArgs", "输入前参数"],
          ["videoArgs", "视频附加参数"],
          ["audioArgs", "音频附加参数"],
          ["subtitleArgs", "字幕附加参数"],
          ["outputArgs", "输出前参数"],
        ].map(([id, label]) => (
          <label key={id} className="tc-field">
            <span className="tc-field-label">{label}</span>
            <textarea
              className="input"
              rows={2}
              disabled={disabled}
              value={listText(draft[id])}
              onChange={(e) => setList(id, e.target.value)}
            />
            <span className="tc-field-hint">每行一个 argv，不做 shell 解析</span>
          </label>
        ))}
      </div>
    </div>
  );
}

function Field({
  field,
  value,
  changed,
  disabled,
  onChange,
}: {
  field: TranscodeSchemaField;
  value: unknown;
  changed: boolean;
  disabled: boolean;
  onChange: (value: unknown) => void;
}) {
  const optionHint = field.type === "select"
    ? field.optionHints?.[String(value ?? "")]
    : undefined;
  return (
    <label className={`tc-field${field.important ? " important" : ""}`}>
      <span className="tc-field-label">
        {field.label}
        {changed && <span className="tc-changed-dot" title="与基线不同" />}
      </span>
      {field.type === "switch" ? (
        <span className="tc-switch">
          <input
            type="checkbox"
            checked={value === true}
            disabled={disabled}
            onChange={(e) => onChange(e.target.checked)}
          />
          <span>{value === true ? "开启" : "关闭"}</span>
        </span>
      ) : field.type === "textarea" ? (
        <textarea
          className="input"
          rows={3}
          value={String(value ?? "")}
          disabled={disabled}
          placeholder={field.hint}
          onChange={(e) => onChange(e.target.value)}
        />
      ) : field.type === "select" ? (
        <select
          className="input"
          value={String(value ?? "")}
          disabled={disabled}
          onChange={(e) => onChange(e.target.value)}
        >
          {(field.options ?? []).map((option) => (
            <option key={option} value={option} title={field.optionHints?.[option]}>
              {option || "（默认）"}
            </option>
          ))}
        </select>
      ) : (
        <input
          className="input"
          value={String(value ?? "")}
          disabled={disabled}
          onChange={(e) => onChange(e.target.value)}
        />
      )}
      {(optionHint || field.hint) && (
        <span className="tc-field-hint">{optionHint ?? field.hint}</span>
      )}
    </label>
  );
}
