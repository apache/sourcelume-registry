/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
    The ASF licenses this file to You under the Apache License, Version 2.0
    (the "License"); you may not use this file except in compliance with
    the License.  You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
    */
package org.apache.sourcelume.registry.ingest.worker;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;

/**
 * Quarkus entry point for the Sourcelume Ingest Worker service.
 *
 * <p>Quarkus equivalent of the Spring Boot {@code @SpringBootApplication} used in
 * the Spring spike. The actual startup work (spec verification + typedef
 * registration) is in {@code AtlasBootstrapObserver}, observing the CDI startup
 * event.
 */
@QuarkusMain
public class SourcelumeWorkerApplication implements QuarkusApplication {

    @Override
    public int run(String... args) {
        Quarkus.waitForExit();
        return 0;
    }
}
