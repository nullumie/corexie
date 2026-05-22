/*
 * SPDX-License-Identifier: MIT
 *
 * Copyright (c) 2026 Nullumie
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.github.nullumie.corexie;

import com.github.zafarkhaja.semver.Version;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.NotNull;

public final class Example extends Application {

    private int count = 0;

    Example() {
        super("Example", Version.of(0, 1, 0, "SNAPSHOT"), TimeUnit.MILLISECONDS.toNanos(1000));
    }

    @Override
    protected void onStartup() throws Exception {
        getLogger().info("STARTUP");
        printInfo();
    }

    @Override
    protected void onExecute() throws Exception {
        getLogger().info("EXECUTE #{}", count);

        int maxCount = 9;
        if (count == maxCount) shutdown();

        if (count == 2) {
            setInterval(TimeUnit.MILLISECONDS.toNanos(500));
        }

        if (count == 4) {
            pause();
        }

        if (count == 6) {
            setInterval(TimeUnit.MILLISECONDS.toNanos(1500));
        }

        count++;
    }

    @Override
    protected void onPause() throws Exception {
        getLogger().info("PAUSE");
        resume();
    }

    @Override
    protected void onResume() throws Exception {
        getLogger().info("RESUME");
    }

    @Override
    protected void onShutdown() throws Exception {
        getLogger().info("SHUTDOWN");
    }

    @Override
    protected void onIdle() throws Exception {
        getLogger().info("IDLE");
    }

    @Override
    protected void onException(@NotNull Throwable throwable) {
        getLogger().info("EXCEPTION: {}", throwable.getMessage());
    }

    private void printInfo() {
        getLogger().info("------------------------------");
        getLogger().info("Name: {}", getName());
        getLogger().info("Version: {}", getVersion());
        getLogger().info("LogPath: {}", Corexie.getLogPath());
        getLogger().info("LogMode: {}", Corexie.getLogMode().toString().toLowerCase(Locale.ROOT));
        getLogger().info("------------------------------");
    }

    static void main(String[] args) {
        Corexie.initialize("logs", LogMode.FILE);
        Example example = new Example();
        example.run();
        Corexie.shutdown();
    }
}
