import { useEffect, useState } from "react";
import { api } from "../api";
import { CollapsibleNote } from "./CollapsibleNote";
import { ConfirmDialog, Dialog } from "./Dialog";
import { PopupItem, PopupMenu, PopupSep } from "./PopupMenu";
import {
  CloseIcon,
  DeleteIcon,
  EditIcon,
  ExtensionIcon,
  FormatListBulletedIcon,
  MoreVertIcon,
  PublicIcon,
  RocketLaunchIcon,
  TerminalIcon,
} from "./icons";
import { I18N } from "../i18n";
import { getSelectedInstanceId, setSelectedInstanceId, useSelectedInstanceId } from "../selection";
import { refreshExternal, refreshZcode, toast, useAppState } from "../store";
import type { ExternalInstance, InstancePlugins } from "../types";
import { errMsg } from "../utils";
import { TerminalWindow, type TerminalTargetInfo } from "./TerminalWindow";

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
///
/// 品牌实例层（`BrandsView`）里它是 zcode 分支的「实例」卡：发行包检测/构建在那一层的
/// 版本卡上（内置弹窗 ZcodeBuildButton 也在那边），这里只管实例：
/// 创建（要填端点与密钥，所以用弹窗而不是内联的创建行）/ 编辑 / 日志 / 启停 / 删除。
/// 导出签名保持无参：它由品牌层整块渲染，不需要外部传参。
export function ZcodeSection() {
  const s = useAppState();
  const selectedId = useSelectedInstanceId();
  const [createOpen, setCreateOpen] = useState(false);
  const [editTarget, setEditTarget] = useState<ExternalInstance | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<ExternalInstance | null>(null);
  const [logTarget, setLogTarget] = useState<ExternalInstance | null>(null);
  const [logLines, setLogLines] = useState<string[]>([]);
  const [pluginsTarget, setPluginsTarget] = useState<ExternalInstance | null>(null);
  const [menuAnchor, setMenuAnchor] = useState<{ el: HTMLElement; inst: ExternalInstance } | null>(null);
  const [busy, setBusy] = useState(false);
  const [form, setForm] = useState({ name: "", baseUrl: DEFAULT_BASE_URL, apiKey: "" });
  const [formError, setFormError] = useState<string | null>(null);
  const [terminalTarget, setTerminalTarget] = useState<TerminalTargetInfo | null>(null);
  /// Web Shell 总开关（开发者模式里控制）与 HTTPS 状态；任一不满足就不显示终端入口
  const [webShellEnabled, setWebShellEnabled] = useState(false);

  useEffect(() => {
    let alive = true;
    api
      .developerStatus()
      .then((status) => {
        if (alive) setWebShellEnabled(status.webShellEnabled && status.https);
      })
      .catch(() => {
        if (alive) setWebShellEnabled(false);
      });
    return () => {
      alive = false;
    };
  }, []);

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

  const running = instances.filter((i) => i.state === "running").length;

  const closeForm = () => {
    if (busy) return;
    setCreateOpen(false);
    setEditTarget(null);
  };

  const openCreate = () => {
    // 一个实例都没有时预填品牌名（与品牌实例卡的创建行同规则），点一下保存就有实例
    setForm({
      name: instances.length === 0 ? I18N["dsh.brand.zcode.short"] : "",
      baseUrl: DEFAULT_BASE_URL,
      apiKey: "",
    });
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
      window.open(r.url, "_blank", "noopener");  // r.url 已带挂载点前缀
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
      // 删掉的正好是「当前实例」时把选择清掉，免得启动面板指向一个不存在的实例
      if (getSelectedInstanceId() === deleteTarget.id) setSelectedInstanceId(null);
      setDeleteTarget(null);
      await refreshExternal();
    } catch (e) {
      toast("error", `删除失败：${errMsg(e)}`);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div
      className="card"
      style={{ flex: 1, minHeight: 0, display: "flex", flexDirection: "column", padding: 0 }}
    >
      <div className="toolbar-row" style={{ padding: "6px 12px", flexShrink: 0 }}>
        <span style={{ fontSize: 13, fontWeight: 600, flex: "none" }}>
          {I18N["dsh.brand.section.instances"]}
        </span>
        <span style={{ fontSize: 12, color: "var(--monet-on-surface-variant)", whiteSpace: "nowrap" }}>
          {instances.length} 个实例{running > 0 ? ` · ${running} 个运行中` : ""}
        </span>
        <span className="spacer" />
        <button className="btn btn-outline ripple-host" onClick={openCreate}>
          添加 ZCode 实例
        </button>
      </div>

      {/* 移动端长说明收成一行 + 详情展开；桌面端原样 */}
      <div style={{ flexShrink: 0, padding: "0 12px 6px" }}>
        <CollapsibleNote
          summary={
            <b style={{ color: "var(--monet-on-surface)" }}>
              纯实现性验证功能：不保证可用，也不承诺与 dsh 同等的功能与兼容
            </b>
          }
        >
          <div style={{ fontSize: 12, lineHeight: 1.7, color: "var(--monet-on-surface-variant)" }}>
            <b style={{ color: "var(--monet-on-surface)" }}>
              纯实现性验证功能：不保证可用，也不承诺与 dsh 同等的功能与兼容
            </b>
            ——能不能跑通取决于 ZCode 上游。实例只绑回环、经面板反代到 <code>/i/&lt;id&gt;/</code>（同端口同证书、
            同样要登录），凭证以明文保存在实例目录。
          </div>
        </CollapsibleNote>
      </div>

      <div className="list-body scroll-hover">
        {instances.length === 0 ? (
          <div className="empty-state" style={{ padding: "6px 12px" }}>
            <span>还没有 ZCode 实例</span>
            <span className="empty-sub">发行包要在上面的「版本」卡里构建或放置</span>
          </div>
        ) : (
          instances.map((inst, idx) => (
            <div key={inst.id}>
              <div className="tlli" style={{ padding: "6px 12px" }}>
                {/* 「当前实例」与 dsh 实例列表同款：启动面板与主侧栏都认这个选择 */}
                <label className="input-check">
                  <input
                    type="radio"
                    name="brand-instance-zcode"
                    checked={inst.id === selectedId}
                    onChange={() => setSelectedInstanceId(inst.id)}
                  />
                </label>
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
                    title="打开终端（ZCode TUI）"
                    disabled={busy || !webShellEnabled || !s.zcodeDist?.present}
                    onClick={() => setTerminalTarget({ kind: "zcode", id: inst.id, name: inst.name })}
                  >
                    <TerminalIcon size={18} />
                  </button>
                  <button
                    className="icon-btn on-variant ripple-host"
                    title="在新标签页打开（经面板 /i/<id>/ 反代）"
                    disabled={inst.state !== "running"}
                    onClick={() => void openInBrowser(inst)}
                  >
                    <PublicIcon size={18} />
                  </button>
                  <button
                    className="icon-btn on-variant ripple-host"
                    title={I18N["dsh.instance.menu"]}
                    onClick={(e) => setMenuAnchor({ el: e.currentTarget, inst })}
                  >
                    <MoreVertIcon size={18} />
                  </button>
                </span>
              </div>
              {idx < instances.length - 1 && <div className="divider" style={{ marginLeft: 12 }} />}
            </div>
          ))
        )}
      </div>

      {/* 更多菜单：行里只留启停与打开（手机上一排按钮挤不下），其余进菜单 */}
      <PopupMenu
        open={menuAnchor !== null}
        anchor={menuAnchor?.el ?? null}
        onClose={() => setMenuAnchor(null)}
      >
        <PopupItem
          icon={<EditIcon size={16} />}
          onClick={() => {
            const inst = menuAnchor?.inst;
            setMenuAnchor(null);
            if (inst) openEdit(inst);
          }}
        >
          编辑（名称 / 端点 / 密钥）
        </PopupItem>
        <PopupItem
          icon={<ExtensionIcon size={16} />}
          onClick={() => {
            const inst = menuAnchor?.inst;
            setMenuAnchor(null);
            if (inst) setPluginsTarget(inst);
          }}
        >
          {I18N["dsh.brand.instance.plugins"]}
        </PopupItem>
        <PopupItem
          icon={<FormatListBulletedIcon size={16} />}
          onClick={() => {
            const inst = menuAnchor?.inst;
            setMenuAnchor(null);
            if (inst) void showLogs(inst);
          }}
        >
          日志
        </PopupItem>
        <PopupSep />
        <PopupItem
          danger
          icon={<DeleteIcon size={16} />}
          onClick={() => {
            const inst = menuAnchor?.inst;
            setMenuAnchor(null);
            if (inst) setDeleteTarget(inst);
          }}
        >
          删除
        </PopupItem>
      </PopupMenu>

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

      {/* 插件通道：形状与品牌实例卡一致（弹窗各持一份，免得两个组件文件互相引用） */}
      <ZcodePluginsDialog inst={pluginsTarget} onClose={() => setPluginsTarget(null)} />

      {/* 原地终端：与品牌实例共用 TerminalWindow，协议同为 docs/terminal-protocol.md 的 v1 */}
      <TerminalWindow target={terminalTarget} onClose={() => setTerminalTarget(null)} />

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
    </div>
  );
}

/// ZCode 的插件弹窗：走 /api/zcode/instances/<id>/plugins，回答恒为
/// `{supported: false, plugins: []}`——如实画出来，后端填上 plugins 就按数据列。
function ZcodePluginsDialog({
  inst,
  onClose,
}: {
  inst: ExternalInstance | null;
  onClose: () => void;
}) {
  const [data, setData] = useState<InstancePlugins | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!inst) return;
    let alive = true;
    setData(null);
    setError(null);
    api
      .zcodePlugins(inst.id)
      .then((d) => {
        if (alive) setData(d);
      })
      .catch((e) => {
        if (alive) setError(errMsg(e));
      });
    return () => {
      alive = false;
    };
  }, [inst]);

  return (
    <Dialog
      open={inst !== null}
      title={`${I18N["dsh.brand.instance.plugins"]} · ${inst?.name ?? ""}`}
      onClose={onClose}
    >
      {error ? (
        <div className="hint error">{error}</div>
      ) : data && data.plugins.length > 0 ? (
        <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
          {data.plugins.map((p) => (
            <div key={p.name} className="toolbar-row" style={{ padding: 0 }}>
              <span style={{ fontSize: 13 }}>{p.name}</span>
              {p.version && <span className="tag plain">{p.version}</span>}
              <span className={`tag ${p.enabled ? "running" : "plain"}`}>
                {p.enabled ? I18N["button.enable"] : I18N["button.disable"]}
              </span>
            </div>
          ))}
        </div>
      ) : data?.supported ? (
        <p style={{ lineHeight: 1.7 }}>{I18N["dsh.brand.plugins.empty"]}</p>
      ) : data ? (
        <p style={{ lineHeight: 1.7 }}>{I18N["dsh.brand.plugins.unsupported"]}</p>
      ) : (
        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <span className="spinner" />
          <span>正在读取插件通道…</span>
        </div>
      )}
      <div className="dialog-actions">
        <button className="btn btn-text" onClick={onClose}>
          {I18N["button.ok"]}
        </button>
      </div>
    </Dialog>
  );
}