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

import java.util.concurrent.locks.LockSupport;
import org.jetbrains.annotations.NotNull;

/**
 * Utility class providing synchronous thread validation and precise scheduling mechanisms.
 *
 * <p>This class cannot be instantiated and offers static methods to assert or verify whether the
 * current execution flow matches specific target threads, as well as high-precision thread parking
 * utilities.
 */
public final class Threads {

    private Threads() {}

    /**
     * Disables the current thread for thread scheduling purposes for up to the remaining timeout
     * duration.
     *
     * <p>This method calculates the elapsed time since {@code startTime} and parks the thread using
     * {@link LockSupport#parkNanos(long)}. If the timeout has already expired, it returns
     * immediately.
     *
     * @param startTime the base JVM high-resolution time source in nanoseconds (e.g., from {@link
     *     System#nanoTime()})
     * @param timeout the total duration to wait in nanoseconds
     * @return the remaining timeout nanoseconds if unparked early, or {@code 0} if the timeout
     *     expired
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public static long park(long startTime, long timeout) throws InterruptedException {
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

    /**
     * Makes available the permit for the given thread, if it was not already available.
     *
     * <p>If the thread was blocked on {@link LockSupport#park() park} (or a variant like our {@link
     * #park(long, long)}), it will unblock. If it was not blocked, its next call to a parking
     * method is guaranteed not to block.
     *
     * @param thread the thread to unpark; must not be null
     */
    public static void unpark(@NotNull Thread thread) {
        LockSupport.unpark(thread);
    }

    /**
     * Asserts that the current execution is occurring on the specified thread.
     *
     * <p>This method utilizes Java's language-level assertion mechanism. It will only validate the
     * thread and throw an {@link AssertionError} if assertions are explicitly enabled in the JVM
     * via the {@code -ea} option.
     *
     * @param thread the expected thread that must execute this method
     * @throws AssertionError if assertions are enabled and the current thread is not the specified
     *     thread
     */
    public static void assertOnThread(@NotNull Thread thread) {
        assert isOnThread(thread) : getOnThreadThrowMessage(thread, Thread.currentThread());
    }

    /**
     * Asserts that the current execution is NOT occurring on the specified thread.
     *
     * <p>This method utilizes Java's language-level assertion mechanism. It will only validate the
     * thread and throw an {@link AssertionError} if assertions are explicitly enabled in the JVM
     * via the {@code -ea} option.
     *
     * @param thread the prohibited thread that must not execute this method
     * @throws AssertionError if assertions are enabled and the current thread is the specified
     *     thread
     */
    public static void assertOffThread(@NotNull Thread thread) {
        assert isOffThread(thread) : getOffThreadThrowMessage(thread);
    }

    /**
     * Ensures that the current execution is occurring on the specified thread.
     *
     * <p>Unlike the assertion variant, this method always performs the validation regardless of JVM
     * configuration flags and unconditionally throws a runtime exception if the check fails.
     *
     * @param thread the expected thread that must execute this method
     * @throws WrongThreadException if the current thread is not the specified thread
     */
    public static void ensureOnThread(@NotNull Thread thread) {
        if (isOnThread(thread)) return;
        throw new WrongThreadException(getOnThreadThrowMessage(thread, Thread.currentThread()));
    }

    /**
     * Ensures that the current execution is NOT occurring on the specified thread.
     *
     * <p>Unlike the assertion variant, this method always performs the validation regardless of JVM
     * configuration flags and unconditionally throws a runtime exception if the check fails.
     *
     * @param thread the prohibited thread that must not execute this method
     * @throws WrongThreadException if the current thread is the specified thread
     */
    public static void ensureOffThread(@NotNull Thread thread) {
        if (isOffThread(thread)) return;
        throw new WrongThreadException(getOffThreadThrowMessage(thread));
    }

    /**
     * Checks if the current thread matches the specified thread.
     *
     * @param thread the thread to compare against the current thread
     * @return {@code true} if the current thread is the specified thread, {@code false} otherwise
     */
    public static boolean isOnThread(@NotNull Thread thread) {
        return Thread.currentThread() == thread;
    }

    /**
     * Checks if the current thread differs from the specified thread.
     *
     * @param thread the thread to compare against the current thread
     * @return {@code true} if the current thread is not the specified thread, {@code false}
     *     otherwise
     */
    public static boolean isOffThread(@NotNull Thread thread) {
        return Thread.currentThread() != thread;
    }

    private static @NotNull String getOnThreadThrowMessage(
            @NotNull Thread expected, @NotNull Thread current) {
        return "Invalid thread access: method must be called on thread '"
                + expected.getName()
                + "' [id="
                + expected.threadId()
                + "] but was executed on thread '"
                + current.getName()
                + "' [id="
                + current.threadId()
                + "]";
    }

    private static @NotNull String getOffThreadThrowMessage(@NotNull Thread thread) {
        return "Invalid thread access: method must not be called on thread '"
                + thread.getName()
                + "' [id="
                + thread.threadId()
                + "]";
    }
}
