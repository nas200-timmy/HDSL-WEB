import { useState, type ReactNode } from "react";
import { useNavigate } from "react-router-dom";
import { launchInstance, stopInstance } from "../actions";
import { I18N } from "../i18n";
import { useAppState } from "../store";
import type { Instance } from "../types";
import { normState } from "../utils";
import { ArrowDropUpIcon } from "./icons";
import { PopupItem, PopupMenu } from "./PopupMenu";
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

/**
 * 启动面板（右下，MainPage.launch-pane）：
 * 200×55 主钮（16px 动作 + 12px 目标，on-primary-container）+ 27×55 箭头菜单钮。
 * 主钮 = 启动/停止当前选中实例；箭头 = 实例选择弹出菜单。
 */
export function LaunchPane({ current }: { current: Instance | null }) {
  const nav = useNavigate();
  const [menuAnchor, setMenuAnchor] = useState<HTMLElement | null>(null);
  const running = current ? normState(current.state) === "RUNNING" : false;
  const state = current ? normState(current.state) : "STOPPED";

  const onAction = () => {
    if (!current) {
      nav("/download?tab=game");
      return;
    }
    if (running) void stopInstance(current);
    else void launchInstance(current);
  };

  return (
    <div className="launch-pane">
      <button className="launch-main ripple-host" onClick={onAction}>
        <span className="launch-action">
          {current ? actionLabel(state) : I18N["dsh.launch.no_instance"]}
        </span>
        <span className="launch-target">
          {current ? current.id : I18N["dsh.launch.no_instance.hint"]}
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
        current={current}
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

/** 实例选择弹出菜单（jfx-popup 样式：surface 底、圆角 4）。 */
export function InstancePickerMenu({
  anchor,
  current,
  onClose,
  footer,
}: {
  anchor: HTMLElement | null;
  current: Instance | null;
  onClose: () => void;
  footer?: ReactNode;
}) {
  const nav = useNavigate();
  const s = useAppState();
  const instances = s.instances;
  const empty = instances.length === 0;

  return (
    <PopupMenu open={anchor !== null} anchor={anchor} onClose={onClose}>
      {empty ? (
        <PopupItem onClick={onClose}>{I18N["dsh.launch.no_instance.hint"]}</PopupItem>
      ) : (
        instances.map((i) => (
          <PopupItem
            key={i.id}
            icon={<InstanceGraphic inst={i} size={20} />}
            onClick={() => {
              onClose();
              nav(`/instances/${i.id}`);
            }}
          >
            {i.name}
            <span style={{ opacity: 0.6, fontSize: 12 }}>{i.id === current?.id ? " ·" : ""}</span>
          </PopupItem>
        ))
      )}
      {footer}
    </PopupMenu>
  );
}

/** 无实例时启动面板（新建实例入口，对应桌面版 no-instance 状态）。 */
export function LaunchPanePlaceholder() {
  const nav = useNavigate();
  return (
    <div className="launch-pane">
      <button className="launch-main ripple-host" onClick={() => nav("/download?tab=game")}>
        <span className="launch-action">{I18N["dsh.launch.no_instance"]}</span>
        <span className="launch-target">{I18N["dsh.launch.no_instance.hint"]}</span>
      </button>
      <button
        className="launch-menu-btn ripple-host"
        title={I18N["dsh.launch.select"]}
        onClick={() => nav("/instances")}
      >
        <ArrowDropUpIcon size={30} />
      </button>
    </div>
  );
}
