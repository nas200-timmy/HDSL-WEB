# MC 安装与下载源回退

这张图对应 `McApiServlet` → `TaskService` → `McCores.install` 的主路径，重点是**源顺序、慢源判定、失败换源**。Forge/NeoForge 走 `McForge` 的 installer 分支，图里单独标出。

```mermaid
sequenceDiagram
  autonumber
  participant B as 浏览器
  participant A as AuthFilter + McFeatureFilter
  participant M as McApiServlet
  participant T as TaskService
  participant C as McCores
  participant S as McSources
  participant U as 上游 API
  participant D as 下载 CDN
  participant I as McInstanceManager
  participant E as EventBus / WS

  B->>A: POST /api/mc/instances/{id}/install {version}
  A->>A: 会话 / Agent 票据 + MC 功能门
  A->>M: 放行
  M->>T: submit("mc-install", id, work)
  T-->>B: 202 {taskId}
  T->>C: install(instance, version, progress)

  alt core = vanilla
    C->>U: McVersions.install
    U-->>C: server.jar + manifest
  else core = paper / folia / purpur / fabric
    C->>S: orderFor(core)
    S-->>C: 当前源顺序
    loop 每个源，直到成功
      C->>U: resolveFrom(source, core, version)
      U-->>C: URL + digest
      C->>D: GET jar
      alt 下载成功且 hash / zip 通过
        C->>C: writeArtifactCache()
        C->>I: link(shared, instance/server.jar)
        C->>I: update(version)
        C-->>T: 返回成功
      else HTTP 错误 / 低于 64 KiB/s / hash 失败
        C->>C: 记录失败，换下一个源
      end
    end
  else core = forge / neoforge
    C->>U: McForge.resolve + installer
    U-->>C: build + installer jar
    C->>D: 下载 installer
    C->>C: 运行 installer（--mirror）
  end

  T->>E: 发布进度 / 完成
  E-->>B: /ws 事件或 /api/tasks 轮询

  alt 所有源都失败
    T-->>B: 任务失败，错误里带每个源的原因
  end
```

## 关键点

- **下载窗口**：`McCores.download` 每 10 秒看一次吞吐，低于 `640 KiB`（约 64 KiB/s）就判定该源不可用；单个 jar 的绝对上限是 10 分钟。慢源不会再把任务挂到 30 分钟超时。
- **换源**：`downloadFromAnySource` 按 `McSources.orderFor(core)` 逐个尝试。解析失败、HTTP 非 200、hash 不匹配、zip 校验失败、下载过慢都会进入下一个源。
- **缓存**：`artifact.json` 只在下载并校验成功后写入；失败源不会污染下一次安装。已经可复用的共享 jar 会直接 `link`，不再下载。
- **进度**：`McCores` 通过 `McVersions.Progress` 更新“下载 x / y MB”，`TaskService` 再经 `EventBus` 送到 `/ws` 或 `/api/tasks`。
- **Forge/NeoForge**：不是直接下 ready jar，而是先解析 build、下载 installer，再用 `--mirror` 跑安装器；库文件由 installer 自己下。
- **Fabric 首次启动**：面板只下 179 KB 左右的 Fabric server launcher；Minecraft 服务端、Fabric 库和 remap 是 launcher 在首次启动时自己下的。这条路径不受面板“下载源”设置控制，详见 [docs/minecraft.md](../../minecraft.md)。
