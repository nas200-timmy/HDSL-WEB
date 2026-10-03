# 更新日志

版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)；每条记录「改了什么」与「为什么」。

## v0.1.4 — 2026-10-03 · 容器发布版

把项目做成**标准容器发布**：镜像在 GitHub 官方 runner 上自动构建并推送到 GitHub Container Registry。
本版与 v0.1.3（暂时稳定版）**代码等价**，差异只在发布设施与文档。

### 新增

- [`.github/workflows/docker.yml`](.github/workflows/docker.yml)：打 `v*` 标签时自动构建并推送
  `ghcr.io/nas200-timmy/hdsl-web`（语义化标签 + `latest`）；手动 dispatch 产出 `edge` 用于验证/尝鲜。
  认证用仓库自带的 `GITHUB_TOKEN`（`packages: write`），任何机器都不需要登录或存放 PAT；
  构建带 GHA 层缓存，后续构建明显加速。版本号透传进 jar，镜像里 `-version` 与镜像标签一致。
- Dockerfile 增加 OCI 标签：`org.opencontainers.image.source`（让 ghcr 上的包自动关联本仓库，
  也是 `GITHUB_TOKEN` 有权推送同名包的前提）、`description`、`licenses`（GPL-3.0-only）。
- README 增加 ghcr 徽章与一行式拉取指引：`docker pull ghcr.io/nas200-timmy/hdsl-web:latest`。

### 验证

- `:edge` 构建通过（Actions run 成功），并以**匿名令牌**校验仓库清单：`GET /v2/nas200-timmy/hdsl-web/manifests/edge` → **HTTP 200**（公开可拉取）。

## v0.1.3 — 2026-10-03 · 暂时稳定版

修好 dsh 界面「历史加载失败、界面一直闪」，并整理一份会话交接文档。**本版为当前暂时稳定版**，日常使用选它。

### 修复

- **反代 WebSocket 中继的 64 KiB 上限**：面板把 `/i/*` 的 WebSocket 升级中继到实例端口，
  用的是 Jetty 默认上限（frame / text / binary 均 65536 字节）。dsh 的一份会话历史、一条
  工具结果、一个粘贴的附件都是一整条 WS 消息，轻易超过 64 KiB——一超，中继这一跳就以
  1009（message too large）失败、随即以 1011 掐断两端，dsh 客户端报
  `api gateway: Remote stream WebSocket closed`（gateway/internal）并反复重连，
  表现为「界面一直闪、历史永远加载不出来」。dsh 自己（node 的 ws）默认允许 100 MiB，
  瓶颈一直在面板这一跳。现在两侧——服务端 upgrade 容器（浏览器→中继）与转发用的
  WebSocketClient（中继→dsh）——的 frame / text / binary 都抬到 32 MiB（覆盖 dsh 实际
  产生的 payload，同时仍给"让面板无限分配内存"的对端一个上限）；中继非正常关闭时写一行
  日志（实例、状态码、原因），此类失败不再只能从浏览器控制台猜。

### 文档

- 新增 [`docs/session-handoff-2026-10-03.md`](docs/session-handoff-2026-10-03.md)：
  当天的会话交接文档——问题、排查结论、五次改动、关键环境事实与复现配方、刻意没做的事、
  当前 git 状态与待办。
- 文档与测试里的示例域名 / 内网地址 / 宿主路径统一为示例值（`dsh.example.com`、
  `192.0.2.x`、`/srv/...`）；`DshReadinessLineTest` 的示例私网 IP 换成 TEST-NET 段。

### 验证

- WS 上限 A/B（假 dsh 单发 1 MiB 帧）：修复前两个方向都 `CLOSE 1011 bytes=0`，
  修复后都 `RECV frame #1 1048576 bytes` 完整送达。
- 全量 **421 用例 / 0 失败 / 0 跳过**。

## v0.1.2 — 2026-10-03

上手配置时暴露的两个问题：账户加不进去，dsh 的工作区落在容器自己的目录里。

### 修复

- **账户添加必然失败**：`GET /api/vendors` 的 `kinds` 按设计报的是「供应商说的协议」
  （`openai-completions` 之类），面板却把它当账户类型随创建请求发了出去，被
  `/api/accounts` 以 `kind must be one of official, third-party, offline` 拒绝——
  从列表里选供应商添加 100% 失败。现在面板不再发 `kind`（类型由后端按供应商推断
  official / third-party），协议只作只读信息显示；后端拒收时点名收到的值。
- **工作区是启动器自己的实例目录**：此前新建实例的「工作区」（会话读写文件、跑命令
  的目录，也是 dsh 进程的工作目录）取的是 `<data>/hdsl/instances` —— 启动器放实例
  清单与安装的地方。现在启动时把 `<home>/workspace`（镜像里 `/home/hdsl/workspace`，
  可用 `HDSL_WORKSPACE` 覆盖）注册成选中目录，新实例默认落在它上面；该目录一直存在，
  挂不挂由部署决定：不挂是容器内目录，挂了就是宿主目录或命名卷。
- **dsh 界面「历史加载失败」并一直闪**：报错是 dsh 客户端的
  `api gateway: Remote stream WebSocket closed（gateway/internal）`。根因在面板的
  WebSocket 中继：它用的是 Jetty 的默认上限——**单帧 / 单消息 64 KiB**，而 dsh 一份
  会话历史、一条工具结果轻易超过；超限时中继把整条连接掐断（1009 → 继电器自己报
  1011），dsh 客户端于是重连、重试，界面就一直闪而历史永远加载不出来。现在两侧
  （服务端 upgrade 容器与转发用的 WebSocketClient）统一提到 32 MiB（dsh 自己的服务端
  上限是 100 MiB），并让中继在非正常关闭时写一行日志，下次同类问题在容器日志里可见。

### 部署

- compose 里两个挂载点写在明面上：`/data`（必须持久化：口令 / 配置 / 证书 / 实例 /
  pnpm store）与 `/home/hdsl/workspace`（可选，工作区；注释给了宿主目录与命名卷两种写法）。
- README「数据与挂载点」补上两个挂载点的分工与工作区说明，环境变量表新增
  `HDSL_WORKSPACE`。

### 工程

- 测试：**421 用例 / 0 失败 / 0 跳过**。新增工作区解析规则用例、「新建实例取选中
  目录当工作区」的接口用例，以及账户契约回归用例（供应商路径不带 `kind` → official；
  协议当 `kind` → 400 且点名）。
- 实机验证：绑定挂载宿主目录后，新建实例记录 `workspace=/home/hdsl/workspace`，
  启动的 dsh 进程 `cwd` 即该目录。
- 中继上限的 A/B 实测（假 dsh 只吐一个大帧）：修复前单个 1 MiB 帧直接断开
  （`1011 upstream connection failed`，客户端收到 0 字节）；修复后两个方向都完整
  收到 `1048576` 字节。

## v0.1.1 — 2026-10-03

修复实机使用中暴露的"实例经常无法启动"系列问题，并把安装编排补成启动器该有的样子。
排查结论：dsh 的版本管理内核（npm 取版本、pnpm 按实例安装、pnpm store 复用）没有坏，
坏的是 Web 层的编排逻辑与前后端状态契约。

### 新增

- **创建即安装 / 启动补装**：`POST /api/instances` 默认 `autoInstall:true` 并返回
  `installTaskId`；对未安装实例点「启动」会先安装再启动（202 `installing`），传
  `autoInstall:false` 可退回旧语义。
- **状态契约补全**：实例状态新增 `NOT_INSTALLED` 与 `INSTALLING`（判定顺序：活进程 >
  停止中 > 安装任务 > 未安装 > 失败 > 已停止；活进程优先，让"装完自动启动"的窗口期
  报 `STARTING` 而不是 `INSTALLING`），安装开始/结束时广播状态事件——此前前端 6 处
  按这两个状态写的引导 UI 全是死代码。
- 新增 [docs/launcher-orchestration.md](docs/launcher-orchestration.md)：诊断过程、
  状态机、契约变化与取舍。

### 修复

- **版本漂移**：详情页版本选择器不随实例重置（路由不重挂组件），会把 A 实例的选择
  提交给 B；改为随实例重置。后端 `/install` 成功后不再无条件把清单改写成"请求的
  版本"，而是**回读磁盘**上的 `package.json`，与请求不符时按真实版本记录并把任务
  判失败（宁如实，不谎报）。
- **安装失败不可见**：pnpm 输出此前只进内存任务表，失败原因随面板重启消失；现在写入
  容器日志与任务记录，并对 `ERR_PNPM_FETCH_404` / `ERR_PNPM_NO_MATCHING_VERSION`
  追加中文提示（点名上游已删除的包，说明重试无用）。
- **容器一直 unhealthy**：HTTPS 下探针访问 `https://127.0.0.1` 触发 Jetty 的 SNI
  主机校验（400 Invalid SNI），服务本身是好的；两个 TLS 连接器关闭该校验（单证书
  面板，校验只会误伤本机探针）。现在 `curl -k https://127.0.0.1:3080/api/health`
  直接 200。
- **npmmirror 尾斜杠导致 corepack 404**：`NPM_REGISTRY` 带尾斜杠时 corepack 拼出
  `mirror//pnpm/latest` → 404，而 compose 注释里推荐的就是带斜杠的写法；入口脚本
  归一化后再写 pnpm 配置与 `COREPACK_NPM_REGISTRY`。
- **中文日志在 `docker logs` 显示 `?????`**：镜像未设 `LANG`，JVM 的
  `stdout.encoding` 退化成 ASCII；镜像加 `LANG=C.UTF-8`。
- 实例运行中禁止切换版本；安装中禁止改名。

### 工程

- 任务 REST 契约补 `instanceId` 字段（WS 事件早就有 `instance` 字段，只有 REST 与
  前端类型缺它，导致前端任务匹配退化成"最近活跃任务"）。
- **构建产物不再入库**：`src/main/resources/web/` 是 `web/dist` 经 `syncWebDist`
  生成的副本，仅因历史原因被跟踪，且会随源码更新而陈旧；改为生成物并加入
  `.gitignore`（Docker 构建、CI、本地构建都会重建它）。
- 测试：**415 用例 / 0 失败 / 0 跳过**（挂 node + pnpm 实跑，含原本被跳过的 21 个）；
  fake-pnpm 支持 `9.9.9-slow`、`9.9.9-mismatch` 两个特殊版本；新增
  `InstallFailureHintTest` 及"创建即安装 / 启动补装 / INSTALLING 可见 / 回读不符
  判失败"等用例。
- 实机端到端验证：创建 → `NOT_INSTALLED`，启动 → 202 `installing` → `INSTALLING` →
  `STARTING` → `RUNNING`；复现上游删包场景（`0.0.1-rc.1`）确认中文提示落到任务错误
  与容器日志。

## v0.1.0 — 2026-10-03

首个版本：把 [HDSL](https://github.com/MCXCC303/HDSL) 桌面版搬进浏览器的单容器实现。

- 登录门 + 首次启动初始化引导（自建用户名/密码），面板内支持修改密码
- 界面按桌面版像素级复刻（Material You 主题、同款图标与动画、亮暗跟随系统）
- 创建实例 → 一键启动 → 面板内进度条与实时日志 → 自动开新标签页
- 同端口、同证书反代 dsh 网页（剥前缀 / Host 透传 / Cookie Path 改写 / WebSocket 中继）
- 完整管理功能：版本 / 实例 / 插件 / 账户 / 整合包 / 会话 / 技能 / 体检 / ACP 控制台
- 声明式 HTTPS：`server.yaml` / 环境变量，首启自签，面板上传证书热切换
- 交付：单可执行 fat jar + Debian 容器镜像（内置 OpenJDK 21、Node 22、pnpm）
