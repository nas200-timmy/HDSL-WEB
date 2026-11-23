import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { api } from "../api";
import {
  installManagedNode, refreshSetupStatus, setSetupCollapsed, setSetupHidden,
  setupSummary, useSetupCollapsed, useSetupHidden, useSetupStatus,
  type SetupItem,
} from "../setup";
import { toast } from "../store";
import { errMsg } from "../utils";
import { CheckCircleIcon, ErrorIcon, InfoIcon, RefreshIcon, StyleIcon, WarningIcon } from "./icons";

/** 首次部署的检查卡：状态来自服务端，动作直达设置页或就地完成。
 *  桌面叠在壁纸左上（避开右下角的启动面板），手机作为首页的第一块内容。 */
export function SetupCard() {
  const hidden = useSetupHidden();
  const collapsed = useSetupCollapsed();
  const status = useSetupStatus();
  const navigate = useNavigate();
  const [busyId, setBusyId] = useState<string | null>(null);
  const [progress, setProgress] = useState("");
  const [showIntro, setShowIntro] = useState(false);
  // 全部就绪时默认只留一行；点开是本次会话的临时展开，不覆盖"收起"的记忆。
  const [forceExpanded, setForceExpanded] = useState(false);

  useEffect(() => {
    void refreshSetupStatus();
    const onFocus = () => void refreshSetupStatus(true);
    window.addEventListener("focus", onFocus);
    return () => window.removeEventListener("focus", onFocus);
  }, []);

  if (hidden || !status) return null;

  const summary = setupSummary(status);
  const worst: SetupItem["tone"] = status.items.some((item) => item.tone === "bad")
    ? "bad"
    : status.items.some((item) => item.tone === "warn") ? "warn" : "ok";
  // 下载源只是建议：官方源能用，只是国内慢。它不该让卡片一直摊着。
  const hasIssue = status.items.some((item) => item.id !== "registry" && item.tone !== "ok");

  if (!forceExpanded && (collapsed || !hasIssue)) {
    return (
      <button
        className={`setup-collapsed tone-${worst}`}
        onClick={() => setForceExpanded(true)}
        title="展开部署检查"
      >
        <span className="setup-dot" />
        <span>{summary}</span>
        <span className="setup-collapsed-hint">展开</span>
      </button>
    );
  }

  const installNode = async () => {
    if (busyId) return;
    setBusyId("node");
    setProgress("正在准备…");
    try {
      await installManagedNode(setProgress);
      await refreshSetupStatus(true);
      toast("success", "Node.js 已装好，可以创建实例了");
    } catch (e) {
      toast("error", `安装失败：${errMsg(e)}`);
    } finally {
      setBusyId(null);
      setProgress("");
    }
  };

  const enableTls = async () => {
    if (busyId) return;
    setBusyId("tls");
    try {
      const result = await api.enableSelfSignedTls();
      if (result.ok) {
        toast("success", "已生成自签名证书，正在切到 HTTPS");
        window.location.href = window.location.href.replace(/^http:/, "https:");
      } else {
        toast("error", result.error ?? "生成证书失败");
      }
    } catch (e) {
      toast("error", `生成证书失败：${errMsg(e)}`);
    } finally {
      setBusyId(null);
    }
  };

  const actionOf = (item: SetupItem) => {
    if (item.unknown || item.tone === "ok") return null;
    switch (item.id) {
      case "node":
        return (
          <button className="btn btn-raised setup-action" disabled={busyId !== null} onClick={() => void installNode()}>
            {busyId === "node" ? "安装中…" : "一键安装"}
          </button>
        );
      case "registry":
        return (
          <button className="btn btn-text setup-action" onClick={() => navigate("/settings?tab=download-source")}>
            去设置
          </button>
        );
      case "tls":
        return (
          <span className="setup-action-pair">
            <button className="btn btn-text" onClick={() => navigate("/settings?tab=tls")}>
              证书设置
            </button>
            <button className="btn btn-raised" disabled={busyId !== null} onClick={() => void enableTls()}>
              {busyId === "tls" ? "生成中…" : "生成自签"}
            </button>
          </span>
        );
      case "developer":
        return (
          <button className="btn btn-text setup-action" onClick={() => navigate("/settings?tab=developer")}>
            去设置
          </button>
        );
      default:
        return null;
    }
  };

  const iconOf = (tone: SetupItem["tone"]) => {
    if (tone === "ok") return <CheckCircleIcon size={16} />;
    if (tone === "warn") return <WarningIcon size={16} />;
    return <ErrorIcon size={16} />;
  };

  return (
    <section className="setup-card" aria-label="部署检查">
      <div className="setup-head">
        <span className={`setup-dot tone-${worst}`} />
        <span className="setup-title">{summary}</span>
        <span className="setup-head-actions">
          <button
            className="setup-icon-btn"
            title="重新检查"
            onClick={() => void refreshSetupStatus(true)}
          >
            <RefreshIcon size={15} />
          </button>
          <button
            className="setup-icon-btn"
            title="收成一行"
            onClick={() => {
              setSetupCollapsed(true);
              setForceExpanded(false);
            }}
          >
            −
          </button>
        </span>
      </div>

      {status.security && (
        <div className={`setup-security ${status.security.level}`}>
          <WarningIcon size={15} />
          <span>{status.security.text}</span>
        </div>
      )}

      <ul className="setup-items">
        {status.items.map((item) => (
          <li key={item.id} className={`setup-item tone-${item.tone}`}>
            <span className="setup-item-icon">{iconOf(item.tone)}</span>
            <span className="setup-item-text">
              <span className="setup-item-title">{item.title}</span>
              <span className="setup-item-detail">{item.detail}</span>
              {item.id === "node" && busyId === "node" && progress && (
                <span className="setup-item-progress">{progress}</span>
              )}
            </span>
            {actionOf(item)}
          </li>
        ))}
      </ul>

      <div className="setup-intro">
        <button className="setup-intro-toggle" onClick={() => setShowIntro((value) => !value)}>
          <InfoIcon size={14} />
          了解面板
          <span>{showIntro ? "收起" : "展开"}</span>
        </button>
        {showIntro && (
          <ul className="setup-intro-list">
            <li>
              <button onClick={() => navigate("/settings?tab=developer")}>
                <b>Agent 票</b>：给自动化用的较小钥匙，只做限定范围的事，不必交出开发者密码。
              </button>
            </li>
            <li>
              <button onClick={() => navigate("/settings?tab=developer")}>
                <b>开发者守卫</b>：面板的总闸门。开成「全部 API」时，连实例网页和终端都要先输开发者密码。
              </button>
            </li>
            <li>
              <button onClick={() => navigate("/instances?view=brands")}>
                <b>品牌实例</b>：一个面板可以同时跑 Kimi Code、Octop、Hermes 等工具，各自独立端口和账号。
              </button>
            </li>
            <li>
              <button onClick={() => navigate("/instances?view=mc")}>
                <b>Minecraft 服务器</b>：原版 / Forge / Fabric、导入整合包、种子地图、批量转码。
              </button>
            </li>
            <li>
              <button onClick={() => navigate("/settings?tab=appearance")}>
                <b>外观</b>：主题明暗、主题色、壁纸都能换。
              </button>
            </li>
          </ul>
        )}
      </div>

      <div className="setup-foot">
        <button className="btn btn-text" onClick={() => navigate("/settings?tab=appearance")}>
          <StyleIcon size={15} />
          换个外观
        </button>
        <button
          className="btn btn-text dim"
          onClick={() => {
            setSetupHidden(true);
            toast("info", "已隐藏。可以在 设置 → 通用 里重新打开");
          }}
        >
          不再显示
        </button>
      </div>
    </section>
  );
}