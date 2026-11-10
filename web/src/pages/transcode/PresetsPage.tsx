import { useEffect, useState } from "react";
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

export function PresetsPage() {
  const [summaries, setSummaries] = useState<TranscodePresetSummary[]>([]);
  const [schema, setSchema] = useState<TranscodeSchema | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [entry, setEntry] = useState<TranscodePresetEntry | null>(null);
  const [draft, setDraft] = useState<TranscodePreset>({});
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

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

  const update = (id: string, value: unknown) => {
    setDraft((current) => ({ ...current, [id]: value }));
  };

  return (
    <div style={{ display: "flex", gap: 8, alignItems: "flex-start", flexWrap: "wrap" }}>
      <div className="card" style={{ flex: "0 1 240px", minWidth: 220, padding: 0 }}>
        <div style={{ display: "flex", gap: 6, padding: "8px 8px 4px", flexWrap: "wrap" }}>
          <button className="btn btn-text" disabled={busy || !schema} onClick={() => void create()}>
            新建
          </button>
          <button className="btn btn-text" disabled={busy} onClick={() => void loadList()}>
            刷新
          </button>
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
      </div>

      <div style={{ flex: "1 1 480px", minWidth: 0, display: "grid", gap: 8 }}>
        {error && (
          <div className="card">
            <div className="comp-row">
              <span className="comp-label">错误</span>
              <span className="comp-value" style={{ color: "var(--monet-error)" }}>{error}</span>
            </div>
          </div>
        )}
        {entry && (
          <div className="card">
            <div className="comp-row">
              <span className="comp-label">预设</span>
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

        {schema?.sections.map((section) => (
          <div className="card" key={section.title}>
            <div className="comp-row">
              <span className="comp-label">{section.title}</span>
            </div>
            <div style={{ display: "grid", gap: 6, padding: "4px 8px 8px" }}>
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