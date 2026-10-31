import { useEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { api, type McEula, type McTerms } from "../api";
import { isElevationRequired, requestDeveloperPassword } from "../elevation";
import { refreshMcFeature, setMcFeature, useMcFeature } from "../mcFeature";
import { toast } from "../store";
import { errMsg } from "../utils";

/// 设置 → 品牌实例 里的「MC 服务端」开关。
///
/// 功能默认关闭；开启是唯一需要读 EULA 的动作（`docs/minecraft.md` §5）。这一块
/// 以前挂在 `/mc` 页面顶部，但开关是「一年动一次」的东西，和品牌策略同页更合适：
/// 日常运营者不该在实例列表里看到它。
///
/// 开启成功后广播到 [useMcFeature]，实例列表据此长出「MC 服务器实例」入口。
export function McFeatureCard() {
  const feature = useMcFeature();
  const nav = useNavigate();
  const [eula, setEula] = useState<McEula | null>(null);
  const [terms, setTerms] = useState<McTerms | null>(null);
  const [eulaError, setEulaError] = useState<string | null>(null);
  const [wizard, setWizard] = useState(false);
  const [scrolledToEnd, setScrolledToEnd] = useState(false);
  const [eulaAccepted, setEulaAccepted] = useState(false);
  const [termsAccepted, setTermsAccepted] = useState(false);
  const [busy, setBusy] = useState(false);
  const [enableError, setEnableError] = useState<string | null>(null);
  const [tick, setTick] = useState(0);

  useEffect(() => {
    void refreshMcFeature();
  }, []);

  const locked = feature?.locked === true;
  useEffect(() => {
    if (!locked) return;
    const timer = window.setInterval(() => setTick((value) => value + 1), 1000);
    return () => window.clearInterval(timer);
  }, [locked]);

  const remaining = useMemo(() => {
    if (!feature?.locked) return 0;
    // tick 只是心跳；剩余秒数以服务端回答为锚，本地按墙钟往前推。
    return Math.max(0, feature.lockRemainingSeconds - tick);
  }, [feature, tick]);

  const withElevation = async <T,>(call: () => Promise<T>): Promise<T> => {
    try {
      return await call();
    } catch (e) {
      if (!isElevationRequired(e)) throw e;
      if (!(await requestDeveloperPassword())) throw e;
      return await call();
    }
  };

  const openWizard = async () => {
    setWizard(true);
    setEnableError(null);
    setEula(null);
    setTerms(null);
    setEulaError(null);
    setScrolledToEnd(false);
    setEulaAccepted(false);
    setTermsAccepted(false);
    try {
      const [eulaAnswer, termsAnswer] = await Promise.all([api.mc.eula(), api.mc.terms()]);
      setEula(eulaAnswer);
      setTerms(termsAnswer);
    } catch (e) {
      const message = errMsg(e);
      // 拉不到 EULA 也就下载不了服务端；不给「离线导入」这种假出路。
      setEulaError(
        message.includes("eula_unavailable")
          ? "网络问题：连不上 Mojang，拉不到 EULA（服务端也下载不了）。检查网络或代理后重试。"
          : message,
      );
    }
  };

  const enable = async () => {
    if (!eula || !terms) return;
    setBusy(true);
    setEnableError(null);
    try {
      const next = await withElevation(() =>
        api.mc.enable({
          eulaAcknowledged: eulaAccepted,
          termsAcknowledged: termsAccepted,
          eulaHash: eula.hash,
          termsVersion: terms.version,
          termsTextHash: terms.hash,
          sawDeclineOption: true,
          uiVersion: "1.0.0",
        }),
      );
      setMcFeature(next);
      setWizard(false);
      toast("success", "MC 服务端功能已开启：入口在「实例列表 → 品牌实例」下面");
      await refreshMcFeature();
    } catch (e) {
      // Inline as well as a toast: this is the button people press to turn the
      // feature on, and a toast that has already faded is indistinguishable from a
      // button that did nothing.
      setEnableError(errMsg(e));
      toast("error", errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  const stopFeature = async () => {
    const phrase = window.prompt("这会停掉所有 MC 服务器，并在 1 小时内不能重新开启。\n输入 STOP 继续：");
    if (phrase !== "STOP") return;
    setBusy(true);
    try {
      setMcFeature(await withElevation(() => api.mc.stopFeature()));
      setTick(0);
      await refreshMcFeature();
    } catch (e) {
      toast("error", errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  const confirmClose = async () => {
    setBusy(true);
    try {
      setMcFeature(await withElevation(() => api.mc.confirmClose()));
      await refreshMcFeature();
    } catch (e) {
      toast("error", errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  const keepFeature = async () => {
    setBusy(true);
    try {
      setMcFeature(await withElevation(() => api.mc.cancelClose()));
      await refreshMcFeature();
    } catch (e) {
      toast("error", errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  const enabled = feature?.enabled === true;

  return (
    <div className="card">
      <div className="comp-row">
        <span className="comp-label">MC 服务端（第三方功能）</span>
        <span className="comp-value">
          {enabled ? (
            <span className={`tag ${locked ? "plain" : "running"}`}>{locked ? "冷却中" : "已开启"}</span>
          ) : (
            <span className="tag plain">默认关闭</span>
          )}
        </span>
      </div>
      <p style={{ fontSize: 12, color: "var(--monet-on-surface-variant)", padding: "4px 8px 0", lineHeight: 1.6 }}>
        把原版 Minecraft 服务端当成一个实例来管：版本、端口、进程、控制台、世界与备份。
        关闭时 `/api/mc/*` 全部 404、不开任何端口，面板与平时没有区别。
        {enabled && " 实例入口在「实例列表 → 品牌实例」下面。"}
      </p>

      {locked && (
        <div style={{ padding: "8px 8px 0", display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap", fontSize: 13 }}>
          <b>已按下停止功能</b>
          <span>{formatDuration(remaining)} 后才能启服 / 重新开启</span>
          {feature?.keepFeature == null && (
            <>
              <button className="btn btn-raised" disabled={busy} onClick={() => void keepFeature()}>
                我不想关掉功能
              </button>
              <button className="btn btn-raised danger" disabled={busy} onClick={() => void confirmClose()}>
                确认关闭功能
              </button>
            </>
          )}
          {feature?.keepFeature === true && <span className="dim">已选择保留功能，倒计时结束才能启服。</span>}
          {feature?.keepFeature === false && <span className="dim">功能已关闭，冷却结束后才能重新开启。</span>}
        </div>
      )}

      <div style={{ display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap", padding: "10px 8px 4px" }}>
        {enabled ? (
          <>
            <button className="btn btn-raised" onClick={() => nav("/instances?view=mc")}>
              打开 MC 实例列表
            </button>
            <button className="btn btn-text danger" disabled={busy || locked} onClick={() => void stopFeature()}>
              停止 MC 功能
            </button>
            <span style={{ fontSize: 12, color: "var(--monet-on-surface-variant)" }}>
              服务端程序不随面板分发，首次安装时从 Mojang 官方下载。
            </span>
          </>
        ) : locked ? (
          <span style={{ fontSize: 12, color: "var(--monet-on-surface-variant)" }}>
            功能已关闭，{formatDuration(remaining)} 后才能重新开启。重启面板进程会清掉这段冷却。
          </span>
        ) : (
          <>
            <button className="btn btn-raised" disabled={busy} onClick={() => void openWizard()}>
              开启功能
            </button>
            <span style={{ fontSize: 12, color: "var(--monet-on-surface-variant)" }}>
              需要开发者密码；开启前要读 Mojang 的 EULA 与面板自己的使用条款。
            </span>
          </>
        )}
      </div>

      {wizard && !enabled && (
        <EnableWizard
          eula={eula}
          terms={terms}
          eulaError={eulaError}
          scrolledToEnd={scrolledToEnd}
          onScroll={(atEnd) => setScrolledToEnd(atEnd)}
          eulaAccepted={eulaAccepted}
          termsAccepted={termsAccepted}
          onEula={setEulaAccepted}
          onTerms={setTermsAccepted}
          busy={busy}
          enableError={enableError}
          onEnable={() => void enable()}
          onRetry={() => void openWizard()}
          onDecline={() => {
            void api.mc.decline().catch(() => undefined);
            setWizard(false);
            toast("info", "已保持关闭");
          }}
        />
      )}
    </div>
  );
}

function formatDuration(seconds: number): string {
  const total = Math.max(0, Math.floor(seconds));
  const minutes = Math.floor(total / 60);
  return minutes > 0 ? `${minutes} 分 ${total % 60} 秒` : `${total} 秒`;
}

/// 开启向导：读完 EULA（滚到底才能勾）+ 双确认。
///
/// EULA 由服务端拉取并**序列化**成纯文本（`McHtml`）：正文在 Mojang 页面中间，
/// 前后是导航与 cookie 提示，直接贴原文就是一堆标签。
function EnableWizard({
  eula,
  terms,
  eulaError,
  scrolledToEnd,
  onScroll,
  eulaAccepted,
  termsAccepted,
  onEula,
  onTerms,
  busy,
  enableError,
  onEnable,
  onRetry,
  onDecline,
}: {
  eula: McEula | null;
  terms: McTerms | null;
  eulaError: string | null;
  scrolledToEnd: boolean;
  onScroll: (atEnd: boolean) => void;
  eulaAccepted: boolean;
  termsAccepted: boolean;
  onEula: (value: boolean) => void;
  onTerms: (value: boolean) => void;
  busy: boolean;
  enableError: string | null;
  onEnable: () => void;
  onRetry: () => void;
  onDecline: () => void;
}) {
  const boxRef = useRef<HTMLDivElement | null>(null);
  const getScrollReport = useRef(onScroll);
  getScrollReport.current = onScroll;

  // 条款短到不需要滚动时，`onScroll` 永远不会触发，两个勾选框就永远是灰的——
  // 整个向导看上去是个摆设。量一次高度，装得下就算读过了。
  useEffect(() => {
    const box = boxRef.current;
    if (box && box.scrollHeight <= box.clientHeight + 4) getScrollReport.current(true);
  }, [eula]);

  return (
    <div style={{ padding: "4px 8px 8px", display: "flex", flexDirection: "column", gap: 10 }}>
      <b>开启 MC 服务端功能</b>
      {eulaError && (
        <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
          <div style={{ color: "var(--monet-error)" }}>{eulaError}</div>
          <button className="btn btn-raised" disabled={busy} onClick={onRetry}>
            重试
          </button>
        </div>
      )}
      {eula && (
        <>
          <div
            ref={boxRef}
            style={{
              height: 260,
              overflow: "auto",
              border: "1px solid var(--monet-outline-variant)",
              padding: 8,
              fontSize: 12,
              lineHeight: 1.6,
              whiteSpace: "pre-wrap",
            }}
            onScroll={(event) => {
              const el = event.currentTarget;
              onScroll(el.scrollTop + el.clientHeight >= el.scrollHeight - 4);
            }}
          >
            {eula.text}
          </div>
          <label style={{ display: "flex", gap: 8, alignItems: "flex-start", fontSize: 13 }}>
            <input type="checkbox" disabled={!scrolledToEnd} checked={eulaAccepted} onChange={(e) => onEula(e.target.checked)} />
            <span>
              我已阅读并同意 Mojang 的《最终用户许可协议》（sha256:{eula.hash.slice(7, 19)}…，拉取于{" "}
              {eula.fetchedAt}）。
            </span>
          </label>
          <label style={{ display: "flex", gap: 8, alignItems: "flex-start", fontSize: 13 }}>
            <input type="checkbox" disabled={!scrolledToEnd} checked={termsAccepted} onChange={(e) => onTerms(e.target.checked)} />
            <span>
              我已阅读《MC 服务端管理功能使用条款》v{terms?.version ?? 3}
              （sha256:{terms?.hash.slice(7, 19) ?? "…"}…），理解开公网端口、插件代码执行与备份责任由我承担。
            </span>
          </label>
          {terms && (
            <details>
              <summary className="dim" style={{ cursor: "pointer", fontSize: 12 }}>查看使用条款全文</summary>
              <div
                style={{
                  maxHeight: 240,
                  overflow: "auto",
                  border: "1px solid var(--monet-outline-variant)",
                  padding: 8,
                  marginTop: 6,
                  fontSize: 12,
                  lineHeight: 1.6,
                  whiteSpace: "pre-wrap",
                }}
              >
                {terms.text}
              </div>
            </details>
          )}
          {enableError && <div style={{ color: "var(--monet-error)" }}>{enableError}</div>}
          {!scrolledToEnd && (
            <div className="dim" style={{ fontSize: 12 }}>
              读到最后一页才能勾选——条款框滚到底即可（短到不需要滚动时自动算读过）。
            </div>
          )}
          <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
            <button className="btn btn-raised" disabled={busy || !eulaAccepted || !termsAccepted} onClick={onEnable}>
              开启
            </button>
          </div>
        </>
      )}
      {!eula && !eulaError && (
        <div className="dim">正在拉取官方 EULA…（要连 www.minecraft.net，最长会等 20 秒）</div>
      )}
      {/* 退出这条路必须在任何状态下都在：拉不到 EULA 时，向导里一个能点的东西都没有。 */}
      <button className="btn btn-text" onClick={onDecline}>
        我已知晓风险，暂不启用
      </button>
    </div>
  );
}
