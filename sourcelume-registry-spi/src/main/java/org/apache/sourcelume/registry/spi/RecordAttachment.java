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

import java.util.Arrays;
import java.util.Objects;

/** Opaque sidecar bound to the exact document digest. The store does not verify signatures. */
public record RecordAttachment(String name, String mediaType, String documentSha256, byte[] bytes) {
    public RecordAttachment {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(mediaType, "mediaType");
        Objects.requireNonNull(documentSha256, "documentSha256");
        if (name.isBlank() || name.length() > 128 || mediaType.isBlank() || mediaType.length() > 256)
            throw new IllegalArgumentException("Invalid attachment name or media type");
        bytes = Objects.requireNonNull(bytes, "bytes").clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    public int size() {
        return bytes.length;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RecordAttachment that
                && name.equals(that.name)
                && mediaType.equals(that.mediaType)
                && documentSha256.equals(that.documentSha256)
                && Arrays.equals(bytes, that.bytes);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(name, mediaType, documentSha256) + Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "RecordAttachment[name=" + name + ", mediaType=" + mediaType + ", size=" + bytes.length + "]";
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String name;
        private String mediaType;
        private String documentSha256;
        private byte[] bytes;

        private Builder() {}

        public Builder name(String value) {
            this.name = value;
            return this;
        }

        public Builder mediaType(String value) {
            this.mediaType = value;
            return this;
        }

        public Builder documentSha256(String value) {
            this.documentSha256 = value;
            return this;
        }

        public Builder bytes(byte[] value) {
            this.bytes = Objects.requireNonNull(value, "bytes").clone();
            return this;
        }

        public RecordAttachment build() {
            return new RecordAttachment(name, mediaType, documentSha256, bytes);
        }
    }
}
