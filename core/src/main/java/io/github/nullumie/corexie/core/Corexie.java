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
import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.NotNull;
import tools.jackson.core.JacksonException;

public final class Corexie {

    private static final @NotNull String META_FILE_NAME = "/corexie.json";
    private static final @NotNull String LOG_PATH_PROPERTY = "corexie.log.path";
    private static final @NotNull String LOG_MODE_PROPERTY = "corexie.log.mode";

    private static final ConcurrentHashMap<String, CoreNode> nodes = new ConcurrentHashMap<>();

    private static String name;
    private static Version version;

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
        CoreMeta meta = loadMeta();
        name = meta.getName();
        version = meta.getVersion();
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

    private static @NotNull CoreMeta loadMeta() {
        InputStream rawStream = CoreNode.class.getResourceAsStream(META_FILE_NAME);
        if (rawStream == null) {
            throw new UncheckedIOException(
                    "Metadata file not found on classpath: " + META_FILE_NAME,
                    new FileNotFoundException("Resource path: " + META_FILE_NAME));
        }
        try (InputStream inputStream = rawStream) {
            return Json.get().read(inputStream, CoreNodeMeta.class);
        } catch (IOException | JacksonException e) {
            IOException ioCause =
                    (e instanceof IOException ioEx) ? ioEx : new IOException(e.getMessage(), e);
            throw new UncheckedIOException(
                    "Failed to read or parse metadata from: " + META_FILE_NAME, ioCause);
        }
    }
}
