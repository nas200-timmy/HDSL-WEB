# 第三方品牌实例（Kimi Code / OpenCode / Claude Web）

> 与 ZCode 实验品类并列的另外三类「自带网页端的第三方工具」：面板负责**安装指定版本、
> 启动、停止、日志、以及把它们自己的网页端经 `/i/<id>/` 反代出来**（同端口、同证书、同登录门）。
> 安装来源有两种：npm registry（Kimi Code / OpenCode）与 git 仓库 tag（Claude Web，首个 git 源品牌）。
> 界面在**实例列表页内**（左侧子侧栏「第三方品牌 → 品牌实例」，内容区原地换成品牌层，
> 不跳第二层页面）：实例是主对象，版本是品牌级共享构建。面板不提供插件管理（这些工具没有
> 可用的插件生态），但**每实例的插件通道已在界面与后端预留**（见「实例模型」）；也不注入任何
> 凭据——登录、模型供应商、计费都由工具自己的网页界面完成。

## 面板到底做了什么 / 没做什么

**做了**：安装指定版本进品牌目录（`<数据目录>/brands/<品牌>/releases/<版本>/`）。npm 品牌从
registry 拉包（pnpm store 跨实例去重）；git 源品牌（Claude Web）从 **GitHub 发行 tag 下载源码 zip**
（本网络下 `github.com:443` 的 git 传输会被拦腰切断，源码 zip 1.5 秒下完 11 MB——详见 v0.3.8 的
变更记录）后只装 server/web 两个 workspace **加根项目**（`npm ci -w server -w web
--include-workspace-root`——漏掉根项目就少了网页端运行内核 ccb，所有 OpenAI 类型供应商都会静默
回退到官方 Claude Code）再 `npm run build -w web`。从回环端口拉起各自的网页端（`kimi web` /
`opencode web` / claude-web 的 Node 服务）；把网页端改写后挂在 `/i/<id>/`（根绝对资源加挂载点前缀
+ 注入运行期 shim，与 dsh 同一套三层改写；按路径路由的客户端再补一层路由修正，见下）；
启动/停止/日志/删除。

**没做（刻意）**：
- **插件管理**：各家都没有可用生态，不做。
- **凭据注入**：账号、API key、模型供应商都在工具自己的网页里配。每个实例的状态目录
  （Kimi Code 的 `~/.kimi-code`、OpenCode 的 `~/.config/opencode`、Claude Web 的 `~/.claude-web`）
  落在实例自己的 `home/` 里、随 `/data` 卷持久化——凭据明文在容器文件系统里，与 ZCode 品类同等待遇。
- **行为担保**：第三方工具；面板仅负责安装、启动与网页反代，其功能、安全与行为归各自官方/社区。
  上游周更，旗标随时可能漂移。
  **OpenCode 与 Claude Web 在界面上标为实验性接入、不保证可用性**：面板能做的（挂载、改写、反代）
  已经到极限。OpenCode 剩下遇到的问题基本都是它自己的——模型目录与模型 id 对不上（实测：客户端挑到
  `deepseek/deepseek-flash`，而它自己的服务器只有 `deepseek-flash`，于是 agent 起不来、会话里
  只有用户消息没有任何回复）、会话与 agent 行为、凭据与计费。这类问题面板修不了，只能换模型、
  换版本或等上游修；诊断过程见 [report-2026-10-08-opencode-web.md](report-2026-10-08-opencode-web.md)。
  Claude Web 是**社区项目**（不是 Anthropic 官方网页版）：内置社区构建的引擎，登录可用官方账号也可
  在「设置 → 供应商」接第三方中转——引擎、供应商、凭据、计费都归上游/服务商。

## 三个品牌的差异（实测记录，2026-10）

| | Kimi Code | OpenCode | Claude Web |
|---|---|---|---|
| 来源 | npm 包 `@moonshot-ai/kimi-code` | npm 包 `opencode-ai` | **GitHub 仓库** [ypyik0669/claude-web](https://github.com/ypyik0669/claude-web)（MIT，社区项目；不发 npm 包） |
| 版本列表 | npm registry（82 个干净 semver） | npm registry（944 个；1.2 万个 snapshot/ci tag 被滤） | **GitHub REST** `/repos/<slug>/tags`（剥 `v` 前缀；`engine-*`/`nightly` 等非发行 tag 被滤） |
| 安装 | pnpm 拉包 | pnpm 拉包 | **`codeload.github.com/<slug>/zip/refs/tags/<tag>` 下载源码 zip**（带重试）→ 解包（剥掉 `<repo>-<tag>/` 根目录）→ `npm ci -w server -w web`（**不装 desktop**，Electron 二进制不下载）→ `npm run build -w web` |
| 网页端命令 | `kimi web` | `opencode web` | `tsx server/src/index.ts`（Node 直跑 TS 源码，无内置入口） |
| 端口 | `--port 0` 可用（内核给临时端口，就绪行播报） | **`--port 0` 不生效**（静默回落默认 4096）→ 面板预分配 | 无端口参数 → **`PORT` 环境变量**预分配（源码核实 `PORT ?? 3090`） |
| 就绪行 | `Local: http://127.0.0.1:<port>/` | `Web interface: http://127.0.0.1:<port>/`（**带 ANSI 颜色码**，匹配前先剥除） | `listening on http://127.0.0.1:<port>` |
| 反代鉴权 | `--dangerous-bypass-auth`（官方文档写明用于 "behind your own authenticating proxy"，面板会话门就是这个代理）+ `--allowed-host <面板域名>`（防 DNS rebinding，对应 dsh 的 `--trusted-host`） | 不设 `OPENCODE_SERVER_PASSWORD`（启动日志会打印 unsecured 告警——面板门就是门） | 无 token 配置时**信任回环**（源码 `authOk`）；WS 升级校验 Origin==Host——与面板反代「Host 透传」契约一致，实测链路通 |
| 浏览器入口 | `/i/<id>/`（客户端认子路径，与 dsh 同款改写） | `/i/<id>/` + **路由修正脚本**（见下） | `/i/<id>/` 标准 shim：前端 `base: './'` 资源全相对、单 WebSocket、无 history 路由——**零补丁** |
| 离线安装 | ❌ postinstall 要从厂商 CDN 拉原生二进制 | ✅ 平台二进制走 registry optionalDependencies | ❌ engine tgz 从 GitHub Releases 拉；npm 依赖可走镜像 registry |

### 为什么 Claude Web 是「零补丁」

上游的 web 构建用的 `base: './'`（资源全部相对路径——上游注释写明是为「手机壳跑在子文件夹」场景），
前端与服务器只通**一条 WebSocket**（`client.ts` 里 `new WebSocket(...location.host + '/ws')`，无散落的
`fetch('/api…')`），且没有任何 history 路由（刷新即首页）。所以 `/i/<id>/` 挂载时：静态资源浏览器按
相对路径自己拼对；WS 地址是绝对 `/ws`，由面板注入品牌页的标准运行期 shim 改写（与 Kimi/OpenCode 同款
机制）；WS 握手的 Origin 校验（`originOk` 要求 Origin==Host 或回环）正好与面板反代一贯以来的 Host 透传
契约吻合（这个契约本身就是 dsh 的 Origin 防护定的）。结论：**不需要上游改动，用户预备的上游 PR 用不上**。

### 为什么下载源码而不用 `git clone`

实测（2026-10-12，本机网络）：`github.com:443` 的 **git 传输会被拦腰切断**——`expected flush after ref
listing`、GnuTLS `-110`、乃至 130 秒连接超时，成功率大约三成；而同一时刻 `api.github.com` 0.6 秒
200，`codeload.github.com` 的源码 zip 1.5 秒下完 11 MB。于是这一品牌**完全不碰 git**：版本列表走
REST API（未认证 60 次/小时，30 分钟缓存远够），安装走 zip 下载 + 解包，下载失败自动重试 3 次。
附带两个好处：镜像不需要 `git` 二进制；下载是普通 HTTPS，失败信息是明确的 HTTP 状态码/超时，
比 git 协议一堆含糊的中断好诊断。

另外：版本目录**从不缓存空结果**——网络抽风时抓空是常态，缓存空列表会让下拉框在网络恢复后仍空白
整整 30 分钟（这正是首次上线后「读取不出来」的原因）。

### 「打开文件夹…」在容器里为什么不能用

Claude Web 的项目选择器里那个 `打开文件夹…` 叫的是**服务器端的原生目录对话框**（它自己的
`terminal/service.ts` `pickDir`：Linux 用 `zenity`、macOS 用 `osascript`、Windows 用 PowerShell）。
容器里没有桌面、也没装 zenity，`execFile` 失败后它**静默返回 null**，前端 `if (p) setCwd(p)` ——
于是点下去什么都不会发生（不是面板或反代的毛病）。

面板的绕法：**实例就绪后，用它自己的协议 `workspaces.add` 把该实例的工作区目录登记成项目**
（`ws://127.0.0.1:<端口>/ws`，回环免鉴权；失败只损失一次点击）。之后打开页面，
「项目」菜单里就能直接选中 `workspace` 目录，完全不需要那个对话框。若还想用别的目录，
可以先用面板的文件能力把它放进实例工作区，或用它的终端 `cd` 过去。

### Claude Web：官方内核与 `ANTHROPIC_BASE_URL`

Claude Web 有两个内核（社区引擎 `claude-web-engine`、官方 `@anthropic-ai/claude-agent-sdk-*`），
**两个都最终连 Anthropic**：官方内核是官方 Claude Code，社区引擎是它的社区构建。而 Anthropic 的
[支持地区政策](https://www.anthropic.com/legal/supported-regions)把未列出的国家/地区（含中国大陆）
列为不受支持，并明确覆盖「人在不支持地区使用」与「主体注册在不支持地区」两种情形；从这类网络出口
访问 `api.anthropic.com` 一律 **403**，Claude Code 自己会打印
`App unavailable in region` 然后退出。

面板为此做两件事：

- **实例级的端点覆盖**：实例菜单 →「Anthropic 端点…」可以填 `ANTHROPIC_BASE_URL`（留空即恢复默认）。
  值随实例存进 `instance.json`，**网页模式与终端都用它**（同一个 workbench、同一份 CLI 配置），
  面板不校验端点是谁，也不代管凭据——走官方网关还是第三方中转、以及服务条款上的风险，都由使用者判断。
  若网关需要密钥，把 `ANTHROPIC_API_KEY` / `ANTHROPIC_AUTH_TOKEN` 放在面板进程的环境里：子进程继承
  面板的环境，无需在面板里再存一份。
- **官方内核的预检**：选官方内核时，面板先对该端点 `GET /v1/models`——不受支持地区回 **403**，
  受支持地区没带 key 回 **401**。只有 403 会被拦下并给出人话（社区引擎或配置端点二选一），
  而不是把一个起不来的 TUI 丢给用户；401、超时、DNS/TLS 失败都放行，由 CLI 自己解释。

要绕过地区限制，正路是用**受支持地区**的出口或官方支持的云厂商（Bedrock / Vertex）通道；
第三方中转不是 Anthropic 的服务，提示词与密钥都会交给对方，而且在不受支持地区使用本身就落在上面
那条政策里，账号风险自负。

## 为什么 OpenCode 需要一段「路由修正脚本」

OpenCode 的网页客户端**按 URL 路径路由**（bundle 里直接读 `window.location.pathname` 匹配路由表，
且整个发行包没有任何 base-path 开关）。挂在 `/i/<id>/` 下时它匹配不到任何路由，界面只剩左上角的
壳（实测：不经反代、直连实例自己的端口加任意子路径，现象完全相同——是客户端本身，不是代理）。
但它仍然走**和 dsh 一样的单端口挂载**，只是这类页面会多拿到两样**必须成对出现**的东西：

- **面板改写它的 bundle**（`assets/index-*.js`，约 2.7 MB）：把路由读取地址的那一处表达式
  （Solid Router 的 `window.location.pathname.replace(/^\/+/,"/")`，实测 1.18.32 与 1.18.35 一致）
  换成先经过挂载点剥离的版本。于是**客户端读到的路径永远是它期望的原生根路径**
  （`/i/<id>/server/k/session/x` → `/server/k/session/x`），路由照常匹配；
- **`/i/<id>/__hdsl-router.js`**（同源外链，CSP 友好）：**把客户端写回地址时的挂载点加回去**——
  `history.pushState/replaceState` 的目标自动带上 `/i/<id>/`（已经在挂载点里的地址不会重复加）。
  于是地址栏停在 `/i/<id>/…`、刷新与深链都回到应用内的同一处（客户端自己的服务器对未知路径返回
  它的 index 页）、地址也可以直接分享；
- **它自己拼的资产地址也算在内**：bundle 里 `"/assets/…"` 字面量（worker、图标）、Vite preload
  助手的基址（`const <name> = function (dep) { return "/" + dep }`，懒加载 chunk 全走它）与 CSS 里的
  `url(/…)`（字体）同样被改写到挂载点下。少了这一步就是**首页能看、点进任何面板都是空白**——
  实测：Settings / Providers 的 chunk 全部 404、控制台 `Failed to load module script … text/html`。
- 请求本身仍全部走挂载点：`/i/<id>/__hdsl-shim.js` 把应用运行时拼的同源绝对 URL（`/assets`、
  `/api/…`、WebSocket、SSE）加上挂载点前缀。

**兜底**：面板处理页面请求时会先探一次客户端的 bundle（按挂载点 + 资源名缓存，只在第一次加载时多
一次回环读取）。如果那个表达式不在（上游换了打包方式或路由库），页面改引用
`/i/<id>/__hdsl-router.js?mode=root`：这一份 shim 把地址**还给面板根**（初次加载替换成 `/`，后续
write 也停在 `/`），应用照常可用，代价是地址栏回到 `/`、刷新落回面板首页——也就是老行为，而不是
只剩一个壳。探针失败（超时、非 200、bundle 过大）同样按兜底走。
只有一个入口端口：`3080`（或你部署时选的那个）——不需要给路由器开任何额外端口。

Kimi Code 的客户端认子路径，不需要这段脚本（实验证明：注入反而会与它自己的路由打架，故按品牌开关）。

## 实例模型：页面、版本与插件通道

### 品牌图标（来源与商标）

页签行上的标志取自各家**上游仓库自己发布的文件**，原样未改，只用于标识所管理的工具；
完整声明在仓库根 [NOTICE](../NOTICE) 的 TRADEMARKS 一节：

| 品牌 | 文件 | 来源与许可 |
| --- | --- | --- |
| dsh | `assets/img/brand-dsh.svg` | DeepSeek 的鲸鱼标，Simple Icons 分发的 CC0 文件（标志取自 deepseek.com）。dsh 实例的默认图标从此是鲸鱼，不再是 HMCL 的草方块（单色 → CSS mask + `currentColor`，浅深主题自适应） |
| Kimi Code | `assets/img/brand-kimi.svg` | `MoonshotAI/kimi-code` 的 `apps/vscode/resources/kimi-icon.svg`（MIT）。单色线稿，走 CSS mask + `currentColor`，浅深主题都自适应 |
| OpenCode | `assets/img/brand-opencode.svg` | `anomalyco/opencode` 的 `packages/identity/mark.svg`（MIT，仓库原址 `sst/opencode`）。自带底色与描边，直接 `<img>` |
| ZCode | `assets/img/brand-zcode.svg` | Simple Icons 分发的 Z.ai 标志（文件 CC0，标志本身来自 z.ai 官方 logo）。**故意不用 ZCode 仓库里那份**：它的 `NOTICE` 对"图标"有笼统保留条款，许可明确的那份优先 |
| Claude Web | **不上图** | 社区仓库的图标就是 Anthropic 的珊瑚色星芒标，而 Anthropic 商标条款写明"须事先书面许可、不得改动"。故只保留文字标签 + 中性图标 |

商标与著作权是两件事：上表的文件许可（MIT / CC0）解决的是**分发**，
标志本身仍是各权利人的**商标**——声明里写明了"不表示隶属/赞助/认可"，且未改色改形。

### 实例模型

`/instances?view=brands`（实例列表页内**原地切换**出来的一层：左侧选「品牌实例」，右侧内容区
换成品牌层，URL 只换查询串、页面不跳转、不重挂载；品牌之间用内容区顶部的页签行切换）
一页管完一个品牌的全部实例：

- **实例是主对象**：实例卡吃掉剩余高度，行内有 radio（= 启动面板的「当前实例」，与 dsh 实例列表
  同一套选择）、状态徽标、`id · 版本 · 端口/打开地址`，以及启动/停止、打开、版本、信息、重命名、
  插件、日志、删除。该品牌还没有实例时，新建行的名称框**预填品牌名**（Kimi Code / OpenCode /
  Claude Web / ZCode）——多数人一个品牌就一个实例，点一下「创建」即可。
- **版本是品牌级共享构建**：`brands/<品牌>/releases/<版本>/` 装一次，同品牌所有实例都能切过去；
  实例清单里只记它 pin 的版本号。面板不假装每个实例各装一份。
- **切换版本按需安装**：行内版本下拉列的是**全量版本目录**（已装标「已装」、未装标「未装」）。
  选已装版本 → 直接 `PATCH`；实例在跑 → 先确认「停止并切换」；选未装版本 → 确认后走品牌级安装
  （`POST /api/brands/<brand>/install`，行内显示进度与失败原因），装完再 `PATCH`。后端那条
  「未安装的版本 → 400」的校验原样保留，是安全网；编排放在前端，少一个会腐坏的新接口。
- **插件通道（预留）**：`GET /api/brands/<brand>/instances/<id>/plugins` 与
  `GET /api/zcode/instances/<id>/plugins` 返回同一形状 `{supported, plugins[]}`。现在没有任何品牌
  支持插件，能力位（`Brand.supportsPlugins()`）一律 false、列表为空，界面显示「该品牌暂不支持插件」
  并说明通道已预留。前端只按这个响应渲染：将来某个品牌有了真插件，后端把 `supported` 置真、把
  `plugins` 填上，界面不用改一行。

## 已知限制

- **Claude Web 的引擎与账号**：它内置的引擎是社区构建（claude-code-best 基底），可登官方 Claude
  账号，也可在「设置 → 供应商」里接第三方中转（DeepSeek/Kimi 等 OpenAI 兼容中转都行）。无论哪条路，
  引擎行为、供应商可用性、凭据与计费都归上游/服务商；中转路线另有 Anthropic 服务条款的考量。
- **同一 origin 的 localStorage 共用**：和 dsh 实例一样，多个品牌实例挂在一个 origin 下时浏览器
  storage 按 origin 隔离、不看路径（Kimi Code 的 web UI 实证使用 `kimi-web.*` 键）。同品牌开多个
  实例会互踩界面状态。需要彻底隔离时的方案见部署文档 §8 末尾的讨论。
- **CSP**：OpenCode 的页面带 `script-src 'self'`，**内联脚本会被拒**——所以运行期 shim 不是内联的，
  而是面板在每个挂载点下自己提供的一个同源脚本（`/i/<id>/__hdsl-shim.js`），页面改写只引用它。
  这个坑是 e2e 用无头浏览器实测挖出来的（shim 在 HTML 里、fetch 却是 native、运行期请求全 404）。
- **面板重启后品牌实例显示为已停止**：进程表在内存里（与 ZCode 品类相同语义），重新点启动即可。
- **bundle 补丁锚定上游实现**：OpenCode 路由读取地址的那一处表达式就是补丁锚点（实测 1.18.32 与
  1.18.35 一致）。上游若换掉打包方式或路由库，探针会判为「不可补丁」并自动退回「地址还给面板根」
  的模式（见上），页面不会变成壳——代价只是地址栏回到 `/`、刷新回面板。资产地址那两条改写
  （`"/assets/…"` 字面量与 preload 基址）各自独立：找不到就只跳过它。
- **`/i/<id>/` 未启动时 404**：品牌实例 id 只在面板进程内登记过才解析；磁盘上有、从未启动过的
  实例走品牌区 API 启动。

## 相关代码

- 后端：`src/main/java/org/jackhuang/hmcl/web/brand/`（`Brand` 描述符 + 插件能力位、`BrandCatalog`
  版本目录、`BrandInstaller` 安装器、`BrandInstanceManager`、`BrandRuntime` 进程运行时、
  `BrandApiServlet` 挂在 `/api/brands/*`）；反代目标解析在 `InstanceProxyTargets`（dsh → 品牌 →
  zcode 顺序）。
- 反代泛化：`InstanceProxyServlet` 的页面改写从「dsh 精确形状」扩展为「任意根绝对 SPA」，回归
  夹具是 `src/test/resources/brand-pages/` 下的真实首页 HTML。
- 前端：`web/src/components/BrandsView.tsx`（品牌层：页签行 + 版本卡 + 实例卡，嵌在实例列表页里）、
  `web/src/components/ZcodeSection.tsx`（zcode 分支的两卡内容）、`LaunchPane.tsx`
  （跨品牌实例切换器）。入口在实例列表页子侧栏（`InstancesPage.tsx`：两层原地切换）。
