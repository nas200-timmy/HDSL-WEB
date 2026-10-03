# HDSL 桌面版 UI 像素级复刻规范（网页版施工图纸）

> 目标：HDSL-web 的 React 面板与桌面版 HDSL（HMCL 衍生，JavaFX + JFoenix + MonetFX/Material You）像素级一致。
> 视觉基准：`docs/ui-reference/` 下的真实截图（浅色 = Xvfb 实跑捕获，深色 = README 实拍）。
> 目标代码库（只读参考）：`/home/coder/code/dsh/HDSL`。

## 1. 窗口外框（Decorator）

| 项目 | 值 | 来源 |
|---|---|---|
| 窗口样式 | 透明 Stage，内容圆角 8px，外阴影 DropShadow rgba(0,0,0,0.4) blur 10 | `ui/decorator/Decorator.java:101,110,119-125`，`MainWindowPane.java:61,90-91` |
| 内容最小尺寸 | 800×490（标题栏 40 + 内容 450） | `Decorator.java:89-92` |
| 标题栏 | 高 40px；左侧：返回箭头（可返回页）/主页或关闭图标 + `icon-title.png`(24×24) + 标题（14px 粗体，左距 8）；右侧：帮助(?) / 最小化 / 关闭 三个 40×40 圆形按钮（SVG 图标） | `MainWindowPane.java:116-123,171-199,247-299`，`root.css:673-679,773-788` |
| 标题栏背景 | **primary-container（浅 #5C6BC0）**，文字/图标 **on-primary-container（#F8F6FF）** | `MainWindowPane.java:202-218`，`Themes.java:1073-1092,1116-1127` |
| 网页版处理 | **整页铺满视口**（网页化适配，不模拟桌面窗口）：width/height 100vw/100vh、无圆角无阴影；返回箭头保留（导航功能）；**桌面版的 帮助/最小化/关闭 三键属于窗口系统，网页不存在，全部移除** | — |

## 2. 左侧主导航（宽 200px，`MainPage`）

结构：`AdvancedListBox`（内容 padding `12 0 0 0`），主页面侧边栏挂半透明板，中心透出壁纸。

顺序（`MainPage.java:150-164`）：

| 分区标题（12px，大写，padding `8 16`，下 1px 分隔线） | 条目 | 图标 |
|---|---|---|
| 账户 | 账户卡片（32×32 头像，标题=账户名/无账户提示，副标题=供应商/提示语） | — |
| 游戏 | 当前实例项（32px 实例图标，标题=实例名，副标题=版本）；无实例时显示"尚无实例/安装一个新实例" | — |
| | 实例列表 | FORMAT_LIST_BULLETED |
| | 下载 | DOWNLOAD |
| 通用 | 设置 | SETTINGS |

- 条目容器 padding `10 16`；左侧图标 20px（或 32px 图形）；文字区 padding-left 10；标题 13px（on-surface），副标题 10px（on-surface-variant）。
- 选中态：背景 secondary-container-50（#D0D5FD80），标题 on-secondary-container 加粗，图标同色。
- 涟漪：圆形，fill on-secondary-container。

## 3. 主页面

- 背景图：`assets/img/wallpapers/2021-08-26.jpg`（默认；另有 2015-06-22/2016-02-25），cover 铺满、底部居中。
- 内容区**不盖遮罩**（壁纸直透）；仅侧边栏盖 surface-transparent-50。
- 启动面板（右下，margin 20）：整 pane 230×57；主按钮 200×55 左圆角 4，背景 primary-container，两行文字（16px 动作 + 12px 实例/版本，on-primary-container）；右侧 27×55 菜单钮（圆角 4，ARROW_DROP_UP 图标），中间 3px 竖直渐变分隔线。
- 动作：启动/停止当前实例（连带进度展示）；箭头弹实例选择菜单（jfx-popup 样式：surface 底、圆角 4）。

## 4. 子页面骨架（所有内页）

- 标题栏显示"← 页面标题"，? _ × 右侧不变。
- 左侧 200px 子侧边栏（半透明）+ 右侧内容区（**盖 surface-transparent-50 遮罩**），内容根 margin/padding 10。
- 列表页内容 = 卡片（card：surface-container-low-80、圆角 4、阴影 depth-1）包裹 工具栏行 + 列表；空态为居中 20px 灰字。

### 实例列表页（`InstancesPage`）
子侧边栏：实例文件夹项（文件夹图标+路径+关闭×）、"添加实例文件夹"；底部操作框（限高 144）：安装新实例(+) / 安装整合包(立方) / 全局设置(齿轮)。
内容：工具栏（刷新/搜索，搜索态整行变输入框）、实例行（radio 选择 + 32px 图标 + 两行文字 + 右侧火箭/更多圆形 30px 按钮）、行底 1px 分隔线。

### 下载页（`DownloadPage`）
子侧边栏分区：NEW GAME（新实例[手柄] / 整合包[立方]）、ADDONS（插件[拼图] / 技能包[纹理]）。
内容：过滤卡片（"名称"标签 + 输入框 + "类型"下拉(max 150) + 右侧实心 Refresh 按钮）；列表行（32px 图标 + 标题=版本号/名称 + channel 徽章 + 副标题=时间 + 右侧 地球/箭头 30px 圆钮）；加载中显示 spinner；失败显示重试。

### 设置页（`SettingsPage`）
子侧边栏：全局实例设置(手柄) / Node.js(咖啡)；分区"启动器"：通用(调谐) / 外观(调色) / 下载源(下载)；分区"帮助"：关于(信息)。右侧 ComponentList 卡片组 + 小标题（16px 帮助图标 + 文字，padding `8 0 0 0`）。
网页版映射：通用（服务器信息/语言）、外观（主题亮暗）、下载源（npm registry）、HTTPS 证书（网页新增，放"启动器"分区末位，样式一致）、环境体检（帮助分区）、关于（版本/版权/第三方声明）。

### 账户（`AccountListPage`）
账户卡片 `dsh-account-card`：surface-container-low-80、圆角 4、depth-1 阴影；头像（皮肤/首字母单色块 16px 圆角）；名称/供应商/模型行；操作按钮。底部"添加账户"卡片。

## 5. 实例详情页组（`InstancePage`）

标题"实例管理 - <实例id>"。子侧边栏：实例管理(齿轮) / 自动安装(部署码) / 插件(拼图) / 技能包(纹理) / 会话(包裹) /（网页新增：日志 / 控制台）/ 详情(信息)；顶部独立操作框：启动(火箭)/停止(取消) + 浏览(文件夹) + 管理(菜单：启动/停止、日志、导出整合包、重命名、复制、删除)。

- 插件页：工具栏（jfx-tool-bar-button 高 37 圆角 5：刷新/添加文件/从来源安装/下载/打开目录 + 搜索；选中行后变选择工具栏：启用/停用/删除/全选/取消）；列表行（checkbox + 32px 图标 + 两行文字 + 版本徽章 + 右侧信息/删除 30px 圆钮）。
- 其余子页复用同构列表/卡片。
- 自动安装 = 快速安装预设（AppBoot 选择）。

## 6. 向导（`DecoratorWizardDisplayer`）

以页面推入导航栈，标题"向导名 - 步骤名"；每步内容 = 垂直卡片选项（TwoLineListItem + 右箭头，max-width 400、间距 8）或表单；底部右侧 取消 / 上一步 / 下一步（文字按钮）。

## 7. 主题（Monet / Material You）

- 机制：种子 **#5C6BC0**、ColorStyle FIDELITY、Contrast DEFAULT、Spec 2025；亮暗双套 `-monet-*` CSS 变量。
- 网页实现：npm `@material/material-color-utilities`，启动时用种子生成 light/dark 两套变量写入 CSS 自定义属性；默认跟随 `prefers-color-scheme`，外观页可手动切换并持久化（localStorage）。
- 浅色默认全量色板（`assets/css/blue.css`，照抄）：

| 变量 | 值 |
|---|---|
| primary / on-primary | #4352A5 / #FFFFFF |
| **primary-container / on-primary-container** | **#5C6BC0 / #F8F6FF**（标题栏、启动钮） |
| secondary-container / on-secondary-container | #D0D5FD / #565B7D（选中态、徽章） |
| surface / on-surface | #FBF8FF / #1B1B21 |
| surface-variant / on-surface-variant | #E2E1EF / #454651（副文字/图标/分隔） |
| surface-container-low(est) | #F5F2FA / #FFFFFF（卡片/输入框） |
| surface-container / high / highest | #EFEDF5 / #E9E7EF / #E3E1E9（列表行/对话框/菜单） |
| outline / outline-variant | #767683 / #C6C5D3 |
| error / error-container | #BA1A1A / #FFDAD6 |
| tertiary 系 | #775200 / #976900 / #FFDEAC（警告） |
| inverse-surface / inverse-on-surface | #303036 / #F2EFF7（tooltip/snackbar） |
| 透明派生 | primary-50 #4352A580、surface-50 #FBF8FF80、surface-container-low-80 #F5F2FACC、secondary-container-50 #D0D5FD80 |

- 暗色：用 material-color-utilities 以同种子生成（HSLCToner 对应 Fidelity 风格），不要手拍。

## 8. 组件样式速查（`root.css`）

- `.card`：bg surface-container-low-80、圆角 4、padding 8、阴影 `0 10px rgba(0,0,0,0.26)` depth-1 微调（gaussian 10 / spread 0.12 / 偏移 -1,2）。
- 实心按钮 `.jfx-button-raised`：bg primary、文字 on-primary、14px；描边按钮 `.jfx-button-border`：透明底、outline 0.2px、圆角 5、文字 primary。
- 工具栏按钮 `.jfx-tool-bar-button`：高 37、圆角 5、透明底、图标 20px+文字 14px。
- 圆形图标按钮 `.toggle-icon4`：30×30。
- 输入框：bg surface-container-highest、粗体、focus 边 primary、padding 8。
- 复选框/单选：选中 primary。开关 `.jfx-toggle-button`。
- 进度条：track secondary-container、bar primary。
- 徽章 tag：bg secondary-container、文字 on-secondary-container、圆角 2、padding 2、12px。
- 提示条 `.hint`：圆角 5 padding 6（info=primary 底/error=error-container/warning=tertiary）。
- 弹窗：遮罩 rgba(0,0,0,0.1)；布局 bg surface-container-high、圆角 4、padding `24 24 16 24`；标题 20px 粗；正文 pref 400；按钮区右对齐（确认=primary 文字、取消=on-surface-variant 文字）。缩放淡入。
- Snackbar：底部居中，inverse-surface 底、inverse-on-surface 文字。
- 弹出菜单：bg surface、圆角 4、项 padding `8 16` 间距 10、12px。
- 滚动条：轨道 #F1F1F1、thumb #BCBCBC（浮动式，hover 才显示）。

## 9. 图标

- 全部内联 SVG path（`HDSL/src/main/java/org/jackhuang/hmcl/ui/SVG.java`，Material Symbols outlined/24px）。网页版把用到的枚举提取成 React 组件，按 currentColor 上色。
- 需要的映射：SETTINGS、DOWNLOAD、FORMAT_LIST_BULLETED、EXTENSION(+FILL)、PACKAGE2(+FILL)、STADIA_CONTROLLER(+FILL)、TEXTURE、LOCAL_CAFE(+FILL)、STYLE(+FILL)、INFO(+FILL)、TUNE、ARROW_BACK/FORWARD、CLOSE、SEARCH、REFRESH、ROCKET_LAUNCH、CANCEL、DELETE/DELETE_FOREVER、FOLDER_OPEN、MENU、HELP、WARNING、ERROR、CHECK、SELECT_ALL、PUBLIC、ADD、HOME、VISIBILITY、MINIMIZE_CENTER 等（以页面实际需要为准，从 SVG.java 抄 path 数据）。

## 10. 字号

窗口标题 14 粗；分区标题 12；侧栏条目 13/10；列表行标题 15 / 副 12 / 徽章 12；启动钮 16+12；工具栏 14；弹窗标题 20 / 正文 14；空态 20；日志等宽 11。字体：系统 sans（中文回退栈）。

## 11. 动画（Motion.java 的 CSS 映射）

- EASE=(0.25,0.1,0.25,1)；EASE_IN_OUT_CUBIC_EMPHASIZED ≈ cubic-bezier(0.2,0,0,1) 近似即可。
- 页面导航（Controllers.navigate）：200ms——旧页 100ms 淡出+左移 30px，新页淡入归位。
- FORWARD 前进：旧页左移 20%+淡出，新页右侧 20% 淡入，200ms。
- Tab 切换：SLIDE_UP_FADE_IN 400ms。
- 点击涟漪：JFXRippler 圆形扩散（CSS 实现）。
- 弹窗：缩放 0.9→1 + 淡入 200ms。

## 12. 文案

以 `HDSL/src/main/resources/assets/lang/I18N_zh_Hans.properties` 为准（实例列表/下载/设置/账户/启动游戏/安装新实例…直接取原文）。

## 13. 网页版与桌面版的差异（仅有的三处）

1. 首屏登录：壁纸 + 居中 HMCL 风格登录卡片（圆角 4、surface-container-high、20px 标题"登录 HDSL-web"）。
2. 窗口控制：帮助/最小化/关闭三键为桌面窗口系统功能，网页版不呈现；「关于」在设置页。
3. 新增条目：设置里"HTTPS 证书"、实例子导航里"日志/控制台"、帮助里"环境体检"——全部用既有 HMCL 组件样式。
