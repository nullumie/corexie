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
package io.github.nullumie.corexie.json.module;

import com.github.zafarkhaja.semver.ParseException;
import com.github.zafarkhaja.semver.Version;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.module.SimpleModule;

/**
 * A Jackson module that provides serialization and deserialization support for Semantic Versioning
 * {@link Version} objects.
 *
 * <p>This class integrates into the library's JSON processing layer to automatically handle
 * conversion between {@link Version} data structures and standard SemVer string representations.
 *
 * @see SimpleModule
 * @see Version
 */
public final class SemVerModule extends SimpleModule {

    private static class Serializer extends ValueSerializer<Version> {
        @Override
        public void serialize(Version value, JsonGenerator gen, SerializationContext ctxt)
                throws JacksonException {
            Objects.requireNonNull(value, "Version value to serialize cannot be null");
            gen.writeString(value.toString());
        }
    }

    private static class Deserializer extends ValueDeserializer<Version> {
        @Override
        public Version deserialize(JsonParser p, DeserializationContext ctxt)
                throws JacksonException {
            String versionText = p.getString();

            if (versionText == null || versionText.isBlank()) {
                throw MismatchedInputException.from(
                        p, Version.class, "SemVer string cannot be null or blank");
            }

            try {
                return Version.parse(versionText);
            } catch (ParseException e) {
                throw ctxt.instantiationException(Version.class, e);
            }
        }
    }

    /**
     * Constructs a new {@code SemVerModule} and registers the {@link Version} serializer and
     * deserializer components.
     *
     * <p>The module is initialized with the identifier {@code "SemVerModule"}.
     */
    public SemVerModule() {
        super("SemVerModule");
        this.addSerializer(Version.class, new Serializer());
        this.addDeserializer(Version.class, new Deserializer());
    }
}
