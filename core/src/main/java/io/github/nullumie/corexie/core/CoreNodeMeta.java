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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.zafarkhaja.semver.Version;
import org.jetbrains.annotations.NotNull;

public class CoreNodeMeta extends CoreMeta {

    private final long interval;

    @JsonCreator
    public CoreNodeMeta(
            @JsonProperty("name") @NotNull String name,
            @JsonProperty("version") @NotNull Version version,
            @JsonProperty("interval") long interval) {
        super(name, version);
        this.interval = interval;
    }

    public long getInterval() {
        return interval;
    }
}
