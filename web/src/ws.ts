import type { WsEvent } from "./types";
import { withBase } from "./base";
import { ensureChatChannel, decryptChatEnvelope, resetChatChannel } from "./chatCrypto";

// WebSocket 连接管理：登录后连接、断线 3s 起指数退避重连（上限 30s）、
// 重连后自动重新订阅全部活跃 topic。握手带 cookie（浏览器默认同源行为）。

type WsHandler = (evt: WsEvent) => void;

const RECONNECT_BASE_MS = 3000;
const RECONNECT_MAX_MS = 30000;

let socket: WebSocket | null = null;
let shouldReconnect = false;
let retryDelay = RECONNECT_BASE_MS;
let retryTimer: number | undefined;
const topics = new Set<string>();
const handlers = new Set<WsHandler>();
let chatReady = false;
// 每条连接允许一次 chat_key_required 重绑（通道在服务端过期/重启后）。
let chatBindAttempts = 0;

function wantsChat(list: Iterable<string>): boolean {
  for (const topic of list) {
    if (topic === "chat" || topic.startsWith("chat:")) return true;
  }
  return false;
}

async function bindChat(sock: WebSocket): Promise<void> {
  if (chatReady || !wantsChat(topics)) return;
  try {
    const channel = await ensureChatChannel();
    if (sock.readyState !== WebSocket.OPEN) return;
    sock.send(JSON.stringify({ type: "chat-hello", channelId: channel.channelId }));
    // chatReady 在收到 chat-hello-ok 时才置位（onmessage）：发了 hello 不等于
    // 绑定成功，服务端对无效通道回 error(chat_key_required)，未绑定的连接
    // 收不到任何 chat 帧。失败帧在那里触发一次重握手重绑。
  } catch {
    // The UI surfaces the error when the chat request itself fails; the
    // socket stays usable for non-chat topics.
  }
}

function sendSubscribe(list: string[]): void {
  if (!socket || socket.readyState !== WebSocket.OPEN || list.length === 0) return;
  try {
    socket.send(JSON.stringify({ type: "subscribe", topics: list }));
  } catch {
    // ignore
  }
}

function open(): void {
  if (!shouldReconnect) return;
  if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) return;

  const proto = window.location.protocol === "https:" ? "wss" : "ws";
  // 子路径挂载：面板可能被反代在挂载点下，WS 同样走 withBase
  const sock = new WebSocket(`${proto}://${window.location.host}${withBase("/ws")}`);
  socket = sock;

  sock.onopen = () => {
    retryDelay = RECONNECT_BASE_MS;
    if (wantsChat(topics)) {
      void bindChat(sock).then(() => sendSubscribe([...topics]));
    } else {
      sendSubscribe([...topics]);
    }
  };

  sock.onmessage = (e) => {
    if (typeof e.data !== "string") return;
    let evt: WsEvent;
    try {
      const parsed = JSON.parse(e.data) as { type?: string; n?: string; ct?: string; message?: string };
      if (parsed && parsed.type === "enc") {
        evt = JSON.parse(decryptChatEnvelope(parsed)) as WsEvent;
      } else {
        // 聊天绑定协议帧不在 WsEvent 联合里，在派发前处理。
        if (parsed?.type === "chat-hello-ok") {
          chatReady = true;
          chatBindAttempts = 0;
          return;
        }
        if (parsed?.type === "error" && parsed.message === "chat_key_required") {
          // 通道在服务端死了（重启/TTL/驱逐）：重握手重绑一次。不重绑的话
          // 连接看着正常，服务端却静默丢弃所有 chat 帧（2026-11 审计）。
          if (chatBindAttempts < 1 && wantsChat(topics)) {
            chatBindAttempts++;
            resetChatChannel();
            void bindChat(sock);
          }
          return;
        }
        evt = parsed as WsEvent;
      }
    } catch {
      return;
    }
    if (evt && typeof evt.type === "string") {
      for (const h of [...handlers]) h(evt);
    }
  };

  sock.onclose = () => {
    if (socket === sock) socket = null;
    chatReady = false;
    chatBindAttempts = 0;
    if (!shouldReconnect) return;
    retryTimer = window.setTimeout(open, retryDelay);
    retryDelay = Math.min(retryDelay * 2, RECONNECT_MAX_MS);
  };

  sock.onerror = () => {
    // onclose 会随后触发，统一在 onclose 里处理重连
  };
}

export const ws = {
  /** 建立连接并保持重连；幂等。 */
  connect(): void {
    if (shouldReconnect) return;
    shouldReconnect = true;
    retryDelay = RECONNECT_BASE_MS;
    open();
  },

  /** 主动断开（退出登录），不再重连，清空订阅。 */
  close(): void {
    shouldReconnect = false;
    topics.clear();
    // 登出/会话失效都走这里：加密通道一并丢弃，别把旧通道留给下一个会话。
    resetChatChannel();
    chatReady = false;
    chatBindAttempts = 0;
    if (retryTimer !== undefined) {
      window.clearTimeout(retryTimer);
      retryTimer = undefined;
    }
    const sock = socket;
    socket = null;
    if (sock) {
      sock.onopen = null;
      sock.onmessage = null;
      sock.onclose = null;
      sock.onerror = null;
      sock.close();
    }
  },

  on(handler: WsHandler): () => void {
    handlers.add(handler);
    return () => {
      handlers.delete(handler);
    };
  },

  addTopics(newTopics: string[]): void {
    let changed = false;
    for (const t of newTopics) {
      if (!topics.has(t)) {
        topics.add(t);
        changed = true;
      }
    }
    if (changed && socket?.readyState === WebSocket.OPEN) {
      if (wantsChat(newTopics)) {
        void bindChat(socket).then(() => sendSubscribe([...topics]));
      } else {
        sendSubscribe([...topics]);
      }
    }
  },

  removeTopics(removed: string[]): void {
    let changed = false;
    for (const t of removed) {
      if (topics.delete(t)) changed = true;
    }
    if (!changed) return;
    // 后端 /ws 明确支持 unsubscribe（WsGateway：有 topics 就移除，订阅“全部”时进排除集；
    // 未知 type 只回一帧 error，不会断连）。不发就等于让服务端继续推这个实例的 log/state。
    if (socket?.readyState === WebSocket.OPEN) {
      try {
        socket.send(JSON.stringify({ type: "unsubscribe", topics: removed }));
      } catch {
        // ignore
      }
    }
  },

  hasTopic(t: string): boolean {
    return topics.has(t);
  },

  /** 发送任意协议消息（如 acp-*）。未连接时返回 false，由调用方降级处理。 */
  send(payload: unknown): boolean {
    if (!socket || socket.readyState !== WebSocket.OPEN) return false;
    try {
      socket.send(JSON.stringify(payload));
      return true;
    } catch {
      return false;
    }
  },
};
