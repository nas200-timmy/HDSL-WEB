# 系统上下文（战略）

这张图只回答“系统边界在哪里”：谁是使用者、面板控制什么、实例在哪里跑、外部依赖有哪些、什么东西必须持久化。Servlet、TaskService、PTY 这些实现细节放在 [full.md](full.md)。

```mermaid
flowchart LR
  subgraph users["使用者"]
    op["运维 / 管理员<br/>浏览器"]
    agent["AI Agent / 自动化<br/>Bearer 票据"]
    tui["hdsl-tui / 脚本<br/>终端客户端"]
  end

  subgraph panel["HDSL-web 控制面（单 JVM 容器）"]
    edge["Jetty 边缘<br/>HTTPS :3080"]
    api["REST API<br/>/api/*"]
    proxy["实例反代<br/>/i/{id}/*"]
    ws["事件 / 终端 WebSocket<br/>/ws/*"]
    control["控制面核心<br/>认证 · 实例 · 品牌 · MC · 任务"]
  end

  subgraph instances["被管理的运行实例（loopback）"]
    dsh["dsh web"]
    brands["Kimi · OpenCode · Claude Web<br/>Octop · Hermes · PI WEB · ZCode"]
    mc["Minecraft 服务端<br/>Java + RCON"]
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

  op -->|"HTTPS / 会话 Cookie"| edge
  agent -->|"HTTPS / Agent 票据"| api
  tui -->|"API + WebSocket"| edge
  edge --> api
  edge --> proxy
  edge --> ws
  api --> control
  ws --> control
  proxy --> dsh
  proxy --> brands
  ws -->|"MC 控制台 / PTY"| mc
  control --> dsh
  control --> brands
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

- **控制面**是面板本身：认证、实例生命周期、版本/插件/下载源、任务、审计、终端票据。它运行在一个 JVM 里，对外只暴露 `:3080`。
- **数据面**是被管理的实例：dsh、品牌 Web 进程、Minecraft 服务端。它们只监听 loopback，浏览器不直接访问；dsh/品牌/ZCode 通过 `/i/{id}/` 反代，MC 通过控制台 WebSocket。
- **实例之间隔离**靠进程、端口、HOME 和工作区；不是靠浏览器多开标签页。
- **`/data` 是唯一需要持久化的目录**（`server.yaml`、`auth/`、`certs/`、`logs/`、`hdsl/`、`brands/`、`minecraft/` 等）。MC 存档默认在 `$HOME/mc`，可用 `HDSL_MC_DIR` / `mc.dir` 指定；旧位置 `/data/minecraft/instances` 只读兼容。
- **外部源只影响下载路径**：品牌版本目录走 GitHub，npm 品牌走 registry，MC 核心/Java 走 Mojang/Paper/Purpur/Fabric/Forge/BMCLAPI/MSL。面板的“下载源”设置只管面板自己的下载，管不到 Fabric 启动器在首次启动时自己下的库。

## 不在这张图里

- 具体 Servlet 路由、Filter 顺序、TaskService 的任务状态机。
- 每个品牌自己的进程参数、wrapper、健康探测细节。
- 抓包级别的 HTTP/WebSocket 帧。

这些在 [full.md](full.md)、[backend.md](backend.md) 和 `flows/` 里。
