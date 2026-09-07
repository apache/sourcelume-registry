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
  `models/sourcelume/sourcelume_model.json` file loaded via one atomic
  `createAtlasTypeDefs` call. Not the full Sourcelume type set. (Atlas's own
  thousand-bucket directory numbering, e.g. `1000-Hadoop`, was deliberately not
  used here - see the module's `pom.xml` for why it solves a different problem.)
- `sourcelume-registry-ingest-worker` — a single runnable class, `WiringSpike`,
  that performs the four proof steps above and exits.

Everything else from the full architecture (`ingest-api`, `messaging`, `cache`,
`query-api`, `export`, the webapps) is intentionally left out of this thread.

## Assumptions to confirm before this will actually build

These are marked `TODO` inline in the POMs and Java source, but the important ones:

- **`sourcelume-spec` Maven coordinates.** The parent POM assumes
  `org.apache.sourcelume:sourcelume-spec:0.0.1-SNAPSHOT`, installed to your local
  `~/.m2` via `mvn install` in the `sourcelume-spec` repo. Update if the real
  coordinates differ.
- **The resource path inside that jar.** `WiringSpike` assumes it can find
  `context/v0.0.1/sourcelume.jsonld` on the classpath. If `sourcelume-spec` doesn't
  package its context/schema files as classpath resources yet, this step will need
  a different approach (e.g. reading from a published schema artifact instead).
- **Target Atlas version.** `atlas.version` in the parent POM is a placeholder
  (`2.5.0`). This is still an open decision per the Registry's architecture doc.
- **`AtlasClientV2` constructor/API shape.** Written from the well-known Atlas
  client usage pattern, but not compiled against a real Atlas dependency in this
  environment — verify against whichever Atlas version you land on.

## Running it (once the above are confirmed)

```bash
# from the sourcelume-spec repo, so it lands in your local Maven repo
mvn install

# from this steel-thread repo
mvn install
mvn -pl sourcelume-registry-ingest-worker exec:java \
  -Dexec.mainClass=org.apache.sourcelume.registry.ingest.worker.WiringSpike \
  -Dexec.args="http://localhost:21000"
```

Point `SOURCELUME_ATLAS_URL` / `SOURCELUME_ATLAS_USER` / `SOURCELUME_ATLAS_PASSWORD`
env vars (or the first CLI arg for the URL) at a running Atlas instance — a local
`docker-compose` Atlas is fine for this purpose.

## What "success" looks like

The process prints four lines confirming each proof step, ending with:

```
Steel thread complete: sourcelume-spec jar resource read, Sourcelume typedefs loaded, and Atlas accepted them.
```

If it gets that far, the wiring described in the Registry's high-level architecture is
real, not just a diagram.


## Overview

## Layout



## Get involved

- Mailing list: dev@sourcelume.apache.org ([archives](https://lists.apache.org/list.html?dev@sourcelume.apache.org))
- ASF Slack: #sourcelume (Ask to be invited)

## License

This project is licensed under [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0).
