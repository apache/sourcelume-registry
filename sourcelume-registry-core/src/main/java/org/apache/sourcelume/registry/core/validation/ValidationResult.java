/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.sourcelume.registry.core.validation;

import java.util.Collections;
import java.util.List;

/**
 * The result of validating a ProvenanceRecord document.
 * Instances are immutable. Use {@link #ok()} for a successful result.
 */
public record ValidationResult(boolean conforms, List<ValidationIssue> issues) {

    /**
     * Defensive copy: results are immutable.
     */
    public ValidationResult {
        issues = List.copyOf(issues);
    }

    /**
     * A successful validation result (zero issues).
     */
    public static final ValidationResult OK = new ValidationResult(true, Collections.emptyList());

    /**
     * Convenience factory for a failed validation result.
     *
     * <p>A null or empty issue list is rejected: a failed result without
     * issues would be indistinguishable from success for callers inspecting
     * issues, so silently mapping it to a conforming result would hide a
     * plugin bug rather than surface it.
     *
     * @param issues Non-empty list of issues (must contain at least one VIOLATION).
     * @throws IllegalArgumentException if the list is null or empty
     */
    public static ValidationResult failed(List<ValidationIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            throw new IllegalArgumentException(
                    "A failed validation result requires at least one issue");
        }
        return new ValidationResult(false, issues);
    }

    /**
     * Returns {@code true} if there are no issues (same as {@link #conforms} but more explicit).
     */
    public boolean isOk() {
        return conforms && issues.isEmpty();
    }
}
