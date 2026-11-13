<!--
    Licensed to the Apache Software Foundation (ASF) under one or more
    contributor license agreements.  See the NOTICE file distributed with
    this work for additional information regarding copyright ownership.
    The ASF licenses this file to You under the Apache License, Version 2.0
    (the "License"); you may not use this file except in compliance with
    the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
-->

# Sourcelume Dev Support: Apache Atlas & Quarkus Runtime

This directory provides local developer infrastructure to stand up an Apache Atlas
backend and develop/test the Sourcelume Registry Quarkus runtime
(`sourcelume-registry-distribution-atlas`) against it.

---

### Overview

The local development environment consists of:

1. **Apache Atlas backend**: an Apache Atlas 2.5.0 container stack with the
   PostgreSQL backend, listening on port `21000`.
2. **Sourcelume Registry runtime**: the Quarkus application that connects to
   Atlas, registers the Sourcelume typedefs on startup, and exposes SmallRye
   Health readiness/liveness probes at `/q/health/{ready,live}` on port `8082`.
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

### Running on Windows

The dev tooling is POSIX-shell based, but the full workflow runs on Windows
in three ways:

#### WSL2 (recommended)

Install Docker Desktop with the WSL2 backend and a WSL2 distro
(`wsl --install`), then clone and run everything inside WSL2 exactly as the
bash instructions below describe. Docker Desktop exposes the same engine to
WSL2, so `docker compose` and the `sourcelume-network` bridge are shared
with Windows. Two caveats:

- Clone the repo inside the WSL2 filesystem (`~/...`), not under `/mnt/c` —
  bind-mounts and Maven disk access are much faster on the ext4 filesystem.
- `~/.m2` inside WSL2 is a different directory than your Windows Maven
  cache; the in-container Atlas build warms its own copy there.

#### Git Bash

Git for Windows ships a POSIX shell; `setup.sh`, `download-archives.sh`,
and `docker compose` all work from it unchanged — Docker Desktop's CLI and
engine are already on the `PATH`. Use `export VAR=value` exactly as written
in the bash snippets.

#### Native PowerShell

Use `setup.ps1` instead of `setup.sh`:

```powershell
.\dev-support\setup.ps1
```

Then note these equivalents:

- `export VAR=value` becomes `$env:VAR = "value"` — e.g.
  `$env:DOCKER_BUILDKIT = "1"`, `$env:COMPOSE_DOCKER_CLI_BUILD = "1"`,
  `$env:ATLAS_BACKEND = "postgres"`, and
  `$env:SOURCELUME_ATLAS_URL = "http://localhost:21000"`.
- `mkdir -p "${HOME}/.m2"` becomes `New-Item -ItemType Directory -Force ~\.m2`.
- `download-archives.sh` still needs a POSIX shell — run it from Git Bash
  or WSL2 (`bash download-archives.sh`), or fetch the five archives listed
  in the vendored `.env` into `downloads/` yourself. Only the Kafka archive
  is needed for the postgres backend.
- `curl` in Windows PowerShell 5.1 aliases to `Invoke-WebRequest`; call
  `curl.exe` explicitly (ships with Windows 10+) or use
  `Invoke-RestMethod` for the same REST calls. `jq` is available via
  `winget install jqlang.jq`.
- The Atlas server container is always named `atlas` (compose
  `container_name`), so attaching it to the shared network is simply:

  ```powershell
  docker network connect sourcelume-network atlas
  ```

Everything else — the `docker compose` files, `mvn` commands, and port
numbers — is platform-independent.

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

Allow containerized Sourcelume services to reach Atlas by hostname (`atlas`).
The Atlas server container is always named `atlas` (compose
`container_name`):

```bash
docker network connect sourcelume-network atlas || true
```

---

### Atlas images: options & trade-offs

There is no official prebuilt Atlas image — `apache/atlas` on Docker Hub
publishes no tags, and the 2.5.0 release ships a source tarball only (no
binary distribution). Building Atlas from source
(`docker-compose.atlas-build.yml`, ~15–45 min the first time, faster with a
warm `~/.m2`) is therefore currently unavoidable if you want a real 2.5.0.
The vendored stack then assembles these images:

| Image | Base | Why it exists |
|---|---|---|
| `atlas` | `atlas-base` (Ubuntu + JDK) | The Atlas server, from the source-built dist tarball |
| `atlas-db` | `postgres:13` | Postgres backend + init script |
| `atlas-solr` | `solr:8` | Solr + Atlas configsets |
| `atlas-kafka` | `atlas-base` + Kafka 2.8.2 | Broker with the Atlas Kafka hook preinstalled |
| `atlas-zk` | `zookeeper:3.9.2` | Stock image, re-tagged |

The dependency images are thin wrappers around stock images; the expensive
parts are the Atlas source build itself and `download-archives.sh` (which
fetches ~GBs of Hadoop/HBase/Hive/Kafka archives — only the Kafka archive
is needed for the postgres backend).

Alternatives, in order of increasing trade-off:

- **Build once, share the image.** `docker save atlas:latest | gzip >
  atlas-2.5.0.tar.gz` lets teammates `docker load` a real 2.5.0 instead of
  rebuilding.
- **Single-container embedded Atlas.** The distro's `atlas_start.py` can
  run embedded HBase+Solr+Kafka in one container (the old quickstart
  pattern). Fewer moving parts and still 2.5.0, but embedded-HBase
  semantics differ from the postgres stack we verify against.
- **`sburn/apache-atlas:2.3.0`.** The de facto community image — single
  container, fast start — but Atlas 2.3.0 (not 2.5.0), amd64-only, and
  unmaintained. Fine for a throwaway REST smoke test; not the supported
  configuration.

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
mvn -pl sourcelume-registry-distribution-atlas quarkus:dev
```

Or run/debug `org.apache.sourcelume.registry.runtime.quarkus.SourcelumeRegistryApplication`
from your IDE using the Atlas distribution module's classpath.

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
# Create a test dataset entity directly in Atlas
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

# Fetch the created entity by qualified name (the bulk endpoint only
# resolves guids; the lookup goes through the unique-attribute endpoint)
curl -s -u admin:atlasR0cks! \
  "http://localhost:21000/api/atlas/v2/entity/uniqueAttribute/type/sourcelume_dataset?attr:qualifiedName=sourcelume://datasets/sample-1" | jq .
```

#### 4. Testing dataset operations directly in Sourcelume Registry

Sourcelume exposes high-level REST endpoints on port `8082`. Records are written via `POST /records`; `/api/v1/datasets` offers read-only views:

```bash
# Publish a dataset record (the only way to write one)
curl -s -X POST http://localhost:8082/records \
  -H "Content-Type: application/ld+json" \
  --data-binary @examples/simple-demo/sample-provenance-record.jsonld | jq .

# Query by qualifiedName (the record's id; query parameter)
curl -s "http://localhost:8082/api/v1/datasets?qualifiedName=https://sourcelume.apache.org/records/rec-glasgow-hackathon-001" | jq .

# Fetch dataset by GUID
curl -s "http://localhost:8082/api/v1/datasets/guid/<ASSIGNED_GUID>" | jq .

# Inspect Sourcelume type definitions
curl -s http://localhost:8082/api/v1/types/typedefs | jq .
curl -s http://localhost:8082/api/v1/types/dataset | jq .
```

#### 5. Automated tests

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

---

### Running the integration tests (`-Pintegration-tests`)

`mvn verify -Pintegration-tests` boots the vendored Atlas stack through
Testcontainers and runs the end-to-end pipeline tests (`*IT`, see
`sourcelume-registry-distribution-atlas/src/.../it/`). Prerequisites: the
vendored tooling and the locally built `atlas:latest` image (the steps
above), plus a reachable Docker daemon.

If your Docker daemon does not listen on the default socket path
(`/var/run/docker.sock` — e.g. Rancher Desktop, colima), point the client
at it via `DOCKER_HOST`. If the cleanup sidecar then cannot mount that
socket, disable it with `TESTCONTAINERS_RYUK_DISABLED=true` (the compose
stack is stopped explicitly by the test resource anyway):

```bash
export DOCKER_HOST=unix://<path-to-your-docker-socket>
export TESTCONTAINERS_RYUK_DISABLED=true
mvn verify -Pintegration-tests -pl sourcelume-registry-distribution-atlas
```

The API version pin for the docker client lives in
`sourcelume-registry-distribution-atlas/src/test/resources/docker-java.properties`.
If compose output is needed while debugging, add
`-Dquarkus.log.console.level=INFO`.

### Testing against an existing Atlas instance

To run the integration tests against an isolated Atlas instance that is already
running, supply its URL. This bypasses the Compose lifecycle manager:

```bash
mvn verify -Pintegration-tests -Dsourcelume.it.atlas-url=http://localhost:21000
```

`SOURCELUME_IT_ATLAS_USER` and `SOURCELUME_IT_ATLAS_PASSWORD` override the local
Atlas development defaults. The suite registers/updates Sourcelume type
definitions and writes uniquely named test records; use a disposable instance.
The ordinary HTTP tests explicitly select their mock profile, so the Atlas
integration profile always uses the real storage provider.
