package app

import (
	"context"
	"fmt"
	"io"

	"github.com/nas200-timmy/HDSL-WEB/client/hdsl-tui/internal/panel"
	"github.com/nas200-timmy/HDSL-WEB/client/hdsl-tui/internal/pterm"
	"github.com/nas200-timmy/HDSL-WEB/client/hdsl-tui/internal/rtty"
)

// attach opens the terminal for one instance and pumps it until the agent
// exits or the user detaches. It returns to the actions menu either way: the
// login session and the panel connection stay alive.
func (a *App) attach(ctx context.Context, brand string, instance panel.Instance) {
	a.busy("正在打开 " + instanceLabel(instance) + " 的终端…")

	terminal, ok := a.openWithElevation(ctx, brand, instance.ID)
	if !ok {
		return
	}
	if terminal.Desktop() {
		a.message("这个面板没有 PTY", terminal.Hint)
		return
	}
	wsURL, err := a.client.WebSocketURL(terminal.WS)
	if err != nil {
		a.message("终端地址无效", explain(err))
		return
	}
	a.logf("终端已开: instance=%s brand=%s protocol=%d", terminal.Instance, terminal.Brand, terminal.Protocol)
	if terminal.Protocol != 0 && terminal.Protocol != pterm.Protocol {
		a.logf("面板的终端协议是 %d，本客户端实现的是 %d，仍会尝试连接", terminal.Protocol, pterm.Protocol)
	}

	conn, err := pterm.Dial(ctx, wsURL, a.client.Session(), a.tlsConfig)
	if err != nil {
		a.message("无法连接终端", explain(err))
		return
	}
	defer conn.Close()

	// Hand the real screen back to the agent, then take it again afterwards.
	a.screen.Suspend()
	resumed := false
	defer func() {
		if !resumed {
			_ = a.screen.Resume()
		}
	}()
	input, feeder := io.Pipe()
	a.screen.AttachInput(feeder)
	defer func() {
		a.screen.DetachInput()
		_ = feeder.Close()
	}()
	result, runErr := rtty.Run(ctx, a.in, a.out, input, conn, a.logf)
	a.screen.DetachInput()
	_ = feeder.Close()
	if err := a.screen.Resume(); err == nil {
		resumed = true
	}

	if ctx.Err() != nil {
		a.quit = true
		return
	}
	if runErr != nil {
		a.message("终端连接中断", explain(runErr))
		return
	}
	switch result.Command {
	case pterm.CommandDetach:
		a.message("已离开终端", "agent 仍在后台运行。重新进来一次就能接回同一个会话。")
	case pterm.CommandKill:
		a.busy("正在停止终端…")
		killed, err := a.client.CloseTerminal(ctx, brand, instance.ID)
		if err != nil {
			a.message("停止终端失败", explain(err))
			return
		}
		if killed {
			a.message("终端已停止", "agent 进程已结束，实例本身没有受影响。")
		} else {
			a.message("没有终端在跑", "这个实例当前没有正在运行的 Web Shell 终端。")
		}
	default:
		if result.ExitKnown {
			a.message("终端已结束", fmt.Sprintf("agent 自己退出了（退出码 %d）。面板会话仍然登录着。", result.ExitCode))
			return
		}
		if result.CloseCode != 0 {
			a.message("终端连接已关闭", fmt.Sprintf("面板关闭了连接（%d %s）。", result.CloseCode, result.CloseReason))
			return
		}
		a.message("终端连接已结束", "连接在没有 exit 帧的情况下结束了，通常表示面板重启或实例被删除。")
	}
}

// openWithElevation asks the panel for a ticket. A missing or expired
// developer elevation is the one refusal that a password fixes, so it is
// handled here instead of at startup.
func (a *App) openWithElevation(ctx context.Context, brand, id string) (panel.Terminal, bool) {
	for attempt := 0; attempt < 2; attempt++ {
		terminal, err := a.client.OpenTerminal(ctx, brand, id, a.opts.Resume)
		if err == nil {
			return terminal, true
		}
		if attempt == 0 && needsElevation(err) {
			if !a.elevate(ctx) {
				return panel.Terminal{}, false
			}
			continue
		}
		a.message("无法打开终端", explain(err))
		return panel.Terminal{}, false
	}
	a.message("无法打开终端", "面板连续两次拒绝了打开终端的请求。")
	return panel.Terminal{}, false
}

func needsElevation(err error) bool {
	return panel.IsCode(err, "developer_elevation_required") ||
		panel.Message(err) == "developer_elevation_required"
}
