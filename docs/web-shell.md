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

## 5. 架构

```
web/terminal/                 常置包，不依赖 MINA
  TerminalTarget              实例 → home/cwd/env/argv（品牌适配器产出）
  BrandTui                    KimiTui / OpencodeTui / DshTui
  TerminalSession             PTY 进程 + 回放缓冲 + 状态
  TerminalManager             实例级互斥、attach/detach、杀树、审计
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
- Windows：阶段 7 用 ConPTY + Job Object；在此之前抛 `PtyException`，按钮禁用并说明。

## 7. 实施阶段

| 阶段 | 内容 | 产出 |
|---|---|---|
| 0 | 分支、基线、kimi/opencode 会话存储 spike | spike 结论 |
| 1 | 品牌无关的终端核心（`TerminalTarget` + `BrandTui`） | 单元测试 |
| 2 | PTY 胶水（JNA） | `PosixPty` + 测试 |
| 3 | `TerminalSession` / `TerminalManager` | 生命周期 + 审计 |
| 4 | HTTP 与 WebSocket + 底栏启停 | 端到端可用 |
| 5 | xterm 前端 | 页面可用 |
| 6 | dsh 适配 + SSH 归位 + 默认 `-PnoSsh` | 双资产 |
| 7 | Windows ConPTY（可选） | Windows 可用 |

品牌顺序：**kimi → opencode → dsh（可选）**。

## 8. 验收

- kimi/opencode 终端里的会话列表与网页模式一致（spike 结论落文档）。
- `/exit`、崩溃、底栏停止、实例停止四条路径都杀净进程树；进程树无 `sh`/`cmd`。
- 关窗/刷新后进程仍在，重开能接上并看到历史输出。
- agent 退出后写 PTY 不会到达 WS；会话移除，attach 返回 410。
- 同实例第二个终端 409；未提权 403。
- `-PnoSsh` 构建的 jar：无 `web/ssh`，终端功能可用。

## 9. 品牌实测（阶段 0 spike，待填）

| 品牌 | TUI 命令 | 会话/配置目录 | 与 web 是否同一份 | resume 参数 |
|---|---|---|---|---|
| kimi | 待测 | 待测 | 待测 | 待测 |
| opencode | 待测 | 待测 | 待测 | 待测 |
| dsh | `dsh tui [--resume]` | 实例 `DSH_HOME` | 是（已有 SessionCatalog） | `--resume <id>` |
