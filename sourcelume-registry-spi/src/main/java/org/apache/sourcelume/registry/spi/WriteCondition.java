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

/** A condition evaluated against the current record as part of a write. */
public sealed interface WriteCondition permits WriteCondition.Absent, WriteCondition.AtRevision {
    record Absent() implements WriteCondition {}

    record AtRevision(String revision) implements WriteCondition {
        public AtRevision {
            Objects.requireNonNull(revision, "revision");
            if (revision.isBlank()) throw new IllegalArgumentException("Empty revision");
        }
    }

    static WriteCondition absent() {
        return new Absent();
    }

    static WriteCondition atRevision(String revision) {
        return new AtRevision(revision);
    }
}
