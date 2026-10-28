# Octop 品牌实例

Octop（[TencentCloud/Octop](https://github.com/TencentCloud/Octop)，MIT）在面板里是**普通品牌实例**：
随机 token、`/i/<token>/`、多实例并存，和其他品牌同一套创建/启动/停止/打开/日志。

## 安装

默认走 GitHub Release 的 portable 包：

```text
Octop-portable-<linux|windows|darwin>-<amd64|arm64>-<version>.zip
```

包里已经带 CPython 3.12、全部 Python 依赖和 dashboard，所以面板不需要系统 Python、uv 或 Docker。
面板只把 `packages/octop/dashboard` 换成随 jar 分发的补丁版：

```text
src/main/resources/octop/octop-dashboard-<version>.zip
```

版本选择器只列出本面板带补丁 dashboard 的版本，避免选到渲染不出挂载点的上游 tag。
需要换版本时，重新生成这个 zip 并重新打包面板。

`OCTOP_RELEASE_BASE` 可以指向镜像；默认是
`https://github.com/TencentCloud/Octop/releases/download`。

## 实例

- 数据：`<data>/brands/octop/instances/<token>/home`（`OCTOP_HOME`），
  workspace 与日志同目录。
- 首次启动自动执行 `launch.py init --yes`，管理员密码写到
  `<home>/octop-admin.txt`（0600）。
- 端口由面板预分配，只绑 `127.0.0.1`；浏览器经 `/i/<token>/` 进入。
- 状态以 `GET /api/health` 为准，不解析日志。

## 前端补丁

上游 dashboard 按 `/` 构建；挂到 `/i/<token>/` 需要两件事：

1. 构建用相对 base，代理在 `index.html` 注入 `<base href="${basePath}/i/<token>/">`
   和 `window.__OCTOP_BASE__`；
2. `auth_token`、`octop:active-agent` 等 localStorage key 按挂载点命名空间化。

补丁源码在 `integrations/octop/patches/`（见该目录 README），构建产物就是上面的
`octop-dashboard-<version>.zip`。

## 最后手段（开发者模式）

设置 → 开发者模式 → 品牌实例管理，需要开发者密码：

- **单实例模式**：必须指定保留实例；其余实例灰掉，启动/打开/修改/删除被拒绝，数据保留。
- **固定反代 `/i/octop/`**：要求单实例，指向保留实例；关闭后回到 token 路径。
- **本地源码编译**（仅 Linux）：下载 tag 源码，应用 dashboard 补丁，`npm ci && build`，再优先从 PyPI
  取 uv、下载 Python 3.12 并 `uv sync`；首次较慢，失败会恢复原预编译 release。构建完删除
  `node_modules`，源码 release 约 2.8 GiB。

策略存 `<data>/brands/policy.json`，默认全关；面板重启后固定路径恢复。
