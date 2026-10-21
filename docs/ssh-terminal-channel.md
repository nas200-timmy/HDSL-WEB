# SSH 终端通道：设计 + 分里程碑实施计划

> 状态：**已实施**（M0–M4 全部落地，分支 `feat/windows-bare-deploy`，一个里程碑一个提交）。
> 类名对齐：计划中的 `PanelShellFactory` 实施为 `MenuShellFactory`；`SpawnSupport` 实施为
> `AttachSupport` + `DshAttachSupport`；会话扫描为 `SessionCatalog`；`-PnoSsh` 的反射桥为
> `SshBridge`（web.server 包）。实施期间的全部取舍固化在 §5 各里程碑的「实施注记」与 §6 风险登记。

## 1. 这是什么

给面板加一条 SSH 前端：用户 `ssh -p 3022 hdsl@主机` 进来，先过 TUI 门禁
（开发者密码），看到一个编号菜单（dsh 实例 / 品牌实例 / 恢复 dsh 会话），
选定后把对应的 agent TUI（`dsh tui [--resume]`）attach 到当前终端。
SSH 的终点**永远是门禁 → 菜单 → 白名单 agent**，不是 shell。

设计决策（已评审定案）：

- **单仓库集成**，代码全部在 `org.jackhuang.hmcl.web.ssh` 包内，自包含、可整包剪出。
- **默认关闭**。`ssh.enabled=false` 时 `SshService` 不被构造、不监听任何端口——
  「不开开关就没有攻击面」是硬保证，有集成测试钉死。
- **平台门（硬封禁）**：SSH 与开发者模式**仅 Linux 完整支持、macOS 放行但标
  「未实测」**；**Windows 与其余平台直接封禁**——网页入口禁用并说明、
  toggle API 返回 409、`SshService.start()` 前置校验拒绝启动（三层防
  配置文件跨平台拷贝绕过）。判定用 `OperatingSystem`。
- **开发者密码**（双角色，同一个凭证）：
  1. **网页开发者模式**：SSH 管理区（开关/公钥/密钥生成/口令登录开关）必须先输
     开发者密码「进入开发者模式」才可操作。它挡住的攻击链：偷到网页会话的人
     塞公钥、把一次性会话变成常驻 SSH 后门。
  2. **TUI 门禁**：SSH 认证（公钥或口令）通过后，先输开发者密码才见菜单。
     与公钥认证合起来 = 持有物 + 知识的双因子。
  - 存储：独立 PBKDF2 哈希 `/data/auth/developer.json`（0600、原子写、可改可清）。
    未设置时网页管理区提示先设置，SSH 侧直接拒绝并说明。
- **公钥优先**：`authorized_keys`（0600，网页可编辑，支持粘贴自己的 OpenSSH 公钥）；
  密钥对**服务端生成（方案 A）、页面一次性展示下载、永不落盘**；
  **默认必须密钥登录**；网页保留「允许口令登录」开关（默认关，开 = 面板口令，
  与网页同 UserStore，跨通道共享 IP 节流，改密即时生效）。
- **敏感操作 HTTPS 前置**：开发者密码的设置/修改、开发者模式进入、密钥对生成下载、
  公钥上传、SSH 开关、口令登录开关——**仅在面板 TLS 生效时可用**；纯 HTTP 下
  入口禁用并说清原因。判定用服务端 TLS 实际状态（连接器是否激活），不看请求头。
- **能力真子集**：SSH 通道只能 spawn 白名单 TUI/恢复会话；网页登录能做的事
  （删实例、改设置、看密钥）SSH 一件做不了。新增的只是「一个认证端点」，
  不是「更大权限」。
- **审计先行**：M0 先落审计设施，之后每个 M 的新事件全部落审计。

复用的现件：`UserStore`（PBKDF2 与恒定时间比较）、`LoginThrottle`（v0.4.7，
限流范式）、`ProcessTree`（v0.4.2，整树清理）、`DshLauncher` 实例 env 注入链路、
`ServerConfig`/`ServerConfigWriter`（声明式配置）、`OperatingSystem`（平台判定）、
体检（doctor）框架。

## 2. 威胁模型与边界（写死，不随实现漂移）

| 面 | 决策 |
|---|---|
| 网络暴露 | 默认绑 `127.0.0.1`；绑 LAN 需网页显式确认 + 警告文案；公网建议走 Tailscale/外层代理白名单 |
| 平台 | Linux 完整支持；macOS 放行标「未实测」（待真机复验转正）；**Windows 及其余平台封禁开发者模式与 SSH**（UI/API/服务三层） |
| 认证（SSH 层） | publickey 默认开、password 默认关（网页开关 `password_auth`，开时带爆破风险警告）；`MaxAuthTries=3`；按来源 IP 退避（与网页 `LoginThrottle` 共享后端） |
| 认证（开发者层） | 开发者密码独立于面板口令：网页管理区提权 + TUI 门禁双角色；PBKDF2 恒定时间核对；TUI 门禁错 3 次断开 + 审计 + 按 IP 退避 |
| 会话劫持 → 后门 | 无开发者密码不能加公钥/开 SSH/开口令登录——阻断「偷会话 → 常驻后门」升级链 |
| 敏感操作 | 开发者密码设置/修改、进入开发者模式、密钥生成下载、公钥上传、SSH 开关、口令登录开关，**仅 HTTPS 生效时可用** |
| 授权 | SSH 会话只允许面板白名单命令；不设 `CommandFactory`、不注册 SFTP 子系统、`ForwardingFilter` 全拒 |
| 逃逸面 | agent 自己的 bash 工具由 **dsh sandbox**（workspace-write）兜底，SSH 层不重复造 |
| 凭据落盘 | host key `/data/ssh/hostkey`（0600）；`authorized_keys`（0600）；开发者哈希 `/data/auth/developer.json`（0600）；server.yaml 先修 0600（M0）；密钥对私钥不落盘 |
| 审计 | 记元事件（连接/断开/开关/spawn/认证失败/提权），**不记键盘流与会话内容** |
| 折回信号 | 若 `web.ssh` 需要的能力超出「门禁 + 菜单 + spawn 白名单」，说明边界画错，合回评估 |

## 3. 配置面

```yaml
# server.yaml（0600，M0 修复权限）
ssh:
  enabled: false            # 默认关；false = 不构造 SshService、无监听端口
  bind_host: 127.0.0.1
  port: 3022
  password_auth: false      # 网页可开关；默认关 = 必须密钥登录
```

环境变量覆盖：`HDSL_SSH_ENABLED` / `HDSL_SSH_BIND` / `HDSL_SSH_PORT`。
开发者密码不进 server.yaml（独立 `/data/auth/developer.json`）。
**不支持的平台即使 enabled=true 也拒绝启动**（`SshService` 前置校验）。

## 4. 端到端流程（验收时走一遍）

1. 设置页「SSH 终端通道」区：不支持的平台（Windows 等）整区禁用并说明原因；
   支持的平台默认显示「已关闭 ✓」，操作区提示需进入开发者模式；体检段同步。
2. 首次开启（**全程要求 HTTPS**，纯 HTTP 入口禁用并说明原因）：
   设置开发者密码（输两遍，≥8 位，不得与面板口令相同，PBKDF2 落盘 0600+原子写）
   → 重输面板口令确认是本人 → 生成 host key、弹 fingerprint → `enabled=true`
   写盘（原子写）→ `SshService` 热启动（平台前置校验通过）→ 审计逐条落。
3. 配置公钥（HTTPS + 开发者模式）——两条路：
   - **粘贴自己的 OpenSSH 公钥**（服务端校验：算法白名单 ed25519/rsa/ecdsa、
     base64 有效、注释可选），写入 `authorized_keys`；
   - 或**生成密钥对**：服务端生成、页面**一次性展示私钥并触发下载，关闭后
     无法找回**（页面明示）；公钥直接写入 `authorized_keys`。
4. （可选）开发者模式内打开「允许口令登录」开关（爆破风险警告）：
   SSH 可用面板口令登录，与公钥方式并存。
5. `ssh -p 3022 hdsl@host -t` → 第一道 SSH 认证（公钥或口令）→ **TUI 门禁**：
   输开发者密码（错 3 次断开 + 审计；**门禁阶段 120 秒无输入断开**，审计
   `ssh.idle-timeout`）→ 菜单（实例/品牌/会话分组，会话条目标题
   来自 session sidecar JSON，锁标记来自 `session.lock` 活性）。
6. 选条目 → 面板按实例 spawn `dsh tui [--resume <id>]`（env 注入走 `DshLauncher`
   链路）→ 字节泵接管，TERM/尺寸透传，resize 实时生效。
7. 断开（`exit` 或网络断）→ `ProcessTree` 杀整棵 agent 树 → 审计落断开事件。
8. 关闭开关（开发者模式 + HTTPS）→ 端口立即释放 → 测试断言 3022 RST。
9. 旁路验证：`docker exec hdsl-web ss -tlnp | grep 3022` 关闭时为空。

## 5. 分里程碑计划（每个 M = 一个提交）

### M0｜审计基础 + server.yaml 权限 — 提交 `feat(audit): …` ✅ 已实施

- `AuditLog`：append-only JSONL（`/data/logs/audit.jsonl`，0600，按日轮转、启动清残留），
  事件 `{ts, actor, channel, action, object, outcome, ip}`；通道枚举先含 `web`/`ssh`。
- 接线首批事件：登录成功/失败/被节流（复用 v0.4.7 的节流回调点）、改密、下载源变更、
  TLS 证书上传、实例 create/delete/launch/stop。
- `ServerConfigWriter` 写 `server.yaml` 后收紧 0600（修当前 0644 明文存 p12 密码的问题，
  顺带核查 p12 密码落盘是否可改为引用 `/data/auth/` 下独立 0600 文件——本期只修权限，不改结构）。
- 测试：审计文件轮转/并发写/权限断言；server.yaml 落盘权限断言；
  每个接线点一条「事件已落」用例。目标用例数 +20 左右。

实施注记：M0 本体随 `e4f1247` 落地；容器内无 JDK，其中两处编译错误（Main 缺
`java.io.IOException` import、ApiGuardFilter rethrow 越界）在 `16815ca` 一并修复。

### M1｜SSH 开关、平台门、开发者密码与骨架 — 提交 `feat(ssh): …` ✅ 已实施

- 依赖：`org.apache.sshd:sshd-core`（shadowJar 已配 `mergeServiceFiles`）。
- `SshService`：仅 `enabled=true` 且平台允许时由 `HdslServer` 构造并启动；
  `stop()` 挂进 `HdslServer.stop()`（对称关闭，TaskService 前车之鉴）。
  热启停：toggle API 直接调 start/stop。
- **平台门 `PlatformGuard`**：`OperatingSystem` 判定——Linux 放行；macOS 放行
  （体检标「未实测」）；Windows 及其余平台：网页整区禁用、toggle API 409、
  `SshService.start()` 拒绝。防配置跨平台拷贝绕过。
- `DeveloperAuth`：开发者密码的设置/修改/校验（PBKDF2、恒定时间、≥8 位、
  不得与面板口令相同、0600+原子写、可清除）；`TuiGate` 校验器（错 3 次断开 +
  按 IP 退避，复用 `LoginThrottle` 模式）；未设置时相关操作给出明确文案。
- host key：首次开启生成 Ed25519，落 `/data/ssh/hostkey`（0600），重启不换；
  网页显示 OpenSSH 格式 fingerprint（SHA256）。
- **HTTPS 前置守卫**：开发者密码设置/修改、开发者模式进入、密钥生成下载、
  公钥上传、SSH 开关、口令登录开关——服务端逐一校验 TLS 生效
  （`HdslServer` 连接器状态），不满足返回 409 + 原因文案；
  前端这些入口在纯 HTTP 下禁用并显示原因（沿用 Windows 不支持项的既有交互模式）。
- `authorized_keys`：CRUD API（**开发者模式内**），文件 0600；支持**粘贴上传**
  （格式校验：算法白名单、base64 解码、注释可选）；
  前端「生成密钥对并下载」（**服务端生成，方案 A**：私钥页面一次性展示、
  不落盘、关闭不可找回）。
- `password_auth` 网页开关：默认关；开发者模式内开启需爆破风险警告文案，
  热应用（`SshService` 重载认证配置）；口令 = 面板口令（同 `UserStore`）。
- 网页「开发者模式」：设置页 SSH 区进入前输开发者密码，提权作用于本次登录会话
  （登出即失效，15 分钟无操作自动退出提权）；所有敏感操作 API 校验提权状态。
- toggle API：`POST /api/settings/ssh`（开发者模式 + 目标状态），
  写 server.yaml（原子写）→ 热应用 → 审计。
- 体检段：平台行（Linux ✓ / macOS 未实测 / Windows 不支持）+ SSH 状态行
  （关闭✓ / 监听地址+fingerprint+连接数+认证方式）。
- 测试：Mina 官方 client JVM 内自连——开/关/toggle 后端口状态、
  公钥登录成功、密码认证默认拒（开启后可用）、未开启时 3022 连接被 RST（非超时）、
  HTTPS 前置守卫 409 用例、公钥格式校验正/反用例、
  开发者密码设置/校验/错 3 次退避/未设置文案、提权过期、
  平台门三态（Linux 过 / macOS 过且标未实测 / Windows 拒）、审计逐条核对。

实施注记：随 `5c25b01` 落地（骨架为 `SkeletonShellFactory`，M2 被替换删除）。
**两处计划外修复**（`3e58736`）：① `net.i2p.crypto:eddsa` 是 sshd-common 的
optional 依赖不传递，缺它所有 Ed25519 路径（authorized_keys/host key）运行期必炸——
已显式声明依赖；② host key 生成对齐 i2p provider（JDK `NamedParameterSpec` 被拒），
重载路径钉死走 sshd 自家 OpenSSH 解析器（BC 解析器会把 Ed25519 文件解成无关 EC key，
重启即换 fingerprint）。`MaxAuthTries=3` 未实现（sshd-core 2.12.1 无该旋钮）：
公钥默认必登录 + 口令 per-IP 节流已覆盖主要爆破面，留作后续加固项。

### M2｜panel-shell 菜单与 TUI 门禁 — 提交 `feat(ssh): …菜单+门禁` ✅ 已实施

- `PanelShellFactory`：唯一入口；无 `CommandFactory`/SFTP/转发。
  行导向 ANSI（JLine3 只做行编辑，不做全屏）。
- **TUI 门禁屏**（SSH 认证通过后、菜单之前）：输开发者密码，错 3 次断开并审计；
  未设置开发者密码 → 明确文案后断开。提权仅本会话有效。
- 菜单数据源：dsh 实例（`DshInstanceRepository`）、品牌实例、
  「恢复 dsh 会话」（扫 `<home>/sessions/*/session-*` 目录，**只读
  `storages/session_projcache` sidecar JSON** 拿 title/seq/mtime；
  v1 绝不解析 `session.v4.jsonl.zstd`）。
  `session.lock` 活性 = 文件存在且 dsh web 进程在跑 → 标「web 使用中」。
- 安全细节：回显实例名/会话标题前过滤控制字符（转义注入用例钉死）。
- 测试：门禁正/反路径、菜单渲染快照、注入用例、坏输入不崩、空列表文案。

实施注记：随 `2651387` 落地。类名 `MenuShellFactory`。取舍固化：① 服务端无 PTY，
开发者密码**回显**，提示行已标注「(输入将回显)」；② 每行一条菜单项（计划草图为
两行一条，纯排版差异）；③ 门禁审计每一次失败（不只第 3 次）；④ 未设置开发者密码
走退出码 0 + 明确文案；⑤ 控制字符过滤 `sanitize()` 在计划三类之外多剥 C1 区
（0x80–0x9F）；⑥ 会话目录两种拼法（裸 `<uuid>` 与旧式 `session-<uuid>`）并存都扫。

### M3｜TUI attach — 提交 `feat(ssh): …attach` ✅ 已实施

- `SpawnSupport`：进程内调 `DshLauncher` 链路拼 env（DSH_HOME/账户路由注入），
  cwd=实例 home，命令 `dsh tui [--resume <id>]`（brand 实例对应 CLI）。
  **无端口分配、无就绪行、无反代**——比 web 启动薄。
- 字节泵：channel↔进程 stdio 双向；pty request 的 TERM/COLUMNS/LINES 进子进程环境；
  WindowChange → `SIGWINCH`（v1 裸管道；v2 换 JPty 真 PTY，本期留接口不留实现）。
- 断开 → `ProcessTree` 整树清理；同 home 同时只允许一个 TUI attach（第二个拒绝并提示）。
- 测试：spawn env 断言（复用 fake-pnpm/fake-dsh 测试基建思路，做一个最小
  `dsh` 桩打印 env 即退）、resize 事件到达子进程、断连后无孤儿进程、
  用真实会话（如 session-60b3f47c）resume 标题对账。

实施注记：随 `5b5759e` 落地。接口名 `AttachSupport`，实现 `DshAttachSupport`。
取舍固化：① env 注入零拷贝复用 `DshLauncher.plan`（web launch 同一入口）+
  `InstanceRuntime.accountOf`，只换 surface（`tui [--resume]`）与 cwd（实例 home）；
  ② **品牌实例 attach 明确延后**——菜单选品牌回一行「后续版本提供」（各品牌 CLI
  差异大，硬套 `dsh tui` 拼装会产生错误命令）；③ resize **已接完整**（超出计划
  「v1 只留接口」）：MINA 服务端 window-change 经 `StandardEnvironment` 的
  WINCH 信号直达 `kill -WINCH <pid>`；④ 同 home 单 attach 由面板侧 ConcurrentHashMap
  把关（第二人得一行的拒绝文案）；⑤ 「resize 事件到达子进程」用例未单独做
  （信号路径即 MINA 既有机制），断连清树用 fake-dsh 的 sleep+子进程夹具实证。

### M4｜加固收尾 — 提交 `feat(ssh): …加固 + 文档` ✅ 已实施

- SSH 认证适配器按 IP 退避（口令登录开启时尤其必要）；空闲超时只挂门禁阶段
  （SSH 认证完成前 + TUI 门禁输密码前），attach 中的会话不设。
- Gradle `-PnoSsh` 变体：shadowJar 剔除 `web/ssh/**` 与 mina/netty（极简镜像硬保证）。
- `docs/ssh-terminal-channel.md` 威胁模型定稿；README/NOTICE 更新；
  平台矩阵（Linux ✓ / macOS 未实测 / Windows 不支持）写进 README 与体检文案。
- 全量回归 + CHANGELOG 草稿（**不发版**，等整批评审后随仓库节奏发布）。

实施注记：随本提交落地。取舍固化：① 门禁 120 秒空闲超时只在**门禁阶段**挂
（watchdog 定时器：关 channel/关流/中断三管齐下），attach 中的会话不设；
② 门禁跨连接退避复用 `LoginThrottle` 数据结构、策略在调用处——**一条耗尽
（错满 3 次）的连接 = 一条记录**，15 条记录（≈15 次重连 ×3 猜）换 10 分钟
per-IP 封禁；③ `-PnoSsh` 采用**反射方案**：`SshBridge`（web.server 包）按名
加载 ssh 类，缺类时日志一行「此构建不含 SSH 终端通道」并继续，/api/ssh/*
 不注册、体检行如实标注——「类隔离」方案因 DoctorApiServlet/组合根多处直接
引用被否；④ 连接数口径：网页 status 的 `connections` 来自 `SessionListener`
 的 sessionEstablished/sessionClosed 计数，M1 起即为真实值。

## 6. 风险登记

| 风险 | 缓解 |
|---|---|
| 开发者密码丢失 | 面板口令 + HTTPS 下重置（审计落「重置」事件）；重置会使已提权会话失效 |
| TUI 门禁被爆破 | 错 3 次即断开 + 按 IP 退避（跨连接，15 条耗尽记录换 10 分钟封禁）+ 门禁 120s 空闲超时 + 公钥前置（双因子）；审计留证 |
| macOS 行为未验证 | 标「未实测」不承诺；真机复验后转正（沿用 Windows 复验流程） |
| v1 裸管道下个别程序要求 tty ioctl | 已知取舍，已在菜单与类注释标注；v2 JPty。TERM/COLUMNS/LINES 已透传、SIGWINCH 已接，TUI 渲染正常 |
| dsh 自身锁比预期严格，resume「web 使用中」会话被拒 | v1 透传 dsh 报错，不替它仲裁（菜单以 `session.lock` 活性标「web 使用中」作前置提示） |
| MINA/netty CVE | dependabot 接入（已建议）+ **`-PnoSsh` 变体**（ssh 类与 MINA/eddsa 整体出包，反射桥缺类即降级）+ 回环默认 |
| `session.v4.jsonl.zstd` 格式随 dsh 升级变化 | v1 不解析主日志；sidecar 字段缺失时优雅降级为「显示 ID」 |
| 纯 HTTP 下用户找不到开关入口 | 入口禁用但**可见**，文案说清「需 HTTPS」并指路 TLS 设置页 |

## 7. 工时与顺序

M0 约 1 天 → M1 约 2 天（平台门 + 开发者密码 + 网页提权占大头）→ M2 约 1 天 →
M3 约 2 天 → M4 半天。
M0 是其他所有通道的公共地基（与后续 ACP 工具层共用），必须先落。
spike 验收线：M1+M2+M3 跑通 `ssh -p 3022 hdsl@nas -t` → 门禁输开发者密码 →
菜单 → attach `dsh tui` → 看到 DeepSeek 的 TUI 画面。
验收状态：容器内以 JVM 自连（MINA client 走真实 SSH 握手 + fake-dsh 夹具断言
env/argv/退出码/断连清树）全链路打穿；真机 ssh 客户端验收待部署到带 dsh 实例的
主机后走 §4 流程。
