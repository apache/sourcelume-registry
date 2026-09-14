# Sourcelume Dev Support: Apache Atlas & Spring Boot Stack

This directory provides local developer infrastructure to stand up an Apache Atlas backend and develop/test the Sourcelume Spring Boot Registry against it.

---

### Overview

The local development environment consists of:
1. **Apache Atlas Backend**: An official Apache Atlas 2.5.0 container stack running with the PostgreSQL backend on port `21000`.
2. **Sourcelume Spring Boot Services**: Spring Boot applications (such as `sourcelume-registry-ingest-worker`) that connect to Atlas, register typedefs on startup, and expose Actuator health/metrics endpoints on management port `9001`.
3. **Shared Network**: A Docker bridge network named `sourcelume-network` connecting the Atlas containers and Sourcelume services.

---

### Prerequisites

- **Docker Engine** ≥ 20.10.5 and **Docker Compose** ≥ 1.28.5 (configured with at least 6 GB RAM).
- **Java 17** (e.g. Eclipse Temurin 17).
- **Maven 3.9+**.
- `curl` and `jq` (recommended for verifying JSON responses).

---

### Quick Start: Standing up the Atlas Backend

#### 1. Initialize Dev Support
Run `setup.sh` to initialize the shared Docker network and vendor Atlas's build tooling:
```bash
./dev-support/setup.sh
```

#### 2. Download Atlas Dependency Archives
Atlas Kafka/Solr/ZooKeeper components require third-party archives before building:
```bash
cd dev-support/vendor/atlas-docker
chmod +x download-archives.sh && ./download-archives.sh
```

#### 3. Build Atlas Containers
```bash
export DOCKER_BUILDKIT=1 COMPOSE_DOCKER_CLI_BUILD=1

# 3a. Build the atlas-base image
docker compose -f docker-compose.atlas-base.yml build

# 3b. Build Atlas from source (takes ~15-45 mins on first run depending on ~/.m2 cache)
mkdir -p "${HOME}/.m2"
docker compose -f docker-compose.atlas-build.yml up
```

#### 4. Start Atlas with PostgreSQL Backend
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

### Running & Testing the Spring Boot Runtime

You can run and test the Sourcelume Spring Boot runtime using either **Docker Compose** or directly on your **Host/IDE**.

#### Option A: Run via Docker Compose (Recommended for Container Validation)

```bash
# From project root
# 1. Build project artifacts
mvn clean package

# 2. Build and start the worker container
docker compose build
docker compose up -d

# 3. View logs
docker compose logs -f worker
```

#### Option B: Run on Host / IDE (Recommended for Rapid Development & Debugging)

```bash
# From project root
export SOURCELUME_ATLAS_URL=http://localhost:21000
export SOURCELUME_ATLAS_USER=admin
export SOURCELUME_ATLAS_PASSWORD=atlasR0cks!

# Run the Spring Boot application
mvn -pl sourcelume-registry-ingest-worker spring-boot:run
```

---

### Testing & Verification Workflows

#### 1. Spring Boot Actuator Health & Probes

The Spring Boot runtime exposes Actuator endpoints on management port `9001`:

- **Overall Health (including Atlas connectivity)**:
  ```bash
  curl -s http://localhost:9001/actuator/health | jq .
  ```
  Expected output includes `status: "UP"` with the `atlas` health component:
  ```json
  {
    "status": "UP",
    "components": {
      "atlas": {
        "status": "UP",
        "details": {
          "url": "http://atlas:21000",
          "ready": true
        }
      },
      "livenessState": {
        "status": "UP"
      },
      "readinessState": {
        "status": "UP"
      }
    }
  }
  ```

- **Kubernetes Liveness and Readiness Probes**:
  ```bash
  curl -s http://localhost:9001/actuator/health/liveness
  curl -s http://localhost:9001/actuator/health/readiness
  ```

- **Prometheus Metrics**:
  ```bash
  curl -s http://localhost:9001/actuator/prometheus
  ```

#### 2. Typedef Bootstrap Verification

On startup, `sourcelume-registry-ingest-worker` automatically registers `sourcelume-spec` models in Atlas.

- **Check all Registered Type Definitions**:
  ```bash
  curl -s -u admin:atlasR0cks! http://localhost:21000/api/atlas/v2/types/typedefs | jq .
  ```

- **Check `sourcelume_dataset` Entity Definition**:
  ```bash
  curl -s -u admin:atlasR0cks! http://localhost:21000/api/atlas/v2/types/entitydef/name/sourcelume_dataset | jq .
  ```

#### 3. Testing Entity Creation & Querying in Atlas

- **Create a Test Dataset Entity**:
  ```bash
  curl -u admin:atlasR0cks! -X POST http://localhost:21000/api/atlas/v2/entity \
    -H "Content-Type: application/json" \
    -d '{
      "entity": {
        "typeName": "sourcelume_dataset",
        "attributes": {
          "name": "sample-dataset-1",
          "qualifiedName": "sourcelume://datasets/sample-1",
          "description": "First test Sourcelume dataset instance",
          "sourceUri": "https://github.com/example/repo",
          "licenseId": "Apache-2.0"
        }
      }
    }'
  ```

- **Fetch the Created Entity by `qualifiedName`**:
  ```bash
  curl -s -u admin:atlasR0cks! \
    "http://localhost:21000/api/atlas/v2/entity/uniqueAttribute/type/sourcelume_dataset?attr:qualifiedName=sourcelume://datasets/sample-1" | jq .
  ```

#### 4. Automated Tests

Run the full automated test suite (unit tests, validation tests, and Spring Boot context tests):
```bash
mvn clean verify
```

---

### Teardown & Reset

#### Clean up Test Typedefs from Atlas
```bash
curl -u admin:atlasR0cks! -X DELETE http://localhost:21000/api/atlas/v2/types/typedefs \
  -H "Content-Type: application/json" \
  -d @sourcelume-registry-typedefs/src/main/resources/models/sourcelume/sourcelume_model.json
```

#### Stop Sourcelume Containers
```bash
docker compose down
```

#### Stop Apache Atlas Stack
```bash
cd dev-support/vendor/atlas-docker
docker compose -f docker-compose.atlas.yml down
```
