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
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/// Runs the cheap `ffmpeg -version` / `-encoders` / `-hwaccels` probes.
///
/// The parsers are package-private and pure so the capability matrix can be tested
/// without an ffmpeg on the build machine. Every process has a hard timeout: a bad
/// binary must not hold a request thread, and a hung probe degrades to "unavailable".
@NotNullByDefault
public final class FfmpegProbe {

    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    private FfmpegProbe() {
    }

    public static FfmpegInfo probe(@Nullable FfmpegLocator.FfmpegPaths paths) {
        if (paths == null) {
            return FfmpegInfo.unavailable("ffmpeg or ffprobe was not found; install it or set transcode.ffmpeg_path");
        }
        try {
            // The exit code is the verdict, not the output: a binary whose shared
            // libraries are gone still prints a banner-shaped error and used to be
            // adopted as the panel's ffmpeg on the strength of that text alone.
            Result versionResult = runWithStatus(paths.ffmpeg(), List.of("-hide_banner", "-version"));
            String version = firstLine(versionResult.output());
            if (versionResult.exit() != 0 || version.isBlank()) {
                return FfmpegInfo.unavailable("ffmpeg -version exited " + versionResult.exit()
                        + (version.isBlank() ? " without a version line" : "")
                        + ": " + firstLine(versionResult.output()));
            }
            Result encodersResult = runWithStatus(paths.ffmpeg(), List.of("-hide_banner", "-encoders"));
            Set<String> encoders = parseEncoders(encodersResult.output());
            if (encodersResult.exit() != 0 || encoders.isEmpty()) {
                return FfmpegInfo.unavailable("ffmpeg -encoders exited " + encodersResult.exit()
                        + " and listed no encoder: " + firstLine(encodersResult.output()));
            }
            // A build that cannot list its accelerators or filters is still an
            // ffmpeg; those two only narrow what a preset may ask for.
            Result hwaccelsResult = runWithStatus(paths.ffmpeg(), List.of("-hide_banner", "-hwaccels"));
            Result filtersResult = runWithStatus(paths.ffmpeg(), List.of("-hide_banner", "-filters"));
            return new FfmpegInfo(paths.ffmpeg(), paths.ffprobe(), version,
                    encoders, parseHwaccels(hwaccelsResult.output()), parseFilters(filtersResult.output()),
                    null, System.currentTimeMillis());
        } catch (Exception e) {
            return FfmpegInfo.unavailable("ffmpeg probe failed: " + e.getMessage());
        }
    }

    /// A finished probe: the exit code and everything the process wrote.
    public record Result(int exit, String output) {
    }

    /// Runs a command and returns its combined output.
    public static String run(Path executable, List<String> args) throws IOException, InterruptedException {
        return runWithStatus(executable, args).output();
    }

    /// Runs a command to completion and reports both its exit code and output.
    public static Result runWithStatus(Path executable, List<String> args)
            throws IOException, InterruptedException {
        List<String> command = new java.util.ArrayList<>(args.size() + 1);
        command.add(executable.toString());
        command.addAll(args);
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        java.util.concurrent.ExecutorService reader =
                java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "ffmpeg-probe-reader");
                    thread.setDaemon(true);
                    return thread;
                });
        try {
            java.util.concurrent.Future<byte[]> future = reader.submit(() -> process.getInputStream().readAllBytes());
            if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                future.cancel(true);
                throw new IOException("ffmpeg did not answer within " + TIMEOUT.toSeconds() + "s");
            }
            return new Result(process.exitValue(), new String(future.get(), StandardCharsets.UTF_8));
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IOException("could not read ffmpeg output", e.getCause());
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            reader.shutdownNow();
        }
    }

    static String firstLine(String output) {
        for (String line : output.split("\\R")) {
            if (!line.isBlank()) {
                return line.trim();
            }
        }
        return "";
    }

    static Set<String> parseEncoders(String output) {
        Set<String> result = new LinkedHashSet<>();
        for (String line : output.split("\\R")) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("^\\s*[VAS][A-Z.]{5}\\s+(\\S+)")
                    .matcher(line);
            if (matcher.find()) {
                String name = matcher.group(1);
                if (!"=".equals(name)) {
                    result.add(name);
                }
            }
        }
        return result;
    }

    static Set<String> parseHwaccels(String output) {
        Set<String> result = new LinkedHashSet<>();
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("Hardware acceleration methods:")) {
                continue;
            }
            if (trimmed.indexOf(' ') < 0) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /// Parses `-filters`.
    ///
    /// The flag column is two characters on a filter row (` .. libvmaf`) while
    /// the legend above it uses three (`  T.. = Timeline support`); matching a
    /// fixed three skipped every filter on ffmpeg 9.x, which made filter-only
    /// features (libvmaf) look absent.
    static Set<String> parseFilters(String output) {
        Set<String> result = new LinkedHashSet<>();
        for (String line : output.split("\\R")) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("^\\s*[TSC.|]{2,3}\\s+(\\S+)")
                    .matcher(line);
            if (matcher.find()) {
                String name = matcher.group(1);
                if (!"=".equals(name)) {
                    result.add(name);
                }
            }
        }
        return result;
    }

    /// The probe result. `available` is false when either binary is missing or the
    /// version line could not be read; `encoders`/`hwaccels` are then empty.
    public record FfmpegInfo(
            @Nullable Path ffmpeg,
            @Nullable Path ffprobe,
            String version,
            Set<String> encoders,
            Set<String> hwaccels,
            Set<String> filters,
            @Nullable String error,
            long probedAt) {

        public FfmpegInfo {
            encoders = Set.copyOf(encoders);
            hwaccels = Set.copyOf(hwaccels);
            filters = Set.copyOf(filters);
        }

        public static FfmpegInfo unavailable(String error) {
            return new FfmpegInfo(null, null, "", Set.of(), Set.of(), Set.of(), error,
                    System.currentTimeMillis());
        }

        public boolean available() {
            return ffmpeg != null && ffprobe != null && error == null;
        }

        public boolean hasEncoder(String name) {
            return encoders.contains(name);
        }

        public boolean hasHardwareAcceleration(String name) {
            return hwaccels.contains(name);
        }
    }
}