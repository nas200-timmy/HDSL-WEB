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
package org.jackhuang.hmcl.web.transcode.runtime;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.CancellationException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/// One pinned HTTPS download, shared by the source build and the portable
/// MinGW bootstrap.
///
/// The caller owns the destination and the SHA-256 comparison: this class only
/// streams bytes, checks that every redirect still points at a public HTTPS URL,
/// reports progress and stops when the task is cancelled. Keeping it separate
/// avoids a second downloader whose redirect handling would drift.
@NotNullByDefault
final class FfmpegDownload {

    private FfmpegDownload() {
    }

    /// The URLs to try for one artifact, in order: a deployment mirror prefix,
    /// the catalogue's own mirrors, then the pinned official URL.
    ///
    /// A prefix is concatenated with the absolute official URL
    /// (`https://gh-proxy.com/` + `https://github.com/...`), which is the shape
    /// most GitHub proxies use. The digest never changes with the source: a
    /// mirror that serves different bytes fails verification like any other.
    ///
    /// @param prefix   the configured prefix, empty for none
    /// @param official the pinned URL
    /// @param mirrors  the catalogue's alternates, possibly empty
    /// @return the candidates, first choice first, without duplicates
    static List<String> candidates(String prefix, String official, List<String> mirrors) {
        List<String> urls = new ArrayList<>();
        String base = prefix == null ? "" : prefix.trim();
        if (!base.isEmpty()) {
            urls.add(base + official);
        }
        if (mirrors != null) {
            urls.addAll(mirrors);
        }
        urls.add(official);
        return urls.stream().distinct().toList();
    }

    static void download(String url, Path target, Duration timeout,
                         FfmpegRuntimeManager.Progress progress,
                         BooleanSupplier cancelled) throws IOException {
        URI uri = URI.create(url);
        FfmpegInstaller.validateUrl(url);
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .GET()
                .build();
        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("ffmpeg_install_interrupted", e);
        }
        FfmpegInstaller.validateUrl(response.uri().toString());
        if (response.statusCode() != 200) {
            throw new IOException("download returned HTTP " + response.statusCode() + " for " + response.uri());
        }
        long total = response.headers().firstValueAsLong("content-length").orElse(-1);
        try (InputStream in = new BufferedInputStream(response.body());
             var out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[1 << 16];
            long copied = 0;
            int lastBucket = -1;
            int read;
            long deadline = System.nanoTime() + timeout.toNanos();
            while ((read = in.read(buffer)) >= 0) {
                if (cancelled.getAsBoolean()) {
                    throw new CancellationException("cancelled");
                }
                if (System.nanoTime() > deadline) {
                    throw new IOException("download timed out after " + timeout.toMinutes() + " min");
                }
                out.write(buffer, 0, read);
                copied += read;
                if (total > 0) {
                    int bucket = (int) (copied * 100 / total);
                    if (bucket != lastBucket) {
                        lastBucket = bucket;
                        progress.stage("downloading " + copied / (1024 * 1024) + " / "
                                + total / (1024 * 1024) + " MiB", 0.58 * copied / total);
                    }
                }
            }
            if (total > 0 && copied != total) {
                throw new IOException("truncated download: expected " + total + " bytes, got " + copied);
            }
        }
    }

    static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[1 << 16];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is not available", e);
        }
    }

    static void verifySha(Path file, String expected) throws IOException {
        String actual = sha256(file);
        if (!actual.equalsIgnoreCase(expected)) {
            throw new IOException("ffmpeg_checksum_mismatch: expected " + expected + ", got " + actual);
        }
    }
}