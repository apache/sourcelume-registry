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
package org.apache.sourcelume.registry.postgres;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.apache.sourcelume.registry.spi.RecordConflictException;
import org.apache.sourcelume.registry.spi.RecordEnvelope;
import org.apache.sourcelume.registry.spi.RecordStatus;
import org.apache.sourcelume.registry.spi.RecordStore;
import org.apache.sourcelume.registry.spi.StorageException;
import org.apache.sourcelume.registry.spi.StoreLimits;
import org.apache.sourcelume.registry.spi.StoredRecord;
import org.apache.sourcelume.registry.spi.WriteCondition;
import org.apache.sourcelume.registry.spi.WriteRequest;
import org.apache.sourcelume.registry.spi.WriteResult;

/**
 * Authoritative record access on PostgreSQL. Writes run in a single transaction per request and
 * rely on row-level locking plus {@code INSERT ... ON CONFLICT}, so the store provides
 * {@link WriteGuarantee#ATOMIC_CONDITIONAL} semantics without any process-wide lock.
 */
final class PostgresRecordStore implements RecordStore {

    private final PostgresPlugin plugin;

    PostgresRecordStore(PostgresPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public StoreLimits limits() {
        return StoreLimits.builder().build();
    }

    @Override
    public WriteGuarantee writeGuarantee() {
        return WriteGuarantee.ATOMIC_CONDITIONAL;
    }

    @Override
    public Optional<StoredRecord> get(String id) {
        plugin.checkOpen();
        requireId(id);
        try (Connection connection = plugin.openSnapshotConnection()) {
            StoredRecord record = RecordMapper.loadOne(connection, id);
            connection.commit();
            return Optional.ofNullable(record);
        } catch (SQLException e) {
            throw new StorageException(StorageException.Code.BACKEND_ERROR, "Point read failed for " + id, e);
        }
    }

    @Override
    public Map<String, StoredRecord> getAll(Collection<String> ids) {
        plugin.checkOpen();
        Objects.requireNonNull(ids, "ids");
        LinkedHashSet<String> distinct = new LinkedHashSet<>(ids);
        for (String id : distinct) {
            requireId(id);
        }
        if (distinct.isEmpty()) {
            return Map.of();
        }
        try (Connection connection = plugin.openSnapshotConnection()) {
            Map<String, StoredRecord> records = RecordMapper.loadAll(connection, distinct);
            connection.commit();
            return records;
        } catch (SQLException e) {
            throw new StorageException(StorageException.Code.BACKEND_ERROR, "Batch read failed", e);
        }
    }

    @Override
    public WriteResult write(WriteRequest request) {
        plugin.checkOpen();
        RecordEnvelope candidate = request.candidate();
        limits().check(candidate);
        String revision = UUID.randomUUID().toString();
        Connection connection = plugin.openConnection();
        try {
            connection.setAutoCommit(false);
            WriteResult result = request.condition() instanceof WriteCondition.Absent
                    ? writeAbsent(connection, request, revision)
                    : writeAtRevision(connection, request, revision);
            try {
                connection.commit();
            } catch (SQLException commitFailed) {
                // The transaction may already have committed server-side; the outcome
                // is unknown, so the SPI's uncertain-outcome contract applies.
                throw new StorageException(
                        StorageException.Code.UNKNOWN_WRITE,
                        "Commit outcome unknown for " + candidate.id(),
                        commitFailed);
            }
            return result;
        } catch (SQLException e) {
            rollback(connection);
            throw new StorageException(StorageException.Code.BACKEND_ERROR, "Write failed", e);
        } catch (RuntimeException e) {
            throw e;
        } finally {
            close(connection);
        }
    }

    private WriteResult writeAbsent(Connection connection, WriteRequest request, String revision) throws SQLException {
        RecordEnvelope candidate = request.candidate();
        int inserted = RecordMapper.insertRecord(connection, candidate, revision);
        if (inserted == 1) {
            RecordMapper.replaceChildren(connection, candidate);
            return WriteResult.builder()
                    .stored(stored(candidate, revision))
                    .disposition(WriteResult.Disposition.CREATED)
                    .build();
        }
        StoredRecord current = RecordMapper.lockRecord(connection, candidate.id());
        throw conflict(current, request);
    }

    private WriteResult writeAtRevision(Connection connection, WriteRequest request, String revision)
            throws SQLException {
        RecordEnvelope candidate = request.candidate();
        String expected = ((WriteCondition.AtRevision) request.condition()).revision();
        StoredRecord current = RecordMapper.lockRecord(connection, candidate.id());
        if (current == null) {
            throw new RecordConflictException(RecordConflictException.Reason.NOT_FOUND, null);
        }
        if (!current.record().type().equals(candidate.type())) {
            throw new RecordConflictException(RecordConflictException.Reason.TYPE_MISMATCH, current);
        }
        if (current.record().validation() != null
                && current.record().validation().status() == RecordStatus.VALIDATED) {
            throw new RecordConflictException(RecordConflictException.Reason.IMMUTABLE, current);
        }
        if (!current.revision().equals(expected)) {
            throw new RecordConflictException(RecordConflictException.Reason.STALE_REVISION, current);
        }
        RecordMapper.updateRecord(connection, candidate, revision);
        RecordMapper.replaceChildren(connection, candidate);
        return WriteResult.builder()
                .stored(stored(candidate, revision))
                .disposition(WriteResult.Disposition.REPLACED)
                .build();
    }

    private RecordConflictException conflict(StoredRecord current, WriteRequest request) {
        RecordEnvelope candidate = request.candidate();
        if (current == null) {
            return new RecordConflictException(RecordConflictException.Reason.NOT_FOUND, null);
        }
        if (!current.record().type().equals(candidate.type())) {
            return new RecordConflictException(RecordConflictException.Reason.TYPE_MISMATCH, current);
        }
        if (current.record().validation() != null
                && current.record().validation().status() == RecordStatus.VALIDATED) {
            return new RecordConflictException(RecordConflictException.Reason.IMMUTABLE, current);
        }
        return new RecordConflictException(RecordConflictException.Reason.ALREADY_EXISTS, current);
    }

    private static StoredRecord stored(RecordEnvelope envelope, String revision) {
        return StoredRecord.builder().record(envelope).revision(revision).build();
    }

    private static void requireId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Empty record id");
        }
    }

    private static void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // best effort; the original failure is reported instead
        }
    }

    private static void close(Connection connection) {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // connection pooling is not used, so closing is best effort
        }
    }
}
