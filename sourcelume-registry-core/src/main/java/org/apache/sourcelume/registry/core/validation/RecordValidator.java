package org.apache.sourcelume.registry.core.validation;

/**
 * SPI for a validator that can validate a raw JSON-LD ProvenanceRecord document.
 * Implementations are discovered via {@link ServiceLoader}. Use {@link ValidatorChain}
 * for ordered, chain-of-responsibility validation.
 */
public interface RecordValidator {

    /**
     * Unique identifier for this validator (e.g., "json-schema", "shacl").
     * Must be stable across instances and registered in {@code META-INF/services}.<br>
     * Duplicate IDs within a chain will cause {@link ValidatorChain} to reject.
     */
    String id();

    /**
     * Relative execution order for this validator within a chain. Lower values execute
     * first. The default is {@value #DEFAULT_ORDER}. The chain is ordered ascending.
     */
    default int order() {
        return DEFAULT_ORDER;
    }

    /**
     * The sentinel order value used when {@link #order()} is not overridden.
     */
    int DEFAULT_ORDER = 1000;

    /**
     * Validate the given JSON-LD string representing a ProvenanceRecord.
     * Never throws an exception for malformed input; all validation results should
     * be expressed via the returned {@link ValidationResult}. Implementations should
     * capture parsing issues as validation failures (i.e., non-conforming results).
     * @param jsonLd The raw JSON-LD document (expected to be a single ProvenanceRecord).
     * @return A {@link ValidationResult} describing conformance and issues.
     */
    ValidationResult validate(String jsonLd);
}