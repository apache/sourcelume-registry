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
package org.apache.sourcelume.registry.runtime.quarkus.health;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * Tests the readiness probe wiring: the {@code atlas} readiness check is
 * registered with SmallRye Health and reports DOWN when Atlas is not
 * reachable (the test profile points {@code sourcelume.atlas.url} at a
 * guaranteed-closed port, so this is deterministic in CI without a backend).
 */
@QuarkusTest
class AtlasReadinessCheckTest {

    @Test
    void readinessShouldReportAtlasDownWithoutAtlas() {
        given().when()
                .get("/q/health/ready")
                .then()
                .statusCode(503)
                .body("status", equalTo("DOWN"))
                .body("checks.find { it.name == 'atlas' }.status", equalTo("DOWN"));
    }
}
