# 第一方面板自带插件

这个目录放的是 **HDSL-web 自己发布**的插件，与面板从 npm 上列出的社区终端界面区分开。

```
plugins/
  README.md            ← 本契约
  shared/              ← 两个终端共用的终端工具箱
    term/  ui/  i18n.js
  dsh-inproc-tui/      ← 进程内终端：叠在 dsh-base 上的 mode bundle（0.2.x）
    package.json       声明 dsh.bundle.patch
    cordis.patch.yml
    src/…
  dsh-acp-tui/         ← ACP 终端：面板直接启动的程序（0.1.2 起）
    package.json       bin/dsh-acp-tui.js
    src/…
```

`shared/` 不是独立包：每个 tarball 里带一份拷贝（`package/shared`），打包时的 staging
步骤把指向仓库布局的 import 说明符（`../../shared/…`）改写成安装后的形态（`../shared/…`）。
所以插件自己的源码保持可读，tarball 又保持自包含。

## 这里的「第一方」指什么

1. **从本仓库构建。** 源码与发布它的代码放在一起，同一许可证（GPL-3.0-or-later），
   与面板一起评审。
2. **打进 jar。** 构建把每个插件打成 `build/first-party-plugins/<包名>-<版本>.tgz`，并以
   `first-party-plugins/` 放进 jar，附一份描述内容的 `index.json`。面板的终端插件安装路由
   把这个 tarball 写出来，用 `dsh plugin --profile <profile> add file:<tarball>` 安装，
   所以**装第一方插件永远不需要 npm registry**。
3. **离线可构建。** 除了它驱动的 harness 之外没有依赖，除了 `node --check` 之外没有构建步骤。
   插件目录里的 `npm run check` 就是全部构建；CI 在 `gradle check` 里跑它。
4. **按契约容忍版本。** 第一方插件要跨 harness 版本可用，所以只通过稳定接口与 harness 说话
   （ACP 终端：ACP stdio 协议；进程内终端：钉死并实测过的 0.2.x 区间），能力缺失时降级，
   而不是崩掉。

## 新增一个插件

1. 建 `plugins/<名字>/`，当普通 npm 包写。`type: "module"`，程序型终端带 `bin`（见下），
   **没有 `dependencies`**。
2. 写一个离线可跑的 `scripts/check.mjs`（语法检查、清单检查），并把 `npm run check` 指向它。
3. 若它是终端界面，在 `DshTuiPlugins.ENTRIES` 里声明：`bundled = true`、它自己的 profile、
   以及它要求的 harness 线。
4. Gradle 会自动接管：`plugins/` 下每个有 `package.json` 的目录都会被打包、进索引、放进 jar
   资源。打包是 Gradle 的 `Tar` 任务，不是 `npm pack`——**构建过程不需要 npm**，没有 Node 的
   机器同样能产出带插件的 jar（只是跳过插件自己的 `check`/`test` 任务）。

## peer 区间：逐个枚举，不要写区间

harness 只发预发布版（`0.2.1-rc.3`），而在 npm 的 semver 规则下 `>=0.2.0-rc.2 <0.3` 这样的
区间**不匹配** `0.2.1-rc.3`——比较符的预发布只承认自己那组 `major.minor.patch` 的预发布。所以
第一方插件逐个列出它真正实测过的 harness 版本，而不是写区间；等 harness 出了稳定版，枚举可以
换成 `>=0.3.0 <0.4`。面板的安装闸门（`DshTuiPlugins.requiresDsh`）是**另一套**、更宽松的判定，
决定按钮是否可点的是它。

## 终端界面的特殊之处

终端界面是 **app 插件**，所以绝不能装进实例的 `web` profile：`dsh-web-app` 与终端 app 各自
解析同一份 argv，装在一起就起不来。因此清单每项都写清它自己的文档要求装进哪个 profile
（`tui`、`dsh-tui`、`pi-tui`、`dsh-inproc-tui`、`dsh-acp-tui`），面板在那个 profile 上启动
终端，而不是实例自己的。

这里的两个终端在**运行时是二选一**：可以都装（ACP 那个是实例 dsh 版本变化时的退路），但只有
第一个匹配的会被启动。清单给两者同一个 `runtimeGroup`（`dsh-terminal`），列表报告哪一行是
`active`，页面把另一个画成「备用」——装它是刻意的选择，不是要拦下来的错误。

支持两种形态，面板按各自的清单启动：

- **程序（`bin`）** —— `dsh-acp-tui`。面板跑
  `node <profile>/node_modules/<package>/<bin>`，让终端本身（而不是包管理器的 shim、也不是
  shell）成为 PTY 的根进程。这个程序随后自己 spawn harness（`dsh --profile acp`），于是面板的
  web-shell 看到的是一个往终端写 ANSI 的进程，与任何终端应用一样。这个形态也是它能跑在 0.1.x
  上的原因：它只需要 ACP 的 profile 模板。

  这里有两个 profile，且不能互换：包自己的 profile（`dsh-acp-tui`）是它的**文件**所在，而
  harness 引导的是 **`acp`** 模板。面板按 `acp` 建启动计划（launcher 把账户的供应商路由与
  default model 写进「这次启动引导的 profile」），并把同一个名字放进
  `HSDL_TUI_ACP_PROFILE`，所以路由就在真正运行的那个 profile 里。过程中不改任何变量的名字：
  第三方账户的密钥始终待在 `HSDL_LAUNCH_API_KEY_<路由名>`（路由自己读这个名字）；
  `DEEPSEEK_API_KEY` 只由 launcher 为 DeepSeek 账户设置，因为它背后的网页搜索入口是
  DeepSeek 的服务。

- **mode bundle（`dsh.bundle`）** —— `dsh-inproc-tui`。面板装上它，再引导它的 profile
  （`dsh --profile dsh-inproc-tui`），与社区 bundle 完全一样。插件跑在 harness 进程里，这是
  它拿到 token 级流式与审批/追问通道的原因——代价是它钉在写它时的那套 0.2.x 接缝上。哪些线
  能跑，看清单里的 `requiresDsh`。

## 检查

```sh
# 插件自己的离线检查
node plugins/dsh-acp-tui/scripts/check.mjs

# 从仓库根：把 tarball 打进 build/first-party-plugins/
./gradlew packageFirstPartyPlugins
```
