# 系统上下文（战略）

这张图只回答“系统边界在哪里”：谁是使用者、面板控制什么、实例在哪里跑、外部依赖有哪些、什么东西必须持久化。Servlet、TaskService、PTY 这些实现细节放在 [full.md](full.md)。

```mermaid
flowchart LR
  subgraph users["使用者"]
    op["运维 / 管理员<br/>浏览器"]
    agent["AI Agent / 自动化<br/>Bearer 票据"]
    tui["hdsl-tui / 脚本<br/>终端客户端"]
    players["Minecraft 玩家<br/>游戏客户端"]
  end

  subgraph panel["HDSL-web 控制面（单 JVM 容器）"]
    edge["Jetty 边缘<br/>HTTP :3080（TLS 可选）"]
    api["REST API<br/>/api/*"]
    proxy["实例反代<br/>/i/{id}/*"]
    ws["事件 / 终端 WebSocket<br/>/ws/*"]
    control["控制面核心<br/>认证 · 实例 · 品牌 · MC · 任务"]
  end

  subgraph instances["被管理的运行实例"]
    dsh["dsh web<br/>loopback"]
    brands["Kimi · OpenCode · Claude Web<br/>Octop · Hermes · PI WEB<br/>loopback"]
    zcode["ZCode 构建 / 运行<br/>loopback"]
    mc["Minecraft 服务端<br/>Java + RCON<br/>游戏端口对玩家开放"]
  end

  subgraph external["外部依赖"]
    npm["npm / pnpm registry"]
    gh["GitHub 品牌目录"]
    upstream["Mojang · Paper · Purpur<br/>Fabric · Forge · NeoForge"]
    mirror["BMCLAPI · MSL"]
    modrinth["Modrinth"]
    jdk["JDK 下载源"]
  end

  data[("/data 卷<br/>配置 · 实例 · 版本 · 日志 · 证书")]

  op -->|"HTTP(S) / 会话 Cookie"| edge
  agent -->|"HTTP(S) / Agent 票据"| api
  tui -->|"API + WebSocket"| edge
  players -->|"游戏协议 :25565（默认）"| mc
  edge --> api
  edge --> proxy
  edge --> ws
  api --> control
  ws --> control
  proxy --> dsh
  proxy --> brands
  proxy --> zcode
  ws -->|"MC 控制台 / PTY"| mc
  control --> dsh
  control --> brands
  control --> zcode
  control --> mc
  control --> data
  control --> npm
  control --> gh
  control --> upstream
  control --> mirror
  control --> modrinth
  control --> jdk
```

## 边界

- **控制面**是面板本身：认证、实例生命周期、版本/插件/下载源、任务、审计、终端票据。它运行在一个 JVM 里，对外只暴露 `:3080`——默认是明文 **HTTP**；`https.enabled` 打开后才是 HTTPS（证书由 `CertificateManager` 管理，自签也行）。
- **数据面**分两类通道，不要混为一谈：
  - dsh / 品牌 / ZCode 只监听 loopback，浏览器经 `/i/{id}/` 反代或 `/ws/terminal/{ticket}` 终端访问它们；
  - Minecraft 服务端的**游戏端口（默认 25565）与 RCON（默认 25575）对玩家开放**：`server.properties` 不写 `server-ip`，即绑所有网卡，玩家的游戏客户端直连，流量不经过面板。面板对 MC 的控制（控制台、启停、文件、备份）走 `/ws/terminal` 与 `/api/mc/*`。
- **实例之间隔离**靠进程、端口、HOME 和工作区；不是靠浏览器多开标签页。
- **`/data` 是唯一需要持久化的目录**（`server.yaml`、`auth/`、`certs/`、`logs/`、`hdsl/`、`brands/`、`minecraft/` 等）。MC 存档默认在 `$HOME/mc`（`HOME` 环境变量优先），可用 `HDSL_MC_DIR` 或 `mc.dir` 指定，**环境变量优先于配置**；旧位置 `/data/minecraft/instances` 只读兼容。
- **外部源只影响下载路径**：品牌版本目录走 GitHub，npm 品牌走 registry，MC 核心/Java 走 Mojang/Paper/Purpur/Fabric/Forge/BMCLAPI/MSL。面板的“下载源”设置只管面板自己的下载，管不到 Fabric 启动器在首次启动时自己下的库。

## 不在这张图里

- 具体 Servlet 路由、Filter 顺序、TaskService 的任务状态机。
- 每个品牌自己的进程参数、wrapper、健康探测细节。
- 抓包级别的 HTTP/WebSocket 帧。

这些在 [full.md](full.md)、[backend.md](backend.md) 和 `flows/` 里。
