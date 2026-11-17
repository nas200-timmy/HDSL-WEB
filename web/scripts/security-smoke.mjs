// Headless security smoke test. Run through scripts/security-smoke.sh.
//
// It exercises the paths a browser uses but without a browser:
//   encrypted login → set developer password over HTTP → elevate over HTTP →
//   gated ACME read → chat-key → encrypted chat GET/POST.
import { x25519 } from "@noble/curves/ed25519";
import { chacha20poly1305 } from "@noble/ciphers/chacha";
import { hkdf } from "@noble/hashes/hkdf";
import { sha256 } from "@noble/hashes/sha256";

const base = process.argv[2];
const adminPassword = process.argv[3];
const devPassword = process.argv[4];
if (!base || !adminPassword || !devPassword) {
  console.error("usage: security-smoke.mjs <base-url> <admin-password> <dev-password>");
  process.exit(2);
}

const b64 = (bytes) => Buffer.from(bytes).toString("base64url");
const fromB64 = (value) => Buffer.from(value, "base64url");
const json = (response) => response.json();

function check(condition, message, detail = "") {
  if (!condition) {
    console.error("FAIL:", message, detail);
    process.exit(1);
  }
  console.log("ok:", message);
}

const passwordKey = await fetch(`${base}/api/crypto/password-key`).then((r) => {
  check(r.ok, "password-key endpoint", `${r.status}`);
  return r.json();
});

async function encryptPassword(password) {
  const priv = crypto.getRandomValues(new Uint8Array(32));
  const pub = x25519.getPublicKey(priv);
  const shared = x25519.getSharedSecret(priv, fromB64(passwordKey.publicKey));
  const info = new TextEncoder().encode("hdsl-web-password-v1");
  const key = hkdf(sha256, shared, new TextEncoder().encode(passwordKey.keyId), info, 32);
  const nonce = crypto.getRandomValues(new Uint8Array(12));
  const ciphertext = chacha20poly1305(key, nonce, info).encrypt(
    new TextEncoder().encode(password),
  );
  return {
    v: "1",
    keyId: passwordKey.keyId,
    clientPublicKey: b64(pub),
    nonce: b64(nonce),
    ciphertext: b64(ciphertext),
  };
}

async function postJson(path, body, cookie) {
  return fetch(`${base}${path}`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...(cookie ? { Cookie: cookie } : {}),
    },
    body: JSON.stringify(body),
  });
}

const login = await postJson("/api/auth/login", {
  username: "admin",
  password: await encryptPassword(adminPassword),
});
check(login.status === 200, "encrypted login", `${login.status} ${await login.clone().text()}`);
const rawCookie = login.headers.getSetCookie?.()[0] ?? login.headers.get("set-cookie");
const cookie = rawCookie.split(";")[0];

const setDev = await postJson("/api/developer/password", {
  adminPassword: await encryptPassword(adminPassword),
  password: await encryptPassword(devPassword),
  confirm: await encryptPassword(devPassword),
}, cookie);
check(setDev.status === 200, "set developer password over HTTP", `${setDev.status} ${await setDev.clone().text()}`);

const beforeElevationAfterSet = await fetch(`${base}/api/settings/acme`, { headers: { Cookie: cookie } });
check(beforeElevationAfterSet.status === 403,
  "gated ACME read is refused without elevation after the password exists",
  `${beforeElevationAfterSet.status}`);

const elevate = await postJson("/api/developer/elevate", {
  password: await encryptPassword(devPassword),
}, cookie);
check(elevate.status === 200, "elevate over HTTP", `${elevate.status} ${await elevate.clone().text()}`);

const acme = await fetch(`${base}/api/settings/acme`, { headers: { Cookie: cookie } });
check(acme.status === 200, "gated ACME read after elevation", `${acme.status}`);
const acmeBody = await json(acme);
check(acmeBody.available === true, "ACME service is available");

const chatPrivate = crypto.getRandomValues(new Uint8Array(32));
const chatPublic = x25519.getPublicKey(chatPrivate);
const chatKeyResponse = await fetch(`${base}/api/crypto/chat-key`, {
  method: "POST",
  headers: { "Content-Type": "application/json", Cookie: cookie },
  body: JSON.stringify({ clientPublicKey: b64(chatPublic) }),
});
check(chatKeyResponse.status === 200, "chat-key handshake", `${chatKeyResponse.status}`);
const chatKey = await json(chatKeyResponse);
const shared = x25519.getSharedSecret(chatPrivate, fromB64(chatKey.serverPublicKey));
const chatInfo = new TextEncoder().encode("hdsl-web-chat-v1");
const chatKeys = hkdf(
  sha256, shared, new TextEncoder().encode(chatKey.channelId), chatInfo, 64,
);
const c2s = chatKeys.slice(0, 32);
const s2c = chatKeys.slice(32);

function encryptChat(value) {
  const nonce = crypto.getRandomValues(new Uint8Array(12));
  const ciphertext = chacha20poly1305(c2s, nonce, chatInfo).encrypt(
    new TextEncoder().encode(JSON.stringify(value)),
  );
  return JSON.stringify({ v: "1", n: b64(nonce), ct: b64(ciphertext) });
}

function decryptChat(envelope) {
  const parsed = JSON.parse(envelope);
  const plain = chacha20poly1305(s2c, fromB64(parsed.n), chatInfo).decrypt(fromB64(parsed.ct));
  return JSON.parse(new TextDecoder().decode(plain));
}

const assistantsResponse = await fetch(`${base}/api/chat/assistants`, {
  headers: { Cookie: cookie, "X-HDSL-Chat": chatKey.channelId },
});
check(assistantsResponse.status === 200, "encrypted chat GET", `${assistantsResponse.status}`);
const assistantsText = await assistantsResponse.text();
const assistants = decryptChat(assistantsText);
check(Array.isArray(assistants.assistants), "chat GET decrypts to the expected shape");

const createResponse = await fetch(`${base}/api/chat/sessions`, {
  method: "POST",
  headers: {
    "Content-Type": "application/json",
    Cookie: cookie,
    "X-HDSL-Chat": chatKey.channelId,
  },
  body: encryptChat({ assistantId: "default", title: "security-smoke" }),
});
check(createResponse.status === 201, "encrypted chat POST", `${createResponse.status}`);
const created = decryptChat(await createResponse.text());
check(created.title === "security-smoke", "chat POST round-trips through encryption");

console.log("security smoke: all checks passed");
