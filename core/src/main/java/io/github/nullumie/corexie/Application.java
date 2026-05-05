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
import java.util.concurrent.locks.LockSupport;
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

    private final @NotNull String name;
    private final @NotNull Version version;
    private @Nullable Thread thread;
    private final @NotNull Logger logger;
    private final @NotNull Thread.UncaughtExceptionHandler uncaughtExceptionHandler;
    private volatile long interval;

    private volatile @NotNull State state = State.INITIALIZED;

    protected Application(@NotNull String name, @NotNull Version version, long interval) {

        this.name = name;
        this.version = version;
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

    public boolean isInoperable() {
        return state == State.INITIALIZED
                || state == State.SHUTTING
                || state == State.SHUTDOWN
                || state == State.FAILED;
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
        return state == State.PAUSED;
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
        if (isInoperable()) return;
        state = State.SHUTTING;
        assert thread != null;
        thread.interrupt();
    }

    public void resume() {
        if (state != State.PAUSING && state != State.PAUSED) return;
        state = State.RESUMING;
        if (isOffThread()) wakeup();
    }

    public void pause() {
        if (isInoperable() || isPausing() || isPaused()) return;
        state = State.PAUSING;
        if (isOffThread()) wakeup();
    }

    public void join() throws InterruptedException {
        if (thread == null || isOnThread()) return;
        thread.join();
    }

    public void start() {
        if (isAlive()) return;
        Corexie.addApplication(this);
        thread =
                new Thread(
                        () -> {
                            try {
                                Thread.currentThread()
                                        .setUncaughtExceptionHandler(uncaughtExceptionHandler);
                                _run();
                            } catch (Throwable fatal) {
                                lifecycleException("internal", fatal);
                            }
                            thread = null;
                            Corexie.removeApplication(this);
                        },
                        formatThreadName(getName()));
        thread.start();
    }

    public void run() {
        if (isAlive()) return;
        Corexie.addApplication(this);
        thread = Thread.currentThread();
        String oldThreadName = thread.getName();
        try {
            thread.setUncaughtExceptionHandler(uncaughtExceptionHandler);
            thread.setName(formatThreadName(getName()));
            _run();
        } catch (Throwable fatal) {
            lifecycleException("internal", fatal);
        } finally {
            thread.setName(oldThreadName);
            thread.setUncaughtExceptionHandler(null);
            thread = null;
            Corexie.removeApplication(this);
        }
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

    private void _run() {
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
    }

    protected abstract void onStartup() throws Exception;

    protected abstract void onExecute() throws Exception;

    protected abstract void onPause() throws Exception;

    protected abstract void onResume() throws Exception;

    protected abstract void onShutdown() throws Exception;

    protected abstract void onException(@NotNull Throwable throwable);

    protected void ensureOnThread() {
        if (isOnThread()) return;
        Thread current = Thread.currentThread();
        assert thread != null;
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
        assert thread != null;
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

    private static @NotNull String formatThreadName(@NotNull String name) {
        return name.toLowerCase() + "-main";
    }

    private static void ensureValidInterval(long interval) {
        if (interval >= 0) return;
        throw new IllegalArgumentException("Interval must not be negative: " + interval);
    }
}
