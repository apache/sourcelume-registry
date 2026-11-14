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
package org.apache.sourcelume.registry.distribution;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import io.restassured.specification.RequestSpecification;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;

/** Runs the fast-jar as a separate process so persistence can be checked across restarts. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresDistributionIT {
    private static final Path APP_LOG = Path.of("target/failsafe-reports/postgres-distribution.log");
    private PostgreSQLContainer<?> database;
    private String jdbcUrl;
    private String username;
    private String password;
    private Process application;
    private int port;

    @BeforeAll
    void start() throws Exception {
        jdbcUrl = System.getenv("SOURCELUME_PG_TCK_JDBC_URL");
        if (jdbcUrl != null && !jdbcUrl.isBlank()) {
            username = System.getenv().getOrDefault("SOURCELUME_PG_TCK_USERNAME", "postgres");
            password = System.getenv().getOrDefault("SOURCELUME_PG_TCK_PASSWORD", "");
            startApplication();
            return;
        }
        database = new PostgreSQLContainer<>("postgres:17-alpine");
        database.start();
        jdbcUrl = database.getJdbcUrl();
        username = database.getUsername();
        password = database.getPassword();
        startApplication();
    }

    @AfterAll
    void stop() throws Exception {
        try {
            stopApplication();
        } finally {
            if (database != null) {
                database.stop();
            }
        }
    }

    @Test
    void packagesPostgresWithoutAtlas() throws Exception {
        try (var libraries = Files.list(Path.of("target/quarkus-app/lib/main"))) {
            var names = libraries.map(path -> path.getFileName().toString()).toList();
            assertTrue(names.stream().anyMatch(name -> name.contains("sourcelume-registry-postgres")));
            assertFalse(names.stream().anyMatch(name -> name.contains("atlas") || name.contains("registry-typedefs")));
        }
        request()
                .get("/q/health/ready")
                .then()
                .statusCode(200)
                .body("checks.find { it.name == 'postgres' }.status", equalTo("UP"));
        request()
                .queryParam("format", "json")
                .get("/q/openapi")
                .then()
                .statusCode(200)
                .body("paths", hasKey("/records"))
                .body("paths", not(hasKey("/api/v1/datasets")));
        request().get("/api/v1/datasets").then().statusCode(404);
        request().get("/api/v1/types/typedefs").then().statusCode(404);
    }

    @Test
    void recordsAndTrainingReferencesSurviveRestart() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String datasetId = "https://sourcelume.apache.org/records/" + suffix + "-valid";
        String draft = "{\"id\":\"" + datasetId + "\",\"type\":\"ProvenanceRecord\",\"name\":\"雪\"}\n";
        request()
                .contentType("application/json")
                .body(draft.getBytes(StandardCharsets.UTF_8))
                .post("/records/validate")
                .then()
                .statusCode(422)
                .body("conforms", equalTo(false));
        String location = request()
                .contentType("application/ld+json")
                .body(draft.getBytes(StandardCharsets.UTF_8))
                .post("/records")
                .then()
                .statusCode(201)
                .body("recordStatus", equalTo("INCOMPLETE"))
                .extract()
                .header("Location");
        request()
                .urlEncodingEnabled(false)
                .get(location + "/document")
                .then()
                .statusCode(200)
                .body(equalTo(draft));

        byte[] dataset = fixture("valid-dataset.jsonld", suffix);
        request()
                .contentType("application/json")
                .body(dataset)
                .post("/records/validate")
                .then()
                .statusCode(200)
                .body("conforms", equalTo(true));
        request()
                .contentType("application/ld+json")
                .body(dataset)
                .post("/records")
                .then()
                .statusCode(200)
                .body("recordStatus", equalTo("VALIDATED"));
        String trainingLocation = request()
                .contentType("application/ld+json")
                .body(fixture("valid-training.jsonld", suffix))
                .post("/records")
                .then()
                .statusCode(201)
                .body("recordStatus", equalTo("VALIDATED"))
                .extract()
                .header("Location");
        assertStoredRecords(location, dataset, trainingLocation, datasetId);

        stopApplication();
        startApplication();
        assertStoredRecords(location, dataset, trainingLocation, datasetId);
    }

    private void assertStoredRecords(String location, byte[] dataset, String trainingLocation, String datasetId)
            throws Exception {
        String changed =
                new String(dataset, StandardCharsets.UTF_8).replace("Minimal example record", "Changed record");
        request()
                .contentType("application/ld+json")
                .body(changed.getBytes(StandardCharsets.UTF_8))
                .post("/records")
                .then()
                .statusCode(409)
                .body("recordStatus", equalTo("VALIDATED"));
        request()
                .urlEncodingEnabled(false)
                .get(location)
                .then()
                .statusCode(200)
                .body("recordStatus", equalTo("VALIDATED"));
        String digest =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(dataset));
        byte[] stored = request()
                .urlEncodingEnabled(false)
                .get(location + "/document")
                .then()
                .statusCode(200)
                .header("ETag", equalTo("\"" + digest + "\""))
                .extract()
                .asByteArray();
        assertArrayEquals(dataset, stored);
        request()
                .urlEncodingEnabled(false)
                .get(trainingLocation)
                .then()
                .statusCode(200)
                .body("type", equalTo("TrainingProvenanceRecord"))
                .body("recordStatus", equalTo("VALIDATED"))
                .body("trainingData[0].dataset", equalTo(datasetId))
                .body("trainingData[0].recordStatus", equalTo("VALIDATED"));
    }

    private RequestSpecification request() {
        return given().baseUri("http://localhost").port(port);
    }

    private static byte[] fixture(String name, String suffix) throws IOException {
        try (var input = PostgresDistributionIT.class.getResourceAsStream("/records/" + name)) {
            assertNotNull(input, "Missing fixture: " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("assembly-", suffix + "-")
                    .getBytes(StandardCharsets.UTF_8);
        }
    }

    private void startApplication() throws Exception {
        Files.createDirectories(APP_LOG.getParent());
        if (port == 0) {
            try (var socket = new ServerSocket(0)) {
                port = socket.getLocalPort();
            }
        }
        var builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar",
                "target/quarkus-app/quarkus-run.jar");
        var env = builder.environment();
        env.remove("SOURCELUME_STORAGE_PROVIDER");
        env.remove("SOURCELUME_STORAGE_REQUIRE_ATOMIC_WRITES");
        env.put("SOURCELUME_POSTGRES_JDBC_URL", jdbcUrl);
        env.put("SOURCELUME_POSTGRES_USERNAME", username);
        env.put("SOURCELUME_POSTGRES_PASSWORD", password);
        env.put("SERVER_PORT", Integer.toString(port));
        application = builder.redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(APP_LOG.toFile()))
                .start();
        var probe = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/q/health/ready"))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();
        try (var client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (application.isAlive() && System.nanoTime() < deadline) {
                try {
                    if (client.send(probe, HttpResponse.BodyHandlers.discarding())
                                    .statusCode()
                            == 200) {
                        return;
                    }
                } catch (IOException ignored) {
                    // The HTTP listener may not be bound yet.
                }
                Thread.sleep(200);
            }
        }
        fail("Packaged registry did not become ready; see " + APP_LOG.toAbsolutePath());
    }

    private void stopApplication() throws InterruptedException {
        if (application == null) {
            return;
        }
        application.destroy();
        if (!application.waitFor(10, TimeUnit.SECONDS)) {
            application.destroyForcibly();
            assertTrue(application.waitFor(10, TimeUnit.SECONDS), "Packaged registry did not stop");
        }
        application = null;
    }
}
