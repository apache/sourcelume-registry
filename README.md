# Apache Sourcelume Registry (`sourcelume-registry`)

This is a deliberately minimal slice through the Registry architecture. It does not
implement any Sourcelume functionality (no signature verification, no schema validation,
no queue, no REST/GraphQL). Its only job is to prove three pieces of wiring connect:

1. A locally-built `sourcelume-spec` jar can be depended on and its resources read.
2. Atlas typedefs bundled in `sourcelume-registry-typedefs` can be loaded and are
   well-formed.
3. This project can talk to a running Apache Atlas instance and successfully register
   those typedefs.

## Modules included

- `sourcelume-registry-typedefs` — one minimal entity type, `sourcelume_dataset`,
  extending Atlas's built-in `DataSet` type, defined in a single
  `models/sourcelume/sourcelume_model.json` file loaded via atomic
  `createAtlasTypeDefs` call.
- `sourcelume-registry-common` — shared DTOs (`ProvenanceRecordDto`, `CreatorDto`, `CustodyEventDto`, `LicenseHistoryDto`, `SourcelumeDatasetDto`), domain exceptions, and schema resource loaders.
- `sourcelume-registry-atlas-adapter` — encapsulates `AtlasClientV2` interactions behind the `AtlasAdapter` domain interface (enforcing architecture decision D-002) with Spring Boot auto-configuration.
- `sourcelume-registry-ingest-worker` — Spring Boot 3 worker runner, Actuator health indicator, and `AtlasBootstrapRunner` verifying spec resources and registering typedefs with Atlas.

See [docs/architecture.md](docs/architecture.md) for the full module design and [docs/deployment.md](docs/deployment.md) for how runners are deployed.

## Running it

### Option 1: Running Spring Boot on Host

```bash
# from this repository
mvn clean package

# Run Spring Boot ingest worker pointing to local Atlas
export SOURCELUME_ATLAS_URL=http://localhost:21000
mvn -pl sourcelume-registry-ingest-worker spring-boot:run
```

### Option 2: Running with Docker Compose

```bash
# 1. Build project artifacts (creates target/*.jar)
mvn clean package

# 2. Build and run worker container
docker compose build
docker compose up
```

Point `SOURCELUME_ATLAS_URL` / `SOURCELUME_ATLAS_USER` / `SOURCELUME_ATLAS_PASSWORD`
env vars at a running Atlas instance — see `dev-support/README.md` for standing up the local Atlas backend.

## Local Dev Support (Apache Atlas)

See [`dev-support/README.md`](dev-support/README.md) for complete instructions on standing up the local Apache Atlas backend using Docker Compose and testing the Spring Boot runtime.


## Overview

## Layout



## Get involved

- Mailing list: dev@sourcelume.apache.org ([archives](https://lists.apache.org/list.html?dev@sourcelume.apache.org))
- ASF Slack: #sourcelume (Ask to be invited)

## License

This project is licensed under [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0).
