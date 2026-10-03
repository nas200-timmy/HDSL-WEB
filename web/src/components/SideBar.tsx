import { useMemo } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { I18N } from "../i18n";
import { useAppState } from "../store";
import type { Instance } from "../types";
import { normState } from "../utils";
import {
  DownloadIcon,
  FormatListBulletedIcon,
  PersonIcon,
  SettingsIcon,
} from "./icons";

function img(name: string): string {
  return `${import.meta.env.BASE_URL}assets-img/${name}`;
}

/** 实例图标：icon 字段是可访问 URL 时显示图片，否则用 HMCL 内置草方块图标。 */
export function InstanceGraphic({ inst, size = 32 }: { inst: Instance | null; size?: number }) {
  const iconUrl = inst?.icon;
  const showImg = !!iconUrl && /^(https?:|\/|data:)/.test(iconUrl);
  if (showImg) {
    return (
      <span className="side-graphic lg" style={{ width: size, height: size }}>
        <img src={iconUrl} alt="" />
      </span>
    );
  }
  return (
    <span className="side-graphic lg" style={{ width: size, height: size }}>
      <img src={img("grass.png")} alt="" />
    </span>
  );
}

/**
 * 主导航侧栏（宽 200，MainPage 结构）：
 * 账户分区（账户卡片）/ 游戏分区（当前实例项、实例列表、下载）/ 通用分区（设置）。
 */
export function MainSideBar() {
  const nav = useNavigate();
  const location = useLocation();
  const s = useAppState();

  const account = s.accounts && s.accounts.length > 0 ? s.accounts[0] : null;
  const current = useMemo(() => {
    const running = s.instances.find((i) => normState(i.state) === "RUNNING");
    return running ?? s.instances[0] ?? null;
  }, [s.instances]);

  const go = (path: string) => () => nav(path);
  const selected = (path: string) => location.pathname === path;

  return (
    <nav className="sidebar-main">
      <div className="side-category">{I18N["dsh.account.list"]}</div>
      <button
        className={`side-item ripple-host${selected("/accounts") ? " selected" : ""}`}
        onClick={go("/accounts")}
      >
        <span className="side-graphic lg">
          {account?.skinSet ? (
            <img src={`/api/accounts/${encodeURIComponent(account.name)}/skin`} alt="" />
          ) : (
            <PersonIcon size={20} />
          )}
        </span>
        <span className="side-text">
          <span className="side-title">
            {account ? account.label || account.name : I18N["dsh.account.none.short"]}
          </span>
          <span className="side-subtitle">
            {account ? account.vendor : I18N["dsh.account.none.hint"]}
          </span>
        </span>
      </button>

      <div className="side-category">{I18N["instance"]}</div>
      <button
        className={`side-item ripple-host${location.pathname.startsWith("/instances/") && location.pathname !== "/instances" ? " selected" : ""}`}
        onClick={() => current && nav(`/instances/${current.id}`)}
      >
        <InstanceGraphic inst={current} />
        <span className="side-text">
          <span className="side-title">{current?.name ?? I18N["dsh.instance.none"]}</span>
          <span className="side-subtitle">
            {current ? current.version : I18N["dsh.instance.install"]}
          </span>
        </span>
      </button>
      <button
        className={`side-item ripple-host${selected("/instances") ? " selected" : ""}`}
        onClick={go("/instances")}
      >
        <span className="side-graphic">
          <FormatListBulletedIcon size={20} />
        </span>
        <span className="side-text">
          <span className="side-title">{I18N["dsh.instance.list"]}</span>
        </span>
      </button>
      <button
        className={`side-item ripple-host${selected("/download") ? " selected" : ""}`}
        onClick={go("/download")}
      >
        <span className="side-graphic">
          <DownloadIcon size={20} />
        </span>
        <span className="side-text">
          <span className="side-title">{I18N["download"]}</span>
        </span>
      </button>

      <div className="side-category">{I18N["settings.launcher.general"]}</div>
      <button
        className={`side-item ripple-host${selected("/settings") ? " selected" : ""}`}
        onClick={go("/settings")}
      >
        <span className="side-graphic">
          <SettingsIcon size={20} />
        </span>
        <span className="side-text">
          <span className="side-title">{I18N["settings"]}</span>
        </span>
      </button>
    </nav>
  );
}

export interface SubSideItem {
  key: string;
  title: string;
  icon: React.ReactNode;
  iconActive?: React.ReactNode;
}

export interface SubSideSection {
  title?: string;
  items: SubSideItem[];
}

/** 子页面侧栏：可选分区标题 + 条目 + 底部操作区。 */
export function SubSideBar({
  sections,
  activeKey,
  onSelect,
  bottom,
  testId,
}: {
  sections: SubSideSection[];
  activeKey?: string;
  onSelect?: (key: string) => void;
  bottom?: React.ReactNode;
  testId?: string;
}) {
  return (
    <nav className="sidebar-sub" data-testid={testId}>
      {sections.map((sec, i) => (
        <div key={i}>
          {sec.title != null && <div className="side-category">{sec.title}</div>}
          {sec.items.map((it) => {
            const active = it.key === activeKey;
            return (
              <button
                key={it.key}
                className={`side-item ripple-host${active ? " selected" : ""}`}
                onClick={() => onSelect?.(it.key)}
              >
                <span className="side-graphic">
                  {active && it.iconActive ? it.iconActive : it.icon}
                </span>
                <span className="side-text">
                  <span className="side-title">{it.title}</span>
                </span>
              </button>
            );
          })}
        </div>
      ))}
      <div className="sidebar-spacer" />
      {bottom && <div className="side-actions">{bottom}</div>}
    </nav>
  );
}
