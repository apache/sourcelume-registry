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
  ServiceLoader (see [docs/validation.md](docs/validation.md))
- **CDI-style runtime**, leaning Quarkus (see dev-list thread)

## Layout

| Module | Purpose |
|---|---|
| `sourcelume-registry-typedefs` | Backend type definitions (JSON resources) |
| `sourcelume-registry-common` | DTOs mapped from `sourcelume-spec`, Jakarta Validation annotations, exceptions, spec resource loader — framework-free (Jakarta API + Jackson only) |
| `sourcelume-registry-core` | Backend-neutral `AtlasAdapter` SPI (API) and the `RecordValidator` validation SPI — no framework, no backend SDK dependencies |
| `sourcelume-registry-validation-jsonschema` | Validation plugin: "json-schema" stage (networknt, draft 2020-12, format assertions) |
| `sourcelume-registry-validation-shacl` | Validation plugin: "shacl" stage (Apache Jena, offline context inlining) |
| `sourcelume-registry-runtime-quarkus` | Quarkus runtime: thin REST `AtlasAdapter` implementation over the core SPI, startup bootstrap (spec verification + typedef registration), SmallRye Health readiness, Docker packaging |

Runtime modules build on the framework-free core as separate modules and own
their framework dependencies (the Quarkus BOM is imported in the runtime
module only). Validation follows the same principle: the core defines the
`RecordValidator` chain, the plugin modules own their validation machinery
(networknt, Jena) — see [docs/validation.md](docs/validation.md) for the
architecture, the shipped stages, and the parity rationale against the
Python reference validator (`sourcelume-spec` `tools/validate.py`).

## Building

Requires JDK 21 and Maven 3.9+. The `sourcelume-spec` artifact must be
available (build `apache/sourcelume-spec` first with `mvn install`, see its
SETUP.md).

```bash
mvn clean install
```

## Running the Quarkus runtime

Against a local Atlas (default `http://localhost:21000`, see
`dev-support/README.md` for standing one up):

```bash
mvn -pl sourcelume-registry-runtime-quarkus quarkus:dev
```

Or as a container next to Atlas via the shared `sourcelume-network`:

```bash
mvn clean package
docker compose build
docker compose up -d
```

Readiness (Atlas connectivity) is exposed at
`http://localhost:8082/q/health/ready`; metrics at `/q/metrics`.
Configuration lives under the `sourcelume.atlas.*` properties
(`sourcelume-registry-runtime-quarkus/src/main/resources/application.properties`).

## Get involved

- Mailing list: dev@sourcelume.apache.org ([archives](https://lists.apache.org/list.html?dev@sourcelume.apache.org))
- ASF Slack: #sourcelume (Ask to be invited)

## License

This project is licensed under [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0).
