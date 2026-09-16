# Sourcelume Registry — Quarkus Spike

This is a deliberately minimal slice through the Registry architecture, parallel to
Jamie's Spring Boot `registry-spike` branch. It does not implement any Sourcelume
functionality (no signature verification, no schema validation, no queue, no full
REST/GraphQL API). Its only job is to prove three pieces of wiring connect using
**Quarkus, CDI, and a thin JDK HttpClient** instead of Spring Boot and
`atlas-client-v2`:

1. A locally-built `sourcelume-spec` jar can be depended on and its resources read.
2. A backend-neutral `AtlasAdapter` (with a thin REST client implementation) can
   reach a running Apache Atlas instance and register typedefs.
3. A Quarkus application starts, bootstraps the typedefs on startup, and exposes a
   readiness health check.

## Why this branch exists

The dev-list thread (Sept 2026) raised two questions this spike answers:

- **Java baseline.** Spring Boot 3.3.5 (Jamie's spike) only supports Java up to 23;
  the Mockito/ByteBuddy version in its BOM fails on Java 26. Java 17 loses Oracle
  support on 30.09.2026. Proposed baseline: Java 21 (LTS), CI on 25, skip 26 (non-LTS).
- **Backend dependency.** Calvin proposed dropping `atlas-client-v2` and its whole
  transitive tree in favour of a thin REST client over the JDK HttpClient + Jackson,
  since the registry only talks to 4 Atlas endpoints. This spike implements that.
- **Framework.** Calvin: "I don't feel strongly about Spring vs Quarkus... Quarkus
  is closer to what JBO wants :). It might be worth doing a Quarkus version of the
  spike and comparing the two." This is that Quarkus version.

## Modules

- `sourcelume-registry-typedefs` — one minimal entity type, `sourcelume_dataset`
  (unchanged from the Spring spike; vendor-neutral).
- `sourcelume-registry-common` — DTOs mapping spec 0.0.1, Jakarta Validation, the
  `SpecResourceLoader` (unchanged; vendor-neutral, only Jakarta API, no Spring/Quarkus
  runtime dependency).
- `sourcelume-registry-atlas-adapter` — **backend-neutral `AtlasAdapter` interface
  using Sourcelume types**, implemented by `RestAtlasAdapter` (thin JDK HttpClient +
  Jackson). Pulls in NO `atlas-client-v2` / `atlas-intg`. The Atlas REST wire shape
  lives entirely in the implementation; the interface exposes only Sourcelume DTOs.
- `sourcelume-registry-ingest-worker` — Quarkus app: starts, bootstraps typedefs on
  startup (`AtlasBootstrapRunner` observing CDI `StartupEvent`), exposes readiness
  via SmallRye Health (`AtlasHealthIndicator`).

## Differences from the Spring spike (registry-spike branch)

| Concern | Spring spike | Quarkus spike |
|---|---|---|
| Framework | Spring Boot 3.3.5 | Quarkus 3.33 LTS |
| DI | Spring `@Component`/`@Bean` | CDI `@ApplicationScoped` + `@Inject` |
| Config | `@ConfigurationProperties` (application.yml) | MicroProfile `@ConfigMapping` (application.properties) |
| Health | Spring Actuator `HealthIndicator` | SmallRye Health `@Readiness` HealthCheck |
| Startup | Spring `ApplicationRunner` | CDI `@Observes StartupEvent` |
| Atlas client | `atlas-client-v2` + `atlas-intg` (full SDK tree) | JDK `HttpClient` + Jackson (thin, ~250 LoC) |
| Adapter interface | imports `AtlasTypesDef`, `AtlasClientV2` | backend-neutral, Sourcelume types only |
| Java baseline | 17 (EOL this month) | 21 (LTS) |
| Mockito | inline-mock, self-attach (breaking on future JDKs) | CDI `@QuarkusComponentTest` / no ByteBuddy on Atlas |

## Building

### Prerequisites

- Java 21 (LTS) — `jenv local 21` in this directory (or `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`)
- Maven 3.9+
- The `sourcelume-spec` jar installed locally: from the `sourcelume-spec` repo, `mvn install`

### Build

```bash
# from the sourcelume-spec repo (on main), so it lands in your local Maven repo
mvn install

# from this repo, on the quarkus-spike branch
mvn clean install
```

### Running

#### Option A: standalone (the steel thread)

```bash
mvn -pl sourcelume-registry-ingest-worker exec:java \
  -Dexec.mainClass=org.apache.sourcelume.registry.ingest.worker.WiringSpike \
  -Dexec.args="http://localhost:21000"
```

#### Option B: Quarkus app (dev mode)

```bash
mvn -pl sourcelume-registry-ingest-worker quarkus:dev
```

Health check: `curl http://localhost:8082/q/health/ready`

#### Option C: Docker

See `dev-support/README.md` for standing up Atlas. Then:

```bash
docker compose up -d --wait
curl http://localhost:8082/q/health/ready
```

## What "success" looks like

The worker starts, logs:

```
1. spec context read: N bytes
2. typedefs loaded: OK
3. atlas reachable: true
4. atlas accepted typedefs, entity types: 1
Sourcelume Registry bootstrap completed successfully.
```

and `/q/health/ready` returns `UP` with `atlas: CONNECTED`.

If it gets that far, the wiring described in the Registry's high-level architecture is
real on the Quarkus + thin-client stack, not just a diagram.

## Open questions for the dev list

This spike does not decide the framework question. It exists so the list can
**compare** the two implementations side by side and settle Java baseline +
framework + backend port before merging either spike into `main` (per Calvin's
proposed sequencing).
