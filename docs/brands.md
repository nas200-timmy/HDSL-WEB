# 第三方品牌实例（Kimi Code / OpenCode）

> 与 ZCode 实验品类并列的另外两类「自带网页端的第三方工具」：面板负责**安装（npm 指定版本）、
> 启动、停止、日志、以及把它们自己的网页端经 `/i/<id>/` 反代出来**（同端口、同证书、同登录门）。
> 面板不提供插件管理（这两个工具没有可用的插件生态），也不注入任何凭据——登录、模型供应商、
> 计费都由工具自己的网页界面完成。

## 面板到底做了什么 / 没做什么

**做了**：从 npm registry 拉指定版本装进品牌目录（`<数据目录>/brands/<品牌>/releases/<版本>/`，
pnpm store 跨实例去重）；从回环端口拉起 `kimi web` / `opencode web`；把它们的网页端改写后挂在
`/i/<id>/`（根绝对资源加挂载点前缀 + 注入运行期 shim，与 dsh 同一套三层改写）；启动/停止/日志/删除。

**没做（刻意）**：
- **插件管理**：两家都没有可用生态，不做。
- **凭据注入**：账号、API key、模型供应商都在工具自己的网页里配。每个实例的状态目录
  （Kimi Code 的 `~/.kimi-code`、OpenCode 的 `~/.config/opencode`）落在实例自己的 `home/` 里、
  随 `/data` 卷持久化——凭据明文在容器文件系统里，与 ZCode 品类同等待遇。
- **行为担保**：第三方工具，由各自官方分发、更新与计费；面板仅负责安装、启动与网页反代，
  其功能、安全与行为由各官方负责。上游周更，旗标随时可能漂移。

## 两个品牌的差异（实测记录，2026-10）

| | Kimi Code | OpenCode |
|---|---|---|
| npm 包 | `@moonshot-ai/kimi-code` | `opencode-ai` |
| 网页端命令 | `kimi web` | `opencode web` |
| 端口 | `--port 0` 可用（内核给临时端口，就绪行播报） | **`--port 0` 不生效**（静默回落默认 4096）→ 面板预分配 |
| 就绪行 | `Local: http://127.0.0.1:<port>/` | `Web interface: http://127.0.0.1:<port>/`（**带 ANSI 颜色码**，匹配前先剥除） |
| 反代鉴权 | `--dangerous-bypass-auth`（官方文档写明用于 "behind your own authenticating proxy"，面板会话门就是这个代理）+ `--allowed-host <面板域名>`（防 DNS rebinding，对应 dsh 的 `--trusted-host`） | 不设 `OPENCODE_SERVER_PASSWORD`（启动日志会打印 unsecured 告警——面板门就是门） |
| 浏览器入口 | `/i/<id>/`（Kimi 的客户端认子路径，与 dsh 同款三层改写） | **独立 origin 端口**（见下） |
| 版本 | npm 上 82 个干净 semver 版本 | 944 个（registry 里 1.2 万个 snapshot/ci tag，面板只列 `x.y.z`） |
| 离线安装 | ❌ postinstall 要从厂商 CDN 拉原生二进制 | ✅ 平台二进制走 registry optionalDependencies |

## 为什么 OpenCode 走独立端口而不是 `/i/<id>/`

OpenCode 的网页客户端**按 URL 路径路由**（bundle 里直接读 `window.location.pathname` 匹配
路由表）。把它放在任何子路径下——直连也好、反代也好——界面都只剩左上角的壳，主内容区空白
（2026-10 实测：不经反代、直接访问 `http://127.0.0.1:<port>/i/whatever/` 复现）。这类客户端
没法用挂载点伺候，所以面板给它一个**自己的 origin**：

- compose 把 `3091-3100` 端口段发布出去（最多 10 个这类实例；不发布则面板内部照绑、容器外不可达）；
- 启动时从该段分配一个端口、写进实例清单（origin 随实例固定，重启不变），面板在同一端口上加一个
  连接器：同一张证书、同一个会话门（会话 cookie 按域名生效、跨端口携带）；
- 该端口上的请求**原样转发**到实例回环端口——路径不动、页面不改写、不注 shim，应用就像站在
  自己的根上，子路径挂载要解决的一整类问题（根绝对资源、运行期 URL、CSP、路由）都不存在；
- 「打开」返回绝对地址 `https://<面板域名>:<端口>/`。

Kimi Code 的客户端认子路径（e2e 无头浏览器实测渲染完整），继续走 `/i/<id>/`。

## 已知限制

- **同一 origin 的 localStorage 共用**：和 dsh 实例一样，多个品牌实例挂在一个 origin 下时浏览器
  storage 按 origin 隔离、不看路径（Kimi Code 的 web UI 实证使用 `kimi-web.*` 键）。同品牌开多个
  实例会互踩界面状态。需要彻底隔离时的方案见部署文档 §8 末尾的讨论。
- **CSP**：OpenCode 的页面带 `script-src 'self'`，**内联脚本会被拒**——所以运行期 shim 不是内联的，
  而是面板在每个挂载点下自己提供的一个同源脚本（`/i/<id>/__hdsl-shim.js`），页面改写只引用它。
  这个坑是 e2e 用无头浏览器实测挖出来的（shim 在 HTML 里、fetch 却是 native、运行期请求全 404）。
- **面板重启后品牌实例显示为已停止**：进程表在内存里（与 ZCode 品类相同语义），重新点启动即可。
- **`/i/<id>/` 未启动时 404**：品牌实例 id 只在面板进程内登记过才解析；磁盘上有、从未启动过的
  实例走品牌区 API 启动。

## 相关代码

- 后端：`src/main/java/org/jackhuang/hmcl/web/brand/`（`Brand` 描述符、`BrandCatalog` 版本目录、
  `BrandInstaller` 安装器、`BrandInstanceManager`、`BrandRuntime` 进程运行时、`BrandApiServlet`
  挂在 `/api/brands/*`）；反代目标解析在 `InstanceProxyTargets`（dsh → 品牌 → zcode 顺序）。
- 反代泛化：`InstanceProxyServlet` 的页面改写从「dsh 精确形状」扩展为「任意根绝对 SPA」，回归
  夹具是 `src/test/resources/brand-pages/` 下的两个真实首页 HTML。
- 前端：`web/src/components/BrandsSection.tsx`（品牌区）、`LaunchPane.tsx`（跨品牌实例切换器）。
