import { x25519 } from "@noble/curves/ed25519";
import { chacha20poly1305 } from "@noble/ciphers/chacha";
import { hkdf } from "@noble/hashes/hkdf";
import { sha256 } from "@noble/hashes/sha256";
import { withBase } from "./base";

/**
 * One-shot password-field encryption for plain-HTTP deployments.
 *
 * This is deliberately not a transport channel: the browser encrypts only the
 * password field against the panel's ephemeral public key. It protects
 * against passive sniffing; an active MITM can still replace the JS or the
 * key. HTTPS is still the real fix.
 */

export interface EncryptedPassword {
  v: "1";
  keyId: string;
  clientPublicKey: string;
  nonce: string;
  ciphertext: string;
}

const INFO = new TextEncoder().encode("hdsl-web-password-v1");
let cached: { keyId: string; publicKey: Uint8Array; at: number } | null = null;

function randomBytes(length: number): Uint8Array {
  const bytes = new Uint8Array(length);
  crypto.getRandomValues(bytes);
  return bytes;
}

function base64url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function fromBase64url(value: string): Uint8Array {
  const padded = value.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((value.length + 3) % 4);
  const binary = atob(padded);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

async function key(): Promise<{ keyId: string; publicKey: Uint8Array }> {
  if (cached && Date.now() - cached.at < 60_000) return cached;
  const response = await fetch(withBase("/api/crypto/password-key"), {
    credentials: "same-origin",
    cache: "no-store",
  });
  if (!response.ok) throw new Error("无法获取密码加密公钥");
  const body = (await response.json()) as { keyId?: string; publicKey?: string };
  if (!body.keyId || !body.publicKey) throw new Error("密码加密公钥格式不正确");
  cached = { keyId: body.keyId, publicKey: fromBase64url(body.publicKey), at: Date.now() };
  return cached;
}

export async function encryptPassword(password: string): Promise<EncryptedPassword> {
  const server = await key();
  const privateKey = randomBytes(32);
  const publicKey = x25519.getPublicKey(privateKey);
  const shared = x25519.getSharedSecret(privateKey, server.publicKey);
  const derived = hkdf(sha256, shared, new TextEncoder().encode(server.keyId), INFO, 32);
  const nonce = randomBytes(12);
  const ciphertext = chacha20poly1305(derived, nonce, INFO).encrypt(
    new TextEncoder().encode(password),
  );
  return {
    v: "1",
    keyId: server.keyId,
    clientPublicKey: base64url(publicKey),
    nonce: base64url(nonce),
    ciphertext: base64url(ciphertext),
  };
}

/** Clears the cached key; callers can retry after a key rotation. */
export function resetPasswordKeyCache(): void {
  cached = null;
}
