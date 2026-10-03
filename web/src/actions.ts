import { api } from "./api";
import { beginLaunch, forgetLaunch } from "./launch";
import { patchInstanceLocal, seedTasks, toast } from "./store";
import type { Instance } from "./types";
import { errMsg } from "./utils";

/**
 * 启动流程：
 * 1. 点击瞬间同步 window.open("about:blank")（popup blocker 豁免）并保存引用；
 * 2. POST launch；成功后实例状态乐观置为 STARTING；
 * 3. store 收到 RUNNING + url 的 WS 事件后自动把弹窗导航到 /i/<id>/?token=…。
 */
export async function launchInstance(inst: Instance, onSubmitted?: () => void): Promise<boolean> {
  const win = beginLaunch(inst.id);
  try {
    await api.launch(inst.id);
    patchInstanceLocal(inst.id, { state: "STARTING" });
    toast(
      "info",
      win
        ? `正在启动「${inst.name}」，就绪后将自动打开 dsh 标签页`
        : "正在启动…（请允许浏览器弹窗，以便就绪后自动打开 dsh）",
    );
    onSubmitted?.();
    return true;
  } catch (e) {
    if (win) win.close();
    forgetLaunch(inst.id);
    toast("error", `启动失败：${errMsg(e)}`);
    return false;
  }
}

export async function stopInstance(inst: Instance): Promise<boolean> {
  try {
    await api.stop(inst.id);
    patchInstanceLocal(inst.id, { state: "STOPPING" });
    toast("info", "已发送停止指令");
    return true;
  } catch (e) {
    toast("error", `停止失败：${errMsg(e)}`);
    return false;
  }
}

export async function installVersion(inst: Instance, version: string): Promise<boolean> {
  try {
    await api.install(inst.id, version);
    patchInstanceLocal(inst.id, { state: "INSTALLING" });
    void seedTasks();
    toast("info", `已开始安装 ${version}`);
    return true;
  } catch (e) {
    toast("error", `安装失败：${errMsg(e)}`);
    return false;
  }
}

/** [打开 dsh]：同步开 about:blank 规避弹窗拦截，拿到 url 后导航。 */
export function openDsh(instanceId: string): void {
  let win: Window | null = null;
  try {
    win = window.open("about:blank", "_blank");
  } catch {
    win = null;
  }
  api
    .open(instanceId)
    .then((r) => {
      if (win) {
        win.location.href = r.url;
      } else {
        window.open(r.url, "_blank");
      }
    })
    .catch((e) => {
      if (win) win.close();
      toast("error", `打开失败：${errMsg(e)}`);
    });
}
