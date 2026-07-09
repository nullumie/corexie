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
package io.github.nullumie.corexie.core;

import com.github.zafarkhaja.semver.Version;
import io.github.nullumie.corexie.json.Json;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.NotNull;

public final class Corexie {

    private static final @NotNull String META_FILE_NAME = "corexie.json";
    private static final @NotNull String LOG_PATH_PROPERTY = "corexie.log.path";
    private static final @NotNull String LOG_MODE_PROPERTY = "corexie.log.mode";

    private static final ConcurrentHashMap<String, CoreNode> nodes = new ConcurrentHashMap<>();

    private static @NotNull String name;
    private static @NotNull Version version;

    private Corexie() {}

    public static @NotNull String getName() {
        return name;
    }

    public static @NotNull Version getVersion() {
        return version;
    }

    public static @NotNull String getLogPath() {
        return System.getProperty(LOG_PATH_PROPERTY);
    }

    public static @NotNull LogMode getLogMode() {
        String mode = System.getProperty(LOG_MODE_PROPERTY);
        try {
            return LogMode.valueOf(mode.toUpperCase());
        } catch (Exception e) {
            return LogMode.NONE;
        }
    }

    public static void initialize(@NotNull String logPath, @NotNull LogMode logMode) {
        try (InputStream inputStream = Corexie.class.getResourceAsStream("/" + META_FILE_NAME)) {
            if (inputStream == null) {
                throw new FileNotFoundException(
                        String.format(
                                "Critical metadata resource file '%s' could not be found in the classpath relative to class %s.",
                                META_FILE_NAME, Corexie.class.getName()));
            }
            CoreMeta meta = Json.get().read(inputStream, CoreMeta.class);
            name = meta.getName();
            version = meta.getVersion();
        } catch (IOException e) {
            throw new IllegalStateException(
                    String.format(
                            "Failed to initialize Corexie framework. Resource loading failed for metadata file: '%s'.",
                            META_FILE_NAME),
                    e);
        }

        System.setProperty("log4j.shutdownHookEnabled", "false");
        setupLog(logPath, logMode);
        setupShutdownHook();
    }

    public static void shutdown() {
        setupLog("logs", LogMode.NONE);
        for (CoreNode node : nodes.values()) {
            if (node == null || node.getState().isInoperable()) return;
            node.shutdown();
            try {
                node.join();
            } catch (InterruptedException _) {
            }
        }
        LogManager.shutdown();
    }

    public static Optional<CoreNode> getNode(@NotNull String id) {
        return Optional.ofNullable(nodes.get(id.toLowerCase()));
    }

    static void addNode(@NotNull CoreNode coreNode) {
        if (nodes.putIfAbsent(coreNode.getId(), coreNode) != null) {
            throw new IllegalStateException(
                    "Failed to add core node: A node with identifier '"
                            + coreNode.getId()
                            + "' already exists.");
        }
    }

    static void removeNode(@NotNull CoreNode coreNode) {
        nodes.remove(coreNode.getName());
    }

    private static void setupLog(@NotNull String logPath, @NotNull LogMode logMode) {
        System.setProperty(LOG_PATH_PROPERTY, logPath.isBlank() ? "logs" : logPath);
        System.setProperty(LOG_MODE_PROPERTY, logMode.name().toLowerCase());
    }

    private static void setupShutdownHook() {
        Thread shutdownHookThread = new Thread(Corexie::shutdown);
        Runtime.getRuntime().addShutdownHook(shutdownHookThread);
    }
}
