import { useEffect, useState } from "react";
import { api, type TranscodeCapabilities, type TranscodeFullBuilds, type TranscodeInstallJob,
  type TranscodeRuntime, type TranscodeSourceStatus } from "../../api";
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
  const [fullBuilds, setFullBuilds] = useState<TranscodeFullBuilds | null>(null);
  const [fullBuildId, setFullBuildId] = useState("");
  const [runtimeMode, setRuntimeMode] = useState<"system" | "binary" | "source">("binary");
  const [binaryVendor, setBinaryVendor] = useState("");
  const [binaryMajor, setBinaryMajor] = useState("");
  const [binaryId, setBinaryId] = useState("");
  const [sourceOrigin, setSourceOrigin] = useState("");
  const [sourceId, setSourceId] = useState("");
  const [compilerId, setCompilerId] = useState("auto");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [policy, setPolicy] = useState("ask");
  const [timeoutMinutes, setTimeoutMinutes] = useState(10);
  const [saving, setSaving] = useState(false);
  const [installJob, setInstallJob] = useState<TranscodeInstallJob | null>(null);
  const [probeEncoderName, setProbeEncoderName] = useState("libx264");
  const [probeResult, setProbeResult] = useState<{ ok: boolean; encoder: string; exitCode: number; output: string } | null>(null);
  const [probing, setProbing] = useState(false);

  const load = async () => {
    setBusy(true);
    setError(null);
    try {
      const [nextCapabilities, nextRuntime, nextSource, nextFull] = await Promise.all([
        api.transcode.capabilities(),
        api.transcode.runtime(),
        api.transcode.runtimeSource().catch(() => null),
        api.transcode.runtimeFullBuilds().catch(() => null),
      ]);
      setCapabilities(nextCapabilities);
      setRuntime(nextRuntime);
      setSourceStatus(nextSource);
      setFullBuilds(nextFull);
      await refreshTranscodeFeature();
    } catch (e) {
      setError(errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  useEffect(() => {
    void load();
  }, []);

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

  const startRuntimeInstall = async (body: {
    mode: "auto" | "artifact" | "package" | "source" | "full-source";
    id?: string;
    major?: string;
    features?: string[];
    compiler?: "auto" | "system" | "portable";
  }) => {
    try {
      setInstallJob(await api.transcode.runtimeInstallArtifact(body));
    } catch (e) {
      toast("error", errMsg(e));
    }
  };

  const setDefaultRuntime = async (value: string) => {
    try {
      setRuntime(await api.transcode.runtimeDefault(value));
      toast("success", value === "system"
        ? "转码队列将使用系统 ffmpeg"
        : value === "auto" ? "转码队列将自动选择 ffmpeg" : "已设为默认 ffmpeg");
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

  const catalog = runtime?.catalog ?? [];
  const binaries = catalog.filter((entry) => entry.kind === "binary");
  const sources = catalog.filter((entry) => entry.kind === "source");
  const host = runtime?.host;
  // 目录里有 Windows 专属的构建源（gyan.dev）。先把“这个宿主真的能装”的条目筛出来，
  // 否则选中它只会得到两个空下拉框——看起来像源坏了，其实是该源没有当前平台的制品。
  const platformBinaries = binaries.filter((entry) => entry.os === host?.os
    && entry.arch === host?.arch
    && (!entry.libc || entry.libc === "none" || entry.libc === host?.libc));
  const vendorIds = [...new Set(platformBinaries.map((entry) => entry.vendor))];
  const currentVendor = runtime?.current
    ? catalog.find((entry) => entry.id === runtime.current?.catalogId)?.vendor ?? ""
    : "";
  const effectiveVendor = vendorIds.includes(binaryVendor)
    ? binaryVendor
    : vendorIds.includes(currentVendor) ? currentVendor : vendorIds[0] ?? "";
  const vendorBinaries = platformBinaries.filter((entry) => entry.vendor === effectiveVendor);
  const binaryMajors = [...new Set(vendorBinaries.map((entry) => entry.major).filter(Boolean))];
  const effectiveMajor = binaryMajors.includes(binaryMajor) ? binaryMajor : binaryMajors[0] ?? "";
  const majorBinaries = vendorBinaries.filter((entry) => entry.major === effectiveMajor);
  const effectiveBinary = majorBinaries.find((entry) => entry.id === binaryId)
    ?? majorBinaries[0] ?? null;
  const installedIds = new Set((runtime?.installed ?? []).map((item) => item.id));
  const binaryInstalled = effectiveBinary ? installedIds.has(effectiveBinary.id) : false;
  const originIds = [...new Set(sources.map((entry) => entry.vendor))];
  const effectiveOrigin = originIds.includes(sourceOrigin) ? sourceOrigin : originIds[0] ?? "";
  const originSources = sources.filter((entry) => entry.vendor === effectiveOrigin);
  const sourceVersions = originSources;
  const effectiveSource = sourceVersions.find((entry) => entry.id === sourceId)
    ?? sourceVersions[0] ?? null;
  const sourceInstalled = effectiveSource
    ? (runtime?.installed ?? []).some((item) => item.catalogId === effectiveSource.id)
    : false;
  const fullEntries = (fullBuilds?.entries ?? [])
    .filter(() => !host || host.os === "linux");
  const effectiveFull = fullEntries.find((entry) => entry.id === fullBuildId)
    ?? fullEntries[0] ?? null;
  const fullSelected = fullBuildId !== "" && effectiveFull != null;
  const fullInstalled = effectiveFull
    ? (runtime?.installed ?? []).some((item) => item.catalogId === effectiveFull.id)
    : false;
  const sourceToolchain = sourceStatus?.toolchain;
  const missingCodecDeps = sourceToolchain?.dependencies.filter((dep) => !dep.present) ?? [];
  const presentCodecDeps = sourceToolchain?.dependencies.filter((dep) => dep.present) ?? [];
  const missingDebianPackages = missingCodecDeps.map((dep) => dep.package);
  const compilers = runtime?.compilers ?? [];
  const effectiveCompiler = compilerId === "auto"
    ? compilers.find((entry) => entry.id === "system" && entry.available)
      ?? compilers.find((entry) => entry.available) ?? compilers[0] ?? null
    : compilers.find((entry) => entry.id === compilerId)
      ?? compilers.find((entry) => entry.available) ?? compilers[0] ?? null;
  const vendorLabel = (id: string) => ({
    btbn: "BtbN", gyan: "gyan.dev", jellyfin: "Jellyfin", "ffmpeg.org": "官方 ffmpeg.org",
  } as Record<string, string>)[id] ?? id;
  const defaultIds = ["auto", "system", ...(runtime?.installed.map((item) => item.id) ?? [])];
  const defaultValue = runtime?.default && defaultIds.includes(runtime.default)
    ? runtime.default : runtime?.default ?? "auto";

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
          <span className="comp-label">安装 / 使用 ffmpeg</span>
          <span className="comp-value" style={{ fontSize: 12, color: "var(--monet-on-surface-variant)" }}>
            {runtime?.resolved.kind === "managed"
              ? `当前解析：${runtime.resolved.id}`
              : runtime?.resolved.kind === "system"
                ? `当前解析：系统 ${runtime.system.version || "ffmpeg"}`
                : runtime?.resolved.kind === "path"
                  ? `当前解析：自定义路径 ${runtime.resolved.ffmpeg}`
                  : runtime?.resolved.kind === "missing"
                    ? `指定的运行时不存在：${runtime.resolved.id}`
                    : "当前没有可用的 ffmpeg"}
          </span>
        </div>

        <div style={{ display: "flex", gap: 6, padding: "0 8px 8px", flexWrap: "wrap" }}>
          <button className={`btn ${runtimeMode === "system" ? "btn-raised" : "btn-text"}`}
                  onClick={() => setRuntimeMode("system")}>使用系统 ffmpeg</button>
          <button className={`btn ${runtimeMode === "binary" ? "btn-raised" : "btn-text"}`}
                  onClick={() => setRuntimeMode("binary")}>下载二进制</button>
          <button className={`btn ${runtimeMode === "source" ? "btn-raised" : "btn-text"}`}
                  onClick={() => setRuntimeMode("source")}>源码构建</button>
        </div>

        {runtimeMode === "system" && (
          <div style={{ padding: "0 8px 10px", display: "grid", gap: 6 }}>
            <div className="comp-row">
              <span className="comp-label">系统 ffmpeg</span>
              <span className="comp-value mono" style={{ fontSize: 11, overflowWrap: "anywhere" }}>
                {runtime?.system.available
                  ? `${runtime.system.version || "版本未知"} · ${runtime.system.ffmpeg}`
                  : runtime?.system.error || "未在 PATH 找到 ffmpeg/ffprobe"}
              </span>
            </div>
            <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
              <button className="btn btn-raised" disabled={!runtime?.system.available}
                      onClick={() => void setDefaultRuntime("system")}>
                {runtime?.resolved.kind === "system" ? "已在使用系统 ffmpeg" : "设为默认（系统）"}
              </button>
              <button className="btn btn-text" disabled={busy} onClick={() => void load()}>
                重新检测
              </button>
              <button className="btn btn-text" disabled={installJob?.state === "running"}
                      onClick={() => void startRuntimeInstall({ mode: "package" })}>
                通过包管理器安装
              </button>
            </div>
            <span style={{ fontSize: 11, color: "var(--monet-on-surface-variant)" }}>
              只检测 PATH，不下载任何东西。找不到时可以点“通过包管理器安装”，或填写 transcode.ffmpeg_path。
            </span>
          </div>
        )}

        {runtimeMode === "binary" && (
          <div style={{ padding: "0 8px 10px", display: "grid", gap: 6 }}>
            <div className="comp-row">
              <span className="comp-label">下载源</span>
              <span className="comp-value">
                <select className="input" style={{ width: "auto", minWidth: 200 }}
                        value={effectiveVendor}
                        onChange={(e) => { setBinaryVendor(e.target.value); setBinaryMajor(""); setBinaryId(""); }}>
                  {vendorIds.map((id) => <option key={id} value={id}>{vendorLabel(id)}</option>)}
                </select>
              </span>
            </div>
            {vendorIds.length === 0 && (
              <span style={{ fontSize: 11, color: "var(--monet-error)", lineHeight: 1.6 }}>
                目录里没有适用于 {host?.os}/{host?.arch}{host?.libc ? `/${host.libc}` : ""} 的二进制
                构建；可以改用“使用系统 ffmpeg”，或等目录更新。
              </span>
            )}
            <div className="comp-row">
              <span className="comp-label">平台</span>
              <span className="comp-value mono" style={{ fontSize: 12 }}>
                {host?.os}/{host?.arch}/{host?.libc}{host?.libcVersion ? ` ${host.libcVersion}` : ""}
              </span>
            </div>
            <div className="comp-row">
              <span className="comp-label">大版本</span>
              <span className="comp-value">
                <select className="input" style={{ width: "auto", minWidth: 140 }}
                        value={effectiveMajor}
                        onChange={(e) => { setBinaryMajor(e.target.value); setBinaryId(""); }}>
                  {binaryMajors.map((major) => <option key={major} value={major}>{major}</option>)}
                </select>
              </span>
            </div>
            <div className="comp-row">
              <span className="comp-label">具体版本</span>
              <span className="comp-value">
                <select className="input" style={{ width: "auto", minWidth: 280 }}
                        value={effectiveBinary?.id ?? ""}
                        onChange={(e) => setBinaryId(e.target.value)}>
                  {majorBinaries.map((entry) => (
                    <option key={entry.id} value={entry.id}>
                      {entry.ffmpegVersion} · {entry.license} · {entry.features.length} 特性
                      {installedIds.has(entry.id) ? " · 已安装" : ""}
                    </option>
                  ))}
                </select>
              </span>
            </div>
            {effectiveBinary && (
              <div style={{ fontSize: 11, color: "var(--monet-on-surface-variant)", lineHeight: 1.6 }}>
                {effectiveBinary.ffmpegVersion} · {effectiveBinary.license}
                {effectiveBinary.minGlibc ? ` · 最低 glibc ${effectiveBinary.minGlibc}` : ""}
                {effectiveBinary.size > 0 ? ` · ${(effectiveBinary.size / 1048576).toFixed(1)} MB` : ""}
                {effectiveBinary.mirrors.length > 0 ? ` · ${effectiveBinary.mirrors.length} 个镜像` : ""}
                <br />
                <span className="mono" style={{ overflowWrap: "anywhere" }}>{effectiveBinary.sha256}</span>
              </div>
            )}
            <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
              <button className="btn btn-raised"
                      disabled={!effectiveBinary || binaryInstalled || installJob?.state === "running"}
                      onClick={() => effectiveBinary
                        && void startRuntimeInstall({ mode: "artifact", id: effectiveBinary.id })}>
                {binaryInstalled ? "已安装" : "安装二进制"}
              </button>
              <span style={{ fontSize: 11, color: "var(--monet-on-surface-variant)", alignSelf: "center" }}>
                {binaryInstalled
                  ? "这个构建已在数据目录里；可在下方设为默认，重装前先删除。"
                  : "只有点这里才会下载；哈希按 catalog 校验。"}
              </span>
            </div>
          </div>
        )}

        {runtimeMode === "source" && (
          <div style={{ padding: "0 8px 10px", display: "grid", gap: 6 }}>
            <div className="comp-row">
              <span className="comp-label">构建方案</span>
              <span className="comp-value">
                <select className="input" style={{ width: "auto", minWidth: 280 }}
                        value={fullSelected && effectiveFull ? effectiveFull.id : "minimal"}
                        onChange={(e) => setFullBuildId(e.target.value === "minimal" ? "" : e.target.value)}>
                  <option value="minimal">官方源码 · 宿主依赖（最小构建）</option>
                  {fullEntries.map((entry) => (
                    <option key={entry.id} value={entry.id}>
                      {entry.profile === "gpl" ? "全编码 GPL" : "全编码 LGPL"} · {entry.ffmpegVersion}（静态）
                    </option>
                  ))}
                </select>
              </span>
            </div>
            {fullSelected && effectiveFull && (
              <>
                <div className="comp-row">
                  <span className="comp-label">许可</span>
                  <span className="comp-value" style={{ fontSize: 12 }}>
                    {effectiveFull.license}
                    {" · "}
                    {effectiveFull.profile === "gpl"
                      ? "含 libx264/libx265 等 GPL 编码器"
                      : "不含 GPL 编码器"}
                  </span>
                </div>
                <div className="comp-row">
                  <span className="comp-label">依赖</span>
                  <span className="comp-value" style={{ fontSize: 11, overflowWrap: "anywhere" }}>
                    {effectiveFull.dependencies.filter((dep) => dep.kind === "codec")
                      .map((dep) => `${dep.id} ${dep.version}`).join(" · ")}
                  </span>
                </div>
                <div className="comp-row">
                  <span className="comp-label">预计</span>
                  <span className="comp-value" style={{ fontSize: 11 }}>
                    下载 {Math.round(effectiveFull.downloadBytes / 1048576)} MB ·
                    约 {effectiveFull.buildMinutes} 分钟 ·
                    磁盘 {Math.round(effectiveFull.diskBytes / 1073741824)} GiB
                  </span>
                </div>
                <div style={{ fontSize: 11, color: "var(--monet-on-surface-variant)", lineHeight: 1.6 }}>
                  声明编码器：{effectiveFull.features.join("、")}
                  <br />
                  全量构建会下载并编译 x264/x265/libvpx/libaom/svt-av1/opus/lame/vorbis，
                  以及 cmake/nasm；产物是静态 ffmpeg（libav 与编码库静态，glibc 仍动态）。
                  首次构建较慢，之后会复用 cmake/nasm 缓存。
                  {fullBuilds && !fullBuilds.available && (
                    <>
                      <br />
                      <span style={{ color: "var(--monet-error)" }}>{fullBuilds.reason}</span>
                    </>
                  )}
                </div>
                <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
                  <button className="btn btn-raised"
                          disabled={!fullBuilds?.available || fullInstalled
                            || installJob?.state === "running"}
                          onClick={() => effectiveFull && void startRuntimeInstall({
                            mode: "full-source", id: effectiveFull.id, compiler: "system",
                          })}>
                    {fullInstalled ? "已构建" : "开始全量构建"}
                  </button>
                  <span style={{ fontSize: 11, color: "var(--monet-on-surface-variant)",
                                 alignSelf: "center" }}>
                    {fullInstalled
                      ? "这个 recipe 已经构建过；要重编先删除已安装的运行时。"
                      : "只有点这里才会下载源码并编译；面板不会替你 sudo。"}
                  </span>
                </div>
              </>
            )}
            {!fullSelected && (<>
            <div className="comp-row">
              <span className="comp-label">源码来源</span>
              <span className="comp-value">
                <select className="input" style={{ width: "auto", minWidth: 220 }}
                        value={effectiveOrigin}
                        onChange={(e) => { setSourceOrigin(e.target.value); setSourceId(""); }}>
                  {originIds.map((id) => <option key={id} value={id}>{vendorLabel(id)}</option>)}
                </select>
              </span>
            </div>
            <div className="comp-row">
              <span className="comp-label">版本</span>
              <span className="comp-value">
                <select className="input" style={{ width: "auto", minWidth: 180 }}
                        value={effectiveSource?.id ?? ""}
                        onChange={(e) => setSourceId(e.target.value)}>
                  {sourceVersions.map((entry) => (
                    <option key={entry.id} value={entry.id}>
                      {entry.ffmpegVersion} · 源码包 {entry.license}
                      {(runtime?.installed ?? []).some((item) => item.catalogId === entry.id)
                        ? " · 已构建" : ""}
                    </option>
                  ))}
                </select>
              </span>
            </div>
            <div className="comp-row">
              <span className="comp-label">编译工具</span>
              <span className="comp-value">
                <select className="input" style={{ width: "auto", minWidth: 260 }}
                        value={compilerId}
                        onChange={(e) => setCompilerId(e.target.value)}>
                  <option value="auto">自动（系统 → 便携）</option>
                  {compilers.map((compiler) => (
                    <option key={compiler.id} value={compiler.id} disabled={!compiler.available}>
                      {compiler.label}{compiler.available ? "" : "（不可用）"}
                    </option>
                  ))}
                </select>
              </span>
            </div>
            {effectiveCompiler && (
              <span style={{ fontSize: 11, color: "var(--monet-on-surface-variant)", lineHeight: 1.6 }}>
                {effectiveCompiler.detail}
              </span>
            )}
            {effectiveSource && (
              <div style={{ fontSize: 11, color: "var(--monet-on-surface-variant)", lineHeight: 1.6 }}>
                {effectiveSource.ffmpegVersion} · 源码包 {effectiveSource.license}
                {effectiveSource.features.length > 0
                  ? ` · 声明特性 ${effectiveSource.features.length} 项` : ""}
                <br />
                源码包本身是 LGPL-2.1+；构建启用 libx264/libx265 后，产物按 GPL-3.0 记录，
                但没有这些外部编码库时就是最小 LGPL 构建。
                <br />
                <span className="mono" style={{ overflowWrap: "anywhere" }}>{effectiveSource.url}</span>
              </div>
            )}
            {sourceToolchain && (
              <div style={{ fontSize: 11, color: "var(--monet-on-surface-variant)", lineHeight: 1.6 }}>
                构建预检：{sourceToolchain.usable ? "系统工具链可用" : "系统工具链不可用"}
                {" · "}{sourceToolchain.x86asm ? "x86 汇编优化开启" : "无 nasm，x86 汇编关闭"}
                <br />
                {missingCodecDeps.length === 0 ? (
                  <>
                    外部编码库齐全：{presentCodecDeps.map((dep) => dep.feature).join("、")}；
                    构建会链接它们，产物按 GPL-3.0 记录。
                  </>
                ) : (
                  <>
                    未检测到：{missingCodecDeps.map((dep) => dep.feature).join("、")}。
                    configure 会跳过它们，产物是最小编码集（LGPL），不含 libx264/libx265 等。
                    <br />
                    要全编码：在宿主用 root 装 Debian/Ubuntu 开发包
                    <span className="mono" style={{ overflowWrap: "anywhere" }}>
                      {" "}{["build-essential", "pkg-config", "nasm", ...missingDebianPackages].join(" ")}
                    </span>
                    ，或切到「下载二进制」选 BtbN / gyan.dev 的 GPL 构建。面板不会替你 sudo。
                  </>
                )}
              </div>
            )}
            <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
              <button className="btn btn-raised"
                      disabled={!effectiveSource || sourceInstalled || installJob?.state === "running"
                        || (compilerId !== "auto" && !effectiveCompiler?.available)}
                      onClick={() => effectiveSource && void startRuntimeInstall({
                        mode: "source", id: effectiveSource.id,
                        compiler: compilerId as "auto" | "system" | "portable",
                      })}>
                {sourceInstalled ? "已构建" : "开始构建"}
              </button>
              <span style={{ fontSize: 11, color: "var(--monet-on-surface-variant)", alignSelf: "center" }}>
                {sourceInstalled
                  ? "这个版本已经构建过；要重编先删除已安装的运行时。"
                  : "只有点这里才会下载源码并编译。"}
              </span>
            </div>
            </>)}
          </div>
        )}

        {installJob && (
          <div style={{ padding: "0 8px 10px", borderTop: "1px solid var(--monet-outline-variant)" }}>
            <div className="comp-row">
              <span className="comp-label">任务状态</span>
              <span className="comp-value">
                <span className={`tag ${installJob.state === "done" ? "running" : "plain"}`}>
                  {installJob.state === "done" ? "完成" : installJob.state === "failed" ? "失败"
                    : installJob.state === "cancelled" ? "已取消" : installJob.message || "运行中"}
                </span>
              </span>
            </div>
            <ProgressBar fraction={installJob.progress} />
            {installJob.error && (
              <span style={{ fontSize: 12, color: "var(--monet-error)" }}>{installJob.error}</span>
            )}
            {installJob.log && (
              <pre style={{ margin: "6px 0 0", padding: 8, maxHeight: 160, overflow: "auto", fontSize: 10,
                            background: "var(--monet-surface-container-low)", whiteSpace: "pre-wrap" }}>
                {installJob.log}
              </pre>
            )}
          </div>
        )}
      </div>

      <div className="card">
        <div className="comp-row">
          <span className="comp-label">转码使用的 ffmpeg</span>
          <span className="comp-value">
            <select className="input" style={{ width: "auto", minWidth: 260 }}
                    value={defaultValue}
                    onChange={(e) => void setDefaultRuntime(e.target.value)}>
              <option value="auto">自动（托管 current 优先，否则系统）</option>
              <option value="system">系统 ffmpeg（PATH）</option>
              {runtime?.installed.map((item) => (
                <option key={item.id} value={item.id}>{item.id}</option>
              ))}
              {!defaultIds.includes(defaultValue) && (
                <option value={defaultValue}>{defaultValue}（未安装）</option>
              )}
            </select>
          </span>
        </div>
        <div className="comp-row">
          <span className="comp-label">当前解析到</span>
          <span className="comp-value" style={{ fontSize: 12, overflowWrap: "anywhere" }}>
            {runtime?.resolved.kind === "managed" ? `托管 ${runtime.resolved.id}`
              : runtime?.resolved.kind === "system" ? `系统 ${runtime.system.version || ""}`
              : runtime?.resolved.kind === "path" ? `自定义路径 ${runtime.resolved.ffmpeg}`
              : runtime?.resolved.kind === "missing" ? `指定的运行时不存在：${runtime.resolved.id}`
              : "没有可用的 ffmpeg"}
            {runtime?.resolved.ffmpeg ? ` · ${runtime.resolved.ffmpeg}` : ""}
          </span>
        </div>
        {runtime && runtime.installed.length > 0 && (
          <div style={{ padding: "0 8px 8px", display: "grid", gap: 4 }}>
            {runtime.installed.map((item) => (
              <div key={item.id}
                   style={{ display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap",
                            padding: "6px 0", borderTop: "1px solid var(--monet-outline-variant)" }}>
                <span className="mono" style={{ fontSize: 12, minWidth: 120 }}>{item.id}</span>
                <span style={{ fontSize: 12, color: "var(--monet-on-surface-variant)" }}>
                  {item.major ? `${item.major} · ` : ""}{item.version} · {item.license}
                  {item.toolchain ? ` · ${item.toolchain}` : ""}
                  {item.featuresProbed.length > 0
                    ? ` · ${item.featuresProbed.length}/${item.featuresDeclared.length} 特性` : ""}
                </span>
                {item.current && <span className="tag running">current</span>}
                {defaultValue === item.id
                  ? <span className="tag running">默认</span>
                  : <button className="btn btn-text"
                            onClick={() => void setDefaultRuntime(item.id)}>设为默认</button>}
                {!item.current && (
                  <button className="btn btn-text"
                          onClick={() => void selectRuntime(item.id)}>启用</button>
                )}
                {!item.current && (
                  <button className="btn btn-text danger"
                          onClick={() => void deleteRuntime(item.id)}>删除</button>
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
        <p style={{ margin: 0, padding: "0 8px 8px", fontSize: 11,
                    color: "var(--monet-on-surface-variant)" }}>
          默认选择持久化在 server.yaml 的 transcode.default_runtime；auto 之外的值不会静默回退。
          若 server.yaml 设置了 ffmpeg_path，它优先于这里的默认选择。
        </p>
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