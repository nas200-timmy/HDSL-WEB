import { useEffect, useState } from "react";
import { api } from "../api";
import { CollapsibleNote } from "./CollapsibleNote";
import { ConfirmDialog, Dialog } from "./Dialog";
import { ZcodeBuildButton } from "./ZcodeBuildDialog";
import {
  CloseIcon,
  DeleteIcon,
  EditIcon,
  InfoIcon,
  PublicIcon,
  RefreshIcon,
  RocketLaunchIcon,
} from "./icons";
import { refreshExternal, refreshZcode, toast, useAppState } from "../store";
import type { ExternalInstance } from "../types";
import { errMsg } from "../utils";

const DEFAULT_BASE_URL = "https://api.openai.com/v1";

const STATE_TEXT: Record<string, string> = {
  created: "未启动",
  starting: "启动中",
  running: "运行中",
  stopped: "已停止",
  error: "出错",
};

const STATE_TAG: Record<string, string> = {
  starting: "running",
  running: "running",
  error: "failed",
};

/// ZCode（实验性）：与 dsh 实例完全隔离的独立品牌分区。
/// 纯实现性验证功能——不保证可用、不承诺兼容，定位与限制见 docs/zcode-experimental.md。
export function ZcodeSection() {
  const s = useAppState();
  const [createOpen, setCreateOpen] = useState(false);
  const [editTarget, setEditTarget] = useState<ExternalInstance | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<ExternalInstance | null>(null);
  const [logTarget, setLogTarget] = useState<ExternalInstance | null>(null);
  const [logLines, setLogLines] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [form, setForm] = useState({ name: "", baseUrl: DEFAULT_BASE_URL, apiKey: "" });
  const [formError, setFormError] = useState<string | null>(null);

  useEffect(() => {
    void refreshZcode();
  }, []);

  // 实例状态唯一来源是 store.external；这里只在挂载时刷新一次，外加
  // 启动中的实例每 1.5 秒跟一次（ZCode 冷启动要等就绪行）。
  const instances = s.external.filter((e) => e.brand === "zcode");
  useEffect(() => {
    void refreshExternal();
    void refreshZcode();
  }, []);
  useEffect(() => {
    if (!instances.some((i) => i.state === "starting")) return;
    const timer = setInterval(() => void refreshExternal(), 1500);
    return () => clearInterval(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [s.external]);

  const dist = s.zcodeDist;
  const running = instances.filter((i) => i.state === "running").length;

  const closeForm = () => {
    if (busy) return;
    setCreateOpen(false);
    setEditTarget(null);
  };

  const openCreate = () => {
    setForm({ name: "", baseUrl: DEFAULT_BASE_URL, apiKey: "" });
    setFormError(null);
    setCreateOpen(true);
  };

  const openEdit = (inst: ExternalInstance) => {
    setForm({ name: inst.name, baseUrl: inst.baseUrl ?? DEFAULT_BASE_URL, apiKey: "" });
    setFormError(null);
    setEditTarget(inst);
  };

  const validate = (): boolean => {
    if (!form.name.trim()) {
      setFormError("请填写名称");
      return false;
    }
    if (form.apiKey.trim() && !form.baseUrl.trim()) {
      setFormError("填写 API Key 时必须同时填写 API Base URL");
      return false;
    }
    return true;
  };

  const submitCreate = async () => {
    if (!validate()) return;
    setBusy(true);
    try {
      await api.createZcodeInstance({
        name: form.name.trim(),
        baseUrl: form.baseUrl.trim(),
        apiKey: form.apiKey.trim() || undefined,
      });
      toast("success", "ZCode 实例已创建（实验性功能，不保证可用）");
      setCreateOpen(false);
      await refreshExternal();
    } catch (e) {
      toast("error", `创建失败：${errMsg(e)}`);
    } finally {
      setBusy(false);
    }
  };

  const submitEdit = async () => {
    if (!editTarget || !validate()) return;
    setBusy(true);
    try {
      await api.patchZcodeInstance(editTarget.id, {
        name: form.name.trim(),
        baseUrl: form.baseUrl.trim(),
        // 留空 = 不修改已保存的 Key（后端只在字段存在时覆盖）
        ...(form.apiKey.trim() ? { apiKey: form.apiKey.trim() } : {}),
      });
      toast("success", "已保存");
      setEditTarget(null);
      await refreshExternal();
    } catch (e) {
      toast("error", `保存失败：${errMsg(e)}`);
    } finally {
      setBusy(false);
    }
  };

  const launch = async (inst: ExternalInstance) => {
    setBusy(true);
    try {
      const r = await api.launchZcode(inst.id);
      if (r.state === "error") toast("error", `启动失败：${r.error ?? "未知原因"}`);
      else toast("success", "已启动（实验性功能，不保证可用）");
      await refreshExternal();
    } catch (e) {
      toast("error", `启动失败：${errMsg(e)}`);
    } finally {
      setBusy(false);
    }
  };

  const stop = async (inst: ExternalInstance) => {
    try {
      await api.stopZcode(inst.id);
      toast("info", "已停止");
      await refreshExternal();
    } catch (e) {
      toast("error", `停止失败：${errMsg(e)}`);
    }
  };

  const openInBrowser = async (inst: ExternalInstance) => {
    try {
      const r = await api.zcodeOpen(inst.id);
      window.open(r.url, "_blank", "noopener");
    } catch (e) {
      toast("error", `打开失败：${errMsg(e)}`);
    }
  };
  const showLogs = async (inst: ExternalInstance) => {
    try {
      const r = await api.zcodeLogs(inst.id, 300);
      setLogLines(r.lines);
      setLogTarget(inst);
    } catch (e) {
      toast("error", `读取日志失败：${errMsg(e)}`);
    }
  };

  const remove = async () => {
    if (!deleteTarget) return;
    setBusy(true);
    try {
      await api.deleteZcodeInstance(deleteTarget.id);
      toast("success", "已删除");
      setDeleteTarget(null);
      await refreshExternal();
    } catch (e) {
      toast("error", `删除失败：${errMsg(e)}`);
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <div className="section-caption">
        <InfoIcon size={16} />
        ZCode（实验性）
      </div>
      <div className="card" style={{ flexShrink: 0, display: "flex", flexDirection: "column", maxHeight: 330 }}>
        {/* 移动端长说明收成一行 + 详情展开；桌面端原样 */}
        <CollapsibleNote
          summary={
            <b style={{ color: "var(--monet-on-surface)" }}>
              纯实现性验证功能：不保证可用，也不承诺与 dsh 同等的功能与兼容
            </b>
          }
        >
          <div style={{ padding: "8px 12px 4px", fontSize: 12, lineHeight: 1.7, color: "var(--monet-on-surface-variant)" }}>
            <b style={{ color: "var(--monet-on-surface)" }}>
              纯实现性验证功能：不保证可用，也不承诺与 dsh 同等的功能与兼容
            </b>
            ——能不能跑通取决于 ZCode 上游。实例只绑回环、经面板反代到 <code>/i/&lt;id&gt;/</code>（同端口同证书、
            同样要登录），凭证以明文保存在实例目录。
          </div>
        </CollapsibleNote>

        <div className="toolbar-row" style={{ padding: "0 12px 6px" }}>
          <span style={{ fontSize: 12, color: "var(--monet-on-surface-variant)" }}>
            {dist === null
              ? "检测中…"
              : dist.present
                ? `发行包：${dist.version ?? "未知版本"} · ${dist.path}`
                : "未检测到 ZCode 发行包"}
            {instances.length > 0 ? ` · ${instances.length} 个实例` : ""}
            {running > 0 ? ` · ${running} 个运行中` : ""}
          </span>
          <span className="spacer" />
          <ZcodeBuildButton />
          <button className="tool-btn ripple-host" disabled={s.zcodeLoading} onClick={() => void refreshZcode(true)}>
            <RefreshIcon size={18} />
            刷新
          </button>
        </div>

        {dist !== null && !dist.present && (
          <div className="empty-state" style={{ padding: "6px 12px" }}>
            <span>
              把自建的 ZCode 发行包放到 <code>{dist.path}</code>（需含 bin/zcode.mjs）
            </span>
            <span className="empty-sub">或用 HDSL_ZCODE_PACKAGE 指向别处；构建方法见 docs/zcode-experimental.md</span>
          </div>
        )}

        <div className="list-body scroll-hover">
          {instances.length === 0 ? (
            <div className="empty-state" style={{ padding: "6px 12px" }}>
              <span>还没有 ZCode 实例</span>
            </div>
          ) : (
            instances.map((inst, idx) => (
              <div key={inst.id}>
                <div className="tlli" style={{ padding: "6px 12px" }}>
                  <span className="tlli-text">
                    <span className="tlli-title">
                      {inst.name}
                      <span className={`tag ${STATE_TAG[inst.state] ?? "plain"}`}>
                        {STATE_TEXT[inst.state] ?? inst.state}
                      </span>
                      {!inst.hasApiKey && <span className="tag plain">无 API Key</span>}
                    </span>
                    <span className="tlli-subtitle">
                      {inst.id}
                      {inst.state === "running" ? " · 经面板反代打开" : ""}
                      {inst.state === "error" && inst.error ? ` · ${inst.error}` : ""}
                    </span>
                  </span>
                  <span className="row-actions">
                    <button
                      className="icon-btn on-variant ripple-host"
                      title={inst.state === "running" ? "停止" : "启动"}
                      disabled={busy || inst.state === "starting"}
                      onClick={() => (inst.state === "running" ? void stop(inst) : void launch(inst))}
                    >
                      {inst.state === "running" ? <CloseIcon size={18} /> : <RocketLaunchIcon size={18} />}
                    </button>
                    <button
                      className="icon-btn on-variant ripple-host"
                      title="在新标签页打开（经面板 /i/<id>/ 反代）"
                      disabled={inst.state !== "running"}
                      onClick={() => void openInBrowser(inst)}
                    >
                      <PublicIcon size={18} />
                    </button>
                    <button className="icon-btn on-variant ripple-host" title="日志" onClick={() => void showLogs(inst)}>
                      <InfoIcon size={18} />
                    </button>
                    <button className="icon-btn on-variant ripple-host" title="编辑" onClick={() => openEdit(inst)}>
                      <EditIcon size={18} />
                    </button>
                    <button
                      className="icon-btn on-variant ripple-host"
                      title="删除"
                      disabled={busy || inst.state === "running" || inst.state === "starting"}
                      onClick={() => setDeleteTarget(inst)}
                    >
                      <DeleteIcon size={18} />
                    </button>
                  </span>
                </div>
                {idx < instances.length - 1 && <div className="divider" style={{ marginLeft: 12 }} />}
              </div>
            ))
          )}
        </div>

        <div style={{ display: "flex", justifyContent: "flex-end", padding: 8 }}>
          <button className="btn btn-outline ripple-host" onClick={openCreate}>
            添加 ZCode 实例
          </button>
        </div>
      </div>

      {/* 新建 / 编辑 */}
      <Dialog
        open={createOpen || editTarget !== null}
        title={editTarget ? "编辑 ZCode 实例" : "添加 ZCode 实例（实验性）"}
        onClose={closeForm}
      >
        <div className="form-row">
          <span className="form-label">名称</span>
          <input
            className="input"
            autoFocus
            value={form.name}
            onChange={(e) => setForm((f) => ({ ...f, name: e.target.value }))}
          />
        </div>
        <div className="form-row">
          <span className="form-label">API Base URL</span>
          <input
            className="input"
            value={form.baseUrl}
            onChange={(e) => setForm((f) => ({ ...f, baseUrl: e.target.value }))}
          />
        </div>
        <div className="form-row">
          <span className="form-label">API Key</span>
          <input
            className="input"
            type="password"
            placeholder={
              editTarget
                ? editTarget.hasApiKey
                  ? "留空则不修改（已设置）"
                  : "留空则不注入"
                : "选填；明文保存并写入实例目录"
            }
            value={form.apiKey}
            onChange={(e) => setForm((f) => ({ ...f, apiKey: e.target.value }))}
          />
        </div>
        {formError && <div className="form-error">{formError}</div>}
        <p style={{ fontSize: 12, color: "var(--monet-on-surface-variant)", padding: "4px 8px 0", lineHeight: 1.6 }}>
          保存时会把凭证写入该实例的 <code>.zcode/v2/provider_config.json</code>（ZCode 的个人供应商配置）。
          此格式取自 ZCode 源码（v3.14.3），上游改动后可能失效——按实验性功能对待。
        </p>
        <div className="dialog-actions">
          <button className="btn btn-text dim" disabled={busy} onClick={closeForm}>
            取消
          </button>
          <button className="btn btn-text" disabled={busy} onClick={() => void (editTarget ? submitEdit() : submitCreate())}>
            保存
          </button>
        </div>
      </Dialog>

      {/* 日志 */}
      <Dialog open={logTarget !== null} title={`日志 · ${logTarget?.name ?? ""}`} wide onClose={() => setLogTarget(null)}>
        <pre style={{ maxHeight: 360, overflow: "auto", fontSize: 12, lineHeight: 1.5, margin: 0 }}>
          {logLines.length === 0 ? "（暂无日志）" : logLines.join("\n")}
        </pre>
        <div className="dialog-actions">
          <button className="btn btn-text dim" onClick={() => setLogTarget(null)}>
            关闭
          </button>
          <button className="btn btn-text" onClick={() => logTarget && void showLogs(logTarget)}>
            刷新
          </button>
        </div>
      </Dialog>

      <ConfirmDialog
        open={deleteTarget !== null}
        title="删除 ZCode 实例"
        message={`删除「${deleteTarget?.name ?? ""}」？实例目录（含会话数据与凭证）会一并删除。`}
        confirmLabel="删除"
        danger
        busy={busy}
        onConfirm={() => void remove()}
        onCancel={() => setDeleteTarget(null)}
      />
    </>
  );
}
