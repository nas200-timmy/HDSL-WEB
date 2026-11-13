import { Fragment, useEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { api } from "../api";
import { ConfirmDialog } from "../components/Dialog";
import {
  AddIcon, ArchiveIcon, DeleteIcon, DeployedCodeIcon, EditIcon, FormatListBulletedIcon,
  LockIcon, MenuIcon, MoreVertIcon, SettingsIcon,
} from "../components/icons";
import { PopupItem, PopupMenu, PopupSep } from "../components/PopupMenu";
import { isElevationRequired, requestDeveloperPassword } from "../elevation";
import { useMobileLayout } from "../hooks";
import { toast } from "../store";
import type {
  ChatAccountProfile, ChatAssistant, ChatAttachment, ChatSession,
  ChatSessionSummary, ChatTurnOutcome, WsEvent,
} from "../types";
import { errMsg } from "../utils";
import { ws } from "../ws";
import { AssistantDialog, AssistantManagerDialog } from "./chat/AssistantDialog";
import { ChatSidebar, RenameSessionDialog } from "./chat/ChatSidebar";
import { Composer } from "./chat/Composer";
import { Markdown } from "./chat/Markdown";
import { MessageItem } from "./chat/MessageItem";
import { AvatarView } from "./chat/AvatarView";
import { ContextMeter } from "./chat/ContextMeter";
import type { ModelGroup } from "./chat/ModelPicker";

function dayLabel(at: number): string {
  const date = new Date(at);
  const now = new Date();
  const start = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  if (at >= start) return "今天";
  if (at >= start - 86400000) return "昨天";
  return date.toLocaleDateString();
}

function requestId(): string {
  return typeof crypto !== "undefined" && "randomUUID" in crypto
    ? crypto.randomUUID()
    : `req-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

/** 流式气泡：会话还没刷新到新节点时先画在列表末尾。 */
function LiveBubble({ assistant, text, reasoning, tool }: { assistant: ChatAssistant | null; text: string; reasoning: string; tool?: { query: string; provider: string; status: string; hits: number } }) {
  return (
    <div className="chat-msg assistant">
      <div className="chat-msg-avatar"><AvatarView value={assistant?.avatar} /></div>
      <div className="chat-msg-body">
        <div className="chat-msg-name">{assistant?.name ?? "助手"}</div>
        {reasoning && (
          <div className="chat-reasoning expanded">
            <div className="chat-reasoning-head static">
              <span className="chat-reasoning-title">思考</span>
              <span className="chat-reasoning-count">{reasoning.length} 字</span>
              <span className="chat-reasoning-toggle">生成中</span>
            </div>
            <div className="chat-reasoning-body">{reasoning}</div>
          </div>
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
        {tool && (
          <div className="chat-tools">
            <div className={`chat-tool-chip ${tool.status}`}>
              <span className="chat-tool-icon">{tool.status === "running" ? "…" : "🔍"}</span>
              <span className="chat-tool-query">{tool.query}</span>
              <span className="chat-tool-meta">
                {tool.status === "running" ? "正在搜索"
                  : tool.status === "failed" ? "搜索失败"
                  : `${tool.hits} 条结果`}
              </span>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

/** 面板聊天页（docs/chat.md §2/§12）。 */
export function ChatPage() {
  const mobile = useMobileLayout();
  const navigate = useNavigate();
  const [assistants, setAssistants] = useState<ChatAssistant[]>([]);
  const [assistantId, setAssistantId] = useState("default");
  const [sessions, setSessions] = useState<ChatSessionSummary[]>([]);
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [session, setSession] = useState<ChatSession | null>(null);
  const [draft, setDraft] = useState("");
  const [draftBeforeEdit, setDraftBeforeEdit] = useState("");
  const [pending, setPending] = useState<ChatAttachment[]>([]);
  const [uploading, setUploading] = useState(false);
  const [chatAccounts, setChatAccounts] = useState<ChatAccountProfile[]>([]);
  const [sessionAccount, setSessionAccount] = useState<string | null>(null);
  const [model, setModel] = useState<string | null>(null);
  const [reasoningLevel, setReasoningLevel] = useState("");
  const [profile, setProfile] = useState<{ name: string; avatar: string | null }>({ name: "", avatar: null });
  const [liveUsage, setLiveUsage] = useState<{ sessionId: string; input: number; output: number } | null>(null);
  const [searchEnabled, setSearchEnabled] = useState(false);
  const [searchStatus, setSearchStatus] = useState<{ configured: boolean } | null>(null);
  const [live, setLive] = useState<{ turnId: string; messageId?: string; text: string; reasoning: string; status: string; tool?: { query: string; provider: string; status: string; hits: number } } | null>(null);
  const [busy, setBusy] = useState(false);
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [renameTarget, setRenameTarget] = useState<ChatSessionSummary | null>(null);
  const [deleteSessionTarget, setDeleteSessionTarget] = useState<ChatSessionSummary | null>(null);
  const [deleteNodeTarget, setDeleteNodeTarget] = useState<string | null>(null);
  const [editTarget, setEditTarget] = useState<{ nodeId: string; text: string } | null>(null);
  const [assistantDialog, setAssistantDialog] = useState<{ assistant: ChatAssistant | null } | null>(null);
  const [managerOpen, setManagerOpen] = useState(false);
  const [confirmRegenerate, setConfirmRegenerate] = useState<{ nodeId: string; mode: "in_place" | "truncate_after" } | null>(null);
  const [loadingChat, setLoadingChat] = useState(true);
  const [needsElevation, setNeedsElevation] = useState(false);
  const [elevating, setElevating] = useState(false);
  const [focusSignal, setFocusSignal] = useState(0);
  const [sessionMenu, setSessionMenu] = useState<HTMLElement | null>(null);

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

  const loadChatAccounts = async () => {
    try {
      const result = await api.chat.accounts();
      setChatAccounts(result.accounts);
      setSessionAccount((previous) => previous ?? result.accounts[0]?.name ?? null);
      return result.accounts;
    } catch (error) {
      if (isElevationRequired(error)) setNeedsElevation(true);
      else toast("error", `读取模型服务失败：${errMsg(error)}`);
      return [] as ChatAccountProfile[];
    }
  };

  const applySession = (next: ChatSession) => {
    setSession(next);
    setSessionId(next.id);
    if (next.assistantId) setAssistantId(next.assistantId);
    setSessionAccount((previous) => next.account ?? currentAssistant?.account ?? previous ?? null);
    setModel(next.model ?? currentAssistant?.model ?? null);
    setReasoningLevel(next.reasoningLevel ?? "");
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
    } catch (error) {
      if (isElevationRequired(error)) setNeedsElevation(true);
      return null;
    }
  };

  const loadSessions = async (id: string) => {
    try {
      const result = await api.chat.sessions(id);
      setSessions(result.sessions);
      setNeedsElevation(false);
      return result.sessions;
    } catch (error) {
      setSessions([]);
      if (isElevationRequired(error)) setNeedsElevation(true);
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
      if (isElevationRequired(error)) setNeedsElevation(true);
      else toast("error", `读取助手失败：${errMsg(error)}`);
      return [];
    }
  };

  useEffect(() => {
    void (async () => {
      const list = await loadAssistants();
      const accounts = await loadChatAccounts();
      const first = list.find((item) => item.id === "default") ?? list[0] ?? null;
      if (first) {
        setAssistantId(first.id);
        setSessionAccount(first.account ?? accounts[0]?.name ?? null);
        setModel(first.model ?? accounts.find((item) => item.name === (first.account ?? accounts[0]?.name))?.model ?? null);
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
      setLoadingChat(false);
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    void api.chat.searchStatus()
      .then((status) => setSearchStatus({ configured: status.configured }))
      .catch(() => { /* 没配置就当关闭 */ });
  }, []);

  useEffect(() => {
    void api.chat.profile()
      .then((next) => setProfile({ name: next.name, avatar: next.avatar }))
      .catch(() => { /* 资料读不到就用登录名 */ });
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
        setSessionAccount(current?.account ?? chatAccounts[0]?.name ?? null);
        setModel(current?.model ?? chatAccounts.find((item) => item.name === current?.account)?.model ?? null);
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
      if (event.type === "chat-usage") {
        setLiveUsage({ sessionId: event.sessionId, input: event.input, output: event.output });
        return;
      }
      if (event.type === "chat-tool") {
        setLive((previous) => {
          if (previous && previous.turnId !== event.turnId) return previous;
          const base = previous ?? { turnId: event.turnId, messageId: undefined, text: "", reasoning: "", status: "streaming" };
          return { ...base, tool: { query: event.query, provider: event.provider ?? "", status: event.status, hits: event.hits ?? 0 } };
        });
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
      account: sessionAccount ?? currentAssistant?.account ?? undefined,
      reasoningLevel: reasoningLevel || undefined,
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
    if (next.account) setSessionAccount(next.account);
    if (chatAccounts.length === 0) void loadChatAccounts();
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

  const reportError = (error: unknown, prefix: string) => {
    if (isElevationRequired(error)) {
      setNeedsElevation(true);
      return;
    }
    toast("error", `${prefix}：${errMsg(error)}`);
  };

  const elevate = async () => {
    setElevating(true);
    try {
      if (await requestDeveloperPassword()) {
        setNeedsElevation(false);
        await loadChatAccounts();
        await loadAssistants();
        await loadSessions(assistantIdRef.current);
      }
    } finally {
      setElevating(false);
    }
  };

  const historyImageCount = useMemo(() => {
    let count = 0;
    for (const node of session?.nodes ?? []) {
      const message = node.variants.find((item) => item.id === node.activeVariantId)
        ?? node.variants[node.variants.length - 1];
      if (!message) continue;
      count += message.attachments.filter((item) => item.kind === "image").length;
    }
    return count;
  }, [session]);

  const modelGroups = useMemo<ModelGroup[]>(() => {
    const groups: ModelGroup[] = chatAccounts
      .map((account) => ({
        name: account.name,
        label: account.label || account.name,
        vendor: account.vendor,
        models: account.modelList?.length
          ? account.modelList
          : (account.models ?? []).map((id) => ({
              id,
              capabilities: {
                vision: false, files: false, reasoning: false, tools: false,
                maxContext: 32768, maxOutput: 4096, source: "unknown",
              },
            })),
      }))
      .filter((group) => group.models.length > 0);
    if (model && !groups.some((group) => group.models.some((item) => item.id === model))) {
      groups.push({
        name: sessionAccount ?? "",
        label: "当前服务",
        vendor: "",
        models: [{
          id: model,
          capabilities: {
            vision: false, files: false, reasoning: false, tools: false,
            maxContext: 32768, maxOutput: 4096, source: "unknown",
          },
        }],
      });
    }
    return groups;
  }, [chatAccounts, model, sessionAccount]);

  const currentCaps = useMemo(() => {
    for (const group of modelGroups) {
      for (const item of group.models) {
        if (item.id === model) return item.capabilities;
      }
    }
    return null;
  }, [modelGroups, model]);

  const contextUsage = useMemo(() => {
    let sessionInput = 0;
    let sessionOutput = 0;
    let last: { input: number; output: number } | null = null;
    for (const node of session?.nodes ?? []) {
      if (node.role !== "assistant") continue;
      const message = node.variants.find((item) => item.id === node.activeVariantId)
        ?? node.variants[node.variants.length - 1];
      if (!message?.usage) continue;
      const input = message.usage.input ?? 0;
      const output = message.usage.output ?? 0;
      sessionInput += input;
      sessionOutput += output;
      last = { input, output };
    }
    const live = liveUsage && session?.id === liveUsage.sessionId ? liveUsage : null;
    const used = live?.input ?? last?.input ?? null;
    return {
      used: used && used > 0 ? used : null,
      lastOutput: live?.output ?? last?.output ?? null,
      sessionInput,
      sessionOutput,
    };
  }, [session, liveUsage]);

  const pickModel = async (accountName: string, modelId: string) => {
    setSessionAccount(accountName);
    setModel(modelId);
    if (!session) return;
    try {
      const next = await api.chat.updateSession(session.id, { account: accountName, model: modelId });
      setSession(next);
    } catch (error) {
      reportError(error, "切换模型失败");
    }
  };

  const pickReasoning = async (level: string) => {
    // 还没有会话时（欢迎页）先记在本地：第一次发送会用 ensureSession 把它带进新会话，
    // 不然点菜单看起来像没反应。
    setReasoningLevel(level);
    if (!session) return;
    try {
      setSession(await api.chat.updateSession(session.id, { reasoningLevel: level }));
    } catch (error) {
      setReasoningLevel(session.reasoningLevel ?? "");
      reportError(error, "切换思考挡位失败");
    }
  };

  const suggestions = currentAssistant?.quickPhrases?.length
    ? currentAssistant.quickPhrases.slice(0, 4).map((phrase) => ({
        icon: <FormatListBulletedIcon size={18} />,
        title: phrase,
        subtitle: "常用短语",
        prompt: phrase,
      }))
    : [
        { icon: <DeployedCodeIcon size={18} />, title: "解释一段代码", subtitle: "贴代码，我来讲清楚", prompt: "请解释下面这段代码：\n" },
        { icon: <EditIcon size={18} />, title: "改写一段文字", subtitle: "更清楚、更简短", prompt: "请把下面这段文字改得更清楚：\n" },
        { icon: <FormatListBulletedIcon size={18} />, title: "列一个计划", subtitle: "把目标拆成步骤", prompt: "帮我为下面这件事列一个可执行的计划：\n" },
        { icon: <ArchiveIcon size={18} />, title: "总结一份文档", subtitle: "PDF / Office 也行", prompt: "请总结这份文档的要点：\n" },
      ];

  const pickSuggestion = (prompt: string) => {
    setDraft(prompt);
    setFocusSignal((value) => value + 1);
  };

  const send = async () => {
    // 编辑模式下的 Enter/发送按钮是「保存修改」，不是新消息
    if (editTarget) {
      await saveEdit();
      return;
    }
    const text = draft.trim();
    if (!text && pending.length === 0) return;
    setBusy(true);
    try {
      const target = await ensureSession();
      const outcome = await api.chat.send(target.id, {
        text,
        attachments: pending.map((item) => item.id),
        model: model ?? undefined,
        account: sessionAccount ?? undefined,
        requestId: requestId(),
        search: searchEnabled,
      });
      setDraft("");
      setPending([]);
      await adoptOutcome(target.id, outcome);
      void loadSessions(assistantId);
    } catch (error) {
      reportError(error, "发送失败");
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
      reportError(error, "上传失败");
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
      reportError(error, "重新生成失败");
    } finally {
      setBusy(false);
    }
  };

  const startEdit = (nodeId: string, text: string) => {
    setDraftBeforeEdit(draft);
    setEditTarget({ nodeId, text });
    setDraft(text);
    setFocusSignal((value) => value + 1);
  };

  const cancelEdit = () => {
    setEditTarget(null);
    setDraft(draftBeforeEdit);
  };

  const saveEdit = async () => {
    if (!editTarget || !session) return;
    const text = draft.trim();
    if (!text) return;
    setBusy(true);
    try {
      const outcome = await api.chat.edit(session.id, {
        nodeId: editTarget.nodeId, text, requestId: requestId(),
      });
      setEditTarget(null);
      setDraft("");
      setDraftBeforeEdit("");
      await adoptOutcome(session.id, outcome);
    } catch (error) {
      reportError(error, "编辑失败");
    } finally {
      setBusy(false);
    }
  };

  const toggleSearch = () => {
    if (!searchStatus?.configured) {
      toast("info", "还没配置搜索 key：去「聊天设置 → 联网搜索」填一个");
      return;
    }
    setSearchEnabled((value) => !value);
  };

  /// 长文本粘贴：包成 .txt 走附件上传，输入框只留一个附件条。
  const uploadText = async (text: string) => {
    const name = `粘贴文本-${text.length}字.txt`;
    await upload(new File([text], name, { type: "text/plain" }));
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
    setSessionAccount(next?.account ?? chatAccounts[0]?.name ?? null);
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
          onSettings={() => navigate("/chat/settings")}
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
          {currentAssistant && (
            <span className="chat-header-avatar"><AvatarView value={currentAssistant.avatar} /></span>
          )}
          <div className="chat-header-title">
            <div className="chat-header-name">{session?.title || currentAssistant?.name || "聊天"}</div>
            <div className="chat-header-sub">
              <span>{currentAssistant?.name ?? "默认助手"}</span>
              {model && <span className="chat-header-model">{model}</span>}
              {live ? " · 正在生成" : ""}
            </div>
          </div>
          <ContextMeter
            used={contextUsage.used}
            max={currentCaps?.maxContext ?? 0}
            lastOutput={contextUsage.lastOutput}
            sessionInput={contextUsage.sessionInput}
            sessionOutput={contextUsage.sessionOutput}
            disabled={!session}
          />
          <span className="spacer" />
          <button className="chat-header-new ripple-host" onClick={() => void newSession()} title="新建会话">
            <AddIcon size={16} />
            新会话
          </button>
          <button className="tool-btn ripple-host" onClick={() => navigate("/chat/settings")} title="聊天设置">
            <SettingsIcon size={20} />
          </button>
          {session && (
            <button
              className="tool-btn ripple-host"
              onClick={(event) => setSessionMenu(event.currentTarget)}
              title="会话操作"
            >
              <MoreVertIcon size={20} />
            </button>
          )}
        </div>

        <PopupMenu open={sessionMenu !== null} anchor={sessionMenu} onClose={() => setSessionMenu(null)}>
          <PopupItem
            icon={<EditIcon size={16} />}
            onClick={() => {
              const summary = sessions.find((item) => item.id === sessionId);
              if (summary) setRenameTarget(summary);
              setSessionMenu(null);
            }}
          >
            重命名会话
          </PopupItem>
          <PopupSep />
          <PopupItem
            icon={<DeleteIcon size={16} />}
            danger
            onClick={() => {
              const summary = sessions.find((item) => item.id === sessionId);
              if (summary) setDeleteSessionTarget(summary);
              setSessionMenu(null);
            }}
          >
            删除会话
          </PopupItem>
        </PopupMenu>

        {needsElevation ? (
          <div className="chat-elevation">
            <div className="chat-elevation-card">
              <span className="chat-elevation-icon"><LockIcon size={26} /></span>
              <h2>聊天接口需要开发者提权</h2>
              <p>
                当前开发者守卫是「全部 API」档：未提权时助手、会话与消息都会被拒绝。
                输入开发者密码后重试，本页不会保存密码。
              </p>
              <button className="btn btn-raised ripple-host" disabled={elevating} onClick={() => void elevate()}>
                {elevating ? "提权中…" : "输入开发者密码"}
              </button>
            </div>
          </div>
        ) : loadingChat ? (
          <div className="chat-loading">
            <span className="spinner" />
            <span>正在加载聊天…</span>
          </div>
        ) : !session ? (
          <div className="chat-welcome scroll">
            <div className="chat-welcome-card">
              <div className="chat-welcome-hero">
                <div className="chat-welcome-avatar-ring">
                  <span className="chat-welcome-avatar"><AvatarView value={currentAssistant?.avatar} /></span>
                </div>
                <div className="chat-welcome-copy">
                  <h1>{currentAssistant?.name ?? "默认助手"}</h1>
                  <p>{currentAssistant?.description || "发一条消息，开始这段对话。"}</p>
                  <div className="chat-welcome-chips">
                    <span className="chat-chip">{assistant?.id === "default" ? "默认助手" : "助手"}</span>
                    <span className="chat-chip">{model || "未选模型"}</span>
                    {currentAssistant?.openingMessage && <span className="chat-chip">有开场白</span>}
                  </div>
                </div>
              </div>
              {currentAssistant?.openingMessage && (
                <div className="chat-welcome-opening">
                  <Markdown content={currentAssistant.openingMessage} />
                </div>
              )}
              <div className="chat-welcome-label">可以这样开始</div>
              <div className="chat-suggestions">
                {suggestions.map((suggestion) => (
                  <button
                    key={suggestion.title}
                    className="chat-suggestion ripple-host"
                    onClick={() => pickSuggestion(suggestion.prompt)}
                  >
                    <span className="chat-suggestion-icon">{suggestion.icon}</span>
                    <span className="chat-suggestion-title">{suggestion.title}</span>
                    <span className="chat-suggestion-sub">{suggestion.subtitle}</span>
                  </button>
                ))}
              </div>
              <div className="chat-welcome-hint">Enter 发送 · Shift+Enter 换行 · 支持图片、PDF、Office 与长文本附件</div>
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
            {(() => {
              let previousDay = "";
              return session.nodes.map((node) => {
                const label = dayLabel(node.createdAt);
                const showDay = label !== previousDay;
                previousDay = label;
                const isLive = live !== null && node.id === liveNodeId;
                return (
                  <Fragment key={node.id}>
                    {showDay && <div className="chat-day"><span>{label}</span></div>}
                    <MessageItem
                      node={node}
                      assistant={currentAssistant ?? { name: "助手", avatar: "💬" }}
                      streaming={isLive}
                      liveContent={isLive ? live?.text : undefined}
                      liveReasoning={isLive ? live?.reasoning : undefined}
                      liveTool={isLive ? live?.tool : undefined}
                      onRegenerate={(nodeId, mode) => setConfirmRegenerate({ nodeId, mode })}
                      onEdit={(nodeId, text) => startEdit(nodeId, text)}
                      onDelete={(nodeId) => setDeleteNodeTarget(nodeId)}
                      onSwitchVariant={(nodeId, messageId) => void switchVariant(nodeId, messageId)}
                      user={profile}
                      editingNodeId={editTarget?.nodeId ?? null}
                    />
                  </Fragment>
                );
              });
            })()}
            {live && !liveAttached && (
              <LiveBubble assistant={currentAssistant} text={live.text} reasoning={live.reasoning} tool={live.tool} />
            )}
          </div>
        )}

        {!needsElevation && !loadingChat && (
          <div className="chat-composer-wrap">
            <Composer
              draft={draft}
              onDraft={setDraft}
              onSend={() => void send()}
              onStop={() => void stop()}
              streaming={live !== null}
              disabled={!canSend}
              groups={modelGroups}
              account={sessionAccount}
              model={model}
              onPick={(accountName, modelId) => void pickModel(accountName, modelId)}
              onManage={() => navigate("/chat/settings")}
              onAdd={() => navigate("/chat/settings?add=1")}
              pending={pending}
              onUpload={(file) => void upload(file)}
              onRemove={(id) => setPending((previous) => previous.filter((item) => item.id !== id))}
              uploading={uploading}
              quickPhrases={currentAssistant?.quickPhrases ?? []}
              focusSignal={focusSignal}
              historyImages={historyImageCount}
              reasoningLevel={reasoningLevel}
              onReasoningLevel={(level) => void pickReasoning(level)}
              onUploadText={(text) => void uploadText(text)}
              editing={editTarget !== null}
              onCancelEdit={cancelEdit}
              searchEnabled={searchEnabled}
              searchConfigured={searchStatus?.configured ?? false}
              onToggleSearch={toggleSearch}
            />
          </div>
        )}
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

    </div>
  );
}