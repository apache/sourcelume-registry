# Sourcelume dev stack

Spins up a local Apache Atlas to develop and test the Registry against, on
your machine. This does not implement any Sourcelume functionality itself -
it's purely the "stand up something to point `WiringSpike` / `ingest-worker`
at" piece.

## Why this isn't one self-contained docker-compose file

Apache Atlas's own dev tooling (`apache/atlas/dev-support/atlas-docker/`) is
a maintained, multi-file build-and-run setup: it builds Atlas from source in
a container, lets you choose a backend (Postgres or HBase), and downloads the
archives it needs. Reproducing that by hand here would mean maintaining a
second, inevitably-drifting copy of Atlas's own build process. Instead, this
folder **vendors Atlas's own tooling at a pinned version** and wraps a thin
Sourcelume-specific layer around it (the shared network, and later, any
Sourcelume-specific dev services like a local queue or cache).

## What's verified vs. what's a placeholder

Verified directly from the actual `dev-support/atlas-docker/README.md` in the
`release-2.5.0` checkout (not assumed — confirmed against the real file after
an earlier version of this doc guessed wrong about the postgres setup):

- The setup requires Docker Engine ≥ 20.10.5 / Compose ≥ 1.28.5, and at least
  6 GB of memory configured for Docker (trivial on a 128 GB machine).
- It supports **two backends**, both via the same `docker-compose.atlas.yml`,
  switched with the `ATLAS_BACKEND` env var: `postgres` (lighter, no extra
  compose file needed) or `hbase` (layers `docker-compose.atlas-hadoop.yml`
  on top — the full Hadoop-ecosystem stack: HBase, Solr, Kafka).
  There is no separate `atlas-postgres.yml` file, despite an earlier version
  of this doc assuming one existed.
- The build step (`docker-compose.atlas-build.yml`) builds Atlas from source
  inside a container and can take **up to an hour** on first run, depending
  on your `~/.m2` cache.
- Default admin login once running: `admin` / `atlasR0cks!`, at
  `http://localhost:21000`.
- **CORRECTION, previously wrong in this doc: `postgres` does NOT skip
  Kafka, Solr, or ZooKeeper.** Confirmed by actually running
  `docker compose -f docker-compose.atlas.yml up` with `ATLAS_BACKEND=postgres`:
  it builds `atlas`, `atlas-kafka`, `atlas-zk`, `atlas-db`, *and* `atlas-solr`
  — five services, unconditionally. `ATLAS_BACKEND` only switches the graph
  storage layer (HBase vs. Postgres); Kafka (notifications), Solr (search
  index), and ZooKeeper are core Atlas infrastructure used by *both* backends.
  The only thing actually specific to `hbase` is HBase itself, via the
  `docker-compose.atlas-hadoop.yml` overlay.
  This means **`download-archives.sh` is NOT skippable on the `postgres`
  path either** — `Dockerfile.atlas-kafka` needs `downloads/kafka_2.12-*.tgz`
  regardless of backend, and the build fails without it
  (`failed to calculate checksum ... "/downloads/kafka_2.12-2.8.2.tgz": not found`).
  Run the full script every time, on either backend:
```
  chmod +x download-archives.sh && ./download-archives.sh
```
- **`docker-compose.atlas-base.yml` only builds a generic Ubuntu + Java base
  image** (`Dockerfile.atlas-base`) — confirmed by reading the file directly.
  This part of the earlier claim still holds: the base build stage itself has
  no Hadoop-ecosystem dependency. It's `docker-compose.atlas.yml` (step 3d,
  not the base build) where all five services get built together.
- **The vendored `.env` needs two edits before building, or the build fails.**
  `docker-compose.atlas-build.yml` mounts `./../..` (two directories up from
  `atlas-docker/`) into the container as `/home/atlas/src`, and
  `BUILD_HOST_SRC=true` (the vendored default) expects a full Atlas source
  checkout to already be sitting there. Because `setup.sh` only vendors the
  `dev-support/atlas-docker` subfolder — not the whole `apache/atlas` repo —
  that mount resolves to an empty/wrong directory, and the build fails with
  `BUILD_HOST_SRC=true, but /home/atlas/src/pom.xml is not found`.
  Confirmed by reading `scripts/atlas-build.sh` directly: setting
  `BUILD_HOST_SRC=false` takes a different code path entirely — the build
  container `git clone`s the source itself, using the `BRANCH` and `GIT_URL`
  env vars, so no host-side source checkout is needed at all. Set in the
  vendored `.env` (`dev-support/vendor/atlas-docker/.env`, **not** this
  folder's `.env`):
```
  BUILD_HOST_SRC=false
  BRANCH=release-2.5.0
```
`BRANCH` defaults to `master` in the vendored `.env` — pin it to
`release-2.5.0` so the build matches the tag `setup.sh` vendored
`dev-support/atlas-docker/` from, rather than silently building whatever's
currently on `master`.
- **The vendored `.env` also defaults `ATLAS_BACKEND=hbase`.** Change this
  to `postgres` in the same file, matching the recommendation below.
- **arm64 confirmed working, at least for the base build stage.** The
  `atlas-base` build's Java setup step
  (`update-java-alternatives --set /usr/lib/jvm/java-1.8.0-openjdk-arm64`)
  completed successfully with no emulation involved — real evidence, not
  inference, that at least this part of the stack runs natively on Apple
  Silicon.

**Not verified — flagged explicitly rather than guessed at:**

- Whether the actual Atlas Maven build (`docker-compose.atlas-build.yml`,
  once `BUILD_HOST_SRC=false` lets it run) and the `postgres` runtime image
  (`Dockerfile.atlas`, `Dockerfile.atlas-db`) also run natively on arm64, or
  fall back to Rosetta emulation. The base image's Java setup succeeding is a
  good sign but doesn't confirm the rest of the pipeline.
- Whether `PROFILE=dist,external-hbase-solr` in the vendored `.env` (a Maven
  build profile, distinct from the `ATLAS_BACKEND` runtime switch) has any
  effect on the `postgres` path. Left as-is rather than guessed at — if the
  build succeeds with it unchanged, that's the answer.

## Setup

```bash
# 1. Vendor Atlas's own dev tooling at a pinned ref
./setup.sh

# 2. Create the shared network Sourcelume services will eventually join
docker network create sourcelume-network

# 3. Follow Atlas's own instructions from here, using the vendored copy:
cd vendor/atlas-docker
export DOCKER_BUILDKIT=1 COMPOSE_DOCKER_CLI_BUILD=1

# Required on BOTH backends - Kafka/Solr/ZooKeeper are built regardless of
# ATLAS_BACKEND, and this fetches what they need (see "What's verified" above -
# an earlier version of this doc wrongly said postgres could skip this):
chmod +x download-archives.sh && ./download-archives.sh

# 3a. Edit the vendored .env (NOT this folder's .env) before building - see
# "What's verified" above for why these two edits are required, not optional:
#   BUILD_HOST_SRC=false
#   BRANCH=release-2.5.0
#   ATLAS_BACKEND=postgres

# 3b. Build the atlas-base image
docker compose -f docker-compose.atlas-base.yml build

# 3c. Build Atlas itself from source (can take up to ~1hr first run)
mkdir -p "${HOME}/.m2"
docker compose -f docker-compose.atlas-build.yml up

# If you already ran this once before editing .env, the container was
# created with the old (wrong) env values baked in - tear it down first so
# the edits actually take effect:
#   docker compose -f docker-compose.atlas-build.yml down
#   docker compose -f docker-compose.atlas-build.yml up

# 3d. Start Atlas with the Postgres backend
export ATLAS_BACKEND=postgres
docker compose -f docker-compose.atlas.yml up -d --wait
```

If you ever want the `hbase` backend instead (adds HBase itself on top of the
Kafka/Solr/ZooKeeper/db services already built above — not recommended for
local dev on Apple Silicon since only the base build stage's arm64 support is
confirmed so far): set `ATLAS_BACKEND=hbase` in the vendored `.env` instead of
`postgres`, and run:

```bash
docker compose -f docker-compose.atlas.yml -f docker-compose.atlas-hadoop.yml up -d --wait
```

Once it's running:

```bash
curl -u admin:atlasR0cks! http://127.0.0.1:21000/api/atlas/admin/version
```

### Joining the shared network

The vendored compose files define their own network(s) by default. To attach
the running Atlas container to `sourcelume-network` (so future containerized
Sourcelume services can reach it by container name instead of `localhost`):

```bash
docker ps   # find the actual Atlas container name - not yet confirmed, check here
docker network connect sourcelume-network <atlas-container-name>
```

For now, this step is optional: `WiringSpike` runs on the host via
`mvn exec:java` and reaches Atlas through the port mapped to `localhost:21000`,
so it doesn't need the shared network at all yet. The network exists so this
doesn't need to be re-architected once `ingest-worker` (or `query-api`, once
built) is itself containerized.

## Running the spike against it

From the repo root, once Atlas is up:

```bash
mvn install
mvn -pl sourcelume-registry-ingest-worker exec:java \
  -Dexec.mainClass=org.apache.sourcelume.registry.ingest.worker.WiringSpike \
  -Dexec.args="http://127.0.0.1:21000"
```

To Clean up from prior run:
```bash
curl -u admin:atlasR0cks! -X DELETE http://127.0.0.1:21000/api/atlas/v2/types/typedefs \
  -H "Content-Type: application/json" \
  -d @sourcelume-registry-typedefs/src/main/resources/models/sourcelume/sourcelume_model.json
```

Retrieve specific Entity def:
```bash
curl -s -u admin:atlasR0cks! \
  http://localhost:21000/api/atlas/v2/types/typedefs | jq .
```

Retrieve all type defs:
```bash
curl -s -u admin:atlasR0cks! \
  http://localhost:21000/api/atlas/v2/types/typedefs | jq .
```

Create instance directly:
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

You should get something like:
```text
{"mutatedEntities":{"CREATE":[{"typeName":"sourcelume_dataset","attributes":{"owner":"","qualifiedName":"sourcelume://datasets/sample-1","name":"sample-dataset-1","description":"First test Sourcelume dataset instance"},"guid":"840025b7-d5ec-43da-9a32-4bdd9db03f4a","status":"ACTIVE","displayText":"sample-dataset-1","classificationNames":[],"classifications":[],"meaningNames":[],"meanings":[],"isIncomplete":false,"labels":[]}]},"guidAssignments":{"-776638218186579":"840025b7-d5ec-43da-9a32-4bdd9db03f4a"}}% 
```
