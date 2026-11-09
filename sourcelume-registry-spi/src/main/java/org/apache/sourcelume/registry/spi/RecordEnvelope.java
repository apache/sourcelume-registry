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

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** One immutable stored snapshot. Document and verdict may be absent only when reading a pre-existing plain catalog entity. */
public record RecordEnvelope(
        String id,
        String type,
        RawDocument document,
        ValidationMetadata validation,
        RecordAttributes attributes,
        RecordFacets facets,
        List<RecordAttachment> attachments) {
    public RecordEnvelope {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        if (id.isBlank() || type.isBlank()) throw new IllegalArgumentException("Record id and type must not be blank");
        Objects.requireNonNull(attributes, "attributes");
        Objects.requireNonNull(facets, "facets");
        attachments = List.copyOf(attachments);
        var names = new HashSet<String>();
        for (RecordAttachment attachment : attachments) {
            if (document == null || !document.sha256().equals(attachment.documentSha256()))
                throw new IllegalArgumentException("Attachment belongs to another document");
            if (!names.add(attachment.name()))
                throw new IllegalArgumentException("Duplicate attachment name: " + attachment.name());
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String id;
        private String type;
        private RawDocument document;
        private ValidationMetadata validation;
        private RecordAttributes attributes = RecordAttributes.builder().build();
        private RecordFacets facets = RecordFacets.builder().build();
        private List<RecordAttachment> attachments = List.of();

        private Builder() {}

        public Builder id(String value) {
            this.id = value;
            return this;
        }

        public Builder type(String value) {
            this.type = value;
            return this;
        }

        public Builder document(RawDocument value) {
            this.document = value;
            return this;
        }

        public Builder validation(ValidationMetadata value) {
            this.validation = value;
            return this;
        }

        public Builder attributes(RecordAttributes value) {
            this.attributes = value;
            return this;
        }

        public Builder facets(RecordFacets value) {
            this.facets = value;
            return this;
        }

        public Builder attachments(List<RecordAttachment> value) {
            this.attachments = value;
            return this;
        }

        public RecordEnvelope build() {
            return new RecordEnvelope(id, type, document, validation, attributes, facets, attachments);
        }
    }
}
