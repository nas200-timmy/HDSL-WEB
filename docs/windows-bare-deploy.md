# Windows 裸部署：排查与适配方案

> 裸部署 = 不跑 Docker，直接在宿主机 `java -jar hdsl-web.jar`（Windows 上装 JDK 即可）。
> 本文是 2026-10-16 的一次只读排查：把「Windows 上到底坏在哪、坏在代码的哪一层、怎么修」
> 钉到 `file:line`，并给出「一个 jar 万能」的实现路径。
>
> **当前状态：这份文档写下时，代码没有任何一处针对 Windows 的平台分支**（`OperatingSystem.WINDOWS`
> 枚举存在，但除几处 `cmd ver`/`chcp` 探测与启动横幅提示外无人使用）。README / CHANGELOG 把
> Windows 裸部署当作「已加固」场景，而 v0.3.6 的实测记录只覆盖**清空 PATH 的容器内**——用户预期
> 被抬高了，这是本文要修的第一件事。

## 0. 摘要

两个具体疑问的答复（都可由代码直接证实，不需要 Windows 机器）：

| 疑问 | 答复 |
|---|---|
| 传闻「npm 在 Windows 上装了 Linux 版」 | **属实**，但根因不在 npm：面板托管运行时的 `platformTag()` 只特判 macOS，Windows 一律返回 `linux-x64`，`archiveExtension()` 同样只特判 macOS → `.tar.xz`。于是它从 nodejs.org 拉的是 `node-v<ver>-linux-x64.tar.xz`。 |
| 「添加 Node.js 运行环境」在 Windows 有用吗 | **不但没用，还是「可用性假象」**：下载与解包都会成功（解包走 Java 库），Linux 二进制甚至会被登记为「可用」并被自动选中；`bin/npm` 装 pnpm 必然失败。**而且即便用户自己装了官方 Node，面板也探测不到**（`which()` 不认 `.exe`/`.cmd`）——所以「指引用户自行安装」这条路现在也是假的。 |

| | 现状 |
|---|---|
| Windows 上能用的 | 面板本体（Jetty/HTTPS/登录门/静态资源）、纯 Java 的功能（实例台账、日志、反代、证书、导出） |
| Windows 上不能用的 | 托管 Node 运行时一键安装、**探测已有的系统 Node**（连手动装也白装）、dsh 版本安装与插件、三个品牌工具（kimi/opencode/claude-web）的安装与启动、ZCode 面板内构建、实例终端、实例的自定义命令（`/bin/sh`） |

## 1. 排查：证据链

### 1.1 托管 Node 运行时在 Windows 上下载的是 Linux 版

`src/main/java/org/jackhuang/hmcl/dsh/NodeRuntimeManager.java`

```java
// :73-88  platformTag()
if (OperatingSystem.CURRENT_OS == OperatingSystem.MACOS) {
    return switch (Architecture.SYSTEM_ARCH) {
        case X86_64 -> "darwin-x64";
        case ARM64  -> "darwin-arm64";
        ...
    };
}
return switch (Architecture.SYSTEM_ARCH) {   // ← Windows 落到这里
    case X86_64 -> "linux-x64";
    case ARM64  -> "linux-arm64";
    ...
};

// :113-115  archiveExtension()
return OperatingSystem.CURRENT_OS == OperatingSystem.MACOS ? ".tar.gz" : ".tar.xz";

// :122-124  archiveFileName()  → node-v<ver>-linux-x64.tar.xz
```

证据链（每一步都成立，所以**不报错**）：

1. URL 由 `NodeSource.archiveUrl`（`NodeSource.java:87-89`）拼成 `…/v22.19.0/node-v22.19.0-linux-x64.tar.xz`；
2. 该文件在镜像源上**真实存在**，HTTP 200；
3. 解包用 Java 库（`NodeRuntimeManager.java:527-585`：`TarFileTree` + `org.tukaani.xz.XInputStream`，依赖见 `build.gradle.kts`），**不依赖外部 `tar`**，于是解包也成功；
4. `NodeRuntime.isUsable()`（`NodeRuntime.java:62-63`）只查 `bin/node` **是否存在**——Linux 包解出来正好有这个文件，于是这套 ELF 二进制被登记为「已安装、可用」；
5. `DshNodeRuntime.detectManaged()`（`DshNodeRuntime.java:139-144`）随后自动优先选中它；
6. `provisionPnpm()`（`NodeRuntimeManager.java:322-341`）用 `bin/npm` 去 `npm install --global pnpm@11` —— 在 Windows 上这一步才炸，而报错与「平台装错」毫无关系。

顺带：版本候选列表也只会问 Linux 标签（`indexTag()` `:98-104` 只把 darwin 映射成 `osx-*-tar`；`hasPlatform()` `:216-218` 精确匹配），所以 Windows 用户永远看不到 `win-x64` 构建。

代码里**不存在**任何 `npm_config_platform` / `--os` / `--cpu` / `optionalDependencies` 平台解析 /
`supportedArchitectures` 逻辑（`ZcodeBuilder.java:237-242` 的注释还说明曾经那个 `.npmrc` 平台开关
已被删除，因为 pnpm 不吃）。**「装上 Linux 版」的唯一来源就是上面这三行平台分支的缺失。**

### 1.2 「可用性假象」还有第二层：探测不到用户自己装的 Node

`DshNodeRuntime.java:321-334`

```java
public static Optional<Path> which(String name) {
    for (String entry : path.split(java.io.File.pathSeparator)) {
        Path candidate = Path.of(entry).resolve(name);
        if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {   // ← 没有 .exe / .cmd / PATHEXT
            return Optional.of(candidate);
        }
    }
    return Optional.empty();
}
```

Windows 上：`which("node")` 找的是 `…\node`（真实文件是 `node.exe`），`which("npm")` 找的是 `npm`
（真实是 `npm.cmd`）——**两个都返回空**。于是面板判定「本机没有 Node」，把用户推向那个坏掉的托管
运行时；而托管运行时又是 Linux 版。这不是「体验问题」，是**闭环堵死**。

### 1.3 运行时布局：三处硬编码 POSIX 形状

`NodeRuntime.java:38-56`（托管运行时）

```java
public Path node() { return directory.resolve("bin/node"); }
public Path npm()  { return directory.resolve("bin/npm"); }
public Path npx()  { return directory.resolve("bin/npx"); }
```

Windows 官方 zip 解出来的形状是**根目录** `node.exe` / `npm.cmd` / `npx.cmd`，**没有 `bin/`**。
同样的假设还在：`NodeRuntimeManager.adopt()`（`:400-434`，采用用户已有的 Node 安装）、
`DshNodeRuntime.fromManaged()`（`:90-97`，`bin/pnpm` + `Files.isExecutable`）。

### 1.4 子进程派生：`.cmd` 不会被 Windows 自动补上

Java 在 Windows 上通过 `CreateProcess` 派生，只自动补 `.exe`，**不会**补 `.cmd`/`.bat`
（那些必须由 `cmd.exe` 执行）。仓库里所有派生点都是裸 `ProcessBuilder`，**没有任何一处**做
shell 包装，而 util 层其实**已经有一个带 Windows 批处理转义/引号的 `CommandBuilder`**
（`CommandBuilder.java:55, 294, 303-323`）——它当前**没有任何调用方**。

| 派生点 | 代码 | Windows 上的结局 |
|---|---|---|
| 品牌安装（Claude Web 的 npm 步骤） | `BrandInstaller.java:261-263`、`:280-281` | `List.of("npm", "ci", …)` → `IOException: Cannot run program "npm"` |
| 品牌安装（npm 品牌的 pnpm 回退） | `BrandInstaller.java:134-136` | 同上（`pnpm` 是 `pnpm.cmd`） |
| 品牌实例启动 | `BrandRuntime.java:404-408`（kimi）、`:429-433`（opencode）、`:447-456`（claude-web 的 `.bin/tsx`） | 直接执行 `node_modules/.bin/<tool>`。Windows 上那份**无扩展名**的文件是 POSIX sh 脚本，真正的入口是 `<tool>.cmd`/`.ps1` → `WinError 193 不是有效的 Win32 应用程序` |
| 托管运行时装 pnpm | `NodeRuntimeManager.java:323, 334-341` | `bin/npm` 不存在（布局）＋ `.cmd` 不认（派生） |
| 启动横幅探测 | `Main.java:152-159, 185-193` | `pnpm` 误报 `MISSING`（`node` 能侥幸找到，因为补 `.exe`） |
| 实例自定义命令 | `DshCustomCommands.java:74` `List.of("/bin/sh", "-c", command)` | 无 `/bin/sh` → **启动前命令失败会阻止实例启动** |
| 实例终端 | `DshInstanceTerminal.java:70-72`（x-terminal-emulator / gnome-terminal…）、`:210, 251`（`#!/usr/bin/env bash`）、`:271`（`/bin/bash`） | 找不到终端模拟器 → 「No terminal emulator was found」 |

### 1.5 文件系统语义：符号链接 / 硬链接 / 被占用文件 / 长路径

- **符号链接**（Windows 需 `SeCreateSymbolicLinkPrivilege`：管理员或开发者模式）：
  - `NodeRuntimeManager.java:570-585` 解包 Node 包时**无条件**重建 tar 里的软链（`bin/npm → ../lib/node_modules/npm/bin/npm-cli.js`）→ 非开发者模式下抛 `FileSystemException`，被 `install()` 的 catch（`:273-277`）转成一句「Failed to install Node.js …」；
  - `NodeRuntimeManager.java:447-452`（`adopt()` 复制树）同样；
  - `ZcodeBuilder.java:387-389` 的 `releases/current` 链**没有兜底** → 几十分钟的 `pnpm install + build` 跑完之后，最后一步「安装发行包」整体报错；
  - `BrandInstaller.java:459-468` 的 `current` 链**有** try/catch 兜底（`:428-435` 会退回「最新 release」）——这是可照抄的写法。
- **硬链接**：`CacheRepository.java:166`（缓存去重）；pnpm store 在 Docker 里靠同卷硬链接共享（`docker-compose.yml`），Windows 裸部署默认不设 `PNPM_STORE_DIR`，跨卷/FAT/网络盘会失败。
- **被占用文件**：Windows 不允许删除/改名被进程打开的目录，而多处是 `deleteQuietly(target)` 后紧跟 move（`NodeRuntimeManager.java:288-289`）、卸载走 `FileUtils.deleteDirectory`（`:465-477`）——没有重试。
- **`ATOMIC_MOVE` 无降级**：`NodeRuntimeManager.java:289`、`:422`（其余调用点基本都有「失败退回普通 move」的写法，是正面的对照）。
- **长路径**：数据目录在 `%APPDATA%\.hdsl\instances\<id>\dsh\node_modules\…`，pnpm 嵌套极易越过 Windows 默认 260 字符 `MAX_PATH`；代码里没有 `\\?\` 前缀处理。

### 1.6 路径与环境层

- `ZcodeBuilder.java:423`：`settings.buildBin() + ":" + path` —— **硬编码冒号**，Windows 需要 `;`（`File.pathSeparator`）。其余 PATH 拼接点（`DshNodeRuntime:103/228`、`DshVersionManager:846`、`NodeRuntimeManager:339`、`DshInstanceTerminal:206`）都用对了。
- `PnpmConfigFile.java:73-83`：pnpm 全局配置写在 `${XDG_CONFIG_HOME:-~/.config}/pnpm/config.yaml`。**Windows 的 pnpm 读的是 `%LOCALAPPDATA%\pnpm\config\rc`**，不读这个文件。多数调用点显式传了 `--registry=` 尚可，但 `dsh plugin` 内部自己转发的 pnpm（`DshPluginInstaller.java:40` 注释明确说了这条路径）会走错源。
- **编码**：子进程输出有的按 `NATIVE_CHARSET` 解（`DshCommand.java:129`、`ManagedProcess.java:155-161`），有的按 UTF-8 解（`BrandInstaller.java:493`、`BrandRuntime.java:538`、`ZcodeRuntime.java:306`）。Windows 中文代码页（936）下二者混用会出现 `?????` 或乱码。
- 数据目录默认 `./data`（相对当前工作目录，`ServerConfigLoader.java:62`）——Windows 上取决于「从哪双击启动」，属体验问题。

### 1.7 进程生命周期

- 只有 dsh 侧枚举子孙进程（`DshProcess.java:361, 373`）；
- `BrandRuntime.stop()`（`:357-365`）与 `ZcodeRuntime.stop()`（`:256-263`）**只 `destroy()+destroyForcibly()`**。Claude Web 是 `tsx` 派生 node，Windows 上强杀 tsx 会留下真正占着端口的 node——而 Windows 的 `destroy()` 本身就是 `TerminateProcess`，没有「优雅退出」可言。

### 1.8 做对了的地方（改动时别弄坏）

- 解包不依赖外部 `tar`（Java 库）；
- POSIX 权限设置普遍捕获了 `UnsupportedOperationException`（`PnpmConfigFile:168-170`、`setting/SettingsManager.java:115-122`、`UserStore:306-307`、`CertificateManager:652-655`、`PackExports:57-72`、`DshLocalPluginPaths:289-293`），`Unzipper.java:148` 有 `!= WINDOWS` 守卫；
- `ATOMIC_MOVE` 的调用点大多有普通 move 兜底；
- 图标/清单里已经打印了「Windows 上 0600 无 NTFS 语义」的提示（`Main.java:164-172`，与 `README.md:343-346` 一致）。

## 2. 涉及的逻辑层面

适配不是「打几个补丁」，而是**七个层次各自都有平台假设**。按依赖顺序：

| 层 | 负责什么 | 现状 | Windows 症状 | 修法取向 |
|---|---|---|---|---|
| **L1 平台识别** | 回答「我是什么平台/架构」 | `OperatingSystem` 枚举齐备（含 `WINDOWS`），`Architecture` 在 Windows 走 `PROCESSOR_IDENTIFIER`（`Architecture.java:201-208`） | 无 | 已有，直接用；**新增一处「平台能力」查询**（是否 POSIX shell、是否支持符号链接、是否区分 `.cmd`）供其余层使用 |
| **L2 发行物解析** | 平台 → 文件名 / URL / 索引标签 / 解包布局 | 只认 `linux-*` / `darwin-*` | 下载 Linux 包 | 补 `win-x64/win-arm64` + `.zip` + 索引标签 `win-x64-zip`；**纯函数化**便于在 Linux 上测 |
| **L3 运行时布局** | node/npm/npx 在哪 | 硬编码 `bin/*` | 找不到可执行文件 | 抽 `RuntimeLayout`：POSIX(`bin/node`,`bin/npm`) vs Windows(`node.exe`,`npm.cmd` 在根) |
| **L4 子进程派生** | 怎么启动 `.cmd`/脚本/原生二进制 | 全部裸 `ProcessBuilder` | `.cmd` 不可执行 / shim 报 193 | 统一入口：`.cmd|.bat` → `cmd /c`；裸名走 PATHEXT；`.bin/*` 优先 `<tool>.cmd` |
| **L5 文件系统语义** | 符号链接/硬链接/权限/占用/原子替换/长路径 | POSIX 语义 | 建链失败、删不动、超 260 | 能力探测 + 降级策略（junction/copy/逻辑指针）+ 占用重试 + 长路径前缀 |
| **L6 路径与环境** | PATH 拼装、配置目录、编码、HOME | 多数正确，个别硬编码 | PATH 拼坏、pnpm 配置不被读、乱码 | 集中到一处 helper（`pathSeparator`、配置目录按平台、统一 charset） |
| **L7 声明层** | UI/文档/doctor 承诺什么 | 把 Windows 当已加固 | 用户踩空 | 修好前明确标注不支持 + doctor 加 Windows 专检 |

## 3. 问题清单（按严重度）

| 严重度 | 问题 | 位置 | 置信度 |
|---|---|---|---|
| 致命 | 平台标签/扩展名把 Windows 当 Linux | `NodeRuntimeManager.java:73-88, 113-115, 98-104` | 代码证实 |
| 致命 | `which()` 不认 `.exe`/`.cmd`（探不到系统 Node） | `DshNodeRuntime.java:321-334` | 代码证实 |
| 致命 | 裸名派生 `npm`/`pnpm` | `BrandInstaller.java:261-263, 280-281, 134-136` | 代码证实 |
| 致命 | 直接执行 `node_modules/.bin/<tool>` | `BrandRuntime.java:404-408, 429-433, 447-456` | 代码证实 |
| 致命 | 运行时布局硬编码 `bin/*` | `NodeRuntime.java:38-56`、`NodeRuntimeManager.java:400-434`、`DshNodeRuntime.java:90-97` | 代码证实 |
| 致命 | 解包/复制 Node 包无条件建符号链接 | `NodeRuntimeManager.java:570-585, 447-452` | 代码证实（是否失败取决于开发者模式） |
| 致命 | `/bin/sh -c` 硬编码 | `DshCustomCommands.java:74` | 代码证实 |
| 致命 | 实例终端整体 Linux 设计 | `DshInstanceTerminal.java:70-72, 210, 251, 271` | 代码证实 |
| 严重 | ZCode `current` 链失败毁掉整次构建 | `ZcodeBuilder.java:387-389` | 代码证实 |
| 严重 | ZCode PATH 硬编码 `":"` | `ZcodeBuilder.java:423` | 代码证实 |
| 严重 | pnpm 全局配置路径 POSIX | `PnpmConfigFile.java:73-83` | 代码证实（pnpm 在 Windows 的读取路径需实测确认） |
| 严重 | 品牌/ZCode 进程不枚举子孙即强杀 | `BrandRuntime.java:357-365`、`ZcodeRuntime.java:256-263` | 代码证实（实际漏杀量需实测） |
| 严重 | 长路径 / 非 ASCII 路径 | 全局无处理 | **需实测** |
| 严重 | 被占用文件删/改无重试 | `NodeRuntimeManager.java:288-289, 465-477` | **需实测** |
| 严重 | pnpm 硬链接 store 跨卷 | `CacheRepository.java:166` + 部署约定 | **需实测** |
| 严重 | 子进程输出编码混用 | `DshCommand.java:129` vs `BrandInstaller.java:493` 等 | **需实测** |
| 严重 | ZCode 依赖外部 `tar` | `ZcodeBuilder.java:224, 376` | **需实测**（Win10 1803+ 自带 bsdtar，大概率可用） |
| 次要 | `ATOMIC_MOVE` 无降级 | `NodeRuntimeManager.java:289, 422` | 代码证实 |
| 次要 | doctor 打印 inotify/容器文案 | `DshDoctor.java:159-173` | 代码证实 |
| 次要 | 横幅把 `pnpm.cmd` 误报 MISSING | `Main.java:152-159, 185-193` | 代码证实 |
| 次要 | 品牌 `current` 链无意义地告警（有兜底） | `BrandInstaller.java:459-468` | 代码证实 |
| 次要 | 数据目录相对 CWD | `ServerConfigLoader.java:62` | 代码证实 |
| 次要 | 默认主目录 `%APPDATA%\.hdsl`（带前导点） | `OperatingSystem.java:263-265` | 代码证实 |

## 4. 适配方法（具体改法）

### 4.1 L2 发行物解析

| | Linux | macOS | Windows |
|---|---|---|---|
| 平台标签 | `linux-x64` / `linux-arm64` | `darwin-x64` / `darwin-arm64` | **`win-x64` / `win-arm64`** |
| 归档扩展名 | `.tar.xz` | `.tar.gz` | **`.zip`**（Node 从不为 Windows 发 tar.xz；也有 `.7z`，不采用） |
| 索引 `files[]` 标签 | `linux-x64` | `osx-x64-tar` / `osx-arm64-tar` | **`win-x64-zip` / `win-arm64-zip`** |
| 解包后根 | `<dir>/bin/…` | `<dir>/bin/…` | **`<dir>/node.exe`、`npm.cmd`、`npx.cmd`、`node_modules/`** |

要点：
- 三个 switch（`platformTag` / `archiveExtension` / `indexTag`）都补 Windows 分支，**并做成纯函数**
  （输入：OS、架构；输出：标签/扩展名）——这样在 Linux 上就能钉住 Windows 的产物名，不需要 Windows CI；
- Windows 走 `.zip` 时解包器要从「TarFileTree + XZ」换成 zip 分支（项目里 `ZipInputStream` 已在
  `BrandInstaller.extractZip` 用过，可抽公共）；
- 顶层目录名固定为 `node-v<ver>-win-x64`（与 Linux 一致），`install()` 里那条「移动顶层目录」的
  逻辑可复用。

### 4.2 L3 运行时布局抽象

引入一个布局描述，而不是在三处各写一遍：

```
RuntimeLayout                              // 由平台决定
  Path nodeExecutable(Path runtimeDir)     // POSIX: bin/node      | Win: node.exe
  Path npmExecutable(Path runtimeDir)      // POSIX: bin/npm       | Win: npm.cmd
  Path npxExecutable(Path runtimeDir)      // POSIX: bin/npx       | Win: npx.cmd
  boolean isUsable(Path runtimeDir)        // 存在的判据（Win 查 node.exe）
  Path binDirectory(Path runtimeDir)       // 塞进 PATH 的目录：POSIX bin/ | Win 根目录
```

改动点：`NodeRuntime.node()/npm()/npx()/isUsable()`、`NodeRuntimeManager.adopt()` 的校验、
`DshNodeRuntime.fromManaged()` 的 `pnpm` 解析、`NodeRuntimeManager.provisionPnpm()` 的 `npm` 解析。
注意 Windows 上 `pnpm` 由 npm 全局装完落在**运行时根目录**（`pnpm.cmd`），不是 `bin/`。

### 4.3 L4 子进程派生

统一到一个派生 helper（或复活 util 层那个 `CommandBuilder`，它已有 Windows 引号/转义实现）：

- `.cmd` / `.bat` → `cmd.exe /c <path> <args>`（路径含空格要正确引号）；
- **裸名解析**要按 `PATHEXT` 逐个扩展名试（`node` → `node.exe`；`npm` → `npm.cmd`）；
- `node_modules/.bin/<tool>`：Windows 上优先 `<tool>.cmd`（其次 `.ps1`），**绝不要执行无扩展名那份**
  （它是 POSIX sh 脚本）；
- `/bin/sh -c <cmd>` → Windows 上 `cmd /c <cmd>`（`DshCustomCommands`）；
- 实例终端：Windows 上不找 `x-terminal-emulator`；要么走 `wt`/`powershell`，要么明确「本平台暂不支持」。

**这一层是 Windows 三条链路（装 dsh / 装品牌 / ZCode 构建）共同的第一失败点**，优先级最高。

### 4.4 L5 文件系统语义

- **符号链接**：先探测能力（试建一次临时链接）；不支持时按用途降级：
  - 解包 Node 包里的 `bin/npm` 这类软链 → **复制**成真文件（或只对 npm/npx 两个入口做复制）；
  - `releases/current` 这类「指针」→ 写一个文本指针文件 + 读侧解析（比 junction 更省事且不需要权限），
    或直接退回「最新 release」（`BrandInstaller` 已有这个兜底，照抄给 ZCode）；
- **被占用文件**：删除/改名加「重试 + 短暂延迟」，失败信息写清「文件被占用」；
- **`ATOMIC_MOVE`**：补普通 move 兜底（照抄其余调用点的写法）；
- **长路径**：数据目录给出「短路径」建议写在 doctor 里；必要时对超长路径加 `\\?\` 前缀（Java 侧
  需谨慎，仅在确有必要处引入）。

### 4.5 L6 路径与环境

- `ZcodeBuilder.java:423` 的 `":"` → `File.pathSeparator`；
- pnpm 全局配置路径按平台（Windows：`%LOCALAPPDATA%\pnpm\config\rc`），或**不写文件**、把 registry
  一律作为命令行参数传（`--registry=` 已经覆盖多数点，剩下 `dsh plugin` 内部那条只能靠文件）；
- 子进程输出解码统一到一处（按平台选 UTF-8 或 NATIVE_CHARSET），别一半一半；
- 启动横幅的 `toolVersion` 走 4.3 的 helper（否则 Windows 永远误报 `pnpm MISSING`）。

### 4.6 L7 声明层（先做，成本最低）

- 设置页「Node.js」：Windows 上在托管运行时安装按钮旁**明确**写明当前不可用，并给出替代；
- `DshDoctor`：加 Windows 专检（`node`/`npm`/`pnpm` 探测结果、`cmd /c` 可用性、符号链接权限、
  数据目录长度、代码页），把「为什么装不了」直接说出来；
- README/CHANGELOG：把「Windows 裸部署已加固」的口径改成实际覆盖范围（Linux/macOS 容器外裸机），
  或注明「Windows 见 docs/windows-bare-deploy.md 的当前限制」。

## 5. 「一个 jar 万能」怎么实现

用户倾向单 jar，这也是**技术上正确的选择**。三种做法的对比：

| 做法 | 评价 |
|---|---|
| **构建期自动适配**（按构建机平台打包） | ❌ 解决不了问题：Windows 用户拿到的是「同一个 jar」，平台差异是**运行时事实**（同一个 jar 在 Windows 上跑就是 Windows 行为）。构建期唯一真正需要适配的是**打包产物**（启动器脚本等），不是代码路径。 |
| **分支改进**（Windows 版单独维护） | ❌ 两套代码分叉：每个 bug 修两遍，测试两份，且差异全在底层工具（正是最容易踩雷的地方）。历史上这类分叉都退化成一个分支长期腐烂。 |
| **单 jar + 运行时分派**（推荐） | ✅ 平台差异集中在 L1–L6 六层的少数几个纯函数与 helper 里；运行时读 `OperatingSystem.CURRENT_OS` 分派；**测试用注入**（在 Linux CI 上钉住 Windows 的产物名/布局/命令行形状），真机只验证「那些纯函数说不清的事」（权限、占用、长路径、编码、bsdtar）。 |

单 jar 的**天然边界**（哪些做不到，只能声明不支持或降级）：

- 实例终端依赖本机终端模拟器与 bash 脚本 → Windows 上要么换成 `wt`/`powershell`，要么标记不支持；
- POSIX 专用外部命令（`flock`，已有降级）与 `/bin/sh` 脚本内容 → 用户写的自定义命令本身可能就不是跨平台的；
- 原生二进制（各 CLI 的 `node-pty` 等）由 npm 按平台解析，属上游行为——只要 L4 的派生正确，它们会自己选对平台。

## 6. 测试与验收

### 6.1 单元层（在 Linux 上就能钉住，无需 Windows）

1. `platformTag()` / `archiveExtension()` / `indexTag()`：注入 OS+架构，断言 `win-x64` + `.zip` + `win-x64-zip`，以及拼出的文件名 `node-v22.19.0-win-x64.zip`；
2. `RuntimeLayout`：给一个假的 Windows 运行时目录（含 `node.exe`/`npm.cmd`/`npx.cmd`），断言
   `node()/npm()/npx()/isUsable()` 的解析结果；POSIX 目录反例同样钉住；
3. 派生 helper：断言 `.cmd` 被包成 `cmd /c …`、裸名 `npm` 解析成 `npm.cmd`、`.bin/<tool>` 优先选 `.cmd`；
4. PATH 拼装：断言 Windows 下用 `;`（`ZcodeBuilder` 的回归）；
5. pnpm 配置路径：断言 Windows 下指向 `%LOCALAPPDATA%\pnpm\config\rc`。

### 6.2 真机验收清单（Windows）

按顺序做，每步记结果：

| # | 场景 | 期望 |
|---|---|---|
| 1 | 未开开发者模式、未装 Node：设置页点「安装托管运行时」 | 装出 **win-x64** 运行时（`node.exe` 在根），装完版本列表可用；或明确报错说清权限原因 |
| 2 | 开开发者模式重做 1 | 同上（符号链接路径也能过） |
| 3 | 用官方 Windows 安装包装了 Node 22：重启面板 | 横幅显示 `node x.y.z, pnpm x.y.z`（不再误报 MISSING），设置页显示系统运行时 |
| 4 | 装一个 dsh 实例（新版本 + 插件） | 安装成功，插件安装走对 registry |
| 5 | 装 kimi / opencode / claude-web 各一个实例并启动 | 启动成功、`/i/<id>/` 打开可用（claude-web 还要能连上 WS、项目菜单里有工作区） |
| 6 | ZCode 面板内构建一次 | 构建成功；`current` 指针可用 |
| 7 | 数据目录放在含中文/空格的路径；再放一个深目录（路径 > 200 字符） | 上述功能仍可用，或给出可诊断的报错 |
| 8 | 中文代码页（936）下看安装日志 | 中文不乱码 |
| 9 | 实例终端 | 明确可用或明确「本平台不支持」（不能只是报错） |
| 10 | 进程停止：启动品牌实例后点停止，看端口是否释放 | 无残留 node 占用端口 |

### 6.3 仍需实测的点（本文无法在 Linux 上定论）

`bsdtar` 是否可用（ZCode 解包）、pnpm 硬链接 store 跨卷、被占用文件的删改、控制台编码、
长路径阈值、符号链接在目标机器的权限——这些只能按 6.2 的真机清单逐条定性。

## 7. 落地计划

| 阶段 | 内容 | 工作量 | 完成判据 |
|---|---|---|---|
| **0 · 诚实 + 探测/派生** | L7 声明层（UI/doctor/文档说清 Windows 现状）＋ 4.2 布局抽象 ＋ 4.3 派生 helper（探测与派生是「自己装 Node」这条路的前提） | ~1 天 | 真机清单 3/4 通过：官方 Node 能被识别，dsh 实例能装 |
| **1 · 托管运行时可用** | 4.1 发行物解析（win-x64/.zip/索引标签）＋ zip 解包分支 ＋ 4.4 符号链接降级 | ~2–3 天 | 真机清单 1/2/5 通过 |
| **2 · 长尾** | 进程树终止（`taskkill /T /F`）、占用重试、长路径、编码统一、ZCode 的 PATH 与 `current`、终端声明 | ~1–2 天 | 真机清单 6–10 通过 |

阶段 0 之后，「Windows 上自己装官方 Node + 装 dsh 实例」应当**真的能用**——这是当前最刺眼的
前后不一致（文档说可以，代码做不到）。阶段 1 之后托管运行时与品牌工具才谈得上可用。

## 8. 相关代码一览

| 文件 | 角色 |
|---|---|
| `src/main/java/org/jackhuang/hmcl/dsh/NodeRuntimeManager.java` | 托管 Node 运行时的下载/解包/安装/pnpm 供给（**问题最集中**） |
| `src/main/java/org/jackhuang/hmcl/dsh/NodeRuntime.java` | 运行时目录的可执行文件布局 |
| `src/main/java/org/jackhuang/hmcl/dsh/DshNodeRuntime.java` | 系统/托管运行时探测、PATH 注入、`which()` |
| `src/main/java/org/jackhuang/hmcl/dsh/{NodeSource,NodeRelease}.java` | 下载源与版本元数据 |
| `src/main/java/org/jackhuang/hmcl/util/platform/{OperatingSystem,Architecture,CommandBuilder}.java` | 平台识别与（当前无人使用的）Windows 命令行处理 |
| `src/main/java/org/jackhuang/hmcl/web/brand/{BrandInstaller,BrandRuntime}.java` | 品牌工具安装与启动 |
| `src/main/java/org/jackhuang/hmcl/web/zcode/{ZcodeBuilder,ZcodeRuntime}.java` | 面板内构建与启动 |
| `src/main/java/org/jackhuang/hmcl/dsh/{DshCustomCommands,DshInstanceTerminal,DshProcess}.java` | 自定义命令、终端、进程生命周期 |
| `src/main/java/org/jackhuang/hmcl/dsh/PnpmConfigFile.java` | pnpm 全局配置写入位置 |
| `src/main/java/org/jackhuang/hmcl/web/Main.java`、`dsh/DshDoctor.java` | 启动横幅体检、doctor |
