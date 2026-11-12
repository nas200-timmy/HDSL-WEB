import { useEffect, useMemo, useState } from "react";
import { Dialog } from "../../components/Dialog";
import { CheckCircleIcon, ErrorIcon, RefreshIcon } from "../../components/icons";
import { api } from "../../api";
import { isElevationRequired, requestDeveloperPassword } from "../../elevation";
import { toast } from "../../store";
import type { ChatAccountProfile, ChatModelInfo, ChatProbe, ChatSettings } from "../../types";
import { errMsg } from "../../utils";

type Tab = "data" | "network" | "endpoint" | "capability";

/** 聊天设置：保留/备份、私网策略、端点与模型能力。写入都需要开发者提权。 */
export function ChatSettingsDialog({
  open, onClose, onSaved,
}: {
  open: boolean;
  onClose: () => void;
  onSaved: (settings: ChatSettings) => void;
}) {
  const [tab, setTab] = useState<Tab>("data");
  const [settings, setSettings] = useState<ChatSettings | null>(null);
  const [accounts, setAccounts] = useState<ChatAccountProfile[]>([]);
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(false);
  const [accountName, setAccountName] = useState("");
  const [endpointForm, setEndpointForm] = useState({
    protocol: "openai-completions", base: "", modelsPath: "/models", chatPath: "/chat/completions",
  });
  const [probe, setProbe] = useState<ChatProbe | null>(null);
  const [probeBusy, setProbeBusy] = useState(false);
  const [capabilityAccount, setCapabilityAccount] = useState("");
  const [capabilityModel, setCapabilityModel] = useState("");
  const [capability, setCapability] = useState<ChatModelInfo | null>(null);

  const withElevation = async <T,>(action: () => Promise<T>): Promise<T> => {
    try {
      return await action();
    } catch (error) {
      if (isElevationRequired(error) && (await requestDeveloperPassword())) {
        return await action();
      }
      throw error;
    }
  };

  const load = async () => {
    setLoading(true);
    try {
      const [next, accountResult] = await Promise.all([
        withElevation(() => api.chat.settings()),
        api.chat.accounts().then((result) => result.accounts).catch(() => [] as ChatAccountProfile[]),
      ]);
      setSettings(next);
      setAccounts(accountResult);
      if (!accountName && accountResult.length > 0) setAccountName(accountResult[0].name);
      if (!capabilityAccount && accountResult.length > 0) setCapabilityAccount(accountResult[0].name);
    } catch (error) {
      toast("error", `读取聊天设置失败：${errMsg(error)}`);
      onClose();
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (open) void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  const selected = useMemo(
    () => accounts.find((item) => item.name === accountName) ?? null,
    [accounts, accountName],
  );
  const capabilityAccountInfo = useMemo(
    () => accounts.find((item) => item.name === capabilityAccount) ?? null,
    [accounts, capabilityAccount],
  );
  const capabilityModels = capabilityAccountInfo?.modelList ?? [];

  useEffect(() => {
    if (!selected) return;
    setEndpointForm({
      protocol: selected.protocol,
      base: selected.base ?? selected.endpoint ?? "",
      modelsPath: selected.modelsPath ?? "/models",
      chatPath: selected.chatPath ?? "/chat/completions",
    });
    setProbe(selected.lastProbe ?? null);
  }, [selected]);

  useEffect(() => {
    if (!capabilityAccountInfo) {
      setCapability(null);
      return;
    }
    const model = capabilityModels.find((item) => item.id === capabilityModel) ?? capabilityModels[0] ?? null;
    if (model) {
      setCapabilityModel(model.id);
      setCapability(model);
    } else {
      setCapability(null);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [capabilityAccountInfo]);

  const patch = <K extends keyof ChatSettings>(key: K, value: ChatSettings[K]) =>
    setSettings((current) => (current ? { ...current, [key]: value } : current));

  const save = async () => {
    if (!settings) return;
    setBusy(true);
    try {
      const body: Record<string, unknown> = {
        retention: settings.retention,
        retentionDays: settings.retentionDays,
        backupEnabled: settings.backupEnabled,
        backupTime: settings.backupTime,
        backupKeep: settings.backupKeep,
        backupDir: settings.backupDir,
        privateEndpoints: settings.privateEndpoints,
        privateAllowlist: settings.privateAllowlist,
        longTextTokens: settings.longTextTokens,
        dailyTokenLimit: settings.dailyTokenLimit,
        defaultAccount: settings.defaultAccount,
        defaultModel: settings.defaultModel,
      };
      const next = await withElevation(() => api.chat.updateSettings(body));
      setSettings(next);
      onSaved(next);
      toast("success", "聊天设置已保存");
    } catch (error) {
      toast("error", `保存失败：${errMsg(error)}`);
    } finally {
      setBusy(false);
    }
  };

  const backupNow = async () => {
    setBusy(true);
    try {
      const entry = await withElevation(() => api.chat.backupNow());
      toast("success", `备份完成：${entry.name}`);
      const next = await withElevation(() => api.chat.settings());
      setSettings(next);
      onSaved(next);
    } catch (error) {
      toast("error", `备份失败：${errMsg(error)}`);
    } finally {
      setBusy(false);
    }
  };

  const saveEndpoint = async () => {
    if (!selected) return;
    setBusy(true);
    try {
      await withElevation(() => api.chat.saveAccountProfile(selected.name, {
        protocol: endpointForm.protocol,
        base: endpointForm.base.trim(),
        modelsPath: endpointForm.modelsPath.trim(),
        chatPath: endpointForm.chatPath.trim(),
      }));
      const next = await api.chat.accounts();
      setAccounts(next.accounts);
      toast("success", "端点已保存");
    } catch (error) {
      toast("error", `保存端点失败：${errMsg(error)}`);
    } finally {
      setBusy(false);
    }
  };

  const runProbe = async () => {
    if (!selected) return;
    setProbeBusy(true);
    try {
      setProbe(await withElevation(() => api.chat.probe(selected.name)));
    } catch (error) {
      toast("error", `测试失败：${errMsg(error)}`);
    } finally {
      setProbeBusy(false);
    }
  };

  const refreshModels = async () => {
    if (!selected) return;
    setBusy(true);
    try {
      const result = await withElevation(() => api.chat.refreshModels(selected.name));
      toast("success", `拉到 ${result.models.length} 个模型`);
      const next = await api.chat.accounts();
      setAccounts(next.accounts);
    } catch (error) {
      toast("error", `拉取模型失败：${errMsg(error)}`);
    } finally {
      setBusy(false);
    }
  };

  const saveCapability = async (reset = false) => {
    if (!capabilityAccount || !capabilityModel) return;
    setBusy(true);
    try {
      const body: Record<string, unknown> = { model: capabilityModel, reset };
      if (!reset && capability) {
        body.vision = capability.capabilities.vision;
        body.files = capability.capabilities.files;
        body.reasoning = capability.capabilities.reasoning;
        body.tools = capability.capabilities.tools;
        body.maxContext = capability.capabilities.maxContext;
        body.maxOutput = capability.capabilities.maxOutput;
      }
      const next = await withElevation(() => api.chat.saveCapability(capabilityAccount, body));
      setCapability(next);
      const refreshed = await api.chat.accounts();
      setAccounts(refreshed.accounts);
      toast("success", reset ? "已恢复默认能力" : "能力已保存");
    } catch (error) {
      toast("error", `保存能力失败：${errMsg(error)}`);
    } finally {
      setBusy(false);
    }
  };

  const toggleCapability = (key: "vision" | "files" | "reasoning" | "tools") => {
    setCapability((current) => current
      ? { ...current, capabilities: { ...current.capabilities, [key]: !current.capabilities[key] } }
      : current);
  };

  return (
    <Dialog
      open={open}
      title="聊天设置"
      onClose={busy ? undefined : onClose}
      wide
      footer={
        <div className="dialog-actions" style={{ width: "100%" }}>
          <span style={{ fontSize: 12, color: "var(--monet-on-surface-variant)" }}>
            设置、备份与端点探测需要开发者提权；这些只影响聊天，不写回账户页。
          </span>
          <span className="spacer" />
          <button className="btn btn-text dim" disabled={busy} onClick={onClose}>关闭</button>
          <button className="btn btn-text" disabled={busy || !settings} onClick={() => void save()}>
            {busy ? "保存中…" : "保存"}
          </button>
        </div>
      }
    >
      <div className="chat-settings-tabs">
        {([["data", "数据"], ["network", "网络"], ["endpoint", "端点"], ["capability", "模型能力"]] as const).map(([key, label]) => (
          <button key={key} className={`chat-settings-tab${tab === key ? " active" : ""}`} onClick={() => setTab(key)}>
            {label}
          </button>
        ))}
      </div>

      {loading || !settings ? (
        <div className="chat-settings-loading"><span className="spinner" /> 读取中…</div>
      ) : tab === "data" ? (
        <div className="chat-settings-body">
          <div className="chat-form-grid">
            <label className="form-row">
              <span className="form-label">保留策略</span>
              <select className="input" value={settings.retention} onChange={(event) => patch("retention", event.target.value as ChatSettings["retention"])}>
                <option value="forever">永久保留</option>
                <option value="30d">30 天</option>
                <option value="90d">90 天</option>
                <option value="180d">180 天</option>
                <option value="365d">365 天</option>
                <option value="custom">自定义天数</option>
              </select>
            </label>
            {settings.retention === "custom" && (
              <label className="form-row">
                <span className="form-label">天数</span>
                <input className="input" type="number" min={1} value={settings.retentionDays}
                  onChange={(event) => patch("retentionDays", Number(event.target.value))} />
              </label>
            )}
            <label className="form-row">
              <span className="form-label">长文本阈值（估算 tokens）</span>
              <input className="input" type="number" min={500} value={settings.longTextTokens}
                onChange={(event) => patch("longTextTokens", Number(event.target.value))} />
            </label>
          </div>

          <div className="chat-settings-section">
            <div className="chat-settings-title">备份</div>
            <div className="chat-form-grid three">
              <label className="form-row">
                <span className="form-label">启用每日备份</span>
                <input type="checkbox" checked={settings.backupEnabled} onChange={(event) => patch("backupEnabled", event.target.checked)} />
              </label>
              <label className="form-row">
                <span className="form-label">时间</span>
                <input className="input" value={settings.backupTime} onChange={(event) => patch("backupTime", event.target.value)} placeholder="03:17" />
              </label>
              <label className="form-row">
                <span className="form-label">保留份数</span>
                <input className="input" type="number" min={1} max={100} value={settings.backupKeep}
                  onChange={(event) => patch("backupKeep", Number(event.target.value))} />
              </label>
            </div>
            <label className="form-row">
              <span className="form-label">目录</span>
              <input className="input" value={settings.backupDir} onChange={(event) => patch("backupDir", event.target.value)} />
            </label>
            <div className="chat-backup-actions">
              <button className="btn btn-text" disabled={busy} onClick={() => void backupNow()}>立即备份</button>
              <span style={{ fontSize: 12, color: "var(--monet-on-surface-variant)" }}>
                备份包含聊天内容与附件，按敏感数据处理。
              </span>
            </div>
            <div className="chat-backup-list">
              {settings.backups.length === 0 ? (
                <div className="chat-backup-empty">还没有备份。</div>
              ) : settings.backups.map((backup) => (
                <div className="chat-backup-row" key={backup.name}>
                  <span className="mono">{backup.name}</span>
                  <span>{backup.sessions} 会话 / {backup.messages} 消息 / {backup.attachments} 附件</span>
                  <span>{Math.round(backup.bytes / 1024)} KB</span>
                  <span className={`tag ${backup.complete ? "running" : "failed"}`}>{backup.complete ? "完整" : "失败"}</span>
                </div>
              ))}
            </div>
          </div>

          <div className="chat-form-grid">
            <label className="form-row">
              <span className="form-label">聊天默认账户</span>
              <select className="input" value={settings.defaultAccount ?? ""} onChange={(event) => patch("defaultAccount", event.target.value || null)}>
                <option value="">跟随会话/助手</option>
                {accounts.map((account) => <option key={account.name} value={account.name}>{account.label || account.name}</option>)}
              </select>
            </label>
            <label className="form-row">
              <span className="form-label">聊天默认模型</span>
              <input className="input" value={settings.defaultModel ?? ""} onChange={(event) => patch("defaultModel", event.target.value || null)} />
            </label>
          </div>
        </div>
      ) : tab === "network" ? (
        <div className="chat-settings-body">
          <label className="form-row">
            <span className="form-label">私网 / loopback 端点</span>
            <select className="input" value={settings.privateEndpoints} onChange={(event) => patch("privateEndpoints", event.target.value as ChatSettings["privateEndpoints"])}>
              <option value="allow">允许（Ollama / LM Studio / vLLM 直接可用）</option>
              <option value="strict">严格：拦私网，只放行 allowlist</option>
            </select>
          </label>
          <label className="form-row">
            <span className="form-label">allowlist（每行一个主机或 .后缀）</span>
            <textarea
              className="input"
              rows={5}
              value={settings.privateAllowlist.join("\n")}
              onChange={(event) => patch("privateAllowlist", event.target.value.split("\n").map((line) => line.trim()).filter(Boolean))}
            />
          </label>
          <div className="hint">
            容器里的 <code>127.0.0.1</code> 不是宿主机；Ollama 请用 <code>host.docker.internal</code> 或宿主 LAN 地址。
          </div>
        </div>
      ) : tab === "endpoint" ? (
        <div className="chat-settings-body">
          {accounts.length === 0 ? (
            <div className="chat-settings-empty">还没有带 key 的账户。先到主界面的账户页添加一个。</div>
          ) : (
            <>
              <label className="form-row">
                <span className="form-label">账户</span>
                <select className="input" value={accountName} onChange={(event) => setAccountName(event.target.value)}>
                  {accounts.map((account) => <option key={account.name} value={account.name}>{account.label || account.name}</option>)}
                </select>
              </label>
              <div className="chat-form-grid">
                <label className="form-row">
                  <span className="form-label">协议</span>
                  <select className="input" value={endpointForm.protocol} onChange={(event) => setEndpointForm((current) => ({ ...current, protocol: event.target.value }))}>
                    <option value="openai-completions">openai-completions</option>
                    <option value="anthropic-messages">anthropic-messages</option>
                  </select>
                </label>
                <label className="form-row">
                  <span className="form-label">Base URL</span>
                  <input className="input" value={endpointForm.base} onChange={(event) => setEndpointForm((current) => ({ ...current, base: event.target.value }))} />
                </label>
              </div>
              <div className="chat-form-grid">
                <label className="form-row">
                  <span className="form-label">models path</span>
                  <input className="input" value={endpointForm.modelsPath} onChange={(event) => setEndpointForm((current) => ({ ...current, modelsPath: event.target.value }))} />
                </label>
                <label className="form-row">
                  <span className="form-label">chat path</span>
                  <input className="input" value={endpointForm.chatPath} onChange={(event) => setEndpointForm((current) => ({ ...current, chatPath: event.target.value }))} />
                </label>
              </div>
              <div className="chat-endpoint-urls">
                <div><span>models</span><code>{endpointForm.base.replace(/\/+$/, "") + endpointForm.modelsPath}</code></div>
                <div><span>chat</span><code>{endpointForm.base.replace(/\/+$/, "") + endpointForm.chatPath}</code></div>
              </div>
              <div className="chat-endpoint-actions">
                <button className="btn btn-text" disabled={busy} onClick={() => void saveEndpoint()}>保存端点</button>
                <button className="btn btn-text" disabled={probeBusy} onClick={() => void runProbe()}>
                  {probeBusy ? "测试中…" : "测试连接"}
                </button>
                <button className="btn btn-text" disabled={busy} onClick={() => void refreshModels()}>
                  <RefreshIcon size={14} /> 拉取模型
                </button>
              </div>
              {probe && (
                <div className={`chat-probe ${probe.error ? "bad" : "good"}`}>
                  <div>
                    {probe.error ? <ErrorIcon size={14} /> : <CheckCircleIcon size={14} />}
                    <span>{probe.error ? probe.error : `连接正常 · ${probe.latencyMs} ms · ${probe.models.length} 个模型`}</span>
                  </div>
                  <div className="chat-probe-stages">
                    <span className={probe.reachable ? "ok" : "no"}>reachable</span>
                    <span className={probe.tlsOk ? "ok" : "no"}>tls</span>
                    <span className={probe.authOk ? "ok" : "no"}>auth</span>
                    <span className={probe.modelsOk ? "ok" : "no"}>models</span>
                  </div>
                  {probe.warnings.length > 0 && <div className="chat-probe-warn">{probe.warnings.join("；")}</div>}
                </div>
              )}
              {(selected?.models?.length ?? 0) > 0 && (
                <div className="chat-model-tags">
                  {selected!.models!.map((model) => <span className="tag plain" key={model}>{model}</span>)}
                </div>
              )}
            </>
          )}
        </div>
      ) : (
        <div className="chat-settings-body">
          {accounts.length === 0 ? (
            <div className="chat-settings-empty">还没有带 key 的账户。</div>
          ) : (
            <>
              <div className="chat-form-grid">
                <label className="form-row">
                  <span className="form-label">账户</span>
                  <select className="input" value={capabilityAccount} onChange={(event) => {
                    setCapabilityAccount(event.target.value);
                    setCapabilityModel("");
                  }}>
                    {accounts.map((account) => <option key={account.name} value={account.name}>{account.label || account.name}</option>)}
                  </select>
                </label>
                <label className="form-row">
                  <span className="form-label">模型</span>
                  <input className="input" list="chat-capability-models" value={capabilityModel} onChange={(event) => {
                    setCapabilityModel(event.target.value);
                    const found = capabilityModels.find((item) => item.id === event.target.value) ?? null;
                    setCapability(found);
                  }} />
                  <datalist id="chat-capability-models">
                    {capabilityModels.map((item) => <option key={item.id} value={item.id} />)}
                  </datalist>
                </label>
              </div>
              {capability ? (
                <>
                  <div className="chat-capability-grid">
                    {(["vision", "files", "reasoning", "tools"] as const).map((key) => (
                      <label className="chat-capability-toggle" key={key}>
                        <input type="checkbox" checked={capability.capabilities[key]} onChange={() => toggleCapability(key)} />
                        <span>{key === "vision" ? "图片" : key === "files" ? "文件" : key === "reasoning" ? "思考" : "工具（仅标记）"}</span>
                      </label>
                    ))}
                  </div>
                  <div className="chat-form-grid">
                    <label className="form-row">
                      <span className="form-label">上下文上限</span>
                      <input className="input" type="number" value={capability.capabilities.maxContext}
                        onChange={(event) => setCapability({ ...capability, capabilities: { ...capability.capabilities, maxContext: Number(event.target.value) } })} />
                    </label>
                    <label className="form-row">
                      <span className="form-label">输出上限</span>
                      <input className="input" type="number" value={capability.capabilities.maxOutput}
                        onChange={(event) => setCapability({ ...capability, capabilities: { ...capability.capabilities, maxOutput: Number(event.target.value) } })} />
                    </label>
                  </div>
                  <div className="chat-capability-source">来源：{capability.capabilities.source}</div>
                  <div className="chat-endpoint-actions">
                    <button className="btn btn-text" disabled={busy} onClick={() => void saveCapability()}>保存能力</button>
                    <button className="btn btn-text dim" disabled={busy} onClick={() => void saveCapability(true)}>恢复默认</button>
                  </div>
                </>
              ) : (
                <div className="chat-settings-empty">这个账户还没有模型列表；先到「端点」页拉取模型。</div>
              )}
            </>
          )}
        </div>
      )}
    </Dialog>
  );
}