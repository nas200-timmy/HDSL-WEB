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
- **固定反代 `/i/octop/` 是"入口"，不只是别名**：开启后，保留实例的「打开」按钮与 `POST
  .../octop/instances/<id>/open` 返回的 URL 都会变成 `<base>/i/octop/`；关闭后回到 `/i/<token>/`。
- **本地源码编译**（仅 Linux）：下载 tag 源码，应用 dashboard 补丁，`npm ci && build`，再优先从 PyPI
  取 uv、下载 Python 3.12 并 `uv sync`；失败会恢复原预编译 release。构建完删除 `node_modules`，
  源码 release 约 2.8 GiB。

### 源码编译的真实成本（先看完再点）

文档以前只写"首次较慢"，实测远比这个吓人，先说清楚：

- **需要 C 工具链**：`evdev`、`crcmod`、`oss2`、`aliyun-python-sdk-core` 等 6 个以上依赖要现场编译
  sdist，机器上没有 `gcc`/`make` 会直接失败。
- **可能要 1 小时以上**：冷缓存 + 慢盘（写盘 ~100 KB/s 时，`playwright/driver/node` 这一个文件
  就写了 45 分钟）实测 55 分钟还没进 `uv sync` 收尾；换成热缓存重跑约 9 分钟。
- **额外磁盘约 3 GiB**：uv 缓存约 2.7 GiB + 源码 release 约 2.8 GiB（构建完会删 `node_modules`）。
- **没有取消入口**：任务面板只有一行滚动文本、进度条是未知（`fraction: -1`），中途想停只能停面板
  进程——**那样会留下半安装**（见下）。

### 半安装与自动修复

源码构建失败/被杀时会留下 `<version>.previous`（原来那份完整的）与一个没有 marker 的
`<version>`。面板现在做了两件事，别再用 `mv` 手修：

- **枚举时**只把带 marker 的目录当成已安装版本，并忽略 `*.previous`；所以半成品不会被列出来，
  `default` 也不会给新实例指一个装不上的版本。
- **启动时对账**（`BrandInstaller.reconcileOctopReleases`）：如果 `<version>` 没有 marker 而
  `<version>.previous` 在，就把半成品改名成 `<version>.broken-<时间戳>` 留着排查，再把
  `.previous` 挪回 `<version>`。看到日志里的 "Recovered the Octop release …" 就是这次修复。

另外两条已知行为，别当故障：

- **任何安装（不只源码构建）都不允许覆盖正在运行的实例所用的版本**，会先返回 409 让你停实例。
- **构建期间同一版本的实例会短暂报「未安装」**：marker 要等构建成功才写，这期间点「启动」会失败
  而不是排队；等构建结束再点即可。

策略存 `<data>/brands/policy.json`，默认全关；面板重启后固定路径恢复。
