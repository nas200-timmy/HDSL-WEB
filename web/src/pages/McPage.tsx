import { useEffect, useMemo, useState } from "react";
import { api, ApiError, type McEula, type McFeature, type McInstance, type McTerms, type McVersion } from "../api";
import { TerminalWindow, type TerminalTargetInfo } from "../components/TerminalWindow";
import { AddIcon, DeleteIcon, RefreshIcon, RocketLaunchIcon, TerminalIcon } from "../components/icons";
import { requestDeveloperPassword, isElevationRequired } from "../elevation";
import { toast } from "../store";
import { errMsg } from "../utils";

/**
 * MC 服务端（docs/minecraft.md）。
 *
 * 功能默认关闭：关闭时只显示一张「开启」卡片，开启要走 EULA 阅读器 + 双勾选。
 * 开启后是实例列表：建服、启停、控制台、装 server.jar。
 *
 * 一小时的锁在这里只是一个倒计时横幅 —— 真正的判断在服务端，这里只负责让
 * 用户看懂自己现在能做什么。
 */
export function McPage() {
  const [feature, setFeature] = useState<McFeature | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [eula, setEula] = useState<McEula | null>(null);
  const [terms, setTerms] = useState<McTerms | null>(null);
  const [eulaError, setEulaError] = useState<string | null>(null);
  const [wizard, setWizard] = useState(false);
  const [scrolledToEnd, setScrolledToEnd] = useState(false);
  const [eulaAccepted, setEulaAccepted] = useState(false);
  const [termsAccepted, setTermsAccepted] = useState(false);
  const [busy, setBusy] = useState(false);
  const [instances, setInstances] = useState<McInstance[]>([]);
  const [versions, setVersions] = useState<McVersion[]>([]);
  const [terminalTarget, setTerminalTarget] = useState<TerminalTargetInfo | null>(null);
  const [logs, setLogs] = useState<{ name: string; lines: string[] } | null>(null);
  const [tick, setTick] = useState(0);

  const load = async () => {
    try {
      const next = await api.mc.feature();
      setFeature(next);
      setLoadError(null);
      if (next.enabled) {
        const [list, catalog] = await Promise.all([api.mc.instances(), api.mc.versions()]);
        setInstances(list.instances);
        setVersions(catalog.versions);
      }
    } catch (e) {
      if (e instanceof ApiError && e.status === 404) {
        setFeature(null);
        setLoadError(null);
        return;
      }
      setLoadError(errMsg(e));
    }
  };

  useEffect(() => {
    void load();
  }, []);

  useEffect(() => {
    if (!feature?.locked) return;
    const timer = window.setInterval(() => setTick((value) => value + 1), 1000);
    return () => window.clearInterval(timer);
  }, [feature?.locked]);

  const remaining = useMemo(() => {
    if (!feature?.locked) return 0;
    // tick 只是心跳；剩余秒数以服务端回答为锚，本地按墙钟往前推。
    return Math.max(0, feature.lockRemainingSeconds - tick);
  }, [feature, tick]);

  const openWizard = async () => {
    setWizard(true);
    setEula(null);
    setTerms(null);
    setEulaError(null);
    setScrolledToEnd(false);
    setEulaAccepted(false);
    setTermsAccepted(false);
    try {
      const [eulaAnswer, termsAnswer] = await Promise.all([api.mc.eula(), api.mc.terms()]);
      setEula(eulaAnswer);
      setTerms(termsAnswer);
    } catch (e) {
      setEulaError(errMsg(e));
    }
  };

  const withElevation = async <T,>(call: () => Promise<T>): Promise<T> => {
    try {
      return await call();
    } catch (e) {
      if (!isElevationRequired(e)) throw e;
      if (!(await requestDeveloperPassword())) throw e;
      return await call();
    }
  };

  const enable = async () => {
    if (!eula || !terms) return;
    setBusy(true);
    try {
      const next = await withElevation(() =>
        api.mc.enable({
          eulaAcknowledged: eulaAccepted,
          termsAcknowledged: termsAccepted,
          eulaHash: eula.hash,
          termsVersion: terms.version,
          termsTextHash: terms.hash,
          sawDeclineOption: true,
          uiVersion: "1.0.0",
        }),
      );
      setFeature(next);
      setWizard(false);
      toast("success", "MC 服务端功能已开启");
      await load();
    } catch (e) {
      toast("error", errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  const stopFeature = async () => {
    const phrase = window.prompt("这会停掉所有 MC 服务器，并在 1 小时内不能重新开启。\n输入 STOP 继续：");
    if (phrase !== "STOP") return;
    setBusy(true);
    try {
      setFeature(await withElevation(() => api.mc.stopFeature()));
      setTick(0);
      await load();
    } catch (e) {
      toast("error", errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  const confirmClose = async () => {
    setBusy(true);
    try {
      setFeature(await withElevation(() => api.mc.confirmClose()));
      await load();
    } catch (e) {
      toast("error", errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  const keepFeature = async () => {
    setBusy(true);
    try {
      setFeature(await withElevation(() => api.mc.cancelClose()));
      await load();
    } catch (e) {
      toast("error", errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  const start = async (instance: McInstance) => {
    setBusy(true);
    try {
      await api.mc.start(instance.id);
      toast("info", `正在启动「${instance.name}」`);
      await refreshInstances();
    } catch (e) {
      toast("error", errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  const stop = async (instance: McInstance) => {
    setBusy(true);
    try {
      await withElevation(() => api.mc.stop(instance.id));
      await refreshInstances();
    } catch (e) {
      toast("error", errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  const kill = async (instance: McInstance) => {
    if (!window.confirm(`强杀「${instance.name}」？可能丢存档。`)) return;
    setBusy(true);
    try {
      await withElevation(() => api.mc.kill(instance.id));
      await refreshInstances();
    } catch (e) {
      toast("error", errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  const remove = async (instance: McInstance) => {
    if (!window.confirm(`删除「${instance.name}」？世界目录会一起删掉，先备份。`)) return;
    setBusy(true);
    try {
      await withElevation(() => api.mc.remove(instance.id));
      await refreshInstances();
    } catch (e) {
      toast("error", errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  const install = async (instance: McInstance, version: string) => {
    setBusy(true);
    try {
      await withElevation(() => api.mc.install(instance.id, version));
      toast("success", `已安装 ${version}`);
      await refreshInstances();
    } catch (e) {
      toast("error", errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  const refreshInstances = async () => {
    const list = await api.mc.instances();
    setInstances(list.instances);
  };

  const showLogs = async (instance: McInstance) => {
    try {
      const answer = await api.mc.logs(instance.id);
      setLogs({ name: instance.name, lines: answer.lines });
    } catch (e) {
      toast("error", errMsg(e));
    }
  };

  if (loadError) {
    return (
      <div className="card" style={{ padding: 16 }}>
        <h2 style={{ marginTop: 0 }}>MC 服务端</h2>
        <p className="dim">{loadError}</p>
        <button className="btn btn-raised" onClick={() => void load()}>重试</button>
      </div>
    );
  }

  // ---- 关闭 / 冷却：只有一张开启卡片 -------------------------------------
  if (!feature?.enabled) {
    const cooling = feature?.locked === true;
    return (
      <div className="card" style={{ padding: 16, display: "flex", flexDirection: "column", gap: 12 }}>
        <h2 style={{ margin: 0 }}>MC 服务端（第三方功能）</h2>
        <p className="dim" style={{ margin: 0 }}>
          把原版 Minecraft 服务端当作一个实例来管：版本、端口、进程、控制台、世界与备份。
          面板只负责安装与运行，服务端程序由你在首次安装时从 Mojang 官方下载。
        </p>
        {cooling ? (
          <div className="card" style={{ padding: 12 }}>
            <b>冷却中</b>：功能已关闭，{formatDuration(remaining)} 后才能重新开启。
            重启面板进程会清掉这段冷却。
          </div>
        ) : (
          <div style={{ display: "flex", gap: 8 }}>
            <button className="btn btn-raised" onClick={() => void openWizard()}>
              开启功能
            </button>
          </div>
        )}
        {wizard && <EnableWizard
          eula={eula}
          terms={terms}
          eulaError={eulaError}
          scrolledToEnd={scrolledToEnd}
          onScroll={(atEnd) => setScrolledToEnd(atEnd)}
          eulaAccepted={eulaAccepted}
          termsAccepted={termsAccepted}
          onEula={setEulaAccepted}
          onTerms={setTermsAccepted}
          busy={busy}
          onEnable={() => void enable()}
          onDecline={() => {
            void api.mc.decline().catch(() => undefined);
            setWizard(false);
            toast("info", "已保持关闭");
          }}
        />}
      </div>
    );
  }

  // ---- 开启：实例列表 + 锁横幅 -------------------------------------------
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 8, minHeight: 0 }}>
      <div className="card" style={{ padding: 16, display: "flex", flexDirection: "column", gap: 10 }}>
        <div style={{ display: "flex", alignItems: "center", gap: 10, flexWrap: "wrap" }}>
          <h2 style={{ margin: 0 }}>MC 服务端</h2>
          <span className="tag plain">第三方工具 · 非官方</span>
          <span style={{ flex: 1 }} />
          <button className="btn btn-text" disabled={busy} onClick={() => void load()}>
            <RefreshIcon size={16} /> 刷新
          </button>
          <button className="btn btn-raised danger" disabled={busy || feature.locked} onClick={() => void stopFeature()}>
            停止 MC 功能
          </button>
        </div>
        <p className="dim" style={{ margin: 0 }}>
          客户端连的是游戏端口，不是面板；默认只建议局域网或加防火墙。
          <b> 服务端程序不随本项目分发</b>，首次安装时从 Mojang 官方下载。
        </p>
        {feature.locked && (
          <div className="card" style={{ padding: 12, display: "flex", gap: 10, alignItems: "center", flexWrap: "wrap" }}>
            <b>已按下停止功能</b>
            <span>{formatDuration(remaining)} 后才能启服 / 重新开启</span>
            {feature.keepFeature == null && (
              <>
                <button className="btn btn-raised" disabled={busy} onClick={() => void keepFeature()}>
                  我不想关掉功能
                </button>
                <button className="btn btn-raised danger" disabled={busy} onClick={() => void confirmClose()}>
                  确认关闭功能
                </button>
              </>
            )}
            {feature.keepFeature === true && <span className="dim">已选择保留功能，倒计时结束才能启服。</span>}
            {feature.keepFeature === false && <span className="dim">功能已关闭，冷却结束后才能重新开启。</span>}
          </div>
        )}
      </div>

      <CreateInstanceForm
        versions={versions}
        busy={busy}
        onCreate={async (body) => {
          setBusy(true);
          try {
            await withElevation(() => api.mc.create(body));
            toast("success", `已创建「${body.name}」`);
            await refreshInstances();
          } catch (e) {
            toast("error", errMsg(e));
          } finally {
            setBusy(false);
          }
        }}
      />

      {instances.length === 0 ? (
        <div className="card" style={{ padding: 16 }}>还没有 MC 实例。选一个版本，建一个。</div>
      ) : (
        instances.map((instance) => (
          <div key={instance.id} className="card" style={{ padding: 12, display: "flex", gap: 10, alignItems: "center", flexWrap: "wrap" }}>
            <div style={{ minWidth: 180, flex: 1 }}>
              <div style={{ fontWeight: 600 }}>{instance.name}</div>
              <div className="dim" style={{ fontSize: 12 }}>
                {instance.version} · 端口 {instance.port} · RCON {instance.rconPort} · {instance.memoryMb} MB
              </div>
              {instance.error && <div style={{ color: "var(--monet-error)", fontSize: 12 }}>{instance.error}</div>}
            </div>
            <span className={`tag plain${instance.state === "RUNNING" ? "" : " dim"}`}>{instance.state}</span>
            <button
              className="btn btn-text"
              disabled={busy || feature.locked || instance.state === "RUNNING" || instance.state === "STARTING"}
              onClick={() => void start(instance)}
              title={feature.locked ? "锁定期间不能启服" : undefined}
            >
              启动
            </button>
            <button
              className="btn btn-text"
              disabled={busy || instance.state !== "RUNNING"}
              onClick={() => void stop(instance)}
            >
              停止
            </button>
            <button className="btn btn-text" disabled={busy || instance.state !== "RUNNING"} onClick={() => void kill(instance)}>
              强杀
            </button>
            <button
              className="btn btn-text"
              disabled={!instance.consoleAvailable}
              onClick={() => setTerminalTarget({ kind: "mc", id: instance.id, name: instance.name })}
            >
              <TerminalIcon size={16} /> 控制台
            </button>
            <button className="btn btn-text" onClick={() => void showLogs(instance)}>
              日志
            </button>
            <InstallVersion
              versions={versions}
              current={instance.version}
              busy={busy || instance.state === "RUNNING"}
              onInstall={(version) => void install(instance, version)}
            />
            <button className="btn btn-text danger" disabled={busy || instance.state === "RUNNING"} onClick={() => void remove(instance)}>
              <DeleteIcon size={16} />
            </button>
          </div>
        ))
      )}

      <TerminalWindow target={terminalTarget} onClose={() => setTerminalTarget(null)} />

      {logs && (
        <div className="card" style={{ padding: 12 }}>
          <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
            <b>{logs.name} · 日志</b>
            <span style={{ flex: 1 }} />
            <button className="btn btn-text" onClick={() => setLogs(null)}>关闭</button>
          </div>
          <pre style={{ maxHeight: 320, overflow: "auto", fontSize: 12, margin: 0 }}>
            {logs.lines.length === 0 ? "（还没有日志；服务端启动后写入 logs/latest.log）" : logs.lines.join("\n")}
          </pre>
        </div>
      )}
    </div>
  );
}

function formatDuration(seconds: number): string {
  const total = Math.max(0, Math.floor(seconds));
  const minutes = Math.floor(total / 60);
  return minutes > 0 ? `${minutes} 分 ${total % 60} 秒` : `${total} 秒`;
}

function EnableWizard({
  eula,
  terms,
  eulaError,
  scrolledToEnd,
  onScroll,
  eulaAccepted,
  termsAccepted,
  onEula,
  onTerms,
  busy,
  onEnable,
  onDecline,
}: {
  eula: McEula | null;
  terms: McTerms | null;
  eulaError: string | null;
  scrolledToEnd: boolean;
  onScroll: (atEnd: boolean) => void;
  eulaAccepted: boolean;
  termsAccepted: boolean;
  onEula: (value: boolean) => void;
  onTerms: (value: boolean) => void;
  busy: boolean;
  onEnable: () => void;
  onDecline: () => void;
}) {
  return (
    <div className="card" style={{ padding: 12, display: "flex", flexDirection: "column", gap: 10 }}>
      <b>开启 MC 服务端功能</b>
      {eulaError && <div style={{ color: "var(--monet-error)" }}>{eulaError}</div>}
      {eula && (
        <>
          <div
            style={{ height: 260, overflow: "auto", border: "1px solid var(--monet-outline-variant)", padding: 8, fontSize: 12, whiteSpace: "pre-wrap" }}
            onScroll={(event) => {
              const el = event.currentTarget;
              onScroll(el.scrollTop + el.clientHeight >= el.scrollHeight - 4);
            }}
          >
            {eula.text}
          </div>
          <label style={{ display: "flex", gap: 8, alignItems: "flex-start" }}>
            <input type="checkbox" disabled={!scrolledToEnd} checked={eulaAccepted} onChange={(e) => onEula(e.target.checked)} />
            <span>我已阅读并同意 Mojang 的《最终用户许可协议》（sha256:{eula.hash.slice(7, 19)}…，拉取于 {eula.fetchedAt}）。</span>
          </label>
          <label style={{ display: "flex", gap: 8, alignItems: "flex-start" }}>
            <input type="checkbox" disabled={!scrolledToEnd} checked={termsAccepted} onChange={(e) => onTerms(e.target.checked)} />
            <span>
              我已阅读《MC 服务端管理功能使用条款》v{terms?.version ?? 3}（sha256:{terms?.hash.slice(7, 19) ?? "…"}…），
              理解开公网端口、插件代码执行与备份责任由我承担。
            </span>
          </label>
          {terms && (
            <details>
              <summary className="dim" style={{ cursor: "pointer", fontSize: 12 }}>查看使用条款全文</summary>
              <div style={{ maxHeight: 240, overflow: "auto", border: "1px solid var(--monet-outline-variant)", padding: 8, marginTop: 6, fontSize: 12, whiteSpace: "pre-wrap" }}>
                {terms.text}
              </div>
            </details>
          )}
          <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
            <button className="btn btn-raised" disabled={busy || !eulaAccepted || !termsAccepted} onClick={onEnable}>
              开启
            </button>
            <button className="btn btn-text" onClick={onDecline}>
              我已知晓风险，暂不启用
            </button>
          </div>
        </>
      )}
      {!eula && !eulaError && <div className="dim">正在拉取官方 EULA…</div>}
    </div>
  );
}

function CreateInstanceForm({
  versions,
  busy,
  onCreate,
}: {
  versions: McVersion[];
  busy: boolean;
  onCreate: (body: { name: string; version: string; memoryMb: number; levelName: string; seed?: string }) => void;
}) {
  const [name, setName] = useState("");
  const [version, setVersion] = useState("");
  const [memoryMb, setMemoryMb] = useState(2048);
  const [levelName, setLevelName] = useState("world");
  const [seed, setSeed] = useState("");
  const releases = versions.filter((entry) => entry.type === "release").slice(0, 80);

  return (
    <div className="card" style={{ padding: 12, display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap" }}>
      <input className="input" placeholder="实例名" value={name} onChange={(e) => setName(e.target.value)} style={{ flex: "1 1 140px" }} />
      <select className="input" value={version} onChange={(e) => setVersion(e.target.value)} style={{ flex: "1 1 140px" }}>
        <option value="">版本</option>
        {releases.map((entry) => (
          <option key={entry.id} value={entry.id}>{entry.id}</option>
        ))}
      </select>
      <input
        className="input"
        type="number"
        min={256}
        step={256}
        value={memoryMb}
        onChange={(e) => setMemoryMb(Number(e.target.value) || 1024)}
        style={{ width: 120 }}
        title="内存（MB）"
      />
      <input className="input" value={levelName} onChange={(e) => setLevelName(e.target.value)} style={{ width: 120 }} title="世界目录名" />
      <input className="input" placeholder="种子（可空）" value={seed} onChange={(e) => setSeed(e.target.value)} style={{ width: 120 }} />
      <button
        className="btn btn-raised"
        disabled={busy || !name.trim() || !version}
        onClick={() => onCreate({ name: name.trim(), version, memoryMb, levelName, seed: seed.trim() || undefined })}
      >
        <AddIcon size={16} /> 创建
      </button>
    </div>
  );
}

function InstallVersion({
  versions,
  current,
  busy,
  onInstall,
}: {
  versions: McVersion[];
  current: string;
  busy: boolean;
  onInstall: (version: string) => void;
}) {
  const [choice, setChoice] = useState("");
  const releases = versions.filter((entry) => entry.type === "release").slice(0, 80);
  return (
    <span style={{ display: "inline-flex", gap: 4, alignItems: "center" }}>
      <select className="input" value={choice} onChange={(e) => setChoice(e.target.value)} style={{ width: 110 }} title={`当前 ${current}`}>
        <option value="">装版本…</option>
        {releases.map((entry) => (
          <option key={entry.id} value={entry.id}>{entry.id}</option>
        ))}
      </select>
      <button
        className="btn btn-text"
        disabled={busy || !choice}
        onClick={() => {
          onInstall(choice);
          setChoice("");
        }}
        title="从 Mojang 官方下载并校验 SHA-1"
      >
        <RocketLaunchIcon size={16} />
      </button>
    </span>
  );
}