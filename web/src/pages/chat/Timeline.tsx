import { useMemo, useState } from "react";
import type { ChatPart } from "../../types";

type Source = { title?: string; url?: string; site?: string };

export type TimelineStep = {
  kind: "thinking" | "tool";
  id: string;
  round: number;
  text?: string;
  durationMs?: number;
  name?: string;
  query?: string;
  hits?: number;
  sources?: Source[];
};

function parseQuery(argumentsJson: string): string {
  try {
    const args = JSON.parse(argumentsJson) as { query?: string; url?: string };
    return args.query || args.url || "";
  } catch {
    return "";
  }
}

function parseHits(content?: string): Source[] {
  if (!content) return [];
  try {
    const data = JSON.parse(content) as { items?: Source[] };
    return Array.isArray(data.items) ? data.items : [];
  } catch {
    return [];
  }
}

/** 普通协议的 parts → 步骤；round 按 tool_calls 递增。 */
export function stepsFromParts(parts: ChatPart[]): TimelineStep[] {
  const calls = new Map<string, { name: string; query: string; round: number }>();
  const steps: TimelineStep[] = [];
  let round = 0;
  let index = 0;
  for (const part of parts) {
    if (part.type === "thinking" && part.text?.trim()) {
      steps.push({
        kind: "thinking",
        id: `t${index++}`,
        round,
        text: part.text,
        durationMs: part.durationMs,
      });
    } else if (part.type === "tool_calls") {
      round += 1;
      for (const call of part.calls ?? []) {
        calls.set(call.id, { name: call.name, query: parseQuery(call.arguments), round });
      }
    } else if (part.type === "tool") {
      const call = calls.get(part.id ?? "");
      if (!call) continue;
      steps.push({
        kind: "tool",
        id: `s${index++}`,
        round: call.round,
        name: call.name,
        query: call.query || call.name,
        hits: part.hits ?? 0,
        sources: parseHits(part.content),
      });
    }
  }
  return steps;
}

export type LiveTimelineBlock =
  | { kind: "thinking"; text: string }
  | { kind: "tool"; tool?: string; query: string; provider: string; status: string; hits: number };

/** 流式阶段只有 thinking/tool 事件，没有 parts；按“tool 之后的新思考”分轮。 */
export function stepsFromLive(blocks: LiveTimelineBlock[]): TimelineStep[] {
  const steps: TimelineStep[] = [];
  let round = 0;
  let seenTool = false;
  let index = 0;
  for (const block of blocks) {
    if (block.kind === "thinking") {
      if (seenTool) {
        round += 1;
        seenTool = false;
      }
      steps.push({
        kind: "thinking",
        id: `t${index++}`,
        round,
        text: block.text,
      });
    } else {
      seenTool = true;
      steps.push({
        kind: "tool",
        id: `s${index++}`,
        round,
        name: block.tool ?? "search_web",
        query: block.query,
        hits: block.hits,
      });
    }
  }
  return steps;
}

function seconds(ms?: number): string {
  return `${Math.max(0, (ms ?? 0) / 1000).toFixed(1)} 秒`;
}

/** 默认只露最后一轮的搜索（以及其后的思考），更早的折成“再显示 N 步”。 */
export function Timeline({ steps }: { steps: TimelineStep[] }) {
  const [expandedAll, setExpandedAll] = useState(false);
  const [open, setOpen] = useState<Record<string, boolean>>({});
  const visible = useMemo(() => {
    if (expandedAll || steps.length === 0) return steps;
    const lastRound = Math.max(...steps.map((step) => step.round));
    const roundSteps = steps.filter((step) => step.round === lastRound);
    const firstTool = roundSteps.findIndex((step) => step.kind === "tool");
    return firstTool >= 0 ? roundSteps.slice(firstTool) : roundSteps.slice(-1);
  }, [steps, expandedAll]);
  if (steps.length === 0) return null;

  const hidden = steps.length - visible.length;
  const toggle = (id: string) => setOpen((current) => ({ ...current, [id]: !current[id] }));

  return (
    <div className="chat-timeline">
      {hidden > 0 && (
        <button className="chat-timeline-more" onClick={() => setExpandedAll(true)}>
          再显示 {hidden} 步
        </button>
      )}
      {expandedAll && steps.length > 2 && (
        <button className="chat-timeline-more" onClick={() => setExpandedAll(false)}>收起</button>
      )}
      {visible.map((step) => {
        const isOpen = open[step.id] ?? false;
        const sources = step.sources ?? [];
        return (
          <div className={`chat-timeline-step ${step.kind}`} key={step.id}>
            <span className="chat-timeline-icon">{step.kind === "thinking" ? "💡" : "🔍"}</span>
            <div className="chat-timeline-main">
              <button className="chat-timeline-head" onClick={() => toggle(step.id)}>
                <span className="chat-timeline-title">
                  {step.kind === "thinking"
                    ? (step.durationMs != null ? `思考了 ${seconds(step.durationMs)}` : "思考中…")
                    : `${step.name === "scrape_web" ? "读取网页" : "搜索"}：${step.query}`}
                </span>
                {step.kind === "tool" && (step.hits ?? 0) > 0 && (
                  <span className="chat-timeline-meta">共 {step.hits} 条结果</span>
                )}
                <span className="chat-timeline-chevron">{isOpen ? "收起" : "展开"}</span>
              </button>
              {isOpen && step.kind === "thinking" && step.text && (
                <div className="chat-timeline-body">{step.text}</div>
              )}
              {isOpen && step.kind === "tool" && sources.length > 0 && (
                <div className="chat-sources chat-timeline-sources">
                  {sources.map((source, index) => (
                    <a
                      key={`${source.url}-${index}`}
                      className="chat-source"
                      href={source.url}
                      target="_blank"
                      rel="noreferrer noopener"
                      title={source.title}
                    >
                      <span className="chat-source-index">{index + 1}</span>
                      <span className="chat-source-title">{source.title ?? source.url}</span>
                      {source.site && <span className="chat-source-site">{source.site}</span>}
                    </a>
                  ))}
                </div>
              )}
            </div>
          </div>
        );
      })}
    </div>
  );
}
