# 会话交接：聊天合并与 v0.6.85 发布准备（2026-11-14）

> 本稿接住 **面板聊天合入 `main`、守门修复与 v0.6.85 发布准备**。前稿
> [`2026-11-12`](session-handoff-2026-11-12.md) 的聊天实现结论仍然有效；本稿只写合并之后
> 的状态、验证与发布清单。
>
> **分支状态**：`web-shell` 已以 `--no-ff` 合入本地 `main`，合并点 **`92034bd39`**；
> 之后只有文档提交。**未 push、未打 tag、未发布**。发布是下一步，见 §4。
>
> **隐私约定（继续遵守）**：真实域名、内网地址、宿主机路径、口令、真实 API Key 一律
> 不写进仓库。

## 0. 当前状态

- `main` 包含 v0.6.84 之后的**面板聊天 v1**、**Qwen / MiMo / MiniMax 三个 TUI 品牌**、
  终端拒绝 shell 回落、主侧栏重构，以及合并前的守门修复（见 §2）。
- 文档已按实现回写：`docs/chat.md`、`docs/architecture/{context,full,backend}.md`、
  `docs/architecture/flows/chat.md`、`README.md`、`docs/deployment.md`。
- 本地发布产物：`build/libs/hdsl-web-0.6.85.jar`（69 MB）与 `SHA256SUMS.txt`，
  见 §3。
- 线上/远端仍是 v0.6.84；这次没有推送任何东西。

## 1. 这一版包含什么

- **聊天**：SQLite 会话/节点、OpenAI + Anthropic 协议、流式 turn、停止、两种重生成、
  编辑、variant、附件（图片/PDF/Office/长文本）、助手与模型服务、备份与保留、联网搜索
  （博查 / DeepSeek / 智谱 / Tavily / Brave / Exa）与只读工具 `search_web` / `scrape_web`。
- **TUI 品牌**：`Brand.Kind`（WEB / TUI / HYBRID）、三个纯终端品牌的安装与启动、
  网页/终端/Go 客户端按 `kind` 渲染。
- **终端加固**：`TerminalTarget` 拒绝 shell 与脚本；PTY 始终 argv 直启；attach 前把
  home/workspace 交给 agent uid。
- **界面**：主侧栏「账户 → 游戏 → 聊天 → 媒体 → 设置」；TUI 品牌独立导航。
- **守门修复**：NOTICE、SQLite 降级、预搜索出锁、前端 TUI 列表读服务端、CI 覆盖
  `web-shell`。

## 2. 守门结论与处理（2026-11-14）

| 级别 | 问题 | 处理 |
|---|---|---|
| P1 | NOTICE 未登记 sqlite-jdbc / PDFBox / POI 等新增依赖 | 已补（`73c9c86aa`） |
| P2 | SQLite 原生库加载失败会让整个面板起不来 | 聊天改为可降级：不注册 `/api/chat/*`，面板继续启动（`c19e08c69`） |
| P2 | 先搜后答在全局锁内做网络 I/O，慢搜索卡住所有会话 20 秒 | 移出锁，搜索后重新校验 busy 与额度（`c19e08c69`） |
| P2 | 前端硬编码 TUI 品牌列表，与后端 `Brand.kind()` 重复 | 登录后读 `/api/brands/capabilities`，硬编码只做兜底（`cc0ce67b4`） |
| P2 | `web-shell` 推送不跑 CI | build.yml 增加分支触发（`0d0f9ee3e`） |
| P3 | `Brand.hasTerminal()` 对 Octop 仍返回 true | 未改，影响仅限 capabilities 字段 |
| P3 | 每日 token 上限进程内计数、重启归零 | 已写入 CHANGELOG「已知限制」 |
| P3 | 备份恢复无 UI | 文档写明手动流程 |

审查细节（攻击面、验证项）见当天的守门报告；这里只留结论。

## 3. 验证与本地产物

- `./gradlew test`：**1483 个用例、0 失败**（254 个测试类）。
- `python3 scripts/api-smoke.py`：**39 项全 PASS**（真实起面板、临时数据目录）。
- `npm run typecheck` / `npm run build` / `npm run compat`：绿。
- `./gradlew shadowJar -PreleaseVersion=0.6.85`：成功。
- jar：`build/libs/hdsl-web-0.6.85.jar`，manifest `Implementation-Version: 0.6.85`；
  发布副本与 `SHA256SUMS.txt` 在 `/tmp/release-v0.6.85/`。

**未验证**：浏览器真机点击（已在容器里由维护者走过）、真实供应商流式对话、Docker 镜像构建
（CI 负责）、JDK 17 运行（本地 JDK 21；CI 的 17/21 双腿覆盖）。

## 4. 发布清单（等维护者明确指令后再执行）

```bash
# 1) 确认工作区干净、main 在预期提交
git status --short && git log --oneline -3

# 2) 打 tag（annotated）
git tag -a v0.6.85 -m "v0.6.85 — 面板聊天、三个 TUI 品牌与终端加固"

# 3) 推送（本机 SSH key 不可用，用 gh 凭据走 HTTPS）
git -c credential.helper='!gh auth git-credential' push https://github.com/nas200-timmy/HDSL-WEB.git main v0.6.85

# 4) 建 Release，附 jar 与 SHA256SUMS.txt（client 流水线会把客户端资产挂上去）
gh release create v0.6.85 -R nas200-timmy/HDSL-WEB \
  --title "v0.6.85 — 面板聊天、三个 TUI 品牌与终端加固" \
  --notes-file /tmp/release-v0.6.85/NOTES.md \
  /tmp/release-v0.6.85/hdsl-web-0.6.85.jar /tmp/release-v0.6.85/SHA256SUMS.txt
```

推送后 CI 会跑 `build`（JDK 17/21）、`client`（五平台 + 挂 Release）、`docker`
（amd64/arm64 → ghcr）。发布说明必须写：**jar 33 MB → 69 MB**、聊天数据在
`<data>/chat`、备份含 API key。

## 5. 下次注意

- `docs/session-handoff-2026-11-12.md` 的分支状态已改成「已并入 main」；再合并/发布后
  继续更新这两份交接稿，不要留「未合并」的旧口径。
- `Brand.hasTerminal()` 仍是恒 true；如果以后要按能力隐藏终端按钮，先修它再给前端用。
- 每日 token 上限要做持久化账本时，`chat_setting` 已经能存 JSON，但计数需要新表。
- 聊天备份恢复没有 UI；恢复时注意 schema 只向前迁移，不接受降级。
## 6. FreeBSD 终端验证（同日，v0.6.85 之后）

真机：FreeBSD 15.1-RELEASE-p6 amd64，openjdk21，2 核 4 GB；面板以 rc 服务跑在
`/home/dev`（jar `/home/dev/hdsl-web.jar`，数据 `/home/dev/hdsl/data`，HTTPS 3080）。

- **根因确认**：`Pty.spawn` 只分 LINUX/MACOS 与 WINDOWS，FreeBSD 落到 `PtyException`。
  FreeBSD 本身有 PTY，是代码缺口。
- **改动**：`Pty.spawn` 增加 FREEBSD；`PosixPty` 的 `F_DUPFD_CLOEXEC` 用 17，
  `TIOCSWINSZ` 沿用 BSD 的 `0x80087467`；`NodeRuntimeManager.platformTag` 对 FreeBSD
  明确拒绝（原先会落到 linux-x64，装一个跑不起来的 ELF）。
- **验证方式**：打 dev jar → 用 `PtyProbe` 直接走 `Pty.spawn`（openpty + posix_spawn），
  再跑 `PtyProbe2` 验证 resize / stdin / 退出码；随后把 jar 覆盖到服务、重启、健康检查。
  实测输出：`probe-ok`、`24 80`、resize 后 `40 120`、`GOT:hello`、`exit=3`。
- **服务的启动横幅**：仍显示 `minecraft: console pipe`（MC 控制台保持 pipe），文案已从
  「no PTY on this platform」改成「PTY console not enabled on this platform」。
- **系统指标**：`SystemMetrics` 在 FreeBSD 上 host=other、CPU 0.0、disks/network/gpu 为空
  （依赖 `/proc`、`/sys`），没有抛异常；文档已写成已知限制。
- **未做**：MC 控制台 PTY（需要真实服务端跑一轮）、uid 分离、`/proc` 指标替代实现。
  真机上 `claude-web` 实例仍在，浏览器点「终端」即可验证修复；本次没有猜/复用管理员口令。
