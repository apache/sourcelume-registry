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

/** Replaces the entire document, verdict and attachments; an empty attachment list clears previous attachments. */
public record WriteRequest(RecordEnvelope candidate, WriteCondition condition) {
    public WriteRequest {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(candidate.document(), "document");
        Objects.requireNonNull(candidate.validation(), "validation");
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private RecordEnvelope candidate;
        private WriteCondition condition;

        private Builder() {}

        public Builder candidate(RecordEnvelope value) {
            this.candidate = value;
            return this;
        }

        public Builder condition(WriteCondition value) {
            this.condition = value;
            return this;
        }

        public WriteRequest build() {
            return new WriteRequest(candidate, condition);
        }
    }
}
