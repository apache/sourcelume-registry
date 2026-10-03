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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * An ordered chain of {@link RecordValidator}s, executed by ascending
 * {@link RecordValidator#order() order} and fail-fast: the first validator
 * that reports a non-conforming result ends the chain.
 *
 * <p>This mirrors the Python reference validator (sourcelume-spec
 * tools/validate.py), which runs the JSON Schema stage before the SHACL
 * stage and only invokes the latter when the former passes.
 *
 * <p>A chain must contain at least one validator: an empty chain never
 * validates anything and is therefore rejected with
 * {@link IllegalStateException} instead of silently reporting conformance.
 * The same applies to duplicate validator {@link RecordValidator#id() ids}:
 * two plugins claiming the same stage (for example two SHACL
 * implementations) is a deployment error that must fail loudly. Swapping a
 * plugin means swapping the jar, not running both.
 */
public final class ValidatorChain {

    private final List<RecordValidator> validators;

    private ValidatorChain(List<RecordValidator> validators) {
        this.validators = validators;
    }

    /**
     * Discovers {@link RecordValidator} implementations via {@link ServiceLoader}
     * (META-INF/services). Validators are ordered by ascending
     * {@link RecordValidator#order() order}, then by id for determinism.
     *
     * @throws IllegalStateException if no validator is discovered, or two
     *                               validators share the same id
     */
    public static ValidatorChain discover() {
        List<RecordValidator> discovered = new ArrayList<>();
        for (RecordValidator validator : ServiceLoader.load(RecordValidator.class)) {
            discovered.add(validator);
        }
        return of(discovered);
    }

    /**
     * Creates a chain from the given validators, ordered by ascending
     * {@link RecordValidator#order() order}, then by id for determinism.
     *
     * @param validators the validators to chain (never {@code null})
     * @throws IllegalStateException if the collection is empty, or two
     *                               validators share the same id
     */
    public static ValidatorChain of(Collection<RecordValidator> validators) {
        Objects.requireNonNull(validators, "validators must not be null");
        List<RecordValidator> copy = new ArrayList<>(validators);
        if (copy.isEmpty()) {
            throw new IllegalStateException(
                    "ValidatorChain must contain at least one validator — an empty chain "
                            + "would silently accept every document");
        }
        Set<String> ids = new HashSet<>();
        for (RecordValidator validator : copy) {
            if (!ids.add(validator.id())) {
                throw new IllegalStateException(
                        "Duplicate validator id in chain: '" + validator.id()
                                + "' — swap the plugin jar instead of running two implementations "
                                + "of the same stage");
            }
        }
        copy.sort(Comparator.comparingInt(RecordValidator::order).thenComparing(RecordValidator::id));
        return new ValidatorChain(List.copyOf(copy));
    }

    /**
     * The validators of this chain, in execution order.
     */
    public List<RecordValidator> validators() {
        return validators;
    }

    /**
     * Validates the document through the chain, fail-fast: the first
     * non-conforming validator ends the chain and its issues (plus any
     * issues collected from earlier validators) are returned.
     *
     * @param jsonLd the raw JSON-LD document
     * @return the aggregated result
     */
    public ValidationResult validate(String jsonLd) {
        Objects.requireNonNull(jsonLd, "jsonLd must not be null");
        List<ValidationIssue> collected = new ArrayList<>();
        for (RecordValidator validator : validators) {
            ValidationResult result = validator.validate(jsonLd);
            collected.addAll(result.issues());
            if (!result.conforms()) {
                if (result.issues().isEmpty()) {
                    // A plugin reporting non-conformance without any issue of
                    // its own is a plugin bug; synthesize one so callers
                    // always see why the chain failed instead of a result
                    // that claims failure but cannot explain it (earlier
                    // validators' warnings do not count as an explanation).
                    collected.add(ValidationIssue.violation(validator.id(), "/",
                            validator.id() + " reported non-conformance without issues"));
                }
                return new ValidationResult(false, List.copyOf(collected));
            }
        }
        return new ValidationResult(true, List.copyOf(collected));
    }
}
