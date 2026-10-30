import { useEffect, useState } from "react";
import type { CSSProperties } from "react";
import { api, type McTerms } from "../api";
import { isElevationRequired, requestDeveloperPassword } from "../elevation";
import { I18N } from "../i18n";
import { refreshMcFeature, useMcFeature } from "../mcFeature";
import { errMsg } from "../utils";
import { InfoIcon } from "./icons";
import notesText from "../minecraft/notes-v1.txt?raw";

/**
 * 「关于」页里的 MC 段：只在功能开启后出现。
 *
 * 两段文本分开，因为它们性质不同：
 * - 说明（说人话版）随前端打包：不联网、不要提权，讲的是操作事实；
 * - 使用条款原文走 `/api/mc/terms`，和开启向导读的是同一份文本与哈希——它有法律
 *   效力，所以不复制一份到前端，否则两份会漂移。
 *
 * 功能开关本身要先读 `/api/mc/feature`；在 `developer_guard.scope: all` 的部署里
 * 这条读也要提权，所以本段和「实例列表」里的 MC 入口一样，未提权时不会出现。
 */
export function McAboutCard() {
  const feature = useMcFeature();
  const [terms, setTerms] = useState<McTerms | null>(null);
  const [termsBusy, setTermsBusy] = useState(false);
  const [termsError, setTermsError] = useState<string | null>(null);

  useEffect(() => {
    void refreshMcFeature();
  }, []);

  if (!feature?.enabled) return null;

  const loadTerms = async () => {
    if (terms || termsBusy) return;
    setTermsBusy(true);
    setTermsError(null);
    try {
      setTerms(await api.mc.terms());
    } catch (e) {
      if (!isElevationRequired(e)) {
        setTermsError(errMsg(e));
      } else if (await requestDeveloperPassword()) {
        try {
          setTerms(await api.mc.terms());
        } catch (retry) {
          setTermsError(errMsg(retry));
        }
      } else {
        setTermsError(I18N["about.mc.terms.cancelled"]);
      }
    } finally {
      setTermsBusy(false);
    }
  };

  return (
    <>
      <div className="section-caption" style={{ marginTop: 18 }}>
        <InfoIcon size={16} />
        {I18N["about.mc"]}
      </div>
      <div className="card">
        <div className="comp-row">
          <span className="comp-label">{I18N["about.mc.state"]}</span>
          <span className="comp-value">{I18N["about.mc.state.on"]}</span>
        </div>
        <div style={{ padding: "2px 8px 6px" }}>
          <p className="dim" style={{ fontSize: 12, margin: "0 0 8px", lineHeight: 1.6 }}>
            {I18N["about.mc.intro"]}
          </p>
          <details>
            <summary style={{ cursor: "pointer", fontSize: 13 }}>{I18N["about.mc.notes.open"]}</summary>
            <div style={BOX_STYLE}>{notesText}</div>
          </details>
          <details
            style={{ marginTop: 8 }}
            onToggle={(event) => {
              if (event.currentTarget.open) void loadTerms();
            }}
          >
            <summary style={{ cursor: "pointer", fontSize: 13 }}>
              {I18N["about.mc.terms.open"]}
              {terms ? `（v${terms.version}，sha256:${terms.hash.slice(7, 19)}…）` : ""}
            </summary>
            {termsBusy && <div className="dim" style={{ fontSize: 12, marginTop: 6 }}>正在读取…</div>}
            {termsError && (
              <div style={{ color: "var(--monet-error)", fontSize: 12, marginTop: 6 }}>{termsError}</div>
            )}
            {terms && <div style={BOX_STYLE}>{terms.text}</div>}
          </details>
        </div>
      </div>
    </>
  );
}

const BOX_STYLE: CSSProperties = {
  maxHeight: 320,
  overflow: "auto",
  border: "1px solid var(--monet-outline-variant)",
  borderRadius: 4,
  padding: 8,
  marginTop: 6,
  fontSize: 12,
  lineHeight: 1.65,
  whiteSpace: "pre-wrap",
  overflowWrap: "anywhere",
  fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
};
