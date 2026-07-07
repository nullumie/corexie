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

/**
 * A thread-safe registry and wrapper for managing and utilizing {@link JsonMapper} instances.
 *
 * <p>This class provides a centralized management layer for named JSON mappers. It initializes with
 * a pre-configured {@code "default"} instance that automatically registers all custom Jackson
 * modules from the {@code io.github.nullumie.corexie.core.json.module} package.
 *
 * @see JsonMapper
 */
public final class Json {

    private static final @NotNull String DEFAULT_NAME = "default";
    private static final @NotNull Map<String, Json> instances = new ConcurrentHashMap<>();

    static {
        instances.put(
                DEFAULT_NAME,
                new Json(DEFAULT_NAME, JsonMapper.builder().addModule(new SemVerModule()).build()));
    }

    private final @NotNull String name;
    private final @NotNull JsonMapper mapper;

    private Json(@NotNull String name, @NotNull JsonMapper mapper) {
        this.name = name;
        this.mapper = mapper;
    }

    /**
     * Retrieves the unique registration name assigned to this {@code Json} instance.
     *
     * <p>For the primary global wrapper, this will return {@code "default"}.
     *
     * @return the non-null identifier name of this instance
     */
    public @NotNull String getName() {
        return name;
    }

    /**
     * Returns the underlying Jackson {@link JsonMapper} engine wrapped by this instance.
     *
     * <p>Use this method to access low-level mapping capabilities, custom configurations, or
     * advanced serialization features not directly exposed by this wrapper class.
     *
     * @return the non-null, configured {@code JsonMapper} instance
     */
    public @NotNull JsonMapper getMapper() {
        return mapper;
    }

    /**
     * Retrieves the default global {@code Json} manager instance.
     *
     * @return the default pre-configured {@code Json} instance; never null
     */
    public static @NotNull Json get() {
        return instances.get(DEFAULT_NAME);
    }

    /**
     * Retrieves a registered {@code Json} manager instance by its assigned name.
     *
     * @param name the unique name of the registered instance
     * @return the matching {@code Json} instance, or {@code null} if no instance is found with that
     *     name
     */
    public static @Nullable Json get(@NotNull String name) {
        return instances.get(name);
    }

    /**
     * Removes and unregisters a named {@code Json} instance from the registry.
     *
     * <p>The primary {@code "default"} instance is protected and cannot be deleted.
     *
     * @param name the unique name of the instance to remove
     * @return the removed {@code Json} instance, or {@code null} if no instance matched the name
     * @throws IllegalArgumentException if the provided {@code name} matches {@code "default"}
     *     (case-insensitive)
     */
    public static @Nullable Json remove(@NotNull String name) {
        if (DEFAULT_NAME.equalsIgnoreCase(name)) {
            throw new IllegalArgumentException("Cannot remove the default JSON instance.");
        }
        return instances.remove(name);
    }

    /**
     * Creates, registers, and returns a new named {@code Json} manager instance.
     *
     * <p>The primary {@code "default"} instance cannot be overwritten, and duplicate registration
     * names are rejected.
     *
     * @param name the unique name to assign to this instance
     * @param mapper the underlying {@link JsonMapper} engine to wrap
     * @return the newly created and registered {@code Json} instance; never null
     * @throws IllegalArgumentException if {@code name} matches {@code "default"}
     *     (case-insensitive), or if an instance with the specified name already exists
     */
    public static @NotNull Json create(@NotNull String name, @NotNull JsonMapper mapper) {
        if (DEFAULT_NAME.equalsIgnoreCase(name)) {
            throw new IllegalArgumentException("Cannot overwrite the default JSON instance.");
        }
        Json json = new Json(name, mapper);
        if (instances.putIfAbsent(name, json) != null) {
            throw new IllegalArgumentException(
                    "A JSON instance with the name '" + name + "' already exists.");
        }
        return json;
    }

    /**
     * Serializes a Java object into JSON string data and writes it to the specified output stream.
     *
     * @param out the destination stream where the JSON output will be written
     * @param value the object payload to serialize
     * @throws JacksonException if a low-level parsing or serialization error occurs
     */
    public void write(@NotNull OutputStream out, @NotNull Object value) throws JacksonException {
        mapper.writeValue(out, value);
    }

    /**
     * Deserializes JSON string content from the specified input stream into a Java object of the
     * target type.
     *
     * @param <T> the generic type of the expected return object
     * @param src the source stream containing the JSON content to process
     * @param clazz the class token representing the expected target type
     * @return the deserialized Java object wrapper; never null
     * @throws JacksonException if a low-level parsing, structural, or mapping error occurs
     */
    public @NotNull <T> T read(@NotNull InputStream src, @NotNull Class<T> clazz)
            throws JacksonException {
        return mapper.readValue(src, clazz);
    }
}
