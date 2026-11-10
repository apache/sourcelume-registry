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
package org.apache.sourcelume.registry.spi.tck;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.sourcelume.registry.spi.*;

/** Independent reference implementation for tests only; never a production backend. */
public final class MemoryPlugin implements RegistryPlugin, RecordStore, RecordSearch {
    private final Map<String, StoredRecord> records = new HashMap<>();
    private boolean closed;

    public RecordStore recordStore() {
        return this;
    }

    public RecordSearch recordSearch() {
        return this;
    }

    public void initialize() {
        checkOpen();
    }

    public synchronized boolean isReady() {
        return !closed;
    }

    public StoreLimits limits() {
        return StoreLimits.builder().build();
    }

    public WriteGuarantee writeGuarantee() {
        return WriteGuarantee.ATOMIC_CONDITIONAL;
    }

    public synchronized Optional<StoredRecord> get(String id) {
        checkOpen();
        return Optional.ofNullable(records.get(id));
    }

    public synchronized WriteResult write(WriteRequest request) {
        checkOpen();
        limits().check(request.candidate());
        StoredRecord current = records.get(request.candidate().id());
        if (current == null && request.condition() instanceof WriteCondition.AtRevision)
            throw new RecordConflictException(RecordConflictException.Reason.NOT_FOUND, null);
        if (current != null) {
            if (!current.record().type().equals(request.candidate().type()))
                throw new RecordConflictException(RecordConflictException.Reason.TYPE_MISMATCH, current);
            if (current.record().validation() != null
                    && current.record().validation().status() == RecordStatus.VALIDATED)
                throw new RecordConflictException(RecordConflictException.Reason.IMMUTABLE, current);
            if (request.condition() instanceof WriteCondition.Absent)
                throw new RecordConflictException(RecordConflictException.Reason.ALREADY_EXISTS, current);
            if (!current.revision().equals(((WriteCondition.AtRevision) request.condition()).revision()))
                throw new RecordConflictException(RecordConflictException.Reason.STALE_REVISION, current);
        }
        StoredRecord written = StoredRecord.builder()
                .record(request.candidate())
                .revision(UUID.randomUUID().toString())
                .build();
        records.put(written.record().id(), written);
        return WriteResult.builder()
                .stored(written)
                .disposition(current == null ? WriteResult.Disposition.CREATED : WriteResult.Disposition.REPLACED)
                .build();
    }

    public synchronized List<StoredRecord> search(SearchQuery query) {
        checkOpen();
        return records.values().stream()
                .filter(v -> v.record().type().equals(query.type())
                        && v.record().validation() != null
                        && v.record().validation().status() == query.status()
                        && v.record().facets().matches(query.facets()))
                .limit(query.limit())
                .toList();
    }

    public synchronized void close() {
        closed = true;
    }

    private void checkOpen() {
        if (closed) throw new StorageException(StorageException.Code.UNAVAILABLE, "Closed");
    }
}
