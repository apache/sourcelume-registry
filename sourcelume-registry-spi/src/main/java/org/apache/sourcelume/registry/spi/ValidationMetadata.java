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

import java.util.List;
import java.util.Objects;

/** A stored verdict. Missing historical validator or time metadata remains null, never fabricated. */
public record ValidationMetadata(
        RecordStatus status, List<ValidationIssue> issues, String validatedBy, String validatedAt) {
    public ValidationMetadata {
        Objects.requireNonNull(status, "status");
        issues = List.copyOf(issues);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private RecordStatus status;
        private List<ValidationIssue> issues = List.of();
        private String validatedBy;
        private String validatedAt;

        private Builder() {}

        public Builder status(RecordStatus value) {
            this.status = value;
            return this;
        }

        public Builder issues(List<ValidationIssue> value) {
            this.issues = value;
            return this;
        }

        public Builder validatedBy(String value) {
            this.validatedBy = value;
            return this;
        }

        public Builder validatedAt(String value) {
            this.validatedAt = value;
            return this;
        }

        public ValidationMetadata build() {
            return new ValidationMetadata(status, issues, validatedBy, validatedAt);
        }
    }
}
