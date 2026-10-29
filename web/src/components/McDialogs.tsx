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
/** Windows 没有 PTY：控制台 = 日志 + 一行命令。 */
export function McPipeConsole({ instance, onClose }: { instance: McInstance; onClose: () => void }) {
  const [lines, setLines] = useState<string[]>([]);
  const [line, setLine] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let alive = true;
    const load = async () => {
      try {
        const answer = await api.mc.logs(instance.id);
        if (alive) setLines(answer.lines);
      } catch {
        // 未就绪/没日志：下次轮询再说
      }
    };
    void load();
    const timer = window.setInterval(() => void load(), 2000);
    return () => {
      alive = false;
      window.clearInterval(timer);
    };
  }, [instance.id]);

  const send = async () => {
    const command = line.trim();
    if (!command) return;
    setBusy(true);
    try {
      await elevated(() => api.mc.consoleInput(instance.id, command));
      setLine("");
    } catch (e) {
      toast("error", errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="terminal-overlay" role="dialog" aria-label="控制台">
      <div className="terminal-card card" style={{ maxWidth: 760, width: "92%" }}>
        <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
          <b>{instance.name} · 控制台（Windows：日志 + 命令）</b>
          <span style={{ flex: 1 }} />
          <button className="btn btn-text" onClick={onClose}>关闭</button>
        </div>
        <pre style={{ height: 340, overflow: "auto", fontSize: 12, margin: "8px 0", whiteSpace: "pre-wrap" }}>
          {lines.length === 0 ? "（等待服务端输出…）" : lines.join("\n")}
        </pre>
        <div style={{ display: "flex", gap: 6 }}>
          <input
            className="input"
            value={line}
            placeholder="输入服务器命令，如 say hi / list / stop"
            onChange={(e) => setLine(e.target.value)}
            onKeyDown={(e) => { if (e.key === "Enter") void send(); }}
            style={{ flex: 1 }}
          />
          <button className="btn btn-raised" disabled={busy || !line.trim()} onClick={() => void send()}>
            发送
          </button>
        </div>
      </div>
    </div>
  );
}

/** RCON 玩家列表与四个管理动作。 */
export function McPlayersDialog({ instance, onClose }: { instance: McInstance; onClose: () => void }) {
  const [online, setOnline] = useState(0);
  const [max, setMax] = useState(0);
  const [names, setNames] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = async () => {
    try {
      const answer = await api.mc.players(instance.id);
      setOnline(answer.online);
      setMax(answer.max);
      setNames(answer.names);
      setError(null);
    } catch (e) {
      setError(errMsg(e));
    }
  };

  useEffect(() => {
    void load();
  }, [instance.id]);

  const act = async (name: string, action: "kick" | "op" | "deop" | "ban") => {
    setBusy(true);
    try {
      const answer = await elevated(() => api.mc.playerAction(instance.id, name, action));
      toast("success", answer.answer || "已执行");
      await load();
    } catch (e) {
      toast("error", errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="terminal-overlay" role="dialog" aria-label="玩家">
      <div className="terminal-card card" style={{ maxWidth: 560, width: "92%" }}>
        <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
          <b>{instance.name} · 玩家</b>
          <span className="dim">{online} / {max}</span>
          <span style={{ flex: 1 }} />
          <button className="btn btn-text" onClick={() => void load()}>刷新</button>
          <button className="btn btn-text" onClick={onClose}>关闭</button>
        </div>
        {error && <div style={{ color: "var(--monet-error)", marginTop: 6 }}>{error}</div>}
        <div style={{ maxHeight: 300, overflow: "auto", marginTop: 8 }}>
          {names.length === 0 ? (
            <div className="dim">没有在线玩家。</div>
          ) : names.map((name) => (
            <div key={name} style={{ display: "flex", gap: 6, alignItems: "center", padding: "4px 0" }}>
              <span style={{ flex: 1 }}>{name}</span>
              <button className="btn btn-text" disabled={busy} onClick={() => void act(name, "kick")}>踢出</button>
              <button className="btn btn-text" disabled={busy} onClick={() => void act(name, "op")}>OP</button>
              <button className="btn btn-text" disabled={busy} onClick={() => void act(name, "deop")}>取消 OP</button>
              <button className="btn btn-text danger" disabled={busy} onClick={() => void act(name, "ban")}>封禁</button>
            </div>
          ))}
        </div>
        <p className="dim" style={{ fontSize: 12, margin: "6px 0 0" }}>
          需要 server.properties 里 <code>enable-rcon=true</code>；默认是关的。
        </p>
      </div>
    </div>
  );
}

/** 世界导入/导出（zip）。两者都要求先停服。 */
export function McWorldDialog({ instance, onClose }: { instance: McInstance; onClose: () => void }) {
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const download = async () => {
    setBusy(true);
    setError(null);
    try {
      const blob = await elevated(() => api.mc.exportWorld(instance.id));
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = `${instance.id}-${instance.levelName}.zip`;
      link.click();
      URL.revokeObjectURL(url);
      setNote("已导出世界 zip");
    } catch (e) {
      setError(errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  const upload = async (file: File) => {
    if (!window.confirm(`用 ${file.name} 覆盖当前世界？当前世界会先自动备份。`)) return;
    setBusy(true);
    setError(null);
    try {
      const answer = await elevated(() => api.mc.importWorld(instance.id, file));
      setNote(`已导入；导入前的世界备份：${answer.safety || "（原本没有世界）"}`);
    } catch (e) {
      setError(errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="terminal-overlay" role="dialog" aria-label="世界">
      <div className="terminal-card card" style={{ maxWidth: 520, width: "92%" }}>
        <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
          <b>{instance.name} · 世界</b>
          <span style={{ flex: 1 }} />
          <button className="btn btn-text" onClick={onClose}>关闭</button>
        </div>
        {instance.state === "RUNNING" ? (
          <p style={{ color: "var(--monet-error)" }}>先停服，再导入或导出世界。</p>
        ) : (
          <div style={{ display: "flex", flexDirection: "column", gap: 10, marginTop: 8 }}>
            <div style={{ display: "flex", gap: 8 }}>
              <button className="btn btn-raised" disabled={busy} onClick={() => void download()}>
                导出世界（zip）
              </button>
              <label className="btn btn-raised" style={{ cursor: "pointer" }}>
                导入世界…
                <input
                  type="file"
                  accept=".zip"
                  style={{ display: "none" }}
                  disabled={busy}
                  onChange={(e) => {
                    const file = e.target.files?.[0];
                    if (file) void upload(file);
                    e.target.value = "";
                  }}
                />
              </label>
            </div>
            <p className="dim" style={{ fontSize: 12, margin: 0 }}>
              导入会先备份当前世界，再整体替换；不会合并两个世界。
            </p>
          </div>
        )}
        {note && <div style={{ marginTop: 6 }}>{note}</div>}
        {error && <div style={{ color: "var(--monet-error)", marginTop: 6 }}>{error}</div>}
      </div>
    </div>
  );
}
