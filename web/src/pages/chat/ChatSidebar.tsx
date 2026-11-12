import { useEffect, useRef, useState } from "react";
import { Dialog } from "../../components/Dialog";
import { PopupItem, PopupMenu, PopupSep } from "../../components/PopupMenu";
import { AddIcon, DeleteIcon, EditIcon, MoreVertIcon, SettingsIcon } from "../../components/icons";
import type { ChatAssistant, ChatSessionSummary } from "../../types";

function ago(at: number): string {
  if (!at) return "";
  const diff = Math.max(0, Date.now() - at);
  const min = Math.floor(diff / 60000);
  if (min < 1) return "刚刚";
  if (min < 60) return `${min} 分钟前`;
  const hour = Math.floor(min / 60);
  if (hour < 24) return `${hour} 小时前`;
  const day = Math.floor(hour / 24);
  return day < 30 ? `${day} 天前` : new Date(at).toLocaleDateString();
}

function preview(text: string | null): string {
  if (!text) return "还没有消息";
  return text.replace(/\s+/g, " ").trim().slice(0, 60);
}

/** 聊天侧栏：助手切换器 + 当前助手的会话列表。 */
export function ChatSidebar({
  assistants, assistantId, onAssistant, onManageAssistants,
  sessions, sessionId, onSelect, onNew, onRename, onDelete, onClose,
}: {
  assistants: ChatAssistant[];
  assistantId: string;
  onAssistant: (id: string) => void;
  onManageAssistants: () => void;
  sessions: ChatSessionSummary[];
  sessionId: string | null;
  onSelect: (id: string) => void;
  onNew: () => void;
  onRename: (session: ChatSessionSummary) => void;
  onDelete: (session: ChatSessionSummary) => void;
  onClose?: () => void;
}) {
  const assistant = assistants.find((item) => item.id === assistantId) ?? assistants[0] ?? null;
  const [assistantMenu, setAssistantMenu] = useState<HTMLElement | null>(null);
  const [sessionMenu, setSessionMenu] = useState<{ anchor: HTMLElement; session: ChatSessionSummary } | null>(null);
  const menuRef = useRef<HTMLButtonElement | null>(null);

  const pick = (id: string) => {
    onSelect(id);
    onClose?.();
  };

  return (
    <aside className="chat-sidebar">
      <div className="chat-sidebar-head">
        <button ref={menuRef} className="chat-assistant-switch ripple-host" onClick={() => setAssistantMenu(menuRef.current)}>
          <span className="chat-avatar">{assistant?.avatar || "💬"}</span>
          <span className="chat-assistant-text">
            <span className="chat-assistant-name">{assistant?.name ?? "默认助手"}</span>
            <span className="chat-assistant-sub">{assistant?.description || "当前助手"}</span>
          </span>
          <span className="chat-caret">▾</span>
        </button>
        <button className="tool-btn ripple-host" onClick={onNew} title="新建会话">
          <AddIcon size={20} />
        </button>
      </div>

      <div className="chat-session-list scroll">
        {sessions.length === 0 ? (
          <div className="chat-session-empty">这个助手还没有会话。发送第一条消息就会出现在这里。</div>
        ) : (
          sessions.map((session) => (
            <div
              key={session.id}
              className={`chat-session-item${session.id === sessionId ? " selected" : ""}`}
              onClick={() => pick(session.id)}
              role="button"
              tabIndex={0}
              onKeyDown={(event) => {
                if (event.key === "Enter" || event.key === " ") {
                  event.preventDefault();
                  pick(session.id);
                }
              }}
            >
              <div className="chat-session-main">
                <div className="chat-session-title">{session.title || "新会话"}</div>
                <div className="chat-session-preview">{preview(session.lastMessage)}</div>
              </div>
              <span className="chat-session-meta">{ago(session.updatedAt)}</span>
              <button
                className="chat-session-more"
                onClick={(event) => {
                  event.stopPropagation();
                  setSessionMenu({ anchor: event.currentTarget, session });
                }}
                title="更多"
              >
                <MoreVertIcon size={16} />
              </button>
            </div>
          ))
        )}
      </div>

      <div className="chat-sidebar-foot">
        <button className="chat-foot-btn ripple-host" onClick={onManageAssistants}>
          <SettingsIcon size={16} />
          管理助手
        </button>
      </div>

      <PopupMenu open={assistantMenu !== null} anchor={assistantMenu} onClose={() => setAssistantMenu(null)}>
        {assistants.map((item) => (
          <PopupItem
            key={item.id}
            onClick={() => {
              setAssistantMenu(null);
              onAssistant(item.id);
            }}
          >
            <span className="chat-avatar small">{item.avatar || "💬"}</span>
            <span style={{ flex: 1 }}>{item.name}</span>
            {item.id === assistantId && <span className="chat-check">✓</span>}
          </PopupItem>
        ))}
        <PopupSep />
        <PopupItem
          icon={<SettingsIcon size={16} />}
          onClick={() => {
            setAssistantMenu(null);
            onManageAssistants();
          }}
        >
          管理助手
        </PopupItem>
      </PopupMenu>

      <PopupMenu
        open={sessionMenu !== null}
        anchor={sessionMenu?.anchor ?? null}
        onClose={() => setSessionMenu(null)}
      >
        <PopupItem
          icon={<EditIcon size={16} />}
          onClick={() => {
            if (sessionMenu) onRename(sessionMenu.session);
            setSessionMenu(null);
          }}
        >
          重命名
        </PopupItem>
        <PopupItem
          icon={<DeleteIcon size={16} />}
          danger
          onClick={() => {
            if (sessionMenu) onDelete(sessionMenu.session);
            setSessionMenu(null);
          }}
        >
          删除会话
        </PopupItem>
      </PopupMenu>
    </aside>
  );
}

/** 重命名会话的弹窗（ChatPage 管状态，这里只画）。 */
export function RenameSessionDialog({
  session, busy, onCancel, onConfirm,
}: {
  session: ChatSessionSummary | null;
  busy: boolean;
  onCancel: () => void;
  onConfirm: (title: string) => void;
}) {
  const [title, setTitle] = useState("");
  useEffect(() => {
    if (session) setTitle(session.title);
  }, [session]);
  return (
    <Dialog open={session !== null} title="重命名会话" onClose={busy ? undefined : onCancel}>
      <input
        className="input"
        value={title}
        autoFocus
        onChange={(event) => setTitle(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === "Enter" && title.trim()) onConfirm(title.trim());
        }}
      />
      <div className="dialog-actions">
        <button className="btn btn-text dim" disabled={busy} onClick={onCancel}>取消</button>
        <button className="btn btn-text" disabled={busy || !title.trim()} onClick={() => onConfirm(title.trim())}>
          {busy ? "保存中…" : "保存"}
        </button>
      </div>
    </Dialog>
  );
}