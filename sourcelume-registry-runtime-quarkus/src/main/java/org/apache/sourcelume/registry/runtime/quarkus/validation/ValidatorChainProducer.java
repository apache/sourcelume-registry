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
package org.apache.sourcelume.registry.runtime.quarkus.validation;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.apache.sourcelume.registry.core.validation.ValidatorChain;

/**
 * CDI wiring for the {@link ValidatorChain}: discovers the validation
 * plugins via the {@code RecordValidator} ServiceLoader SPI once, at
 * application startup.
 *
 * <p>Fail-on-start is deliberate: {@link ValidatorChain#discover()} throws
 * when no plugins are on the classpath, which aborts boot instead of
 * leaving the registry silently accepting unvalidated records. There is
 * no opt-out switch — a registry that skips validation contradicts its
 * purpose (verifiable provenance).
 */
@ApplicationScoped
public class ValidatorChainProducer {

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
