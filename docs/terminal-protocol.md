# 终端通道协议（Web Shell）

> 状态：v1（`protocol: 1`）。服务端 `TerminalWebSocket.PROTOCOL` 与本文是同一份契约；
> 改协议必须同时改版本号、本文和客户端，并在 Release 说明里写清楚。

浏览器里的终端覆盖层和仓库里的 Go 客户端（`client/hdsl-tui`）走的是同一套接口。
把它们写下来的原因很简单：这套东西以前只服务一个前端，前端和它一起改不会出事；
有了第二个客户端，一次重构就能悄悄打断它。

## 1. 鉴权与开关

所有 REST 调用都在 `/api/*` 下，走面板会话 Cookie（`hdsl_session`，`HttpOnly` +
`SameSite=Strict`，HTTPS 下带 `Secure`）。

终端相关端点还额外要求：

- **HTTPS**：面板此刻真的在讲 TLS，或打开了 `https.trust_forwarded_proto` 且请求带
  `X-Forwarded-Proto: https`。否则 403 `insecure_transport`。
- **Web Shell 总开关**：`web_shell.enabled` 为真。关掉时 403 `web_shell_disabled`。
- **开发者密码提权**：会话级 30 分钟滑动。没有提权时 403 `developer_elevation_required`。

错误体统一是 `{"error":"<code>: <说明>"}`；客户端按冒号前的 code 分支。

## 2. REST

### `POST /api/auth/login`

```json
{ "username": "admin", "password": "…" }
```

200 → `{"ok":true,"username":"admin"}`，并下发 `Set-Cookie: hdsl_session=…`。
401 → 用户名或密码错误（失败次数有预算，超限时 429 带 `Retry-After`）。

### `GET /api/health`

公开端点，用于版本与连通性检查：`{"status":"ok","version":"0.5.4","uptimeSec":123}`。

### `POST /api/developer/elevate`

```json
{ "password": "…" }
```

200 → `{"elevated":true,"expiresAt":"…"}`。要求 HTTPS，否则 409 `tls_required`。
其他：401 密码错误；409 `developer_password_not_set`（还没设置过）；429 连续失败被锁。

### 实例列表

- dsh：`GET /api/instances` → `{"instances":[{"id","name","state",…}]}`
- 品牌：`GET /api/brands/{brand}/instances`，`brand ∈ {kimi, opencode, claude-web}`，
  元素形状同上（另带 `version` 等字段）

### `POST /api/instances/{id}/terminal`

品牌版本是 `POST /api/brands/{brand}/instances/{id}/terminal`。可选查询参数
`?resume=<sessionId>`（TUI 里恢复某个会话）。请求体可以是 `{}`；Windows 桌面模式要求
`{"confirm":"open-desktop-terminal"}`。

POSIX 面板返回：

```json
{
  "mode": "pty",
  "protocol": 1,
  "instance": "DClDN1J3w0C_",
  "brand": "dsh",
  "ticket": "…",
  "ws": "/ws/terminal/<ticket>"
}
```

Windows 面板返回（没有 ticket，也没有 PTY——终端开在面板所在机器的桌面上）：

```json
{ "mode": "desktop", "instance": "…", "hint": "…" }
```

`ws` 是**面板内部路径**（根相对），不是公开地址：面板挂在反向代理的 `/hdsl` 下时它仍是
`/ws/terminal/<ticket>`，由客户端把挂载点前缀加上去（浏览器读 `<base href>` /
`window.__HDSL_BASE__`，Go 客户端用它连接地址的 path 部分）。客户端不要用
“根相对路径替换整个 path”的解析方式，那样会丢掉 `/hdsl`，表现是健康检查、登录、实例列表
都正常，只有终端连不上。服务端也可以返回完整的 `ws://` / `wss://` 地址，此时客户端按原样
使用，不再加前缀。

重复调用不会重开会话：该实例已有运行中的终端时，只是再发一张 ticket（等价于 attach）。

错误：

| HTTP | code | 含义 |
|---|---|---|
| 403 | `insecure_transport` | 面板不在 HTTPS 下（或反代未开 `trust_forwarded_proto`） |
| 403 | `web_shell_disabled` | 总开关关了 |
| 403 | `developer_elevation_required` | 开发者密码提权过期 |
| 409 | `developer_password_not_set` | 还没设置过开发者密码 |
| 409 | `terminal_already_open` | 同实例已有终端（正常路径是重连，不该看到这个） |
| 409 | `terminal_failed: …` | 启动 TUI 失败，冒号后是原因 |
| 400 | `confirmation_required` | Windows 面板缺 `confirm` |
| 404 | `instance not found` / `no such instance` | 实例不存在 |

### `DELETE /api/instances/{id}/terminal`

结束该实例的终端（杀进程树）：`{"ok":true,"killed":true}`。不需要提权（与打开分开）。

## 3. WebSocket

`GET /ws/terminal/{ticket}`，升级为 WebSocket。握手必须带**与申请 ticket 时同一个**
面板会话 Cookie：ticket 里记录了申请它的会话 token，两者对不上就 1008 关闭。

- **ticket**：一次性、30 秒有效、绑定实例与会话；握手成功即失效。URL 里会出现它，
  所以它只够开一次连接，事后无效——这也是会话 Cookie 不进 URL 的原因。
- **二进制帧**：两端都是 PTY 原始字节，不做任何包装。
- **文本帧**：JSON。客户端发 `{"type":"resize","cols":120,"rows":40}`；
  服务端发：

```json
{"type":"ready","protocol":1,"instance":"…","brand":"…"}
{"type":"exit","code":0}
```

- **断开**只代表 detach：agent 继续跑，重新走一次 `POST …/terminal` 拿新 ticket 即可接回，
  并会先收到最近的输出回放（服务端保留 256 KB）。
- 关闭码：1008 ticket 无效/不匹配；1011 该实例没有运行中的终端；1000/1001 正常关闭。
- 客户端应忽略 `ready` 里的未知字段；`protocol` 不是自己认识的版本时警告并继续尝试。

## 4. 版本策略

- `protocol` 只在**不兼容**时自增：新增可选字段不算。
- 服务端先发新字段，客户端后跟上；客户端必须容忍未知字段。
- 客户端与面板同版本号发布；`hdsl-tui --version` 打印自己的版本与 `protocol`。