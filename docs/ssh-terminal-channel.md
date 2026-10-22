# SSH 终端通道

给面板加一条 SSH 前端：`ssh -p 3022 hdsl@主机` 进来，先过一道独立的**开发者密码门禁**，
再看到一个编号菜单（dsh 实例 / 品牌实例 / 恢复 dsh 会话），选定后对应实例的
`dsh tui [--resume]` 终端直接接管当前 SSH 会话。SSH 的终点永远是
**门禁 → 菜单 → 白名单 agent**，不是一个 shell。

## 它是干什么的

在手机上、或任何没有浏览器的终端里，用真正的 SSH 客户端接回你的 dsh 实例：

```
$ ssh -p 3022 -t hdsl@nas
开发者密码: (输入将回显) ********

dsh 实例
  1) dsh-1
品牌实例
  2) kimi: 主力机
恢复 dsh 会话
  3) [web 使用中] 克隆仓库并分析改进点   (12:38)
  4) 另一个标题   (昨天 09:12)
q) 退出
> 1
（dsh tui 接管整个终端；TERM/尺寸透传，resize 生效）
```

选定实例后，面板按 **web 启动同一条链路**（`DshLauncher.plan`：账户路由注入、provider
key 环境变量、实例自己的 Node 运行时、DSH_HOME）spawn `dsh tui`，只是换了 surface、
cwd 落在实例 home——所以 resume 会话、账户、模型路由和网页启动完全一致。断开
（`exit` 或网络断）时面板杀整棵进程树，不留孤儿。

## 快速上手

前置：面板已启用 **HTTPS**——SSH 通道的所有敏感操作都以 TLS 生效为前置（服务端按
连接器实际状态判定，不看请求头）。

1. **设置开发者密码**：设置页「SSH 终端通道」区，进入开发者模式（输面板口令确认是本人）
   后设置。≥8 位、不得与面板口令相同，PBKDF2 哈希独立存储于
   `/data/auth/developer.json`（0600、原子写）。
2. **打开开关**：「启用」写 `server.yaml`（原子落盘）并热启动监听器；页面随即显示
   监听地址与 host key 的 OpenSSH fingerprint（Ed25519，首启生成、持久化、重启不换）。
   「绑定地址」可直接在设置页填写并保存，运行中的监听器会立即换地址重启；绑不上时
   后端回滚到旧地址。填非回环地址（如 `0.0.0.0`）会先弹确认框说明暴露面。默认
   `127.0.0.1` 只监听本机，**容器部署要填 `0.0.0.0`**（并把 SSH 端口映射到宿主机），
   见下文「容器部署」。
3. **添加公钥**（默认必须密钥登录）：
   - 粘贴自己的 OpenSSH 公钥（算法白名单 ed25519/rsa/ecdsa + base64 校验），写入
     `/data/ssh/authorized_keys`（0600）；或
   - 「生成密钥对」：服务端生成、页面**一次性展示私钥并触发下载，永不落盘**，公钥直接入库。
   - （可选）开发者模式内打开「允许口令登录」：SSH 可用面板口令登录，开前弹爆破风险警告。
4. **连接**：`ssh -p 3022 -t hdsl@主机` → 输开发者密码 → 菜单输编号进入 TUI；
   `q`/`exit`/EOF 退出。会话条目带 `[web 使用中]` 前缀表示 dsh web 正占用该会话
   （`session.lock` 活性），attach 会被 dsh 自身拒绝并透传其报错。

## 安全模型

| 面 | 决策 |
|---|---|
| 默认关闭 | `ssh.enabled=false` 时不构造服务、不监听任何端口——「不开开关就没有攻击面」是硬保证，有集成测试钉死 |
| 能力真子集 | 通道只能 spawn 白名单 TUI/恢复会话；不设 `CommandFactory`、不注册 SFTP、转发全拒。删实例/改设置/看密钥等网页操作 SSH 一件做不了 |
| 双因子 | SSH 公钥（持有物）+ 独立开发者密码（知识）。开发者密码同时是网页 SSH 管理区的提权凭证，挡住「偷网页会话 → 塞公钥 → 常驻后门」的升级链 |
| 门禁防爆破 | 错 3 次断开；**每次错答都计数**（挂断重连也照记）——15 次错答花光预算，第 16 次起门禁全局封 30 分钟（换 IP 也一样），并把该来源地址的 SSH 登录一并拒绝 30 分钟；门禁阶段 120 秒无输入断开（`ssh.idle-timeout`） |
| 认证 | 公钥默认开（authorized_keys，0600）；口令登录默认关、可开（与网页同 UserStore、共享 per-IP 节流、热应用） |
| 敏感操作 HTTPS 前置 | 开发者密码设置/修改、提权、密钥生成下载、公钥上传、开关、口令登录开关——仅面板 TLS 生效时可用（409 + 原因文案） |
| 凭据落盘 | host key / authorized_keys / 开发者哈希均 0600+原子写；密钥对私钥不落盘 |
| 审计 | append-only JSONL（`/data/logs/audit.jsonl`，按日轮转）：连接/断开/开关/门禁成败/attach/detach/提权/改钥——**只记元事件，不记键盘流与会话内容** |
| 数据消毒 | 实例名/会话标题等盘上数据回显前过控制字符过滤（防终端转义注入） |

## 平台矩阵

| 平台 | 支持 |
|---|---|
| Linux | ✅ 完整支持（开发与测试平台） |
| macOS | ⚠️ 放行但**未真机实测**（体检标 warn；真机复验后转正） |
| Windows | ❌ 不支持——网页入口禁用并说明、toggle API 409、`SshService.start()` 拒绝（三层防配置跨平台拷贝绕过） |

## 编译开关：`-PwithSsh`

SSH 是「万不得已」通道：**默认构建不含它**。CI 与 Dockerfile 的 `./gradlew build`
产出里物理不存在 `org/jackhuang/hmcl/web/ssh.**`、`org.apache.sshd.**`、`net.i2p.**`；
运行时由反射桥（`SshBridge`）按名加载，缺类时启动日志一行「此构建不含 SSH 终端通道」，
`/api/ssh/*` 不注册，体检如实标注，其余功能零影响。

要在二进制里带上 SSH 服务端：

```bash
./gradlew --no-daemon -PwithSsh shadowJar
```

这是**代码层面的开关**：带与不带是两份 jar，不是运行时隐藏。实现要点：`-PwithSsh`
之外 MINA/eddsa 走 `compileOnly`（源码照常编译、测试照常跑），`shadowJar` 对 SSH
类与这两个包做物理剔除；包外代码（HdslServer/DoctorApiServlet 等）不持有任何 SSH
类型的静态引用，这是审计过的硬约束。

日常的 TUI 入口是 Web Shell（[docs/web-shell.md](web-shell.md)），不需要 SSH。

## 配置参考

```yaml
# server.yaml（0600）
ssh:
  enabled: false            # 默认关；false = 不构造服务、无监听端口
  bind_host: 127.0.0.1      # 网页可改（开发者模式）；0.0.0.0 = 所有网卡
  port: 3022
  password_auth: false      # 网页可开关；默认关 = 必须密钥登录
```

环境变量覆盖：`HDSL_SSH_ENABLED` / `HDSL_SSH_BIND` / `HDSL_SSH_PORT`。
开发者密码不进 server.yaml（独立 `/data/auth/developer.json`）。
不支持的平台即使 `enabled=true` 也拒绝启动。

### 容器部署

容器里的 `127.0.0.1` 是**容器自己的回环**，从宿主机或其它机器连不上。要让 SSH
通道从外部可用，需要两件事：

1. 设置页把「绑定地址」改成 `0.0.0.0`（保存后监听器热重启）；
2. compose 里把 SSH 端口映射到宿主机（本仓库的 `docker-compose.yml` 已给出
   `3022:3022`；若改了 `ssh.port`，映射的内外端口要一起改）。

绑定 `0.0.0.0` 的那一刻，SSH 端口对所有可达网络开放。门禁（独立开发者密码）、
公钥/口令认证与审计仍然生效，但请同时用防火墙/安全组把来源限制到你信任的范围；
公网场景建议只经由 Tailscale/WireGuard 或反向代理白名单进入。

## 当前限制

- **品牌实例（Kimi Code / OpenCode / Claude Web）暂不能从 SSH 接入**：菜单选品牌回一行
  「后续版本提供」。各品牌 CLI 差异大，硬套 dsh 的拼装会产生错误命令；品牌终端接入
  随其 CLI 方案单独落地。
- **开发者密码回显**：服务端通道无 PTY，输入会回显在客户端（提示行已标注
  「(输入将回显)」）。v2 计划引入真 PTY。
- **裸管道**：agent 跑在管道上而非 tty，全屏渲染/TERM 处理正常的 TUI（含 dsh tui）工作
  良好；个别强制 tty ioctl 的程序表现受限（已知取舍）。TERM/COLUMNS/LINES 已透传、
  resize 经 SIGWINCH 已达子进程。
- **`MaxAuthTries=3` 未实现**：sshd-core 2.12.1 没有该旋钮；公钥默认必登录 + per-IP
  节流已覆盖主要爆破面，留作后续加固项。
- macOS 行为未实测（见平台矩阵）；Windows 明确不支持。

## 附：实施注记

实现历程（M0 审计设施 → M1 服务骨架 → M2 门禁菜单 → M3 attach → M4 加固文档，
一个里程碑一个提交）与下列取舍固化于 git 历史，本节留档要点：

- **类名对齐**：计划中的 `PanelShellFactory` 实施为 `MenuShellFactory`；`SpawnSupport`
  为 `AttachSupport` + `DshAttachSupport`；会话扫描为 `SessionCatalog`；noSsh 反射桥为
  `SshBridge`（web.server 包）。
- **eddsa 依赖**：`net.i2p.crypto:eddsa` 是 sshd-common 的 optional 依赖不传递，缺它所有
  Ed25519 路径（authorized_keys/host key）运行期必炸——已显式声明并写入 NOTICE。
- **host key 与 i2p provider**：JDK `NamedParameterSpec` 被 i2p 生成器拒绝，曲线改用
  provider 自己的 spec 语言；重载路径钉死走 sshd 自家 OpenSSH 解析器（BC 解析器会把
  Ed25519 文件解成无关 EC key、重启即换 fingerprint）。
- **门禁语义**：每连接错 3 次断开，但**每一次错答都进全局预算**（挂断重连不再白拿）；
  15 次错答花光预算，第 16 次起门禁全局封 30 分钟——一个 key 记所有来源，换 IP 也得等——
  同时该来源地址的 SSH 登录被拒 30 分钟（公钥/口令都不放行）；审计每一次失败与封禁；
  未设置开发者密码给明确文案后退出。
- **提权按会话**：网页「开发者模式」的 15 分钟提权以会话 token 为键，不跨会话共享，登出/
  撤销会话即失效；开发者密码的二次确认（面板口令）同样走 per-IP 预算，15 次后拒 10 分钟。
- **会话扫描**：只读 sidecar JSON（`record.rows.title.val`）拿标题，`session.lock`
  mtime ≤2 分钟标「web 使用中」；`session.v4.jsonl.zstd` 主日志永不解析；新旧两种会话
  目录拼法（裸 `<uuid>` 与 `session-<uuid>`）并存都扫。
- **attach**：env 注入零拷贝复用 `DshLauncher.plan` + `InstanceRuntime.accountOf`；
  同 home 单 attach（ConcurrentHashMap 把关）；断连 `ProcessTree` 杀整棵进程树并审计
  `ssh.detach`；进程非 0 退出审计 `ssh.attach` failure。
- **容器内无 JDK 的教训**：M0/M1 曾混入 5 处编译错误从未被编译器核过，已修复并从此
  把「能编译」当作提交底线。
