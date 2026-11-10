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
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.apache.sourcelume.registry.atlas.adapter.AtlasAdapter;
import org.apache.sourcelume.registry.common.dto.SourcelumeDatasetDto;
import org.junit.jupiter.api.Test;

@QuarkusTest
@io.quarkus.test.junit.TestProfile(org.apache.sourcelume.registry.runtime.quarkus.test.StubStorageProfile.class)
class DatasetResourceTest {

    @Inject
    AtlasAdapter atlasAdapter;

    @Test
    void shouldOfferNoWritePathOnDatasets() {
        // An id denotes one record; records are written via POST /records only,
        // so the dataset views are read-only.
        given().contentType(ContentType.JSON)
                .body("{\"qualifiedName\": \"sourcelume://datasets/no-write\", \"name\": \"No write\"}")
                .when()
                .post("/api/v1/datasets")
                .then()
                .statusCode(405);

        given().contentType(ContentType.JSON)
                .body("{\"name\": \"No write\"}")
                .when()
                .put("/api/v1/datasets/sourcelume://datasets/no-write")
                .then()
                .statusCode(405);

        given().queryParam("qualifiedName", "sourcelume://datasets/no-write")
                .when()
                .get("/api/v1/datasets")
                .then()
                .statusCode(404);
    }

    @Test
    void shouldFetchADatasetByQueryPathAndGuid() {
        SourcelumeDatasetDto dataset = new SourcelumeDatasetDto();
        dataset.setQualifiedName("sourcelume://datasets/test-1");
        dataset.setName("Test Dataset 1");
        dataset.setLicenseId("Apache-2.0");
        String guid = atlasAdapter.createOrUpdateRecordEntity(dataset);

        given().queryParam("qualifiedName", "sourcelume://datasets/test-1")
                .when()
                .get("/api/v1/datasets")
                .then()
                .statusCode(200)
                .body("qualifiedName", equalTo("sourcelume://datasets/test-1"))
                .body("guid", equalTo(guid));

        // Both the full and the collapsed URI path resolve.
        given().when()
                .get("/api/v1/datasets/sourcelume://datasets/test-1")
                .then()
                .statusCode(200)
                .body("name", equalTo("Test Dataset 1"));

        given().when()
                .get("/api/v1/datasets/sourcelume:/datasets/test-1")
                .then()
                .statusCode(200)
                .body("name", equalTo("Test Dataset 1"));

        given().when()
                .get("/api/v1/datasets/guid/" + guid)
                .then()
                .statusCode(200)
                .body("qualifiedName", equalTo("sourcelume://datasets/test-1"))
                .body("licenseId", equalTo("Apache-2.0"));
    }

    @Test
    void shouldReturn404WhenDatasetNotFound() {
        given().queryParam("qualifiedName", "sourcelume://datasets/nonexistent")
                .when()
                .get("/api/v1/datasets")
                .then()
                .statusCode(404);

        given().when()
                .get("/api/v1/datasets/sourcelume://datasets/nonexistent")
                .then()
                .statusCode(404);

        given().when().get("/api/v1/datasets/guid/invalid-guid").then().statusCode(404);
    }
}
