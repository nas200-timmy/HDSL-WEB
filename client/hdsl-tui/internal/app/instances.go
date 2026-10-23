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

// brandOrder is the menu order of the three brand surfaces.
var brandOrder = []string{"kimi", "opencode", "claude-web"}

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
		groups = append(groups, group{Brand: brand, Title: brand + " 实例", Items: items})
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
		index, ok := a.menu(menuPage{
			Title:    "实例：" + instanceLabel(instance),
			Subtitle: fmt.Sprintf("%s · %s · %s", entry.Title, instance.ID, stateText(instance.State)),
			Items: []ui.Item{
				{Label: "进入终端", Note: "attach"},
				{Label: "停止正在运行的终端", Note: "只停终端，不停实例"},
				{Label: "查看详情"},
				{Label: "← 返回实例列表"},
			},
			Hint: "↑/↓ 选择    Enter 确定    Esc 返回    Ctrl-C 取消",
		})
		if !ok || index == 3 {
			return
		}
		switch index {
		case 0:
			a.attach(ctx, entry.Brand, instance)
		case 1:
			a.stopTerminal(ctx, entry.Brand, instance)
		case 2:
			a.instanceDetail(entry, instance)
		}
	}
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
		"面板：" + a.server,
	}
	a.message("实例详情", strings.Join(lines, "\n"))
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
