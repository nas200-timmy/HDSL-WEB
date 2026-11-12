import { useEffect, useRef, useState } from "react";
import { api } from "../api";
import { ConfirmDialog, Dialog } from "../components/Dialog";
import { AddIcon, MenuIcon, SettingsIcon } from "../components/icons";
import { useMobileLayout } from "../hooks";
import { toast } from "../store";
import type {
  ChatAssistant, ChatAttachment, ChatModelInfo, ChatSession, ChatSessionSummary, ChatTurnOutcome, WsEvent,
} from "../types";
import { errMsg } from "../utils";
import { ws } from "../ws";
import { AssistantDialog, AssistantManagerDialog } from "./chat/AssistantDialog";
import { ChatSettingsDialog } from "./chat/ChatSettingsDialog";
import { ChatSidebar, RenameSessionDialog } from "./chat/ChatSidebar";
import { Composer } from "./chat/Composer";
import { Markdown } from "./chat/Markdown";
import { MessageItem } from "./chat/MessageItem";

function requestId(): string {
  return typeof crypto !== "undefined" && "randomUUID" in crypto
    ? crypto.randomUUID()
    : `req-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

/** 流式气泡：会话还没刷新到新节点时先画在列表末尾。 */
function LiveBubble({ assistant, text, reasoning }: { assistant: ChatAssistant | null; text: string; reasoning: string }) {
  return (
    <div className="chat-msg assistant">
      <div className="chat-msg-avatar">{assistant?.avatar || "💬"}</div>
      <div className="chat-msg-body">
        <div className="chat-msg-name">{assistant?.name ?? "助手"}</div>
        {reasoning && (
          <details className="chat-reasoning" open>
            <summary>思考</summary>
            <div className="chat-reasoning-body">{reasoning}</div>
          </details>
        )}
        {text ? (
          <div className="chat-bubble">
            <Markdown content={text} streaming />
            <span className="chat-cursor" />
          </div>
        ) : (
          <div className="chat-bubble chat-thinking">
            <span className="chat-dot" /><span className="chat-dot" /><span className="chat-dot" />
          </div>
        )}
      </div>
    </div>
  );
}

/** 面板聊天页（docs/chat.md §2/§12）。 */
export function ChatPage() {
  const mobile = useMobileLayout();
  const [assistants, setAssistants] = useState<ChatAssistant[]>([]);
  const [assistantId, setAssistantId] = useState("default");
  const [sessions, setSessions] = useState<ChatSessionSummary[]>([]);
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [session, setSession] = useState<ChatSession | null>(null);
  const [draft, setDraft] = useState("");
  const [pending, setPending] = useState<ChatAttachment[]>([]);
  const [uploading, setUploading] = useState(false);
  const [models, setModels] = useState<ChatModelInfo[]>([]);
  const [model, setModel] = useState<string | null>(null);
  const [live, setLive] = useState<{ turnId: string; messageId?: string; text: string; reasoning: string; status: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [renameTarget, setRenameTarget] = useState<ChatSessionSummary | null>(null);
  const [deleteSessionTarget, setDeleteSessionTarget] = useState<ChatSessionSummary | null>(null);
  const [deleteNodeTarget, setDeleteNodeTarget] = useState<string | null>(null);
  const [editTarget, setEditTarget] = useState<{ nodeId: string; text: string } | null>(null);
  const [assistantDialog, setAssistantDialog] = useState<{ assistant: ChatAssistant | null } | null>(null);
  const [managerOpen, setManagerOpen] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [confirmRegenerate, setConfirmRegenerate] = useState<{ nodeId: string; mode: "in_place" | "truncate_after" } | null>(null);

  const sessionIdRef = useRef<string | null>(null);
  const assistantIdRef = useRef("default");
  const liveRef = useRef<typeof live>(null);
  const finishedTurns = useRef<Set<string>>(new Set());
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const atBottom = useRef(true);

  sessionIdRef.current = sessionId;
  assistantIdRef.current = assistantId;
  liveRef.current = live;

  const assistant = assistants.find((item) => item.id === assistantId) ?? assistants[0] ?? null;
  const currentAssistant = assistant;
  const canSend = !busy && !!currentAssistant;

  const loadModels = async (account: string | null, preferred: string | null) => {
    if (!account) {
      setModels([]);
      setModel(preferred);
      return;
    }
    try {
      const result = await api.chat.models(account);
      setModels(result.models);
      if (preferred && result.models.some((item) => item.id === preferred)) setModel(preferred);
      else if (result.models.length > 0) setModel(result.models[0].id);
      else setModel(preferred);
    } catch {
      setModels([]);
      setModel(preferred);
    }
  };

  const applySession = (next: ChatSession) => {
    setSession(next);
    setSessionId(next.id);
    const preferred = next.model ?? currentAssistant?.model ?? null;
    setModel(preferred);
    void loadModels(next.account ?? currentAssistant?.account ?? null, preferred);
    requestAnimationFrame(() => {
      const el = scrollRef.current;
      if (el) el.scrollTop = el.scrollHeight;
    });
  };

  const refreshSession = async (id: string) => {
    try {
      const next = await api.chat.session(id);
      if (sessionIdRef.current === id) setSession(next);
      return next;
    } catch {
      return null;
    }
  };

  const loadSessions = async (id: string) => {
    try {
      const result = await api.chat.sessions(id);
      setSessions(result.sessions);
      return result.sessions;
    } catch {
      setSessions([]);
      return [];
    }
  };

  const loadAssistants = async (select?: string) => {
    try {
      const result = await api.chat.assistants();
      setAssistants(result.assistants);
      if (select) setAssistantId(select);
      else if (!result.assistants.some((item) => item.id === assistantIdRef.current)) {
        setAssistantId(result.assistants[0]?.id ?? "default");
      }
      return result.assistants;
    } catch (error) {
      toast("error", `读取助手失败：${errMsg(error)}`);
      return [];
    }
  };

  useEffect(() => {
    void (async () => {
      const list = await loadAssistants();
      const first = list.find((item) => item.id === "default") ?? list[0] ?? null;
      if (first) {
        setAssistantId(first.id);
        setModel(first.model);
        if (first.account) void loadModels(first.account, first.model);
      }
      const params = new URLSearchParams(window.location.search);
      const requested = params.get("session");
      if (requested) {
        try {
          applySession(await api.chat.session(requested));
        } catch {
          // The session may have been deleted; fall through to the list.
        }
      }
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (!assistantId) return;
    void (async () => {
      const list = await loadSessions(assistantId);
      if (sessionId && !list.some((item) => item.id === sessionId)) {
        setSessionId(null);
        setSession(null);
      }
      if (!sessionId && !session) {
        const current = assistants.find((item) => item.id === assistantId);
        setModel(current?.model ?? null);
        if (current?.account) void loadModels(current.account, current.model);
      }
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [assistantId]);

  useEffect(() => {
    if (!sessionId) {
      setSession(null);
      return;
    }
    let cancelled = false;
    void (async () => {
      try {
        const next = await api.chat.session(sessionId);
        if (!cancelled) applySession(next);
      } catch (error) {
        if (!cancelled) {
          toast("error", `读取会话失败：${errMsg(error)}`);
          setSessionId(null);
          setSession(null);
        }
      }
    })();
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sessionId]);

  useEffect(() => {
    ws.addTopics(["chat"]);
    return () => ws.removeTopics(["chat"]);
  }, []);

  useEffect(() => {
    if (!sessionId) return;
    ws.addTopics([`chat:${sessionId}`]);
    return () => ws.removeTopics([`chat:${sessionId}`]);
  }, [sessionId]);

  useEffect(() => {
    return ws.on((event: WsEvent) => {
      const current = sessionIdRef.current;
      if (event.type === "chat-assistants-changed") {
        void loadAssistants();
        return;
      }
      if (event.type === "chat-sessions-changed") {
        void loadSessions(assistantIdRef.current);
        if (event.sessionId && event.sessionId === current && !liveRef.current) {
          void refreshSession(event.sessionId);
        }
        return;
      }
      if (event.type === "chat-settings-changed") return;
      if (!("sessionId" in event) || event.sessionId !== current) return;

      if (event.type === "chat-turn-started") {
        finishedTurns.current.delete(event.turnId);
        setLive((previous) => previous && previous.turnId === event.turnId
          ? { ...previous, messageId: event.messageId }
          : { turnId: event.turnId, messageId: event.messageId, text: "", reasoning: "", status: "streaming" });
        return;
      }
      if (event.type === "chat-delta" || event.type === "chat-reasoning") {
        if (finishedTurns.current.has(event.turnId)) return;
        setLive((previous) => {
          if (previous && previous.turnId !== event.turnId) return previous;
          const base = previous ?? { turnId: event.turnId, messageId: undefined, text: "", reasoning: "", status: "streaming" };
          return event.type === "chat-delta"
            ? { ...base, text: base.text + event.text }
            : { ...base, reasoning: base.reasoning + event.text };
        });
        return;
      }
      if (event.type === "chat-error") {
        toast("error", `${event.code}: ${event.message}`);
        return;
      }
      if (event.type === "chat-turn-finished") {
        finishedTurns.current.add(event.turnId);
        if (finishedTurns.current.size > 40) {
          finishedTurns.current = new Set(Array.from(finishedTurns.current).slice(-20));
        }
        setLive(null);
        void refreshSession(event.sessionId);
        void loadSessions(assistantIdRef.current);
      }
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    const el = scrollRef.current;
    if (!el || !atBottom.current) return;
    el.scrollTop = el.scrollHeight;
  }, [session?.nodes.length, live?.text, live?.reasoning]);

  // 断线或漏帧的兜底：流式中每 3 秒拉一次快照；服务端说已结束就收掉本地的流。
  useEffect(() => {
    if (!live || !sessionId) return;
    const timer = window.setInterval(() => {
      void (async () => {
        try {
          const next = await api.chat.session(sessionId);
          if (sessionIdRef.current !== sessionId) return;
          setSession(next);
          if (!next.streaming && liveRef.current) setLive(null);
        } catch {
          // The next tick will try again.
        }
      })();
    }, 3000);
    return () => window.clearInterval(timer);
  }, [live?.turnId, sessionId]);

  const ensureSession = async (): Promise<ChatSession> => {
    if (session) return session;
    const created = await api.chat.createSession({
      assistantId,
      model: model ?? undefined,
      account: currentAssistant?.account ?? undefined,
    });
    // Subscribe before the first send: the kickoff/started frames can race the
    // React effect that normally installs the topic.
    ws.addTopics(["chat", `chat:${created.id}`]);
    applySession(created);
    setSessions((previous) => [{
      id: created.id, assistantId: created.assistantId, title: created.title,
      model: created.model, account: created.account, createdAt: created.createdAt,
      updatedAt: created.updatedAt, messageCount: created.nodes.length, lastMessage: null,
    }, ...previous]);
    return created;
  };

  const adoptOutcome = async (targetId: string, outcome: ChatTurnOutcome) => {
    const next = await api.chat.session(targetId);
    if (sessionIdRef.current === targetId || sessionIdRef.current === null) setSession(next);
    if (next.model) setModel((previous) => previous ?? next.model);
    if (next.account && models.length === 0) void loadModels(next.account, next.model);
    const messageId = outcome.assistantMessage?.id;
    const message = next.nodes.flatMap((node) => node.variants).find((item) => item.id === messageId);
    if (message && message.status !== "streaming") {
      setLive(null);
      return;
    }
    setLive((previous) => previous && previous.turnId === outcome.turnId
      ? { ...previous, messageId }
      : { turnId: outcome.turnId, messageId, text: "", reasoning: "", status: "streaming" });
  };

  const send = async () => {
    const text = draft.trim();
    if (!text && pending.length === 0) return;
    setBusy(true);
    try {
      const target = await ensureSession();
      const outcome = await api.chat.send(target.id, {
        text,
        attachments: pending.map((item) => item.id),
        model: model ?? undefined,
        requestId: requestId(),
      });
      setDraft("");
      setPending([]);
      await adoptOutcome(target.id, outcome);
      void loadSessions(assistantId);
    } catch (error) {
      toast("error", `发送失败：${errMsg(error)}`);
    } finally {
      setBusy(false);
    }
  };

  const stop = async () => {
    if (!live) return;
    try {
      await api.chat.cancel(live.turnId);
    } catch (error) {
      toast("error", `停止失败：${errMsg(error)}`);
    }
  };

  const upload = async (file: File) => {
    setUploading(true);
    try {
      const target = await ensureSession();
      const attachment = await api.chat.uploadAttachment(target.id, file);
      setPending((previous) => [...previous, attachment]);
    } catch (error) {
      toast("error", `上传失败：${errMsg(error)}`);
    } finally {
      setUploading(false);
    }
  };

  const regenerate = async (nodeId: string, mode: "in_place" | "truncate_after") => {
    setConfirmRegenerate(null);
    if (!session) return;
    setBusy(true);
    try {
      const outcome = await api.chat.regenerate(session.id, { nodeId, mode, requestId: requestId() });
      await adoptOutcome(session.id, outcome);
      void loadSessions(assistantId);
    } catch (error) {
      toast("error", `重新生成失败：${errMsg(error)}`);
    } finally {
      setBusy(false);
    }
  };

  const saveEdit = async () => {
    if (!editTarget || !session) return;
    setBusy(true);
    try {
      const outcome = await api.chat.edit(session.id, {
        nodeId: editTarget.nodeId, text: editTarget.text, requestId: requestId(),
      });
      setEditTarget(null);
      await adoptOutcome(session.id, outcome);
    } catch (error) {
      toast("error", `编辑失败：${errMsg(error)}`);
    } finally {
      setBusy(false);
    }
  };

  const removeNode = async () => {
    if (!deleteNodeTarget || !session) return;
    setBusy(true);
    try {
      setSession(await api.chat.deleteNode(session.id, deleteNodeTarget));
      setDeleteNodeTarget(null);
      void loadSessions(assistantId);
    } catch (error) {
      toast("error", `删除失败：${errMsg(error)}`);
    } finally {
      setBusy(false);
    }
  };

  const switchVariant = async (nodeId: string, messageId: string) => {
    if (!session) return;
    try {
      setSession(await api.chat.switchVariant(session.id, nodeId, messageId));
    } catch (error) {
      toast("error", `切换版本失败：${errMsg(error)}`);
    }
  };

  const newSession = async () => {
    if (!currentAssistant) return;
    setBusy(true);
    try {
      const created = await api.chat.createSession({
        assistantId: currentAssistant.id,
        model: model ?? currentAssistant.model ?? undefined,
        account: currentAssistant.account ?? undefined,
      });
      setDraft("");
      setPending([]);
      setLive(null);
      applySession(created);
      await loadSessions(currentAssistant.id);
      setSidebarOpen(false);
    } catch (error) {
      toast("error", `新建会话失败：${errMsg(error)}`);
    } finally {
      setBusy(false);
    }
  };

  const renameSession = async (title: string) => {
    if (!renameTarget) return;
    setBusy(true);
    try {
      const updated = await api.chat.updateSession(renameTarget.id, { title });
      setRenameTarget(null);
      if (session?.id === updated.id) setSession(updated);
      void loadSessions(assistantId);
    } catch (error) {
      toast("error", `重命名失败：${errMsg(error)}`);
    } finally {
      setBusy(false);
    }
  };

  const deleteSession = async () => {
    if (!deleteSessionTarget) return;
    setBusy(true);
    try {
      await api.chat.deleteSession(deleteSessionTarget.id);
      setDeleteSessionTarget(null);
      const remaining = sessions.filter((item) => item.id !== deleteSessionTarget.id);
      setSessions(remaining);
      if (sessionId === deleteSessionTarget.id) {
        setSessionId(null);
        setSession(null);
        setLive(null);
        setDraft("");
        setPending([]);
      }
    } catch (error) {
      toast("error", `删除失败：${errMsg(error)}`);
    } finally {
      setBusy(false);
    }
  };

  const switchAssistant = (id: string) => {
    if (id === assistantId) return;
    const next = assistants.find((item) => item.id === id);
    setAssistantId(id);
    setSessionId(null);
    setSession(null);
    setLive(null);
    setDraft("");
    setPending([]);
    setModel(next?.model ?? null);
    setSidebarOpen(false);
  };

  const liveNodeId = live?.messageId
    ? session?.nodes.find((node) => node.variants.some((variant) => variant.id === live.messageId))?.id ?? null
    : null;
  const liveAttached = liveNodeId !== null;
  const openManager = () => setManagerOpen(true);

  return (
    <div className="chat-page">
      {mobile && sidebarOpen && <div className="chat-scrim" onClick={() => setSidebarOpen(false)} />}
      <div className={`chat-sidebar-wrap${mobile && sidebarOpen ? " open" : ""}`}>
        <ChatSidebar
          assistants={assistants}
          assistantId={assistantId}
          onAssistant={switchAssistant}
          onManageAssistants={openManager}
          sessions={sessions}
          sessionId={sessionId}
          onSelect={(id) => {
            setSessionId(id);
            setLive(null);
            setDraft("");
            setPending([]);
            setSidebarOpen(false);
          }}
          onNew={() => void newSession()}
          onRename={setRenameTarget}
          onDelete={setDeleteSessionTarget}
          onClose={() => setSidebarOpen(false)}
        />
      </div>

      <div className="chat-main">
        <div className="chat-header">
          {mobile && (
            <button className="icon-btn" onClick={() => setSidebarOpen(true)} title="会话列表">
              <MenuIcon size={20} />
            </button>
          )}
          <div className="chat-header-title">
            <div className="chat-header-name">{session?.title || currentAssistant?.name || "聊天"}</div>
            <div className="chat-header-sub">
              {currentAssistant?.avatar} {currentAssistant?.name ?? "默认助手"}
              {model ? ` · ${model}` : ""}
            </div>
          </div>
          <span className="spacer" />
          <button className="tool-btn ripple-host" onClick={() => setSettingsOpen(true)} title="聊天设置">
            <SettingsIcon size={20} />
          </button>
          <button className="tool-btn ripple-host" onClick={() => void newSession()} title="新建会话">
            <AddIcon size={20} />
          </button>
        </div>

        {!session ? (
          <div className="chat-welcome">
            <div className="chat-welcome-inner">
              <div className="chat-welcome-avatar">{currentAssistant?.avatar || "💬"}</div>
              <h1>{currentAssistant?.name ?? "默认助手"}</h1>
              <p>{currentAssistant?.description || "发一条消息开始新会话"}</p>
              {currentAssistant?.openingMessage && (
                <div className="chat-welcome-opening">
                  <Markdown content={currentAssistant.openingMessage} />
                </div>
              )}
            </div>
          </div>
        ) : (
          <div
            className="chat-messages scroll"
            ref={scrollRef}
            onScroll={(event) => {
              const el = event.currentTarget;
              atBottom.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
            }}
          >
            {session.nodes.map((node) => {
              const isLive = live !== null && node.id === liveNodeId;
              return (
                <MessageItem
                  key={node.id}
                  node={node}
                  assistant={currentAssistant ?? { name: "助手", avatar: "💬" }}
                  streaming={isLive}
                  liveContent={isLive ? live?.text : undefined}
                  liveReasoning={isLive ? live?.reasoning : undefined}
                  onRegenerate={(nodeId, mode) => setConfirmRegenerate({ nodeId, mode })}
                  onEdit={(nodeId, text) => setEditTarget({ nodeId, text })}
                  onDelete={(nodeId) => setDeleteNodeTarget(nodeId)}
                  onSwitchVariant={(nodeId, messageId) => void switchVariant(nodeId, messageId)}
                />
              );
            })}
            {live && !liveAttached && (
              <LiveBubble assistant={currentAssistant} text={live.text} reasoning={live.reasoning} />
            )}
          </div>
        )}

        <div className="chat-composer-wrap">
          <Composer
            draft={draft}
            onDraft={setDraft}
            onSend={() => void send()}
            onStop={() => void stop()}
            streaming={live !== null}
            disabled={!canSend}
            models={models}
            model={model}
            onModel={(value) => {
              setModel(value);
              if (session) {
                void api.chat.updateSession(session.id, { model: value })
                  .then((next) => setSession((current) => current ? { ...current, model: next.model } : current))
                  .catch(() => undefined);
              }
            }}
            pending={pending}
            onUpload={(file) => void upload(file)}
            onRemove={(id) => setPending((previous) => previous.filter((item) => item.id !== id))}
            uploading={uploading}
            quickPhrases={currentAssistant?.quickPhrases ?? []}
          />
        </div>
      </div>

      <RenameSessionDialog
        session={renameTarget}
        busy={busy}
        onCancel={() => setRenameTarget(null)}
        onConfirm={(title) => void renameSession(title)}
      />

      <ConfirmDialog
        open={deleteSessionTarget !== null}
        title="删除会话"
        message={`删除「${deleteSessionTarget?.title ?? ""}」及其全部消息与附件？这个操作不能撤销。`}
        confirmLabel="删除"
        danger
        busy={busy}
        onConfirm={() => void deleteSession()}
        onCancel={() => setDeleteSessionTarget(null)}
      />

      <ConfirmDialog
        open={deleteNodeTarget !== null}
        title="删除消息"
        message="删除这条消息以及它之后的所有消息？这个操作不能撤销。"
        confirmLabel="删除"
        danger
        busy={busy}
        onConfirm={() => void removeNode()}
        onCancel={() => setDeleteNodeTarget(null)}
      />

      <ConfirmDialog
        open={confirmRegenerate !== null}
        title={confirmRegenerate?.mode === "in_place" ? "重新生成当前回复" : "从这里重新生成"}
        message={confirmRegenerate?.mode === "in_place"
          ? "只给当前回复加一个新版本，后续消息仍然基于旧回复；可以随时用 ‹ › 切回。"
          : "这条消息之后的内容会被删除，然后在当前位置重新生成。"}
        confirmLabel="继续"
        busy={busy}
        onConfirm={() => confirmRegenerate && void regenerate(confirmRegenerate.nodeId, confirmRegenerate.mode)}
        onCancel={() => setConfirmRegenerate(null)}
      />

      <Dialog
        open={editTarget !== null}
        title="编辑消息"
        onClose={busy ? undefined : () => setEditTarget(null)}
        wide
      >
        <textarea
          className="input chat-prompt-area"
          rows={8}
          value={editTarget?.text ?? ""}
          onChange={(event) => setEditTarget((current) => current ? { ...current, text: event.target.value } : current)}
        />
        <div className="dialog-actions">
          <button className="btn btn-text dim" disabled={busy} onClick={() => setEditTarget(null)}>取消</button>
          <button className="btn btn-text" disabled={busy || !editTarget?.text.trim()} onClick={() => void saveEdit()}>
            {busy ? "保存中…" : "保存并重新生成"}
          </button>
        </div>
      </Dialog>

      <AssistantManagerDialog
        open={managerOpen}
        assistants={assistants}
        onClose={() => setManagerOpen(false)}
        onCreate={() => {
          setManagerOpen(false);
          setAssistantDialog({ assistant: null });
        }}
        onEdit={(item) => {
          setManagerOpen(false);
          setAssistantDialog({ assistant: item });
        }}
        onChanged={() => void loadAssistants()}
        onDeleted={(id) => {
          void loadAssistants();
          if (id === assistantId) switchAssistant("default");
        }}
      />

      <AssistantDialog
        open={assistantDialog !== null}
        assistant={assistantDialog?.assistant ?? null}
        onClose={() => setAssistantDialog(null)}
        onSaved={(saved, isNew) => {
          setAssistantDialog(null);
          void loadAssistants(isNew ? saved.id : undefined);
          if (isNew) switchAssistant(saved.id);
        }}
        onDeleted={(id) => {
          setAssistantDialog(null);
          void loadAssistants();
          if (id === assistantId) switchAssistant("default");
        }}
      />

      <ChatSettingsDialog
        open={settingsOpen}
        onClose={() => setSettingsOpen(false)}
        onSaved={() => undefined}
      />
    </div>
  );
}