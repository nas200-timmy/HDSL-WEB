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

/// Overworld structure types whose positions can be computed without running a
/// Minecraft server.
public enum StructureType {
    VILLAGE("village"),
    DESERT_PYRAMID("desert_pyramid"),
    JUNGLE_TEMPLE("jungle_temple"),
    IGLOO("igloo"),
    SWAMP_HUT("swamp_hut"),
    WOODLAND_MANSION("woodland_mansion"),
    OCEAN_MONUMENT("ocean_monument"),
    OCEAN_RUIN("ocean_ruin"),
    SHIPWRECK("shipwreck"),
    BURIED_TREASURE("buried_treasure"),
    RUINED_PORTAL("ruined_portal"),
    PILLAGER_OUTPOST("pillager_outpost"),
    ANCIENT_CITY("ancient_city"),
    TRIAL_CHAMBERS("trial_chambers"),
    STRONGHOLD("stronghold"),
    MINESHAFT("mineshaft");

    private final String key;

    StructureType(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
