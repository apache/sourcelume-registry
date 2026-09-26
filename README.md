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
- **CDI-style runtime**, leaning Quarkus (see dev-list thread)

## Layout

| Module | Purpose |
|---|---|
| `sourcelume-registry-typedefs` | Backend type definitions (JSON resources) |
| `sourcelume-registry-common` | DTOs mapped from `sourcelume-spec`, Jakarta Validation annotations, exceptions, spec resource loader — framework-free (Jakarta API + Jackson only) |
| `sourcelume-registry-core` | Backend-neutral `AtlasAdapter` SPI (API) — no framework, no backend SDK dependencies |

Runtime modules (for example a Quarkus runtime providing a thin REST
`AtlasAdapter` implementation, startup bootstrap, and health checks) build on
this core as separate modules.

## Building

Requires JDK 21 and Maven 3.9+. The `sourcelume-spec` artifact must be
available (build `apache/sourcelume-spec` first with `mvn install`, see its
SETUP.md).

```bash
mvn clean install
```

## Get involved

- Mailing list: dev@sourcelume.apache.org ([archives](https://lists.apache.org/list.html?dev@sourcelume.apache.org))
- ASF Slack: #sourcelume (Ask to be invited)

## License

This project is licensed under [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0).
