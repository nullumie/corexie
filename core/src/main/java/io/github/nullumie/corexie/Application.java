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

/**
 * An abstract base class that encapsulates a high-performance, single-threaded lifecycle loop for
 * building manageable services or applications.
 *
 * <p>The {@code Application} framework provides a structured state machine supporting startup,
 * sequential execution cycles, real-time loop interval throttling, thread parking/idling, pausing,
 * resuming, and graceful shutdown phases. It isolates business logic into well-defined hook methods
 * while abstracting away concurrency assertions, global application tracking, and automated
 * fallback error handling.
 *
 * <h2>Lifecycle Transitions</h2>
 *
 * The internal state transitions through multiple phases governed by {@link CycleState}:
 *
 * <ul>
 *   <li>{@code INITIALIZED} - Set when the application instance is constructed.
 *   <li>{@code STARTING} - Set during initialization while executing {@link #onStartup()}.
 *   <li>{@code RUNNING} - The standard active phase while executing {@link #onExecute()}.
 *   <li>{@code IDLE} - A temporary phase executed right before the thread is parked/throttled.
 *   <li>{@code SLEEPING} - Active when a timed park is manually invoked via {@link #sleep(long)}.
 *   <li>{@code PAUSING} / {@code PAUSED} - Entered via {@link #pause()} to suspend loop processing.
 *   <li>{@code RESUMING} - Entered via {@link #resume()} to return to active processing.
 *   <li>{@code SHUTTING} / {@code SHUTDOWN} - Entered via {@link #shutdown()} for graceful resource
 *       cleanup.
 *   <li>{@code FAILED} - The terminal state reached if any unhandled lifecycle exception occurs.
 * </ul>
 *
 * <h2>Concurrency Model</h2>
 *
 * An application can be executed asynchronously in a managed daemon thread via {@link #start()} or
 * synchronously blocking the caller thread via {@link #run()}. This class includes rigorous
 * calling-context checks (e.g., {@link #ensureOnThread()} and {@link #ensureOffThread()}) to detect
 * illegal multithreaded interactions early in development.
 */
public abstract class Application {

    private final @NotNull String name;
    private final @NotNull Version version;
    private @Nullable Thread thread;
    private final @NotNull Logger logger;
    private final @NotNull Thread.UncaughtExceptionHandler uncaughtExceptionHandler;
    private volatile long interval;

    private volatile @NotNull CycleState state = CycleState.INITIALIZED;

    /**
     * Constructs a new {@code Application} instance with a dedicated identity, version, and cycle
     * throttling interval.
     *
     * @param name the unique identifier name of this application, used for logging and thread
     *     naming
     * @param version the semantic version of this application instance
     * @param interval the cycle execution interval/throttling delay in nanoseconds
     * @throws IllegalArgumentException if the provided interval is negative
     */
    protected Application(@NotNull String name, @NotNull Version version, long interval) {
        this.name = name;
        this.version = version;
        this.logger = LoggerFactory.getLogger(this.name);
        this.uncaughtExceptionHandler = createUncaughtExceptionHandler();
        this.interval = validateInterval(interval);
    }

    /**
     * Retrieves the unique identifier name of this application.
     *
     * @return the non-null {@link String} representing the application name
     */
    public @NotNull String getName() {
        return name;
    }

    /**
     * Retrieves the current semantic version of this application.
     *
     * @return the non-null {@link Version} specifying the application version
     */
    public @NotNull Version getVersion() {
        return version;
    }

    /**
     * Retrieves the primary logging instance assigned to this application.
     *
     * @return the non-null {@link Logger} configured for this application context
     */
    public @NotNull Logger getLogger() {
        return logger;
    }

    /**
     * Retrieves the current execution lifecycle state of the application.
     *
     * @return the non-null {@link CycleState} representing the active lifecycle phase
     */
    public @NotNull CycleState getState() {
        return state;
    }

    /**
     * Retrieves the current execution cycle interval of the application in nanoseconds.
     *
     * @return the current cycle interval in nanoseconds
     */
    public long getInterval() {
        return interval;
    }

    /**
     * Updates the application's execution cycle interval.
     *
     * <p>The new interval value is validated before it is applied. If the new value matches the
     * existing interval, no changes are made.
     *
     * <p>If the application is currently idling (off-thread) when the interval changes, the
     * execution thread is awakened immediately to apply the new configuration.
     *
     * @param interval the new cycle interval in nanoseconds
     * @return the previous interval value in nanoseconds
     * @throws IllegalArgumentException if the provided interval is invalid
     */
    public long setInterval(long interval) {
        validateInterval(interval);
        if (interval == this.interval) return this.interval;
        long oldInterval = this.interval;
        this.interval = interval;
        if (isOffThread()) wakeup();
        return oldInterval;
    }

    /**
     * Initiates a graceful shutdown sequence for the application.
     *
     * <p>If the application is already in an inoperable state (such as already closed or failed),
     * this call returns immediately without taking any action.
     *
     * <p>When executed, this method transitions the lifecycle state to {@code SHUTTING} and sends
     * an interrupt signal to the active execution thread. This breaks the thread out of any parking
     * or idling routines, allowing it to proceed immediately to the {@link #onShutdown()} hook and
     * clean up resources.
     */
    public void shutdown() {
        if (state.isInoperable()) return;
        state = CycleState.SHUTTING;
        assert thread != null;
        thread.interrupt();
    }

    /**
     * Resumes the application from a paused or pausing state.
     *
     * <p>If the application is not currently in the {@code PAUSING} or {@code PAUSED} state, this
     * call returns immediately without taking any action.
     *
     * <p>When executed, this method transitions the lifecycle state to {@code RESUMING}. If called
     * from an external thread while the execution thread is parked or idling, it will automatically
     * trigger a wakeup signal to resume active processing immediately.
     */
    public void resume() {
        if (!state.isPausing() && !state.isPaused()) return;
        state = CycleState.RESUMING;
        if (isOffThread()) wakeup();
    }

    /**
     * Initiates a pause sequence for the application.
     *
     * <p>If the application is already inoperable, pausing, or fully paused, this call returns
     * immediately without taking any action.
     *
     * <p>When executed, this method transitions the lifecycle state to {@code PAUSING}. If called
     * from an external thread while the execution thread is parked or idling, it will automatically
     * trigger a wakeup signal to force the application thread to process the pause state transition
     * immediately.
     */
    public void pause() {
        if (state.isInoperable() || state.isPausing() || state.isPaused()) return;
        state = CycleState.PAUSING;
        if (isOffThread()) wakeup();
    }

    /**
     * Waits for the application's execution thread to terminate.
     *
     * <p>This method blocks the calling thread until the dedicated application thread finishes its
     * execution cycle and shuts down completely.
     *
     * <p>To prevent deadlocks, this call returns immediately without taking action if the
     * application thread has not been started (is {@code null}) or if the method is called from
     * within the application thread itself.
     *
     * @throws InterruptedException if the calling thread is interrupted while waiting for the
     *     application thread to finish
     */
    public void join() throws InterruptedException {
        if (thread == null || isOnThread()) return;
        thread.join();
    }

    /**
     * Starts the application asynchronously in a new dedicated thread.
     *
     * <p>If the application is already running or active, this call returns immediately without
     * taking any action.
     *
     * <p>When executed, this method provisions a separate thread to drive the core lifecycle loop.
     * The spawned thread executes the exact same underlying hook sequence as {@link #run()}:
     *
     * <ul>
     *   <li><b>Startup Phase:</b> Transitions to {@code STARTING} and fires the {@code onStartup()}
     *       hook.
     *   <li><b>Main Loop Phase:</b> Transitions to {@code RUNNING} and continuously processes
     *       execution, pause, resume, and idle hooks while throttling via the configured interval.
     *   <li><b>Shutdown Phase:</b> Upon a termination signal, executes the {@code onShutdown()}
     *       hook and sets the final state to {@code SHUTDOWN}.
     * </ul>
     *
     * <p>The executing thread also handles custom exception management, thread naming, global
     * application registry tracking, and guarantees resource cleanup upon exit.
     */
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

    /**
     * Executes the application's lifecycle orchestration on the current thread.
     *
     * <p>If the application is already running or active, this call returns immediately without
     * taking action.
     *
     * <p>When executed, this method sets up the environment, runs the state machine loop, and
     * guarantees final cleanup. The execution sequence flows as follows:
     *
     * <ul>
     *   <li><b>Setup:</b> Registers the application globally, captures the active thread context,
     *       and configures custom exception handling and thread naming.
     *   <li><b>Startup Phase:</b> Transitions to {@code STARTING} and fires the {@code onStartup()}
     *       hook.
     *   <li><b>Main Loop Phase:</b> Transitions to {@code RUNNING}. Continuously processes
     *       execution, pause, resume, and idle hooks while updating the active {@code CycleState}.
     *       Throttles loops using the configured interval.
     *   <li><b>Shutdown Phase:</b> Upon receiving a termination signal, executes the {@code
     *       onShutdown()} hook and sets the final state to {@code SHUTDOWN}.
     *   <li><b>Teardown:</b> Restores the original thread name, removes exception handlers, clears
     *       thread references, and unregisters the application.
     * </ul>
     */
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

    /**
     * Places the application thread into a timed parking state.
     *
     * <p>This method manages the temporary transition of the application's lifecycle state to
     * {@code SLEEPING} while the thread is parked. It ensures that the state is safely restored to
     * its previous phase upon awakening.
     *
     * <p>The sleep operation is skipped entirely if the application is in an inoperable state or if
     * the requested timeout duration is zero.
     *
     * @param timeout the maximum duration to park the thread in nanoseconds
     * @return the remaining unspent timeout duration in nanoseconds if awakened early, or {@code 0}
     *     if the full timeout elapsed or the sleep was skipped
     * @throws InterruptedException if the thread is interrupted while waiting
     * @throws WrongThreadException if called from an external thread context
     */
    protected long sleep(long timeout) throws InterruptedException {
        long startTime = System.nanoTime();
        ensureOnThread();
        if (state.isInoperable() || timeout == 0) return 0;
        CycleState lastState = state;
        if (!state.isPausing()) state = CycleState.SLEEPING;
        try {
            return Threads.park(startTime, timeout);
        } finally {
            if (state.isSleeping()) state = lastState;
        }
    }

    /**
     * Signals and wakes up the application execution thread if it is currently parked or idle.
     *
     * <p>This method can only be legally invoked from an external thread context. It evaluates
     * whether the application is in an inactive phase, and resets its parking state via {@link
     * Threads#unpark(Thread)}.
     *
     * @throws WrongThreadException if called from within the internal application thread context
     */
    protected void wakeup() {
        ensureOffThread();
        if (!state.isInactive()) return;
        assert thread != null;
        Threads.unpark(thread);
    }

    /**
     * Invoked exactly once when the application starts up.
     *
     * <p>This hook is called before the main execution loop begins, immediately after the lifecycle
     * state transitions to {@code STARTING}. Use this method to allocate resources, open
     * connections, or perform initialization logic.
     *
     * @throws Exception if initialization fails, which aborts startup and routes the error through
     *     the lifecycle exception handling mechanism
     */
    protected abstract void onStartup() throws Exception;

    /**
     * Invoked repeatedly as the core processing logic of the application loop.
     *
     * <p>This hook is called during every cycle iteration while the application state remains
     * {@code RUNNING}. Put your primary runtime work or business logic here.
     *
     * @throws Exception if an error occurs during execution, which routes the error through the
     *     lifecycle exception handling mechanism
     */
    protected abstract void onExecute() throws Exception;

    /**
     * Invoked when the application transitions into a paused state.
     *
     * <p>This hook is called when the state is set to {@code PAUSING}. Use this method to safely
     * suspend active background operations or hold processing until the application is resumed.
     *
     * @throws Exception if the pausing routine fails, which routes the error through the lifecycle
     *     exception handling mechanism
     */
    protected abstract void onPause() throws Exception;

    /**
     * Invoked when the application recovers from a paused state.
     *
     * <p>This hook is called when the state is set to {@code RESUMING}. Use this method to restore
     * operations or reload variables that were suspended during the pause phase.
     *
     * @throws Exception if the resuming routine fails, which routes the error through the lifecycle
     *     exception handling mechanism
     */
    protected abstract void onResume() throws Exception;

    /**
     * Invoked exactly once when the application receives a termination signal.
     *
     * <p>This hook is called when the state changes to {@code SHUTTING}, right before the
     * application loop terminates permanently. Use this method to release resources, flush buffers,
     * close files, or perform graceful teardown.
     *
     * @throws Exception if the cleanup routine fails, which routes the error through the lifecycle
     *     exception handling mechanism
     */
    protected abstract void onShutdown() throws Exception;

    /**
     * Invoked at the end of a running iteration before the application thread idles.
     *
     * <p>This hook is called when the state transitions to {@code IDLE}, occurring right before the
     * thread parks for its configured cycle interval.
     *
     * @throws Exception if the idle routine fails, which routes the error through the lifecycle
     *     exception handling mechanism
     */
    protected abstract void onIdle() throws Exception;

    /**
     * Invoked whenever an unhandled exception is caught during any lifecycle hook execution.
     *
     * <p>This callback acts as an interception point for application-level logging or metrics
     * collection before the lifecycle manager processes the failure. If this handler itself throws
     * an exception, the error is caught and logged to prevent interrupting the framework's recovery
     * routine.
     *
     * <p><b>Note on Lifecycle Impact:</b> Except during startup or shutdown, any exception routed
     * here is treated as unrecoverable. The framework will automatically trigger a fallback to
     * {@link #onShutdown()} and move the final application state to {@code FAILED}.
     *
     * @param throwable the non-null exception or error captured by the lifecycle manager
     */
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

    /**
     * Checks if the current executing thread is the application's dedicated lifecycle thread.
     *
     * <p>This method verifies that the calling context matches the internal thread driving the
     * application's runtime cycle.
     *
     * @return {@code true} if called from the application thread, {@code false} otherwise
     */
    protected boolean isOnThread() {
        assert thread != null;
        return Threads.isOnThread(thread);
    }

    /**
     * Checks if the current executing thread is different from the application's dedicated
     * lifecycle thread.
     *
     * <p>This method verifies that the calling context is external to the internal thread driving
     * the application's runtime cycle.
     *
     * @return {@code true} if called from an external thread, {@code false} otherwise
     */
    protected boolean isOffThread() {
        assert thread != null;
        return Threads.isOffThread(thread);
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
                        Threads.park(startTime, interval);
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
