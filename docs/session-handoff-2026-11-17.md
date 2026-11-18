# 会话交接：v0.6.92 合并与发布（2026-11-17）

> 本稿接住 **`web-shell` 合入 `main` 与 v0.6.92 发布**。前稿
> [`2026-11-14`](session-handoff-2026-11-14.md) 的聊天、终端与发布结论仍然有效；本稿只写
> 这一版的分支处理、验证与产物。
>
> **分支状态**：`main` 的合并点是 `d7f8376c03 merge: web-shell → main（v0.6.92 …）`，
> 用 `--no-ff` 保留；`web-shell` 停在 `d3d5d8c27b`；`v0.6.92` tag 打在发布提交上。
> 发布在 GitHub Release，容器镜像由 `docker.yml` 在 tag 推送时构建。
>
> **2026-11-18 注**：v0.6.92 后来被重切到清理后的单提交历史 `e21eb8fc5`（tag 也指向
> 这里）；本稿记录的 `59b038b77c` / `web-shell` 合并点是当时的现场，已不再是当前发布
> 状态。v0.6.93 见 [`2026-11-18`](session-handoff-2026-11-18.md)。
>
> **隐私约定（继续遵守）**：真实域名、内网地址、宿主机路径、口令、真实 API Key 一律
> 不写进仓库。

## 0. 当前状态

- `main` 包含 v0.6.91 之后的聊天会话级操作、KaTeX、上下文占用环，以及 `/plain` 的
  SSR 并发配置与 legacy 外壳调优。
- `5ba80ebed1`（SSR 任务有界 + 失败退避）此前没进 v0.6.91 的 Release，这一版随
  `main` 一起发布。
- 本地发布产物：`/tmp/release-0.6.92/`，内含 `hdsl-web-0.6.92.jar`、
  `SHA256SUMS.txt` 与 `notes.md`。
- `web-shell-before-rebase` 保留了改直线前的本地历史（`1e888dc601`），确认无误后可删。

## 1. 这一版包含什么

### 聊天

- **会话分叉**：整段会话复制到指定助手，单事务复制节点/变体/附件行；附件内容寻址共享，
  删除任一侧不影响另一侧。生成中拒绝（409 `chat_busy`）。
- **配置快照**：`chat_session.system_prompt`（schema v5）冻结创建时的助手提示词，
  改助手不影响老会话；分叉/导入各带自己的快照。
- **导入 / 导出**：单会话 `hdsl-chat-session` v1 信封；导入接受 DeepSeek 官方导出 zip
  与本格式 JSON，文件/条目/正文有上限，校验先于落库。
- **KaTeX**：SPA 与 `/plain` 共用 remark-math + rehype-katex；点击公式复制 TeX；
  样式与字体随 jar 发布。
- **上下文**：不再静默截断历史，超限由服务商报错并归到 `context_exceeded`；失败/中断的
  回复和不带可用回答的提问不进上下文。
- **占用环**：常驻显示绝对用量，浮窗展示系统提示 / 历史与提问 / 附件构成。
- **顺手修**：DSML 的 `tool_calls` 变体不再落进正文；助手切换菜单头像改用 `AvatarView`。

### 纯文本版（`/plain`）

- SSR 并发从写死 2 改为默认 4，`server.yaml` 的 `plain.ssr_workers` 可调到 1–16；
  32 个运行+排队上限、失败 5 分钟冷却、10 秒 watchdog 继续有效。
- 外壳与登录卡对齐 `monet.css`：`#4352A5` 主色、半透明卡片、4px 圆角、填充式输入框；
  登录/错误页 420px 居中卡片，居中只用 CSS。
- 文本浏览器布局写回标记：导航逐项换行、提交按钮改 `submit input`、表格补列宽。
- 会话页新增模型选择 `<select>` 与「跳到末尾 ↓」；列表与 303 都带 `#bottom`。
- 会话页补「导出」与「分叉」；SSR 带上搜索来源，`[citation:N]` 不再显示成原文。
- IE11 可用：聊天读写、自动更新、公式、模型切换、导出、分叉、提权；导入仍建议在完整面板。

### 终端客户端与转码

- `hdsl-tui` 的 `/model` 打开模型选择弹窗，标出推理/视觉/文件能力。
- 转码参数面板去掉左侧导航的悬浮提示，说明文字不再被盖住。

## 2. 分支处理

- `5ba80ebed1` 先在 `web-shell` 里通过合并点 `18bb48f1d6` 接入；后来为了让历史保持直线，
  把 `web-shell` rebase 到 `5ba80ebed1`，合并点被丢弃，提交哈希全部重写（本地未推送）。
- 发版前在 `main` 上用 `--no-ff` 合入 `web-shell`，合并点为 `d7f8376c03`，保留明显的
  合并点；`5ba80ebed1` 成为它的第一父提交。
- 推送 `main`、`web-shell` 与 `v0.6.92` tag；`docker.yml` 由 tag 触发。

## 3. 验证与本地产物

- `./gradlew test`：**1555 例、0 失败**（27 跳过）。
- `npm run typecheck` / `compat` / `build`：通过（构建在 `shadowJar` 内执行）。
- `go test ./...`（`client/hdsl-tui`）：通过。
- `./gradlew shadowJar -PreleaseVersion=0.6.92`：成功，manifest
  `Implementation-Version: 0.6.92`，前端与 SSR bundle 已打包。
- `python3 scripts/api-smoke.py build/libs/hdsl-web-0.6.92.jar`：**43/43 PASS**。
- jar：74.6 MB，`sha256 355d1ea1c70fa516e2804906c90b602c8a37b83b162c4b72279941917d3f7fc9`。

## 4. 发布清单

```bash
git tag -a v0.6.92 -m "v0.6.92 — 会话分叉、导入导出与 legacy 表面调优"
git -c credential.helper='!gh auth git-credential' \
  push https://github.com/nas200-timmy/HDSL-WEB.git main web-shell v0.6.92
gh release create v0.6.92 -R nas200-timmy/HDSL-WEB \
  --title "v0.6.92 — 会话分叉、导入导出与纯文本版调优" \
  --notes-file /tmp/release-0.6.92/notes.md \
  /tmp/release-0.6.92/hdsl-web-0.6.92.jar /tmp/release-0.6.92/SHA256SUMS.txt
```

推送 tag 后 `docker.yml` 构建 `ghcr.io/nas200-timmy/hdsl-web:0.6.92`、`:0.6`、`:latest`；
`client.yml` 在 Release 创建后把终端客户端二进制挂上去。

## 5. 下次注意

- `web-shell` 目前停在合并前的 tip；下一次开发前重置到新的 `main`。
- `web-shell-before-rebase` 只是本地备份，确认发布无误后可以删除。
- README 只做事实性回写（导入/导出、公式、`/plain` 自动更新、`/model`），没有写发布宣传。
- 上下文超限依赖服务商报错；如果以后要本地估算，先补持久化账本，不要在发送路径里静默裁剪。
