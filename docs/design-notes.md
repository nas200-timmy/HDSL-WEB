# 设计说明

README 只保留「用户需要知道的」。这里记录为什么这样做——改动相关逻辑前先读这一篇，
再动手；用户文档不重复这些实现事实。

## 1. 架构与反向代理

- 面板与 dsh 是两个彼此独立的页面：面板只负责创建、安装、启动、停止，真正的使用在 dsh
  自己的网页里。因此两者共享同一个域名、端口和证书，dsh 页面挂在 `/i/<id>/` 下。
- 面板自己也会被 nginx / Caddy 架在域名或子路径后面。「反代的反代」是支持形态：SPA、登录、
  API、WebSocket、`/i/<id>/`、壁纸资产都有实测覆盖。
- `Host` 必须原样透传：dsh 会校验 `Origin == Host`，改写 Host 会得到 403。
- 子路径挂载（`HDSL_BASE_PATH=/panel`）时，反代剥掉前缀转发；面板在 index.html 注入
  `<base href="/panel/">` 与 `window.__HDSL_BASE__`，SPA 路由、API、WS、`/i/<id>/` 打开地址
  都按挂载点前缀生成。
- WebSocket 升级头（`Upgrade` / `Connection`）两种挂法都必须透传，否则日志流、任务进度和
  品牌实例状态不会刷新；页面其余功能不受影响。
- `/i/<id>` 无尾斜杠时反代 301 到目录形式；dsh 自己的 WebSocket（`/api/remote.mux`）原样转发。

## 2. 品牌区（Kimi Code / OpenCode / Claude Web）

- 实例是主对象，版本是品牌级共享构建：一次构建（尤其是 Claude Web 的源码构建）成本很高，
  同品牌实例共用同一份 `releases/<版本>/`，切换版本不需要重新安装。品牌区因此做成实例列表页
  内的第二层，而不是「装完版本就没下文」的一次性工具。
- 每个实例的插件通道已在界面与后端预留；上游没有统一插件协议，所以现在一律答「暂不支持」。
- 图标取自上游仓库自己发布的标志：dsh 用 DeepSeek 鲸鱼，Kimi Code / OpenCode 取 MIT 仓库
  原文件，ZCode 取许可明确的 CC0 那份。Claude Web 不上图——Anthropic 的商标条款要求事先
  书面许可，只保留文字标签。完整来源与声明见 `NOTICE` 的 TRADEMARKS 一节。
- Claude Web 是首个 git 源品牌：上游不发 npm 包，面板从 GitHub 发行 tag 下载源码 zip。
  不走 git 协议是因为该网络下 git 传输成功率低；`npm ci` 会连同根项目与 ccb 引擎一起装齐。
- 面板替 Claude Web 兜了三个容器环境的坑：
  - 页面用相对路径资源，WS 连接需要注入 shim，否则界面报 "connection closed"；
  - 无桌面环境时「打开文件夹」不可用，改用它的 `workspaces.add` 协议注册工作区；
  - OpenAI 类供应商需要 ccb 内核，安装流程会一并处理。

## 3. 下载源与 pnpm 配置

- 优先级固定为「面板设置 → `NPM_CONFIG_REGISTRY` → 官方默认」。面板里保存立即生效，
  改环境变量需要重启。
- 面板会校验 URL（协议、主机名、非法字符、长度），拒绝时设置不变。
- pnpm 11 不读 `npm_config_*` / `NPM_CONFIG_REGISTRY`，所以设置要落到它真正读取的地方：
  - 重写 pnpm 全局 `config.yaml` 的 `registry` 一行，保留其它设置（`storeDir` 不会丢），
    值按 YAML 规则加引号，临时文件原子替换并尽量收紧权限到 0600；
  - 给子进程同时注入 `npm_config_registry` / `NPM_CONFIG_REGISTRY`（npm 系读它）；
  - 面板自己起的 pnpm 显式加 `--registry=`，日志里能看到实际使用的源；
  - corepack 下载 pnpm 本体时用它自己的 `COREPACK_NPM_REGISTRY`。
- 镜像入口脚本每次启动都会生成这份 `config.yaml`；绕开入口脚本直接 `--entrypoint java`
  时，镜像里预置了只含 `storeDir` 的同名文件作兜底。

## 4. 实例生命周期

- `POST /api/instances` 默认 `autoInstall:true`，创建即安装；未安装的实例点「启动」会先装再启。
  进度与失败原因在面板显示，失败同时写入容器日志。
- 停止会杀掉整棵进程树：端口立即释放，`/i/<id>/` 立即不可用。dsh 的访问 token 每个进程
  随机生成、只存内存、重启即换，不运行就没有可被攻击的东西。
- 实例端口从 3081–4081 的池子里分配并终身绑定，只监听回环，不对外发布。
- 版本安装失败不毁旧版：旧树先挪到 `dsh.previous`，失败或中断时挪回原路径（Windows 的
  junction 是绝对目标，必须回到原路径）。这条对所有平台都重要，不只是 Windows。

## 5. 数据布局与工作区

- 全部状态都放在 `HDSL_DATA` 下，所以「挂一个卷、备份一个目录」就覆盖账号、证书、实例、
  `DSH_HOME`、pnpm store。dsh 实例自己的安装与 home 在 `hdsl/instances/<id>/` 下。
- pnpm store 与实例目录默认在同一个文件系统（都在 `/data` 下），这样 pnpm 能用硬链接复用；
  跨文件系统时只能复制，安装会变慢、占更多空间。
- 工作区是 dsh 会话的「项目根」：读写文件、跑命令、dsh 进程的工作目录都在它下面。它与面板
  放实例的目录不是一回事；容器里默认是 `/home/hdsl/workspace`，已有实例保留创建时记录的值。

## 6. HTTPS

- 配置优先级：环境变量 > `server.yaml` > 内置默认值。`server.yaml` 键都可选；写错或非法值
  会让启动失败并指出行/字段，服务器不会带着半份配置起来。
- `redirect_same_port`（默认开）：TLS 端口同时接受明文连接，只回
  `308 Location: https://<同主机>:<同端口><同路径>`，内容永远不会以明文发出。这样没有域名、
  直接用 IP 访问也不会撞上 `ERR_INVALID_HTTP_RESPONSE`。会话 Cookie 带 `Secure`，明文请求
  不会带上。
- 自签证书的 SAN 覆盖 `public_base_url` 的 host、`localhost`/`127.0.0.1`、本机所有网卡地址
  与主机名，因此 IP 直连不会报域名不匹配。
- 面板上传证书是热切换：HTTPS 用 `SslContextFactory.reload()`；HTTP 在运行时把明文连接器
  换成 TLS 连接器；运行时切换失败则回滚为明文并返回 `restartRequired: true`（选择已写入
  `server.yaml`，重启即生效）。
- 只认 TLS 的端口：把 `redirect_same_port` 设为 `false`；再开一个固定明文端口做跳转：
  `redirect_http: true` + `redirect_port`。

## 7. 安全模型

- 公开端点只有 `POST /api/auth/login`、`GET /api/auth/status`、`POST /api/auth/setup`
  （仅无用户时）与 `GET /api/health`，外加不含数据的 SPA 外壳（登录页必须先能打开）。
  `/api/*`、`/ws`、`/i/*` 一律要求会话。
- 口令用 PBKDF2WithHmacSHA256，per-user 盐，21 万次迭代，参数记录在 `users.json` 内；
  文件原子写入并尽量收紧到 0600。
- 改密码或改用户名后，同用户的其它会话立即失效（发起操作的那个会话保留）。
- dsh 只监听回环：`dsh web` 不接受 `--host 0.0.0.0`，外部能到达的只有面板端口；
  TLS 在边缘终结。
- 面板 Cookie 是 `HttpOnly` + `SameSite=Strict`，HTTPS 下加 `Secure`，`Path=/`；反代把
  dsh 的 `Set-Cookie: … Path=/` 改写成 `Path=/i/<id>/`，多实例的同名 Cookie 不会互相覆盖。
- `auth.disabled: true` 只在绑定回环地址时允许——能免登录，但不可能误配到公网。
- Windows 没有 POSIX 的 0600 语义，凭据文件可能被同机其他账户读到；按单用户机器对待，
  并且不要把数据目录暴露到公网可访问的位置。

## 8. inotify 配额

- dsh 启动要监视自己的 profile 目录。内核配额不足时，node 可能当场退出，也可能永远不打印
  就绪行；两种都不像「配额不足」，所以 FAQ 先让人查 `fs.inotify.max_user_watches`。
- 配额按 uid 计、容器之间不隔离：宿主上同一个 uid 的其它程序（编辑器服务、同步客户端）会
  与容器抢同一份额度。
- 面板「设置 → 环境体检」的 File watchers 一行会直接标出是否不足（`[SHORTAGE]`）；
  调大办法见 `docs/deployment.md` 的 inotify 一节。

## 9. Windows 适配

- Windows 上有两个目录：启动器用户目录 `%APPDATA%\.hdsl`（实例、运行时；可用
  `-Dhdsl.home=` / `HDSL_USER_HOME` 覆盖）与面板数据目录 `HDSL_DATA`（账号、配置、证书、
  pnpm store；默认 `./data`，相对当前工作目录）。两者分开是为了让「从哪双击启动」不影响
  实例位置。
- 实例终端不支持：它开的是 POSIX shell，经 Linux 终端模拟器列表启动，两者在 Windows 上都
  不存在。会话租约的 `flock` 观察也不可用。
- 其余 Windows 适配（运行时布局、`.cmd` 派生、符号链接降级、进程树终止、ZCode 构建自供
  工具链）见 `docs/windows-bare-deploy.md`。

## 10. 构建、兼容底线与发布

- Dockerfile 是三阶段：web（npm 构建 SPA）→ build（Gradle 产 shadow jar）→ runtime
  （Debian + JRE + Node + pnpm）。build 阶段故意不带 npm，SPA 由 web 阶段产出后拷进去，
  所以容器构建不需要本机的 `web/node_modules`。
- 本地 `./gradlew build` / `shadowJar` 会检测 PATH 上的 npm 并执行 `npm run build`：
  第一次构建前需要 `cd web && npm install`，否则因缺少 `node_modules` 失败。
- jar 用 `--release 17` 编译，最低运行时 JDK 17；镜像仍内置 OpenJDK 21。
- SPA 的兼容底线是 Firefox 115 ESR / Chrome 109 / Safari 15.6。CI 的 Compatibility gate
  用 `es-check es2020` 钉 JS 语法、用 `compat-check.mjs` 拦 CSS 黑名单；底线与规则见
  `docs/compatibility.md`。
- 发布：`v*` 标签触发多架构镜像（amd64 + arm64）与 Release；只有最新的 `v*` 标签会移动
  `latest`。细节见 `.github/workflows/docker.yml`。

## 11. ZCode（实验性）

- ZCode 不在稳定性承诺内：实例只绑回环，经 `/i/<id>/` 访问；发行包由面板从上游源码构建，
  能否使用取决于上游。
- 构建不需要 C++ 编译链：`node-pty` 用预编译，`cpu-features` 是 ssh2 的可选依赖。
  面板自供 Node 24+、pnpm 与指向系统 bsdtar 的 `tar` shim；构建前用 `vswhere` 预检
  `cl.exe`，缺了只说明会被跳过，不自动下载编译器。见 `docs/report-2026-10-18-zcode-windows-build.md`。

## 12. 账户与模型目录

- 账户的供应商与模型名来自 models.dev 目录，并与 dsh 自家目录合并；目录拉不到时退回纯手输。
  目录缓存有 TTL，避免每次打开设置都请求外网。
- 「校验密钥」会向供应商请求一次模型列表：状态码是 2xx 且正文像列表才算有效；401/403 只说明
  「这个地址不接受这把钥匙」，不能断言钥匙本身错误（供应商的说法会一并展示）。检查失败仍然
  保存——失败可能是网络或该地址不提供模型列表。
