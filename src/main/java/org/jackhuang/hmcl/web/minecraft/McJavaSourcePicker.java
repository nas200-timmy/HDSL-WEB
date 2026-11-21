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
package org.jackhuang.hmcl.web.minecraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// Picks the JDK download source before a download starts, by sampling real
/// bytes from every candidate and remembering the winner.
///
/// The picker never decides what gets installed — the catalogue (version,
/// size, checksum) is official regardless. It only answers "which origin
/// should serve the bytes of this release". One source is chosen per vendor;
/// the installer downloads from that single source and retries once against
/// the official origin if the bytes fail their checks.
///
/// Sampling downloads at most [SAMPLE_BYTES] per source, verifies the archive
/// magic (a mirror that answers HTML for everything — Huawei's portal does —
/// is unusable and must not win on a fast-but-fake response), and is fully
/// serialized: at most one probe runs at a time, wherever it was triggered.
@NotNullByDefault
public final class McJavaSourcePicker {

    /// How the sample fetcher answered. `head` holds the first bytes for the
    /// magic check; `bytes` is what was actually read within the deadline.
    public record Sample(int status, byte[] head, long bytes, long durationNanos) {
    }

    /// Fetches the first bytes of a URL. Implemented with HTTP in production,
    /// with fakes in tests.
    public interface Sampler {
        Sample sample(String url) throws IOException, InterruptedException;
    }

    public record ProbeResult(String sourceId, boolean ok, long speedKBps, long sampleBytes,
                              long durationMs, String lastError, Instant probedAt,
                              int failures, @Nullable Instant skipUntil) {
    }

    public record Choice(McJavaSources.Source source, boolean probed, String reason) {
    }

    static final long SAMPLE_BYTES = 512 * 1024;
    static final Duration PER_SOURCE_TIMEOUT = Duration.ofSeconds(6);
    static final Duration TTL = Duration.ofHours(6);
    static final Duration BASE_SKIP = Duration.ofMinutes(30);
    /// A result younger than this is reused as-is: two triggers that race
    /// each other must not sample the same source twice.
    static final Duration MIN_REPROBE = Duration.ofMinutes(2);
    static final int MAX_FAILURES = 4;
    /// A domestic mirror within this fraction of the best speed still wins:
    /// being 10% slower than GitHub but sparing the upstream is the right call.
    static final double MIRROR_PREFERENCE = 0.85;

    static final String ENV_SOURCE = "HDSL_JAVA_SOURCE";
    static final String POLICY_AUTO = "auto";
    static final String POLICY_OFFICIAL = "official";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Sampler sampler;
    private final Clock clock;
    private final Object lock = new Object();
    private final Map<McJavaRuntimes.Vendor, String> choices = new EnumMap<>(McJavaRuntimes.Vendor.class);
    private final Map<String, ProbeResult> probes = new java.util.LinkedHashMap<>();
    private volatile String policy = POLICY_AUTO;

    public McJavaSourcePicker(Path dataDir) {
        this(dataDir, httpSampler(), Clock.systemUTC());
    }

    McJavaSourcePicker(Path dataDir, Sampler sampler, Clock clock) {
        this.file = dataDir.resolve("minecraft").resolve("java-sources.json");
        this.sampler = sampler;
        this.clock = clock;
        load();
    }

    private void load() {
        synchronized (lock) {
            if (!Files.isRegularFile(file)) {
                return;
            }
            try {
                JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
                if (!root.isJsonObject()) {
                    return;
                }
                JsonObject object = root.getAsJsonObject();
                JsonElement policyElement = object.get("policy");
                if (policyElement != null && policyElement.isJsonPrimitive()) {
                    policy = policyElement.getAsString();
                }
                JsonElement choicesElement = object.get("choices");
                if (choicesElement != null && choicesElement.isJsonObject()) {
                    for (Map.Entry<String, JsonElement> entry
                            : choicesElement.getAsJsonObject().entrySet()) {
                        McJavaRuntimes.Vendor vendor = McJavaRuntimes.Vendor.of(entry.getKey());
                        if (vendor != null && entry.getValue().isJsonPrimitive()) {
                            choices.put(vendor, entry.getValue().getAsString());
                        }
                    }
                }
                JsonElement probesElement = object.get("probes");
                if (probesElement != null && probesElement.isJsonObject()) {
                    for (Map.Entry<String, JsonElement> entry
                            : probesElement.getAsJsonObject().entrySet()) {
                        if (entry.getValue().isJsonObject()) {
                            probes.put(entry.getKey(), readProbe(entry.getValue().getAsJsonObject()));
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                // A corrupt cache is rebuilt by the next probe; never fatal.
            }
        }
    }

    private static ProbeResult readProbe(JsonObject object) {
        return new ProbeResult(
                string(object, "source", "official"),
                bool(object, "ok"),
                object.has("speedKBps") ? object.get("speedKBps").getAsLong() : 0,
                object.has("sampleBytes") ? object.get("sampleBytes").getAsLong() : 0,
                object.has("durationMs") ? object.get("durationMs").getAsLong() : 0,
                string(object, "error", ""),
                instant(object.get("probedAt")),
                object.has("failures") ? object.get("failures").getAsInt() : 0,
                instant(object.get("skipUntil")));
    }

    private void save() {
        synchronized (lock) {
            try {
                Files.createDirectories(file.getParent());
                JsonObject root = new JsonObject();
                root.addProperty("policy", policy);
                JsonObject choicesObject = new JsonObject();
                choices.forEach((vendor, source) -> choicesObject.addProperty(vendor.id(), source));
                root.add("choices", choicesObject);
                JsonObject probesObject = new JsonObject();
                probes.forEach((id, probe) -> {
                    JsonObject row = new JsonObject();
                    row.addProperty("source", probe.sourceId());
                    row.addProperty("ok", probe.ok());
                    row.addProperty("speedKBps", probe.speedKBps());
                    row.addProperty("sampleBytes", probe.sampleBytes());
                    row.addProperty("durationMs", probe.durationMs());
                    row.addProperty("error", probe.lastError());
                    if (probe.probedAt() != null) {
                        row.addProperty("probedAt", probe.probedAt().toString());
                    }
                    row.addProperty("failures", probe.failures());
                    if (probe.skipUntil() != null) {
                        row.addProperty("skipUntil", probe.skipUntil().toString());
                    }
                    probesObject.add(id, row);
                });
                root.add("probes", probesObject);
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
                try {
                    Files.setPosixFilePermissions(tmp, Set.of(
                            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
                } catch (IOException | UnsupportedOperationException ignored) {
                    // Non-POSIX filesystems cannot express this.
                }
                try {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException e) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException ignored) {
                // A lost cache only means the next choice re-probes.
            }
        }
    }

    // -------------------------------------------------------------- policies --

    public String policy() {
        return policy;
    }

    /// `auto`, `official`, or one of [McJavaSources]' ids. Unknown ids fall
    /// back to `official` with the reason recorded on the returned choice.
    public void setPolicy(@Nullable String next) {
        synchronized (lock) {
            policy = next == null || next.isBlank() ? POLICY_AUTO : next.trim();
            if (!POLICY_AUTO.equals(policy) && !POLICY_OFFICIAL.equals(policy)
                    && McJavaSources.byId(policy) == null) {
                policy = POLICY_AUTO;
            }
            save();
        }
    }

    /// Environment override, per process.
    private @Nullable String envOverride() {
        String value = System.getenv(ENV_SOURCE);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private @Nullable Choice explicit(@Nullable String wanted, McJavaRuntimes.Vendor vendor) {
        if (wanted == null || POLICY_AUTO.equals(wanted)) {
            return null;
        }
        if (POLICY_OFFICIAL.equals(wanted)) {
            return new Choice(McJavaSources.OFFICIAL, false, "policy=official");
        }
        McJavaSources.Source source = McJavaSources.byId(wanted);
        if (source == null) {
            return new Choice(McJavaSources.OFFICIAL, false,
                    "unknown " + ENV_SOURCE + "=" + wanted + "; using official");
        }
        if (!source.serves(vendor)) {
            return new Choice(McJavaSources.OFFICIAL, false,
                    source.id() + " does not serve " + vendor.id() + "; using official");
        }
        return new Choice(source, false, "policy=" + wanted);
    }

    // ---------------------------------------------------------------- probing --

    /// Samples every candidate source for `vendor` against `release` (except
    /// sources still inside their skip window), updates the cache and the
    /// per-vendor choice, and returns the results in candidate order.
    public List<ProbeResult> probe(McJavaRuntimes.Vendor vendor, McJavaRuntimes.Release release) {
        synchronized (lock) {
            List<ProbeResult> results = new ArrayList<>();
            for (McJavaSources.Source source : McJavaSources.candidates(vendor)) {
                ProbeResult previous = probes.get(source.id());
                Instant now = clock.instant();
                boolean coolingDown = previous != null && previous.skipUntil() != null
                        && previous.skipUntil().isAfter(now);
                boolean justProbed = previous != null && previous.probedAt() != null
                        && Duration.between(previous.probedAt(), now).compareTo(MIN_REPROBE) < 0;
                if (coolingDown || justProbed) {
                    results.add(previous);
                    continue;
                }
                ProbeResult result = sampleOne(source, release);
                probes.put(source.id(), result);
                results.add(result);
            }
            chooseFromProbes(vendor, results);
            save();
            return List.copyOf(results);
        }
    }

    private ProbeResult sampleOne(McJavaSources.Source source, McJavaRuntimes.Release release) {
        String url = source == McJavaSources.OFFICIAL
                ? release.url() : McJavaSources.rewrite(source, release.url());
        Instant probedAt = clock.instant();
        if (url == null) {
            return failed(source, probedAt, "no mirror URL for this release");
        }
        long start = System.nanoTime();
        try {
            Sample sample = sampler.sample(url);
            long durationMs = sample.durationNanos() > 0
                    ? Math.max(1, sample.durationNanos() / 1_000_000)
                    : Math.max(1, (System.nanoTime() - start) / 1_000_000);
            if (sample.status() != 206 && sample.status() != 200) {
                return failed(source, probedAt, "HTTP " + sample.status());
            }
            if (!hasArchiveMagic(sample.head())) {
                return failed(source, probedAt, "not an archive (HTML portal page?)");
            }
            long speed = sample.bytes() * 1000L / durationMs / 1024L;
            ProbeResult previous = probes.get(source.id());
            return new ProbeResult(source.id(), true, speed, sample.bytes(), durationMs, "",
                    probedAt, previous == null ? 0 : previous.failures(), null);
        } catch (Exception e) {
            return failed(source, probedAt, e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private ProbeResult failed(McJavaSources.Source source, Instant probedAt, String error) {
        ProbeResult previous = probes.get(source.id());
        int failures = (previous == null ? 0 : previous.failures()) + 1;
        Duration skip = BASE_SKIP.multipliedBy(1L << Math.min(failures - 1, MAX_FAILURES));
        return new ProbeResult(source.id(), false, 0, 0, 0, error, probedAt, failures,
                probedAt.plus(skip));
    }

    private static boolean hasArchiveMagic(byte[] head) {
        if (head.length < 4) {
            return false;
        }
        boolean gzip = (head[0] & 0xff) == 0x1f && (head[1] & 0xff) == 0x8b;
        boolean zip = head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4;
        return gzip || zip;
    }

    private void chooseFromProbes(McJavaRuntimes.Vendor vendor, List<ProbeResult> results) {
        long best = 0;
        for (ProbeResult result : results) {
            if (result.ok()) {
                best = Math.max(best, result.speedKBps());
            }
        }
        String chosen = McJavaSources.OFFICIAL.id();
        if (best > 0) {
            for (McJavaSources.Source source : McJavaSources.candidates(vendor)) {
                ProbeResult result = results.stream()
                        .filter(item -> item.sourceId().equals(source.id())).findFirst().orElse(null);
                if (result != null && result.ok()
                        && result.speedKBps() >= best * MIRROR_PREFERENCE) {
                    chosen = source.id();
                    break;
                }
            }
        }
        choices.put(vendor, chosen);
    }

    // ----------------------------------------------------------------- choice --

    /// Which source the next install of `release` should download from.
    /// Probes on demand when the cached choice is stale, then caches it.
    public Choice choose(McJavaRuntimes.Vendor vendor, McJavaRuntimes.Release release) {
        Choice fromEnv = explicit(envOverride(), vendor);
        if (fromEnv != null) {
            return fromEnv;
        }
        synchronized (lock) {
            Choice fromPolicy = explicit(policy, vendor);
            if (fromPolicy != null) {
                return fromPolicy;
            }
            String chosen = choices.get(vendor);
            ProbeResult probe = chosen == null ? null : probes.get(chosen);
            boolean fresh = probe != null && probe.ok()
                    && Duration.between(probe.probedAt(), clock.instant()).compareTo(TTL) < 0
                    && (probe.skipUntil() == null || !probe.skipUntil().isAfter(clock.instant()));
            if (fresh) {
                McJavaSources.Source source = McJavaSources.byId(chosen);
                if (source != null) {
                    return new Choice(source, false, "cached probe");
                }
            }
        }
        List<ProbeResult> results = probe(vendor, release);
        synchronized (lock) {
            String chosen = choices.get(vendor);
            McJavaSources.Source source = McJavaSources.byId(chosen);
            return new Choice(source == null ? McJavaSources.OFFICIAL : source, true,
                    source == null ? "no source answered; using official" : "probed");
        }
    }

    /// A finished download tells the picker how the chosen source did, so a
    /// mirror that serves truncated bytes is skipped before the next install.
    public void recordDownloadOutcome(String sourceId, boolean success, @Nullable String error) {
        synchronized (lock) {
            ProbeResult previous = probes.get(sourceId);
            Instant now = clock.instant();
            if (success) {
                probes.put(sourceId, new ProbeResult(sourceId, true,
                        previous == null ? 0 : previous.speedKBps(),
                        previous == null ? 0 : previous.sampleBytes(),
                        previous == null ? 0 : previous.durationMs(),
                        "", previous == null ? now : previous.probedAt(), 0, null));
            } else {
                probes.put(sourceId, failed(McJavaSources.byId(sourceId) == null
                        ? McJavaSources.OFFICIAL : McJavaSources.byId(sourceId), now,
                        error == null ? "download failed" : error));
            }
            save();
        }
    }

    /// Snapshot for the settings API. Never triggers network I/O.
    public JsonObject describe() {
        synchronized (lock) {
            JsonObject root = new JsonObject();
            root.addProperty("policy", policy);
            String env = envOverride();
            if (env != null) {
                root.addProperty("envOverride", env);
            }
            com.google.gson.JsonArray array = new com.google.gson.JsonArray();
            for (McJavaSources.Source source : List.of(McJavaSources.TUNA_ADOPTIUM,
                    McJavaSources.HUAWEI_OPENJDK, McJavaSources.OFFICIAL)) {
                JsonObject row = new JsonObject();
                row.addProperty("id", source.id());
                row.addProperty("label", source.label());
                if (source.host() != null) {
                    row.addProperty("host", source.host());
                }
                com.google.gson.JsonArray vendors = new com.google.gson.JsonArray();
                source.vendors().forEach(vendor -> vendors.add(vendor.id()));
                row.add("vendors", vendors);
                ProbeResult probe = probes.get(source.id());
                if (probe != null) {
                    row.addProperty("ok", probe.ok());
                    row.addProperty("speedKBps", probe.speedKBps());
                    row.addProperty("sampleBytes", probe.sampleBytes());
                    row.addProperty("durationMs", probe.durationMs());
                    row.addProperty("probedAt", probe.probedAt() == null ? null : probe.probedAt().toString());
                    row.addProperty("failures", probe.failures());
                    row.addProperty("skipUntil", probe.skipUntil() == null ? null : probe.skipUntil().toString());
                    if (!probe.lastError().isBlank()) {
                        row.addProperty("error", probe.lastError());
                    }
                }
                array.add(row);
            }
            root.add("sources", array);
            JsonObject chosen = new JsonObject();
            choices.forEach((vendor, source) -> chosen.addProperty(vendor.id(), source));
            root.add("choices", chosen);
            return root;
        }
    }

    // ---------------------------------------------------------------- helpers --

    private static Sampler httpSampler() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        return url -> {
            HttpRequest request = HttpRequest.newBuilder(java.net.URI.create(url))
                    .timeout(PER_SOURCE_TIMEOUT)
                    .header("User-Agent", "HDSL-web")
                    .header("Range", "bytes=0-" + (SAMPLE_BYTES - 1))
                    .GET()
                    .build();
            HttpResponse<InputStream> response =
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] buffer = new byte[8192];
            byte[] head = new byte[0];
            long bytes = 0;
            int read;
            try (InputStream in = response.body()) {
                while ((read = in.read(buffer)) >= 0) {
                    if (bytes == 0 && read > 0) {
                        head = java.util.Arrays.copyOf(buffer, Math.min(read, 8));
                    }
                    bytes += read;
                    if (bytes >= SAMPLE_BYTES) {
                        break;
                    }
                }
            }
            return new Sample(response.statusCode(), head, bytes, 0);
        };
    }

    private static String string(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        return value == null || !value.isJsonPrimitive() ? fallback : value.getAsString();
    }

    private static boolean bool(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsBoolean();
    }

    private static @Nullable Instant instant(@Nullable JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        try {
            return Instant.parse(element.getAsString());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
