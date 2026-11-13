import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { api } from "../../api";
import { ConfirmDialog, Dialog } from "../../components/Dialog";
import {
  AddIcon, ArrowBackIcon, CheckCircleIcon, DeleteIcon, ErrorIcon, LockIcon,
  RefreshIcon, SearchIcon, StarIcon,
} from "../../components/icons";
import { isElevationRequired, requestDeveloperPassword } from "../../elevation";
import { toast } from "../../store";
import type {
  ChatAccountProfile, ChatBackup, ChatModelInfo, ChatProbe, ChatSettings,
  CreateAccountBody, ModelProvider,
} from "../../types";
import { errMsg } from "../../utils";
import { ModelCaps, capabilitySourceLabel } from "./ModelCaps";

type Run = <T>(action: () => Promise<T>) => Promise<T>;
type Tab = "providers" | "defaults" | "data" | "network";

function bytes(value: number): string {
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${Math.round(value / 1024)} KB`;
  return `${(value / 1024 / 1024).toFixed(1)} MB`;
}

function providerLetter(account: ChatAccountProfile): string {
  const text = account.label || account.name || account.vendor || "?";
  return text.slice(0, 1).toUpperCase();
}

function modelCount(account: ChatAccountProfile): number {
  return account.modelList?.length ?? account.models?.length ?? 0;
}

/** 聊天设置：独立二级页（模型服务 / 默认模型 / 数据与备份 / 网络）。 */
export function ChatSettingsPage() {
  const nav = useNavigate();
  const [tab, setTab] = useState<Tab>("providers");
  const [settings, setSettings] = useState<ChatSettings | null>(null);
  const [accounts, setAccounts] = useState<ChatAccountProfile[]>([]);
  const [loading, setLoading] = useState(true);
  const [needsElevation, setNeedsElevation] = useState(false);
  const [elevating, setElevating] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);

  const run: Run = async (action) => {
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
      const nextSettings = await run(() => api.chat.settings());
      const accountResult = await run(() => api.chat.accounts());
      setSettings(nextSettings);
      setAccounts(accountResult.accounts);
      setNeedsElevation(false);
      setLoadError(null);
    } catch (error) {
      if (isElevationRequired(error)) setNeedsElevation(true);
      else {
        const message = errMsg(error);
        setLoadError(message);
        toast("error", `读取聊天设置失败：${message}`);
      }
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const reloadAccounts = async (_select?: string) => {
    const result = await run(() => api.chat.accounts());
    setAccounts(result.accounts);
    return result.accounts;
  };

  const elevate = async () => {
    setElevating(true);
    try {
      if (await requestDeveloperPassword()) {
        setNeedsElevation(false);
        await load();
      }
    } finally {
      setElevating(false);
    }
  };

  return (
    <div className="chat-settings-page">
      <div className="chat-settings-top">
        <button className="chat-back-btn ripple-host" onClick={() => nav("/chat")}>
          <ArrowBackIcon size={18} />
          返回聊天
        </button>
        <h1>聊天设置</h1>
        <span className="spacer" />
        <span className="chat-settings-note">只影响聊天，不写回主界面的账户页</span>
      </div>

      <div className="chat-settings-layout">
        <nav className="chat-settings-nav">
          {([
            ["providers", "模型服务"],
            ["defaults", "默认模型"],
            ["data", "数据与备份"],
            ["network", "网络"],
          ] as const).map(([key, label]) => (
            <button
              key={key}
              className={`chat-settings-nav-item${tab === key ? " active" : ""}`}
              onClick={() => setTab(key)}
            >
              {label}
            </button>
          ))}
        </nav>

        <div className="chat-settings-content scroll">
          {needsElevation ? (
            <div className="chat-elevation-card">
              <span className="chat-elevation-icon"><LockIcon size={26} /></span>
              <h2>需要开发者提权</h2>
              <p>当前开发者守卫拦截了聊天设置与账户列表。输入开发者密码后重试，本页不会保存密码。</p>
              <button className="btn btn-raised ripple-host" disabled={elevating} onClick={() => void elevate()}>
                {elevating ? "提权中…" : "输入开发者密码"}
              </button>
            </div>
          ) : loadError ? (
            <div className="chat-provider-detail-empty">
              <span className="chat-provider-empty-icon"><ErrorIcon size={26} /></span>
              <h2>聊天设置读不到</h2>
              <p>{loadError}</p>
              <button className="btn btn-raised ripple-host" onClick={() => void load()}>重试</button>
            </div>
          ) : loading || !settings ? (
            <div className="chat-settings-loading"><span className="spinner" /> 读取中…</div>
          ) : tab === "providers" ? (
            <ProvidersPanel
              accounts={accounts}
              run={run}
              reloadAccounts={reloadAccounts}
            />
          ) : tab === "defaults" ? (
            <DefaultsPanel
              settings={settings}
              accounts={accounts}
              run={run}
              onSaved={setSettings}
            />
          ) : tab === "data" ? (
            <DataPanel
              settings={settings}
              run={run}
              onSaved={setSettings}
            />
          ) : (
            <NetworkPanel
              settings={settings}
              run={run}
              onSaved={setSettings}
            />
          )}
        </div>
      </div>
    </div>
  );
}

function ProvidersPanel({
  accounts, run, reloadAccounts,
}: {
  accounts: ChatAccountProfile[];
  run: Run;
  reloadAccounts: (select?: string) => Promise<ChatAccountProfile[]>;
}) {
  const [selectedName, setSelectedName] = useState(accounts[0]?.name ?? "");
  const [query, setQuery] = useState("");
  const [addOpen, setAddOpen] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<ChatAccountProfile | null>(null);
  const [deleteBusy, setDeleteBusy] = useState(false);
  const [capabilityTarget, setCapabilityTarget] = useState<ChatModelInfo | null>(null);

  const account = accounts.find((item) => item.name === selectedName) ?? null;
  const [form, setForm] = useState({
    label: "", protocol: "openai-completions", base: "", modelsPath: "/models",
    chatPath: "/chat/completions", apiKey: "", model: "",
  });
  const [models, setModels] = useState<ChatModelInfo[]>([]);
  const [modelQuery, setModelQuery] = useState("");
  const [probe, setProbe] = useState<ChatProbe | null>(null);
  const [busy, setBusy] = useState(false);
  const [probeBusy, setProbeBusy] = useState(false);

  useEffect(() => {
    if (new URLSearchParams(window.location.search).get("add") === "1") {
      setAddOpen(true);
    }
  }, []);

  useEffect(() => {
    if (!account && accounts.length > 0) setSelectedName(accounts[0].name);
  }, [accounts, account]);

  useEffect(() => {
    if (!account) return;
    setForm({
      label: account.label || account.name,
      protocol: account.protocol || "openai-completions",
      base: account.base ?? account.endpoint ?? "",
      modelsPath: account.modelsPath ?? "/models",
      chatPath: account.chatPath ?? "/chat/completions",
      apiKey: "",
      model: account.model ?? "",
    });
    setModels(account.modelList ?? []);
    setProbe(account.lastProbe ?? null);
    setModelQuery("");
    let cancelled = false;
    void (async () => {
      try {
        const result = await run(() => api.chat.models(account.name));
        if (!cancelled) setModels(result.models);
      } catch {
        // Keep the cached list.
      }
    })();
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [account]);

  const visibleProviders = accounts.filter((item) => {
    const text = `${item.label} ${item.name} ${item.vendor}`.toLowerCase();
    return !query.trim() || text.includes(query.trim().toLowerCase());
  });
  const visibleModels = models.filter((item) =>
    !modelQuery.trim() || item.id.toLowerCase().includes(modelQuery.trim().toLowerCase()));

  const save = async () => {
    if (!account) return;
    setBusy(true);
    try {
      await run(() => api.chat.saveAccountProfile(account.name, {
        protocol: form.protocol,
        base: form.base.trim(),
        modelsPath: form.modelsPath.trim(),
        chatPath: form.chatPath.trim(),
      }));
      if (form.label.trim() && form.label.trim() !== account.label) {
        await run(() => api.patchAccount(account.name, { label: form.label.trim() }));
      }
      if (form.apiKey.trim()) {
        await run(() => api.patchAccount(account.name, { apiKey: form.apiKey.trim() }));
      }
      if (form.model.trim() && form.model.trim() !== account.model) {
        await run(() => api.patchAccount(account.name, { model: form.model.trim() }));
      }
      await reloadAccounts(account.name);
      setForm((current) => ({ ...current, apiKey: "" }));
      toast("success", "模型服务已保存");
    } catch (error) {
      toast("error", `保存失败：${errMsg(error)}`);
    } finally {
      setBusy(false);
    }
  };

  const runProbe = async () => {
    if (!account) return;
    setProbeBusy(true);
    try {
      setProbe(await run(() => api.chat.probe(account.name)));
    } catch (error) {
      toast("error", `测试失败：${errMsg(error)}`);
    } finally {
      setProbeBusy(false);
    }
  };

  const refreshModels = async () => {
    if (!account) return;
    setBusy(true);
    try {
      const result = await run(() => api.chat.refreshModels(account.name));
      toast("success", `拉到 ${result.models.length} 个模型`);
      await reloadAccounts(account.name);
    } catch (error) {
      toast("error", `拉取模型失败：${errMsg(error)}`);
    } finally {
      setBusy(false);
    }
  };

  const setDefaultModel = async (model: string) => {
    if (!account) return;
    try {
      await run(() => api.patchAccount(account.name, { model }));
      await reloadAccounts(account.name);
      toast("success", `默认模型已设为 ${model}`);
    } catch (error) {
      toast("error", `设置默认模型失败：${errMsg(error)}`);
    }
  };

  const remove = async () => {
    if (!deleteTarget) return;
    setDeleteBusy(true);
    try {
      await run(() => api.deleteAccount(deleteTarget.name));
      toast("success", "模型服务已删除");
      setDeleteTarget(null);
      await reloadAccounts();
    } catch (error) {
      toast("error", `删除失败：${errMsg(error)}`);
    } finally {
      setDeleteBusy(false);
    }
  };

  const applyCapability = (next: ChatModelInfo) => {
    setModels((current) => current.map((item) => item.id === next.id ? next : item));
    setCapabilityTarget(null);
    void reloadAccounts(account?.name);
  };

  return (
    <div className="chat-providers">
      <aside className="chat-provider-list">
        <button className="chat-provider-add ripple-host" onClick={() => setAddOpen(true)}>
          <AddIcon size={16} />
          添加模型服务
        </button>
        <label className="chat-search">
          <SearchIcon size={14} />
          <input value={query} placeholder="搜索服务" onChange={(event) => setQuery(event.target.value)} />
        </label>
        <div className="chat-provider-items">
          {visibleProviders.map((item) => (
            <button
              key={item.name}
              className={`chat-provider-item${item.name === selectedName ? " selected" : ""}`}
              onClick={() => setSelectedName(item.name)}
            >
              <span className="chat-provider-avatar">{providerLetter(item)}</span>
              <span className="chat-provider-text">
                <span className="chat-provider-name">{item.label || item.name}</span>
                <span className="chat-provider-sub">{item.vendor} · {modelCount(item)} 个模型</span>
              </span>
              <span
                className={`chat-provider-dot${item.configured || modelCount(item) > 0 ? " ok" : " warn"}`}
                title={item.configured || modelCount(item) > 0 ? "可用" : "还没有模型列表"}
              />
            </button>
          ))}
          {visibleProviders.length === 0 && <div className="chat-provider-empty">没有匹配的模型服务。</div>}
        </div>
      </aside>

      <section className="chat-provider-detail">
        {!account ? (
          <div className="chat-provider-detail-empty">
            <span className="chat-provider-empty-icon">🔌</span>
            <h2>还没有模型服务</h2>
            <p>添加一个 OpenAI 兼容服务，或从供应商目录里选一个。API Key 只保存在面板后端。</p>
            <button className="btn btn-raised ripple-host" onClick={() => setAddOpen(true)}>添加模型服务</button>
          </div>
        ) : (
          <>
            <header className="chat-provider-head">
              <span className="chat-provider-avatar large">{providerLetter(account)}</span>
              <div className="chat-provider-head-text">
                <h2>{account.label || account.name}</h2>
                <p>{account.vendor}{account.endpoint ? ` · ${account.endpoint}` : ""}</p>
              </div>
              <span className="spacer" />
              <button className="btn btn-text danger" onClick={() => setDeleteTarget(account)}>
                <DeleteIcon size={14} />
                删除
              </button>
            </header>

            <div className="chat-settings-card">
              <div className="chat-settings-card-title">连接</div>
              <div className="chat-settings-form-grid">
                <label className="form-row">
                  <span className="form-label">显示名称</span>
                  <input className="input" value={form.label} onChange={(event) => setForm((current) => ({ ...current, label: event.target.value }))} />
                </label>
                <label className="form-row">
                  <span className="form-label">API 密钥（留空保持不变）</span>
                  <input className="input" type="password"
                    placeholder={account.maskedKey ? `已保存 ${account.maskedKey}` : "sk-…"}
                    value={form.apiKey}
                    onChange={(event) => setForm((current) => ({ ...current, apiKey: event.target.value }))} autoComplete="off" />
                </label>
                <label className="form-row">
                  <span className="form-label">协议</span>
                  <select className="input" value={form.protocol} onChange={(event) => setForm((current) => ({ ...current, protocol: event.target.value }))}>
                    <option value="openai-completions">openai-completions</option>
                    <option value="anthropic-messages">anthropic-messages</option>
                  </select>
                </label>
                <label className="form-row">
                  <span className="form-label">API 地址</span>
                  <input className="input" value={form.base} onChange={(event) => setForm((current) => ({ ...current, base: event.target.value }))} />
                </label>
                <label className="form-row">
                  <span className="form-label">models path</span>
                  <input className="input" value={form.modelsPath} onChange={(event) => setForm((current) => ({ ...current, modelsPath: event.target.value }))} />
                </label>
                <label className="form-row">
                  <span className="form-label">chat path</span>
                  <input className="input" value={form.chatPath} onChange={(event) => setForm((current) => ({ ...current, chatPath: event.target.value }))} />
                </label>
              </div>
              <div className="chat-provider-actions">
                <button className="btn btn-raised" disabled={busy} onClick={() => void save()}>
                  {busy ? "保存中…" : "保存"}
                </button>
                <button className="btn btn-outline" disabled={probeBusy} onClick={() => void runProbe()}>
                  {probeBusy ? "测试中…" : "测试连接"}
                </button>
                <button className="btn btn-outline" disabled={busy} onClick={() => void refreshModels()}>
                  <RefreshIcon size={14} />
                  拉取模型
                </button>
              </div>
              {probe && (
                <div className={`chat-probe ${probe.error ? "bad" : "good"}`}>
                  <div>
                    {probe.error ? <ErrorIcon size={14} /> : <CheckCircleIcon size={14} />}
                    <span>{probe.error ? probe.error : `连接正常 · ${probe.latencyMs} ms · ${probe.models?.length ?? 0} 个模型`}</span>
                  </div>
                  <div className="chat-probe-stages">
                    <span className={probe.reachable ? "ok" : "no"}>reachable</span>
                    <span className={probe.tlsOk ? "ok" : "no"}>tls</span>
                    <span className={probe.authOk ? "ok" : "no"}>auth</span>
                    <span className={probe.modelsOk ? "ok" : "no"}>models</span>
                  </div>
                </div>
              )}
            </div>

            <div className="chat-settings-card chat-model-card">
              <div className="chat-model-head">
                <div className="chat-settings-card-title">模型 <span className="chat-count">{models.length}</span></div>
                <span className="spacer" />
                <label className="chat-search compact">
                  <SearchIcon size={13} />
                  <input value={modelQuery} placeholder="搜索模型" onChange={(event) => setModelQuery(event.target.value)} />
                </label>
                <button className="btn btn-text" disabled={busy} onClick={() => void refreshModels()}>
                  <RefreshIcon size={14} />
                  刷新
                </button>
              </div>
              <div className="chat-model-note">
                能力默认按模型名自动识别（内置模型表）；识别不到时标「能力未识别」并全部关闭，
                点每行的「能力」可手动覆盖，覆盖一直有效直到「恢复默认」。
              </div>
              <div className="chat-model-list">
                {visibleModels.length === 0 ? (
                  <div className="chat-provider-empty">这个服务还没有模型列表；点「拉取模型」或手填默认模型。</div>
                ) : visibleModels.map((item) => {
                  const caps = item.capabilities;
                  const isDefault = account.model === item.id;
                  return (
                    <div className={`chat-model-row${isDefault ? " default" : ""}`} key={item.id}>
                      <button
                        className={`chat-model-star${isDefault ? " active" : ""}`}
                        title={isDefault ? "当前默认模型" : "设为默认模型"}
                        onClick={() => void setDefaultModel(item.id)}
                      >
                        <StarIcon size={15} />
                      </button>
                      <span className="chat-model-id" title={item.id}>{item.id}</span>
                      <span className="chat-model-caps"><ModelCaps caps={caps} /></span>
                      <span className={`chat-cap-source ${caps.source}`} title="能力来源：内置识别按模型名自动判断，可随时手动覆盖">
                        {capabilitySourceLabel(caps.source)}
                      </span>
                      <span className="chat-model-context">{Math.round(caps.maxContext / 1000)}K / {Math.round(caps.maxOutput / 1000)}K</span>
                      <button className="btn btn-text" onClick={() => setCapabilityTarget(item)}>能力</button>
                    </div>
                  );
                })}
              </div>
            </div>
          </>
        )}
      </section>

      <AddProviderDialog
        open={addOpen}
        run={run}
        onClose={() => setAddOpen(false)}
        onCreated={async (name) => {
          setAddOpen(false);
          await reloadAccounts(name);
          setSelectedName(name);
        }}
      />
      <CapabilityDialog
        open={capabilityTarget !== null}
        account={account}
        model={capabilityTarget}
        run={run}
        onClose={() => setCapabilityTarget(null)}
        onSaved={applyCapability}
      />
      <ConfirmDialog
        open={deleteTarget !== null}
        title="删除模型服务"
        message={`删除「${deleteTarget?.label || deleteTarget?.name || ""}」？账户页与使用这个账户的实例会一起失去它。`}
        confirmLabel="删除"
        danger
        busy={deleteBusy}
        onConfirm={() => void remove()}
        onCancel={() => setDeleteTarget(null)}
      />
    </div>
  );
}

function AddProviderDialog({
  open, run, onClose, onCreated,
}: {
  open: boolean;
  run: Run;
  onClose: () => void;
  onCreated: (name: string) => void;
}) {
  const [providers, setProviders] = useState<ModelProvider[]>([]);
  const [query, setQuery] = useState("");
  const [picked, setPicked] = useState<ModelProvider | "custom" | null>(null);
  const [form, setForm] = useState({ vendor: "", endpoint: "", apiKey: "", label: "", model: "" });
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!open) return;
    setPicked(null);
    setQuery("");
    setForm({ vendor: "", endpoint: "", apiKey: "", label: "", model: "" });
    void api.modelProviders().then((result) => setProviders(result.providers)).catch(() => setProviders([]));
  }, [open]);

  const visible = providers.filter((provider) => {
    const text = `${provider.name} ${provider.id} ${provider.endpoint ?? ""}`.toLowerCase();
    return !query.trim() || text.includes(query.trim().toLowerCase());
  });

  const pick = (provider: ModelProvider | "custom") => {
    setPicked(provider);
    if (provider === "custom") {
      setForm((current) => ({ ...current, vendor: "", endpoint: "" }));
    } else {
      setForm((current) => ({
        ...current,
        vendor: provider.id,
        endpoint: provider.endpoint ?? "",
        label: current.label || provider.name,
      }));
    }
  };

  const create = async () => {
    if (!picked) return;
    if (!form.apiKey.trim()) {
      toast("error", "请填写 API Key");
      return;
    }
    const body: CreateAccountBody = { apiKey: form.apiKey.trim() };
    if (picked === "custom") {
      if (!form.vendor.trim() && !form.endpoint.trim()) {
        toast("error", "自定义服务需要名称或 API 地址");
        return;
      }
      if (form.vendor.trim()) body.vendor = form.vendor.trim();
      if (form.endpoint.trim()) body.endpoint = form.endpoint.trim();
    } else {
      body.vendor = picked.id;
      if (!picked.known) {
        if (!form.endpoint.trim()) {
          toast("error", "这个供应商需要 API 地址");
          return;
        }
        body.endpoint = form.endpoint.trim();
        if (picked.protocol) body.protocol = picked.protocol;
      }
    }
    if (form.label.trim()) body.label = form.label.trim();
    if (form.model.trim()) body.model = form.model.trim();
    setBusy(true);
    try {
      const result = await run(() => api.createAccount(body));
      if (result.verified === false) {
        toast("info", `已保存，但现场验证失败：${result.verifyError || "可稍后重试"}`);
      } else {
        toast("success", `已添加「${result.account.label || result.account.name}」`);
      }
      onCreated(result.account.name);
    } catch (error) {
      toast("error", `添加失败：${errMsg(error)}`);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open={open} title={picked ? "添加模型服务" : "选择供应商"} onClose={busy ? undefined : onClose} wide>
      {!picked ? (
        <>
          <label className="chat-search">
            <SearchIcon size={14} />
            <input autoFocus value={query} placeholder="搜索供应商（名称 / id / 端点）" onChange={(event) => setQuery(event.target.value)} />
          </label>
          <div className="chat-provider-grid">
            {visible.map((provider) => (
              <button key={provider.id} className="chat-provider-choice" disabled={provider.protocol === null} onClick={() => pick(provider)}>
                <span className="chat-provider-avatar">{provider.name.slice(0, 1).toUpperCase()}</span>
                <span className="chat-provider-choice-text">
                  <span>{provider.name}</span>
                  <span>{provider.protocol === null ? "协议不支持" : provider.endpoint ?? "需填写端点"}</span>
                </span>
                {!provider.known && <span className="tag plain">目录</span>}
              </button>
            ))}
            <button className="chat-provider-choice" onClick={() => pick("custom")}>
              <span className="chat-provider-avatar">+</span>
              <span className="chat-provider-choice-text">
                <span>自定义服务</span>
                <span>任意 OpenAI / Anthropic 兼容接口</span>
              </span>
            </button>
          </div>
        </>
      ) : (
        <>
          <div className="chat-provider-picked">
            <span className="chat-provider-avatar">{picked === "custom" ? "+" : picked.name.slice(0, 1).toUpperCase()}</span>
            <span>{picked === "custom" ? "自定义服务" : picked.name}</span>
            <button className="btn btn-text" onClick={() => setPicked(null)}>更换</button>
          </div>
          <div className="chat-settings-form-grid">
            {picked === "custom" ? (
              <label className="form-row">
                <span className="form-label">名称</span>
                <input className="input" value={form.vendor} placeholder="my-ollama" onChange={(event) => setForm((current) => ({ ...current, vendor: event.target.value }))} />
              </label>
            ) : null}
            <label className="form-row">
              <span className="form-label">显示名称</span>
              <input className="input" value={form.label} placeholder="例如 阿里云百炼" onChange={(event) => setForm((current) => ({ ...current, label: event.target.value }))} />
            </label>
            <label className="form-row">
              <span className="form-label">API 地址</span>
              <input className="input" value={form.endpoint} placeholder="https://api.example.com/v1" onChange={(event) => setForm((current) => ({ ...current, endpoint: event.target.value }))} />
            </label>
            <label className="form-row">
              <span className="form-label">API Key</span>
              <input className="input" type="password" value={form.apiKey} placeholder="sk-…" onChange={(event) => setForm((current) => ({ ...current, apiKey: event.target.value }))} autoComplete="off" />
            </label>
            <label className="form-row">
              <span className="form-label">默认模型（可选）</span>
              <input className="input" value={form.model} placeholder="可留空，之后在模型列表里选" onChange={(event) => setForm((current) => ({ ...current, model: event.target.value }))} />
            </label>
          </div>
          <div className="hint">保存时面板会现场验证一次 Key；验证失败也会保存，稍后可在详情里重试。</div>
          <div className="dialog-actions">
            <button className="btn btn-text dim" disabled={busy} onClick={onClose}>取消</button>
            <button className="btn btn-text" disabled={busy || !form.apiKey.trim()} onClick={() => void create()}>
              {busy ? "验证并保存中…" : "添加"}
            </button>
          </div>
        </>
      )}
    </Dialog>
  );
}

function CapabilityDialog({
  open, account, model, run, onClose, onSaved,
}: {
  open: boolean;
  account: ChatAccountProfile | null;
  model: ChatModelInfo | null;
  run: Run;
  onClose: () => void;
  onSaved: (model: ChatModelInfo) => void;
}) {
  const [caps, setCaps] = useState(model?.capabilities ?? null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    setCaps(model?.capabilities ?? null);
  }, [model]);

  if (!model || !caps || !account) return null;

  const save = async (reset: boolean) => {
    setBusy(true);
    try {
      const next = await run(() => api.chat.saveCapability(account.name, reset
        ? { model: model.id, reset: true }
        : {
            model: model.id,
            vision: caps.vision,
            files: caps.files,
            reasoning: caps.reasoning,
            tools: caps.tools,
            maxContext: caps.maxContext,
            maxOutput: caps.maxOutput,
          }));
      toast("success", reset ? "已恢复默认能力" : "模型能力已保存");
      onSaved(next);
    } catch (error) {
      toast("error", `保存能力失败：${errMsg(error)}`);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open={open} title={`模型能力 · ${model.id}`} onClose={busy ? undefined : onClose}>
      <div className="chat-capability-grid">
        {(["vision", "files", "reasoning", "tools"] as const).map((key) => (
          <label className="chat-capability-toggle" key={key}>
            <input type="checkbox" checked={caps[key]}
              onChange={() => setCaps({ ...caps, [key]: !caps[key] })} />
            <span>{key === "vision" ? "图片" : key === "files" ? "原生文件（PDF）" : key === "reasoning" ? "思考" : "工具（仅标记）"}</span>
          </label>
        ))}
      </div>
      <div className="chat-settings-form-grid">
        <label className="form-row">
          <span className="form-label">上下文上限</span>
          <input className="input" type="number" value={caps.maxContext}
            onChange={(event) => setCaps({ ...caps, maxContext: Number(event.target.value) })} />
        </label>
        <label className="form-row">
          <span className="form-label">输出上限</span>
          <input className="input" type="number" value={caps.maxOutput}
            onChange={(event) => setCaps({ ...caps, maxOutput: Number(event.target.value) })} />
        </label>
      </div>
      <div className="chat-capability-source">
        当前来源：{capabilitySourceLabel(caps.source)}
        {caps.source === "builtin" && " · 按模型名从内置模型表自动判断，不用手改"}
        {caps.source === "unknown" && " · 模型名没匹配到内置表，能力默认全关，可在这里手动打开"}
        {caps.source === "manual" && " · 你手动改过的值会一直覆盖自动判断，直到「恢复默认」"}
      </div>
      <div className="hint">「原生文件」只在服务端支持 file/document 块时建议打开；关闭后 PDF/Office 以抽取文本发送。</div>
      <div className="dialog-actions">
        <button className="btn btn-text dim" disabled={busy} onClick={onClose}>取消</button>
        <button className="btn btn-text dim" disabled={busy} onClick={() => void save(true)}>恢复默认</button>
        <button className="btn btn-text" disabled={busy} onClick={() => void save(false)}>{busy ? "保存中…" : "保存"}</button>
      </div>
    </Dialog>
  );
}

function DefaultsPanel({
  settings, accounts, run, onSaved,
}: {
  settings: ChatSettings;
  accounts: ChatAccountProfile[];
  run: Run;
  onSaved: (settings: ChatSettings) => void;
}) {
  const [account, setAccount] = useState(settings.defaultAccount ?? "");
  const [model, setModel] = useState(settings.defaultModel ?? "");
  const [models, setModels] = useState<ChatModelInfo[]>([]);
  const [busy, setBusy] = useState(false);

  const selected = accounts.find((item) => item.name === account) ?? null;

  useEffect(() => {
    if (!selected) {
      setModels([]);
      return;
    }
    setModels(selected.modelList ?? []);
    void (async () => {
      try {
        const result = await run(() => api.chat.models(selected.name));
        setModels(result.models);
      } catch {
        // Keep the cached list.
      }
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selected?.name]);

  const save = async () => {
    setBusy(true);
    try {
      const next = await run(() => api.chat.updateSettings({
        defaultAccount: account || null,
        defaultModel: model || null,
      }));
      onSaved(next);
      toast("success", "默认模型已保存");
    } catch (error) {
      toast("error", `保存失败：${errMsg(error)}`);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="chat-settings-card">
      <div className="chat-settings-card-title">默认模型</div>
      <p className="chat-settings-desc">
        新会话与没有指定模型的助手会用它；会话里仍然可以临时切换。留空表示跟随第一个带 Key 的账户。
      </p>
      <div className="chat-settings-form-grid">
        <label className="form-row">
          <span className="form-label">默认账户</span>
          <select className="input" value={account} onChange={(event) => {
            setAccount(event.target.value);
            setModel("");
          }}>
            <option value="">跟随第一个可用账户</option>
            {accounts.map((item) => <option key={item.name} value={item.name}>{item.label || item.name}</option>)}
          </select>
        </label>
        <label className="form-row">
          <span className="form-label">默认模型</span>
          <input className="input" list="chat-default-models" value={model} placeholder="可留空"
            onChange={(event) => setModel(event.target.value)} />
          <datalist id="chat-default-models">
            {models.map((item) => <option key={item.id} value={item.id} />)}
          </datalist>
        </label>
      </div>
      <div className="chat-provider-actions">
        <button className="btn btn-raised" disabled={busy} onClick={() => void save()}>{busy ? "保存中…" : "保存"}</button>
      </div>
    </div>
  );
}

function DataPanel({
  settings, run, onSaved,
}: {
  settings: ChatSettings;
  run: Run;
  onSaved: (settings: ChatSettings) => void;
}) {
  const [form, setForm] = useState(settings);
  const [busy, setBusy] = useState(false);
  useEffect(() => setForm(settings), [settings]);

  const patch = <K extends keyof ChatSettings>(key: K, value: ChatSettings[K]) =>
    setForm((current) => ({ ...current, [key]: value }));

  const save = async () => {
    setBusy(true);
    try {
      const next = await run(() => api.chat.updateSettings({
        retention: form.retention,
        retentionDays: form.retentionDays,
        backupEnabled: form.backupEnabled,
        backupTime: form.backupTime,
        backupKeep: form.backupKeep,
        backupDir: form.backupDir,
        longTextTokens: form.longTextTokens,
        dailyTokenLimit: form.dailyTokenLimit,
      }));
      onSaved(next);
      toast("success", "数据设置已保存");
    } catch (error) {
      toast("error", `保存失败：${errMsg(error)}`);
    } finally {
      setBusy(false);
    }
  };

  const backupNow = async () => {
    setBusy(true);
    try {
      const entry = await run(() => api.chat.backupNow());
      toast("success", `备份完成：${entry.name}`);
      const next = await run(() => api.chat.settings());
      onSaved(next);
    } catch (error) {
      toast("error", `备份失败：${errMsg(error)}`);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="chat-settings-stack">
      <div className="chat-settings-card">
        <div className="chat-settings-card-title">保留策略</div>
        <div className="chat-settings-form-grid">
          <label className="form-row">
            <span className="form-label">保留</span>
            <select className="input" value={form.retention} onChange={(event) => patch("retention", event.target.value as ChatSettings["retention"])}>
              <option value="forever">永久保留</option>
              <option value="30d">30 天</option>
              <option value="90d">90 天</option>
              <option value="180d">180 天</option>
              <option value="365d">365 天</option>
              <option value="custom">自定义天数</option>
            </select>
          </label>
          {form.retention === "custom" && (
            <label className="form-row">
              <span className="form-label">天数</span>
              <input className="input" type="number" min={1} value={form.retentionDays}
                onChange={(event) => patch("retentionDays", Number(event.target.value))} />
            </label>
          )}
          <label className="form-row">
            <span className="form-label">长文本阈值（估算 tokens）</span>
            <input className="input" type="number" min={500} value={form.longTextTokens}
              onChange={(event) => patch("longTextTokens", Number(event.target.value))} />
          </label>
          <label className="form-row">
            <span className="form-label">每日 token 上限（0 = 不限制）</span>
            <input className="input" type="number" min={0} value={form.dailyTokenLimit}
              onChange={(event) => patch("dailyTokenLimit", Number(event.target.value))} />
          </label>
        </div>
      </div>

      <div className="chat-settings-card">
        <div className="chat-settings-card-title">备份</div>
        <div className="chat-settings-form-grid three">
          <label className="form-row">
            <span className="form-label">每日备份</span>
            <input type="checkbox" checked={form.backupEnabled} onChange={(event) => patch("backupEnabled", event.target.checked)} />
          </label>
          <label className="form-row">
            <span className="form-label">时间</span>
            <input className="input" value={form.backupTime} placeholder="03:17" onChange={(event) => patch("backupTime", event.target.value)} />
          </label>
          <label className="form-row">
            <span className="form-label">保留份数</span>
            <input className="input" type="number" min={1} max={100} value={form.backupKeep}
              onChange={(event) => patch("backupKeep", Number(event.target.value))} />
          </label>
        </div>
        <label className="form-row">
          <span className="form-label">目录</span>
          <input className="input" value={form.backupDir} onChange={(event) => patch("backupDir", event.target.value)} />
        </label>
        <div className="chat-provider-actions">
          <button className="btn btn-raised" disabled={busy} onClick={() => void save()}>{busy ? "保存中…" : "保存"}</button>
          <button className="btn btn-outline" disabled={busy} onClick={() => void backupNow()}>立即备份</button>
        </div>
        <div className="chat-backup-list">
          {form.backups.length === 0 ? (
            <div className="chat-provider-empty">还没有备份。</div>
          ) : form.backups.map((backup: ChatBackup) => (
            <div className="chat-backup-row" key={backup.name}>
              <span className="mono">{backup.name}</span>
              <span>{backup.sessions} 会话 / {backup.messages} 消息 / {backup.attachments} 附件</span>
              <span>{bytes(backup.bytes)}</span>
              <span className={`tag ${backup.complete ? "running" : "failed"}`}>{backup.complete ? "完整" : "失败"}</span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

function NetworkPanel({
  settings, run, onSaved,
}: {
  settings: ChatSettings;
  run: Run;
  onSaved: (settings: ChatSettings) => void;
}) {
  const [mode, setMode] = useState(settings.privateEndpoints);
  const [allowlist, setAllowlist] = useState(settings.privateAllowlist.join("\n"));
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    setMode(settings.privateEndpoints);
    setAllowlist(settings.privateAllowlist.join("\n"));
  }, [settings]);

  const save = async () => {
    setBusy(true);
    try {
      const next = await run(() => api.chat.updateSettings({
        privateEndpoints: mode,
        privateAllowlist: allowlist.split("\n").map((line) => line.trim()).filter(Boolean),
      }));
      onSaved(next);
      toast("success", "网络设置已保存");
    } catch (error) {
      toast("error", `保存失败：${errMsg(error)}`);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="chat-settings-card">
      <div className="chat-settings-card-title">私网 / loopback 端点</div>
      <p className="chat-settings-desc">
        默认允许本地模型（Ollama / LM Studio / vLLM）。切到严格模式后，私网地址必须写进 allowlist。
      </p>
      <label className="form-row">
        <span className="form-label">策略</span>
        <select className="input" value={mode} onChange={(event) => setMode(event.target.value as ChatSettings["privateEndpoints"])}>
          <option value="allow">允许</option>
          <option value="strict">严格：拦私网，只放行 allowlist</option>
        </select>
      </label>
      <label className="form-row">
        <span className="form-label">allowlist（每行一个主机或 .后缀）</span>
        <textarea className="input" rows={6} value={allowlist} onChange={(event) => setAllowlist(event.target.value)} />
      </label>
      <div className="hint">容器里的 <code>127.0.0.1</code> 不是宿主机；Ollama 请用 <code>host.docker.internal</code> 或 LAN 地址。</div>
      <div className="chat-provider-actions">
        <button className="btn btn-raised" disabled={busy} onClick={() => void save()}>{busy ? "保存中…" : "保存"}</button>
      </div>
    </div>
  );
}