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

    private final @NotNull String name;
    private final @NotNull Version version;
    private @Nullable Thread thread;
    private final @NotNull Logger logger;
    private final @NotNull Thread.UncaughtExceptionHandler uncaughtExceptionHandler;
    private volatile long interval;

    private volatile @NotNull CycleState state = CycleState.INITIALIZED;

    protected Application(@NotNull String name, @NotNull Version version, long interval) {
        this.name = name;
        this.version = version;
        this.logger = LoggerFactory.getLogger(this.name);
        this.uncaughtExceptionHandler = createUncaughtExceptionHandler();
        this.interval = validateInterval(interval);
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

    public @NotNull CycleState getState() {
        return state;
    }

    public long getInterval() {
        return interval;
    }

    public long setInterval(long interval) {
        validateInterval(interval);
        if (interval == this.interval) return this.interval;
        long oldInterval = this.interval;
        this.interval = interval;
        if (isOffThread()) wakeup();
        return oldInterval;
    }

    public boolean isOnThread() {
        assert thread != null;
        return Threads.isOnThread(thread);
    }

    public boolean isOffThread() {
        assert thread != null;
        return Threads.isOffThread(thread);
    }

    public void shutdown() {
        if (state.isInoperable()) return;
        state = CycleState.SHUTTING;
        assert thread != null;
        thread.interrupt();
    }

    public void resume() {
        if (!state.isPausing() && !state.isPaused()) return;
        state = CycleState.RESUMING;
        if (isOffThread()) wakeup();
    }

    public void pause() {
        if (state.isInoperable() || state.isPausing() || state.isPaused()) return;
        state = CycleState.PAUSING;
        if (isOffThread()) wakeup();
    }

    public void join() throws InterruptedException {
        if (thread == null || isOnThread()) return;
        thread.join();
    }

    public void start() {
        if (state.isAlive()) return;
        Corexie.addApplication(this);
        thread =
                new Thread(
                        () -> {
                            Thread.currentThread()
                                    .setUncaughtExceptionHandler(uncaughtExceptionHandler);
                            _run();
                            thread = null;
                            Corexie.removeApplication(this);
                        },
                        formatThreadName(getName()));
        thread.start();
    }

    public void run() {
        if (state.isAlive()) return;
        Corexie.addApplication(this);
        thread = Thread.currentThread();
        String oldThreadName = thread.getName();
        thread.setUncaughtExceptionHandler(uncaughtExceptionHandler);
        thread.setName(formatThreadName(getName()));
        _run();
        thread.setName(oldThreadName);
        thread.setUncaughtExceptionHandler(null);
        thread = null;
        Corexie.removeApplication(this);
    }

    protected long sleep(long timeout) throws InterruptedException {
        long startTime = System.nanoTime();
        ensureOnThread();
        if (state.isInoperable() || timeout == 0) return 0;
        CycleState lastState = state;
        if (!state.isPausing()) state = CycleState.SLEEPING;
        try {
            return _sleep(startTime, timeout);
        } finally {
            if (state.isSleeping()) state = lastState;
        }
    }

    protected void wakeup() {
        ensureOffThread();
        if (!state.isInactive()) return;
        LockSupport.unpark(thread);
    }

    private void _run() {
        state = CycleState.STARTING;
        try {
            onStartup();
        } catch (Throwable t) {
            lifecycleException(t);
            return;
        }

        state = CycleState.RUNNING;
        while (state.isAlive()) {
            long startTime = System.nanoTime();

            if (state.isShutting()) break;

            switch (state) {
                case PAUSING:
                    try {
                        onPause();
                    } catch (Throwable t) {
                        lifecycleException(t);
                    }
                    if (state != CycleState.PAUSING) break;
                    state = CycleState.PAUSED;
                    break;
                case RESUMING:
                    try {
                        onResume();
                    } catch (Throwable t) {
                        lifecycleException(t);
                    }
                    if (state != CycleState.RESUMING) break;
                    state = CycleState.RUNNING;
                    break;
                case RUNNING:
                    try {
                        onExecute();
                    } catch (Throwable t) {
                        lifecycleException(t);
                    }

                    if (state != CycleState.RUNNING) break;

                    state = CycleState.IDLE;

                    try {
                        onIdle();
                    } catch (Throwable e) {
                        lifecycleException(e);
                    }

                    try {
                        _sleep(startTime, interval);
                    } catch (InterruptedException _) {
                    }

                    if (state.isIdle()) state = CycleState.RUNNING;

                    break;
                case PAUSED:
                    LockSupport.parkNanos(Long.MAX_VALUE);
                    break;
            }
        }

        if (state == CycleState.SHUTTING) {
            try {
                onShutdown();
                state = CycleState.SHUTDOWN;
            } catch (Throwable t) {
                lifecycleException(t);
            }
        }
    }

    protected abstract void onStartup() throws Exception;

    protected abstract void onExecute() throws Exception;

    protected abstract void onPause() throws Exception;

    protected abstract void onResume() throws Exception;

    protected abstract void onShutdown() throws Exception;

    protected abstract void onIdle() throws Exception;

    protected abstract void onException(@NotNull Throwable throwable);

    /**
     * Asserts that the current execution is occurring on the application's target thread.
     *
     * <p>This check utilizes Java's language-level assertion mechanism and is only active if
     * assertions are enabled via the {@code -ea} JVM option.
     *
     * @see Threads#assertOnThread(Thread)
     */
    protected void assertOnThread() {
        assert thread != null;
        Threads.assertOnThread(thread);
    }

    /**
     * Asserts that the current execution is NOT occurring on the application's target thread.
     *
     * <p>This check utilizes Java's language-level assertion mechanism and is only active if
     * assertions are enabled via the {@code -ea} JVM option.
     *
     * @see Threads#assertOffThread(Thread)
     */
    protected void assertOffThread() {
        assert thread != null;
        Threads.assertOffThread(thread);
    }

    /**
     * Ensures that the current execution is occurring on the application's target thread.
     *
     * <p>Unlike assertions, this verification is always active in production environments.
     *
     * @throws WrongThreadException if the current thread is not the target thread
     * @see Threads#ensureOnThread(Thread)
     */
    protected void ensureOnThread() {
        assert thread != null;
        Threads.ensureOnThread(thread);
    }

    /**
     * Ensures that the current execution is NOT occurring on the application's target thread.
     *
     * <p>Unlike assertions, this verification is always active in production environments.
     *
     * @throws WrongThreadException if the current thread is the target thread
     * @see Threads#ensureOffThread(Thread)
     */
    protected void ensureOffThread() {
        assert thread != null;
        Threads.ensureOffThread(thread);
    }

    private void lifecycleException(@NotNull Throwable e) {
        try {
            onException(e);
        } catch (Exception userEx) {
            getLogger().error("The onException handler itself threw an exception.", userEx);
        }

        if (state == CycleState.SHUTTING) {
            getLogger()
                    .error(
                            "Failed to complete [onShutdown]. The application will force close to avoid an infinite error loop.",
                            e);
        } else if (state == CycleState.STARTING) {
            getLogger()
                    .error(
                            "The application failed during [onStartup]. Startup process has been cancelled.",
                            e);
        } else {
            String action =
                    "on"
                            + switch (state) {
                                case RUNNING -> "Execute";
                                case PAUSING -> "Pause";
                                case RESUMING -> "Resume";
                                case IDLE -> "Idle";
                                default ->
                                        throw new IllegalStateException(
                                                "Unexpected lifecycle state: " + state);
                            };

            getLogger()
                    .error(
                            "An unrecoverable error occurred during [{}]. Closing the application...",
                            action,
                            e);
            try {
                onShutdown();
            } catch (Exception shutdownEx) {
                getLogger()
                        .error(
                                "A follow-up error occurred while trying to close the application during [{}].",
                                action,
                                shutdownEx);
            }
        }

        state = CycleState.FAILED;
    }

    private long _sleep(long startTime, long timeout) throws InterruptedException {
        long timeoutElapsedTime = System.nanoTime() - startTime;
        long timeoutRemainingTime = timeout - timeoutElapsedTime;

        if (timeoutRemainingTime <= 0) return 0;

        LockSupport.parkNanos(timeoutRemainingTime);

        if (Thread.interrupted())
            throw new InterruptedException("Interrupted while waiting for " + timeout + "ns");

        long elapsedTime = System.nanoTime() - startTime;
        long remaining = timeout - elapsedTime;
        return (remaining <= 0) ? 0 : remaining;
    }

    private @NotNull Thread.UncaughtExceptionHandler createUncaughtExceptionHandler() {
        return (t, e) -> {
            logger.error("Internal fatal error. The application is stopping...", e);
            state = CycleState.FAILED;
        };
    }

    private static @NotNull String formatThreadName(@NotNull String name) {
        return name.toLowerCase() + "-main";
    }

    private static long validateInterval(long interval) {
        if (interval >= 0) return interval;
        throw new IllegalArgumentException("Interval must not be negative: " + interval);
    }
}
