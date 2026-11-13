# Apache Sourcelume Simple Demo
**Community Over Code Glasgow 2026 Hackathon**

This demo provides a comprehensive hands-on walkthrough of **Apache Sourcelume Spec** and **Apache Sourcelume Registry** working together with **Apache Atlas** to validate, ingest, index, curate, and govern dataset provenance metadata.

---

## What This Demo Demonstrates

### The Problem
AI models and data pipelines often train on large-scale datasets with murky origins, untracked licensing constraints, missing audit trails, and unverifiable custody chains. Without standardized, enforceable provenance:
- Organizations face severe intellectual property and legal compliance liabilities.
- Data curators lack structured feedback mechanisms when receiving partial or flawed metadata.
- Data governance teams cannot guarantee that published provenance records remain tamper-resistant and byte-accurate over time.

### The Sourcelume Solution
1. **Spec (`sourcelume-spec`)**: A standardized JSON-LD / SHACL provenance specification defining data structures for dataset identification, custody chains, actor attestations, and SPDX licensing history.
2. **Registry (`sourcelume-registry`)**: A cloud-native Quarkus metadata registry service that validates records against spec schemas, automatically bootstraps domain type definitions into **Apache Atlas**, enforces byte-level SHA-256 auditing, manages curatable ingestion lifecycles, and guarantees provenance immutability.

### The Story You Present
Within minutes, an operator, data curator, or CI pipeline can:
1. **Verify Readiness**: Confirm registry health and live connectivity to Apache Atlas.
2. **Observe Zero-Config Type Bootstrapping**: Inspect automatic synchronization of custom entity types (`sourcelume_dataset`, `sourcelume_model`) and status enums (`sourcelume_record_status`) into Apache Atlas.
3. **Execute Pre-Flight Validation**: Test multi-stage in-memory validation (JSON Schema draft 2020-12 followed by Apache Jena SHACL) with fast structured error reporting before touching storage.
4. **Experience the Curatable Ingestion Lifecycle**: Ingest an incomplete draft record, inspect recorded validation issues, and resubmit the corrected record with automatic in-place promotion from `INCOMPLETE` to `VALIDATED`.
5. **Verify Immutability & Tamper Protection**: Observe how attempts to overwrite a published/validated record are rejected with `409 Conflict`.
6. **Query & Explore Lineage**: Query metadata through multiple interfaces (canonical spec IRI, dataset URI, and Atlas GUID) and inspect the entity graph in the Apache Atlas Web UI.

---

## Directory Contents

| File | Description |
|---|---|
| `run-demo.sh` | Automated end-to-end demo script with ASCII diagrams, formatted console output, and step-by-step verification. |
| `sample-incomplete-record.jsonld` | Sample JSON-LD provenance card missing required license metadata (triggers validation rejection & `INCOMPLETE` state). |
| `sample-provenance-record.jsonld` | Fully conforming Spec-compliant JSON-LD provenance card with custody chain, actor attestations, and SPDX licensing. |
| `sample-dataset.json` | Sample dataset payload mapped to `SourcelumeDatasetDto` for direct dataset endpoint operations. |
| `README.md` | This guided walkthrough and reference documentation. |

---

## Prerequisites

Before running the demo, ensure you have:

- **Docker Engine** (≥ 20.10) & **Docker Compose** (≥ 1.28)
- **Java 21** (LTS) & **Maven 3.9+**
- `curl` and `jq` CLI utilities installed

---

## Quickstart (Automated Execution)

### 1. Start the Environment

Ensure Apache Atlas and the Sourcelume Registry runtime are running:

```bash
# Terminal 1: Start Apache Atlas backend (from project root)
cd dev-support
./setup.sh
# Ensure Atlas container is running (see dev-support/README.md for build steps)
docker compose -f docker-compose.atlas.yml up -d

# Terminal 2: Start Quarkus Registry runtime (from project root)
mvn -pl sourcelume-registry-distribution-atlas quarkus:dev
```

### 2. Run the Demo Script

From the `examples/simple-demo` directory:

```bash
cd examples/simple-demo
./run-demo.sh
```

---

## What `run-demo.sh` Executes (Step-by-Step Breakdown)

The automated script walks through 6 verification stages:

```text
                                  Apache Sourcelume Demo Workflow
  +-----------------------------------------------------------------------------------------------+
  |                                                                                               |
  |  [Step 1] Health Probe -------------> [Step 2] Bootstrap Typedefs                             |
  |     • GET /q/health/ready                • Auto-sync dataset & model types + enum             |
  |                                                                                               |
  |  [Step 3] Pre-Flight Check ---------> [Step 4] Ingest & Curation Lifecycle                    |
  |     • POST /records/validate             • POST draft (INCOMPLETE) -> Query -> POST fix       |
  |     • Fail-fast JSON Schema + SHACL      • Byte-level SHA-256 + State promotion (VALIDATED)   |
  |                                                                                               |
  |  [Step 5] Immutability Gate --------> [Step 6] Unified Query & Atlas Graph                    |
  |     • Re-POST VALIDATED record           • Dual query: /records/{id} vs /api/v1/datasets      |
  |     • 409 Conflict Protection            • Atlas Web UI Inspection (http://localhost:21000)   |
  |                                                                                               |
  +-----------------------------------------------------------------------------------------------+
```

---

### Step 1: System Readiness & Health Probe (`/q/health/ready`)
- **What it does**: Issues a SmallRye Health check against the Quarkus registry runtime.
- **What you are showing**: Sourcelume actively monitors its connection to the Apache Atlas backend. If Atlas is reachable, the registry reports `UP` and is ready for traffic.

```text
  +----------------------+             +----------------------------------+             +-------------------------+
  |    Client / CI       |             |   Sourcelume Registry (Quarkus)  |             |      Apache Atlas       |
  +----------------------+             +----------------------------------+             +-------------------------+
             |                                          |                                            |
             |  1. GET /q/health/ready                  |                                            |
             | ---------------------------------------> |                                            |
             |                                          |  2. Probe Atlas connectivity / status      |
             |                                          | -----------------------------------------> |
             |                                          |                                            |
             |                                          |  3. Connection OK (HTTP 200 / UP)          |
             |                                          | <----------------------------------------- |
             |  4. Health check JSON (status: UP)       |                                            |
             | <--------------------------------------- |                                            |
             |                                          |                                            |
```

```bash
curl -s http://localhost:8082/q/health/ready | jq .
```

---

### Step 2: Automatic Atlas Type Bootstrapping (`/api/v1/types/typedefs`)
- **What it does**: Queries the registry to inspect active entity and enum type definitions in Apache Atlas.
- **What you are showing**: Zero manual Atlas configuration required. On startup, Sourcelume reads `models/sourcelume/sourcelume_model.json`, performs conflict-aware partitioned synchronization, and registers the `sourcelume_dataset` and `sourcelume_model` entity types and the `sourcelume_record_status` enum type into Atlas.

```text
  +--------------------------------+       +----------------------------------+       +-------------------------+
  |   Sourcelume Spec Models       |       |   Sourcelume Registry (Quarkus)  |       |      Apache Atlas       |
  +--------------------------------+       +----------------------------------+       +-------------------------+
                  |                                         |                                      |
                  |  1. Load models/sourcelume/             |                                      |
                  |     sourcelume_model.json               |                                      |
                  | --------------------------------------> |                                      |
                  |                                         |  2. Check existing / conflict sync   |
                  |                                         |  3. Register entity & enum typedefs  |
                  |                                         | -----------------------------------> |
                  |                                         |     - sourcelume_dataset (entity)    |
                  |                                         |     - sourcelume_model (entity)      |
                  |                                         |     - sourcelume_record_status (enum)|
                  |                                         |                                      |
```

```bash
# Inspect all registered typedefs
curl -s http://localhost:8082/api/v1/types/typedefs | jq .

# Inspect the sourcelume_dataset entity type definition
curl -s http://localhost:8082/api/v1/types/dataset | jq .
```

---

### Step 3: Pre-Flight Specification Validation (`POST /records/validate`)
- **What it does**: Runs a two-stage in-memory validation chain over submitted JSON-LD documents without making any database writes or touching Apache Atlas:
  1. **Stage 1 (JSON Schema)**: Validates structural syntax against Draft 2020-12 schema using NetworkNT with date-time and URI format assertions.
  2. **Stage 2 (SHACL Graph)**: Validates semantic graph shapes against W3C SHACL definitions using Apache Jena and Titanium JSON-LD.
- **What you are showing**:
  - Non-conforming records immediately return `422 Unprocessable Entity` with exact violation paths and error descriptions.
  - Conforming records return `200 OK` (`{"conforms": true, "issues": []}`).

```text
       +-----------------------------------------------------------------------------------+
       |                       Sourcelume In-Memory Validator Chain                        |
       +-----------------------------------------------------------------------------------+
                                                 |
  +----------------+    Raw JSON-LD    +-------------------+    Clean Syntax   +-------------------+
  |  Client / CI   | ----------------> | Stage 1:          | ----------------> | Stage 2:          |
  |  Pre-Flight    |                   | JSON Schema       |                   | SHACL Graph       |
  +----------------+                   | (Draft 2020-12)   |                   | (Apache Jena)     |
          ^                            +-------------------+                   +-------------------+
          |                                      | Fail (422)                            | Pass (200) / Fail (422)
          +--------------------------------------+---------------------------------------+
                              ValidationResult (conforms + structured issues)
```

```bash
# 3a. Pre-flight check on non-conforming record (missing license) -> 422 Unprocessable Entity
curl -s -X POST http://localhost:8082/records/validate \
  -H "Content-Type: application/json" \
  -d @sample-incomplete-record.jsonld | jq .

# 3b. Pre-flight check on conforming record -> 200 OK
curl -s -X POST http://localhost:8082/records/validate \
  -H "Content-Type: application/json" \
  -d @sample-provenance-record.jsonld | jq .
```

---

### Step 4: Synchronous Ingestion & Curation Lifecycle (`POST /records`)
- **What it does**: Executes the full synchronous ingest pipeline and demonstrates the real-world data curation lifecycle:
  1. **Byte-Accurate Digesting**: Calculates a raw `sha256` hash over the request body before decoding, providing tamper-evident provenance tracking.
  2. **Incomplete Record Persistence**: Submitting a draft record with missing metadata persists it with status `INCOMPLETE` (`201 Created`), capturing its exact validation issues so curators can inspect what needs resolution.
  3. **In-Place State Promotion**: Submitting the corrected record under the same canonical identifier (`id`) re-evaluates the validator chain, updates the entity in Apache Atlas, clears validation issues, promotes `recordStatus` to `VALIDATED`, and returns `200 OK`.

```text
  +-------------------------------------------------------------------------------------------------+
  |                             Curatable Ingestion State Machine                                   |
  +-------------------------------------------------------------------------------------------------+
                                                  |
  +---------------------+   POST Incomplete   +---------------------+   Inspect Issues   +---------+
  | 1. Draft Submission | ------------------> | Persisted Entity    | -----------------> | Review  |
  |    (Missing License)|                     | Status: INCOMPLETE  |  GET /records/id   | Issues  |
  +---------------------+                     | HTTP 201 Created    |                    +---------+
                                              +---------------------+                         |
                                                         |                                    |
  +---------------------+   POST Corrected Record        | Fix Missing Metadata               |
  | 2. Resubmission     | -------------------------------+------------------------------------+
  |    (Valid Spec)     |                                v
  +---------------------+                     +---------------------+
                                              | Updated Entity      |
                                              | Status: VALIDATED   |
                                              | HTTP 200 OK         |
                                              +---------------------+
```

```bash
# 4a. Ingest initial draft record -> 201 Created (Status: INCOMPLETE)
curl -s -X POST http://localhost:8082/records \
  -H "Content-Type: application/json" \
  -d @sample-incomplete-record.jsonld | jq .

# 4b. Inspect stored record status and issues
RECORD_ID="https://sourcelume.apache.org/records/rec-glasgow-hackathon-001"
ENCODED_ID=$(jq -rn --arg x "${RECORD_ID}" '$x|@uri')
curl -s "http://localhost:8082/records/${ENCODED_ID}" | jq .

# 4c. Resubmit corrected record under same ID -> 200 OK (Status: VALIDATED)
curl -s -X POST http://localhost:8082/records \
  -H "Content-Type: application/json" \
  -d @sample-provenance-record.jsonld | jq .
```

---

### Step 5: Immutability & Tamper Protection (`409 Conflict`)
- **What it does**: Enforces strict governance invariants on published provenance data.
- **What you are showing**: Once a record achieves `VALIDATED` status, subsequent attempts to POST modifications to that identifier are strictly rejected with `409 Conflict`. Published datasets cannot have their licensing history, origin, or custody chain silently mutated.

```text
  +----------------------+             +----------------------------------+             +-------------------------+
  |  Mutate / Overwrite  |             |   Sourcelume Ingest Pipeline     |             |      Apache Atlas       |
  +----------------------+             +----------------------------------+             +-------------------------+
             |                                          |                                            |
             |  1. POST /records (Existing ID)          |                                            |
             | ---------------------------------------> |                                            |
             |                                          |  2. Check existing recordStatus            |
             |                                          |     Found: VALIDATED (Published)           |
             |                                          |                                            |
             |  3. HTTP 409 Conflict                    |  [Rejected: Modification Forbidden]        |
             | <--------------------------------------- |                                            |
             |     {"recordStatus": "VALIDATED"}        |                                            |
```

```bash
# Attempting to re-ingest or mutate an already VALIDATED record returns 409 Conflict
curl -s -i -X POST http://localhost:8082/records \
  -H "Content-Type: application/json" \
  -d @sample-provenance-record.jsonld
```

---

### Step 6: Unified Query Resolution & Apache Atlas Lineage Exploration
- **What it does**: Demonstrates multi-path entity resolution and backend integration.
- **What you are showing**: Consumers, downstream training scripts, and compliance auditors can query the entity using:
  1. **Canonical Spec Record IRI** (`GET /records/{encodedId}`)
  2. **Dataset Domain URI** (`GET /api/v1/datasets?qualifiedName=...`)
  3. **Internal Atlas Graph GUID** (`GET /api/v1/datasets/guid/{guid}`)

```text
                               +--------------------------------------------+
                               |     Unified Metadata Resolution Paths      |
                               +--------------------------------------------+
                                                     |
             +---------------------------------------+---------------------------------------+
             |                                       |                                       |
             v                                       v                                       v
  [1. Canonical Record IRI]              [2. Dataset URI Query]                 [3. Atlas Graph GUID]
   GET /records/{encodedId}               GET /api/v1/datasets?qualifiedName=    GET /api/v1/datasets/guid/{guid}
             |                                       |                                       |
             +---------------------------------------+---------------------------------------+
                                                     |
                                                     v
                                     +-------------------------------+
                                     | Apache Atlas Governed Entity  |
                                     | - Type: sourcelume_dataset    |
                                     | - Status: VALIDATED           |
                                     | - SHA-256 Digest & Audit Log  |
                                     +-------------------------------+
```

```bash
# 6a. Query via Canonical Record Endpoint
curl -s "http://localhost:8082/records/${ENCODED_ID}" | jq .

# 6b. Query via Dataset Qualified Name
curl -s "http://localhost:8082/api/v1/datasets?qualifiedName=${ENCODED_ID}" | jq .

# 6c. Query via Atlas Graph GUID (using guid returned in 6b)
# curl -s "http://localhost:8082/api/v1/datasets/guid/<ASSIGNED_GUID>" | jq .
```

---

## Exploring in the Apache Atlas Web UI

To conclude the demo with visual verification:

1. Open your browser to [http://localhost:21000](http://localhost:21000).
2. Log in with credentials:
   - **Username**: `admin`
   - **Password**: `atlasR0cks!`
3. In the search box, select **Type**: `sourcelume_dataset`.
4. Click **Search** to view the `Glasgow Hackathon Instruction Corpus v1` entity.
5. Inspect the entity attributes:
   - `name`: `Glasgow Hackathon Instruction Corpus v1`
   - `recordStatus`: `VALIDATED`
   - `licenseId`: `https://spdx.org/licenses/Apache-2.0.html`
   - `sourceUri`: `sourcelume://datasets/glasgow-hackathon-corpus-v1`
   - `sha256`: Cryptographic digest of submitted JSON-LD payload
   - `validatedBy`: `json-schema,shacl`
   - `validatedAt`: ISO-8601 UTC timestamp
   - **Audit History**: Complete timeline showing entity creation, status updates, and property changes.

---

## Companion Artifact: Spec Provenance Record

The `sample-provenance-record.jsonld` file illustrates the complete **Sourcelume Spec (v0.0.1)** linked data model:

- **Custody Chain (`custodyChain`)**: Tracking who created, transformed, or reviewed the dataset (`agent`), what operation was performed (`action`), and when (`startTime`).
- **License Governance (`licenseHistory`)**: Tracking SPDX license attribution (`Apache-2.0`), scope, and compatibility verification.
- **JSON-LD Interoperability**: Fully interoperable with standard W3C linked data graphs.

```json
{
  "@context": "https://sourcelume.apache.org/context/0.0.1/sourcelume.jsonld",
  "id": "https://sourcelume.apache.org/records/rec-glasgow-hackathon-001",
  "type": "ProvenanceRecord",
  "identifier": "sourcelume://datasets/glasgow-hackathon-corpus-v1",
  "name": "Glasgow Hackathon Instruction Corpus v1",
  "version": "1.0.0",
  "license": "https://spdx.org/licenses/Apache-2.0.html",
  "licenseScope": "dataset",
  "licenseCategory": "open-source",
  "licenseNote": "SPDX compliant Apache-2.0 dataset metadata verification",
  "licenseHistory": [
    {
      "iri": "https://spdx.org/licenses/Apache-2.0.html",
      "effectiveDate": "2026-10-11T09:00:00Z"
    }
  ],
  "creator": [
    {
      "type": "schema:Organization",
      "name": "Community Over Code Glasgow 2026 Hackathon Team",
      "role": "originator"
    }
  ],
  "created": "2026-10-11T09:00:00Z",
  "added": "2026-10-11T10:00:00Z",
  "contentCreated": "2026-10-11T08:30:00Z",
  "origin": "Curated multi-turn instruction data assembled during Community Over Code Glasgow 2026 Hackathon.",
  "custodyChain": [
    {
      "agent": "https://sourcelume.apache.org/agents/glasgow-curator-01",
      "action": "dataset_ingestion_and_validation",
      "startTime": "2026-10-11T10:00:00Z"
    }
  ]
}
```

---

## Teardown

To shut down local services after completing the demo:

```bash
# Stop Quarkus (Ctrl+C in terminal)

# Stop Atlas backend
cd dev-support
docker compose -f docker-compose.atlas.yml down
```

## License

This project is licensed under [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0).
