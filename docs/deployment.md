# Apache Sourcelume Registry Deployment & Operations

> Status: discussion anchor for `dev@sourcelume.apache.org`.
>
> Scope of this document: **where each thing runs, in what container, on what
> network, with what configuration.** Module responsibilities and dependency
> rules are owned by [architecture.md](architecture.md) — this document does not
> restate them, it points at them. If the two disagree, architecture.md wins and
> this file is the bug.
>
> Read [architecture.md](architecture.md) first if you haven't: in particular
> [The Atlas access boundary](architecture.md#the-atlas-access-boundary), because
> it is the rule that decides which containers are allowed on a public network.

## 1. Spring Boot application topology & placement

Four deployable Spring Boot applications, each a separate OCI image on its own
JVM. The split is not cosmetic — it follows the Atlas containment rule:

- **`app-ingest` and `app-query` are the only web-facing services, and neither
  contains the Atlas Java client.** `app-query` reads Atlas over plain HTTP via
  `atlas-rest`.
- **`app-worker` and `app-export` carry `atlas-client-v2`** (and its Jersey 1.x /
  Hadoop transitives) and are therefore **private-network only, never
  published**. Containment is a deployment property, not just a POM property.

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                          PUBLIC / INGRESS ZONE  (no Atlas client)                       │
│                                                                                         │
│   Signed record submissions (REST)              Interactive queries / Explorer (GraphQL)│
│                  │                                                  │                   │
│                  ▼                                                  ▼                   │
│   ┌─────────────────────────────┐                  ┌─────────────────────────────┐      │
│   │          app-ingest         │                  │          app-query          │      │
│   │   Boot web · public :8080   │                  │   Boot web · public :8081   │      │
│   │   mgmt :9001 (private)      │                  │   mgmt :9001 (private)      │      │
│   │                             │                  │   owns look-aside cache     │      │
│   └──────────────┬──────────────┘                  └───────┬──────────────┬──────┘      │
└──────────────────┼──────────────────────────────────────────┼──────────────┼────────────┘
                   │ produce                                  │ cache        │ HTTP reads
                   │                                          │ (Caffeine/   │ (atlas-rest,
                   │                                          │  Redis)      │  no Atlas client)
┌──────────────────┼──────────────────────────────────────────┼──────────────┼────────────┐
│                  ▼        PRIVATE / BACKEND ZONE            ▼              │            │
│   ┌─────────────────────────────┐                  ┌─────────────────┐     │            │
│   │        Kafka cluster        │                  │  Redis (opt.)   │     │            │
│   └──────────────┬──────────────┘                  └─────────────────┘     │            │
│                  │ consume                                                 │            │
│                  ▼                                                         ▼            │
│   ┌─────────────────────────────┐   writes         ┌─────────────────────────────┐      │
│   │          app-worker         │   (atlas-client) │       Apache Atlas core     │      │
│   │   headless · no public port │─────────────────▶│ (JanusGraph, HBase/Cass,    │      │
│   │   CARRIES atlas-client-v2   │                  │  Solr) — externally managed │      │
│   └─────────────────────────────┘                  └──────────────┬──────────────┘      │
│                                                                   │ reads               │
│   ┌─────────────────────────────┐                                 │ (atlas-client)      │
│   │          app-export         │◀────────────────────────────────┘                     │
│   │   headless batch (post-POC) │                                                       │
│   │   CARRIES atlas-client-v2   │──▶ artifact store ──▶ auditors  (see open question 3) │
│   └─────────────────────────────┘                                                       │
└─────────────────────────────────────────────────────────────────────────────────────────┘
```

### Deployment matrix

| Application | Tier | Exposure | Atlas access | Scaling |
|---|---|---|---|---|
| `sourcelume-registry-app-ingest` | Ingress / edge | Public `:8080` behind a reverse proxy; mgmt `:9001` private | **none** (enqueues only) | Horizontal, stateless. Driven by inbound request rate. |
| `sourcelume-registry-app-worker` | Worker / backend | **Private only.** No published ports; mgmt `:9001` private | `atlas-adapter` → `atlas-client-v2` (writes) | Horizontal, bounded above by the ingest topic's partition count (count is [open question 4](architecture.md#4-ingest-correctness-semantics)). Driven by consumer group lag. |
| `sourcelume-registry-app-query` | Ingress / API | Public `:8081` behind a reverse proxy; mgmt `:9001` private | `atlas-rest` → Atlas HTTP API (reads) | Horizontal, stateless. Driven by request concurrency. Shares Redis when >1 replica. |
| `sourcelume-registry-app-export` | Batch / backend | **Private only.** Triggered by job submission | `atlas-adapter` → `atlas-client-v2` (reads) | Vertical; one high-memory instance, or an ephemeral job per request. **Post-POC** — also needs a `JobRepository` database ([open question 2](architecture.md#2-spring-batch-needs-a-jobrepository-database)). |

Starters per application are listed in
[architecture.md § Deployable modules](architecture.md#deployable-modules); they
are deliberately not duplicated here.

## 2. Local development topology

This is the only topology every contributor needs, and the one to get right
first. It already exists in the repo: **[`dev-support/`](../dev-support/README.md)**.

- Atlas is **not** hand-written into a compose file here. `dev-support/setup.sh`
  vendors Apache Atlas's own `atlas-docker` tooling at a pinned ref and builds
  Atlas from source (first build can take ~an hour). There is no official
  published `apache/atlas` image to pull, which is exactly why the vendoring
  exists — see `dev-support/README.md` for the full reasoning and the two
  mandatory `.env` edits.
- `dev-support/docker-compose.yml` owns the shared `sourcelume-network` and is
  where a local Kafka and Redis belong once
  [open question 10](architecture.md#10-kafka-shared-with-atlas-or-dedicated)
  is settled. It currently defines no services on purpose.
- Registry services today run on the host via `mvn exec:java` and reach Atlas at
  `localhost:21000`. As `app-ingest` / `app-worker` / `app-query` become real,
  they join `sourcelume-network` and the POC target becomes a single
  `docker compose up` plus a running Atlas.

**Local defaults differ from production, deliberately:** Caffeine instead of
Redis, one Kafka broker, one replica each, no reverse proxy, actuator on the
same host. Do not read §6 as the dev setup.

## 3. Workload isolation across distinct JVMs

Separate JVMs per concern buy three concrete things: independent scaling,
independent GC behaviour, and fault containment. A bulk export that balloons the
heap cannot pause interactive queries if it is not in the same heap.

### Workload characteristics & resource profiles

> **These numbers are engineering guesses, not measurements.** Nothing here has
> been benchmarked. They exist so the reference stack has *some* limits set
> rather than none — treat them as starting points to be validated, and see
> [open question 12](architecture.md#12-are-the-resource-profiles-anywhere-near-right).

| Service | Primary bottleneck | Scaling signal | Starting heap / CPU (unvalidated) | Fault domain |
|---|---|---|---|---|
| `app-ingest` | Network I/O, deserialization | Inbound HTTP request rate | 512 MB–1 GB, 0.5–1 core | Overload degrades submission only; queries unaffected. Producing a 429 rather than just collapsing is [open question 5](architecture.md#5-ingest-backpressure-and-request-limits). |
| `app-worker` | CPU (signature verification, SHACL/JSON-LD validation), Atlas write latency | Kafka consumer group lag | 1–2 GB, 2–4 cores | Stalled workers mean ingest latency and a growing backlog — queries and the API stay up. |
| `app-query` | Memory (cache), Atlas read latency | Request concurrency, cache hit rate | 2–4 GB, 1–2 cores | Isolated from ingest GC. Read-only: never writes to Atlas. |
| `app-export` | Heap (result buffering), disk/network I/O | Export job queue depth | 2–8 GB, 1–2 cores | Runs out-of-band; cannot affect interactive latency. This is the entire justification for it being a separate runner ([D-003](architecture.md#d-003-app-export-is-its-own-runner)). |

## 4. Containerization strategy & 12-factor configuration

### Image build: buildpacks or Dockerfile

Both work, but the choice has a consequence people get bitten by:

- **`spring-boot:build-image` (Paketo buildpacks)** — zero Dockerfile, layered
  images, sensible container-aware JVM defaults. **But the resulting images have
  no `curl`/`wget`**, so a Compose/Swarm `healthcheck` of the
  `["CMD", "curl", ...]` form can never pass. With buildpacks, drop
  container-level healthchecks and probe over HTTP from the orchestrator.
- **Multi-stage Dockerfile** — one extra file per deployable, but you control the
  base image and can install a probe tool.

The reference stack in §6 uses Dockerfile-built images so that its healthchecks
are real. Which one the project ships is worth a decision on the list.

### Image naming

Use a valid registry-qualified name. `org.apache.sourcelume/...` is **not** a
valid image name — Docker treats a first path component containing dots as a
registry hostname and tries to resolve `org.apache.sourcelume` via DNS. Use
`apache/sourcelume-registry-app-ingest:<version>` or
`ghcr.io/apache/sourcelume-registry-app-ingest:<version>`.

Pin tags. `:latest` in a reference manifest guarantees that someone,
eventually, reproduces a different system than the one documented.

### Configuration (12-factor)

Everything environment-specific is injected; nothing environment-specific is
baked into an image.

| Concern | Variables |
|---|---|
| Atlas | `SOURCELUME_ATLAS_URL`, `SOURCELUME_ATLAS_USER`, `SOURCELUME_ATLAS_PASSWORD_FILE` |
| Kafka | `SPRING_KAFKA_BOOTSTRAP_SERVERS`, `SOURCELUME_INGEST_TOPIC`, `SPRING_KAFKA_CONSUMER_GROUP_ID` |
| Cache | `SPRING_CACHE_TYPE` (`caffeine` \| `redis`), `SPRING_DATA_REDIS_HOST`, `SPRING_DATA_REDIS_PORT` |
| Management | `MANAGEMENT_SERVER_PORT=9001`, `MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED=true`, `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,info,prometheus` |
| JVM | `JAVA_TOOL_OPTIONS=-XX:+UseG1GC -XX:MaxRAMPercentage=75.0` |

**Credentials are never plain environment values.** Use orchestrator secrets
mounted as files (`/run/secrets/...`) and a `*_FILE` variable, or at minimum
host-side substitution (`${SOURCELUME_ATLAS_PASSWORD}`) — never a literal
`admin` in a manifest that people copy.

## 5. Orchestration & scaling topologies

| Target | Status (proposed — [open question 11](architecture.md#11-orchestration-commitment)) | Notes |
|---|---|---|
| Docker Compose | Supported; the development topology (§2) | What contributors actually run. |
| Docker Swarm | Documented option for small single- or few-node deployments | Built into the Docker engine: overlay networking, rolling updates, declarative replicas, near-zero setup. |
| Kubernetes | Example manifests; left to downstream operators | Same images, no application changes. HPA on Prometheus metrics (consumer lag, request rate). |

Two things to be honest about with Swarm:

1. **Swarm has no autoscaler.** The "scaling signal" column in §3 is *advice for
   a human or external tooling*, not something Swarm acts on. Scaling is
   imperative:

   ```bash
   docker service scale sourcelume_app-worker=8   # heavy ingest batch
   docker service scale sourcelume_app-query=4    # read traffic spike
   ```

2. **Stateful services need placement constraints on multi-node Swarm.** Named
   volumes are node-local: a rescheduled task lands on another node with an
   empty volume. Either pin stateful services to one node (as §6 does) or treat
   them as externally managed.

## 6. Reference stack (`docker-stack.yml`)

Deployable with `docker stack deploy -c docker-stack.yml sourcelume`, or
`docker compose -f docker-stack.yml up` for the non-Swarm subset.

Deliberate choices, because earlier drafts of this manifest could not actually
start:

- **Atlas is not a service here.** There is no official published Atlas image to
  pull; Atlas is stood up separately (locally via [`dev-support/`](../dev-support/README.md),
  in production as managed infrastructure) and reached over
  `SOURCELUME_ATLAS_URL`. Atlas is never published to the host from this stack —
  that would contradict §1's zone model.
- **Kafka is configured for KRaft.** A bare `apache/kafka` image with no node id,
  roles, listeners or cluster id does not start.
- **No published ports on `app-worker` / `app-export`**, and no published
  management port on anything.
- **Passwords come from Docker secrets**, not from literal env values.

```yaml
# Requires: a reachable Apache Atlas (see ../dev-support/README.md) and an
# `atlas-password` secret:  printf 'atlasR0cks!' | docker secret create atlas-password -
#
# Image tags below are placeholders for a released version - pin them, do not
# use :latest.

networks:
  sourcelume-net:
    driver: overlay
    attachable: true

volumes:
  kafka-data:

secrets:
  atlas-password:
    external: true

x-jvm: &jvm
  JAVA_TOOL_OPTIONS: "-XX:+UseG1GC -XX:MaxRAMPercentage=75.0"

x-actuator: &actuator
  MANAGEMENT_SERVER_PORT: "9001"
  MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED: "true"
  MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE: "health,info,prometheus"

services:
  # ---------------------------------------------------------------
  # PUBLIC ZONE. No Atlas client on these classpaths (enforced by
  # maven-enforcer - see architecture.md "The Atlas access boundary").
  # ---------------------------------------------------------------
  app-ingest:
    image: apache/sourcelume-registry-app-ingest:0.0.1
    environment:
      <<: [*jvm, *actuator]
      SERVER_PORT: "8080"
      SPRING_KAFKA_BOOTSTRAP_SERVERS: "kafka:9092"
      SOURCELUME_INGEST_TOPIC: "sourcelume.ingest.v1"
    ports:
      - "8080:8080"        # mgmt 9001 intentionally NOT published
    networks: [sourcelume-net]
    deploy:
      replicas: 2
      resources:
        limits:   { cpus: "1.0", memory: 1024M }
        reservations: { cpus: "0.25", memory: 512M }
      restart_policy: { condition: on-failure }
    healthcheck:
      # Works only because we build with a Dockerfile that includes curl.
      # With buildpack-built images, delete this block (see section 4).
      test: ["CMD", "curl", "-fsS", "http://localhost:9001/actuator/health/liveness"]
      interval: 10s
      timeout: 5s
      retries: 3
      start_period: 30s

  app-query:
    image: apache/sourcelume-registry-app-query:0.0.1
    environment:
      <<: [*jvm, *actuator]
      SERVER_PORT: "8081"
      SOURCELUME_ATLAS_URL: "http://atlas:21000"
      SOURCELUME_ATLAS_USER: "admin"
      SOURCELUME_ATLAS_PASSWORD_FILE: "/run/secrets/atlas-password"
      SPRING_CACHE_TYPE: "redis"
      SPRING_DATA_REDIS_HOST: "redis"
      SPRING_DATA_REDIS_PORT: "6379"
    secrets: [atlas-password]
    ports:
      - "8081:8081"
    networks: [sourcelume-net]
    deploy:
      replicas: 2
      resources:
        limits:   { cpus: "2.0", memory: 3072M }
        reservations: { cpus: "0.5", memory: 1536M }
      restart_policy: { condition: on-failure }
    healthcheck:
      test: ["CMD", "curl", "-fsS", "http://localhost:9001/actuator/health/liveness"]
      interval: 10s
      timeout: 5s
      retries: 3
      start_period: 30s

  # ---------------------------------------------------------------
  # PRIVATE ZONE. These carry atlas-client-v2 and its Jersey 1.x /
  # Hadoop transitives - no published ports, ever.
  # ---------------------------------------------------------------
  app-worker:
    image: apache/sourcelume-registry-app-worker:0.0.1
    environment:
      <<: [*jvm, *actuator]
      SPRING_KAFKA_BOOTSTRAP_SERVERS: "kafka:9092"
      SPRING_KAFKA_CONSUMER_GROUP_ID: "sourcelume-ingest-worker"
      SOURCELUME_INGEST_TOPIC: "sourcelume.ingest.v1"
      SOURCELUME_ATLAS_URL: "http://atlas:21000"
      SOURCELUME_ATLAS_USER: "admin"
      SOURCELUME_ATLAS_PASSWORD_FILE: "/run/secrets/atlas-password"
    secrets: [atlas-password]
    networks: [sourcelume-net]
    deploy:
      # Do not exceed the ingest topic's partition count (open question 4).
      replicas: 4
      resources:
        limits:   { cpus: "2.0", memory: 2048M }
        reservations: { cpus: "0.5", memory: 1024M }
      restart_policy: { condition: on-failure }

  # app-export is POST-POC and is intentionally commented out: Spring Batch
  # needs a JobRepository DataSource and the artifact store is undecided
  # (open questions 2 and 3). Enabling it without those is how you get a
  # service that starts and then fails on first job submission.
  #
  # app-export:
  #   image: apache/sourcelume-registry-app-export:0.0.1
  #   environment:
  #     <<: [*jvm, *actuator]
  #     SOURCELUME_ATLAS_URL: "http://atlas:21000"
  #     SOURCELUME_ATLAS_PASSWORD_FILE: "/run/secrets/atlas-password"
  #     SPRING_DATASOURCE_URL: "jdbc:postgresql://batch-db:5432/sourcelume_batch"
  #   secrets: [atlas-password]
  #   networks: [sourcelume-net]
  #   deploy:
  #     replicas: 1
  #     resources:
  #       limits: { cpus: "2.0", memory: 4096M }
  #     restart_policy: { condition: on-failure }

  # ---------------------------------------------------------------
  # Supporting infrastructure (private zone).
  # ---------------------------------------------------------------
  redis:
    image: redis:7.4-alpine
    networks: [sourcelume-net]
    deploy:
      replicas: 1
      resources:
        limits: { cpus: "1.0", memory: 1024M }
      restart_policy: { condition: on-failure }

  kafka:
    image: apache/kafka:3.9.0
    environment:
      # Single-broker KRaft. Replace with a real cluster in production.
      KAFKA_NODE_ID: "1"
      KAFKA_PROCESS_ROLES: "broker,controller"
      KAFKA_LISTENERS: "PLAINTEXT://:9092,CONTROLLER://:9093"
      KAFKA_ADVERTISED_LISTENERS: "PLAINTEXT://kafka:9092"
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT"
      KAFKA_CONTROLLER_LISTENER_NAMES: "CONTROLLER"
      KAFKA_CONTROLLER_QUORUM_VOTERS: "1@kafka:9093"
      KAFKA_INTER_BROKER_LISTENER_NAME: "PLAINTEXT"
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: "1"
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: "1"
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: "1"
      CLUSTER_ID: "sourcelume-dev-cluster-0001"
    volumes:
      - kafka-data:/var/lib/kafka/data
    networks: [sourcelume-net]
    deploy:
      replicas: 1
      # Node-local volume: pin it, or a reschedule loses the log directory.
      placement:
        constraints: [node.role == manager]
      restart_policy: { condition: on-failure }
```

## 7. Observability and health management

Actuator's probe endpoints and Prometheus endpoint are **not** available by
default — Boot only auto-enables the liveness/readiness groups when it detects
Kubernetes, which means they are missing under Compose and Swarm unless asked
for. Required, per deployable:

- `management.server.port=9001` — a separate connector, so actuator is never
  reachable through the public load balancer.
- `management.endpoint.health.probes.enabled=true` — creates
  `/actuator/health/liveness` and `/actuator/health/readiness`.
- `micrometer-registry-prometheus` on the classpath **and**
  `management.endpoints.web.exposure.include=health,info,prometheus` — otherwise
  `/actuator/prometheus` returns 404.

| Endpoint (on `:9001`) | Use |
|---|---|
| `/actuator/health/liveness` | Is the JVM/app alive? Restart on failure. |
| `/actuator/health/readiness` | Are Kafka / Atlas / cache reachable? Remove from load balancing on failure, do not restart. |
| `/actuator/prometheus` | Consumer lag, request latency percentiles, cache hit ratio, JVM/GC. |

Readiness should reflect the dependencies a service actually needs:
`app-ingest` on Kafka, `app-worker` on Kafka and Atlas, `app-query` on Atlas
(and Redis when configured). A service whose readiness ignores its dependencies
will happily accept traffic it cannot serve.

Prometheus scrape targets are the private management ports; metrics must not be
exposed on the public listener.

## Related documents

- [architecture.md](architecture.md) — modules, dependency rules, decisions, and
  the open questions for the dev list.
- [`dev-support/README.md`](../dev-support/README.md) — local Atlas dev stack.
