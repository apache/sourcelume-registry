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
package org.apache.sourcelume.registry.atlas.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.sourcelume.registry.atlas.adapter.exception.AtlasAdapterException;
import org.apache.sourcelume.registry.common.dto.RecordType;
import org.apache.sourcelume.registry.spi.*;

/** Single active writer for one Atlas catalog. The REST API offers no atomic compare-and-set. */
public final class AtlasRegistryPlugin implements RegistryPlugin, RecordStore, RecordSearch {
    private final AtlasAdapter adapter;
    private final AtlasRecordMapper mapper;
    private final String typedefsResource;
    private final StoreLimits limits;
    private final ReentrantLock[] locks = new ReentrantLock[64];
    private final AtomicBoolean uncertainWrite = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    public AtlasRegistryPlugin(AtlasAdapter adapter, ObjectMapper json, String typedefsResource) {
        this.adapter = adapter;
        this.mapper = new AtlasRecordMapper(json);
        this.typedefsResource = typedefsResource;
        this.limits = StoreLimits.builder().build();
        for (int i = 0; i < locks.length; i++) locks[i] = new ReentrantLock();
    }
    /** Used only by the existing Atlas-specific HTTP views. Not part of the storage SPI. */
    public AtlasAdapter legacyAdapter() {
        return adapter;
    }

    @Override
    public RecordStore recordStore() {
        return this;
    }

    @Override
    public RecordSearch recordSearch() {
        return this;
    }

    @Override
    public StoreLimits limits() {
        return limits;
    }

    @Override
    public WriteGuarantee writeGuarantee() {
        return WriteGuarantee.CHECK_THEN_WRITE;
    }

    @Override
    public void initialize() {
        checkOpen();
        try {
            adapter.registerTypeDefsFromResource(typedefsResource);
        } catch (AtlasAdapterException e) {
            throw translate(e);
        }
    }

    @Override
    public boolean isReady() {
        return !closed.get() && !uncertainWrite.get() && adapter.isServerReady();
    }

    @Override
    public Optional<StoredRecord> get(String id) {
        checkOpen();
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Empty record id");
        try {
            var found = adapter.getRecordEntitiesByQualifiedName(RecordType.entityTypeNames(), id);
            if (found.size() > 1)
                throw new StorageException(
                        StorageException.Code.INVALID_DATA, "Record id exists under multiple entity types");
            return found.stream().findFirst().map(mapper::read);
        } catch (AtlasAdapterException e) {
            throw translate(e);
        }
    }

    @Override
    public Map<String, StoredRecord> getAll(Collection<String> ids) {
        checkOpen();
        var distinct = new LinkedHashSet<>(Objects.requireNonNull(ids, "ids"));
        for (String id : distinct) {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("Empty record id");
        }
        if (distinct.isEmpty()) return Map.of();
        List<String> names = List.copyOf(distinct);
        Map<String, StoredRecord> found = new LinkedHashMap<>();
        try {
            // Read each entity type separately so the transport's type precedence cannot
            // hide duplicate identities that an authoritative point read would reject.
            for (String type : RecordType.entityTypeNames()) {
                var entities = adapter.getRecordEntitiesByQualifiedNames(List.of(type), names);
                for (var entry : entities.entrySet()) {
                    if (found.containsKey(entry.getKey()))
                        throw new StorageException(
                                StorageException.Code.INVALID_DATA, "Record id exists under multiple entity types");
                    found.put(entry.getKey(), mapper.read(entry.getValue()));
                }
            }
            return Map.copyOf(found);
        } catch (AtlasAdapterException e) {
            throw translate(e);
        }
    }

    @Override
    public List<StoredRecord> search(SearchQuery query) {
        checkOpen();
        String type = RecordType.fromTypeName(query.type())
                .orElseThrow(() -> new IllegalArgumentException("Unsupported record type"))
                .entityTypeName();
        try {
            return adapter.findRecordEntities(type, query).stream()
                    .map(mapper::read)
                    .toList();
        } catch (AtlasAdapterException e) {
            throw translate(e);
        }
    }

    @Override
    public WriteResult write(WriteRequest request) {
        checkOpen();
        limits.check(request.candidate());
        ReentrantLock lock = locks[Math.floorMod(request.candidate().id().hashCode(), locks.length)];
        lock.lock();
        try {
            checkOpen();
            if (uncertainWrite.get())
                throw new StorageException(
                        StorageException.Code.UNKNOWN_WRITE,
                        "Writes are blocked pending reconciliation of an earlier ambiguous write");
            StoredRecord current = get(request.candidate().id()).orElse(null);
            checkCondition(request, current);
            String revision = UUID.randomUUID().toString();
            var entity = mapper.write(request.candidate(), revision);
            try {
                adapter.createOrUpdateRecordEntity(entity);
                StoredRecord persisted = get(request.candidate().id())
                        .orElseThrow(() ->
                                new StorageException(StorageException.Code.INVALID_DATA, "Written record is missing"));
                if (!revision.equals(persisted.revision())
                        || !request.candidate().equals(persisted.record())) {
                    throw new StorageException(
                            StorageException.Code.INVALID_DATA, "Stored snapshot differs from the submitted write");
                }
                return WriteResult.builder()
                        .stored(persisted)
                        .disposition(
                                current == null ? WriteResult.Disposition.CREATED : WriteResult.Disposition.REPLACED)
                        .build();
            } catch (RuntimeException e) {
                if (e instanceof AtlasAdapterException notSent && notSent.isRequestNotSent()) {
                    throw translate(notSent);
                }
                if (e instanceof AtlasAdapterException denied
                        && (Integer.valueOf(401).equals(denied.getHttpStatusCode())
                                || Integer.valueOf(403).equals(denied.getHttpStatusCode()))) {
                    // Atlas rejected the mutation before execution. A verification-read
                    // failure is already wrapped as StorageException and remains uncertain.
                    throw translate(denied);
                }
                // After sending a mutation, even a failed verification read leaves its
                // outcome uncertain. A digest alone cannot confirm the full snapshot.
                uncertainWrite.set(true);
                throw new StorageException(
                        StorageException.Code.UNKNOWN_WRITE,
                        "Atlas write outcome is unknown for "
                                + request.candidate().id() + " at attempted revision " + revision
                                + "; reconcile before resuming writes",
                        e);
            }
        } finally {
            lock.unlock();
        }
    }

    private static void checkCondition(WriteRequest request, StoredRecord current) {
        if (current == null) {
            if (request.condition() instanceof WriteCondition.AtRevision)
                throw new RecordConflictException(RecordConflictException.Reason.NOT_FOUND, null);
            return;
        }
        RecordEnvelope record = current.record();
        if (!record.type().equals(request.candidate().type()))
            throw new RecordConflictException(RecordConflictException.Reason.TYPE_MISMATCH, current);
        if (record.validation() != null && record.validation().status() == RecordStatus.VALIDATED)
            throw new RecordConflictException(RecordConflictException.Reason.IMMUTABLE, current);
        if (request.condition() instanceof WriteCondition.Absent)
            throw new RecordConflictException(RecordConflictException.Reason.ALREADY_EXISTS, current);
        if (!current.revision().equals(((WriteCondition.AtRevision) request.condition()).revision()))
            throw new RecordConflictException(RecordConflictException.Reason.STALE_REVISION, current);
    }

    private void checkOpen() {
        if (closed.get()) throw new StorageException(StorageException.Code.UNAVAILABLE, "Plugin is closed");
    }

    private static StorageException translate(AtlasAdapterException e) {
        Throwable cause = e.getCause();
        boolean unavailable = cause instanceof InterruptedException
                || (cause instanceof IOException && !(cause instanceof JsonProcessingException));
        return new StorageException(
                unavailable ? StorageException.Code.UNAVAILABLE : StorageException.Code.BACKEND_ERROR,
                "Atlas operation failed",
                e);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        if (adapter instanceof AutoCloseable resource) {
            try {
                resource.close();
            } catch (Exception e) {
                throw new StorageException(StorageException.Code.BACKEND_ERROR, "Cannot close Atlas client", e);
            }
        }
    }
}
