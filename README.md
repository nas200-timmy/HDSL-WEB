# HDSL-web

[![build](https://github.com/nas200-timmy/HDSL-WEB/actions/workflows/build.yml/badge.svg)](https://github.com/nas200-timmy/HDSL-WEB/actions/workflows/build.yml)
[![docker](https://github.com/nas200-timmy/HDSL-WEB/actions/workflows/docker.yml/badge.svg)](https://github.com/nas200-timmy/HDSL-WEB/actions/workflows/docker.yml)
[![release](https://img.shields.io/github/v/release/nas200-timmy/HDSL-WEB?label=release)](https://github.com/nas200-timmy/HDSL-WEB/releases)
[![ghcr](https://img.shields.io/badge/ghcr.io-hdsl--web-blue?logo=docker)](https://github.com/nas200-timmy/HDSL-WEB/pkgs/container/hdsl-web)
[![license](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)
[![platform](https://img.shields.io/badge/platform-Docker%20%7C%20Linux%20%7C%20macOS-lightgrey)]()

把 [HDSL](https://github.com/MCXCC303/HDSL)（Hello DeepSeek! Launcher，桌面版）搬进浏览器的**单容器**版本：
打开网页 → 登录 → 傻瓜式创建 dsh 实例 → 点「启动」→ 在**同域名、同端口、同一张证书**下直接使用 dsh 自己的网页界面。
界面按桌面版**像素级复刻**（HMCL 视觉语言、Material You 主题、同款图标与动画），管理功能全部保留：
实例/版本/插件/账户/整合包/会话/技能/体检/ACP 控制台。

![主界面](https://gitee.com/nas200-timmy/hdsl-web/raw/main/docs/screenshots/02-main.png)

- 交付形态：**一个可执行 fat jar**（`java -jar hdsl-web.jar`）+ 一个 Debian 容器镜像。
- jar 之外只需两样东西：**JDK 21+** 和 **Node ^22.19.0 || >=24.0.0 + pnpm**（dsh 由 pnpm 安装、由 node 运行）。
- 手机可用：窄屏（≤760px）或矮屏（≤520px）自动切换移动版式——主导航收成抽屉、内页子侧栏变顶部横向标签条、
  启动面板变成贴底整条操作栏，可点区域按触摸标准放大到 44px，支持「加到主屏幕」。
  判定条件与规则见 [docs/ui-spec.md §14](docs/ui-spec.md)；桌面版式零变化。
- 账户：为某个模型供应商填一次 API Key。供应商与模型名来自 **[models.dev](https://models.dev) 模型目录**（200+ 家，
  选中供应商即自动带出端点、协议与密钥环境变量提示），默认模型是可搜索下拉、也一直能手输；目录拉不到时退回纯手输。
  规则见 [docs/ui-spec.md §4](docs/ui-spec.md)。
- 启动器行为：**创建即安装**（`POST /api/instances` 默认 `autoInstall:true`），**未安装的实例点「启动」会先装再启**；
  进度与失败原因都显示在面板上，失败还会写进容器日志。细节见 [docs/launcher-orchestration.md](docs/launcher-orchestration.md)。
- 下载源：设置页可选预设镜像（npmmirror / 中科大 / 腾讯云 / 华为云 / 官方）或手填 URL，服务端校验后**真正生效**；
  面板会把它写进 pnpm 的全局配置并显式传给 pnpm/npm 子进程（pnpm 11 不读环境变量），
  环境变量 `NPM_CONFIG_REGISTRY` 退化成回退值。细节见 [docs/deployment.md](docs/deployment.md)。

<details>
<summary>更多截图（初始化引导 / 实例列表 / 下载 / 设置）</summary>

| 初始化引导（首启建号） | 实例列表 |
|---|---|
| ![初始化](https://gitee.com/nas200-timmy/hdsl-web/raw/main/docs/screenshots/01-setup.png) | ![实例列表](https://gitee.com/nas200-timmy/hdsl-web/raw/main/docs/screenshots/03-instances.png) |

| 下载（真实 npm 版本列表） | 设置 |
|---|---|
| ![下载](https://gitee.com/nas200-timmy/hdsl-web/raw/main/docs/screenshots/04-download.png) | ![设置](https://gitee.com/nas200-timmy/hdsl-web/raw/main/docs/screenshots/05-settings.png) |

</details>

## 目录

- [架构](#架构)
- [快速开始](#快速开始)（Release 下载 / Docker Compose / 裸 `java -jar`）
- [首次登录与初始化引导](#首次登录与初始化引导)
- [数据与挂载点](#数据与挂载点)
- [声明式 HTTPS](#声明式-https)
- [环境变量](#环境变量)
- [安全说明](#安全说明)
- [构建与开发](#构建与开发)
- [常见问题](#常见问题)
- [许可](#许可)

## 架构

```
浏览器标签页 A：HDSL 面板            浏览器标签页 B：dsh web
   https://host:3080（同一张证书、同一端口）
        │                                   │
        ▼                                   ▼
┌─ 单个容器（HDSL-web）────────────────────────────────────────┐
│  Jetty 边缘（一个 JVM 进程，监听 0.0.0.0:3080）               │
│   ├─ SPA 静态资源（React 构建产物，已打进 jar）               │
│   ├─ /api/*       REST（认证/实例/版本/插件/账户/整合包/…）   │
│   ├─ /ws          WebSocket（日志流、任务进度、状态事件）      │
│   └─ /i/{id}/*    反向代理 → 127.0.0.1:3081-4081             │
│        剥前缀 / Host 原样透传 / Cookie Path 改写 / WS 中继     │
│  Java 领域层（自 HDSL 移植）：实例、版本、插件、账户、整合包   │
│  dsh 实例进程池：node bin.js --profile web --port N …        │
│  容器内置 OpenJDK 21 + Node ^22 + pnpm(通过 corepack)         │
└───────────────────────────────────────────────────────────────┘
        │  卷：hdsl-data → /data（唯一需要持久化的目录）
        ▼
```

两个标签页是彼此独立的界面：面板只负责「点火」和「熄火」，真正的使用在 dsh 自己的页面里。
再往前放一层 nginx/caddy 也支持，但必须**原样透传 `Host` 头**（dsh 校验 `Origin == Host`，改写会 403）——
详见 [docs/deployment.md](docs/deployment.md)。

## 快速开始

### 方式零：下载 Release 产物

从 [Releases](https://github.com/nas200-timmy/HDSL-WEB/releases) 下载 `hdsl-web-<版本>.jar` 与 `SHA256SUMS.txt`：

```bash
sha256sum -c SHA256SUMS.txt                       # 校验完整性
HDSL_DATA="$PWD/data" HDSL_PORT=3080 java -jar hdsl-web-0.1.0.jar
```

jar 的运行前提见下方「方式二」（JDK 21 与 Node/pnpm）。Docker 部署推荐用方式一。

### 方式一：Docker / Compose（推荐）

不想自己构建镜像时，用已经发布到 **GitHub Container Registry** 的镜像（打 `v*` 标签时由 GitHub Actions 在官方 runner 上自动构建，见 [`.github/workflows/docker.yml`](.github/workflows/docker.yml)）：

```bash
docker pull ghcr.io/nas200-timmy/hdsl-web:latest

docker run -d --name hdsl-web --restart unless-stopped \
  -p 3080:3080 \
  -v hdsl-data:/data \
  -e HDSL_ADMIN_PASSWORD='换成一个强口令' \
  ghcr.io/nas200-timmy/hdsl-web:latest
```

版本化标签与 Release 一一对应（`ghcr.io/nas200-timmy/hdsl-web:0.1.3` 等）。要在本地从源码构建：

```bash
cd HDSL-web

# 可选：首次启动的管理员口令（不设则首次打开网页时创建管理员账号）
export HDSL_ADMIN_PASSWORD='换成一个强口令'
docker compose up -d --build
```

打开 `http://<主机>:3080`：若设了 `HDSL_ADMIN_PASSWORD`，用 `admin` + 该口令登录；
否则会自动进入**初始化引导页**——设置用户名和密码后即创建账号并直接进入面板。
默认是纯 HTTP。要 HTTPS 见下面的「声明式 HTTPS」。

### 方式二：裸 `java -jar`（不用 Docker）

前提：**JDK 21+**、**Node ^22.19.0 || >=24.0.0**、**pnpm**（`corepack enable pnpm`，或 `npm i -g pnpm`）。
Node 版本范围是 dsh 自己的 `engines` 要求；pnpm 既是 dsh 的安装器，也是插件管理（`dsh plugin`）的转发目标。

```bash
./gradlew shadowJar                     # 产出 build/libs/hdsl-web-<版本>.jar

HDSL_DATA="$PWD/data" \
HDSL_PORT=3080 \
HDSL_ADMIN_PASSWORD='换成一个强口令' \
  java -jar build/libs/hdsl-web-0.1.0-dev.jar
```

不用环境变量也行：`hdsl-web` 会读 `$HDSL_DATA/server.yaml`（默认数据目录 `./data`），
没有配置文件就用内置默认值。`java -jar … -version` 只打印版本号后退出。

### 首次登录与初始化引导

- 设了 `HDSL_ADMIN_PASSWORD` → 首次启动自动创建 `admin` 账号，直接登录即可；重启**不会**重置已存在的口令。
- 没设 → 服务端**不生成任何密码**，保持"待初始化"状态；打开网页会自动进入引导页，
  填写用户名（1–32 位、不含空白）和密码（至少 6 位）后点击「创建并进入」，账号创建成功即自动登录。
- 引导页只在**没有任何用户**时可用（`GET /api/auth/status` 的 `setupRequired` 为 true）；
  之后再次访问会被拒绝（409），正常走登录页。
- 已初始化后随时可以在「设置 → 通用 → 修改密码」更换密码：修改成功后**其它浏览器中的会话会被退出**（当前会话保留）。

## 数据与挂载点

容器里有一个**必须挂**的挂载点，另有一个可选的工作区：

| 挂载点 | 必须？ | 内容 |
|---|---|---|
| `/data` | **必须持久化** | 面板自己的全部状态：配置、口令、证书、实例、`DSH_HOME`、pnpm store —— 下表逐项列出 |
| `/home/hdsl/workspace` | 可选 | dsh 的**工作区**：会话读写文件的地方，也是 dsh 进程的工作目录。这个目录一直存在（镜像里就建好、启动时也保证在）：**不挂**就是容器内目录（重建容器会丢），**挂了**就随你 —— 宿主目录 = dsh 直接编辑你的真实文件，命名卷 = 留在容器外持久保存 |

`HDSL_DATA`（容器内固定为 `/data`）是**必须**持久化的目录。dsh 实例的 `DSH_HOME`、pnpm store
都设计在它下面，所以「挂一个卷、备份一个目录」就覆盖全部状态。

| 路径 | 内容 | 需要备份 |
|---|---|---|
| `/data/server.yaml` | 声明式配置（监听、HTTPS、认证）；可手写，也可由面板证书上传回写 | 是 |
| `/data/certs/` | TLS 身份。上传的证书是 `uploaded.pem`/`uploaded.key` 或 `uploaded.p12`；首启自签是 `self-signed.pem`/`self-signed-key.pem`（私钥 0600） | 是 |
| `/data/auth/users.json` | 管理员账号的口令哈希（PBKDF2WithHmacSHA256，per-user 盐，文件 0600） | 是 |
| `/data/logs/` | HDSL-web 服务端日志 | 否 |
| `/data/tmp/` | multipart 上传的暂存目录（插件 `.tgz`、证书、整合包 `.dspack`） | 否 |
| `/data/exports/` | 从面板导出的整合包产物，`GET /api/exports` 列出的就是这个目录 | 否 |
| `/data/hdsl/` | `hdsl.home`：启动器自己的全部状态（可用 `-Dhdsl.home=` 覆盖） | 是 |
| `/data/hdsl/launcher-settings.json` | 启动器设置（代理、下载并发、下载源、隔离模式、游戏目录/工作区选择等） | 是 |
| `/data/hdsl/instances/<id>/` | 每个实例：`instance.json`、`dsh/`（该实例自己的 dsh 安装）、`home/`（隔离模式的 `DSH_HOME`） | 是 |
| `/data/hdsl/homes/<版本>/` | 共享隔离模式下多个实例共用的 home | 是 |
| `/data/hdsl/runtimes/<版本>/` | 自下载的 Node 运行时。镜像已内置 Node，安装新运行时的路径仍可用，通常为空 | 否 |
| `/data/hdsl/catalog/` | 远程目录的缓存：快速安装预设，以及 models.dev 模型目录（12 小时 TTL） | 否 |
| `/data/hdsl/logs/` | 从日志窗口导出的日志文件 | 否 |
| `/data/pnpm-store/` | pnpm 共享内容寻址仓库（pnpm 全局配置里的 `storeDir`）。多实例安装同一个包时会硬链接复用而不是重复下载 | 否（可重建，重装会重新拉） |
| `/data/corepack/` | corepack 缓存（`COREPACK_HOME`）。dsh 固定 `packageManager: pnpm@…` 时需要的那个 pnpm 版本会缓存在这里 | 否 |

> **工作区**是 dsh 会话的「项目根」：会话里读写文件、跑命令都在它下面，也是 dsh 进程的工作目录。
> 容器里它就是 **`/home/hdsl/workspace`**（这个目录一直存在，挂不挂由你决定）；**不是**面板自己放实例的那个目录。
> 要持久化或要让 dsh 直接编辑宿主机的目录，取消注释 compose 里那两行之一：
> `- /srv/dsh-workspace:/home/hdsl/workspace`（宿主目录，要让 uid/gid 1000 能读写）或
> `- hdsl-workspace:/home/hdsl/workspace`（命名卷）；也可以用 `HDSL_WORKSPACE` 指到别的已挂路径。
> 已有实例保留它创建时记录的工作区，新建的才用新的。

> 备份/迁移的做法很简单：**停容器 → 整个拷走 `/data`**；工作区挂了卷的话按需一起拷，是你自己的宿主目录就更不用管。恢复同理。细节见 [docs/deployment.md](docs/deployment.md)。

## 声明式 HTTPS

配置优先级：**环境变量 > `server.yaml` > 内置默认值**。`server.yaml` 的每个键都可选，
写错或写了非法值会**启动失败并指出是哪一行/哪个字段**（服务器不会带着半份配置起来）。

```yaml
# <HDSL_DATA>/server.yaml
bind_host: 0.0.0.0        # 默认 0.0.0.0
port: 3080                # 默认 3080；HTTPS 启用时同一端口直接说 TLS
public_base_url: https://dsh.example.com   # 反代后的公网基址；用于自签证书的 SAN 与实例启动的 --trusted-host

https:
  enabled: true           # 默认 false（纯 HTTP，仅建议回环）
  # 方式 A：PEM 证书链 + 私钥（相对路径按 HDSL_DATA 解析，也可写绝对路径）
  cert_file: certs/fullchain.pem
  key_file:  certs/privkey.pem
  # 方式 B：PKCS#12；同时配置时 PKCS#12 优先
  pkcs12_file: certs/site.p12
  pkcs12_password: "…"
  redirect_http: false    # true = 再开一个纯 HTTP 端口，把所有请求 308 到同主机的 HTTPS
  redirect_port: 80       # 上面那个 HTTP 端口，默认 80

auth:
  disabled: false         # true 只有 bind_host 是回环地址时才允许（开发用）
  session_ttl_hours: 168  # 会话有效期，滑动续期，默认 168 小时（一周）
```

### 两步启用 HTTPS

1. **首启自签**：把 `https.enabled` 设为 `true`（或 `HDSL_HTTPS=true`）而不配证书，第一次启动会自动生成
   自签证书放进 `/data/certs/`。可以直接用，浏览器会告警（点继续即可）。
2. **面板上传证书，热切换**：登录后进「设置 → TLS 证书」，上传 PEM（证书链 + 私钥）或 `.p12`（+ 口令）。
   服务器把选择写回 `server.yaml`，然后**在不重启、不换端口的前提下**换掉身份：
   - 已经是 HTTPS → `SslContextFactory.reload()`，新证书立即生效；
   - 还是 HTTP → 运行时把明文连接器换成 TLS 连接器（同主机同端口）；
   - 万一运行时切换失败 → 回滚成明文并在响应里带 `restartRequired: true`，重启容器即应用
     （选择已经写进 `server.yaml`，重启就是生效方式）。

自签证书的 SAN 会带上 `public_base_url` 的 host（配了的话）和 `localhost`/`127.0.0.1` 等常用名，
所以配好 `public_base_url` 后反代域名能对上名字。

## 环境变量

| 变量 | 默认 | 作用 |
|---|---|---|
| `HDSL_DATA` | `./data`（镜像内 `/data`） | 数据目录；`server.yaml`、证书、实例、pnpm store 全在它下面 |
| `HDSL_PORT` | `3080` | 主监听端口。HTTPS 启用时是同一个端口；`0` 表示让系统随机选（测试用） |
| `HDSL_BIND_HOST` | `0.0.0.0` | 主监听地址。容器内保持 `0.0.0.0`；只想本机访问可设 `127.0.0.1` |
| `HDSL_HTTPS` | `false` | 只接受 `true`/`false`；等价于 `https.enabled` |
| `HDSL_ADMIN_PASSWORD` | 空 | **仅首次启动**、且数据目录里还没有任何用户时，用它创建 `admin` 账号（无人值守部署）；已存在账号时忽略。不设则首次打开网页走初始化引导创建账号 |
| `NPM_CONFIG_REGISTRY` | `https://registry.npmjs.org/` | 下载源的**回退值**，优先级是「**面板设置 → 这个环境变量 → 官方默认**」。npm 系（`npm view`、`npm install -g`、插件元数据）直接读它；pnpm 不读环境变量，由入口脚本与面板转写进 pnpm 自己的配置文件（见下）。生效时机：面板里改完保存**立即生效**（此后新建/重装的实例、面板内构建），改环境变量要重启容器 |
| `JAVA_OPTS` | 空 | 追加到 `java` 命令行的 JVM 参数，例如 `-Xmx2g` |
| `PNPM_STORE_DIR` | `/data/pnpm-store` | pnpm 共享仓库位置（本镜像的约定变量，入口脚本会写进 pnpm 全局配置）；一般不用改 |
| `HDSL_WORKSPACE` | 空 | dsh 的工作区路径。不设时用 `$HOME/workspace`（镜像里 `/home/hdsl/workspace`）；设了就用它（不存在会创建）。新建实例默认用它，已有实例保留创建时记录的那个 |
| `HDSL_ZCODE_PACKAGE` | 空 | **实验性 ZCode 品类**的发行包目录（需含 `bin/zcode.mjs`）；不设时找 `<数据目录>/zcode/current/`。见 [docs/zcode-experimental.md](docs/zcode-experimental.md) |
| `HDSL_ZCODE_NODE` | 空 | 跑 ZCode 用的 node 可执行文件；不设时用 PATH 上的 `node` |

`HDSL_BIND_HOST`/`HDSL_PORT`/`HDSL_HTTPS` 之外的一切（证书路径、`auth.disabled`、`redirect_http` 等）
都从 `server.yaml` 读。dsh 实例自己的端口从池子 **3081–4081** 里分配、终身绑定，只监听回环，**不对外发布**。

> **ZCode（实验性）不在这套承诺内**：实例只绑回环，经面板的 `/i/<id>/` 挂载点访问（同端口、
> 同证书、同样要登录）；发行包由面板自己从上游源码构建，能不能用取决于上游。定位、构建方式与
> 限制见 [docs/zcode-experimental.md](docs/zcode-experimental.md)。

> **注意优先级**：环境变量盖过 `server.yaml`。镜像里已经设了 `HDSL_PORT=3080`，所以想换端口要改 **`HDSL_PORT`
> 环境变量**（改 `server.yaml` 的 `port` 会被忽略）；同理，想用 `server.yaml` 里的 `port` 就得把 `HDSL_PORT` 显式清空。
> `HDSL_HTTPS` 镜像没有设，`server.yaml` 的 `https.enabled` 正常生效。

> **pnpm 的配置不是环境变量**：pnpm 11 的 registry 与 `storeDir` 都读它自己的全局配置文件
> `<XDG_CONFIG_HOME>/pnpm/config.yaml`——容器内这份文件的路径是 `/home/hdsl/.config/pnpm/config.yaml`（镜像里没有任何 `.npmrc`）；
> `npm_config_*` / `NPM_CONFIG_REGISTRY` 环境变量对 pnpm **完全无效**（`NPM_CONFIG_REGISTRY=… pnpm config get registry` 会被忽略）。
> 所以镜像的入口脚本每次启动会**生成**这份 `config.yaml`：`storeDir` 取 `PNPM_STORE_DIR`，`registry` 取 `NPM_CONFIG_REGISTRY`；
> 同时把后者作为 `COREPACK_NPM_REGISTRY` 导出（corepack 下载 pnpm 本体时用它自己的这个变量）。
> 在面板上改「设置 → 下载源」会**重写这份 `config.yaml`**：保留其它设置行（`storeDir` 不会丢）、只重写 `registry` 一行
> （值经 YAML 引号、临时文件原子替换、0600）。此外面板还给子进程注入 `npm_config_registry`/`NPM_CONFIG_REGISTRY`
> （npm 系读它），并在自己起的 pnpm 上显式加 `--registry=`（dsh 安装与面板内构建，日志里看得见实际用的源）。
> 领域层以子进程方式启动 pnpm，所以上述设置对实例安装/插件安装都生效。
> 如果你绕开入口脚本直接 `--entrypoint java`，镜像里已经预置了只含 `storeDir` 的同名文件作为兜底。

## 安全说明

- **登录门**：`POST /api/auth/login`、`GET /api/auth/status`、`POST /api/auth/setup`（仅无用户时可用）
  与 `GET /api/health` 是公开的（外加不含数据的 SPA 外壳，因为登录/引导页必须先能打开）。
  `/api/*`、`/ws`、`/i/*` 一律要求会话。`POST /api/auth/password`（改密）与 `POST /api/auth/username`（改名）
  需会话，成功后同用户的其它会话立即失效（改名的那个会话保留并切换到新用户名）。
- **口令存储**：PBKDF2WithHmacSHA256（per-user 盐、21 万次迭代，参数记录在 users.json 内），文件原子写入且 0600。
- **dsh 只绑回环**：`dsh web` 被设计成只监听 `127.0.0.1`（`--host 0.0.0.0` 会被 CLI 拒绝），
  外部能到达的只有 HDSL-web 的 3080。TLS 在边缘终结，因此天然满足这个约束。
- **停止 = 真停止**：面板点「停止」会杀整棵进程树，端口立刻关闭、`/i/<id>/` 立刻 404。
  dsh 的访问 token 每个进程随机、只存内存、重启即换——不运行就没有可被攻击的东西。
- **Cookie 隔离**：面板会话 cookie 是 `HttpOnly` + `SameSite=Strict`（HTTPS 下再加 `Secure`），`Path=/`；
  反代会把 dsh 的 `Set-Cookie: … Path=/` 改写成 `Path=/i/<id>/`，所以多实例的同名 cookie 不会互相覆盖。
- **反代契约**：剥 `/i/<id>/` 前缀、**`Host` 原样透传**（改写会触发 dsh 的 403）、`/i/<id>` 301 到目录形式、
  WebSocket（`/api/remote.mux`）原样转发。
- `auth.disabled: true` 只在绑定回环地址时被允许——它能让你免登录，但不可能把它误配到公网。
- 首启自签证书会让浏览器告警，这是预期行为；正式使用请上传受信任的证书。

## 构建与开发

```bash
# 后端：编译 + 全部测试（JUnit 5）
./gradlew build

# 只跑测试
./gradlew test

# 单个 jar（SPA 会被打进 jar；本地有 npm 时 build.gradle.kts 会先跑 web 的构建）
./gradlew shadowJar
# → build/libs/hdsl-web-<版本>.jar（版本由 -PreleaseVersion=1.2.3 决定，缺省 0.1.0-dev）

# 前端开发服务器（默认 5173，把 /api /ws /i 代理到 127.0.0.1:3080）
cd web && npm install && npm run dev
cd web && npm run build      # 产出 web/dist，packaging 时同步进 jar

# 容器
docker build -t hdsl-web .
docker compose up -d

# 端到端冒烟：真实 npm 安装 dsh → 启动 → 反代访问 → 停止后确认 /i/ 404
bash scripts/e2e-real.sh
```

`scripts/e2e-real.sh` 会真的访问 npm registry、装一个 dsh 版本并启动它，耗时几分钟，需要网络。
开发时的分工是：先起后端（3080），再 `npm run dev`，浏览器开 5173；后端没起时页面会报连接/登录失败，属预期。

> 本机第一次 `./gradlew build` / `shadowJar` 之前先 `cd web && npm install`：`build.gradle.kts` 只要发现
> PATH 上有 npm 就会执行 `npm run build`，没有 `node_modules` 会失败。**Docker 构建不需要**——镜像的
> build 阶段故意不带 npm，SPA 由单独的 web 阶段产出后拷进去。

## 常见问题

**浏览器提示证书不安全。** 首启用的是自签证书，属正常。点「继续访问」，或到「设置 → TLS 证书」上传正式证书（立即生效，无需重启）。

**npm / pnpm 下载很慢。** 首选「设置 → 下载源」：选一个预设（`npmmirror` / 中科大 / 腾讯云 / 华为云 / 官方）或填自己的镜像 URL，
保存即生效、不用重启容器。没有面板可点的时候（裸跑、无人值守部署）再退回环境变量：运行时设 `NPM_CONFIG_REGISTRY`；
构建镜像时用 `--build-arg NPM_REGISTRY=…`（只影响构建期，见 [docs/deployment.md](docs/deployment.md)）。注意**面板设置压过环境变量**，
环境变量只是回退值。

**端口被占用。** 面板端口冲突改 `ports` 映射和 `HDSL_PORT`；dsh 实例用的 3081–4081 只在容器内回环，不占宿主机端口。

**实例启动后立刻消失，或者一直停在「启动中」、打开 `/i/<id>/` 返回 502。** 先怀疑内核的文件监视器配额
`fs.inotify.max_user_watches`：dsh 启动要监视自己的 profile 目录，内核一拒绝，node 要么当场退出、
要么永远不打印就绪行——两种都不像「配额不足」。这个配额**按 uid 计、容器之间不隔离**，宿主上同一个
uid 的其它程序（编辑器服务、同步客户端）会把它吃光。面板「设置 → 环境体检」的 **File watchers** 一行
直接给答案（不够会标 `[SHORTAGE]`），调大办法见
[部署细节 · 宿主 inotify 配额](docs/deployment.md#8-宿主-inotify-配额多实例)。

**pnpm store 想放到别处。** 改 `PNPM_STORE_DIR` 环境变量（入口脚本会写进 pnpm 的 `config.yaml`）。
注意 store 与实例目录要在**同一文件系统**上，否则 pnpm 只能复制而不能硬链接；默认两者都在 `/data` 下，正是为此。

**裸跑时数据放在哪。** 默认 `./data`；用 `HDSL_DATA` 指定。`hdsl.home` 自动取 `<HDSL_DATA>/hdsl`，
也可以用 `-Dhdsl.home=/path` 覆盖（更高优先级）。

**用 bind mount 而不是命名卷时权限报错。** 容器里的运行用户是 `hdsl`(uid/gid 1000)，绑定的宿主目录必须让它可写：
`sudo chown -R 1000:1000 ./data`。命名卷没有这个问题（首次创建时会继承镜像里 `/data` 的属主）。

**日志在哪。** 容器里看 `docker compose logs -f hdsl-web`；服务端日志同时落在 `/data/logs/`，
实例的运行日志从面板的「日志」标签看，导出目录是 `/data/hdsl/logs/`。

**装某个插件时报 node-gyp / 找不到编译器。** 镜像里已经带了 `python3` / `make` / `g++`（面板内构建 ZCode 时要用它编译
`node-pty`、`cpu-features`，顺带也让这类插件能直接装）；dsh 本体的原生模块（Landlock 启动器、flock）以预编译包分发、
本来就不需要编译器，`git`、`xz-utils`、`zstd` 也都装了。仍然报缺编译器时先看它要的是不是别的东西（`cmake`、`pkg-config` 之类），
再基于本镜像加一层补装。

**改了 `server.yaml` 要重启吗。** 手改文件需要重启。面板上传证书不需要（热重载/运行时切换）。

## 许可

HDSL-web 以 **GNU GPL v3（或更新版本）** 发布，见 [LICENSE](LICENSE)。

本项目是 [HDSL](https://github.com/MCXCC303/HDSL) 的 Web 服务端衍生版，而 HDSL 又是
[Hello Minecraft! Launcher (HMCL)](https://github.com/HMCL-dev/HMCL) 的修改版：
领域层（`dsh/`、`setting/`、`util/`）自 HDSL 拷贝并去除 JavaFX 依赖，保留原包名、版权头与 GPL 授权。
修改说明、商标与第三方组件（Jetty、Bouncy Castle、snakeyaml-engine、Gson、SLF4J、kala-compress、
Node.js、pnpm 等）的授权声明见 [NOTICE](NOTICE)。

「DeepSeek」「DeepSeek Harness」「dsh」归其各自权利人所有；HDSL-web 是独立的非官方项目，与 DeepSeek Harness 项目无隶属或背书关系。
