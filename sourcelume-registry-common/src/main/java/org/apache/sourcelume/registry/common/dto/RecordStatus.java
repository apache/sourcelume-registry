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
package org.apache.sourcelume.registry.common.dto;

/**
 * Curation state of a stored provenance record, mirroring the
 * {@code sourcelume_record_status} enum of the Sourcelume type model.
 *
 * <p>This is a Sourcelume domain state, deliberately independent of the
 * persistence backend's own lifecycle: Atlas distinguishes whether an
 * entity <em>exists</em> (ACTIVE/DELETED/PURGED), while this status
 * describes the record's validation verdict. Keeping the two apart lets
 * the vendor-neutral {@code AtlasAdapter} SPI expose the same state
 * machine on any backend.
 *
 * <p>Validation is synchronous: every stored record carries its verdict,
 * and no verdict-less state exists. Only {@link #VALIDATED} records are
 * considered published; consumers should filter on it. An
 * {@link #INCOMPLETE} record carries its validation issues and stays
 * curatable, so curation can continue over several rounds.
 */
public enum RecordStatus {

    /** Validation failed; the record carries its issues and stays curatable. A corrected resubmission gets a new verdict. */
    INCOMPLETE,

    /** Validated against the spec and published. */
    VALIDATED
}
