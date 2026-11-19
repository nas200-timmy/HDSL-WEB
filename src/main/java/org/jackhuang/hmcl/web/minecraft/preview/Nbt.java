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
package org.jackhuang.hmcl.web.minecraft.preview;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// The smallest NBT reader that can open a chunk.
///
/// The panel already depends on no NBT library, and adding one for two tags
/// would be the wrong trade: a chunk is a compound tree, and everything the map
/// needs (`Biomes`, `sections`, `structures`) is reachable with eleven tag
/// types. Numbers come back as the widest Java type that cannot lose them
/// (`int` for byte/short/int, `long` for long), arrays stay arrays, lists stay
/// lists.
///
/// The parser is deliberately strict: an unknown tag throws instead of being
/// skipped, because a silently mis-parsed chunk produces a map that is wrong in
/// ways nobody can see.
@NotNullByDefault
final class Nbt {

    private Nbt() {
    }

    /// Parses one named root compound.
    ///
    /// @param data the decompressed chunk payload
    /// @return the root compound
    /// @throws IOException when the payload is not NBT this reader understands
    static Map<String, Object> read(byte[] data) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int type = in.readUnsignedByte();
            if (type != Tag.COMPOUND) {
                throw new IOException("the chunk root is not a compound (tag " + type + ")");
            }
            readString(in); // the root's name is empty in practice
            @SuppressWarnings("unchecked")
            Map<String, Object> root = (Map<String, Object>) readPayload(in, type);
            return root;
        }
    }

    private static Object readPayload(DataInputStream in, int type) throws IOException {
        return switch (type) {
            case Tag.BYTE -> (int) in.readByte();
            case Tag.SHORT -> (int) in.readShort();
            case Tag.INT -> in.readInt();
            case Tag.LONG -> in.readLong();
            case Tag.FLOAT -> in.readFloat();
            case Tag.DOUBLE -> in.readDouble();
            case Tag.BYTE_ARRAY -> {
                int length = in.readInt();
                if (length < 0 || length > 1 << 24) {
                    throw new IOException("byte array of " + length + " entries");
                }
                byte[] values = new byte[length];
                in.readFully(values);
                yield values;
            }
            case Tag.STRING -> readString(in);
            case Tag.LIST -> {
                int element = in.readUnsignedByte();
                int length = in.readInt();
                if (length < 0 || length > 1 << 22) {
                    throw new IOException("list of " + length + " entries");
                }
                List<Object> values = new ArrayList<>(Math.min(length, 1024));
                for (int i = 0; i < length; i++) {
                    values.add(readPayload(in, element));
                }
                yield values;
            }
            case Tag.COMPOUND -> {
                Map<String, Object> values = new LinkedHashMap<>();
                while (true) {
                    int tag = in.readUnsignedByte();
                    if (tag == Tag.END) {
                        break;
                    }
                    String name = readString(in);
                    values.put(name, readPayload(in, tag));
                }
                yield values;
            }
            case Tag.INT_ARRAY -> {
                int length = in.readInt();
                if (length < 0 || length > 1 << 22) {
                    throw new IOException("int array of " + length + " entries");
                }
                int[] values = new int[length];
                for (int i = 0; i < length; i++) {
                    values[i] = in.readInt();
                }
                yield values;
            }
            case Tag.LONG_ARRAY -> {
                int length = in.readInt();
                if (length < 0 || length > 1 << 22) {
                    throw new IOException("long array of " + length + " entries");
                }
                long[] values = new long[length];
                for (int i = 0; i < length; i++) {
                    values[i] = in.readLong();
                }
                yield values;
            }
            default -> throw new IOException("unsupported NBT tag " + type);
        };
    }

    /// NBT strings are an unsigned short length followed by UTF-8 — not Java's
    /// modified UTF-8, although they agree on every biome name and key.
    private static String readString(DataInputStream in) throws IOException {
        int length = in.readUnsignedShort();
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /// Reads a compound member as a map, or answers `null` when the tag is not
    /// a compound.
    static @Nullable Map<String, Object> compound(@Nullable Object value) {
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return typed;
        }
        return null;
    }

    /// Reads a compound member as a list, or answers an empty list.
    static List<?> list(@Nullable Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    /// Reads a compound member as an int, or answers the fallback.
    static int integer(@Nullable Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private interface Tag {
        int END = 0;
        int BYTE = 1;
        int SHORT = 2;
        int INT = 3;
        int LONG = 4;
        int FLOAT = 5;
        int DOUBLE = 6;
        int BYTE_ARRAY = 7;
        int STRING = 8;
        int LIST = 9;
        int COMPOUND = 10;
        int INT_ARRAY = 11;
        int LONG_ARRAY = 12;
    }
}
