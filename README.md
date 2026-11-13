# Apache Sourcelume Registry (`sourcelume-registry`)

## Overview

The Apache Sourcelume Registry persists, indexes, and queries signed Sourcelume
provenance records for AI training datasets.

Per the dev-list consensus (September 2026, thread "Apache Sourcelume Registry
repo creation, and initial discussion") the codebase is organized as a
**framework-independent Core/API** with **Runtime modules** layered on top:

- **Java 21 baseline** (latest LTS)
- **Adapter isolation**: backend operations behind a backend-neutral
  `AtlasAdapter` SPI; only Sourcelume types in the interface, no backend SDK
  types in the core
- **Pluggable record validation**: a `RecordValidator` SPI in the core;
  validation stages ship as separate plugin modules discovered via
  ServiceLoader, wired into the Quarkus runtime (fail-on-start) and exposed
  as a pre-flight `POST /records/validate` endpoint (see
  [docs/validation.md](docs/validation.md)). The spec's record types are
  supported end to end: `ProvenanceRecord` persists as `sourcelume_dataset`,
  `TrainingProvenanceRecord` as `sourcelume_model` (see
  [docs/ingest.md](docs/ingest.md))
- **CDI-style runtime**, leaning Quarkus (see dev-list thread)

## Layout

| Module | Purpose |
|---|---|
| `sourcelume-registry-typedefs` | Backend type definitions (JSON resources) |
| `sourcelume-registry-spi` | JDK-only metadata storage contracts and immutable value types |
| `sourcelume-registry-spi-tck` | Reusable plugin contract tests and an in-memory test backend; test scope only |
| `sourcelume-registry-common` | DTOs mapped from `sourcelume-spec`, Jakarta Validation annotations, exceptions, spec resource loader — framework-free (Jakarta API + Jackson only) |
| `sourcelume-registry-core` | Framework-free ingest policy (`DefaultIngestService`), validation SPI (`RecordValidator`) and storage plugin selection (`PluginLoader`) |
| `sourcelume-registry-validation-jsonschema` | Validation plugin: "json-schema" stage (networknt, draft 2020-12, format assertions) |
| `sourcelume-registry-validation-shacl` | Validation plugin: "shacl" stage (Apache Jena, offline context inlining) |
| `sourcelume-registry-atlas-adapter` | Atlas storage plugin, REST transport and mapping to the existing dataset/model entities; owns its schema initialization |
| `sourcelume-registry-runtime-quarkus` | Reusable Quarkus runtime JAR: record API, validation, SPI configuration, initialization, health and shutdown; no storage implementation dependency |
| `sourcelume-registry-distribution-atlas` | Atlas-specific dataset/type HTTP routes and error mapping; assembles the runtime and Atlas storage plugin into a runnable Quarkus application and Docker image |
| `tests/storage-distribution` | Test-only packaged application proving the record API runs without Atlas libraries or type definitions |

Runtime modules build on the framework-free core as separate modules and own
their framework dependencies (the Quarkus BOM is imported in the runtime
and distribution modules). Validation follows the same principle: the core defines the
`RecordValidator` chain, the plugin modules own their validation machinery
(networknt, Jena) — see [docs/validation.md](docs/validation.md) for the
architecture, the shipped stages, and the parity rationale against the
Python reference validator (`sourcelume-spec` `tools/validate.py`).

The storage interfaces, entry points, Atlas mapping and operational assumptions
are described in [docs/storage-spi.md](docs/storage-spi.md).

## Building

Requires JDK 21 and Maven 3.9+. The `sourcelume-spec` artifact must be
available (build `apache/sourcelume-spec` first with `mvn install`, see its
SETUP.md).

```bash
mvn clean install
```

On Windows, see the "Running on Windows" section of
`dev-support/README.md` for WSL2/Git Bash/PowerShell guidance.

## Running the Quarkus runtime

Against a local Atlas (default `http://localhost:21000`, see
`dev-support/README.md` for standing one up):

```bash
mvn -pl sourcelume-registry-distribution-atlas quarkus:dev
```

Or as a container next to Atlas via the shared `sourcelume-network`:

```bash
mvn clean package
docker compose build
docker compose up -d
```

### Health & Observability

- Readiness (selected storage plugin and initialization): `http://localhost:8082/q/health/ready`
- Liveness probe: `http://localhost:8082/q/health/live`
- Prometheus metrics: `http://localhost:8082/q/metrics`
- OpenAPI document: `http://localhost:8082/q/openapi`
- Swagger UI: `http://localhost:8082/q/swagger-ui`

Each distribution selects a packaged provider with `sourcelume.storage.provider`.
The Atlas distribution defaults to `atlas`; the reusable runtime has no backend default.
Atlas connection settings remain under `sourcelume.atlas.*`
(`sourcelume-registry-distribution-atlas/src/main/resources/application.properties`).

### REST API Endpoints

The Atlas distribution exposes these REST endpoints on port `8082`:

#### Datasets (`/api/v1/datasets`)

Read-only views. A record is written only via `POST /records`; the dataset endpoints read what it stored.

```bash
# Publish a dataset record (the only way to write one)
curl -s -X POST http://localhost:8082/records \
  -H "Content-Type: application/ld+json" \
  --data-binary @examples/simple-demo/sample-provenance-record.jsonld | jq .

# Query by qualifiedName (the record's id; query parameter)
curl -s "http://localhost:8082/api/v1/datasets?qualifiedName=https://sourcelume.apache.org/records/rec-glasgow-hackathon-001" | jq .

# Query by internal Atlas GUID
curl -s "http://localhost:8082/api/v1/datasets/guid/<ASSIGNED_GUID>" | jq .
```

#### Types & Models (`/api/v1/types`)

```bash
# Inspect all registered Sourcelume typedefs
curl -s http://localhost:8082/api/v1/types/typedefs | jq .

# Inspect the sourcelume_dataset entity definition
curl -s http://localhost:8082/api/v1/types/dataset | jq .

# Inspect the sourcelume_model entity definition
curl -s http://localhost:8082/api/v1/types/model | jq .
```

## Examples

- **Simple Demo (`examples/simple-demo`)**: A self-contained, automated walkthrough (referencing the Community Over Code Glasgow 2026 Hackathon) demonstrating registry health verification, Atlas typedef inspection, dataset registration, and query resolution. See [examples/simple-demo/README.md](examples/simple-demo/README.md).

## Get involved

- Mailing list: dev@sourcelume.apache.org ([archives](https://lists.apache.org/list.html?dev@sourcelume.apache.org))
- ASF Slack: #sourcelume (Ask to be invited)

## License

This project is licensed under [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0).
