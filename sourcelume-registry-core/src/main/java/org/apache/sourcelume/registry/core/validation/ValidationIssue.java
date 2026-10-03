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