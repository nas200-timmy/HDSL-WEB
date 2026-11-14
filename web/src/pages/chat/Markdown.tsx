import { memo, useState, type ReactNode } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import rehypeSanitize, { defaultSchema } from "rehype-sanitize";
import rehypeHighlight from "rehype-highlight";
import { ContentCopyIcon, DownloadIcon } from "../../components/icons";
import type { ChatSearchHit } from "../../types";

/** hast 节点的纯文本；rehype-highlight 之后代码块里是 span 数组，不能只看 children。 */
function hastText(node: any): string {
  if (!node) return "";
  if (typeof node.value === "string") return node.value;
  if (Array.isArray(node.children)) return node.children.map(hastText).join("");
  return "";
}

function stripAnsi(text: string): string {
  return text.replace(/\u001b\[[0-9;]*m/g, "");
}

/** 代码块：语言标签 + 复制 + 下载 + 换行。高亮由 rehype-highlight 注入 class。 */
function CodeBlock({ node, children }: { node?: any; children?: ReactNode }) {
  const [copied, setCopied] = useState(false);
  const [wrap, setWrap] = useState(false);
  const [expanded, setExpanded] = useState(false);
  const raw = hastText(node).replace(/\n$/, "");
  const codeNode = node?.children?.find((child: any) => child.tagName === "code") ?? node;
  const classes = Array.isArray(codeNode?.properties?.className)
    ? codeNode.properties.className.join(" ")
    : typeof codeNode?.properties?.className === "string" ? codeNode.properties.className : "";
  const language = /language-([\w+-]+)/.exec(classes)?.[1] ?? "";
  const long = raw.split("\n").length > 32;

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(stripAnsi(raw));
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1200);
    } catch {
      // Clipboard permission refused; the user can still select the code.
    }
  };

  const download = () => {
    const blob = new Blob([stripAnsi(raw)], { type: "text/plain;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = `snippet${language ? "." + language : ".txt"}`;
    link.click();
    URL.revokeObjectURL(url);
  };

  return (
    <div className="chat-code">
      <div className="chat-code-head">
        <span className="chat-code-lang">{language || "text"}</span>
        <span className="spacer" />
        <button className="chat-code-btn" onClick={() => setWrap((value) => !value)}>
          {wrap ? "不换行" : "换行"}
        </button>
        <button className="chat-code-btn" onClick={download} title="下载代码">
          <DownloadIcon size={14} />
        </button>
        <button className="chat-code-btn" onClick={() => void copy()} title="复制代码">
          <ContentCopyIcon size={14} />
          {copied ? "已复制" : "复制"}
        </button>
      </div>
      <pre className={`chat-code-body${wrap ? " wrap" : ""}${long && !expanded ? " collapsed" : ""}`}>
        {children}
      </pre>
      {long && (
        <button className="chat-code-more" onClick={() => setExpanded((value) => !value)}>
          {expanded ? "收起" : `展开全部（${raw.split("\n").length} 行）`}
        </button>
      )}
    </div>
  );
}

const sanitizeSchema = {
  ...defaultSchema,
  attributes: {
    ...defaultSchema.attributes,
    span: [...(defaultSchema.attributes?.span ?? []), "className"],
    code: [...(defaultSchema.attributes?.code ?? []), "className"],
  },
  protocols: {
    ...defaultSchema.protocols,
    href: ["http", "https", "mailto"],
  },
};

/** GFM + sanitize + highlight。不启用 raw HTML。 */
function MarkdownImpl({ content, streaming, citations, sources }: {
  content: string;
  streaming?: boolean;
  /** 引用短 id → 原页面 URL；模型写 [citation,domain](id) 时用它换成真链接。 */
  citations?: Record<string, string>;
  /** DeepSeek 官方引用：正文里的 [citation:N] 按来源顺序换成链接。 */
  sources?: ChatSearchHit[];
}) {
  let text = content ?? "";
  if (streaming) {
    // 未闭合的围栏临时补上，避免流式时整段代码被当成普通段落抖动。
    const fences = (text.match(/^```/gm) ?? []).length;
    if (fences % 2 === 1) text += "\n```";
  }
  if (sources && sources.length > 0) {
    text = text.replace(/\[citation:(\d+)\]/g, (_, raw) => {
      const index = Number(raw) - 1;
      const source = sources[index];
      if (!source) return `[${raw}]`;
      return `[${raw}](${source.url})`;
    });
  }
  if (citations && Object.keys(citations).length > 0) {
    // rehype-sanitize 只放行 http/https/mailto，短 id 会被当成非法协议删掉，
    // 所以先在这里换成真实 URL。
    text = text.replace(/\[citation,([^\]]+)\]\(([0-9a-f]{6})\)/g, (_, domain, id) => {
      const url = citations[id];
      return url ? `[${domain}](${url})` : `[${domain}]`;
    });
  }
  return (
    <div className="chat-markdown">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        rehypePlugins={[[rehypeSanitize, sanitizeSchema] as any, rehypeHighlight]}
        components={{
          pre: ({ node, children }) => <CodeBlock node={node}>{children}</CodeBlock>,
          a: ({ href, children }) => (
            <a href={href} target="_blank" rel="noopener noreferrer">
              {children}
            </a>
          ),
        }}
      >
        {text}
      </ReactMarkdown>
    </div>
  );
}

export const Markdown = memo(MarkdownImpl);