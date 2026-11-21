/*
 * HDSL-web
 * Copyright (C) 2026  HDSL-web contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.web.minecraft.preview;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jackhuang.hmcl.web.config.ServerConfig;
import org.jackhuang.hmcl.web.minecraft.McException;
import org.jackhuang.hmcl.web.minecraft.McInstanceManager;
import org.jackhuang.hmcl.web.minecraft.McJavaRuntimes;
import org.jackhuang.hmcl.web.minecraft.McPorts;
import org.jackhuang.hmcl.web.minecraft.McVersions;
import org.jackhuang.hmcl.web.minecraft.seedmap.adapter.SeedMapBackend;
import org.jackhuang.hmcl.web.minecraft.seedmap.core.BlockPos;
import org.jackhuang.hmcl.web.minecraft.seedmap.core.SeedMap;
import org.jackhuang.hmcl.web.minecraft.seedmap.core.Structure;
import org.jackhuang.hmcl.web.minecraft.seedmap.core.StructureType;
import org.jackhuang.hmcl.web.minecraft.seedmap.core.Version;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/// Runs the real Minecraft server once, reads what it wrote, and draws a map.
///
/// The panel does **not** re-implement world generation. Terrain generation
/// changed completely in 1.18 (density functions, multi-noise biomes) and again
/// in smaller ways every release; a re-implementation would be wrong on the
/// first day and unmaintainable on the second. Instead the panel borrows the
/// same `server.jar` it already caches for instances, points it at a throwaway
/// directory, asks it to generate a square of chunks, and parses the region
/// files it produced. Whatever the game does, the map does.
///
/// Three things make this affordable:
///
/// - the jar is already on disk for every installed version (see
///   {@link McVersions}), so a preview usually needs no download at all;
/// - generation is bounded by the requested radius and the process is killed on
///   timeout, so a bad request cannot wedge the panel;
/// - only one preview runs at a time. It is the most expensive thing the panel
///   does, and two concurrent runs would make every other page feel slow.
///
/// The throwaway world is deleted when the picture is written; only the PNG and
/// its metadata are kept, and they are keyed by version, seed, radius and slice
/// so asking twice costs nothing.
@NotNullByDefault
public final class SeedPreviewService {

    /// How long a server may take to print `Done` before the preview gives up.
    private static final Duration BOOT_TIMEOUT = Duration.ofMinutes(3);
    /// How long generation may take. Whatever exists then is still rendered,
    /// marked partial, because a slow machine should get a picture and a
    /// warning rather than nothing.
    private static final Duration GENERATE_TIMEOUT = Duration.ofMinutes(6);
    /// EXTREME renders trade minutes for pixels; the default covers a slow
    /// shared host at the largest radius with headroom, and operators with
    /// patience (or a very small box) can raise it per-minute by environment.
    private static final Duration EXTREME_TIMEOUT = extremeTimeout();

    private static Duration extremeTimeout() {
        String env = System.getenv("HDSL_PREVIEW_EXTREME_TIMEOUT_MINUTES");
        if (env != null && !env.isBlank()) {
            try {
                int minutes = Integer.parseInt(env.trim());
                if (minutes >= 1 && minutes <= 24 * 60) {
                    return Duration.ofMinutes(minutes);
                }
                org.jackhuang.hmcl.util.logging.Logger.LOG.warning(
                        "Ignoring HDSL_PREVIEW_EXTREME_TIMEOUT_MINUTES=" + env
                        + " (want 1..1440)");
            } catch (NumberFormatException e) {
                org.jackhuang.hmcl.util.logging.Logger.LOG.warning(
                        "Ignoring HDSL_PREVIEW_EXTREME_TIMEOUT_MINUTES=" + env
                        + " (not a number)");
            }
        }
        return Duration.ofMinutes(60);
    }
    /// How long `stop` may take to end the process.
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(90);
    /// How many finished previews are kept. A player trying seeds in a row
    /// expects the last ones to still be there when they come back, so the
    /// cache is counted in results, not megabytes.
    public static final int MAX_HISTORY = 30;
    /// How many finished jobs are remembered for status polling.
    private static final int MAX_JOBS = 64;

    private final Path cacheRoot;
    private final Path tmpRoot;
    private final McVersions versions;
    private final McJavaRuntimes javaRuntimes;

    private final Map<String, PreviewJob> jobs = new ConcurrentHashMap<>();
    private final Map<String, String> runningByKey = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "seed-preview");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicInteger active = new AtomicInteger();

    /// @param config       the panel configuration (for its data directory)
    /// @param versions     the shared server-jar cache
    /// @param javaRuntimes the JREs the panel manages
    public SeedPreviewService(ServerConfig config, McVersions versions, McJavaRuntimes javaRuntimes) {
        this.cacheRoot = config.dataDir.resolve("cache").resolve("seed-preview");
        this.tmpRoot = cacheRoot.resolve("tmp");
        this.versions = versions;
        this.javaRuntimes = javaRuntimes;
    }

    // -------------------------------------------------------------------- API --

    /// Structure positions for a seed. By default the Java core answers; with
    /// `exact=true` the panel starts a real server and runs `/locate` to
    /// confirm the nearest candidate of each type.
    public JsonObject structures(String versionId, String seedText, int radius, String typeParam, boolean exact)
            throws IOException, InterruptedException {
        Version version = Version.parse(versionId);
        long seed = PreviewRenderer.parseSeed(seedText);
        SeedMap map = SeedMap.of(version, seed);
        List<StructureType> types = new ArrayList<>();
        if (typeParam != null && !typeParam.isBlank()) {
            for (String name : typeParam.split(",")) {
                types.add(StructureType.valueOf(name.trim().toUpperCase(Locale.ROOT).replace('-', '_')));
            }
        } else {
            types.add(StructureType.VILLAGE);
            types.add(StructureType.DESERT_PYRAMID);
            types.add(StructureType.JUNGLE_TEMPLE);
            types.add(StructureType.IGLOO);
            types.add(StructureType.SWAMP_HUT);
            types.add(StructureType.WOODLAND_MANSION);
            types.add(StructureType.OCEAN_MONUMENT);
            types.add(StructureType.OCEAN_RUIN);
            types.add(StructureType.SHIPWRECK);
            types.add(StructureType.RUINED_PORTAL);
            types.add(StructureType.PILLAGER_OUTPOST);
            types.add(StructureType.ANCIENT_CITY);
            types.add(StructureType.TRIAL_CHAMBERS);
        }

        JsonObject body = new JsonObject();
        body.addProperty("version", versionId);
        body.addProperty("seed", seedText);
        body.addProperty("radius", radius);
        body.addProperty("exact", exact);
        JsonArray out = new JsonArray();
        for (StructureType type : types) {
            List<Structure> found = map.structures(type, -radius, -radius, radius, radius);
            for (Structure structure : found) {
                JsonObject entry = new JsonObject();
                entry.addProperty("id", type.key());
                entry.addProperty("x", structure.pos().x());
                entry.addProperty("z", structure.pos().z());
                entry.addProperty("approximate", true);
                out.add(entry);
            }
        }
        body.add("structures", out);
        if (exact) {
            refineWithServer(body, versionId, seedText, radius, types);
        }
        return body;
    }

    private void refineWithServer(JsonObject body, String versionId, String seedText, int radius,
                                  List<StructureType> types) throws IOException, InterruptedException {
        Path work = null;
        Process process = null;
        try {
            Path jar;
            try {
                jar = versions.ensureServerJar(versionId, null);
            } catch (McException e) {
                body.addProperty("exactError", e.getMessage());
                return;
            }
            int required = Math.max(8, McJavaRuntimes.recommendFor(versionId));
            Path java = javaFor(required);
            if (java == null) {
                body.addProperty("exactError", "需要 Java " + required);
                return;
            }
            work = Files.createDirectories(tmpRoot.resolve("locate-" + UUID.randomUUID()));
            writeProperties(work, new PreviewSpec(versionId, seedText, radius, 63, PreviewSpec.Mode.DETAIL), freePort());
            Files.writeString(work.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
            final Process server = start(java, jar, work, required, radius);
            process = server;
            CountDownLatch ready = new CountDownLatch(1);
            List<String> logs = Collections.synchronizedList(new ArrayList<>());
            Thread pump = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        logs.add(line);
                        if (line.contains("Done (") || line.contains("Done in")) {
                            ready.countDown();
                        }
                    }
                } catch (IOException ignored) {
                } finally {
                    ready.countDown();
                }
            });
            pump.setDaemon(true);
            pump.start();
            if (!ready.await(BOOT_TIMEOUT.toSeconds(), TimeUnit.SECONDS) || !process.isAlive()) {
                body.addProperty("exactError", "服务端启动失败");
                return;
            }

            JsonArray refined = new JsonArray();
            for (StructureType type : types) {
                String command = locateCommand(type, versionId);
                if (command == null) {
                    continue;
                }
                int logMark = logs.size();
                send(process, command);
                // The locate answer arrives asynchronously; poll the new tail
                // until it says either "The nearest" or "There is no structure".
                String answer = null;
                for (int attempt = 0; attempt < 40; attempt++) {
                    Thread.sleep(250);
                    answer = latestLocate(logs, logMark);
                    if (answer != null) {
                        break;
                    }
                    if (hasNoStructure(logs, logMark)) {
                        break;
                    }
                }
                if (answer != null) {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("id", type.key());
                    entry.addProperty("x", parseLocateX(answer));
                    entry.addProperty("z", parseLocateZ(answer));
                    entry.addProperty("approximate", false);
                    refined.add(entry);
                }
            }
            body.add("structures", refined);
            send(process, "stop");
            process.waitFor(STOP_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            deleteTree(work);
        }
    }

    private static @Nullable String locateCommand(StructureType type, String versionId) {
        String name = switch (type) {
            case VILLAGE -> "village_plains";
            case DESERT_PYRAMID -> "desert_pyramid";
            case JUNGLE_TEMPLE -> "jungle_pyramid";
            case IGLOO -> "igloo";
            case SWAMP_HUT -> "swamp_hut";
            case WOODLAND_MANSION -> "mansion";
            case OCEAN_MONUMENT -> "monument";
            case OCEAN_RUIN -> "ocean_ruin_cold";
            case SHIPWRECK -> "shipwreck";
            case RUINED_PORTAL -> "ruined_portal";
            case PILLAGER_OUTPOST -> "pillager_outpost";
            case ANCIENT_CITY -> "ancient_city";
            case TRIAL_CHAMBERS -> "trial_chambers";
            default -> null;
        };
        return name == null ? null : "locate structure minecraft:" + name;
    }

    private static @Nullable String latestLocate(List<String> logs, int fromIndex) {
        for (int i = logs.size() - 1; i >= fromIndex; i--) {
            String line = logs.get(i);
            if (line.contains("The nearest") && line.contains("is at")) {
                return line;
            }
        }
        return null;
    }

    private static boolean hasNoStructure(List<String> logs, int fromIndex) {
        for (int i = logs.size() - 1; i >= fromIndex; i--) {
            if (logs.get(i).contains("There is no structure")) {
                return true;
            }
        }
        return false;
    }

    private static int parseLocateX(String line) {
        return parseCoordinate(line, 0);
    }

    private static int parseLocateZ(String line) {
        return parseCoordinate(line, 2);
    }

    private static int parseCoordinate(String line, int index) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\[(-?\\d+|~),\\s*(-?\\d+|~),\\s*(-?\\d+|~)\\]").matcher(line);
        if (!matcher.find()) {
            return 0;
        }
        String value = matcher.group(index + 1);
        return "~".equals(value) ? 0 : Integer.parseInt(value);
    }

    /// Whether the panel can preview a version right now.
    ///
    /// @param version the Minecraft version id
    /// @return what the UI needs to decide between "preview" and "install first"
    public JsonObject capabilities(String version) {
        JsonObject body = new JsonObject();
        body.addProperty("version", version);
        body.addProperty("jarPresent", versions.hasServerJar(version));
        int required = McJavaRuntimes.recommendFor(version);
        body.addProperty("javaRequired", required);
        Path java = javaFor(required);
        body.addProperty("javaReady", java != null);
        body.addProperty("javaPath", java == null ? "" : java.toString());
        body.addProperty("running", active.get() > 0);
        // 预计等待时间的分母：渲染机的核数（客户端 hardwareConcurrency 是浏览器
        // 所在机器的，跟渲染发生在哪里毫无关系）。
        body.addProperty("cores", Runtime.getRuntime().availableProcessors());
        return body;
    }

    /// Starts (or reuses) a preview.
    ///
    /// @param spec the request
    /// @return the job; poll it until it is done
    public PreviewJob submit(PreviewSpec spec) {
        String key = spec.key();
        String runningId = runningByKey.get(key);
        if (runningId != null) {
            PreviewJob running = jobs.get(runningId);
            if (running != null && !running.finished()) {
                return running;
            }
        }
        Path directory = cacheDirectory(spec);
        Path cached = directory.resolve("preview.png");
        if (Files.isRegularFile(cached)) {
            PreviewJob hit = new PreviewJob(newId(), spec);
            hit.done(cached, Files.isRegularFile(directory.resolve("meta.json"))
                    ? directory.resolve("meta.json") : null, false);
            remember(hit);
            return hit;
        }
        PreviewJob job = new PreviewJob(newId(), spec);
        remember(job);
        runningByKey.put(key, job.id());
        active.incrementAndGet();
        workers.submit(() -> {
            try {
                run(job);
            } finally {
                active.decrementAndGet();
                runningByKey.remove(key, job.id());
            }
        });
        return job;
    }

    /// The job with that id, or `null`.
    public @Nullable PreviewJob job(String id) {
        return jobs.get(id);
    }

    /// Cancels a job, killing its server if it has one.
    public boolean cancel(String id) {
        PreviewJob job = jobs.get(id);
        if (job == null || job.finished()) {
            return false;
        }
        job.cancel();
        return true;
    }

    // ------------------------------------------------------------------- work --

    private void run(PreviewJob job) {
        PreviewSpec spec = job.spec();
        if (spec.engine() == PreviewSpec.Engine.JAVA) {
            if (spec.mode() != PreviewSpec.Mode.BIOME) {
                failOrCancel(job, "Java 内核只支持群系速览，详细地图请使用 MC 服务器方案");
                return;
            }
            if (SeedMapBackend.supports(spec.version())) {
                try {
                    runBiomeCore(job);
                    return;
                } catch (IOException | RuntimeException e) {
                    job.log("Java 群系内核失败，回退到服务端生成: " + e.getMessage());
                    // fall through to the real-server backend
                }
            } else {
                job.log("该版本暂不支持 Java 内核，回退到服务端生成");
            }
        }
        Path work = null;
        try {
            if (job.cancelled()) {
                job.markCancelled();
                return;
            }
            job.state(PreviewJob.State.RESOLVING);
            Path jar = versions.ensureServerJar(spec.version(), null);
            int required = Math.max(8, McJavaRuntimes.recommendFor(spec.version()));
            Path java = javaFor(required);
            if (java == null) {
                throw new McException("这个版本需要 Java " + required + "。面板自带的 Java 太旧，"
                        + "面板托管的运行时里也没有——请先在「Minecraft → Java 运行时」里安装它。");
            }

            job.state(PreviewJob.State.STARTING);
            work = Files.createDirectories(tmpRoot.resolve(job.id()));
            writeProperties(work, spec, freePort());
            Files.writeString(work.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);

            Process process = start(java, jar, work, required, spec.radiusBlocks());
            job.attach(process);
            CountDownLatch ready = new CountDownLatch(1);
            pump(process, job, ready);
            // Wait for `Done (`, but stop early when the process is already
            // gone: a crash must be reported with its output, not as a timeout
            // three minutes later and then a bare "Stream closed" on the first
            // write.
            awaitBoot(process, ready, job);
            if (job.cancelled()) {
                job.markCancelled();
                return;
            }

            job.state(PreviewJob.State.GENERATING);
            requestChunks(process, spec);
            Path regionDirectory = regionDirectory(work);
            boolean complete = waitForChunks(job, regionDirectory, spec, process);
            if (job.cancelled()) {
                job.markCancelled();
                return;
            }
            long present = countInRange(regionDirectory, spec);

            job.state(PreviewJob.State.SAVING);
            send(process, supportsFlush(spec.version()) ? "save-all flush" : "save-all");
            send(process, "stop");
            boolean stopped = process.waitFor(STOP_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            if (!stopped) {
                process.destroyForcibly();
                process.waitFor(15, TimeUnit.SECONDS);
            }

            job.state(PreviewJob.State.PARSING);
            List<RegionReader.ChunkSummary> chunks = readChunks(regionDirectory, spec);
            if (chunks.isEmpty()) {
                throw new McException("服务端没有生成任何区块（" + present + " 个落盘），无法出图。"
                        + tail(job));
            }

            job.state(PreviewJob.State.RENDERING);
            int[] first = {spec.firstChunk()};
            int step = spec.sampleStep();
            String[][] samples = new String[0][];
            BufferedImage image;
            if (spec.mode() == PreviewSpec.Mode.BIOME) {
                int cells = PreviewSpec.BIOME_SAMPLES;
                samples = new String[cells][cells];
                for (RegionReader.ChunkSummary chunk : chunks) {
                    int i = Math.floorDiv(chunk.chunkX() - first[0], step);
                    int j = Math.floorDiv(chunk.chunkZ() - first[0], step);
                    if (i >= 0 && j >= 0 && i < cells && j < cells) {
                        samples[j][i] = chunk.biomes()[0];
                    }
                }
                image = PreviewRenderer.renderBiome(samples, spec);
            } else {
                image = PreviewRenderer.render(chunks, spec);
            }
            Path directory = Files.createDirectories(cacheDirectory(spec));
            Path png = directory.resolve("preview.png");
            Path meta = directory.resolve("meta.json");
            ImageIO.write(image, "png", png.toFile());
            if (spec.mode() == PreviewSpec.Mode.BIOME) {
                SeedMap map = null;
                if (SeedMapBackend.supports(spec.version())) {
                    map = SeedMap.of(Version.parse(spec.version()), PreviewRenderer.parseSeed(spec.seed()));
                }
                writeBiomeMeta(meta, spec, samples, map, !complete);
            } else {
                writeMeta(meta, spec, chunks, !complete);
            }
            job.done(png, meta, !complete);
            trimCache();
        } catch (McException e) {
            failOrCancel(job, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failOrCancel(job, "预览被中断");
        } catch (IOException | RuntimeException e) {
            failOrCancel(job, e.getMessage() == null ? e.toString() : e.getMessage());
        } finally {
            Process process = job.process();
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            deleteTree(work);
        }
    }

    private void runBiomeCore(PreviewJob job) throws IOException {
        PreviewSpec spec = job.spec();
        if (job.cancelled()) {
            job.markCancelled();
            return;
        }
        job.state(PreviewJob.State.RENDERING);
        if (spec.quality() == PreviewSpec.Quality.EXTREME) {
            // 8192×8192 的采样网格（光引用就半个 GB）加同尺寸位图，远超面板
            // 默认的 1G 堆——整个渲染放进子进程，面板 JVM 一片叶子都不多占。
            runBiomeCoreExtreme(job, spec);
            return;
        }
        SeedMapBackend.Result result = SeedMapBackend.render(spec, job::renderProgress);
        Path directory = Files.createDirectories(cacheDirectory(spec));
        Path png = directory.resolve("preview.png");
        Path meta = directory.resolve("meta.json");
        ImageIO.write(result.image(), "png", png.toFile());
        writeBiomeMeta(meta, spec, result.samples(), result.map(), false);
        job.done(png, meta, false);
        trimCache();
    }

    /// The EXTREME tier, isolated: `java -Xmx3g -cp panel.jar PreviewRenderMain
    /// …` does the sampling, picture and meta, so the panel's own (typically
    /// 1 GB) heap never sees the 8192² grid. The child inherits the panel's
    /// classpath, which is the jar path under every supported deployment.
    private void runBiomeCoreExtreme(PreviewJob job, PreviewSpec spec) {
        try {
            runBiomeCoreExtremeInner(job, spec);
        } catch (IOException e) {
            // 不走 IOException 出口：那条路会"回退到服务端生成"，而 8192 半径的
            // 真实世界生成是另一场灾难——极限档的失败就停在极限档。
            failOrCancel(job, "无法启动极限渲染子进程：" + e.getMessage());
        }
    }

    private void runBiomeCoreExtremeInner(PreviewJob job, PreviewSpec spec) throws IOException {
        Path javaExe = javaFor(17);
        if (javaExe == null) {
            failOrCancel(job, "极限渲染需要一个 Java 17+ 来跑子进程，面板没有找到。");
            return;
        }
        String classPath = System.getProperty("java.class.path", "");
        Path jar = classPath.split(java.io.File.pathSeparator).length == 1
                && Files.isRegularFile(Path.of(classPath)) ? Path.of(classPath) : null;
        if (jar == null) {
            failOrCancel(job, "极限渲染需要以 jar 方式部署的面板（子进程要复用同一份字节码），"
                    + "当前 classpath 不是单个 jar");
            return;
        }
        Path directory = Files.createDirectories(cacheDirectory(spec));
        Path png = directory.resolve("preview.png");
        Path meta = directory.resolve("meta.json");
        Process process = new ProcessBuilder(
                javaExe.toString(), "-Xmx3g", "-cp", jar.toString(), PreviewRenderMain.class.getName(),
                spec.version(), spec.seed(), Integer.toString(spec.radiusBlocks()),
                Integer.toString(spec.biomeY()), png.toString(), meta.toString())
                .redirectErrorStream(true)
                .start();
        job.attach(process);
        // 子进程报的是"已完成采样数"，总数是采样/边的平方——少传一个，进度条
        // 就会显示成 5349376 / 1（-1 被 Math.max(1,…) 抬成 1），百分比当场爆表。
        long totalSamples = (long) spec.javaSamples() * spec.javaSamples();
        List<String> tail = new ArrayList<>();
        long deadline = System.currentTimeMillis() + EXTREME_TIMEOUT.toMillis();
        try (var reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // 刹车先踩再看内容：进度行占绝大多数，continue 不能跳过检查。
                if (System.currentTimeMillis() > deadline) {
                    process.destroyForcibly();
                    failOrCancel(job, "极限渲染超过 " + EXTREME_TIMEOUT.toMinutes()
                            + " 分钟未完成，已终止。" + String.join(" | ", tail));
                    return;
                }
                if (job.cancelled()) {
                    process.destroyForcibly();
                    job.markCancelled();
                    return;
                }
                if (line.startsWith(PreviewRenderMain.PROGRESS_PREFIX)) {
                    try {
                        long done = Long.parseLong(line.substring(PreviewRenderMain.PROGRESS_PREFIX.length()));
                        job.renderProgress(done, totalSamples);
                    } catch (RuntimeException ignored) {
                        // A malformed progress line is not worth failing the job.
                    }
                    continue;
                }
                // 失败时要给的是"为什么"，不是 20 行进度刷屏——真正的输出和报错
                // 才进 tail（进度行一秒几十条，会把有用信息全部顶出去）。
                if (line.startsWith(PreviewRenderMain.RATE_PREFIX)) {
                    job.log(line.substring(PreviewRenderMain.RATE_PREFIX.length()));
                    continue;
                }
                if (tail.size() >= 20) {
                    tail.remove(0);
                }
                tail.add(line);
            }
            int exit = process.waitFor();
            if (exit != 0) {
                failOrCancel(job, "极限渲染子进程退出码 " + exit + "。" + String.join(" | ", tail));
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            job.markCancelled();
            return;
        }
        if (!Files.isRegularFile(png) || !Files.isRegularFile(meta)) {
            failOrCancel(job, "极限渲染子进程没有产出图片或元数据。" + String.join(" | ", tail));
            return;
        }
        job.done(png, meta, false);
        trimCache();
    }

    static void writeBiomeStructures(JsonObject body, @Nullable SeedMap map, PreviewSpec spec) {
        int radius = spec.radiusBlocks();
        JsonObject counts = new JsonObject();
        JsonArray structures = new JsonArray();
        if (map == null) {
            body.add("structureCounts", counts);
            body.add("structures", structures);
            return;
        }
        int markers = 0;
        outer:
        for (StructureType type : StructureType.values()) {
            if (type == StructureType.STRONGHOLD || type == StructureType.MINESHAFT) {
                continue;
            }
            List<Structure> found = map.structures(type, -radius, -radius, radius, radius);
            if (!found.isEmpty()) {
                counts.addProperty(type.key(), found.size());
            }
            for (Structure structure : found) {
                if (markers >= 500) {
                    break outer;
                }
                BlockPos pos = structure.pos();
                JsonObject entry = new JsonObject();
                entry.addProperty("id", type.key());
                entry.addProperty("chunkX", pos.x() >> 4);
                entry.addProperty("chunkZ", pos.z() >> 4);
                entry.addProperty("x", pos.x());
                entry.addProperty("z", pos.z());
                structures.add(entry);
                markers++;
            }
        }
        body.add("structureCounts", counts);
        body.add("structures", structures);
    }

    private static void failOrCancel(PreviewJob job, String message) {
        if (job.cancelled()) {
            job.markCancelled();
        } else {
            job.fail(message);
        }
    }

    // -------------------------------------------------------------- the server --

    /// A JVM that can run a server of this feature release.
    ///
    /// Preference order: a runtime the panel installed for this version, then
    /// the panel's own JVM when it is new enough. The second case matters: the
    /// official image runs on Java 21, and requiring the operator to install a
    /// second Java 21 just to preview a 1.21 world would be theatre.
    private @Nullable Path javaFor(int required) {
        Path installed = javaRuntimes.findInstalled(required);
        if (installed != null) {
            return installed;
        }
        String spec = System.getProperty("java.specification.version", "0");
        int major;
        try {
            major = Integer.parseInt(spec.contains(".") ? spec.substring(spec.indexOf('.') + 1) : spec);
        } catch (NumberFormatException e) {
            return null;
        }
        if (major < required) {
            return null;
        }
        String home = System.getProperty("java.home");
        if (home == null || home.isBlank()) {
            return null;
        }
        Path binary = Path.of(home, "bin", org.jackhuang.hmcl.util.platform.OperatingSystem.CURRENT_OS
                == org.jackhuang.hmcl.util.platform.OperatingSystem.WINDOWS ? "java.exe" : "java");
        return Files.isRegularFile(binary) ? binary : null;
    }

    /// Blocks until the server prints `Done (`, exits, or the boot deadline
    /// passes — whichever comes first.
    private void awaitBoot(Process process, CountDownLatch ready, PreviewJob job)
            throws McException, InterruptedException {
        long deadline = System.currentTimeMillis() + BOOT_TIMEOUT.toMillis();
        while (ready.getCount() > 0 && System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) {
                throw new McException("服务端启动后立即退出（exit " + process.exitValue() + "）。" + tail(job));
            }
            Thread.sleep(200);
        }
        if (!process.isAlive()) {
            throw new McException("服务端启动后立即退出（exit " + process.exitValue() + "）。" + tail(job));
        }
        if (ready.getCount() > 0) {
            throw new McException("服务端没有在 " + BOOT_TIMEOUT.toMinutes() + " 分钟内启动完成。" + tail(job));
        }
    }

    private Process start(Path java, Path jar, Path work, int javaMajor) throws IOException {
        return start(java, jar, work, javaMajor, PreviewSpec.MIN_RADIUS);
    }

    private Process start(Path java, Path jar, Path work, int javaMajor, int radius) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(java.toString());
        // Generating thousands of chunks in one JVM needs more than the
        // original 1G: a 2048×2048 preview can OOM before the first region is
        // written. Scale with the requested area, capped so a huge request
        // cannot starve the panel itself.
        int memoryMb = Math.min(4096, Math.max(1024, radius * 4));
        command.add("-Xmx" + memoryMb + "M");
        // Java4Shell: the hardware Base64 path is the vulnerable one, and a
        // preview server has no reason to use it. The flag itself only exists
        // from Java 9 — passing it to a Java 8 runtime is a hard startup error,
        // which is exactly the class of 1.16.5 server this feature has to run.
        if (javaMajor >= 9) {
            command.add("-XX:-UseBASE64Intrinsics");
        }
        command.add("-Dfile.encoding=UTF-8");
        command.add("-jar");
        command.add(jar.toString());
        command.add("--nogui");
        ProcessBuilder builder = new ProcessBuilder(command).directory(work.toFile());
        builder.redirectErrorStream(true);
        return builder.start();
    }

    private void pump(Process process, PreviewJob job, CountDownLatch ready) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    job.log(line);
                    // Every version since 1.3 prints this once it accepts
                    // commands; the wording differs, the `Done (` does not.
                    if (line.contains("Done (") || line.contains("Done in")) {
                        ready.countDown();
                    }
                }
            } catch (IOException ignored) {
                // The process is gone; the wait below reports it.
            } finally {
                ready.countDown();
            }
        }, "seed-preview-" + job.id());
        thread.setDaemon(true);
        thread.start();
    }

    private void send(Process process, String command) throws IOException {
        OutputStream input = process.getOutputStream();
        input.write((command + "\n").getBytes(StandardCharsets.UTF_8));
        input.flush();
    }

    /// Asks the server to generate the square.
    ///
    /// `/forceload` arrived in 1.14 and loads 256 chunks per command. Older
    /// servers have no such command, so the panel writes one air block per
    /// chunk — `setblock` in an ungenerated chunk forces that chunk to load,
    /// which runs generation exactly like forceload does.
    private void requestChunks(Process process, PreviewSpec spec) throws IOException, InterruptedException {
        int first = spec.firstChunk();
        int last = spec.lastChunk();
        int side = spec.chunksPerSide();
        if (supportsForceload(spec.version())) {
            // Keep each command to ~64 chunks. A single `forceload` of 256
            // chunks generates them on the server thread; on a version whose
            // watchdog is still armed that is a 60-second tick, and the server
            // kills itself before writing anything.
            int rows = Math.max(1, 64 / side);
            for (int z = first; z <= last; z += rows) {
                int z2 = Math.min(last, z + rows - 1);
                send(process, "forceload add " + (first * 16) + " " + (z * 16)
                        + " " + (last * 16 + 15) + " " + (z2 * 16 + 15));
                Thread.sleep(120);
            }
            return;
        }
        for (int z = first; z <= last; z++) {
            for (int x = first; x <= last; x++) {
                send(process, "setblock " + (x * 16 + 8) + " 250 " + (z * 16 + 8) + " minecraft:air");
            }
        }
    }

    private boolean waitForChunks(PreviewJob job, Path regionDirectory, PreviewSpec spec, Process process)
            throws InterruptedException, McException {
        long expected = spec.sampleCount();
        Instant deadline = Instant.now().plus(GENERATE_TIMEOUT);
        int stable = 0;
        int previous = 0;
        // Chunks are held in memory until the world is saved, so the region
        // files stay empty and the bar would sit at zero for the whole run.
        // A periodic flush turns generation into something the user can watch.
        long lastFlush = System.currentTimeMillis();
        while (Instant.now().isBefore(deadline)) {
            if (job.cancelled()) {
                return false;
            }
            if (System.currentTimeMillis() - lastFlush > 6000) {
                try {
                    send(process, supportsFlush(spec.version()) ? "save-all flush" : "save-all");
                } catch (IOException e) {
                    return false;
                }
                lastFlush = System.currentTimeMillis();
            }
            if (!process.isAlive()) {
                // A crashed server must not masquerade as "nothing generated".
                throw new McException("服务端中途退出（exit " + process.exitValue() + "）。" + tail(job));
            }
            int present = countInRange(regionDirectory, spec);
            job.progress(present, expected);
            if (present >= expected) {
                return true;
            }
            // A slow disk can leave the count flat for a while; three quiet
            // seconds after most chunks are in means the rest were refused
            // (structure-less biomes, unloaded corners) rather than pending.
            if (present == previous && present > expected * 3 / 4) {
                if (++stable >= 6) {
                    return false;
                }
            } else {
                stable = 0;
            }
            previous = present;
            Thread.sleep(500);
        }
        return false;
    }

    /// Where the overworld chunks live.
    ///
    /// 1.21 and earlier keep them at `<world>/region`. The 26.x line moved every
    /// dimension under `<world>/dimensions/minecraft/<dimension>/region`, so a
    /// hard-coded path silently reads an empty directory and the preview reports
    /// "no chunks generated".
    private static Path regionDirectory(Path work) {
        Path world = work.resolve("preview");
        Path legacy = world.resolve("region");
        if (Files.isDirectory(legacy)) {
            return legacy;
        }
        Path modern = world.resolve("dimensions").resolve("minecraft")
                .resolve("overworld").resolve("region");
        if (Files.isDirectory(modern)) {
            return modern;
        }
        // A future layout change should not break the feature outright: look for
        // any region directory that actually holds region files.
        try (Stream<Path> walk = Files.walk(world, 5)) {
            return walk.filter(path -> path.getFileName() != null
                            && "region".equals(path.getFileName().toString())
                            && Files.isDirectory(path)
                            && hasRegionFiles(path))
                    .findFirst()
                    .orElse(modern);
        } catch (IOException e) {
            return modern;
        }
    }

    private static boolean hasRegionFiles(Path directory) {
        try (Stream<Path> files = Files.list(directory)) {
            return files.anyMatch(path -> path.getFileName().toString().endsWith(".mca"));
        } catch (IOException e) {
            return false;
        }
    }

    private int countInRange(Path regionDirectory, PreviewSpec spec) {
        return RegionReader.countPresentChunks(regionDirectory, spec.firstChunk(), spec.lastChunk(),
                spec.sampleStep());
    }

    private List<RegionReader.ChunkSummary> readChunks(Path regionDirectory, PreviewSpec spec)
            throws IOException {
        List<RegionReader.ChunkSummary> chunks = new ArrayList<>();
        if (!Files.isDirectory(regionDirectory)) {
            return chunks;
        }
        try (Stream<Path> files = Files.list(regionDirectory)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (!name.startsWith("r.") || !name.endsWith(".mca")) {
                    continue;
                }
                int[] region = regionOf(name);
                if (region == null) {
                    continue;
                }
                for (RegionReader.ChunkSummary chunk : RegionReader.read(file, region[0], region[1], spec.biomeY())) {
                    if (chunk.chunkX() >= spec.firstChunk() && chunk.chunkX() <= spec.lastChunk()
                            && chunk.chunkZ() >= spec.firstChunk() && chunk.chunkZ() <= spec.lastChunk()) {
                        chunks.add(chunk);
                    }
                }
            }
        }
        return chunks;
    }

    private static int @Nullable [] regionOf(String fileName) {
        String[] parts = fileName.substring(2, fileName.length() - 4).split("\\.");
        if (parts.length != 2) {
            return null;
        }
        try {
            return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------- the picture --

    private void writeMeta(Path meta, PreviewSpec spec, List<RegionReader.ChunkSummary> chunks, boolean partial)
            throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("version", spec.version());
        body.addProperty("seed", spec.seed());
        body.addProperty("radius", spec.radiusBlocks());
        body.addProperty("biomeY", spec.biomeY());
        body.addProperty("chunks", chunks.size());
        body.addProperty("mode", "detail");
        body.addProperty("engine", spec.engine().name().toLowerCase(Locale.ROOT));
        body.addProperty("quality", spec.quality().name().toLowerCase(Locale.ROOT));
        body.addProperty("partial", partial);
        body.addProperty("generatedAt", Instant.now().toString());

        JsonObject counts = new JsonObject();
        JsonArray structures = new JsonArray();
        int markers = 0;
        for (RegionReader.ChunkSummary chunk : chunks) {
            for (RegionReader.StructureRef structure : chunk.structures()) {
                counts.addProperty(structure.id(),
                        counts.has(structure.id()) ? counts.get(structure.id()).getAsInt() + 1 : 1);
                if (markers < 500) {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("id", structure.id());
                    entry.addProperty("chunkX", structure.chunkX());
                    entry.addProperty("chunkZ", structure.chunkZ());
                    entry.addProperty("x", structure.chunkX() * 16 + 8);
                    entry.addProperty("z", structure.chunkZ() * 16 + 8);
                    structures.add(entry);
                    markers++;
                }
            }
        }
        body.add("structureCounts", counts);
        body.add("structures", structures);

        // The agent-facing view: counts and a 64×64 character map, so a model
        // can answer "where is the desert" without decoding a PNG. One
        // character is sixteen blocks.
        java.util.Map<String, Integer> biomeCounts = new java.util.LinkedHashMap<>();
        int grid = 64;
        char[][] cells = new char[grid][grid];
        for (char[] row : cells) {
            java.util.Arrays.fill(row, '?');
        }
        int radius = spec.radiusBlocks();
        int span = radius * 2;
        for (RegionReader.ChunkSummary chunk : chunks) {
            for (int z4 = 0; z4 < 4; z4++) {
                for (int x4 = 0; x4 < 4; x4++) {
                    String biome = chunk.biomes()[z4 * 4 + x4];
                    biomeCounts.merge(biome, 1, Integer::sum);
                    int blockX = chunk.chunkX() * 16 + x4 * 4 + 2;
                    int blockZ = chunk.chunkZ() * 16 + z4 * 4 + 2;
                    int gx = (int) Math.max(0, Math.min(grid - 1, (long) (blockX + radius) * grid / span));
                    int gz = (int) Math.max(0, Math.min(grid - 1, (long) (blockZ + radius) * grid / span));
                    cells[gz][gx] = biomeSymbol(biome);
                }
            }
        }
        JsonArray ascii = new JsonArray();
        for (char[] row : cells) {
            ascii.add(new String(row));
        }
        JsonObject biomeJson = new JsonObject();
        biomeCounts.forEach(biomeJson::addProperty);
        body.add("biomeCounts", biomeJson);
        body.add("ascii", ascii);

        // Slime chunks are a pure function of the seed, so the browser can paint
        // them without another server run; sending the list keeps the formula in
        // one place.
        JsonArray slime = new JsonArray();
        long seed = PreviewRenderer.parseSeed(spec.seed());
        for (int cz = spec.firstChunk(); cz <= spec.lastChunk(); cz++) {
            for (int cx = spec.firstChunk(); cx <= spec.lastChunk(); cx++) {
                if (SlimeChunks.isSlimeChunk(seed, cx, cz)) {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("x", cx);
                    entry.addProperty("z", cz);
                    slime.add(entry);
                }
            }
        }
        body.add("slimeChunks", slime);
        Files.writeString(meta, body.toString(), StandardCharsets.UTF_8);
    }

    /// One character per sixteen blocks. Materials, not heights: a model reads
    /// "there is desert south of spawn" from `#`, and nothing from a shade.
    private static char biomeSymbol(String biome) {
        String name = biome.toLowerCase(Locale.ROOT);
        if (name.contains("snow") || name.contains("ice") || name.contains("frozen")) {
            return '*';
        }
        if (name.contains("ocean")) {
            return '.';
        }
        if (name.contains("river")) {
            return '~';
        }
        if (name.contains("beach") || name.contains("shore")) {
            return ',';
        }
        if (name.contains("desert") || name.contains("badlands")) {
            return '#';
        }
        if (name.contains("swamp") || name.contains("mangrove")) {
            return '+';
        }
        if (name.contains("jungle") || name.contains("forest") || name.contains("taiga")
                || name.contains("grove") || name.contains("cherry")) {
            return 'T';
        }
        if (name.contains("peak") || name.contains("mountain") || name.contains("windswept")
                || name.contains("stony") || name.contains("hill")) {
            return '^';
        }
        if (name.contains("cave") || name.contains("deep_dark") || name.contains("dripstone")) {
            return '%';
        }
        if (name.contains("mushroom")) {
            return '&';
        }
        if (name.contains("plains") || name.contains("meadow") || name.contains("savanna")) {
            return '"';
        }
        return '?';
    }

    /// The overview's metadata: the same shape as the detail one, with the
    /// sample grid standing in for the chunk list.
    static void writeBiomeMeta(Path meta, PreviewSpec spec, String[][] samples, SeedMap map, boolean partial)
            throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("version", spec.version());
        body.addProperty("seed", spec.seed());
        body.addProperty("radius", spec.radiusBlocks());
        body.addProperty("biomeY", spec.biomeY());
        body.addProperty("mode", "biome");
        body.addProperty("engine", spec.engine().name().toLowerCase(Locale.ROOT));
        body.addProperty("quality", spec.quality().name().toLowerCase(Locale.ROOT));
        body.addProperty("chunks", samples.length * samples.length);
        body.addProperty("partial", partial);
        body.addProperty("generatedAt", Instant.now().toString());
        writeBiomeStructures(body, map, spec);
        body.add("slimeChunks", new JsonArray());

        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        char[][] ascii = new char[64][64];
        for (char[] row : ascii) {
            java.util.Arrays.fill(row, '?');
        }
        int cells = samples.length;
        for (int j = 0; j < cells; j++) {
            for (int i = 0; i < cells; i++) {
                String biome = samples[j][i];
                if (biome == null) {
                    continue;
                }
                counts.merge(biome, 1, Integer::sum);
                int gx = Math.min(63, i * 64 / cells);
                int gz = Math.min(63, j * 64 / cells);
                ascii[gz][gx] = biomeSymbol(biome);
            }
        }
        JsonObject biomeJson = new JsonObject();
        counts.forEach(biomeJson::addProperty);
        body.add("biomeCounts", biomeJson);
        JsonArray lines = new JsonArray();
        for (char[] row : ascii) {
            lines.add(new String(row));
        }
        body.add("ascii", lines);
        Files.writeString(meta, body.toString(), StandardCharsets.UTF_8);
    }

    private Path cacheDirectory(PreviewSpec spec) {
        return cacheRoot.resolve(spec.version()).resolve(seedHash(spec.seed()))
                // The suffix is the renderer generation: an image drawn before
                // the overlays moved to the browser must not be served as if it
                // were the new one.
                .resolve(spec.radiusBlocks() + "-" + spec.biomeY()
                        + ("BIOME".equals(spec.mode().name()) ? "-biome" : "")
                        + (spec.engine() == PreviewSpec.Engine.JAVA ? "-java" : "")
                        + (spec.quality() == PreviewSpec.Quality.HIGH ? "-hd"
                            : spec.quality() == PreviewSpec.Quality.ULTRA ? "-ultra"
                            : spec.quality() == PreviewSpec.Quality.EXTREME ? "-extreme" : "") + "-v10");
    }

    private static String seedHash(String seed) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(seed.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(seed.hashCode());
        }
    }

    // ------------------------------------------------------------------ plumbing --

    private void writeProperties(Path work, PreviewSpec spec, int port) throws IOException {
        String properties = """
                level-name=preview
                level-seed=%s
                level-type=minecraft:normal
                generate-structures=true
                online-mode=false
                max-players=0
                spawn-protection=0
                view-distance=2
                simulation-distance=2
                sync-chunk-writes=false
                enable-rcon=false
                enable-query=false
                allow-nether=false
                max-tick-time=-1
                motd=HDSL seed preview
                server-port=%d
                """.formatted(spec.seed().replace("\n", "").replace("\r", ""), port);
        Files.writeString(work.resolve("server.properties"), properties, StandardCharsets.UTF_8);
    }

    private static int freePort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            int fallback = 25565;
            for (int port = McPorts.MIN_PORT; port < McPorts.MAX_PORT; port++) {
                if (McPorts.isFree(port)) {
                    return port;
                }
            }
            return fallback;
        }
    }

    /// `save-all flush` arrived with 1.13; older servers only know `save-all`.
    private static boolean supportsFlush(String version) {
        return minor(version) >= 13;
    }

    /// `forceload` arrived with 1.14.
    private static boolean supportsForceload(String version) {
        return minor(version) >= 14;
    }

    private static int minor(String version) {
        String[] parts = version.split("\\.");
        try {
            return parts.length >= 2 ? Integer.parseInt(parts[1].replaceAll("[^0-9].*$", "")) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void remember(PreviewJob job) {
        jobs.put(job.id(), job);
        if (jobs.size() > MAX_JOBS) {
            List<PreviewJob> ordered = new ArrayList<>(jobs.values());
            ordered.sort(Comparator.comparing(PreviewJob::id));
            for (int i = 0; i < ordered.size() - MAX_JOBS; i++) {
                PreviewJob old = ordered.get(i);
                if (old.finished()) {
                    jobs.remove(old.id(), old);
                }
            }
        }
    }

    /// Every finished preview on disk, grouped by seed.
    ///
    /// The list is built from the cache itself rather than an index file: the
    /// PNG and its `meta.json` are the record, so a manually deleted file or a
    /// half-written one simply does not appear. Results are grouped so the same
    /// seed can be viewed in several versions, engines and resolutions; the cap
    /// counts seeds, not individual pictures.
    public List<JsonObject> history() {
        List<JsonObject> entries = new ArrayList<>();
        try (Stream<Path> versions = Files.list(cacheRoot)) {
            for (Path version : versions.filter(Files::isDirectory).toList()) {
                if ("tmp".equals(version.getFileName().toString())) {
                    continue;
                }
                try (Stream<Path> seeds = Files.list(version)) {
                    for (Path seed : seeds.filter(Files::isDirectory).toList()) {
                        try (Stream<Path> variants = Files.list(seed)) {
                            for (Path variant : variants.filter(Files::isDirectory).toList()) {
                                JsonObject entry = entryOf(variant);
                                if (entry != null) {
                                    entries.add(entry);
                                }
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            return List.of();
        }
        // Group by seed, newest variant first inside each group.
        Map<String, List<JsonObject>> bySeed = new LinkedHashMap<>();
        entries.sort((left, right) -> right.get("generatedAt").getAsString()
                .compareTo(left.get("generatedAt").getAsString()));
        for (JsonObject entry : entries) {
            bySeed.computeIfAbsent(entry.get("seed").getAsString(), k -> new ArrayList<>()).add(entry);
        }
        List<JsonObject> result = new ArrayList<>();
        for (List<JsonObject> group : bySeed.values()) {
            result.addAll(group);
            if (result.size() >= MAX_HISTORY) {
                break;
            }
        }
        return result;
    }

    /// The same history, shaped for the UI: one row per seed, with every cached
    /// variant (version / engine / quality / radius) underneath.
    public JsonObject historyGrouped() {
        List<JsonObject> entries = history();
        Map<String, List<JsonObject>> bySeed = new LinkedHashMap<>();
        for (JsonObject entry : entries) {
            bySeed.computeIfAbsent(entry.get("seed").getAsString(), k -> new ArrayList<>()).add(entry);
        }
        JsonArray seeds = new JsonArray();
        for (Map.Entry<String, List<JsonObject>> group : bySeed.entrySet()) {
            JsonObject seed = new JsonObject();
            seed.addProperty("seed", group.getKey());
            JsonArray variants = new JsonArray();
            for (JsonObject variant : group.getValue()) {
                variants.add(variant);
            }
            seed.add("variants", variants);
            seeds.add(seed);
        }
        JsonObject body = new JsonObject();
        body.addProperty("limit", MAX_HISTORY);
        body.add("seeds", seeds);
        return body;
    }

    /// The directory of one history entry, or `null` when it is gone.
    public @Nullable Path cachedDirectory(String id) {
        try (Stream<Path> versions = Files.list(cacheRoot)) {
            for (Path version : versions.filter(Files::isDirectory).toList()) {
                if ("tmp".equals(version.getFileName().toString())) {
                    continue;
                }
                try (Stream<Path> seeds = Files.list(version)) {
                    for (Path seed : seeds.filter(Files::isDirectory).toList()) {
                        try (Stream<Path> variants = Files.list(seed)) {
                            for (Path variant : variants.filter(Files::isDirectory).toList()) {
                                JsonObject entry = entryOf(variant);
                                if (entry != null && id.equals(entry.get("id").getAsString())) {
                                    return variant;
                                }
                            }
                        }
                    }
                }
            }
        } catch (IOException ignored) {
            // A cache that cannot be listed has no entries.
        }
        return null;
    }

    /// Removes one cached preview, images and all.
    public boolean deleteCached(String id) {
        Path directory = cachedDirectory(id);
        if (directory == null) {
            return false;
        }
        deleteTree(directory);
        // An empty seed/version folder is litter; drop it too when possible.
        try {
            Path seed = directory.getParent();
            if (seed != null && isEmpty(seed)) {
                Files.deleteIfExists(seed);
                Path version = seed.getParent();
                if (version != null && isEmpty(version)) {
                    Files.deleteIfExists(version);
                }
            }
        } catch (IOException ignored) {
        }
        return true;
    }

    private static boolean isEmpty(Path directory) throws IOException {
        try (Stream<Path> children = Files.list(directory)) {
            return children.findAny().isEmpty();
        }
    }

    private static @Nullable JsonObject entryOf(Path variant) {
        Path meta = variant.resolve("meta.json");
        Path png = variant.resolve("preview.png");
        if (!Files.isRegularFile(meta) || !Files.isRegularFile(png)) {
            return null;
        }
        try {
            JsonObject body = com.google.gson.JsonParser.parseString(
                    Files.readString(meta, StandardCharsets.UTF_8)).getAsJsonObject();
            String version = body.get("version").getAsString();
            String seed = body.get("seed").getAsString();
            int radius = body.get("radius").getAsInt();
            int biomeY = body.get("biomeY").getAsInt();
            String engine = body.has("engine") ? body.get("engine").getAsString() : "server";
            String quality = body.has("quality") ? body.get("quality").getAsString() : "standard";
            JsonObject entry = new JsonObject();
            entry.addProperty("id", historyId(version, seed, radius, biomeY, engine, quality));
            entry.addProperty("version", version);
            entry.addProperty("seed", seed);
            entry.addProperty("radius", radius);
            entry.addProperty("biomeY", biomeY);
            entry.addProperty("generatedAt", body.get("generatedAt").getAsString());
            entry.addProperty("partial", body.get("partial").getAsBoolean());
            entry.addProperty("mode", body.has("mode") ? body.get("mode").getAsString() : "detail");
            entry.addProperty("engine", engine);
            entry.addProperty("quality", quality);
            entry.addProperty("bytes", Files.size(png));
            if (body.has("structures") && body.get("structures").isJsonArray()) {
                entry.addProperty("structures", body.getAsJsonArray("structures").size());
            }
            return entry;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /// A stable handle for a cached result: the same four inputs always name the
    /// same picture, so a bookmark or a page reload keeps working.
    public static String historyId(String version, String seed, int radius, int biomeY, String engine, String quality) {
        String key = version + "|" + seed + "|" + radius + "|" + biomeY + "|" + engine + "|" + quality;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(key.hashCode());
        }
    }

    /// Keeps the newest [#MAX_HISTORY] seeds, not pictures. A seed may keep every
    /// cached variant (version / engine / quality / radius); only whole seeds
    /// fall off the end. Image and metadata are deleted together.
    private void trimCache() {
        Map<String, List<Path>> bySeed = new LinkedHashMap<>();
        try (Stream<Path> versions = Files.list(cacheRoot)) {
            for (Path version : versions.filter(Files::isDirectory).toList()) {
                if ("tmp".equals(version.getFileName().toString())) {
                    continue;
                }
                try (Stream<Path> seeds = Files.list(version)) {
                    for (Path seed : seeds.filter(Files::isDirectory).toList()) {
                        try (Stream<Path> children = Files.list(seed)) {
                            for (Path variant : children.filter(Files::isDirectory).toList()) {
                                JsonObject entry = entryOf(variant);
                                if (entry != null) {
                                    bySeed.computeIfAbsent(entry.get("seed").getAsString(), k -> new ArrayList<>()).add(variant);
                                }
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            return;
        }
        if (bySeed.size() <= MAX_HISTORY) {
            return;
        }
        // Rank each seed by its newest variant, then drop the oldest whole seeds.
        List<Map.Entry<String, List<Path>>> groups = new ArrayList<>(bySeed.entrySet());
        groups.sort((left, right) -> Long.compare(newest(right.getValue()), newest(left.getValue())));
        for (int i = MAX_HISTORY; i < groups.size(); i++) {
            for (Path variant : groups.get(i).getValue()) {
                deleteTree(variant);
            }
        }
    }

    private static long newest(List<Path> variants) {
        long best = 0;
        for (Path variant : variants) {
            best = Math.max(best, lastModified(variant));
        }
        return best;
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static void deleteTree(@Nullable Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Windows keeps chunk files open for a moment after stop.
                }
            });
        } catch (IOException ignored) {
        }
    }

    private static String tail(PreviewJob job) {
        List<String> lines = job.logLines();
        if (lines.isEmpty()) {
            return "";
        }
        return " 最后输出：" + String.join(" / ", lines.subList(Math.max(0, lines.size() - 3), lines.size()));
    }

    private static String newId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /// The version id, lower-cased, for cache keys the panel compares later.
    static String normaliseVersion(String version) {
        return version.trim().toLowerCase(Locale.ROOT);
    }
}
