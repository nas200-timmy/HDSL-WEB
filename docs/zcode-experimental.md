# ZCode（实验性品类）

> **纯实现性验证功能：不保证可用，也不承诺与 dsh 同等的功能与兼容。** 能不能跑通取决于
> ZCode 上游；本页只说明我们怎么接的、怎么放发行包、以及已知限制。

面板的第二个可启动品类，对应 [zai-org/ZCode](https://github.com/zai-org/ZCode)（Z.ai 的
AI 编程工作台，Apache-2.0）。它与 dsh 品类**完全隔离**：实例页里独立品牌分区、独立数据
目录、独立进程；浏览器侧经面板 `/i/<id>/` 反代打开（同端口、同证书、同会话门），实例
本身只绑回环、不开令牌。

## 为什么是「实验性」

- ZCode 官方**没有 CLI 发行渠道**——官网与 GitHub Releases 只有桌面安装包，命令行发行包
  得自己构建（`pnpm build:zcode`，要求 Node 24.14+）。
- ZCode 的 Web 前端把根路径写死（`/assets`、`/api`、`/ws`），所以面板构建时会打上反代补丁
  （见下），让它按 `/i/<id>/` 前缀工作；补丁之外的行为取决于上游，随时可能变。
- 凭证注入写的是 ZCode 的内部配置文件格式（`provider_config.json`），未文档化，上游一改
  就失效——「能注就注，不能就算了」。
- 上游大约周更。

## 怎么得到发行包

### 方式一：面板内构建（默认）

实例页 ZCode 分区里的「构建发行包」按钮：填一个上游 tag（`v3.14.3`）或分支（`main`），
面板会

1. 从 GitHub 下载源码 tarball（`codeload.github.com/zai-org/ZCode`）；
2. 解包后打上[反代补丁](#反代补丁)；
3. 跑 `pnpm install` 与 `pnpm build:zcode`；
4. 校验产物记录的 `sha256`，装到 `<数据目录>/zcode/releases/<版本>/`，并把 `current` 指过去。

界面上能看到当前步骤、进度和日志（日志文件在 `<数据目录>/zcode/build/build.log`）。

要求与代价：镜像里已经装好 Node 24（`/opt/node24`，只有构建用它），以及 `python3` / `make` / `g++`
——ZCode 依赖里的 `node-pty`、`cpu-features` 要**现场编译**，缺了编译链会直接报
`Could not find any Python installation` / `Unable to detect compiler type`；要下几百 MB 源码、
装 GB 级依赖，**慢是正常的**；构建失败（环境缺失、上游改了锚点）只影响这一次构建，
已装好的版本不受影响——而且**失败时源码树会保留**（`<数据目录>/zcode/build/src` 与同目录的 `build.log`），
排查完不用重下，下一次构建开始时才清掉。

| 环境变量 | 说明 |
| --- | --- |
| `HDSL_ZCODE_SOURCE_URL` | 源码 tarball 模板（`%s` = tag/分支）；也接受 `file:` 或本地路径（离线/内网代理） |
| `HDSL_ZCODE_BUILD_BIN` | 构建时前置到 `PATH` 的目录；镜像里默认 `/opt/node24/bin` |
| `HDSL_ZCODE_PNPM` | 构建用的 pnpm 可执行文件；默认 `pnpm` |
| `HDSL_ZCODE_KEEP_SOURCES` | `true` 时构建**成功**后也保留源码树与依赖（排错用，占几个 GB）；失败时无论如何都保留 |
**慢？** 时间几乎全花在 `pnpm install` 上。三个杠杆：

- **换 registry**：首选面板的「设置 → 下载源」（预设或自填 URL，见
  [deployment.md 的运行期下载源](deployment.md#6-运行期下载源面板设置)），保存即生效不用重启容器。
  构建会把它显式传给 `pnpm install --registry=…`，日志里能看到实际用的是哪个。环境变量 `NPM_CONFIG_REGISTRY`
  只是**回退**：面板没有设置时才用它（镜像默认官方 `registry.npmjs.org`，国内常用 `https://registry.npmmirror.com/`）。
  选 `npmmirror` 预设时构建还会带上 `ELECTRON_MIRROR=https://npmmirror.com/mirrors/electron/`——Electron 的
  postinstall 要从 GitHub 下约 100 MB 的二进制，国内直连基本下不动；别的源不带这个镜像，走 GitHub 默认。
- **架构过滤已取消**：曾经有一版会往源码目录追加 `.npmrc` 的 `supportedArchitectures`，只下本机架构的
  可选依赖。它有两个问题：pnpm 是从 `pnpm-workspace.yaml` 读这个设置、**根本不看 `.npmrc`**（所以它从未生效）；
  而一旦生效就会把构建搞坏——组包阶段要为 6 个平台收集原生资源（darwin/linux/win × x64/arm64），缺一个直接失败。
  上游的 `pnpm-workspace.yaml` 已经写着它需要的平台，交给它即可。
- **源码下载**：默认从 `codeload.github.com` 拉 tarball；慢的话用 `HDSL_ZCODE_SOURCE_URL` 指向镜像、
  内网缓存，或本地路径（`/path/to/ZCode.tar.gz`，也接受 `file:`）。
- **native 编译那两段（换源管不到）**：依赖里有要现场编译的原生模块（`node-pty` 等）。node-gyp 编译前会先从
  `nodejs.org` 下 10 MB 头文件——国内实测约 104 KB/s，且缓存在 `~/.cache/node-gyp`（**不在卷里**，重建容器后
  第一次构建要重下）；它默认也不给 `make` 加 `-j`。用镜像源时构建会设 `NODEJS_ORG_MIRROR` /
  `npm_config_disturl` 指向 npmmirror 的 Node 镜像，并按 CPU 数设 `JOBS` / `npm_config_jobs` 并行编译。

安装之后、打包之前，构建器会额外补一步 `pnpm exec tsc -b packages/shared`：上游干净检出下
`pnpm build:zcode` 必然停在 `Missing @zcode/shared dist files`——`packages/shared` 没有 build 脚本，
报错里让你跑的 `pnpm build` 也不会编译它，只有项目引用构建（`tsc -b`）会产出 `dist/index.js`。
上游补好之后这一步可以直接删。

### 方式二：手工放一个发行包

`HDSL_ZCODE_PACKAGE` 指向一个解包好的发行包目录，或直接放进
`<数据目录>/zcode/releases/<版本>/`（需含 `bin/zcode.mjs`）。

面板找发行包的顺序：`HDSL_ZCODE_PACKAGE` → `current` 指向的版本 → `releases/` 里最新的一个。

| 环境变量 | 说明 |
| --- | --- |
| `HDSL_ZCODE_PACKAGE` | 发行包目录（覆盖上面的自动查找） |
| `HDSL_ZCODE_NODE` | 运行 ZCode 的 node 可执行文件；缺省用 PATH 上的 `node` |

### 反代补丁

面板构建时把 5 处根路径改成「带 `/i/<id>/` 前缀工作」：vite 的 `base: "./"`、
`main.tsx` 里的 `hdslBasePath()`（从浏览器路径读前缀）、WS origin、`/api/server-info`
fetch、Z.ai OAuth 的 `tokenUrl`。上游把这些锚点挪走时构建会**明确失败**并点名文件，
而不是产出一个指向错误地址的发行包；届时更新 `ZcodePatch` 与本文档。

## 实例怎么跑

- 每实例一个目录 `<数据目录>/zcode/instances/<id>/`：`instance.json`（面板记录）、
  `data/`（作为 `ZCODE_DATA_BASE_DIR`——ZCode 的配置、凭据、会话都在其下）、`workspace/`、
  `logs/zcode.log`。
- 启动命令：`node <发行包>/bin/zcode.mjs --web --no-open --host 127.0.0.1 --port 0
  --no-token --workspace <实例>/workspace`；面板从 stdout 的就绪行
  `Local: http://127.0.0.1:<port>/` 解析真实端口。**只绑回环、不开令牌**：对外只有面板这一条路，
  令牌认证在这里没有位置，面板的会话门就是唯一的门。
- 「打开」= `/i/<id>/`——和 dsh 同一个挂载点，同端口、同证书、同样要登录；请求由面板剥掉前缀
  转发到实例的回环端口，WebSocket 也走同一条中继。
- 保存 API Key 时写入 `<实例>/data/.zcode/v2/provider_config.json`（ZCode 的个人供应商
  配置；条目 `providerId = hdsl-injected`，协议 `openai-chat-completions`）。

## 已知限制（都是有意为之）

- **只有面板这一条路**：实例只绑回环、不开令牌，浏览器经 `/i/<id>/` 进来（面板的会话门就是
  唯一的门）。别去映射 ZCode 自己的端口——它也不监听外面。
- **API Key 明文**：同时写在实例清单与 `<实例>/data/.zcode/v2/provider_config.json`。
- **没有面板侧集成**：会话列表、插件管理、控制台（ZCode 没有 ACP）都只属于 dsh 品类。
- **凭证注入尽力而为**：格式取自 ZCode v3.14.3 源码，换成别的版本可能不生效——那就当它
  没有这个功能。
- **深层相对路径可能取不到资源**：补丁用 vite 的 `base: "./"`，资源按文档 URL 解析——
  `/i/<id>/` 下正常，更深的路径（例如上游的分享页）可能失效。

## 相关代码

- 后端：`src/main/java/org/jackhuang/hmcl/web/zcode/`（`ZcodeApiServlet`、`ZcodeRuntime`、
  `ZcodeInstanceManager`），挂在 `HdslServer` 的 `/api/zcode/*`（与其它 `/api/*` 一样过
  会话门）。
- 前端：`web/src/components/ZcodeSection.tsx`（实例页里的独立品牌分区）。
- 测试：`src/test/java/org/jackhuang/hmcl/web/zcode/`，用 `src/test/resources/fake-zcode/`
  这份 stub 发行包跑完整生命周期。
