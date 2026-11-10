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

import java.util.Objects;

/** Bounded search of the current index. Results may lag point reads; not an authoritative scan. */
public record SearchQuery(String type, RecordStatus status, int limit, RecordFacets facets) {
    public SearchQuery {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(facets, "facets");
        if (type.isBlank() || limit < 1 || limit > 1000)
            throw new IllegalArgumentException("Invalid search type or limit");
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String type;
        private RecordStatus status;
        private int limit = 100;
        private RecordFacets facets = RecordFacets.builder().build();

        private Builder() {}

        public Builder type(String value) {
            this.type = value;
            return this;
        }

        public Builder status(RecordStatus value) {
            this.status = value;
            return this;
        }

        public Builder limit(int value) {
            this.limit = value;
            return this;
        }

        public Builder facets(RecordFacets value) {
            this.facets = value;
            return this;
        }

        public SearchQuery build() {
            return new SearchQuery(type, status, limit, facets);
        }
    }
}
