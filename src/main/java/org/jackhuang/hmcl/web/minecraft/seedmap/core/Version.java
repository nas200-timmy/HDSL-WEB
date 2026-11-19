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
package org.jackhuang.hmcl.web.minecraft.seedmap.core;

import java.util.Locale;

/// Minecraft release lines supported by the seed-map core.
///
/// Versions are grouped into two families because world generation changed
/// fundamentally in 1.18: [Family#LEGACY] covers the old layer stack (1.12.2
/// through 1.17), [Family#MODERN] covers multi-noise (1.18 and later).
public enum Version {
    V1_12_2("1.12.2", Family.LEGACY),
    V1_14_4("1.14.4", Family.LEGACY),
    V1_16_5("1.16.5", Family.LEGACY),
    V1_18_2("1.18.2", Family.MODERN),
    V1_20_6("1.20.6", Family.MODERN),
    V1_21_1("1.21.1", Family.MODERN),
    V1_21_3("1.21.3", Family.MODERN);

    private final String id;
    private final Family family;

    Version(String id, Family family) {
        this.id = id;
        this.family = family;
    }

    public String id() {
        return id;
    }

    public Family family() {
        return family;
    }

    /// Parses a panel version id such as `1.21.4` or `1.21`.
    ///
    /// Minor patches that share the same world-generation rules map to the
    /// nearest supported release line. Unknown or unsupported versions throw
    /// [IllegalArgumentException].
    public static Version parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("version is null");
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "1.12", "1.12.2" -> V1_12_2;
            case "1.14", "1.14.4" -> V1_14_4;
            case "1.16", "1.16.5" -> V1_16_5;
            case "1.18", "1.18.2" -> V1_18_2;
            case "1.20", "1.20.6" -> V1_20_6;
            case "1.21", "1.21.1", "1.21.3", "1.21.4" -> V1_21_3;
            default -> throw new IllegalArgumentException("unsupported Minecraft version: " + raw);
        };
    }

    public enum Family {
        LEGACY,
        MODERN
    }
}
