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
package org.jackhuang.hmcl.web.plain;

import org.jackhuang.hmcl.util.platform.Executables;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/// Server-side markdown for the legacy surface, rendered by the **panel's own
/// frontend renderer**.
///
/// `web/scripts/render-markdown.mjs` is bundled at build time into
/// `/web-legacy/ssr/render-markdown.cjs` and runs react-markdown with the same
/// plugin chain and the same sanitize schema as the SPA's `Markdown.tsx`, so a
/// transcript read without JavaScript carries exactly the markup a modern
/// browser shows. Rendering is expensive (a Node process), so it happens once
/// per finalized message and the HTML is cached under `<data>/plain-md/`,
/// keyed by message id and the hash of the content — a regenerate or an edit
/// changes the hash and therefore misses the cache.
///
/// Every failure mode degrades to `null`, and the caller then renders the
/// escaped `<pre>` the surface always had: no Node on a bare `java -jar`
/// install, a missing bundle, a renderer crash, a timeout. The legacy surface
/// must never lose the message because its pretty rendering failed.
@NotNullByDefault
public final class MarkdownSsr {

    private static final org.jackhuang.hmcl.util.logging.Logger LOG =
            org.jackhuang.hmcl.util.logging.Logger.LOG;

    /// The bundled renderer, as shipped inside the jar.
    private static final String RESOURCE = "/web-legacy/ssr/render-markdown.cjs";

    /// A renderer that cannot answer in this time is treated as absent.
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /// A rendered fragment larger than this is not a chat message.
    private static final long MAX_OUTPUT_BYTES = 4L * 1024 * 1024;

    /// Cached fragments beyond this count prune the oldest files.
    private static final int MAX_CACHE_FILES = 2000;

    private final Path cacheDir;
    private final AtomicReference<Optional<Path>> node = new AtomicReference<>();
    private final AtomicReference<Optional<Path>> script = new AtomicReference<>();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public MarkdownSsr(Path cacheDir) {
        this.cacheDir = cacheDir;
    }

    /// The rendered HTML for one finalized message, or `null` when server-side
    /// rendering is unavailable and the caller should fall back.
    ///
    /// @param messageId the stored message id, used in the cache file name
    /// @param content   the stored markdown text
    /// @return the rendered fragment, or `null`
    public @Nullable String render(String messageId, String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        Path cache = cacheFile(messageId, content);
        if (cache != null) {
            Object lock = locks.computeIfAbsent(cache.getFileName().toString(), key -> new Object());
            synchronized (lock) {
                try {
                    if (Files.isRegularFile(cache)) {
                        return Files.readString(cache, StandardCharsets.UTF_8);
                    }
                    String rendered = invoke(content);
                    if (rendered != null) {
                        writeCache(cache, rendered);
                    }
                    return rendered;
                } catch (IOException e) {
                    LOG.warning("Plain markdown cache failed for " + messageId, e);
                    return null;
                }
            }
        }
        return invoke(content);
    }

    /// `<cacheDir>/<messageId>-<sha256(content)>.html`, or `null` when the id
    /// carries characters that do not belong in a file name.
    private @Nullable Path cacheFile(String messageId, String content) {
        if (messageId == null || !messageId.matches("[A-Za-z0-9_-]+")) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String hash = HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
            return cacheDir.resolve(messageId + "-" + hash + ".html");
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

    private @Nullable String invoke(String content) {
        Path nodeExecutable = nodeExecutable();
        Path scriptFile = scriptFile();
        if (nodeExecutable == null || scriptFile == null) {
            return null;
        }
        Process process = null;
        try {
            process = new ProcessBuilder(nodeExecutable.toString(), scriptFile.toString())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(content.getBytes(StandardCharsets.UTF_8));
            }
            byte[] stdout;
            try (InputStream out = process.getInputStream()) {
                stdout = out.readNBytes((int) (MAX_OUTPUT_BYTES + 1));
            }
            if (!process.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                LOG.warning("Plain markdown renderer timed out");
                return null;
            }
            if (process.exitValue() != 0 || stdout.length > MAX_OUTPUT_BYTES) {
                LOG.warning("Plain markdown renderer answered " + process.exitValue());
                return null;
            }
            String rendered = new String(stdout, StandardCharsets.UTF_8);
            return rendered.isBlank() ? null : rendered;
        } catch (IOException e) {
            LOG.warning("Plain markdown renderer could not run", e);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /// `node` on the PATH, resolved once. A bare `java -jar` install without
    /// Node.js answers empty forever, which is the documented fallback.
    private @Nullable Path nodeExecutable() {
        Optional<Path> resolved = node.get();
        if (resolved == null) {
            resolved = Executables.resolve("node");
            node.set(resolved);
        }
        return resolved.orElse(null);
    }

    /// The bundled renderer unpacked beside the cache, refreshed when the jar
    /// ships a different build of it.
    private @Nullable Path scriptFile() {
        Optional<Path> current = script.get();
        if (current != null) {
            return current.orElse(null);
        }
        Optional<Path> unpacked = Optional.ofNullable(unpackScript());
        script.set(unpacked);
        return unpacked.orElse(null);
    }

    private @Nullable Path unpackScript() {
        try (InputStream resource = MarkdownSsr.class.getResourceAsStream(RESOURCE)) {
            if (resource == null) {
                LOG.warning("Plain markdown renderer is not in this jar");
                return null;
            }
            byte[] bundle = resource.readAllBytes();
            Files.createDirectories(cacheDir);
            Path target = cacheDir.resolve("render-markdown.cjs");
            if (Files.isRegularFile(target) && Files.size(target) == bundle.length) {
                return target;
            }
            Path staging = cacheDir.resolve("render-markdown.cjs.tmp");
            Files.write(staging, bundle);
            try {
                Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        } catch (IOException e) {
            LOG.warning("Plain markdown renderer could not be unpacked", e);
            return null;
        }
    }

    private void writeCache(Path cache, String rendered) throws IOException {
        Files.createDirectories(cacheDir);
        Path staging = cacheDir.resolve(cache.getFileName() + ".tmp");
        Files.write(staging, rendered.getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(staging, cache, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(staging, cache, StandardCopyOption.REPLACE_EXISTING);
        }
        prune();
    }

    /// Bounds the cache: beyond [MAX_CACHE_FILES] the oldest fragments go.
    private void prune() {
        try (Stream<Path> files = Files.list(cacheDir)) {
            var cached = files.filter(path -> path.getFileName().toString().endsWith(".html"))
                    .sorted(Comparator.comparingLong(this::modified))
                    .toList();
            for (int i = 0; i + MAX_CACHE_FILES < cached.size(); i++) {
                Files.deleteIfExists(cached.get(i));
            }
        } catch (IOException e) {
            // A cache that cannot be pruned is still a cache; the next write tries again.
        }
    }

    private long modified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}
