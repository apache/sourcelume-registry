# Sourcelume Dev Support: Apache Atlas & Quarkus Runtime

This directory provides local developer infrastructure to stand up an Apache Atlas
backend and develop/test the Sourcelume Registry Quarkus runtime
(`sourcelume-registry-runtime-quarkus`) against it.

---

### Overview

The local development environment consists of:

1. **Apache Atlas backend**: an Apache Atlas 2.5.0 container stack with the
   PostgreSQL backend, listening on port `21000`.
2. **Sourcelume Registry runtime**: the Quarkus application that connects to
   Atlas, registers the Sourcelume typedefs on startup, and exposes SmallRye
   Health readiness/liveness probes at `/q/health/{ready,live}` on port `8082`.
3. **Sourcelume ingest worker**: a headless companion that polls Atlas for
   `PENDING` records (submitted via `POST /records`), validates them with the
   plugin chain, and promotes them to `ACTIVE`/`INCOMPLETE` — see
   `docs/ingest.md`. Runs in the same compose stack (`docker-compose.yml` at
   the repo root), driven by the same Atlas environment variables.
3. **Shared network**: a Docker bridge network named `sourcelume-network`
   connecting the Atlas containers and the Sourcelume services.

---

### Prerequisites

- **Docker Engine** ≥ 20.10.5 and **Docker Compose** ≥ 1.28.5 (configured with
  at least 6 GB RAM).
- **Java 21** (e.g. Eclipse Temurin 21).
- **Maven 3.9+**.
- `curl` and `jq` (recommended for verifying JSON responses).

---

### Quick Start: Standing up the Atlas backend

#### 1. Initialize dev support

Run `setup.sh` to create the shared Docker network and vendor Atlas's build
tooling:

```bash
./dev-support/setup.sh
```

#### 2. Download Atlas dependency archives

Atlas Kafka/Solr/ZooKeeper components require third-party archives before
building:

```bash
cd dev-support/vendor/atlas-docker
chmod +x download-archives.sh && ./download-archives.sh
```

#### 3. Build Atlas containers

```bash
export DOCKER_BUILDKIT=1 COMPOSE_DOCKER_CLI_BUILD=1

# 3a. Build the atlas-base image
docker compose -f docker-compose.atlas-base.yml build

# 3b. Build Atlas from source (takes ~15-45 mins on first run depending on ~/.m2 cache)
mkdir -p "${HOME}/.m2"
docker compose -f docker-compose.atlas-build.yml up
```

#### 4. Start Atlas with the PostgreSQL backend

```bash
export ATLAS_BACKEND=postgres
docker compose -f docker-compose.atlas.yml up -d --wait
```

Verify Atlas is running:

```bash
curl -u admin:atlasR0cks! http://localhost:21000/api/atlas/admin/version
```

#### 5. Connect Atlas to `sourcelume-network`

Allow containerized Sourcelume services to reach Atlas by hostname (`atlas`):

```bash
# Locate the Atlas server container and attach it to the shared network
ATLAS_CONTAINER=$(docker ps --filter "name=atlas" --filter "ancestor=apache/atlas" --format "{{.Names}}" | head -n 1)
[ -z "$ATLAS_CONTAINER" ] && ATLAS_CONTAINER=$(docker ps --filter "name=atlas" --format "{{.Names}}" | grep -v "zk\|solr\|db\|kafka" | head -n 1)
docker network connect sourcelume-network "${ATLAS_CONTAINER}" || true
```

---

### Running & testing the Quarkus runtime

#### Option A: Docker Compose (container validation)

```bash
# From the repo root
# 1. Build project artifacts (also produces the Quarkus fast-jar for the image)
mvn clean package

# 2. Build and start the registry container
docker compose build
docker compose up -d

# 3. View logs
docker compose logs -f registry
```

#### Option B: Host / IDE (rapid development & debugging)

```bash
# From the repo root, with Atlas reachable on localhost:21000
export SOURCELUME_ATLAS_URL=http://localhost:21000
export SOURCELUME_ATLAS_USER=admin
export SOURCELUME_ATLAS_PASSWORD=atlasR0cks!

# Run the Quarkus runtime in dev mode
mvn -pl sourcelume-registry-runtime-quarkus quarkus:dev
```

Or run/debug `org.apache.sourcelume.registry.runtime.quarkus.SourcelumeRegistryApplication`
from your IDE.

---

### Testing & verification workflows

#### 1. SmallRye Health probes

The runtime exposes SmallRye Health probes at `/q/health/{ready,live}` on
port `8082`:

```bash
# Overall health (including the atlas readiness check)
curl -s http://localhost:8082/q/health | jq .

# Liveness and readiness probes (Kubernetes style)
curl -s http://localhost:8082/q/health/live | jq .
curl -s http://localhost:8082/q/health/ready | jq .
```

Expected readiness output when Atlas is reachable:

```json
{
  "status": "UP",
  "checks": [
    {
      "name": "atlas",
      "status": "UP",
      "data": {
        "atlasUrl": "http://atlas:21000",
        "status": "CONNECTED"
      }
    }
  ]
}
```

Prometheus metrics:

```bash
curl -s http://localhost:8082/q/metrics
```

#### 2. Typedef bootstrap verification

On startup the runtime automatically registers the `sourcelume-spec` models in
Atlas (retries via `sourcelume.atlas.max-retries` / `retry-delay-ms`).

```bash
# Check the registered type definitions
curl -s -u admin:atlasR0cks! http://localhost:21000/api/atlas/v2/types/typedefs | jq .

# Check the sourcelume_dataset entity definition
curl -s -u admin:atlasR0cks! http://localhost:21000/api/atlas/v2/types/entitydef/name/sourcelume_dataset | jq .
```

#### 3. Testing entity creation & querying in Atlas

```bash
# Create a test dataset entity
curl -u admin:atlasR0cks! -X POST http://localhost:21000/api/atlas/v2/entity/bulk \
  -H "Content-Type: application/json" \
  -d '{
    "entities": [{
      "typeName": "sourcelume_dataset",
      "attributes": {
        "name": "sample-dataset-1",
        "qualifiedName": "sourcelume://datasets/sample-1",
        "description": "First test Sourcelume dataset instance",
        "sourceUri": "https://github.com/example/repo",
        "licenseId": "Apache-2.0"
      }
    }]
  }'

# Fetch the created entity by qualifiedName
curl -s -u admin:atlasR0cks! \
  "http://localhost:21000/api/atlas/v2/entity/bulk?typeName=sourcelume_dataset&attr:qualifiedName=sourcelume://datasets/sample-1" | jq .
```

#### 4. Automated tests

Run the full automated test suite (framework-free core tests plus the Quarkus
wiring tests):

```bash
mvn clean verify
```

---

### Teardown & reset

#### Clean up test typedefs from Atlas

```bash
curl -u admin:atlasR0cks! -X DELETE http://localhost:21000/api/atlas/v2/types/typedefs \
  -H "Content-Type: application/json" \
  -d @sourcelume-registry-typedefs/src/main/resources/models/sourcelume/sourcelume_model.json
```

#### Stop the Sourcelume container

```bash
docker compose down
```

#### Stop the Apache Atlas stack

```bash
cd dev-support/vendor/atlas-docker
docker compose -f docker-compose.atlas.yml down
```
