/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sourcelume.registry.spi;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Exact, case-sensitive values. Search uses OR within each facet and AND across facets. */
public record RecordFacets(Map<RecordFacet, Set<String>> values) {
    public RecordFacets {
        var copy = new EnumMap<RecordFacet, Set<String>>(RecordFacet.class);
        values.forEach((facet, entries) -> {
            Objects.requireNonNull(facet, "facet");
            var unique = new TreeSet<String>();
            for (String value : entries) {
                if (value == null
                        || value.isBlank()
                        || !java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(value))
                    throw new IllegalArgumentException("Facet values must be nonblank Unicode text");
                unique.add(value);
            }
            if (unique.isEmpty()) throw new IllegalArgumentException("A facet must contain at least one value");
            copy.put(facet, Collections.unmodifiableSet(unique));
        });
        values = Collections.unmodifiableMap(copy);
    }

    public Set<String> values(RecordFacet facet) {
        return values.getOrDefault(Objects.requireNonNull(facet), Set.of());
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    /** Whether this snapshot satisfies every filter, independent of search-index freshness. */
    public boolean matches(RecordFacets filter) {
        for (var entry : filter.values.entrySet()) {
            if (Collections.disjoint(values(entry.getKey()), entry.getValue())) return false;
        }
        return true;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Map<RecordFacet, Set<String>> values = new EnumMap<>(RecordFacet.class);

        private Builder() {}

        public Builder add(RecordFacet facet, String value) {
            Objects.requireNonNull(facet, "facet");
            if (value == null
                    || value.isBlank()
                    || !java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(value))
                throw new IllegalArgumentException("Facet values must be nonblank Unicode text");
            values.computeIfAbsent(facet, ignored -> new TreeSet<>()).add(value);
            return this;
        }

        public RecordFacets build() {
            return new RecordFacets(values);
        }
    }
}
