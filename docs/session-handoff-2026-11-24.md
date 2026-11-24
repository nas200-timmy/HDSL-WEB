# 会话交接：v0.6.95 发布后审计修复（2026-11-24）

## 状态

- 分支 `web-shell`，基于 `main` 的 `d06b875e`（v0.6.95 已正式发布运行，本容器即其实例）。
- 本轮 6 个提交，**尚未推送、未合并 main、未打 tag**；版本号未动。
- 起因：一次全仓只读审计（4 路并行：后端安全 / 后端并发 / 前端 / 客户端+部署），
  发现 6 条 P1、27 条 P2。审计报告原件在审计会话的实例工作区
  （`hdsl-web-audit-2026-11-24.md`，实例级、随实例存亡），关键结论已收进本文件。

## 本轮改动（每功能一提交）

1. `4fdbd5db` **fix(web)**：`AbortSignal.timeout`（Safari 16+）击穿 browserslist
   底线 15.6，旧 Safari 全站 API 不可用 → AbortController+setTimeout；
   `URLSearchParams.size`（Chrome 113+）致终端 resume/kernel 参数静默丢失 →
   toString().length；compat-check 增加 JS 运行时 API 黑名单（先收前者）。
2. `4030821d` **fix(build)**：Node tarball 与 SHASUMS256 曾同源下载（compose 默认
   腾讯镜像），投毒可同时换包与清单 → 官方 sha256 钉进 `NODE*_SHA256_*` ARG
   （22.23.1 / 24.14.0 × x64/arm64，取自 nodejs.org 并与腾讯镜像核对一致）。
   tarball 仍走镜像，国内可达性权衡保留。**bump Node 版本必须同步更新哈希**，
   忘更新构建失败（fail-closed）。docker-install.sh 只传 NODE_DIST_MIRROR，无需改。
3. `b0d710e9` **fix(transcode)**：worker 锁外赋值 `job.process`，cancel/stopAll
   锁内读 → spawn 窗口内的取消漏杀 ffmpeg（跑完数小时编码）。赋值+state 复查进
   短锁；steps/command/完成路径/fail() 的写统一进锁（修 Gson 序列化 CME 逃逸）；
   saveLocked 兜底 catch RuntimeException。**编码/waitFor/terminate 永远在锁外**。
   回归测试：10 并发假编码取消后无 finished 标记。
4. `3deedfe1` **fix(setting)**：计划外新发现（由 ④ 的回归测试挖出）——
   GameDirectoryManager.init() 先置 initialized 再建列表，ensureInitialized()
   无锁 → 并发建实例读到空列表 get(0) AIOOBE 500。ensureInitialized 加
   synchronized（与 init 同类锁）。
5. `6c16726b` **fix(ws)**：WsGateway.send 无每会话锁，EventBus 发布线程并发
   sendText → IllegalStateException 被当死浏览器关会话。加 synchronized(session)
   （TerminalWebSocket 现成范本）；写失败仍按死消费者处理（粗背压保留）。
   回归测试：16 并发建实例经真实 servlet 线程发布，全事件到达且会话不关闭。
6. `8ae8ddd1` **fix(web)**：聊天加密通道过期（服务端内存态：重启即丢/TTL 12h/
   超 1024 驱逐）→ 401 chat_key_required → 前端误判会话失效强制登出。
   resetChatChannel() 出口 + REST 识别该错误重握手重试一次 + WS 等 chat-hello-ok
   才置 chatReady、失败帧重绑一次 + 登出清通道；解密绑定发送时的通道防换钥竞态。
   后端 401 语义未动。

## 决策记录（维护者拍板，2026-11-24）

- **反代转发面板 hdsl_session Cookie 给实例后端：保留，不修。** 审计原报 P1；
  核验确认非文档化权衡（§6 未列、类注释无记录、对实例无功能用途），但维护者
  决定接受。利用前提：攻击者控制实例服务端代码。后续审计**不应再当新发现报**。
  可择机补记 security.md §6。
- **NodeRuntimeManager（面板一键装 Node）的校验顺序：保留。** publishedChecksum
  先问下载源、镜像有清单就信镜像；国内镜像已核对与官方一致。维护者方针：
  **构建成功优先于安全性**（同理解释底座镜像等设计）。
- ① 修实现而非抬高 browserslist（支持面是声明过的产品价值）。
- ④ 选简单锁版；async remote + 每会话队列（无队头阻塞）留作跟进项。

## 验证（实际执行）

- `./gradlew test --rerun-tasks`：**1689 例、0 失败、27 跳过**（2m38s；基线 1687+2 新）。
- `cd web && npm run typecheck && npm run build && npm run compat`：通过。
- Node 钉扎端到端：腾讯镜像下载 node22 x64 tarball 对钉扎值 `sha256sum -c` OK；
  两个 RUN 块 bash -n 通过。
- 转码模块 12/12、WsGatewayTest 6/6（含两个新回归测试）。
- 全量验证串行执行（gradle 与 npm 不并行——11-23 交接的构建竞争教训）。

## 仍未做 / 待办

- 没有 push、没有合并 main、没有 tag、没有发布。
- **人肉测试**：⑤ 的「面板重启 + 长开聊天标签页」场景（审计会话够不着真实浏览器）。
- 审计 P2  backlog（重点）：E-Stop 覆盖不全（Brand/ZCode web 根、Windows MC 管道、
  transcode）；`/api/auth/password` 无失败预算（唯一漏网的密码入口）；
  trust_forwarded_proto 空代理名单时信任任意 XFP；Brand/ZCode instance.json
  非原子写；MC 并发 start 无锁（Windows 可双进程）；**MC 备份恢复是覆盖合并
  而非替换**（残留新世界 region）；面板重启后转码 WAITING_DECISION 永久停摆；
  附件落盘与 pruneOrphans 竞态；子路径挂载附件 URL 前导 `/` 绕过 base href；
  agent 密码/API key 明文上行；设置页提权状态过期不刷新；hdsl-tui 事件通道
  首个 pong 前无读超时；CI action 未钉 SHA。完整清单见审计报告（实例工作区）。
- ④ 的 async remote 跟进项；⑤ 若实测有边角再议后端配合。
- 已知构建竞态的结构性残留：Gradle 内部排序已修，但与外部并行 `npm run build`
  仍无跨进程互斥（buildWeb inputs 也不含 vite.config/public/lock）。
