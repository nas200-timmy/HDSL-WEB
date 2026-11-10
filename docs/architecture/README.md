# 架构图

本目录用 **Markdown + Mermaid** 维护 HDSL-web 的架构图。GitHub 直接渲染，不需要额外的图片服务或文档站。

目标不是把 336 个 Java 类逐一画出来，而是固定住**边界、依赖方向和关键流程**。代码仍是唯一事实来源；图只回答“谁负责什么、请求怎么走、数据落在哪里”。

## 图

| 文件 | 视角 | 回答的问题 |
|---|---|---|
| [context.md](context.md) | 战略 | 面板、实例、外部源、`/data` 的边界 |
| [full.md](full.md) | 战术 | 全部模块、进程、端口、数据布局 |
| [backend.md](backend.md) | 实现 | 后端包的依赖方向与回边 |
| [flows/mc-install.md](flows/mc-install.md) | 流程 | MC 安装、下载源顺序、慢源回退 |
| [flows/brand-launch.md](flows/brand-launch.md) | 流程 | 品牌实例启动、就绪探测、`/i/` 反代、终端 |
| [flows/auth.md](flows/auth.md) | 流程 | 登录、开发者提权、Agent 票据、scope、自杀闸 |

建议阅读顺序：`context` → `full` → 按需看一个 `flows`。`backend` 适合改代码前确认依赖方向。

## 维护约定

- 图是**模块级**，不是类级；不要为 getter、字段、单个方法画图。
- 先改代码，再改图；行为、边界、端口或数据目录变了就更新对应图。
- Mermaid 节点 ID 用英文，标签用中文；标签里有 `/`、`:`、括号时放进 `["..."]` 引号。
- 不把 token、密码、真实账号、内网地址写进图。
- 新增外部依赖、端口段或数据目录时，同步 `context.md` 和 `full.md`。
- 如果 CI 要校验，使用 `@mermaid-js/mermaid-cli` 渲染到临时目录；不把渲染产物提交进仓库。
