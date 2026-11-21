import { useNavigate } from "react-router-dom";
import { McSeedPreview } from "../components/McSeedPreview";
import { ArrowBackIcon, PublicIcon } from "../components/icons";

/**
 * 种子预览的独立页面（`/mc/preview`）。
 *
 * 之所以要一个独立入口：试种子是个"来回比较"的动作，不该先进入某台服务器
 * 的详情页才找得到。这里可以先用实例的版本/种子起手，也可以直接填任意版本和
 * 种子——预览本来就是一次性进程，和哪台实例没有关系。
 */
export function McSeedPreviewPage() {
  const nav = useNavigate();

  return (
    <div className="page-with-sidebar">
      <nav className="sidebar-sub sidebar-stack" style={{ paddingTop: 0 }}>
        <div className="side-actions">
          <button className="side-item ripple-host" onClick={() => nav("/instances?view=mc")}>
            <span className="side-graphic">
              <ArrowBackIcon size={20} />
            </span>
            <span className="side-text">
              <span className="side-title">返回 MC 列表</span>
            </span>
          </button>
        </div>
      </nav>

      <div className="page-content scroll" style={{ gap: 8 }}>
        <div className="card" style={{ padding: 16 }}>
          <h2 style={{ margin: 0, display: "flex", alignItems: "center", gap: 8 }}>
            <PublicIcon size={20} />
            种子预览
          </h2>
          <p className="dim" style={{ margin: "4px 0 0", fontSize: 13 }}>
            和任何一台服务器都无关：选版本和种子，面板会起一个一次性进程生成，用完即删。
            版本没下载过就现场拉一份，之后一直在缓存里。
          </p>
        </div>

        <McSeedPreview />
      </div>
    </div>
  );
}
