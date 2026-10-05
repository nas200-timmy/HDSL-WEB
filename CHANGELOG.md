# 更新日志

版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)；每条记录「改了什么」与「为什么」。

## v0.2.4 — 2026-10-06 · 环境体检：内核文件监视器配额

给「实例起不来」补一个能看的病因。症状是**两个实例不能共存**：一个在另一个起来之后起不来，反过来
也一样；而两种表现都不指向病因——实例要么起来就消失，要么进程活着、端口在监听，却一直停在「启动中」、
`/i/<id>/` 返回 502。

根因不在反向代理，也不在 dsh 之间，而在一个共享的内核数字：`fs.inotify.max_user_watches`
**按 uid 计、容器之间不隔离**。面板容器以 `hdsl`(1000) 运行，宿主上任何同样以 uid 1000 运行的程序
都从这个数字里扣；而 dsh 启动时要用 chokidar 监视自己的 `profiles/<profile>`。

- 实测账：上限 65536（内核默认值），而宿主上**同一个 uid 的另一个递归监视程序**就能把它吃到几乎
  全满——用正确测法（连续申请并保持打开）只剩 **1 个**可注册；一个 dsh 实例实际只要 ≈9 个。
- 拿不到监视器时 node 要么当场崩在
  `Error: ENOSPC: System limit for number of file watchers reached, watch '…/profiles/web'`，要么那个
  启动项永不结束、就绪行一直不打印。两种失败都在一次性容器里复现过。
- 「谁被拒」由启动那一刻还剩几个空位决定，与实例身份无关：`instance` 的 pokemon profile 实测只占
  0 个 watch（`@hellosz/dsh-pets` 与 `dsh-wildmon` 都不用文件监视器），它不是吃配额的那个。
- 把那个占着配额的程序停掉、配额腾空之后，同一个一次性容器里两个实例（0.2.0-rc.2 与 0.1.5-alpha.2）
  可同时 RUNNING 并稳定运行，两个挂载点各自 200、页面 `<base href>` 各指向自己的挂载点、互不串台。

### 新增：环境体检里的「File watchers」

**只加诊断，不动启动流程。**

- 新增 `util/platform/WatcherBudget`：读上限，并用「真实注册目录」探测还剩多少（每个 watch 对应一个
  自己的目录，与 `inotify_add_watch` 同语义；用完即删；`-1` 表示问不出来，绝不当成「没有了」）。
- `DshDoctor` 新增 `File watchers` 段：充足时 `at least 64 free of 65536  [ok]`，不足时
  `1 free of 65536  [SHORTAGE]` 并打出可直接复制的 `sysctl -w …=524288`。探测上限是 64，所以充足时
  写「at least」——把 64 说成总数会读成「快满了」，正好相反。
- `DoctorApiServlet`：`[SHORTAGE]` 映射为 `warn`。

### 顺手修掉一个 JDK 坑

`/proc` 文件报告 `st_size=0`，而 `Files.readString` 走按 size 读的路径，对 `65536` **只返回首字节
`"6"`**（同一容器里 `readLine` 得到 `65536`）。`WatcherBudget` 因此按行读，并有用例钉住「读完整的数、
不是首字节」——一个看起来合理的错数字比没有数字更糟。

### 验证

- 全量 **497 用例 / 0 失败 / 0 错误 / 0 跳过**（新增 11 条：探测读数与清理、三种行文案、状态映射）。
- 临时容器里换入新 jar 的面板打 `GET /api/doctor`，实到
  `file-watchers  status=ok  inotify watches: at least 64 free of 65536  [ok]`。
- 未验到「真·配额不足时该行在 API 里的渲染」：要验就得把那台机器的配额喂满。该分支由单测覆盖，而
  「配额满时内核确实拒绝」另有实测。

### 文档

- `README.md`「常见问题」加这条症状的入口。
- `docs/deployment.md` 新增 §8「宿主 inotify 配额」（配额怎么算、两种现象、怎么确认、怎么调大，以及
  不想调配额时的两条替代路——其中「换 uid」按实测写了除 compose 之外还要解决的写权限），并补上此前
  漏掉的 §7/§8 目录项。

## v0.2.3 — 2026-10-05 · 反代挂载下 dsh 网页能打开了

修一个用不了的功能：实例改走 `/i/<id>/` 反代之后（v0.2.2），**点「打开 dsh」进不去**——
按钮看着像在原地刷新，手动敲地址则是白屏。根因是 dsh 的网页端假定自己拥有整个 origin，
而代理只做了上游那份参考实现里的那几件事。

### 修复

- **`Location` 没有重写**：dsh 的 token 兑换回 `303 Location: /`，浏览器跟着它离开挂载点、落到
  面板首页——就是「原地跳转」。现在改回挂载点内；指向上游回环端口的绝对地址也一并改写，
  外部地址原样放行。
- **dsh 生成的页面把根路径写死**：页面开头是 `<base href="/">`，另有 52 处根绝对的 `/plugins/...`
  （`<link rel=preload>`、`<script src>`，以及内联 `__DSH_BOOT__` 里每个插件条目的 `url`）。经挂载点
  访问时它们指向面板，面板的 SPA 兜底把 `index.html` 当 JS 回，浏览器报
  `Failed to load module script: … MIME type of "text/html"`，一个脚本都加载不了——白屏。现在把这两处
  加上挂载点前缀，并去掉那行取不到凭证的 manifest 链接；正文是 gzip 的，**先解压再改写**，回给浏览器时
  丢掉 `content-encoding` / `content-length`。
- **应用运行期自己拼的 URL 也是根绝对**（`fetch("/api/…")`、`ws://<host>/api/remote.mux`、
  `/plugins/events` 的 SSE）：改写后的页面里注入一段小 shim，把 `fetch` / `XMLHttpRequest` /
  `WebSocket` / `EventSource` 包一层，同 host 且不在 `/i/` 下的 URL 加上挂载点（字符串、`URL` 对象、
  `Request` 三种入参都处理）。SSE 与 WebSocket 仍然流式转发，不受影响。
- **`ProxyBehaviorTest` 加 3 条回归用例**：页面改写（含 gzip、含「不认识就一字不改」）、`Location`
  的四种形状。把实现换回修复前的版本跑同一批用例，新用例如期失败——测试确实钉住了这个 bug。

### 验证

- 全量 **486 用例 / 0 失败 / 0 错误 / 0 跳过**（新增 3 条）。
- 临时容器里用无头 Chromium 真跑整条链路：dsh **0.1.5-alpha.2** 与 **0.2.1-alpha.1** 两个版本都能打开，
  最终地址停在 `/i/<id>/`，所有接口变成 `/i/<id>/api/…` 并返回 200，`/i/<id>/plugins/events` 是
  `text/event-stream`，DOM 里有完整界面，**控制台零报错、无 401/404**。

### 其他

- 对照上游的结论（免得以后以为我们擅自偏离）：`deepseek-ai/deepseek-harness`（**默认分支是 `master`，
  不是 `main`**）的 `apps/web/tests/prefix-proxy.ts` 是给**它自己的测试客户端**（`scaffold.ts`：知道前缀、
  自己处理 303、用 `baseUrl` 拼路径）用的最小件，**它本身也不重写 `Location`、不碰页面**；全仓没有
  `X-Forwarded-Prefix`，dsh 也没有任何 base/prefix 旗标。上面那三层是本启动器自己的。

## v0.2.2 — 2026-10-05 · ZCode 实验品类 + 面板可选下载源

v0.2.0 之后的第一个发布，两块内容合在一起：**ZCode**（第二个可启动品类，实验性）从零接进来——
面板自己下载上游源码、打反代补丁、`pnpm` 构建、装成版本，实例只绑回环、经 `/i/<id>/` 反代访问；
以及**面板里可选下载源**，把「换源要改环境变量、还得重启容器」变成一等设置，并顺手拆掉 ZCode
构建链上几个必然失败的坎。（v0.2.1 未单独发布，其内容并入本版。）

### 新增：ZCode 实验品类（实验性）

- **独立品牌分区**：实例页里 ZCode 与 dsh 完全隔离，文案与 `docs/zcode-experimental.md` 都写明
  「纯实现性验证功能：不保证可用，也不承诺与 dsh 同等的功能与兼容」。
- **面板内构建** `POST /api/zcode/build`：下载上游 tag/分支的源码 tarball → 打 5 处反代补丁
  （vite `base: "./"`、`main.tsx` 的 `hdslBasePath()` 与 WS origin、`/api/server-info`、OAuth
  `tokenUrl`）→ `pnpm install` → `pnpm build:zcode` → 校验产物记录的 sha256 → 装成
  `<数据目录>/zcode/releases/<版本>/` 并维护 `current`。界面有步骤、进度与实时日志；上游把锚点
  挪走时构建**明确失败并点名文件**，而不是产出一个指向错误地址的发行包。
- **反代**：实例只绑 `127.0.0.1`、`--no-token`，浏览器经面板的 `/i/<id>/` 进来——同端口、同证书、
  同样要登录；HTTP 代理与 WebSocket 中继共用一套目标解析（`InstanceProxyTargets`），
  未知 id 404、已知未运行 502 的语义与 dsh 一致。
- **凭证**：保存的 API Key 按 ZCode 的 `provider_config.json` 格式写入实例目录（未文档化格式，
  尽力而为；上游一改就可能失效）。
- 镜像里加 Node 24（`/opt/node24`，只有构建用它，面板与 dsh 仍是 Node 22），发行包与限制见
  `docs/zcode-experimental.md`。

### 新增：面板可选下载源

- **设置 → 下载源**（`web/src/pages/SettingsPage.tsx` 的 `DownloadSourceTab`）：预设下拉（跟随部署环境（默认）/
  官方 / npmmirror / 中科大 / 腾讯云 / 华为云）+「自定义…」时才出现的手填 URL，输入即时行内校验（红字 + 保存置灰），
  另有一行显示**当前生效的源与它的来源**（面板设置 / 环境变量 / 默认官方）；保存成功后回读服务端值。
  此前这张卡片是个只写 `localStorage` 的摆设——无 API、无校验，刷新后「当前」还会显示错，卡片自己写着
  「网页版暂未接入服务端下载源设置」。
- **`GET/POST /api/settings/registry`**（`web/http/RegistrySettingsApiServlet`）：`GET` 回
  `preset` / `registry` / `effective` / `source` / `presets[]`；`POST` 收预设 id 或自定义 URL，
  非法输入 400 **且设置保持不变**。另有 `POST /api/settings/registry/test`：拿生效或指定的地址取一次
  `is-number` 的元数据，回 `{ok, millis, status}`（5 秒超时）——「源通但慢得像卡死」正是这个设置要解决的问题，
  光看界面分不出来；卡片上就有一个「测速」按钮，测的是当前选中的那个地址（选着「自定义…」时测输入框里填的，
  否则测那个预设自己的），结果旁会回显服务端实际测的地址。`DshDoctor` 的 Settings 段顺带加一行
  `npm registry: <有效值> (<来源>)`——卡住时不用 `docker exec` 就能判断实际用的哪个源。
- **设置落盘**（`dsh/LauncherSettings` + `setting/SettingsManager`，落 `/data/hdsl/launcher-settings.json`，
  0600 原子写）：新增 `npmRegistryPreset` 与 `npmRegistry` 两项。此前启动器设置没有 REST 通道，前端也没有对应控件；
  这份快照是 `save()` 唯一的写入面、`load()` 唯一的读取面——不登记进来的设置就是「能改、下次启动就丢」，所以一次补齐。
- **防注入校验**（`dsh/NpmRegistry.normalize`，REST 与子进程/文件写入共用）：只允许 `http`/`https`、
  必须能解析出主机名、禁止用户名密码 / 查询串 / 片段，以及空白、控制字符与 `"` `'` `` ` `` `\` `#` `$` `;` `|` `&` `@`，
  长度 ≤ 200，尾斜杠归一化。自定义源是管理员填的，但它要进 argv 与配置文件，写时校验、用时再规范化，两道都要。

### 修复

- **pnpm 系根本不读环境变量，光注入 env 覆盖不到它**：pnpm 11 的 registry 只来自它自己的全局配置
  `~/.config/pnpm/config.yaml`——容器里没有任何 `.npmrc`，`npm_config_*` 对它完全无效
  （实测 `NPM_CONFIG_REGISTRY=… pnpm config get registry` 被忽略）。所以面板在启动时与每次保存后**重写这份文件**
  （`dsh/PnpmConfigFile`）：逐行保留其它设置（`storeDir` 不会丢，本来缺了还会按 `PNPM_STORE_DIR` 补一行）、
  只重写 `registry` 一行，值经 YAML 引号、临时文件 + 原子替换、权限 0600。同时给子进程注入
  `npm_config_registry` / `NPM_CONFIG_REGISTRY`（npm 系读它），并给面板自己起的 pnpm 显式加 `--registry=`
  （dsh 安装、ZCode 构建，日志里看得见）。优先级定为**面板设置 > 环境变量 `NPM_CONFIG_REGISTRY` > 默认官方**，
  预设「跟随部署环境」就是显式回退到后两者。
- **ZCode 构建报 `Could not find any Python installation` / `Unable to detect compiler type`**：
  `Dockerfile` 运行时阶段补上 `python3` / `make` / `g++`——ZCode 依赖里的 `node-pty`、`cpu-features`
  要现场编译。这不是用户环境的问题，镜像里没编译链，面板自己那次也栽在同一处。
- **Electron 的 postinstall 卡死**：构建器按所选源设置 `ELECTRON_MIRROR`（`npmmirror` 预设带
  `https://npmmirror.com/mirrors/electron/`）——它要从 GitHub releases 下约 100 MB 二进制，实测直连 20 秒 0 字节。
- **构建里还有两个环节不在 registry 的覆盖范围内，都不是「换源」能解决的**：
  ① node-gyp 编译原生模块前要从 `nodejs.org` 下一份 10 MB 的 Node 头文件，那个域名跟 npmjs 一样慢
  （实测 104 KB/s，npmmirror 的 Node 镜像 5.7 MB/s），而头文件缓存在 `~/.cache/node-gyp` —— **不在卷里**，
  所以每次重建容器后第一次构建都要重下；现在用镜像源时会同时设 `NODEJS_ORG_MIRROR` / `npm_config_disturl`
  指向镜像（用官方源的机器保持 node-gyp 默认——能连 npmjs.org 的机器也能连 nodejs.org）。
  ② node-gyp 默认**不给 `make` 加 `-j`**，而构建跑在一台 12 核的机器上：现在按 CPU 数设
  `JOBS` / `npm_config_jobs`。顺带，ZCode 构建的子进程现在会继承启动器给**所有**子进程的那套环境
  （代理、registry、上面这些旋钮）——此前它一个都没继承，面板里配的代理对构建完全无效。
- **构建失败的证据被删掉**：`finally` 里改成失败时**保留**源码树与 `build.log`（原来失败也照样删干净），
  排查完不用重下，下一次构建开始时才清。
- **ZCode 干净检出必然失败于 `Missing @zcode/shared dist files`**：上游 `packages/shared` 没有 build 脚本，
  报错让你跑的 `pnpm build` 也不会编译它（`pnpm -r build` 跳过没有该脚本的包），只有项目引用构建（`tsc -b`）
  会产出 `dist/index.js`——上游开发者本地「碰巧」跑过根 `typecheck` 脚本（它列了 `packages/shared`）才有这个文件。
  构建器因此在装完依赖后补一步 `pnpm exec tsc -b packages/shared` 并检查产物存在，再继续打包。
- **删掉了构建器往源码目录写 `supportedArchitectures` 的「平台过滤」**：pnpm 是从 `pnpm-workspace.yaml`
  读这个设置、**不看 `.npmrc`**，所以它从未生效；而一旦生效就会把构建搞坏——组包阶段要为 6 个平台
  （darwin/linux/win × x64/arm64）收集原生资源，缺一个直接失败。上游 `pnpm-workspace.yaml` 已经写着它需要的平台。
- 文档修正：README 原来说「pnpm 11 只从 `.npmrc` 读认证与 registry」，实测 registry 来自 pnpm 自己的
  `config.yaml`，且 `npm_config_*` 对 pnpm 无效——README 与 `docs/zcode-experimental.md` 一并改正，
  并把「面板设置优先、环境变量只是回退」写进环境变量表、常见问题、功能清单与部署说明（`docs/deployment.md`
  新增「运行期下载源（面板设置）」一节，讲清与构建期 `NPM_REGISTRY` 的区别与改写 `config.yaml` 的后果）；
  常见问题里「要用编译器就自己加 `build-essential`」一条随编译链内置一并更新，`docs/ui-spec.md` 把下载源卡片
  从占位补成实际控件（含窄屏行为），差异清单登记为第 4 处。
- **Node 探测失败被当成「没装 Node」**（`dsh/DshNodeRuntime`）：探测内部要 spawn `node --version`，
  负载高时那一次 spawn 失败就报成「Node.js was not found on PATH」——CI 上偶发红、重跑即过的那两条
  插件用例正是同一个原因。现在版本探针有一次重试，成功的探测结果缓存 60 秒（失败不缓存，装好工具链
  立刻可用），顺带每次启动少 spawn 两三个进程。
- **测试不再依赖 stub 的可执行位**：本仓库 `core.fileMode=false`，git 根本不记录文件模式，CI 检出后
  假 pnpm 没有可执行位就卡在「起不来」；测试改为复制到临时目录再 `setExecutable`，索引里也用
  `update-index --chmod=+x` 把 100755 钉住。

### 验证

- 全量 **483 用例 / 0 失败 / 0 错误 / 0 跳过**（含新增的 `NpmRegistryTest`、`PnpmConfigFileTest`、
  `RegistrySettingsApiTest`，以及 `SettingsPersistenceTest` 与 `ZcodeBuilderTest` 的扩展）。
- `npm run typecheck` 与 SPA 构建通过（`vite build` 不做类型检查，单独跑过）。
- 独立端口 + 独立卷的临时容器（HTTP）跑完整验收：
  - **界面**：预设下拉列出 6 项（含服务端下发的实测速度注记「本机实测约 25 MB/s」）；切到「自定义…」后手填框
    才出现；非法地址 `https://a b/` 当场红字「地址里不能有空格」且保存置灰；测速按钮回「约 53 ms」；
    保存后「当前生效」显示 *面板设置* + `https://registry.npmmirror.com`；桌面 1440×900 与手机 390×844
    均无横向溢出（`scrollWidth === innerWidth`）。
  - **接口**：四条注入尝试（换行注入 `https://x/\nstoreDir: /etc`、`javascript:alert(1)`、带空格、
    带用户名密码）全部 **400** 且设置保持不变；自定义地址的尾斜杠被归一化；`POST /test` 回
    `{"ok":true,"status":200,"millis":43}`。
  - **落盘**：`launcher-settings.json` 出现 `npmRegistryPreset` / `npmRegistry`；容器内
    `pnpm config get registry` 回**新值**（pnpm 不读环境变量，所以这只能来自重写的那份配置）、
    `pnpm config get store-dir` 仍是共享仓库（`storeDir` 没被重写丢掉）、配置文件权限 0600；
    `DshDoctor` 报 `npm registry: https://registry.npmmirror.com  (setting)`。
  - **ZCode**：从面板设置取源（`pnpm install --registry=https://registry.npmmirror.com`）跑完整条构建，
    **6 分半**出 `zcode-3.14.3.tar.gz`（带 sha256）并装成 release；`/data/zcode/current` 指向它、
    `bin/zcode.mjs` 可执行、面板 `GET /api/zcode/releases` 列出 `3.14.3  current=true`。
- **ZCode 反代**（本机实跑面板 + stub 发行包）：未登录 `/i/<id>/` → **401**；登录后 → **200**（实例页面）；
  `/i/<id>/some/route?x=1` 路径与查询串原样透传；裸挂载 `/i/<id>` → **301**；未知 id → **404**；
  停止后 → **502**；实例进程 argv 确认为 `--host 127.0.0.1 --no-token`（对外只有面板这一条路）。
- **界面**：桌面 1440×900 与手机 390×844 截图核对 ZCode 分区（发行包识别、构建对话框的状态与实时日志、
  实例卡片操作），移动端 `scrollWidth === innerWidth` 无横向溢出。

## v0.2.0 — 2026-10-05 · models.dev 模型目录

中等规模的功能更新（minor：0.1 → 0.2）。账户页接上 [models.dev](https://models.dev)
目录（226 家供应商 / 约 3500 个模型）：供应商可搜索、可刷新，选定后端点与协议自动
带出；「默认模型」从手写文本框变成可搜索下拉（副标题带上下文与价格），手输一直保留。

### 新增

- **模型目录** `dsh/DshModelCatalog`：读 `https://models.dev/api.json`（`-Dhdsl.modelCatalog`
  可指向别处，测试与内网代理用它），先解析再落盘——读不动的文档不会覆盖可用副本；
  缓存 `/data/hdsl/catalog/models-dev-<sha256(url) 前 16 位>.json`（与插件目录同一套
  命名与目录，尊重自定义缓存目录）。协议只认能映射到 `DshVendor.APIS` 三种的，
  认不出返回 null——界面禁用该卡片，不假装可用。
- **`/api/models/*`**（`ModelsApiServlet`）：
  - `GET /api/models/providers[?refresh=1]`：dsh 自家目录（offered 13 家 + harness 37 家
    + 本机自定义）与 models.dev 合并，**在服务端合并**；TTL 12 小时，`?refresh=1` 绕过；
    整个目录拉不到且磁盘也没有副本时 502，有副本时降级为 `source:"cache"` 并只保留
    5 分钟（过后再试上游）。
  - `GET /api/models/providers/{id}`：单家供应商的模型（窗口 / 输出上限 / 推理档位 /
    能力 / 价格）；dsh 认识而目录没有 → 200 + 空模型数组，界面退回手输，不是 404。
- **`ModelSelect`** 组件：受控的「输入框 + 过滤下拉」，首项固定「留空（由 harness
  决定）」——不填才是安全默认，写错模型名会让 harness 拒绝启动；目录不可用 / 这家
  没有模型时退化成普通输入框，不挡路、不弹错；列表异步到齐时会自己弹出。
- **账户页**：供应商步骤加搜索框 + 刷新按钮 + 计数；卡片网格去掉写死的 `minWidth: 420`
  （360px 屏必然横向溢出）改自适应 + 内部滚动；编辑弹窗同样列出模型。
- 数据文件 `assets/models-dev-aliases.txt`（dsh id ↔ models.dev id 别名表），其余靠
  host 自动命中（fireworks→fireworks-ai、kimi-coding→kimi-code-plan-cn 等）。
- 测试 16 条：`DshModelCatalogTest`（纯解析 + 容错 + 别名/host/id 三条匹配路）、
  `ModelsApiTest`（真服务器 + JDK `HttpServer` 假目录：合并名单、刷新真重抓、失败退回
  磁盘副本、无副本 502、字段裁剪）、`AccountApiTest` 三个新用例。

### 改动

- `POST /api/accounts` 新增可选 `protocol`，只对目录里**新发现**的供应商生效（dsh 自带
  的以 dsh 的为准；已有同 id 的自定义厂商原样返回）——否则 `@ai-sdk/anthropic` 那几家
  会被按 `openai-completions` 写进路由，而错误协议是启动时才失败的静默故障。
- `DshVendor`：新增 `keyVariableOf(id)` 与 `discovered(id, name, baseUrl, api)` 重载；
  密钥环境变量名仍按 harness 的推导，models.dev 的 `env` 只作界面提示（两者确实不同，
  如 ZHIPU_API_KEY vs ZAI_API_KEY）。
- 刻意**不碰启动链路**：`DshAccountRoute` 里的硬编码与 ACP 注入一行未动，本版只动界面
  与它背后的目录。
- 文档：`docs/ui-spec.md` 账户页一节改写 + 新增「模型目录（models.dev）」小节；
  README 补 `/data/hdsl/catalog/` 一行与功能清单；新增
  [`docs/session-handoff-2026-10-05.md`](docs/session-handoff-2026-10-05.md)。

### 验证

- 后端 **443 用例 / 0 失败 / 0 错误 / 0 跳过**（基线 427 + 新增 16）；`npm run typecheck`
  与 SPA 生产构建通过。
- 独立端口 + 独立卷的临时容器真拉 models.dev：名单 **228 家**（dsh 认识 29 + 目录独有
  199，17 家因签名体制类协议禁用）；`?refresh=1` 后 `fetchedAt` 变化；openrouter
  390 个模型；走完「搜 Moonshot → 选供应商（端点自动带出）→ 下拉选 kimi-k2.6 → 保存」
  全流程，落盘与卡片显示均正确；手机 390×844 供应商弹窗与模型下拉无横向溢出。

## v0.1.61 — 2026-10-04 · 关于页改讲本项目

小改动：关于页不再照搬上游（HMCL）的版权与作者，改讲 HDSL-web 自己；
版本号随构建写入「项目」一行——本版起显示 v0.1.61。

### 修复

- **关于页不再冒充上游**：原来照搬桌面版 HDSL 的关于页，显示的全是 HMCL 的数据——
  版权「© 2013-2026 huangyuhui 及贡献者」、作者「bilibili @huanghongxun」、
  开源链接指向 HMCL 仓库，等于把别的项目的数据当成自己的。现在讲本项目：
  项目（`HDSL-web v<服务端版本>`，取自 `GET /api/health`）、说明、仓库链接、
  许可证（GPL-3.0）、上游（HDSL · HMCL，完整声明见仓库 NOTICE）、技术栈；
  顺手修掉上游行重复的 "HDSL" 与窄屏下仓库链接断行。

### 其他

- CI 测试失败时打印完整异常（`exceptionFormat=FULL`）：上次 `PluginApiTest` 偶发失败
  （本地 10/10 通过、重跑即过），日志里只有一行行号、断言消息被吞，无法定位；
  此后失败会带上完整消息。

### 验证

- 全量 **427 用例 / 0 失败 / 0 跳过**；`npm run typecheck` 与 SPA 构建通过。
- 实机截图：本机构建的 v0.1.61 起服务，桌面 1440×900 与手机 390×844 的关于页均显示
  `HDSL-web v0.1.61`，各条目与仓库信息一致。

## v0.1.6 — 2026-10-04 · 手机端适配

面板现在可以拿手机用了：侧栏抽屉、横向标签条、贴底启动栏，主屏一屏启停；
顺带修好深链接白屏，以及 ACP 控制台「对话报缺密钥」。

### 新增

- **手机端适配**（`web/src/styles/mobile.css`，桌面端零变化）：
  - 移动判定 `(max-width: 760px), (max-height: 520px)`——后者覆盖横屏手机；
    触摸优化判定 `(hover: none) and (pointer: coarse)`——与宽度无关，平板同享。
  - 主页侧栏收进抽屉（汉堡唤出、遮罩，点条目 / 遮罩 / 路由变化都收起）；
    内容页子侧栏改为顶部横向可滑标签条；启动面板改为贴底整条操作栏
    （主钮 56px 高、避让安全区，进度浮层改为底部卡片）。
  - 主页补「当前实例」卡片：手机上一屏看到状态并启停 / 打开 dsh。
  - 支持添加到主屏幕：`manifest.json` + apple-mobile-web-app 元信息、`viewport-fit=cover`。
- `useMobileLayout()`（`useSyncExternalStore` 订阅 `matchMedia`），判定条件与 CSS 两处一致。

### 修复

- **深链接白屏**：vite 的 `base` 由相对 `./` 改为 `/`——此前直接打开或刷新 `/instances/xxx`
  时 `./assets/…` 解析成 `/instances/assets/…`，命中 SPA 兜底返回 HTML，模块脚本被浏览器
  拒绝执行，整页空白。
- **控制台对话报缺密钥**：ACP 控制台启动时也把实例指定的账户交接给它——路由写进
  `profiles/acp` 自己的补丁层、密钥放进子进程环境，停止 / 失败 / 退出时收回；与启动路径
  共用同一份账号解析。刻意不碰每个 home 一份的 `settings.yaml` / `.hdsl-injected.json`。
- `StaticHandlerTest` 的资源断言跟随 `base: "/"`（此前写死了 `./assets/`）。

### 验证

- 全量 **427 用例 / 0 失败 / 0 跳过**；`npm run typecheck`（noUnusedLocals）与 SPA 构建通过。
- 4 个视口 11 张截图逐张核对（390×844 / 360×800 / 844×390 / 1440×900），每张断言无横向
  溢出；桌面版式与改造前一致。

## v0.1.5 — 2026-10-03 · 修改用户名

用户系统补上「修改用户名」：改完不用重新登录，本设备的会话直接切到新名字，
其它设备上的会话照旧退出——与改密码同一套「本机保留、其余下线」的语义。

### 新增

- `POST /api/auth/username`：修改当前登录用户的用户名。口令、盐、PBKDF2 迭代次数与创建时间都不动，
  只改 `users.json` 里的名字（仍是原子 0600 写入）；发起改名的这个会话保留并切换到新用户名，
  该用户的其它会话全部吊销。未登录 401，非法名字（空白 / 超长 / 空）400。
- 设置页新增「修改用户名」卡片（在「修改密码」下方）：1–32 位且不含空白字符，
  成功后面板右上角的用户名即时更新，失败弹出原因。
- 回归测试 `usernameChangeRenamesLoginAndRevokesOtherSessions`：覆盖匿名 401、非法名字 400、
  改名后 `whoami` 返回新名、另一个会话被吊销、旧名不再能登录、新名用原密码可以登录、新名字已落盘。

### 验证

- 全量 **422 用例 / 0 失败 / 0 跳过**（较 v0.1.4 新增 1 条）；`npm run typecheck` 与 SPA 构建通过。

## v0.1.4 — 2026-10-03 · 容器发布版

把项目做成**标准容器发布**：镜像在 GitHub 官方 runner 上自动构建并推送到 GitHub Container Registry。
本版与 v0.1.3（暂时稳定版）**代码等价**，差异只在发布设施与文档。

### 新增

- [`.github/workflows/docker.yml`](.github/workflows/docker.yml)：打 `v*` 标签时自动构建并推送
  `ghcr.io/nas200-timmy/hdsl-web`（语义化标签 + `latest`）；手动 dispatch 产出 `edge` 用于验证/尝鲜。
  认证用仓库自带的 `GITHUB_TOKEN`（`packages: write`），任何机器都不需要登录或存放 PAT；
  构建带 GHA 层缓存，后续构建明显加速。版本号透传进 jar，镜像里 `-version` 与镜像标签一致。
- Dockerfile 增加 OCI 标签：`org.opencontainers.image.source`（让 ghcr 上的包自动关联本仓库，
  也是 `GITHUB_TOKEN` 有权推送同名包的前提）、`description`、`licenses`（GPL-3.0-only）。
- README 增加 ghcr 徽章与一行式拉取指引：`docker pull ghcr.io/nas200-timmy/hdsl-web:latest`。

### 验证

- `:edge` 构建通过（Actions run 成功），并以**匿名令牌**校验仓库清单：`GET /v2/nas200-timmy/hdsl-web/manifests/edge` → **HTTP 200**（公开可拉取）。

## v0.1.3 — 2026-10-03 · 暂时稳定版

修好 dsh 界面「历史加载失败、界面一直闪」，并整理一份会话交接文档。**本版为当前暂时稳定版**，日常使用选它。

### 修复

- **反代 WebSocket 中继的 64 KiB 上限**：面板把 `/i/*` 的 WebSocket 升级中继到实例端口，
  用的是 Jetty 默认上限（frame / text / binary 均 65536 字节）。dsh 的一份会话历史、一条
  工具结果、一个粘贴的附件都是一整条 WS 消息，轻易超过 64 KiB——一超，中继这一跳就以
  1009（message too large）失败、随即以 1011 掐断两端，dsh 客户端报
  `api gateway: Remote stream WebSocket closed`（gateway/internal）并反复重连，
  表现为「界面一直闪、历史永远加载不出来」。dsh 自己（node 的 ws）默认允许 100 MiB，
  瓶颈一直在面板这一跳。现在两侧——服务端 upgrade 容器（浏览器→中继）与转发用的
  WebSocketClient（中继→dsh）——的 frame / text / binary 都抬到 32 MiB（覆盖 dsh 实际
  产生的 payload，同时仍给"让面板无限分配内存"的对端一个上限）；中继非正常关闭时写一行
  日志（实例、状态码、原因），此类失败不再只能从浏览器控制台猜。

### 文档

- 新增 [`docs/session-handoff-2026-10-03.md`](docs/session-handoff-2026-10-03.md)：
  当天的会话交接文档——问题、排查结论、五次改动、关键环境事实与复现配方、刻意没做的事、
  当前 git 状态与待办。
- 文档与测试里的示例域名 / 内网地址 / 宿主路径统一为示例值（`dsh.example.com`、
  `192.0.2.x`、`/srv/...`）；`DshReadinessLineTest` 的示例私网 IP 换成 TEST-NET 段。

### 验证

- WS 上限 A/B（假 dsh 单发 1 MiB 帧）：修复前两个方向都 `CLOSE 1011 bytes=0`，
  修复后都 `RECV frame #1 1048576 bytes` 完整送达。
- 全量 **421 用例 / 0 失败 / 0 跳过**。

## v0.1.2 — 2026-10-03

上手配置时暴露的两个问题：账户加不进去，dsh 的工作区落在容器自己的目录里。

### 修复

- **账户添加必然失败**：`GET /api/vendors` 的 `kinds` 按设计报的是「供应商说的协议」
  （`openai-completions` 之类），面板却把它当账户类型随创建请求发了出去，被
  `/api/accounts` 以 `kind must be one of official, third-party, offline` 拒绝——
  从列表里选供应商添加 100% 失败。现在面板不再发 `kind`（类型由后端按供应商推断
  official / third-party），协议只作只读信息显示；后端拒收时点名收到的值。
- **工作区是启动器自己的实例目录**：此前新建实例的「工作区」（会话读写文件、跑命令
  的目录，也是 dsh 进程的工作目录）取的是 `<data>/hdsl/instances` —— 启动器放实例
  清单与安装的地方。现在启动时把 `<home>/workspace`（镜像里 `/home/hdsl/workspace`，
  可用 `HDSL_WORKSPACE` 覆盖）注册成选中目录，新实例默认落在它上面；该目录一直存在，
  挂不挂由部署决定：不挂是容器内目录，挂了就是宿主目录或命名卷。
- **dsh 界面「历史加载失败」并一直闪**：报错是 dsh 客户端的
  `api gateway: Remote stream WebSocket closed（gateway/internal）`。根因在面板的
  WebSocket 中继：它用的是 Jetty 的默认上限——**单帧 / 单消息 64 KiB**，而 dsh 一份
  会话历史、一条工具结果轻易超过；超限时中继把整条连接掐断（1009 → 继电器自己报
  1011），dsh 客户端于是重连、重试，界面就一直闪而历史永远加载不出来。现在两侧
  （服务端 upgrade 容器与转发用的 WebSocketClient）统一提到 32 MiB（dsh 自己的服务端
  上限是 100 MiB），并让中继在非正常关闭时写一行日志，下次同类问题在容器日志里可见。

### 部署

- compose 里两个挂载点写在明面上：`/data`（必须持久化：口令 / 配置 / 证书 / 实例 /
  pnpm store）与 `/home/hdsl/workspace`（可选，工作区；注释给了宿主目录与命名卷两种写法）。
- README「数据与挂载点」补上两个挂载点的分工与工作区说明，环境变量表新增
  `HDSL_WORKSPACE`。

### 工程

- 测试：**421 用例 / 0 失败 / 0 跳过**。新增工作区解析规则用例、「新建实例取选中
  目录当工作区」的接口用例，以及账户契约回归用例（供应商路径不带 `kind` → official；
  协议当 `kind` → 400 且点名）。
- 实机验证：绑定挂载宿主目录后，新建实例记录 `workspace=/home/hdsl/workspace`，
  启动的 dsh 进程 `cwd` 即该目录。
- 中继上限的 A/B 实测（假 dsh 只吐一个大帧）：修复前单个 1 MiB 帧直接断开
  （`1011 upstream connection failed`，客户端收到 0 字节）；修复后两个方向都完整
  收到 `1048576` 字节。

## v0.1.1 — 2026-10-03

修复实机使用中暴露的"实例经常无法启动"系列问题，并把安装编排补成启动器该有的样子。
排查结论：dsh 的版本管理内核（npm 取版本、pnpm 按实例安装、pnpm store 复用）没有坏，
坏的是 Web 层的编排逻辑与前后端状态契约。

### 新增

- **创建即安装 / 启动补装**：`POST /api/instances` 默认 `autoInstall:true` 并返回
  `installTaskId`；对未安装实例点「启动」会先安装再启动（202 `installing`），传
  `autoInstall:false` 可退回旧语义。
- **状态契约补全**：实例状态新增 `NOT_INSTALLED` 与 `INSTALLING`（判定顺序：活进程 >
  停止中 > 安装任务 > 未安装 > 失败 > 已停止；活进程优先，让"装完自动启动"的窗口期
  报 `STARTING` 而不是 `INSTALLING`），安装开始/结束时广播状态事件——此前前端 6 处
  按这两个状态写的引导 UI 全是死代码。
- 新增 [docs/launcher-orchestration.md](docs/launcher-orchestration.md)：诊断过程、
  状态机、契约变化与取舍。

### 修复

- **版本漂移**：详情页版本选择器不随实例重置（路由不重挂组件），会把 A 实例的选择
  提交给 B；改为随实例重置。后端 `/install` 成功后不再无条件把清单改写成"请求的
  版本"，而是**回读磁盘**上的 `package.json`，与请求不符时按真实版本记录并把任务
  判失败（宁如实，不谎报）。
- **安装失败不可见**：pnpm 输出此前只进内存任务表，失败原因随面板重启消失；现在写入
  容器日志与任务记录，并对 `ERR_PNPM_FETCH_404` / `ERR_PNPM_NO_MATCHING_VERSION`
  追加中文提示（点名上游已删除的包，说明重试无用）。
- **容器一直 unhealthy**：HTTPS 下探针访问 `https://127.0.0.1` 触发 Jetty 的 SNI
  主机校验（400 Invalid SNI），服务本身是好的；两个 TLS 连接器关闭该校验（单证书
  面板，校验只会误伤本机探针）。现在 `curl -k https://127.0.0.1:3080/api/health`
  直接 200。
- **npmmirror 尾斜杠导致 corepack 404**：`NPM_REGISTRY` 带尾斜杠时 corepack 拼出
  `mirror//pnpm/latest` → 404，而 compose 注释里推荐的就是带斜杠的写法；入口脚本
  归一化后再写 pnpm 配置与 `COREPACK_NPM_REGISTRY`。
- **中文日志在 `docker logs` 显示 `?????`**：镜像未设 `LANG`，JVM 的
  `stdout.encoding` 退化成 ASCII；镜像加 `LANG=C.UTF-8`。
- 实例运行中禁止切换版本；安装中禁止改名。

### 工程

- 任务 REST 契约补 `instanceId` 字段（WS 事件早就有 `instance` 字段，只有 REST 与
  前端类型缺它，导致前端任务匹配退化成"最近活跃任务"）。
- **构建产物不再入库**：`src/main/resources/web/` 是 `web/dist` 经 `syncWebDist`
  生成的副本，仅因历史原因被跟踪，且会随源码更新而陈旧；改为生成物并加入
  `.gitignore`（Docker 构建、CI、本地构建都会重建它）。
- 测试：**415 用例 / 0 失败 / 0 跳过**（挂 node + pnpm 实跑，含原本被跳过的 21 个）；
  fake-pnpm 支持 `9.9.9-slow`、`9.9.9-mismatch` 两个特殊版本；新增
  `InstallFailureHintTest` 及"创建即安装 / 启动补装 / INSTALLING 可见 / 回读不符
  判失败"等用例。
- 实机端到端验证：创建 → `NOT_INSTALLED`，启动 → 202 `installing` → `INSTALLING` →
  `STARTING` → `RUNNING`；复现上游删包场景（`0.0.1-rc.1`）确认中文提示落到任务错误
  与容器日志。

## v0.1.0 — 2026-10-03

首个版本：把 [HDSL](https://github.com/MCXCC303/HDSL) 桌面版搬进浏览器的单容器实现。

- 登录门 + 首次启动初始化引导（自建用户名/密码），面板内支持修改密码
- 界面按桌面版像素级复刻（Material You 主题、同款图标与动画、亮暗跟随系统）
- 创建实例 → 一键启动 → 面板内进度条与实时日志 → 自动开新标签页
- 同端口、同证书反代 dsh 网页（剥前缀 / Host 透传 / Cookie Path 改写 / WebSocket 中继）
- 完整管理功能：版本 / 实例 / 插件 / 账户 / 整合包 / 会话 / 技能 / 体检 / ACP 控制台
- 声明式 HTTPS：`server.yaml` / 环境变量，首启自签，面板上传证书热切换
- 交付：单可执行 fat jar + Debian 容器镜像（内置 OpenJDK 21、Node 22、pnpm）
