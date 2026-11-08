package app

import (
	"context"
	"fmt"
	"strings"

	"github.com/nas200-timmy/HDSL-WEB/client/hdsl-tui/internal/panel"
	"github.com/nas200-timmy/HDSL-WEB/client/hdsl-tui/internal/ui"
)

// group is one block of the instance picker: the DSH surface or one brand.
type group struct {
	Brand string
	Title string
	Items []panel.Instance
}

// brandOrder is the menu order of the instance surfaces: the three brand
// categories, then ZCode — an experimental category with its own endpoints
// (`/api/zcode/*`), but the same instance and terminal contract.
//
// Octop is deliberately absent: it has no terminal, so a client whose only job
// is attaching to one would be offering a dead end. Its instances stay on the
// web page.
var brandOrder = []string{"kimi", "opencode", "claude-web", "pi-web", "zcode"}

func brandTitle(brand string) string {
	switch brand {
	case "zcode":
		return "ZCode 实例（实验）"
	case "pi-web":
		return "PI WEB 实例（实验）"
	default:
		return brand + " 实例"
	}
}

func (a *App) loadGroups(ctx context.Context) ([]group, bool) {
	a.busy("正在读取实例列表…")
	instances, err := a.client.Instances(ctx)
	if err != nil {
		a.message("读取实例失败", explain(err))
		return nil, false
	}
	groups := []group{{Title: "DSH 实例", Items: instances}}
	for _, brand := range brandOrder {
		items, err := a.client.BrandInstances(ctx, brand)
		if err != nil {
			// A panel without that brand's directory is normal.
			a.logf("跳过 %s 实例列表: %v", brand, err)
			continue
		}
		groups = append(groups, group{Brand: brand, Title: brandTitle(brand), Items: items})
	}
	return groups, true
}

// pickInstance is the two-level picker: surface first, then the instances on
// that surface, then the per-instance actions. Esc walks back one level at a
// time, all the way to the main menu.
func (a *App) pickInstance(ctx context.Context) {
	for {
		groups, ok := a.loadGroups(ctx)
		if !ok {
			return
		}
		items := make([]ui.Item, 0, len(groups)+1)
		for _, entry := range groups {
			item := ui.Item{Label: entry.Title, Note: fmt.Sprintf("%d 个", len(entry.Items))}
			item.Disabled = len(entry.Items) == 0
			if item.Disabled {
				item.Note = "空"
			}
			items = append(items, item)
		}
		items = append(items, ui.Item{Label: "← 返回主菜单"})

		index, ok := a.menu(menuPage{
			Title:    "选择实例分类",
			Subtitle: "先选分类，再选实例。Esc 逐级返回。",
			Items:    items,
		})
		if !ok || index >= len(groups) {
			return
		}
		a.pickFromGroup(ctx, groups[index])
	}
}

// pickFromGroup shows one surface's instances and the actions of the chosen
// one. It returns when the user backs out to the category list.
func (a *App) pickFromGroup(ctx context.Context, entry group) {
	for {
		items := make([]ui.Item, 0, len(entry.Items)+2)
		for _, instance := range entry.Items {
			items = append(items, ui.Item{
				Label: instanceLabel(instance),
				Note:  fmt.Sprintf("%s · %s", stateText(instance.State), instance.ID),
			})
		}
		items = append(items, ui.Item{Label: "刷新列表"})
		items = append(items, ui.Item{Label: "← 返回分类"})

		subtitle := entry.Title
		if len(entry.Items) == 0 {
			subtitle += "（这个分类下还没有实例）"
		}
		index, ok := a.menu(menuPage{
			Title:    entry.Title,
			Subtitle: subtitle,
			Items:    items,
		})
		if !ok {
			return
		}
		switch {
		case index == len(items)-1:
			return
		case index == len(items)-2:
			refreshed, err := a.refreshGroup(ctx, entry)
			if err != nil {
				a.message("刷新失败", explain(err))
				continue
			}
			entry = refreshed
		default:
			a.instanceActions(ctx, entry, entry.Items[index])
		}
	}
}

func (a *App) refreshGroup(ctx context.Context, entry group) (group, error) {
	a.busy("正在刷新 " + entry.Title + "…")
	var (
		items []panel.Instance
		err   error
	)
	if entry.Brand == "" {
		items, err = a.client.Instances(ctx)
	} else {
		items, err = a.client.BrandInstances(ctx, entry.Brand)
	}
	if err != nil {
		return entry, err
	}
	refreshed := entry
	refreshed.Items = items
	return refreshed, nil
}

func instanceLabel(instance panel.Instance) string {
	if instance.Name != "" {
		return instance.Name
	}
	return instance.ID
}

func stateText(state string) string {
	switch strings.ToLower(state) {
	case "", "unknown":
		return "状态未知"
	case "created":
		return "未启动"
	case "running", "started":
		return "运行中"
	case "stopped", "exited":
		return "已停止"
	case "starting":
		return "启动中"
	case "failed":
		return "失败"
	}
	return state
}

// instanceActions is the per-instance menu. Attaching returns here, not to the
// shell: the login and the panel connection survive an agent exit.
func (a *App) instanceActions(ctx context.Context, entry group, instance panel.Instance) {
	for {
		running := runningState(instance.State)
		items := []ui.Item{
			{Label: "进入终端", Note: "attach"},
			{Label: "启动实例", Note: stateText(instance.State), Disabled: running},
			{Label: "停止实例", Note: stopNote(instance), Disabled: !running && !instance.TerminalOpen},
			{Label: "停止正在运行的终端", Note: "只停终端，不停实例", Disabled: !instance.TerminalOpen},
			{Label: "查看详情"},
			{Label: "← 返回实例列表"},
		}

		index, ok := a.menu(menuPage{
			Title:    "实例：" + instanceLabel(instance),
			Subtitle: fmt.Sprintf("%s · %s · %s", entry.Title, instance.ID, stateText(instance.State)),
			Items:    items,
			Hint:     "↑/↓ 选择    Enter 确定    Esc 返回    Ctrl-C 取消",
		})
		if !ok || index == len(items)-1 {
			return
		}
		switch index {
		case 0:
			a.attach(ctx, entry.Brand, instance)
		case 1:
			a.launchInstance(ctx, entry, instance)
			return
		case 2:
			a.stopInstance(ctx, entry, instance)
			return
		case 3:
			a.stopTerminal(ctx, entry.Brand, instance)
		case 4:
			a.instanceDetail(entry, instance)
		}
	}
}

// runningState is the panel's "there is a process here" answer, in the casing
// the different surfaces use.
func runningState(state string) bool {
	switch strings.ToLower(state) {
	case "running", "started", "ready", "starting", "stopping":
		return true
	}
	return false
}

func stopNote(instance panel.Instance) string {
	if instance.TerminalOpen {
		return "含正在运行的终端"
	}
	return stateText(instance.State)
}

func (a *App) instanceDetail(entry group, instance panel.Instance) {
	brand := entry.Brand
	if brand == "" {
		brand = "dsh"
	}
	lines := []string{
		"名称：" + instanceLabel(instance),
		"ID：" + instance.ID,
		"分类：" + entry.Title,
		"品牌：" + brand,
		"状态：" + stateText(instance.State),
	}
	if instance.Version != "" {
		lines = append(lines, "版本："+instance.Version)
	}
	if port := instance.PortOf(); port != 0 {
		lines = append(lines, fmt.Sprintf("端口：%d", port))
	}
	if instance.URL != "" {
		lines = append(lines, "地址："+instance.URL)
	}
	if instance.Account != "" {
		lines = append(lines, "账户："+instance.Account)
	}
	if instance.TerminalOpen {
		lines = append(lines, "终端：正在运行")
	}
	if instance.Disabled {
		lines = append(lines, "策略：已禁用启动")
	}
	if instance.Error != "" {
		lines = append(lines, "失败原因：", instance.Error)
	}
	lines = append(lines, "面板："+a.server)
	a.message("实例详情", strings.Join(lines, "\n"))
}

// launchInstance starts an instance and returns to the list: the new state is
// what the next refresh reads, not something to guess here.
func (a *App) launchInstance(ctx context.Context, entry group, instance panel.Instance) {
	if instance.Disabled {
		a.message("已被策略禁用", "面板的品牌策略把这个实例标成不可启动。")
		return
	}
	if !a.confirm("启动实例", "启动 "+instanceLabel(instance)+"？") {
		return
	}
	a.busy("正在启动…")
	if err := a.client.LaunchInstance(ctx, entry.Brand, instance.ID); err != nil {
		a.message("启动失败", explain(err))
		return
	}
	a.message("已提交", "启动请求已提交；实例状态会在列表刷新后更新。")
}

func (a *App) stopInstance(ctx context.Context, entry group, instance panel.Instance) {
	if !a.confirm("停止实例", "停止 "+instanceLabel(instance)+"？正在运行的终端也会结束。") {
		return
	}
	a.busy("正在停止…")
	if err := a.client.StopInstance(ctx, entry.Brand, instance.ID); err != nil {
		a.message("停止失败", explain(err))
		return
	}
	a.message("已提交", "停止请求已提交；实例状态会在列表刷新后更新。")
}

func (a *App) stopTerminal(ctx context.Context, brand string, instance panel.Instance) {
	if !a.confirm("停止终端", "停掉 "+instanceLabel(instance)+" 正在运行的 Web Shell 终端？agent 进程会结束。") {
		return
	}
	a.busy("正在停止终端…")
	killed, err := a.client.CloseTerminal(ctx, brand, instance.ID)
	if err != nil {
		a.message("停止失败", explain(err))
		return
	}
	if killed {
		a.message("已停止", "终端已停止，实例本身没有受影响。")
		return
	}
	a.message("没有终端在跑", "这个实例当前没有正在运行的 Web Shell 终端。")
}

// instanceDirect is --instance: skip the menus, attach once, then land on the
// main menu instead of exiting.
func (a *App) instanceDirect(ctx context.Context) {
	instance := panel.Instance{ID: a.opts.Instance}
	if a.opts.Brand == "" || a.opts.Brand == "dsh" {
		instances, err := a.client.Instances(ctx)
		if err == nil {
			for _, candidate := range instances {
				if candidate.ID == a.opts.Instance {
					instance = candidate
					break
				}
			}
		}
	} else {
		instances, err := a.client.BrandInstances(ctx, a.opts.Brand)
		if err == nil {
			for _, candidate := range instances {
				if candidate.ID == a.opts.Instance {
					instance = candidate
					break
				}
			}
		}
	}
	a.attach(ctx, a.opts.Brand, instance)
}
