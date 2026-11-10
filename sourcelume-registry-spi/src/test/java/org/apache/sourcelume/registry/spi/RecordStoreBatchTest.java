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

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RecordStoreBatchTest {
    private final List<String> reads = new ArrayList<>();
    private final RecordStore store = new RecordStore() {
        public StoreLimits limits() {
            return StoreLimits.builder().build();
        }

        public WriteGuarantee writeGuarantee() {
            return WriteGuarantee.CHECK_THEN_WRITE;
        }

        public Optional<StoredRecord> get(String id) {
            reads.add(id);
            if (id.equals("urn:failed")) throw new StorageException(StorageException.Code.UNAVAILABLE, "offline");
            return Optional.empty();
        }

        public WriteResult write(WriteRequest request) {
            throw new UnsupportedOperationException();
        }
    };

    @Test
    void validatesTheWholeRequestBeforeReadingAndDeduplicatesMissingIds() {
        assertTrue(store.getAll(List.of()).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> store.getAll(List.of("urn:one", " ")));
        assertThrows(IllegalArgumentException.class, () -> store.getAll(Arrays.asList("urn:one", null)));
        assertTrue(reads.isEmpty());
        assertTrue(store.getAll(List.of("urn:one", "urn:one", "urn:two")).isEmpty());
        assertEquals(List.of("urn:one", "urn:two"), reads);
    }

    @Test
    void failureAbortsTheBatchWithoutReadingLaterIds() {
        var failure =
                assertThrows(StorageException.class, () -> store.getAll(List.of("urn:one", "urn:failed", "urn:later")));
        assertEquals(StorageException.Code.UNAVAILABLE, failure.code());
        assertEquals(List.of("urn:one", "urn:failed"), reads);
    }
}
