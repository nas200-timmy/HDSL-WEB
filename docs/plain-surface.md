# 无 JavaScript 保底页（`/plain`）

状态：P0 已实现。目标读者是 w3m / lynx、Windows 7 上的旧浏览器，以及任何加载
2.8 MB bundle 会白屏的环境。它不替代聊天页，只保证“还能登录、看会话、发消息、下载附件”。

## 1. 入口闸门

`web/index.html` 在 `<head>` 里先跑一段 ES3 语法的脚本，早于 bundle：

- 检查 `fetch`、`WebSocket`、`Promise`、`CSS.supports("display", "flex")`；
- 缺任何一项 → `location.replace(<base>/plain/old)`，不下载 SPA；
- `<noscript>` 用 `meta refresh` 直接去 `<base>/plain`（关 JS 的人要的是能聊天，不是看说明）。

`/plain/old` 是友善提醒页：说明“不是服务器挂了”，给出两个选择——继续用纯文本版，
或换现代浏览器。Windows 7 上建议 Firefox ESR 115 / Chrome 109。

服务端还有一层 UA 兜底（`StaticServlet`）：w3m / lynx / links / elinks 发 `/` 时 302 到
`/plain`，`MSIE` / `Trident/` 302 到 `/plain/old`。这层是给“JS 被禁用”准备的——IE ≤ 9 不解析
`<head>` 里的 `<noscript>`，只靠 meta refresh 不可靠；`<body>` 里另有一个可见的纯文本版链接。
其他浏览器一律发 SPA 壳，交给内联闸门判断，避免 UA 猜测误伤现代浏览器。

限制：不 eval 就无法可靠探测“ES2017 语法”，`fetch + WebSocket + flex` 是代理指标。
真要严格，只能靠 `nomodule` 兜底或 UA 判断，代价更高，这一版只做了上面那条窄兜底。

## 2. 路由

所有路径都带 `basePath` 前缀（`HDSL_BASE_PATH`，例如 `/panel`）。

| 方法 | 路径 | 作用 |
|---|---|---|
| GET | `/plain` | 会话列表 + 新建会话；未登录时是登录表单 |
| GET/POST | `/plain/login` | 登录表单 / 表单登录（`username`、`password`） |
| POST | `/plain/logout` | 清 cookie，回登录页 |
| GET | `/plain/old` | 浏览器太旧的提醒页（公开） |
| POST | `/plain/new` | 用 `assistantId` 新建会话，303 到会话页 |
| GET | `/plain/s/{id}` | 会话历史 + 发送表单；`?auto=1` 开启自动刷新 |
| POST | `/plain/s/{id}/send` | 表单 `text`，提交后 303 回会话页 |

`/plain` 不注册到 `AuthFilter`：过滤器对未认证的 HTML 请求回 JSON 401，文本浏览器会
直接把那段 JSON 显示给用户。Servlet 自己校验会话，未登录时渲染登录表单。

## 3. 登录与表单安全

- 复用 `AuthService.login` 与 `AuthFilter.sessionCookie`：`HttpOnly`、`SameSite=Strict`、
  子路径挂载时 `Path` 跟着挂载点走。
- 登录失败与 `ApiServlet` 同口径：15 次失败 / 5 分钟窗口 / 封 10 分钟（`LoginThrottle`），
  写 `audit.jsonl` 的 `login` 记录。
- 所有 POST 校验 `Origin`／`Referer` 与 `Host` 一致；两者都没有（w3m、curl）时放行，
  因为 `SameSite=Strict` 已经挡住跨站表单。
- 响应头固定：`Cache-Control: no-store`、`nosniff`、`X-Frame-Options: DENY`、
  `Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; form-action 'self'`。
- 所有动态内容经 `PlainHtml.escape` 转义；页面里没有脚本，也没有 `innerHTML` 这条路。

## 4. 聊天循环

发送直接调 `ChatTurnManager.send(...)`，和 SPA 是同一条 turn、同一份 SQLite 快照：

1. 表单 POST → 303 到 `/plain/s/{id}?auto=1`；
2. 会话页从 `ChatService.sessionJson` 读节点与当前变体；
3. 回复生成中显示“正在生成回答…”，`auto=1` 时页头带 `<meta http-equiv="refresh" content="5">`；
4. 页面上始终有「刷新」与「自动刷新：开/关」链接，手动刷新不依赖 meta refresh；
5. 生成结束（`streaming=false`）后 meta refresh 自动消失。

已知取舍：

- 自动刷新是整页刷新，正在输入的文字会丢；要长时间输入就先关掉自动刷新。
- 没有 token 级流式，只有每秒落库的快照。
- 不渲染思考过程、搜索时间线和 Markdown；助手正文按 `<pre>` 原样显示（已转义）。
- 附件只给下载链接（`/api/chat/attachments/{id}`，走同一会话 cookie），P0 不做上传。

## 5. 代码位置

- `src/main/java/org/jackhuang/hmcl/web/plain/PlainChatServlet.java`：路由、表单、登录、渲染；
- `src/main/java/org/jackhuang/hmcl/web/plain/PlainHtml.java`：HTML 骨架、转义、时间格式；
- `web/index.html`：能力闸门与 `<noscript>` 跳转；
- `src/main/java/org/jackhuang/hmcl/web/server/HdslServer.java`：`/plain/*` 注册。

## 6. 验证

- `PlainChatTest`：登录表单发 cookie、公开提醒页、SPA 入口带闸门、
  `/plain/new` → 表单发送 → 同一回复在页面出现（假 provider 走完一轮）。
- `scripts/api-smoke.py`：`/plain` 渲染表单、`/plain/old` 给出提醒。
- 手工：`w3m http://<panel>/plain` 应能看到登录表单与后续页面。