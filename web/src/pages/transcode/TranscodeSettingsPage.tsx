import { useEffect, useState } from "react";
import { api, type TranscodeCapabilities, type TranscodeInstallJob, type TranscodeRuntime, type TranscodeSourceStatus } from "../../api";
import { refreshTranscodeFeature, setTranscodeFeature, useTranscodeFeature } from "../../transcodeFeature";
import { errMsg } from "../../utils";
import { toast } from "../../store";
import { ProgressBar } from "../../components/ProgressBar";
import { HelpNote } from "./common";


export function TranscodeSettingsPage() {
  const feature = useTranscodeFeature();
  const [capabilities, setCapabilities] = useState<TranscodeCapabilities | null>(null);
  const [runtime, setRuntime] = useState<TranscodeRuntime | null>(null);
  const [sourceStatus, setSourceStatus] = useState<TranscodeSourceStatus | null>(null);
  const [preferredMajor, setPreferredMajor] = useState("auto");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [policy, setPolicy] = useState("ask");
  const [timeoutMinutes, setTimeoutMinutes] = useState(10);
  const [saving, setSaving] = useState(false);
  const [installKind, setInstallKind] = useState<"static" | "package">("package");
  const [installUrl, setInstallUrl] = useState("");
  const [installSha, setInstallSha] = useState("");
  const [installVersion, setInstallVersion] = useState("");
  const [installJob, setInstallJob] = useState<TranscodeInstallJob | null>(null);
  const [probeEncoderName, setProbeEncoderName] = useState("libx264");
  const [probeResult, setProbeResult] = useState<{ ok: boolean; encoder: string; exitCode: number; output: string } | null>(null);
  const [probing, setProbing] = useState(false);

  const load = async () => {
    setBusy(true);
    setError(null);
    try {
      const [nextCapabilities, nextRuntime] = await Promise.all([
        api.transcode.capabilities(),
        api.transcode.runtime(preferredMajor),
      ]);
      setCapabilities(nextCapabilities);
      setRuntime(nextRuntime);
      setSourceStatus(await api.transcode.runtimeSource().catch(() => null));
      await refreshTranscodeFeature();
    } catch (e) {
      setError(errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  useEffect(() => {
    void load();
  }, [preferredMajor]);

  useEffect(() => {
    if (!feature) return;
    setPolicy(feature.failurePolicy);
    setTimeoutMinutes(feature.failureTimeoutMinutes);
    setBinaryMirror(feature.binaryMirror ?? "");
    setSourceMirror(feature.sourceMirror ?? "");
  }, [feature?.failurePolicy, feature?.failureTimeoutMinutes, feature?.binaryMirror,
      feature?.sourceMirror]);

  useEffect(() => {
    if (!installJob || installJob.state !== "running") return;
    const timer = window.setInterval(() => {
      void api.transcode.runtimeJob(installJob.id).then((job) => {
        setInstallJob(job);
        if (job.state === "done") void load();
      }).catch(() => undefined);
    }, 1000);
    return () => window.clearInterval(timer);
  }, [installJob]);

  const startInstall = async () => {
    try {
      setInstallJob(await api.transcode.runtimeInstall({
        kind: installKind,
        url: installKind === "package" ? undefined : installUrl,
        sha256: installKind === "package" ? undefined : installSha,
        version: installVersion || undefined,
      }));
    } catch (e) {
      toast("error", errMsg(e));
    }
  };

  const startRuntimeInstall = async (body: {
    mode: "auto" | "artifact" | "source";
    id?: string;
    major?: string;
    features?: string[];
  }) => {
    try {
      setInstallJob(await api.transcode.runtimeInstallArtifact(body));
    } catch (e) {
      toast("error", errMsg(e));
    }
  };

  const selectRuntime = async (version: string) => {
    try {
      setRuntime(await api.transcode.runtimeSelect(version));
      toast("success", "已切换 ffmpeg 版本");
      await load();
    } catch (e) {
      toast("error", errMsg(e));
    }
  };

  const rollbackRuntime = async () => {
    try {
      setRuntime(await api.transcode.runtimeRollback());
      toast("success", "已回滚到上一个版本");
      await load();
    } catch (e) {
      toast("error", errMsg(e));
    }
  };

  const deleteRuntime = async (id: string) => {
    try {
      await api.transcode.runtimeDelete(id);
      toast("success", "已删除该版本");
      await load();
    } catch (e) {
      toast("error", errMsg(e));
    }
  };

  const [binaryMirror, setBinaryMirror] = useState("");
  const [sourceMirror, setSourceMirror] = useState("");

  /// 保存下载源。接口把失败策略和源放在同一个 PATCH 里，所以顺带带上当前策略，
  /// 免得保存源把未保存的策略改动带走。
  const saveSources = async () => {
    setSaving(true);
    try {
      const next = await api.transcode.updateSettings({
        failurePolicy: policy,
        failureTimeoutMinutes: timeoutMinutes,
        binaryMirror,
        sourceMirror,
      });
      setTranscodeFeature(next);
      toast("success", "下载源已保存");
    } catch (e) {
      toast("error", errMsg(e));
    } finally {
      setSaving(false);
    }
  };

  const savePolicy = async () => {
    setSaving(true);
    try {
      const next = await api.transcode.updateSettings({
        failurePolicy: policy,
        failureTimeoutMinutes: timeoutMinutes,
      });
      setTranscodeFeature(next);
      toast("success", "失败策略已保存");
    } catch (e) {
      toast("error", errMsg(e));
    } finally {
      setSaving(false);
    }
  };

  return (
    <>
      {error && (
        <div className="card">
          <div className="comp-row">
            <span className="comp-label">读取失败</span>
            <span className="comp-value" style={{ color: "var(--monet-error)" }}>{error}</span>
          </div>
        </div>
      )}

      <HelpNote title="设置项怎么理解">
        <ul>
          <li><b>失败策略</b>：<b>询问</b>每次失败都等你决定；<b>超时自动降级</b>等到超时时间后自动换等价编码器；<b>完全自动化</b>失败立即降级、不再询问。</li>
          <li><b>并发 / 队列上限</b>来自 <span className="mono">server.yaml</span> 的 <span className="mono">transcode:</span> 段或 <span className="mono">HDSL_TRANSCODE_*</span> 环境变量，改完要重启面板。</li>
          <li><b>媒体根</b>是转码读写文件的范围；<b>ffmpeg 路径</b>留空就用镜像/系统自带的。</li>
          <li>关闭转码功能后 <span className="mono">/api/transcode/*</span> 返回 404，导航入口也会消失；数据与预设保留。</li>
          <li>「探测编码器」只跑 1 帧试编码，用来区分“参数写错”和“硬件根本不可用”。</li>
        </ul>
      </HelpNote>

      <div className="card">
        <div className="comp-row">
          <span className="comp-label">ffmpeg 运行时</span>
          <span className="comp-value">
            {runtime?.current
              ? <span className="tag running">{runtime.current.version}</span>
              : <span className="tag plain">未托管</span>}
          </span>
        </div>
        {runtime && (
          <div className="comp-row">
            <span className="comp-label">宿主</span>
            <span className="comp-value mono" style={{ fontSize: 12 }}>
              {runtime.host.os}/{runtime.host.arch}/{runtime.host.libc}
              {runtime.host.libcVersion ? ` ${runtime.host.libcVersion}` : ""}
            </span>
          </div>
        )}
        {runtime && (
          <div className="comp-row">
            <span className="comp-label">ffmpeg 版本线</span>
            <span className="comp-value">
              <select className="input" style={{ width: "auto", minWidth: 180 }}
                      value={preferredMajor}
                      onChange={(e) => setPreferredMajor(e.target.value)}>
                <option value="auto">自动（优先最高可用版本线）</option>
                {runtime.majors.map((major) => (
                  <option key={major} value={major}>{major}</option>
                ))}
              </select>
            </span>
          </div>
        )}
        {runtime?.current && (
          <div className="comp-row">
            <span className="comp-label">当前</span>
            <span className="comp-value" style={{ fontSize: 12 }}>
              {runtime.current.id}
              {runtime.current.major ? ` · ${runtime.current.major}` : ""} · {runtime.current.source} · {runtime.current.license}
              {runtime.current.featuresProbed.length > 0
                ? ` · ${runtime.current.featuresProbed.slice(0, 6).join(" / ")}`
                : " · 未探测到声明特性"}
            </span>
          </div>
        )}
        {runtime?.recommended && (
          <div style={{ display: "flex", gap: 8, padding: "8px", flexWrap: "wrap", alignItems: "center" }}>
            <button className="btn btn-raised"
                    disabled={installJob?.state === "running"}
                    onClick={() => void startRuntimeInstall({ mode: "auto", major: preferredMajor })}>
              安装推荐版本
            </button>
            <span style={{ fontSize: 12, color: "var(--monet-on-surface-variant)" }}>
              {runtime.recommended.entry.major ? `${runtime.recommended.entry.major} · ` : ""}
              {runtime.recommended.entry.ffmpegVersion} · {runtime.recommended.reason}
            </span>
          </div>
        )}
        {runtime && !runtime.recommended && (
          <p style={{ margin: 0, padding: "0 8px 8px", fontSize: 12, color: "var(--monet-on-surface-variant)" }}>
            {runtime.recommendationError
              ? `没有可安装的推荐版本：${runtime.recommendationError}`
              : "内置目录还没有匹配当前平台的条目。"}
            可以用 `transcode.ffmpeg_catalog` 指向一份已验证的本地目录，或在下面用自定义 URL。
          </p>
        )}
        {runtime && (
          <div style={{ padding: "0 8px 8px", display: "grid", gap: 6,
                        borderTop: "1px solid var(--monet-outline-variant)" }}>
            <div style={{ display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap", paddingTop: 8 }}>
              <span style={{ fontSize: 12, fontWeight: 600 }}>源码构建</span>
              <button className="btn btn-raised"
                      disabled={installJob?.state === "running" || !sourceStatus?.buildable}
                      onClick={() => void startRuntimeInstall({ mode: "source", major: preferredMajor })}>
                构建 {preferredMajor === "auto" ? "自动版本线" : preferredMajor}
              </button>
              <span style={{ fontSize: 12, color: "var(--monet-on-surface-variant)" }}>
                {sourceStatus
                  ? sourceStatus.toolchain.usable
                    ? `${sourceStatus.toolchain.portable ? "便携" : "系统"}工具链 · ${sourceStatus.toolchain.binDir || "PATH"}`
                    : sourceStatus.portableFallback
                      ? "缺少系统工具链；开始构建时会下载固定版本的便携 MinGW"
                      : "缺少构建工具链"
                  : "读取构建环境…"}
              </span>
            </div>
            {sourceStatus && (
              <>
                <div style={{ display: "flex", gap: 6, flexWrap: "wrap" }}>
                  {sourceStatus.toolchain.features.length > 0
                    ? sourceStatus.toolchain.features.map((feature) => (
                      <span key={feature} className="tag running" style={{ fontSize: 11 }}>{feature}</span>
                    ))
                    : <span style={{ fontSize: 11, color: "var(--monet-on-surface-variant)" }}>
                        未检测到外部编解码开发包：将做最小构建（无 x264/x265 等）
                      </span>}
                </div>
                {sourceStatus.toolchain.missing.length > 0 && (
                  <span style={{ fontSize: 11, color: sourceStatus.buildable ? "var(--monet-on-surface-variant)" : "var(--monet-error)" }}>
                    缺少/未验证：{sourceStatus.toolchain.missing.slice(0, 12).join("、")}
                    {sourceStatus.toolchain.missing.length > 12 ? " …" : ""}
                  </span>
                )}
                {sourceStatus.toolchain.detail && (
                  <span style={{ fontSize: 11, color: "var(--monet-on-surface-variant)" }}>
                    {sourceStatus.toolchain.detail}
                  </span>
                )}
              </>
            )}
          </div>
        )}
        {runtime && runtime.installed.length > 0 && (
          <div style={{ padding: "0 8px 8px", display: "grid", gap: 4 }}>
            {runtime.installed.map((item) => (
              <div key={item.id}
                   style={{ display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap",
                            padding: "6px 0", borderTop: "1px solid var(--monet-outline-variant)" }}>
                <span className="mono" style={{ fontSize: 12, minWidth: 120 }}>{item.id}</span>
                <span style={{ fontSize: 12, color: "var(--monet-on-surface-variant)" }}>
                  {item.major ? `${item.major} · ` : ""}{item.version} · {item.license}
                  {item.buildRecipe ? ` · ${item.buildRecipe}` : ""} · {item.featuresProbed.length}/{item.featuresDeclared.length} 特性
                </span>
                {item.current
                  ? <span className="tag running">当前</span>
                  : <button className="btn btn-text" onClick={() => void selectRuntime(item.id)}>启用</button>}
                {!item.current && (
                  <button className="btn btn-text danger" onClick={() => void deleteRuntime(item.id)}>删除</button>
                )}
              </div>
            ))}
            {runtime.installed.length > 1 && (
              <button className="btn btn-text" style={{ justifySelf: "start" }}
                      onClick={() => void rollbackRuntime()}>
                回滚到上一个版本
              </button>
            )}
          </div>
        )}
        {runtime && runtime.catalog.length > 0 && (
          <div style={{ padding: "0 8px 8px", display: "grid", gap: 4 }}>
            <span style={{ fontSize: 12, color: "var(--monet-on-surface-variant)" }}>目录条目</span>
            {runtime.catalog.map((entry) => (
              <div key={entry.id}
                   style={{ display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap" }}>
                <span className="mono" style={{ fontSize: 12, minWidth: 160 }}>{entry.id}</span>
                <span style={{ fontSize: 12, color: "var(--monet-on-surface-variant)" }}>
                  {entry.major ? `${entry.major} · ` : ""}{entry.ffmpegVersion} · {entry.license} · {entry.os}/{entry.arch}/{entry.libc}
                </span>
                {entry.kind === "source"
                  ? <>
                      <span className="tag plain" style={{ fontSize: 11 }}>源码</span>
                      <button className="btn btn-text"
                              disabled={installJob?.state === "running" || !sourceStatus?.buildable}
                              onClick={() => void startRuntimeInstall({ mode: "source", id: entry.id })}>
                        源码构建
                      </button>
                    </>
                  : <button className="btn btn-text" disabled={installJob?.state === "running"}
                            onClick={() => void startRuntimeInstall({ mode: "artifact", id: entry.id })}>
                      安装
                    </button>}
              </div>
            ))}
          </div>
        )}
      </div>

      <div className="card">
        <div className="comp-row">
          <span className="comp-label">ffmpeg</span>
          <span className="comp-value">
            {capabilities?.available ? <span className="tag running">可用</span> : <span className="tag plain">不可用</span>}
          </span>
        </div>
        <div className="comp-row">
          <span className="comp-label">版本</span>
          <span className="comp-value mono" style={{ fontSize: 12 }}>{capabilities?.version || "—"}</span>
        </div>
        <div className="comp-row">
          <span className="comp-label">ffmpeg 路径</span>
          <span className="comp-value mono" style={{ fontSize: 11, overflowWrap: "anywhere" }}>
            {capabilities?.ffmpeg || "—"}
          </span>
        </div>
        <div className="comp-row">
          <span className="comp-label">ffprobe 路径</span>
          <span className="comp-value mono" style={{ fontSize: 11, overflowWrap: "anywhere" }}>
            {capabilities?.ffprobe || "—"}
          </span>
        </div>
        <div style={{ display: "flex", gap: 8, padding: "8px", flexWrap: "wrap" }}>
          <button className="btn btn-text" disabled={busy} onClick={() => void load()}>
            {busy ? "探测中…" : "重新探测"}
          </button>
          <span style={{ fontSize: 12, color: "var(--monet-on-surface-variant)", alignSelf: "center" }}>
            也可以手动配置 `transcode.ffmpeg_path` / `HDSL_FFMPEG_PATH`。
          </span>
        </div>
        <div style={{ display: "grid", gap: 6, padding: "0 8px 8px", borderTop: "1px solid var(--monet-outline-variant)" }}>
          <label style={{ fontSize: 12, paddingTop: 8 }}>在线安装</label>
          <select className="input" value={installKind}
                  onChange={(e) => setInstallKind(e.target.value as typeof installKind)}>
            <option value="package">包管理器（apt-get / dnf / zypper）</option>
            <option value="static">下载静态包（Windows/macOS/Linux）</option>
          </select>
          {installKind !== "package" && (
            <>
              <input className="input"
                     placeholder="https://…/ffmpeg-release-essentials.zip 或 .tar.xz"
                     value={installUrl} onChange={(e) => setInstallUrl(e.target.value)} />
              <input className="input" placeholder="SHA-256（必填；64 位十六进制）"
                     value={installSha} onChange={(e) => setInstallSha(e.target.value)} />
              <input className="input" placeholder="安装目录名（可选，例如 7.1.5）"
                     value={installVersion} onChange={(e) => setInstallVersion(e.target.value)} />
            </>
          )}
          <div style={{ display: "flex", gap: 8 }}>
            <button className="btn btn-raised" disabled={installJob?.state === "running"
              || (installKind !== "package" && (!installUrl || !/^[0-9a-fA-F]{64}$/.test(installSha)))}
                    onClick={() => void startInstall()}>
              {installKind === "package" ? "开始安装" : "下载并安装"}
            </button>
            {installJob?.state === "running" && (
              <button className="btn btn-text danger"
                      onClick={() => void api.transcode.runtimeCancel(installJob.id).then(setInstallJob)}>
                取消
              </button>
            )}
          </div>
          <p style={{ fontSize: 11, color: "var(--monet-on-surface-variant)", lineHeight: 1.6 }}>
            自定义下载必须填写 SHA-256（面板不再把同源 sidecar 当作唯一信任）。源码构建在运行时卡片里，
            需要 make/gcc/pkg-config 与可选的 libx264/libx265/libva 开发包；包管理器安装只对当前容器有效，
            重建后需重装；静态包与源码构建结果都在数据卷里。
          </p>
          {installJob && (
            <>
              <div className="comp-row">
                <span className="comp-label">安装状态</span>
                <span className="comp-value">
                  <span className={`tag ${installJob.state === "done" ? "running" : "plain"}`}>
                    {installJob.state === "done" ? "完成" : installJob.state === "failed" ? "失败"
                      : installJob.state === "cancelled" ? "已取消" : installJob.message || "运行中"}
                  </span>
                </span>
              </div>
              <ProgressBar fraction={installJob.progress} />
              {installJob.logPath && (
                <span style={{ fontSize: 11, color: "var(--monet-on-surface-variant)" }}>
                  构建日志：<span className="mono">{installJob.logPath}</span>
                </span>
              )}
              {installJob.error && (
                <span style={{ fontSize: 12, color: "var(--monet-error)" }}>{installJob.error}</span>
              )}
              <pre style={{ margin: 0, padding: 8, maxHeight: 180, overflow: "auto", fontSize: 10,
                            background: "var(--monet-surface-container-low)", whiteSpace: "pre-wrap" }}>
                {installJob.log || "（暂无输出）"}
              </pre>
            </>
          )}
        </div>
      </div>

      <div className="card">
        <div className="comp-row">
          <span className="comp-label">媒体根</span>
          <span className="comp-value mono" style={{ fontSize: 11, overflowWrap: "anywhere" }}>
            {capabilities?.mediaRoot || feature?.mediaRoot || "—"}
          </span>
        </div>
        <div className="comp-row">
          <span className="comp-label">可写性</span>
          <span className="comp-value">
            {capabilities?.mediaRootExists
              ? capabilities.mediaRootWritable ? "可写" : "只读"
              : "不存在"}
          </span>
        </div>
        <p style={{ fontSize: 12, padding: "4px 8px 8px", lineHeight: 1.6, color: "var(--monet-on-surface-variant)" }}>
          容器里固定为 `/home/hdsl/video`（`HDSL_VIDEO_DIR` 决定宿主挂载）；裸部署默认
          &lt;数据目录&gt;/video，可用 `HDSL_MEDIA_ROOT` 或 `server.yaml` 的 `transcode.media_root` 修改。
        </p>
      </div>

      <div className="card">
        <div className="comp-row">
          <span className="comp-label">并发 / 队列上限</span>
          <span className="comp-value">
            {capabilities?.maxConcurrent ?? feature?.maxConcurrent ?? "—"} /{" "}
            {capabilities?.maxQueue ?? feature?.maxQueue ?? "—"}
          </span>
        </div>
        <div style={{ display: "grid", gap: 6, padding: "4px 8px 8px" }}>
          <label style={{ fontSize: 12 }}>失败策略</label>
          <select className="input" value={policy} onChange={(e) => setPolicy(e.target.value)}>
            <option value="ask">询问（默认）</option>
            <option value="timeout-auto">超时自动降级</option>
            <option value="full-auto">完全自动化（立即降级）</option>
          </select>
          <label style={{ fontSize: 12 }}>超时时间（分钟，仅询问/超时自动降级）</label>
          <input className="input" type="number" min={1} max={1440} value={timeoutMinutes}
                 onChange={(e) => setTimeoutMinutes(Number(e.target.value) || 10)} />
          <button className="btn btn-raised" disabled={saving} onClick={() => void savePolicy()}>
            {saving ? "保存中…" : "保存失败策略"}
          </button>
        </div>
        <div className="comp-row">
          <span className="comp-label">重启恢复队列</span>
          <span className="comp-value">{feature?.autoResume ? "是" : "否"}</span>
        </div>
        <p style={{ fontSize: 12, padding: "4px 8px 8px", lineHeight: 1.6, color: "var(--monet-on-surface-variant)" }}>
          这些值来自 `server.yaml` 的 `transcode:` 段与 `HDSL_TRANSCODE_*` 环境变量；改完重启面板生效。
        </p>
      </div>

      <div className="card">
        <div className="comp-row">
          <span className="comp-label">下载源（镜像）</span>
          <span className="comp-value mono" style={{ fontSize: 11, overflowWrap: "anywhere" }}>
            {binaryMirror || "官方"} / {sourceMirror || "官方"}
          </span>
        </div>
        <div style={{ display: "grid", gap: 6, padding: "4px 8px 8px" }}>
          <label style={{ fontSize: 12 }}>二进制构建下载前缀</label>
          <input className="input" placeholder="https://gh-proxy.com/ 或留空用官方 GitHub"
                 value={binaryMirror} onChange={(e) => setBinaryMirror(e.target.value)} />
          <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
            <button className="btn btn-text" onClick={() => setBinaryMirror("")}>官方（默认）</button>
            <button className="btn btn-text" onClick={() => setBinaryMirror("https://gh-proxy.com/")}>
              gh-proxy.com（第三方代理）
            </button>
          </div>
          <label style={{ fontSize: 12 }}>源码归档下载前缀</label>
          <input className="input" placeholder="https://你的镜像/ffmpeg/ 或留空用 ffmpeg.org"
                 value={sourceMirror} onChange={(e) => setSourceMirror(e.target.value)} />
          <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
            <button className="btn btn-text" onClick={() => setSourceMirror("")}>官方（默认）</button>
          </div>
          <button className="btn btn-raised" disabled={saving} onClick={() => void saveSources()}>
            {saving ? "保存中…" : "保存下载源"}
          </button>
        </div>
        <p style={{ fontSize: 12, padding: "4px 8px 8px", lineHeight: 1.6,
                    color: "var(--monet-on-surface-variant)" }}>
          前缀会把官方绝对地址直接接在后面（<span className="mono">https://gh-proxy.com/</span> +{" "}
          <span className="mono">https://github.com/…</span>）。镜像只改变从哪里取字节，
          不改变校验用的 SHA-256；镜像返回的字节不一样就按校验失败处理，不会装上去。
        </p>
      </div>

      <div className="card">
        <div className="comp-row">
          <span className="comp-label">编码器</span>
          <span className="comp-value" style={{ fontSize: 12 }}>
            {capabilities ? `${capabilities.encoders.length} 个` : "—"}
          </span>
        </div>
        {capabilities && (
          <div style={{ display: "flex", gap: 6, flexWrap: "wrap", padding: "4px 8px 8px", maxHeight: 180, overflow: "auto" }}>
            {capabilities.encoders
              .filter((name) => /264|265|av1|vp9|aac|opus|vaapi|qsv|nvenc/.test(name))
              .map((name) => (
                <span key={name} className="tag plain" style={{ fontSize: 11 }}>{name}</span>
              ))}
          </div>
        )}
        <div style={{ display: "flex", gap: 6, alignItems: "center", padding: "0 8px 8px", flexWrap: "wrap" }}>
          <select className="input" style={{ width: "auto", minWidth: 160 }} value={probeEncoderName}
                  onChange={(e) => setProbeEncoderName(e.target.value)}>
            {(capabilities?.encoders ?? [])
              .filter((name) => /264|265|av1|vp9|vaapi|qsv|nvenc/.test(name))
              .map((name) => <option key={name} value={name}>{name}</option>)}
          </select>
          <button className="btn btn-text" disabled={probing} onClick={() => {
            setProbing(true);
            setProbeResult(null);
            void api.transcode.probeEncoder(probeEncoderName)
              .then(setProbeResult)
              .finally(() => setProbing(false));
          }}>
            {probing ? "测试中…" : "测试 1 帧"}
          </button>
          <span style={{ fontSize: 11, color: "var(--monet-on-surface-variant)" }}>
            列出 ≠ 能编：QSV/VAAPI/NVENC 用 1 帧实测，失败会给出 ffmpeg 输出。
          </span>
        </div>
        {probeResult && (
          <pre style={{ margin: "0 8px 8px", padding: 8, maxHeight: 160, overflow: "auto", fontSize: 10,
                        background: "var(--monet-surface-container-low)", whiteSpace: "pre-wrap" }}>
            {probeResult.ok ? "OK" : "FAILED"} ({probeResult.exitCode})
            {"\n"}{probeResult.output || "（无输出）"}
          </pre>
        )}
      </div>
    </>
  );
}