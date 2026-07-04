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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.NotNull;

public final class Corexie {

    private static final @NotNull String LOG_PATH_PROPERTY = "corexie.log.path";
    private static final @NotNull String LOG_MODE_PROPERTY = "corexie.log.mode";

    private static final @NotNull Version version = Version.of(0, 2, 0, "SNAPSHOT");

    private static final ConcurrentHashMap<String, Application> applications =
            new ConcurrentHashMap<>();

    private Corexie() {}

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
        System.setProperty("log4j.shutdownHookEnabled", "false");
        setupLog(logPath, logMode);
        setupShutdownHook();
    }

    public static void shutdown() {
        setupLog("logs", LogMode.NONE);
        for (Application application : applications.values()) {
            if (application == null || application.getState().isInoperable()) return;
            application.shutdown();
            try {
                application.join();
            } catch (InterruptedException _) {
            }
        }
        LogManager.shutdown();
    }

    public static Optional<Application> getApplication(@NotNull String name) {
        return Optional.ofNullable(applications.get(name));
    }

    static void addApplication(@NotNull Application application) {
        if (applications.putIfAbsent(application.getName(), application) != null) {
            throw new IllegalStateException(
                    String.format(
                            "Failed to add application. An entry with name '%s' already exists in the registry.",
                            application.getName()));
        }
    }

    static void removeApplication(@NotNull Application application) {
        applications.remove(application.getName());
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
