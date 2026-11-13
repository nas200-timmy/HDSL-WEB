# 面板聊天：命令、流、附件与设置

本图固定聊天 v1 的边界：**命令走 REST，token 流走既有 `/ws`，存储只在 `<data>/chat/`**。它不展开到每个类，类级细节看 [`docs/chat.md`](../../chat.md) 与 `web/chat` 包。

## 一次 turn

```mermaid
sequenceDiagram
  autonumber
  participant B as 浏览器 /chat
  participant A as ChatApiServlet
  participant S as ChatService / ChatStore
  participant T as ChatTurnManager
  participant P as 上游模型（OpenAI / Anthropic）
  participant W as WsGateway / EventBus
  participant DB as SQLite <data>/chat/chat.db

  B->>A: POST /api/chat/sessions/{id}/messages
  A->>S: 校验会话/附件/requestId 幂等
  S->>DB: 写 user 节点 + assistant streaming 节点
  A->>T: start(turn)
  T-->>W: chat-turn-started {turnId,messageId}
  T->>P: HTTP + SSE（不跟随重定向）
  P-->>T: delta / reasoning / usage
  T-->>W: chat-delta / chat-reasoning（30–60ms 批量）
  T->>DB: 每 1 秒快照（streaming）
  P-->>T: finish / error / 连接结束
  T->>DB: 最终文本 + status(done/stopped/error)
  T-->>W: chat-turn-finished
  W-->>B: WS 帧（只认当前 turnId）
  B->>A: GET /api/chat/sessions/{id}（完成后回读快照）
```

- 停止：`POST /api/chat/turns/{turnId}/cancel` 关闭上游流，落 `stopped` + 部分内容。
- 重生成：`in_place` 给助手节点加 variant；`truncate_after` 删除目标之后的节点再生成。
- 编辑：更新用户消息 + 删除后续节点 + 重新生成。
- 幂等：客户端带 `requestId`，服务端 10 分钟内重复请求返回同一 turn。

## 附件

```mermaid
flowchart LR
  up["POST /api/chat/attachments?sessionId="] --> sniff["魔数/MIME + 大小 + 宏文档拒绝"]
  sniff --> img["图片：ImageIO 校验/去 EXIF"]
  sniff --> doc["PDF / Office / 文本"]
  img --> files["<data>/chat/files/<sha256>"]
  doc --> files
  doc --> extract["PDFBox / POI 有界线程池抽取"]
  extract --> txt["<sha256>.txt"]
  files --> send["发送时按能力映射"]
  txt --> send
  send --> native["图片 image block / Anthropic document / 可选 OpenAI file part"]
  send --> text["files=false：抽取文本（单附件 30k 字符截断）"]
```

- 附件按 sha256 去重；删除会话/节点时清记录，孤儿文件由 `pruneOrphans` 清。
- Office 的 docx/pptx/xlsx 没有可移植原生块，统一走抽取文本；音频/视频只保存。

## 设置与模型服务

```mermaid
flowchart LR
  ui["/chat/settings"] --> api["/api/chat/settings<br/>/api/chat/accounts/*"]
  api --> profile["chat_account_profile<br/>protocol · base · paths · models"]
  api --> cap["chat_model_capability<br/>vision/files/reasoning/tools"]
  api --> account["SettingsManager 账户<br/>key / base / model"]
  api --> probe["probe / models/refresh"]
  api --> backup["ChatBackupService<br/>VACUUM INTO + files/ + manifest"]
  backup --> out["/data/backup/chat-backup-*/"]
```

- 账户 key 仍只在 Java 后端使用；聊天页不返回明文 key。
- `/api/chat/*` 对 agent ticket 全关；设置/备份/端点/能力覆盖要求开发者提权。
- 聊天设置只写 `chat_account_profile` / `chat_model_capability` / `chat_setting`，不改主界面账户页。
