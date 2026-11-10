import { useEffect, useRef, useState } from "react";
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

export function PresetsPage() {
  const [summaries, setSummaries] = useState<TranscodePresetSummary[]>([]);
  const [schema, setSchema] = useState<TranscodeSchema | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [entry, setEntry] = useState<TranscodePresetEntry | null>(null);
  const [draft, setDraft] = useState<TranscodePreset>({});
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [filterDialog, setFilterDialog] = useState(false);
  const [inputPath, setInputPath] = useState("");
  const [preview, setPreview] = useState<string>("");
  const [previewError, setPreviewError] = useState<string | null>(null);
  const [activeSection, setActiveSection] = useState<string | null>(null);
  const fileInput = useRef<HTMLInputElement | null>(null);

  const loadList = async (preferred?: string) => {
    try {
      const answer = await api.transcode.presets();
      setSummaries(answer.presets);
      const target = preferred ?? selectedId ?? answer.presets[0]?.id;
      if (target) await select(target);
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
    } catch (e) {
      setError(errMsg(e));
    }
  };

  useEffect(() => {
    void api.transcode.schema().then(setSchema).catch((e) => setError(errMsg(e)));
    void loadList();
  }, []);

  const save = async (id: string, next: TranscodePreset) => {
    setBusy(true);
    setError(null);
    try {
      const saved = await api.transcode.savePreset(id, next);
      setEntry(saved);
      setDraft({ ...saved.preset });
      await loadList(saved.id);
      toast("success", "预设已保存");
    } catch (e) {
      setError(errMsg(e));
      toast("error", errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  const saveAs = async () => {
    if (!entry) return;
    const id = window.prompt("新预设 id（字母/数字/._-）", `${entry.id}-copy`);
    if (!id) return;
    const name = window.prompt("预设名称", `${entry.preset.name ?? entry.id} 副本`);
    if (!name) return;
    await save(id, { ...draft, name });
  };

  const create = async () => {
    if (!schema) return;
    const id = window.prompt("新预设 id（字母/数字/._-）", "my-preset");
    if (!id) return;
    const preset: TranscodePreset = { name: "新预设", description: "" };
    for (const section of schema.sections) {
      for (const field of section.fields) {
        preset[field.id] = field.default;
      }
    }
    await save(id, preset);
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
      await loadList();
    } catch (e) {
      toast("error", errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  const previewCommand = async () => {
    if (!inputPath.trim()) {
      setPreviewError("先填一个相对媒体根的输入文件（例如 input.mp4）");
      return;
    }
    setPreviewError(null);
    try {
      const answer = await api.transcode.preview({ preset: draft, input: inputPath.trim() });
      setPreview(answer.command);
    } catch (e) {
      setPreviewError(errMsg(e));
      setPreview("");
    }
  };

  const importFile = async (file: File) => {
    setBusy(true);
    setError(null);
    try {
      const data = JSON.parse(await file.text()) as Record<string, unknown>;
      const id = window.prompt("导入为预设 id（字母/数字/._-）",
        file.name.replace(/\.(3fui|json)$/i, ""));
      if (!id) return;
      const saved = await api.transcode.importPreset(id, data);
      toast("success", "预设已导入");
      await loadList(saved.id);
    } catch (e) {
      setError(errMsg(e));
      toast("error", errMsg(e));
    } finally {
      setBusy(false);
      if (fileInput.current) fileInput.current.value = "";
    }
  };

  const exportFile = async (format: "json" | "3fui") => {
    if (!entry) return;
    try {
      const data = await api.transcode.exportPreset(entry.id, format);
      const blob = new Blob([JSON.stringify(data, null, 2)], { type: "application/json" });
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = `${entry.id}.${format}`;
      link.click();
      URL.revokeObjectURL(url);
    } catch (e) {
      toast("error", errMsg(e));
    }
  };

  const appendFilter = (filter: string) => {
    const current = typeof draft.filterGraph === "string" ? draft.filterGraph.trim() : "";
    update("filterGraph", current ? `${current},${filter}` : filter);
    setFilterDialog(false);
  };

  const update = (id: string, value: unknown) => {
    setDraft((current) => {
      const next = { ...current, [id]: value };
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
          // VAAPI/QSV/NVENC do not understand x264's `slower`/profile/tune in the
          // same way; clearing them avoids the classic compression_level failure.
          next.qualityMode = "cqp";
          next.preset = "";
          next.profile = "";
          next.tune = "";
        } else if (next.qualityMode !== "bitrate") {
          next.qualityMode = "crf";
        }
      }
      if (id === "qualityMode" && value === "bitrate") {
        // A bitrate mode does not use CRF/CQP; keep the field as the user typed.
      }
      return next;
    });
  };

  const warnings: string[] = [];
  if (!draft.videoEncoder) warnings.push("没有选择视频编码器");
  if (!draft.container) warnings.push("没有选择输出容器");
  if (!draft.qualityValue) warnings.push("没有设置质量值/码率");
  if (draft.audioMode === "copy" && draft.subtitleMode === "copy") {
    warnings.push("音频/字幕均为 copy：请确认目标容器装得下全部轨道");
  }

  return (
    <div style={{ display: "flex", gap: 8, alignItems: "flex-start", flexWrap: "wrap" }}>
      <div className="card" style={{ flex: "0 1 220px", minWidth: 200, padding: 0 }}>
        <div style={{ padding: "8px 10px 2px", fontSize: 12, fontWeight: 600 }}>参数集</div>
        <div style={{ display: "flex", gap: 6, padding: "4px 8px 4px", flexWrap: "wrap" }}>
          <button className="btn btn-text" disabled={busy || !schema} onClick={() => void create()}>
            新建
          </button>
          <button className="btn btn-text" disabled={busy} onClick={() => void loadList()}>
            刷新
          </button>
          <button className="btn btn-text" disabled={busy} onClick={() => fileInput.current?.click()}>
            导入
          </button>
          <button className="btn btn-text" disabled={busy || !entry} onClick={() => void exportFile("3fui")}>
            导出 .3fui
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
        </div>
        <div className="list-body scroll-hover" style={{ maxHeight: 520 }}>
          {summaries.map((preset) => (
            <div
              key={preset.id}
              className={`tlli ripple-host${selectedId === preset.id ? " selected" : ""}`}
              style={{
                padding: "7px 10px",
                cursor: "pointer",
                background: selectedId === preset.id ? "var(--monet-secondary-container)" : undefined,
              }}
              onClick={() => void select(preset.id)}
            >
              <span className="tlli-text" style={{ minWidth: 0 }}>
                <span className="tlli-title" style={{ overflow: "hidden", textOverflow: "ellipsis" }}>
                  {preset.name}
                  <span className={`tag ${preset.builtin ? "plain" : "running"}`} style={{ fontSize: 10, marginLeft: 6 }}>
                    {preset.builtin ? "内置" : "自定义"}
                  </span>
                </span>
                <span className="tlli-subtitle" style={{ overflow: "hidden", textOverflow: "ellipsis" }}>
                  {preset.description || preset.id}
                </span>
              </span>
            </div>
          ))}
        </div>
        <div style={{ padding: "8px 10px 2px", fontSize: 12, fontWeight: 600 }}>参数分类</div>
        <div className="list-body scroll-hover" style={{ maxHeight: 280 }}>
          <div
            className={`tlli ripple-host${activeSection === null ? " selected" : ""}`}
            style={{ padding: "6px 10px", cursor: "pointer",
                     background: activeSection === null ? "var(--monet-secondary-container)" : undefined }}
            onClick={() => setActiveSection(null)}
          >
            <span className="tlli-text"><span className="tlli-title">参数总览</span></span>
          </div>
          {schema?.sections.map((section) => (
            <div
              key={section.title}
              className={`tlli ripple-host${activeSection === section.title ? " selected" : ""}`}
              style={{ padding: "6px 10px", cursor: "pointer",
                       background: activeSection === section.title ? "var(--monet-secondary-container)" : undefined }}
              onClick={() => setActiveSection(section.title)}
            >
              <span className="tlli-text"><span className="tlli-title">{section.title}</span></span>
            </div>
          ))}
          <div
            className={`tlli ripple-host${activeSection === "自定义参数" ? " selected" : ""}`}
            style={{ padding: "6px 10px", cursor: "pointer",
                     background: activeSection === "自定义参数" ? "var(--monet-secondary-container)" : undefined }}
            onClick={() => setActiveSection("自定义参数")}
          >
            <span className="tlli-text"><span className="tlli-title">自定义参数</span></span>
          </div>
        </div>
      </div>

      <div style={{ flex: "1 1 560px", minWidth: 0, display: "flex", gap: 8, alignItems: "flex-start", flexWrap: "wrap" }}>
        <div style={{ flex: "1 1 420px", minWidth: 0, display: "grid", gap: 8 }}>
        {error && (
          <div className="card">
            <div className="comp-row">
              <span className="comp-label">错误</span>
              <span className="comp-value" style={{ color: "var(--monet-error)" }}>{error}</span>
            </div>
          </div>
        )}
        {activeSection === null && warnings.length > 0 && (
          <div className="card">
            <div className="comp-row">
              <span className="comp-label">参数总览 · 警告</span>
              <span className="comp-value" style={{ fontSize: 12 }}>{warnings.length} 条</span>
            </div>
            <div style={{ display: "grid", gap: 2, padding: "4px 8px 8px", fontSize: 12 }}>
              {warnings.map((warning, index) => (
                <span key={warning} style={{ color: "var(--monet-error)" }}>
                  {index + 1}. {warning}
                </span>
              ))}
            </div>
          </div>
        )}
        {entry && (
          <div className="card">
            <div className="comp-row">
              <span className="comp-label">参数集</span>
              <span className="comp-value mono" style={{ fontSize: 12 }}>
                {entry.id} {entry.builtin ? "（只读）" : ""}
              </span>
            </div>
            <div style={{ display: "grid", gap: 6, padding: "4px 8px 8px" }}>
              <label style={{ fontSize: 12 }}>名称</label>
              <input
                className="input"
                value={String(draft.name ?? "")}
                disabled={entry.builtin}
                onChange={(e) => update("name", e.target.value)}
              />
              <label style={{ fontSize: 12 }}>描述</label>
              <input
                className="input"
                value={String(draft.description ?? "")}
                disabled={entry.builtin}
                onChange={(e) => update("description", e.target.value)}
              />
              <div style={{ display: "flex", gap: 8, flexWrap: "wrap", paddingTop: 4 }}>
                {entry.builtin ? (
                  <button className="btn btn-raised" disabled={busy} onClick={() => void saveAs()}>
                    另存为
                  </button>
                ) : (
                  <>
                    <button className="btn btn-raised" disabled={busy} onClick={() => void save(entry.id, draft)}>
                      保存
                    </button>
                    <button className="btn btn-text danger" disabled={busy} onClick={() => void remove()}>
                      删除
                    </button>
                  </>
                )}
              </div>
            </div>
          </div>
        )}

        {schema?.sections.filter((section) => activeSection === null || activeSection === section.title).map((section) => (
          <div className="card" key={section.title}>
            <div className="comp-row">
              <span className="comp-label">{section.title}</span>
              {section.title.includes("滤镜") && (
                <span className="comp-value">
                  <button className="btn btn-text" disabled={entry?.builtin === true} onClick={() => setFilterDialog(true)}>
                    滤镜选择
                  </button>
                </span>
              )}
            </div>
            <div style={{ display: "grid", gap: 6, padding: "4px 8px 8px",
                          gridTemplateColumns: "repeat(auto-fit, minmax(190px, 1fr))" }}>
              {section.fields.map((field) => (
                <Field
                  key={field.id}
                  field={field}
                  value={draft[field.id] ?? field.default}
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
        </div>

        <div className="card" style={{ flex: "0 1 340px", minWidth: 280, position: "sticky", top: 8 }}>
          <div className="comp-row">
            <span className="comp-label">命令行预览</span>
            <span className="comp-value" style={{ fontSize: 11, color: "var(--monet-on-surface-variant)" }}>
              只生成，不执行
            </span>
          </div>
          <div style={{ display: "grid", gap: 5, padding: "4px 8px 8px" }}>
            <input className="input" value={inputPath} placeholder="输入文件（相对媒体根）"
                   onChange={(e) => setInputPath(e.target.value)} />
            <button className="btn btn-text" disabled={!entry} onClick={() => void previewCommand()}>
              生成命令
            </button>
            {previewError && (
              <span style={{ fontSize: 11, color: "var(--monet-error)" }}>{previewError}</span>
            )}
            <pre style={{ margin: 0, padding: 8, maxHeight: 360, overflow: "auto", fontSize: 10,
                          lineHeight: 1.5, background: "var(--monet-surface-container-low)",
                          whiteSpace: "pre-wrap", wordBreak: "break-all" }}>
              {preview || "选择参数集、填输入文件后生成"}
            </pre>
          </div>
        </div>
      </div>

      <Dialog open={filterDialog} title="追加视频滤镜" onClose={() => setFilterDialog(false)}>
        <div style={{ display: "grid", gap: 6, maxWidth: 420 }}>
          {FILTERS.map((filter) => (
            <button key={filter.value} className="btn btn-text" style={{ justifyContent: "flex-start" }}
                    onClick={() => appendFilter(filter.value)}>
              {filter.label} <span className="mono" style={{ fontSize: 11, opacity: 0.7 }}>{filter.value}</span>
            </button>
          ))}
        </div>
      </Dialog>
    </div>
  );
}

const FILTERS = [
  { label: "缩放到 1080p", value: "scale=-2:1080" },
  { label: "缩放到 720p", value: "scale=-2:720" },
  { label: "固定帧率 30", value: "fps=30" },
  { label: "去隔行", value: "yadif" },
  { label: "裁掉顶部 10%", value: "crop=iw:ih*0.9:0:0" },
  { label: "水平翻转", value: "hflip" },
  { label: "垂直翻转", value: "vflip" },
  { label: "顺时针旋转 90°", value: "transpose=1" },
  { label: "轻量调色", value: "eq=contrast=1.05:saturation=1.1" },
];

function CustomArgsEditor({
  draft,
  disabled,
  update,
}: {
  draft: TranscodePreset;
  disabled: boolean;
  update: (id: string, value: unknown) => void;
}) {
  const listText = (value: unknown) => Array.isArray(value) ? value.join("\n") : "";
  const setList = (id: string, text: string) =>
    update(id, text.split(/\r?\n/).map((line) => line.trim()).filter(Boolean));
  return (
    <div className="card">
      <div className="comp-row">
        <span className="comp-label">自定义参数</span>
        <span className="comp-value" style={{ fontSize: 11, color: "var(--monet-on-surface-variant)" }}>
          每行一个 argv，不做 shell 解析
        </span>
      </div>
      <div style={{ display: "grid", gap: 6, padding: "4px 8px 8px" }}>
        {[
          ["inputArgs", "输入前参数"],
          ["videoArgs", "视频附加参数"],
          ["audioArgs", "音频附加参数"],
          ["subtitleArgs", "字幕附加参数"],
          ["outputArgs", "输出前参数"],
        ].map(([id, label]) => (
          <label key={id} style={{ display: "grid", gap: 3, fontSize: 12 }}>
            <span>{label}</span>
            <textarea
              className="input"
              rows={2}
              disabled={disabled}
              value={listText(draft[id])}
              onChange={(e) => setList(id, e.target.value)}
            />
          </label>
        ))}
      </div>
    </div>
  );
}

function Field({
  field,
  value,
  disabled,
  onChange,
}: {
  field: TranscodeSchemaField;
  value: unknown;
  disabled: boolean;
  onChange: (value: unknown) => void;
}) {
  if (field.type === "switch") {
    return (
      <label style={{ display: "flex", gap: 8, alignItems: "center", fontSize: 13 }}>
        <input
          type="checkbox"
          checked={value === true}
          disabled={disabled}
          onChange={(e) => onChange(e.target.checked)}
        />
        {field.label}
      </label>
    );
  }
  if (field.type === "textarea") {
    return (
      <label style={{ display: "grid", gap: 3, fontSize: 12 }}>
        <span>{field.label}</span>
        <textarea
          className="input"
          rows={3}
          value={String(value ?? "")}
          disabled={disabled}
          onChange={(e) => onChange(e.target.value)}
        />
      </label>
    );
  }
  return (
    <label style={{ display: "grid", gap: 3, fontSize: 12 }}>
      <span>{field.label}</span>
      {field.type === "select" ? (
        <select
          className="input"
          value={String(value ?? "")}
          disabled={disabled}
          onChange={(e) => onChange(e.target.value)}
        >
          {(field.options ?? []).map((option) => (
            <option key={option} value={option}>
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
    </label>
  );
}