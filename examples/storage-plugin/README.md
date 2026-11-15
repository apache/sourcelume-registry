<!--
Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file distributed with
this work for additional information regarding copyright ownership.
The ASF licenses this file to You under the Apache License, Version 2.0
(the "License"); you may not use this file except in compliance with
the License. You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# Standalone storage plugin example

This project shows how to implement a storage plugin and run it with the Registry
HTTP runtime. It has its own Maven parent and can be copied outside this repository.

The backend is an in-memory map for learning and tests. Records disappear on
restart. The default Registry distribution uses PostgreSQL for persistent storage.

## Build and run

Use JDK 21 and Maven 3.9+. First install the spec revision pinned by the Registry's
CI, following [the development setup](../../CONTRIBUTING.md#development-setup).
Registry artifacts are currently snapshots built locally.

From the Registry repository root, install the runtime, SPI and TCK artifacts:

```bash
mvn -pl sourcelume-registry-runtime-quarkus,sourcelume-registry-spi-tck -am install -DskipTests
```

Then build and test the example:

```bash
mvn -f examples/storage-plugin/pom.xml verify
java -jar examples/storage-plugin/application/target/quarkus-app/quarkus-run.jar
```

`verify` runs the inherited storage contract tests and starts the packaged
application to check provider discovery, HTTP access and configuration forwarding.
These tests need no database or Docker daemon. To build a copy elsewhere, copy this
whole directory and run `mvn verify` from that copy; the installed artifacts are the
only dependency on the Registry checkout.

The example is outside the Registry's Maven reactor. CI builds a separate copy
after installing the Registry artifacts, so it also checks the external-project setup.

The application listens on port `8082` (`SERVER_PORT` overrides it). In another
terminal:

```bash
curl -fsS http://localhost:8082/q/health/ready

curl -i http://localhost:8082/records \
  -H 'Content-Type: application/ld+json' \
  --data-binary '{"id":"urn:example:one","type":"ProvenanceRecord","name":"Example"}'

curl -fsS http://localhost:8082/records/urn:example:one/document
```

The submission returns `201` with `recordStatus: INCOMPLETE`: the example document
intentionally omits required provenance fields. The document endpoint returns the
submitted bytes. The generic runtime supplies validation and the HTTP API.

The application sets a small document limit of 4096 bytes to demonstrate plugin
configuration. Override it before starting the application, for example:

```bash
EXAMPLE_MAX_DOCUMENT_BYTES=8192 java -jar examples/storage-plugin/application/target/quarkus-app/quarkus-run.jar
```

## Source map

| File or directory | Purpose |
|---|---|
| `plugin/pom.xml` | SPI dependency; TCK in test scope |
| `plugin/src/main/java/org/example/sourcelume/ExamplePluginProvider.java` | ServiceLoader entry point and provider configuration |
| `plugin/src/main/java/org/example/sourcelume/InMemoryPlugin.java` | Plugin lifecycle, conditional writes and facet search |
| `plugin/src/main/resources/META-INF/services/` | Provider registration |
| `plugin/src/test/java/org/example/sourcelume/ExamplePluginContractTest.java` | Runs the shared storage contracts against a fresh plugin for each test |
| `application/pom.xml` | Assembles the plugin and shared Quarkus runtime into a fast-jar |
| `application/src/main/resources/application.properties` | Selects the provider and declares its configuration keys |
| `application/src/test/java/org/example/sourcelume/PluginApplicationIT.java` | Tests the packaged application and verifies that backend and test libraries are not included accidentally |

Follow the [plugin development guide](../../docs/plugin-development.md) to replace
the map with your backend. Keep backend SDKs in the plugin and Quarkus dependencies
in the application module.
