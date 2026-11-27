import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  api,
  type McSeedPreviewCapabilities,
  type McSeedPreviewHistoryEntry,
  type McSeedPreviewJob,
  type McSeedPreviewMeta,
  type McSeedPreviewSeedGroup,
  type McSeedPreviewStructures,
  type McVersion,
} from "../api";
import { withBase } from "../base";
import { errMsg } from "../utils";

/** Java 内核的清晰度档位；采样上限与 PreviewSpec.Quality 一一对应。 */
type PreviewQuality = "standard" | "high" | "ultra" | "extreme";

/// 与 `PreviewSpec.javaSamples()` 同源：min(cap, max(128, radius))。
/// 改后端采样公式时这里必须跟着改（PreviewSpecQualityTest 守着后端）。
const QUALITY_TIERS: { id: PreviewQuality; cap: number; name: string }[] = [
  { id: "standard", cap: 1024, name: "默认（快速）" },
  { id: "high", cap: 2048, name: "高清" },
  { id: "ultra", cap: 4096, name: "超清" },
  { id: "extreme", cap: 8192, name: "极限" },
];
const qualityCap = (id: PreviewQuality) =>
  QUALITY_TIERS.find((tier) => tier.id === id)?.cap ?? 1024;
const samplesFor = (radius: number, cap: number) => Math.min(cap, Math.max(128, radius));

/// 3.6µs/采样 是 ULTRA（4096² 约 1 分钟）实测的单核成本；并行收益按渲染机
/// 核数折算（min(16, cores)），+30% 留给 PNG 编码与子进程启动。诚实声明：
/// 共享宿主机、老版本层栈路径、冷 JVM 都会让它失真——仅供参考，不具合同效力。
const estimateSeconds = (samples: number, cores?: number) => {
  const workers = Math.min(16, Math.max(1, cores ?? 1));
  return (samples * samples * 3.6e-6 * 1.3) / workers + 3;
};
const formatEta = (seconds: number) =>
  seconds < 90 ? `约 ${Math.max(3, Math.round(seconds))} 秒` : `约 ${Math.round(seconds / 60)} 分钟`;
const qualityLabel = (quality?: string) =>
  quality === "extreme" ? "极限" : quality === "ultra" ? "超清" : quality === "high" ? "高清" : "标准";

/**
 * 组内排序键。`time` 是纯粹的时间关系：被选为主排序时，其余键一律不参与。
 * 其余键用于「把相似参数排在一起」——同版本、同引擎、同清晰度、同大小。
 */
type SeedSortKey = "time" | "version" | "engine" | "quality" | "radius";
const SEED_SORT_LABELS: Record<SeedSortKey, string> = {
  time: "时间（新→旧）",
  version: "版本（新→旧）",
  engine: "引擎（Java→MC）",
  quality: "清晰度（低→高）",
  radius: "范围（小→大）",
};
const SEED_SORT_KEYS: SeedSortKey[] = ["time", "version", "engine", "quality", "radius"];
const SORT_LEVEL_LABELS = ["主", "次", "三", "四"] as const;

/** 排序链的槽位：主排序之外可以留空。 */
type SeedSortChoice = SeedSortKey | "none";

/** MC 版本号按数字段比较（`1.9` < `1.14`），而不是裸字典序。 */
const compareSeedVersion = (left: string, right: string) =>
  left.localeCompare(right, undefined, { numeric: true, sensitivity: "base" });

const compareSeedField = (
  left: McSeedPreviewHistoryEntry,
  right: McSeedPreviewHistoryEntry,
  key: SeedSortKey,
): number => {
  switch (key) {
    case "version":
      return compareSeedVersion(right.version, left.version);
    case "engine":
      return (left.engine === "server" ? 1 : 0) - (right.engine === "server" ? 1 : 0);
    case "quality":
      return QUALITY_TIERS.findIndex((tier) => tier.id === (left.quality ?? "standard"))
        - QUALITY_TIERS.findIndex((tier) => tier.id === (right.quality ?? "standard"));
    case "radius":
      return left.radius - right.radius;
    case "time":
      return Date.parse(right.generatedAt) - Date.parse(left.generatedAt);
  }
};

/**
 * 组内排序：按给定的优先级链逐级比较，最后以时间倒序兜底。
 *
 * 第一个槽位是主排序；为 `time` 时直接返回纯时间序——其余槽位被完全忽略，
 * 不会因为"引擎相同"之类的原因重新洗牌。其余情况下每一级只在上一级打平时
 * 生效，相同参数的记录里新记录在前。
 */
const sortSeedVariants = (variants: McSeedPreviewHistoryEntry[], choices: SeedSortChoice[]) => {
  const sorted = [...variants];
  const primary = choices[0] ?? "time";
  if (primary === "time") {
    return sorted.sort((left, right) => compareSeedField(left, right, "time"));
  }
  const chain = [...new Set(choices)]
    .filter((key): key is SeedSortKey => key !== "none");
  return sorted.sort((left, right) => {
    for (const key of chain) {
      const byKey = compareSeedField(left, right, key);
      if (byKey !== 0) return byKey;
    }
    return compareSeedField(left, right, "time");
  });
};

/** 正在看的是哪一张图：刚跑完的作业，还是历史里的一条。 */
type View = {
  source: "job" | "history";
  id: string;
  seed: string;
  radius: number;
  partial: boolean;
  /** `biome` 是稀疏采样的群系速览，图很小，由浏览器平滑放大。 */
  mode: "detail" | "biome";
};

/** 单个种子组内部的筛选；与顶部的全局筛选叠加，互不改写。 */
type SeedGroupFilter = {
  version: string;
  engine: string;
  quality: string;
  radius: string;
};
const EMPTY_GROUP_FILTER: SeedGroupFilter = { version: "", engine: "", quality: "", radius: "" };
const matchesGroupFilter = (entry: McSeedPreviewHistoryEntry, filter: SeedGroupFilter) =>
  (!filter.version || entry.version === filter.version)
  && (!filter.engine || (entry.engine ?? "java") === filter.engine)
  && (!filter.quality || (entry.quality ?? "standard") === filter.quality)
  && (!filter.radius || String(entry.radius) === filter.radius);

/**
 * 种子预览：给一个种子，出一张"这世界长什么样"的图。
 *
 * 实现方式不是重写地形生成，而是让**面板自己缓存的那个 `server.jar`** 起一个
 * 一次性服务端，在临时目录里生成一块区域，然后把 region 文件读出来画图。所以
 * 图上的一草一木都来自游戏本体，不会和实际开出来的世界有偏差。
 *
 * 地形烧成 PNG（那是重活，要生成世界）；网格、史莱姆区块、结构点都放在浏览器
 * 的 SVG 里——它们是元数据，切成开挂不该再跑一次世界生成。
 *
 * 跑过的结果留在服务端缓存里（最近 30 个），关掉页面再回来还在：试种子这件事
 * 本来就是来回比较，结果不能是"一转头就没了"。
 */
export function McSeedPreview({
  initialVersion = "",
  initialSeed = "",
}: {
  /** 从实例带进来的默认值；独立页面上可以留空。 */
  initialVersion?: string;
  initialSeed?: string;
}) {
  const [version, setVersion] = useState(initialVersion);
  const [versions, setVersions] = useState<McVersion[]>([]);
  const [seed, setSeed] = useState(initialSeed);
  const [mode, setMode] = useState<"detail" | "biome">("biome");
  const [engine, setEngine] = useState<"java" | "server">("java");
  const [quality, setQuality] = useState<PreviewQuality>("high");
  const [extremeConfirm, setExtremeConfirm] = useState(false);
  const [historyFilter, setHistoryFilter] = useState({
    seed: "",
    version: "",
    engine: "",
    quality: "",
    radius: "",
  });
  const [expandedSeeds, setExpandedSeeds] = useState<Set<string>>(() => new Set());
  const [groupFilters, setGroupFilters] = useState<Record<string, SeedGroupFilter>>({});
  const [historySort, setHistorySort] = useState<SeedSortChoice[]>(["time", "none", "none", "none"]);
  const setSortLevel = (index: number, value: SeedSortChoice) =>
    setHistorySort((previous) => {
      const next = [...previous];
      next[index] = value;
      // 某一级被清空、或与更高级重复时，后面的级别不再有意义。
      for (let level = 1; level < next.length; level++) {
        if (next[level] !== "none"
            && (next[level - 1] === "none" || next.slice(0, level).includes(next[level]))) {
          next[level] = "none";
        }
      }
      return next;
    });
  const [deleting, setDeleting] = useState<string | null>(null);
  const [radius, setRadius] = useState(512);
  const [caps, setCaps] = useState<McSeedPreviewCapabilities | null>(null);
  const [job, setJob] = useState<McSeedPreviewJob | null>(null);
  const [view, setView] = useState<View | null>(null);
  const [meta, setMeta] = useState<McSeedPreviewMeta | null>(null);
  const [history, setHistory] = useState<McSeedPreviewSeedGroup[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [exactStructures, setExactStructures] = useState<McSeedPreviewStructures | null>(null);
  const [exactLoading, setExactLoading] = useState(false);
  const jobRef = useRef<string | null>(null);

  useEffect(() => {
    api.mc
      .versions()
      .then((catalog) => {
        const releases = catalog.versions.filter((entry) => entry.type === "release");
        setVersions(releases);
        setVersion((current) => current || releases[0]?.id || "");
      })
      .catch(() => setVersions([]));
  }, []);

  useEffect(() => {
    if (!version.trim()) {
      setCaps(null);
      return;
    }
    let alive = true;
    api.mc
      .previewCapabilities(version)
      .then((value) => alive && setCaps(value))
      .catch(() => alive && setCaps(null));
    return () => {
      alive = false;
    };
  }, [version]);

  const refreshHistory = useCallback(async () => {
    try {
      const page = await api.mc.previewHistory();
      setHistory(page.seeds);
    } catch {
      // 历史读不到不影响正在看的图。
    }
  }, []);

  useEffect(() => {
    void refreshHistory();
  }, [refreshHistory]);

  const poll = useCallback(
    async (id: string) => {
      try {
        const next = await api.mc.previewJob(id);
        if (jobRef.current !== id) return;
        setJob(next);
        if (next.state === "DONE") {
          setView({
            source: "job",
            id,
            seed: next.seed,
            radius: next.radius,
            partial: next.partial,
            mode: next.mode === "biome" ? "biome" : "detail",
          });
          try {
            setMeta(await api.mc.previewMeta(id));
          } catch {
            // 元数据只是加分项，图在就够了。
          }
          void refreshHistory();
        }
        if (next.state === "FAILED") {
          setError(next.error ?? "预览失败");
        }
      } catch (e) {
        if (jobRef.current === id) setError(errMsg(e));
      }
    },
    [refreshHistory],
  );

  useEffect(() => {
    if (!job || job.state === "DONE" || job.state === "FAILED" || job.state === "CANCELLED") return;
    const timer = window.setTimeout(() => void poll(job.id), 700);
    return () => window.clearTimeout(timer);
  }, [job, poll]);

  const start = async (quality: "standard" | "high" | "ultra" | "extreme" = "standard") => {
    const trimmed = seed.trim();
    if (!trimmed) {
      setError("先填一个种子");
      return;
    }
    setExtremeConfirm(false);
    setBusy(true);
    setError(null);
    setView(null);
    setMeta(null);
    setJob(null);
    try {
      const created = await api.mc.previewStart({ version: version.trim(), seed: trimmed, radius, mode, engine, quality });
      jobRef.current = created.id;
      setJob(created);
    } catch (e) {
      setError(errMsg(e));
    } finally {
      setBusy(false);
    }
  };

  const cancel = async () => {
    if (!job) return;
    try {
      await api.mc.previewCancel(job.id);
    } catch (e) {
      setError(errMsg(e));
    }
  };

  const openHistory = async (entry: McSeedPreviewHistoryEntry) => {
    setError(null);
    setMeta(null);
    setView({
      source: "history",
      id: entry.id,
      seed: entry.seed,
      radius: entry.radius,
      partial: entry.partial,
      mode: entry.mode === "biome" ? "biome" : "detail",
    });
    setSeed(entry.seed);
    setVersion(entry.version);
    setRadius(entry.radius);
    setEngine(entry.engine === "server" ? "server" : "java");
    setExpandedSeeds((previous) => {
      const next = new Set(previous);
      next.add(entry.seed);
      return next;
    });
    if (entry.quality === "standard" || entry.quality === "high"
        || entry.quality === "ultra" || entry.quality === "extreme") {
      setQuality(entry.quality);
    }
    try {
      setMeta(await api.mc.previewHistoryMeta(entry.id));
    } catch {
      // 图还在，元数据读不到就少一层覆盖。
    }
  };

  const removeHistory = async (entry: McSeedPreviewHistoryEntry) => {
    try {
      await api.mc.previewHistoryDelete(entry.id);
      if (view?.source === "history" && view.id === entry.id) {
        setView(null);
        setMeta(null);
      }
      await refreshHistory();
    } catch (e) {
      setError(errMsg(e));
    }
  };

  const fetchExactStructures = async () => {
    if (!view) return;
    setExactLoading(true);
    setError(null);
    try {
      const result = await api.mc.previewStructures({
        version: version.trim(),
        seed: view.seed,
        radius: view.radius,
        exact: true,
      });
      setExactStructures(result);
      if (result.exactError) {
        setError(result.exactError);
      }
    } catch (e) {
      setError(errMsg(e));
    } finally {
      setExactLoading(false);
    }
  };

  const running = job !== null && !["DONE", "FAILED", "CANCELLED"].includes(job.state);

  // 32768 跨度锁 2048 采样——与服务端强制一致（PreviewSpec.javaSamples 在
  // 该半径下对 high 的 cap 正好 2048），锁定时清晰度下拉直接灰掉。
  const javaQualityLocked = engine === "java" && radius > 8192;
  const effectiveQuality: PreviewQuality = javaQualityLocked ? "high" : quality;
  const currentSamples = samplesFor(radius, qualityCap(effectiveQuality));
  const blocksPerSample = Math.max(1, Math.round((radius * 2) / currentSamples));
  const currentEta = formatEta(estimateSeconds(currentSamples, caps?.cores));
  // 某档与低一档算出的采样数相同 = 这个范围下它是摆设，灰掉并注明原因。
  const tierRedundant = (id: PreviewQuality) => {
    const index = QUALITY_TIERS.findIndex((tier) => tier.id === id);
    if (index <= 0) return false;
    return samplesFor(radius, QUALITY_TIERS[index].cap)
        === samplesFor(radius, QUALITY_TIERS[index - 1].cap);
  };

  const flatHistory = useMemo(() => history.flatMap((group) => group.variants), [history]);
  const historyChoices = useMemo(() => ({
    version: [...new Set(flatHistory.map((entry) => entry.version))].sort(compareSeedVersion),
    engine: [...new Set(flatHistory.map((entry) => entry.engine ?? "java"))].sort(),
    quality: [...new Set(flatHistory.map((entry) => entry.quality ?? "standard"))].sort(),
    radius: [...new Set(flatHistory.map((entry) => String(entry.radius)))].sort((a, b) => Number(a) - Number(b)),
  }), [flatHistory]);
  const matchesHistoryFilter = (entry: McSeedPreviewHistoryEntry) =>
    (!historyFilter.seed || entry.seed.includes(historyFilter.seed))
    && (!historyFilter.version || entry.version === historyFilter.version)
    && (!historyFilter.engine || (entry.engine ?? "java") === historyFilter.engine)
    && (!historyFilter.quality || (entry.quality ?? "standard") === historyFilter.quality)
    && (!historyFilter.radius || String(entry.radius) === historyFilter.radius);
  const filteredGroups = useMemo(() => {
    const groups = history
      .map((group) => ({
        seed: group.seed,
        variants: group.variants
          .filter(matchesHistoryFilter)
          .sort((left, right) => Date.parse(right.generatedAt) - Date.parse(left.generatedAt)),
      }))
      .filter((group) => group.variants.length > 0);
    groups.sort((left, right) =>
      Date.parse(right.variants[0].generatedAt) - Date.parse(left.variants[0].generatedAt));
    return groups;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [history, historyFilter]);
  const filteredCount = filteredGroups.reduce((total, group) => total + group.variants.length, 0);
  const filtersActive = Object.values(historyFilter).some((value) => value !== "");
  const updateGroupFilter = (seed: string, patch: Partial<SeedGroupFilter>) =>
    setGroupFilters((previous) => ({
      ...previous,
      [seed]: { ...(previous[seed] ?? EMPTY_GROUP_FILTER), ...patch },
    }));

  const toggleSeed = (seed: string) => {
    setExpandedSeeds((previous) => {
      const next = new Set(previous);
      if (next.has(seed)) next.delete(seed);
      else next.add(seed);
      return next;
    });
  };

  const removeSeedGroup = async (seed: string, variants: McSeedPreviewHistoryEntry[]) => {
    if (!window.confirm(`删除种子 ${seed} 的全部 ${variants.length} 条预览？`)) return;
    setDeleting(`seed:${seed}`);
    try {
      for (const variant of variants) {
        await api.mc.previewHistoryDelete(variant.id);
      }
      if (view?.source === "history" && variants.some((variant) => variant.id === view.id)) {
        setView(null);
        setMeta(null);
      }
      setExpandedSeeds((previous) => {
        const next = new Set(previous);
        next.delete(seed);
        return next;
      });
    } catch (e) {
      setError(errMsg(e));
    } finally {
      setDeleting(null);
      // Even a partial failure leaves some rows gone; re-read whatever is left.
      await refreshHistory();
    }
  };

  const clearHistory = async () => {
    if (flatHistory.length === 0) return;
    if (!window.confirm(`清空全部 ${flatHistory.length} 条种子预览？`)) return;
    setDeleting("all");
    try {
      for (const entry of flatHistory) {
        await api.mc.previewHistoryDelete(entry.id);
      }
      setView(null);
      setMeta(null);
      setExpandedSeeds(new Set());
    } catch (e) {
      setError(errMsg(e));
    } finally {
      setDeleting(null);
      await refreshHistory();
    }
  };

  const structureRows = meta ? Object.entries(meta.structureCounts).sort((a, b) => b[1] - a[1]) : [];

  return (
    <div className="card" style={{ padding: 16, display: "flex", flexDirection: "column", gap: 12 }}>
      <div>
        <h3 style={{ margin: 0 }}>种子预览</h3>
        <p className="dim" style={{ margin: "4px 0 0", fontSize: 13 }}>
          群系速览由 Java 内核直接计算，秒出；详细地图才启动一次性服务端生成。世界用完即删，只留图片和结构清单。
        </p>
      </div>

      <div style={{ display: "flex", flexWrap: "wrap", gap: 8, alignItems: "flex-end" }}>
        <label style={{ display: "flex", flexDirection: "column", gap: 4 }}>
          <span className="dim" style={{ fontSize: 12 }}>版本</span>
          <select
            className="input"
            value={version}
            onChange={(event) => setVersion(event.target.value)}
            style={{ width: 168 }}
          >
            {version && !versions.some((entry) => entry.id === version) && (
              <option value={version}>{version}</option>
            )}
            {versions.map((entry) => (
              <option key={entry.id} value={entry.id}>
                {entry.id}
              </option>
            ))}
          </select>
        </label>
        <label style={{ display: "flex", flexDirection: "column", gap: 4, flex: "1 1 220px" }}>
          <span className="dim" style={{ fontSize: 12 }}>种子（数字或文本）</span>
          <input
            className="input mono"
            value={seed}
            onChange={(event) => setSeed(event.target.value)}
            placeholder="例如 123456789 或 我的世界"
          />
        </label>
        <label style={{ display: "flex", flexDirection: "column", gap: 4 }}>
          <span className="dim" style={{ fontSize: 12 }}>方案</span>
          <select
            className="input"
            value={engine}
            onChange={(event) => {
              const next = event.target.value as "java" | "server";
              setEngine(next);
              if (next === "java") setMode("biome");
            }}
            style={{ width: 150 }}
          >
            <option value="java">Java 内核（秒出）</option>
            <option value="server">MC 服务器（精准）</option>
          </select>
        </label>
        <label style={{ display: "flex", flexDirection: "column", gap: 4 }}>
          <span className="dim" style={{ fontSize: 12 }}>类型</span>
          <select
            className="input"
            value={mode}
            onChange={(event) => {
              const next = event.target.value as "detail" | "biome";
              setMode(next);
              if (next === "detail") setEngine("server");
            }}
            style={{ width: 130 }}
          >
            <option value="biome">群系速览</option>
            <option value="detail" disabled={engine === "java"}>详细地图</option>
          </select>
        </label>
        <label style={{ display: "flex", flexDirection: "column", gap: 4 }}>
          <span className="dim" style={{ fontSize: 12 }}>范围</span>
          <select
            className="input"
            value={radius}
            onChange={(event) => setRadius(Number(event.target.value))}
            style={{ minWidth: 176, flex: "0 0 auto" }}
          >
            <option value={256}>512 × 512 格</option>
            <option value={512}>1024 × 1024 格</option>
            <option value={1024}>2048 × 2048 格</option>
            {engine === "java" && (
              <>
                <option value={2048}>4096 × 4096 格</option>
                <option value={4096}>8192 × 8192 格</option>
                <option value={8192}>16384 × 16384 格</option>
                <option value={16384}>32768 × 32768 格（密度锁定）</option>
              </>
            )}
          </select>
        </label>
        {engine === "java" && (
          <label style={{ display: "flex", flexDirection: "column", gap: 4 }}>
            <span className="dim" style={{ fontSize: 12 }}>
              清晰度{javaQualityLocked ? "（32768 跨度固定）" : ""}
            </span>
            <select
              className="input"
              value={effectiveQuality}
              disabled={javaQualityLocked}
              onChange={(event) => setQuality(event.target.value as PreviewQuality)}
              style={{ minWidth: 240 }}
              title={javaQualityLocked
                ? "32768 跨度固定 2048 采样（与原 16384 默认同地面密度）；放开选择就是把预览当跑分用"
                : "采样越高图越细、越慢、PNG 越大；与低档结果相同的档位已灰掉"}
            >
              {QUALITY_TIERS.map((tier) => {
                const samples = samplesFor(radius, tier.cap);
                const per = Math.max(1, Math.round((radius * 2) / samples));
                const redundant = tierRedundant(tier.id);
                return (
                  <option key={tier.id} value={tier.id} disabled={redundant}>
                    {tier.name} · {samples} 采样 · 1 采样 ≈ {per} 格
                    {redundant ? "（与低档相同）" : ""}
                  </option>
                );
              })}
            </select>
          </label>
        )}
        <button
          className="btn btn-raised"
          disabled={busy || running}
          onClick={() => {
            if (effectiveQuality === "extreme" && !extremeConfirm) {
              setExtremeConfirm(true);
              return;
            }
            void start(effectiveQuality);
          }}
        >
          {busy ? "提交中…" : engine === "java" ? `生成预览（${currentEta}）` : "生成预览"}
        </button>
        {running && (
          <button className="btn btn-text" onClick={() => void cancel()}>
            取消
          </button>
        )}
      </div>

      {engine === "java" && (
        <span className="dim" style={{ fontSize: 11 }}>
          当前：{currentSamples.toLocaleString()} 采样 · 1 采样 ≈ {blocksPerSample} 格 ·
          出图 {(radius * 2).toLocaleString()} × {(radius * 2).toLocaleString()} 格 ·
          预计 {currentEta}（按服务器 {caps?.cores ?? "?"} 核估计，仅供参考）
        </span>
      )}

      {extremeConfirm && (
        <div className="hint warning" style={{ margin: 0, display: "grid", gap: 6 }}>
          <b>极限档（{currentSamples} 采样）生成前确认</b>
          <span>· 共 {(currentSamples * currentSamples).toLocaleString()} 次噪声求值，1 采样 ≈ {blocksPerSample} 格</span>
          <span>· 预计 {currentEta}（按服务器 {caps?.cores ?? "?"} 核估计；共享宿主机/冷启动会更慢，上限见 HDSL_PREVIEW_EXTREME_TIMEOUT_MINUTES）</span>
          <span>· 在独立子进程渲染（-Xmx3g），不占用面板自身的堆</span>
          <span>· PNG 可达数十 MB；浏览器里拖动/缩放大图可能明显卡顿</span>
          <span>· 浏览器有 GPU 纹理上限（常见 4096/8192px），超限的大图缩放会发虚——最细细节请用"下载"拿原图本地看</span>
          <span style={{ display: "flex", gap: 8 }}>
            <button
              className="btn btn-raised"
              onClick={() => {
                setExtremeConfirm(false);
                void start("extreme");
              }}
            >
              确认生成
            </button>
            <button className="btn btn-text" onClick={() => setExtremeConfirm(false)}>
              取消
            </button>
          </span>
        </div>
      )}

      {caps && (
        <p className="dim" style={{ margin: 0, fontSize: 12 }}>
          {engine === "java"
            ? "Java 内核直接计算，无需启动服务端"
            : caps.jarPresent
              ? "服务端 jar 已缓存"
              : "首次需要下载该版本的服务端 jar（约 50MB）"}
          {engine === "server" && (
            <>
              {" · "}
              {caps.javaReady
                ? `Java ${caps.javaRequired} 已就绪`
                : `需要先安装 Java ${caps.javaRequired}（Minecraft → Java 运行时）`}
            </>
          )}
        </p>
      )}

      {running && job && (
        <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
          <div style={{ display: "flex", justifyContent: "space-between", fontSize: 12 }} className="dim">
            <span>
              {job.label}
              {job.etaSeconds !== undefined && job.percent > 3 ? ` · 约剩 ${job.etaSeconds} 秒` : ""}
            </span>
            <span className="mono">
              {job.chunksDone.toLocaleString()} / {job.chunksTotal.toLocaleString()} {job.engine === "java" ? "采样" : "区块"} · {job.percent}%
            </span>
          </div>
          <div style={{ height: 6, background: "rgba(127,127,127,.25)", borderRadius: 3, overflow: "hidden" }}>
            <div
              style={{
                width: `${job.percent}%`,
                height: "100%",
                background: "var(--accent, #4e6fff)",
                transition: "width .3s ease",
              }}
            />
          </div>
          {job.log.length > 0 && (
            <details>
              <summary className="dim" style={{ fontSize: 12, cursor: "pointer" }}>
                服务端日志（最近 {job.log.length} 行）
              </summary>
              <pre
                className="mono"
                style={{
                  maxHeight: 180,
                  overflow: "auto",
                  fontSize: 11,
                  lineHeight: 1.5,
                  background: "rgba(0,0,0,.25)",
                  padding: 8,
                  borderRadius: 4,
                  margin: "6px 0 0",
                }}
              >
                {job.log.join("\n")}
              </pre>
            </details>
          )}
        </div>
      )}

      {error && (
        <p style={{ margin: 0, color: "#b3261e", fontSize: 13, whiteSpace: "pre-wrap" }}>{error}</p>
      )}

      {view && (
        <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
          <MapView view={view} meta={meta} exactStructures={exactStructures} />
          <p className="dim" style={{ margin: 0, fontSize: 12 }}>
            {view.partial ? "⚠ 生成超时，这张图不完整。" : "生成完成。"}
            {" "}1 像素 = 1 格。滚轮 / 双指缩放，拖动平移，双击复位，单击选点。
          </p>
          <div style={{ display: "flex", flexWrap: "wrap", gap: 8, alignItems: "center" }}>
            <button
              className="btn btn-text"
              disabled={exactLoading}
              onClick={() => void fetchExactStructures()}
              title="启动服务端执行 /locate，获取精确结构坐标"
            >
              {exactLoading ? "查询中…" : "精准结构"}
            </button>
            {exactStructures && (
              <span className="dim" style={{ fontSize: 12 }}>
                {exactStructures.exact ? "已用服务端校准" : "Java 内核估算"}
                {" · "}{exactStructures.structures.length} 个结构
              </span>
            )}
          </div>
          {structureRows.length > 0 && (
            <div style={{ display: "flex", flexWrap: "wrap", gap: 6, fontSize: 12 }}>
              {structureRows.map(([id, count]) => (
                <span
                  key={id}
                  className="mono"
                  style={{ padding: "2px 6px", borderRadius: 4, background: "rgba(127,127,127,.15)" }}
                >
                  {id} × {count}
                </span>
              ))}
            </div>
          )}
          {exactStructures && exactStructures.structures.length > 0 && (
            <details style={{ fontSize: 12 }}>
              <summary className="dim" style={{ cursor: "pointer" }}>结构坐标清单</summary>
              <div style={{ display: "flex", flexDirection: "column", gap: 2, marginTop: 6, maxHeight: 180, overflow: "auto" }}>
                {exactStructures.structures.map((s, i) => (
                  <div key={i} className="mono" style={{ display: "flex", gap: 8, padding: "2px 4px" }}>
                    <span style={{ minWidth: 140 }}>{s.id}</span>
                    <span>X {s.x} Z {s.z}</span>
                    {s.approximate && <span className="dim">约</span>}
                  </div>
                ))}
              </div>
            </details>
          )}
        </div>
      )}

      {flatHistory.length > 0 && (
        <div className="seed-history">
          <div className="seed-history-head">
            <h4 style={{ margin: 0, fontSize: 13 }}>试过的种子</h4>
            <span className="seed-history-count">
              {filteredGroups.length} / {history.length} 个种子 · {filteredCount} / {flatHistory.length} 条记录
            </span>
            <span className="seed-history-actions">
              <button
                className="btn btn-text"
                onClick={() => setExpandedSeeds(new Set(filteredGroups.map((group) => group.seed)))}
              >
                全部展开
              </button>
              <button className="btn btn-text" onClick={() => setExpandedSeeds(new Set())}>
                全部收起
              </button>
              <button
                className="btn btn-text"
                style={{ color: "#b3261e" }}
                disabled={deleting !== null}
                onClick={() => void clearHistory()}
              >
                {deleting === "all" ? "清空中…" : "清空全部"}
              </button>
            </span>
          </div>

          <div className="seed-filters">
            <label className="seed-filter seed-filter-seed">
              <span>种子</span>
              <input
                className="input mono"
                value={historyFilter.seed}
                placeholder="筛选…"
                onChange={(e) => setHistoryFilter((f) => ({ ...f, seed: e.target.value }))}
              />
            </label>
            <label className="seed-filter">
              <span>版本</span>
              <select
                className="input"
                value={historyFilter.version}
                onChange={(e) => setHistoryFilter((f) => ({ ...f, version: e.target.value }))}
              >
                <option value="">全部</option>
                {historyChoices.version.map((value) => (
                  <option key={value} value={value}>{value}</option>
                ))}
              </select>
            </label>
            <label className="seed-filter">
              <span>引擎</span>
              <select
                className="input"
                value={historyFilter.engine}
                onChange={(e) => setHistoryFilter((f) => ({ ...f, engine: e.target.value }))}
              >
                <option value="">全部</option>
                {historyChoices.engine.map((value) => (
                  <option key={value} value={value}>{value === "java" ? "Java" : "MC"}</option>
                ))}
              </select>
            </label>
            <label className="seed-filter">
              <span>清晰度</span>
              <select
                className="input"
                value={historyFilter.quality}
                onChange={(e) => setHistoryFilter((f) => ({ ...f, quality: e.target.value }))}
              >
                <option value="">全部</option>
                {historyChoices.quality.map((value) => (
                  <option key={value} value={value}>{qualityLabel(value)}</option>
                ))}
              </select>
            </label>
            <label className="seed-filter">
              <span>范围</span>
              <select
                className="input"
                value={historyFilter.radius}
                onChange={(e) => setHistoryFilter((f) => ({ ...f, radius: e.target.value }))}
              >
                <option value="">全部</option>
                {historyChoices.radius.map((value) => (
                  <option key={value} value={value}>{(Number(value) * 2).toLocaleString()} 格</option>
                ))}
              </select>
            </label>
            <button
              className="btn btn-text"
              disabled={!filtersActive}
              onClick={() => setHistoryFilter({ seed: "", version: "", engine: "", quality: "", radius: "" })}
            >
              重置
            </button>
          </div>

          <div className="seed-sort-row">
            <span className="seed-sort-label">组内排序</span>
            {SORT_LEVEL_LABELS.map((label, index) => (
              <label className="seed-sort-field" key={label}>
                <span>{label}</span>
                <select
                  className="input"
                  value={historySort[index]}
                  disabled={index > 0
                    && (historySort[0] === "time" || historySort[index - 1] === "none")}
                  onChange={(e) => setSortLevel(index, e.target.value as SeedSortChoice)}
                >
                  {index > 0 && (
                    <option value="none">
                      {historySort[0] === "time" ? "忽略" : "无"}
                    </option>
                  )}
                  {SEED_SORT_KEYS
                    .filter((key) => index === 0
                      || (key !== "time" && !historySort.slice(0, index).includes(key)))
                    .map((key) => (
                      <option key={key} value={key}>{SEED_SORT_LABELS[key]}</option>
                    ))}
                </select>
              </label>
            ))}
            <span className="seed-sort-hint">
              {historySort[0] === "time"
                ? "纯时间序：新→旧，其余键不参与"
                : "按 主 → 次 → 三 → 四；同级内按时间新→旧兜底"}
            </span>
          </div>

          {filteredGroups.length === 0 ? (
            <div className="empty-state seed-empty">没有匹配的种子记录。</div>
          ) : filteredGroups.map((group) => {
            const expanded = expandedSeeds.has(group.seed);
            const localFilter = groupFilters[group.seed] ?? EMPTY_GROUP_FILTER;
            const visibleVariants = group.variants
              .filter((entry) => matchesGroupFilter(entry, localFilter));
            // 过滤结果本身按时间倒序（filteredGroups 里排好），最新一条仍在首位；
            // 表格展示再按用户设的优先级链重排，两者互不干扰。
            const sortedVariants = sortSeedVariants(visibleVariants, historySort);
            const latest = visibleVariants[0] ?? group.variants[0];
            const busy = deleting === `seed:${group.seed}`;
            const localActive = Object.values(localFilter).some((value) => value !== "");
            // 只给这一组用的选项：别的种子用过的版本/档位不该出现在这里。
            const groupChoices = {
              version: [...new Set(group.variants.map((entry) => entry.version))].sort(compareSeedVersion),
              engine: [...new Set(group.variants.map((entry) => entry.engine ?? "java"))].sort(),
              quality: [...new Set(group.variants.map((entry) => entry.quality ?? "standard"))].sort(),
              radius: [...new Set(group.variants.map((entry) => String(entry.radius)))]
                .sort((a, b) => Number(a) - Number(b)),
            };
            return (
              <div className="seed-group" key={group.seed}>
                <div className="seed-group-row">
                  <button
                    className="seed-group-toggle"
                    aria-expanded={expanded}
                    onClick={() => toggleSeed(group.seed)}
                  >
                    <span className="seed-group-caret">{expanded ? "▾" : "▸"}</span>
                    <span className="seed-group-seed mono">{group.seed}</span>
                    <span className="seed-group-meta">
                      {localActive
                        ? `${visibleVariants.length} / ${group.variants.length}`
                        : group.variants.length} 条
                      {visibleVariants.length > 0
                        && ` · 最近 ${new Date(latest.generatedAt).toLocaleString()}`}
                    </span>
                  </button>
                  <span className="seed-group-actions">
                    <button className="btn btn-text" onClick={() => void openHistory(latest)}>
                      查看最新
                    </button>
                    <button
                      className="btn btn-text"
                      style={{ color: "#b3261e" }}
                      disabled={deleting !== null}
                      onClick={() => void removeSeedGroup(group.seed, group.variants)}
                    >
                      {busy ? "删除中…" : "删除整组"}
                    </button>
                  </span>
                </div>
                {expanded && (
                  <div className="seed-variants">
                    {group.variants.length > 1 && (
                      <div className="seed-group-filters">
                        <label className="seed-filter">
                          <span>版本</span>
                          <select
                            className="input"
                            value={localFilter.version}
                            onChange={(e) => updateGroupFilter(group.seed, { version: e.target.value })}
                          >
                            <option value="">全部</option>
                            {groupChoices.version.map((value) => (
                              <option key={value} value={value}>{value}</option>
                            ))}
                          </select>
                        </label>
                        <label className="seed-filter">
                          <span>引擎</span>
                          <select
                            className="input"
                            value={localFilter.engine}
                            onChange={(e) => updateGroupFilter(group.seed, { engine: e.target.value })}
                          >
                            <option value="">全部</option>
                            {groupChoices.engine.map((value) => (
                              <option key={value} value={value}>{value === "java" ? "Java" : "MC"}</option>
                            ))}
                          </select>
                        </label>
                        <label className="seed-filter">
                          <span>清晰度</span>
                          <select
                            className="input"
                            value={localFilter.quality}
                            onChange={(e) => updateGroupFilter(group.seed, { quality: e.target.value })}
                          >
                            <option value="">全部</option>
                            {groupChoices.quality.map((value) => (
                              <option key={value} value={value}>{qualityLabel(value)}</option>
                            ))}
                          </select>
                        </label>
                        <label className="seed-filter">
                          <span>范围</span>
                          <select
                            className="input"
                            value={localFilter.radius}
                            onChange={(e) => updateGroupFilter(group.seed, { radius: e.target.value })}
                          >
                            <option value="">全部</option>
                            {groupChoices.radius.map((value) => (
                              <option key={value} value={value}>
                                {(Number(value) * 2).toLocaleString()} 格
                              </option>
                            ))}
                          </select>
                        </label>
                        <button
                          className="btn btn-text"
                          disabled={!localActive}
                          onClick={() => updateGroupFilter(group.seed, EMPTY_GROUP_FILTER)}
                        >
                          重置
                        </button>
                      </div>
                    )}
                    <div className="seed-variant-head" aria-hidden="true">
                      <span>版本</span>
                      <span>引擎</span>
                      <span>清晰度</span>
                      <span>范围</span>
                      <span>结构</span>
                      <span>时间</span>
                      <span />
                    </div>
                    {visibleVariants.length === 0 ? (
                      <div className="empty-state seed-empty">这个种子下没有匹配的记录。</div>
                    ) : sortedVariants.map((entry) => {
                      const active = view?.source === "history" && view.id === entry.id;
                      return (
                        <div className={`seed-variant${active ? " active" : ""}`} key={entry.id}>
                          <span className="seed-cell" data-label="版本">{entry.version}</span>
                          <span className="seed-cell" data-label="引擎">{entry.engine === "server" ? "MC" : "Java"}</span>
                          <span className="seed-cell" data-label="清晰度">{qualityLabel(entry.quality)}</span>
                          <span className="seed-cell" data-label="范围">{(entry.radius * 2).toLocaleString()} 格</span>
                          <span className="seed-cell" data-label="结构">{entry.structures ?? "—"}</span>
                          <span className="seed-cell" data-label="时间">{new Date(entry.generatedAt).toLocaleString()}</span>
                          <span className="seed-variant-actions">
                            <button className="btn btn-text" onClick={() => void openHistory(entry)}>
                              查看
                            </button>
                            <button
                              className="btn btn-text"
                              style={{ color: "#b3261e" }}
                              disabled={deleting !== null}
                              onClick={() => void removeHistory(entry)}
                            >
                              删除
                            </button>
                          </span>
                        </div>
                      );
                    })}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

/// 结构标记的颜色，和后端 `PreviewRenderer.structureColor` 同一套。
function markerColor(id: string): string {
  if (id.startsWith("village")) return "#FFD54F";
  if (id.includes("monument")) return "#4FC3F7";
  if (id.includes("mansion")) return "#8D6E63";
  if (id === "stronghold") return "#E0E0E0";
  if (id.startsWith("mineshaft")) return "#A1887F";
  if (id.includes("fortress") || id.includes("bastion")) return "#B71C1C";
  if (id.includes("end_city")) return "#BA68C8";
  return "#E57373";
}

/** 比例尺候选长度（格）；按当前像素密度挑一根够长又不溢出容器的。 */
const SCALE_STEPS = [4, 8, 16, 32, 64, 128, 256, 512];

/**
 * 地图本体：PNG 地形 + SVG 覆盖层，缩放和平移都在一个 transform 里完成。
 *
 * 覆盖层画在方块坐标系里（viewBox 就是 ±radius），所以缩放时不用重新算任何
 * 东西；`vector-effect: non-scaling-stroke` 让线宽不跟着放大。
 *
 * 平移会夹在"图片始终铺满视口"的范围内——否则缩小后四边会露出底色，看起来
 * 像地图缺了一块（第一版就是这样，被当成生成失败）。
 */
function MapView({ view, meta, exactStructures }: { view: View; meta: McSeedPreviewMeta | null; exactStructures: McSeedPreviewStructures | null }) {
  const radius = view.radius;
  const size = radius * 2;
  const frameRef = useRef<HTMLDivElement>(null);
  const imageRef = useRef<HTMLImageElement>(null);
  const [box, setBox] = useState(0);
  const [boxH, setBoxH] = useState(0);
  const [scale, setScale] = useState(1);
  const [offset, setOffset] = useState({ x: 0, y: 0 });
  const [hover, setHover] = useState<{ x: number; z: number } | null>(null);
  const [pinned, setPinned] = useState<{ x: number; z: number } | null>(null);
  const [layers, setLayers] = useState({ grid: true, slime: true, structures: true });
  const [structureFilter, setStructureFilter] = useState<Record<string, boolean>>({});
  const [fullscreen, setFullscreen] = useState(false);
  const [jumpText, setJumpText] = useState("");
  const [searchText, setSearchText] = useState("");
  const [searchResults, setSearchResults] = useState<{ id: string; x: number; z: number }[]>([]);
  const [searchIndex, setSearchIndex] = useState(-1);
  const [measure, setMeasure] = useState<{ x: number; z: number }[]>([]);
  const [measuring, setMeasuring] = useState(false);

  const zoomRef = useRef<(factor: number, mx: number, my: number) => void>(() => {});
  const [dragging, setDragging] = useState(false);
  const pointers = useRef(new Map<number, { x: number; y: number }>());
  const drag = useRef<{ x: number; y: number; ox: number; oy: number; moved: boolean } | null>(null);
  const pinch = useRef<{ d0: number; s0: number; mx: number; my: number; ox: number; oy: number } | null>(null);

  useEffect(() => {
    const frame = frameRef.current;
    if (!frame) return;
    const update = () => {
      setBox(frame.clientWidth);
      setBoxH(frame.clientHeight);
    };
    update();
    const observer = new ResizeObserver(update);
    observer.observe(frame);
    return () => observer.disconnect();
  }, []);

  // A different picture means a different coordinate system: reset the viewport
  // so a new seed does not open somewhere off-screen.
  useEffect(() => {
    setScale(1);
    setOffset({ x: 0, y: 0 });
    setPinned(null);
    setHover(null);
  }, [view.id]);

  const clampOffset = (value: { x: number; y: number }, next: number) => {
    const width = box || 1;
    // 放大时夹在"图片始终铺满"的范围内；100% 时给一段越界余量，否则一拖就撞墙，
    // 看起来像拖动功能坏了。
    const margin = width * 0.25;
    const min = -(next - 1) * width - margin;
    const max = margin;
    return {
      x: Math.min(max, Math.max(min, value.x)),
      y: Math.min(max, Math.max(min, value.y)),
    };
  };

  const reset = () => {
    setScale(1);
    setOffset({ x: 0, y: 0 });
  };

  const imageUrl = withBase(
    view.source === "job"
      ? `/api/mc/preview/${view.id}.png`
      : `/api/mc/preview/history/${view.id}.png`,
  );

  const jumpTo = () => {
    const parts = jumpText.trim().split(/[\s,]+/);
    if (parts.length < 2) return;
    const x = Number(parts[0]);
    const z = Number(parts[1]);
    if (!Number.isFinite(x) || !Number.isFinite(z)) return;
    const target = { x, z };
    setPinned(target);
    // Centre the viewport on the target at a readable zoom.
    const nextScale = Math.max(2, Math.min(8, scale));
    const width = box || 1;
    setScale(nextScale);
    setOffset(
      clampOffset(
        {
          x: width / 2 - ((target.x + radius) / size) * width * nextScale,
          // 16:9 帧：纵向中心是 boxH/2，且 cover 裁掉的 (box-boxH)/2 随缩放一起放大
          y: boxH / 2 - ((target.z + radius) / size) * width * nextScale
            + nextScale * ((width - boxH) / 2),
        },
        nextScale,
      ),
    );
    setJumpText("");
  };

  const download = () => {
    const link = document.createElement("a");
    link.href = imageUrl;
    link.download = `seed-${view.seed}-${view.mode}-${radius * 2}.png`;
    link.click();
  };

  const allStructures = (exactStructures?.structures ?? meta?.structures ?? []);
  const structureTypes = [...new Set(allStructures.map((s) => s.id))].sort();
  const filteredStructures = allStructures.filter((s) => structureFilter[s.id] !== false);

  const searchStructures = () => {
    const query = searchText.trim().toLowerCase();
    if (!query) {
      setSearchResults([]);
      setSearchIndex(-1);
      return;
    }
    const matches = allStructures.filter((s) => s.id.toLowerCase().includes(query));
    setSearchResults(matches);
    setSearchIndex(matches.length > 0 ? 0 : -1);
    if (matches.length > 0) {
      centreOn(matches[0]);
    }
  };

  const centreOn = (target: { x: number; z: number }) => {
    setPinned(target);
    const nextScale = Math.max(2, Math.min(8, scale));
    const width = box || 1;
    setScale(nextScale);
    setOffset(
      clampOffset(
        {
          x: width / 2 - ((target.x + radius) / size) * width * nextScale,
          y: boxH / 2 - ((target.z + radius) / size) * width * nextScale
            + nextScale * ((width - boxH) / 2),
        },
        nextScale,
      ),
    );
  };

  const nextResult = (dir: number) => {
    if (searchResults.length === 0) return;
    const next = (searchIndex + dir + searchResults.length) % searchResults.length;
    setSearchIndex(next);
    centreOn(searchResults[next]);
  };

  const measureDistance = measure.length >= 2
    ? Math.hypot(measure[measure.length - 1].x - measure[0].x, measure[measure.length - 1].z - measure[0].z)
    : 0;

  const zoomAt = (factor: number, mx: number, my: number) => {
    const next = Math.min(16, Math.max(1, scale * factor));
    if (next === scale) return;
    const moved = {
      x: mx - ((mx - offset.x) * next) / scale,
      y: my - ((my - offset.y) * next) / scale,
    };
    setOffset(clampOffset(moved, next));
    setScale(next);
  };

  // React 把 onWheel 绑成 passive 监听，里面调 preventDefault 是无效的——页面照样
  // 跟着滚。只有原生 addEventListener + `{ passive: false }` 才能把滚轮留在图上。
  useEffect(() => {
    const frame = frameRef.current;
    if (!frame) return;
    const handler = (event: WheelEvent) => {
      event.preventDefault();
      const rect = frame.getBoundingClientRect();
      zoomRef.current(event.deltaY < 0 ? 1.15 : 1 / 1.15, event.clientX - rect.left, event.clientY - rect.top);
    };
    frame.addEventListener("wheel", handler, { passive: false });
    return () => frame.removeEventListener("wheel", handler);
  }, []);

  useEffect(() => {
    zoomRef.current = zoomAt;
  });

  const coordsAt = (clientX: number, clientY: number) => {
    const image = imageRef.current;
    if (!image) return null;
    // img 元素本身就是整张位图（显式 box×box 尺寸，见下方 style），
    // getBoundingClientRect 已含变换——框内直接归一化，无需任何裁剪补偿。
    const rect = image.getBoundingClientRect();
    const bx = clientX - rect.left;
    const by = clientY - rect.top;
    if (bx < 0 || by < 0 || bx > rect.width || by > rect.height) return null;
    return { x: Math.round((bx / rect.width) * size - radius), z: Math.round((by / rect.height) * size - radius) };
  };

  const onPointerDown = (event: React.PointerEvent) => {
    (event.currentTarget as HTMLElement).setPointerCapture?.(event.pointerId);
    pointers.current.set(event.pointerId, { x: event.clientX, y: event.clientY });
    if (pointers.current.size === 2) {
      drag.current = null;
      const [a, b] = [...pointers.current.values()];
      const rect = frameRef.current?.getBoundingClientRect();
      pinch.current = {
        d0: Math.max(1, Math.hypot(a.x - b.x, a.y - b.y)),
        s0: scale,
        mx: (a.x + b.x) / 2 - (rect?.left ?? 0),
        my: (a.y + b.y) / 2 - (rect?.top ?? 0),
        ox: offset.x,
        oy: offset.y,
      };
    } else if (pointers.current.size === 1) {
      drag.current = { x: event.clientX, y: event.clientY, ox: offset.x, oy: offset.y, moved: false };
      setDragging(true);
    }
  };

  const onPointerMove = (event: React.PointerEvent) => {
    if (pointers.current.has(event.pointerId)) {
      pointers.current.set(event.pointerId, { x: event.clientX, y: event.clientY });
    }
    if (pinch.current && pointers.current.size >= 2) {
      const [a, b] = [...pointers.current.values()];
      const distance = Math.max(1, Math.hypot(a.x - b.x, a.y - b.y));
      const started = pinch.current;
      const next = Math.min(16, Math.max(1, (started.s0 * distance) / started.d0));
      setScale(next);
      setOffset(
        clampOffset(
          {
            x: started.mx - ((started.mx - started.ox) * next) / started.s0,
            y: started.my - ((started.my - started.oy) * next) / started.s0,
          },
          next,
        ),
      );
      return;
    }
    const current = drag.current;
    if (current) {
      const dx = event.clientX - current.x;
      const dy = event.clientY - current.y;
      if (Math.abs(dx) + Math.abs(dy) > 3) current.moved = true;
      setOffset(clampOffset({ x: current.ox + dx, y: current.oy + dy }, scale));
    }
    setHover(coordsAt(event.clientX, event.clientY));
  };

  const onPointerUp = (event: React.PointerEvent) => {
    const wasClick = drag.current !== null && !drag.current.moved;
    pointers.current.delete(event.pointerId);
    if (pointers.current.size < 2) pinch.current = null;
    drag.current = null;
    setDragging(false);
    if (wasClick) {
      const point = coordsAt(event.clientX, event.clientY);
      if (point) {
        if (measuring) {
          setMeasure((prev) => [...prev, point]);
        } else {
          setPinned(point);
        }
      }
    }
  };

  // 网格随缩放换挡：放大到能看清区块时画 16 格（区块边界），再放大才画
  // 4 格；缩小回去就只剩大尺度的线。固定间隔会让放大后满屏只有两三根线。
  const pxPerBlock = ((box || 1) * scale) / size;
  const gridStep = [4, 8, 16, 32, 64, 128, 256].find((value) => value * pxPerBlock >= 36) ?? 256;
  const gridMajor = gridStep * 4;

  // 网格线只依赖网格开关与步进；标签额外依赖缩放（字号随缩放反向补偿）。
  // 8192² 大图拖动时每次 pointermove 都重建数千个 SVG 节点是网页卡顿的
  // 头号来源——memo 住，拖拽期间只更新 transform。
  const gridLayer = useMemo(() => {
    const lines = [];
    const labels = [];
    if (layers.grid) {
      for (let block = -radius; block <= radius; block += gridStep) {
        const major = block % gridMajor === 0;
        const stroke = major ? "#ffffff" : "#bfd4e8";
        const opacity = major ? 0.32 : 0.14;
        lines.push(
          <line key={`v${block}`} x1={block} y1={-radius} x2={block} y2={radius}
            stroke={stroke} strokeOpacity={opacity} strokeWidth={1} vectorEffect="non-scaling-stroke" />,
          <line key={`h${block}`} x1={-radius} y1={block} x2={radius} y2={block}
            stroke={stroke} strokeOpacity={opacity} strokeWidth={1} vectorEffect="non-scaling-stroke" />,
        );
        if (major) {
          // Tick labels: the font is scaled down by the zoom so the text keeps
          // the same size on screen whatever the viewport does.
          labels.push(
            <text key={`tx${block}`} x={block} y={-radius + 14 / scale} fontSize={11 / scale}
              fill="#d6e4f0" fillOpacity={0.75} textAnchor="middle">{block}</text>,
            <text key={`tz${block}`} x={-radius + 4 / scale} y={block + 4 / scale} fontSize={11 / scale}
              fill="#d6e4f0" fillOpacity={0.75} textAnchor="start">{block}</text>,
          );
        }
      }
    }
    return { lines, labels };
  }, [layers.grid, gridStep, gridMajor, radius, scale]);
  const lines = gridLayer.lines;
  const labels = gridLayer.labels;

  const barBlocks = SCALE_STEPS.find((value) => value * pxPerBlock >= 56) ?? SCALE_STEPS[SCALE_STEPS.length - 1];
  const barWidth = barBlocks * pxPerBlock;

  // Only paint structures inside the current viewport, and thin them out when
  // the whole region is visible. A 16k×16k Java map can carry thousands of
  // markers; drawing them all at once turns the picture into white noise.
  const visibleStructures = useMemo(() => filteredStructures.filter((s) => {
    if (scale <= 1.5) return true;
    const left = (-offset.x / scale / (box || 1)) * size - radius;
    const top = (-offset.y / scale / (box || 1)) * size - radius;
    const right = ((box - offset.x) / scale / (box || 1)) * size - radius;
    const bottom = ((box - offset.y) / scale / (box || 1)) * size - radius;
    return s.x >= left && s.x <= right && s.z >= top && s.z <= bottom;
  }).slice(0, 300), [filteredStructures, scale, offset.x, offset.y, box, size, radius]);

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
      <div
        ref={frameRef}
        tabIndex={0}
        onKeyDown={(event) => {
          const step = 60;
          if (event.key === "+" || event.key === "=") {
            event.preventDefault();
            zoomAt(1.2, box / 2, box / 2);
          } else if (event.key === "-" || event.key === "_") {
            event.preventDefault();
            zoomAt(1 / 1.2, box / 2, box / 2);
          } else if (event.key === "0") {
            event.preventDefault();
            reset();
          } else if (event.key === "Escape" && fullscreen) {
            event.preventDefault();
            setFullscreen(false);
          } else if (event.key === "ArrowLeft") {
            event.preventDefault();
            setOffset((o) => clampOffset({ x: o.x + step, y: o.y }, scale));
          } else if (event.key === "ArrowRight") {
            event.preventDefault();
            setOffset((o) => clampOffset({ x: o.x - step, y: o.y }, scale));
          } else if (event.key === "ArrowUp") {
            event.preventDefault();
            setOffset((o) => clampOffset({ x: o.x, y: o.y + step }, scale));
          } else if (event.key === "ArrowDown") {
            event.preventDefault();
            setOffset((o) => clampOffset({ x: o.x, y: o.y - step }, scale));
          }
        }}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={onPointerUp}
        onPointerLeave={() => {
          pointers.current.clear();
          drag.current = null;
          pinch.current = null;
          setDragging(false);
          setHover(null);
        }}
        onDoubleClick={reset}
        style={{
          position: fullscreen ? "fixed" : "relative",
          inset: fullscreen ? 0 : undefined,
          zIndex: fullscreen ? 1000 : undefined,
          width: "100%",
          maxWidth: fullscreen ? undefined : "1080px",
          margin: fullscreen ? undefined : "0 auto",
          aspectRatio: fullscreen ? undefined : "16 / 9",
          overflow: "hidden",
          borderRadius: fullscreen ? 0 : 6,
          border: "1px solid rgba(127,127,127,.25)",
          cursor: dragging ? "grabbing" : "grab",
          userSelect: "none",
          touchAction: "none",
          outline: "none",
          background: "#141418",
        }}
      >
        <div
          style={{
            position: "absolute",
            inset: 0,
            transformOrigin: "0 0",
            transform: `translate(${offset.x}px, ${offset.y}px) scale(${scale})`,
          }}
        >
          <img
            ref={imageRef}
            src={`${imageUrl}?v=${view.id}`}
            alt={`种子 ${view.seed} 的地图`}
            draggable={false}
            style={{
              // 显式像素尺寸摆放，不用 object-fit：cover 会触发 Chrome 的
              // "适配快照"光栅路径——pixelated 失效、缩放不再按原图重采样，
              // 地图就成了"图片放大缩小器"。这里用与 cover 完全等价的几何
              // （宽 = 帧宽，垂直居中、超出裁掉）走常规图片栅格，缩放随拖随锐。
              position: "absolute",
              left: 0,
              top: (boxH - box) / 2,
              width: box || "100%",
              height: box || "100%",
              display: "block",
              // 群系图是精确到方块边界的采样，放大时必须保持色块锐利；
              // 平滑插值会让 590% 时变成一团渐变色。
              imageRendering: "pixelated",
            }}
          />
          <svg
            viewBox={`${-radius} ${-radius} ${size} ${size}`}
            preserveAspectRatio="xMidYMid slice"
            style={{ position: "absolute", inset: 0, width: "100%", height: "100%", pointerEvents: "none" }}
          >
            {lines}
            {labels}
            {layers.slime &&
              meta?.slimeChunks?.map((chunk) => (
                <rect key={`s${chunk.x}:${chunk.z}`} x={chunk.x * 16} y={chunk.z * 16}
                  width={16} height={16} fill="#59c46a" fillOpacity={0.3} />
              ))}
            {layers.structures &&
              visibleStructures?.map((structure, index) => (
                <g key={`m${index}`}>
                  <rect x={structure.x - 2} y={structure.z - 2} width={4} height={4}
                    fill={markerColor(structure.id)} stroke="#1a1a1a" strokeWidth={1}
                    vectorEffect="non-scaling-stroke"
                    transform={`rotate(45 ${structure.x} ${structure.z})`} />
                  {!structure.approximate && (
                    <circle cx={structure.x} cy={structure.z} r={4}
                      fill="none" stroke="#ffffff" strokeWidth={1} vectorEffect="non-scaling-stroke" />
                  )}
                </g>
              ))}
            <line x1={-8} y1={0} x2={8} y2={0} stroke="#e53935" strokeWidth={2} vectorEffect="non-scaling-stroke" />
            <line x1={0} y1={-8} x2={0} y2={8} stroke="#e53935" strokeWidth={2} vectorEffect="non-scaling-stroke" />
            {pinned && (
              <g>
                <line x1={pinned.x - 14 / scale} y1={pinned.z} x2={pinned.x + 14 / scale} y2={pinned.z}
                  stroke="#ffffff" strokeWidth={2} vectorEffect="non-scaling-stroke" />
                <line x1={pinned.x} y1={pinned.z - 14 / scale} x2={pinned.x} y2={pinned.z + 14 / scale}
                  stroke="#ffffff" strokeWidth={2} vectorEffect="non-scaling-stroke" />
              </g>
            )}
            {measure.length > 0 && (
              <g>
                <polyline
                  points={measure.map((p) => `${p.x},${p.z}`).join(" ")}
                  fill="none" stroke="#ffeb3b" strokeWidth={2} vectorEffect="non-scaling-stroke"
                />
                {measure.map((p, i) => (
                  <circle key={i} cx={p.x} cy={p.z} r={3 / scale} fill="#ffeb3b" />
                ))}
              </g>
            )}
          </svg>
        </div>

        {fullscreen && (
          <button
            className="btn btn-raised"
            style={{ position: "absolute", right: 12, top: 12, zIndex: 1001 }}
            onClick={() => setFullscreen(false)}
          >
            退出全屏 (Esc)
          </button>
        )}
        <div
          className="mono"
          style={{
            position: "absolute",
            left: 8,
            top: 8,
            padding: "2px 8px",
            borderRadius: 4,
            fontSize: 12,
            lineHeight: 1.6,
            color: "#fff",
            background: "rgba(0,0,0,.55)",
            pointerEvents: "none",
          }}
        >
          {pinned ? `📍 X ${pinned.x}  Z ${pinned.z}` : hover ? `X ${hover.x}  Z ${hover.z}` : `种子 ${view.seed}`}
        </div>

        {/* 比例尺：随缩放变化，像地图软件左下角那根 */}
        <div style={{ position: "absolute", left: 10, bottom: 10, pointerEvents: "none", color: "#e8eef4", fontSize: 11 }}>
          <div style={{ width: barWidth, height: 4, borderLeft: "1px solid #e8eef4", borderRight: "1px solid #e8eef4", borderBottom: "1px solid #e8eef4" }} />
          <div className="mono" style={{ marginTop: 2, textShadow: "0 1px 2px rgba(0,0,0,.8)" }}>{barBlocks} 格</div>
        </div>
      </div>

      <div style={{ display: "flex", flexWrap: "wrap", gap: 12, alignItems: "center", fontSize: 12 }}>
        <label style={{ display: "flex", gap: 4, alignItems: "center" }}>
          <input type="checkbox" checked={layers.grid}
            onChange={(e) => setLayers({ ...layers, grid: e.target.checked })} />
          网格
        </label>
        <label style={{ display: "flex", gap: 4, alignItems: "center" }}>
          <input type="checkbox" checked={layers.slime}
            onChange={(e) => setLayers({ ...layers, slime: e.target.checked })} />
          <span style={{ display: "inline-block", width: 10, height: 10, background: "#59c46a", opacity: 0.55 }} />
          史莱姆区块
        </label>
        <label style={{ display: "flex", gap: 4, alignItems: "center" }}>
          <input type="checkbox" checked={layers.structures}
            onChange={(e) => setLayers({ ...layers, structures: e.target.checked })} />
          <span style={{ display: "inline-block", width: 10, height: 10, background: "#FFD54F", borderRadius: 5 }} />
          结构
        </label>
        {structureTypes.length > 0 && (
          <details style={{ fontSize: 12 }}>
            <summary className="dim" style={{ cursor: "pointer" }}>按类型筛选</summary>
            <div style={{ display: "flex", flexWrap: "wrap", gap: 6, marginTop: 4, maxHeight: 120, overflow: "auto" }}>
              {structureTypes.map((id) => (
                <label key={id} style={{ display: "flex", gap: 2, alignItems: "center", fontSize: 11 }}>
                  <input
                    type="checkbox"
                    checked={structureFilter[id] !== false}
                    onChange={(e) => setStructureFilter({ ...structureFilter, [id]: e.target.checked })}
                  />
                  {id}
                </label>
              ))}
            </div>
          </details>
        )}
        <span style={{ flex: 1 }} />
        <span style={{ display: "inline-flex", gap: 4, alignItems: "center", flex: "0 0 auto" }}>
          <input
            className="input mono"
            style={{ width: 130, padding: "2px 6px", fontSize: 12 }}
            placeholder="搜索结构"
            value={searchText}
            onChange={(e) => setSearchText(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter") searchStructures();
            }}
          />
          <button className="btn btn-text" onClick={searchStructures} title="搜索结构">
            搜索
          </button>
        </span>
        {searchResults.length > 0 && (
          <>
            <button className="btn btn-text" onClick={() => nextResult(-1)} title="上一个结果">
              ↑
            </button>
            <span className="dim mono">{searchIndex + 1}/{searchResults.length}</span>
            <button className="btn btn-text" onClick={() => nextResult(1)} title="下一个结果">
              ↓
            </button>
          </>
        )}
        <span style={{ display: "inline-flex", gap: 4, alignItems: "center", flex: "0 0 auto" }}>
          <input
            className="input mono"
            style={{ width: 130, padding: "2px 6px", fontSize: 12 }}
            placeholder="X Z 跳转"
            value={jumpText}
            onChange={(e) => setJumpText(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter") jumpTo();
            }}
          />
          <button className="btn btn-text" onClick={jumpTo} title="跳转到坐标">
            跳转
          </button>
        </span>
        <span className="dim mono">{Math.round(scale * 100)}%</span>
        {pinned && (
          <button className="btn btn-text" onClick={() => setPinned(null)}>
            取消选点
          </button>
        )}
        <button
          className="btn btn-text"
          onClick={() => {
            setMeasuring(!measuring);
            if (!measuring) setMeasure([]);
          }}
          title="测距：依次点击地图上的点"
        >
          {measuring ? "完成测距" : "测距"}
        </button>
        {measuring && measure.length > 0 && (
          <button className="btn btn-text" onClick={() => setMeasure([])} title="清除测距点">
            清除
          </button>
        )}
        <button className="btn btn-text" onClick={download} title="下载 PNG">
          下载
        </button>
        <button className="btn btn-text" onClick={() => setFullscreen(!fullscreen)}>
          {fullscreen ? "退出全屏" : "全屏"}
        </button>
        <button className="btn btn-text" onClick={reset}>
          复位
        </button>
      </div>

      {/* 坐标读数：固定在地图下面，像 CAD 的状态栏 */}
      <div
        className="mono"
        style={{
          display: "flex",
          flexWrap: "wrap",
          gap: 16,
          alignItems: "center",
          fontSize: 12,
          padding: "6px 10px",
          borderRadius: 4,
          background: "rgba(127,127,127,.10)",
        }}
      >
        <span>X <b>{hover ? hover.x : pinned ? pinned.x : "—"}</b></span>
        <span>Z <b>{hover ? hover.z : pinned ? pinned.z : "—"}</b></span>
        <span className="dim">
          中心 (0, 0) · 范围 {size} 格 · 网格 {gridStep} 格
        </span>
        <span style={{ flex: 1 }} />
        {pinned && (
          <button
            className="btn btn-text"
            style={{ padding: "0 6px" }}
            onClick={() => void navigator.clipboard?.writeText(`${pinned.x} ${pinned.z}`)}
          >
            复制坐标
          </button>
        )}
        {measure.length >= 2 && (
          <span style={{ color: "#ffeb3b" }}>距离 {Math.round(measureDistance)} 格</span>
        )}
        <span className="dim">{Math.round(scale * 100)}% · {Math.round(pxPerBlock * 100) / 100} px/格</span>
      </div>
    </div>
  );
}
