import { useEffect, useState } from "react";
import { api, type McBackup, type McInstance } from "../api";
import { requestDeveloperPassword, isElevationRequired } from "../elevation";
import { toast } from "../store";
import { errMsg } from "../utils";

/** 提权失败（需要开发者密码）时就地弹框并重试一次。 */
async function elevated<T>(call: () => Promise<T>): Promise<T> {
  try {
    return await call();
  } catch (e) {
    if (!isElevationRequired(e)) throw e;
    if (!(await requestDeveloperPassword())) throw e;
    return await call();
  }
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/** 备份：停机快照、列表、恢复（恢复前自动再拍一张）、删除。 */
export function McBackupsDialog({ instance, onClose }: { instance: McInstance; onClose: () => void }) {
  const [backups, setBackups] = useState<McBackup[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = async () => {
    try {
      setBackups((await api.mc.backups(instance.id)).backups);
      setError(null);
    } catch (e) {
      setError(errMsg(e));
    }
  };

  useEffect(() => {
    void load();
  }, []);

  const create = async () => {
    setBusy(true);
    try {
      const backup = await elevated(() => api.mc.createBackup(instance.id));
      toast("success", `已备份：${backup.name}`);
      await load();
    } catch (e) {
      toast("error", errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  const restore = async (name: string) => {
    if (!window.confirm(`恢复 ${name}？当前世界会先自动备份一份。`)) return;
    setBusy(true);
    try {
      const answer = await elevated(() => api.mc.restoreBackup(instance.id, name));
      toast("success", `已恢复；恢复前的世界保存在 ${answer.safety}`);
      await load();
    } catch (e) {
      toast("error", errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  const remove = async (name: string) => {
    if (!window.confirm(`删除备份 ${name}？`)) return;
    setBusy(true);
    try {
      await elevated(() => api.mc.deleteBackup(instance.id, name));
      await load();
    } catch (e) {
      toast("error", errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="terminal-overlay" role="dialog" aria-label="备份">
      <div className="terminal-card card" style={{ maxWidth: 620, width: "92%" }}>
        <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
          <b>{instance.name} · 备份</b>
          <span style={{ flex: 1 }} />
          <button className="btn btn-text" onClick={onClose}>关闭</button>
        </div>
        <p className="dim" style={{ margin: "6px 0" }}>
          备份只在停服时拍，避免半个世界的 zip。恢复前会自动再备份当前世界。
        </p>
        {error && <div style={{ color: "var(--monet-error)" }}>{error}</div>}
        <div style={{ display: "flex", gap: 8, margin: "8px 0" }}>
          <button className="btn btn-raised" disabled={busy || instance.state === "RUNNING"} onClick={() => void create()}>
            立即备份
          </button>
          <span className="dim" style={{ alignSelf: "center", fontSize: 12 }}>
            {instance.state === "RUNNING" ? "先停服" : ""}
          </span>
        </div>
        <div style={{ maxHeight: 320, overflow: "auto" }}>
          {backups.length === 0 ? (
            <div className="dim">还没有备份。</div>
          ) : backups.map((backup) => (
            <div key={backup.name} style={{ display: "flex", alignItems: "center", gap: 8, padding: "6px 0", borderTop: "1px solid var(--monet-outline-variant)" }}>
              <span style={{ flex: 1, fontSize: 13 }}>{backup.name}</span>
              <span className="dim" style={{ fontSize: 12 }}>{formatBytes(backup.bytes)}</span>
              <button className="btn btn-text" disabled={busy || instance.state === "RUNNING"} onClick={() => void restore(backup.name)}>
                恢复
              </button>
              <button className="btn btn-text danger" disabled={busy} onClick={() => void remove(backup.name)}>
                删除
              </button>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

const BOOL_FIELDS = new Set(["online-mode", "white-list", "enforce-whitelist", "pvp", "allow-flight"]);
const NUMBER_FIELDS = new Set(["max-players", "spawn-protection", "view-distance", "simulation-distance"]);
const EDITABLE = [
  "motd", "max-players", "online-mode", "white-list", "enforce-whitelist",
  "difficulty", "gamemode", "pvp", "allow-flight", "spawn-protection",
  "view-distance", "simulation-distance",
];

/** server.properties 的受管字段；未知键和注释留在文件里，但这里不编辑。 */
export function McPropertiesDialog({ instance, onClose }: { instance: McInstance; onClose: () => void }) {
  const [values, setValues] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void api.mc.properties(instance.id)
      .then((answer) => setValues(answer.properties))
      .catch((e) => setError(errMsg(e)));
  }, [instance.id]);

  const save = async () => {
    const body: Record<string, string> = {};
    for (const key of EDITABLE) {
      if (values[key] !== undefined) body[key] = String(values[key]);
    }
    setBusy(true);
    try {
      await elevated(() => api.mc.saveProperties(instance.id, body));
      toast("success", "已保存；下次启动生效");
      onClose();
    } catch (e) {
      toast("error", errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="terminal-overlay" role="dialog" aria-label="设置">
      <div className="terminal-card card" style={{ maxWidth: 560, width: "92%" }}>
        <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
          <b>{instance.name} · server.properties</b>
          <span style={{ flex: 1 }} />
          <button className="btn btn-text" onClick={onClose}>关闭</button>
        </div>
        {error && <div style={{ color: "var(--monet-error)" }}>{error}</div>}
        <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 8, margin: "10px 0", maxHeight: 340, overflow: "auto" }}>
          {EDITABLE.map((key) => (
            <label key={key} style={{ display: "flex", flexDirection: "column", gap: 4, fontSize: 12 }}>
              <span className="dim">{key}</span>
              {BOOL_FIELDS.has(key) ? (
                <select className="input" value={values[key] ?? "false"} onChange={(e) => setValues({ ...values, [key]: e.target.value })}>
                  <option value="true">true</option>
                  <option value="false">false</option>
                </select>
              ) : (
                <input
                  className="input"
                  type={NUMBER_FIELDS.has(key) ? "number" : "text"}
                  value={values[key] ?? ""}
                  onChange={(e) => setValues({ ...values, [key]: e.target.value })}
                />
              )}
            </label>
          ))}
        </div>
        <div style={{ display: "flex", gap: 8, justifyContent: "flex-end" }}>
          <button className="btn btn-text" onClick={onClose}>取消</button>
          <button className="btn btn-raised" disabled={busy} onClick={() => void save()}>保存</button>
        </div>
      </div>
    </div>
  );
}