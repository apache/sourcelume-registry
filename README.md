# Apache Sourcelume Registry (`sourcelume-registry`)

## Overview

The Apache Sourcelume Registry persists, indexes, and queries signed Sourcelume
provenance records for AI training datasets.

Per the dev-list consensus (September 2026, thread "Apache Sourcelume Registry
repo creation, and initial discussion") the codebase is organized as a
**framework-independent Core/API** with **Runtime modules** layered on top:

- **Java 21 baseline**
- **Pluggable storage**: a JDK-only `RegistryPluginProvider` SPI, with
  PostgreSQL as the default distribution and Apache Atlas as an alternative;
  no backend SDK types in the core
- **Pluggable record validation**: a `RecordValidator` SPI in the core;
  validation stages ship as separate plugin modules discovered via
  ServiceLoader, wired into the Quarkus runtime (fail-on-start) and exposed
  as a pre-flight `POST /records/validate` endpoint (see
  [docs/validation.md](docs/validation.md)). The spec's `ProvenanceRecord` and
  `TrainingProvenanceRecord` types are supported end to end (see
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
| `sourcelume-registry-postgres` | PostgreSQL storage plugin, JDBC persistence and schema migrations |
| `sourcelume-registry-runtime-quarkus` | Reusable Quarkus runtime JAR: record API, validation, SPI configuration, initialization, health and shutdown; no storage implementation dependency |
| `sourcelume-registry-distribution-postgres` | Default runnable Quarkus application and Docker image: shared runtime plus PostgreSQL storage plugin |
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

Requires JDK 21, Maven 3.9+, and Docker for PostgreSQL tests. The `sourcelume-spec` artifact must be
available (build `apache/sourcelume-spec` first with `mvn install`, see its
SETUP.md).

```bash
mvn clean install
```

On Windows, see the "Running on Windows" section of
`dev-support/README.md` for WSL2/Git Bash/PowerShell guidance.

## Running the Quarkus runtime

The default distribution stores records directly in PostgreSQL. After building,
start both services from the repository root:

```bash
docker compose up -d --build --wait
```

The API is available at `http://localhost:8082`. PostgreSQL data is kept in the
`registry-data` named volume across container recreation. `docker compose down`
keeps the data; `docker compose down -v` deletes it. The Compose setup uses local
development credentials (`sourcelume`); set `SOURCELUME_POSTGRES_PASSWORD` before
the first startup to use a different password.

For host or IDE development, start only the development database:

```bash
docker compose -f dev-support/docker-compose.postgres.yml up -d --wait
mvn -pl sourcelume-registry-distribution-postgres quarkus:dev
```

The distribution defaults to `jdbc:postgresql://localhost:55432/sourcelume_registry`,
with username and password `sourcelume`. Override these with
`SOURCELUME_POSTGRES_JDBC_URL`, `SOURCELUME_POSTGRES_USERNAME`, and
`SOURCELUME_POSTGRES_PASSWORD`. Schema migrations run automatically at startup.

### Apache Atlas distribution

To use Atlas, follow [dev-support/README.md](dev-support/README.md) to start Atlas,
then run `mvn -pl sourcelume-registry-distribution-atlas quarkus:dev`.
For the container setup on the shared `sourcelume-network`, use the separate
Compose file from the repository root:

```bash
docker compose -f docker-compose.atlas.yml up -d --build --wait
```

### Health & Observability

- Readiness (selected storage plugin and initialization): `http://localhost:8082/q/health/ready`
- Liveness probe: `http://localhost:8082/q/health/live`
- Prometheus metrics: `http://localhost:8082/q/metrics`
- OpenAPI document: `http://localhost:8082/q/openapi`
- Swagger UI: `http://localhost:8082/q/swagger-ui`

Each distribution selects a packaged provider with `sourcelume.storage.provider`.
The PostgreSQL distribution defaults to `postgres` and requires atomic writes;
the Atlas distribution defaults to `atlas`. The reusable runtime has no backend
default. Each distribution owns its provider connection settings.

### REST API Endpoints

Both distributions expose the same record API on port `8082`:

| Endpoint | Purpose |
|---|---|
| `POST /records/validate` | Validate a record without storing it |
| `POST /records` | Submit a record or correct an incomplete record |
| `GET /records/{id}` | Read a record and its validation status |
| `GET /records/{id}/document` | Retrieve the exact submitted bytes and SHA-256 ETag |

For example, submit a record and use the returned `Location` header to read it:

```bash
curl -i -X POST http://localhost:8082/records \
  -H "Content-Type: application/ld+json" \
  --data-binary @examples/simple-demo/sample-provenance-record.jsonld
```

Encode the complete record ID as a path segment when constructing a read URL.
See [docs/ingest.md](docs/ingest.md) for the record lifecycle.

The following catalog endpoints are specific to the Atlas distribution:

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

- **Atlas Demo (`examples/simple-demo`)**: A self-contained, automated walkthrough (referencing the Community Over Code Glasgow 2026 Hackathon) demonstrating registry health verification, Atlas typedef inspection, dataset registration, and query resolution. See [examples/simple-demo/README.md](examples/simple-demo/README.md).

## Get involved

- Mailing list: dev@sourcelume.apache.org ([archives](https://lists.apache.org/list.html?dev@sourcelume.apache.org))
- ASF Slack: #sourcelume (Ask to be invited)

## License

This project is licensed under [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0).
