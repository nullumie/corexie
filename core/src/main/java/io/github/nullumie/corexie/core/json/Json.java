/*
 * SPDX-License-Identifier: LGPL-3.0-only
 *
 * Copyright (c) 2026 Nullumie
 *
 * This library is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, version 3 of the License only.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this library. If not, see <https://www.gnu.org/licenses/>.
 */
package io.github.nullumie.corexie.core.json;

import io.github.nullumie.corexie.core.json.module.SemVerModule;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

public final class Json {

    private static final @NotNull String DEFAULT_NAME = "default";
    private static final @NotNull Map<String, Json> instances = new ConcurrentHashMap<>();

    static {
        instances.put(
                DEFAULT_NAME, new Json(JsonMapper.builder().addModule(new SemVerModule()).build()));
    }

    private final @NotNull JsonMapper mapper;

    private Json(@NotNull JsonMapper mapper) {
        this.mapper = mapper;
    }

    public static @NotNull Json get() {
        return instances.get(DEFAULT_NAME);
    }

    public static @Nullable Json get(@NotNull String name) {
        return instances.get(name);
    }

    public static @Nullable Json remove(@NotNull String name) {
        if (DEFAULT_NAME.equalsIgnoreCase(name)) {
            throw new IllegalArgumentException("Cannot remove the default JSON instance.");
        }
        return instances.remove(name);
    }

    public static @NotNull Json create(@NotNull String name, @NotNull JsonMapper mapper) {
        if (DEFAULT_NAME.equalsIgnoreCase(name)) {
            throw new IllegalArgumentException("Cannot overwrite the default JSON instance.");
        }
        Json json = new Json(mapper);
        instances.put(name, json);
        return json;
    }

    public void write(@NotNull OutputStream out, @NotNull Object value) throws JacksonException {
        mapper.writeValue(out, value);
    }

    public @NotNull <T> T read(@NotNull InputStream src, @NotNull Class<T> clazz)
            throws JacksonException {
        return mapper.readValue(src, clazz);
    }
}
