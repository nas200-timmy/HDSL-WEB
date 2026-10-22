import { useEffect, useMemo, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { api } from "../api";
import { launchInstance, openDsh, stopInstance } from "../actions";
import { ConfirmDialog, Dialog } from "../components/Dialog";
import { InstanceIcon, StateBadge } from "../components/InstanceIcon";
import { ExportInstanceModal } from "../components/ExportInstanceModal";
import { TerminalWindow, type TerminalTargetInfo } from "../components/TerminalWindow";
import {
  AddCircleIcon,
  AddIcon,
  CloseIcon,
  DeleteIcon,
  DeployedCodeIcon,
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
import { BrandsView, fallbackBrand, isBrandKey, type BrandKey } from "../components/BrandsView";
import { I18N } from "../i18n";
import { getSelectedInstanceId, setSelectedInstanceId, useSelectedInstanceId } from "../selection";
import { getState, refreshExternal, refreshInstances, toast, useAppState } from "../store";
import type { Instance } from "../types";
import { errMsg, normState } from "../utils";
import { withBase } from "../base";

/** 实例列表页（InstancesPage.java 结构）。
 *
 *  内容区是**原地切换**的两层：左侧子侧栏选「instances」= dsh 实例列表，
 *  选「品牌实例」= 品牌实例视图（BrandsView）。切换只换查询串（`?view=brands&brand=kimi`），
 *  页面不跳转、不重挂载——品牌实例的一切操作都在这一页里完成。 */
export function InstancesPage() {
  const nav = useNavigate();
  const s = useAppState();
  const selectedId = useSelectedInstanceId();
  const [searchParams, setSearchParams] = useSearchParams();

  // 视图与品牌都从查询串来：`?view=brands` 开品牌视图；带 `?brand=` 的旧链接
  // （以及 /brands/<品牌> 的重定向）一律按品牌视图处理，不再跳第二层页面。
  const brandParam = searchParams.get("brand");
  const brandView = searchParams.get("view") === "brands" || brandParam != null;
  const activeBrand: BrandKey = isBrandKey(brandParam) ? brandParam : fallbackBrand(s.external);

  // 品牌视图缺省/非法品牌：等第一份跨品牌快照落地再落到「第一个有实例的品牌」，
  // 顺便把结果写进 URL（否则刷新一次又会跳回 zcode）
  const [externalLoaded, setExternalLoaded] = useState(false);
  useEffect(() => {
    void refreshExternal().then(() => setExternalLoaded(true));
  }, []);
  useEffect(() => {
    if (!brandView || !externalLoaded || isBrandKey(brandParam)) return;
    // 用 getState 读最新快照，别把这个 effect 挂到每次轮询上
    setSearchParams(
      { view: "brands", brand: fallbackBrand(getState().external) },
      { replace: true },
    );
  }, [brandView, brandParam, externalLoaded, setSearchParams]);

  const showBrands = (brand: BrandKey) => setSearchParams({ view: "brands", brand });
  const showDsh = () => setSearchParams({});

  const [query, setQuery] = useState("");
  const [searchMode, setSearchMode] = useState(false);
  const [menuAnchor, setMenuAnchor] = useState<{ el: HTMLElement; inst: Instance } | null>(null);
  const [terminalTarget, setTerminalTarget] = useState<TerminalTargetInfo | null>(null);
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
      {/* 子侧栏：两部分各是一次原地切换——「instances」（dsh 实例列表）与「品牌实例」。
          底部操作框（限高 144）。分区标题自带的 1px 底线就是两层之间的分割线。 */}
      <nav className="sidebar-sub">
        <div className="side-category">{I18N["dsh.brand.side.category.dsh"]}</div>
        <div
          className={`side-item ripple-host${brandView ? "" : " selected"}`}
          style={{ cursor: "pointer" }}
          role="button"
          onClick={showDsh}
        >
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
            onClick={(e) => {
              e.stopPropagation();
              toast("info", "网页版使用服务端实例目录，无法移除");
            }}
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
        {/* 第三方品牌实例：同一页内的第二层视图，点它右侧内容区原地换成品牌区
            （不再跳 /brands）。桌面靠 side-category 的 1px 底线分割，移动端靠
            .sidebar-sub > div + div 的左边线——两边都不额外写 CSS */}
        <div className="side-category">{I18N["dsh.brand.side.category"]}</div>
        <button
          className={`side-item ripple-host${brandView ? " selected" : ""}`}
          onClick={() => showBrands(activeBrand)}
        >
          <span className="side-graphic">
            <PublicIcon size={20} />
          </span>
          <span className="side-text">
            <span className="side-title">{I18N["dsh.brand.page.title"]}</span>
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
        {brandView ? (
          /* 品牌实例视图：原地换内容（不跳页、不重挂载页面） */
          <BrandsView brand={activeBrand} onSelect={showBrands} />
        ) : (
        <>
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
                          {st === "RUNNING" && inst.url ? ` · ${withBase(inst.url)}` : ""}
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
                          title={I18N["terminal.open"]}
                          disabled={st === "INSTALLING"}
                          onClick={(e) => {
                            e.stopPropagation();
                            setTerminalTarget({ kind: "dsh", id: inst.id, name: inst.name });
                          }}
                        >
                          <DeployedCodeIcon size={18} />
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
        </>
        )}
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

      <TerminalWindow target={terminalTarget} onClose={() => setTerminalTarget(null)} />
    </div>
  );
}
