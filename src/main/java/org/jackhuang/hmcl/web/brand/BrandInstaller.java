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
package org.jackhuang.hmcl.web.brand;

import org.jackhuang.hmcl.dsh.DshNodeRuntime;
import org.jackhuang.hmcl.dsh.NpmRegistry;
import org.jackhuang.hmcl.util.io.FileUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Installs and enumerates the tool releases of one [Brand].
///
/// Where the ZCode category builds its distribution from upstream sources, the
/// brand categories install **published npm packages** — the same mechanism the
/// dsh installer uses, minus the lockstep machinery: one package, one version,
/// no companion packages. The command mirrors the dsh install exactly:
///
/// ```
/// pnpm add --dir <releases>/<version> <pkg>@<version> \
///     --reporter=append-only --registry=<effective> \
///     --config.dangerously-allow-all-builds=true
/// ```
///
/// Two details are load-bearing:
///
/// - `--config.dangerously-allow-all-builds=true` — pnpm 10 and newer refuse to
///   run dependency lifecycle scripts by default, and Kimi Code's `postinstall`
///   is what downloads its native binary. Without the flag the install
///   "succeeds" into a prefix with no working executable.
/// - `--registry` is the panel's effective download source, so a mirror-first
///   deployment installs from the mirror like everything else. (Kimi Code's
///   postinstall still reaches its vendor CDN for the binary; fully offline
///   installs of that brand are impossible, which the docs say.)
///
/// Each version lands in `releases/<version>/`; a `current` symlink points at
/// the newest successful install and is what new instances default to.
@NotNullByDefault
public final class BrandInstaller {

    /// One installed release, as listed by the panel.
    public record Release(String version, String path, boolean current) {
    }

    private static final String RELEASES_DIR = "releases";
    private static final String CURRENT_LINK = "current";
    private static final String INSTALL_LOG = "install.log";

    private BrandInstaller() {
    }

    /// Installs one version of the brand's package into `releases/<version>`.
    ///
    /// Idempotent: an already-installed version reports done without running
    /// pnpm again. The merged output is appended to `<brandRoot>/install.log`.
    ///
    /// @param brand the brand
    /// @param version the exact version to install
    /// @param brandRoot the brand's directory under the data directory
    /// @param message progress sink for the task record
    /// @throws BrandException when pnpm is missing, the install fails, or the
    ///                          installed prefix has no executable
    public static void install(Brand brand, String version, Path brandRoot, Consumer<String> message)
            throws BrandException {
        Path releases = brandRoot.resolve(RELEASES_DIR);
        Path target = releases.resolve(version);
        Path bin = target.resolve("node_modules/.bin/" + brand.binName());
        if (Files.exists(bin)) {
            message.accept(brand.npmPackage() + " " + version + " is already installed");
            pointCurrentAt(brandRoot, target);
            return;
        }

        DshNodeRuntime runtime = DshNodeRuntime.detect()
                .orElseThrow(() -> new BrandException("Node.js was not found; installing " + brand.npmPackage()
                        + " needs the toolchain the panel itself runs on"));
        String pnpm = runtime.pnpm() == null
                ? "pnpm"
                : runtime.pnpm().toString();
        String registry = NpmRegistry.effective().registry();

        try {
            Files.createDirectories(target);
            // pnpm add wants a package.json to extend; give it a minimal private one.
            Path manifest = target.resolve("package.json");
            if (!Files.exists(manifest)) {
                Files.writeString(manifest,
                        "{\"name\":\"hdsl-brand-" + brand.id() + "\",\"private\":true}\n",
                        StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new BrandException("Failed to prepare " + target, e);
        }

        List<String> command = List.of(
                pnpm, "add", "--dir", target.toString(),
                brand.npmPackage() + "@" + version,
                "--reporter=append-only",
                "--registry=" + registry,
                "--config.dangerously-allow-all-builds=true");
        message.accept("Installing " + brand.npmPackage() + " " + version);
        LOG.info("Installing " + brand.npmPackage() + " " + version + ": " + String.join(" ", command));

        Path log = brandRoot.resolve(INSTALL_LOG);
        try (OutputStreamAppend output = new OutputStreamAppend(log);
                BufferedReader reader = run(command, target, output)) {
            StringBuilder tail = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (tail.length() > 0) {
                    tail.append(" | ");
                }
                tail.append(line);
                if (tail.length() > 2000) {
                    tail.delete(0, tail.length() - 1000);
                }
                message.accept(line);
            }
            int code = output.exitCode();
            if (code != 0) {
                throw new BrandException(brand.npmPackage() + " " + version + " failed to install (exit " + code
                        + "): " + tail);
            }
        } catch (IOException e) {
            throw new BrandException("Failed to run pnpm for " + brand.npmPackage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrandException("Installing " + brand.npmPackage() + " was interrupted", e);
        }

        if (!Files.exists(bin)) {
            throw new BrandException(brand.npmPackage() + " " + version
                    + " installed without its executable (" + bin + "); the package's install scripts may have "
                    + "been skipped or failed — see " + log);
        }
        pointCurrentAt(brandRoot, target);
        message.accept("Installed " + brand.npmPackage() + " " + version);
        LOG.info("Installed " + brand.npmPackage() + " " + version + " into " + target);
    }

    /// The installed releases, newest version first.
    ///
    /// @param brandRoot the brand's directory
    /// @return the releases
    public static List<Release> releases(Path brandRoot) {
        Path releases = brandRoot.resolve(RELEASES_DIR);
        if (!Files.isDirectory(releases)) {
            return List.of();
        }
        Path current = currentLink(brandRoot);
        List<Release> found = new ArrayList<>();
        try (Stream<Path> entries = Files.list(releases)) {
            for (Path directory : entries.filter(Files::isDirectory).toList()) {
                String version = directory.getFileName().toString();
                found.add(new Release(version, directory.toString(),
                        current != null && current.equals(directory)));
            }
        } catch (IOException e) {
            LOG.warning("Failed to enumerate releases in " + releases, e);
        }
        found.sort(Comparator.comparing(Release::version, newestFirst()));
        return found;
    }

    /// The directory holding the given installed version, or `null` when that
    /// version is not installed.
    ///
    /// @param brand the brand
    /// @param brandRoot the brand's directory
    /// @param version the version
    /// @return the prefix, or `null`
    public static @Nullable Path packageDir(Brand brand, Path brandRoot, String version) {
        Path candidate = brandRoot.resolve(RELEASES_DIR).resolve(version);
        return Files.exists(candidate.resolve("node_modules/.bin/" + brand.binName()))
                ? candidate
                : null;
    }

    /// The release a new instance should default to: the `current` link's
    /// target, else the newest installed release, else `null`.
    ///
    /// @param brand the brand
    /// @param brandRoot the brand's directory
    /// @return the default package directory, or `null` when nothing is installed
    public static @Nullable Path defaultPackage(Brand brand, Path brandRoot) {
        Path current = currentLink(brandRoot);
        if (current != null && Files.exists(current.resolve("node_modules/.bin/" + brand.binName()))) {
            return current;
        }
        List<Release> releases = releases(brandRoot);
        return releases.isEmpty() ? null : Path.of(releases.get(0).path());
    }

    /// The tail of the install log, for the panel's log view.
    ///
    /// @param brandRoot the brand's directory
    /// @param maxLines the most lines to return
    /// @return the trailing lines, oldest first
    public static List<String> tailLog(Path brandRoot, int maxLines) {
        Path log = brandRoot.resolve(INSTALL_LOG);
        if (!Files.isRegularFile(log)) {
            return List.of();
        }
        try (var lines = Files.lines(log, StandardCharsets.UTF_8)) {
            List<String> all = lines.toList();
            return List.copyOf(all.subList(Math.max(0, all.size() - maxLines), all.size()));
        } catch (IOException e) {
            LOG.warning("Failed to read " + log, e);
            return List.of();
        }
    }

    /// Points the `current` symlink at the given release, replacing whatever
    /// link was there. Best effort: a broken link only affects the default for
    /// new instances, never an instance's pinned version.
    private static void pointCurrentAt(Path brandRoot, Path target) {
        Path link = brandRoot.resolve(CURRENT_LINK);
        try {
            Files.createDirectories(brandRoot.resolve(RELEASES_DIR));
            Files.deleteIfExists(link);
            Files.createSymbolicLink(link, target.toAbsolutePath());
        } catch (IOException e) {
            LOG.warning("Could not point " + link + " at " + target, e);
        }
    }

    /// The release the `current` link resolves to, or `null`.
    private static @Nullable Path currentLink(Path brandRoot) {
        Path link = brandRoot.resolve(CURRENT_LINK);
        if (!Files.isSymbolicLink(link)) {
            return null;
        }
        try {
            return link.toRealPath();
        } catch (IOException e) {
            return null;
        }
    }

    /// Runs one command in a directory, teeing its merged output to the log.
    private static BufferedReader run(List<String> command, Path directory, OutputStreamAppend output)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(directory.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        output.attach(process);
        return new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    }

    /// Numeric component-wise ordering, newest first.
    private static Comparator<String> newestFirst() {
        return (left, right) -> {
            String[] a = left.split("\\.");
            String[] b = right.split("\\.");
            for (int i = 0; i < Math.max(a.length, b.length); i++) {
                int x = i < a.length ? parsePart(a[i]) : 0;
                int y = i < b.length ? parsePart(b[i]) : 0;
                if (x != y) {
                    return Integer.compare(y, x);
                }
            }
            return right.compareTo(left);
        };
    }

    private static int parsePart(String part) {
        try {
            return Integer.parseInt(part.replaceAll("[^0-9].*$", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /// An output stream appended to a log file, wired to a process's merged
    /// output so every install line lands in the panel-visible log.
    private static final class OutputStreamAppend implements AutoCloseable {
        private final java.io.OutputStream stream;
        private Process process;

        OutputStreamAppend(Path log) throws IOException {
            Files.createDirectories(log.getParent());
            this.stream = Files.newOutputStream(log,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }

        void attach(Process process) {
            this.process = process;
        }

        int exitCode() throws InterruptedException {
            return process.waitFor();
        }

        @Override
        public void close() throws IOException {
            stream.close();
        }
    }
}
