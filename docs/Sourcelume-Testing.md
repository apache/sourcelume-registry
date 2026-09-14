# Sourcelume Build and Testing Guide

This guide outlines the end-to-end workflow to build the project, stand up the local Apache Atlas backend, run the Sourcelume Spring Boot runtime, and verify integrations.

---

## Prerequisites

- **Java 17+** (e.g., Eclipse Temurin 17, OpenJDK 17)
- **Maven 3.9+**
- **Docker Engine** (≥ 20.10.5) and **Docker Compose** (≥ 1.28.5) configured with at least 6 GB RAM
- `curl` and `jq` for testing HTTP endpoints and formatting JSON

---

## Phase 1: Build Java Modules & Run Automated Tests

Before running containers or local services, build the Maven reactor to compile all modules, execute unit and Spring context tests, and generate the executable Spring Boot application jar.

```bash
# Set environment variables (fill in paths for your local machine)
export JAVA_HOME=<path-to-your-jdk-17>
export PATH=<path-to-your-maven-bin>:$JAVA_HOME/bin:$PATH

# Verify tool versions
java -version
mvn -version

# Compile all modules, run tests, and package executable jar
mvn clean package
```

### What This Step Does
- Compiles `sourcelume-registry-typedefs`, `sourcelume-registry-common`, `sourcelume-registry-atlas-adapter`, and `sourcelume-registry-ingest-worker`.
- Runs all unit and integration tests (spec resource loader, DTO validation, Atlas adapter mock tests, Spring Boot context bootstrapping).
- Packages `../sourcelume-registry-ingest-worker/target/sourcelume-registry-ingest-worker-0.0.1-SNAPSHOT.jar`.

---

## Phase 2: Stand Up Apache Atlas Backend (`dev-support`)

Stand up a local Apache Atlas 2.5.0 instance using the vendored PostgreSQL-backed Docker Compose configuration.

### 1. Initialize Network & Tooling
From the repository root:
```bash
./dev-support/setup.sh
```
- Creates the shared Docker bridge network `sourcelume-network`.
- Vendors `apache/atlas` release `2.5.0` Docker tooling into `../dev-support/vendor/atlas-docker`.

### 2. Download Dependency Archives & Build Atlas Containers (One-time)
```bash
cd dev-support/vendor/atlas-docker

# Make script executable and fetch third-party archives
chmod +x download-archives.sh && ./download-archives.sh

# Build Atlas base container and compile Atlas from source
export DOCKER_BUILDKIT=1 COMPOSE_DOCKER_CLI_BUILD=1
docker compose -f docker-compose.atlas-base.yml build
mkdir -p "${HOME}/.m2"
docker compose -f docker-compose.atlas-build.yml up
```

### 3. Start Atlas Services
```bash
export ATLAS_BACKEND=postgres
docker compose -f docker-compose.atlas.yml up -d --wait
```

### 4. Attach Atlas Container to `sourcelume-network`
```bash
ATLAS_CONTAINER=$(docker ps --filter "name=atlas" --filter "ancestor=apache/atlas" --format "{{.Names}}" | head -n 1)
[ -z "$ATLAS_CONTAINER" ] && ATLAS_CONTAINER=$(docker ps --filter "name=atlas" --format "{{.Names}}" | grep -v "zk\|solr\|db\|kafka" | head -n 1)
docker network connect sourcelume-network "${ATLAS_CONTAINER}" || true
```

### 5. Verify Atlas is Up
```bash
curl -s -u admin:atlasR0cks! http://localhost:21000/api/atlas/admin/version
```

---

## Phase 3: Run the Sourcelume Spring Boot Runtime

You can run the Sourcelume runtime either via **Docker Compose** or directly on your **Host / IDE**.

### Option A: Run via Docker Compose (Recommended for Full Stack Validation)
```bash
# Return to repository root
cd ../../..  # or cd /path/to/sourcelume-registry

# Build worker image and start container
docker compose build
docker compose up -d

# Inspect worker logs to confirm typedef registration and startup
docker compose logs -f worker
```

### Option B: Run on Host / Local IDE (Recommended for Rapid Development)
```bash
# From repository root
export SOURCELUME_ATLAS_URL=http://localhost:21000
export SOURCELUME_ATLAS_USER=admin
export SOURCELUME_ATLAS_PASSWORD=atlasR0cks!

# Run directly via Spring Boot Maven plugin
mvn -pl sourcelume-registry-ingest-worker spring-boot:run
```

---

## Phase 4: Test & Verify Runtime Integration

### 1. Check Spring Boot Actuator Health & Probes
Inspect the management port (`9001`):

- **Overall & Atlas Health**:
  ```bash
  curl -s http://localhost:9001/actuator/health | jq .
  ```
  *(Confirms `status: "UP"` and `atlas.status: "UP"`)*

- **Kubernetes Liveness and Readiness Probes**:
  ```bash
  curl -s http://localhost:9001/actuator/health/liveness
  curl -s http://localhost:9001/actuator/health/readiness
  ```

- **Prometheus Metrics**:
  ```bash
  curl -s http://localhost:9001/actuator/prometheus
  ```

### 2. Verify Typedef Registration in Atlas
Confirm that the `AtlasBootstrapRunner` successfully registered the Sourcelume type models on startup:

```bash
curl -s -u admin:atlasR0cks! http://localhost:21000/api/atlas/v2/types/entitydef/name/sourcelume_dataset | jq .
```

### 3. Test Entity Creation in Atlas
Verify end-to-end dataset creation against the registered Sourcelume types:

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

---

## Teardown & Cleanup

- **Stop Sourcelume Worker**:
  ```bash
  docker compose down
  ```
- **Stop Atlas Backend**:
  ```bash
  cd dev-support/vendor/atlas-docker
  docker compose -f docker-compose.atlas.yml down
  ```
