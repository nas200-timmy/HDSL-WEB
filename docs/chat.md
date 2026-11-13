# 面板聊天（Panel Chat）设计稿

状态：Implemented v1（2026-11-12，`web-shell`），设计稿按落地实现回写  
日期：2026-11-12  
范围：HDSL-web 面板内的纯聊天页；不接 NextChat，不引入桌面壳。

## 1. 结论与边界

在面板内新增一个原生聊天页，复用现有登录、账户/key、`/ws` 事件通道和 HMCL 视觉语言。  
参考 RikkaHub 的交互与动效，但**不复制其代码**（AGPL-3.0），也不搬它的 mesh gradient、毛玻璃和 Android 导航。

### 1.1 做什么

- **单人使用**：和 RikkaHub 一样，聊天记录属于面板当前管理员，不做多用户会话隔离。
- **助手/角色隔离**：每个助手有自己的系统提示词、模型默认值、开场白和会话列表；这是单用户下的逻辑隔离，不是权限隔离。用户可以用系统提示词“调教”角色，但助手之间不共享会话上下文。
- 多会话聊天，历史持久化；默认永久保留，可在设置里按时间清理，用户也可手动删除。
- 多模型/多协议：v1 同时支持 `openai-completions` 和 `anthropic-messages`；`openai-responses` 后续。
- 流式输出、停止、重新生成；重新生成分两种：**只重生成当前回复** 和 **丢弃后续内容、从当前强行重生成**。
- Markdown：GFM、代码高亮、代码块复制/下载、表格、KaTeX（可选）。
- 附件：图片、PDF、DOC/DOCX、PPT/PPTX、XLS/XLSX、TXT/MD/CSV/JSON 等常见聊天上传类型；Office/PDF 走文本抽取兜底。
- 长文本自动转 `.txt` 附件。
- 模型能力声明：图片、文件、思考、工具（工具仅标记，不启用）。
- 聊天页自己的「模型与端点」设置：endpoint profile、自动拉模型、分阶段联通测试、能力覆盖；**不修改主界面账户页**（账户还供 dsh 使用）。
- SQLite 存储 + 定时/手动备份。

### 1.2 不做什么

- 不做 agent：不调用工具、不执行命令、不控制面板/实例。
- 不做 MCP、Skills、Workspace、Phone Use、TTS/语音、PWA、多端同步。
- 不做多用户共享会话；v1 按面板管理员单用户设计。
- 不把聊天页塞进 `/i/<id>/`；它是面板自己的页面。
- 不引入 MySQL/Postgres/H2；用 SQLite。
- 不接受宏文档执行；`.docm/.pptm/.xlsm` 不解析宏。
- 音频/视频可上传保存，v1 不送模型；等能力字段扩展后再接。

## 2. 用户体验

### 2.1 入口与布局

- 路由：`/chat`，侧栏新增一级「聊天」项。
- 桌面：左侧会话列表（可折叠）+ 右侧聊天区；移动端会话列表变成抽屉。
- 空态：居中欢迎语 + 输入框 + 模型选择；不放大 banner。
- 顶部：会话标题、模型选择、聊天设置（模型/端点/能力）、更多（重命名/导出/删除）。
- 视觉：沿用 HMCL 的颜色、圆角、ripple、字体与间距；不新增第二套主题。

### 2.2 消息

- 用户消息靠右、助手消息靠左；助手气泡固定显示。
- 消息内容用 Markdown 渲染；代码块顶部「语言 + 复制 + 下载」，长代码可展开/折叠。
- 图片显示缩略图，点击在新标签打开；文件显示 chip（文件名 + 类型图标）。
- 思考内容折叠在「思考」块里：显示字数，可展开/收起查看全文；流式时自动展开并跟随到底部。完整 reasoning 由后端保存，不做长度截断。工具调用不在 v1 出现。
- 消息底部操作栏常驻：复制、重新生成（两种模式）、编辑（仅用户消息）、分支 `‹ 1/3 ›`、删除、更多。
- 流式中隐藏编辑/删除/重新生成，只保留流式内容。
- 「重新生成」是一个 split menu：
  - **重新生成当前回复**：给当前助手节点加 variant，保留后续消息；提示「后续消息仍基于旧回复」。
  - **从这里重新生成（丢弃后续）**：删除当前节点之后的所有节点，再生成。
  - 用户消息只有「从这里重新生成（丢弃后续）」。

### 2.3 动效（参考 RikkaHub，套 HMCL 皮肤）

| 场景 | 做法 | 时长/曲线 |
|---|---|---|
| 首 token 前 | 6–8px 圆点脉冲，或三点呼吸 | 600–800ms reverse |
| 流式追加 | 新词/新块 `opacity 0→1` + `translateY(2px)→0` | 120–160ms ease-out |
| 末尾光标 | 细竖线 1s 闪烁，完成后消失 | 1s |
| 气泡增长 | 内容自然撑开，内部块淡入；Web 不硬 animate height | 120–180ms |
| 操作栏 | 完成后常驻；hover 加深，不做位移 | 120ms |
| 思考/附件 | 展开收起 150–200ms；上传中 shimmer | 150ms |
| 停止 | 发送↔停止形态/颜色过渡，不旋转 | 100ms |

- 曲线统一 `cubic-bezier(0.2, 0, 0, 1)`，不要 Expressive 回弹。
- `prefers-reduced-motion` 下关闭逐词动画和位移，只保留内容出现。

## 3. 总体架构

```text
React ChatPage
  ├─ REST /api/chat/*     命令、历史、上传、设置
  └─ WS /ws topic chat:<id>  流式事件
        │
Java ChatApiServlet
  ├─ ChatService          会话/消息/turn 编排
  ├─ ChatStore            SQLite
  ├─ ChatTurnManager      每个会话一个 active turn
  ├─ EndpointResolver     account -> EndpointProfile
  ├─ ChatProtocol         openai-completions / anthropic-messages
  ├─ ChatAttachmentStore  附件文件 + 文本抽取
  └─ ChatBackupService    定时/手动备份
```

- 命令走 REST，流走 WS：HTTP 状态、幂等、上传都更简单；WS 只做服务端→客户端事件。
- 复用现有 `/ws`，不新增 SSE、不改反代规则；面板的 `withBase("/ws")` 和重连已经可用。
- 复用现有 `AuthFilter`、session cookie、账户记录（key/base/model）；聊天专属配置单独存。

## 4. 账户与 Endpoint Profile

### 4.1 边界

- 主界面 `AccountsPage` / `/api/accounts` **保持原样**，继续服务 dsh。
- 聊天不复用 `/api/accounts` 的 UI，只复用账户记录里的 key、base、model。
- 聊天专属的 protocol、path、能力、probe 放在聊天设置里，存到独立的表。

### 4.2 数据

新增两张聊天专属表：

```text
chat_account_profile
  account_name, protocol, base_override, models_path, chat_path,
  auth_style, extra_headers_json, models_json, models_fetched_at,
  last_probe_json, updated_at

chat_model_capability
  account_name, model, vision, files, reasoning, tools,
  max_context, max_output, source, updated_at
```

- `account_name` 对应现有账户的 name；key/base/model 仍从账户读取。
- `protocol` 默认取 vendor 的 `DshVendor.api()`；用户可覆盖。
- `models_path` / `chat_path` 是显式数据，**禁止运行时 `base + "/v1/chat/completions"` 猜测**。
- `source`：`manual` / `vendor` / `models.dev` / `unknown`。

### 4.3 路径解析

```java
record EndpointProfile(
    String protocol,
    URI base,
    String modelsPath,
    String chatPath,
    AuthStyle auth,
    Map<String, String> extraHeaders
) {}
```

- 内置 vendor 在 `DshVendor` 增加 `modelsPath` / `chatPath` 默认值；聊天设置里可覆盖。
- 自定义 provider 在聊天设置里填写 base、protocol、paths，并展示最终 URL。
- `base` 规范化：去尾斜杠、只允许 `http/https`、拒绝 userinfo/query/fragment。
- 账户卡片和 probe 结果展示最终 URL。

路径示例（数据，不是公式）：

| vendor | protocol | models | chat |
|---|---|---|---|
| OpenAI | openai-completions | `/models` | `/chat/completions` |
| DeepSeek | openai-completions | `/models` | `/chat/completions` |
| Anthropic | anthropic-messages | `/v1/models` | `/v1/messages` |
| Mistral | openai-completions | `/v1/models` | `/v1/chat/completions` |
| Gemini | openai-completions | `/openai/models` | `/openai/chat/completions` |
| Ollama | openai-completions | `/models` | `/chat/completions` |

### 4.4 模型发现与联通测试

- 聊天设置里提供「拉取模型」「测试连接」；自动拉取失败不阻塞使用。
- 缓存 `{models, fetchedAt, latencyMs, error}`；手动刷新。
- `POST /api/chat/accounts/{name}/probe` 返回分阶段结果：

```json
{
  "reachable": true,
  "tlsOk": true,
  "authOk": true,
  "modelsOk": true,
  "models": ["..."],
  "latencyMs": 183,
  "resolved": {
    "modelsUrl": "https://api.example.com/v1/models",
    "chatUrl": "https://api.example.com/v1/chat/completions"
  },
  "warnings": ["endpoint did not list models"],
  "error": null
}
```

- 默认不跑「测试对话」，避免花钱；需要时单独按钮并提示消耗 token。
- 私网/loopback 策略是设置项：默认 `allow`（Ollama/LM Studio/vLLM 直接可用）；`strict` 模式拦私网并配 allowlist。
- Docker 提示：容器里的 `127.0.0.1` 不是宿主机，Ollama 用 `host.docker.internal` 或宿主 LAN IP。

## 5. 协议适配

内部规范模型，协议差异全部在 adapter：

```java
record ChatRequest(String model, List<ChatMessage> messages, String system,
                   Double temperature, Integer maxTokens) {}

sealed interface ChatEvent {
    record TextDelta(String text) implements ChatEvent {}
    record ReasoningDelta(String text) implements ChatEvent {}
    record Usage(int input, int output) implements ChatEvent {}
    record Done(String finishReason) implements ChatEvent {}
    record Error(String code, String message, boolean retryable) implements ChatEvent {}
}

interface ChatProtocol {
    String id();
    PreparedRequest prepare(EndpointProfile profile, ChatRequest request, String key);
    void read(Stream<String> lines, ChatEventSink sink) throws IOException;
}
```

- v1 实现 `openai-completions` + `anthropic-messages`；`openai-responses` 放 Phase 4。
- 共用 `SseParser`：`event:`、`data:`、`id:`、注释、空行分帧、多行 data、`[DONE]`、CRLF、行长度上限。
- 错误映射：401/403 key 被拒；429 可重试；400 参数/上下文；5xx 可重试；SSE 内 `error` 事件不能当正常结束。
- 请求默认 `followRedirects(NEVER)`，避免公网地址 302 到内网。
- Java 17：`HttpClient` + 有界线程池；不使用虚拟线程。

## 6. SQLite 存储

### 6.1 模型

采用 RikkaHub 式「线性节点 + 每个节点多版本」，不做任意 parent_id 树：

```text
chat_session
  id, assistant_id, title, model, account_name, created_at, updated_at

chat_node
  id, session_id, ordinal, role, active_variant_id, created_at

chat_message
  id, node_id, variant_index, content, status, model,
  usage_json, created_at, edited_at

chat_attachment
  id, session_id, message_id, sha256, mime, size, path,
  extracted_text_path, extract_status, created_at

chat_account_profile
  account_name, protocol, base_override, models_path, chat_path,
  auth_style, extra_headers_json, models_json, models_fetched_at,
  last_probe_json, updated_at

chat_model_capability
  account_name, model, vision, files, reasoning, tools,
  max_context, max_output, source, updated_at

chat_setting
  key, value
```

- 一个会话就是按 `ordinal` 排列的节点列表。
- 助手节点可有多个 variant；`active_variant_id` 决定当前显示/上下文。
- 重新生成当前回复 = 给助手节点加 variant。
- 从当前强行重生成 = 删除目标节点之后的节点，再生成。
- 编辑用户消息 = 更新该节点内容 + 删除后续节点 + 重新生成（旧分支保留靠 fork）。
- fork = 复制会话到目标节点为新会话（Phase 4，可选）。
- 上下文 = 从第一个节点到最后一个节点，每个节点取 active variant。

### 6.2 配置与迁移

- 依赖 `org.xerial:sqlite-jdbc:3.54.1.0`。
- 文件 `<data>/chat/chat.db`；启动时建目录（0700）。
- `PRAGMA journal_mode=WAL; synchronous=NORMAL; busy_timeout=5000; foreign_keys=ON;`
- 迁移用 `PRAGMA user_version` + 顺序 SQL；不引 Flyway。
- 流式内容不按 token 写库：内存缓冲，`done`/`stopped` 落最终文本；需要恢复时每 1s 落快照。

### 6.3 保留策略

- 设置项 `chat.retention`：`forever`（默认）/ `30d` / `90d` / `180d` / `365d` / 自定义天数。
- 每日清理任务：删除 `updated_at` 早于阈值的会话、消息、附件文件。
- 用户手动删除优先；删除会话同时清附件和抽取文本。
- 备份不受保留策略影响，按备份保留策略单独清理。

### 6.4 备份

- 默认目录 `/data/backup`，命名 `chat-backup-YYYYMMDD-HHmmss/`。
- 内容：
  - `chat.db`：用 `VACUUM INTO` 生成一致快照；
  - `files/`：附件快照（复制或同盘硬链接）；
  - `manifest.json`：schema version、会话/消息/附件数量、校验和、创建时间。
- 默认每天 03:17 跑一次，保留最近 7 份；可在设置里改时间/保留数/关闭。
- 提供「立即备份」按钮和备份列表；恢复流程：停面板 → 替换 `<data>/chat` → 启动迁移。
- 备份目录权限 0700；备份包含聊天内容和附件，视同敏感数据。

## 7. Turn 状态机与重生成

```text
idle -> streaming -> done
                  -> stopped
                  -> error
```

- 每个会话同时只有一个 active turn；流式中再次发送先拒绝，UI 引导「先停止」。
- `ChatTurnManager` 持有 `turnId`、取消句柄、缓冲区、目标消息。
- 停止：`POST /api/chat/turns/{turnId}/cancel`，关闭上游流、停止发 delta、保存部分内容，状态 `stopped`。
- 迟到 delta 按 `turnId` 过滤，不能追加到已结束消息。
- 编辑/重新生成前先取消当前 turn。
- 崩溃恢复：`streaming` 状态在启动时改为 `interrupted`，允许重新生成。

### 7.1 两种重生成

`POST /api/chat/sessions/{id}/regenerate`：

```json
{ "nodeId": "...", "mode": "in_place" | "truncate_after" }
```

- `in_place`：
  - 只对助手节点有效；
  - 给该节点新增 variant，保留后续节点；
  - UI 警告：后续消息仍基于旧回复，适合「只想看看另一个答案」。
- `truncate_after`：
  - 删除目标节点之后的所有节点；
  - 对用户节点和助手节点都可用；
  - 适合「从这里改变对话走向」。
- `edit` 用户消息等价于更新内容 + `truncate_after`。
- 前端 split menu 明确写两种文案，不能只放一个「重新生成」让用户猜。

## 8. 附件与长文本

### 8.1 上传

- `POST /api/chat/attachments`（multipart）→ `{id, name, mime, size, sha256, kind}`。
- 文件存 `<data>/chat/files/<sha256>`；去重；元数据入库。
- `/api/chat/attachments` 在 `ApiGuardFilter.streamed()` 的放行名单里：它不适用
  1 MiB JSON 上限，走 servlet 的 multipart 限制（单请求 60 MB、图片 20 MB、
  文档 50 MB）；否则超过 1 MiB 的图片会在进入上传处理器前得到
  `413 payload too large`。
- 下载 `GET /api/chat/attachments/{id}`，校验会话归属。
- 限制：单文件 50MB（Office/PDF）/ 20MB（图片）、单消息 8 个、会话配额；删会话清附件。
- 图片前端 canvas 压到最长边 2048、JPEG/WebP 0.85；后端 `ImageIO` 校验/剥 EXIF。
- 拒绝 SVG/HTML/可执行文件；MIME 用魔数嗅探，不只看扩展名。

### 8.2 支持类型

| 类型 | 扩展名 | 处理 |
|---|---|---|
| 图片 | png/jpg/jpeg/webp/gif | 原生 image block（需要 `vision`） |
| PDF | pdf | PDFBox 抽文本；支持时发原生 document |
| Word | doc/docx | Apache POI 抽文本；`.docm` 拒绝宏 |
| PowerPoint | ppt/pptx | Apache POI 抽文本；`.pptm` 拒绝宏 |
| Excel | xls/xlsx | Apache POI 抽文本/CSV；`.xlsm` 拒绝宏 |
| 文本 | txt/md/csv/json | 直接读取，编码检测 |
| 音频/视频 | mp3/mp4/wav/webm 等 | v1 只保存，不送模型；标记「当前模型不支持」 |

- 其他类型明确报错，不要静默丢弃。
- Office/PDF 的图片、图表、格式不保证保留；只抽文字。
- 加密 PDF、损坏文件、超大压缩包直接拒绝并给原因。

### 8.3 协议映射

| 协议 | 图片 | 文件 |
|---|---|---|
| openai-completions | `image_url` data URL | 默认发抽取文本；模型能力打开「原生文件（PDF）」后发 `file` part（`file_data` + `filename`） |
| anthropic-messages | `image` block | PDF 发 `document` block；Office 发抽取文本 |
| openai-responses（后续） | `input_image` | `input_file` |

- 模型 `files=false` 时：发送抽取文本；单附件超过 30k 字符会截断并附「完整内容见附件」。
- `files=true` 表示**允许原生文件块**，不是“能读文件”：Anthropic PDF 默认可原生；OpenAI 兼容服务默认关闭，避免网关不支持时 400，用户可在模型能力里单独打开。
- **历史图片按模型能力省略**：切到 `vision=false` 的模型后，上文里的图片不会让整轮发送失败，而是变成「历史图片，当前模型不支持图片，已省略」的文字注记；只有**当前这条消息**里的图片仍返回 `model_no_vision`，提示移除图片或换模型。
- 转不了或超限就在发送前报错，不要发出去等 400；历史附件属于例外，省略而不是阻塞。
- 抽取文本也计入上下文预算。

### 8.4 长文本自动附件

- 阈值按估算 token，不按固定字符数：默认约 4k tokens。
- 粘贴超阈值文本 → 保存为 `.txt` 附件 + 消息 chip。
- 模型 `files=true`：发文件/文本 block；`files=false`：发送前 N 字 + 提示。
- 附件仍占上下文；超大文件的分块/检索不在 v1。

### 8.5 解析安全

- PDFBox / Apache POI 在独立有界线程池执行，设超时和内存/页数上限。
- 拒绝宏文档；不执行任何嵌入对象/脚本。
- 防 zip bomb、路径穿越、超大解压比。
- 解析失败保留原文件，但消息标记「无法读取内容」，不阻塞发送其他文本。

## 9. 模型能力

```java
record ModelCapabilities(
    boolean vision, boolean files, boolean reasoning, boolean tools,
    int maxContext, int maxOutput
) {}
```

- 存储：`chat_model_capability`，按 `account_name + model` 唯一。
- 来源优先级：用户手动覆盖 > vendor 预设 > 内置模型表 > unknown。
- 内置模型表（`ChatModelRules`）：按模型 id 的**词元序列**匹配，不是子串。
  `qwen3.8-flash` → `qwen 3 . 8 - flash`，`tokens("qwen","3","8")` 命中；
  `qwen3-8b` 里的 `8b` 是参数量，会被跳过，不会误判成 3.8。
  匹配按命中词元数评分：具体型号（`qwen3.8-max`、`glm-5.3-flash`、`deepseek-v4.1-flash`）
  压过家族通配（`qwen3.8`、`glm5`、`deepseek-v4`），同分规则取并集。
  规则来自 RikkaHub `ModelRegistry` 的同款做法，覆盖 GPT / Gemini / Claude / DeepSeek /
  Qwen / GLM / Kimi / 豆包 / Grok / MiniMax / MiMo / 混元 / 阶跃 / 商汤 等常见家族，
  另有 `vision`/`vl`/`omni` 关键词兜底。认不出来的 id 仍是 `unknown`（能力全关），
  不靠猜——猜错会直接变成 400。
- `files` 在实现里表示**原生文件块**（PDF）：Anthropic 默认开；OpenAI/Gemini/Qwen/GLM/Kimi 默认关，关闭时走文本抽取。
- **能力设置入口在聊天页的模型菜单**，不在主界面账户页；避免和 dsh 共用账户时互相影响。
- UI：模型列表逐行显示「图片 / 文件 / 思考 / 工具」文字徽章和来源（内置识别 / 供应商预设 /
  手动覆盖 / 未识别）；模型选择器的每条模型也带同一套徽章，认不出来时显示
  「能力未识别」。能力弹窗打开开关和上下文/输出上限，可「恢复默认」。
- 当前消息带图但模型没有图片能力时返回 422（提示换模型）；历史图片只省略，不阻塞整轮。
- `reasoning=true` 时折叠显示思考；不要在下一轮无条件回传 reasoning。
- `tools` 只做字段，v1 不展示工具 UI。

## 10. 后端接口

```text
GET    /api/chat/sessions?assistant={id}
POST   /api/chat/sessions
GET    /api/chat/sessions/{id}
PATCH  /api/chat/sessions/{id}
DELETE /api/chat/sessions/{id}

POST   /api/chat/sessions/{id}/messages      send
POST   /api/chat/sessions/{id}/regenerate    nodeId + mode
POST   /api/chat/sessions/{id}/edit          nodeId + text
DELETE /api/chat/sessions/{id}/nodes/{nodeId}
POST   /api/chat/sessions/{id}/nodes/{nodeId}/variant
POST   /api/chat/turns/{turnId}/cancel

GET    /api/chat/assistants
POST   /api/chat/assistants
PATCH  /api/chat/assistants/{id}
DELETE /api/chat/assistants/{id}
POST   /api/chat/assistants/{id}/duplicate

POST   /api/chat/attachments?sessionId={id}
GET    /api/chat/attachments/{id}

GET    /api/chat/accounts
PATCH  /api/chat/accounts/{name}
POST   /api/chat/accounts/{name}/capabilities
POST   /api/chat/accounts/{name}/probe
POST   /api/chat/accounts/{name}/models/refresh
GET    /api/chat/models?account={name}

GET    /api/chat/settings
PATCH  /api/chat/settings
POST   /api/chat/backup
GET    /api/chat/backups
```

- 发送/编辑/重新生成返回 `{turnId, userMessage, assistantMessage}`；流通过 WS 推。
- 客户端带 `requestId`，服务端做幂等；网络重试不重复插入用户消息。
- 设置包含：retention、backup、private endpoints、默认账户/模型；模型能力覆盖走
  `/api/chat/accounts/{name}/capabilities`。
- 权限：普通聊天只要求面板 session；`/api/chat/settings`、`/api/chat/backup`、
  `/api/chat/accounts/*`（PATCH/capabilities/probe/refresh）额外要求 developer elevation；
  所有 `/api/chat/*` 对 agent ticket 关闭（见 13.1）。

## 11. WS 协议

复用 `/ws`，topic `chat:<sessionId>`；服务端事件：

```text
chat-turn-started  { sessionId, turnId, messageId, attemptNo }
chat-delta         { sessionId, turnId, seq, text }
chat-reasoning     { sessionId, turnId, seq, text }
chat-usage         { sessionId, turnId, input, output }
chat-turn-finished { sessionId, turnId, status, finishReason }
chat-error         { sessionId, turnId, code, message, retryable }
```

- 前端进入会话 `ws.addTopics(["chat:"+id])`，离开 `removeTopics`。
- 每个事件带 `turnId` + `seq`；前端只认当前 turn，丢弃过期帧。
- 断线重连后先 REST 拉会话快照，再订阅；WS 不回放。
- 不在 WS 上做命令（发送/停止/编辑走 REST），减少 `WsGateway` 耦合。

## 12. 前端实现

### 12.1 新增文件

```text
web/src/pages/ChatPage.tsx
web/src/pages/chat/
  ChatSidebar.tsx       会话列表/助手切换/搜索/分组
  MessageItem.tsx       消息、variant、两种重生成、常驻操作栏
  Composer.tsx          输入区、附件、能力徽章、发送/停止
  ModelPicker.tsx       跨服务模型弹层（搜索/分组/能力）
  ChatSettingsPage.tsx  独立二级页（模型服务/默认模型/数据/网络）
  AssistantDialog.tsx   助手管理
  Markdown.tsx          GFM + sanitize + highlight + CodeBlock
```

- 路由：`web/src/App.tsx` 增加 `/chat` 与 `/chat/settings`；侧栏 `SideBar.tsx` 增加入口。
- 状态：页面级 reducer + hooks；不新增 Zustand/Redux。
- 复用：`Dialog`、`Snackbar`、`PopupMenu`、`icons`、`textBoxStyle`、`ws`。

### 12.2 Markdown

- `react-markdown` + `remark-gfm` + `rehype-sanitize` + `rehype-highlight`。
- 自定义 `CodeBlock`：语言标签、复制、下载、换行开关、长代码折叠；复制原始文本（strip ANSI）。
- 流式：30–60ms 合并 delta；未闭合围栏临时补闭合；代码块闭合后再高亮；已完成消息 `memo`。
- 可选 KaTeX；Mermaid 延后且延迟加载。
- 不启用 raw HTML；链接只允许 `http/https/mailto`，加 `rel="noopener noreferrer"`。

### 12.3 Composer

- 多行 textarea，Enter 发送 / Shift+Enter 换行（可配置），IME composing 时不发送。
- 模型选择、附件、发送/停止一体；流式中发送变停止。
- 图片粘贴/拖拽；Office/PDF/文本可拖入；长文本超过阈值自动转附件。
- 显示当前模型能力徽章；不支持图片时禁用图片入口，不支持文件时提示走文本抽取。
- 滚动：用户贴底才自动跟随；上翻时暂停。
- 模型弹层（`PopupMenu`）：按 `visualViewport` 定位与限高，手机键盘弹出、地址栏收起
  导致视口变化时收进可见区域而不是被顶出屏幕或直接关闭；触屏上搜索框不自动聚焦，
  点模型按钮会先收起输入法。

### 12.4 聊天设置（独立二级页 `/chat/settings`）

- **模型服务**：服务列表（搜索/状态/模型数）、添加供应商（内置目录 + 自定义）、
  API Key/地址/协议/paths、测试连接、拉取模型、模型列表（搜索/默认星标/能力徽章/
  能力覆盖）。
- **默认模型**：默认账户与默认模型。
- **数据与备份**：保留策略、长文本阈值、每日 token 上限、备份开关/时间/份数/目录、
  立即备份、备份列表。
- **网络**：允许私网端点（默认开）/ strict allowlist。
- 模型选择在输入区按服务分组，选中时同时切换会话的 `account` 与 `model`。
- 这些设置只影响聊天，不写回主界面账户页。

## 13. 安全

- Key 只在 Java 后端使用；任何 API/WS 不返回明文 key。
- Markdown sanitize；禁止 raw HTML、`javascript:` 链接、SVG 附件。
- 附件按会话鉴权；路径不暴露；删除会话清理文件。
- Office/PDF 解析在受限线程池，设超时/大小/页数上限；拒绝宏文档。
- 默认允许私网/loopback（本地模型），`strict` 模式可收紧；provider 请求不跟随重定向。
- 速率限制：每会话并发 1、每用户每日 token 上限（可配置）。
- 审计：记录 turn、模型、usage、错误；不记录 key。
- 备份目录 0700；备份包含敏感内容，不自动外传。
- 聊天页不持有面板写权限；v1 无工具，模型无法触发面板操作。

### 13.1 权限与 agent 边界

**v1 决策：聊天是人类操作员的表面，不是 agent API。**

- 所有 `/api/chat/*` 只接受面板 session cookie，走现有 `AuthFilter`；不接受 bearer agent ticket。
- 已把 `/api/chat/` 加进 `AgentScopes.forbidden()`，并让 `required()` 对聊天返回 `null`；两条都在 `GET -> READ` 快捷判断之前。否则 `GET /api/chat/sessions` 会被当成 `read`，任何默认 `read` 票都能读聊天历史。
- 不依赖“`POST` 没有 agent scope”这种偶然行为；显式 `forbidden` 是契约。
- `/ws` 本来就是浏览器通道：`required()` 对非 `/api/*` 返回 `null`，agent 拿不到 `chat:<id>` 的流；保持现状。
- 聊天设置、备份、endpoint/key 相关操作（`/api/chat/settings`、`/api/chat/backup`、`/api/chat/accounts/*`）除 session 外，已要求 developer elevation，复用现有 `DeveloperElevation`：它们能改 endpoint、导出/恢复全部聊天。
- 附件下载同样只接受 session，不能通过 agent ticket 拉取。
- v1 **不新增 agent scope**。如果未来确实要让 agent 使用聊天：
  - 新增独立 `chat` scope，且不要放进 `DEFAULT`；
  - 拆 `chat-read` / `chat-write`，不要复用通用 `read`；
  - 只允许专用 agent 会话，不允许读人类会话历史；
  - 加 turn/token 限额、审计、无附件/无设置；
  - 仍然不让 agent 接 `/ws`，只能轮询或走专用流。
- 风险说明：如果误开 `chat` 写权限，agent 可以循环“发消息 → 读历史 → 再发”，用面板的 key 烧 token。v1 工具/控制为零，不会出现 agent 反过来控制面板，但成本和隐私仍要防。

## 14. Java 17 与性能

- 目标 `--release 17`；CI 17/21 双腿继续保留。
- `HttpClient` + `Executors.newFixedThreadPool(4)`；一个活跃 turn 一个线程。
- 不使用虚拟线程、21-only API。
- 流式缓冲 + 30–60ms 批量推送；避免每 token 写库/每 token 重渲染。
- Office/PDF 抽取单独有界线程池，避免拖慢聊天。
- 长会话分页加载消息；历史上下文按模型 `maxContext` 截断。

## 15. 测试

- 路径解析：每个 vendor 的 `modelsUrl`/`chatUrl` 表驱动测试。
- SSE：每个协议正常、半帧、错误、usage、`[DONE]` fixture；fake provider server。
- Store：空库迁移、重复启动、节点/variant、两种重生成、编辑截断、附件清理。
- Turn：停止、停止与完成竞争、重试、编辑中取消、崩溃恢复。
- 附件：MIME 嗅探、大小限制、路径穿越、鉴权下载、PDF/Office 抽取、宏文档拒绝、zip bomb。
- 备份：`VACUUM INTO`、附件快照、manifest、保留清理、恢复。
- 前端：发送/流式/停止/两种重生成/编辑/复制代码；markdown XSS fixture。
- 权限：默认 `read` agent ticket 对 `GET/POST /api/chat/*` 均为 403；浏览器 session 正常；`/ws` 仍不可被 agent 使用。
- 部署：ARM64 SQLite 冒烟；nginx/Caddy 下 `/ws` 升级；子路径 `HDSL_BASE_PATH`。

## 16. 实施阶段

> 实现状态（2026-11-12）：Phase 0/1/2/3 已完成；Phase 4 未做。

1. **Phase 0 聊天设置地基**：已完成。
2. **Phase 1 后端聊天**：已完成。
3. **Phase 2 聊天体验**：已完成（Markdown/代码块、停止、两种重生成、编辑、分支、会话列表、动效、保留设置）。
4. **Phase 3 附件与能力**：已完成（图片、PDF/Office/文本抽取、长文本附件、能力开关、备份、跨服务模型选择）。
5. **Phase 4 扩展**：未做（openai-responses、音频/视频能力、fork、搜索；工具/控制仍不做）。

## 17. 已确认决策

1. **单人使用**：和 RikkaHub 一样，不做多用户隔离。
2. **保留策略**：默认永久；设置里可按时间清理；用户可手动删除。
3. **重生成**：同时支持「只重生成当前回复」和「丢弃后续、从当前强行重生成」。
4. **附件**：图片、PDF、DOC/DOCX、PPT/PPTX、XLS/XLSX、文本等常见类型；Office/PDF 走文本抽取。
5. **私网端点**：设置项，默认允许（Ollama 等），可切 strict。
6. **首批协议**：`openai-completions` + `anthropic-messages` 都做。
7. **能力设置**：放在聊天页模型菜单，与主界面账户页隔离。
8. **入口**：主界面侧边栏一级「聊天」。
9. **SQLite**：接受 `<data>/chat/chat.db`；备份到 `/data/backup/chat-backup-<时间>/`。
10. **工具/控制**：v1 不做，只保留 `tools` capability 字段。
11. **权限**：聊天是人类操作员表面；`/api/chat/` 对 agent ticket 关闭，v1 不新增 agent scope；设置/备份要求 developer elevation。
12. **实例隔离**：v1 不做。聊天是面板级功能，不绑定实例；单用户下实例隔离既不增加安全边界，又会把会话生命周期、备份和 UI 复杂化。只有以后聊天能读实例工作区/日志或按实例分权限时，再加 `scope=instance` 的独立模式。

## 18. 实现时采用的默认（原“剩余待定”已关闭）

1. 保留策略：永久 / 30 / 90 / 180 / 365 天 / 自定义天数。
2. 备份默认 03:17、保留 7 份，可在设置页改。
3. `.doc/.ppt/.xls` 旧格式支持（POI scratchpad）；宏文档仍拒绝。
4. 音频/视频只保存不送模型；发送时明确报错/提示。
5. 能力默认值：用户手动覆盖 > vendor 预设 > 内置常见模型表 > unknown。
6. v1 不新增 agent `chat` scope；`/api/chat/*` 对 agent ticket 全关。

## 19. 助手（Assistant）与角色隔离

> 名称：UI 用「助手」，可标注「专家 / 角色」；不要叫「智能体」，避免和未来有工具的 agent 混淆。
> 这是单用户下的逻辑隔离，不是安全边界。

### 19.1 数据

```text
chat_assistant
  id, name, avatar, description, system_prompt, opening_message,
  account_name, model, temperature, max_tokens, reasoning_level,
  quick_phrases_json, is_builtin, created_at, updated_at

chat_session
  + assistant_id
```

- 内置「默认助手」：空系统提示词、默认模型；可编辑但不可删除，提供「恢复默认」。
- 用户可新建助手：名称、emoji/头像、描述、系统提示词、开场白、默认模型/参数、常用短语（可选）。
- 会话必须属于一个助手；切换助手 = 新建会话，不在旧会话里替换系统提示词。
- 系统提示词每轮注入在消息历史之前；不出现在 transcript 里，编辑器显示估算 token 数。
- 助手默认模型在新建会话时复制到 session；会话内可临时切换模型，能力 gate 仍按会话模型判断。
- 删除助手：弹确认，把该助手的会话迁移到「默认助手」；不允许留下无主会话。
- 知识库、MCP、全局记忆、世界书、角色卡导入导出：v1 不做；等真正实现时再加字段和 UI，不先留死数据。

### 19.2 隔离语义

- 会话列表按 `assistant_id` 过滤；上下文只包含当前会话的消息，不跨助手拼接。
- 助手之间不共享 system prompt、开场白、快捷短语。
- 单用户下这不是权限隔离：聊天 API 仍是 session-only，agent ticket 仍然全禁。
- 备份包含助手表和会话的 `assistant_id`；恢复后分组不变。

### 19.3 接口

```text
GET    /api/chat/assistants
POST   /api/chat/assistants
PATCH  /api/chat/assistants/{id}
DELETE /api/chat/assistants/{id}        // 迁移会话到默认助手
POST   /api/chat/assistants/{id}/duplicate   // 可选

POST   /api/chat/sessions               // body 带 assistantId
```

### 19.4 UI

- 聊天侧栏顶部：助手切换器（头像 + 名称）+「管理助手」。
- 会话列表只显示当前助手的会话；「默认助手」是初始入口。
- 助手编辑对话框分页：
  - **基本**：名称、emoji/头像、描述；
  - **提示词**：系统提示词、token 估算、恢复默认；
  - **模型**：账户/模型、temperature、max tokens、reasoning level；
  - **短语**：常用短语（可选）。
- 顶部显示当前助手；点「切换助手」时提示会新建会话。
- 不做「知识库 / MCP / 全局记忆」tab，避免空壳入口。

### 19.5 测试

- 助手 CRUD；默认助手不可删；删除后会话迁移正确。
- 系统提示词注入顺序、token 估算、开场白只出现一次。
- 会话按 assistant 过滤；上下文不串助手。
- 备份/恢复后 assistant_id 和分组保持。
- 默认 `read` agent ticket 对 `/api/chat/assistants*` 仍为 403。

### 19.6 阶段

- Phase 0/1：`chat_assistant` 表、默认助手种子、会话 `assistant_id`、CRUD API。
- Phase 2：侧栏切换器、助手编辑对话框、按助手过滤会话。
- Phase 3/4：常用短语、开场白增强、角色卡导入导出（如果确实需要）。

## 20. 实现记录（2026-11-12）

- 分支：`web-shell`；未合入 `main`，未推送。
- 主要提交：
  - `82815c238` 存储、端点解析与两种协议适配
  - `244eea39b` 附件落盘、MIME 嗅探与文本抽取
  - `9da754812` 会话 API、turn 状态机与 agent 权限边界
  - `15c2a7c78` 聊天页、Markdown 与助手/设置界面
  - `9d3e5ddae` 默认账户兜底、probe 缓存与端到端 turn 测试
  - `d300c8e65` 流式竞态、附件清理与图片压缩
  - `a806c8b8c` 视觉、提权态与代码块修复
  - `1db4814d7` 独立设置页与模型服务管理、消息左右对齐
  - `71636e843` 跨服务模型选择、模型服务入口与原生文件开关
- 后端入口：`src/main/java/org/jackhuang/hmcl/web/chat/`；前端入口：
  `web/src/pages/ChatPage.tsx`、`web/src/pages/chat/`。
- 验证：`./gradlew test` 全绿；`npm run typecheck` / `build` / `compat` 绿；
  `shadowJar` 成功；本地 Playwright 在 1440×900 与 390×844 下检查聊天页、
  模型弹层、设置页与能力弹窗。
- 线上状态：面板 0.6.85 仍服务旧前端资源，需重新构建/重启容器；未在本仓库
  执行部署，也未写入任何真实 key/口令。
- 已知未做：模型服务 ON/OFF 开关、模型家族分组、原生 OpenAI PDF 的真实网关
  联调、备份恢复 UI、音频/视频原生输入、fork/搜索。
