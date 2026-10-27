# 会话交接：HDSL-web（2026-10-27）

> 本稿接住 **v0.6.0**：第一方终端从"能开"变成"能日常用"——三档审批（`/yolo`）、审批对话框四个
> 选项（含"拒绝并说明原因"）、把斜杠命令交给 harness 自己执行、真正的 Tab 补全、终端里增删模型
> 供应商。前两稿仍然有效：
> [`2026-10-25`](session-handoff-2026-10-25.md)（实例生命周期 ↔ 终端、反代、整合包、部署）、
> [`2026-10-26`](session-handoff-2026-10-26.md)（第一方终端本体、三轮审查、两个 profile 的账户交接）。
> 本稿只写它之后的增量与新的坑。
>
> **隐私约定（继续遵守）**：真实域名、内网地址、宿主机路径、口令一律不写进仓库。

## 0. 当前状态

- `main` = **v0.6.0**（`web-shell` 以 `--no-ff` 合并进来）；两个第一方插件版本仍是 **0.1.0**
  （随 jar 分发、从未上 npm；改版本要同时改 `package.json` 与 `DshTuiPlugins.ENTRIES`）。
- 测试基线：插件 **ACP 86 条 / 进程内 45 条**（含分类器 14 条单测），后端 **814 用例**基线不变
  （这一版没动 Java），前端 `typecheck` + `compat` 绿。
- 这一版**没有**改面板的 Java 侧与前端；改动集中在 `plugins/`（共享模块 + 两个终端）。

## 1. 这一版改了什么

### 1.1 三档审批（`/yolo`）

harness 的 `ApprovalPolicy` 只有 `ask` 与 `never`，而 **`never` 是"一律拒绝"**（无人值守语义），
不是"一律放行"。所以"不问就执行"只能是终端自己的决定，落在
`plugins/shared/approval/policy.js` 的纯函数 `decide()`：

- `ask` 全问；`critical`（默认）只问危险命令与计划审批；`full` 全放行。
- 自动放行由 answerer（进程内 `approval/request` waterfall，ACP 的 `session/request_permission`
  应答）返回 `allowed-once` —— **仍然写 harness 的审计**，也**不放宽沙箱**。
- 危险分类器的输入：进程内从 `tool/call` 事件按 `callId` 记住 `arguments`（审批请求本身不带命令），
  ACP 从 `toolCall.rawInput`。"未知即问"是硬规则。
- 档位是**会话级**：`/new`、`/resume` 之后回到默认（`detachTurn` 里清空）。

### 1.2 审批对话框四个选项

1 运行 · 2 当前会话默认运行 · 3 拒绝 · 4 拒绝并说明原因。数字键 1-4 与编号一致（以前快捷键能用，
界面上没编号）。第 4 项在对话框内展开一行输入，理由作为**一条用户消息**交给模型：

- 进程内：`session.send()` → `followup()`，下一步进上下文，不打断当前回合。
- ACP：`session/prompt` **不允许并发**（"a prompt is already in flight"），所以理由先进入终端
  自己的队列，等当前回合结束、`drainQueue()` 时再发。抢着发会把会话打成错误。

### 1.3 命令：能用 harness 的就不自己写

`ctx.commands.execute(agent, line, [], signal)` 直接跑 `/permission` `/plan` `/goal` `/feedback`
`/export` `/compact`，结果文本原样显示。面板/帮助的命令表 = 本地表 + `commands.list(agent)`。
未知命令**先问 harness**，它也不认识才报错。ACP 没有命令运行时，这些名字会得到一句
"这条线跑不了"（`cmd.needs-inproc`），不是静默失败。

### 1.4 Tab 补全

`plugins/shared/ui/complete.js`（纯函数，8 条单测）：命令名（本地 + harness）、参数
（`/model` 的 `provider/model`、`/effort`、`/yolo`、`/resume`、`/permission`、`/provider`）、
`@` 工作区路径（仅进程内）。重复 Tab 在候选间循环（记住"上次应用的候选"，而不是重新按前缀过滤），
候选行在输入框上方，任何其它按键结束循环。

### 1.5 `/provider`（仅进程内）

- 写 **`llm-pi-ai` 的 settings 段**：`settings.mutate('llm-pi-ai', [{op:'set', path:['providers', route], value}], revision)`。
  用 `mutate` 而非 `replace`：后者会把已 redact 的 secret 字段清空。`dsh-base/cordis.patch.yml:127`
  的注释确认这就是网页「模型」页的做法。
- 密钥：`credentials.set(apiKeyEnv, key)`；`--key-env` 只写变量名；`--key` 不回显、不写日志、
  不进 settings。列表只显示"已配置/缺失 + 变量名"。
- `add` / `remove` 不加 `--yes` 只打印将要写入的块。`test <route>` 用 `llm.listModels` 验证连通。
- profile 没有 `llm-pi-ai` 段时明确报错，不猜。

### 1.6 会话切换

ACP 的 `/resume` 现在**先 `session/close` 旧会话**（此前只有 `/new` 关）；列表标「当前」并显示
相对时间；失败时说"仍在原会话"。进程内没有挂载 `sessionPersistence` 时说明原因，而不是空列表。

## 2. 关键文件

```
plugins/shared/approval/policy.js     三档判定 + 危险规则 + 白名单 key（纯函数）
plugins/shared/ui/complete.js         token 切分、候选、循环、候选行（纯函数）
plugins/shared/ui/dialogs.js          选项编号、审批详情翻页、理由输入行
plugins/shared/ui/render.js           状态栏（档位/会话）、实时规模、提示行、候选行
plugins/dsh-inproc-tui/src/app.js     审批四选项/白名单/理由、命令委托、/yolo、/status、/provider、补全
plugins/dsh-inproc-tui/src/agent.js   ChatSession：modelState、history()、动态 peer import
plugins/dsh-acp-tui/src/ui/app.js     四选项、理由排队、/yolo、/status、补全、切会话关旧会话
plugins/README.md                     终端契约：三档、四选项、命令、补全、供应商、能力矩阵
docs/web-shell.md                     能力矩阵摘要
```

## 3. 验证到哪一步

- `./gradlew --no-daemon build`：全绿（Java 814 用例 + 两个插件的 `check.mjs` 与 `node --test`）。
- 插件行为测试覆盖：四选项与数字键、`session-allow` 白名单、拒绝理由入上下文（inproc）与
  排队（ACP）、三档自动放行/拦截、命令委托与未知命令、`/provider` 的写入路径与密钥边界、
  补全的循环与 `@` 路径、切会话关旧会话与失败提示。
- **没验证**：真机 0.2.x 实例上 `/provider` 写完是否**热生效**（`settings` 的 `applies:'live'`；
  不生效就退化成"提示重启实例"）；ACP 的理由排队在真实慢回合下的顺序；`@` 补全在真实工作区的
  性能；分类器对真实工具名的匹配率（工具名依赖 `tool-bash` 等命名）。
- 渲染只在单元层面验证（状态栏/编号/候选行），没有截图对比。

## 4. 已知缺口与下一步

- `/provider` 的热生效要真机确认；确认不了就在 `addProvider` 后补一句"可能需要在实例里重开终端"。
- 分类器规则可以继续加（`docker`、`kubectl delete`、`terraform apply` 之类），规则集中在
  `policy.js`，加一条就多一条单测。
- ACP 没有 `/permission`，除非 harness 的 ACP 服务端以后广告 approval config；到那时
  `applyConfigOptions` 里加一个分支即可。
- `@` 补全不做 `~` 展开、不递归列目录（有意）。
- `session/list` 的 `createdAt` 在旧 harness 上可能缺——已经按"缺就不显示时间"处理。

## 5. 发布动作：v0.6.0（本次执行）

与 v0.5.91 相同：改动提交在 `web-shell` → `--no-ff` 合并 `main` → `shadowJar -PreleaseVersion=0.6.0`
→ tag → `gh release create`（正文写面向用户的介绍，不抄 CHANGELOG）。**`Dockerfile` 的白名单 COPY
与 `first-party-plugins/*.tgz` 断言别动**——v0.5.91 第一次发布就是在这里漏掉的，镜像里的 jar 因此
没有插件，而 CI 全绿。

## 6. 环境备注

沿用 [`2026-10-25`](session-handoff-2026-10-25.md) §6 与 [`2026-10-26`](session-handoff-2026-10-26.md) §6：
推送强制 `-c http.version=HTTP/1.1`（github.com 传满 32 KB 就停）、推送后用 `gh api` 核 sha、
`github.com:443` 整段不可用时**先重试再考虑走 API**（API 生成的 commit sha 与本地不同）、
Gradle 用 `flock /tmp/hdsl-gradle.lock`、插件的 `test/` 下不要放导入其它测试文件的入口
（`node --test` 会让每个测试跑两遍）。
