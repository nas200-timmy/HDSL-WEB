import { useMemo, useState } from "react";
import type { ChatPart } from "../../types";

type Source = { title?: string; url?: string; site?: string };

type Step =
  | { kind: "thinking"; id: string; text: string; durationMs?: number }
  | {
      kind: "tool";
      id: string;
      name: string;
      query: string;
      hits: number;
      content?: string;
      durationMs?: number;
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

function parseSteps(parts: ChatPart[]): Step[] {
  const calls = new Map<string, { name: string; query: string }>();
  const steps: Step[] = [];
  let index = 0;
  for (const part of parts) {
    if (part.type === "thinking" && part.text?.trim()) {
      steps.push({ kind: "thinking", id: `t${index++}`, text: part.text, durationMs: part.durationMs });
    } else if (part.type === "tool_calls") {
      for (const call of part.calls ?? []) {
        calls.set(call.id, { name: call.name, query: parseQuery(call.arguments) });
      }
    } else if (part.type === "tool") {
      const call = calls.get(part.id ?? "");
      if (!call) continue;
      steps.push({
        kind: "tool",
        id: `s${index++}`,
        name: call.name,
        query: call.query || call.name,
        hits: part.hits ?? 0,
        content: part.content,
        durationMs: part.durationMs,
      });
    }
  }
  return steps;
}

function seconds(ms?: number): string {
  return `${Math.max(0, (ms ?? 0) / 1000).toFixed(1)} 秒`;
}

/** 一条助手消息的思考/搜索时间线：默认只露最后两步，其余折成“再显示 N 步”。 */
export function Timeline({ parts }: { parts: ChatPart[] }) {
  const steps = useMemo(() => parseSteps(parts), [parts]);
  const [expandedAll, setExpandedAll] = useState(false);
  const [open, setOpen] = useState<Record<string, boolean>>({});
  if (steps.length === 0) return null;

  const visible = expandedAll ? steps : steps.slice(-2);
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
        const sources = step.kind === "tool" ? parseHits(step.content) : [];
        return (
          <div className={`chat-timeline-step ${step.kind}`} key={step.id}>
            <span className="chat-timeline-icon">{step.kind === "thinking" ? "💡" : "🔍"}</span>
            <div className="chat-timeline-main">
              <button className="chat-timeline-head" onClick={() => toggle(step.id)}>
                <span className="chat-timeline-title">
                  {step.kind === "thinking"
                    ? `思考了 ${seconds(step.durationMs)}`
                    : `${step.name === "scrape_web" ? "读取网页" : "搜索"}：${step.query}`}
                </span>
                {step.kind === "tool" && step.hits > 0 && (
                  <span className="chat-timeline-meta">共 {step.hits} 条结果</span>
                )}
                <span className="chat-timeline-chevron">{isOpen ? "收起" : "展开"}</span>
              </button>
              {isOpen && step.kind === "thinking" && (
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
