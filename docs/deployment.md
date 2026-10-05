# HDSL-web 部署细节

面向「已经跑起来，准备接到公网或长期运行」的场景。快速开始与配置字段见 [../README.md](../README.md)。

- [1. 再套一层反向代理（nginx / Caddy）](#1-再套一层反向代理nginx--caddy)
- [2. TLS 证书轮换](#2-tls-证书轮换)
- [3. 备份与恢复](#3-备份与恢复)
- [4. 多架构构建（buildx）](#4-多架构构建buildx)
- [5. 升级与回滚](#5-升级与回滚)
- [6. 运行期下载源（面板设置）](#6-运行期下载源面板设置)
- [7. 自签证书与容器健康检查（`Invalid SNI`）](#7-自签证书与容器健康检查invalid-sni)
- [8. 宿主 inotify 配额（多实例）](#8-宿主-inotify-配额多实例)

---

## 1. 再套一层反向代理（nginx / Caddy）

HDSL-web 自带边缘代理、自带证书，可以不用外层代理直接对公网。
但如果你已经有一台 nginx/Caddy（共享 443、统一证书、统一日志），把它放在前面也完全支持。

### 唯一的硬约束：`Host` 必须原样透传

dsh 的浏览器信任栅栏会比较 `Origin` 与 `Host`（以及 `--trusted-host` 白名单，**无通配符**）。
外层代理如果把 `Host` 改写成 `127.0.0.1:3080`，请求会直接被 dsh 以 **403** 拒绝。
`proxy_pass http://127.0.0.1:3080;` 在 nginx 里**默认就会改写 Host**（改成 `$proxy_host`），
所以必须显式写出来。

同时把反代后的公网地址写进 `server.yaml`：

```yaml
public_base_url: https://dsh.example.com
```

HDSL-web 用它给自签证书加 SAN，并在启动 dsh 实例时传 `--trusted-host dsh.example.com`。

### nginx（TLS 在外层终结）

```nginx
# http 上下文
map $http_upgrade $connection_upgrade {
    default upgrade;
    ''      close;
}

server {
    listen 443 ssl;
    http2 on;
    server_name dsh.example.com;

    ssl_certificate     /etc/nginx/certs/fullchain.pem;
    ssl_certificate_key /etc/nginx/certs/privkey.pem;

    # 整合包上传（.dspack）上限 200 MiB，留一点余量
    client_max_body_size 210m;

    location / {
        proxy_pass http://127.0.0.1:3080;

        proxy_http_version 1.1;
        # 关键：保留客户端发来的 Host（含端口），不改写
        proxy_set_header Host $http_host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # WebSocket：面板 /ws，以及每个实例的 /i/<id>/api/remote.mux
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection $connection_upgrade;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;

        # SSE（/i/<id>/plugins/events）与流式聊天不要缓冲
        proxy_buffering off;
    }
}
```

### Caddy（更少的坑：默认就透传 Host）

```caddyfile
dsh.example.com {
    encode zstd gzip
    reverse_proxy 127.0.0.1:3080 {
        header_up Host {host}      # 显式写出，等价于 Caddy 的默认行为
        flush_interval -1          # SSE 立即刷新
    }
}
```

Caddy 自动签发/续期证书；HDSL-web 这边就不用再配 HTTPS 了（见下面的注意）。

### 注意：外层 TLS + 内层 HTTP 的 cookie 安全标志

HDSL-web 是否给会话 cookie 加 `Secure`，取决于**它自己**是否启用 HTTPS（`https.enabled`）。
如果外层代理终结 TLS、HDSL-web 仍是 HTTP，cookie 就不带 `Secure`。在「3080 只绑回环、只被本机代理访问」
的拓扑下风险有限，但更稳妥的做法二选一：

- **推荐**：不要让 3080 暴露到公网。代理跑在宿主机上就只发布到宿主回环
  （`ports: ["127.0.0.1:3080:3080"]`，容器内保持 `HDSL_BIND_HOST=0.0.0.0`，否则宿主的端口映射连不进去）；
  代理本身跑在容器里就把它和 HDSL-web 放进同一个 Docker 网络、**完全不发布** ports，用服务名互访。
- **或者**：HDSL-web 自己也开 HTTPS（面板上传证书），外层代理改用 `proxy_pass https://127.0.0.1:3080;`
  配 `proxy_ssl_verify off;`（上游是自签或内网证书时）。此时 cookie 带 `Secure`，也是双层加密。

```nginx
# 内层也是 HTTPS 时的 location 片段
location / {
    proxy_pass https://127.0.0.1:3080;
    proxy_ssl_verify off;          # 上游证书由 HDSL-web 自己管理
    proxy_set_header Host $http_host;
    # …其余同上
}
```

### 端口与可达性

- 面板：容器内 `3080`，HTTPS 开启时同端口直接说 TLS。
- dsh 实例：容器内回环 `3081–4081`，**只监听回环、不要发布**，一切经 `/i/<id>/` 走 3080。
- 若同时开了 `https.redirect_http: true`，会额外监听 `redirect_port`（默认 80）做 308→HTTPS 跳转，
  需要在 compose 里把 80 也发布出去；外层已有代理负责跳转时**不要**开启它。

### 容器侧建议

```yaml
services:
  hdsl-web:
    # 只发布到宿主机回环，公网入口交给外层代理
    ports:
      - "127.0.0.1:3080:3080"
    environment:
      HDSL_BIND_HOST: "0.0.0.0"   # 容器内仍需 0.0.0.0 才能被宿主 127.0.0.1 映射访问
```

---

## 2. TLS 证书轮换

证书相关的三个位置：

| 文件 | 来源 |
|---|---|
| `/data/certs/self-signed.pem`、`self-signed-key.pem` | 首启自动生成（`https.enabled: true` 且未配证书） |
| `/data/certs/uploaded.pem`、`uploaded.key` | 面板上传 PEM |
| `/data/certs/uploaded.p12` | 面板上传 PKCS#12（**加载优先级最高**，会盖过 PEM） |

### 面板上传：热切换，不重启

「设置 → TLS 证书」上传 PEM（证书链 + 私钥）或 `.p12`（+ 口令）。服务器会：

1. 把选择写回 `/data/server.yaml`（`https.enabled: true` + 对应文件键，并清掉另一种类型的键，避免旧 `pkcs12_file` 盖住新 PEM）；
2. 已经在 HTTPS → `SslContextFactory.reload()`，**新证书立即生效，连接不断**；
3. 还是 HTTP → 运行时把明文连接器换成 TLS 连接器（同主机同端口）；
4. 切换失败 → 回滚成明文，响应带 `restartRequired: true`；`server.yaml` 已经写好，**重启容器即生效**。

### Let's Encrypt / 自动续期

HDSL-web 不做 ACME，续期要么交给外层代理（推荐：Caddy 自动续期，内层不配证书），
要么把续期产物送进容器再重启：

```bash
# 宿主机上 certbot 续期后
docker cp /etc/letsencrypt/live/dsh.example.com/fullchain.pem hdsl-web:/data/certs/uploaded.pem
docker cp /etc/letsencrypt/live/dsh.example.com/privkey.pem  hdsl-web:/data/certs/uploaded.key
# 让 HDSL-web 读取新文件：走面板上传（热重载）或直接重启
docker compose restart hdsl-web
```

> `docker cp` 写入的是 root 属主的文件。HDSL-web 以 `hdsl`(1000) 运行，读取没问题；
> 但如果你手工改了 `/data/certs` 的目录权限，记得 `chown 1000:1000`。
> 走面板上传则完全不用操心权限。

### 手工替换 + 重启

也可以直接把新 PEM 覆盖到 `/data/certs/`，或改 `server.yaml` 指到别处（绝对路径/相对 `HDSL_DATA` 都行），
然后 `docker compose restart hdsl-web`。重启会重新加载并校验证书；证书与私钥不匹配、文件缺失都会**启动失败**并指出原因。

---

## 3. 备份与恢复

### 备份：停容器，拷 `/data`

一致性来自「先把进程停下」：运行中的 dsh 实例会写 `home/`、pnpm 会写 store 与 `node_modules`，
热拷贝可能拿到半截状态。

```bash
# 1) 停
docker compose stop hdsl-web

# 2) 打包卷（用镜像自带的 tar，避免权限/uid 漂移）
#    -u 0:0：卷里的 auth/ 等目录是 0700，root 才读得全；产物属主是 root，必要时自行 chown
docker run --rm -u 0:0 \
  -v hdsl-data:/data \
  -v "$PWD":/backup \
  --entrypoint tar hdsl-web:latest \
  czf "/backup/hdsl-data-$(date +%F-%H%M).tgz" -C /data .

# 3) 起回来
docker compose start hdsl-web
```

只要 `/data` 就够了：`server.yaml`、证书、账号哈希、全部实例、pnpm store、corepack 缓存都在里面。
`/data/pnpm-store` 与 `/data/corepack` 可以还原后重建（重新下载），嫌大的话备份时排除它们。

### 恢复：空卷 + 解包 + 修属主

```bash
docker compose stop hdsl-web

# 用一个新的空卷（或先清空现有卷）
docker volume create hdsl-data-restore

docker run --rm -u 0:0 \
  -v hdsl-data-restore:/data \
  -v "$PWD":/backup:ro \
  --entrypoint tar hdsl-web:latest \
  xzf /backup/hdsl-data-2026-10-03-1200.tgz -C /data

# 解包是以 root 做的，把属主对齐镜像里的运行用户 hdsl(1000)
docker run --rm -u 0:0 -v hdsl-data-restore:/data --entrypoint chown hdsl-web:latest -R 1000:1000 /data
```

然后在 `docker-compose.yml` 里把卷指到 `hdsl-data-restore`（或者在原卷上恢复）再启动。
恢复后口令、证书、实例、会话记录原样回来，不需要重新登录配置。

### 迁移到另一台机器

同上：目标机器 `docker compose up -d` 一次（创建卷）→ 停 → 解包覆盖 → 起。
`/data` 的内容基本与 CPU 架构无关（jar 和实例数据都是），唯一例外是 `/data/hdsl/runtimes/` 里
**自下载**的 Node 二进制——镜像已内置 Node，一般没有这层数据；真跨架构迁移时删掉它、让新容器重新下载即可。
镜像本身用 [buildx](#4-多架构构建buildx) 为目标架构构建。

---

## 4. 多架构构建（buildx）

Dockerfile 对 `amd64` 与 `arm64` 都是自适应的：Node 版本按 `dpkg --print-architecture` 选官方 tarball，
校验 `SHASUMS256.txt`；三阶段基础镜像（`node:22-bookworm-slim`、`eclipse-temurin:21-jdk`、`debian:trixie-slim`）
都有两个架构的 manifest。

```bash
# 一次性准备 builder
docker buildx create --name hdsl-builder --use --bootstrap

# 构建并推送双架构（--push 才保留多架构 manifest；--load 一次只能装一个平台）
docker buildx build \
  --platform linux/amd64,linux/arm64 \
  --build-arg NPM_REGISTRY=https://registry.npmjs.org/ \
  --build-arg RELEASE_VERSION=0.1.0 \
  -t yourname/hdsl-web:0.1.0 \
  -t yourname/hdsl-web:latest \
  --push .
```

要点：

- 非本机架构走 QEMU 模拟，Gradle 与 Vite 都会明显变慢；急的话先在本机架构上验证，再跑双架构。
- **两个架构都必须能构建**：`nodejs.org` 与 npm registry 都要可达；`corepack prepare` 会下载 pnpm。
- `--build-arg RELEASE_VERSION=…` 才会让 jar 带上发布版本号（缺省 `0.1.0-dev`）；它同时写进 jar 的 manifest，
  面板显示的版本来自这里。
- 想省下 SPA 阶段的重复劳动可以用 registry cache：`--cache-from/--cache-to type=registry,ref=…`。

---

## 5. 升级与回滚

```bash
# 升级：重新构建镜像 → 换容器 → 卷不变
docker compose build --pull
docker compose up -d

# 指定版本（build arg）
docker compose build --build-arg RELEASE_VERSION=0.2.0
```

- 数据全部在 `/data`，换容器（甚至换镜像）不会丢；`HDSL_ADMIN_PASSWORD` 只在**首次**创建账号时有用，
  升级时传不传都行。
- 升级前建议按上面第 3 节做一次备份。
- 回滚就是把旧 tag 的镜像重新 `up -d`；如果新版本写过 `server.yaml` 的新字段，旧版本会给出
  「unknown key … ignored」警告而不影响启动。

---

## 6. 运行期下载源（面板设置）

「设置 → 下载源」改的是**运行期**用哪个 npm registry，和构建镜像时的 `--build-arg NPM_REGISTRY` 是两回事：

| | 构建期 `NPM_REGISTRY`（build-arg） | 运行期「下载源」（面板设置） |
|---|---|---|
| 作用范围 | `docker build` 期间：SPA 阶段的 `npm ci`、corepack 下载 pnpm 本体 | 容器运行期间：dsh 实例安装/升级、插件安装、ZCode 构建 |
| 改完怎么生效 | 重新构建镜像（`docker compose build`） | 保存即生效，不用重启容器 |
| 存在哪 | 只在构建过程里，不进镜像 | `/data/hdsl/launcher-settings.json`（随 `/data` 一起备份） |

**优先级：面板设置 > 环境变量 `NPM_CONFIG_REGISTRY` > 官方默认 `https://registry.npmjs.org/`。**
想在面板之外钉死一个源，就把面板里的下载源设成「跟随部署环境（默认）」——那正是「清空设置、只用环境变量」的开关。

### 怎么改、什么时候生效

预设给的是几个公开镜像，也可以填自己的 URL：

| 预设 id | 地址 |
|---|---|
| `environment` | 跟随部署环境（默认；清空设置，回到环境变量 / 官方） |
| `npmjs` | `https://registry.npmjs.org`（官方） |
| `npmmirror` | `https://registry.npmmirror.com` |
| `ustc` | `https://npmreg.proxy.ustclug.org`（中科大） |
| `tencent` | `https://mirrors.cloud.tencent.com/npm`（腾讯云） |
| `huawei` | `https://repo.huaweicloud.com/repository/npm`（华为云） |

内网的 Nexus、Verdaccio 这类 `http://192.0.2.10:8081/repository/npm/` 是允许的——这一版要防的是**注入**，
不是强制 TLS，所以 `http` 只校验、不拒绝。保存前服务端会校验：只允许 `http`/`https`、必须能解析出主机名、
禁止用户口令 / 查询串 / 片段，以及空白、控制字符、`"` `'` `` ` `` `\` `#` `$` `;` `|` `&` `@`，长度 ≤ 200，
尾斜杠自动去掉；不合法直接 400 且**设置保持不变**。

保存后对**之后产生**的 pnpm/npm 子进程生效：新建或重装的实例、插件安装、面板内构建都会用新源；
已经装好的实例不会因此重装（要重装/升级才会走新源）。容器重启后设置仍在，入口脚本先生成
`config.yaml`、面板启动时再按设置重写一次。

### 它会重写 pnpm 的全局配置

pnpm 11 **不读环境变量**，registry 只来自它自己的全局配置 `~/.config/pnpm/config.yaml`
（容器内是 `/home/hdsl/.config/pnpm/config.yaml`，镜像里没有任何 `.npmrc`）。所以面板在**启动时**和**每次保存**都会
按生效值重写这份文件：逐行保留其它设置（`storeDir` 不会丢）、只重写 `registry` 一行，值经 YAML 引号、
写临时文件后原子替换、权限 0600；若本来没有 `storeDir`，还会按 `PNPM_STORE_DIR` 补一行。
同时面板还给子进程注入 `npm_config_registry` / `NPM_CONFIG_REGISTRY`（npm 系读它），
并在自己起的 pnpm 上显式加 `--registry=`（dsh 安装、ZCode 构建，日志里能看到实际用的源）。

**后果**：手工挂载或手改的 `config.yaml` 里的 `registry` 会被覆盖——即使面板里选的是「跟随部署环境（默认）」，
写进去的也是环境变量或官方默认，而不是你手写的那一行（`storeDir` 等其它行原样保留）。
想自己维护这个键，就得让这个文件别被面板碰：目前没有开关，只能改完文件后别在面板里保存下载源。
另外这份文件在容器 home 下、**不在 `/data` 卷里**：容器重建会回到入口脚本生成的那份，所以面板每次启动都会重写一次。

> corepack 下载 pnpm 本体走的是它自己的 `COREPACK_NPM_REGISTRY`（由入口脚本从 `NPM_CONFIG_REGISTRY` 派生）：
> 面板设置只写 pnpm 那份 config.yaml、不碰这个变量，所以完全离线的内网部署仍需用环境变量把 corepack 也指到位。

---

## 7. 自签证书与容器健康检查（`Invalid SNI`）

面板启用 HTTPS、而证书的主机名是 `dsh.example.com` 这类域名时，`curl https://127.0.0.1:3080/api/health`
这种「按地址访问」的请求不带 SNI；Jetty 默认的 SNI 主机校验会把它判成 `400 Invalid SNI`，
于是容器健康检查一直失败、`docker compose ps` 里永远 `unhealthy`（服务其实是好的）。

HDSL-web 已经把这项校验关掉——单证书面板，这个校验只会误伤本机探针：

```java
// web/server/HdslServer.java：两个 TLS 连接器共用的定制器
customizer.setSniHostCheck(false);   // sniRequired 保持默认 false
```

所以 compose 里的健康检查可以直接写 `https://127.0.0.1:${HDSL_PORT}/api/health`。
若你的部署里探针仍然失败，退一步也可以让探针带上证书里的主机名：

```bash
curl -fsSk --resolve dsh.example.com:3080:127.0.0.1 https://dsh.example.com:3080/api/health
```

排查时先手动跑一次上面的命令：返回 `{"status":"ok"}` 就说明服务没问题，是探针的 SNI 不对。

---

## 8. 宿主 inotify 配额（多实例）

**跑两个以上实例之前值得先看一眼这一节**：实例启动失败的一种常见原因在这里，而它的现象完全不指向这里。

### 它是什么

dsh 启动时会用文件监视器（chokidar）盯着自己的 `profiles/<profile>` 目录。内核为此设了一个上限
`fs.inotify.max_user_watches`（默认 65536），而这个数字**是按用户（uid）算的，容器之间不隔离**：
面板容器以 `hdsl`(1000) 运行，于是宿主上**任何同样以 uid 1000 运行的程序**——编辑器/IDE 服务、
文件同步客户端、别的容器——所监视的每一个目录，都从这个数字里扣。一个递归监视整棵工作树的程序
就能把 65536 吃干。

### 配额用尽时的现象

都不像「配额不足」，所以容易查错方向：

- **实例起来就消失**：进程崩了，它的日志（面板实例页的「日志」标签）里是
  `Error: ENOSPC: System limit for number of file watchers reached, watch '…/profiles/web'`
  ——node 的 `fs.watch` 拿不到监视器就直接退出；
- **实例永远停在「启动中」**：进程活着、端口在监听，但启动项因为拿不到监视器而永不结束，
  于是**就绪行一直不打印**，面板拿不到地址，`/i/<id>/` 返回 502。

两种现象都跟「另一个实例先跑起来了」强相关，因为争夺的是同一个共享数字——谁先谁后决定谁被拒，
不是某个实例本身有问题（`instance`、`dsh-…` 只是运气不同的那一个）。

### 怎么看

面板「设置 → 环境体检」（也就是 `GET /api/doctor`）里的 **File watchers** 一行：
它用一次真实的注册探测给出「还剩多少」，低于阈值会标 `[SHORTAGE]` 并打印下面这条命令。
想看原始数字也可以直接问内核：

```bash
# 上限（所有 uid 1000 的进程共用的那个数字）
cat /proc/sys/fs/inotify/max_user_watches
# 谁在占（容器里的进程要进各自的容器里数）
docker exec <容器> sh -c 'for p in /proc/[0-9]*; do n=0; for f in $p/fdinfo/*; do \
  [ -f "$f" ] && n=$((n+$(grep -c "^inotify wd:" "$f" 2>/dev/null))); done; \
  [ "$n" -gt 0 ] && echo "$n $(tr -d "\0" < $p/cmdline | cut -c1-60)"; done'
```

### 怎么修

在**宿主**上把上限调大（容器里改不动这个内核参数）：

```bash
echo 'fs.inotify.max_user_watches=524288' | sudo tee /etc/sysctl.d/99-inotify.conf
echo 'fs.inotify.max_user_instances=1024'  | sudo tee -a /etc/sysctl.d/99-inotify.conf
sudo sysctl --system
```

- 524288 是这类工具的常见建议值，够跑若干个实例；宿主上真有一棵几十万目录的树被递归监视时再往上调。
- 已经在监视的文件不受影响，**容器不用重启**；但已经崩掉或卡在「启动中」的实例要回面板重新启动。
- 不想动配额，还有两条路：让那个吃配额的程序少监视一点（多数编辑器/IDE 服务都有「排除监视目录」
  之类的设置，把 `node_modules`、构建产物这类大目录排除掉）；或者**给面板容器换一个独立的 uid**，
  让它有自己完整的一份配额。换 uid 不只是 compose 里加一行 `user:`——实测至少还有两处要可写：
  入口脚本要往 `$HOME`(`/home/hdsl`) 写 pnpm 配置，面板要在 `/data/auth` 下建账号文件，
  所以 `/data` 卷得先 `chown` 给新 uid，`$HOME` 也要 chown 或者干脆挂一个属于该 uid 的卷。

### 顺带一提：两个实例的 localStorage 是共用的

这一条与上面的配额无关，但也是「两个实例互相影响」的一个来源，值得记下来：面板把两个实例放在**同一个
origin** 下，而浏览器只按 origin（协议 + 域名 + 端口）隔离 `localStorage` / `sessionStorage`，
**完全不看路径**——所以两个实例的网页端会读写同一批键（dsh 自己的客户端状态、界面偏好等）。
面板目前只处理了 cookie 的 `Path`，没管 storage。目前没有证据表明它导致过实例起不来；
真出现「界面串台」时再往这个方向查。


