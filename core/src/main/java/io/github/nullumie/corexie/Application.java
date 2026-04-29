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
package io.github.nullumie.corexie;

import com.github.zafarkhaja.semver.Version;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class Application {

    public enum State {
        INITIALIZED,
        STARTING,
        RUNNING,
        SHUTTING,
        SHUTDOWN,
        FAILED
    }

    static {
        setupShutdownHook();
    }

    private static final @NotNull String LOG_PATH_PROPERTY = "corexie.log.path";
    private static final @NotNull String LOG_MODE_PROPERTY = "corexie.log.mode";

    private static @Nullable Application instance;

    private final @NotNull String name;
    private final @NotNull Version version;
    private final @NotNull Thread thread;
    private final @NotNull Logger logger;
    private final @NotNull Thread.UncaughtExceptionHandler uncaughtExceptionHandler;

    private volatile @NotNull State state = State.INITIALIZED;

    protected Application(
            @NotNull String name,
            @NotNull Version version,
            @NotNull String logPath,
            @NotNull LogMode logMode) {
        System.setProperty(LOG_PATH_PROPERTY, logPath.isBlank() ? "logs" : logPath);
        System.setProperty(LOG_MODE_PROPERTY, logMode.name().toLowerCase());

        this.name = name;
        this.version = version;
        this.thread = Thread.currentThread();
        this.logger = LoggerFactory.getLogger(this.name);
        this.uncaughtExceptionHandler = createUncaughtExceptionHandler();
    }

    public @NotNull String getName() {
        return name;
    }

    public @NotNull Version getVersion() {
        return version;
    }

    public @NotNull Logger getLogger() {
        return logger;
    }

    public @NotNull State getState() {
        return state;
    }

    public @NotNull String getLogPath() {
        return System.getProperty(LOG_PATH_PROPERTY);
    }

    public @NotNull LogMode getLogMode() {
        String mode = System.getProperty(LOG_MODE_PROPERTY);
        try {
            return LogMode.valueOf(mode.toUpperCase());
        } catch (Exception e) {
            return LogMode.NONE;
        }
    }

    public boolean isAlive() {
        return state == State.STARTING || state == State.RUNNING || state == State.SHUTTING;
    }

    public boolean isOnThread() {
        return Thread.currentThread() == thread;
    }

    public void shutdown() {
        if (state == State.INITIALIZED || state == State.SHUTDOWN || state == State.FAILED) return;
        state = State.SHUTTING;
        thread.interrupt();
    }

    protected void sleep(long timeout) throws InterruptedException {
        assertOnThread();
        try {
            TimeUnit.NANOSECONDS.sleep(timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (state == State.SHUTTING) return;
            throw e;
        }
    }

    protected void run() {
        assertOnThread();
        if (isAlive()) return;

        instance = this;
        String oldThreadName = thread.getName();

        try {
            thread.setUncaughtExceptionHandler(uncaughtExceptionHandler);
            thread.setName(getName().toLowerCase() + "-main");

            state = State.STARTING;
            try {
                onStartup();
            } catch (Throwable t) {
                lifecycleException("startup", t);
                return;
            }

            state = State.RUNNING;
            try {
                while (state == State.RUNNING) {
                    onExecute();
                }
            } catch (Throwable t) {
                lifecycleException("execute", t);
            }

            if (state == State.SHUTTING) {
                try {
                    onShutdown();
                    state = State.SHUTDOWN;
                } catch (Throwable t) {
                    lifecycleException("shutdown", t);
                }
            }

        } catch (Throwable fatal) {
            lifecycleException("internal", fatal);
        } finally {
            thread.setName(oldThreadName);
            thread.setUncaughtExceptionHandler(null);
        }
    }

    protected abstract void onStartup() throws Exception;

    protected abstract void onExecute() throws Exception;

    protected abstract void onShutdown() throws Exception;

    protected abstract void onException(@NotNull Throwable throwable);

    public static @NotNull Optional<Application> getApplication() {
        return Optional.ofNullable(instance);
    }

    protected void assertOnThread() {
        if (isOnThread()) return;

        Thread current = Thread.currentThread();

        throw new WrongThreadException(
                String.format(
                        "Invalid thread access: method must be called on '%s' (id=%d) but was '%s' (id=%d)",
                        thread.getName(),
                        thread.threadId(),
                        current.getName(),
                        current.threadId()));
    }

    private void lifecycleException(@NotNull String lifecycle, @NotNull Throwable throwable) {
        state = State.FAILED;

        String message = String.format("Application lifecycle failure (phase=%s)", lifecycle);

        ApplicationLifecycleException exception =
                new ApplicationLifecycleException(message, throwable);

        logger.error(message, throwable);

        onException(exception);
    }

    private @NotNull Thread.UncaughtExceptionHandler createUncaughtExceptionHandler() {
        return (t, e) -> {
            logger.error("FATAL: Uncaught exception in thread {}", t.getName(), e);
            state = State.FAILED;
        };
    }

    private static void setupShutdownHook() {
        System.setProperty("log4j.shutdownHookEnabled", "false");
        Thread shutdownHookThread =
                new Thread(
                        () -> {
                            if (instance == null
                                    || instance.state == State.INITIALIZED
                                    || instance.state == State.SHUTDOWN) return;
                            instance.shutdown();
                            try {
                                instance.thread.join();
                            } catch (InterruptedException _) {
                            }
                            LogManager.shutdown();
                        });
        Runtime.getRuntime().addShutdownHook(shutdownHookThread);
    }
}
