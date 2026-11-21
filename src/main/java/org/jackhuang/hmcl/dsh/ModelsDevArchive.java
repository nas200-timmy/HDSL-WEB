/*
 * HDSL
 * Copyright (C) 2026  HDSL contributors
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
package org.jackhuang.hmcl.dsh;

import kala.compress.archivers.tar.TarArchiveEntry;
import kala.compress.archivers.tar.TarArchiveInputStream;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/// Reads the models.dev source archive when its `api.json` is slow or unreachable.
///
/// `api.json` is a generated artefact: the repository itself has one TOML file per provider and
/// one per model, and the published JSON is those files flattened. On this launcher's usual
/// network the JSON is a multi-minute download that often times out, while the 2.6 MB source
/// archive finishes in seconds. This reader parses the subset the account page needs — provider
/// id/name/endpoint/protocol/env and each model's name, limits, modalities and capabilities.
///
/// It is deliberately tolerant, like [DshModelCatalog.parse]: a model file with one odd member is
/// skipped rather than taking the whole catalogue down.
@NotNullByDefault
final class ModelsDevArchive {

    /// The archive the repository publishes for its default branch.
    static final String DEFAULT_URL =
            "https://codeload.github.com/anomalyco/models.dev/tar.gz/refs/heads/dev";

    private static final Duration TIMEOUT = Duration.ofSeconds(300);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final String USER_AGENT = org.jackhuang.hmcl.Metadata.NAME + "/"
            + org.jackhuang.hmcl.Metadata.VERSION + " (+" + org.jackhuang.hmcl.Metadata.HOMEPAGE_URL + ")";

    private ModelsDevArchive() {
    }

    static String archiveUrl() {
        String configured = System.getProperty("hdsl.modelCatalogArchive");
        return configured == null || configured.isBlank() ? DEFAULT_URL : configured.trim();
    }

    static DshModelCatalog.Catalog fetch() throws DshException {
        String url = archiveUrl();
        try {
            byte[] archived = download(url);
            DshModelCatalog.Catalog parsed = parse(archived);
            keepCopy(cachedFile(), archived);
            return parsed;
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new DshException("Failed to read the model catalogue archive from " + url
                    + " (" + e.getMessage() + ")", e);
        }
    }

    /// The archive is kept on disk so a restart does not pay for the same 2.6 MB download before
    /// the account page can show a provider list.
    static @Nullable DshModelCatalog.Catalog readCached() {
        Path file = cachedFile();
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            DshModelCatalog.Catalog parsed = parse(Files.readAllBytes(file));
            return new DshModelCatalog.Catalog(Files.getLastModifiedTime(file).toMillis(),
                    parsed.providers());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static Path cachedFile() {
        String name;
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            StringBuilder hex = new StringBuilder();
            for (byte value : digest.digest(archiveUrl().getBytes(StandardCharsets.UTF_8))) {
                hex.append(String.format("%02x", value));
            }
            name = hex.substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            name = "unhashed";
        }
        return DshPluginCatalog.cacheDirectory().resolve("models-dev-archive-" + name + ".tar.gz");
    }

    private static void keepCopy(Path file, byte[] bytes) {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, bytes);
        } catch (IOException e) {
            org.jackhuang.hmcl.util.logging.Logger.LOG.warning(
                    "Could not keep a copy of the model catalogue archive", e);
        }
    }

    private static byte[] download(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("Accept", "application/gzip, application/x-gzip, application/octet-stream")
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        try {
            var pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
            HttpResponse<byte[]> response;
            try {
                response = pending.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                pending.cancel(true);
                throw new IOException("timed out after " + TIMEOUT.toSeconds() + "s", e);
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof IOException io) {
                    throw io;
                }
                throw new IOException(cause == null ? "request failed" : cause.toString(), cause);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("HTTP " + response.statusCode());
            }
            if (response.body() == null || response.body().length == 0) {
                throw new IOException("empty archive");
            }
            return response.body();
        } finally {
            org.jackhuang.hmcl.util.io.HttpClients.closeQuietly(client);
        }
    }

    static DshModelCatalog.Catalog parse(byte[] tarGz) throws IOException {
        Map<String, String> providerFiles = new LinkedHashMap<>();
        Map<String, Map<String, String>> modelFiles = new LinkedHashMap<>();
        Map<String, String> baseFiles = new LinkedHashMap<>();

        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(tarGz));
             TarArchiveInputStream tar = new TarArchiveInputStream(gzip, StandardCharsets.UTF_8)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                if (entry.isDirectory() || entry.isSymbolicLink() || entry.isLink()) {
                    continue;
                }
                String path = stripTopDirectory(entry.getName().replace('\\', '/'));
                if (path.startsWith("providers/") && path.endsWith("/provider.toml")) {
                    String id = path.substring("providers/".length(),
                            path.length() - "/provider.toml".length());
                    if (!id.isBlank() && !id.contains("/")) {
                        providerFiles.put(id, read(tar));
                    }
                } else if (path.startsWith("providers/") && path.contains("/models/")
                        && path.endsWith(".toml")) {
                    int modelsAt = path.indexOf("/models/");
                    String id = path.substring("providers/".length(), modelsAt);
                    String model = path.substring(modelsAt + "/models/".length(),
                            path.length() - ".toml".length());
                    if (!id.isBlank() && !model.isBlank() && !model.contains("/")) {
                        modelFiles.computeIfAbsent(id, ignored -> new LinkedHashMap<>())
                                .put(model, read(tar));
                    }
                } else if (path.startsWith("models/") && path.endsWith(".toml")) {
                    String key = path.substring("models/".length(), path.length() - ".toml".length());
                    if (!key.isBlank()) {
                        baseFiles.put(key, read(tar));
                    }
                }
            }
        }

        List<DshModelCatalog.Provider> providers = new ArrayList<>();
        for (Map.Entry<String, String> provider : providerFiles.entrySet()) {
            String id = provider.getKey();
            Map<String, String> header = parseToml(provider.getValue());
            List<DshModelCatalog.Model> models = new ArrayList<>();
            for (Map.Entry<String, String> file
                    : modelFiles.getOrDefault(id, Map.of()).entrySet()) {
                Map<String, String> override = parseToml(file.getValue());
                Map<String, String> merged = new LinkedHashMap<>();
                String base = value(override.get("base_model"));
                if (base != null && baseFiles.containsKey(base)) {
                    merged.putAll(parseToml(baseFiles.get(base)));
                }
                merged.putAll(override);
                DshModelCatalog.Model model = model(file.getKey(), merged);
                if (model != null) {
                    models.add(model);
                }
            }
            models.sort(Comparator.comparing(model -> model.name().toLowerCase(Locale.ROOT)));
            providers.add(new DshModelCatalog.Provider(
                    id,
                    valueOr(header.get("name"), id),
                    value(header.get("api")),
                    array(header.get("env")),
                    valueOr(header.get("npm"), ""),
                    value(header.get("doc")),
                    List.copyOf(models)));
        }
        providers.sort(Comparator.comparing(provider -> provider.id().toLowerCase(Locale.ROOT)));
        return new DshModelCatalog.Catalog(System.currentTimeMillis(), List.copyOf(providers));
    }

    private static @Nullable DshModelCatalog.Model model(String id, Map<String, String> fields) {
        List<String> output = array(fields.get("modalities.output"));
        if (!output.isEmpty() && output.stream().noneMatch("text"::equalsIgnoreCase)) {
            return null;
        }
        List<String> input = array(fields.get("modalities.input"));
        boolean vision = input.stream().anyMatch(type -> type.startsWith("image/")
                || type.equals("application/pdf") || type.startsWith("video/"));
        String name = valueOr(fields.get("name"), id);
        Integer context = integer(fields.get("limit.context"));
        Integer maxOutput = integer(fields.get("limit.output"));
        boolean reasoning = supported(fields.get("capabilities.reasoning"));
        boolean tools = supported(fields.get("capabilities.tools"));
        List<String> efforts = efforts(fields.get("capabilities.reasoning"));
        return new DshModelCatalog.Model(id, name, context, maxOutput, reasoning, efforts,
                tools, vision, number(fields.get("cost.text.input.price")),
                number(fields.get("cost.text.output.price")), value(fields.get("status")));
    }

    private static String stripTopDirectory(String path) {
        int slash = path.indexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String read(TarArchiveInputStream tar) throws IOException {
        return new String(tar.readAllBytes(), StandardCharsets.UTF_8);
    }

    /// A deliberately small TOML reader: section + scalar keys, which is the whole shape of a
    /// provider/model file this launcher reads. Nested inline tables stay as their raw text and
    /// are interpreted by [supported]/[efforts].
    static Map<String, String> parseToml(String text) {
        Map<String, String> values = new LinkedHashMap<>();
        String section = "";
        for (String line : text.split("\n")) {
            String trimmed = stripComment(line).trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                section = trimmed.replace("[", "").replace("]", "").trim();
                continue;
            }
            int equals = trimmed.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String key = trimmed.substring(0, equals).trim();
            String value = trimmed.substring(equals + 1).trim();
            if (key.isEmpty() || value.isEmpty()) {
                continue;
            }
            values.put(section.isEmpty() ? key : section + "." + key, value);
        }
        return values;
    }

    private static String stripComment(String line) {
        boolean quote = false;
        boolean escaped = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\' && quote) {
                escaped = true;
            } else if (c == '"') {
                quote = !quote;
            } else if (c == '#' && !quote) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static String valueOr(@Nullable String raw, String fallback) {
        String value = value(raw);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static @Nullable String value(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length() - 1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\");
        }
        return text;
    }

    private static List<String> array(@Nullable String raw) {
        if (raw == null) {
            return List.of();
        }
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quote = false;
        for (int i = start + 1; i < end; i++) {
            char c = raw.charAt(i);
            if (c == '"') {
                quote = !quote;
                current.append(c);
            } else if (c == ',' && !quote) {
                addArrayValue(values, current);
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        addArrayValue(values, current);
        return List.copyOf(values);
    }

    private static void addArrayValue(List<String> values, StringBuilder current) {
        String value = value(current.toString());
        if (value != null && !value.isBlank()) {
            values.add(value);
        }
    }

    private static boolean supported(@Nullable String raw) {
        return raw != null && Pattern.compile("supported\\s*=\\s*true", Pattern.CASE_INSENSITIVE)
                .matcher(raw).find();
    }

    private static List<String> efforts(@Nullable String raw) {
        if (raw == null) {
            return List.of();
        }
        Matcher matcher = Pattern.compile("effort\\s*=\\s*(\\[[^]]*])").matcher(raw);
        return matcher.find() ? array(matcher.group(1)) : List.of();
    }

    private static @Nullable Integer integer(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim().replace("_", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static @Nullable Double number(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Double.valueOf(raw.trim().replace("_", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}