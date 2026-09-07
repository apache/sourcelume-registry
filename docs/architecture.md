# Apache SourceLume Registry Architecture

> Status: design doc, tracking the current implementation. Update this alongside
> code changes — if a module's responsibility shifts, this file should shift with it.

## What the Registry is

Per the Sourcelume proposal: a reference implementation built on Apache Atlas,
exposing REST and GraphQL APIs so trainers and auditors can search, filter, and
query provenance records at scale.

It sits between two other Sourcelume deliverables:

- **Upstream**: `sourcelume-attest` signs provenance records before they ever
  reach the Registry. The Registry verifies signatures; it does not create them.
- **Downstream**: `sourcelume-explorer` is a pure client of this project's public
  REST/GraphQL APIs — it has no access to Atlas or any Registry internals beyond
  what those APIs expose.

## Design principles

1. **Write path and read path scale independently, and differently.**
   Ingest is bursty and write-heavy; queries are read-heavy and latency-sensitive
   for interactive use, but need a different shape entirely for bulk audits.
   These are pulled apart into separate modules rather than one "ingest" and one
   "api" module, so each can scale, deploy, and fail independently.

2. **Ingest is asynchronous by design.** A producer submitting a signed record
   should not be blocked on Atlas's write latency. The ingest API accepts and
   enqueues; a separate pool of workers does the actual verify/validate/load
   work, consuming from a queue.

3. **Provenance records are treated as append-only.** Once signed and accepted,
   a record doesn't change. This is a deliberate assumption, not an accident —
   it's what justifies fairly aggressive caching (long TTLs, cache-by-record-id)
   in front of Atlas without a complex invalidation strategy.

4. **Interactive search and bulk audit are different problems.** "Give me every
   record matching X" for an auditor can mean millions of rows — the wrong tool
   for pagination. Bulk requests go through an async job pattern (submit query →
   job id → poll → download) instead of the same paginated REST/GraphQL surface
   used for interactive search.

5. **GraphQL needs guardrails, not just a schema.** An open GraphQL endpoint over
   a graph database invites recursive, expensive queries (e.g. walking
   dataset → source → transform → source → ...). Depth limiting, query
   complexity scoring, and a dataloader pattern to avoid N+1 calls into Atlas
   are load-bearing parts of the design, not later hardening.

6. **The typedefs module owns the Sourcelume ↔ Atlas contract, and nothing else.**
   It has no logic and no dependents that flow backward into it. Everything else
   depends on it; it depends on nothing in this repo.

## Architecture

```
                        Producers
                            │
                            ▼
                     Ingest API  (stateless, horizontally scaled)
                            │  enqueue
                            ▼
                          Queue
                            │  consume
                            ▼
                    Ingest workers  (horizontally scaled)
                    verify signature → validate against
                    sourcelume-spec → load into Atlas
                            │
                            ▼
                    Apache Atlas core
                 (JanusGraph, HBase/Cassandra, Solr)
                       │              │
                       ▼              ▼
                 Cache layer     Bulk export jobs
                       │              │
                       ▼              ▼
              Stateless API tier (REST + GraphQL)
                       │              │
                       ▼              ▼
          Explorer, trainers    Auditors, bulk consumers
```

## Modules

| Module | Responsibility | Depends on |
|---|---|---|
| `sourcelume-registry-typedefs` | Atlas type-system definitions for Sourcelume entities. Resources only, no logic. | nothing in this repo |
| `sourcelume-registry-common` | Shared DTOs mapped from `sourcelume-spec`, shared exceptions/config. | `sourcelume-spec` |
| `sourcelume-registry-ingest-api` | Thin, stateless. Accepts signed records over HTTP, enqueues, ACKs. Does not touch Atlas. | `common`, `messaging` |
| `sourcelume-registry-ingest-worker` | Consumes the queue. Verifies signature, validates against spec, loads into Atlas. | `common`, `typedefs`, `messaging`, `sourcelume-spec`, `sourcelume-attest` |
| `sourcelume-registry-messaging` | Queue abstraction — interface + swappable backend impl. | nothing in this repo |
| `sourcelume-registry-cache` | Cache abstraction — interface + swappable backend impl, sits in front of Atlas reads. | nothing in this repo |
| `sourcelume-registry-query-api` | Stateless REST + GraphQL. Cache-aware, depth/complexity limited. | `common`, `cache` |
| `sourcelume-registry-export` | Async bulk-export job service for auditors. | `common`, `cache` |
| `sourcelume-registry-webapp-ingest` | Deployable assembly of `ingest-api`, scales independently of query traffic. | `ingest-api` |
| `sourcelume-registry-webapp-query` | Deployable assembly of `query-api`, scales independently of ingest traffic. | `query-api` |
| `sourcelume-registry-tests` | Integration tests spanning modules. | all of the above |

## Typedefs: why not Atlas's own numbering convention

Atlas's own `addons/models/` directory uses thousand-numbered buckets
(`0000-Area0`, `1000-Hadoop`, `2000-RDBMS`, `3000-Cloud`) with gapped numbering
inside each. That scheme solves a specific problem: sequencing independently-
evolving addon packages that Atlas's *server* loads one file at a time during
its own bootstrap, where a later file (e.g. Hive) may reference types defined
by an earlier one (e.g. a base filesystem type).

Sourcelume's typedefs don't go through that mechanism. `sourcelume-registry-ingest-worker`
reads its own bundled resource and calls Atlas's `createAtlasTypeDefs` API at
runtime, as one project we fully control — not as a file dropped into Atlas's
bootstrap directory alongside other teams' independently-versioned addons.

Because of that, all of Sourcelume's entity types (`Dataset`, `Source`,
`License`, `Transform`, `ProvenanceRecord`) live in a **single file**,
`models/sourcelume/sourcelume_model.json`, loaded via one atomic API call.
Cross-references between them (e.g. a `ProvenanceRecord` referencing a
`Dataset`) resolve within that one call — there's no cross-file load-order
problem to solve, so there's nothing for a numbering scheme to order.

The one piece of Atlas's convention that *is* kept: a `patches/` subdirectory,
for evolving a typedef after it's already live in a running Atlas instance
(e.g. adding an attribute to `sourcelume_dataset` in a later release). That's
a genuinely separate problem — schema migration, not bootstrap ordering — and
Atlas's own numbered-patch-file pattern is a reasonable fit for it.

## Current implementation status

Only a throwaway wiring spike exists so far (`WiringSpike`, in
`sourcelume-registry-ingest-worker`), proving that a locally-built
`sourcelume-spec` jar can be depended on, that the Sourcelume typedefs load and
are well-formed, and that this project can register them against a running
Atlas instance. It implements none of the real ingest logic described above
(no queue consumption, no signature verification, no schema validation) and is
expected to be deleted once real `ingest-worker` code lands.

None of `messaging`, `cache`, `query-api`, `export`, or the webapp assemblies
exist yet.

## Open questions

- **Target Atlas version.** Pins the parent POM's `atlas.version`, and by
  extension which `jackson-databind` version is safe to declare (Atlas's own
  transitive Jackson version should be matched or deferred to, to avoid a
  classpath conflict).
- **Queue technology.** Kafka is the natural ASF-native choice, and Atlas
  itself already uses Kafka internally for its notification bus — worth
  checking whether the Registry can reuse that instance rather than standing
  up a second one.
- **Cache technology.** Not yet chosen; the `RegistryCache` interface in
  `sourcelume-registry-cache` is designed so the choice can be deferred and
  swapped without touching calling code.
- **Does `query-api` talk to Atlas directly or only through the cache?** i.e.
  is the cache an explicit look-aside layer the API code calls, or a
  transparent proxy in front of Atlas's own REST endpoint?
- **`sourcelume-spec` Maven coordinates and resource layout.** Currently
  assumed (`org.apache.sourcelume:sourcelume-spec:0.0.1-SNAPSHOT`, bundling
  `context/v0.0.1/sourcelume.jsonld` on the classpath) but not confirmed
  against the real published artifact.
