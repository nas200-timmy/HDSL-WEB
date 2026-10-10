import { useEffect, useMemo, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { api } from "../api";
import { launchInstance, openDsh, stopInstance } from "../actions";
import { ConfirmDialog, Dialog } from "../components/Dialog";
import { InstanceIcon, StateBadge } from "../components/InstanceIcon";
import { ExportInstanceModal } from "../components/ExportInstanceModal";
import {
  AddCircleIcon,
  AddIcon,
  CloseIcon,
  DeleteIcon,
  EditIcon,
  FolderIcon,
  MoreVertIcon,
  Package2Icon,
  PublicIcon,
  RefreshIcon,
  RocketLaunchIcon,
  SearchIcon,
  SettingsFillIcon,
} from "../components/icons";
import { PopupItem, PopupMenu, PopupSep } from "../components/PopupMenu";
import { BrandsSection } from "../components/BrandsSection";
import { I18N } from "../i18n";
import { getSelectedInstanceId, setSelectedInstanceId, useSelectedInstanceId } from "../selection";
import { getState, refreshInstances, toast, useAppState } from "../store";
import type { Instance } from "../types";
import { errMsg, normState } from "../utils";

/** 实例列表页（InstancesPage.java 结构）。 */
export function InstancesPage() {
  const nav = useNavigate();
  const s = useAppState();
  const selectedId = useSelectedInstanceId();
  // 主页品牌卡「管理」跳进来时带 ?brand=kimi|opencode，品牌区自动切到对应页签
  const [searchParams] = useSearchParams();
  const brandTab = searchParams.get("brand");

  const [query, setQuery] = useState("");
  const [searchMode, setSearchMode] = useState(false);
  const [menuAnchor, setMenuAnchor] = useState<{ el: HTMLElement; inst: Instance } | null>(null);
  const [renameTarget, setRenameTarget] = useState<Instance | null>(null);
  const [renameValue, setRenameValue] = useState("");
  const [renameBusy, setRenameBusy] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<Instance | null>(null);
  const [deleteBusy, setDeleteBusy] = useState(false);
  const [exportTarget, setExportTarget] = useState<Instance | null>(null);

  useEffect(() => {
    void refreshInstances(!getState().instancesLoaded);
  }, []);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return s.instances;
    return s.instances.filter(
      (i) => i.name.toLowerCase().includes(q) || i.id.toLowerCase().includes(q),
    );
  }, [s.instances, query]);

  const running = s.instances.filter((i) => normState(i.state) === "RUNNING").length;

  const doRename = async () => {
    if (!renameTarget) return;
    const name = renameValue.trim();
    if (!name || name === renameTarget.name) {
      setRenameTarget(null);
      return;
    }
    setRenameBusy(true);
    try {
      await api.patchInstance(renameTarget.id, { name });
      toast("success", "实例已重命名");
      setRenameTarget(null);
      await refreshInstances(false);
    } catch (e) {
      toast("error", `重命名失败：${errMsg(e)}`);
    } finally {
      setRenameBusy(false);
    }
  };

  const doDelete = async () => {
    if (!deleteTarget) return;
    setDeleteBusy(true);
    try {
      await api.deleteInstance(deleteTarget.id);
      toast("success", I18N["dsh.instance.removed"].replace("%s", deleteTarget.id));
      if (getSelectedInstanceId() === deleteTarget.id) setSelectedInstanceId(null);
      setDeleteTarget(null);
      await refreshInstances(false);
    } catch (e) {
      toast("error", `删除失败：${errMsg(e)}`);
    } finally {
      setDeleteBusy(false);
    }
  };

  const rowAction = (inst: Instance) => {
    const st = normState(inst.state);
    if (st === "RUNNING" || st === "STARTING") void stopInstance(inst);
    else void launchInstance(inst, () => {});
  };

  return (
    <div className="page-with-sidebar">
      {/* 子侧栏：实例文件夹项 + 添加项；底部操作框（限高 144） */}
      <nav className="sidebar-sub">
        <div className="side-item" style={{ cursor: "default" }}>
          <span className="side-graphic">
            <FolderIcon size={20} />
          </span>
          <span className="side-text">
            <span className="side-title">instances</span>
            <span className="side-subtitle">服务端实例目录</span>
          </span>
          <button
            className="icon-btn on-variant ripple-host"
            style={{ width: 24, height: 24 }}
            title="实例文件夹由服务端管理"
            onClick={() => toast("info", "网页版使用服务端实例目录，无法移除")}
          >
            <CloseIcon size={14} />
          </button>
        </div>
        <button
          className="side-item ripple-host"
          onClick={() => toast("info", "网页版使用服务端实例目录，无需添加")}
        >
          <span className="side-graphic">
            <AddCircleIcon size={20} />
          </span>
          <span className="side-text">
            <span className="side-title">{I18N["dsh.directory.add"]}</span>
          </span>
        </button>
        <div className="sidebar-spacer" />
        <div className="side-actions">
            <button className="side-item ripple-host" onClick={() => nav("/instances/new")}>
              <span className="side-graphic">
                <AddIcon size={20} />
              </span>
              <span className="side-text">
                <span className="side-title">{I18N["dsh.instance.install"]}</span>
              </span>
            </button>
            <button className="side-item ripple-host" onClick={() => nav("/install/modpack")}>
              <span className="side-graphic">
                <Package2Icon size={20} />
              </span>
              <span className="side-text">
                <span className="side-title">{I18N["install.modpack"]}</span>
              </span>
            </button>
            <button className="side-item ripple-host" onClick={() => nav("/settings")}>
              <span className="side-graphic">
                <SettingsFillIcon size={20} />
              </span>
              <span className="side-text">
                <span className="side-title">{I18N["dsh.settings.global"]}</span>
              </span>
            </button>
        </div>
      </nav>

      <div className="page-content">
        {/* 工具栏：刷新/搜索（搜索态整行变输入框） */}
        {searchMode ? (
          <div className="card toolbar-row" style={{ flexWrap: "nowrap" }}>
            <SearchIcon size={18} style={{ color: "var(--monet-on-surface-variant)" }} />
            <input
              className="input"
              style={{ flex: 1 }}
              autoFocus
              placeholder={I18N["search"]}
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Escape") {
                  setQuery("");
                  setSearchMode(false);
                }
              }}
            />
            <button
              className="icon-btn on-variant ripple-host"
              onClick={() => {
                setQuery("");
                setSearchMode(false);
              }}
            >
              <CloseIcon size={18} />
            </button>
          </div>
        ) : (
          <div className="card toolbar-row">
            <button
              className="tool-btn ripple-host"
              disabled={s.instancesLoading}
              title={I18N["button.refresh"]}
              onClick={() => void refreshInstances(true)}
            >
              <RefreshIcon size={20} />
              {I18N["button.refresh"]}
            </button>
            <span style={{ fontSize: 12, color: "var(--monet-on-surface-variant)" }}>
              {s.instancesLoaded
                ? I18N["dsh.instance.count"].replace("%d", String(s.instances.length)) +
                  (running > 0 ? ` · ${running} 个运行中` : "")
                : "加载中…"}
            </span>
            <span className="spacer" />
            <button className="tool-btn ripple-host" title={I18N["search"]} onClick={() => setSearchMode(true)}>
              <SearchIcon size={20} />
              {I18N["search"]}
            </button>
          </div>
        )}

        {/* 实例列表 */}
        <div className="card" style={{ flex: 1, minHeight: 0, display: "flex", flexDirection: "column", padding: 0 }}>
          <div className="list-body scroll-hover">
            {filtered.length === 0 ? (
              <div className="empty-state">
                <span>
                  {query ? I18N["search.no_results_found"] : I18N["dsh.instance.empty"]}
                </span>
                {!query && (
                  <span className="empty-sub">{I18N["dsh.instance.empty.hint"]}</span>
                )}
              </div>
            ) : (
              filtered.map((inst, idx) => {
                const st = normState(inst.state);
                const selected = inst.id === selectedId;
                return (
                  <div key={inst.id}>
                    <div
                      className="tlli ripple-host"
                      style={{ cursor: "pointer", padding: "8px 12px" }}
                      onClick={() => {
                        setSelectedInstanceId(inst.id);
                        nav(`/instances/${inst.id}`);
                      }}
                    >
                      <label className="input-check" onClick={(e) => e.stopPropagation()}>
                        <input
                          type="radio"
                          name="instance-select"
                          checked={selected}
                          onChange={() => setSelectedInstanceId(inst.id)}
                        />
                      </label>
                      <InstanceIcon inst={inst} size={32} />
                      <span className="tlli-text">
                        <span className="tlli-title">
                          {inst.name}
                          <StateBadge state={inst.state} />
                        </span>
                        <span className="tlli-subtitle">
                          {inst.id} · {inst.version}
                          {st === "RUNNING" && inst.url ? ` · ${inst.url}` : ""}
                        </span>
                      </span>
                      <span className="row-actions" onClick={(e) => e.stopPropagation()}>
                        <button
                          className="icon-btn on-variant ripple-host"
                          title={st === "RUNNING" || st === "STARTING" ? I18N["dsh.stop"] : I18N["dsh.launch"]}
                          disabled={st === "INSTALLING" || st === "STOPPING"}
                          onClick={(e) => {
                            e.stopPropagation();
                            rowAction(inst);
                          }}
                        >
                          <RocketLaunchIcon size={18} />
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
                    {idx < filtered.length - 1 && <div className="divider" style={{ marginLeft: 12 }} />}
                  </div>
                );
              })
            )}
          </div>
        </div>

        {/* 品牌区：ZCode（实验）+ 第三方品牌（Kimi Code / OpenCode） */}
        <BrandsSection initialTab={brandTab ?? undefined} />
      </div>

      {/* 更多菜单（HMCL IconedMenuItem 风格） */}
      <PopupMenu
        open={menuAnchor !== null}
        anchor={menuAnchor?.el ?? null}
        onClose={() => setMenuAnchor(null)}
      >
        <PopupItem
          icon={<SettingsFillIcon size={16} />}
          onClick={() => {
            const inst = menuAnchor?.inst;
            setMenuAnchor(null);
            if (inst) nav(`/instances/${inst.id}`);
          }}
        >
          {I18N["dsh.instance.manage"]}
        </PopupItem>
        {menuAnchor && normState(menuAnchor.inst.state) === "RUNNING" && (
          <PopupItem
            icon={<PublicIcon size={16} />}
            onClick={() => {
              const inst = menuAnchor.inst;
              setMenuAnchor(null);
              openDsh(inst.id);
            }}
          >
            {I18N["dsh.instance.open_browser"]}
          </PopupItem>
        )}
        <PopupItem
          icon={<EditIcon size={16} />}
          onClick={() => {
            const inst = menuAnchor?.inst;
            setMenuAnchor(null);
            if (inst) {
              setRenameValue(inst.name);
              setRenameTarget(inst);
            }
          }}
        >
          {I18N["instance.manage.rename"]}
        </PopupItem>
        <PopupItem
          icon={<Package2Icon size={16} />}
          onClick={() => {
            const inst = menuAnchor?.inst;
            setMenuAnchor(null);
            if (inst) setExportTarget(inst);
          }}
        >
          {I18N["modpack.export"]}
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
          {I18N["dsh.instance.remove"]}
        </PopupItem>
      </PopupMenu>

      {/* 重命名 */}
      <Dialog
        open={renameTarget !== null}
        title={I18N["instance.manage.rename"]}
        onClose={() => !renameBusy && setRenameTarget(null)}
      >
        <div className="form-row">
          <span className="form-label">{I18N["dsh.instance.name"]}</span>
          <input
            className="input"
            autoFocus
            value={renameValue}
            onChange={(e) => setRenameValue(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && !renameBusy) void doRename();
            }}
          />
        </div>
        <div className="dialog-actions">
          <button className="btn btn-text dim" onClick={() => setRenameTarget(null)} disabled={renameBusy}>
            {I18N["button.cancel"]}
          </button>
          <button className="btn btn-text" onClick={() => void doRename()} disabled={renameBusy}>
            {I18N["button.ok"]}
          </button>
        </div>
      </Dialog>

      {/* 删除确认 */}
      <ConfirmDialog
        open={deleteTarget !== null}
        title={I18N["dsh.instance.remove"]}
        message={I18N["dsh.instance.remove.confirm"].replace("%s", deleteTarget?.id ?? "")}
        confirmLabel={I18N["button.delete"]}
        danger
        busy={deleteBusy}
        onConfirm={() => void doDelete()}
        onCancel={() => setDeleteTarget(null)}
      />

      {exportTarget && (
        <ExportInstanceModal
          instance={exportTarget}
          open
          onClose={() => setExportTarget(null)}
        />
      )}
    </div>
  );
}
