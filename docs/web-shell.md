# Web Shell：实例级原地终端

> 状态：已上线。v0.5.3 起它是面板里唯一的 TUI 入口（SSH 通道已移除）。v0.5.71 起覆盖
> 五个品类：**dsh**、**Kimi Code**、**OpenCode**、**Claude Web**（社区引擎 / 官方
> Claude Code 两个内核由用户选）与 **ZCode**（实验性品类）。

## 1. 是什么

在实例上点「终端」，**就地**在当前界面打开该实例的原生 TUI，走现有 WebSocket。
不新增端口、不引入 SSH 协议栈、不需要反向代理。

终端以**实例**为单位启动：同一份 home、账号/模型路由、工作区，所以 TUI 里的会话记录、
能 resume 的会话，与网页模式看到的是同一份数据。

## 2. 不是什么

- 不是通用 shell：没有自由命令、文件上传下载、端口转发、切换用户。
- 不是 SSH 的替代协议：SSH 通道已在 v0.5.3 移除，面板里唯一的终端入口就是这个 Web Shell。
- 不是沙箱：agent 以面板进程的用户身份运行。

## 3. 使用流程

```
实例卡片/详情页  [终端]
      │  web 运行中则先停 web（记 terminal.switch）
      ▼
  原地覆盖层（桌面）/ 全屏 sheet（手机）
      │  xterm.js ←WSS→ 服务端 PTY → kimi / opencode / dsh（已装的 TUI 插件）
      ▼
  底栏：实例状态 + [启动] / [停止]
```

- `/exit`、进程退出、崩溃 → 清屏关窗，恢复之前的 web 模式。
- 关窗 / 刷新 / 断网 → 只 detach，进程继续跑；重新打开即 attach 并回放最近输出。
- 底栏「停止」= 紧急杀进程（复用实例停止机制），不是关窗。
- 手机形态（窄屏或触屏）：覆盖层全屏铺满，窗口高度钉在 `visualViewport` 上——软键盘
  起落、地址栏伸缩都不遮挡输入行（iOS 上软键盘只改 visualViewport）；刘海与手势条走
  `env(safe-area-inset-*)`。终端与底栏之间有一排功能键（Esc、Tab、Ctrl+C、Ctrl+D、
  Ctrl+Z、方向键），按下即发对应 PTY 字节——手机软键盘发不出这些键，而 WS 二进制帧
  本来就是原始字节（terminal-protocol.md §3），协议与服务端都不用动。
- 不做空闲超时；终端一直挂着不自动回收。终端 WebSocket 显式 `setMaxIdleTimeout(0)`——Jetty 默认的
  30 秒曾把静默终端以 `1001 Connection Idle Timeout` 关掉，看起来就像"终端自己断了"（v0.5.81 修）。

## 4. 安全不变量

1. **不经过 shell 启动**：`posix_spawn` 直接 exec `node <品牌入口 JS>`，不复用 `.bin` shim；
   启动后断言根进程不是 `sh`/`cmd`。
2. **根进程就是 agent**：`waitpid` 返回后的顺序是**先拒绝输入**（`inputClosed`）、
   给泵一个有界的时间排空最后几个字节（排到 EOF 即止，超时只是兜底），**再 `close(master)`**
   ——此后不再有输出可被观察到——最后 `kill(-pgid)`、移除会话、通知 WS、审计。
   排空之所以在关 master 之前，是因为泵手里那块已经读出、还没交给读者的字节属于读者，
   关掉就丢尾（旧文写的"关 master 之前不做任何别的事"与实现不符，此处按实现纠正）。
   master 的**描述符**另有规矩：任何调用都不直接用它，每次 `poll`/`read`/`write`/`ioctl`
   各持一份副本（`fcntl(F_DUPFD_CLOEXEC)`，一次调用完成复制并带 CLOEXEC）——描述符号会被内核复用，
   一个已经通过检查、正在内核里的调用若继续用原号，读到的可能是**另一个实例**终端的输出，
   或者把按键写进别人的 PTY。两个描述符都在 `openpty` 返回后**立刻**带上 `FD_CLOEXEC`，
   子进程的文件动作里也关掉它手里的 slave 副本：否则一个孙进程会一直握住 pty，
   master 就永远等不到 EOF（排空会退化成等超时）。
3. **WS 只搬运字节**：没有 exec / send-keys / 切换程序的接口。
4. **进程组隔离**：`POSIX_SPAWN_SETPGROUP`（pgroup=0），杀树用 `kill(-pgid)`；
   窗口尺寸写 `TIOCSWINSZ`，**SIGWINCH 由面板自己发**（`resize` 里对根进程 `kill(SIGWINCH)`）——
   子进程没有控制终端（未做 `TIOCSCTTY`），内核没有可通知的前台进程组，只写 `TIOCSWINSZ`
   会改尺寸但不通知 TUI（旧文写成"由内核发送"，与实现不符）。
5. **权限**：打开终端需要会话 + 开发者密码提权（会话级 30 分钟滑动）；argv 白名单；
   单实例单会话；审计 `terminal.open` / `terminal.exit` / `terminal.kill` /
   `terminal.desktop` / `desktop.reveal`。
6. **ticket**：WebSocket 用一次性的 `/ws/terminal/{ticket}` 鉴权，ticket 只对一次 attach、
   一个实例有效，30 秒后失效；会话 Cookie 不进 URL，避免落进历史记录与代理日志。

**做不到的**：agent 自带 shell 功能不归本层管；`setsid` 逃出进程组的孤儿 `kill(-pgid)`
清不掉（master 已关，无法再交互），彻底清理需 cgroup v2，列为后续加固。

### 4.1 威胁模型：入口即 RCE，本地权限不是边界

Windows 桌面终端不增加**本地**攻击面：能坐到这台机器前、亲手开 `cmd`、亲手 `java -jar`
的人，本来就能直接执行同样的命令；面板再弹一个控制台，对本地权限是净零。降低的只是
「本地用户需要被面板防住」这个假设，**不是鉴权**。

真正被改变的是入口的位置：`POST /api/brands/{brand}/instances/{id}/terminal` 能启动
一个执行 agent 的进程，屏幕还能交互——**在效果上等价于远程代码执行**。因此：

- 端点仍走 `/api/*` 的会话门（`AuthFilter`），没有会话一律 401；
- 还要开发者密码提权（会话级 30 分钟）；没有提权 403 `developer_elevation_required`；
- Windows 桌面启动额外要求字面确认 `{"confirm":"open-desktop-terminal"}`，并记审计
  `terminal.desktop`（谁、从哪个地址）；这是对「只偷到 cookie」的减速带，不是加密证明；
- **部署前提**：面板只监听 loopback 或可信网络；要暴露公网，必须在网络层加保护
  （反代鉴权/VPN/白名单），或者干脆不开终端按钮。文档明确写这条，而不是拿
  「Windows 有桌面」当安全边界；
- 反过来，Linux 的 PTY 通道同样受这套入口约束：会话 + 提权 + 单实例单会话。

### 4.2 总开关与开发者模式

设置页的「开发者模式」页本身也要开发者密码，进去后有两个控件：

- **Web Shell 总开关**（写入 `server.yaml` 的 `web_shell.enabled`，环境变量
  `HDSL_WEB_SHELL_ENABLED` 可覆盖）：关掉后 `POST .../terminal` 返回
  403 `web_shell_disabled`，实例列表不再显示终端按钮；已经在运行的终端不受影响，
  可以从自己的窗口里停止。开关本身要求开发者密码提权 + HTTPS 才会被接受。
- **修改开发者密码**：需要当前开发者密码 + 面板密码（与设置时同一套确认）；成功后
  所有已提权的会话立即失效，需要重新验证。
- **提权时长**：提权默认 30 分钟滑动续期；开发者模式页和 hdsl-tui 的「开发者提权」入口
  都可以把它**延长到 3 小时后**（`POST /api/developer/extend`，要求当前已提权），也可以
  **随时停止**（`POST /api/developer/revoke`，幂等，不等到期）。`GET /api/developer/status`
  的 `expiresAt` 告诉两端还剩多久。

### 4.3 强制 HTTPS 与自签名权宜

开发者模式和终端都只在面板**此刻真的在讲 TLS** 时可用（读的是主连接器，不是配置里的
`https.enabled`）：

- HTTP 下进入「开发者模式」会直接弹出「当前环境不安全」的提示，并指引到「HTTPS 证书」；
  终端按钮在实例列表里隐藏，直接调 `POST .../terminal` 返回 403 `insecure_transport`。
- 没有证书时可以选择生成自签名证书：`POST /api/settings/tls/self-signed` 生成（或复用）
  `<data>/certs/self-signed*.pem`，把 `https.enabled` 写进 `server.yaml`，在同一端口热切换
  连接器。浏览器会提示证书不受信任，这是预期行为；它只适合内网或临时使用。
- 「HTTPS 证书」页顶部同样有红框说明，并在 HTTPS 未启用时提供同一个按钮；已经配置了真实
  证书（`https.cert_file` / `https.pkcs12_file`）时，后端拒绝回退到自签名
  （409 `certificate_already_configured`），避免误把线上身份换掉。
- **反代终结 TLS** 也算：打开 `https.trust_forwarded_proto` 后，请求带
  `X-Forwarded-Proto: https`（nginx/Caddy 的常规配置）即视为 HTTPS，开发者模式、终端和
  `Secure` 会话 Cookie 都按 HTTPS 处理。这个信任是显式开关，默认关闭——直接能连到面板
  HTTP 端口的客户端可以自己写这个头。已经是这种部署时，不要再生成面板自己的自签名证书，
  接口会以 409 `tls_terminated_upstream` 拒绝，免得把反代到面板这段改坏。

## 5. 架构

```
web/terminal/                 常置包，不依赖 MINA
  TerminalTarget              实例 → home/cwd/env/argv
  BrandTui                    KimiTui / OpencodeTui；ClaudeWebTui 带内核参数（BrandTui.forBrand 故意不返回它）
  ZcodeTui                    ZCode 的官方 TUI（不带 --web 的 bin/zcode.mjs）
  InstanceTerminal            三个品类共用的门禁 / Windows 分支 / spawn / ticket
  TerminalSink                一个挂载者（生产是 WebSocket，测试是收集器）
  TerminalSession             PTY 进程 + 回放缓冲 + attach/detach + 退出
  TerminalManager             按 brand + instanceId 互斥、open/terminate、审计
  TerminalBusyException       同实例第二个终端
  pty/                        伪终端胶水（JNA）
    Pty / PtySize / PtyException / PosixPty
web/http/InstancesApiServlet   dsh 的 POST/DELETE /api/instances/{id}/terminal
web/brand/BrandApiServlet      品牌的 /api/brands/{brand}/instances/{id}/terminal 与 …/terminals（内核清单）
web/zcode/ZcodeApiServlet      ZCode 的 /api/zcode/instances/{id}/terminal
web/http/DshApiServlet         GET /api/dsh/tui-plugins（精选 TUI 终端插件清单，见 DshTuiPlugins）
web/http/InstancesApiServlet   /api/instances/{id}/tui-plugins（每个插件自己的 profile 上的状态与安装/卸载）
web/ws/TerminalWebSocket       WSS /ws/terminal/{ticket}
```

## 6. PTY 胶水（JNA）

- 依赖只有 JNA（LGPL-2.1/Apache-2.0 双许可，按 Apache-2.0 使用，与 GPL-3.0 兼容）。
  `build.gradle.kts` 头部的「no JNA」改为「唯一原生依赖是 JNA，仅用于 PTY 胶水」。
- Linux/macOS：`openpty` + `posix_spawn`；
  file actions：`adddup2(slave,0/1/2)`、`addclose(master)`、`addchdir_np(cwd)`；
  attr：`POSIX_SPAWN_SETPGROUP` + pgroup=0。
- 不在 JVM 里 `fork()`；不注册 JNA 回调；泵线程 `poll` + 关闭标志；
  `read` 遇 `EIO` 视为子进程结束；`waitpid` 独立线程收尸。
- Windows：不用 ConPTY。`Pty.spawn` 在 Windows 抛 `PtyException`；品牌终端改走
  `DesktopTerminal`（§7.1），在桌面会话里打开真正的控制台窗口。

## 7. 实施阶段

| 阶段 | 内容 | 状态 |
|---|---|---|
| 0 | 分支、基线、kimi/opencode 会话存储 spike | 完成（源码核对） |
| 1 | 品牌无关的终端核心（`TerminalTarget` + `BrandTui`） | 完成 |
| 2 | PTY 胶水（JNA） | 完成 |
| 3 | `TerminalSession` / `TerminalManager` | 完成 |
| 4 | HTTP 与 WebSocket（ticket、`/ws/terminal`、Windows 桌面终端） | 后端完成，见 §10 缺口 |
| 5 | xterm 前端 + 底栏启停 | 完成 |
| 6 | dsh 适配 | 完成（dsh 终端） |
| 7 | ~~Windows ConPTY~~ | **取消**：Windows 用桌面终端（见 §7.1） |
| 8 | 移除 SSH 通道（v0.5.3） | 完成：`web/ssh`、MINA 与 eddsa 已删除 |
| 9 | ZCode 终端（v0.5.71） | 完成：`ZcodeTui`、`/api/zcode/instances/{id}/terminal` |
| 10 | Claude Web 双内核（v0.5.71） | 完成：社区引擎 / 官方 Claude Code，`…/terminals` + `?kernel=` |
| 11 | 门禁收口与打开顺序（v0.5.71） | 完成：`InstanceTerminal`；先密码对话框、必要时选内核，再出终端窗口 |

品类覆盖顺序：**kimi → opencode → dsh → zcode → claude-web（双内核）**。

### 7.1 Windows：用桌面的终端，不做浏览器 PTY

裸部署的 Windows 有桌面和自己的 shell，不需要在浏览器里再复制一个 PTY（服务进程也不共享
交互桌面）。因此 Windows 上点「终端」时，面板进程（就跑在那个桌面会话里）用 Java 直接
起一个控制台窗口，并把工作目录与 HOME 指到该实例：

- `DesktopTerminal` 在实例工作区写一个 `.cmd`：`cd /d <工作区>`、`set HOME=…`、再执行该
  品牌的 TUI 命令；
- 通过 `cmd.exe /c start … cmd.exe /k <script>` 打开，窗口属于桌面，面板不代理键盘；
- API 返回 `{"mode":"desktop"}`，前端只显示「已在本机桌面打开终端」。

这条路径**故意**经过 `cmd.exe`：它启动的就是用户自己的终端；「不经 shell」的不变量只
约束 POSIX 的 Web 终端路径。Windows 端也不额外假设密码限制——按部署现实，能坐到这台
桌面的人本来就能直接开命令行。

**面板必须跑在交互式会话里**（控制台或 RDP）：判断用 `SESSIONNAME`，服务（session 0）
下它不存在，`reveal` / 桌面终端返回 409，而不是静默弹一个看不见的窗口。把 Windows 当
服务器、只靠 RDP 运维时，终端窗口开在 RDP 会话里，前提是面板本身就在那个会话启动；
如果面板是服务，桌面功能不可用——这正是想要的，不在 session 0 里假装有桌面。

## 8. 验收

- kimi/opencode 终端里的会话列表与网页模式一致（spike 结论落文档）。
- `/exit`、崩溃、底栏停止、实例停止四条路径都杀净进程树；进程树无 `sh`/`cmd`。
- 关窗/刷新后进程仍在，重开能接上并看到历史输出。
- agent 退出后写 PTY 不会到达 WS；会话移除，attach 返回 410。
- 同实例第二个终端 409；未提权 403。
- jar 里没有任何 SSH 代码与依赖；终端功能可用。

## 9. 品牌实测（阶段 0 spike）

已从源码核对（`kimi-code` / `opencode` 的仓库，以及 dsh 工作区里的 `zcode`、`claude-web-main` 两份上游源码）：

| 品牌 | TUI 命令 | 会话/配置目录 | 与 web 是否同一份 | resume 参数 |
|---|---|---|---|---|
| kimi | `node <包>/dist/main.mjs`（`kimi` 无子命令） | `KIMI_CODE_HOME` 或 `~/.kimi-code`；会话在 `<home>/sessions` | 是：`kimi web` 用同一 home | `-r/--resume [id]`、`-c/--continue` |
| opencode | 1.19+：`opencode-ai/bin/opencode.exe`（POSIX 也叫 .exe，ELF）；旧版：`opencode-<os>-<arch>/bin/opencode` 或 `@opencode/cli-*`。`bin/opencode.cjs` 只是启动器 | XDG：data `~/.local/share/opencode`、config `~/.config/opencode` | 是：同一 data 目录 | `--session <id>`、`--continue` |
| dsh | 终端由社区插件提供（dsh 自身只有 WEB/HEADLESS/ACP/SDK 四种 surface）：**启动插件自己的 profile**，`dsh --profile tui`（上游把 `dsh tui` 也展开成它），bin 型可优先直接跑其入口。详见下方 | 实例 `DSH_HOME` | 是（已有 SessionCatalog） | `--resume <id>`（bin 型与纯插件都支持） |
| zcode | `node <发行包>/bin/zcode.mjs`（不带参数；CLI 默认命令是 `tui`） | `ZCODE_DATA_BASE_DIR` 下的 `.zcode/`；会话库跟 `homedir()` | 是：与 web 进程同一个 `ZCODE_DATA_BASE_DIR` 与 `HOME` | 第一版不做 |
| claude-web | 社区引擎 `node_modules/claude-web-engine/dist/cli-node.js`；官方 `@anthropic-ai/claude-agent-sdk-<platform>-<arch>/claude[.exe]`（缺失退回 PATH 上的 `claude`） | `~/.claude` 与 `~/.claude-web`（实例 home） | 是：网页工作台写的就是这两处 | 第一版不做 |

实现约定：这几家都由适配器解析成「node + 入口 JS」或「原生二进制」，**不经过
`.bin` shim / `.cjs` 启动器**；环境里 `HOME`（和 Windows 的 `USERPROFILE`）指向实例
home，因此 TUI 读到的就是网页模式写下的那份会话。

ZCode 是唯一的例外：它的会话库跟 `homedir()` 走，而凭据与供应商配置跟
`ZCODE_DATA_BASE_DIR` 走。为了让终端与 web 进程看到同一批会话，`HOME` **保持与 web
进程一致**（不指到实例目录），只额外设 `ZCODE_DATA_BASE_DIR`。

- kimi：pnpm 的 `.bin/kimi` 是 `#!/bin/sh` shim（不是软链），所以直接取
  `node_modules/@moonshot-ai/kimi-code/dist/main.mjs`；只有 npm 的软链布局才回退到
  `.bin/kimi`。
- opencode：1.19 起平台二进制由 `opencode-ai` 自己带（`bin/opencode.exe`，POSIX 同样
  叫 .exe 的 ELF）；先找它，再退回无 scope 的平台包与旧的 `@opencode/cli-*`。
- dsh（`DshTui` + `DshTuiPlugins`）：**终端是插件，跑在它自己的 profile 上**。上游把
  `dsh <name>` 展开成 `dsh --profile <name>`，所以 `dsh tui` 确实是上游入口——但 `tui` 不是
  内置模板名（模板只有 acp/web/headless/sdk/sdk-minimal），它要先被插件建出来。于是：
  - **装**：`POST /api/instances/{id}/tui-plugins/install` `{package}`，服务端按清单用
    `dsh plugin --profile <清单里的 profile> add <包@锁定版本>`（`GET …/tui-plugins` 读状态，
    每个插件从**它自己的 profile** 读，实例的 web profile 里本来就看不到它们）。
    **装完要验**：`pnpm` 先写依赖再取包，取不到时会留下"manifest 说有、`node_modules` 里没有"
    的半装状态——那正是"打开终端 0.4 秒死掉、一个字节都没有"的样子。所以 installer 在
    `dsh plugin add` 退出 0 之后还要看 `node_modules/<包>` 是否真的在（`DshPluginInstaller.requireLanded`；
    只验 registry 型 spec，`file:`/`link:`/`github:`/URL 的名字从文本里读不出来，误判会打断正常安装），
    不在就把任务判失败。这个状态**不再被当成已安装**：`terminalPlugin` 同时要求 manifest 声明
    与文件真的在（有 bin 的包还要求它声明的入口存在），否则用同一句可行动的中文拒绝
    （"声明了 X 但文件不在，请重新安装"），而不是去启动一个空 profile。
  - **还要配对宿主版本**：终端界面的**工具面来自 agent-preset 层**，而这一层是 dsh **0.2.0**
    才有的（`dsh-agent-preset-registry` + 每条 `preset` 一行）。写给它的插件（天枢 / tomowang /
    pi-tui）自带这一层，装在 0.1.x 实例上**装得上、起得来、但会话没有工具**——插件自己的原话是
    `agent-presets 未装配：本会话没有工具面`。所以清单每项都有 `requiresDsh`
    （天枢 `>=0.2.0-rc.1`、tomowang `>=0.2.1-rc.3`、pi-tui `>=0.2.1-rc.2`；`@deepseek-harness-tui/dsh-tui`
    的 peer 跨两条线，故为空=任意线），安装路由在提交任务**之前**就拒（`409` + 一句说清
    "需要 X、你的实例是 Y、工具面为什么没了、可以改用哪个"），`terminalPlugin` 在开终端时也拒
    （已装的旧组合）。清单行不会消失，只是**变灰不可装**并写明原因——隐藏会让人以为没这个功能。
  - **绝不能装进 web**：TUI 插件与 `dsh-web-app` 都是 app 插件，各按自己的语法解析同一份
    argv，上游不仲裁；同 profile 装一起会让应用遇不认识的旗标直接退出（社区实测
    `error: unknown option '--no-open'`），实例起不来。守卫在 `DshPluginInstaller.installSpecs`
    （拒绝发生在解析运行时之前，所有安装路径共用），`DshTuiPlugins.refuseAppProfile` 负责说明；
    卸载永不拒绝（那是把实例修回来）。
  - **起**：先按 `<DSH_HOME>/profiles/<插件 profile>/node_modules/<包>/<其 package.json 声明的
    bin>` 直接跑（bin 型，先读包自己的清单、再退回 `node_modules/.bin/<bin>`，且只接受解析后是
    JS 文件的软链——pnpm 写的是 `#!/bin/sh` shim，不能当根进程，理由同 kimi）；没有 bin
    （纯 bundle）就启动那个 profile：`node <bin.js> --profile <插件 profile>`。argv 里出现
    `--profile web`（例如用户在实例启动参数里自己写的）一律拒绝。
  - 一个都没装 → 明确报错并指向实例「安装」页；**声明了但文件不在** → 同样拒绝，但说的是
    "重新安装这个"。清单与许可证/仓库/profile 由 `GET /api/dsh/tui-plugins` 提供
    （`web/http/DshApiServlet`）；许可证含 Apache-2.0（`@huiliyi37/dsh-tianshu-tui`）。
    `@ruhooai/dsh-tui`（bin 需要 Bun + 外部 `cordis.yml`）与 `@zhangweiii/dsh-tui` 已不在清单里。
  - **终端死了要留话**：子进程非零退出且**一个字节都没输出**时，应用日志写一条
    （`TerminalSession`，实例 / 退出码 / 没有输出），退出帧带 `failedWithoutOutput`
    （`docs/terminal-protocol.md §3`），前端在"会话已结束 (N)"下面补一句可行动的提示。
    面板自己结束的会话不算——那个非零码就是要来的（143 = SIGTERM），诊断它只会变成噪音。

## 10. 已知缺口

- **web ↔ 终端互斥**：**两个方向都成立**——开终端会同步停掉同实例的 web 运行时（品牌
  `BrandRuntime.stop` / ZCode `ZcodeRuntime.stop` / dsh `DshProcessManager.stop` 都等
  进程树退出），**启动**/停止/删除实例也会先结束它的终端（品牌与 ZCode 一直如此，dsh
  原先只做了"开终端→停 web"这半个方向，现已补齐，顺序与理由见 §4）。
  停掉 web 后不会自动恢复——恢复动作交给底栏「启动」（实例启动按钮）。
- **refresh 重连**：运行中的会话会直接发新票据，浏览器刷新后能接回同一进程并回放；
  但两个窗口同时 attach 目前都会收到广播、输入也会同时写入——v1 不做单控制端仲裁。
- **Windows 便利功能**：`POST .../reveal`（`target=home|workspace|log`）用
  `explorer.exe` / `notepad.exe` 打开，路径由服务端按实例记录计算，请求只能点名
  三个目标之一；dsh 的日志在内存里，`log` 返回 400。
- **ZCode / Claude Web 的真机终端**：两个适配器的命令形状有单测锁定，但「TUI 到底能不能
  在 PTY 里起来、中文与 resize 是否正常」只能在 Linux/macOS 上对着真实发行包验证；
  Windows 面板本来就没有 PTY（走桌面控制台）。
- **Claude Web 的官方内核是否存在**取决于发行包里有没有 SDK 的平台可选依赖
  （`@anthropic-ai/claude-agent-sdk-<platform>-<arch>`），没有时退回 PATH 上的 `claude`；
  `…/terminals` 端点会把可用性与缺什么如实报出来。
