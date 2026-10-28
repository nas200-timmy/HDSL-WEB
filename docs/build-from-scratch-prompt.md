# 从零重建 HDSL-web：给模型的任务提示词

> ## ⚠️ 只给"从零新建的项目"用
>
> **如果你是在已有的 HDSL-web 仓库里接手工作，不要按本文重建。** 代码已经在这里了，照本文动手
> 只会覆盖真实实现、丢掉这些修复的来龙去脉。那种情况请读：
>
> - [`session-handoff-2026-10-27.md`](session-handoff-2026-10-27.md) —— 当前状态、未发布的改动、发版流程、踩过的坑；
> - [`plan.md`](plan.md) —— 原始设计；
> - 各模块文档（`web-shell.md`、`brands.md`、`octop.md`、`deployment.md`、`design-notes.md`）。
>
> 本文的唯一用途：**仓库被清空、或换一个模型从空目录重写时**，把「要求」和「坑」一次性交代清楚。
> 判断标准很简单——你所在的目录里已经有 `src/`、`web/`、`plugins/` 了吗？有，就关掉本文。

> 这份文件本身就是一段提示词：把下面「任务」整段交给一个模型，它可以不知道本项目的历史，只凭它
> 从空仓库把项目建出来。写的是**要求与坑**，不是实现讲解——实现方式可以不同，坑不能踩第二遍。
> 维护约定：每修一个"只有跑起来才看得见"的问题，就往 §5 加一条，并写清「现象 / 根因 / 正确做法」。

---

## 任务

从**空仓库**开始，实现 **HDSL-web**：一个把 DeepSeek Harness（简称 dsh）以及若干第三方 AI 客户端
**以 web 面板的形式管起来**的自托管服务。它必须做到：

1. 一条命令产出**单个可执行 jar**（`java -jar hdsl-web.jar`），面板自带前端静态资源、自带两个第一方
   TUI 插件包，**离线可用**：目标机器上没有 npm、没有 registry 也能装。
2. 能把若干「实例」跑起来并反代它们：每个实例是一份独立的 home，只监听回环，浏览器经面板的
   `/i/<id>/` 访问；面板负责会话鉴权、`Host` 透传、Cookie 路径隔离、WebSocket 中继、页面挂载点改写。
3. 提供**网页终端**：在浏览器里跑实例自己的 TUI（PTY 在服务端，浏览器只是查看器），带开发者密码二次确认。
4. 提供一套**品牌目录**（dsh / Kimi Code / OpenCode / Claude Web / ZCode / Octop），每个品牌的安装、
   版本选择、启动、策略、终端入口都要能用。
5. 提供**开发者模式**：开发者密码 + 限时提权；可选的作用域守卫（反代 / 实例写操作 / 全部写操作）与
   **紧急停机**（E-Stop）。
6. 产出一个 Go 客户端（`client/hdsl-tui`），能在服务器上交互式挑实例、开终端，5 平台交叉编译。
7. 全套文档：部署、协议、设计笔记、安全模型、会话交接稿，中文，与实现一致。

技术栈固定为：**Java 17**（`options.release = 17`，允许用更高 JDK 编译，但**不得使用 JDK 18+ API**）、
Gradle（shadow 插件打单 jar）、Jetty 12（ee10）、Gson、JNA（仅 PTY）、React 18 + Vite + xterm 6、
Go 1.24。许可证 GPL-3.0-or-later。

---

## 1. 硬约束（违反任何一条都算不合格）

- **单 jar**：`shadowJar` 产物包含 `web/` 构建出的静态资源与 `plugins/` 打出的 npm tarball；不含任何
  需要联网下载的运行时依赖。
- **插件零外部依赖**：`plugins/` 下两个第一方 TUI 插件（进程内 / ACP）必须 **不引任何 npm 依赖**
  （只用 dsh 的 peer 包与 node 内置模块），因为它们要能被离线安装进别人的实例。
- **不许引入新的三方库**除非确实无法避免；现有依赖只有上面列的这些。HTTP 客户端用 `java.net.http`，
  不引 OkHttp/Netty。
- **隐私**：仓库里不写真实域名、内网地址、宿主机路径、任何口令。文档举例一律用 `example.com` / `/data`。
- **不可用要变灰、不隐藏**：功能因配置/权限不可用时，按钮保留、置灰、并写明原因（例如"需要先启用
  HTTPS"）。隐藏按钮会让人以为没有这个功能。
- **不做假防线**：见 §5.9。面板内检查在同 uid 下都是剧场，别写。
- **错误码约定**：所有 API 错误体是 `{"error":"<code>: <人能照着做的一句话>"}`；前端与 Go 客户端按
  `<code>` 前缀分流。

---

## 2. 功能清单与验收点

### 2.1 服务与静态资源
- 一个 Jetty 服务，端口默认 3080；`/` 出 SPA（`<base href>` 由服务端按挂载点注入），`/api/*` REST，
  `/ws` 事件流，`/i/<id>/` 反代，`/ws/terminal/<ticket>` 终端。
- 支持子路径部署（`HDSL_BASE_PATH=/hdsl`）：所有前端资源与 API 路径都要跟着走；`<base href>`、
  运行时注入的 `window.__HDSL_BASE__`、WebSocket URL 都必须带挂载点。
- 首次启动无用户时提供 `POST /api/auth/setup`；`HDSL_ADMIN_PASSWORD` 可用于首启建号。

### 2.2 认证与提权
- 单管理员；PBKDF2WithHmacSHA256，per-user 盐，21 万次迭代；`users.json` 0600。
- 会话 = `HttpOnly` + `SameSite=Strict` cookie（HTTPS 下加 `Secure`），`Path` 跟挂载点走，
  滑动过期（默认 168h）；改密码/改用户名让同用户其它会话立即失效。
- `auth.disabled: true` 仅当绑定回环时允许。
- **开发者密码**（第二把钥匙，`developer.json` 0600）：会话级提权 30 分钟滑动、可延长一次到 3 小时、
  按 **session token** 记账（不是用户名）、10 分钟内 3 次错误锁 10 分钟；`POST /api/developer/elevate`
  响应里额外返回一个 32 字节 **bearer token**（内存、同 TTL、改密码即失效），供无法持 cookie 的脚本
  用 `Authorization: Bearer` 调接口。
- **开发者面只在 HTTPS 下可用**（读主连接器真实状态，尊重 `https.trust_forwarded_proto`）：
  明文 HTTP 下开发者页只给提示与「去 HTTPS 证书」，终端 403 `insecure_transport`，提权/守卫
  409/403 `tls_required`；`/i/*` 的拦截页说明"开发者模式不可用"。**不要**在 HTTP 上放开它。

### 2.3 反代 `/i/<id>/`
- 剥掉前缀、`Host` 原样透传（实例靠 `Origin == Host` 判同源）、hop-by-hop 头双向剥离、
  `Set-Cookie` 的 `Path=/` 改写成 `/i/<id>/`（多实例同名 cookie 不互相覆盖）。
- 页面改写：注入 `<base href>`、把根路径资源引用改到挂载点、给按 `location.pathname` 路由的客户端
  注入 router shim；**CSP 严格的实例不能用内联脚本**，脚本要以同源 `<script src>` 提供。
- WebSocket 中继：`/i/*` 的升级请求要在 WebSocket 层处理（servlet 过滤器看不到升级），
  逐字转发 cookie/headers，双向 relay，消息上限要调高（一条会话历史就能超过 64 KiB）。
- `GET /i/<id>`（无斜杠）301 到 `/i/<id>/`；未运行 502、不存在 404，错误体是 JSON。
- 只对 gzip/deflate 做解压改写：遇到 br/zstd 要么把上行 `Accept-Encoding` 归一成 `identity`，
  要么干脆不改写原样透传，**不要**把压缩字节当文本处理。

### 2.4 网页终端
- POSIX 用 `posix_spawn` 起 PTY（不经 shell），子进程自己的进程组；Windows 不做浏览器 PTY，
  改为在桌面打开控制台窗口并明确告知。
- 一次性 ticket（32 字节随机、30 秒 TTL、绑定会话与实例）桥接「POST 开终端」与「WS 挂载」；
  每实例至多一个终端；`setMaxIdleTimeout(0)`（用户发呆时不能被踢）。
- 退出顺序：关输入 → 有界 drain → 关 master → `SIGKILL` 进程组；面板退出时全关。
- 浏览器侧 xterm 6：CJK 等宽字体栈、整帧与光标都没变时**一个字节都不写**、组词期间缓冲输出
  （见 §5.4）。

### 2.5 第一方 TUI 插件（`plugins/`）
- 两个包，各是一个 npm 包（`package.json` + `files` 白名单），要能被 `dsh plugin add file:<tarball>`
  离线装进实例：**进程内版**叠在 dsh 之上（peer 依赖 harness 包，运行时动态 import）与 **ACP 版**
  （独立进程，零依赖）。两者的能力差异要在 UI 与文档里说清（ACP 没有命令运行时）。
- 版本号与后端里的清单（`DshTuiPlugins.ENTRIES`）保持一致；**内容变了但版本没变也要能被发现**——
  面板按安装包摘要判断"有新版本"，记录在 `<实例 home>/.hdsl-web/first-party-installs.json`。
- 半安装态（目录在、文件不在）要显示"安装未完成"并给「重新安装」，不能显示成已安装。
- 终端内的交互约定：审批对话框四选项（数字 1-4）、`/` 命令菜单画在输入框**下方**、Tab 补全、
  `ctrl+j` 换行、`ctrl+s` 插话、`shift+tab` 计划模式、状态栏（模型 · 档位 · 计划 · cwd · 会话 · 上下文）。

### 2.6 品牌目录
- 六类品牌，安装源分三种：GitHub Release（dsh、Octop）、npm（Kimi、OpenCode）、ZCode（自建分发）。
  每个品牌的：版本目录、安装（含进度与日志）、启动/停止、端口分配（回环）、策略、终端入口、页面反代。
- **安装目录必须有完成标记**：安装成功后写 marker（Octop 是 `octop-install.json`），只有带 marker
  的目录才算"已安装"；`*.previous` 这类暂存名不是版本（§5.7）。
- **运行中的版本不能重装**（任何安装路径都要 409）。
- Octop 额外两条：portable 包 + 本地源码编译（Linux）；前端要注入挂载点补丁（见 `docs/octop.md`）。
- 品牌策略（开发者模式）：单实例（其余灰掉、数据保留，**停止仍允许**）、固定反代 `/i/octop/`
  （开启后「打开」按钮与 open 接口都要返回这个固定路径）、源码编译开关。策略存
  `<data>/brands/policy.json`。
- **图标**：只用许可清晰的标志文件（本项目自己的 `NOTICE` 里有逐个来源）。**商标不随代码许可授予**：
  上游是 MIT 不等于可以用它的 logo；没有明确授权就用中性图标 + 文字标签。

### 2.7 开发者守卫与紧急停机
- 守卫 `developer_guard.scope = off | proxy | instance | all`（默认 off，存 `server.yaml`，
  环境变量 `HDSL_DEVELOPER_GUARD`）。逐级包含：`proxy` 管 `/i/*`（HTTP + WS）；`instance` 再加实例族
  的写操作（`/api/instances|brands|zcode|packs|settings|node` 的 POST/PUT/PATCH/DELETE）；
  `all` 再加实例族之外的写操作。
- **三档都不拦读**：前端要靠 `/api/*` 的 GET 渲染，拦了只会让面板看起来"刚装好、什么都没有"。
- 守卫是**门禁不是断路器**：只挡新请求，已加载的页面、已附着的终端、正在跑的 agent 都不动。
- 守卫挡住操作时，前端要**弹开发者密码框**并重试一次，而不是甩一句错误；`/i/*` 的拦截页也要带密码表单
  （传输策略拒绝时不给表单，因为填了也没用）。
- **紧急停机**：三次确认（先 `GET /api/developer/estop/preview` 列出会杀掉哪些实例/终端）→
  `POST .../engage` 先翻转状态、再关所有终端、再强杀所有实例的整棵进程树（先收集 descendants，再
  `destroyForcibly`，**不跑用户配置的 post-exit 脚本**）；之后 `/i/*`、实例写操作、终端一律 503，
  登录/健康检查/开发者页/只读列表与日志/`/ws` 照常。冷却 1 小时，未到点 409 `estop_cooldown`。
  **状态只在内存**：重启进程/容器即解除（唯一的物理恢复方式）。重复按下不重置计时。

### 2.8 Go 客户端（`client/hdsl-tui`）
- 登录（保存会话、401 自动清）、开发者提权、实例列表、终端（WS + 本地渲染输入框）、
  错误码 → 可执行文案的映射；5 平台交叉编译，CI 把二进制挂到 Release。

### 2.9 配置与部署
- `server.yaml` 全部键可选，非法值启动失败并指出字段；环境变量优先级高于文件。至少包含：
  `bind_host`、`port`、`public_base_url`、`https.*`（cert/key/pkcs12/redirect/trust_forwarded_proto）、
  `auth.*`、`web_shell.enabled`、`developer_guard.scope`、`zcode.*`。
- HTTPS：支持 PEM / PKCS#12 / 自签生成 / 运行时热切换；`redirect_http`、`redirect_same_port`；
  反代终结 TLS 时用 `https.trust_forwarded_proto` 显式打开信任。
- Dockerfile：多阶段（node 构建 SPA → gradle 打 jar → 运行时 debian-slim），**非 root**、tini 转信号、
  健康检查打 `/api/health`。
- 裸部署（systemd）文档：`HOME` 与 `HDSL_DATA` 必须指向服务用户可写的目录。

---

## 3. 对外契约（最小集，名字固定）

| 面 | 契约 |
|---|---|
| 健康检查 | `GET /api/health` → `{"status":"ok","version":"x.y.z"}` |
| 登录 | `POST /api/auth/login`、`GET /api/auth/status`、`POST /api/auth/setup`（仅无用户） |
| 实例 | `/api/instances/*`（dsh）、`/api/brands/<brand>/instances/*`（其它品牌） |
| 反代 | `/i/<id>/`、`/i/octop/`（可选固定别名） |
| 终端 | `POST .../terminal` → `{mode,protocol,ticket,ws}`，`WSS /ws/terminal/<ticket>` |
| 开发者 | `GET /api/developer/status`（含 `https`、`guardScope`、`estop`）、`POST .../elevate`（返回 `token`）、`.../extend`、`.../revoke`、`.../web-shell`、`.../guard`、`.../estop/preview|engage|release` |
| 事件 | `WS /ws`：实例状态、任务进度、日志行、外部实例变化 |

审计：`<data>/logs/audit-*.jsonl`，每行一条 JSON，含时间、channel、动作、对象、结果、操作者、来源地址。
里面会有用户名与地址，别把它当成秘密文件而把它读进 UI；也别忘了它是 0644。

---

## 4. 构建与验证（缺一不可）

```bash
# 后端 + 两个插件（用 flock 串行，避免并发 Gradle 争锁）
flock /tmp/hdsl-gradle.lock ./gradlew --no-daemon check

# 前端三连
cd web && npm run typecheck && npm run build && npm run compat

# 插件（零依赖 + 行为测试）
node plugins/dsh-inproc-tui/scripts/check.mjs && node plugins/dsh-acp-tui/scripts/check.mjs
(cd plugins/dsh-inproc-tui && node --test) && (cd plugins/dsh-acp-tui && node --test)

# Go 客户端
cd client/hdsl-tui && gofmt -l . | grep -v '^vendor/' ; go vet ./... ; go test ./...

# 单 jar
flock /tmp/hdsl-gradle.lock ./gradlew --no-daemon shadowJar -PreleaseVersion=<版本>
unzip -l build/libs/hdsl-web-<版本>.jar | grep -c first-party-plugins   # 必须是 2 个 tgz + index.json
```

**然后真的启动一次**（这条最重要，静态检查看不出顺序类问题）：

```bash
HDSL_DATA=/tmp/panel HDSL_PORT=<随机空闲端口> HDSL_BIND_HOST=127.0.0.1 \
  java -jar build/libs/hdsl-web-<版本>.jar     # 不加任何 -D 参数
# 断言：/api/health 200；带会话请求 /i/whatever/ 是 404 而不是 500；启动日志里没有
# "restricted header name" / IllegalStateException
```

测试纪律：**先红后绿**。每条"修了什么"的用例，先把实现改回去确认它会红，再改回来。断言要写清
"只有这个修复才会给的理由"，不要写"能跑就算过"。

---

## 5. 已知的坑（照抄，别重新发现一遍）

### 5.1 `Host` 透传的属性只能在第一个 HttpClient 之前设
- **现象**：一切正常启动，但所有 `/i/*` 回 `500 java.lang.IllegalArgumentException: restricted header name: "Host"`；
  加上 `-Djdk.httpclient.allowRestrictedHeaders=host` 立刻恢复。
- **根因**：JDK 在**第一次构造 `HttpClient`** 时就把受限头允许列表定死；之后 `System.setProperty` 无效。
  只要启动路径上先碰到任何带 `static HttpClient` 的类（例如某个安装器），反代就永远发不出 `Host`。
- **做法**：`main` 的**第一行**设属性；装配完最后一个 servlet 后做一次**真探针**（构造一个带 `Host`
  的请求）自检，失败即启动失败并提示 `-D` 补救。测试要用**独立 JVM** 跑（测试 JVM 往往早已设好属性，
  进程内测试会给出假安全感）。

### 5.2 Dockerfile 必须 `COPY plugins`，并断言 jar 里有插件
- **现象**：镜像里的 jar 一个插件都没有，面板"假装"能装终端，点开才失败；CI 全绿。
- **根因**：构建阶段只 `COPY src`，而 `shadowJar` 依赖的打包任务要从 `plugins/` 取输入。
- **做法**：构建阶段加 `COPY plugins ./plugins`，并在打完后断言
  `jar tf … | grep -c '^first-party-plugins/.*\.tgz$' >= 1`，否则构建失败。

### 5.3 同一版本号、不同内容
- **现象**：改了插件代码没升版本，用户那边永远显示"已安装"，新行为永远不生效。
- **做法**：安装成功时记录**安装包摘要**（`first-party-installs.json`），列表按摘要比；没有记录也算
  "有新版本"，给「更新」按钮。升级插件时不必强求改版本号。

### 5.4 xterm 的输入法与重绘
- **现象**：中文候选框突然跑到别处；按错一个键时前面输入的一整段又被复制进输入框；"输入法被吞"。
- **根因**：xterm 6 在 composition 期间每次写屏都会重定位候选框；隐藏 textarea 还残留大写字母与空格，
  229 键会把整段旧值重发（xtermjs/xterm.js#6078）。此外每秒重绘本身就会打断输入。
- **做法**：整帧与光标都没变时**不写任何字节**；组词期间缓冲 PTY 输出、`compositionend` 后再刷；
  keyup/compositionend 之后延迟清理 textarea（`screenReaderMode` 除外）。字体栈要有 CJK 等宽字体。

### 5.4.1 测试里别用「先声明 holder，再 submit」的写法
- **现象**：`TaskApiTest.cancelMarksTheTaskAndTheWorkStops` 在 JDK 21 的 CI 上偶发失败
  （`assertTrue(tasks.cancel(...))` 得到 false），本地反复跑却是绿的。
- **根因**：`TaskService.submit` 可能在**返回句柄之前**就把 work 跑起来了；work 里读
  `holder[0].isCancelled()` 时它还是 null → NPE → 任务变失败 → cancel 返回 false。
- **做法**：work 需要句柄就用 `TaskWork` 重载（`submit(kind, id, own -> …)`，句柄直接传进来），
  不要靠调用方事后赋值；测试里的「等它跑起来」一律给 30 秒（CI 机器比本机慢得多）。

### 5.5 TUI 自己的输入细节
- composer 行首是 `│ ` + `❯ `（四格），光标列按 3 格算会压住最后两个字；Tab 需要在按键解码表里有
  `0x09`，否则"补全坏了"其实是键从来没到应用；`ctrl+j` 是换行，别解码成 enter。

### 5.6 会话列表（`/sessions`）
- dsh 的 `sessionPersistence` header **没有 `updatedAt`**：排序只能靠日志文件 mtime。
- 日志布局 `<root>/<workspace>/<sessionId>/session[.vN].jsonl[.zstd]`；一个目录可能同时有多代，
  取**最新一代**（取最大 mtime，别按 `readdir` 顺序取最后一个）。
- 根的顺序：`DSH_TUI_SESSION_ROOT` → `$DSH_HOME/sessions` → `~/.dsh/sessions` → `~/.dsh-tui/sessions`。
- 读事件用 `open(id,'read')` + `read(offset, length)`，offset/length 是**事件条数**；短读不是 EOF，
  空切片才算读到尾。
- 首条人类提示：`user/message` 且 `source.kind === 'user'`；`source` 缺失/null 的老日志也算人类；
  提示也可能先以 `agent/inbox/spliced` 落盘；`content` 可能是数组也可能直接是字符串。
- **纪律**：读不出、扫到上限没找到 → **保留**并标「标题未读出」；只有确认读到日志结尾才过滤。

### 5.7 品牌安装的半安装状态
- **现象**：源码构建被中断后面板把 `.previous` 当成已安装版本，`default` 指向一个永远装不上的名字。
- **做法**：枚举只认带 marker 的目录、忽略 `*.previous`；启动时对账（半成品改名 `.broken-<ts>`，
  把**带 marker 的** `.previous` 挪回原名）；`.broken-*` 只留最新一份；运行中的版本禁止重装。

### 5.8 子进程与超时
- **现象**：`octop init` 卡住一小时，面板永远起不来；设置了 120 秒超时却从不触发。
- **根因**：先 `readAllBytes()` 再 `waitFor(timeout)`，读永不返回。
- **做法**：独立线程**有界**排水（例如保留最后 64 KiB）+ `waitFor(timeout)`；超时要
  `descendants().forEach(destroyForcibly)` 再杀父进程，并把已捕获输出写进错误信息。

### 5.9 同 uid 不是安全边界
agent 以面板用户身份跑，同 uid 下可以直接改写 `users.json` / `developer.json` / 证书私钥
（0600 只挡别的用户），也能读 `/proc/<pid>/environ`。校验和、签名、内存基线告警都挡不住——**不要做
这些假防线**。需要把 agent 当对手时，只有 OS/容器层有效：外层再认证、拆容器、不同 uid 跑实例。

### 5.10 反代的其它细则
- 升级请求（WebSocket）不走 servlet 过滤器：`/i/*` 的守卫与停机检查必须在升级层再做一次。
- 实例的 `Set-Cookie: … Path=/` 必须改写成挂载点，否则两个实例的同名 cookie 互相覆盖。
- 实例响应若是 br/zstd，改写页面会把字节弄坏：见 §2.3。

### 5.11 Windows
- 没有 POSIX PTY：终端在桌面开控制台，杀不掉（E-Stop 里要如实说明）。
- 没有 0600 语义：凭据文件可能被同机其它账户读到，文档里写清楚。
- 路径与 `chmod` 相关代码要 `try { … } catch (UnsupportedOperationException) {}`，别让面板在这些地方崩。

### 5.12 Java 17 兼容
`options.release = 17` 时不能用 `SequencedCollection`、`List.getFirst/getLast` 等 JDK 18+ API；
`ArrayDeque` 的同名方法会掩盖这个错误，写代码时注意声明类型。`HttpClient.close()` 要到 21 才有，
需要反射或交给 GC。

### 5.13 CI 与发布
- 三条工作流：`build`（JDK 17/21，push main 触发）、`client`（tag 触发，交叉编译并上传 Release 资产）、
  `docker`（tag 触发，多架构推 ghcr）。
- `docker` 有一道"只有最新版本标签才移动 `latest`"的闸门：tag 刚推上去时 tags API 可能还没可见，
  闸门会判成非最新 → `latest` 不动。**重跑一次该 tag 的 workflow** 即可，不必改代码。
- `github.com:443` 会间歇不可达（`GnuTLS recv error` / 连接超时），而 `api.github.com` 正常：
  推送失败先重试；实在不行可以用 Git API 重放提交，然后 `git fetch` 把本地对齐（sha 会不同，内容一致）。
- 发布流程：`web-shell` → `--no-ff` 合 `main` → `shadowJar -PreleaseVersion=<版本>` → `sha256sum` 写
  `SHA256SUMS.txt` → `git tag -a` → 推 `main`/`web-shell`/tag → `gh release create`（正文写给用户看，
  不抄 CHANGELOG）→ 核三条 CI 与 ghcr 镜像标签。

---

## 6. 人工验收清单（自动化测不到的部分）

1. 独立数据目录起一个面板（**不要**在生产面板上试 E-Stop），装一个实例，打开页面、登录、发一条消息。
2. 网页终端：中文输入法连续输入、粘贴多行、`ctrl+o` 展开、终端退出后重连。
3. 手机：品牌实例列表可竖向滚动、行内按钮可横向滑动、长 url 不撑破卡片。
4. 反代：子路径部署（`HDSL_BASE_PATH`）下页面与 WebSocket 都通。
5. 开发者模式：提权 → 开守卫（proxy / instance / all 各试一次）→ 读仍然可用、写被要求密码 →
   E-Stop 三次确认 → 冷却期内解除被拒 → 重启面板后解除。
6. 品牌安装：portable 与源码构建各一次；中途杀面板，确认重启后会自动恢复/清理半安装。

---

## 7. 明确不要做的事

- 不要在 HTTP 上放开开发者模式（没有 TLS 时"提权"就是明文传密码）。
- 不要给进程内检查加"哈希文件保护"之类的假防线（§5.9）。
- 不要隐藏不可用功能，要变灰并写原因。
- 不要在仓库里写真实域名 / 内网地址 / 宿主机路径 / 口令。
- 不要 force push 远端历史、不要移动已发布的 tag（除非用户明确要求"同版本重发"）。
- 不要把"测试全绿"当成发布依据：本文里至少 §5.1、§5.7、§5.8 都是**只有跑起来**才看得见的。
