import { x25519 } from "@noble/curves/ed25519";
import { chacha20poly1305 } from "@noble/ciphers/chacha";
import { hkdf } from "@noble/hashes/hkdf";
import { sha256 } from "@noble/hashes/sha256";
import { withBase } from "./base";

/**
 * Per-tab chat channel. The server and browser derive two ChaCha20 keys with
 * X25519 + HKDF; `/api/chat/*` JSON and `chat:*` WebSocket frames are wrapped
 * in `{v,n,ct}`. Nothing is persisted: a refresh performs a new handshake.
 */

const INFO = new TextEncoder().encode("hdsl-web-chat-v1");

export interface ChatChannel {
  channelId: string;
  c2s: Uint8Array;
  s2c: Uint8Array;
}

let channel: ChatChannel | null = null;
let pending: Promise<ChatChannel> | null = null;

function randomBytes(length: number): Uint8Array {
  const bytes = new Uint8Array(length);
  crypto.getRandomValues(bytes);
  return bytes;
}

function b64(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function fromB64(value: string): Uint8Array {
  const padded = value.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((value.length + 3) % 4);
  const binary = atob(padded);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

async function start(): Promise<ChatChannel> {
  const privateKey = randomBytes(32);
  const publicKey = x25519.getPublicKey(privateKey);
  const response = await fetch(withBase("/api/crypto/chat-key"), {
    method: "POST",
    credentials: "same-origin",
    cache: "no-store",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ clientPublicKey: b64(publicKey) }),
  });
  if (!response.ok) {
    let message = "无法获取聊天加密密钥";
    try {
      const body = await response.json() as { error?: string };
      if (body.error) message = body.error;
    } catch {
      // keep the generic message
    }
    throw new Error(message);
  }
  const body = await response.json() as { channelId?: string; serverPublicKey?: string };
  if (!body.channelId || !body.serverPublicKey) throw new Error("聊天加密密钥格式不正确");
  const shared = x25519.getSharedSecret(privateKey, fromB64(body.serverPublicKey));
  const keys = hkdf(
    sha256,
    shared,
    new TextEncoder().encode(body.channelId),
    INFO,
    64,
  );
  return {
    channelId: body.channelId,
    c2s: keys.slice(0, 32),
    s2c: keys.slice(32, 64),
  };
}

export function ensureChatChannel(): Promise<ChatChannel> {
  if (channel) return Promise.resolve(channel);
  if (!pending) {
    pending = start().then((value) => {
      channel = value;
      pending = null;
      return value;
    }).catch((error) => {
      pending = null;
      throw error;
    });
  }
  return pending;
}

export function chatChannelId(): string | null {
  return channel?.channelId ?? null;
}

/** 丢弃缓存通道。服务端通道是内存态：面板重启即丢、TTL 12h、超压随机驱逐，
 * 过期 id 会换来 401 chat_key_required——那不是会话失效。调用方（REST 重试、
 * WS 重绑、登出）丢弃后，下一次 ensureChatChannel() 重新握手。 */
export function resetChatChannel(): void {
  channel = null;
}

export function encryptChatJson(plain: string): string {
  if (!channel) throw new Error("chat key is not ready");
  const nonce = randomBytes(12);
  const ciphertext = chacha20poly1305(channel.c2s, nonce, INFO).encrypt(
    new TextEncoder().encode(plain),
  );
  return JSON.stringify({ v: "1", n: b64(nonce), ct: b64(ciphertext) });
}

/// 解密默认用当前通道；并发重试可能在响应回来前换掉通道，发送方应把
/// 发送时用的通道传进来，避免拿新钥匙开旧锁。
export function decryptChatJson(plain: string, key: ChatChannel | null = channel): string {
  if (!key) throw new Error("chat key is not ready");
  const envelope = JSON.parse(plain) as { n?: string; ct?: string };
  if (!envelope.n || !envelope.ct) throw new Error("加密聊天响应格式不正确");
  const bytes = chacha20poly1305(key.s2c, fromB64(envelope.n), INFO).decrypt(
    fromB64(envelope.ct),
  );
  return new TextDecoder().decode(bytes);
}

export function decryptChatEnvelope(envelope: { n?: string; ct?: string }): string {
  if (!channel) throw new Error("chat key is not ready");
  if (!envelope.n || !envelope.ct) throw new Error("加密聊天帧格式不正确");
  return new TextDecoder().decode(
    chacha20poly1305(channel.s2c, fromB64(envelope.n), INFO).decrypt(fromB64(envelope.ct)),
  );
}
