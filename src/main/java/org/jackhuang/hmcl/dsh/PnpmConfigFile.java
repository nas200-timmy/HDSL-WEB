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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// The pnpm configuration file, and the one line of it the settings page is responsible for.
///
/// **pnpm does not read its registry from the environment.** Measured on this image
/// (pnpm 11.28.2): `NPM_CONFIG_REGISTRY=https://a.example.invalid/ pnpm config get registry` answers
/// with the configured registry, and `npm_config_store_dir` is ignored the same way. What pnpm
/// reads is this file — `$XDG_CONFIG_HOME/pnpm/config.yaml`, which the container's entrypoint
/// writes once at startup from `NPM_CONFIG_REGISTRY`. npm, by contrast, does read the environment.
///
/// That asymmetry is why a setting changed in the panel has to be written here to be worth
/// anything: every pnpm invocation the launcher makes goes through this file — including the
/// `pnpm` that `dsh plugin` runs for itself, which the launcher never sees the command line of.
/// Passing `--registry=` at the call sites the launcher owns (which it also does, so the choice is
/// visible in the logs) cannot reach that one.
///
/// The file is rewritten rather than parsed: every line that is not this setting is kept exactly
/// as it was, so a `storeDir` an operator set by hand survives, and the value written is quoted by
/// [YamlScalar] rather than interpolated — the difference between a URL and a second setting.
///
/// ## Windows keeps the same fact in two other files
///
/// Measured on Windows 10 with pnpm 11.28.6, by running `pnpm config set` and watching which file
/// changed:
///
/// | what | where pnpm reads it |
/// | --- | --- |
/// | the registry | `%LOCALAPPDATA%\pnpm\config\auth.ini`, as `registry=<address>` |
/// | everything else (`storeDir`) | `%LOCALAPPDATA%\pnpm\config\config.yaml` |
///
/// Neither `%LOCALAPPDATA%\pnpm\config\rc` nor `$HOME/.config/pnpm/config.yaml` is read for the
/// registry there — a value written to either is silently ignored, which is how a Chinese mirror
/// configured in the panel still reached npmjs.org. The registry lives in an `auth.ini` because an
/// address may carry credentials, and that is why the two halves are separate files rather than
/// two keys in one.
@NotNullByDefault
public final class PnpmConfigFile {

    /// The property that points the writer somewhere else, so a test does not touch the real one.
    private static final String PATH_PROPERTY = "hdsl.pnpmConfig";

    /// The key this class owns.
    private static final String REGISTRY_KEY = "registry";

    /// The key that must not be lost — it is what makes two instances share packages instead of
    /// downloading them twice.
    private static final String STORE_KEY = "storeDir";

    private PnpmConfigFile() {
    }

    /// Returns the file pnpm reads the registry from.
    ///
    /// On POSIX that is `${XDG_CONFIG_HOME:-$HOME/.config}/pnpm/config.yaml`, the same path the
    /// container's entrypoint writes. On Windows it is `%LOCALAPPDATA%\pnpm\config\auth.ini`,
    /// because that is the file pnpm actually reads there — see the class comment for how that was
    /// measured.
    ///
    /// @return the file, which may not exist
    public static Path path() {
        String override = System.getProperty(PATH_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Path.of(override.trim());
        }
        if (org.jackhuang.hmcl.util.platform.OperatingSystem.CURRENT_OS
                == org.jackhuang.hmcl.util.platform.OperatingSystem.WINDOWS) {
            return windowsConfigDirectory().resolve("auth.ini");
        }
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path configHome = xdg != null && !xdg.isBlank()
                ? Path.of(xdg.trim())
                : Path.of(System.getProperty("user.home", "/home/hdsl"), ".config");
        return configHome.resolve("pnpm").resolve("config.yaml");
    }

    /// Returns pnpm's user config directory on Windows.
    ///
    /// @return `%LOCALAPPDATA%\pnpm\config`
    private static Path windowsConfigDirectory() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            return Path.of(localAppData.trim()).resolve("pnpm").resolve("config");
        }
        // APPDATA is the roaming half and not where pnpm looks, but a path under it is still
        // closer than one under the home directory that pnpm provably does not read.
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Path.of(appData.trim()).resolve("pnpm").resolve("config");
        }
        return Path.of(System.getProperty("user.home", ".")).resolve("pnpm").resolve("config");
    }

    /// Writes the registry in force, if it is not already there.
    ///
    /// Never throws: whatever this runs on — a settings save, a startup — a configuration file
    /// that cannot be written is a warning, not a failed request. The next save tries again.
    public static void apply() {
        String registry = NpmRegistry.effective().registry();
        try {
            if (write(registry)) {
                LOG.info("pnpm's registry is now " + registry + " (" + path() + ")");
            }
        } catch (IOException | RuntimeException e) {
            LOG.warning("Could not write the pnpm registry into " + path(), e);
        }
    }

    /// Writes one registry into the file, keeping every other line.
    ///
    /// @param registry the address, which must already have been through [NpmRegistry#normalize]
    /// @return whether a file changed
    /// @throws IOException when it cannot be read or written
    public static boolean write(String registry) throws IOException {
        Path file = path();
        String store = System.getenv("PNPM_STORE_DIR");
        boolean storeWanted = store != null && !store.isBlank();

        if (Format.of(file) == Format.INI) {
            // Windows: the registry and everything else are two files, so each line goes to the
            // one pnpm reads it from. Writing the registry into the YAML half is not a smaller
            // change — it is a change pnpm ignores.
            Path storeFile = file.resolveSibling("config.yaml");
            boolean changed = rewrite(storeFile, Format.YAML, null, storeWanted ? store : null);
            return rewrite(file, Format.INI, registry, null) || changed;
        }
        return rewrite(file, Format.YAML, registry, storeWanted ? store : null);
    }

    /// Rewrites the settings this class owns, keeping every other line exactly as it was.
    ///
    /// @param file     the file to rewrite
    /// @param format   the syntax of that file
    /// @param registry the registry to write, or `null` to leave the file's alone
    /// @param store    the store directory to add when the file does not name one, or `null`
    /// @return whether the file changed
    /// @throws IOException when it cannot be read or written
    private static boolean rewrite(Path file, Format format, @Nullable String registry, @Nullable String store)
            throws IOException {
        List<String> kept = new ArrayList<>();
        boolean hadStore = false;
        boolean exists = Files.isRegularFile(file);
        if (exists) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (registry != null && isSetting(trimmed, REGISTRY_KEY)) {
                    continue;
                }
                if (isSetting(trimmed, STORE_KEY)) {
                    // A store directory named here — by the entrypoint, by an operator — is kept
                    // as it is: this class runs on every settings save, and taking a hand-set
                    // value away would be a worse surprise than not having set it at all.
                    hadStore = true;
                }
                kept.add(line);
            }
        }

        List<String> written = new ArrayList<>(kept);
        while (!written.isEmpty() && written.get(written.size() - 1).isBlank()) {
            written.remove(written.size() - 1);
        }
        if (store != null && !hadStore) {
            written.add(format.line(STORE_KEY, store));
        }
        if (registry != null) {
            written.add(format.line(REGISTRY_KEY, registry));
        }
        String text = String.join(System.lineSeparator(), written) + System.lineSeparator();

        if (!exists && registry == null && store == null) {
            // Nothing to add and no file to keep: do not create one. The Windows path calls
            // this with `store == null` on every settings save, and a file holding a single
            // newline is a setting nobody set.
            return false;
        }
        if (text.equals(readText(file))) {
            return false;
        }
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, text, StandardCharsets.UTF_8);
        restrict(temporary);
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // Windows refuses `ATOMIC_MOVE` across volumes, and some filesystems refuse it
            // outright; a plain replace is still better than the half-written file that writing
            // in place would leave.
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
        return true;
    }

    /// Reports whether a line sets a key, in either syntax this class writes.
    ///
    /// @param trimmedLine the line, trimmed
    /// @param key         the key
    /// @return whether the line assigns that key
    private static boolean isSetting(String trimmedLine, String key) {
        if (!trimmedLine.startsWith(key)) {
            return false;
        }
        String rest = trimmedLine.substring(key.length()).trim();
        return rest.startsWith(":") || rest.startsWith("=");
    }

    /// How a setting is spelled in a given file's syntax.
    private enum Format {
        /// `registry: "https://…"` — pnpm's YAML user config, and what the container writes.
        YAML(": ", true),
        /// `registry=https://…` — pnpm's INI user config, the form npm also reads.
        INI("=", false);

        private final String separator;
        private final boolean quoted;

        Format(String separator, boolean quoted) {
            this.separator = separator;
            this.quoted = quoted;
        }

        /// The file's syntax, told from its name.
        ///
        /// @param file the file
        /// @return the syntax that file is written in
        static Format of(Path file) {
            String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            return name.endsWith(".ini") || name.endsWith(".rc") ? INI : YAML;
        }

        /// One assignment in this syntax.
        ///
        /// @param key   the key
        /// @param value the value
        /// @return the line
        String line(String key, String value) {
            return key + separator + (quoted ? YamlScalar.of(value) : value);
        }
    }

    private static @Nullable String readText(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /// Keeps the file to its owner.
    ///
    /// A registry address is not a secret, but the file beside this one may be, and a
    /// configuration nobody else can read is one fewer thing to think about.
    private static void restrict(Path file) {
        try {
            Files.setPosixFilePermissions(file, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (IOException | UnsupportedOperationException e) {
            LOG.warning("Could not restrict the permissions of " + file, e);
        }
    }
}
