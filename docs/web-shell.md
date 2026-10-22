# Web Shell：实例级原地终端

> 状态：实施中（分支 `web-shell`）。本文先是设计文档，功能上线后会改成功能介绍。
> 主要适配 **Kimi Code** 与 **OpenCode** 的原生 TUI；dsh 以网页为主，TUI 是插件能力，
> 列为次要目标。

## 1. 是什么

在实例上点「终端」，**就地**在当前界面打开该实例的原生 TUI，走现有 WebSocket。
不新增端口、不引入 SSH 协议栈、不需要反向代理。

终端以**实例**为单位启动：同一份 home、账号/模型路由、工作区，所以 TUI 里的会话记录、
能 resume 的会话，与网页模式看到的是同一份数据。

## 2. 不是什么

- 不是通用 shell：没有自由命令、文件上传下载、端口转发、切换用户。
- 不是 SSH 的替代协议：SSH 保留为 `-PwithSsh` 构建里的「万不得已」通道，默认构建不含。
- 不是沙箱：agent 以面板进程的用户身份运行。

## 3. 使用流程

```
实例卡片/详情页  [终端]
      │  web 运行中则先停 web（记 terminal.switch）
      ▼
  原地覆盖层（桌面）/ 全屏 sheet（手机）
      │  xterm.js ←WSS→ 服务端 PTY → kimi / opencode / dsh tui
      ▼
  底栏：实例状态 + [启动] / [停止]
```

- `/exit`、进程退出、崩溃 → 清屏关窗，恢复之前的 web 模式。
- 关窗 / 刷新 / 断网 → 只 detach，进程继续跑；重新打开即 attach 并回放最近输出。
- 底栏「停止」= 紧急杀进程（复用实例停止机制），不是关窗。
- 不做空闲超时；终端一直挂着不自动回收。

## 4. 安全不变量

1. **不经过 shell 启动**：`posix_spawn` 直接 exec `node <品牌入口 JS>`，不复用 `.bin` shim；
   启动后断言根进程不是 `sh`/`cmd`。
2. **根进程就是 agent**：`waitpid` 返回后**先 `close(master)`**，再 `kill(-pgid)`、
   移除会话、通知 WS、审计。关 master 之前不做任何别的事。
3. **WS 只搬运字节**：没有 exec / send-keys / 切换程序的接口。
4. **进程组隔离**：`POSIX_SPAWN_SETPGROUP`（pgroup=0），杀树用 `kill(-pgid)`；
   窗口尺寸只写 `TIOCSWINSZ`，SIGWINCH 由内核发送。
5. **权限**：打开终端需要会话 + 开发者密码提权（会话级 15 分钟滑动）；argv 白名单；
   单实例单会话；审计 `terminal.open/attach/detach/exit/kill/denied`。
6. **token**：终端会话存在期间该会话 token 按 3 小时滑动续期；结束终端恢复默认策略。

**做不到的**：agent 自带 shell 功能不归本层管；`setsid` 逃出进程组的孤儿 `kill(-pgid)`
清不掉（master 已关，无法再交互），彻底清理需 cgroup v2，列为后续加固。

### 4.1 威胁模型：入口即 RCE，本地权限不是边界

Windows 桌面终端不增加**本地**攻击面：能坐到这台机器前、亲手开 `cmd`、亲手 `java -jar`
的人，本来就能直接执行同样的命令；面板再弹一个控制台，对本地权限是净零。降低的只是
「本地用户需要被面板防住」这个假设，**不是鉴权**。

真正被改变的是入口的位置：`POST /api/brands/{brand}/instances/{id}/terminal` 能启动
一个执行 agent 的进程，屏幕还能交互——**在效果上等价于远程代码执行**。因此：

- 端点仍走 `/api/*` 的会话门（`AuthFilter`），没有会话一律 401；
- 还要开发者密码提权（会话级 15 分钟）；没有提权 403 `developer_elevation_required`；
- Windows 桌面启动额外要求字面确认 `{"confirm":"open-desktop-terminal"}`，并记审计
  `terminal.desktop`（谁、从哪个地址）；这是对「只偷到 cookie」的减速带，不是加密证明；
- **部署前提**：面板只监听 loopback 或可信网络；要暴露公网，必须在网络层加保护
  （反代鉴权/VPN/白名单），或者干脆不开终端按钮。文档明确写这条，而不是拿
  「Windows 有桌面」当安全边界；
- 反过来，Linux 的 PTY 通道同样受这套入口约束：会话 + 提权 + 单实例单会话。

## 5. 架构

```
web/terminal/                 常置包，不依赖 MINA
  TerminalTarget              实例 → home/cwd/env/argv
  BrandTui                    KimiTui / OpencodeTui（dsh 的 DshTui 在阶段 6）
  TerminalSink                一个挂载者（生产是 WebSocket，测试是收集器）
  TerminalSession             PTY 进程 + 回放缓冲 + attach/detach + 退出
  TerminalManager             实例级互斥、open/terminate、审计
  TerminalBusyException       同实例第二个终端
  pty/                        伪终端胶水（JNA）
    Pty / PtySize / PtyException / PosixPty
web/http/TerminalApiServlet   POST 开终端、DELETE 结束终端
web/ws/TerminalWebSocket      WSS /ws/terminal/{ticket}
web/ssh/                      只剩传输与认证；SshAttachAdapter 复用终端核心
```

单向依赖：`web.ssh → web.terminal`；`web.terminal` 不 import `org.apache.sshd.*`。

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
| 6 | dsh 适配 + SSH 归位 + 默认不带 SSH | 完成（dsh 终端、`-PwithSsh` 开关） |
| 7 | ~~Windows ConPTY~~ | **取消**：Windows 用桌面终端（见 §7.1） |

品牌顺序：**kimi → opencode → dsh（可选）**。

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

## 8. 验收

- kimi/opencode 终端里的会话列表与网页模式一致（spike 结论落文档）。
- `/exit`、崩溃、底栏停止、实例停止四条路径都杀净进程树；进程树无 `sh`/`cmd`。
- 关窗/刷新后进程仍在，重开能接上并看到历史输出。
- agent 退出后写 PTY 不会到达 WS；会话移除，attach 返回 410。
- 同实例第二个终端 409；未提权 403。
- `-PnoSsh` 构建的 jar：无 `web/ssh`，终端功能可用。

## 9. 品牌实测（阶段 0 spike）

已从源码仓库核对（`kimi-code` / `opencode`）：

| 品牌 | TUI 命令 | 会话/配置目录 | 与 web 是否同一份 | resume 参数 |
|---|---|---|---|---|
| kimi | `node <包>/dist/main.mjs`（`kimi` 无子命令） | `KIMI_CODE_HOME` 或 `~/.kimi-code`；会话在 `<home>/sessions` | 是：`kimi web` 用同一 home | `-r/--resume [id]`、`-c/--continue` |
| opencode | `@opencode/cli-<os>-<arch>/bin/opencode`（原生二进制；`bin/opencode.cjs` 只是启动器） | XDG：data `~/.local/share/opencode`、config `~/.config/opencode` | 是：同一 data 目录 | `--session <id>`、`--continue` |
| dsh | `dsh tui [--resume]` | 实例 `DSH_HOME` | 是（已有 SessionCatalog） | `--resume <id>` |

实现约定：两个品牌都由适配器解析成「node + 入口 JS」或「原生二进制」，**不经过
`.bin` shim / `.cjs` 启动器**；环境里 `HOME`（和 Windows 的 `USERPROFILE`）指向实例
home，因此 TUI 读到的就是网页模式写下的那份会话。

## 10. 已知缺口（阶段 5/6 之前不发布）

- **web ↔ 终端互斥**：开终端会停掉品牌的 web 运行时（同一 HOME 不能两个进程抢），
  但不会自动恢复；恢复动作交给底栏「启动」（实例启动按钮）。
- **refresh 重连**：运行中的会话会直接发新票据，浏览器刷新后能接回同一进程并回放；
  但两个窗口同时 attach 目前都会收到广播、输入也会同时写入——v1 不做单控制端仲裁。
- **Windows 便利功能**：`POST .../reveal`（`target=home|workspace|log`）用
  `explorer.exe` / `notepad.exe` 打开，路径由服务端按实例记录计算，请求只能点名
  三个目标之一；dsh 的日志在内存里，`log` 返回 400。
