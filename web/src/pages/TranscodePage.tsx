import { useEffect, type ReactNode } from "react";
import { Navigate, useNavigate, useSearchParams } from "react-router-dom";
import { SubSideBar } from "../components/SideBar";
import {
  ArrowBackIcon,
  ExtensionIcon,
  FolderOpenIcon,
  FormatListBulletedIcon,
  HomeIcon,
  SettingsIcon,
  TuneIcon,
} from "../components/icons";
import { refreshTranscodeFeature, useTranscodeFeature } from "../transcodeFeature";

type TranscodeTab = "overview" | "prepare" | "queue" | "presets" | "tools" | "settings";

const TABS: Array<{ key: TranscodeTab; title: string; icon: ReactNode; iconActive?: ReactNode }> = [
  { key: "overview", title: "概览", icon: <HomeIcon size={20} /> },
  { key: "prepare", title: "准备文件", icon: <FolderOpenIcon size={20} /> },
  { key: "queue", title: "编码队列", icon: <FormatListBulletedIcon size={20} /> },
  { key: "presets", title: "预设", icon: <TuneIcon size={20} /> },
  { key: "tools", title: "工具", icon: <ExtensionIcon size={20} /> },
  { key: "settings", title: "设置", icon: <SettingsIcon size={20} /> },
];

/// 转码模块的二级页面骨架：左侧固定六项，右侧内容区。
///
/// 功能关闭时整个页面不存在（回首页）；开启后才有入口。本提交先落壳与
/// 环境状态，准备文件/队列/预设/工具/设置随后按各自提交接入。
export function TranscodePage() {
  const feature = useTranscodeFeature();
  const nav = useNavigate();
  const [params, setParams] = useSearchParams();
  const raw = params.get("tab");
  const tab: TranscodeTab = TABS.some((entry) => entry.key === raw) ? (raw as TranscodeTab) : "overview";

  useEffect(() => {
    void refreshTranscodeFeature();
  }, []);

  if (!feature) {
    return (
      <div className="page-with-sidebar">
        <div className="page-content" style={{ alignItems: "center", justifyContent: "center" }}>
          <div className="spinner" />
        </div>
      </div>
    );
  }
  if (!feature.enabled) return <Navigate to="/" replace />;

  return (
    <div className="page-with-sidebar">
      <SubSideBar
        activeKey={tab}
        onSelect={(key) => setParams({ tab: key }, { replace: true })}
        sections={[{ items: TABS }]}
        bottom={
          <button className="side-item ripple-host" onClick={() => nav("/")}>
            <span className="side-graphic">
              <ArrowBackIcon size={20} />
            </span>
            <span className="side-text">
              <span className="side-title">返回主界面</span>
            </span>
          </button>
        }
      />

      <div className="page-content scroll" style={{ gap: 8 }}>
        <div className="section-caption">
          <TuneIcon size={16} />
          转码 · {TABS.find((entry) => entry.key === tab)?.title}
        </div>

        {tab === "overview" && <OverviewCard />}
        {tab !== "overview" && (
          <div className="card">
            <div className="comp-row">
              <span className="comp-label">{TABS.find((entry) => entry.key === tab)?.title}</span>
              <span className="comp-value">
                <span className="tag plain">建设中</span>
              </span>
            </div>
            <p style={{ fontSize: 12, color: "var(--monet-on-surface-variant)", padding: "4px 8px 8px", lineHeight: 1.6 }}>
              功能门与页面骨架已就位；这个分区在后续提交里接入。
            </p>
          </div>
        )}
      </div>
    </div>
  );
}

function OverviewCard() {
  const feature = useTranscodeFeature();
  if (!feature) return null;
  const policy =
    feature.failurePolicy === "ask"
      ? "询问（默认）"
      : feature.failurePolicy === "timeout-auto"
        ? `超时自动降级（${feature.failureTimeoutMinutes} 分钟）`
        : "完全自动化";
  const mediaState = !feature.mediaRootExists
    ? "目录不存在"
    : feature.mediaRootWritable
      ? "可写"
      : "只读";
  return (
    <>
      <div className="card">
        <div className="comp-row">
          <span className="comp-label">功能状态</span>
          <span className="comp-value">
            <span className="tag running">已开启</span>
          </span>
        </div>
        <div className="comp-row">
          <span className="comp-label">媒体根</span>
          <span className="comp-value mono" style={{ fontSize: 12 }}>
            {feature.mediaRoot} · {mediaState}
          </span>
        </div>
        <div className="comp-row">
          <span className="comp-label">并发 / 队列上限</span>
          <span className="comp-value">
            {feature.maxConcurrent} / {feature.maxQueue}
          </span>
        </div>
        <div className="comp-row">
          <span className="comp-label">失败策略</span>
          <span className="comp-value">{policy}</span>
        </div>
        <div className="comp-row">
          <span className="comp-label">重启后恢复队列</span>
          <span className="comp-value">{feature.autoResume ? "是" : "否"}</span>
        </div>
      </div>

      {(!feature.mediaRootExists || !feature.mediaRootWritable) && (
        <div className="card">
          <div className="comp-row">
            <span className="comp-label" style={{ color: "var(--monet-error)" }}>
              媒体根需要处理
            </span>
          </div>
          <p style={{ fontSize: 13, padding: "4px 8px 8px", lineHeight: 1.7 }}>
            {feature.mediaRootExists
              ? "媒体根当前不可写：可以读取源文件，但入队前需要选一个可写的输出目录。"
              : "媒体根目录还不存在：请先创建它并让面板运行用户可写（容器里是 /home/hdsl/video 的挂载）。"}
          </p>
        </div>
      )}

      <div className="card">
        <div className="comp-row">
          <span className="comp-label">下一步</span>
        </div>
        <p style={{ fontSize: 13, padding: "4px 8px 8px", lineHeight: 1.7 }}>
          准备文件、编码队列、全量预设、工具与设置分区会按计划逐步接入。队列在服务端运行，
          关闭网页不会影响正在编码的任务。
        </p>
      </div>
    </>
  );
}