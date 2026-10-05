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

    /// Returns the file pnpm reads.
    ///
    /// The same path the entrypoint writes: `${XDG_CONFIG_HOME:-$HOME/.config}/pnpm/config.yaml`.
    ///
    /// @return the file, which may not exist
    public static Path path() {
        String override = System.getProperty(PATH_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Path.of(override.trim());
        }
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path configHome = xdg != null && !xdg.isBlank()
                ? Path.of(xdg.trim())
                : Path.of(System.getProperty("user.home", "/home/hdsl"), ".config");
        return configHome.resolve("pnpm").resolve("config.yaml");
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
    /// @return whether the file changed
    /// @throws IOException when it cannot be read or written
    public static boolean write(String registry) throws IOException {
        Path file = path();
        List<String> kept = new ArrayList<>();
        boolean hadStore = false;
        boolean hadRegistry = false;
        if (Files.isRegularFile(file)) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.startsWith(REGISTRY_KEY + ":")) {
                    hadRegistry = true;
                    continue;
                }
                if (trimmed.startsWith(STORE_KEY + ":")) {
                    hadStore = true;
                }
                kept.add(line);
            }
        }

        List<String> written = new ArrayList<>(kept);
        while (!written.isEmpty() && written.get(written.size() - 1).isBlank()) {
            written.remove(written.size() - 1);
        }
        if (!hadStore) {
            String store = System.getenv("PNPM_STORE_DIR");
            if (store != null && !store.isBlank()) {
                written.add(STORE_KEY + ": " + YamlScalar.of(store.trim()));
            }
        }
        written.add(REGISTRY_KEY + ": " + YamlScalar.of(registry));
        String text = String.join(System.lineSeparator(), written) + System.lineSeparator();

        if (hadRegistry && text.equals(readText(file))) {
            return false;
        }
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, text, StandardCharsets.UTF_8);
        restrict(temporary);
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // Some filesystems do not do an atomic replace; a plain one is still better than a
            // half-written file, which is what writing in place would leave.
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
        return true;
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
