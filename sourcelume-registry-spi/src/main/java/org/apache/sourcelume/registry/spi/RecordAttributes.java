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

/** Searchable provenance attributes. Field names retain their existing HTTP meanings. */
public record RecordAttributes(
        String name,
        String description,
        String licenseId,
        String sourceUri,
        String modelUri,
        String modelVersion,
        String disclosure) {
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String name;
        private String description;
        private String licenseId;
        private String sourceUri;
        private String modelUri;
        private String modelVersion;
        private String disclosure;

        private Builder() {}

        public Builder name(String value) {
            this.name = value;
            return this;
        }

        public Builder description(String value) {
            this.description = value;
            return this;
        }

        public Builder licenseId(String value) {
            this.licenseId = value;
            return this;
        }

        public Builder sourceUri(String value) {
            this.sourceUri = value;
            return this;
        }

        public Builder modelUri(String value) {
            this.modelUri = value;
            return this;
        }

        public Builder modelVersion(String value) {
            this.modelVersion = value;
            return this;
        }

        public Builder disclosure(String value) {
            this.disclosure = value;
            return this;
        }

        public RecordAttributes build() {
            return new RecordAttributes(name, description, licenseId, sourceUri, modelUri, modelVersion, disclosure);
        }
    }
}
