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

import org.apache.sourcelume.registry.core.validation.ValidationResult;
import org.apache.sourcelume.registry.spi.RecordStatus;

/**
 * The outcome of one record's run through the ingest pipeline: which
 * record (by qualified name), which record type it claimed (the
 * {@code type} term, null when the document carries none), which
 * {@link RecordStatus} it ended up in, the full {@link ValidationResult}
 * that led there, and the verdict's context — the SHA-256 of the received
 * bytes, the validator chain that judged the record, and when.
 *
 * <p>The result carries the issues as data instead of throwing — an
 * INCOMPLETE record is a normal, expected outcome of curation, not an
 * exceptional one.
 */
public record IngestResult(
        String qualifiedName,
        String recordType,
        RecordStatus status,
        ValidationResult validation,
        String sha256,
        String validatedBy,
        String validatedAt,
        org.apache.sourcelume.registry.spi.WriteResult.Disposition disposition) {}
