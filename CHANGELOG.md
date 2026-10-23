# 更新日志

版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)；每条记录「改了什么」与「为什么」。

## v0.5.3 — 2026-10-23 · 移除 SSH 终端通道

SSH 通道整个拿掉，Web Shell 成为面板里唯一的 TUI 入口。这不是「再关一个开关」，而是把
协议栈、认证面和监听端口一起从二进制里删掉。

- **为什么删**：TUI 的需求（手机或终端里接回实例、恢复会话）已经由 Web Shell 覆盖；
  SSH 是面板里唯一自带协议 CVE 历史的组件，还叠着公钥/口令/开发者密码三套认证和一套
  自研门禁。留着它只有负担。
- **删了什么**：`org.jackhuang.hmcl.web.ssh` 整包（服务、门禁、菜单、公钥、会话目录）、
  `SshBridge`、`/api/ssh/*`、设置页「SSH 终端」整页、`HDSL_SSH_ENABLED` /
  `HDSL_SSH_BIND` / `HDSL_SSH_PORT`、`server.yaml` 的 `ssh:` 段、Apache MINA SSHD 与
  eddsa 依赖、`docker-compose.yml` 的 3022 端口映射、`-PwithSsh` 构建开关与
  `-full.jar` 产物。环境体检不再有 SSH 两行。
- **没变**：开发者密码与 30 分钟提权、Web Shell 的打开/回放/停止语义、实例级互斥、
  审计日志（web / terminal 两个通道）。
- **设置页换页**：「SSH 终端」整页改为「开发者模式」，进入同样需要开发者密码（30 分钟
  滑动）。页内有两个控件：Web Shell 总开关（写 `server.yaml` 的 `web_shell.enabled`，
  也可用 `HDSL_WEB_SHELL_ENABLED` 覆盖；关掉后终端按钮隐藏、接口返回 403
  `web_shell_disabled`，已运行的终端不受影响）和修改开发者密码（当前开发者密码 +
  面板密码确认，成功后所有提权立即失效）。
- **开发者模式与终端强制 HTTPS**：两者只在主连接器此刻讲 TLS 时可用。HTTP 下开发者模式
  直接弹「当前环境不安全」并指引到 HTTPS 设置，终端按钮隐藏、接口返回 403
  `insecure_transport`。没有证书时可以生成自签名证书（`POST /api/settings/tls/self-signed`，
  同端口热切换，浏览器会告警）；「HTTPS 证书」页也加了红框说明和同一个按钮，已配置真实
  证书时后端拒绝回退到自签名。
- **反代终结 TLS 也认**：`https.trust_forwarded_proto`（`HDSL_TRUST_FORWARDED_PROTO`，
  默认关）打开后，`X-Forwarded-Proto: https` 即视为 HTTPS，开发者模式、终端和 `Secure`
  会话 Cookie 在 nginx/Caddy 后面照常工作。默认关闭是因为直接连到 HTTP 端口的客户端可以
  伪造该头；这种部署下生成自签名证书会被 409 `tls_terminated_upstream` 拒绝。
- **升级说明**：旧 `server.yaml` 里的 `ssh:` 段会被忽略并在启动日志里打一条
  `unknown key` 警告，不需要手工删除；3022 不再监听；以前下载的 `-full.jar` 不再产出，
  从源码构建也只需要 `./gradlew build`。

## v0.5.21 — 2026-10-23 · 终端空白屏与连接稳定性

- **opencode 终端永远空白**：xterm 6.0.0 的 `requestMode` 在 esbuild 压缩后把
  `return f=…, p=…; var f,p` 改坏，DECRQM（`CSI ? Ps $ p`）抛 `ReferenceError`，
  xterm 的写入队列停摆——WS 已 attach、字节也到了浏览器，画面却不再解析。构建改为
  `minify: false`（体积 +约 70 KB gzip），供应商代码原样执行。
- **终端窗口反复重连**：父组件每次渲染都传入新的 `onClose`，挂载 effect 的依赖因此
  不断变化；改用 ref 兜住回调，并给每次 `openSession` 加 generation 丢弃过期结果。
- 其余可见性修复：WS 打开后补发真实尺寸（PTY 不再停在 80×24）、WS 错误与异常关闭
  不再静默、标题栏显示「已接收 N KB」、Blob 帧兜底；后端补 attach/发送失败日志。

## v0.5.2 — 2026-10-22 · Web Shell：实例级原地终端

实例上的「终端」按钮现在就地打开该实例的原生 TUI（`kimi` / `opencode` / `dsh tui`），
走现有 WebSocket，不新增端口、不引入 SSH 协议栈。终端以实例为单位启动：同一份 home、
账号与模型路由，TUI 里的会话记录与网页模式一致。

- **终端核心**：常置包 `web/terminal`（`TerminalTarget`、`BrandTui` + `KimiTui` /
  `OpencodeTui` / `DshTui`、`TerminalSession`、`TerminalManager`）。PTY 用 JNA 自写
  （`openpty` + `posix_spawn`，不 fork、无 JNA 回调），每个终端一个进程组；子进程退出后
  先关 master 再杀进程组，屏幕不可能再出现新内容。
- **会话**：关窗/刷新只 detach，agent 继续跑；重开接回同一会话并回放最近 256 KB；
  `/exit`、底栏「停止」、实例停止才杀进程树。同一实例单会话。
- **授权**：打开终端需要会话 + 开发者密码提权（15 分钟滑动）；Windows 桌面启动额外要求
  `{"confirm":"open-desktop-terminal"}` 并记审计。入口等价于 RCE，威胁模型见
  [docs/web-shell.md §4.1](docs/web-shell.md)。
- **Windows**：不做 ConPTY，直接在桌面会话里开控制台；`reveal`（home/workspace/log）用
  `explorer` / `notepad`，路径由服务端按实例记录计算，只接受三个目标名。面板以服务运行
  时没有交互桌面，桌面功能返回 409。
- **构建**：SSH 改为 opt-in——默认 jar 不含 `web/ssh`、MINA 与 eddsa，`-PwithSsh` 才带上；
  日常 TUI 用 Web Shell。
- **前端**：xterm 覆盖层（桌面）/全屏（手机）、底栏启停、断线提示与重连；`compat` 闸门
  给 es-check 加 `--module`（动态 import 产生的 chunk 是 ES module）。
- 验证：**725 用例 / 0 失败 / 2 跳过**（JDK 21 与 17，默认构建）；默认 jar `web/ssh=0`、
  `web/terminal=28`、MINA=0；`-PwithSsh` jar `web/ssh=21`、MINA=1173；前端
  typecheck / build / compat 通过。

## v0.5.1 — 2026-10-22 · SSH 门禁与提权加固（v0.5.0 审阅 P1）

- **门禁每次错答都计数**：此前只有「一条连接错满 3 次」才给节流记一条，客户端错 1–2 次就
  挂断可以无限重连白拿猜测，15 条的封禁永远攒不满。现在每次错答进同一个全局预算：15 次
  花光后，第 16 次起门禁封 30 分钟，且**换 IP 也一样**（预算是单个全局键）；同时把触发者
  的地址加入 SSH 登录拒绝名单 30 分钟——公钥、口令都不放行，审计 `ssh.auth-blocked`。
- **提权按会话而不是用户名**：`elevations` 的键改成会话 token，登出/撤销会话即失效，
  每次敏感调用还会确认 token 仍存活。此前同一账号的任意会话共享提权，登出也不清，偷到
  任意网页会话的人只要真用户在 15 分钟内提过权，就能靠一次合法提权无限续期。
- **开发者密码的二次确认接上预算**：`/api/ssh/dev-password` 与 `.../clear` 用面板口令
  二次确认时走 per-IP `LoginThrottle`（15 次失败后拒 10 分钟，429 + `Retry-After`）。
  此前这条路对面板口令是没有预算的 oracle，每次还白烧一次 PBKDF2。
- 验证：全量 **698 用例 / 0 失败 / 0 错误 / 2 跳过**（JDK 21 与 17 各一遍）。新增 4 条
  单测（单答挂断仍计数、封禁对换 IP 生效、提权不跨会话、二次确认锁定）与 1 条真 SSH
  集成测试（花光预算后，同一地址用有效公钥也登录不上）。

## v0.5.0 — 2026-10-22 · SSH 终端通道（M0–M4）

`ssh -p 3022 hdsl@主机` → TUI 门禁（独立开发者密码）→ 编号菜单（dsh 实例 / 品牌实例 /
恢复 dsh 会话）→ 选定项的 `dsh tui [--resume]` 终端直接接管当前 SSH 会话。该通道已在
v0.5.3 整体移除（原因是 Web Shell 已覆盖同一需求，且 SSH 自带协议 CVE 面）。

- **审计设施（M0）**：append-only JSONL 审计日志（`/data/logs/audit.jsonl`，0600、按日轮转），
  事件 `{ts, actor, channel, action, object, outcome, ip}`；登录/实例/TLS 等既有操作全部接线。
  server.yaml 写入收紧 0600。
- **SSH 服务骨架（M1）**：嵌入式 Apache MINA SSHD；`ssh.enabled` 默认关（不开关 = 无监听
  端口，有集成测试钉死）；Ed25519 host key 首启生成、持久化、重启不换 fingerprint；
  平台门（Linux ✓ / macOS 放行标未实测 / Windows 三层封禁）；开发者密码（PBKDF2 0600 独立
  存储，网页开发者模式提权 + TUI 门禁双角色）；authorized_keys 管理（粘贴上传/服务端生成
  密钥对、私钥一次性展示永不落盘）；HTTPS 前置守卫 + 15 分钟滑动提权；口令登录开关
  （默认关、开带警告、热应用）。
- **TUI 门禁 + 编号菜单（M2）**：SSH 认证后再输开发者密码（错 3 次断开+审计）；三区分区
  菜单全局连续编号；「恢复 dsh 会话」只读 sidecar JSON 拿标题、按 `session.lock` 活性标
  「web 使用中」，不解压 zstd 主日志；实例名/会话标题回显前过 `sanitize()` 控制字符过滤
  （转义注入用例钉死）。服务端无 PTY，密码回显已在提示行标注。
- **TUI attach（M3）**：菜单选定后 spawn `dsh tui [--resume]`——env 注入零拷贝复用
  web 启动链路（`DshLauncher.plan`：账户路由、provider key、实例 Node 运行时、DSH_HOME），
  cwd=实例 home；双向字节泵（虚拟线程），channel 断开 `ProcessTree` 杀整棵进程树；
  TERM/COLUMNS/LINES 透传、window-change 转 SIGWINCH；同 home 单 attach；品牌实例
  attach 明确延后（回一行说明）；未安装实例友好报错。
- **加固收尾（M4）**：门禁阶段 120 秒空闲超时（审计 `ssh.idle-timeout`）；门禁失败按
  per-IP 跨连接退避（一条耗尽连接 = 一条节流记录，15 条换 10 分钟封禁）；依赖修复
  `net.i2p.crypto:eddsa`（sshd-common 的 optional 依赖不传递，缺它 Ed25519 运行期必炸）
  与 host key 的 i2p provider 对齐（重载路径钉死 sshd 自家解析器，防 BC 解析换 key）；
  `-PnoSsh` 构建变体（shadowJar 剔除 ssh 类与 MINA/eddsa，`SshBridge` 反射加载、缺类
  即降级为「无 SSH 能力」并明示）。
- 修复（同分支早前提交顺带，容器内无 JDK、从未被编译器核过）：M0 权限迁移段里 Main
  漏掉的 `IOException` import；SshService/SshApiServlet 漏 catch 的受检异常（`loadKeys` 的
  `GeneralSecurityException`、`getInstance` 的 `NoSuchAlgorithmException`）、`audit()` 少传
  实参、POST 分支把 `switch` 语句直接挂在 `->` 后面。另外两处（`ApiGuardFilter` 的 rethrow
  越界、`HdslServer` 缺 import）在 v0.4.8 已修并发过版，与本分支无关。
- 验证：全量 **694 用例 / 0 失败 / 0 错误 / 2 跳过**（JDK 21 与 17 各跑一遍）；前端
  typecheck / build / compat 三道闸门通过；`-PnoSsh` 的 jar 里 SSH 类与 MINA/eddsa 均为 0，
  起服务 `/` 回 200 且日志明示「此构建不含 SSH 终端通道」。合并时另修两处：attach 的字节泵
  改走 `VirtualThreads.start`（原 `Thread.startVirtualThread` 是 21 API，`--release 17` 下
  编译不过）；`-PnoSsh` 不带值时被读成 false，文档里的命令实际会打出全量 jar。

## v0.4.9 — 2026-10-21 · 前端健壮性与动画体验

- **ErrorBoundary**：任何子组件的渲染/生命周期崩溃都被拦成可恢复的错误页
  （出错信息 + 刷新按钮），不再整棵 React 树卸载白屏。已登录区（Outlet）与
  登录/初始化页各挂一道。
- **旧页退出动画**（ui-spec §11 补票）：路由切换走 View Transitions API——
  旧页整页快照 100ms 淡出 + 左移 30px，新页沿用 page-in 入场。不认识该 API 的
  浏览器（Firefox 115 在列）自动维持原状，只有新页入场。
- **断网提示条**：浏览器网络断开时贴顶显示红色提示（`offline-banner`），
  恢复即消失。检测的是浏览器网络状态；面板服务器不可达仍由各请求报错承担。

## v0.4.8 — 2026-10-21 · API 契约与安全小修一批

- **未捕获异常不再撕毁 API 契约**：`/api/*` 新增 `ApiGuardFilter`，servlet 漏出的
  任何异常（比如一个 NPE）统一回 `{"error":"internal error"}` JSON 500——此前 Jetty
  的默认 HTML 错误页会让前端拿到不符合契约的响应。已提交的响应无法重写，照旧上抛。
- **`server.yaml` 收紧为 0600**：写入时 staging 文件先设 owner-only 权限再原子移动。
  该文件里躺着 PKCS#12 密码，此前是 umask 默认的 0644——同目录其他敏感文件全是
  0600，唯独它 world-readable。
- **任务历史有界**：`TaskService` 终态任务（done/failed）最多保留 400 条，超出按
  提交顺序淘汰最旧；运行中与等待审批的任务永不淘汰。此前任务表随运行时长只增不减，
  长跑的面板内存缓慢上涨。
- **插件工具栏按钮文案与行为对齐**：「打开配置文件夹」按钮实际跳转插件市场下载页，
  文案改为「去插件市场」（`dsh.instance.plugins.to_market`）。

## v0.4.7 — 2026-10-20 · 上游 P0 移植 + 网页版差距首批 + 登录防爆破

上游 HDSL 分叉后把包名改成 `com.mcxcc303.hdsl.*` 又把单体类拆成主题包，cherry-pick 的路径
已经断了；这一批全部是**逐笔手工移植**。类名对应表、每月同步流程、已移植与不移植的清单见
[docs/upstream-sync.md](docs/upstream-sync.md)。

- **解压越界（安全）**：归档条目名整条预检——含 `..` 段、`\` 或 NUL 的条目不进解压树
  （`03d9f34`，只记一条 warning，归档其余部分照常解压）；插件包成员 normalize 后必须仍在插件
  目录内，否则记录条目名并让整步失败（`43401ca`）；Node 运行时解压里的软链接只在「非绝对路径
  且解析后仍落在 root 之内」时重建（同笔）——`bin/npm -> /etc/passwd` 这种链接一旦建出来，
  之后每次读它都等于把那个文件读进来。
- **设置文件不再被截断**（`8760af8`）：写进同目录临时文件 → 收紧权限 → `ATOMIC_MOVE` 顶替，
  不支持原子移动的文件系统降级为普通 move。此前是先截断再写，读到中间态就是「没有账号、没有
  API Key」。
- **实例目录四处**：改名不再删除占用新名称的目录（`8062b6e`）；创建不再认养已存在的目录
  （`66415a6`，web 侧 `uniqueId` 配套改成「目录存在也算占用」，否则删除中断留下的目录会让创建
  从「自动加后缀」变成失败）；运行中/停止中的实例拒绝改名（`4842f45`）；复制到自身被拒绝
  （`e3ec345`）。
- **整合包导出**：不再带走 `link:`／目录安装的插件（`08e72c3`＋`fdc8349`＋`ad517a1`）；导出前
  先把这类插件逐个点名（stage 行直接显示在导出弹窗里），拒绝消息也改成说清原因。
- **插件适配判定对齐 npm 的 semver**（`9b47a36`＋`8b56869`）：`workspace:^|~|*`、连字符区间、
  `~>`、运算符与版本之间的空白、`v` 前缀与 `+build` 不参与比较、`includePrerelease` 语义，以及
  harness 自身版本补进依赖表。判定用 node-semver 自己的语料钉住（167 条用例 + 62 条「按设计
  不同」的清单，两个方向都钉）。
- **安装要求 pnpm 10.9**（`e146c5b`）：门槛只落在 dsh harness 的安装路径与体检显示上。品牌区
  与 dsh 共用 `DshNodeRuntime`，所以门槛**不进** `canManagePlugins()`——品牌安装行为不变。
- **命令有界运行**（`8d17e2e`）：`DshCommand` 新增带期限的 `run`、强杀整棵进程树的 `killTree`
  与 `Result.timedOut`。本项目今天没有需要它的调用点（上游的动机是桌面版新增的端口占用探测），
  所以这一笔只落能力与测试。
- **登录防爆破**：同一来源地址五分钟内 15 次失败之后，第 16 次起拒绝十分钟（响应带 `Retry-After`），
  期间口令正确也一样进不来，一次成功即清零。预算没花完之前页面上不提示任何计数，只有真的被挡住时
  才弹出来（表单里留一条 + 一条会消失的提示）。拒绝发生在核对口令之前——核对口令本身就是攻击者想
  花掉的开销。时间由调用方传入，所以窗口边缘、拒绝的最后一秒都在单元测试里钉住，不必等十分钟。
- 验证：**636 用例 / 0 失败 / 0 错误 / 1 跳过**（跳过的是 Windows 专有的 junction 用例）；前端
  `typecheck` / `build` / `compat` 三道闸门通过；品牌区与 `/i/*` 反代目录未改动（diff 为空），
  也不引用本批改过的领域类。

### 网页版与桌面版的差距（首批）

- **整合包安装的进度弹窗加取消**：此前「进行中」分支只有进度条和一行文字，而弹窗在安装期间
  拒绝关闭——点下安装就只能看着，选错了版本也没有退路。现在接 `POST /api/tasks/{id}/cancel`
  （实例详情页与主页早就在用），取消之后显示「安装已取消」，而不是把后端那句英文 `cancelled`
  当故障报出来。
- **版本行的上游发布页按钮**：桌面版版本行上的那颗地球（`DownloadPage.java:423`）。地址形状与
  桌面版一致（`…/releases/tag/dsh-v<版本>`）；列出来的版本来自 npm，上游若没打过对应的 tag
  会落到 GitHub 404，要换成 npm 页面就改 `web/src/constants.ts` 一处。
- **实例名称输入加清除钮与空名提示**：此前要点「创建」之后才 toast 一句「名称不能为空」。
  桌面版那两个对话框（非法字符、重名）没有照搬——实例目录是后端从名字派生的 id，`uniqueId`
  会净化非法字符并在重名时自动加后缀，这里没有对应的失败模式。
- **窄屏标签条的横向滚动**：手机上滑不动、桌面 DevTools 窄窗里用鼠标拖也滑不动。实测这条栏本身是
  可滚的（`scrollWidth 708` 对 `clientWidth 378`），问题在手势归属与「鼠标没有横向平移」：加
  `touch-action: pan-x` + `overscroll-behavior-x: contain`，去掉 iOS 13 之前那条
  `-webkit-overflow-scrolling: touch`（已被移除、留着会和原生惯性滚动打架），并给鼠标加了拖动平移
  （只接管鼠标、只在窄屏；且只有真的滚动了才吃掉那次点击，手抖几像素的正常点击照样点得开）。
- **开发服务器上的两级深链**：`npm run dev` 下打开或刷新 `/instances/<id>`、`/instances/new` 会整页
  被错误边界接住（响应是 `200 /instances/api/auth/whoami`——相对路径按当前地址解析，落到 vite 的
  SPA 兜底页）。模板补一条根挂载的 `<base href>`；服务端注入的那条仍排在它前面，`/panel` 子路径
  挂载不受影响。
- 这几条都过过真东西：标签条用 playwright 在 390×844 下真触摸与鼠标各拖一次（`scrollLeft` 0→330 /
  0→308）、点标签仍能切换；移动端信息架构跑了仓库自带的 `scripts/mobile-acceptance.py`（11 条 PASS，
  剩 1 条需要存在一个品牌实例）；防爆破在临时面板上用真 HTTP 与真浏览器验过（第 16 次 429 +
  `Retry-After: 600`，被拒期间对的口令也 429，页面上两条提示）。**仍未在真机上点过**——这台机器没有
  iOS / Android 设备，也没有浏览器可以直接点。

## v0.4.6 — 2026-10-19 · 最低运行时降到 JDK 17

- **最低运行时降到 JDK 17**：jar 用 `--release 17` 编译（class 61），OpenJDK 17 可以直接
  `java -jar` 启动；21+ 照常。为此补了三处兼容：
  - 虚拟线程（`Executors.newVirtualThreadPerTaskExecutor`，4 处）收进
    `util/platform/VirtualThreads`：21+ 反射用虚拟线程，17 回退 cached daemon 线程池；
  - `HttpClient` 的显式关闭（7 处）收进 `util/io/HttpClients`：21+ 反射调用 `close()`，
    17 上 no-op（连接池由 GC 回收）；
  - `List.reversed()`（1 处）改为反向下标循环。
- **CI 加 JDK 17 矩阵**：build 任务在 17 与 21 上各跑一遍 SPA 构建、兼容闸门与全量测试。
- 验证时顺手修掉一个构建状态竞态：`ZcodeBuilder` 原先在清理 scratch 目录**之前**就把状态
  置为 DONE，测试（以及任何看到 DONE 立刻读目录的调用方）会和删除动作抢时间；现在清理
  完成后才发布 DONE。
- 镜像仍内置 OpenJDK 21（17 字节码在 21 上照常跑），Dockerfile 不变；README 的
  「JDK 21+」改为「JDK 17+」。

## v0.4.5 — 2026-10-19 · 浏览器兼容底线：Firefox 115 ESR（Win7）

- **声明兼容底线**（`web/package.json` 的 `browserslist`）：Firefox 115、Chrome/Edge 109、
  Safari/iOS 15.6——Windows 7/8.1 上能用的最后一条浏览器线。`npm run typecheck` 并入
  SPA 构建。
- **CI 兼容闸门**（`build.yml` 的 Compatibility gate / `npm run compat`）：
  `es-check es2020` 钉住产物 JS 语法；`web/scripts/compat-check.mjs` 扫描产物 CSS，
  把 `:has()`（FF121+）、`text-wrap`（FF121+）、`[popover]`（FF125+）这些
  Firefox 115 不支持、Vite/esbuild 又不转译的特性挡在构建前。
- **复核修正**：最初的排查稿写成「`env()` Firefox 136 才支持、115 会整条作废」，并据此
  给 `mobile.css` 加了 5 处常量兜底和一条 env() 配对闸门。MDN browser-compat-data 证实
  `env()` 与 `safe-area-inset-*` 自 **Firefox 65 / Chrome 69 / Safari 11** 起支持——115
  不是缺口。兜底声明与配对闸门已撤掉，文档改为真实口径。
- **责任边界**（`docs/compatibility.md`）：底线只覆盖面板自身；`/i/<id>/` 反代里的
  dsh / Kimi / OpenCode / Claude Web / ZCode 页面是上游产物，兼容性归上游。

## v0.4.4 — 2026-10-18 · ZCode 在 Windows 上能一键构建了

- **ZCode 面板内构建在 Windows 上跑通（v3.14.3）**：缺的不是编译器，而是三样非编译器的东西——
  Node 24+、pnpm、以及一个能正确处理 `C:\` 的 `tar`。构建前由面板自动准备：优先镜像
  `/opt/node24`，其次托管运行时，缺失则装进 `<data>/hdsl/runtimes`（与设置页同一个目录）；
  运行时没有 pnpm 就用它的 npm 装进去；Windows 写 `<data>/zcode/build/bin/tar.bat` 指向
  `System32\tar.exe` 并前置 `PATH`（Git 的 GNU tar 会把盘符当远程主机）。
- **Windows 上 pnpm 改 `node-linker=hoisted`**（品牌安装与 ZCode 构建）：isolated 布局下
  kimi-code 启动即 `ERR_MODULE_NOT_FOUND(ws)`、opencode 的 postinstall 拿不到平台包。
  POSIX 仍用 isolated。
- **清理 pnpm 树**：Windows 用 `robocopy /MIR /XJ`（`rmdir /s` 在 2 GB 树上十分钟未完），
  junction 目标消失不再让构建失败；`current` 指针按链接/文件分别删除，绝不走进它指向的发行包。
  删除不干净时明确失败，不把旧树留给下一次安装。
- **品牌安装日志**此前从未写入 `install.log`，改为逐行落盘并 flush。
- 复核时补的护栏：`samePath` 改用 `Files.isSameFile`（Windows 的大小写/8.3 短名/`\\?\`
  前缀不再影响「当前版本」判定）；构建工具链在日志里写明来源；并发安装 Node 的竞态转化为复用。
- 测试 **584 / 0 失败 / 0 错误 / 0 跳过**（新增 3 条：文本指针的 current 判定、清理指针不碰发行包 ×2）。
- **真机复验（2026-10-18）**：同一台 Windows 10 上从空数据目录连跑两次（全新 + 重建）都成功；
  Node 26.12.0 与 pnpm 自动装进 `<data>/hdsl/runtimes`，`tar.bat` shim 生效，实例反代 200。
  `cpu-features`（ssh2 的可选依赖）在没有 MSVC 时会报 `Unable to detect compiler type`，
  pnpm 按可选依赖继续——**不需要编译器即可构建**；`node-pty` 用预编译。详见
  `docs/report-2026-10-18-zcode-windows-build.md` §8.1。
- **编译链预检**：Windows 构建前用 `vswhere` 找 `cl.exe`；没有就明确说明
  `cpu-features` 是可选依赖、会被跳过，并给出最小 VS Build Tools 命令。面板不自动下载
  编译器（需管理员 + 2.5–3 GB），也不在构建里弹 UAC。

## v0.4.3 — 2026-10-18 · 容器镜像加 `linux/arm64`

- **容器镜像加 `linux/arm64`**：CI 现在产 `linux/amd64` + `linux/arm64` 双架构 manifest，
  `docker pull` 会按机器自动选择。Dockerfile 的 web/build 两个阶段钉在 `$BUILDPLATFORM`
  （产物与架构无关），只有 runtime 阶段走 QEMU 目标架构；Dockerfile 本身早已按
  `dpkg --print-architecture` 选 Node tarball，这次只是把发布侧补齐。
- jar 与 v0.4.2 相同，没有代码改动；本版只动交付（CI、镜像、文档）。

## v0.4.2 — 2026-10-17 · Windows 裸部署：从「文档说可以」到「真能跑」

> 起因：`docs/windows-bare-deploy.md`（同日只读排查）证明 README/CHANGELOG 把 Windows
> 裸部署当「已加固」，而代码里**没有任何一处**针对 Windows 的平台分支——v0.3.6 那份实测
> 只覆盖「清空 PATH 的容器内」。本版在真 Windows 10（JDK 21 + Node 24）上逐层补齐，
> 并按排查稿的 §6.1 落单元测试（`NodeArtifactNamingTest` / `RuntimeLayoutTest` /
> `ExecutablesTest` / `DshCustomCommandsTest` / `DshVersionManagerRollbackTest`），
> §6.2 的真机清单逐条跑过。

### 为什么：真机基线（不是推测）

- 在那台机器上跑全量测试：**559 用例 / 31 失败**（文档基线是容器内 527/0/0）。31 个失败
  归到 7 个根因，其中 **15 个出自同一个**：`DshNodeRuntime.which()` 按精确名字找文件，
  而 Windows 上 `node` 是 `node.exe`、`npm` 是 `npm.cmd`——一个都找不到，于是面板判定
  「本机没有 Node」，把用户推向那个坏掉的托管运行时，**连手动装官方 Node 都白装**。
- **项目自己的构建在 Windows 上就跑不起来**：`build.gradle.kts` 用
  `File(it, "npm").canExecute()` 判断 npm 可用——Node 安装包同时放了无扩展名的 `npm`
  （POSIX 脚本，文件存在所以判断为真）和 `npm.cmd`，紧接着 `Exec("npm")` 必然
  `CreateProcess error=2`，`./gradlew test` 直接 BUILD FAILED。
- **`.cmd` 不需要 `cmd /c` 包壳**（推翻了排查稿 §4.3 的建议，实测 JDK 21 + Windows 10）：
  裸 `npm` → error 2；**裸 `npm.cmd` 与全路径 `npm.cmd` 都能跑**；无扩展名的全路径 `npm`
  → error 193。正确的修法是**把扩展名补对**，不是套 `cmd.exe /c`——后者会引入参数二次
  转义（那正是 `CommandBuilder.toBatchStringLiteral` 存在的理由），前者参数原样透传。

### 改了什么

- **L4 派生解析**（新增 `util/platform/Executables`）：`resolve()` 按 `PATH` + `PATHEXT`
  补全可执行文件——Windows 上优先 `.cmd`，无扩展名的文件退到最后（那是一份 POSIX 脚本，
  `CreateProcess` 会以「不是有效的 Win32 应用程序」拒绝）；`startable()` 把 argv[0] 补成
  能启动的形式、参数一字不动。接入 `DshCommand.run`（所有 npm/node/pnpm 的唯一漏斗）、
  `BrandInstaller`、`ZcodeBuilder`、`ZcodeRuntime`、`Main.toolVersion`、`SystemUtils`、
  `DshSessions`、`NodeRuntimeManager`；`DshNodeRuntime.which()` / `SystemUtils.which()` 改走它。
  品牌实例的入口（`node_modules/.bin/<tool>`）与 `tsx` 同样补全到 `.cmd`。
- **L3 运行时布局**（新增 `dsh/RuntimeLayout`）：POSIX（`bin/node`、`bin/npm`、`bin/` 进
  `PATH`）对 Windows（根目录 `node.exe`/`npm.cmd`/`npx.cmd`/`pnpm.cmd`，根目录进 `PATH`）。
  `NodeRuntime`、`DshNodeRuntime.fromManaged`、`NodeRuntimeManager`、`NodeApiServlet`
  全部改走它。可执行判定按平台分开：**POSIX 仍然要求执行位、答案仍然是 realpath**
  （`Executables.startableAmong`，`node_modules/.bin/tsx` 这类软链照旧解析到目标）——这一半
  不因为 Windows 适配而改变；Windows 上 NTFS 没有执行位，改按扩展名 + `PATHEXT` 补全，
  并且**不套 `cmd /c`**（`CreateProcess` 会自己把点名的批处理转给 `cmd.exe`，套壳反而多一层
  转义；实测见下）。
- **L2 发行物解析**：`platformTag` / `archiveExtension` / `indexTag` 补 `win-x64` / `win-arm64`、
  `.zip`、`win-x64-zip`，并改成**接收 `Platform` 参数的纯函数**——于是 Windows 的产物名
  在任意机器上都能被测试钉住，不需要 Windows CI；解包加 zip 分支（Windows 包没有软链，
  但加条目越界拒绝）。
- **L5 文件系统语义**（新增 `util/io/Links`）：探测符号链接能力（试建一次临时链接，结果
  缓存），不可用时按用途降级——解包与复制里的软链（`bin/npm`）**改成复制**；`current`
  这类**指针改成写一个文本指针文件**，读侧两种形式都认，所以同一目录从一台机器拷到
  另一台仍然可读。`ATOMIC_MOVE` 补普通 move 兜底（跨卷时 Windows 直接拒绝）；
  删除/改名加短暂重试（杀软、索引器、刚退出的子进程都会占用几毫秒，一次失败就报
  「安装失败」太重）——重试落在共享的 `FileUtils.deleteQuietly` 上，实例安装清旗标与
  运行时安装清暂存目录用的是同一份，不再一个有重试、一个没有。
- **实例安装不再「先暂存再搬」**（真机实测挖出的 Windows 专属致命项）：原来 pnpm 在
  `dsh.installing/` 里装完，再把整棵树 `Files.move` 到 `dsh/`。POSIX 上 pnpm 写的是**相对**
  软链，搬了也没事；Windows 上 pnpm 用的是 **junction**，其目标**必须是绝对路径**——于是搬过去
  的树里每一条链接都还指着刚被删掉的 `dsh.installing/`，`package.json` 读不出来，面板报
  「pnpm reported success, but the installed version could not be read back」。现在 pnpm 直接写在
  实例最终要用的路径上，`dsh.installing` 改为「还在写」的旗标（`isInstalled()` 见到它就答「没装」，
  `discardPartial()` 会连同半成品目录一起清掉），中断的安装仍然不会被误当成可用版本。
  **就地安装不能把「失败不毁旧版」这条保证丢掉**（旧流程靠暂存目录天然做到，且这一条对所有
  平台都重要）：安装前把已有的树**挪到 `dsh.previous`**，pnpm 失败或产物不全时删掉半成品、
  **把旧树挪回原路径**（Windows 的 junction 是绝对目标，只有回到原路径才重新有效），
  发布成功才删备份。`DshVersionManagerRollbackTest` 钉住四种状态（失败/中断/已发布/无备份）。
- **L6 路径与环境**：`ZcodeBuilder` 硬编码的 `":"` → `File.pathSeparator`；
  `DshCustomCommands` 的 `/bin/sh -c` → Windows `cmd /c`（启动前命令失败会**阻止实例启动**，
  这条在 Windows 上等于每个带命令的实例都起不来）；`DshInstanceTerminal` 生成的 bash 脚本里
  `File.pathSeparator` → 字面 `:`（bash 到哪都是 `:`，用宿主分隔符会写出一个只含一个
  超长目录的 `PATH`）；`BrandRuntime` 在 Windows 同时设 `USERPROFILE`——Node 认它不认
  `HOME`，否则实例隔离静默失效、品牌凭据落进真实用户目录。
- **L7 声明层**：`DshDoctor` 加 Windows 专检（符号链接能力、启动器用户目录长度、控制台编码、
  实例终端不可用），并把 inotify 那一段在 Windows 上标为「不适用」；启动横幅加一条终端
  不可用的提示；实例终端在 Windows 上抛**说明为什么**的错并给出三个可用路径
  （工作目录、`DSH_HOME`、运行时 `bin`），而不是「找不到终端模拟器」——那对一台装着
  Windows Terminal 的机器既真实又无用。
- **测试夹具平台化**：两个 fake `pnpm` 改成「一份 `pnpm.mjs` + 每平台一行包装」
  （Windows 那边**必须**有 `pnpm.cmd`，否则面板的可执行文件解析会跳过去、找到真正的 pnpm，
  测试就会去打网络——这正是这个夹具要防的事）；5 处 `Files.getPosixFilePermissions`
  断言收进 `PosixPermissions`（POSIX 上仍然断 0600，Windows 上该断言不适用而不是
  把整个用例跳过）；`DshAcpClientTest` 硬编码的 `/tmp` → 系统临时目录。

### 真机复验（2026-10-17，v0.4.2 候选 jar）

在那台 Windows 10 上把 jar 从零跑了一遍——不是单元测试，是 `java -jar` 裸部署：

- 面板：首启建号、登录、`/api/doctor` 全部通过；横幅 `runtime: node v24.22.0, pnpm 11.28.6`
  （pnpm 不再误报 MISSING）；
- 托管运行时：`POST /api/node/install` 装出 **win-x64 26.12.0**——根目录 `node.exe`/`pnpm.cmd`、
  没有 `bin/`，`node -v` 与 `pnpm -v` 都能跑；
- dsh：`0.2.1-rc.1` 的 626 个包**就地**装进实例目录（`dsh.installing` 只是旗标、装完即消失），
  启动后经 `/i/<id>/?token=…` 反代 **200**、停止后端口无监听；
- 品牌：opencode 1.19.3 的装、启、反代（`<title>OpenCode</title>`）全通；停止后状态停在
  `stopped`、端口释放、`opencode.exe` 无残留（这一条见下）；
- **中文 + 空格**数据目录（`C:\Users\timmy\hdsl-测试 空格`）：装、启、反代同样通过；
- HTTPS：自签证书打开后，裸 IP 同端口 `http://192.168.1.11:3080/` 答 **308** →
  `https://192.168.1.11:3080/`；
- 实例自定义命令：启动前命令经 `cmd /c` 执行，工作目录里的 `pre-win.txt` 写入成功。
- 未复跑：ZCode 面板内构建（耗时长）、kimi 品牌（需要账号）。

### 停止实例必须带走整棵进程树

真机复验挖出的最实在的一个问题：opencode 的 `stop()` 只 `destroy()` 了面板持有的那个
`Process`，真正占着端口的是它派生出来的 `opencode.exe`——面板报 `stopped`，端口却还在
LISTENING，再次启动就撞「port already in use」。新增 `util/ProcessTree`：**先**收集后代
（父进程一死，子进程就被重新挂到系统进程之下，`descendants()` 再也找不到它们），再按
「先礼后兵」的顺序终止；只杀父进程的写法在 Linux 上同样会留下 npm/tsx 的 shim 子进程。
`BrandRuntime.stop()` 与 `ZcodeRuntime.stop()` 都改走它。`STOPPING` 旗标改由输出泵消费，
不再由 `stop()` 提前移除——否则「请求停止」和「泵读到 EOF」会竞争同一个答案，把主动停止
误报成「进程意外退出」。真机复验：停止后 `listeners=0`、`opencode=0`、状态保持 `stopped`。

启动日志也不再为 Windows 上必然发生的 `setPosixFilePermissions` 打一条带堆栈的 WARNING
（横幅已经写明 NTFS 没有 0600 语义，warning 只会像故障）。

### pnpm 配置：实测修正了排查稿的猜测

排查稿写「Windows 的 pnpm 读 `%LOCALAPPDATA%\pnpm\config\rc`」，并标注「需实测确认」。
在 Windows 10 + pnpm 11.28.6 上实测（`pnpm config set` 之后看哪个文件变了）：

| 内容 | pnpm 真正读的位置 |
|---|---|
| registry | `%LOCALAPPDATA%\pnpm\config\auth.ini`，形式 `registry=<地址>` |
| 其余（`storeDir`） | `%LOCALAPPDATA%\pnpm\config\config.yaml` |

`%LOCALAPPDATA%\pnpm\config\rc` 与 `~/.config/pnpm/config.yaml` **都不读**——面板写进
后者的国内镜像因此被静默忽略、一路打到 npmjs.org。`PnpmConfigFile` 现在按平台分派：
POSIX 仍是 `$XDG_CONFIG_HOME/pnpm/config.yaml`（YAML、值带引号），Windows 是两个文件
（INI 给 registry、YAML 给 storeDir），语法由文件名决定。

### 仍然不支持（明确声明，不再只是报错）

- **实例终端**：它开的是 POSIX shell、走的是 Linux 终端模拟器列表，两端在 Windows 上
  都不存在。现在抛的错会说清原因并给出可以照抄的替代路径。要真做就是接 `wt`/`powershell`。
- **`flock` 会话租约**：util-linux 的命令，Windows 没有；面板在 Windows 上直接答
  「观察不到」而不是每次去 spawn 一个必然失败的进程。
- POSIX `0600` 语义（旧限制，未变）：NTFS 无 umask，凭据文件可能被同机其他账户读到。


## v0.4.1 — 2026-10-15 · 开山：品牌实例在实例列表页内原地成层，实例成为一等对象

> 版本号：原计划的 **v0.4 不单独发 release**——本版把「开山」与随后的访问宽容化
> （HTTPS 下裸 IP 也能用）合在一起发。

**「开山」**：品牌区从实例列表页底部那排挤成一堆的页签里整块搬出来，成为**同一页内的第二层**
（左侧选「dsh 实例 / 品牌实例」，右侧内容区原地换），并且第一次真正按**实例**来组织——
不再是一个"装完版本就没下文"的一次性安装工具。

> 路线说明：中途试过把品牌区独立成 `/brands/<品牌>` 一页，最终按"不要第三层跳转"的意见
> 收回——现在是实例列表页内的视图切换：URL 只换查询串（`?view=brands&brand=kimi`），
> 页面不跳转、不重挂载，`/brands/<品牌>` 旧深链重定向到这里。

### 为什么（改之前的实测数据，1440×900，9 个 dsh 实例 + 1 kimi + 1 zcode）

`.page-content` 一列里 dsh 列表与品牌区抢同一块纵向空间：dsh 列表 529px，品牌区 266px——
页签 45 + 警示 30 + 版本条 53 + 已安装行 26 + 实例列表卡 112，而卡里真正要操作的 `list-body`
只剩 **48px**：一行半，最后一行被压到屏幕边缘截断。

版本切换为什么没人用：`PATCH /api/brands/<brand>/instances/{id}` 对未安装的版本直接回
400 `... is not installed`，而行内下拉又**只列已装版本**——"想换个版本"于是变成
先停实例 → 回品牌级装版本 → 再回来切。

### 改了什么

- **实例列表页（`/instances`）**：改成**两层原地切换**——左侧子侧栏两个分区
  「dsh 实例」（instances 文件夹项）与「第三方品牌」（品牌实例项），点哪层右侧内容区就换成哪层；
  切 dsh 层时列表拿到整屏（529px → **795px**）。切换只换查询串，页面不重挂载
  （移动端 `side-category` 隐藏，两层自动变顶部 chip；实测点击后 `page-root` 探针仍在）。
  旧链接 `/instances?brand=x` 与 `/brands/<品牌>` 都落到同一处的品牌视图。
- **品牌视图**（内容区两卡 + 顶部页签行）：页签行是 4 个品牌（带实例数），下面是：
  - 卡片**「实例」**（主卡，吃剩余高度，桌面实测 672~725px、移动端 469px）：新建行
    （**该品牌还没有实例时名称预填品牌名**——Kimi Code / OpenCode / Claude Web / ZCode，
    点一下「创建」就行）、radio（= 启动面板的「当前实例」，与 dsh 实例列表同一套选择）、
    状态徽标、`id · 版本 · 端口/打开地址`，加行内版本选择器与启动/停止、打开、更多
    （信息 / 重命名 / 插件 / 日志 / 删除）。
  - 卡片**「版本」**（辅卡，默认折叠 **69~77px**）：已安装的版本构建 tag + 「安装新版本」。
- **版本切换做到能用**（三条路径，都在行内完成）：已装 + 已停 → 直接 `PATCH`；
  已装 + 运行中 → 确认「停止并切换」→ 停止 → `PATCH`；**未装 → 确认后走品牌级安装
  （行内进度条 + 失败原因）→ 装完自动 `PATCH`**。行内下拉改成**全量版本目录**，
  已装/未装都打标；版本构建仍是品牌级共享（装一次，同品牌实例都能切过去），界面把这层
  心智模型写在卡片里。后端那条 400 校验原样保留（安全网），编排放在前端——
  **不新增版本切换接口**，少一个会腐坏的状态机。
- **插件通道（界面 + 后端各留一手）**：新增 `GET /api/brands/<brand>/instances/{id}/plugins`
  与 `GET /api/zcode/instances/{id}/plugins`，同一形状 `{supported, plugins[]}`；
  能力位来自 `Brand.supportsPlugins()`（现在一律 `false`）。前端只按这个响应渲染——
  将来某品牌有了真插件，后端置 `supported: true` 并填 `plugins` 即可，界面不用改一行。
- **品牌图标（并按法律风险分档）**：页签行、启动面板的跨品牌切换器、主页品牌卡与运行浮层
  都显示各家标志，**dsh 自己的默认图标也从 HMCL 草方块换成 DeepSeek 鲸鱼**——
  但只上**许可清晰且不触商标红线**的：dsh 与 ZCode 取 Simple Icons 的 CC0 文件
  （后者**不用 ZCode 仓库自己那份**：它的 `NOTICE` 对"图标"有笼统保留条款），
  Kimi Code 与 OpenCode 取各自 MIT 仓库里的原文件（单色的走 CSS mask + `currentColor`
  跟主题明暗自适应）。**Claude Web 刻意不上图**：社区仓库的图标就是 Anthropic 的珊瑚色星芒标，
  而 Anthropic 商标条款写明"须事先书面许可、不得改动"。顺手删掉 `InstanceIcon` 里那段
  `grad-a`…`grad-d` 死代码——那四个类在 CSS 里从来没有定义过。
  来源、许可与商标归属见 [NOTICE](NOTICE) 的 TRADEMARKS 一节与 `docs/brands.md`。
- **HTTPS 打开后，裸 IP 也能用**（没有域名、没有反代的部署本来就该这样）：`https.redirect_same_port`
  （默认开）让 **TLS 端口同时接明文**，一律 308 跳到同主机同端口的 HTTPS。浏览器在地址栏里只看到
  `192.168.1.6` 时会按 http 发请求，过去直接撞上 TLS 监听、只报 `ERR_INVALID_HTTP_RESPONSE`
  ——既没页面也没线索；现在自动跳过去，而且内容绝不走明文（非 TLS 请求只得到这个 308，会话 Cookie
  是 `Secure`，明文请求里也不会被带上）。自签证书的 SAN 同时补上**本机所有网卡地址与主机名**，
  `https://192.168.1.6:3080` 从此只是"自签不受信任"这一条告警，不再是"证书主机名不匹配"。
  想要纯 TLS 端口就设 `redirect_same_port: false`（那条老报错的行为有测试钉着）。
- **删死代码**：`BrandsSection.tsx`（页签行 + 移动端手风琴 `BrandSummaryBar` + `EXPANDED_KEY`
  + 深链白名单）整块删除；`mobile.css` 里随之失效的 `.brand-summary*` 规则一并清掉。

### 验证

`tsc --noEmit` 与 `vite build` 通过；全量 **556 用例绿**（新增两条插件通道用例：形状 + 未知实例 404）。
真后端 + 真浏览器（Playwright，1440×900 与 390×844）：dsh 列表 795px、品牌视图版本卡 69~77px /
实例卡 469~725px、行内启动按钮零滚动可达、插件弹窗真调后端、原地切换不重挂载（`page-root` 探针）、
`?brand=` 与 `/brands/<品牌>` 旧链接落到同一处、首个实例名称预填品牌名；**按需安装真跑通**：
选未装 2.1.0 → 真 pnpm 安装 15 秒（行内进度）→ 自动 `PATCH` 切过去 → 切回已装 2.1.1 走直接 `PATCH`。

### 没动的部分（有意）

反代与被反代模块**一行没动**：`InstanceProxyServlet`（页面改写 / 两个 shim 资源 / 路由修正）、
`InstanceProxyTargets`、`BrandRuntime`（进程运行时）、`BrandInstaller`、`BrandInstanceManager`、
`BrandCatalog`、`ZcodeRuntime`，以及 `HdslServer` 的 servlet 装配全部原样。本次后端只新增了
两个**只读** GET 端点，挂在既有路径分支上。（zcode 分支的实例行按钮排版变了：五个平铺图标
收成 启动/停止 + 打开 + 更多菜单——处理函数与弹窗一字未改。）

## v0.3.9 — 2026-10-15 · 品牌实例也有运行中浮层

dsh 实例运行中时主界面有一张信息浮层（名称 + `运行中` + `id · 端口 · /i/<id>/?token=…` + [打开]），
品牌实例（Kimi Code / OpenCode / Claude Web / ZCode）此前**完全没有对应物**：主界面选中品牌目标后，
运行中既看不到端口也拿不到打开地址——启动那一刻自动开的标签页一关，就再无入口。

- `MainPage` 新增 `RunningPaneBrand`：品牌 Tag + 名称 + 状态，运行中显示 `id · 端口 · 打开地址`
  与 [打开]、复制地址；启动中也显示同款浮层（无地址行），补上品牌启动最长 60 秒里主界面的反馈。
  打开规则与手机品牌卡一致：zcode 走 `api.zcodeOpen`，其余直接用后端返回的 url（已带挂载点前缀），
  兜底 `withBase("/i/<id>/")`。
- `refreshExternal` 把后端**一直有返回、前端却丢掉**的 `lastPort` 映射进 `ExternalInstance.port`
  （品牌三件套与 zcode 都带）。
- 无选中目标且没有任何 dsh 实例时，浮层退回第一个运行中的品牌实例——只用品牌实例的用户
  主界面也能直接拿到打开链接。

验证：`tsc --noEmit`、`vite build` 通过；全量 554 用例绿（Java 侧无改动）。纯增量：
不涉及进程运行时、反代或被反代模块的任何代码。

## v0.3.8 — 2026-10-12 · Claude Web 品牌（首个 git 源品牌）

品牌区新增第三个第三方品牌 **Claude Web**（[ypyik0669/claude-web](https://github.com/ypyik0669/claude-web)，
MIT）——社区开源的 Claude Code 可视化工作台（**不是** Anthropic 官方的托管网页版，那是 SaaS，无法自托管）。
与 Kimi Code / OpenCode 同款待遇：装指定版本 → 拉起自带网页端 → 经 `/i/<id>/` 反代（同端口、同证书、
同登录门）；界面上标为**实验性接入、不保证可用性**（社区引擎 + 可选第三方中转，凭据与计费归上游/服务商）。

**`Brand` 由此分化出两种来源**。NPM（现有两品牌不变）与 **GITHUB**：

- 安装：**不走 git**——`github.com:443` 的 git 传输在本机网络下会被拦腰切断（实测 `expected flush
  after ref listing`、GnuTLS 重置、130 秒超时，成功率约三成），而 `codeload.github.com` 的源码 zip
  1.5 秒下完 11 MB。改为 `codeload.github.com/<slug>/zip/refs/tags/<tag>` 下载源码 zip（**3 次重试**）
  → 解包（剥掉 `<repo>-<tag>/` 根目录，含 zip-slip 防护）→ `npm ci -w server -w web
  --include-workspace-root`（只装网页端需要的两个 workspace + 根项目——desktop 的 Electron 二进制
  不下载，`ELECTRON_SKIP_BINARY_DOWNLOAD=1` 双保险）→ `npm run build -w web`。node-pty 等原生模块
  用镜像里现成的工具链编译；engine tgz 随 npm 依赖从 GitHub Releases 拉取。镜像无需任何变动
  （也不需要 `git` 二进制）。
- **`--include-workspace-root` 是必需的**：网页端的运行内核 `claude-web-engine`（ccb，社区构建，
  也是唯一听得懂 OpenAI/Gemini 供应商协议的内核）是**根项目**的依赖而不是 server workspace 的。
  只装两个 workspace 会让它缺席，应用静默回退到官方 Claude Code，于是**所有 OpenAI 类型供应商都
  用不了**（实测 0.2.1：启动行的 `runtime: claude 2.1.283` 就是那个回退）。npm 步骤同样带
  3 次重试与放宽的 fetch 超时（引擎 tgz 是绝对 GitHub URL，同样受网络抽风影响）。
- 版本列表：GitHub REST `/repos/<slug>/tags`（剥离 `v` 前缀、滤掉 `engine-*`/`nightly` 等非发行 tag），
  复用 npm 通道的缓存与降噪逻辑；**空结果永不缓存**——网络抽风时抓空是常态，缓存空列表会让下拉框
  在网络恢复后仍空白 30 分钟（首次上线后「读取不出来」的原因）。
- 启动：`tsx server/src/index.ts` 直跑 TS 源码（无内置入口）；**端口走 `PORT` 环境变量**预分配
  （源码核实 `PORT ?? 3090`，它没有端口参数）——`BrandRuntime` 新增 `LaunchPlan`（命令+环境）
  携带；就绪行 `listening on http://127.0.0.1:<port>`。

**子路径兼容经源码核实、零补丁**：前端 `base: './'`（资源全相对路径，上游为「手机壳跑子文件夹」所设）、
前端与服务器只通一条 WebSocket（标准 shim 改写其绝对 `/ws`）、无 history 路由；WS 握手的 Origin
校验（Origin==Host 或回环）与面板反代一贯的 **Host 透传契约**吻合——不需要给上游提 PR。

验证：新增用例（GitHub tag 解析降噪/排序、源码 zip 解包与 zip-slip 防护、`LaunchPlan` 的 tsx+PORT
组装与缺失入口拒绝、GitHub 品牌安装标记）；全量测试绿；`tsc --noEmit` 通过。生产镜像内实测：
API 取 tags 0.6 秒 200、源码 zip 1.5 秒下完 11 MB、解包 → npm ci（engine 与原生模块齐）→ vite 构建
→ `PORT=<n>` 启动 → 就绪行 `claude-web 0.2.1 listening on http://127.0.0.1:<n>` → GET / 200。
面板经 `/i/<id>/` 的浏览器全链路待人工复验。

### 「打开文件夹」在容器里的绕法

Claude Web 的 `打开文件夹…` 会叫**服务器端的原生目录对话框**（zenity / osascript / PowerShell）——
容器里没有桌面，它静默返回 null，点了没反应（上游设计假定跑在有桌面的机器上，不是面板的问题）。
面板改用它自己的协议：实例就绪后向 `ws://127.0.0.1:<端口>/ws` 发一条 `workspaces.add`，把实例的
工作区目录登记成项目——页面里的「项目」菜单从此直接可选，无需对话框。实测回复 `ok:true` 并伴随
`meta.changed` 事件。

## v0.3.7 — 2026-10-12 · 子路径反代下品牌代理修复（Kimi Code / OpenCode / ZCode）

面板自身被反代在子路径（`HDSL_BASE_PATH`）时，dsh 实例正常但品牌实例全崩：
zcode 显示 "Web Remote Control Failed"、kimi 报 `WebSocket connection failed:
ws://host/ws`。根挂载不受影响（本轮所有改动在根侧均为恒等路径，根/子路径双场景
18+9 项验收全绿）。

- **上游根因**：Kimi Code / ZCode 的 web 端原生认识 `/i/<id>/` 挂载，API base 来自
  `location.pathname.match(/^\/i\/[^/]+\//)`——正则锚在路径开头。浏览器地址是
  `/hdsl-web/i/<id>/` 时检测失配，API/WS 打到源站根（`/api/server-info` 404、
  `ws://host/ws` 被宿主 nginx 404）。
- **修复**：新 `patchZcodeScript` 把检测正则放宽为 `/^.*?\/i\/[^/]+\//`（从路径
  开头非贪婪匹配到第一个 `/i/<id>/`；`match()` 返回匹配子串，只去 `^` 锚会丢
  base 前缀——已实测修正一次）。zcode 走这个最小补丁（品牌那套路由读法补丁会把
  它原生按 `/i/<id>/` 的路由读坏）；kimi 走品牌补丁通道，同一放宽覆盖。
- **打开链接双前缀**：后端已在 REST 边界给 open/list 的 url 统一加挂载点前缀，
  前端两处仍 `withBase(inst.url)` → `/hdsl-web/hdsl-web/i/<id>/` 落回主面板。
  契约钉死：API 返回的 url 直接用，仅本地兜底路径 `withBase`。

验证：锚点逐字节比对真实 bundle（zcode 3.15.1）；新增单测；VM（nginx + 
`HDSL_BASE_PATH=/hdsl-web`）真浏览器 zcode/kimi/opencode 9/9 PASS（地球图标打开
单前缀落点）；全量 528 用例绿。

## v0.3.6 — 2026-10-11 · 裸部署三道防线 + 面板托管 Node 运行时一键供给

围绕「拦不住的 Windows/macOS 裸部署」体验的一轮加固，全部带反例实测：

- **面板托管 Node.js 运行时**：裸部署没装 Node.js 时，「设置 → Node.js」可一键安装
  托管运行时（落 `<data>/hdsl/runtimes`，默认国内镜像源，官方源可选），装完后版本安装、
  插件、品牌工具全部自动回退使用；也保留用户自行安装 Node.js 的权利（手动指引 +
  nodejs.org 链接）。关键坑：npm/pnpm 是 `#!/usr/bin/env node` 脚本，探测与 spawn
  必须把运行时自己的 bin 塞进 PATH，全部调用点已接上。新端点
  `GET/POST /api/node(/install|/uninstall)`，安装走任务系统带进度。
- **启动横幅升级成体检报告**：open 行直接给访问地址并注明首跑建号；data dir 带
  内容清单与备份/别上公网提示；runtime 缺失给两条修复路；Windows 平台单独提示
  0600 无 NTFS 语义、凭据文件可能被同机其他账户读到（README 安全说明同款）。
- **构建期哨兵**：没有 npm 且没有可用的 web/dist 时构建直接报错（"this jar would
  ship without the web frontend"），不再静默产出缺前端的 jar——Windows 上装了 JDK
  没装 Node 时 33 秒"成功"、跑起来全站 not found 的事故根源。
- **运行期可诊断**：jar 缺 index.html 时日志明确写「web/index.html 不在 classpath，
  请用 release jar」，不再只丢光秃秃的 not found。

实测：清空环境变量（PATH 无 node/npm/pnpm）下装一枚托管运行时后版本列表成功返回；
全量 528 用例绿；设置页 UI 断言五项全 PASS。

## v0.3.5 — 2026-10-11 · 审查拆雷（8 颗）+ 死代码清理 + 反向代理挂载支持

一次四路交叉审查定位到 8 颗确认的雷，全部拆除（每颗独立提交、逐个验证）：

- **取消任务只杀自己的进程树**（最危险的一颗）：`MAX_INSTALLS=2` 下取消实例 A 的安装
  会全局杀死实例 B 健康进行的 pnpm。进程改按工作线程归属，新增回归用例（旧行为实测失败）。
- **compareVersions 换用 VersionNumber**：同前缀 prerelease 曾按字典序排
  （`alpha.10 < alpha.2`），且 Node 的 `v20` 前缀被解析成 0。喂给版本选择器、lockstep
  最老判定、插件/Node 排序的比较器从此按 Maven 优先级。
- **Host 头解析收敛 HostHeaders**：品牌 allowlist 的 `[::1]:8080` 曾被砍成 `"["`。
- **过时安全文档对齐现状**：Zcode 类文档/Servlet/API 文档/前端按钮还活在「独立端口
  明文 HTTP」时代，与「回环绑定 + `/i/<id>/` 反代」的实际姿态矛盾——文档撒谎是最阴的雷。
- **移动断点统一**：layout.css 的孤儿 `(max-width:800px),(max-height:490px)` 与
  `MOBILE_QUERY` 对齐，760–800px 窗口不再 JS/CSS 各说各话。
- **插件面板任务归属**：删掉被推翻的 `kind.includes(id)` 启发式（恒 false），改用
  `t.instance` 精确匹配。
- **品牌实例状态三份真相收敛为 `store.external` 唯一源**（删 `zcodeInstances` 双拷贝）。
- **死代码**：DshCli（1266 行零引用）、「浏览」菜单四项死 UI、`api.vendors()` 死端点、
  15 个死图标。

**反向代理挂载支持（面板自身被反代）**：新增 `HDSL_BASE_PATH`——面板架在 nginx/Caddy
子路径（如 `/panel/`）下时，index.html 注入 `<base href>` + `window.__HDSL_BASE__`，SPA
的资产/API/WS/路由链接、`/i/<id>/` 打开地址、实例反代的页面改写/品牌脚本补丁/重定向
Location/Cookie Path 全部按浏览器眼里的完整挂载点重写；反代只需剥前缀转发（与面板反代
dsh 实例同款挂法）。根路径挂载零配置、行为不变。两种挂法均端到端实测（SPA/登录/API/
WS/品牌页/资产全通），README 新增「反向代理」部署章节。

## v0.3.4 — 2026-10-10 · 修复桌面缩窄窗口时品牌摘要条样式失效

v0.3.3 的摘要条/长说明降级样式被误放进了 `(hover: none) and (pointer: coarse)`
（触摸）媒体查询块里——真机（粗指针）正常，但桌面浏览器把窗口缩窄触发移动版式时
（`max-width: 760px` 命中、粗指针不命中）这些样式完全不生效：摘要条退化成块级
堆叠，「全部展开」和「启动」掉到第二行。已把整块移回 `(max-width: 760px),
(max-height: 520px)` 布局查询（与 `useMobileLayout()` 判定同域）。

教训：mobile.css 有两个媒体查询块，加规则时必须认准布局块；验收脚本此前全部用
`has_touch=True`（粗指针）跑，漏掉了细指针路径——已补回归用例 ⑨（无触摸 500px
视口断言摘要条单行、药丸 32px、按钮组右对齐），18 项全 PASS。

## v0.3.3 — 2026-10-10 · 移动端首屏还给 dsh 实例 + 主页品牌实例卡

手机访问时品牌区（ZCode / Kimi Code / OpenCode 三个面板，桌面版式 6~7 个横条原样搬进
390px 全部折行）整块约 678px 高，把 dsh 实例列表挤出首屏——「挡着 dsh 实例管理」是
信息架构问题，不是控件大小问题。同时修掉主页「双真相」：卡片显示一个 dsh 实例、
贴底启动栏操作另一个品牌实例。

### 改了什么

- **品牌面板移动端默认折叠**（最大杠杆）：页签行 + 摘要条并进一张卡，摘要条版式
  `Kimi Code · 已安装 2.1.1 · 1 实例 · 已停止　[全部展开▾] [启动]`——右侧成组：明显的
  「全部展开▾」药丸按钮（secondary-container 底、一眼可见）+ 默认操作按钮（有运行中
  的实例显示「停止」，否则「启动」第一个，行为与启动面板主按钮一致，真实拉起/停掉
  验证过）；左侧文字区点按同样展开。点当前页签（带 ▾ 箭头引导）也可展开收起，
  手风琴天然同时只开一个；展开状态记 localStorage，刷新后页签跟随上次展开的
  品牌。折叠态整块 105px（两个 44px 触控目标 + 分隔线）vs 基线 678px。桌面端一字
  不动。判定与 `mobile.css` / `hooks.ts` 的 `MOBILE_QUERY` 逐字一致。
- **长说明移动端降级**：品牌第三方警示条、ZCode「纯实现性验证……」长文，手机上收成
  一行摘要 + 「详情」展开（新组件 `CollapsibleNote`，桌面端原样渲染）。
- **主页卡片跟随启动目标**：`MainPage` 的数据源从「第一个运行中的 dsh」改成与贴底
  启动栏同一个 `useLaunchEntry()`——品牌实例从此和 dsh 平权：卡片渲染
  [品牌 Tag] 实例名、版本、状态徽标，操作 = 启动/停止、打开、管理，全屏只剩一个
  「当前实例」。细节：品牌实例打开地址就是 `/i/<id>/`（会话门就是门，不拼 dsh 的
  `?token=`）；运行时长 BrandRuntime 不记 startedAt，品牌卡不显示这项（不填假的）；
  地址行截断 + 复制按钮。
- **`/instances?brand=kimi|opencode` 深链**：主页品牌卡「管理」跳实例列表并自动切到
  对应品牌页签。

### 验证

- 新验收脚本 `scripts/mobile-acceptance.py`（headless Chromium 390×844，起静态 dist +
  /api 反代）：首屏可见 dsh 行 4 ≥ 3 ✓、首行启动按钮零滚动 ✓、品牌折叠态 105 < 110px ✓、
  摘要卡默认操作按钮 + 「全部▾」提示 ✓、摘要卡**真实启动/停止一轮**（状态翻到运行中再
  停回）✓、展开/收起/刷新保持 ✓、长说明一行降级 ✓、主页品牌卡 + ?brand= 深链 ✓、
  桌面端 1146px 无摘要卡/面板原样 ✓——全 PASS。
- `tsc --noEmit` 通过；全量 528 用例 + shadowJar 构建绿。

## v0.3.2 — 2026-10-09 · 品牌面板实例行的版本下拉文字被上下裁掉

修复 Kimi Code / OpenCode 面板**实例行**里的版本下拉渲染成「几个灰点」——数字的上下边缘被
裁掉，叠上运行中 `disabled` 的半透明后几乎看不清。

根因：这行下拉写死 `height: 28`。全局 `box-sizing: border-box`，而 `.input` 的
`padding: 8px` 在 border-box 下也占高度，内容盒只剩 28 − 16 = 12px，装不下 13px 字体约
15–16px 的行盒；`<select>` 默认 `overflow: hidden`，多出来的部分被上下裁掉。v0.3.1 修
「版本行 / 实例列表头」两处下拉时只验证了**对齐**，没验证**文字装不装得下**——36 的内容盒
是 20px（装得下），28 的只有 12px（装不下），实例行这处遂漏网。

修法：实例行下拉同样提到显式 `height: 36`，与组件内另外两处一致（行容器 `.tlli` 是
`align-items: center` 的 flex，高度变大由居中自然消化，不会再上浮）。

## v0.3.1 — 2026-10-09 · 品牌面板工具行的下拉对齐加固

修复 Kimi Code / OpenCode 品牌面板两处下拉在个别浏览器里**上浮、与旁边的按钮不齐**
（「版本行」的版本下拉 vs 「已安装」，「实例列表」头的版本下拉 vs 「创建」）。

根因：不设高度的 `<select>` 作 flex 子项时，个别引擎对它的高度测量有偏差——
`align-items: center` 按被夸大的盒模型居中，框的实际绘制位置偏上。修法很简单：
这两处下拉给出显式 `height: 36`，与相邻 `.btn` 按钮（36px）齐平。旁证是实例行内
那个一直显式 `height: 28` 的下拉在同样的环境里从未错位。

Chromium / Firefox / WebKit 三引擎实测：三行（版本行、实例列表头、实例行）内所有
控件中心线一致（±0.1px 内）。

## v0.3.0 — 2026-10-08 · 第三方品牌实例：Kimi Code 与 OpenCode

实例列表页底部的实验框升级为「品牌区」：除 ZCode（实验声明保留）外，新增 **Kimi Code** 与
**OpenCode** 两个第三方品牌——面板从 npm registry 安装指定版本、拉起它们自带的网页端
（`kimi web` / `opencode web`），并像 dsh 一样经 `/i/<id>/` 反代（同端口、同证书、同登录门）。
同时把主页启动按钮旁的实例选择菜单改为**跨品牌实例切换器**：点选 = 切换主按钮的目标实例
（不再跳进详情页），dsh / Kimi Code / OpenCode / ZCode 混合列出并带品牌标签。

### 新增

- **品牌实例后端**（`web/brand/`）：`Brand` 描述符（npm 包、可执行名、就绪行、端口策略）、
  `BrandCatalog`（registry 版本目录，过滤 snapshot/ci 噪音 tag，只留 `x.y.z`，缓存 30 分钟）、
  `BrandInstaller`（`pnpm add <pkg>@<version>` 装进 `<数据目录>/brands/<品牌>/releases/`，
  沿用 `--config.dangerously-allow-all-builds=true`——pnpm≥10 默认不跑依赖 postinstall，而 Kimi
  Code 的 postinstall 要拉原生二进制）、`BrandRuntime`（进程池 + 就绪行解析 + ANSI 剥除）、
  `BrandApiServlet`（`/api/brands/{kimi,opencode}/*`：版本/安装/实例 CRUD/启动/停止/打开/日志）。
- **两个品牌的实测差异**（详见 [docs/brands.md](docs/brands.md)）：Kimi `--port 0` 可用；
  OpenCode 的 `--port 0` 会静默回落默认 4096 → 面板预分配端口；Kimi 起服务时带
  `--allowed-host <面板域名>` + `--dangerous-bypass-auth`（其官方文档写明用于 "behind your own
  authenticating proxy"，面板会话门正是那个代理）；OpenCode 不设 basic auth（面板门即门）。
- **反代泛化**：`InstanceProxyServlet` 的页面改写从「dsh 精确形状」扩展为「任意根绝对 SPA」——
  非 dsh 页面里所有 `(src|href|action)="/…"` 加挂载点前缀、`<head>` 内引用运行期 shim
  （fetch/XHR/WebSocket/EventSource 包装）。e2e 挖出三个坑都钉成了用例：shim 必须作为**同源外链
  脚本**由面板在每个挂载点自服务（OpenCode 的 `script-src 'self'` CSP 拒内联脚本，而「shim 在
  HTML 里却从没执行」是静默失败）；manifest 链接要剥掉（浏览器不带凭证抓它，过会话门必 401）；
  「就绪」以**端口真的在监听**为准（OpenCode 的就绪行比监听早一拍）。dsh 页面的既有处理一字未改，
  回归夹具是 `src/test/resources/brand-pages/` 下两个品牌的真实首页 HTML。
- **e2e 后续实测修正（最终形态：单端口 + 地址栏留在挂载点）**：OpenCode 的客户端**按 URL 路径
  路由**（bundle 直读 `window.location.pathname`，全发行包无 base-path 开关），挂在子路径下只剩壳。
  试过「独立 origin 端口」一版后按「只留一个端口」的要求收回；最终做法是**读时剥、写时加**，两半
  必须成对：
  - 面板在反代它的 JS 时，把路由读取地址的那一处表达式补成「先剥挂载点」
    （`window.location.pathname.replace(/^\/+/,"/")` → 先经 `window.__hdslUnmount`；锚点实测
    1.18.32 与 1.18.35 一致），补过的字节带 `no-store` —— 缓存里留一份没补丁的旧 bundle，会让客户端
    按挂载点本身路由、匹配不到任何路由；
  - `/i/<id>/__hdsl-router.js`（同源外链、CSP 友好）把客户端写回的地址重新加上挂载点
    （`pushState/replaceState`；已经在挂载点里的地址不会重复加）。
  于是地址栏停在 `/i/<id>/…`、刷新与深链回到应用内的原处（上游对未知路径返回它自己的 index 页）、
  地址也可以直接分享——不再是「地址栏显示面板根、刷新回面板」。**兜底**：处理页面请求前先探一次
  客户端的 bundle（按挂载点 + 资源名缓存；抓不到、非 200 或过大都算探不到），探不到就改注入
  `?mode=root` 的那份 shim ——把地址还给面板根（即旧行为），应用照常可用，而不是只剩一个壳。
  Kimi Code 认子路径、不需要这段脚本（按品牌开关，注入反而与它自己的路由打架）。端口段
  （3091–3100）、边过滤器与端口注册表全部删除，compose 恢复只发布一个端口。全量 **528 用例**全绿；
  e2e 无头浏览器与「直连实例」逐项对照（渲染、点击、pushState/popstate、深链、刷新）逐字一致，
  零控制台错误、零面板侧失败请求。
- **用起来才暴露的三处代理缺陷**（用户实测「加 DeepSeek key 点继续就失败」触发，全部修掉并复验）：
  - **客户端自己拼的根绝对 URL**：页面改写只看得见 HTML 里的引用；客户端运行时拼的 chunk、
    worker、图标（bundle 里 `"/assets/…"` 字面量与 Vite preload 助手的 `"/"+dep` 基址）与 CSS 里的
    `url(/…)` 全都打到面板根，拿到的是面板自己的 HTML——OpenCode 的 Settings / Providers / 所有
    对话框都是懒加载 chunk，于是「首页能看、点进任何面板都是空的」。现在反代 JS/CSS 时一并改写
    这三类引用（见 [docs/brands.md](docs/brands.md)）；CSS 字体回退的问题也一并消失。
  - **shim 重包 `Request` 的方式**：`new Request(url, 原Request)` 会把请求体变成流，Chrome 对这种
    带 body 的 `PUT` 直接以 `net::ERR_ALPN_NEGOTIATION_FAILED` 失败——面板连请求都收不到。改成从
    原 Request 的字段显式重建、body 从 clone 读出，`PUT /auth/{provider}` 由此走通。
  - **上游连接复用**：实例（Node/Bun）5 秒就掐掉空闲 keep-alive，池里那条死连接让请求静默失败
    （JDK 自己抛 `Http1RequestBodySubscriber` NPE，面板对它答 502）。现在空闲连接 4 秒退休；
    可重放的请求失败后用「从未连过实例」的 client 重试一次；带 body 的请求改为「放得下就缓冲、
    放不下才流式」，顺带修掉「无 body 的 GET 被当成带 body」这件旧事。

- **界面上的能力边界声明**：OpenCode 的页签改名「OpenCode（实验）」，品牌页在原有的第三方警示下面
  多一行——面板对它的接入**不保证可用性**，只做到「装指定版本 → 拉起它自带的网页端 → 反代到
  `/i/<id>/`」，再往下遇到的问题（模型目录与模型 id 对不上、会话与 agent 的行为、凭据与计费）都是它
  自己的，面板修不了。`docs/brands.md` 与新增的诊断报告
  [docs/report-2026-10-08-opencode-web.md](docs/report-2026-10-08-opencode-web.md) 写明了同一条边界。

### 刻意没做

- **插件管理**：两个品牌都没有可用生态。
- **凭据注入**：登录/供应商/计费都在工具自己的网页里完成；实例状态目录（`~/.kimi-code` /
  `~/.config/opencode`）落在实例 `home/` 里随 `/data` 持久化。第三方工具，功能/安全/行为由各官方负责。

### 验证

- 全量 **528 用例 / 0 失败 / 0 错误 / 0 跳过**（相对上一版净增 29 条：目录解析/过滤、启动命令组装、
  ANSI 剥除、就绪行、release 簿记、API 全生命周期（fake 可执行文件真绑定端口走完 创建→启动→就绪→
  打开→守卫→停止→删除）、真实 HTML 夹具的页面改写、shim 资源端点、bundle 补丁（路由锚点、拒绝歧义、
  不碰客户端那处 `auth_token` 清理、preload 基址与 `/assets/` 字面量、CSS `url(/…)`）与入口脚本解析，
  外加一条「带 body 的请求穿过反代」（小 body 缓冲、512 KB 走流式，两端都要求逐字节到达））。
- spike 实测：pnpm 安装两品牌（kimi postinstall 正常）、`--port 0` 行为、就绪行、反代旗标；
  OpenCode 路由读取地址的那一处表达式（1.18.32 与 1.18.35 各抓一份 bundle 核对，两版一致）。
- e2e（临时容器，无头 Chromium）：真实面板装两品牌 → 启动 → 经 `/i/<id>/` 打开——Kimi Code
  完整渲染出引导页（0 失败请求）；OpenCode 经反代建会话成功、UI 可交互（0 控制台错误、0 失败
  请求）；期间挖出并修掉 CSP 内联拦截、manifest 401、就绪行早于监听三个真实坑。
- e2e 复验（2026-10-08 晚，同一套无头浏览器，单端口）：地址栏在 `/i/<id>/` 与
  `/i/<id>/new-session` 下都留在挂载点；页内 `pushState('/zzz')` 得到 `/i/<id>/zzz`，把带挂载点的
  地址再写一次也不会双前缀；深链 `/server/…/session/…`、失效会话、刷新各项与「直连实例」对照逐字
  一致（含应用自身那 3 个 500 `/api/reference`——直连也有，与面板无关）。
- e2e 复验（2026-10-08 晚第二轮，同一天同一套无头浏览器）：Settings 的 Servers / Providers / Models
  面板**完整渲染**（此前只有首屏，懒加载 chunk 全 404）；走完真实流程「Providers → Show more
  providers → 搜索 DeepSeek → 填 key → Continue」——`PUT /auth/deepseek` 200、对话框关闭、key 落进
  实例的 `auth.json`；**零控制台错误、零失败请求、面板日志零上游失败**，连跑两轮结果一致。修之前
  同一套流程稳定复现两个症状：chunk 打到面板根（`Failed to load module script … text/html`）与那条
  `PUT` 的 `net::ERR_ALPN_NEGOTIATION_FAILED`（面板根本没收到请求）。

### 顺手修掉的一处类型错误

默认壁纸一直以 `application/octet-stream` 下发：`StaticServlet` 的内容类型表里有 `svg`、`png`、`ico`，
唯独没有 `jpg`/`jpeg`，而壁纸正是 `.jpg`。浏览器会按内容嗅探渲染，所以画面从来是对的——这也正是它
一直没人报的原因；但类型是错的，且哪天加上 `X-Content-Type-Options: nosniff`，图片就会真的被拦掉。

- 补上 `jpg`/`jpeg` 两个键。
- 新增用例钉住：`/assets-img/wallpapers/2021-08-26.jpg` 必须回 `image/jpeg`、且是**它自己的字节**
  （长度 > 1KB），而不是 SPA 兜底返回的 HTML 外壳——坏的时候状态码也是 200，只看状态码看不出来。

## v0.2.5 — 2026-10-06 · 容器镜像里丢失的壁纸与图标

独立部署（`docker pull` 起容器）第一次打开网页就能看见：**默认壁纸不见了**，启动器与实例的图标也是坏的。
同一份代码本机 `./gradlew shadowJar` 出来的 jar 却一切正常。

根因不在前端、也不在静态服务，而是镜像构建的 web 阶段少带了一份源码目录。前端请求的
`/assets-img/wallpapers/2021-08-26.jpg` 不是源码里现成的文件，而是 `web/scripts/sync-assets.mjs`
（`package.json` 的 `prebuild` 钩子）从 `../src/main/resources/assets/img` 同步到 `web/public/assets-img/`
的产物。web 阶段只 `COPY web/ ./`，那份源目录不在阶段里，脚本于是走了它自己的「资源目录不存在，跳过」分支：
`console.warn` 之后 `process.exit(0)` —— **构建依然是绿的**。

- 实测账：镜像内 jar 的 `assets-img` **0 条**，同版本 Release jar **84 条**
  （3 张壁纸 + 约 79 个图标：`icon.png`/`icon-title.png`/`icon@4x`/`icon@8x`、`unknown_pack.png`，
  以及 `8mi-tech`、`ShulkerSakura`、`april_fools`、`chest`、`chicken` 等实例图标）。
- 为什么一直没被发现：这些路径**不返回 404**，而是被 SPA 外壳兜底接管 ——
  回的是 `200 + Content-Type: text/html` 的 `index.html`（1234 字节）。浏览器拿 HTML 当图片用，
  背景自然不显示；而只看状态码的检查会认为一切正常。
- 只有镜像坏：CI 的 `./gradlew` 在完整仓库里构建，`sync-assets` 读得到源目录，所以 Release jar 一直是好的。

### 修法

- web 阶段按脚本期望的相对路径把资源源目录带上：
  `COPY src/main/resources/assets/img /src/main/resources/assets/img`。
- 构建末尾加一条断言 `RUN test -f /web/dist/assets-img/wallpapers/2021-08-26.jpg`：
  资源缺失就当场失败，不让这类回归留到部署之后才被发现。

### 验证

- 构建上下文用 `git ls-files` 构造（等同于全新 clone，`assets-img` 0 条），在真实 docker 上双向跑：
  带修复 `BUILD_RC=0`、`[sync-assets] 82 个文件 → public/assets-img`、阶段镜像里 `dist/assets-img` 84 条、
  三张壁纸俱在；去掉修复则在断言处 `BUILD_RC=1`（`exit code 1`）。
- 另有一条不需要 docker 的复现：按 web 阶段的目录布局单独跑 `npm run build`，`vite` 产出的 `dist/`
  只有 `assets/` + `index.html`，与镜像内 jar 的内容逐项一致。
- 全量 **499 用例 / 0 失败 / 0 错误 / 0 跳过**；`hdsl-web-0.2.5.jar` 内 `assets-img` **84 条**。

## v0.2.4 — 2026-10-06 · 环境体检：内核文件监视器配额

给「实例起不来」补一个能看的病因。症状是**两个实例不能共存**：一个在另一个起来之后起不来，反过来
也一样；而两种表现都不指向病因——实例要么起来就消失，要么进程活着、端口在监听，却一直停在「启动中」、
`/i/<id>/` 返回 502。

根因不在反向代理，也不在 dsh 之间，而在一个共享的内核数字：`fs.inotify.max_user_watches`
**按 uid 计、容器之间不隔离**。面板容器以 `hdsl`(1000) 运行，宿主上任何同样以 uid 1000 运行的程序
都从这个数字里扣；而 dsh 启动时要用 chokidar 监视自己的 `profiles/<profile>`。

- 实测账：上限 65536（内核默认值），而宿主上**同一个 uid 的另一个递归监视程序**就能把它吃到几乎
  全满——用正确测法（连续申请并保持打开）只剩 **1 个**可注册；一个 dsh 实例实际只要 ≈9 个。
- 拿不到监视器时 node 要么当场崩在
  `Error: ENOSPC: System limit for number of file watchers reached, watch '…/profiles/web'`，要么那个
  启动项永不结束、就绪行一直不打印。两种失败都在一次性容器里复现过。
- 「谁被拒」由启动那一刻还剩几个空位决定，与实例身份无关：`instance` 的 pokemon profile 实测只占
  0 个 watch（`@hellosz/dsh-pets` 与 `dsh-wildmon` 都不用文件监视器），它不是吃配额的那个。
- 把那个占着配额的程序停掉、配额腾空之后，同一个一次性容器里两个实例（0.2.0-rc.2 与 0.1.5-alpha.2）
  可同时 RUNNING 并稳定运行，两个挂载点各自 200、页面 `<base href>` 各指向自己的挂载点、互不串台。

### 新增：环境体检里的「File watchers」

**只加诊断，不动启动流程。**

- 新增 `util/platform/WatcherBudget`：读上限，并用「真实注册目录」探测还剩多少（每个 watch 对应一个
  自己的目录，与 `inotify_add_watch` 同语义；用完即删；`-1` 表示问不出来，绝不当成「没有了」）。
- `DshDoctor` 新增 `File watchers` 段：充足时 `at least 64 free of 65536  [ok]`，不足时
  `1 free of 65536  [SHORTAGE]` 并打出可直接复制的 `sysctl -w …=524288`。探测上限是 64，所以充足时
  写「at least」——把 64 说成总数会读成「快满了」，正好相反。
- `DoctorApiServlet`：`[SHORTAGE]` 映射为 `warn`。

### 顺手修掉一个 JDK 坑

`/proc` 文件报告 `st_size=0`，而 `Files.readString` 走按 size 读的路径，对 `65536` **只返回首字节
`"6"`**（同一容器里 `readLine` 得到 `65536`）。`WatcherBudget` 因此按行读，并有用例钉住「读完整的数、
不是首字节」——一个看起来合理的错数字比没有数字更糟。

### 验证

- 全量 **497 用例 / 0 失败 / 0 错误 / 0 跳过**（新增 11 条：探测读数与清理、三种行文案、状态映射）。
- 临时容器里换入新 jar 的面板打 `GET /api/doctor`，实到
  `file-watchers  status=ok  inotify watches: at least 64 free of 65536  [ok]`。
- 未验到「真·配额不足时该行在 API 里的渲染」：要验就得把那台机器的配额喂满。该分支由单测覆盖，而
  「配额满时内核确实拒绝」另有实测。

### 文档

- `README.md`「常见问题」加这条症状的入口。
- `docs/deployment.md` 新增 §8「宿主 inotify 配额」（配额怎么算、两种现象、怎么确认、怎么调大，以及
  不想调配额时的两条替代路——其中「换 uid」按实测写了除 compose 之外还要解决的写权限），并补上此前
  漏掉的 §7/§8 目录项。

## v0.2.3 — 2026-10-05 · 反代挂载下 dsh 网页能打开了

修一个用不了的功能：实例改走 `/i/<id>/` 反代之后（v0.2.2），**点「打开 dsh」进不去**——
按钮看着像在原地刷新，手动敲地址则是白屏。根因是 dsh 的网页端假定自己拥有整个 origin，
而代理只做了上游那份参考实现里的那几件事。

### 修复

- **`Location` 没有重写**：dsh 的 token 兑换回 `303 Location: /`，浏览器跟着它离开挂载点、落到
  面板首页——就是「原地跳转」。现在改回挂载点内；指向上游回环端口的绝对地址也一并改写，
  外部地址原样放行。
- **dsh 生成的页面把根路径写死**：页面开头是 `<base href="/">`，另有 52 处根绝对的 `/plugins/...`
  （`<link rel=preload>`、`<script src>`，以及内联 `__DSH_BOOT__` 里每个插件条目的 `url`）。经挂载点
  访问时它们指向面板，面板的 SPA 兜底把 `index.html` 当 JS 回，浏览器报
  `Failed to load module script: … MIME type of "text/html"`，一个脚本都加载不了——白屏。现在把这两处
  加上挂载点前缀，并去掉那行取不到凭证的 manifest 链接；正文是 gzip 的，**先解压再改写**，回给浏览器时
  丢掉 `content-encoding` / `content-length`。
- **应用运行期自己拼的 URL 也是根绝对**（`fetch("/api/…")`、`ws://<host>/api/remote.mux`、
  `/plugins/events` 的 SSE）：改写后的页面里注入一段小 shim，把 `fetch` / `XMLHttpRequest` /
  `WebSocket` / `EventSource` 包一层，同 host 且不在 `/i/` 下的 URL 加上挂载点（字符串、`URL` 对象、
  `Request` 三种入参都处理）。SSE 与 WebSocket 仍然流式转发，不受影响。
- **`ProxyBehaviorTest` 加 3 条回归用例**：页面改写（含 gzip、含「不认识就一字不改」）、`Location`
  的四种形状。把实现换回修复前的版本跑同一批用例，新用例如期失败——测试确实钉住了这个 bug。

### 验证

- 全量 **486 用例 / 0 失败 / 0 错误 / 0 跳过**（新增 3 条）。
- 临时容器里用无头 Chromium 真跑整条链路：dsh **0.1.5-alpha.2** 与 **0.2.1-alpha.1** 两个版本都能打开，
  最终地址停在 `/i/<id>/`，所有接口变成 `/i/<id>/api/…` 并返回 200，`/i/<id>/plugins/events` 是
  `text/event-stream`，DOM 里有完整界面，**控制台零报错、无 401/404**。

### 其他

- 对照上游的结论（免得以后以为我们擅自偏离）：`deepseek-ai/deepseek-harness`（**默认分支是 `master`，
  不是 `main`**）的 `apps/web/tests/prefix-proxy.ts` 是给**它自己的测试客户端**（`scaffold.ts`：知道前缀、
  自己处理 303、用 `baseUrl` 拼路径）用的最小件，**它本身也不重写 `Location`、不碰页面**；全仓没有
  `X-Forwarded-Prefix`，dsh 也没有任何 base/prefix 旗标。上面那三层是本启动器自己的。

## v0.2.2 — 2026-10-05 · ZCode 实验品类 + 面板可选下载源

v0.2.0 之后的第一个发布，两块内容合在一起：**ZCode**（第二个可启动品类，实验性）从零接进来——
面板自己下载上游源码、打反代补丁、`pnpm` 构建、装成版本，实例只绑回环、经 `/i/<id>/` 反代访问；
以及**面板里可选下载源**，把「换源要改环境变量、还得重启容器」变成一等设置，并顺手拆掉 ZCode
构建链上几个必然失败的坎。（v0.2.1 未单独发布，其内容并入本版。）

### 新增：ZCode 实验品类（实验性）

- **独立品牌分区**：实例页里 ZCode 与 dsh 完全隔离，文案与 `docs/zcode-experimental.md` 都写明
  「纯实现性验证功能：不保证可用，也不承诺与 dsh 同等的功能与兼容」。
- **面板内构建** `POST /api/zcode/build`：下载上游 tag/分支的源码 tarball → 打 5 处反代补丁
  （vite `base: "./"`、`main.tsx` 的 `hdslBasePath()` 与 WS origin、`/api/server-info`、OAuth
  `tokenUrl`）→ `pnpm install` → `pnpm build:zcode` → 校验产物记录的 sha256 → 装成
  `<数据目录>/zcode/releases/<版本>/` 并维护 `current`。界面有步骤、进度与实时日志；上游把锚点
  挪走时构建**明确失败并点名文件**，而不是产出一个指向错误地址的发行包。
- **反代**：实例只绑 `127.0.0.1`、`--no-token`，浏览器经面板的 `/i/<id>/` 进来——同端口、同证书、
  同样要登录；HTTP 代理与 WebSocket 中继共用一套目标解析（`InstanceProxyTargets`），
  未知 id 404、已知未运行 502 的语义与 dsh 一致。
- **凭证**：保存的 API Key 按 ZCode 的 `provider_config.json` 格式写入实例目录（未文档化格式，
  尽力而为；上游一改就可能失效）。
- 镜像里加 Node 24（`/opt/node24`，只有构建用它，面板与 dsh 仍是 Node 22），发行包与限制见
  `docs/zcode-experimental.md`。

### 新增：面板可选下载源

- **设置 → 下载源**（`web/src/pages/SettingsPage.tsx` 的 `DownloadSourceTab`）：预设下拉（跟随部署环境（默认）/
  官方 / npmmirror / 中科大 / 腾讯云 / 华为云）+「自定义…」时才出现的手填 URL，输入即时行内校验（红字 + 保存置灰），
  另有一行显示**当前生效的源与它的来源**（面板设置 / 环境变量 / 默认官方）；保存成功后回读服务端值。
  此前这张卡片是个只写 `localStorage` 的摆设——无 API、无校验，刷新后「当前」还会显示错，卡片自己写着
  「网页版暂未接入服务端下载源设置」。
- **`GET/POST /api/settings/registry`**（`web/http/RegistrySettingsApiServlet`）：`GET` 回
  `preset` / `registry` / `effective` / `source` / `presets[]`；`POST` 收预设 id 或自定义 URL，
  非法输入 400 **且设置保持不变**。另有 `POST /api/settings/registry/test`：拿生效或指定的地址取一次
  `is-number` 的元数据，回 `{ok, millis, status}`（5 秒超时）——「源通但慢得像卡死」正是这个设置要解决的问题，
  光看界面分不出来；卡片上就有一个「测速」按钮，测的是当前选中的那个地址（选着「自定义…」时测输入框里填的，
  否则测那个预设自己的），结果旁会回显服务端实际测的地址。`DshDoctor` 的 Settings 段顺带加一行
  `npm registry: <有效值> (<来源>)`——卡住时不用 `docker exec` 就能判断实际用的哪个源。
- **设置落盘**（`dsh/LauncherSettings` + `setting/SettingsManager`，落 `/data/hdsl/launcher-settings.json`，
  0600 原子写）：新增 `npmRegistryPreset` 与 `npmRegistry` 两项。此前启动器设置没有 REST 通道，前端也没有对应控件；
  这份快照是 `save()` 唯一的写入面、`load()` 唯一的读取面——不登记进来的设置就是「能改、下次启动就丢」，所以一次补齐。
- **防注入校验**（`dsh/NpmRegistry.normalize`，REST 与子进程/文件写入共用）：只允许 `http`/`https`、
  必须能解析出主机名、禁止用户名密码 / 查询串 / 片段，以及空白、控制字符与 `"` `'` `` ` `` `\` `#` `$` `;` `|` `&` `@`，
  长度 ≤ 200，尾斜杠归一化。自定义源是管理员填的，但它要进 argv 与配置文件，写时校验、用时再规范化，两道都要。

### 修复

- **pnpm 系根本不读环境变量，光注入 env 覆盖不到它**：pnpm 11 的 registry 只来自它自己的全局配置
  `~/.config/pnpm/config.yaml`——容器里没有任何 `.npmrc`，`npm_config_*` 对它完全无效
  （实测 `NPM_CONFIG_REGISTRY=… pnpm config get registry` 被忽略）。所以面板在启动时与每次保存后**重写这份文件**
  （`dsh/PnpmConfigFile`）：逐行保留其它设置（`storeDir` 不会丢，本来缺了还会按 `PNPM_STORE_DIR` 补一行）、
  只重写 `registry` 一行，值经 YAML 引号、临时文件 + 原子替换、权限 0600。同时给子进程注入
  `npm_config_registry` / `NPM_CONFIG_REGISTRY`（npm 系读它），并给面板自己起的 pnpm 显式加 `--registry=`
  （dsh 安装、ZCode 构建，日志里看得见）。优先级定为**面板设置 > 环境变量 `NPM_CONFIG_REGISTRY` > 默认官方**，
  预设「跟随部署环境」就是显式回退到后两者。
- **ZCode 构建报 `Could not find any Python installation` / `Unable to detect compiler type`**：
  `Dockerfile` 运行时阶段补上 `python3` / `make` / `g++`——ZCode 依赖里的 `node-pty`、`cpu-features`
  要现场编译。这不是用户环境的问题，镜像里没编译链，面板自己那次也栽在同一处。
- **Electron 的 postinstall 卡死**：构建器按所选源设置 `ELECTRON_MIRROR`（`npmmirror` 预设带
  `https://npmmirror.com/mirrors/electron/`）——它要从 GitHub releases 下约 100 MB 二进制，实测直连 20 秒 0 字节。
- **构建里还有两个环节不在 registry 的覆盖范围内，都不是「换源」能解决的**：
  ① node-gyp 编译原生模块前要从 `nodejs.org` 下一份 10 MB 的 Node 头文件，那个域名跟 npmjs 一样慢
  （实测 104 KB/s，npmmirror 的 Node 镜像 5.7 MB/s），而头文件缓存在 `~/.cache/node-gyp` —— **不在卷里**，
  所以每次重建容器后第一次构建都要重下；现在用镜像源时会同时设 `NODEJS_ORG_MIRROR` / `npm_config_disturl`
  指向镜像（用官方源的机器保持 node-gyp 默认——能连 npmjs.org 的机器也能连 nodejs.org）。
  ② node-gyp 默认**不给 `make` 加 `-j`**，而构建跑在一台 12 核的机器上：现在按 CPU 数设
  `JOBS` / `npm_config_jobs`。顺带，ZCode 构建的子进程现在会继承启动器给**所有**子进程的那套环境
  （代理、registry、上面这些旋钮）——此前它一个都没继承，面板里配的代理对构建完全无效。
- **构建失败的证据被删掉**：`finally` 里改成失败时**保留**源码树与 `build.log`（原来失败也照样删干净），
  排查完不用重下，下一次构建开始时才清。
- **ZCode 干净检出必然失败于 `Missing @zcode/shared dist files`**：上游 `packages/shared` 没有 build 脚本，
  报错让你跑的 `pnpm build` 也不会编译它（`pnpm -r build` 跳过没有该脚本的包），只有项目引用构建（`tsc -b`）
  会产出 `dist/index.js`——上游开发者本地「碰巧」跑过根 `typecheck` 脚本（它列了 `packages/shared`）才有这个文件。
  构建器因此在装完依赖后补一步 `pnpm exec tsc -b packages/shared` 并检查产物存在，再继续打包。
- **删掉了构建器往源码目录写 `supportedArchitectures` 的「平台过滤」**：pnpm 是从 `pnpm-workspace.yaml`
  读这个设置、**不看 `.npmrc`**，所以它从未生效；而一旦生效就会把构建搞坏——组包阶段要为 6 个平台
  （darwin/linux/win × x64/arm64）收集原生资源，缺一个直接失败。上游 `pnpm-workspace.yaml` 已经写着它需要的平台。
- 文档修正：README 原来说「pnpm 11 只从 `.npmrc` 读认证与 registry」，实测 registry 来自 pnpm 自己的
  `config.yaml`，且 `npm_config_*` 对 pnpm 无效——README 与 `docs/zcode-experimental.md` 一并改正，
  并把「面板设置优先、环境变量只是回退」写进环境变量表、常见问题、功能清单与部署说明（`docs/deployment.md`
  新增「运行期下载源（面板设置）」一节，讲清与构建期 `NPM_REGISTRY` 的区别与改写 `config.yaml` 的后果）；
  常见问题里「要用编译器就自己加 `build-essential`」一条随编译链内置一并更新，`docs/ui-spec.md` 把下载源卡片
  从占位补成实际控件（含窄屏行为），差异清单登记为第 4 处。
- **Node 探测失败被当成「没装 Node」**（`dsh/DshNodeRuntime`）：探测内部要 spawn `node --version`，
  负载高时那一次 spawn 失败就报成「Node.js was not found on PATH」——CI 上偶发红、重跑即过的那两条
  插件用例正是同一个原因。现在版本探针有一次重试，成功的探测结果缓存 60 秒（失败不缓存，装好工具链
  立刻可用），顺带每次启动少 spawn 两三个进程。
- **测试不再依赖 stub 的可执行位**：本仓库 `core.fileMode=false`，git 根本不记录文件模式，CI 检出后
  假 pnpm 没有可执行位就卡在「起不来」；测试改为复制到临时目录再 `setExecutable`，索引里也用
  `update-index --chmod=+x` 把 100755 钉住。

### 验证

- 全量 **483 用例 / 0 失败 / 0 错误 / 0 跳过**（含新增的 `NpmRegistryTest`、`PnpmConfigFileTest`、
  `RegistrySettingsApiTest`，以及 `SettingsPersistenceTest` 与 `ZcodeBuilderTest` 的扩展）。
- `npm run typecheck` 与 SPA 构建通过（`vite build` 不做类型检查，单独跑过）。
- 独立端口 + 独立卷的临时容器（HTTP）跑完整验收：
  - **界面**：预设下拉列出 6 项（含服务端下发的实测速度注记「本机实测约 25 MB/s」）；切到「自定义…」后手填框
    才出现；非法地址 `https://a b/` 当场红字「地址里不能有空格」且保存置灰；测速按钮回「约 53 ms」；
    保存后「当前生效」显示 *面板设置* + `https://registry.npmmirror.com`；桌面 1440×900 与手机 390×844
    均无横向溢出（`scrollWidth === innerWidth`）。
  - **接口**：四条注入尝试（换行注入 `https://x/\nstoreDir: /etc`、`javascript:alert(1)`、带空格、
    带用户名密码）全部 **400** 且设置保持不变；自定义地址的尾斜杠被归一化；`POST /test` 回
    `{"ok":true,"status":200,"millis":43}`。
  - **落盘**：`launcher-settings.json` 出现 `npmRegistryPreset` / `npmRegistry`；容器内
    `pnpm config get registry` 回**新值**（pnpm 不读环境变量，所以这只能来自重写的那份配置）、
    `pnpm config get store-dir` 仍是共享仓库（`storeDir` 没被重写丢掉）、配置文件权限 0600；
    `DshDoctor` 报 `npm registry: https://registry.npmmirror.com  (setting)`。
  - **ZCode**：从面板设置取源（`pnpm install --registry=https://registry.npmmirror.com`）跑完整条构建，
    **6 分半**出 `zcode-3.14.3.tar.gz`（带 sha256）并装成 release；`/data/zcode/current` 指向它、
    `bin/zcode.mjs` 可执行、面板 `GET /api/zcode/releases` 列出 `3.14.3  current=true`。
- **ZCode 反代**（本机实跑面板 + stub 发行包）：未登录 `/i/<id>/` → **401**；登录后 → **200**（实例页面）；
  `/i/<id>/some/route?x=1` 路径与查询串原样透传；裸挂载 `/i/<id>` → **301**；未知 id → **404**；
  停止后 → **502**；实例进程 argv 确认为 `--host 127.0.0.1 --no-token`（对外只有面板这一条路）。
- **界面**：桌面 1440×900 与手机 390×844 截图核对 ZCode 分区（发行包识别、构建对话框的状态与实时日志、
  实例卡片操作），移动端 `scrollWidth === innerWidth` 无横向溢出。

## v0.2.0 — 2026-10-05 · models.dev 模型目录

中等规模的功能更新（minor：0.1 → 0.2）。账户页接上 [models.dev](https://models.dev)
目录（226 家供应商 / 约 3500 个模型）：供应商可搜索、可刷新，选定后端点与协议自动
带出；「默认模型」从手写文本框变成可搜索下拉（副标题带上下文与价格），手输一直保留。

### 新增

- **模型目录** `dsh/DshModelCatalog`：读 `https://models.dev/api.json`（`-Dhdsl.modelCatalog`
  可指向别处，测试与内网代理用它），先解析再落盘——读不动的文档不会覆盖可用副本；
  缓存 `/data/hdsl/catalog/models-dev-<sha256(url) 前 16 位>.json`（与插件目录同一套
  命名与目录，尊重自定义缓存目录）。协议只认能映射到 `DshVendor.APIS` 三种的，
  认不出返回 null——界面禁用该卡片，不假装可用。
- **`/api/models/*`**（`ModelsApiServlet`）：
  - `GET /api/models/providers[?refresh=1]`：dsh 自家目录（offered 13 家 + harness 37 家
    + 本机自定义）与 models.dev 合并，**在服务端合并**；TTL 12 小时，`?refresh=1` 绕过；
    整个目录拉不到且磁盘也没有副本时 502，有副本时降级为 `source:"cache"` 并只保留
    5 分钟（过后再试上游）。
  - `GET /api/models/providers/{id}`：单家供应商的模型（窗口 / 输出上限 / 推理档位 /
    能力 / 价格）；dsh 认识而目录没有 → 200 + 空模型数组，界面退回手输，不是 404。
- **`ModelSelect`** 组件：受控的「输入框 + 过滤下拉」，首项固定「留空（由 harness
  决定）」——不填才是安全默认，写错模型名会让 harness 拒绝启动；目录不可用 / 这家
  没有模型时退化成普通输入框，不挡路、不弹错；列表异步到齐时会自己弹出。
- **账户页**：供应商步骤加搜索框 + 刷新按钮 + 计数；卡片网格去掉写死的 `minWidth: 420`
  （360px 屏必然横向溢出）改自适应 + 内部滚动；编辑弹窗同样列出模型。
- 数据文件 `assets/models-dev-aliases.txt`（dsh id ↔ models.dev id 别名表），其余靠
  host 自动命中（fireworks→fireworks-ai、kimi-coding→kimi-code-plan-cn 等）。
- 测试 16 条：`DshModelCatalogTest`（纯解析 + 容错 + 别名/host/id 三条匹配路）、
  `ModelsApiTest`（真服务器 + JDK `HttpServer` 假目录：合并名单、刷新真重抓、失败退回
  磁盘副本、无副本 502、字段裁剪）、`AccountApiTest` 三个新用例。

### 改动

- `POST /api/accounts` 新增可选 `protocol`，只对目录里**新发现**的供应商生效（dsh 自带
  的以 dsh 的为准；已有同 id 的自定义厂商原样返回）——否则 `@ai-sdk/anthropic` 那几家
  会被按 `openai-completions` 写进路由，而错误协议是启动时才失败的静默故障。
- `DshVendor`：新增 `keyVariableOf(id)` 与 `discovered(id, name, baseUrl, api)` 重载；
  密钥环境变量名仍按 harness 的推导，models.dev 的 `env` 只作界面提示（两者确实不同，
  如 ZHIPU_API_KEY vs ZAI_API_KEY）。
- 刻意**不碰启动链路**：`DshAccountRoute` 里的硬编码与 ACP 注入一行未动，本版只动界面
  与它背后的目录。
- 文档：`docs/ui-spec.md` 账户页一节改写 + 新增「模型目录（models.dev）」小节；
  README 补 `/data/hdsl/catalog/` 一行与功能清单；新增
  [`docs/session-handoff-2026-10-05.md`](docs/session-handoff-2026-10-05.md)。

### 验证

- 后端 **443 用例 / 0 失败 / 0 错误 / 0 跳过**（基线 427 + 新增 16）；`npm run typecheck`
  与 SPA 生产构建通过。
- 独立端口 + 独立卷的临时容器真拉 models.dev：名单 **228 家**（dsh 认识 29 + 目录独有
  199，17 家因签名体制类协议禁用）；`?refresh=1` 后 `fetchedAt` 变化；openrouter
  390 个模型；走完「搜 Moonshot → 选供应商（端点自动带出）→ 下拉选 kimi-k2.6 → 保存」
  全流程，落盘与卡片显示均正确；手机 390×844 供应商弹窗与模型下拉无横向溢出。

## v0.1.61 — 2026-10-04 · 关于页改讲本项目

小改动：关于页不再照搬上游（HMCL）的版权与作者，改讲 HDSL-web 自己；
版本号随构建写入「项目」一行——本版起显示 v0.1.61。

### 修复

- **关于页不再冒充上游**：原来照搬桌面版 HDSL 的关于页，显示的全是 HMCL 的数据——
  版权「© 2013-2026 huangyuhui 及贡献者」、作者「bilibili @huanghongxun」、
  开源链接指向 HMCL 仓库，等于把别的项目的数据当成自己的。现在讲本项目：
  项目（`HDSL-web v<服务端版本>`，取自 `GET /api/health`）、说明、仓库链接、
  许可证（GPL-3.0）、上游（HDSL · HMCL，完整声明见仓库 NOTICE）、技术栈；
  顺手修掉上游行重复的 "HDSL" 与窄屏下仓库链接断行。

### 其他

- CI 测试失败时打印完整异常（`exceptionFormat=FULL`）：上次 `PluginApiTest` 偶发失败
  （本地 10/10 通过、重跑即过），日志里只有一行行号、断言消息被吞，无法定位；
  此后失败会带上完整消息。

### 验证

- 全量 **427 用例 / 0 失败 / 0 跳过**；`npm run typecheck` 与 SPA 构建通过。
- 实机截图：本机构建的 v0.1.61 起服务，桌面 1440×900 与手机 390×844 的关于页均显示
  `HDSL-web v0.1.61`，各条目与仓库信息一致。

## v0.1.6 — 2026-10-04 · 手机端适配

面板现在可以拿手机用了：侧栏抽屉、横向标签条、贴底启动栏，主屏一屏启停；
顺带修好深链接白屏，以及 ACP 控制台「对话报缺密钥」。

### 新增

- **手机端适配**（`web/src/styles/mobile.css`，桌面端零变化）：
  - 移动判定 `(max-width: 760px), (max-height: 520px)`——后者覆盖横屏手机；
    触摸优化判定 `(hover: none) and (pointer: coarse)`——与宽度无关，平板同享。
  - 主页侧栏收进抽屉（汉堡唤出、遮罩，点条目 / 遮罩 / 路由变化都收起）；
    内容页子侧栏改为顶部横向可滑标签条；启动面板改为贴底整条操作栏
    （主钮 56px 高、避让安全区，进度浮层改为底部卡片）。
  - 主页补「当前实例」卡片：手机上一屏看到状态并启停 / 打开 dsh。
  - 支持添加到主屏幕：`manifest.json` + apple-mobile-web-app 元信息、`viewport-fit=cover`。
- `useMobileLayout()`（`useSyncExternalStore` 订阅 `matchMedia`），判定条件与 CSS 两处一致。

### 修复

- **深链接白屏**：vite 的 `base` 由相对 `./` 改为 `/`——此前直接打开或刷新 `/instances/xxx`
  时 `./assets/…` 解析成 `/instances/assets/…`，命中 SPA 兜底返回 HTML，模块脚本被浏览器
  拒绝执行，整页空白。
- **控制台对话报缺密钥**：ACP 控制台启动时也把实例指定的账户交接给它——路由写进
  `profiles/acp` 自己的补丁层、密钥放进子进程环境，停止 / 失败 / 退出时收回；与启动路径
  共用同一份账号解析。刻意不碰每个 home 一份的 `settings.yaml` / `.hdsl-injected.json`。
- `StaticHandlerTest` 的资源断言跟随 `base: "/"`（此前写死了 `./assets/`）。

### 验证

- 全量 **427 用例 / 0 失败 / 0 跳过**；`npm run typecheck`（noUnusedLocals）与 SPA 构建通过。
- 4 个视口 11 张截图逐张核对（390×844 / 360×800 / 844×390 / 1440×900），每张断言无横向
  溢出；桌面版式与改造前一致。

## v0.1.5 — 2026-10-03 · 修改用户名

用户系统补上「修改用户名」：改完不用重新登录，本设备的会话直接切到新名字，
其它设备上的会话照旧退出——与改密码同一套「本机保留、其余下线」的语义。

### 新增

- `POST /api/auth/username`：修改当前登录用户的用户名。口令、盐、PBKDF2 迭代次数与创建时间都不动，
  只改 `users.json` 里的名字（仍是原子 0600 写入）；发起改名的这个会话保留并切换到新用户名，
  该用户的其它会话全部吊销。未登录 401，非法名字（空白 / 超长 / 空）400。
- 设置页新增「修改用户名」卡片（在「修改密码」下方）：1–32 位且不含空白字符，
  成功后面板右上角的用户名即时更新，失败弹出原因。
- 回归测试 `usernameChangeRenamesLoginAndRevokesOtherSessions`：覆盖匿名 401、非法名字 400、
  改名后 `whoami` 返回新名、另一个会话被吊销、旧名不再能登录、新名用原密码可以登录、新名字已落盘。

### 验证

- 全量 **422 用例 / 0 失败 / 0 跳过**（较 v0.1.4 新增 1 条）；`npm run typecheck` 与 SPA 构建通过。

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
