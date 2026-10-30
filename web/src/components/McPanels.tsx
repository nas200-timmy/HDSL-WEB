import { useEffect, useState } from "react";
import { api, type McBackup, type McInstance } from "../api";
import { requestDeveloperPassword, isElevationRequired } from "../elevation";
import { toast } from "../store";
import { errMsg } from "../utils";

/// MC 实例的各块面板。
///
/// 这些以前是弹窗（`terminal-overlay` 里的卡片）。实例有了自己的二级页面之后，
/// 它们就只是页面里的一块：同样的接口、同样的动作，只是不再盖住列表。
/// 破坏性动作仍然在这里逐个确认——面板不是沙箱。

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
export function McBackupsPanel({ instance }: { instance: McInstance }) {
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
  }, [instance.id]);

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

  const running = instance.state === "RUNNING";

  return (
    <div className="card">
      <div className="comp-row">
        <span className="comp-label">世界备份</span>
        <span className="comp-value">
          <button className="btn btn-text" disabled={busy || running} onClick={() => void create()}>
            立即备份
          </button>
        </span>
      </div>
      <p style={{ fontSize: 12, color: "var(--monet-on-surface-variant)", padding: "4px 8px 0", lineHeight: 1.6 }}>
        备份只在停服时拍，避免半个世界的 zip。恢复前会自动再备份当前世界。
        {running && " 先停服。"}
      </p>
      {error && <div style={{ color: "var(--monet-error)", padding: "6px 8px" }}>{error}</div>}
      <div style={{ maxHeight: 380, overflow: "auto", padding: "4px 8px 8px" }}>
        {backups.length === 0 ? (
          <div className="dim" style={{ fontSize: 13 }}>还没有备份。</div>
        ) : backups.map((backup) => (
          <div
            key={backup.name}
            style={{ display: "flex", alignItems: "center", gap: 8, padding: "6px 0", borderTop: "1px solid var(--monet-outline-variant)" }}
          >
            <span style={{ flex: 1, fontSize: 13 }}>{backup.name}</span>
            <span className="dim" style={{ fontSize: 12 }}>{formatBytes(backup.bytes)}</span>
            <button className="btn btn-text" disabled={busy || running} onClick={() => void restore(backup.name)}>
              恢复
            </button>
            <button className="btn btn-text danger" disabled={busy} onClick={() => void remove(backup.name)}>
              删除
            </button>
          </div>
        ))}
      </div>
    </div>
  );
}

/// `server.properties` 的受管字段，按界面上出现的顺序。
/// 左边是文件里的键（不能翻译），右边是给人看的名字与一句解释。
const PROPERTY_FIELDS: { key: string; label: string; hint: string }[] = [
  { key: "motd", label: "服务器标语（MOTD）", hint: "多人列表里显示在名字下面的一行字，可以用 § 颜色代码。" },
  { key: "max-players", label: "最大玩家数", hint: "同时在线的上限。" },
  { key: "online-mode", label: "正版验证", hint: "开启时只允许 Mojang 账号登录；关闭＝离线模式，任何名字都能进，只适合内网或自己人。" },
  { key: "white-list", label: "白名单", hint: "只允许白名单里的玩家进入。" },
  { key: "enforce-whitelist", label: "强制白名单", hint: "白名单变动后立刻踢掉不在名单里的人。" },
  { key: "difficulty", label: "游戏难度", hint: "和平不刷怪、不掉血；困难会饿死。" },
  { key: "gamemode", label: "默认游戏模式", hint: "新玩家第一次进来的模式。" },
  { key: "pvp", label: "玩家互相伤害（PvP）", hint: "关掉后玩家之间打不掉血。" },
  { key: "allow-flight", label: "允许飞行", hint: "关掉时，用飞行类插件或外挂会被服务端踢掉。" },
  { key: "spawn-protection", label: "出生点保护半径", hint: "以方块计，0 表示关闭；只挡没有 OP 的玩家。" },
  { key: "view-distance", label: "视距（区块）", hint: "客户端能看到多远，越大越吃 CPU 与带宽。" },
  { key: "simulation-distance", label: "模拟距离（区块）", hint: "超出这个距离的区块不再刷怪、跑红石。" },
];

const BOOL_LABEL: Record<string, string> = { true: "开启", false: "关闭" };
const DIFFICULTY_LABEL: Record<string, string> = {
  peaceful: "和平",
  easy: "简单",
  normal: "普通",
  hard: "困难",
};
const GAMEMODE_LABEL: Record<string, string> = {
  survival: "生存",
  creative: "创造",
  adventure: "冒险",
  spectator: "旁观",
};

const BOOL_FIELDS = new Set(["online-mode", "white-list", "enforce-whitelist", "pvp", "allow-flight"]);
const NUMBER_FIELDS = new Set(["max-players", "spawn-protection", "view-distance", "simulation-distance"]);
const EDITABLE = PROPERTY_FIELDS.map((field) => field.key);

/** `server.properties` 的受管字段；未知键和注释留在文件里，但这里不编辑。 */
export function McPropertiesPanel({ instance }: { instance: McInstance }) {
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
    } catch (e) {
      toast("error", errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  const set = (key: string, value: string) => setValues({ ...values, [key]: value });

  return (
    <div className="card">
      <div className="comp-row">
        <span className="comp-label">服务端设置</span>
        <span className="comp-value mono" style={{ fontSize: 11, color: "var(--monet-on-surface-variant)" }}>
          server.properties
        </span>
      </div>
      <p style={{ fontSize: 12, color: "var(--monet-on-surface-variant)", padding: "4px 8px 0", lineHeight: 1.6 }}>
        只列面板管得住的这 {EDITABLE.length} 项；文件里的注释与其他键原样保留。改动下次启动生效。
      </p>
      {error && <div style={{ color: "var(--monet-error)", padding: "6px 8px" }}>{error}</div>}
      <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(240px, 1fr))", gap: 10, padding: "10px 8px" }}>
        {PROPERTY_FIELDS.map((field) => (
          <label key={field.key} style={{ display: "flex", flexDirection: "column", gap: 4, fontSize: 12 }}>
            <span>{field.label}</span>
            {BOOL_FIELDS.has(field.key) ? (
              <select className="input" value={values[field.key] ?? "false"} onChange={(e) => set(field.key, e.target.value)}>
                <option value="true">{BOOL_LABEL.true}</option>
                <option value="false">{BOOL_LABEL.false}</option>
              </select>
            ) : field.key === "difficulty" ? (
              <select className="input" value={values[field.key] ?? "normal"} onChange={(e) => set(field.key, e.target.value)}>
                {Object.entries(DIFFICULTY_LABEL).map(([value, label]) => (
                  <option key={value} value={value}>{label}</option>
                ))}
              </select>
            ) : field.key === "gamemode" ? (
              <select className="input" value={values[field.key] ?? "survival"} onChange={(e) => set(field.key, e.target.value)}>
                {Object.entries(GAMEMODE_LABEL).map(([value, label]) => (
                  <option key={value} value={value}>{label}</option>
                ))}
              </select>
            ) : (
              <input
                className="input"
                type={NUMBER_FIELDS.has(field.key) ? "number" : "text"}
                value={values[field.key] ?? ""}
                onChange={(e) => set(field.key, e.target.value)}
              />
            )}
            <span style={{ color: "var(--monet-on-surface-variant)", lineHeight: 1.5 }}>{field.hint}</span>
          </label>
        ))}
      </div>
      <div style={{ display: "flex", gap: 8, justifyContent: "flex-end", padding: "0 8px 8px" }}>
        <button className="btn btn-raised" disabled={busy} onClick={() => void save()}>保存设置</button>
      </div>
    </div>
  );
}

/** 端口与内存：清单里的三项，启动时读取，所以只能在停服时改。
 *
 *  端口不是「面板映射」——服务端直接把 `server-port` 绑在宿主/容器网卡上；
 *  改这里会同时写 `instance.json` 与 `server.properties`，两边不会各说各话。 */
export function McNetworkPanel({
  instance,
  onChange,
}: {
  instance: McInstance;
  onChange: (next: McInstance) => void;
}) {
  const [port, setPort] = useState(instance.port);
  const [rconPort, setRconPort] = useState(instance.rconPort);
  const [memoryMb, setMemoryMb] = useState(instance.memoryMb);
  const [busy, setBusy] = useState(false);
  const running = instance.state === "RUNNING" || instance.state === "STARTING" || instance.state === "STOPPING";

  useEffect(() => {
    setPort(instance.port);
    setRconPort(instance.rconPort);
    setMemoryMb(instance.memoryMb);
  }, [instance.port, instance.rconPort, instance.memoryMb]);

  const dirty = port !== instance.port || rconPort !== instance.rconPort || memoryMb !== instance.memoryMb;

  const save = async () => {
    setBusy(true);
    try {
      const next = await elevated(() =>
        api.mc.update(instance.id, { port, rconPort, memoryMb }),
      );
      onChange(next);
      toast("success", "已保存；下次启动生效");
    } catch (e) {
      toast("error", errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="card">
      <div className="comp-row">
        <span className="comp-label">端口与内存</span>
        <span className="comp-value mono" style={{ fontSize: 11, color: "var(--monet-on-surface-variant)" }}>
          instance.json · server.properties
        </span>
      </div>
      <p style={{ fontSize: 12, color: "var(--monet-on-surface-variant)", padding: "4px 8px 0", lineHeight: 1.6 }}>
        客户端连的是游戏端口；RCON 端口给面板用（不能与游戏端口相同）。改端口要停服，
        并记得在防火墙放行新端口。
      </p>
      <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(180px, 1fr))", gap: 10, padding: "10px 8px" }}>
        <label style={{ display: "flex", flexDirection: "column", gap: 4, fontSize: 12 }}>
          <span>游戏端口</span>
          <input
            className="input"
            type="number"
            min={1024}
            max={65535}
            value={port}
            disabled={running || busy}
            onChange={(e) => setPort(Number(e.target.value) || 0)}
          />
        </label>
        <label style={{ display: "flex", flexDirection: "column", gap: 4, fontSize: 12 }}>
          <span>RCON 端口</span>
          <input
            className="input"
            type="number"
            min={1024}
            max={65535}
            value={rconPort}
            disabled={running || busy}
            onChange={(e) => setRconPort(Number(e.target.value) || 0)}
          />
        </label>
        <label style={{ display: "flex", flexDirection: "column", gap: 4, fontSize: 12 }}>
          <span>内存（MB）</span>
          <input
            className="input"
            type="number"
            min={256}
            step={256}
            value={memoryMb}
            disabled={running || busy}
            onChange={(e) => setMemoryMb(Number(e.target.value) || 0)}
          />
        </label>
      </div>
      {instance.dir && (
        <div style={{ padding: "0 8px 4px", fontSize: 11, color: "var(--monet-on-surface-variant)", wordBreak: "break-all" }}>
          服务器目录：<span className="mono">{instance.dir}</span>
          {instance.storage === "external" ? "（专用目录，独立挂载）" : "（面板数据目录）"}
        </div>
      )}
      <div style={{ display: "flex", gap: 8, justifyContent: "flex-end", padding: "0 8px 8px" }}>
        {running && (
          <span style={{ fontSize: 12, color: "var(--monet-on-surface-variant)", alignSelf: "center" }}>
            停服后可改
          </span>
        )}
        <button className="btn btn-raised" disabled={busy || running || !dirty} onClick={() => void save()}>
          保存
        </button>
      </div>
    </div>
  );
}

/** 服务端输出：`logs/latest.log` 的尾巴，2 秒一跳。 */
function LogTail({ instance, height }: { instance: McInstance; height: number }) {
  const [lines, setLines] = useState<string[]>([]);

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

  return (
    <pre style={{ height, overflow: "auto", fontSize: 12, margin: 0, whiteSpace: "pre-wrap", padding: 8 }}>
      {lines.length === 0 ? "（等待服务端输出…）" : lines.join("\n")}
    </pre>
  );
}

/// 控制台的两种形态：Linux 是 PTY（xterm 窗口），Windows 没有 PTY，只有
/// 「日志 + 一行命令」。两种都能看到输出，区别只在于命令怎么送进去。
export function McConsolePanel({
  instance,
  onOpenTerminal,
}: {
  instance: McInstance;
  onOpenTerminal: () => void;
}) {
  const [pipe, setPipe] = useState(false);
  const [line, setLine] = useState("");
  const [busy, setBusy] = useState(false);

  const open = async () => {
    setBusy(true);
    try {
      const answer = await elevated(() => api.mc.consoleTicket(instance.id));
      if (answer.mode === "pipe") setPipe(true);
      else onOpenTerminal();
    } catch (e) {
      toast("error", errMsg(e));
    } finally {
      setBusy(false);
    }
  };

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
    <div className="card" style={{ padding: 0, overflow: "hidden" }}>
      <div className="comp-row" style={{ padding: "8px 8px 0" }}>
        <span className="comp-label">控制台</span>
        <span className="comp-value">
          <button className="btn btn-text" disabled={busy || !instance.consoleAvailable} onClick={() => void open()}>
            {pipe ? "重新连接" : "打开交互终端"}
          </button>
        </span>
      </div>
      <p style={{ fontSize: 12, color: "var(--monet-on-surface-variant)", padding: "4px 8px", lineHeight: 1.6 }}>
        {instance.consoleAvailable
          ? pipe
            ? "这台服务端没有 PTY：命令从下面的输入框送进去，输出仍然在这里。"
            : "交互终端走 Web Shell（PTY）；这一块只是输出预览。"
          : "服务端起来之后才有控制台。"}
      </p>
      <LogTail instance={instance} height={420} />
      {pipe && (
        <div style={{ display: "flex", gap: 6, padding: 8, borderTop: "1px solid var(--monet-outline-variant)" }}>          <input
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
      )}
    </div>
  );
}

/** 日志页：同一份 latest.log，给一个更高更宽的位置慢慢看。 */
export function McLogsPanel({ instance }: { instance: McInstance }) {
  return (
    <div className="card" style={{ padding: 0, overflow: "hidden" }}>
      <div className="comp-row" style={{ padding: "8px 8px 0" }}>
        <span className="comp-label">日志</span>
        <span className="comp-value mono" style={{ fontSize: 11, color: "var(--monet-on-surface-variant)" }}>
          logs/latest.log
        </span>
      </div>
      <LogTail instance={instance} height={560} />
    </div>
  );
}

/** RCON 玩家列表与四个管理动作。 */
export function McPlayersPanel({ instance }: { instance: McInstance }) {
  const [online, setOnline] = useState(0);
  const [max, setMax] = useState(0);
  const [names, setNames] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [listsNote, setListsNote] = useState<string | null>(null);

  const importLists = async (file: File) => {
    setBusy(true);
    setListsNote(null);
    try {
      const answer = await elevated(() => api.mc.importPlayerLists(instance.id, file));
      const parts = Object.entries(answer.files).map(([name, count]) => `${name} ${count} 条`);
      setListsNote(`已导入：${parts.join("、")}`);
      toast("success", "玩家名单已导入；重启服务器后生效");
      await load();
    } catch (e) {
      toast("error", errMsg(e));
    } finally {
      setBusy(false);
    }
  };

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
    <div className="card">
      <div className="comp-row">
        <span className="comp-label">在线玩家</span>
        <span className="comp-value">
          <span className="dim">{online} / {max}</span>
          <button className="btn btn-text" onClick={() => void load()}>刷新</button>
        </span>
      </div>
      {instance.state !== "RUNNING" && (
        <p style={{ fontSize: 12, color: "var(--monet-on-surface-variant)", padding: "4px 8px 0" }}>
          服务端没在运行，这里读不到玩家。
        </p>
      )}
      {error && <div style={{ color: "var(--monet-error)", padding: "6px 8px", fontSize: 13 }}>{error}</div>}
      <div style={{ maxHeight: 380, overflow: "auto", padding: "4px 8px 8px" }}>
        {names.length === 0 ? (
          <div className="dim" style={{ fontSize: 13 }}>没有在线玩家。</div>
        ) : names.map((name) => (
          <div key={name} style={{ display: "flex", gap: 6, alignItems: "center", padding: "4px 0" }}>
            <span style={{ flex: 1 }}>{name}</span>
            <button className="btn btn-text" disabled={busy} onClick={() => void act(name, "kick")}>踢出</button>
            <button className="btn btn-text" disabled={busy} onClick={() => void act(name, "op")}>给 OP</button>
            <button className="btn btn-text" disabled={busy} onClick={() => void act(name, "deop")}>收回 OP</button>
            <button className="btn btn-text danger" disabled={busy} onClick={() => void act(name, "ban")}>封禁</button>
          </div>
        ))}
      </div>
      <div style={{ display: "flex", gap: 8, alignItems: "center", padding: "4px 8px 0", flexWrap: "wrap" }}>
        <label
          className={`btn btn-text${instance.state === "RUNNING" ? " disabled" : ""}`}
          style={{ cursor: instance.state === "RUNNING" ? "not-allowed" : "pointer" }}
        >
          导入名单 zip…
          <input
            type="file"
            accept=".zip"
            style={{ display: "none" }}
            disabled={busy || instance.state === "RUNNING"}
            onChange={(e) => {
              const file = e.target.files?.[0];
              if (file) void importLists(file);
              e.target.value = "";
            }}
          />
        </label>
        <span className="dim" style={{ fontSize: 12 }}>
          whitelist.json / ops.json / banned-*.json，放在 zip 根上；停服才能导入
        </span>
      </div>
      {listsNote && <div style={{ padding: "4px 8px 0", fontSize: 12 }}>{listsNote}</div>}
      <p className="dim" style={{ fontSize: 12, margin: 0, padding: "0 8px 8px" }}>
        需要 server.properties 里开启 RCON（<code>enable-rcon=true</code>）；默认是关的。
      </p>
    </div>
  );
}

/** 世界导入/导出（zip）。两者都要求先停服。 */
export function McWorldPanel({ instance }: { instance: McInstance }) {
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

  const running = instance.state === "RUNNING";

  return (
    <div className="card">
      <div className="comp-row">
        <span className="comp-label">世界</span>
        <span className="comp-value mono" style={{ fontSize: 11, color: "var(--monet-on-surface-variant)" }}>
          {instance.levelName}
        </span>
      </div>
      {running ? (
        <p style={{ color: "var(--monet-error)", padding: "6px 8px", fontSize: 13 }}>先停服，再导入或导出世界。</p>
      ) : (
        <div style={{ display: "flex", flexDirection: "column", gap: 10, padding: "10px 8px" }}>
          <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
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
          <p className="dim" style={{ fontSize: 12, margin: 0, lineHeight: 1.6 }}>
            导入会先备份当前世界，再整体替换；不会合并两个世界。
            zip 里的目录可以叫 <span className="mono">{instance.levelName}/…</span>，也可以直接是世界内容
            （平铺或 <span className="mono">region/…</span> 这种层级）；上限 2 GB。
            <b>顶层目录名和上面的世界目录名不一致会被拒绝</b>——否则服务器找不到
            <span className="mono">level.dat</span>，会当成新世界。改世界目录名或重新打包即可。
            <br />
            这里<b>只搬存档</b>：服务端程序、插件/模组、白名单与 OP 名单都不在 zip 里。
            旧服要带过来时，版本选成和旧服一致（服务端程序走官方下载），
            名单用「玩家」页的 op/deop 或控制台 <span className="mono">whitelist add</span> 重建。
          </p>
        </div>
      )}
      {note && <div style={{ padding: "0 8px 8px", fontSize: 13 }}>{note}</div>}
      {error && <div style={{ color: "var(--monet-error)", padding: "0 8px 8px", fontSize: 13 }}>{error}</div>}
    </div>
  );
}
