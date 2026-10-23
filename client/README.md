# hdsl-tui — 在终端里进入 HDSL-Web 的实例

`client/hdsl-tui` 是给 HDSL-Web 面板配的终端客户端：在真终端里登录面板、提权、
选实例，然后把 agent 的 PTY 原样搬到你面前。它**只做渲染**——HTTPS 判定、Web Shell
总开关、开发者密码提权全部仍由面板决定，客户端不复制任何安全逻辑。

协议契约见 [`docs/terminal-protocol.md`](../docs/terminal-protocol.md)。

## 安装

从 Release 页下载对应平台的二进制即可，没有运行期依赖（`CGO_ENABLED=0` 静态链接）：

| 平台 | 资产名 |
|---|---|
| Linux x86-64 / arm64 | `hdsl-tui-<版本>-linux-amd64` / `-arm64` |
| macOS Intel / Apple Silicon | `hdsl-tui-<版本>-darwin-amd64` / `-arm64` |
| Windows x86-64 | `hdsl-tui-<版本>-windows-amd64.exe` |

下载后 `chmod +x` 放进 `PATH`，用 `hdsl-tui --version` 确认能跑。
Release 同时提供 `SHA256SUMS-client.txt`，请先核对校验和。

从源码构建（仓库里已带 `vendor/`，**不联网也能编**）：

```sh
cd client/hdsl-tui
GOFLAGS=-mod=vendor GOPROXY=off CGO_ENABLED=0 go build -trimpath -ldflags "-s -w -X main.version=v0.5.4" ./cmd/hdsl-tui
```

需要 **Go 1.24 或更新**（`go.mod` 的 `go 1.24.0`；依赖锁在同时支持 1.24 的版本上，
所以发行版自带的 Go 通常就够）。也可以用 `scripts/install-hdsl-tui.sh --build`，
它会下载源码包并离线编译。

## 用法

```sh
hdsl-tui [选项] <面板地址>

hdsl-tui https://panel.example.com
hdsl-tui --instance DClDN1J3w0C_ --resume <sessionId> https://panel.example.com
hdsl-tui --brand kimi --instance kimi-1 https://panel.example.com
```

注意：选项写在面板地址前面（地址是位置参数）。

| 选项 | 说明 |
|---|---|
| `--instance <id>` | 直接进入该实例，不显示菜单 |
| `--brand <name>` | `kimi` / `opencode` / `claude-web`，默认走 dsh 实例 |
| `--resume <sessionId>` | 附着到该 harness 会话（kimi / opencode 的会话恢复） |
| `--user <name>` | 面板用户名，默认 `admin`；给了就不再提示 |
| `--ca <pem>` | 额外信任的 CA 证书（PEM 文件），用于自签面板 |
| `--insecure` | 跳过证书校验；**会先打印 SHA-256 指纹并要求输入 `yes`** |
| `--version` | 打印客户端版本与 `protocol` 后退出，不联网 |
| `--verbose` | 把请求与握手细节写到 stderr |

不带 `--instance` 时，客户端拉取 dsh 实例列表和三个品牌列表，在终端里给出编号菜单；
空分组不显示。输入编号进入实例，输入 `q` 退出。

凭证处理：面板密码与开发者密码都用**关闭回显**的方式读取（`term.ReadPassword`），
不进 argv、不落盘、不进日志；会话 Cookie 只存在内存里，进程退出即消失。

### 会话中的按键

| 按键 | 行为 |
|---|---|
| `Ctrl-]` 然后 `q` | 离开客户端，agent 继续在后台跑（再运行一次即可接回） |
| `Ctrl-]` 然后 `k` | 停掉终端（`DELETE …/terminal`）再退出 |
| `Ctrl-]` `Ctrl-]` | 给 agent 发一个字面量 `Ctrl-]` |
| 其他按键（含 `Ctrl-C`） | 原样发给 agent，不被本地吞掉 |

窗口大小：Unix 用 `SIGWINCH`；Windows 没有这个信号，改为每 500 ms 采样一次，变化才发
`{"type":"resize"}`。agent 自己退出时，客户端按 `exit.code` 作为进程退出码。

## TLS

默认做完整校验。自签或私有 CA 的面板有两条路：

- 推荐 `--ca server.pem`：把这张 CA 证书加进信任池，其余校验照常。
- 兜底 `--insecure`：跳过校验，但会先把面板证书的 SHA-256 指纹、颁发对象、有效期打印
  出来，要求你手输 `yes`。指纹要自己核对（比如从面板的「设置 → HTTPS 证书」抄一份），
  不然这一步只是让你多敲了四个字符。

## Windows

Windows 客户端连接 **POSIX 面板**是正常的。反过来则不行：Windows 面板上「终端」是在
面板所在机器的桌面上开一个控制台，没有 PTY、没有 ticket，接口返回
`{"mode":"desktop","hint":"…"}`；客户端会把 `hint` 原样打印出来并以退出码 4 结束。这是
平台限制（要做需要 ConPTY，见 `docs/` 里的规划），不是配置问题。

Windows 上客户端会自行打开控制台需要的开关并在退出时恢复原状：

- 输出句柄启用 `ENABLE_VIRTUAL_TERMINAL_PROCESSING`；
- 输入句柄启用 `ENABLE_VIRTUAL_TERMINAL_INPUT`；
- 输入/输出代码页设为 65001（UTF-8）。

conhost 与 Windows Terminal 的行为差异、`Ctrl-C` 语义、窗口缩放属于必须真机验证的部分；
本仓库的 CI 只能覆盖交叉编译，覆盖不了控制台行为。

## 排错

| 现象 | 含义与做法 |
|---|---|
| `面板不在 HTTPS 下…` | 面板要求 HTTPS 才能开终端：在「设置 → HTTPS 证书」里启用，或打开 `https.trust_forwarded_proto` |
| `Web Shell 已在面板里关闭…` | 「设置 → 开发者」里的 Web Shell 开关是关的 |
| `还没设置开发者密码…` | 先在「设置 → 开发者」里设置开发者密码 |
| `需要先提权…` | 提权是 30 分钟滑动过期，重新运行本命令并输开发者密码 |
| `开发者密码不正确` / `用户名或密码不正确` | 密码不对；连续失败会被面板临时锁定 |
| `该实例已经有终端在运行…` | 重连即可，不必另开 |
| `找不到该实例…` | 不带 `--instance` 跑一次拿到列表 |
| `服务端拒绝了这个连接（1008）` | ticket 过期或不属于当前会话，重新运行本命令 |
| `服务端没有正在运行的终端（1011）` | agent 已经不在了，重新运行本命令 |
| `这台面板运行在 Windows 上…` | 见上面的「Windows」一节 |
| `--insecure 需要交互确认…` | 该选项必须在真终端里运行 |
| `无法关闭回显读取密码…` | 密码提示需要一个真终端；管道里跑不了 |

## 退出码

| 码 | 含义 |
|---|---|
| 0 | 正常结束：`Ctrl-] q` 离开、`Ctrl-] k` 停掉、agent 以 0 退出 |
| 2 | 认证或提权失败（登录 401、开发者密码 401、被锁定、需要提权、ticket 1008） |
| 3 | 无法连接面板或连接中断（DNS/连接/TLS 失败、socket 断开、1011） |
| 4 | 策略拒绝，重试无用（面板不在 HTTPS、Web Shell 关闭、Windows 桌面终端、确定不能启动） |
| 5 | 用法错误（参数、面板地址、读不到密码） |
| N | agent 自己以 N 退出时，客户端原样透传 |

## 目录

```
cmd/hdsl-tui/       参数、选实例、生命周期、TLS 与指纹确认
internal/panel/     REST：登录 / 提权 / 实例列表 / 开终端 / 关终端
internal/pterm/     WebSocket：字节通道与控制帧
internal/termx/     raw 模式、窗口尺寸、Windows 控制台（build tags 分平台）
internal/cli/       实例选择器、detach 状态机、错误文案与退出码
```

测试不需要真终端（raw 模式抽成 `termx.Raw`，CI 用 `termx.Nop`）：

```sh
GOFLAGS=-mod=vendor GOPROXY=off go test ./...
```

## 依赖

随仓库提交（`vendor/`），构建完全离线；升级是显式提交：

| 模块 | 版本 | 用途 | 许可 |
|---|---|---|---|
| `github.com/gorilla/websocket` | v1.5.3 | WebSocket 客户端 | BSD-3-Clause |
| `golang.org/x/term` | v0.47.0 | raw 模式、尺寸、无回显读密码 | BSD-3-Clause |
| `golang.org/x/sys` | v0.49.0 | Windows 控制台模式与代码页 | BSD-3-Clause |

升级方式：`go get -u <pkg> && go mod tidy && go mod vendor`，并在 `NOTICE` 里同步版本。