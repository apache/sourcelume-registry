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

/** Limits enforced before any write. maxAttachmentBytes bounds the combined attachment payload. */
public record StoreLimits(int maxDocumentBytes, int maxAttachments, int maxAttachmentBytes) {
    public StoreLimits {
        if (maxDocumentBytes < 1 || maxAttachments < 0 || maxAttachmentBytes < 0)
            throw new IllegalArgumentException("Invalid store limits");
    }

    public void check(RecordEnvelope record) {
        check(record.document(), record.attachments());
    }

    /** Checks the submission before validation as well as the final envelope before storage. */
    public void check(RawDocument document, java.util.List<RecordAttachment> attachments) {
        if (document.size() > maxDocumentBytes || attachments.size() > maxAttachments) {
            throw new IllegalArgumentException("Record exceeds store limits");
        }
        long size = attachments.stream().mapToLong(RecordAttachment::size).sum();
        if (size > maxAttachmentBytes) throw new IllegalArgumentException("Attachments exceed store limit");
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int maxDocumentBytes = 4 * 1024 * 1024;
        private int maxAttachments = 16;
        private int maxAttachmentBytes = 1024 * 1024;

        private Builder() {}

        public Builder maxDocumentBytes(int value) {
            this.maxDocumentBytes = value;
            return this;
        }

        public Builder maxAttachments(int value) {
            this.maxAttachments = value;
            return this;
        }

        public Builder maxAttachmentBytes(int value) {
            this.maxAttachmentBytes = value;
            return this;
        }

        public StoreLimits build() {
            return new StoreLimits(maxDocumentBytes, maxAttachments, maxAttachmentBytes);
        }
    }
}
