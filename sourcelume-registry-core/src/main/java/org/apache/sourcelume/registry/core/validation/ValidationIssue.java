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
package org.apache.sourcelume.registry.core.validation;

/**
 * Represents a single validation issue (error, warning, or information) produced by a {@link RecordValidator}.
 * Instances are immutable.
 */
public record ValidationIssue(String validatorId, Severity severity, String path, String message) {

    /**
     * Severity levels for validation issues.
     */
    public enum Severity {
        /** A violation: the document is not valid (e.g., missing required field, invalid format). */
        VIOLATION,
        /** A warning: the document deviates from the expected shape but may still be usable. */
        WARNING,
        /** An informational note: e.g., a detected usage pattern. */
        INFO
    }

    /**
     * Helper to create a VIOLATION issue.
     */
    public static ValidationIssue violation(String validatorId, String path, String message) {
        return new ValidationIssue(validatorId, Severity.VIOLATION, path, message);
    }

    /**
     * Helper to create a WARNING issue.
     */
    public static ValidationIssue warning(String validatorId, String path, String message) {
        return new ValidationIssue(validatorId, Severity.WARNING, path, message);
    }

    /**
     * Helper to create an INFO issue.
     */
    public static ValidationIssue info(String validatorId, String path, String message) {
        return new ValidationIssue(validatorId, Severity.INFO, path, message);
    }
}
