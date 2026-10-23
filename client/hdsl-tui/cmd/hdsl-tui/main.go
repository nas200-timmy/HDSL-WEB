// Command hdsl-tui opens one HDSL-Web instance's terminal in a real terminal.
//
// It is a viewer, not a session owner: closing it detaches and the agent keeps
// running. Everything the panel checks — HTTPS, the web shell switch, the
// developer password — stays checked by the panel; this client only renders.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"os/signal"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/nas200-timmy/HDSL-WEB/client/hdsl-tui/internal/cli"
	"github.com/nas200-timmy/HDSL-WEB/client/hdsl-tui/internal/panel"
	"github.com/nas200-timmy/HDSL-WEB/client/hdsl-tui/internal/pterm"
	"github.com/nas200-timmy/HDSL-WEB/client/hdsl-tui/internal/termx"
)

// version is stamped by the release build: -X main.version=v0.5.4.
var version = "dev"

// brands are the three brand instance surfaces, in menu order.
var brands = []string{"kimi", "opencode", "claude-web"}

const requestTimeout = 30 * time.Second

type options struct {
	instance    string
	brand       string
	resume      string
	user        string
	ca          string
	insecure    bool
	showVersion bool
	verbose     bool
}

func main() {
	os.Exit(run(os.Args[1:], os.Stdin, os.Stdout, os.Stderr))
}

func run(args []string, stdin io.Reader, stdout, stderr io.Writer) int {
	opts, base, err := parseArgs(args)
	if err != nil {
		if errors.Is(err, flag.ErrHelp) {
			usage(stdout)
			return cli.ExitOK
		}
		fmt.Fprintln(stderr, err)
		return cli.ExitUsage
	}
	if opts.showVersion {
		fmt.Fprintf(stdout, "hdsl-tui %s\nterminal protocol %d\n", version, pterm.Protocol)
		return cli.ExitOK
	}

	log := logger{w: stderr, enabled: opts.verbose}
	fail := func(err error) int {
		code, text := cli.Explain(err)
		fmt.Fprintln(stderr, "错误: "+text)
		return code
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	tlsConfig, err := tlsConfigFor(opts.ca, opts.insecure, base, stdin, stderr, log.Logf)
	if err != nil {
		return fail(err)
	}
	httpClient := &http.Client{
		Transport: &http.Transport{TLSClientConfig: tlsConfig, Proxy: http.ProxyFromEnvironment},
		Timeout:   requestTimeout,
	}
	client, err := panel.New(base.String(), httpClient, log.Logf)
	if err != nil {
		fmt.Fprintln(stderr, err)
		return cli.ExitUsage
	}

	health, err := client.Health(ctx)
	if err != nil {
		return fail(err)
	}
	log.Logf("面板 %s 版本 %s，已运行 %d 秒", client.BaseURL(), health.Version, health.UptimeSec)
	if health.Status != "" && health.Status != "ok" {
		fmt.Fprintf(stderr, "警告: 面板自报状态 %q，仍会尝试连接。\n", health.Status)
	}

	username := opts.user
	if username == "" {
		answer, err := termx.ReadLine(stdin, stderr, "面板用户名 [admin]: ")
		if err != nil && !errors.Is(err, io.EOF) {
			fmt.Fprintln(stderr, "错误: 无法读取用户名："+err.Error())
			return cli.ExitUsage
		}
		username = strings.TrimSpace(answer)
		if username == "" {
			username = "admin"
		}
	}
	password, err := termx.ReadPassword(stderr, fmt.Sprintf("面板密码（%s）: ", username))
	if err != nil {
		fmt.Fprintln(stderr, "错误: "+err.Error())
		return cli.ExitUsage
	}
	if _, err := client.Login(ctx, username, password); err != nil {
		return fail(err)
	}
	log.Logf("已登录，会话 Cookie 只保存在内存里")

	developerPassword, err := termx.ReadPassword(stderr, "开发者密码: ")
	if err != nil {
		fmt.Fprintln(stderr, "错误: "+err.Error())
		return cli.ExitUsage
	}
	elevation, err := client.Elevate(ctx, developerPassword)
	if err != nil {
		return fail(err)
	}
	if expiry := elevation.Expiry(); !expiry.IsZero() {
		log.Logf("提权有效至 %s", expiry.Format(time.RFC3339))
	}

	brand, id := normaliseBrand(opts.brand), opts.instance
	if id == "" {
		groups, err := collectGroups(ctx, client, log)
		if err != nil {
			return fail(err)
		}
		choice, err := cli.Pick(stdin, stderr, groups)
		if errors.Is(err, cli.ErrQuit) {
			return cli.ExitOK
		}
		if err != nil {
			return fail(err)
		}
		brand, id = choice.Brand, choice.ID
	}

	terminal, err := client.OpenTerminal(ctx, brand, id, opts.resume)
	if err != nil {
		return fail(err)
	}
	if terminal.Desktop() {
		// A Windows panel has no PTY at all; there is nothing to render.
		fmt.Fprintln(stderr, "错误: "+terminal.Hint)
		return cli.ExitPolicy
	}
	wsURL, err := client.WebSocketURL(terminal.WS)
	if err != nil {
		return fail(err)
	}
	log.Logf("终端已开: instance=%s brand=%s protocol=%d", terminal.Instance, terminal.Brand, terminal.Protocol)

	conn, err := pterm.Dial(ctx, wsURL, client.Session(), tlsConfig)
	if err != nil {
		return fail(err)
	}
	defer conn.Close()

	local := localTerminal(stderr)
	restore := rawMode(local, log)
	defer restore()

	sizeCtx, stopSize := context.WithCancel(ctx)
	defer stopSize()
	go func() {
		if err := local.Poll(sizeCtx, func(size termx.Size) {
			log.Logf("窗口尺寸 %dx%d", size.Cols, size.Rows)
			_ = conn.SendResize(size.Cols, size.Rows)
		}); err != nil {
			log.Logf("尺寸监听结束: %v", err)
		}
	}()
	if size, err := local.Size(); err == nil {
		// The agent needs a size before its first paint, or it guesses 80x24.
		if sendErr := conn.SendResize(size.Cols, size.Rows); sendErr != nil {
			log.Logf("初始尺寸未发送: %v", sendErr)
		}
	} else {
		log.Logf("读不到本地窗口尺寸: %v", err)
	}

	detach := &cli.Detach{}
	result, runErr := conn.Run(ctx, pterm.Options{
		In: os.Stdin, Out: os.Stdout, Logf: log.Logf, Filter: detach.Feed,
		OnReady: func(control pterm.Control) {
			log.Logf("ready: instance=%s brand=%s protocol=%d", control.Instance, control.Brand, control.Protocol)
			if control.Protocol != 0 && control.Protocol != pterm.Protocol {
				fmt.Fprintf(stderr, "警告: 面板的终端协议是 %d，本客户端实现的是 %d，仍会尝试连接。\n",
					control.Protocol, pterm.Protocol)
			}
		},
	})
	restore()

	if runErr != nil {
		return fail(runErr)
	}
	switch result.Command {
	case pterm.CommandDetach:
		fmt.Fprintln(stderr, cli.DetachHint)
		return cli.ExitOK
	case pterm.CommandKill:
		return killTerminal(ctx, client, brand, id, stderr)
	}
	if result.ExitKnown {
		fmt.Fprintf(stderr, "终端已结束（agent 退出码 %d）。\n", result.ExitCode)
		return result.ExitCode
	}
	if result.CloseCode != 0 {
		return fail(&pterm.CloseError{Code: result.CloseCode, Reason: result.CloseReason})
	}
	return fail(errors.New("终端连接在没有收到 exit 帧的情况下结束"))
}

// killTerminal stops the terminal through the REST surface, which is what
// Ctrl-] k promises.
func killTerminal(ctx context.Context, client *panel.Client, brand, id string, stderr io.Writer) int {
	closeCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), requestTimeout)
	defer cancel()
	killed, err := client.CloseTerminal(closeCtx, brand, id)
	if err != nil {
		code, text := cli.Explain(err)
		fmt.Fprintln(stderr, "错误: 停止终端失败："+text)
		return code
	}
	if killed {
		fmt.Fprintln(stderr, "终端已停止。")
	} else {
		fmt.Fprintln(stderr, "没有正在运行的终端。")
	}
	return cli.ExitOK
}

// collectGroups fetches the DSH list plus the three brand lists for the picker.
// A brand that answers with an error is skipped: a panel without that brand's
// directory is normal, not a reason to refuse the whole menu.
func collectGroups(ctx context.Context, client *panel.Client, log logger) ([]cli.Group, error) {
	instances, err := client.Instances(ctx)
	if err != nil {
		return nil, err
	}
	groups := []cli.Group{{Title: "DSH 实例", Items: instances}}
	for _, brand := range brands {
		items, err := client.BrandInstances(ctx, brand)
		if err != nil {
			log.Logf("跳过 %s 实例列表: %v", brand, err)
			continue
		}
		groups = append(groups, cli.Group{Title: brand + " 实例", Brand: brand, Items: items})
	}
	return groups, nil
}

// localTerminal uses the real terminal when there is one and a no-op stand-in
// otherwise, so the client still works with redirected stdin/stdout.
func localTerminal(stderr io.Writer) termx.Raw {
	if !termx.IsTerminal(os.Stdin) || !termx.IsTerminal(os.Stdout) {
		fmt.Fprintln(stderr, "提示: 标准输入/输出不是终端，已跳过 raw 模式（交互式使用请在真正的终端里运行）。")
		return termx.Nop{}
	}
	local, err := termx.Open(os.Stdin, os.Stdout)
	if err != nil {
		fmt.Fprintln(stderr, "提示: 无法初始化终端，已跳过 raw 模式："+err.Error())
		return termx.Nop{}
	}
	return local
}

// rawMode enters raw mode and returns a restore function that is safe to call
// more than once: it runs before the closing messages and again on the way out.
func rawMode(local termx.Raw, log logger) func() {
	restore, err := local.Enter()
	if err != nil {
		log.Logf("进入 raw 模式失败: %v", err)
		return func() {}
	}
	var once sync.Once
	return func() {
		once.Do(func() {
			if err := restore(); err != nil {
				log.Logf("恢复终端失败: %v", err)
			}
		})
	}
}

func normaliseBrand(brand string) string {
	if brand == "dsh" {
		return ""
	}
	return brand
}

func parseArgs(args []string) (options, *url.URL, error) {
	var opts options
	flags := flag.NewFlagSet("hdsl-tui", flag.ContinueOnError)
	flags.SetOutput(io.Discard)
	flags.Usage = func() {}
	flags.StringVar(&opts.instance, "instance", "", "直接进入该实例，不显示菜单")
	flags.StringVar(&opts.brand, "brand", "", "品牌：kimi | opencode | claude-web（默认 dsh）")
	flags.StringVar(&opts.resume, "resume", "", "附着到该 harness 会话")
	flags.StringVar(&opts.user, "user", "", "面板用户名（默认 admin）")
	flags.StringVar(&opts.ca, "ca", "", "额外信任的 CA 证书（PEM 文件）")
	flags.BoolVar(&opts.insecure, "insecure", false, "跳过证书校验（打印指纹并要求确认）")
	flags.BoolVar(&opts.showVersion, "version", false, "打印客户端版本与协议号后退出")
	flags.BoolVar(&opts.verbose, "verbose", false, "把请求细节写到 stderr")

	if err := flags.Parse(args); err != nil {
		if errors.Is(err, flag.ErrHelp) {
			return options{}, nil, flag.ErrHelp
		}
		return options{}, nil, fmt.Errorf("%w\n\n%s", err, usageText)
	}
	if opts.showVersion {
		return opts, nil, nil
	}
	switch opts.brand {
	case "", "dsh", "kimi", "opencode", "claude-web":
	default:
		return options{}, nil, fmt.Errorf("--brand 只能是 kimi | opencode | claude-web（或留空表示 dsh），收到 %q\n\n%s", opts.brand, usageText)
	}
	rest := flags.Args()
	if len(rest) == 0 {
		return options{}, nil, fmt.Errorf("缺少面板地址\n\n%s", usageText)
	}
	if len(rest) > 1 {
		return options{}, nil, fmt.Errorf("只需要一个面板地址，收到 %d 个\n\n%s", len(rest), usageText)
	}
	base, err := url.Parse(strings.TrimRight(strings.TrimSpace(rest[0]), "/"))
	if err != nil || (base.Scheme != "http" && base.Scheme != "https") || base.Host == "" {
		return options{}, nil, fmt.Errorf("面板地址必须是 http:// 或 https:// 开头的完整地址，收到 %q\n\n%s", rest[0], usageText)
	}
	return opts, base, nil
}

func usage(w io.Writer) { fmt.Fprint(w, usageText) }

const usageText = `hdsl-tui — 在终端里进入 HDSL-Web 面板的实例

用法:
  hdsl-tui [选项] <面板地址>

  <面板地址> 例如 https://nas200.top:8443 或 http://127.0.0.1:8080

选项:
  --instance <id>   直接进入该实例，不显示菜单
  --brand <name>    品牌：kimi | opencode | claude-web（默认 dsh）
  --resume <id>     附着到该 harness 会话（kimi / opencode）
  --user <name>     面板用户名，默认 admin；给了就不再提示
  --ca <pem>        额外信任的 CA 证书（PEM 文件）
  --insecure        跳过证书校验：先打印 SHA-256 指纹，要求输入 yes
  --version         打印客户端版本与协议号后退出（不联网）
  --verbose         把请求细节写到 stderr
  -h, --help        显示本帮助

会话中的按键:
  Ctrl-] q          离开客户端，agent 继续在后台跑
  Ctrl-] k          停掉终端（DELETE .../terminal）再退出
  Ctrl-] Ctrl-]     发送一个字面量 Ctrl-]
  其他按键（含 Ctrl-C）原样送给 agent

退出码:
  0  正常结束（含 agent 以 0 退出、Ctrl-] q 离开）
  2  认证或提权失败
  3  无法连接面板 / 连接中断
  4  策略拒绝：面板不在 HTTPS、Web Shell 已关闭、Windows 面板的桌面终端
  5  用法错误
`

type logger struct {
	w       io.Writer
	enabled bool
}

func (l logger) Logf(format string, args ...any) {
	if l.enabled {
		fmt.Fprintf(l.w, "[debug] "+format+"\n", args...)
	}
}
