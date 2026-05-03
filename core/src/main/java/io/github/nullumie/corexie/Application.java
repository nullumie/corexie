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
import java.util.concurrent.locks.LockSupport;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class Application {

    public enum State {
        INITIALIZED,
        IDLE,
        STARTING,
        RUNNING,
        RESUMING,
        PAUSING,
        PAUSED,
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
    private volatile long interval;

    private volatile @NotNull State state = State.INITIALIZED;

    protected Application(
            @NotNull String name,
            @NotNull Version version,
            @NotNull String logPath,
            @NotNull LogMode logMode,
            long interval) {
        ensureValidInterval(interval);

        System.setProperty(LOG_PATH_PROPERTY, logPath.isBlank() ? "logs" : logPath);
        System.setProperty(LOG_MODE_PROPERTY, logMode.name().toLowerCase());

        this.name = name;
        this.version = version;
        this.thread = Thread.currentThread();
        this.logger = LoggerFactory.getLogger(this.name);
        this.uncaughtExceptionHandler = createUncaughtExceptionHandler();

        this.interval = interval;
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

    public long getInterval() {
        return interval;
    }

    public long setInterval(long interval) {
        ensureValidInterval(interval);
        if (interval == this.interval) return this.interval;
        long oldInterval = this.interval;
        this.interval = interval;
        if (isOffThread()) wakeup();
        return oldInterval;
    }

    public boolean isAlive() {
        return state == State.STARTING
                || state == State.RUNNING
                || state == State.PAUSING
                || state == State.PAUSED
                || state == State.RESUMING
                || state == State.SHUTTING
                || state == State.IDLE;
    }

    public boolean isActive() {
        return state == State.STARTING
                || state == State.RUNNING
                || state == State.PAUSING
                || state == State.RESUMING
                || state == State.SHUTTING;
    }

    public boolean isInactive() {
        return state == State.IDLE || state == State.PAUSED;
    }

    public boolean isRunning() {
        return state == State.RUNNING;
    }

    public boolean isIdle() {
        return state == State.IDLE;
    }

    public boolean isPausing() {
        return state == State.PAUSING;
    }

    public boolean isPaused() {
        return state == State.PAUSING;
    }

    public boolean isResuming() {
        return state == State.RESUMING;
    }

    public boolean isShutting() {
        return state == State.SHUTTING;
    }

    public boolean isShutdown() {
        return state == State.SHUTDOWN;
    }

    public boolean isFailed() {
        return state == State.FAILED;
    }

    public boolean isOnThread() {
        return Thread.currentThread() == thread;
    }

    public boolean isOffThread() {
        return Thread.currentThread() != thread;
    }

    public void shutdown() {
        if (state == State.INITIALIZED || state == State.SHUTDOWN || state == State.FAILED) return;
        state = State.SHUTTING;
        thread.interrupt();
    }

    public void resume() {
        if (state != State.PAUSING && state != State.PAUSED) return;
        state = State.RESUMING;
        if (isOffThread()) wakeup();
    }

    public void pause() {
        if (state != State.RUNNING) return;
        state = State.PAUSING;
        if (isOffThread()) wakeup();
    }

    protected void sleep(long timeout) throws InterruptedException {
        ensureOnThread();
        if (timeout == 0) return;
        State lastState = state;
        if (isActive()) {
            if (!isPausing()) {
                state = State.IDLE;
            }
            LockSupport.parkNanos(timeout);
            if (isIdle()) {
                state = lastState;
            }
        }
        if (Thread.interrupted())
            throw new InterruptedException("Interrupted while waiting for " + timeout + "ns");
    }

    protected void wakeup() {
        ensureOffThread();
        if (!isInactive()) return;
        LockSupport.unpark(thread);
    }

    protected void run() {
        ensureOnThread();
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
            while (isAlive()) {
                if (isShutting()) break;

                switch (state) {
                    case PAUSING:
                        try {
                            onPause();
                        } catch (Throwable t) {
                            lifecycleException("pause", t);
                        }
                        if (state != State.PAUSING) break;
                        state = State.PAUSED;
                        break;
                    case RESUMING:
                        try {
                            onResume();
                        } catch (Throwable t) {
                            lifecycleException("resume", t);
                        }
                        if (state != State.RESUMING) break;
                        state = State.RUNNING;
                        break;
                    case RUNNING:
                        long startTime = System.nanoTime();
                        try {
                            onExecute();
                        } catch (Throwable t) {
                            lifecycleException("execute", t);
                        }

                        if (state != State.RUNNING) break;

                        long elapsedTime = System.nanoTime() - startTime;
                        if (elapsedTime >= interval) continue;
                        long sleepTime = interval - elapsedTime;

                        try {
                            sleep(sleepTime);
                        } catch (InterruptedException _) {
                        }

                        break;
                    case PAUSED:
                        try {
                            sleep(Long.MAX_VALUE);
                        } catch (InterruptedException _) {
                        }
                        break;
                }
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

    protected abstract void onPause() throws Exception;

    protected abstract void onResume() throws Exception;

    protected abstract void onShutdown() throws Exception;

    protected abstract void onException(@NotNull Throwable throwable);

    public static @NotNull Optional<Application> getApplication() {
        return Optional.ofNullable(instance);
    }

    protected void ensureOnThread() {
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

    protected void ensureOffThread() {
        if (isOffThread()) return;
        throw new WrongThreadException(
                String.format(
                        "Invalid thread access: method must not be called on '%s' (id=%d)",
                        thread.getName(), thread.threadId()));
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

    private static void ensureValidInterval(long interval) {
        if (interval >= 0) return;
        throw new IllegalArgumentException("Interval must not be negative: " + interval);
    }
}
