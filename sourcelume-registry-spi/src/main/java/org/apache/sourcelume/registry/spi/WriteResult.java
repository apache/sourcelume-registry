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

/** A confirmed write. Uncertain outcomes must throw UNKNOWN_WRITE, never return a successful result. */
public record WriteResult(StoredRecord stored, Disposition disposition) {
    public WriteResult {
        Objects.requireNonNull(stored, "stored");
        Objects.requireNonNull(disposition, "disposition");
    }

    public enum Disposition {
        CREATED,
        REPLACED
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private StoredRecord stored;
        private Disposition disposition;

        private Builder() {}

        public Builder stored(StoredRecord value) {
            this.stored = value;
            return this;
        }

        public Builder disposition(Disposition value) {
            this.disposition = value;
            return this;
        }

        public WriteResult build() {
            return new WriteResult(stored, disposition);
        }
    }
}
