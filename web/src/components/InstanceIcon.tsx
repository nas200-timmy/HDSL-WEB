import type { Instance } from "../types";
import { normState } from "../utils";

const GRADS = ["grad-a", "grad-b", "grad-c", "grad-d"];

function gradFor(name: string): string {
  let h = 0;
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) >>> 0;
  return GRADS[h % GRADS.length];
}

function img(name: string): string {
  return `${import.meta.env.BASE_URL}assets-img/${name}`;
}

/** 实例图标：icon 是可访问 URL 时显示图片，否则显示 HMCL 内置草方块图标。 */
export function InstanceIcon({ inst, size = 32 }: { inst: Instance; size?: number }) {
  const iconUrl = inst.icon;
  const showImg = !!iconUrl && /^(https?:|\/|data:)/.test(iconUrl);
  return (
    <span
      className="side-graphic lg"
      style={{ width: size, height: size, borderRadius: Math.max(2, Math.round(size * 0.12)) }}
      title={inst.name}
    >
      {showImg ? (
        <img src={iconUrl} alt="" />
      ) : (
        <img src={img("grass.png")} alt="" className={gradFor(inst.name)} />
      )}
    </span>
  );
}

/** 实例状态徽章（实例列表行副标题用）。 */
export function StateBadge({ state }: { state: string }) {
  const st = normState(state);
  if (st === "RUNNING") return <span className="tag running">{stateLabel(st)}</span>;
  if (st === "FAILED") return <span className="tag failed">{stateLabel(st)}</span>;
  if (st === "INSTALLING" || st === "STARTING" || st === "STOPPING")
    return <span className="tag warn">{stateLabel(st)}</span>;
  return null;
}

export function stateLabel(state: string): string {
  switch (normState(state)) {
    case "NOT_INSTALLED":
      return "未安装";
    case "INSTALLING":
      return "安装中";
    case "STARTING":
      return "启动中";
    case "RUNNING":
      return "运行中";
    case "STOPPING":
      return "停止中";
    case "FAILED":
      return "失败";
    default:
      return "已停止";
  }
}
