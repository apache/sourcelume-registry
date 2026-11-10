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
import java.util.List;
import org.junit.jupiter.api.Test;

class RecordEnvelopeTest {
    @Test
    void documentAndAttachmentBytesAreDefensivelyCopied() {
        byte[] bytes = {123, 125};
        RawDocument document = RawDocument.builder().bytes(bytes).build();
        bytes[0] = 0;
        document.bytes()[1] = 0;
        assertEquals("{}", document.text());
        byte[] signature = {1, 2};
        var builder = RecordAttachment.builder()
                .name("sig")
                .mediaType("application/jose")
                .documentSha256(document.sha256())
                .bytes(signature);
        signature[0] = 0;
        var attachment = builder.build();
        attachment.bytes()[0] = 0;
        assertArrayEquals(new byte[] {1, 2}, attachment.bytes());
    }

    @Test
    void rejectsInvalidUtf8AndAttachmentsForAnotherDocument() {
        assertThrows(
                IllegalArgumentException.class,
                () -> RawDocument.builder().bytes(new byte[] {(byte) 0xc3}).build());
        var document = RawDocument.builder().bytes(new byte[] {123, 125}).build();
        var attachment = RecordAttachment.builder()
                .name("sig")
                .mediaType("application/jose")
                .documentSha256("wrong")
                .bytes(new byte[] {1})
                .build();
        assertThrows(IllegalArgumentException.class, () -> RecordEnvelope.builder()
                .id("urn:one")
                .type("ProvenanceRecord")
                .document(document)
                .attachments(List.of(attachment))
                .build());
    }

    @Test
    void rejectsDuplicateAttachmentsAndCopiesCollections() {
        var document = RawDocument.builder().bytes(new byte[] {123, 125}).build();
        var attachment = RecordAttachment.builder()
                .name("sig")
                .mediaType("application/jose")
                .documentSha256(document.sha256())
                .bytes(new byte[] {1})
                .build();
        var values = new ArrayList<RecordAttachment>();
        values.add(attachment);
        var record = RecordEnvelope.builder()
                .id("urn:one")
                .type("ProvenanceRecord")
                .document(document)
                .attachments(values)
                .build();
        values.clear();
        assertEquals(1, record.attachments().size());
        assertThrows(
                UnsupportedOperationException.class, () -> record.attachments().clear());
        assertThrows(IllegalArgumentException.class, () -> RecordEnvelope.builder()
                .id("urn:one")
                .type("ProvenanceRecord")
                .document(document)
                .attachments(List.of(attachment, attachment))
                .build());
    }

    @org.junit.jupiter.api.Test
    void facetSnapshotsAndFiltersAreImmutableAndExact() {
        var values = new java.util.HashSet<>(java.util.Set.of("Team A"));
        var map = new java.util.EnumMap<RecordFacet, java.util.Set<String>>(RecordFacet.class);
        map.put(RecordFacet.CREATOR_NAME, values);
        var facets = new RecordFacets(map);
        values.add("Team B");
        map.clear();
        org.junit.jupiter.api.Assertions.assertEquals(
                java.util.Set.of("Team A"), facets.values(RecordFacet.CREATOR_NAME));
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> facets.values(RecordFacet.CREATOR_NAME).clear());
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> RecordFacets.builder().add(RecordFacet.CREATOR_NAME, " "));
        org.junit.jupiter.api.Assertions.assertFalse(facets.matches(
                RecordFacets.builder().add(RecordFacet.CREATOR_NAME, "team a").build()));
    }
}
