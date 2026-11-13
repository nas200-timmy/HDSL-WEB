import { useEffect, useRef, useState } from "react";
import { PopupItem, PopupMenu, PopupSep } from "../../components/PopupMenu";
import { ContentCopyIcon, DeleteIcon, EditIcon, MoreVertIcon, PersonIcon, RefreshIcon } from "../../components/icons";
import type { ChatAttachment, ChatMessage, ChatNode } from "../../types";
import { api } from "../../api";
import { Markdown } from "./Markdown";
import { AvatarView } from "./AvatarView";

function activeMessage(node: ChatNode): ChatMessage | null {
  if (node.variants.length === 0) return null;
  return node.variants.find((item) => item.id === node.activeVariantId) ?? node.variants[node.variants.length - 1];
}

function AttachmentChip({ attachment, onOpen }: { attachment: ChatAttachment; onOpen: (url: string) => void }) {
  const url = api.chat.attachmentUrl(attachment.id);
  if (attachment.kind === "image") {
    return (
      <button className="chat-attach-image" onClick={() => onOpen(url)} title={attachment.name}>
        <img src={url} alt={attachment.name} loading="lazy" />
      </button>
    );
  }
  return (
    <button className="chat-attach-chip" onClick={() => onOpen(url)} title={attachment.name}>
      <span className="chat-attach-kind">{attachment.kind === "document" ? "DOC" : attachment.kind.toUpperCase()}</span>
      <span className="chat-attach-name">{attachment.name}</span>
      <span className="chat-attach-size">{Math.max(1, Math.round(attachment.size / 1024))} KB</span>
      {attachment.extractStatus === "failed" && <span className="chat-attach-warn" title={attachment.extractError ?? ""}>!</span>}
    </button>
  );
}

/** 一条消息（一个节点）。variant 切换、两种重生成、编辑与删除都在这里。 */
export function MessageItem({
  node, assistant, user, editingNodeId, liveContent, liveReasoning, streaming,
  onRegenerate, onEdit, onDelete, onSwitchVariant,
}: {
  node: ChatNode;
  assistant: { name: string; avatar: string };
  user: { name: string; avatar: string | null };
  editingNodeId?: string | null;
  liveContent?: string;
  liveReasoning?: string;
  streaming?: boolean;
  onRegenerate: (nodeId: string, mode: "in_place" | "truncate_after") => void;
  onEdit: (nodeId: string, text: string) => void;
  onDelete: (nodeId: string) => void;
  onSwitchVariant: (nodeId: string, messageId: string) => void;
}) {
  const message = activeMessage(node);
  const [menu, setMenu] = useState<HTMLElement | null>(null);
  const [copied, setCopied] = useState(false);
  const [reasoningExpanded, setReasoningExpanded] = useState(false);
  const moreRef = useRef<HTMLButtonElement | null>(null);
  const reasoningRef = useRef<HTMLDivElement | null>(null);

  const content = streaming && liveContent !== undefined ? liveContent : (message?.content ?? "");
  const reasoning = streaming && liveReasoning !== undefined ? liveReasoning : (message?.reasoning ?? "");

  useEffect(() => {
    if (streaming) setReasoningExpanded(true);
  }, [streaming]);

  useEffect(() => {
    if (streaming && reasoningExpanded && reasoningRef.current) {
      reasoningRef.current.scrollTop = reasoningRef.current.scrollHeight;
    }
  }, [reasoning, streaming, reasoningExpanded]);

  if (!message) return null;

  const isUser = node.role === "user";
  const index = node.variants.findIndex((item) => item.id === message.id);
  const canSwitch = node.variants.length > 1;
  const time = new Date(node.createdAt).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(content);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1200);
    } catch {
      // Clipboard unavailable; ignore.
    }
  };

  const openAttachment = (url: string) => window.open(url, "_blank", "noopener,noreferrer");

  return (
    <div className={`chat-msg ${isUser ? "user" : "assistant"}${editingNodeId === node.id ? " editing" : ""}`} data-node={node.id}>
      {!isUser && (
        <div className="chat-msg-avatar"><AvatarView value={assistant.avatar} /></div>
      )}
      {isUser && (
        <div className="chat-msg-avatar user">
          {user?.avatar ? <AvatarView value={user.avatar} /> : <PersonIcon size={16} />}
        </div>
      )}
      <div className="chat-msg-body">
        <div className={`chat-msg-meta${isUser ? " user" : ""}`}>
          {isUser && user?.name && <span className="chat-msg-name">{user.name}</span>}
          {!isUser && <span className="chat-msg-name">{assistant.name}</span>}
          {!isUser && message.model && <span className="chat-msg-model">{message.model}</span>}
          {streaming && <span className="chat-streaming-pill"><span className="chat-streaming-dot" />生成中</span>}
          <span className="chat-msg-time">{time}</span>
        </div>
        {reasoning && (
          <div className={`chat-reasoning${reasoningExpanded ? " expanded" : ""}`}>
            <button
              className="chat-reasoning-head"
              onClick={() => setReasoningExpanded((value) => !value)}
              aria-expanded={reasoningExpanded}
            >
              <span className="chat-reasoning-title">思考</span>
              <span className="chat-reasoning-count">{reasoning.length} 字</span>
              <span className="chat-reasoning-toggle">{reasoningExpanded ? "收起" : "展开"}</span>
            </button>
            {reasoningExpanded && (
              <div className="chat-reasoning-body" ref={reasoningRef}>{reasoning}</div>
            )}
          </div>
        )}
        {message.attachments.length > 0 && (
          <div className="chat-attachments">
            {message.attachments.map((attachment) => (
              <AttachmentChip key={attachment.id} attachment={attachment} onOpen={openAttachment} />
            ))}
          </div>
        )}
        {content ? (
          <div className="chat-bubble">
            <Markdown content={content} streaming={streaming} />
            {streaming && <span className="chat-cursor" />}
          </div>
        ) : streaming ? (
          <div className="chat-bubble chat-thinking">
            <span className="chat-dot" />
            <span className="chat-dot" />
            <span className="chat-dot" />
          </div>
        ) : null}
        {message.status !== "done" && message.status !== "streaming" && (
          <div className={`chat-status ${message.status}`}>
            {message.status === "stopped" ? "已停止" : message.status === "interrupted" ? "上次输出被中断" : "生成失败"}
          </div>
        )}
        {!streaming && (
          <div className="chat-actions">
            {canSwitch && (
              <span className="chat-branch">
                <button
                  disabled={index <= 0}
                  onClick={() => onSwitchVariant(node.id, node.variants[index - 1].id)}
                  title="上一个版本"
                >‹</button>
                <span>{index + 1}/{node.variants.length}</span>
                <button
                  disabled={index < 0 || index >= node.variants.length - 1}
                  onClick={() => onSwitchVariant(node.id, node.variants[index + 1].id)}
                  title="下一个版本"
                >›</button>
              </span>
            )}
            {!isUser && (
              <button
                className="chat-action"
                onClick={() => onRegenerate(node.id, "in_place")}
                title="重新生成当前回复"
              >
                <RefreshIcon size={14} />
              </button>
            )}
            <button className="chat-action" onClick={() => void copy()} title="复制">
              <ContentCopyIcon size={14} />
              {copied ? "已复制" : "复制"}
            </button>
            {isUser ? (
              <button className="chat-action" onClick={() => onEdit(node.id, message.content)}>
                <EditIcon size={14} />
                编辑
              </button>
            ) : null}
            <button
              ref={moreRef}
              className="chat-action"
              onClick={() => setMenu(moreRef.current)}
              title="更多操作"
            >
              <MoreVertIcon size={14} />
            </button>
            <button className="chat-action danger" onClick={() => onDelete(node.id)} title="从这里删除">
              <DeleteIcon size={14} />
            </button>
          </div>
        )}
      </div>

      <PopupMenu open={menu !== null} anchor={menu} onClose={() => setMenu(null)}>
        {isUser ? (
          <PopupItem
            icon={<RefreshIcon size={16} />}
            onClick={() => {
              setMenu(null);
              onRegenerate(node.id, "truncate_after");
            }}
          >
            从这里重新生成（丢弃后续）
          </PopupItem>
        ) : (
          <>
            <PopupItem
              icon={<RefreshIcon size={16} />}
              onClick={() => {
                setMenu(null);
                onRegenerate(node.id, "in_place");
              }}
            >
              重新生成当前回复
              <span className="chat-popup-hint">保留后续消息</span>
            </PopupItem>
            <PopupItem
              icon={<RefreshIcon size={16} />}
              onClick={() => {
                setMenu(null);
                onRegenerate(node.id, "truncate_after");
              }}
            >
              从这里重新生成（丢弃后续）
            </PopupItem>
          </>
        )}
        <PopupSep />
        <PopupItem
          icon={<DeleteIcon size={16} />}
          danger
          onClick={() => {
            setMenu(null);
            onDelete(node.id);
          }}
        >
          删除这条及之后的消息
        </PopupItem>
      </PopupMenu>
    </div>
  );
}