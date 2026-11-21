# 会话交接：v0.6.94 预发布准备（2026-11-21）

> 本稿接住 **`web-shell` 的 v0.6.94 预发布准备**。前稿
> [`2026-11-18`](session-handoff-2026-11-18.md) 的证书管理结论仍然有效；本稿只写这一批
> 种子地图 / JDK 下载源 / 转码工作台 / 聊天模型树的范围、验证与发布前状态。
>
> **分支状态**：`main` 的合并点是 `62802a1215 merge: web-shell → main（v0.6.94 …）`，
> 用 `--no-ff` 保留，第一父 `4dd8f13bd`、第二父 `e731e587c9`；`web-shell` 停在
> `e731e587c9`；`v0.6.94` tag 打在本稿所在提交。推送 `main`/tag 后 GitHub Release
> 与容器由 CI 生成。`container/web-shell` 已到 `e731e587c9`；`web-shell-before-msg-rewrite`
> 仍是改写前旧历史，`origin/web-shell` 还停在 v0.6.92 的旧分支。
>
> **隐私约定（继续遵守）**：真实域名、内网地址、宿主机路径、口令、真实 API Key、
> DNS provider token 一律不写进仓库。

## 0. 当前状态

- 种子地图 / 种子预览、JDK 下载源、转码工作台、聊天模型树、内置百炼 / models.dev
  源码归档都已在这 66 个提交里。
- 文档已回写：`CHANGELOG.md` 新增 v0.6.94；README 补种子地图 / JDK 下载源 / 转码工作台；
  `docs/seed-preview.md`、`docs/seedmap.md`、`docs/seedmap-deviations.md` 是新的功能/精度
  文档；`docs/chat.md`、`docs/transcode.md`、`docs/minecraft.md` 同步了本轮变化。
- 本地预发布产物：`/tmp/release-0.6.94/`（jar、`SHA256SUMS.txt`、`notes.md`）。
- 没有合并 `main`、没有推送、没有打 tag。

## 1. 这一版包含什么

- **种子地图**：MC 实例页签 / 独立页面 `/mc/preview`；版本 + 种子出群系、结构、史莱姆
  俯视图。Java 内核（cubiomes 移植，零依赖，秒级大范围）与真服务端（逐格精确）两种
  引擎；1.12.2 到最新正式版；历史缓存、缩放拖动、图层、结构搜索/过滤/测距、高质量
  渲染、agent 文本报告。
- **精度**：L1 与 cubiomes C 版逐格 0 差异；L3 与真服务端的差异集中在旧版丘陵变体和
  1.18+ 地表洞穴群系，完整数字见 `docs/seedmap-deviations.md`。L2（游戏数据包）仍未建。
- **Java 运行时下载源**：清华 TUNA Temurin / 华为云 OpenJDK 镜像；目录与 SHA-256 仍用
  官方值；512 KiB 测速 + 魔数校验、缓存 6 小时、失败退避、官方兜底；向导单次源；
  `runtime.json` 记录来源；`HDSL_JAVA_SOURCE` 可覆盖。
- **转码**：准备文件跨页共享工作区；工具改成选择器驱动的批量工作台；队列实时遥测 /
  持续日志；空闲队列提交后自动开始。
- **聊天**：模型选择改成「来源 → 品牌 → 模型」资源管理器树，折叠/隐藏/localStorage，
  搜索匹配 id/品牌/来源/vendor；家族识别覆盖云厂商模型超市；智谱搜索补必填项。
- **账户 / 模型目录**：内置阿里云百炼 / 千问 / Token Plan；models.dev 源码归档解析，
  失败回源 api.json；百炼模型每 6 小时后台刷新。
- **纯文本版**：用户与助手的每条消息之间加 `<hr class="turn-sep">`，w3m/lynx 是横线，
  IE11/图形浏览器是浅灰线；会话页改成头像列 + 气泡 + 卡片输入区，进入会话可靠落底，
  向上阅读时轮询不抢滚动。
- **界面**：手机工具栏竖排、地图工具栏拥挤、地图滚轮带页、第三方核心弹窗溢出等修复。

## 2. 验证

- `./gradlew test`：**1638 例、0 失败**（27 跳过）。
- `npm run typecheck` / `compat`：通过；`npm run build` 随 jar 构建执行。
- `go vet ./...` / `go test ./...`：通过。
- `./gradlew shadowJar -PreleaseVersion=0.6.94`：成功，manifest
  `Implementation-Version: 0.6.94`；前端与 SSR bundle 已打包。
- `scripts/api-smoke.py`：43/43 PASS；`scripts/security-smoke.sh`：PASS（加密登录、
  提权、ACME 门禁、chat 加密读写）。
- jar：`/tmp/release-0.6.94/hdsl-web-0.6.94.jar`，sha256
  `c93a97043fa9fa8acf656ae5ed4abd8cba844e899f64a695e98a4bed21e83116`。
- 种子地图 L1/L3 验证器、`SeedPreviewTest`、`McJavaSource*Test`、`McJavaRuntimesTest`、
  `ModelsDevArchiveTest` 已覆盖；`PlainLegacyTest` 覆盖 `/plain` 的 `turn-sep`。
  没有新增真实 Minecraft 世界/真实 CA 联调。

## 3. 发布

- 分支：`main` 已有 `--no-ff` 合并点 `62802a1215`；`web-shell` 停在 `e731e587c9`。
- 版本：`CHANGELOG.md` v0.6.94；jar 用 `-PreleaseVersion=0.6.94`。
- 产物：`/tmp/release-0.6.94/hdsl-web-0.6.94.jar` 与 `SHA256SUMS.txt`。
- 发布命令（本稿所在提交打 tag）：

```bash
git tag -a v0.6.94 -m "v0.6.94 — 种子地图、JDK 下载源与纯文本气泡"
git -c credential.helper='!gh auth git-credential' \
  push https://github.com/nas200-timmy/HDSL-WEB.git main v0.6.94
gh release create v0.6.94 -R nas200-timmy/HDSL-WEB \
  --title "v0.6.94 — 种子地图、JDK 下载源与纯文本气泡" \
  --notes-file /tmp/release-0.6.94/notes.md \
  /tmp/release-0.6.94/hdsl-web-0.6.94.jar /tmp/release-0.6.94/SHA256SUMS.txt
```

- `docker.yml` 由 tag 触发构建 `ghcr.io/nas200-timmy/hdsl-web:0.6.94`、`:0.6`、`:latest`；
  `client.yml` 在 Release 后追加客户端二进制。
- Dockerfile 仍钉在 Temurin 21.0.13+9 / 25.0.5+8，入口在 x86_64 上加
  `-XX:-UseBASE64Intrinsics`；修复版 Temurin 资产可用后再移除。

## 4. 下次注意

- 种子地图 L2 还没建；L3 的 1.18+ 洞穴群系采样偏差仍是已知限制，不要把 Java 模式
  宣传成逐格精确。
- 真实服务端预览很慢且吃资源；并发固定 1，2048 格需要 1G–4G 堆，不要在生产面板上
  压测。
- 预览进程只在 Java 9+ 加 `-XX:-UseBASE64Intrinsics`；Java 8 的 1.16.5 预览不能加，
  否则 JVM 起不来。
- `web-shell` 的 66 个提交已经重写过 message，旧历史只存在于备份 ref 与
  `origin/web-shell` 的 v0.6.92 分支；推远端前先确认要保留哪套历史。
