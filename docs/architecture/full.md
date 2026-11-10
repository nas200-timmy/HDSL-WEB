# 全量模块与数据布局（战术）

这张图是**模块级全量**：把边缘、API、控制面服务、实例运行时、进程/PTY、`/data` 和外部依赖放在一张图上。它不展开到类和方法；单个品牌或单个下载器的细节看 `flows/`。

> 图较大，建议在 GitHub 里点开全屏查看。节点 ID 是给 Mermaid 用的，标签才是给人看的。

```mermaid
flowchart LR
  subgraph client["客户端"]
    direction TB
    spa["React SPA<br/>web/src"]
    pages["实例页面<br/>dsh · 品牌 · MC"]
    wsclient["WebSocket 客户端"]
  end

  subgraph edge["Jetty 边缘 :3080"]
    direction TB
    static["StaticServlet<br/>SPA 静态资源"]
    authf["AuthFilter<br/>会话 / Agent 票据"]
    guard["DeveloperGuardFilter<br/>提权 / E-Stop"]
    mcfilter["McFeatureFilter<br/>/api/mc/* 门（关闭即 404）"]
    proxy["InstanceProxyServlet<br/>/i/* + WS 中继"]
    wsgw["WsGateway<br/>/ws/*"]
    termws["TerminalWebSocket<br/>/ws/terminal/*"]
    apiguard["ApiGuardFilter<br/>JSON 500"]
  end

  subgraph apilayer["API 层"]
    direction TB
    api["System · Auth · Instances · Versions<br/>Tasks · Node · Plugins · Dsh<br/>Packs · Exports · Doctor · Accounts<br/>Vendors · Models · ZCode · Brands<br/>Developer · Settings · Network<br/>Wallpapers · MC"]
  end

  subgraph services["控制面服务"]
    direction TB
    task["TaskService<br/>安装 / 下载 / 构建任务"]
    event["EventBus"]
    audit["AuditLog"]
    authsvc["AuthService / UserStore<br/>LoginThrottle"]
    elev["DeveloperAuth / DeveloperElevation<br/>AgentScopes"]
    peer["PeerIdentity / SocketOwnerLookup"]
    termmgr["TerminalManager / TerminalTickets"]
    sysm["SystemMetrics / SystemActivity"]
  end

  subgraph runtime["实例运行时"]
    direction TB
    dshrt["DshInstanceManager / DshProcessManager<br/>dsh 进程池"]
    brandrt["BrandRuntime / BrandInstaller<br/>BrandCatalog / BrandPolicy"]
    mcrt["McInstanceManager / McRuntime<br/>McCores / McForge / McSources"]
    mcjava["McJavaRuntimes<br/>托管 JDK"]
    zcodert["ZcodeRuntime / ZcodeBuilder"]
    nodert["NodeRuntimeManager<br/>托管 Node"]
  end

  subgraph procs["进程 / 伪终端"]
    direction TB
    dshp["dsh web"]
    brandp["Kimi · OpenCode · Claude Web<br/>Octop · Hermes · PI WEB"]
    mcp["Minecraft Java + RCON"]
    zcodep["ZCode 构建 / 运行进程"]
    ptyp["PTY<br/>JNA · ConPTY · PosixPty"]
  end

  data[("/data 卷")]

  subgraph storage["持久化布局"]
    direction TB
    cfg["server.yaml<br/>设置"]
    authd["auth/<br/>users · developer · agent"]
    certs["certs/<br/>TLS"]
    logs["logs/<br/>面板 · 审计"]
    hdsl["hdsl/<br/>launcher-settings · instances · homes<br/>runtimes · catalog · logs"]
    brandsd["brands/<brand>/<br/>releases · instances"]
    mcd["minecraft/<br/>cores · java · versions · backups<br/>sources.json · eula.json"]
    mcr["MC 存档根<br/>$HOME/mc 或 HDSL_MC_DIR / mc.dir"]
    other["packs/ · plugins/ · exports/<br/>wallpapers/ · tmp/"]
  end

  subgraph ext["外部依赖"]
    direction TB
    npm["npm / pnpm registry"]
    gh["GitHub 品牌目录"]
    upstream["Mojang · Paper · Purpur<br/>Fabric · Forge · NeoForge"]
    mirror["BMCLAPI · MSL"]
    modrinth["Modrinth"]
    nodesrc["Node.js 下载源"]
    jdk["JDK 下载源"]
  end

  spa --> static
  pages --> proxy
  wsclient --> wsgw
  wsclient --> termws
  proxy --> authf
  wsgw --> authf
  termws --> authf
  authf --> guard
  guard --> api
  guard --> mcfilter
  mcfilter --> api
  api -.-> apiguard

  api --> task
  api --> authsvc
  api --> elev
  api --> brandrt
  api --> mcrt
  api --> mcjava
  api --> dshrt
  api --> zcodert
  api --> sysm
  api --> audit
  api --> termmgr

  task --> brandrt
  task --> mcrt
  task --> dshrt
  task --> zcodert
  task --> nodert

  event --> wsgw
  termmgr --> ptyp
  dshrt --> dshp
  brandrt --> brandp
  mcrt --> mcp
  zcodert --> zcodep
  ptyp --> dshp
  ptyp --> brandp
  ptyp --> mcp

  proxy --> dshrt
  proxy --> brandrt
  proxy --> zcodert

  authsvc --> authd
  elev --> authd
  audit --> logs
  dshrt --> hdsl
  brandrt --> brandsd
  zcodert --> hdsl
  nodert --> hdsl
  mcrt --> mcd
  mcrt --> mcr
  api --> cfg
  api --> certs

  brandrt --> gh
  brandrt --> npm
  mcrt --> upstream
  mcrt --> mirror
  mcrt --> modrinth
  nodert --> nodesrc
  mcjava --> jdk
  mcjava --> mcd

  data --> cfg
  data --> authd
  data --> certs
  data --> logs
  data --> hdsl
  data --> brandsd
  data --> mcd
  data --> other
```

## API 面

| 前缀 | Servlet | 职责 |
|---|---|---|
| `/api/system/*` | `SystemApiServlet` | CPU / 内存 / 磁盘 / 网络快照 |
| `/api/auth/*`、`/api/health` | `ApiServlet` + `AuthFilter` | 登录、首次设置、健康检查 |
| `/api/instances/*` | `InstancesApiServlet` | dsh 实例的创建、启停、终端、导入导出 |
| `/api/versions` | `VersionsApiServlet` | 游戏版本清单 |
| `/api/tasks/*` | `TasksApiServlet` | 任务列表、取消、进度 |
| `/api/node/*` | `NodeApiServlet` | 托管 Node 运行时与消费者视图 |
| `/api/plugins/*`、`/api/dsh/*` | `PluginsApiServlet`、`DshApiServlet` | 第一方 TUI 插件与 dsh 目录 |
| `/api/packs/*`、`/api/exports/*` | `PacksApiServlet`、`ExportsApiServlet` | 整合包与实例导出 |
| `/api/doctor`、`/api/accounts/*`、`/api/vendors`、`/api/models/*` | 对应 Servlet | 自检、账户、供应商、模型目录 |
| `/api/zcode/*` | `ZcodeApiServlet` | ZCode 实验性构建与运行 |
| `/api/brands/*` | `BrandApiServlet` | 品牌安装、实例、日志、终端、反代入口 |
| `/api/developer/*` | `DeveloperApiServlet` | 开发者提权、Agent 票据、E-Stop |
| `/api/settings/*` | `TlsApiServlet`、registry / 日志保留 / 品牌源 | TLS、registry、品牌源、日志保留 |
| `/api/network/*` | `NetworkApiServlet` | 代理、超时、网络自检 |
| `/api/wallpapers/*` | `WallpaperApiServlet` | 壁纸 |
| `/api/mc/*` | `McApiServlet` | MC 实例、核心、Java、插件、世界、备份、控制台（受 `McFeatureFilter` 保护） |
| `/i/{id}/*` | `InstanceProxyServlet` | dsh / 品牌 / ZCode 的 HTTP + WebSocket 反代 |
| `/ws/*`、`/ws/terminal/*` | `WsGateway`、`TerminalWebSocket` | 事件流、终端 PTY |
| `/` | `StaticServlet` | React SPA |

## 端口与进程

| 端口 / 通道 | 归属 | 说明 |
|---|---|---|
| `:3080` | 面板 | HTTP 入口（`https.enabled` 打开后变 HTTPS）；可被 nginx/Caddy 再包一层 |
| `3081–4081` | dsh | `DshPorts` 的实例端口池 |
| `20000–60999` | 品牌实例 | `BrandRuntime` 预分配的 loopback 端口 |
| `25565` / `25575` | MC | 对玩家开放的游戏端口 / RCON，每个实例可单独配置 |
| `/i/{id}/` | 反代 | 只代理 dsh、品牌、ZCode；MC 不走这里 |
| `/ws/terminal/{ticket}` | 终端 | 一次性 ticket 换取 PTY 连接 |

## 数据布局

```mermaid
flowchart LR
  data[("/data")] --> cfg["server.yaml"]
  data --> auth["auth/"]
  auth --> users["users.json"]
  auth --> dev["developer.json"]
  auth --> agent["agent.json"]
  data --> certs["certs/"]
  data --> logs["logs/"]
  data --> hdsl["hdsl/"]
  hdsl --> launcher["launcher-settings.json"]
  hdsl --> hinst["instances/<id>/"]
  hdsl --> hhomes["homes/<version>/"]
  hdsl --> runtimes["runtimes/<node>/"]
  hdsl --> catalog["catalog/"]
  data --> brands["brands/<brand>/"]
  brands --> releases["releases/<version>/"]
  brands --> binst["instances/<id>/<br/>home · workspace · logs"]
  data --> minecraft["minecraft/"]
  minecraft --> cores["cores/<core>/<version>/"]
  minecraft --> java["java/<feature>/"]
  minecraft --> versions["versions/"]
  minecraft --> backups["backups/"]
  minecraft --> sources["sources.json"]
  minecraft --> eula["eula.json"]
  data --> other["packs/ · plugins/ · exports/<br/>wallpapers/ · tmp/"]
  data -.-> legacy["minecraft/instances/<br/>旧 MC 位置，只读兼容"]
  mcroot["MC 存档根<br/>$HOME/mc 或 HDSL_MC_DIR / mc.dir"] --> mcinst["instances/<id>/<br/>world · server.jar · logs"]
```

## 读图提示

- `api` 是一个聚合节点，具体路由看上面的表；Servlet 之间不互相调用，业务都在 `services` / `runtime`。
- `task` 是长任务的入口：MC 安装、Java 安装、整合包、品牌安装都可能在这里跑；`EventBus` 把进度送到 `/ws`。
- `proxy` 不直接持有实例，它通过 `InstanceProxyTargets` 向 `BrandRuntime` / `ZcodeRuntime` / dsh 进程池要 loopback 端口。
- `ptyp` 是终端复用的那一层：品牌 TUI、dsh TUI、MC 控制台都最终落到 PTY。
- `mcfilter` 是 MC 的第一道门：功能关闭时 `/api/mc/*` 返回 **404**（不是 403），让“这里原本有东西”不可见。第二道门（第三方核心）在 `McApiServlet` 内部按同样口径 404，不出现在这条 Filter 链上。
- `/ws/terminal/{ticket}` 在 `AuthFilter` 里是一条公开路径，但它自己就是门：握手时校验一次性 ticket **且** 校验签发票据的会话 Cookie 或 Agent 授权（见 `flows/auth.md`）。
- `/data` 是持久化边界；MC 存档根可能不在 `/data` 下，取决于 `mc.dir` / `HDSL_MC_DIR`（后者优先）/ `$HOME/mc`。
