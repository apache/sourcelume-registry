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
package org.apache.sourcelume.registry.core.ingest;

import java.util.List;
import java.util.Objects;
import org.apache.sourcelume.registry.spi.RawDocument;
import org.apache.sourcelume.registry.spi.RecordAttachment;

/** Input to the common ingest path. An omitted revision uses the legacy correction policy. */
public record RecordSubmission(RawDocument document, String expectedRevision, List<RecordAttachment> attachments) {
    public RecordSubmission {
        Objects.requireNonNull(document, "document");
        attachments = List.copyOf(attachments);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private RawDocument document;
        private String expectedRevision;
        private List<RecordAttachment> attachments = List.of();

        private Builder() {}

        public Builder document(RawDocument value) {
            document = value;
            return this;
        }

        public Builder expectedRevision(String value) {
            expectedRevision = value;
            return this;
        }

        public Builder attachments(List<RecordAttachment> value) {
            attachments = value;
            return this;
        }

        public RecordSubmission build() {
            return new RecordSubmission(document, expectedRevision, attachments);
        }
    }
}
