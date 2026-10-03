import { useMemo, type ReactNode } from "react";
import { useLocation, useNavigate, useParams, useSearchParams } from "react-router-dom";
import { I18N } from "../i18n";
import { useAppState } from "../store";
import { useServerVersion } from "../version";
import { ArrowBackIcon, HomeIcon } from "./icons";

/** 路由 → 标题栏文字（仿 MainWindowPane 的标题切换）。 */
function useWindowTitle(): string {
  const location = useLocation();
  const params = useParams();
  const [searchParams] = useSearchParams();
  const s = useAppState();
  const version = useServerVersion();
  const base = version ? `${I18N.appFullName} v${version}` : I18N.appFullName;

  return useMemo(() => {
    const p = location.pathname;
    if (p === "/") return base;
    if (p === "/login" || p === "/setup") return I18N.appFullName;
    if (p === "/instances") return I18N["dsh.instance.list"];
    if (p === "/download") return I18N["download"];
    if (p === "/accounts") return I18N["account"];
    if (p === "/settings") return I18N["settings"];
    if (p.startsWith("/instances/") && params.id) {
      const inst = s.instances.find((i) => i.id === params.id);
      return I18N["instance.manage.manage.title"].replace("%s", inst?.name ?? params.id);
    }
    if (p === "/instances/new") {
      const step = searchParams.get("step") === "2" ? I18N["dsh.install.step.quick"] : I18N["dsh.install.step.version"];
      return `${I18N["dsh.instance.install"]} - ${step}`;
    }
    if (p === "/install/modpack") {
      return I18N["install.modpack"];
    }
    return I18N.appFullName;
  }, [location.pathname, params.id, searchParams, s.instances, base]);
}

const WIZARD_PATHS = ["/instances/new", "/install/modpack"];

/**
 * 应用外框：整页铺满视口（网页化适配，不再模拟桌面窗口），
 * 结构 = 标题栏（40px，primary-container，模仿桌面版观感）+ 内容区（铺满剩余空间）。
 * 标题栏只保留导航：返回（有历史时）与向导页的主页按钮；
 * 桌面版的最小化/关闭/帮助三键属于窗口系统，网页里不存在，已移除。
 */
export function Decorator({ children }: { children: ReactNode }) {
  const nav = useNavigate();
  const location = useLocation();
  const title = useWindowTitle();

  const historyIdx = (window.history.state as { idx?: number } | null)?.idx ?? 0;
  const canGoBack = historyIdx > 0 && location.pathname !== "/login" && location.pathname !== "/setup";
  const isWizard = WIZARD_PATHS.some((w) => location.pathname.startsWith(w));

  return (
    <div className="decorator">
      <header className="titlebar">
        <div className="titlebar-nav">
          <button
            className="titlebar-btn ripple-host"
            disabled={!canGoBack}
            title="返回"
            onClick={() => nav(-1)}
          >
            <ArrowBackIcon size={16} />
          </button>
          {isWizard && (
            <button className="titlebar-btn ripple-host" title="主页" onClick={() => nav("/")}>
              <HomeIcon size={16} />
            </button>
          )}
        </div>
        <span className="titlebar-icon">
          <img src={`${import.meta.env.BASE_URL}assets-img/icon-title.png`} alt="" />
        </span>
        <span className="titlebar-title">{title}</span>
      </header>
      <div className="decorator-content">{children}</div>
    </div>
  );
}
