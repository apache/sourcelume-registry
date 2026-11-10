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

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Authoritative record access. A validated record and a record's bound type are immutable. */
public interface RecordStore {
    enum WriteGuarantee {
        CHECK_THEN_WRITE,
        ATOMIC_CONDITIONAL
    }

    StoreLimits limits();

    WriteGuarantee writeGuarantee();

    Optional<StoredRecord> get(String id);

    /**
     * Authoritative reads keyed by record ID. Missing records are omitted and duplicate
     * IDs are read once. Null or blank IDs are rejected before any backend access.
     * An empty request returns an empty map without backend access. A failed read throws
     * rather than returning partial results; it must not look like a missing record.
     * Each record is a complete snapshot, but the batch need not share one database snapshot.
     * Implementations may batch the default point reads; an eventually consistent search
     * index is not a substitute. The returned map is immutable; order is unspecified.
     */
    default Map<String, StoredRecord> getAll(Collection<String> ids) {
        var distinct = new LinkedHashSet<>(Objects.requireNonNull(ids, "ids"));
        for (String id : distinct) {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("Empty record id");
        }
        Map<String, StoredRecord> found = new LinkedHashMap<>();
        for (String id : distinct) get(id).ifPresent(record -> found.put(id, record));
        return Map.copyOf(found);
    }

    WriteResult write(WriteRequest request);
}
