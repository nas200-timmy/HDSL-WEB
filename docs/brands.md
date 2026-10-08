# 第三方品牌实例（Kimi Code / OpenCode）

> 与 ZCode 实验品类并列的另外两类「自带网页端的第三方工具」：面板负责**安装（npm 指定版本）、
> 启动、停止、日志、以及把它们自己的网页端经 `/i/<id>/` 反代出来**（同端口、同证书、同登录门）。
> 面板不提供插件管理（这两个工具没有可用的插件生态），也不注入任何凭据——登录、模型供应商、
> 计费都由工具自己的网页界面完成。

## 面板到底做了什么 / 没做什么

**做了**：从 npm registry 拉指定版本装进品牌目录（`<数据目录>/brands/<品牌>/releases/<版本>/`，
pnpm store 跨实例去重）；从回环端口拉起 `kimi web` / `opencode web`；把它们的网页端改写后挂在
`/i/<id>/`（根绝对资源加挂载点前缀 + 注入运行期 shim，与 dsh 同一套三层改写；按路径路由的客户端
再补一层路由修正，见下）；启动/停止/日志/删除。

**没做（刻意）**：
- **插件管理**：两家都没有可用生态，不做。
- **凭据注入**：账号、API key、模型供应商都在工具自己的网页里配。每个实例的状态目录
  （Kimi Code 的 `~/.kimi-code`、OpenCode 的 `~/.config/opencode`）落在实例自己的 `home/` 里、
  随 `/data` 卷持久化——凭据明文在容器文件系统里，与 ZCode 品类同等待遇。
- **行为担保**：第三方工具，由各自官方分发、更新与计费；面板仅负责安装、启动与网页反代，
  其功能、安全与行为由各官方负责。上游周更，旗标随时可能漂移。
  **OpenCode 在界面上标为实验性接入、不保证可用性**：面板能做的（挂载、改写、反代）已经到极限，
  剩下遇到的问题基本都是它自己的——模型目录与模型 id 对不上（实测：客户端挑到
  `deepseek/deepseek-flash`，而它自己的服务器只有 `deepseek-flash`，于是 agent 起不来、会话里
  只有用户消息没有任何回复）、会话与 agent 行为、凭据与计费。这类问题面板修不了，只能换模型、
  换版本或等上游修；诊断过程见 [report-2026-10-08-opencode-web.md](report-2026-10-08-opencode-web.md)。

## 两个品牌的差异（实测记录，2026-10）

| | Kimi Code | OpenCode |
|---|---|---|
| npm 包 | `@moonshot-ai/kimi-code` | `opencode-ai` |
| 网页端命令 | `kimi web` | `opencode web` |
| 端口 | `--port 0` 可用（内核给临时端口，就绪行播报） | **`--port 0` 不生效**（静默回落默认 4096）→ 面板预分配 |
| 就绪行 | `Local: http://127.0.0.1:<port>/` | `Web interface: http://127.0.0.1:<port>/`（**带 ANSI 颜色码**，匹配前先剥除） |
| 反代鉴权 | `--dangerous-bypass-auth`（官方文档写明用于 "behind your own authenticating proxy"，面板会话门就是这个代理）+ `--allowed-host <面板域名>`（防 DNS rebinding，对应 dsh 的 `--trusted-host`） | 不设 `OPENCODE_SERVER_PASSWORD`（启动日志会打印 unsecured 告警——面板门就是门） |
| 浏览器入口 | `/i/<id>/`（客户端认子路径，与 dsh 同款改写） | `/i/<id>/` + **路由修正脚本**（见下） |
| 版本 | npm 上 82 个干净 semver 版本 | 944 个（registry 里 1.2 万个 snapshot/ci tag，面板只列 `x.y.z`） |
| 离线安装 | ❌ postinstall 要从厂商 CDN 拉原生二进制 | ✅ 平台二进制走 registry optionalDependencies |

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

## 已知限制

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

- 后端：`src/main/java/org/jackhuang/hmcl/web/brand/`（`Brand` 描述符、`BrandCatalog` 版本目录、
  `BrandInstaller` 安装器、`BrandInstanceManager`、`BrandRuntime` 进程运行时、`BrandApiServlet`
  挂在 `/api/brands/*`）；反代目标解析在 `InstanceProxyTargets`（dsh → 品牌 → zcode 顺序）。
- 反代泛化：`InstanceProxyServlet` 的页面改写从「dsh 精确形状」扩展为「任意根绝对 SPA」，回归
  夹具是 `src/test/resources/brand-pages/` 下的两个真实首页 HTML。
- 前端：`web/src/components/BrandsSection.tsx`（品牌区）、`LaunchPane.tsx`（跨品牌实例切换器）。
