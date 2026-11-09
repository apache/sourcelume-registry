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

/** A definitive rejection; the candidate has not been written. */
public final class RecordConflictException extends RuntimeException {
    public enum Reason {
        ALREADY_EXISTS,
        IMMUTABLE,
        TYPE_MISMATCH,
        STALE_REVISION,
        NOT_FOUND
    }

    private final Reason reason;
    private final StoredRecord current;

    public RecordConflictException(Reason reason, StoredRecord current) {
        super(reason.name());
        this.reason = reason;
        this.current = current;
    }

    public Reason reason() {
        return reason;
    }

    public StoredRecord current() {
        return current;
    }
}
