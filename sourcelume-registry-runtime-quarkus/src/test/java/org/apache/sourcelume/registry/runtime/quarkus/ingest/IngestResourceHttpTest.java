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
package org.apache.sourcelume.registry.runtime.quarkus.ingest;

import static io.restassured.RestAssured.given;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

/**
 * Container-level smoke test for the ingest endpoints: the two failure
 * paths that are decided before any Atlas round-trip — unsupported
 * content type (415, container-enforced via @Consumes) and a body
 * without a usable record id (400). The lifecycle semantics (202,
 * 409 on resubmission, resubmitting INCOMPLETE records, status views)
 * are covered by {@link IngestResourceTest}; the test profile has no
 * Atlas backend, so success paths would fail on the unreachable
 * backend here.
 */
@QuarkusTest
class IngestResourceHttpTest {

    @Test
    void wrongContentTypeIsRejected() {
        given().contentType(ContentType.TEXT)
                .body("{}")
                .when()
                .post("/records")
                .then()
                .statusCode(415);
    }

    @Test
    void bodyWithoutAnIdIsRejectedOverHttp() {
        given().contentType(ContentType.JSON)
                .body("{\"name\": \"no id here\"}")
                .when()
                .post("/records")
                .then()
                .statusCode(400);
    }
}
