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
import java.util.Comparator;
import java.util.HexFormat;
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
    /// How long `stop` may take to end the process.
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(90);
    /// Upper bound for the picture cache.
    private static final long CACHE_LIMIT_BYTES = 256L * 1024 * 1024;
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
        body.addProperty("javaReady", javaRuntimes.findInstalled(required) != null);
        body.addProperty("running", active.get() > 0);
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
        Path work = null;
        try {
            if (job.cancelled()) {
                job.markCancelled();
                return;
            }
            job.state(PreviewJob.State.RESOLVING);
            Path jar = versions.ensureServerJar(spec.version(), null);
            int required = Math.max(8, McJavaRuntimes.recommendFor(spec.version()));
            Path java = javaRuntimes.findInstalled(required);
            if (java == null) {
                throw new McException("这个版本需要 Java " + required + "，面板里还没有。"
                        + "请先在「Minecraft → Java 运行时」里安装它。");
            }

            job.state(PreviewJob.State.STARTING);
            work = Files.createDirectories(tmpRoot.resolve(job.id()));
            writeProperties(work, spec, freePort());
            Files.writeString(work.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);

            Process process = start(java, jar, work);
            job.attach(process);
            CountDownLatch ready = new CountDownLatch(1);
            pump(process, job, ready);
            if (!ready.await(BOOT_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                throw new McException("服务端没有在 " + BOOT_TIMEOUT.toMinutes() + " 分钟内启动完成。"
                        + tail(job));
            }
            if (job.cancelled()) {
                job.markCancelled();
                return;
            }

            job.state(PreviewJob.State.GENERATING);
            requestChunks(process, spec);
            Path regionDirectory = work.resolve("preview").resolve("region");
            boolean complete = waitForChunks(job, regionDirectory, spec);
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
                throw new McException("服务端没有生成任何区块（" + present + " 个落盘），无法出图。");
            }

            job.state(PreviewJob.State.RENDERING);
            BufferedImage image = PreviewRenderer.render(chunks, spec, true, true);
            Path directory = Files.createDirectories(cacheDirectory(spec));
            Path png = directory.resolve("preview.png");
            Path meta = directory.resolve("meta.json");
            ImageIO.write(image, "png", png.toFile());
            writeMeta(meta, spec, chunks, !complete);
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

    private static void failOrCancel(PreviewJob job, String message) {
        if (job.cancelled()) {
            job.markCancelled();
        } else {
            job.fail(message);
        }
    }

    // -------------------------------------------------------------- the server --

    private Process start(Path java, Path jar, Path work) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(java.toString());
        command.add("-Xmx1G");
        // Java4Shell: the hardware Base64 path is the vulnerable one, and a
        // preview server has no reason to use it. Harmless on every version.
        command.add("-XX:-UseBASE64Intrinsics");
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
    private void requestChunks(Process process, PreviewSpec spec) throws IOException {
        int first = spec.firstChunk();
        int last = spec.lastChunk();
        int side = spec.chunksPerSide();
        if (supportsForceload(spec.version())) {
            int rows = Math.max(1, 256 / side);
            for (int z = first; z <= last; z += rows) {
                int z2 = Math.min(last, z + rows - 1);
                send(process, "forceload add " + (first * 16) + " " + (z * 16)
                        + " " + (last * 16 + 15) + " " + (z2 * 16 + 15));
            }
            return;
        }
        for (int z = first; z <= last; z++) {
            for (int x = first; x <= last; x++) {
                send(process, "setblock " + (x * 16 + 8) + " 250 " + (z * 16 + 8) + " minecraft:air");
            }
        }
    }

    private boolean waitForChunks(PreviewJob job, Path regionDirectory, PreviewSpec spec)
            throws InterruptedException {
        long expected = spec.chunkCount();
        Instant deadline = Instant.now().plus(GENERATE_TIMEOUT);
        int stable = 0;
        int previous = 0;
        while (Instant.now().isBefore(deadline)) {
            if (job.cancelled()) {
                return false;
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

    private int countInRange(Path regionDirectory, PreviewSpec spec) {
        return RegionReader.countPresentChunks(regionDirectory, spec.firstChunk(), spec.lastChunk());
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
        Files.writeString(meta, body.toString(), StandardCharsets.UTF_8);
    }

    private Path cacheDirectory(PreviewSpec spec) {
        return cacheRoot.resolve(spec.version()).resolve(seedHash(spec.seed()))
                .resolve(spec.radiusBlocks() + "-" + spec.biomeY());
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

    private void trimCache() {
        try (Stream<Path> files = Files.walk(cacheRoot)) {
            List<Path> images = files.filter(path -> path.getFileName().toString().equals("preview.png"))
                    .sorted(Comparator.comparingLong(path -> {
                        try {
                            return Files.getLastModifiedTime(path).toMillis();
                        } catch (IOException e) {
                            return 0L;
                        }
                    }))
                    .toList();
            long total = 0;
            for (Path image : images) {
                total += Files.size(image);
            }
            for (Path image : images) {
                if (total <= CACHE_LIMIT_BYTES) {
                    break;
                }
                long size = Files.size(image);
                Files.deleteIfExists(image);
                Files.deleteIfExists(image.resolveSibling("meta.json"));
                total -= size;
            }
        } catch (IOException ignored) {
            // The cache is best-effort: a failed cleanup must not fail a preview.
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
