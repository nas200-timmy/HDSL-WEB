# 转码模块（设计稿 / 实施计划）

> 状态：已实现并持续补齐；本文件保留设计与取舍，最新落地范围见下节「实现状态」。
>
> 行为参考：FFmpegFreeUI `6.3.0`（上游）；linux-3fui-wedui `19f4a87`。上游后续变更不自动跟随，按需评估。
>
> 定位：把 FFmpegFreeUI（桌面版）与 linux-3fui-wedui（Linux/网页移植版）的转码能力并入 HDSL-web。
> 两个参考仓库**只作为行为参考，不复制代码**；面板独立实现 Java 领域层和 React 前端，不引入 .NET runtime。
>
> 相关约定：功能默认关闭，关闭时与 MC 一样「当它不存在」；UI 只用面板现有 HMCL/Monet 风格；
> 队列在服务端运行，浏览器只是观察窗。

## 实现状态（2026-11-11）

已落地并能通过 `scripts/transcode-smoke.py`：

- 功能门（`feature.json`、关闭即 404、开发者密码启用）、`transcode:` 配置。
- ffmpeg/ffprobe 定位与能力探测、媒体根浏览、ffprobe 媒体信息。
- 预设存储/内置预设/命令生成/预览，前端六页（概览/准备文件/编码队列/预设/工具/设置）。
- 服务端队列、真实 ffmpeg 执行、进度、日志、暂停/恢复、输出 `.part` 原子改名。
- 失败决策三档、QSV→VAAPI→CPU 降级链、失败弹窗与「应用队列」。
- 合并/混流/抽流工具入队；质量评测 PSNR/SSIM/XPSNR/VMAF 与逐帧曲线。
- GPU 监控（NVIDIA/AMD/Intel sysfs，任务管理器式四图，多卡下拉选择）。
- 元数据/章节/附件、滤镜选择器、自定义参数编辑器、`.3fui` 双向尽力映射。
- ffmpeg 静态包 / apt-dnf / 源码编译的在线安装 UI（SHA-256、进度、日志、取消）。
- 失败策略持久化（写回 server.yaml）与超时链路测试。
- 3FUI 防呆：图片编码器跳过音/字幕、QSV `-low_power 1`、VAAPI 自动注入、
  容器兼容提醒、编码器切换清理 preset/profile/tune、1 帧实测、任务 argv 展示。
- Agent scope `transcode` / `transcode-raw`；容器媒体根、`/dev/dri`/NVIDIA 直通参数与冒烟脚本。
- 2026-11-11 补齐：编码器按 `ffmpeg -encoders` 置灰；滤镜顺序改为裁剪/反交错在缩放前；
  质量模式支持 none/VBR/CBR 与 minrate/maxrate/bufsize；字幕支持 convert（mov_text/ASS/SRT/WebVTT）；
  流控制支持保留其它视频/音频/字幕轨并按索引单独编码；后端在保存/预览/提交前校验编码器与质量、
  容器与编码器、MP4 字幕格式、流控制组合；MP4/AVI/TS/WebM 装不下的附件/章节直接跳过。
- 2026-11-21（v0.6.94）：准备文件改成跨页共享工作区（切换页面不丢列表），工具改成
  选择器驱动的批量工作台，编码队列补齐实时遥测与持续日志；空闲队列提交后自动开始，
  实时进度条有兜底。
- 准备文件支持按文件夹递归加入准备列表并批量入队；准备列表用 `/api/transcode/plan` 显示
  服务器上的绝对输出路径（与真正入队共用命名/防撞逻辑），输出目录留空 = 源文件旁边。
- 编码队列的任务管理：逐项「停止 / 移除 / 重置使用预设」与顶部「清除已完成」。**移除只删队列
  记录与任务日志、不删已生成的输出文件**；运行中 / 已暂停 / 停止中的任务必须先「停止」才能移除或
  重置（`可移除` 对齐 3FUI；`可重置` 比 3FUI 宽一档——**未开始的任务也允许**换预设，便于开跑前
  调整，3FUI 只允许已完成 / 已停止 / 错误）。任务保存的是提交时的预设快照，事后改预设不自动生效；
  `reset-preset` 显式替换快照、清空进度/日志/失败决策、按新容器重新规划输出名（目录沿用任务原来的
  输出目录，旧产物不覆盖，新名自动加序号）并回到 `queued`。队列持久化 `queue.json`、输出名预留
  `reservedOutputs`、决策超时与任务日志同步清理。API：`DELETE /queue/jobs/{id}`、
  `POST /queue/clear`、`POST /queue/jobs/{id}/reset-preset`。工具任务（合并/混流/抽流）没有可替换
  的预设，前端按钮置灰、后端拒绝。
- 队列工作台（对齐 3FUI 的交互模型）：控制条独立成一条，放在使用说明下方、宽度与任务列表一致；
  开始 / 暂停 / 恢复 / 停止 / 移除 / 重置预设全部作用于**勾选集合**，另有全选、选中错误、上移 /
  下移与 `Enter`/`Delete`/`空格` 快捷键。行内只保留「日志」，不再被动作按钮挤掉任务名。
  按钮可用性由服务端下发的 `caps`（`start/pause/resume/stop/remove/reset/reorder`）决定，前端不再
  维护状态机副本。顺序是一等公民：`POST /queue/reorder` 要求**全量 ids**，运行中 / 暂停 / 待决定的
  任务位置钉死；重排只影响派发顺序，不改已定名的输出文件。新接口：
  `POST /queue/jobs/{id}/start`（置顶 + 启动队列，仍受并发上限）、`POST /queue/action`（批量动作，
  逐条返回成败）、`POST /queue/sync-preset`（只改未开始的任务，返回 updated / skipped 计数）。
  队列页订阅 `/ws` 的 `transcode:queue` 事件（30 秒兜底轮询）；详情区显示 ETA、每遍命令（`steps`）
  与编码器切换记录（`switchRecords`）。

尚未完成（后续提交）：

- Windows 的 AMD/Intel GPU 进程表与 Intel 整卡利用率（Linux sysfs/NVIDIA 已有；
  Windows 目前只有 nvidia-smi 摘要，性能页的显卡下拉已支持多卡）。
- Windows PDH 每进程 GPU 引擎计数的解析与前端进程表。
- 3FUI 防呆尚未完全搬：容器兼容现在是“继续/取消”确认 + 后端自动跳过不兼容项，还没有
  “改 MKV / 丢弃不兼容项 / 取消”三选；编码器参数已做组合校验，但 profile/tune/pix_fmt 的
  逐项枚举仍未全部覆盖；编码器故障探测仍是失败后决策，不是启动探测/任务前检查/stderr 三层自动；
  Agent 写操作确认卡与工具目录、应急上传三重校验仍未做。
- 全编码源码构建（面板托管依赖栈）：设计稿见文末「附：全编码源码构建」。本版明确不做，默认
  仍用二进制目录；现在的最小源码构建保持“宿主有什么编什么”。

## Context

HDSL-web 目前是「一个 JVM、一个端口」的控制面：实例、品牌、MC、ZCode、终端、Agent 票据都已就位。
转码不是再加一个独立服务，而是成为面板的一等功能：

- 默认关闭，关闭时：无导航、`/api/transcode/*` 404、不探测、不建数据目录。
- 启用后是一个独立二级页面，左侧纵向列表，右侧工作区，操作习惯参考 3FUI，视觉完全使用面板风格。
- 转码队列在服务端运行：关网页、断网、agent 断开都不影响；第一次手动开始后进入运行态，自动补位。
- Agent 通过面板现有 Bearer + scope + 审计模型调用，不引入第二套鉴权。
- ffmpeg 运行时、媒体根、硬件直通、GPU 监控都按面板现有部署方式补齐。

## Decisions locked（讨论结论）

### 1. 功能门与启用

- 参照 `McFeatureFilter` / `McFeatureState` / `web/mcFeature.ts`：
  - `transcode/feature.json` 持久化 `DISABLED/ENABLED` + 同意记录。
  - `TranscodeFeatureFilter` 挂在 `/api/transcode/*` 之前；关闭时只放行 `GET feature`、`GET terms`、`POST enable`、`POST decline`，其余一律 **404**（不是 403）。
  - 关闭时：前端导航不出现、路由回首页、不建 `<data>/transcode`、不启动 executor、不探测 ffmpeg、不注册 WS 主题、agent 工具目录 404。
- 启用必须过一次开发者密码（复用 `McApiServlet#requireElevation` 的模式，调用 `AuthFilter.elevationSatisfied`）：
  - 未设置开发者密码 → 409 `developer_password_not_set`。
  - 非 HTTPS → 403 `tls_required`（与现有提权约束一致）。
  - 启用前展示一次性告知（ffmpeg/CPU/磁盘/媒体根权限），并做能力探测；探测失败给可读原因，不静默启用。
- 禁用：
  - 有任务在跑 → 409 `transcode_busy`，前端提供「停止全部并禁用」。
  - 不做 MC 那种 1 小时锁（没有孤儿服务器风险）。
- 急停联动：`EmergencyStop` 触发时暂停转码队列并终止 ffmpeg 子进程；恢复后队列保持暂停，由用户显式继续。

### 2. 页面信息架构

主侧栏新增「转码」入口，进入独立二级页面，左侧纵向列表固定为六项：

1. **概览**：拖放/添加入口、环境状态、当前任务、最近完成、警告。
2. **准备文件**：媒体根服务端浏览为主、上传为辅；ffprobe、逐文件预设/输出、兼容性检查、入队。
3. **编码队列**：任务表 + 当前任务大进度条 + 底部 ffmpeg 日志抽屉。
4. **预设**：全量参数面板（schema 驱动），左侧预设库、中间分节表单、右侧粘性命令行预览。
5. **工具**：ffprobe 媒体信息、合并、混流、抽流、质量评测。
6. **设置**：媒体、ffmpeg、编码器、队列、失败策略、Agent、诊断。

- 不设转码「性能」页。GPU 监控放面板 **设置 → 通用 → 系统性能**，扩展现有 `SystemMonitorCard` 的 GPU tab。
- 右侧不要全卡片化：
  - 概览、工具、设置用卡片；
  - 准备文件、编码队列用表格/紧凑行；
  - 预设用分节表单 + 粘性预览；
  - 移动端左侧列表变顶部标签条，卡片纵向堆叠。

### 3. 队列运行模型（服务端自治）

- 调度、ffmpeg 进程、日志、状态全部在服务端；WebSocket 只是观察窗，断线不影响任务。
- **第一次显式开始**后队列进入 `RUNNING`；即使队列跑空也保持运行态。
- 运行态下自动补位：任务完成/失败/取消后，空出的并发槽自动取下一个 `QUEUED`；运行态下新加入的任务也自动开始。
- 用户点「暂停/停止」才改变运行态；`PAUSED` 不再派发新任务。
- 暂停记住原因：等决定造成的 `PAUSED` 会在最后一个 `WAITING_DECISION` 任务被移除 / 取消 / 换预设时自动解除；手动「队列暂停」不会被自动解除。
- 关闭网页、agent 断开、浏览器刷新：任务继续；重新打开时拉 `/api/transcode/queue` 恢复状态、进度、日志。
- 面板重启：排队任务持久化；运行中任务标记「被中断」，按设置自动重新排队或等待用户。
- 默认并发 1，可配；硬件编码器单独限 1 槽；队列上限防灌爆。
- 默认输出不覆盖原文件：先写 `name.part-<id>.<ext>`，成功后原子改名；失败删除或保留 `.failed`（可配）。

### 4. 失败策略（三档，类似开发者守卫）

| 档位 | 失败时 | 10 分钟无人处理 | 队列 |
|---|---|---|---|
| **询问**（默认） | 立即弹窗，任务 `WAITING_DECISION` | 当前任务失败 | **暂停队列**，不再派发；运行中的任务跑完 |
| **超时自动降级** | 弹窗 + 倒计时 | 当前任务降级，排队中同硬件编码器一并降级 | 继续 |
| **完全自动化** | 不阻塞，只发通知说明原因 | 不适用，**立即降级** | 继续 |

- 运行中的任务永远不自动重启；只有用户明确选「降级并应用到队列（含运行中）」才停掉重跑，并提示从 0 开始、进度会丢。
- 自动降级范围：当前失败任务 + 队列中尚未开始的、同一硬件编码器任务；不碰软件编码任务和运行中任务。
- 降级链固定 **QSV → VAAPI → CPU**；每任务最多降一次；降级后再失败记失败，不循环。
- 失败判定：
  - stderr 命中硬件特征（`Invalid FrameType`、`MFX session`、`Device lost`、`No device available` 等）立即终止进程；
  - 退出码 0 但 `ffprobe` 校验无视频帧/时长异常也算失败；
  - 环境失败（磁盘满、媒体根不可写、ffmpeg 消失）不降级，直接暂停队列并提示；
  - 输入/预设错误只失败当前任务；用户取消不触发策略。
- 弹窗四个动作：停止 / 仅此任务降级 / 降级并应用到队列 / **完全自动化**（本次运行或保存为默认）。
- 弹窗在 1 秒内出现；多个任务同时失败合并成一个弹窗；页面未打开时服务端照常计时，回来后队列页显示「因无人处理已失败/已降级」。
- Agent 可针对单个 `WAITING_DECISION` 任务做决策（`transcode` scope），但不能修改失败策略档位；策略只允许登录会话修改。

### 5. QSV

- `ffmpeg -encoders` 里有 `h264_qsv` / `hevc_qsv` 就可见、可用，不默认禁用，不因探测失败隐藏。
- 探测结果只作为提示（已验证/未验证/上次失败）；首次选用给一次注意事项，写清部分 Intel 核显会产出空视频（`Invalid FrameType:0`，应用层不可修），建议 VAAPI。
- 真实失败按上面的三档策略处理；降级链优先 QSV → VAAPI（同硬件换 API）→ CPU。
- 本次运行内连续失败后记住「不稳定」，后续默认降级，但保留「仍然尝试 QSV」；重启重置。

### 6. 全量预设参数面板

- 首发就覆盖 3FUI 参数面板的全部功能分组，使用 **schema 驱动**，不在 React 里手写几百个字段：
  - 容器与输出
  - 视频编码（编码器数据库、质量模式、preset/profile/level/tune、像素格式、10-bit、GOP、线程、硬件参数）
  - 画面帧（分辨率、帧率、裁剪、旋转、去隔行）
  - 色彩/HDR（primaries/transfer/matrix/range）
  - 音频（copy/编码、码率/质量、声道、采样率、流映射）
  - 字幕（copy/烧录/编码、字体、默认/强制）
  - 流与元数据（map、默认轨、章节、附件）
  - 滤镜（启用项、顺序、自定义滤镜）
  - 自定义参数（分阶段附加参数，数组而不是 shell 串）
  - 输出命名/输出位置/覆盖策略
- 每个字段定义：id、分组、label、控件类型、默认值、单位、选项、依赖关系、高级标记、校验、hint。
- 复杂编辑器单独组件：流选择器、滤镜选择、分辨率/帧率、元数据/章节、编码器参数。
- 右侧命令行预览由服务端生成，防抖调用；枚举写错报错并列出合法值，不静默回退。
- 内置预设只读，「另存为」编辑；用户预设 CRUD；导入/导出自有 JSON；`.3fui` 兼容作为可选适配器，不反向约束内部 schema。
- 编码器目录运行时探测：`ffmpeg -encoders`、`ffmpeg -h encoder=X`、`-filters`、`-hwaccels`，配少量展示元数据（中文名、分组、推荐质量映射），不抄静态表。

### 7. 媒体根与 ffmpeg

- 容器固定 `/home/hdsl/video`；`HDSL_VIDEO_DIR` 决定宿主挂载（宿主路径或命名卷）。
- 镜像 `Dockerfile` 建 `/home/hdsl/video`；入口脚本单独 `fix_owner /home/hdsl/video 1000:1000 2775`（不能只靠 `/home/hdsl` 递归）。
- compose 增加 `- "${HDSL_VIDEO_DIR:-hdsl-video}:/home/hdsl/video"` 与 `hdsl-video` 卷；`docker-install.sh` 增加 `--video <宿主路径|volume:名称|none>`，默认命名卷，摘要明确提示 NAS 用户应指到真实视频目录。
- Linux 裸部署：`HDSL_MEDIA_ROOT` 环境变量优先；首次启用转码时向导选一次，默认 `<data>/video`；写进 `server.yaml` 的 `transcode.media_root`；UI 显示生效路径、来源、可写性；改根需开发者提权。
- 所有任务输入/输出、上传/下载、浏览 API 限制在媒体根内；`toRealPath()` + `startsWith`，挡软链逃逸；只读媒体根允许读入但必须另选可写输出目录。
- 转码子进程复用 `Privileges.asAgent()`（`setpriv --reuid=1000 --regid=1000 --groups=<gid,…>`，无附加组时 `--clear-groups`），输出文件归 uid 1000。

ffmpeg 获取阶梯：

1. 官方镜像默认带 ffmpeg（Debian `libavcodec-extra`）；启动/启用时探测版本、编码器、硬件能力。
2. Linux 缺失：`apt-get install -y ffmpeg` / `dnf install -y ffmpeg` / `zypper install -y ffmpeg-7`；明确告知只对当前容器有效。
3. 无法安装：给分发行版教程 + 「重新检测」。
4. 用户明确选择且检测到编译链：下载源码编译到 `<data>/tools/ffmpeg/<version>`；显示耗时、磁盘占用、完整日志、可取消；这是最后手段。
5. Windows / macOS：只下载静态二进制到 `<data>/tools/ffmpeg/<version>`，SHA256 校验、zip 解压、`ffmpeg`/`ffprobe` 绝对路径写入配置；不自动升级。
6. 下载源：官方/BtbN、gyan.dev、macOS 对应源、可配置镜像；**自定义 URL 放最后**，需开发者提权 + 必须填 SHA256，拦内网/本机地址；保留上一版可回滚。

### 7.1 安装卡片与默认运行时（2026-11-25 实现）

设置页把“生产一个运行时”和“面板用哪个运行时”拆成两块：

- **安装卡片**：先选类型 `系统 / 下载二进制 / 源码构建`。
  - `系统`：只检测 `PATH` 里的 ffmpeg/ffprobe，显示版本与路径；不下载。找不到时给出包管理器提示或 `transcode.ffmpeg_path`。
  - `二进制`：`vendor`（BtbN / gyan.dev / Jellyfin）→ 当前平台（os/arch/libc，只读）→ 大版本 → 具体 build；详情显示许可、特性、minGlibc、大小、SHA-256、镜像数。Jellyfin 的 LZU 镜像就是 catalog 里的 `mirrors`，属于该 vendor，不是独立来源。
  - `源码构建`：来源（当前为官方 ffmpeg.org）→ 版本 → 编译器（`auto` / `system` / `portable`）→ 构建。`system` 不可用时直接报错，不会偷偷下载；`portable` 未安装时下载固定版本的 Zig/w64devkit。实际编译器写入 runtime.json 的 `toolchain`。
  - 只有最后的“安装/构建”按钮会下载或编译；切换下拉不触网。
- **默认运行时**（卡片下方，类似证书默认项）：`transcode.default_runtime: auto | system | <runtime-id>`，也接受 `HDSL_TRANSCODE_DEFAULT_RUNTIME`。
  - `auto`：托管 `current` 优先，否则 `PATH`；
  - `system`：只走 `PATH`，即使有托管运行时；
  - `<id>`：只用该已安装运行时；不存在时报错，**不静默回退**；
  - `transcode.ffmpeg_path` 仍是运维硬覆盖，优先于这里的默认；
  - 改动需要开发者提权并记审计（`PUT /api/transcode/runtime/default`）。
- `GET /api/transcode/runtime` 增加 `default`、`resolved`（kind/id/路径）、`system`（检测结果）与 `compilers`（系统/便携可用性与详情）；旧字段保留。
- 设置页只保留这一套安装入口；旧的“在线安装”折叠块（静态包 / 自定义 URL / 重复的安装状态）已移除。`mode=custom` 仍留在后端接口，页面不再暴露第二套路径。
- 源码版本下拉写的是**源码包**的许可（LGPL-2.1+），不是产物许可：构建启用 libx264/libx265 后产物按 GPL-3.0 记录，否则是最小编码集（LGPL）。页面从 `/api/transcode/runtime/source` 读缺失的 Debian/Ubuntu 开发包并给出安装命令；要全编码就装包后重建，或直接选 BtbN / gyan.dev 的 GPL 二进制。
- 已安装的二进制/源码版本在下拉和按钮上标记，重复安装按钮禁用；“任务状态”只由安装卡片显示。
- **队列快照带运行时**：`GET /api/transcode/queue` 的 `runtime` 块（`default/kind/id/path/version/available/error`）表示“现在开跑会用哪个 ffmpeg”，只读 agent 票也能看到；每个任务在**第一次启动时**把实际使用的二进制写进 `job.ffmpeg`（`kind/id/path/version/error`），任务结束后仍保留。之后切换默认运行时不会改写已完成任务的记录。队列详情接口 `GET /queue/jobs/{id}` 同样带 `ffmpeg`。

### 8. 硬件直通

- `docker-install.sh` 增加 `--hw auto|vaapi|nvidia|none`（默认 auto）：
  - `/dev/dri` 存在 → `--device /dev/dri`，传入宿主 `renderD128` 的 GID。
  - `nvidia-smi` 存在且 nvidia-container-toolkit 可用 → `--gpus all`；没有 toolkit 只给教程，不硬加参数。
  - compose 基础文件不直接写 `devices`（没设备会启动失败），由安装脚本生成 override 或只留注释示例。
- 入口脚本按 GID 建组并把 `hdsl` 加入：`groupadd -g "$gid" hdsl-render; usermod -aG hdsl-render hdsl`。原因：面板派生 agent 时用显式 `--groups=<gid,…>` 保留 `hdsl` 在 `/etc/group` 中的附加组，单纯 `--group-add` 在 `--groups` 整体替换附加组时会丢。
- 前端明确声明：
  - VAAPI 推荐；
  - QSV 可见、可用，标「实验性」并给出已知问题；
  - NVENC 需要 NVIDIA 驱动 + container toolkit；
  - 探测失败只提示，不隐藏；任务失败按失败策略处理。
- 能力探测：对候选硬件做 1 帧测试编码，成功才点亮；失败回退 CPU，并显示原因。

### 9. GPU 监控

- 放面板 **设置 → 通用 → 系统性能**，扩展 `SystemMonitorCard` 的 GPU tab（`SettingsPage.tsx` 的 `GeneralTab` 已渲染它）。
- 参照 Windows 任务管理器信息层级，不照抄皮肤：
  - 每 GPU 选择器；
  - 四张折线图：GPU 利用率、编码单元、解码单元、显存使用；
  - 当前值 + 4 分钟历史（沿用现有 240 帧环形缓冲）；
  - 可选进程表：进程/任务、PID、3D、编码、解码、专用显存；ffmpeg 行关联到转码任务。
- 数据来源：
  - Windows：PDH `GPU Engine` / `GPU Adapter Memory`，按 `engtype_3D / VideoEncode / VideoDecode` 解析；
  - Linux NVIDIA：`nvidia-smi --query-gpu`（利用率/编码器/解码器/显存/温度/功耗）+ 每进程显存/`pmon`；
  - Linux AMD：`/sys/class/drm/card*/device/gpu_busy_percent`、VRAM、hwmon + `/proc/<pid>/fdinfo` 的 `drm-engine-*`；
  - Linux Intel：频率 + 每进程 `drm-engine-render/video`，文案写「任务占用」，不冒充整卡；
  - macOS：无免 root 稳定接口，显示不可用。
- 采样 2~5 秒缓存，失败软降级；`nvidia-smi` 不 1 秒 spawn 一次；GPU 页在前台时才做进程扫描。
- 监控不跟转码开关绑定：没有转码也能看 GPU。

### 10. Agent 面

- 新增 scope：
  - `transcode`：读队列/预设/日志、入队、开始/暂停/停止/取消、预设 CRUD、对 `WAITING_DECISION` 做决策；
  - `transcode-raw`：原始参数、命令行编辑、浏览/删除媒体文件、覆盖输出。
- `AgentScopes` 默认拒绝：未显式映射的路由返回 null，`AuthFilter` 直接拒绝；`/i/*`、`/ws/*` 对 Agent 关闭的规则不变。
- 暴露工具目录 `GET /api/transcode/agent/tools`（JSON Schema、write 标记、所需 scope）；执行仍走明确 REST 端点，不做通用 dispatcher。
- Agent 不能启用/禁用功能、不能修改失败策略、不能改媒体根/ffmpeg 路径；这些只允许登录会话（必要时开发者提权）。
- 只读的 `transcode` 票在 `GET /api/transcode/queue` 里能看到 `runtime` 块与每个任务的 `ffmpeg`，回答“这个任务用的是哪个 ffmpeg / 什么版本”；安装、切换、设为默认仍是操作员动作。
- 全局并发/队列上限对 Agent 同样生效；关键操作写 `AuditLog`，记录 `agent:<label>`、scope、grant id。
- 可选：`transcode-raw` 和删除类操作走 `TaskService.WAITING_APPROVAL`，由操作员在面板批准。

### 11. 安全与数据

- 数据布局：
  - `/data/transcode/feature.json`
  - `/data/transcode/settings.json`
  - `/data/transcode/presets/`（内置只读资源 + 用户预设）
  - `/data/transcode/queue.json`
  - `/data/transcode/jobs/`、`logs/`
  - `/data/transcode/tools/ffmpeg/<version>/`
- 媒体根独立挂载；备份需要同时包含 `/data` 与媒体根。
- 命令行一律 argv 数组，绝不拼 shell；自定义参数按数组保存和校验。
- 自定义下载 URL 需开发者提权 + SHA256 + 私网/环回拦截。
- 保留 MIT 来源声明与 NOTICE；面板整体 GPL-3.0，合并后仍 GPL-3.0。

## Approach

独立实现，不引入 .NET runtime，不复制参考仓库代码。

后端新增 `org.jackhuang.hmcl.web.transcode` 包，子包建议：

```
web/transcode/
  TranscodeFeatureState.java       // feature.json / 同意记录
  TranscodeFeatureFilter.java      // 关闭即 404
  TranscodeApiServlet.java         // REST
  TranscodeSettings.java           // server.yaml 中的 transcode.*
  preset/                          // schema、字段、内置预设、导入导出
  encoder/                         // ffmpeg 探测、编码器目录、硬件能力
  command/                         // 从预设生成 argv、命令行预览
  queue/                           // 队列状态机、调度、持久化
  job/                             // 进程、进度、日志、暂停/恢复、产物校验
  probe/                           // ffprobe 媒体信息
  tools/                           // 合并/混流/抽流/质量评测
  agent/                           // 工具目录、scope 映射
  runtime/                         // ffmpeg 路径、下载、安装、编译
```

复用现有基础设施：

- `EventBus` → WS 主题 `transcode:queue`、`transcode:job:<id>`；
- `ProcessTree` / `Privileges.asAgent` → 进程树终止与降权；
- `TaskService` → 下载/安装/编译等一次性长任务（转码队列用自己的调度器，不塞进 TaskService）；
- `SystemMetrics` / `SystemSnapshot` → GPU 监控扩展；
- `AuthFilter` / `DeveloperGuardFilter` / `AgentScopes` / `AuditLog` → 鉴权与审计。

前端新增 `web/src/pages/transcode/` 与 `web/src/components/transcode/`：

- `TranscodeLayout`：二级侧栏 + 右侧内容区；
- 六页：`OverviewPage`、`PreparePage`、`QueuePage`、`PresetsPage`、`ToolsPage`、`SettingsPage`；
- 复用 `Dialog`、`ConfirmDialog`、`PopupMenu`、`Snackbar`、`ProgressBar`、`LogView`、`FileDropInput`、`Ripple`、`i18n`；
- 新增字段引擎 `FieldDef` / `SectionDef` 与控件：select、number、slider、switch、multi-select、stream picker、filter picker；
- `SystemMonitorCard` 的 GPU tab 从占位改成真实图表。

## Key changes

### 后端

1. `src/main/java/org/jackhuang/hmcl/web/transcode/`：
   - feature state/filter/servlet、settings、preset schema、encoder catalog、command builder、queue、job、probe、tools、agent catalog、runtime installer。
2. `src/main/java/org/jackhuang/hmcl/web/server/HdslServer.java`：
   - 注册 `/api/transcode/*`、`TranscodeFeatureFilter`（顺序：Auth → DeveloperGuard → TranscodeFeatureFilter）；
   - 可选的转码停止钩子接入 `EmergencyStop` / shutdown。
3. `src/main/java/org/jackhuang/hmcl/web/developer/AgentScopes.java`：
   - 增加 `transcode`、`transcode-raw` 的 required 映射与 forbidden 规则。
4. `src/main/java/org/jackhuang/hmcl/web/config/ServerConfig*.java`：
   - `transcode.enabled`（或 feature.json 为准）、`transcode.media_root`、`transcode.ffmpeg_path`、`transcode.max_concurrent`、`transcode.max_queue`、`transcode.failure_policy`、`transcode.failure_timeout_minutes`、`transcode.auto_resume` 等。
5. `src/main/java/org/jackhuang/hmcl/web/system/`：
   - `GpuReader` 接口与 Linux/Windows 实现；扩展 `SystemSnapshot.Gpu`；`SystemMetrics` 接入；新增按需进程端点。
6. `src/main/java/org/jackhuang/hmcl/web/transcode/runtime/`：
   - ffmpeg 探测、静态包下载（SHA256）、解压、版本目录、PATH 解析、apt/dnf/zypper 安装提示、源码编译脚本调用。
7. `build.gradle.kts`：
   - 新增转码测试任务/资源；如需，加入小型 ffmpeg fixture 生成脚本（测试用，不提交大文件）。

### 前端

1. `web/src/App.tsx`：新增 `/transcode/*` 路由与功能门守卫。
2. `web/src/components/SideBar.tsx`：新增「转码」顶层入口（仅启用时显示）。
3. `web/src/pages/transcode/`：六页 + `TranscodeLayout`。
4. `web/src/components/transcode/`：
   - `FieldRenderer`、`SectionCard`、`PresetList`、`CommandPreview`、`MediaPicker`、`StreamPickerDialog`、`FilterPickerDialog`、`FailureDecisionDialog`、`JobLogDrawer`、`QueueTable`、`GpuCharts`（或复用 SystemMonitorCard）。
5. `web/src/api.ts` / `web/src/types.ts` / `web/src/i18n.ts`：
   - 转码 API、类型、文案。
6. `web/src/components/SystemMonitorCard.tsx`：
   - GPU tab 接入四张图表与可选进程表；`SystemSnapshot.Gpu` 字段扩展。
7. `web/src/styles/`：
   - 只用现有 Monet 变量和组件类；不新增第二套主题、不搬 LakeUI 毛玻璃。

### 容器与安装

1. `Dockerfile`：
   - `mkdir -p /home/hdsl/video`；
   - 入口脚本 `fix_owner /home/hdsl/video`；
   - Debian 增加 VAAPI/QSV 用户态驱动（`libva2`、`libva-drm2`、`intel-media-va-driver`、`mesa-va-drivers`、`libvpl2`、`libmfx-gen1.2`，按底座条件安装）。
2. `docker-compose.yml`：
   - `HDSL_VIDEO_DIR` 挂载 `/home/hdsl/video`；
   - 注释示例：`devices: /dev/dri`、`group_add`、`--gpus`；
   - `hdsl-video` 命名卷。
3. `docker-install.sh`：
   - `--video`、`--hw`、自动探测 `/dev/dri` 与 `nvidia-smi`、GID 传递、摘要输出、dry-run。
4. `docker-compose.override.yml.example`（或安装脚本生成 override）：
   - 硬件直通示例，避免默认 compose 在无设备时启动失败。
5. `README.md` / `docs/deployment.md`：
   - 媒体根、硬件直通、ffmpeg 获取、GPU 监控、QSV 注意事项。
6. `NOTICE`：
   - 记录 FFmpegFreeUI / linux-3fui-wedui 的 MIT 来源与行为参考说明。

### 文档

- `docs/transcode.md`：本文档。
- `docs/architecture/flows/transcode.md`：Mermaid 流程图（启用门、入队、失败决策、降级、队列自治）；已随模块落地补齐。
- `docs/architecture/README.md`、`context.md`、`full.md`、`backend.md`：已补转码模块、`/home/hdsl/video`、`<data>/transcode`、进程与依赖边界。
- `README.md`：功能实现后更新功能速览、快速开始、媒体根、硬件、GPU 章节。

## Verification

### 单元/集成测试

- 预设 schema 序列化/反序列化、字段默认值、枚举严格校验、内置预设只读。
- 命令生成 golden tests：固定预设 + 输入/输出 → 期望 argv；覆盖硬件/软件、滤镜、流映射、命名。
- 进度解析：`-progress pipe:1` 样例、未知时长、暂停/恢复、异常输出。
- 队列状态机：首次开始、自动补位、暂停/继续、并发、上限、重启恢复、中断任务。
- 失败策略：三档、10 分钟超时（测试用短超时）、运行中任务不重启、降级范围、连续失败保护。
- 路径安全：媒体根内/外、`..`、软链逃逸、只读根。
- 功能门：关闭时 `/api/transcode/*` 404、导航不出现、不建目录；启用需开发者密码 + HTTPS。
- Agent scope：`transcode` / `transcode-raw` 允许/拒绝矩阵；默认拒绝；审计字段。

### 真实 ffmpeg 冒烟

- 小样本 H.264 / H.265 / 音频 copy / 仅重封装 / 图片。
- 软件编码必过；硬件按宿主能力，探测失败跳过并断言「不可用 + 原因」。
- 产物校验：ffprobe 有视频帧；失败注入（fake ffmpeg 输出 QSV 特征 stderr / 空视频）触发弹窗与策略。
- 队列自治：启动任务后关闭 WS/模拟浏览器断开，任务继续；重新连接恢复状态。
- 工具：合并/混流/抽流/质量评测各一条，含取消。

### 容器/部署

- `docker build` / `docker compose up`；健康检查通过。
- `/home/hdsl/video` 挂载、权限、输出归 uid 1000。
- `/dev/dri` 挂载时 VAAPI 1 帧测试通过；无设备时不因 `--device` 启动失败。
- NVIDIA 宿主（如有）验证 `nvidia-smi` 与 NVENC；没有则断言「不可用 + 教程」。
- 自定义 URL 下载：SHA256 正确/错误、私网拦截。
- 禁用功能后容器重启：无转码数据目录、无转码探测日志。

### 前端

- `cd web && npm run typecheck && npm run build` 零错误。
- 六页导航、移动端标签条、预设表单、命令预览、队列日志、失败弹窗、GPU 图表。
- 关闭网页后重新打开，队列/进度/日志恢复；断网重连。
- 失败弹窗在 1 秒内出现；10 分钟超时（测试缩短）按档位处理。

### 命令

```bash
./gradlew test
./gradlew build
cd web && npm run typecheck && npm run build
python3 scripts/api-smoke.py
# 新增：转码接口/队列/失败策略冒烟脚本
```

## Assumptions

- 「失败就停」= 当前任务失败 + 队列暂停；运行中的任务继续跑完，不自动杀掉。
- 「完全自动化」= 立即降级，不等 10 分钟；10 分钟只属于「询问 / 超时自动降级」。
- 「只有第一次」= 第一次显式开始后队列保持运行态；之后新任务、完成任务自动补位，直到用户暂停/停止。
- 准备文件以媒体根服务端浏览为主；上传只作为应急，拖放视为上传，不假装能拿到服务器路径。
- Linux 容器固定 `/home/hdsl/video`；裸部署默认 `<data>/video`，由 `HDSL_MEDIA_ROOT` / 首次启用向导 / `server.yaml` 决定。
- 全量预设 = 覆盖 3FUI 参数面板的功能分组；内部 schema 自定，`.3fui` 导入导出为可选适配器，不是首发阻塞项。
- QSV 默认可见可用，探测只提示不隐藏；失败后按策略降级。
- GPU 监控放面板设置 → 通用 → 系统性能；转码页不设性能页。
- Agent 不能启用/禁用功能、不能改失败策略、不能改媒体根/ffmpeg 路径；只能执行被 scope 允许的操作。
- 不引入 .NET runtime；不复制参考仓库代码；实现以 ffmpeg 行为和面板契约为准。
- 功能总开关在面板设置 → 功能；转码设置页只在启用后存在。
- 文档先写、代码后动：实现等维护者明确指令后再开始。

## 附：ffmpeg 运行时管理器（设计稿，2026-11-24）

> 状态：设计稿，未实现。本文把 §7「媒体根与 ffmpeg」里的获取阶梯收成一个可实现的运行时管理器；
> 实现时以代码与本文的契约表为准，两者不一致时先改文档再改代码。
>
> 目标不是把 ffmpeg 塞进 jar（体积与许可证都不允许），而是让面板像管 Node/JDK 一样管它：
> **按宿主能力选对一个制品、原子地装上、能探测、能回滚、能说清缺什么。**
>
> **实现状态（2026-11-24）**：M0/M1/M2 的主体已落地——M0 目录与原子安装/回滚/GC；M1 的 7.1/8.1/auto、glibc 版本与 minGlibc 过滤、目录 v2；M2 的官方源码构建、Linux 系统工具链与 Windows 便携 MinGW 解析。M1 的多 GPU/硬件能力矩阵仍待补；Windows MinGW 真机与完整 GPL 构建尚未验证。

### 1. 为什么 ffmpeg 是唯一“装上了也可能白装”的依赖

| 依赖 | “正确产物”由什么决定 | 编排难度 |
|---|---|---|
| JDK | OS / arch | 低 |
| Node | OS / arch / libc | 中 |
| **ffmpeg** | OS / arch / libc + GPU/驱动 + 许可证 + 编码器/滤镜集合 + 预设需求 | **高** |

JDK 和 Node 是确定性的：同一平台只有少数正确产物。ffmpeg 不是：

- **能力取决于宿主硬件**：同一份二进制在 Intel 核显、NVIDIA、AMD、Apple VideoToolbox 上能用的编码器完全不同；
- **没有单一官方发行**：BtbN / gyan.dev / evermeet.cx / 发行版仓库 / 自编译，功能集、许可证、更新节奏都不同；
- **构建变体多**：GPL / LGPL、static / shared、x264/x265/vpx/aom/svt-av1/libvmaf、VAAPI/QSV/NVENC/AMF/VideoToolbox；
- **源码编译成本高**：`configure` 依赖一堆 `-dev` 包、`pkg-config`、`nasm`，还要处理动态库/rpath；
- **长任务**：下载 + 解包 + 编译可能几分钟到几十分钟，必须可取消、可回滚、不能留下半套。

所以“ffmpeg 已安装”不等于“转码可用”。管理器必须同时回答三个问题：**装的是哪个构建、这个构建有什么、这台机器能不能用。**

### 2. 现状与缺口

现有实现（`web/transcode/runtime/`）：

| 组件 | 已做 |
|---|---|
| `FfmpegInstaller` | `static` / `package` / `compile` 三条路径；SHA-256（含发布方 sidecar）；URL https + 部分私网拦截；进度、日志、取消；装到 `<data>/transcode/tools/ffmpeg` |
| `FfmpegLocator` | `transcode.ffmpeg_path` → `<data>/.../current/bin` → `PATH`；要求 `ffprobe` 成对 |
| `FfmpegProbe` | 解析 `-version` / `-encoders` / `-hwaccels` / `-filters`，带超时与缓存 |
| `HardwareProbe` | 单帧试编码（VAAPI/QSV），失败给 ffmpeg 输出 |
| `TranscodeSettingsPage` | 三条安装路径的 UI、预设来源、进度/日志、重新探测 |
| `scripts/transcode-smoke.py` | 真实 ffmpeg 冒烟 |

缺口按影响排序：

1. **没有制品目录**：UI 里的四个来源写死，且使用 `releases/latest` / `getrelease` 这类会移动的地址；不可复现，也无法按 arch/libc/GPU 自动选。
2. **没有能力元数据**：只探测 encoder 名字，不记录许可证、是否带 NVENC/VMAF、硬件 API 是否真的可用；预设失败只能在任务里发现。
3. **源码编译路径按代码看很可能产出不可运行的二进制**：默认 `./configure` 是 shared，`make install` 把 `libav*.so` 放进 `prefix/lib`，而 `copyToCurrent()` 只复制 `ffmpeg` / `ffprobe` 到 `current/bin`；`FfmpegLocator` 又只认 `current/bin`。除非构建加了 rpath，否则会报 `error while loading shared libraries`。
4. **源码编译的依赖与功能都不完整**：只开 `libx264` / `libx265` / `vaapi` / `libvpl`，没有 NVENC、libvmaf、libvpx/aom/svt-av1；不检查 `pkg-config` / `nasm` / `-dev` 包；干净 Debian 上会停在 `configure`。
5. **解包依赖系统 `tar`**：`.tar.xz` 走 `ProcessBuilder("tar", ...)`，与“只有 JDK”的目标冲突；Node 侧已经用 Java + `kala-compress`/`xz` 解包，ffmpeg 应复用同一套。
6. **安装不是原子的，也没有台账**：`installTo` + `copyToCurrent` 是两次复制，中途失败留下半套；没有 `runtime.json`，没有旧版本回滚，没有 GC；`current` 是复制目录而不是原子指针。
7. **硬件探测覆盖不全**：`HardwareProbe` 硬编码 `/dev/dri/renderD128`，没有多卡枚举，没有 NVENC 单帧测试，没有 Windows AMF / macOS VideoToolbox。
8. **供应链仍是同源信任**：`expectedSha` 从 `url + ".sha256"` / 同目录清单取哈希，镜像投毒可以同时换包与清单；Node 那边已经把官方 SHA-256 钉进 Dockerfile，ffmpeg 也需要内置目录。

### 3. 目标与非目标

**目标**

- 提供 `auto` 安装：按宿主 OS/arch/libc/GPU 与预设需求，从内置目录里选一个已钉版本的 ffmpeg，下载、校验、原子安装、探测、激活。
- 保留 `artifact`（指定目录条目）、`source`（源码编译）、`package`（发行版包管理器）、`custom`（高级自定义 URL）四种显式模式。
- 安装结果有版本目录、`runtime.json`、许可证与能力清单；可回滚、可删除、可 GC。
- 安装/编译/探测全部走 `TaskService`，有进度、日志、取消、审计；失败给出可行动原因。
- 源码构建产出**可复制即运行**的二进制（静态 libav 或带 rpath），不再出现“编译成功、复制后缺库”。

**非目标**

- 不把 ffmpeg 二进制打进 jar 或镜像（体积、许可证、更新节奏都不合适）；镜像里的 `ffmpeg` 包保持现状。
- 不自动安装 GPU 驱动、不替代 `nvidia-container-toolkit`、不承诺每个硬件组合都能用。
- 不自动升级：目录更新由面板发版带入，安装由操作员触发。
- 不覆盖 `transcode.ffmpeg_path` 的优先级：显式路径永远最高，管理器只负责“面板自己的那一份”。
- 第一阶段不做 Windows/macOS 源码构建（Windows 需要 MSVC，macOS 需要 Xcode CLT）；这两个平台先走静态制品。
- 不解决 ffmpeg 版本与所有上游预设的兼容矩阵；只保证“目录条目的声明能力”和“探测结果”一致。

### 4. 组件与数据流

```text
FfmpegCatalog        内置制品清单（jar 资源）+ 可选操作员覆盖
FfmpegCapabilities   宿主探测：os/arch/libc/GPU/设备/驱动
FfmpegSelector       按宿主 + 需求 + 许可证打分，选条目或建议源码构建
FfmpegRuntimeManager 下载/校验/解包/探测/原子激活/回滚/GC/台账
FfmpegBuildKit       可选源码构建：预检、configure/make、静态产物、日志
FfmpegInstaller      现有入口，保留为兼容 facade，内部委托给 RuntimeManager
FfmpegLocator        读取 current 指针或旧目录，保持现有查找顺序
```

数据流：

```text
enable transcode
  → FfmpegCapabilities.detect()（缓存）
  → FfmpegLocator.locate() 找现有 ffmpeg
  → 若不可用/缺能力：FfmpegSelector.recommend()
  → FfmpegRuntimeManager.install(entry | source | package)
  → probe + feature check
  → activate（原子切换 current）
  → TranscodeRuntime.refresh()
  → 预设/工具按 capabilities 置灰或放行
```

### 5. 制品目录 `FfmpegCatalog`

内置资源建议放在 `src/main/resources/transcode/ffmpeg-catalog.json`；操作员可用
`transcode.ffmpeg_catalog` 或 `HDSL_FFMPEG_CATALOG` 指向一个本地 JSON 覆盖/追加（不支持任意远程 URL，
避免把目录变成第二个 SSRF 面）。目录条目示例：

```json
{
  "schema": 1,
  "updatedAt": "2026-11-24",
  "entries": [
    {
      "id": "linux-x64-glibc-gpl-btbn-7.1",
      "os": "linux",
      "arch": "x64",
      "libc": "glibc",
      "ffmpegVersion": "7.1.5",
      "license": "GPL-3.0",
      "features": [
        "libx264", "libx265", "libvpx", "libaom", "libsvtav1",
        "libvmaf", "vaapi", "qsv", "nvenc"
      ],
      "url": "https://.../ffmpeg-7.1.5-linux64-gpl.tar.xz",
      "sha256": "…64 hex…",
      "size": 123456789,
      "archive": "tar.xz",
      "notes": "静态构建；NVENC 需宿主驱动，探测通过才点亮"
    }
  ]
}
```

约定：

- **`features` 是构建声明，不是探测结果**。安装后必须用 `FfmpegProbe` / `HardwareProbe` 校验；声明有、探测没有的项在 UI 标“声明但不可用”。
- **`sha256` 必须来自目录，不随下载源走**。镜像只改 `url`，不改哈希；自定义 URL 才允许“操作员显式填哈希”。
- **禁止 `latest`**。目录条目是某个确定版本；升级目录 = 发版，不是运行时解析“最新”。
- **许可证是字段**。GPL 构建与 LGPL 构建在 UI 上分开；默认推荐能覆盖预设的 GPL 构建，并显示许可证说明。
- 第一阶段只收录**已经人工验证过**的条目；没有验证过的平台宁可不列，也不要“看起来能装”。arm64 / musl / Windows arm64 在验证前不承诺。

### 6. 宿主能力 `FfmpegCapabilities`

探测结果缓存（建议 5 分钟或直到安装/驱动变化），包含：

```json
{
  "os": "linux",
  "arch": "x64",
  "libc": "glibc",
  "gpus": [
    {"vendor": "intel", "devices": ["/dev/dri/renderD128"], "apis": ["vaapi", "qsv"]},
    {"vendor": "nvidia", "devices": ["GPU-…"], "apis": ["nvenc", "nvdec"]}
  ],
  "hardwareEncoders": ["h264_vaapi", "hevc_vaapi", "h264_nvenc"],
  "checkedAt": "2026-11-24T…"
}
```

采集方式：

| 项 | Linux | Windows | macOS |
|---|---|---|---|
| OS/arch | `Platform.SYSTEM_PLATFORM` | 同左 | 同左 |
| libc | `ldd --version` / `/lib/ld-musl-*` | 不适用 | 不适用 |
| GPU/设备 | `/sys/class/drm/card*/device`、`/dev/dri/renderD*`、`nvidia-smi -L` | `Win32_VideoController`（PowerShell/WMI） | `system_profiler SPDisplaysDataType` |
| 硬件 API | `vainfo`（可选）、`nvidia-smi`、ffmpeg `-hwaccels` | `-hwaccels` + 适配器厂商 | `-hwaccels`（videotoolbox） |
| 单帧验证 | 现有 `HardwareProbe` 扩展多设备 | 扩展 AMF / NVENC | 扩展 VideoToolbox |

`HardwareProbe` 的改造点：

- `vaapi_device` 不再硬编码 `renderD128`，由 capabilities 传入第一个可用设备；
- 增加 `h264_nvenc` / `hevc_nvenc` / `h264_videotoolbox` / `h264_amf` 的单帧测试；
- 测试结果按 `(encoder, device)` 缓存；失败保留 ffmpeg 输出尾部，UI 展示原因；
- 只做“能不能出 1 帧”，不做性能承诺；性能仍归队列遥测。

### 7. 选择 `FfmpegSelector`

输入：宿主能力、需求特性集（来自预设/工具，例如 `libx264`、`libvmaf`、`nvenc`）、许可证偏好、
是否允许源码构建、网络是否可用。

```text
候选 = 目录中 os/arch/libc 匹配的条目
评分 = 4 × 硬件命中 + 3 × 需求特性覆盖 + 1 × 许可证偏好 - 0.001 × 体积
排序 = 评分降序，同分取 ffmpegVersion 新的
对每个候选：
  安装 → probe → 检查需求特性
  通过 → 激活；失败 → 记录原因，试下一个
无候选且允许源码构建 → FfmpegBuildKit
无候选且允许包管理器 → package（仅容器/root）
否则 → 明确报错：缺哪个平台/哪个特性/哪条原因
```

原则：

- **不静默降级**：缺少预设必需的编码器/滤镜时，安装可以成功，但激活前要明确说“这个构建缺 X”，让用户选择换构建或接受功能受限。
- **不猜硬件**：没有 `nvenc` 声明或驱动探测失败，就不推荐 NVENC 构建；CPU 构建永远是兜底。
- **可解释**：`GET /runtime` 返回 `recommended` 与 `why`（命中了哪些硬件/特性、排除了哪些候选及原因）。

### 8. 安装 `FfmpegRuntimeManager`

安装流程固定为：

```text
预检（磁盘、权限、特性、并发）
→ 下载（流式、进度、超时、代理、重定向校验）
→ 校验 SHA-256（目录值，失败即删）
→ 解包（Java：zip / tar.gz / tar.xz；拒绝路径穿越与越界软链）
→ 暂存目录 <version>.installing
→ 运行 ffmpeg -version / ffprobe -version、-encoders、-hwaccels
→ 检查动态库（ldd / otool -L / DLL 依赖）或确认静态
→ 写入 runtime.json
→ 原子激活 current
→ refresh probe
```

**目录布局**

```text
<data>/transcode/tools/ffmpeg/
  catalog.json                 # 可选的本地覆盖/缓存（内置目录仍是权威）
  <version>/                   # 不可变安装目录
    bin/ffmpeg
    bin/ffprobe
    lib/…                      # 仅 shared 构建；静态构建可无
    runtime.json
  current -> <version>         # POSIX 符号链接；Windows 用 junction / 指针文件
  .download-<jobId>            # 临时文件，失败清理
  .extract-<jobId>             # 临时目录，失败清理
```

**`runtime.json`**

```json
{
  "schema": 1,
  "version": "7.1.5",
  "catalogId": "linux-x64-glibc-gpl-btbn-7.1",
  "source": "catalog",
  "license": "GPL-3.0",
  "sha256": "…",
  "installedAt": "2026-11-24T…",
  "archive": "tar.xz",
  "featuresDeclared": ["libx264", "nvenc"],
  "featuresProbed": ["libx264"],
  "probe": {"version": "ffmpeg version 7.1.5", "encoders": [], "hwaccels": []},
  "builtFrom": null,
  "notes": ""
}
```

**原子激活**

- POSIX：`current` 是指向版本目录的符号链接；`Files.move(tmpLink, current, ATOMIC_MOVE)` 完成切换。
- Windows：优先 junction（`mklink /J`）；失败则退回“指针文件 + 复制”并明确标注非原子；`FfmpegLocator` 两种都认。
- 激活失败：保留旧 `current`，新版本目录标 `failed`，不回滚已下载文件（留给重试）。
- 回滚：`POST /runtime/select {version}` 或 `POST /runtime/rollback` 切回上一个可用版本；不重新下载。

**GC / 保留**

- 默认保留 `current` + 最近 2 个可用版本；`DELETE /runtime/{version}` 手工删除。
- 删除前检查没有队列任务/预设绑定；被引用的版本拒绝删除。
- 旧布局（`current` 是真实目录）在首次对账时登记为 `legacy`，不移动文件；新安装才使用版本目录 + 指针。

**并发**

- 同一时间只允许一个 ffmpeg 安装/编译任务；第二个请求返回 `409 ffmpeg_busy` 并给出当前 taskId。
- 安装期间不影响已激活版本；`current` 只在最后一步切换。

### 9. 源码构建 `FfmpegBuildKit`

第一阶段只支持 **Linux**，并且只在操作员显式选择 `source` 或 `auto` 没有可用制品时进入。

**预检**

- 工具链：`cc` / `c++` / `make` / `pkg-config` / `nasm`（或 `yasm`）；
- 开发库（按发行版包名）：`libx264-dev`、`libx265-dev`、`libvpx-dev`、`libaom-dev`、`libsvtav1-dev`、
  `libvmaf-dev`、`libva-dev`、`libvpl-dev`；NVENC 需要 `nv-codec-headers`；
- 磁盘：源码 + 构建目录 + 安装目录 + 余量（建议至少 3 GB）；
- 权限：构建进程以 agent uid 运行；系统包安装需要 root/容器，面板不自动 `sudo`。

缺失时**在 configure 之前**失败，并列出精确包名；不要等 `configure` 报一行 `ERROR: libx264 not found`。

**configure（建议基线）**

```sh
./configure \
  --prefix=<version-dir> \
  --disable-doc --disable-debug \
  --enable-gpl --enable-libx264 --enable-libx265 \
  --enable-libvpx --enable-libaom --enable-libsvtav1 \
  --enable-libvmaf \
  --enable-vaapi --enable-libvpl \
  --enable-nvenc --enable-nvdec \
  --disable-shared --enable-static
```

- `--disable-shared --enable-static` 是为了让 `bin/ffmpeg` 复制到 `current/bin` 后仍可运行；如果坚持 shared，
  必须给二进制加 rpath（`-Wl,-rpath,$ORIGIN/../lib`）或让 `current` 指向保留 `lib/` 的整个目录，二者选一。
- `--enable-nvenc` / `--enable-libvmaf` / `--enable-libvpl` 视预检结果裁剪；缺依赖就不要开，并在 `runtime.json` 的
  `featuresDeclared` 里如实记录。
- 构建产物必须通过 `ffmpeg -version`、`ffprobe -version`、`-encoders`、`-hwaccels` 和一次 lavfi 单帧编码；
  不通过就判失败，不激活。

**执行**

- `make -jN`，`N = min(核数, 4)` 起步，可配；`nice`/`ionice` 降低对面板的影响；
- 分阶段进度：下载 → 解包 → configure → make → install → probe；日志落 `transcode/logs/build-<id>.log`；
- 取消：杀整棵构建进程树，保留日志；半成品目录清理；
- 超时：下载 10 分钟、构建 90 分钟（可配）；超时判失败，不激活。

**Windows / macOS**

- Windows 官方 Node/ffmpeg 源码构建需要 MSVC；第一阶段不支持，`source` 直接返回
  `409 ffmpeg_source_unsupported` 并说明改用静态制品。
- macOS 需要 Xcode CLT；第一阶段也不做，先用静态制品。

### 10. 包管理器路径

保留 `package` 作为便捷路径，但明确边界：

- 仅 Linux、仅 root/容器；`apt-get update` 后再 `install -y --no-install-recommends ffmpeg`；
  `DEBIAN_FRONTEND=noninteractive`；`dnf` / `zypper` 同理。
- 安装后必须 `refresh` 并验证 `ffmpeg`/`ffprobe` 成对；失败给出包管理器输出尾部。
- 记录 `source: "package"`，`runtime.json` 标注“系统路径、容器重建后需重装”。
- 不假装可回滚：包管理器装的是系统路径，管理器只能切回之前的面板托管版本；UI 要写明。
- 没有包管理器或非 root：直接 `409 ffmpeg_package_unsupported`，给静态/源码路径。

### 11. API

| 方法 | 路径 | 作用 | 门槛 |
|---|---|---|---|
| GET | `/api/transcode/runtime` | 当前版本、已安装列表、目录候选、宿主能力、推荐与原因、缺失特性 | 功能门 + 会话 |
| GET | `/api/transcode/runtime/catalog` | 目录条目（可按 os/arch 过滤） | 同上 |
| POST | `/api/transcode/runtime/install` | `{mode, id?, features?, license?, url?, sha256?, version?}` → `{taskId}` | 开发者提权 |
| GET | `/api/transcode/runtime/jobs/{id}` | 安装/构建任务状态、进度、日志 | 会话 |
| POST | `/api/transcode/runtime/jobs/{id}/cancel` | 取消 | 开发者提权 |
| POST | `/api/transcode/runtime/select` | `{version}` 切换已安装版本 | 开发者提权 |
| POST | `/api/transcode/runtime/rollback` | 切回上一个可用版本 | 开发者提权 |
| DELETE | `/api/transcode/runtime/{version}` | 删除未使用版本 | 开发者提权 |
| POST | `/api/transcode/capabilities/probe` | 现有单帧探测，扩展 `{encoder, device?}` | 会话 |

`mode`：

- `auto`：选择器；不需要 URL；
- `artifact`：`id` 指定目录条目；
- `source`：源码构建；
- `package`：包管理器；
- `custom`：高级；必须 `url` + `sha256`，https，私网/环回/链路本地/多播地址拒绝，重定向逐跳校验；不提供“自动读取 sidecar 哈希”作为唯一信任。

错误码（新增）：

| code | 含义 |
|---|---|
| `ffmpeg_not_found` | 当前没有任何可用 ffmpeg |
| `ffmpeg_no_candidate` | 目录里没有匹配宿主/特性的条目 |
| `ffmpeg_feature_missing` | 选中构建缺少必需特性 |
| `ffmpeg_checksum_mismatch` | 下载内容与目录哈希不符 |
| `ffmpeg_unsupported_platform` | 该平台没有可用制品/不支持源码构建 |
| `ffmpeg_build_toolchain_missing` | 源码构建缺工具链/开发包 |
| `ffmpeg_disk_full` | 空间不足 |
| `ffmpeg_busy` | 已有安装/构建任务 |
| `ffmpeg_license_mismatch` | 许可证不满足操作员选择 |

Agent：`transcode` scope 只读 runtime 状态与能力；安装/切换/删除/源码构建一律要求开发者提权，
不向 agent 票开放（编译和下载都是代码执行面）。`transcode-raw` 也不放行安装。

### 12. UI

设置 → 转码 → **运行时**（新增一块，放在现有「ffmpeg」卡片里或紧接着）：

1. **当前运行时**：版本、来源（catalog/package/legacy/custom）、许可证、路径、健康状态、
   `featuresDeclared` vs `featuresProbed` 的差异（声明但不可用要高亮）。
2. **推荐安装**：选择器给出的 `recommended` 与 `why`；按钮「安装推荐版本」；旁边「选择其他版本」打开目录列表。
3. **宿主能力**：OS/arch/libc、GPU 列表、VAAPI/QSV/NVENC/VideoToolbox/AMF 徽章；每项可点「单帧测试」。
4. **缺失特性**：预设/工具需要但当前构建没有的编码器/滤镜，逐条列出并给「换构建 / 源码构建」入口。
5. **安装/构建**：模式选择（自动 / 指定目录 / 源码 / 包管理器 / 自定义）、进度、阶段、日志抽屉、取消。
6. **版本管理**：已安装列表、激活、回滚、删除（被引用时禁用并说明）。
7. **许可证与来源**：GPL/LGPL、SHA-256、下载地址、安装时间；自定义 URL 收起在「高级」里。

概览页：ffmpeg 不可用时给出「去安装」；可用但缺预设特性时给黄色警告，不要等任务失败。

预设页：编码器/滤镜按 `featuresProbed` 置灰；置灰原因写“当前构建缺 X”，并提供跳到运行时的链接。

### 13. 安全与供应链

- **目录哈希是权威**：内置目录里的 `sha256` 随面板发版，镜像只改 `url`；下载后不匹配立即删除并失败。
- **自定义 URL**：仅 https；解析 DNS 后拒绝私网/环回/链路本地/多播/IPv6 本地地址；逐跳校验重定向；
  必须显式 SHA-256；要求开发者提权；审计 `transcode.runtime.install`（含 URL、哈希、操作者、来源地址）。
- **代理**：走面板的出站代理设置；没有配置就直连；不要在安装器里另造一套代理逻辑。
- **源码构建**：argv 数组，不经 shell；以 agent uid 运行；`nice`；超时；日志；不接受任意 configure 参数
  （只接受面板生成的基线 + 有限的开关）。
- **许可证**：GPL/LGPL 在 UI 与 `runtime.json` 都记录；不把 ffmpeg 二进制打进 jar/镜像；运行时下载；
  `NOTICE` 保持现有说明。
- **审计**：install / select / rollback / delete / probe / build 全部进 `audit-*.jsonl`，agent 调用记 `agent:<label>`。

### 14. 迁移与兼容

- `transcode.ffmpeg_path` / `HDSL_FFMPEG_PATH` 仍然最高优先级；设置后 UI 显示 `source: external`，
  管理器不覆盖它，只提示“当前使用外部路径，安装不会生效直到清空”。
- 旧的 `<data>/transcode/tools/ffmpeg/current` 若是真实目录：首次对账登记为 `legacy`，写 `runtime.json`
  （`source: "legacy"`, `verified: false`），不移动文件；新安装使用版本目录 + `current` 指针。
- `FfmpegInstaller` 的 `start(kind,url,sha256,version)` 保留，内部映射：
  `static` → `artifact/custom`，`compile` → `source`，`package` → `package`；旧 API 不破坏现有 UI/测试。
- `FfmpegLocator` 保持现有顺序；新增“读取 `current` 指针”的逻辑，旧目录继续可用。
- 数据库/预设不需要迁移；`runtime.json` 缺失时按 `legacy` 处理。

### 15. 验证

**单元/集成**

- 目录解析/校验：缺字段、重复 id、非法 os/arch、非法 sha256、`latest` 拒绝；
- 选择器：硬件命中、特性覆盖、许可证、无候选、回退顺序、`why` 文案；
- 安装：zip / tar.gz / tar.xz 解包；路径穿越、越界软链、空归档、缺 ffprobe、坏 SHA、截断下载；
- 激活：POSIX symlink 原子切换；Windows 指针回退；失败保留旧 current；回滚；GC 不删在用版本；
- 探测：fake ffmpeg/ffprobe 脚本输出固定文本；缺库模拟（脚本退出非 0 + stderr）；
- API：install/job/cancel/select/rollback/delete 的状态码、提权、agent 拒绝、`ffmpeg_busy`；
- 安全：私网 URL、重定向到私网、IPv6 环回、缺哈希、同源 sidecar 不作为唯一信任。

**真实冒烟**

- `scripts/transcode-smoke.py` 扩展：在临时数据目录起面板，`auto` 安装一个目录条目（Linux x64），
  跑 10 秒 lavfi 转码，断言 `capabilities.available=true` 且输出可 ffprobe。
- 有 `/dev/dri` 的宿主：VAAPI 单帧通过则点亮；失败断言“不可用 + 原因”，不误报可用。
- 有 NVIDIA 的宿主：NVENC 单帧通过才点亮；没有驱动时选择器不推荐 NVENC 构建。
- 源码构建：在有工具链/开发包的临时环境编译并通过；缺依赖时在 configure 前失败并列出包名。
- 旧 `current` 目录：启动后仍可用，`runtime.json` 为 legacy；安装新版本后可回滚到 legacy。

### 16. 里程碑

| 阶段 | 内容 | 验收 |
|---|---|---|
| M0 | `FfmpegCatalog` + `FfmpegRuntimeManager` + 原子安装 + Java 解包 + `runtime.json` + 回滚/GC | 干净 Linux 上 `auto` 装一个目录条目并跑通冒烟；不再依赖系统 `tar` |
| M1 | `FfmpegCapabilities` + `FfmpegSelector` + 预设特性预检 + UI 运行时卡片 | 缺特性的构建被明确拦下；VAAPI/QSV/NVENC 徽章来自真实探测 |
| M2 | `FfmpegBuildKit`（Linux，最小静态） + 预检 + 进度/取消 | 有工具链/开发包时源码构建成功且复制后可运行；缺依赖时 configure 前失败 |
| M3 | 多 GPU/设备、Windows AMF、macOS VideoToolbox、包管理器路径硬化 | 各平台探测矩阵有测试；包管理器失败给可行动原因 |
| M4 | 目录更新流程、镜像、文档、Release 说明 | 目录条目有来源/哈希/验证记录；发版可审计 |

### 17. 开放问题

1. **第一批目录条目收哪些**：BtbN（Linux x64/arm64？）、gyan.dev（Windows x64）、evermeet.cx（macOS x64/arm64？）、
   johnvansickle（Linux 全静态，但硬件加速可能没有）。每个条目必须人工验证 arch/libc/features/SHA-256 后再收录，
   不凭名字推断。
2. **默认许可证**：预设需要 x264/x265，默认只能是 GPL 构建；LGPL 作为“不要 GPL 编码器”的可选项。
3. **源码构建是否产品化**：建议产品化但仅 Linux；Windows/macOS 只做静态制品，不承诺源码构建。
4. **是否自动安装构建依赖**：第一阶段不做（只给精确包名）；第二阶段接未来的 `ToolchainManager`/BootstrapKit。
5. **shared vs static**：默认 `--disable-shared --enable-static`；如果要支持外部 libav 插件，再评估 rpath 方案。
6. **Windows 的 `current` 切换**：junction 需要权限/文件系统支持；退回指针文件 + 复制时要明确“非原子”并保留旧版本。
7. **目录刷新**：随面板发版最安全；是否允许操作员从本地 JSON 追加条目（建议允许，远程 URL 不允许）。

### 18. 完成定义

- 在一台**只有 JDK + 面板、没有系统 ffmpeg** 的干净 Debian 上，`POST /runtime/install {mode:"auto"}`
  能下载目录条目、校验、解包、安装、探测，`capabilities.available=true`，一次 10 秒测试转码成功；
  全程不调用系统 `tar`。
- 有 `/dev/dri` 时，VAAPI 单帧探测通过才点亮；没有设备或构建不带 VAAPI 时，UI 明确说缺什么。
- 有 NVIDIA 时，只有构建声明 `nvenc` 且单帧探测通过才推荐 NVENC；否则推荐 CPU 构建并说明原因。
- 源码构建在有工具链/开发包的机器上成功，复制到 `current/bin` 后可直接运行；缺依赖时在 configure 前
  给出精确包名。
- 安装失败/取消不会留下半套 `current`；旧版本可回滚；未被引用的旧版本可删除；所有操作有审计。
- 自定义 URL 仍可用，但必须显式哈希 + 提权；同源 sidecar 不再作为唯一信任。

## 附：全编码源码构建（设计稿，暂不实现）

> 状态：设计稿，2026-11-25。**本版线不实现**：二进制目录（BtbN / gyan / Jellyfin）已经覆盖
> “要一个能用的全编码 ffmpeg”，源码构建继续只做当前的最小 / 宿主依赖模式。本文留给后续版本，
> 也用来解释为什么“再补一个源码 URL”解决不了全编码问题。
>
> 目标：在一台**没有系统 ffmpeg、也没有 libx264-dev 等开发包**的干净宿主机上，由面板自己下载
> 并编译一套固定版本的依赖库与 FFmpeg，产出一个可托管、可探测、可回滚、可删除的运行时。
>
> 非目标：不自动 `sudo`；不追求“所有编码器”（见 §4 的声明矩阵）；不执行目录里的任意构建脚本；
> 不把 non-free 组件放进默认产物。

### 1. 现状：现在的源码构建是“宿主有什么编什么”

2026-11-25 在 Debian 13 容器里实测 `ffmpeg-source-9.1-gpl-static-v1`：

- 构建成功，但 `External libraries:` 只有 `iconv`；
- `featuresProbed` 为空，167 个编码器全是 FFmpeg 原生软件编码器；
- `-hwaccels` 为空；
- 产物 `runtime.json` 的 `license` 是 `LGPL-2.1-or-later`。

原因不是源码来自官方，而是 `FfmpegBuildKit` 的 `gpl-static-v1` 只启用宿主机上 `pkg-config`
能发现的 `-dev` 包。没有 `libx264-dev` / `libx265-dev` / `libvpx-dev` / `libaom-dev` /
`libsvtav1-dev` / `libva-dev` / `libvpl-dev` / `nv-codec-headers` 时，`configure` 自然什么也不带。

因此“全编码源码构建”不是一个下载地址问题，而是**依赖供应链问题**：FFmpeg 官方 tarball 只含
FFmpeg 自己，H.264/HEVC 软件编码来自 x264/x265，VP9/AV1 来自 libvpx/libaom/libsvtav1，硬件编码
来自 libva/libvpl/nv-codec-headers。把这些都交给宿主机 `apt`，构建就仍然依赖宿主机；要真正
自举，就得由面板把依赖也编出来。

### 2. 方案比较

| 方案 | 做法 | 优点 | 缺点 |
|---|---|---|---|
| A. 宿主开发包（现状） | 检测 `pkg-config`，有就启用 | 实现简单、构建快、产物小 | 干净宿主只能得到最小构建；用户要自己 `sudo apt` |
| B. **托管依赖栈（推荐）** | 面板按固定清单下载并编译依赖到临时 prefix，再编 FFmpeg | 真正自举、版本可复现、可声明特性矩阵、可做 GPL/LGPL 两种 profile | 工程量大、构建慢、磁盘与失败面大、要维护依赖版本与哈希 |
| C. Debian 源码包 | `apt-get source ffmpeg` / `debian/rules` | 带发行版补丁，`debian/rules` 已写全 `--enable-lib*` | 仍然依赖 `Build-Depends`；补丁绑定 Debian 版本；版本通常落后；不是自举 |
| D. 只发二进制 | 维持 BtbN / gyan / Jellyfin 目录 | 立刻可用、跨机器、无需编译器 | 不能按宿主选补丁/特性；版本受发布方节奏限制；许可证固定 |

结论：C 不解决“干净宿主缺库”，只是把“上游源码”换成“Debian 补丁源码”；D 是当前默认，继续保留。
B 才是“全编码源码构建”的完整形态，但它是独立产品线，不塞进本版。

### 3. 两种 profile，而不是一个“全编码”开关

| profile | 内容 | 产物许可 | 用途 |
|---|---|---|---|
| `lgpl` | 仅宽松许可依赖：libvpx、libaom、libsvtav1、libdav1d、libopus、libmp3lame、libvorbis、libwebp、libtheora、libjxl 等 | LGPL-2.1-or-later | 不想承担 GPL 义务；有 VP9/AV1/Opus 等 |
| `gpl` | `lgpl` + libx264、libx265、libxvid（以及 GPL-only 滤镜） | GPL-3.0（按实际链接记录） | 需要 H.264/HEVC 软件编码 |
| `nonfree` | 额外 libfdk_aac / libnpp 等 | 不可再分发 | **默认不提供**，只在文档中说明存在 |

`gpl` 不是“另一种源码”，而是 `--enable-gpl` 加 GPL 库；`lgpl` 里也可以有硬件编码器
（VAAPI / QSV / NVENC / AMF / Vulkan），它们不是 GPL-only。硬件能否使用仍由运行期探测决定，
构建成功不等于机器上有驱动。

### 4. “全编码”的声明矩阵

“全编码”必须是一个可验收的列表，不是“所有 codec”。第一版建议固定为：

- 视频编码：`libx264`、`libx265`、`libvpx-vp9`、`libaom-av1`、`libsvtav1`、`librav1e`；
- 音频编码：原生 `aac`、`libopus`、`libmp3lame`、`libvorbis`；
- 容器/滤镜：`libwebp`、`libtheora`、`libass`、`libfreetype`（drawtext）、`libvmaf`；
- 硬件包装（有头文件时）：`vaapi`、`qsv`、`nvenc`、`amf`、`vulkan`；
- 明确排除：`libfdk_aac`（non-free）、`libnpp`（non-free）、闭源 SDK、平台专属且无法在
  当前宿主验证的组件。排除项在 UI 与 `runtime.json` 里写明。

`runtime.json` 继续分 `featuresDeclared` / `featuresProbed`：声明是构建计划，探测是安装后
`-encoders` / `-hwaccels` 的真实结果，两者不一致要在设置页高亮。

### 5. 数据与配方

新增只读资源 `src/main/resources/transcode/ffmpeg-full-builds.json`（随 jar 发布，不支持远程 URL）：

```json
{
  "profiles": [
    {
      "id": "full-gpl-9.1",
      "profile": "gpl",
      "ffmpeg": "9.1",
      "recipe": "full-static-v1",
      "ffmpegSource": {"url": "https://ffmpeg.org/releases/ffmpeg-9.1.tar.xz", "sha256": "…"},
      "dependencies": [
        {"id": "x264", "version": "…", "url": "https://…", "sha256": "…", "archive": "tar.xz"},
        {"id": "x265", "version": "…", "url": "https://…", "sha256": "…", "archive": "tar.xz"}
      ],
      "features": ["libx264", "libx265", "libvpx-vp9", "libaom-av1", "libsvtav1", "libopus", "libmp3lame", "vaapi", "qsv", "nvenc"],
      "estimated": {"downloadBytes": 0, "buildMinutes": 0, "diskBytes": 0}
    }
  ]
}
```

约束：

- JSON 只做**选版与校验**，不携带可执行的 `configure` 参数或 shell 片段；每个依赖的构建方式由
  Java 里的固定 recipe（`X264Recipe`、`X265Recipe`、`AutotoolsRecipe`…）实现，目录只能引用
  recipe id。这样目录被替换也不能变成任意代码执行。
- 每个 URL 必须 https + SHA-256 + 允许镜像列表；镜像只换主机，不换哈希，沿用
  `DownloadSourcePicker` 与 `FfmpegDownload` 的校验/换源逻辑。
- 版本与 FFmpeg 大版本绑定；同一 profile 的依赖集合不可在运行时由用户增删。

### 6. 构建流水线

```
预检（工具链/磁盘/网络）
  → 下载全部源码到 <tools>/.cache/full-build/<sha256>/
  → 逐个解包、configure --prefix=<stage>/prefix、make -j、make install
  → 以 <stage>/prefix 为 PKG_CONFIG_PATH 配置 FFmpeg
  → make -j、make install 到同一 prefix
  → 探测 -version / -encoders / -hwaccels + 单帧 lavfi 编码
  → 写入 runtime.json，原子激活
```

关键点：

- **不调用系统 tar**：继续用 `FfmpegArchive` 的 Java 解包与路径/软链检查。
- **构建身份**：所有 `configure` / `make` 通过 `BootstrapEnvironment.agentProcess` 以 agent uid
  运行，prefix 在 `<data>/transcode/tools` 下；面板不以 root 编译或安装。
- **环境隔离**：显式设置 `PKG_CONFIG_PATH`、`PKG_CONFIG_LIBDIR`、`CFLAGS`、`LDFLAGS`、
  `MAKEFLAGS`；清掉继承的 `LD_PRELOAD` / `CC` / `CFLAGS` 之类变量，避免宿主环境污染。
- **并行**：`make -j` 取 `min(CPU 核数, 内存/2GiB, 4)`；面板同一时间只允许一个全量构建。
- **磁盘**：预检要求 ≥ 6 GiB 可用；下载缓存与构建目录都算；低于阈值在 configure 前失败。
- **超时**：单依赖 20 分钟、FFmpeg 30 分钟、总 90 分钟；超时 kill 整个进程组并清理 stage。
- **取消**：与现有安装任务一致，取消后不产生 `current`，临时目录按 GC 规则清理。
- **日志**：一个 `build-<job>.log`，按 `== dependency x264 ==`、`== configure ffmpeg ==` 分段；
  进度阶段为“下载 i/n → 编译依赖 i/n → 配置 FFmpeg → 编译 FFmpeg → 探测”。
- **缓存**：第一版只缓存下载的源码包（按 SHA-256），不缓存编译产物；跨版本复用的依赖缓存
  留到 M3，避免“旧对象文件混进新构建”。

### 7. UI

设置页的源码卡片增加“构建方案”下拉：

- `官方源码（宿主依赖）`：现状，最小/宿主有什么编什么；
- `全编码 LGPL`：列出声明的编码器、依赖下载大小、预计时间、产物许可；
- `全编码 GPL`：同上，并明确“产物为 GPL-3.0，分发需履行 GPL 义务”。

选中后展示：

- 依赖矩阵：每个依赖的版本、是否将从源码构建、预计下载大小；
- 预检结果：工具链、nasm、磁盘、网络；不可构建时按钮禁用并说明原因；
- 许可提示：`lgpl` / `gpl` 的实际含义，不能只写“全编码”；
- 构建中：阶段、总进度、当前依赖、日志尾部、取消；
- 完成后：`featuresProbed` 与声明差异、`toolchain`、`runtime.json` 路径、设为默认。

不把“全编码”做成默认项：默认仍是二进制下载；源码构建是高级路径。

### 8. API

- `GET /api/transcode/runtime/full-builds`：返回 profile、依赖清单、预计大小/时间、预检结果；
  只读，agent 可读与否按现有 `transcode` scope 决定（安装仍需提权）。
- `POST /api/transcode/runtime/install` 增加
  `{mode:"full-source", profile:"lgpl"|"gpl", id:"full-gpl-9.1"}`；继续走
  `requireHumanElevation` + 审计 `transcode.runtime.install`。
- `TranscodeInstallJob` 增加 `stage`（`download` / `dependency` / `configure` / `make` / `probe`）
  与 `stageIndex` / `stageTotal`，前端只读展示；旧字段保留。
- 失败统一返回可行动的原因：`ffmpeg_build_dependency_failed: x265`、`ffmpeg_build_disk_full`、
  `ffmpeg_build_timeout`、`ffmpeg_build_hash_mismatch`，日志里给最后 40 行。

### 9. 安全与供应链

- 依赖与 FFmpeg 的源码全部 https + SHA-256 固定；镜像只允许同哈希；重定向逐跳校验，拒绝私网/
  环回/链路本地地址（复用 `FfmpegInstaller.validateUrl` / `FfmpegDownload`）。
- 构建逻辑是 Java 里的固定 recipe，不接受目录或用户传入的 `configure` 参数；不拼接 shell。
- 解包必须防路径穿越、绝对路径、越界软链；沿用 `FfmpegArchive` 的检查并补依赖归档的测试。
- 构建产物由 agent uid 写出，探测与单帧编码也以 agent 身份执行；面板不以 root 运行新二进制。
- `lgpl` profile 禁止 `--enable-gpl`，也禁止链接 x264/x265/xvid；构建后静态检查
  `-encoders` 与链接表，发现 GPL 库即失败，而不是悄悄改许可。
- `nonfree` 不提供 UI；若未来提供，必须单独确认、不可再分发，并在 `runtime.json` 记录。
- GPL profile 的分发义务：面板应保留对应源码包与构建配方（下载缓存/日志可清理，但配方与版本
  固定），并在设置页说明；这是产品选择，不只是技术问题。
- 构建期间限制并发与 `nice`，避免把面板/转码队列拖死；磁盘不足时先失败，不写半套。

### 10. 验收

- 干净 Debian 13（无 ffmpeg、无 `-dev`）：
  - `lgpl` profile 构建成功，`-encoders` 含 `libvpx-vp9`、`libaom-av1`、`libsvtav1`、`libopus`、
    `libmp3lame`，不含 `libx264` / `libx265` / `libxvid`；`runtime.json.license=LGPL-2.1-or-later`；
  - `gpl` profile 构建成功，含 `libx264` / `libx265` / `libxvid`，`license=GPL-3.0`；
  - 两种 profile 都通过 H.264/HEVC/VP9/AV1 的单帧编码（有对应编码器时）。
- 依赖归档坏哈希/截断/路径穿越：失败且不产生 `current`。
- 磁盘不足、超时、取消、断网换源：日志可读，临时目录可回收，旧 `current` 不受影响。
- 重复构建同一 profile：命中源码缓存仍重新编译；M3 后才评估对象缓存。
- 探测结果与声明不一致：设置页高亮，不静默当作可用。
- `lgpl` 产物中静态检查不到 GPL 库；`gpl` 产物许可字段正确。
- Windows：第一版不做；若后续做，只承诺 MinGW-w64 工具链，不引入 MSVC 全家桶。

### 11. 里程碑（本版不做，仅排期参考）

| 阶段 | 内容 | 验收 |
|---|---|---|
| M0 | 配方 JSON、依赖 recipe 骨架、预检、UI 只读展示 | 能列出依赖与预计大小，不下载 |
| M1 | Linux `lgpl` profile：libvpx/aom/svt-av1/opus/mp3lame 编译链 | 干净宿主构建成功并通过编码测试 |
| M2 | Linux `gpl` profile：x264/x265/xvid | 同上，许可与特性矩阵正确 |
| M3 | 源码缓存、镜像、断点/重试、依赖版本升级流程 | 失败可恢复，升级有哈希记录 |
| M4 | Windows MinGW、硬件头文件矩阵、多 GPU 探测 | 各平台有冒烟；不承诺 macOS 源码构建 |

### 12. 开放问题

1. 第一版依赖集合是否包含 `libdav1d`、`libvmaf`、`libass`/`libfreetype`；它们会显著增加构建面。
2. 是否静态链接所有依赖（`--enable-static` + `.a`）还是保留共享库；静态更可移植，但 glibc 仍动态。
3. 依赖版本固定策略：跟 FFmpeg 大版本走，还是各自 latest stable；后者会让同一面板版本产出不同字节。
4. 源码缓存与 GPL 源码提供义务的生命周期；清理日志/缓存时如何保留合规材料。
5. 构建产物的可复现性：是否记录 `configure` 全参数、编译器版本、依赖哈希；是否要求 bit-reproducible。
6. Windows 的 MinGW 与 Linux 的 Zig/系统工具链差异；是否接受“同一 profile 在不同工具链下特性不同”。
7. 是否把 Debian 源码包作为“补丁来源”而不是“全编码来源”；如果做，需要单独的 patch 应用与冲突处理。

## 附录 A：设计评审待补清单

> 2026-11-10 评审记录。实现前按优先级补齐，或在实现中明确取舍；不阻塞本文档定稿。

### P0（动工前最好定死）

1. **API / WS 契约表**：`/api/transcode/feature|queue|jobs|presets|preview|browse|tools|settings|capabilities|agent` 的 method、请求/响应、错误码；WS 主题、快照/增量、日志 tail、断线重连后的补状态。
2. **功能关闭时如何安装 ffmpeg**：启用向导要能完成下载/安装/编译，或允许「已启用但运行时不可用」的中间态；否则关闭时没有页面可装依赖。
3. **功能状态唯一来源**：`feature.json` 与 `server.yaml` 只能有一个事实来源；另一个只放运行参数。
4. **失败状态机边界**：多会话决策仲裁、`WAITING_DECISION` 时面板重启的倒计时、降级后仍失败、弹窗「停止」的确切语义、完全自动化下无降级路径可用的处理。
5. **资源治理**：每任务线程上限、`nice`/`ionice`、媒体根磁盘阈值、工具任务是否占队列槽、连续失败熔断、面板自身 CPU 保护。
6. **媒体浏览/上传契约**：分页、排序、隐藏文件、权限错误、软链；上传大小上限、`ApiGuardFilter` 流式例外、`.part` 落盘、下载 Range。
7. **「全量预设」验收矩阵**：以 3FUI 哪个版本为基准、字段/功能对应表、明确排除项（AviSynth、ffplay、插件系统、更新器、用户统计、MediaFoundation 等 Windows 专属）。
8. **实施阶段**：文档与契约 → 功能门/运行时探测 → 核心域/命令生成 → 队列/失败策略 → 前端六页 → 全量预设 → 工具 → 硬件/下载/编译 → GPU → Agent → 验收。

### P1（实现中补齐）

9. **数据生命周期**：preset/queue schema 版本与迁移、job 历史保留、日志清理、备份/恢复是否包含媒体根、升级兼容。
10. **Windows / macOS 专项**：静态包架构/校验/解压、macOS quarantine、Defender、服务账号与 UNC/映射盘、VideoToolbox 是否计入硬件编码。
11. **多 GPU / 设备选择**：`vaapi_device` 默认与多卡选择、NVIDIA 多卡、预设是否绑定 GPU。
12. **Agent 细节**：工具目录 schema 版本、每工具 scope/write 标记、每 token 配额、`WAITING_DECISION` 决策端点、审计字段。
13. **安全细则**：不可信预设导入、附加参数 denylist（`-y`、覆盖 `-i`、任意输出路径）、输出软链、ffmpeg CVE、启用告知文本。
14. **可观测性**：任务历史/失败计数、审计事件、完成/失败通知（页面关闭后是否 webhook）、GPU 数据异常日志。
15. **测试矩阵扩展**：并发多任务、磁盘满、只读媒体根、决策中重启、多会话同时操作、Windows/macOS 静态包、schema 迁移、自定义 URL SSRF。

### 已记录但暂不定

- Linux 的 ffmpeg 获取顺序：本文按「镜像自带 → apt/dnf → 教程 → 源码编译」写；静态包下载是否也作为 Linux 可选源，待实现前确认。
- `.3fui` 导入导出为可选适配器，不是首发阻塞项；是否首发送，取决于全量预设的字段映射成本。