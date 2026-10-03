# HDSL-web 部署细节

面向「已经跑起来，准备接到公网或长期运行」的场景。快速开始与配置字段见 [../README.md](../README.md)。

- [1. 再套一层反向代理（nginx / Caddy）](#1-再套一层反向代理nginx--caddy)
- [2. TLS 证书轮换](#2-tls-证书轮换)
- [3. 备份与恢复](#3-备份与恢复)
- [4. 多架构构建（buildx）](#4-多架构构建buildx)
- [5. 升级与回滚](#5-升级与回滚)

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

## 6. 自签证书与容器健康检查（`Invalid SNI`）

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

