import { useRef, useState } from "react";
import { PopupMenu } from "../../components/PopupMenu";
import type { ChatContextUsage } from "../../types";

/** 千单位：999 / 1.2K / 12.3K / 128K / 1.2M。 */
export function tokenText(value: number | null | undefined): string {
  if (value == null || !Number.isFinite(value) || value <= 0) return "0";
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(1).replace(/\.0$/, "")}M`;
  if (value >= 100_000) return `${Math.round(value / 1_000)}K`;
  if (value >= 10_000) return `${(value / 1_000).toFixed(1)}K`;
  if (value >= 1_000) return `${(value / 1_000).toFixed(1)}K`;
  return String(Math.round(value));
}

const RING = 30;
const STROKE = 3;
const RADIUS = (RING - STROKE) / 2;
const CIRCUMFERENCE = 2 * Math.PI * RADIUS;

const SOURCE_LABELS: Record<string, string> = {
  manual: "手动覆盖",
  saved: "手动覆盖",
  probe: "识图实测",
  vendor: "供应商预设",
  catalog: "模型目录",
  builtin: "内置识别",
  unknown: "未识别",
};

const sourceLabel = (source: string | null | undefined) =>
  source ? SOURCE_LABELS[source] ?? source : "未知";

/**
 * 上下文占用环：分子是最近一次回复的 input tokens（系统提示 + 历史 + 附件），
 * 分母是当前模型的 maxContext。始终显示绝对数量（98K / 1M），百分比只在浮窗里，
 * 避免盯着百分比焦虑。点开可以看这一轮的构成估算。
 */
export function ContextMeter({
  used, max, maxSource, estimated, context, lastOutput, sessionInput, sessionOutput, disabled,
}: {
  used: number | null;
  max: number;
  maxSource?: string | null;
  estimated?: boolean;
  context?: ChatContextUsage | null;
  lastOutput?: number | null;
  sessionInput: number;
  sessionOutput: number;
  disabled?: boolean;
}) {
  const [open, setOpen] = useState(false);
  const anchor = useRef<HTMLButtonElement | null>(null);
  const ratio = used != null && max > 0 ? Math.min(1, used / max) : 0;
  const percent = used != null && max > 0 ? Math.round((used / max) * 1000) / 10 : null;
  const tone = ratio >= 0.95 ? "danger" : ratio >= 0.8 ? "warn" : "normal";
  const maxLabel = max > 0 ? tokenText(max) : "上限未知";
  const approximate = estimated ? "≈" : "";
  const usedLabel = used == null ? "—" : `${approximate}${tokenText(used)}`;
  const title = used == null
    ? `上下文：还没有可用的用量（上限 ${maxLabel}）`
    : `上下文 ${approximate}${tokenText(used)} / ${maxLabel}`
      + (percent == null ? "" : `（${percent}%）`);

  return (
    <>
      <button
        ref={anchor}
        className={`context-meter ${tone}`}
        disabled={disabled}
        title={title}
        onClick={() => setOpen((value) => !value)}
      >
        <svg width={RING} height={RING} viewBox={`0 0 ${RING} ${RING}`} aria-hidden="true">
          <circle
            className="context-meter-track"
            cx={RING / 2} cy={RING / 2} r={RADIUS}
            fill="none" strokeWidth={STROKE}
          />
          <circle
            className="context-meter-value"
            cx={RING / 2} cy={RING / 2} r={RADIUS}
            fill="none" strokeWidth={STROKE} strokeLinecap="round"
            strokeDasharray={CIRCUMFERENCE}
            strokeDashoffset={CIRCUMFERENCE * (1 - ratio)}
            transform={`rotate(-90 ${RING / 2} ${RING / 2})`}
          />
        </svg>
        <span className="context-meter-text">
          {usedLabel} / {maxLabel}
        </span>
      </button>

      <PopupMenu open={open} anchor={anchor.current} onClose={() => setOpen(false)} className="chat-context-popup">
        <div className="chat-context-head">
          <span>上下文</span>
          <span className={`chat-context-percent ${tone}`}>{percent == null ? "—" : `${percent}%`}</span>
        </div>
        <div className="chat-context-row">
          <span>当前占用</span>
          <span>{usedLabel} / {maxLabel}</span>
        </div>
        <div className="chat-context-row">
          <span>本轮输出</span>
          <span>{lastOutput == null ? "—" : tokenText(lastOutput)}</span>
        </div>
        {used != null && max > 0 && (
          <div className="chat-context-row">
            <span>剩余</span>
            <span>{tokenText(Math.max(0, max - used))}</span>
          </div>
        )}
        {context && (
          <>
            <div className="chat-context-sep" />
            <div className="chat-context-subhead">构成（估算）</div>
            <div className="chat-context-row">
              <span>系统提示</span>
              <span>≈{tokenText(context.system)}</span>
            </div>
            <div className="chat-context-row">
              <span>历史与提问</span>
              <span>≈{tokenText(context.history)}</span>
            </div>
            <div className="chat-context-row">
              <span>附件</span>
              <span>
                ≈{tokenText(context.attachments)}
                {context.images ? ` · ${context.images} 张图` : ""}
              </span>
            </div>
          </>
        )}
        <div className="chat-context-sep" />
        <div className="chat-context-row">
          <span>会话累计输入</span>
          <span>{tokenText(sessionInput)}</span>
        </div>
        <div className="chat-context-row">
          <span>会话累计输出</span>
          <span>{tokenText(sessionOutput)}</span>
        </div>
        <div className="chat-context-row">
          <span>上限来源</span>
          <span>{sourceLabel(maxSource)}</span>
        </div>
        <div className="chat-context-hint">
          {max <= 0
            ? "这个模型的上下文上限未识别；可以在模型能力里手动填写，占用环才会准确。"
            : "当前占用是模型这一轮实际看到的 tokens；构成按字符估算（约 3 字符/token，图片按 1K/张），与计费口径可能不同。"}
          {max > 0 && ratio >= 0.95
            ? " 已经接近上限，建议新建会话或从这里分支。"
            : ""}
          {estimated ? " 服务商没有返回用量，这里按估算显示。" : ""}
        </div>
      </PopupMenu>
    </>
  );
}
