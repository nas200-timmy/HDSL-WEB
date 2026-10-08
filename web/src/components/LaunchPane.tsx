import { useMemo, useState, type ReactNode } from "react";
import { useNavigate } from "react-router-dom";
import { launchBrandInstance, launchInstance, stopBrandInstance, stopInstance } from "../actions";
import { I18N } from "../i18n";
import { setSelectedInstanceId, useSelectedInstanceId } from "../selection";
import { useAppState } from "../store";
import type { ExternalInstance, Instance } from "../types";
import { normState } from "../utils";
import { ArrowDropUpIcon } from "./icons";
import { PopupItem, PopupMenu, PopupSep } from "./PopupMenu";
import { InstanceGraphic } from "./SideBar";

function actionLabel(state: string): string {
  switch (state) {
    case "STARTING":
      return I18N["dsh.launch.launching"];
    case "RUNNING":
      return I18N["dsh.stop"];
    case "STOPPING":
      return I18N["dsh.stopping"];
    default:
      return I18N["dsh.launch"];
  }
}

/// 主按钮与实例选择菜单统一用的"当前实例"形状（dsh 实例或跨品牌外部实例）
export interface LaunchEntry {
  id: string;
  name: string;
  /** 已归一化（大写）：RUNNING / STARTING / STOPPED … */
  state: string;
  kind: "dsh" | "kimi" | "opencode" | "zcode";
  /** kind = dsh 时的原始实例；品牌/zcode 实例为 null */
  dsh: Instance | null;
  /** 品牌/zcode 条目；dsh 实例为 null */
  external: ExternalInstance | null;
}

const BRAND_LABEL: Record<string, string> = {
  dsh: I18N["dsh.brand.dsh"],
  kimi: I18N["dsh.brand.kimi"],
  opencode: I18N["dsh.brand.opencode"],
  zcode: I18N["dsh.brand.zcode"],
};

/**
 * 按"当前选中实例 id"解析启动面板的当前条目：
 * 先在 dsh 实例里找，再在跨品牌外部实例（kimi/opencode/zcode）里找；
 * 无选中或选中已不存在 → null（现有空态）。
 */
function useLaunchEntry(): LaunchEntry | null {
  const s = useAppState();
  const selectedId = useSelectedInstanceId();
  return useMemo(() => {
    if (!selectedId) return null;
    const dsh = s.instances.find((i) => i.id === selectedId);
    if (dsh) {
      return { id: dsh.id, name: dsh.name, state: normState(dsh.state), kind: "dsh", dsh, external: null };
    }
    const ext = s.external.find((e) => e.id === selectedId);
    if (ext) {
      return { id: ext.id, name: ext.name, state: normState(ext.state), kind: ext.brand, dsh: null, external: ext };
    }
    return null;
  }, [s.instances, s.external, selectedId]);
}

/**
 * 启动面板（右下，MainPage.launch-pane）：
 * 200×55 主钮（16px 动作 + 12px 目标，on-primary-container）+ 27×55 箭头菜单钮。
 * 主钮 = 启动/停止当前选中实例（dsh / kimi / opencode / zcode 按 kind 分发）；
 * 箭头 = 跨品牌实例选择弹出菜单。
 */
export function LaunchPane() {
  const nav = useNavigate();
  const [menuAnchor, setMenuAnchor] = useState<HTMLElement | null>(null);
  const current = useLaunchEntry();
  const running = current ? current.state === "RUNNING" : false;

  const onAction = () => {
    if (!current) {
      nav("/download?tab=game");
      return;
    }
    if (current.kind === "dsh") {
      if (!current.dsh) return;
      if (running) void stopInstance(current.dsh);
      else void launchInstance(current.dsh);
    } else {
      if (running) void stopBrandInstance(current.kind, current.id);
      else void launchBrandInstance(current.kind, { id: current.id, name: current.name });
    }
  };

  return (
    <div className="launch-pane">
      <button className="launch-main ripple-host" onClick={onAction}>
        <span className="launch-action">
          {current ? actionLabel(current.state) : I18N["dsh.launch.no_instance"]}
        </span>
        <span className="launch-target">
          {current ? current.name : I18N["dsh.launch.no_instance.hint"]}
        </span>
      </button>
      <button
        className="launch-menu-btn ripple-host"
        title={I18N["dsh.launch.select"]}
        onClick={(e) => setMenuAnchor(e.currentTarget)}
      >
        <ArrowDropUpIcon size={30} />
      </button>
      <InstancePickerMenu
        anchor={menuAnchor}
        onClose={() => setMenuAnchor(null)}
        footer={
          <PopupItem onClick={() => nav("/instances/new")}>
            {I18N["dsh.launch.no_instance"]}
          </PopupItem>
        }
      />
    </div>
  );
}

/** 跨品牌实例选择弹出菜单（jfx-popup 样式：surface 底、圆角 4）：点击条目切换选中实例，不路由跳转。 */
export function InstancePickerMenu({
  anchor,
  onClose,
  footer,
}: {
  anchor: HTMLElement | null;
  onClose: () => void;
  footer?: ReactNode;
}) {
  const s = useAppState();
  const selectedId = useSelectedInstanceId();
  const dshItems: LaunchEntry[] = s.instances.map((i) => ({
    id: i.id,
    name: i.name,
    state: normState(i.state),
    kind: "dsh",
    dsh: i,
    external: null,
  }));
  const extItems: LaunchEntry[] = s.external.map((e) => ({
    id: e.id,
    name: e.name,
    state: normState(e.state),
    kind: e.brand,
    dsh: null,
    external: e,
  }));
  const items = [...dshItems, ...extItems];
  const empty = items.length === 0;

  return (
    <PopupMenu open={anchor !== null} anchor={anchor} onClose={onClose}>
      {empty ? (
        <PopupItem onClick={onClose}>{I18N["dsh.launch.no_instance.hint"]}</PopupItem>
      ) : (
        items.map((i) => (
          <PopupItem
            key={`${i.kind}:${i.id}`}
            icon={
              i.dsh ? (
                <InstanceGraphic inst={i.dsh} size={20} />
              ) : (
                <InstanceGraphic inst={null} size={20} />
              )
            }
            onClick={() => {
              onClose();
              setSelectedInstanceId(i.id);
            }}
          >
            {i.name}
            <span style={{ opacity: 0.6, fontSize: 12 }}> — {BRAND_LABEL[i.kind]}</span>
            <span style={{ opacity: 0.6, fontSize: 12 }}>{i.id === selectedId ? " ·" : ""}</span>
          </PopupItem>
        ))
      )}
      {!empty && <PopupSep />}
      {footer}
    </PopupMenu>
  );
}
