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
package org.apache.sourcelume.registry.ingest.worker.validation;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.apache.sourcelume.registry.core.validation.ValidatorChain;

/**
 * CDI wiring for the {@link ValidatorChain}: discovers the validation
 * plugins via the {@code RecordValidator} ServiceLoader SPI once, at
 * application startup.
 *
 * <p>Fail-on-start is deliberate: {@link ValidatorChain#discover()} throws
 * when no plugins are on the classpath, which aborts boot instead of
 * leaving the worker silently promoting records it never validated. A
 * worker without validators contradicts the registry's core guarantee
 * ("no record is ACTIVE without validation"), so there is no opt-out.
 *
 * <p>Ported from the Quarkus runtime module, which owns the same wiring
 * for the pre-flight validation endpoint.
 */
@ApplicationScoped
public class ValidatorChainProducer {

    /** Client proxy to the produced chain; touching it at startup forces discovery. */
    @Inject
    ValidatorChain validatorChain;

    void onStart(@Observes StartupEvent event) {
        // CDI creates this bean for the StartupEvent observer; touching the
        // (proxied) chain field forces plugin discovery (and schema/shapes
        // parsing) at boot — exactly once, cached for all later injection
        // points. If no plugins are on the classpath, discover() throws and
        // aborts startup instead of failing on the first validation request.
        validatorChain.validators();
    }

    /**
     * Produces the application-wide validator chain. The plugins are
     * instantiated here (their constructors parse schema and shapes, which
     * is expensive and must happen exactly once); the chain itself is
     * immutable and its validators are required to be thread-safe.
     *
     * @return the discovered validator chain, never null
     */
    @Produces
    @ApplicationScoped
    public ValidatorChain validatorChain() {
        return ValidatorChain.discover();
    }
}
