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

/**
 * Represents the lifecycle states of a process or execution cycle.
 *
 * <p>This enum defines the specific behavioral characteristics of each state, tracking whether the
 * cycle is currently alive, operable, or actively processing.
 */
public enum CycleState {

    /**
     * The initial state before the cycle has officially started. The process is created but not yet
     * active or ready for operation.
     */
    INITIALIZED(false, false, false),

    /**
     * The transient state indicating the cycle is currently booting up. The process is alive,
     * operable, and actively transitioning.
     */
    STARTING(true, true, true),

    /**
     * The main execution state where the cycle is fully functional. The process is alive, operable,
     * and actively executing tasks.
     */
    RUNNING(true, true, true),

    /**
     * The transient state indicating the cycle is transitioning back from a paused state. The
     * process is alive, operable, and actively recovering.
     */
    RESUMING(true, true, true),

    /**
     * The transient state indicating the cycle is in the middle of suspending operations. The
     * process remains alive and operable while actively shutting down current work.
     */
    PAUSING(true, true, true),

    /**
     * The state indicating execution is temporarily suspended. The process is alive and operable,
     * but no active work is being executed.
     */
    PAUSED(true, true, false),

    /**
     * The state indicating the cycle is suspended in a low-power or dormant mode. The process is
     * alive and operable, but currently inactive.
     */
    SLEEPING(true, true, false),

    /**
     * The state indicating the cycle is ready for work but waiting for a trigger or workload. The
     * process is alive and operable, but currently inactive.
     */
    IDLE(true, true, false),

    /**
     * The transient state indicating the cycle is in the process of terminating. The process
     * remains alive and active to perform cleanup, but it is no longer operable.
     */
    SHUTTING(true, false, true),

    /**
     * The final state indicating successful, graceful termination. The process is no longer alive,
     * operable, or active.
     */
    SHUTDOWN(false, false, false),

    /**
     * The final state indicating an unrecoverable failure or crash. The process terminated abruptly
     * and is no longer alive, operable, or active.
     */
    FAILED(false, false, false);

    private final boolean alive;
    private final boolean operable;
    private final boolean active;

    CycleState(boolean alive, boolean operable, boolean active) {
        this.alive = alive;
        this.operable = operable;
        this.active = active;
    }

    /**
     * Checks if the cycle is alive.
     *
     * @return {@code true} if the state is not initialized, shutdown, or failed; {@code false}
     *     otherwise.
     */
    public boolean isAlive() {
        return alive;
    }

    /**
     * Checks if the cycle can accept or execute operations.
     *
     * @return {@code true} if the state is operable; {@code false} otherwise.
     */
    public boolean isOperable() {
        return operable;
    }

    /**
     * Checks if the cycle cannot accept or execute operations.
     *
     * @return {@code true} if the state is inoperable; {@code false} otherwise.
     */
    public boolean isInoperable() {
        return !operable;
    }

    /**
     * Checks if the cycle is actively running or transitioning.
     *
     * @return {@code true} if the state is active; {@code false} otherwise.
     */
    public boolean isActive() {
        return active;
    }

    /**
     * Checks if the cycle is resting, paused, or terminated.
     *
     * @return {@code true} if the state is inactive; {@code false} otherwise.
     */
    public boolean isInactive() {
        return !active;
    }

    /**
     * Helper to verify if the current state is {@link #INITIALIZED}.
     *
     * @return {@code true} if initialized; {@code false} otherwise.
     */
    public boolean isInitialized() {
        return this == INITIALIZED;
    }

    /**
     * Helper to verify if the current state is {@link #STARTING}.
     *
     * @return {@code true} if starting; {@code false} otherwise.
     */
    public boolean isStarting() {
        return this == STARTING;
    }

    /**
     * Helper to verify if the current state is {@link #RUNNING}.
     *
     * @return {@code true} if running; {@code false} otherwise.
     */
    public boolean isRunning() {
        return this == RUNNING;
    }

    /**
     * Helper to verify if the current state is {@link #RESUMING}.
     *
     * @return {@code true} if resuming; {@code false} otherwise.
     */
    public boolean isResuming() {
        return this == RESUMING;
    }

    /**
     * Helper to verify if the current state is {@link #PAUSING}.
     *
     * @return {@code true} if pausing; {@code false} otherwise.
     */
    public boolean isPausing() {
        return this == PAUSING;
    }

    /**
     * Helper to verify if the current state is {@link #PAUSED}.
     *
     * @return {@code true} if paused; {@code false} otherwise.
     */
    public boolean isPaused() {
        return this == PAUSED;
    }

    /**
     * Helper to verify if the current state is {@link #SLEEPING}.
     *
     * @return {@code true} if sleeping; {@code false} otherwise.
     */
    public boolean isSleeping() {
        return this == SLEEPING;
    }

    /**
     * Helper to verify if the current state is {@link #IDLE}.
     *
     * @return {@code true} if idle; {@code false} otherwise.
     */
    public boolean isIdle() {
        return this == IDLE;
    }

    /**
     * Helper to verify if the current state is {@link #SHUTTING}.
     *
     * @return {@code true} if shutting down; {@code false} otherwise.
     */
    public boolean isShutting() {
        return this == SHUTTING;
    }

    /**
     * Helper to verify if the current state is {@link #SHUTDOWN}.
     *
     * @return {@code true} if shutdown; {@code false} otherwise.
     */
    public boolean isShutdown() {
        return this == SHUTDOWN;
    }

    /**
     * Helper to verify if the current state is {@link #FAILED}.
     *
     * @return {@code true} if failed; {@code false} otherwise.
     */
    public boolean isFailed() {
        return this == FAILED;
    }
}
