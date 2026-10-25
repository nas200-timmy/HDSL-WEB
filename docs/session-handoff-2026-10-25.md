# 会话交接：HDSL-web（2026-10-25）

> 本稿接住 **v0.5.81** 这一版：终端入口统一、整合包契约补齐（`home/` / `dshVersions` /
> `launchers` / 向后兼容）、**dsh 终端改成"装一个 TUI 插件"**、终端 WS 的空闲超时、以及 Go 客户端
> 的一批对齐。目标是不看会话原文也能接着干。
>
> **隐私约定（继续遵守）**：真实域名、内网地址、宿主机路径、口令一律不写进仓库；示例统一用
> `dsh.example.com`、`192.0.2.x`、`/srv/...`、`<口令>`。仓库账号 `nas200-timmy` 属公开信息。

## 0. 当前状态

- 分支 `main`，本地已合并 `web-shell`（`--no-ff`），**尚未推送**。
- 版本号：**v0.5.81**（本地已打 annotated tag）。上一版是 v0.5.72；中间没有发布过，也没有 73–80。
- 测试基线（本机 Linux）：后端 **743 用例 / 0 失败 / 2 跳过**（两个跳过是既有的
  `AuditLogTest.logBeforeInitIsANoOp` 与 Windows 专有项）；前端 `tsc --noEmit` + `vite build` +
  兼容闸门通过；客户端 `gofmt` / `go vet` / `go test ./...` 全绿，5 平台交叉编译通过。
- Release 资产**还没构建**（`shadowJar` 本地跑过 0.1.0-dev，未按 v0.5.81 出包）。

## 1. 这一版改了什么

### 1.1 终端入口统一为「终端窗口」

四处入口（实例列表 `InstancesPage`、实例详情 `InstanceDetailPage`、品牌 `BrandsView`、ZCode
`ZcodeSection`）统一换成 Material Symbols 的 `terminal` 图标（`icons.tsx` 的 `TerminalIcon`；
`makeIcon` 新增可选 `viewBox`，因为该图标在新版 Symbols 用 `0 -960 960 960` 视口）。
不可用时**变灰禁用而不是隐藏**（新增 `terminal.unavailable` 文案）。

### 1.2 整合包：可选能力与向后兼容

见 [modpacks.md](modpacks.md)。要点：

- **导出 `home/`**：白名单式，不勾选则什么都不随包走；导出弹窗有勾选列表，`GET …/export` 给可选项。
- **`dshVersions`**：导出写实测集；导入做 `∩ 本机已装` 的确定性选择；市场条目也读。
- **`launchers`**：读 + 归一化 `hdsl` + 逐段比较 + **警告放行**；导出侧刻意不写。
- **向后兼容**：`.tgz` 与 manifest v2/v3 可导入；v1 拒绝并说明压平了什么。

### 1.3 dsh 的终端：从 `dsh tui` 到「装一个 TUI 插件」（本版最大的认知修正）

**上游事实**：`dsh <name>` = `dsh --profile <name>`，所以 `dsh tui` 是内置入口；但 `tui` **不在**
自动初始化的 profile 模板里（模板只有 acp/web/headless/sdk/sdk-minimal），非模板名要先
`dsh plugin --profile tui add <pkg>` 建出来。所以"没装插件就打开终端"会以
`profile "tui" does not exist` 在 0.4 秒内退出——**这是审核实测的 dsh 实例崩溃原因**。

**硬约束**：TUI 插件**绝不能装进实例的 `web` profile**。TUI bundle 与 `dsh-web-app` 都是 app 插件，
各按自己的语法解析同一份 argv，上游不仲裁；混装会让应用遇未知旗标直接退出（社区 README 实测
`error: unknown option '--no-open'`）。安装器因此新增 profile 形参与 `refuseAppProfile` 守卫。

**清单**（`dsh/DshTuiPlugins`，每项带它自己的 profile）：

| 包 | 版本 | 许可证 | profile | bin |
|---|---|---|---|---|
| `@deepseek-harness-tui/dsh-tui` | 0.17.0 | MIT | `dsh-tui` | 有（5670★，头部） |
| `@huiliyi37/dsh-tianshu-tui` | 1.0.0-rc.2 | **Apache-2.0** | `tui` | 无 |
| `@tomowang/dsh-tui` | 0.10.2 | MIT | `tui` | 无 |
| `@xmoon76/dsh-pi-tui` | 0.5.3 | MIT | `pi-tui` | 无 |

**剔除**：`@ruhooai/dsh-tui`（bin 需要 Bun + 外部 `cordis.yml`，面板起不来）、`@zhangweiii/dsh-tui`。

**REST**：`GET /api/dsh/tui-plugins`（清单，`DshApiServlet`）、
`GET|POST /api/instances/{id}/tui-plugins[/install|/remove]`（`{package}`；**profile 由服务端按清单
决定，请求无法指定**；安装/卸载复用同一套任务体）。`DshTui` 改为启动插件自己的 profile；没装插件时
给可行动的错误。

**前端**：实例「安装」页新增 TUI 插件区段 + **醒目免责声明**（面板只做 npm 安装 / 版本锁定 / 挂到
插件自己的 profile；不改 dsh 源码；第三方作品不背书；展示实际包名/版本/仓库/许可证 + 一键卸载）。

### 1.4 终端 WS 不再被 30 秒静默掐断

`docs/web-shell.md §3` 一直写着「无空闲超时」，但 `TerminalWebSocket.onOpen` 从未设置过，于是吃
Jetty 默认 30 秒 → 静默终端被 `1001 Connection Idle Timeout` 关掉。现在 `ws.setMaxIdleTimeout(0)`。
客户端加心跳（20s ping / 10s pong 期限）；仓库 pin 的 Jetty 12.0.39 会回 pong（已反编译确认）。

### 1.5 客户端（hdsl-tui）对齐

保留服务端给的原因（`terminal_failed` 不再被通用文案盖掉）；提权顺序「状态 → 密码 → 内核 → 终端」
（新调 `GET /api/developer/status`）；选项可放地址前后；补上 `j/k` 菜单导航；`terminal_failed`
退出码统一为 4。

## 2. 关键文件

```
src/main/java/org/jackhuang/hmcl/dsh/DshPackForge.java       导出（home/ 白名单、dshVersions）
src/main/java/org/jackhuang/hmcl/dsh/DshPackInstaller.java   导入（launchers、dshVersions、.tgz/v2/v3）
src/main/java/org/jackhuang/hmcl/dsh/DshPackMarket.java      索引（dshVersions / launcherRestricted）
src/main/java/org/jackhuang/hmcl/dsh/DshTuiPlugins.java      精选 TUI 插件清单（含 profile）
src/main/java/org/jackhuang/hmcl/dsh/DshPluginInstaller.java 安装器（新增 profile 形参 + 守卫）
src/main/java/org/jackhuang/hmcl/web/terminal/DshTui.java    起插件自己的 profile
src/main/java/org/jackhuang/hmcl/web/terminal/TerminalWebSocket.java  终端 WS（setMaxIdleTimeout(0)）
src/main/java/org/jackhuang/hmcl/web/http/DshApiServlet.java  /api/dsh/tui-plugins
src/main/java/org/jackhuang/hmcl/web/http/InstancesApiServlet.java  /tui-plugins 与 /export
web/src/components/icons.tsx                                 TerminalIcon
web/src/components/TuiPluginSection.tsx                      TUI 插件区段 + 免责声明
web/src/components/ExportInstanceModal.tsx                   导出弹窗（home 白名单 / dshVersions）
client/hdsl-tui/internal/pterm/client.go                     客户端心跳
client/hdsl-tui/internal/app/terminal.go                     提权顺序
docs/modpacks.md                                             整合包契约（本版新增）
```

## 3. 验证到哪一步

- 已做：§0 的全量测试与构建；整合包新增用例（真 tar.gz、v1 拒绝理由、`dshVersions` 选择、
  `launchers` 五行判定）；TUI 插件用例（清单自洽、web 守卫、端到端把 `@tomowang/dsh-tui` 写进
  `profiles/tui/package.json` 而实例 web profile 仍为空）；终端 WS 与客户端心跳的单测。
- 未做：**真机复验**。审核（v0.5.72）报的两处核心故障——品牌 agent 零输出、30 秒断开——只有后者
  是本版能确证的修复；**品牌 agent 零输出仍未解决**，见 §4。

## 4. 已知缺口与下一步

- **品牌 agent 零输出（未解决）**：已排除 PTY 尺寸假设（初始 `PtySize.DEFAULT = 80×24`）。剩下两个
  假设都需要真机上的包才能证实：① `PosixPty` 注释自陈"子进程没有控制终端"（未做 `TIOCSCTTY`），
  而 TUI 普遍靠 `/dev/tty` 做 raw 模式与尺寸初始化；② 品牌入口命令可能不是交互 TUI（例如 kimi 适配器
  跑 `node <包>/dist/main.mjs` 不带参数，若不是它的交互命令就会读着 stdin 干等）。**诊断办法**：在
  审核机上用 `script -qc '<同样的 argv>' /dev/null` 或 `socat` 起一遍——有画面即假设 ①，仍静默即 ②。
- **同实例重复打开终端**卡在「正在打开…」（面板有 `TerminalBusyException`，但表现是悬着）。
- **`dshhome` 形态**仍被 web API 拒绝；`files[]` / `.dshpkcfg` 未实现（见 modpacks.md §2）。
- **客户端**：多服务器会话单槽位（后登录的覆盖前者）；plain 模式无法脚本化；协议号不匹配只在
  `--verbose` 可见；`--resume` 无品牌校验。
- **测试护栏**：`internal/rtty` 整包零测试；`internal/panel/fakeserver` 缺 zcode/kernels 路由，导致
  `TerminalKernels` 路径零覆盖。
- **上游同步**：本次扫描结论是**没有需要移植的改动**（详见 [upstream-sync.md](upstream-sync.md) 的
  v0.5.81 段）。

## 5. 发布动作（还没执行）

```bash
./gradlew shadowJar -PreleaseVersion=0.5.81          # → build/libs/hdsl-web-0.5.81.jar
git push https://github.com/nas200-timmy/HDSL-WEB.git main
git push https://github.com/nas200-timmy/HDSL-WEB.git v0.5.81
gh release create v0.5.81 build/libs/hdsl-web-0.5.81.jar SHA256SUMS.txt --notes-file <notes>
```

客户端 5 个平台资产与 `SHA256SUMS-client.txt` 由 tag 触发的 `client.yml` 上传；docker `0.5.81`
会拿到 `latest`（`sort -V` 下它最大）。

## 6. 环境备注（下次接手别踩）

- **`git fetch github.com` 在这台机器上会被掐断**（`GnuTLS recv error (-110)`）。读上游提交用
  `gh api repos/MCXCC303/HDSL/commits`；源码 zip 走 `codeload.github.com` 也通。
- 子代理在同一个工作树里并发干活时，**分支会被漂到 `main`**（本次两回），收尾时务必确认
  `git branch --show-current`。
- 本仓库 `origin` 是 HTTPS + `gh` 凭据（`git -c credential.helper='!gh auth git-credential' push …`）。
- `npm run compat` 在 Windows 会报 "Did not find any files"（es-check 不做 glob 展开），CI 的 Linux 上正常。