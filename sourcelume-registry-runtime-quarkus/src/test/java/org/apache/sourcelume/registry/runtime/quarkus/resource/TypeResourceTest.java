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
package org.apache.sourcelume.registry.runtime.quarkus.resource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class TypeResourceTest {

    @Test
    void shouldReturnTypeDefs() {
        given().when().get("/api/v1/types/typedefs").then().statusCode(200).body(containsString("sourcelume_dataset"));
    }

    @Test
    void shouldReturnDatasetTypeDefWithItsEnums() {
        given().when()
                .get("/api/v1/types/dataset")
                .then()
                .statusCode(200)
                .body("keySet()", containsInAnyOrder("enumDefs", "entityDefs"))
                .body("entityDefs.name", contains("sourcelume_dataset"))
                .body("enumDefs.name", contains("sourcelume_record_status"));
    }
}
