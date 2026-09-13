# Apache Sourcelume Registry Architecture

> Status: design doc, tracking the current implementation. Update this alongside
> code changes — if a module's responsibility shifts, this file should shift with it.
>
> Scope of this document: **what the modules are, what they are allowed to depend
> on, and why.** Runtime placement, container images, scaling and orchestration
> live in [deployment.md](deployment.md); that document treats this one as the
> source of truth for module responsibilities and dependency rules.
>
> Spelling note: the project is `Sourcelume` (matching the Maven artifacts in
> `pom.xml`). Not `SourceLume`.

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

### What "done" means for the first POC

The near-term goal is not the full design below — it is a POC-quality but
end-to-end-honest slice that our community can build. Concretely:

1. A signed record can be POSTed to the ingest API and is acknowledged without
   waiting on Atlas.
2. A worker consumes it, verifies the signature, validates it against
   `sourcelume-spec`, and loads it into Atlas.
3. That record can be read back through the query API by id, and through one
   non-trivial GraphQL traversal.
4. All three run as separate JVMs from one `docker compose up`, against the
   local Atlas in [`dev-support/`](../dev-support/README.md).

Bulk export, Redis, Swarm/Kubernetes, and autoscaling are explicitly **not**
POC scope. They are designed for here so the POC does not paint itself into a
corner, and are marked as such in the module tables.

## Design principles

1. **Write path and read path scale independently, and differently.**
   Ingest is bursty and write-heavy; queries are read-heavy and latency-sensitive
   for interactive use, but need a different shape entirely for bulk audits.
   These are pulled apart into separate Spring Boot executable services rather than a monolithic
   application, so each can scale, deploy, and fail independently across distinct JVMs.

2. **Ingest is asynchronous by design.** A producer submitting a signed record
   should not be blocked on Atlas's write latency. The ingest API accepts and
   enqueues via Spring Kafka; a separate pool of headless worker instances does
   the actual verify/validate/load work, consuming from the queue.

3. **Provenance records are treated as append-only.** Once signed and accepted,
   a record doesn't change. This is a deliberate assumption, not an accident —
   it is what justifies caching by record id in front of Atlas via Spring's Cache
   Abstraction (`@Cacheable`). Note that the assumption holds for *records*, not
   for everything a query can return; see
   [Open question 6](#6-what-is-actually-cacheable-and-how-does-a-cache-get-purged).

4. **Interactive search and bulk audit are different problems.** "Give me every
   record matching X" for an auditor can mean millions of rows — the wrong tool
   for pagination. Bulk requests go through an asynchronous job pattern via Spring Batch
   (submit query → job id → poll/notify → download) instead of the same
   paginated REST/GraphQL surface used for interactive search.

5. **GraphQL needs guardrails, not just a schema.** An open GraphQL endpoint over
   a graph database invites recursive, expensive queries (e.g. walking
   dataset → source → transform → source → ...). Depth limiting, query complexity
   scoring, and batch loading via `@BatchMapping` / `DataLoaderRegistry` to
   eliminate N+1 calls into Atlas are foundational, not later hardening. The
   actual numeric budgets are still open —
   [Open question 7](#7-graphql-budgets-and-pagination-limits).

6. **The Atlas client is contained, not spread.** The Apache Atlas Java client is
   a heavy, Jersey-1.x/`javax.ws.rs`-era dependency. Exactly one module is
   allowed to depend on it, and that module is never on the query or web path.
   See [The Atlas access boundary](#the-atlas-access-boundary) — this is the
   single most load-bearing dependency rule in the project.

7. **Standardized production observability and lifecycle.** All deployable services
   expose Spring Boot Actuator liveness/readiness probes and Micrometer metrics,
   on a **separate management port** that is never published publicly. The
   required configuration is not free by default; it is specified in
   [deployment.md §7](deployment.md#7-observability-and-health-management).

8. **The typedefs module owns the Sourcelume ↔ Atlas contract, and nothing else.**
   It has no logic and no dependents that flow backward into it. Everything else
   depends on it; it depends on nothing in this repo.

## Architecture

```
                        Producers
                            │  POST signed record (REST)
                            ▼
              app-ingest    (stateless, horizontally scaled)
                            │  enqueue (Kafka)
                            ▼
                      Kafka topic
                            │  consume (Kafka)
                            ▼
              app-worker    (horizontally scaled, headless)
              verify signature → validate against
              sourcelume-spec → load into Atlas
                            │  writes, via atlas-adapter (atlas-client-v2)
                            ▼
                    Apache Atlas core
                 (JanusGraph, HBase/Cassandra, Solr)
                       ▲              ▲
     reads, via        │              │  reads, via atlas-adapter
     atlas-rest        │              │  (atlas-client-v2)
     (RestClient)      │              │
                       │              │
              app-query│              │app-export
        ┌──────────────┴───┐      ┌───┴──────────────┐
        │ REST + GraphQL   │      │ Spring Batch job │
        │ look-aside cache │      │ submit/poll/     │
        │ (Caffeine/Redis) │      │ download         │
        └──────────┬───────┘      └───┬──────────────┘
                   │                  │ writes artifact
                   ▼                  ▼
        Explorer, trainers      Artifact store ──▶ Auditors
```

Two things this diagram is deliberate about, because earlier revisions of it
were not:

- **The cache is not a stage in front of Atlas.** It is a look-aside cache
  *owned by* `app-query`; Atlas does not push into it. Nothing else reads it.
- **`app-export` does not feed `app-query`.** It reads Atlas and writes archive
  artifacts that auditors fetch. Where those artifacts live is
  [Open question 3](#3-where-do-export-artifacts-live-and-who-serves-them).

## The Atlas access boundary

**Decision (see [D-002](#d-002-contain-the-atlas-client-in-a-single-adapter-module)):**
`org.apache.atlas:atlas-client-v2` and `org.apache.atlas:atlas-intg` may appear
in exactly **one** module, `sourcelume-registry-atlas-adapter`. That module is a
dependency of the write/batch path only (`ingest-worker`, `export`). It must
never appear — directly or transitively — on the classpath of `query-api`,
`app-ingest`, or `app-query`.

The query path reads Atlas through `sourcelume-registry-atlas-rest`: a thin
read-only client over Atlas's HTTP REST API using Spring's `RestClient` and
hand-written DTOs, with **zero** Atlas artifacts on the classpath.

### Why

`atlas-client-v2` (2.x) is built on Jersey 1.x / `javax.ws.rs` and drags in
Hadoop-era transitives. It generally *can* coexist with Spring Boot 3 — it is a
client, not a servlet app — but:

- it cannot be "excluded into" Jakarta compliance; the `javax.ws.rs` namespace
  is compiled in;
- its Jackson expectations skew against the version Spring Boot manages;
- putting it behind a public web tier means every one of those transitives is in
  the blast radius of anything internet-facing.

Containing it means the hairball exists on two headless, private, non-web JVMs,
and the two public web JVMs stay on a clean Boot 3 classpath. It also shrinks
the "which Atlas version do we target" question
([Open question 8](#8-target-atlas-version)) to one module.

### The exclusion set

This is the concrete, reviewable form of the rule. It belongs in
`sourcelume-registry-atlas-adapter/pom.xml`:

```xml
<dependency>
  <groupId>org.apache.atlas</groupId>
  <artifactId>atlas-client-v2</artifactId>
  <version>${atlas.version}</version>
  <exclusions>
    <!-- Logging: route everything through Boot's slf4j + logback. -->
    <exclusion><groupId>org.slf4j</groupId><artifactId>slf4j-log4j12</artifactId></exclusion>
    <exclusion><groupId>log4j</groupId><artifactId>log4j</artifactId></exclusion>
    <exclusion><groupId>org.apache.logging.log4j</groupId><artifactId>log4j-slf4j-impl</artifactId></exclusion>
    <exclusion><groupId>commons-logging</groupId><artifactId>commons-logging</artifactId></exclusion>

    <!-- Servlet/JAX-RS server-side bits a client has no business shipping. -->
    <exclusion><groupId>javax.servlet</groupId><artifactId>servlet-api</artifactId></exclusion>
    <exclusion><groupId>javax.servlet</groupId><artifactId>javax.servlet-api</artifactId></exclusion>
    <exclusion><groupId>com.sun.jersey</groupId><artifactId>jersey-server</artifactId></exclusion>
    <exclusion><groupId>com.sun.jersey.contribs</groupId><artifactId>jersey-multipart</artifactId></exclusion>

    <!-- Hadoop ecosystem: pulled in by atlas-intg, unused by a REST client. -->
    <exclusion><groupId>org.apache.hadoop</groupId><artifactId>hadoop-common</artifactId></exclusion>
    <exclusion><groupId>org.apache.hadoop</groupId><artifactId>hadoop-hdfs</artifactId></exclusion>
    <exclusion><groupId>org.apache.hadoop</groupId><artifactId>hadoop-annotations</artifactId></exclusion>

    <!-- Let the Spring Boot BOM own Jackson's version, not Atlas. -->
    <exclusion><groupId>com.fasterxml.jackson.core</groupId><artifactId>jackson-core</artifactId></exclusion>
    <exclusion><groupId>com.fasterxml.jackson.core</groupId><artifactId>jackson-databind</artifactId></exclusion>
    <exclusion><groupId>com.fasterxml.jackson.core</groupId><artifactId>jackson-annotations</artifactId></exclusion>
    <exclusion><groupId>com.fasterxml.jackson.jaxrs</groupId><artifactId>jackson-jaxrs-base</artifactId></exclusion>
    <exclusion><groupId>com.fasterxml.jackson.jaxrs</groupId><artifactId>jackson-jaxrs-json-provider</artifactId></exclusion>
  </exclusions>
</dependency>
```

Notes a reviewer should hold us to:

- `com.sun.jersey:jersey-client` and `jersey-core` are **intentionally not
  excluded** — `AtlasClientV2` needs them at runtime. They are the reason the
  adapter module exists as a quarantine rather than as a style preference.
- The list above is a starting point derived from Atlas 2.x's published
  dependency graph. It **must be validated** against a real
  `mvn -pl sourcelume-registry-atlas-adapter dependency:tree` once the module
  exists, and corrected in this file when it is. Until then treat it as
  reviewed-but-unverified.

### Enforcing it in the build

A rule nobody can see is a rule that leaks back in on the first convenient
afternoon. The root `pom.xml` should fail the build if `atlas-client-v2` shows
up anywhere it shouldn't, via `maven-enforcer-plugin`:

```xml
<!-- In sourcelume-registry-query-api, app-ingest, app-query -->
<rules>
  <bannedDependencies>
    <searchTransitive>true</searchTransitive>
    <excludes>
      <exclude>org.apache.atlas:*</exclude>
      <exclude>com.sun.jersey:*</exclude>
      <exclude>javax.ws.rs:*</exclude>
    </excludes>
  </bannedDependencies>
</rules>
```

## Modules and runtime packaging

The project uses **Spring Boot 3.5.x** on **Java 17** (matching
`maven.compiler.release` in `pom.xml`) as the unified runtime. Boot 4 / Java 21
is a future decision, not a current one.

Two kinds of module, and the distinction is strict:

- **Library modules** produce a plain jar. They are never deployed and never
  contain a `main`.
- **Deployable modules** are thin `app-*` runners: a `@SpringBootApplication`
  class, configuration, and `spring-boot-maven-plugin`. Close to no logic.
  Every deployable is an `app-*` module — including the worker and the exporter,
  which earlier revisions of this doc inconsistently deployed directly.

### Library modules

| Module | Responsibility | Key dependencies | POC? |
|---|---|---|---|
| `sourcelume-registry-typedefs` | Atlas type-system definitions for Sourcelume entities. Pure JSON resources, no logic. | *none in this repo* | yes |
| `sourcelume-registry-common` | Shared DTOs mapped from `sourcelume-spec`, validation annotations (`jakarta.validation`), exceptions, config models. | `sourcelume-spec`, `spring-boot-starter-validation` | yes |
| `sourcelume-registry-atlas-adapter` | **The only module permitted to use `atlas-client-v2`/`atlas-intg`.** Wraps typedef bootstrap and entity writes/reads behind a narrow Sourcelume-facing interface. Write/batch path only. | `typedefs`, `common`, `atlas-client-v2` (see [exclusion set](#the-exclusion-set)) | yes |
| `sourcelume-registry-atlas-rest` | Read-only Atlas access over Atlas's HTTP REST API via Spring `RestClient`, hand-written DTOs. No Atlas artifacts. Used by the query path. | `common`, `spring-boot-starter-web` (`RestClient`) | yes |
| `sourcelume-registry-messaging` | Kafka producer/consumer abstraction, topic naming, serialization, DLT wiring. | `common`, `spring-kafka` | yes |
| `sourcelume-registry-ingest-api` | Stateless HTTP ingest endpoint. Fast structural validation, enqueue, immediate ack. | `common`, `messaging`, `spring-boot-starter-web` | yes |
| `sourcelume-registry-ingest-worker` | Queue consumer logic: signature verification, spec/SHACL validation, Atlas load. | `common`, `messaging`, `atlas-adapter`, `sourcelume-spec`, `sourcelume-attest` | yes |
| `sourcelume-registry-cache` | Look-aside cache configuration over Spring Cache. Caffeine locally, Redis for multi-replica. | `spring-boot-starter-cache`, Caffeine / `spring-boot-starter-data-redis` | Caffeine only |
| `sourcelume-registry-query-api` | REST + Spring for GraphQL query interface. Batch data loaders, depth/complexity instrumentation, cache-backed reads. **No Atlas client.** | `common`, `cache`, `atlas-rest`, `spring-boot-starter-graphql`, `spring-boot-starter-web` | yes |
| `sourcelume-registry-export` | Asynchronous bulk audit export jobs (Spring Batch). Reads via `atlas-adapter`, writes archive artifacts. | `common`, `atlas-adapter`, `spring-boot-starter-batch` | no |

### Deployable modules

| Module | Packages | Web? | Notes |
|---|---|---|---|
| `sourcelume-registry-app-ingest` | `ingest-api`, `messaging` | yes (public) | Stateless. Must not resolve any Atlas artifact — enforced by `bannedDependencies`. |
| `sourcelume-registry-app-worker` | `ingest-worker`, `messaging`, `atlas-adapter` | no | Headless. Carries the Atlas client. Private network only. |
| `sourcelume-registry-app-query` | `query-api`, `cache`, `atlas-rest` | yes (public) | Stateless, cache-backed. Must not resolve any Atlas artifact. |
| `sourcelume-registry-app-export` | `export`, `atlas-adapter` | job-triggered | Headless batch. Carries the Atlas client. Needs a `JobRepository` datastore — [Open question 2](#2-spring-batch-needs-a-jobrepository-database). Post-POC. |
| `sourcelume-registry-tests` | — | — | Integration/E2E suite against containerized Atlas + Kafka (Testcontainers). |

`app-export` being its own runner — rather than being folded into `app-query` —
is deliberate, and is the whole point of [D-003](#d-003-app-export-is-its-own-runner).

## Spring Boot runtime analysis

Adopting Spring Boot 3.x replaces a meaningful amount of custom plumbing with
standard abstractions. Mapped against the design principles:

- **Decoupled executable packaging (P1).** Self-contained executable jars per
  deployable, each with only its own dependencies, each scaling on its own JVM.
  This is also what makes P6's containment enforceable: the two web jars simply
  do not contain the Atlas client.
- **Asynchronous ingest (P2).** Spring Kafka (`@KafkaListener`, `KafkaTemplate`)
  brings consumer group management, retry/backoff, and dead-letter topics
  without hand-rolling them. Delivery semantics still need deciding —
  [Open question 4](#4-ingest-correctness-semantics).
- **Caching (P3).** Spring's Cache Abstraction (`@Cacheable`, `CacheManager`)
  lets Caffeine (single node, POC) and Redis (multi-replica) swap by
  configuration with no code change.
- **Bulk export (P4).** Spring Batch handles job lifecycle
  (submitted → processing → completed → downloadable) without holding HTTP
  connections open. It does, however, require a real database — that is a cost,
  not a freebie.
- **GraphQL guardrails (P5).** Spring for GraphQL gives `@BatchMapping` /
  `DataLoaderRegistry` for the N+1 traversal problem, and
  `WebGraphQlInterceptor` / `Instrumentation` hooks for depth and complexity
  limits before execution begins.
- **Atlas containment (P6).** Spring's `RestClient` is what makes
  `atlas-rest` cheap enough to be worth writing, and therefore what makes the
  containment rule practical rather than aspirational.
- **Observability (P7).** Actuator + Micrometer, on a private management port.

### Dependency management

- The root `pom.xml` **will** import the `spring-boot-dependencies` BOM into
  `<dependencyManagement>`, which then owns the Jackson/SLF4J/Netty/Jakarta
  versions. **This has not happened yet** — the current `pom.xml` pins
  `jackson.version=2.12.7` by hand and imports no BOM. Treat the BOM as
  *planned*.
- After the BOM lands, the hand-pinned `jackson.version` property should be
  deleted, not raised, so there is one owner of that version.
- Container images: see
  [deployment.md §4](deployment.md#4-containerization-strategy--12-factor-configuration).
  The choice between buildpacks and a Dockerfile is not cosmetic — it decides
  whether container-level healthchecks can work at all.

## Decisions

Recorded so reviewers can tell what is settled from what is open. Anything not
in this list and not in [Open questions](#open-questions) is simply undecided by
omission — say so on the list rather than assuming.

### D-001: Spring Boot 3.x as the runtime

**Status:** accepted, 2026-09-13 · **Supersedes:** the original servlet/WAR
`webapp-*` sketch.

Rejected alternatives:

- **Quarkus / Micronaut.** Faster startup and lower footprint, and both would
  work. Rejected on contributor-availability grounds: Spring is the stack the
  largest share of the community can already review, and none of the services
  are startup-latency-sensitive (they are long-lived servers and batch jobs).
- **Plain Jetty + JAX-RS, closer to Atlas's own stack.** Rejected because it
  would pull the project *toward* the Jersey-1.x world we are deliberately
  quarantining in D-002, and we would hand-build caching, batch jobs, GraphQL
  instrumentation and health probes.
- Revisit trigger: if the Boot 3 / Atlas client coexistence in `atlas-adapter`
  turns out to be unworkable in practice.

### D-002: Contain the Atlas client in a single adapter module

**Status:** accepted, 2026-09-13.

`atlas-client-v2` lives only in `sourcelume-registry-atlas-adapter`, used only
by the write/batch path, never on the query/web path. The exclusion set and the
enforcer rule are specified in
[The Atlas access boundary](#the-atlas-access-boundary).

Rejected alternative: **drop the Atlas client entirely** and talk to Atlas's
REST API directly everywhere. Cleaner dependency-wise, and we do exactly this
on the read path via `atlas-rest`. Rejected for the write path for POC scope:
typedef registration and entity mutation are where the client's model classes
and validation earn their keep, and hand-writing those DTOs is real work with
real room for error. The boundary is drawn such that this decision can be
revisited later by reimplementing one module.

### D-003: `app-export` is its own runner

**Status:** accepted, 2026-09-13 · **Resolves** the contradiction where the
module table folded `export` into `app-query` while the deployment doc gave it
its own container and JVM.

Bulk export exists precisely so that multi-gigabyte audit extracts cannot
disturb interactive query latency. Packaging it inside `app-query` would put
them back in the same heap and the same GC, defeating the reason for having it.
It gets its own deployable, its own resource profile, and its own fault domain.

### D-004: Every deployable is an `app-*` runner

**Status:** accepted, 2026-09-13.

Previously `ingest-api`/`query-api` were wrapped in runners while
`ingest-worker` and `export` were deployed directly — making `ingest-worker`
simultaneously a library and a container image. Now uniform: logic lives in
library modules, `app-*` modules only assemble and configure. The cost is two
extra near-empty modules; the benefit is one packaging rule with no exceptions
to remember.

## Typedefs: why not Atlas's own numbering convention

Atlas's own `addons/models/` directory uses thousand-numbered buckets
(`0000-Area0`, `1000-Hadoop`, `2000-RDBMS`, `3000-Cloud`) with gapped numbering
inside each. That scheme solves a specific problem: sequencing independently-
evolving addon packages that Atlas's *server* loads one file at a time during
its own bootstrap, where a later file (e.g. Hive) may reference types defined
by an earlier one (e.g. a base filesystem type).

Sourcelume's typedefs don't go through that mechanism. The Registry reads its
own bundled resource and calls Atlas's `createAtlasTypeDefs` API at runtime, as
one project we fully control — not as a file dropped into Atlas's bootstrap
directory alongside other teams' independently-versioned addons.

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

Note that `patches/` is also what makes "records are append-only" narrower than
it sounds; see [Open question 6](#6-what-is-actually-cacheable-and-how-does-a-cache-get-purged).

## Current implementation status

What exists in the repo right now, as distinct from what is designed above:

- `sourcelume-registry-typedefs` — one minimal entity type,
  `sourcelume_dataset`.
- `sourcelume-registry-ingest-worker` — a throwaway `WiringSpike` proving that a
  locally-built `sourcelume-spec` jar can be depended on, that the typedefs load
  and are well-formed, and that this project can register them against a running
  Atlas instance. It talks to `AtlasClientV2` directly, which is exactly the code
  that D-002 moves into `atlas-adapter`.
- `dev-support/` — the local Atlas dev stack (vendors Atlas's own
  `atlas-docker` tooling at a pinned ref). This is the topology every
  contributor actually needs; see
  [deployment.md §2](deployment.md#2-local-development-topology).
- Root `pom.xml` — no Spring Boot BOM yet, hand-pinned Jackson, and a module
  list covering only `typedefs` and `ingest-worker`.

Nothing else in the module tables exists yet. First implementation steps, in
dependency order: extract `atlas-adapter` out of the spike (with the exclusion
set and enforcer rules in place, since retrofitting them is harder than starting
with them), then `common`, `messaging`, `app-ingest`, `app-worker`, then
`atlas-rest` + `query-api` + `app-query`.

## Open questions

These are the items we need to discuss on `dev@sourcelume.apache.org` — each one
either adds a module, adds infrastructure, or changes a public contract, so none
of them should be settled by whoever writes the code first. Each is stated as an
actual question, with the options as we see them and what the POC minimally
needs.

### 1. Authentication and authorization

Entirely unaddressed today. Signature verification proves *authorship of a
record*; it says nothing about whether this caller is allowed to submit one, or
to run a bulk export.

- Who may POST a provenance record — mTLS, API key, OIDC bearer token?
- Is the query API public-read, or authenticated?
- Who may submit an export job, and who may fetch the resulting artifact?

*POC minimum:* a pluggable authentication filter on `app-ingest` with one
working mechanism, and an explicit decision (written down) on whether query is
public-read. Without that decision the query API's cacheability and rate-limit
design can't be settled either.

### 2. Spring Batch needs a `JobRepository` database

The map-based `JobRepository` was removed in Spring Batch 5, so Batch requires a
`DataSource`. The design currently has no database anywhere — not in the module
tables, not in the deployment stack, not in the config list.

- Add Postgres purely as the export job store?
- Or avoid Batch for the POC and use a simpler job model (e.g. Kafka-triggered
  tasks + status records) until bulk export is genuinely needed?

*POC minimum:* nothing — `app-export` is out of POC scope. But this needs
deciding before anyone starts on export, because the answer adds a service to
every deployment.

### 3. Where do export artifacts live, and who serves them?

"Downloadable archives" implies storage, a download endpoint, and a retention
policy, none of which are specified.

- Object store (S3-compatible), or a shared volume?
- Does `app-query` serve the download, or does `app-export` expose one endpoint?
  (Serving it from `app-query` puts audit-sized byte streams back through the
  latency-sensitive JVM — which cuts against D-003.)
- TTL and cleanup, and does an artifact inherit the authorization of the job that
  produced it?

### 4. Ingest correctness semantics

The async ingest design (P2) is only as good as its delivery guarantees, and
none are currently stated.

- Idempotency/dedup key — record id? Content hash of the signed payload?
- Kafka partition key, and what ordering that buys us. Related: the scaling
  guidance in deployment.md says "scale workers up to the partition count"
  without our having chosen a partition count.
- At-least-once with idempotent writes, or something stronger?
- DLT topic naming, who drains it, and the poison-message policy.
- Replay procedure after a bad deploy.

*POC minimum:* a stated dedup key and at-least-once + idempotent-write
semantics, even if the DLT is just "a topic nobody drains yet".

### 5. Ingest backpressure and request limits

The fault-domain table in deployment.md claims overloaded clients "receive HTTP
429/503", but nothing in the design produces a 429.

- Rate limit per authenticated principal — and where, in `app-ingest` or at the
  reverse proxy?
- Maximum request body size, and maximum batch size if batch submission is
  allowed at all.
- What `app-ingest` does when Kafka is unavailable: fail fast with 503, or
  buffer?

### 6. What is actually cacheable, and how does a cache get purged?

P3 justifies caching via immutability, but that assumption is narrower than the
cache currently assumes:

- typedef `patches/` can change how an entity is interpreted;
- Atlas-side mutations (classifications, relationships, soft deletes) can change
  what a query returns even when no record changed;
- search/list results are not immutable even if each record is.

*Needs:* an explicit split between cache-forever-by-record-id and short-TTL
(search, traversals, aggregates), plus a purge path for after a typedef patch
or an Atlas-side change.

### 7. GraphQL budgets and pagination limits

P5 commits to guardrails but names no numbers.

- Max query depth, and max complexity score?
- Page size default and hard maximum for interactive search?
- What happens on exceeding them — reject, or truncate with a warning?

*POC minimum:* pick conservative numbers and write them here; they are easier to
argue about once they exist.

### 8. Target Atlas version

`atlas.version` is `2.5.0` in `pom.xml` and is still a placeholder, not a
decision. D-002 shrinks this question's surface to one module but does not
answer it.

- Which Atlas versions do we commit to supporting for the POC?
- Does the read path via `atlas-rest` target the same version's REST API, and do
  we test both paths against the same Atlas container?

### 9. `sourcelume-spec` Maven coordinates and resource layout

Currently assumed (`org.apache.sourcelume:sourcelume-spec:0.0.1-SNAPSHOT`,
bundling `context/v0.0.1/sourcelume.jsonld` on the classpath) but to be
confirmed against the published artifact. Blocks `common`, which maps its DTOs
from the spec.

### 10. Kafka: shared with Atlas, or dedicated?

Atlas runs its own Kafka for notifications (confirmed — the `dev-support` stack
builds `atlas-kafka` on both backends).

- Does the Registry share that cluster, or require its own?
- Sharing is cheaper to stand up and tempting for the POC; it also couples our
  ingest availability to Atlas's internal messaging and makes topic/ACL
  ownership murky.

*POC minimum:* one dedicated single-broker Kafka in the dev stack, so the
question stays open rather than being answered by accident.

### 11. Orchestration commitment

deployment.md documents Compose, Swarm and Kubernetes. Each one we claim to
support is one we have to keep working.

- Is Swarm a *supported* target or an example? Its long-term upstream
  stewardship is a risk worth stating out loud, and it has no autoscaler — the
  "scaling trigger" columns are manual there.
- Do we ship Helm charts, or leave Kubernetes to downstream operators?

*Proposed framing for the list:* Compose for development (supported),
Kubernetes for large deployments (manifests as examples), Swarm documented as a
viable small-deployment option but not gated in CI.

### 12. Are the resource profiles anywhere near right?

The heap/CPU numbers in
[deployment.md §3](deployment.md#workload-characteristics--resource-profiles)
are engineering guesses with no benchmark behind them. They are labelled as such
in that document — but someone will quote them back as requirements, so we
should agree on how they get validated (a load test against the dev stack, with
what payload shape?) before they harden.

## Related documents

- [deployment.md](deployment.md) — runtime placement, container images, scaling,
  orchestration, observability.
- [`dev-support/README.md`](../dev-support/README.md) — local Atlas dev stack.
- [`README.md`](../README.md) — current steel-thread scope and how to run it.
