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

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpTimeoutException;
import org.apache.sourcelume.registry.atlas.adapter.exception.AtlasAdapterException;
import org.apache.sourcelume.registry.common.dto.RecordEntityDto;
import org.apache.sourcelume.registry.common.dto.SourcelumeDatasetDto;
import org.apache.sourcelume.registry.spi.*;
import org.apache.sourcelume.registry.spi.tck.RecordStoreContract;
import org.junit.jupiter.api.Test;

class AtlasWriteRecoveryTest {
    private static WriteRequest request(String id, WriteCondition condition) {
        return WriteRequest.builder()
                .candidate(RecordStoreContract.record(id, "ProvenanceRecord", "{}", RecordStatus.INCOMPLETE))
                .condition(condition)
                .build();
    }

    @Test
    void timeoutBlocksFurtherWritesEvenWhenReadBackStillShowsOldState() {
        class DelayedEntities extends AtlasStoreContractTest.Entities {
            RecordEntityDto pending;
            int writes;

            @Override
            public String createOrUpdateRecordEntity(RecordEntityDto record) {
                writes++;
                pending = record;
                throw new AtlasAdapterException("Timed out after sending", 500, new HttpTimeoutException("timeout"));
            }

            void finishRemoteWrite() {
                super.createOrUpdateRecordEntity(pending);
            }
        }
        DelayedEntities entities = new DelayedEntities();
        try (var plugin = new AtlasRegistryPlugin(entities, new ObjectMapper(), "unused")) {
            assertEquals(
                    StorageException.Code.UNKNOWN_WRITE,
                    assertThrows(
                                    StorageException.class,
                                    () -> plugin.write(request("urn:one", WriteCondition.absent())))
                            .code());
            assertTrue(plugin.get("urn:one").isEmpty());
            assertFalse(plugin.isReady());
            assertEquals(
                    StorageException.Code.UNKNOWN_WRITE,
                    assertThrows(
                                    StorageException.class,
                                    () -> plugin.write(request("urn:one", WriteCondition.absent())))
                            .code());
            entities.finishRemoteWrite();
            StoredRecord landed = plugin.get("urn:one").orElseThrow();
            assertEquals(
                    StorageException.Code.UNKNOWN_WRITE,
                    assertThrows(
                                    StorageException.class,
                                    () -> plugin.write(
                                            request("urn:one", WriteCondition.atRevision(landed.revision()))))
                            .code());
            assertEquals(1, entities.writes, "No second mutation while an outcome needs reconciliation");
        }
    }

    @Test
    void explicitAuthorizationRejectionDoesNotQuarantineWrites() {
        var entities = new AtlasStoreContractTest.Entities() {
            @Override
            public String createOrUpdateRecordEntity(RecordEntityDto record) {
                throw new AtlasAdapterException("Forbidden", 403, null);
            }
        };
        try (var plugin = new AtlasRegistryPlugin(entities, new ObjectMapper(), "unused")) {
            assertEquals(
                    StorageException.Code.BACKEND_ERROR,
                    assertThrows(
                                    StorageException.class,
                                    () -> plugin.write(request("urn:one", WriteCondition.absent())))
                            .code());
            assertTrue(plugin.isReady());
            assertTrue(plugin.get("urn:one").isEmpty());
        }
    }

    @Test
    void failedConditionReadDoesNotSendMutationOrPoisonWrites() {
        var entities = new AtlasStoreContractTest.Entities() {
            @Override
            public RecordEntityDto getRecordEntityByQualifiedName(String type, String id) {
                throw new AtlasAdapterException("read timed out", 500, new HttpTimeoutException("timeout"));
            }

            @Override
            public String createOrUpdateRecordEntity(RecordEntityDto record) {
                fail("Must not write after failed condition read");
                return null;
            }
        };
        try (var plugin = new AtlasRegistryPlugin(entities, new ObjectMapper(), "unused")) {
            assertEquals(
                    StorageException.Code.UNAVAILABLE,
                    assertThrows(
                                    StorageException.class,
                                    () -> plugin.write(request("urn:one", WriteCondition.absent())))
                            .code());
            assertTrue(plugin.isReady());
        }
    }

    @Test
    void legacyRevisionTracksEntireSnapshotAndDoesNotInventMetadata() {
        var entities = new AtlasStoreContractTest.Entities();
        var old = new SourcelumeDatasetDto("urn:old", "old", null, null);
        old.setRawJsonLd("{}");
        old.setRecordStatus(RecordStatus.INCOMPLETE);
        entities.createOrUpdateRecordEntity(old);
        try (var plugin = new AtlasRegistryPlugin(entities, new ObjectMapper(), "unused")) {
            StoredRecord first = plugin.get("urn:old").orElseThrow();
            assertNull(first.record().validation().validatedAt());
            assertNull(first.record().validation().validatedBy());
            old.setValidatedBy("new-validator");
            StoredRecord changed = plugin.get("urn:old").orElseThrow();
            assertNotEquals(first.revision(), changed.revision());
            assertEquals(
                    RecordConflictException.Reason.STALE_REVISION,
                    assertThrows(
                                    RecordConflictException.class,
                                    () -> plugin.write(request("urn:old", WriteCondition.atRevision(first.revision()))))
                            .reason());
            assertEquals(
                    WriteResult.Disposition.REPLACED,
                    plugin.write(request("urn:old", WriteCondition.atRevision(changed.revision())))
                            .disposition());
        }
    }

    @Test
    void sameDocumentDigestDoesNotHideAnIncompleteWrite() {
        var entities = new AtlasStoreContractTest.Entities() {
            @Override
            public String createOrUpdateRecordEntity(RecordEntityDto record) {
                String result = super.createOrUpdateRecordEntity(record);
                record.setValidatedBy("different-validator");
                return result;
            }
        };
        try (var plugin = new AtlasRegistryPlugin(entities, new ObjectMapper(), "unused")) {
            assertEquals(
                    StorageException.Code.UNKNOWN_WRITE,
                    assertThrows(
                                    StorageException.class,
                                    () -> plugin.write(request("urn:one", WriteCondition.absent())))
                            .code());
            assertFalse(plugin.isReady());
        }
    }

    @Test
    void corruptDocumentDigestIsNeverReturnedAsValidBytes() {
        var entities = new AtlasStoreContractTest.Entities();
        var old = new SourcelumeDatasetDto("urn:bad", "bad", null, null);
        old.setRawJsonLd("{}");
        old.setSha256("wrong");
        entities.createOrUpdateRecordEntity(old);
        try (var plugin = new AtlasRegistryPlugin(entities, new ObjectMapper(), "unused")) {
            assertEquals(
                    StorageException.Code.INVALID_DATA,
                    assertThrows(StorageException.class, () -> plugin.get("urn:bad"))
                            .code());
        }
    }

    @Test
    void failedVerificationConnectionStillQuarantinesTheMutation() {
        var entities = new AtlasStoreContractTest.Entities() {
            boolean written;

            @Override
            public String createOrUpdateRecordEntity(RecordEntityDto entity) {
                String result = super.createOrUpdateRecordEntity(entity);
                written = true;
                return result;
            }

            @Override
            public RecordEntityDto getRecordEntityByQualifiedName(String type, String id) {
                if (written)
                    throw AtlasAdapterException.notSent("Read connection failed", new java.net.ConnectException());
                return super.getRecordEntityByQualifiedName(type, id);
            }
        };
        try (var plugin = new AtlasRegistryPlugin(entities, new ObjectMapper(), "unused")) {
            assertEquals(
                    StorageException.Code.UNKNOWN_WRITE,
                    assertThrows(
                                    StorageException.class,
                                    () -> plugin.write(request("urn:verify", WriteCondition.absent())))
                            .code());
            assertFalse(plugin.isReady());
        }
    }
}
