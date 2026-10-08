import { api } from "./api";
import { beginLaunch, consumeLaunchPopup, forgetLaunch } from "./launch";
import { I18N } from "./i18n";
import { patchInstanceLocal, refreshExternal, seedTasks, toast } from "./store";
import type { Instance } from "./types";
import { errMsg } from "./utils";

/**
 * 启动流程：
 * 1. 点击瞬间同步 window.open("about:blank")（popup blocker 豁免）并保存引用；
 * 2. POST launch；成功后实例状态乐观置为 STARTING（后端回答 installing 时说明
 *    该实例还没装：它会先装再启动，这里就置 INSTALLING，弹窗继续等 RUNNING）；
 * 3. store 收到 RUNNING + url 的 WS 事件后自动把弹窗导航到 /i/<id>/?token=…。
 */
export async function launchInstance(inst: Instance, onSubmitted?: () => void): Promise<boolean> {
  const win = beginLaunch(inst.id);
  try {
    const r = await api.launch(inst.id);
    const installing = r.state === "installing";
    patchInstanceLocal(inst.id, { state: installing ? "INSTALLING" : "STARTING" });
    toast(
      "info",
      installing
        ? `「${inst.name}」尚未安装 dsh，正在自动安装，装好后会直接启动`
        : win
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

/**
 * 启动第三方品牌 / zcode 实例：
 * 1. 点击瞬间同步 beginLaunch(id) 拿 about:blank 弹窗（popup blocker 豁免）；
 * 2. launch 同步阻塞至就绪（最多 60s），返回 running 后取 open url 导航弹窗；
 * 3. 失败关闭弹窗并提示；结束后立即刷新跨品牌实例列表。
 */
export async function launchBrandInstance(
  brand: "kimi" | "opencode" | "zcode",
  entry: { id: string; name: string },
): Promise<boolean> {
  beginLaunch(entry.id);
  try {
    let state: string;
    if (brand === "zcode") {
      const r = await api.launchZcode(entry.id);
      state = r.state;
      if (state === "error") throw new Error(r.error ?? "未知原因");
    } else {
      const r = await api.launchBrand(brand, entry.id);
      state = r.state;
      if (state === "error") throw new Error(r.error ?? "未知原因");
    }
    const target =
      brand === "zcode"
        ? (await api.zcodeOpen(entry.id)).url
        : (await api.openBrand(brand, entry.id)).url;
    const popup = consumeLaunchPopup(entry.id);
    toast("success", I18N["dsh.launch.ready"].replace("%s", entry.name));
    if (popup && !popup.closed) {
      popup.location.href = target;
    } else {
      window.open(target, "_blank", "noopener");
    }
    return true;
  } catch (e) {
    const popup = consumeLaunchPopup(entry.id);
    if (popup && !popup.closed) popup.close();
    forgetLaunch(entry.id);
    toast("error", `启动失败：${errMsg(e)}`);
    return false;
  } finally {
    void refreshExternal();
  }
}

export async function stopBrandInstance(
  brand: "kimi" | "opencode" | "zcode",
  id: string,
): Promise<boolean> {
  try {
    if (brand === "zcode") await api.stopZcode(id);
    else await api.stopBrand(brand, id);
    toast("info", "已发送停止指令");
    void refreshExternal();
    return true;
  } catch (e) {
    toast("error", `停止失败：${errMsg(e)}`);
    return false;
  }
}
